/*
 * Copyright (C) 2018 The Android Open Source Project
 * Copyright (C) 2020 Sony Mobile Communications Inc.
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
 *
 * NOTE: This file has been modified by Sony Mobile Communications Inc.
 * Modifications are licensed under the License.
 */

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.server.wallpaper.WallpaperManagerService;


/**
 * Receiver responsible for updating the wallpaper when the device
 * configuration has changed.
 *
 * @hide
 */
public class WallpaperUpdateReceiver extends BroadcastReceiver {

    private static final String TAG = "WallpaperUpdateReceiver";
    private static final boolean DEBUG = false;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (DEBUG) Slog.d(TAG, "onReceive: " + intent);

        if (intent != null && Intent.ACTION_DEVICE_CUSTOMIZATION_READY.equals(intent.getAction())) {
            AsyncTask.execute(this::updateWallpaper);
        }
    }

    private void updateWallpaper() {
        if (DEBUG) Slog.d(TAG, "Set customized default_wallpaper.");
        WallpaperManagerService service =
                (WallpaperManagerService) ServiceManager.getService(Context.WALLPAPER_SERVICE);
        if (service == null) {
            Slog.w(TAG, "WallpaperManagerService is not started");
            return;
        }
        service.requestUpdateImageWallpaper();
    }
}
