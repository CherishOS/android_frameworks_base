/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static android.provider.DeviceConfig.NAMESPACE_SYSTEMUI;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.SCREENSHOT_CORNER_FLOW;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.util.Log;
import android.view.WindowManager;

import java.util.function.Consumer;

import javax.inject.Inject;

public class TakeScreenshotService extends Service {
    private static final String TAG = "TakeScreenshotService";

    private final GlobalScreenshot mScreenshot;
    private final GlobalScreenshotLegacy mScreenshotLegacy;
    private final UserManager mUserManager;

    private Handler mHandler = new Handler(Looper.myLooper()) {
        @Override
        public void handleMessage(Message msg) {
            final Messenger callback = msg.replyTo;
            Consumer<Uri> finisher = uri -> {
                Message reply = Message.obtain(null, 1, uri);
                try {
                    callback.send(reply);
                } catch (RemoteException e) {
                }
            };

            // If the storage for this user is locked, we have no place to store
            // the screenshot, so skip taking it instead of showing a misleading
            // animation and error notification.
            if (!mUserManager.isUserUnlocked()) {
                Log.w(TAG, "Skipping screenshot because storage is locked!");
                post(() -> finisher.accept(null));
                return;
            }

            // TODO (mkephart): clean up once notifications flow is fully deprecated
            boolean useCornerFlow = DeviceConfig.getBoolean(
                    NAMESPACE_SYSTEMUI, SCREENSHOT_CORNER_FLOW, false);
            switch (msg.what) {
                case WindowManager.TAKE_SCREENSHOT_FULLSCREEN:
                    if (useCornerFlow) {
                        mScreenshot.takeScreenshot(finisher);
                    } else {
                        mScreenshotLegacy.takeScreenshot(finisher, msg.arg1 > 0, msg.arg2 > 0);
                    }
                    break;
                case WindowManager.TAKE_SCREENSHOT_SELECTED_REGION:
                    if (useCornerFlow) {
                        mScreenshot.takeScreenshotPartial(finisher);
                    } else {
                        mScreenshotLegacy.takeScreenshotPartial(
                                finisher, msg.arg1 > 0, msg.arg2 > 0);
                    }
                    break;
                default:
                    Log.d(TAG, "Invalid screenshot option: " + msg.what);
            }
        }
    };

    @Inject
    public TakeScreenshotService(GlobalScreenshot globalScreenshot,
            GlobalScreenshotLegacy globalScreenshotLegacy, UserManager userManager) {
        mScreenshot = globalScreenshot;
        mScreenshotLegacy = globalScreenshotLegacy;
        mUserManager = userManager;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new Messenger(mHandler).getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (mScreenshot != null) mScreenshot.stopScreenshot();
        // TODO (mkephart) remove once notifications flow is fully deprecated
        if (mScreenshotLegacy != null) mScreenshotLegacy.stopScreenshot();
        return true;
    }
}
