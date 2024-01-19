/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static com.android.systemui.Flags.screenshareNotificationHiding;

import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Trace;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.util.ListenerSet;

import javax.inject.Inject;

/** Implementation of SensitiveNotificationProtectionController. **/
@SysUISingleton
public class SensitiveNotificationProtectionControllerImpl
        implements SensitiveNotificationProtectionController {
    private final MediaProjectionManager mMediaProjectionManager;
    private final ListenerSet<Runnable> mListeners = new ListenerSet<>();
    private volatile MediaProjectionInfo mProjection;

    @VisibleForTesting
    final MediaProjectionManager.Callback mMediaProjectionCallback =
            new MediaProjectionManager.Callback() {
                @Override
                public void onStart(MediaProjectionInfo info) {
                    Trace.beginSection(
                            "SNPC.onProjectionStart");
                    mProjection = info;
                    mListeners.forEach(Runnable::run);
                    Trace.endSection();
                }

                @Override
                public void onStop(MediaProjectionInfo info) {
                    Trace.beginSection(
                            "SNPC.onProjectionStop");
                    mProjection = null;
                    mListeners.forEach(Runnable::run);
                    Trace.endSection();
                }
            };

    @Inject
    public SensitiveNotificationProtectionControllerImpl(
            MediaProjectionManager mediaProjectionManager,
            @Main Handler mainHandler) {
        mMediaProjectionManager = mediaProjectionManager;

        if (screenshareNotificationHiding()) {
            mMediaProjectionManager.addCallback(mMediaProjectionCallback, mainHandler);
        }
    }

    @Override
    public void registerSensitiveStateListener(Runnable onSensitiveStateChanged) {
        mListeners.addIfAbsent(onSensitiveStateChanged);
    }

    @Override
    public void unregisterSensitiveStateListener(Runnable onSensitiveStateChanged) {
        mListeners.remove(onSensitiveStateChanged);
    }

    @Override
    public boolean isSensitiveStateActive() {
        // TODO(b/316955558): Add disabled by developer option
        // TODO(b/316955306): Add feature exemption for sysui and bug handlers
        // TODO(b/316955346): Add feature exemption for single app screen sharing
        return mProjection != null;
    }

    @Override
    public boolean shouldProtectNotification(NotificationEntry entry) {
        if (!isSensitiveStateActive()) {
            return false;
        }

        // Exempt foreground service notifications from protection in effort to keep screen share
        // stop actions easily accessible
        // TODO(b/316955208): Exempt FGS notifications only for app that started projection
        return !entry.getSbn().getNotification().isFgsOrUij();
    }
}
