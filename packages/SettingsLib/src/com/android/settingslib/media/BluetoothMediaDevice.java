/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.settingslib.media;

import android.bluetooth.BluetoothClass;
import android.content.Context;
import android.util.Log;

import com.android.settingslib.R;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;

/**
 * BluetoothMediaDevice extends MediaDevice to represents Bluetooth device.
 */
public class BluetoothMediaDevice extends MediaDevice {

    private static final String TAG = "BluetoothMediaDevice";

    private CachedBluetoothDevice mCachedDevice;

    BluetoothMediaDevice(Context context, CachedBluetoothDevice device) {
        super(context, MediaDeviceType.TYPE_BLUETOOTH_DEVICE);
        mCachedDevice = device;
        initDeviceRecord();
    }

    @Override
    public String getName() {
        return mCachedDevice.getName();
    }

    @Override
    public int getIcon() {
        //TODO(b/117129183): This is not final icon for bluetooth device, just for demo.
        return R.drawable.ic_bt_headphones_a2dp;
    }

    @Override
    public String getId() {
        return MediaDeviceUtils.getId(mCachedDevice);
    }

    @Override
    public void connect() {
        //TODO(b/117129183): add callback to notify LocalMediaManager connection state.
        mIsConnected = mCachedDevice.setActive();
        super.connect();
        Log.d(TAG, "connect() device : " + getName() + ", is selected : " + mIsConnected);
    }

    @Override
    public void disconnect() {
        //TODO(b/117129183): disconnected last select device
        mIsConnected = false;
    }

    /**
     * Get current CachedBluetoothDevice
     */
    public CachedBluetoothDevice getCachedDevice() {
        return mCachedDevice;
    }

    @Override
    protected boolean isCarKitDevice() {
        final BluetoothClass bluetoothClass = mCachedDevice.getDevice().getBluetoothClass();
        if (bluetoothClass != null) {
            switch (bluetoothClass.getDeviceClass()) {
                // Both are common CarKit class
                case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                    return true;
            }
        }
        return false;
    }
}
