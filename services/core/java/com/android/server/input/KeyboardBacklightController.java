/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.input;

import android.annotation.ColorInt;
import android.content.Context;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.hardware.lights.Light;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputDevice;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * A thread-safe component of {@link InputManagerService} responsible for managing the keyboard
 * backlight for supported keyboards.
 */
final class KeyboardBacklightController implements InputManager.InputDeviceListener {

    private static final String TAG = "KbdBacklightController";

    // To enable these logs, run:
    // 'adb shell setprop log.tag.KbdBacklightController DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private enum Direction {
        DIRECTION_UP, DIRECTION_DOWN
    }
    private static final int MSG_INCREMENT_KEYBOARD_BACKLIGHT = 1;
    private static final int MSG_DECREMENT_KEYBOARD_BACKLIGHT = 2;
    private static final int MAX_BRIGHTNESS = 255;
    private static final int NUM_BRIGHTNESS_CHANGE_STEPS = 10;
    @VisibleForTesting
    static final TreeSet<Integer> BRIGHTNESS_LEVELS = new TreeSet<>();

    private final Context mContext;
    private final NativeInputManagerService mNative;
    // The PersistentDataStore should be locked before use.
    @GuardedBy("mDataStore")
    private final PersistentDataStore mDataStore;
    private final Handler mHandler;
    private final SparseArray<Light> mKeyboardBacklights = new SparseArray<>();

    static {
        // Fixed brightness levels to avoid issues when converting back and forth from the
        // device brightness range to [0-255]
        // Levels are: 0, 25, 51, ..., 255
        for (int i = 0; i <= NUM_BRIGHTNESS_CHANGE_STEPS; i++) {
            BRIGHTNESS_LEVELS.add(
                    (int) Math.floor(((float) i * MAX_BRIGHTNESS) / NUM_BRIGHTNESS_CHANGE_STEPS));
        }
    }

    KeyboardBacklightController(Context context, NativeInputManagerService nativeService,
            PersistentDataStore dataStore, Looper looper) {
        mContext = context;
        mNative = nativeService;
        mDataStore = dataStore;
        mHandler = new Handler(looper, this::handleMessage);
    }

    void systemRunning() {
        InputManager inputManager = Objects.requireNonNull(
                mContext.getSystemService(InputManager.class));
        inputManager.registerInputDeviceListener(this, mHandler);
        // Circle through all the already added input devices
        for (int deviceId : inputManager.getInputDeviceIds()) {
            onInputDeviceAdded(deviceId);
        }
    }

    public void incrementKeyboardBacklight(int deviceId) {
        Message msg = Message.obtain(mHandler, MSG_INCREMENT_KEYBOARD_BACKLIGHT, deviceId);
        mHandler.sendMessage(msg);
    }

    public void decrementKeyboardBacklight(int deviceId) {
        Message msg = Message.obtain(mHandler, MSG_DECREMENT_KEYBOARD_BACKLIGHT, deviceId);
        mHandler.sendMessage(msg);
    }

    private void updateKeyboardBacklight(int deviceId, Direction direction) {
        InputDevice inputDevice = getInputDevice(deviceId);
        Light keyboardBacklight = mKeyboardBacklights.get(deviceId);
        if (inputDevice == null || keyboardBacklight == null) {
            return;
        }
        // Follow preset levels of brightness defined in BRIGHTNESS_LEVELS
        int currBrightness = BRIGHTNESS_LEVELS.floor(Color.alpha(
                mNative.getLightColor(deviceId, keyboardBacklight.getId())));
        int newBrightness;
        if (direction == Direction.DIRECTION_UP) {
            newBrightness = currBrightness != MAX_BRIGHTNESS ? BRIGHTNESS_LEVELS.higher(
                    currBrightness) : currBrightness;
        } else {
            newBrightness = currBrightness != 0 ? BRIGHTNESS_LEVELS.lower(currBrightness)
                    : currBrightness;
        }
        @ColorInt int newColor = Color.argb(newBrightness, 0, 0, 0);
        mNative.setLightColor(deviceId, keyboardBacklight.getId(), newColor);
        if (DEBUG) {
            Slog.d(TAG, "Changing brightness from " + currBrightness + " to " + newBrightness);
        }

        synchronized (mDataStore) {
            try {
                mDataStore.setKeyboardBacklightBrightness(inputDevice.getDescriptor(),
                        keyboardBacklight.getId(),
                        newBrightness);
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    private void restoreBacklightBrightness(InputDevice inputDevice, Light keyboardBacklight) {
        OptionalInt brightness;
        synchronized (mDataStore) {
            brightness = mDataStore.getKeyboardBacklightBrightness(
                    inputDevice.getDescriptor(), keyboardBacklight.getId());
        }
        if (!brightness.isEmpty()) {
            mNative.setLightColor(inputDevice.getId(), keyboardBacklight.getId(),
                    Color.argb(brightness.getAsInt(), 0, 0, 0));
            if (DEBUG) {
                Slog.d(TAG, "Restoring brightness level " + brightness.getAsInt());
            }
        }
    }

    private boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_INCREMENT_KEYBOARD_BACKLIGHT:
                updateKeyboardBacklight((int) msg.obj, Direction.DIRECTION_UP);
                return true;
            case MSG_DECREMENT_KEYBOARD_BACKLIGHT:
                updateKeyboardBacklight((int) msg.obj, Direction.DIRECTION_DOWN);
                return true;
        }
        return false;
    }

    @VisibleForTesting
    @Override
    public void onInputDeviceAdded(int deviceId) {
        onInputDeviceChanged(deviceId);
    }

    @VisibleForTesting
    @Override
    public void onInputDeviceRemoved(int deviceId) {
        mKeyboardBacklights.remove(deviceId);
    }

    @VisibleForTesting
    @Override
    public void onInputDeviceChanged(int deviceId) {
        InputDevice inputDevice = getInputDevice(deviceId);
        if (inputDevice == null) {
            return;
        }
        final Light keyboardBacklight = getKeyboardBacklight(inputDevice);
        if (keyboardBacklight == null) {
            mKeyboardBacklights.remove(deviceId);
            return;
        }
        final Light oldBacklight = mKeyboardBacklights.get(deviceId);
        if (oldBacklight != null && oldBacklight.getId() == keyboardBacklight.getId()) {
            return;
        }
        // The keyboard backlight was added or changed.
        mKeyboardBacklights.put(deviceId, keyboardBacklight);
        restoreBacklightBrightness(inputDevice, keyboardBacklight);
    }

    private InputDevice getInputDevice(int deviceId) {
        InputManager inputManager = mContext.getSystemService(InputManager.class);
        return inputManager != null ? inputManager.getInputDevice(deviceId) : null;
    }

    private Light getKeyboardBacklight(InputDevice inputDevice) {
        // Assuming each keyboard can have only single Light node for Keyboard backlight control
        // for simplicity.
        for (Light light : inputDevice.getLightsManager().getLights()) {
            if (light.getType() == Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT
                    && light.hasBrightnessControl()) {
                return light;
            }
        }
        return null;
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + TAG + ": " + mKeyboardBacklights.size() + " keyboard backlights");
        for (int i = 0; i < mKeyboardBacklights.size(); i++) {
            Light light = mKeyboardBacklights.get(i);
            pw.println(prefix + "  " + i + ": { id: " + light.getId() + ", name: " + light.getName()
                    + " }");
        }
    }
}
