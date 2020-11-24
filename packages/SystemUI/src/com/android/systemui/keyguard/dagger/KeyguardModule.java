/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.keyguard.dagger;

import android.annotation.Nullable;
import android.app.trust.TrustManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.face.FaceManager;
import android.os.Handler;
import android.os.PowerManager;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardDisplayManager;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardViewController;
import com.android.keyguard.dagger.KeyguardStatusViewComponent;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.classifier.FalsingModule;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.keyguard.FaceAuthScreenBrightnessController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.KeyguardLiftController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SystemSettings;

import java.util.Optional;
import java.util.concurrent.Executor;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module providing {@link StatusBar}.
 */
@Module(subcomponents = {KeyguardStatusViewComponent.class},
        includes = {FalsingModule.class})
public class KeyguardModule {
    /**
     * Provides our instance of KeyguardViewMediator which is considered optional.
     */
    @Provides
    @SysUISingleton
    public static KeyguardViewMediator newKeyguardViewMediator(
            Context context,
            FalsingCollector falsingCollector,
            LockPatternUtils lockPatternUtils,
            BroadcastDispatcher broadcastDispatcher,
            Lazy<KeyguardViewController> statusBarKeyguardViewManagerLazy,
            DismissCallbackRegistry dismissCallbackRegistry,
            KeyguardUpdateMonitor updateMonitor,
            DumpManager dumpManager,
            PowerManager powerManager,
            TrustManager trustManager,
            @UiBackground Executor uiBgExecutor,
            DeviceConfigProxy deviceConfig,
            NavigationModeController navigationModeController,
            KeyguardDisplayManager keyguardDisplayManager) {
        return new KeyguardViewMediator(
                context,
                falsingCollector,
                lockPatternUtils,
                broadcastDispatcher,
                statusBarKeyguardViewManagerLazy,
                dismissCallbackRegistry,
                updateMonitor,
                dumpManager,
                uiBgExecutor,
                powerManager,
                trustManager,
                deviceConfig,
                navigationModeController,
                keyguardDisplayManager
        );
    }

    @SysUISingleton
    @Provides
    @Nullable
    static KeyguardLiftController provideKeyguardLiftController(
            Context context,
            StatusBarStateController statusBarStateController,
            AsyncSensorManager asyncSensorManager,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DumpManager dumpManager) {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FACE)) {
            return null;
        }
        return new KeyguardLiftController(statusBarStateController, asyncSensorManager,
                keyguardUpdateMonitor, dumpManager);
    }

    @SysUISingleton
    @Provides
    static Optional<FaceAuthScreenBrightnessController> provideFaceAuthScreenBrightnessController(
            Context context,
            NotificationShadeWindowController notificationShadeWindowController,
            @Main Resources resources,
            Handler handler,
            @Nullable FaceManager faceManager,
            PackageManager packageManager,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            GlobalSettings globalSetting,
            SystemSettings systemSettings,
            DumpManager dumpManager) {
        if (faceManager == null || !packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)) {
            return Optional.empty();
        }

        // Cameras that support "self illumination," via IR for example, don't need low light
        // environment mitigation.
        boolean needsLowLightMitigation = faceManager.getSensorPropertiesInternal().stream()
                .anyMatch((properties) -> !properties.supportsSelfIllumination);
        if (!needsLowLightMitigation) {
            return Optional.empty();
        }

        return Optional.of(new FaceAuthScreenBrightnessController(
                notificationShadeWindowController, keyguardUpdateMonitor, resources,
                globalSetting, systemSettings, handler, dumpManager));
    }
}
