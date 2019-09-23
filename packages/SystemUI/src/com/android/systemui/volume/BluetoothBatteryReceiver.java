/* * Copyright (C) 2019 The Android Open Source Project
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
package com.android.systemui.volume;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.os.Handler;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;

import com.android.systemui.Dependency;

import com.android.systemui.statusbar.policy.BluetoothController;

import java.util.Collection;

/** Toggle button in Volume Dialog that allows extra state for when streams are opted-out */
public class BluetoothBatteryReceiver implements BluetoothController.Callback {

    private BluetoothController mBluetooth;

    private Handler mHandler = new Handler();

    private int mBatteryLevel;

    public BluetoothBatteryReceiver(Context context) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED);
        context.registerReceiver(mIntentReceiver, filter, null, mHandler);
        mBluetooth = Dependency.get(BluetoothController.class);
        updateBluetooth();
        mBluetooth.addCallback(this);
    }

    private final void updateBluetooth() {
        boolean bluetoothVisible = false;
        if (mBluetooth != null) {
            if (mBluetooth.isBluetoothConnected()) {
                final Collection<CachedBluetoothDevice> devices = mBluetooth.getDevices();
                if (devices != null) {
                    // get battery level for the first device with battery level support
                    for (CachedBluetoothDevice device : devices) {
                        // don't get the level if still pairing
                        if (mBluetooth.getBondState(device) == BluetoothDevice.BOND_NONE) continue;
                        int state = device.getMaxConnectionState();
                        if (state == BluetoothProfile.STATE_CONNECTED) {
                            mBatteryLevel = device.getBatteryLevel();
                            break;
                        }
                    }
                }
            bluetoothVisible = mBluetooth.isBluetoothEnabled();
            }
        }
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED:
                    updateBluetooth();
                    break;
            }
        }
    };
    @Override
    public void onBluetoothStateChange(boolean enabled) {
        updateBluetooth();
    }
    @Override
    public void onBluetoothDevicesChanged() {
        updateBluetooth();
    }
    public int getBatteryLevel() {
        return mBatteryLevel;
    }
}
