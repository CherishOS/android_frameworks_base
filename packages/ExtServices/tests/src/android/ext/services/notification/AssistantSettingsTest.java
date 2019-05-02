/**
 * Copyright (C) 2018 The Android Open Source Project
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

package android.ext.services.notification;

import static android.ext.services.notification.AssistantSettings.DEFAULT_MAX_SUGGESTIONS;
import static android.provider.DeviceConfig.setProperty;

import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.ContentResolver;
import android.os.Handler;
import android.os.Looper;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.support.test.uiautomator.UiDevice;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class AssistantSettingsTest {
    private static final String CLEAR_DEVICE_CONFIG_KEY_CMD =
            "device_config delete " + DeviceConfig.NAMESPACE_SYSTEMUI;

    private static final int USER_ID = 5;

    @Rule
    public final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getContext(), null);

    @Mock Runnable mOnUpdateRunnable;

    private ContentResolver mResolver;
    private AssistantSettings mAssistantSettings;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mResolver = mContext.getContentResolver();
        Handler handler = new Handler(Looper.getMainLooper());

        // To bypass real calls to global settings values, set the Settings values here.
        Settings.Global.putFloat(mResolver,
                Settings.Global.BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT, 0.8f);
        Settings.Global.putInt(mResolver, Settings.Global.BLOCKING_HELPER_STREAK_LIMIT, 2);
        Settings.Secure.putInt(mResolver, Settings.Secure.NOTIFICATION_NEW_INTERRUPTION_MODEL, 1);

        mAssistantSettings = AssistantSettings.createForTesting(
                handler, mResolver, USER_ID, mOnUpdateRunnable);
    }

    @After
    public void tearDown() throws IOException {
        clearDeviceConfig();
    }

    @Test
    public void testGenerateRepliesDisabled() {
        runWithShellPermissionIdentity(() -> setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_GENERATE_REPLIES,
                "false",
                false /* makeDefault */));
        mAssistantSettings.onDeviceConfigPropertiesChanged(DeviceConfig.NAMESPACE_SYSTEMUI);

        assertFalse(mAssistantSettings.mGenerateReplies);
    }

    @Test
    public void testGenerateRepliesEnabled() {
        runWithShellPermissionIdentity(() -> setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_GENERATE_REPLIES,
                "true",
                false /* makeDefault */));
        mAssistantSettings.onDeviceConfigPropertiesChanged(DeviceConfig.NAMESPACE_SYSTEMUI);

        assertTrue(mAssistantSettings.mGenerateReplies);
    }

    @Test
    public void testGenerateRepliesNullFlag() {
        runWithShellPermissionIdentity(() -> setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_GENERATE_REPLIES,
                "false",
                false /* makeDefault */));
        mAssistantSettings.onDeviceConfigPropertiesChanged(DeviceConfig.NAMESPACE_SYSTEMUI);

        assertFalse(mAssistantSettings.mGenerateReplies);

        runWithShellPermissionIdentity(() -> setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_GENERATE_REPLIES,
                null,
                false /* makeDefault */));
        mAssistantSettings.onDeviceConfigPropertiesChanged(DeviceConfig.NAMESPACE_SYSTEMUI);

        // Go back to the default value.
        assertTrue(mAssistantSettings.mGenerateReplies);
    }

    @Test
    public void testGenerateActionsDisabled() {
        runWithShellPermissionIdentity(() -> setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_GENERATE_ACTIONS,
                "false",
                false /* makeDefault */));
        mAssistantSettings.onDeviceConfigPropertiesChanged(DeviceConfig.NAMESPACE_SYSTEMUI);

        assertFalse(mAssistantSettings.mGenerateActions);
    }

    @Test
    public void testGenerateActionsEnabled() {
        runWithShellPermissionIdentity(() -> setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_GENERATE_ACTIONS,
                "true",
                false /* makeDefault */));
        mAssistantSettings.onDeviceConfigPropertiesChanged(DeviceConfig.NAMESPACE_SYSTEMUI);

        assertTrue(mAssistantSettings.mGenerateActions);
    }

    @Test
    public void testGenerateActionsNullFlag() {
        runWithShellPermissionIdentity(() -> setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_GENERATE_ACTIONS,
                "false",
                false /* makeDefault */));
        mAssistantSettings.onDeviceConfigPropertiesChanged(DeviceConfig.NAMESPACE_SYSTEMUI);

        assertFalse(mAssistantSettings.mGenerateActions);

        runWithShellPermissionIdentity(() -> setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_GENERATE_ACTIONS,
                null,
                false /* makeDefault */));
        mAssistantSettings.onDeviceConfigPropertiesChanged(DeviceConfig.NAMESPACE_SYSTEMUI);

        // Go back to the default value.
        assertTrue(mAssistantSettings.mGenerateActions);
    }

    @Test
    public void testMaxMessagesToExtract() {
        runWithShellPermissionIdentity(() -> setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_MAX_MESSAGES_TO_EXTRACT,
                "10",
                false /* makeDefault */));
        mAssistantSettings.onDeviceConfigPropertiesChanged(DeviceConfig.NAMESPACE_SYSTEMUI);

        assertEquals(10, mAssistantSettings.mMaxMessagesToExtract);
    }

    @Test
    public void testMaxSuggestions() {
        runWithShellPermissionIdentity(() -> setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_MAX_SUGGESTIONS,
                "5",
                false /* makeDefault */));
        mAssistantSettings.onDeviceConfigPropertiesChanged(DeviceConfig.NAMESPACE_SYSTEMUI);

        assertEquals(5, mAssistantSettings.mMaxSuggestions);
    }

    @Test
    public void testMaxSuggestionsEmpty() {
        mAssistantSettings.onDeviceConfigPropertiesChanged(DeviceConfig.NAMESPACE_SYSTEMUI);

        assertEquals(DEFAULT_MAX_SUGGESTIONS, mAssistantSettings.mMaxSuggestions);
    }

    @Test
    public void testStreakLimit() {
        verify(mOnUpdateRunnable, never()).run();

        // Update settings value.
        int newStreakLimit = 4;
        Settings.Global.putInt(mResolver,
                Settings.Global.BLOCKING_HELPER_STREAK_LIMIT, newStreakLimit);

        // Notify for the settings value we updated.
        mAssistantSettings.onChange(false, Settings.Global.getUriFor(
                Settings.Global.BLOCKING_HELPER_STREAK_LIMIT));

        assertEquals(newStreakLimit, mAssistantSettings.mStreakLimit);
        verify(mOnUpdateRunnable).run();
    }

    @Test
    public void testDismissToViewRatioLimit() {
        verify(mOnUpdateRunnable, never()).run();

        // Update settings value.
        float newDismissToViewRatioLimit = 3f;
        Settings.Global.putFloat(mResolver,
                Settings.Global.BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT,
                newDismissToViewRatioLimit);

        // Notify for the settings value we updated.
        mAssistantSettings.onChange(false, Settings.Global.getUriFor(
                Settings.Global.BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT));

        assertEquals(newDismissToViewRatioLimit, mAssistantSettings.mDismissToViewRatioLimit, 1e-6);
        verify(mOnUpdateRunnable).run();
    }

    private static void clearDeviceConfig() throws IOException {
        UiDevice uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        uiDevice.executeShellCommand(
                CLEAR_DEVICE_CONFIG_KEY_CMD + " " + SystemUiDeviceConfigFlags.NAS_GENERATE_ACTIONS);
        uiDevice.executeShellCommand(
                CLEAR_DEVICE_CONFIG_KEY_CMD + " " + SystemUiDeviceConfigFlags.NAS_GENERATE_REPLIES);
        uiDevice.executeShellCommand(
                CLEAR_DEVICE_CONFIG_KEY_CMD + " "
                + SystemUiDeviceConfigFlags.NAS_MAX_MESSAGES_TO_EXTRACT);
        uiDevice.executeShellCommand(
                CLEAR_DEVICE_CONFIG_KEY_CMD + " " + SystemUiDeviceConfigFlags.NAS_MAX_SUGGESTIONS);
    }

}
