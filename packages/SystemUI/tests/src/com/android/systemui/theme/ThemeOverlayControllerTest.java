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

package com.android.systemui.theme;

import static com.android.systemui.theme.ThemeOverlayApplier.MONET_ACCENT_COLOR_PACKAGE;
import static com.android.systemui.theme.ThemeOverlayApplier.MONET_SYSTEM_PALETTE_PACKAGE;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_ACCENT_COLOR;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_SYSTEM_PALETTE;
import static com.android.systemui.theme.ThemeOverlayController.USE_LOCK_SCREEN_WALLPAPER;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.graphics.Color;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ThemeOverlayControllerTest extends SysuiTestCase {

    private ThemeOverlayController mThemeOverlayController;
    @Mock
    private Executor mBgExecutor;
    @Mock
    private Executor mMainExecutor;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private Handler mBgHandler;
    @Mock
    private ThemeOverlayApplier mThemeOverlayApplier;
    @Mock
    private SecureSettings mSecureSettings;
    @Mock
    private WallpaperManager mWallpaperManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private DumpManager mDumpManager;
    @Captor
    private ArgumentCaptor<KeyguardStateController.Callback> mKeyguardStateControllerCallback;
    @Captor
    private ArgumentCaptor<WallpaperManager.OnColorsChangedListener> mColorsListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mThemeOverlayController = new ThemeOverlayController(null /* context */,
                mBroadcastDispatcher, mBgHandler, mMainExecutor, mBgExecutor, mThemeOverlayApplier,
                mSecureSettings, mWallpaperManager, mUserManager, mKeyguardStateController,
                mDumpManager);

        mThemeOverlayController.start();
        if (USE_LOCK_SCREEN_WALLPAPER) {
            verify(mKeyguardStateController).addCallback(
                    mKeyguardStateControllerCallback.capture());
        }
        verify(mWallpaperManager).addOnColorsChangedListener(mColorsListener.capture(), eq(null),
                eq(UserHandle.USER_ALL));
        verify(mDumpManager).registerDumpable(any(), any());

        List<Integer> colorList = List.of(Color.RED, Color.BLUE);
        when(mThemeOverlayApplier.getAvailableAccentColors()).thenReturn(colorList);
        when(mThemeOverlayApplier.getAvailableSystemColors()).thenReturn(colorList);
    }

    @Test
    public void start_checksWallpaper() {
        ArgumentCaptor<Runnable> registrationRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(mBgExecutor).execute(registrationRunnable.capture());

        registrationRunnable.getValue().run();
        verify(mWallpaperManager).getWallpaperColors(eq(WallpaperManager.FLAG_LOCK));
        verify(mWallpaperManager).getWallpaperColors(eq(WallpaperManager.FLAG_SYSTEM));
    }

    @Test
    public void onWallpaperColorsChanged_setsTheme() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);
        ArgumentCaptor<Map<String, String>> themeOverlays = ArgumentCaptor.forClass(Map.class);

        verify(mThemeOverlayApplier).getAvailableSystemColors();
        verify(mThemeOverlayApplier).getAvailableAccentColors();
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(themeOverlays.capture(), any());

        // Assert that we received the colors that we were expecting
        assertThat(themeOverlays.getValue().get(OVERLAY_CATEGORY_SYSTEM_PALETTE))
                .isEqualTo(MONET_SYSTEM_PALETTE_PACKAGE
                        + Integer.toHexString(Color.RED).toUpperCase());
        assertThat(themeOverlays.getValue().get(OVERLAY_CATEGORY_ACCENT_COLOR))
                .isEqualTo(MONET_ACCENT_COLOR_PACKAGE
                        + Integer.toHexString(Color.BLUE).toUpperCase());

        // Should not ask again if changed to same value
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);
        verifyNoMoreInteractions(mThemeOverlayApplier);
    }

    @Test
    public void onWallpaperColorsChanged_preservesWallpaperPickerTheme() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);

        String jsonString =
                "{\"android.theme.customization.system_palette\":\"override.package.name\"}";
        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);
        ArgumentCaptor<Map<String, String>> themeOverlays = ArgumentCaptor.forClass(Map.class);

        verify(mThemeOverlayApplier).getAvailableSystemColors();
        verify(mThemeOverlayApplier).getAvailableAccentColors();
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(themeOverlays.capture(), any());

        // Assert that we received the colors that we were expecting
        assertThat(themeOverlays.getValue().get(OVERLAY_CATEGORY_SYSTEM_PALETTE))
                .isEqualTo("override.package.name");
    }
}
