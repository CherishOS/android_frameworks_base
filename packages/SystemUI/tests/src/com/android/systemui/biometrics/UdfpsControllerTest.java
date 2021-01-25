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

package com.android.systemui.biometrics;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.hardware.biometrics.SensorProperties;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.PowerManager;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class UdfpsControllerTest extends SysuiTestCase {

    // Use this for inputs going into SystemUI. Use UdfpsController.mUdfpsSensorId for things
    // leaving SystemUI.
    private static final int TEST_UDFPS_SENSOR_ID = 1;

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    // Unit under test
    private UdfpsController mUdfpsController;

    // Dependencies
    @Mock
    private Resources mResources;
    @Mock
    private LayoutInflater mLayoutInflater;
    @Mock
    private FingerprintManager mFingerprintManager;
    @Mock
    private DisplayManager mDisplayManager;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private StatusBarStateController mStatusBarStateController;

    private FakeSettings mSystemSettings;
    private FakeExecutor mFgExecutor;

    // Stuff for configuring mocks
    @Mock
    private UdfpsView mUdfpsView;
    @Mock
    private TypedArray mBrightnessValues;
    @Mock
    private TypedArray mBrightnessBacklight;

    // Capture listeners so that they can be used to send events
    @Captor private ArgumentCaptor<IUdfpsOverlayController> mOverlayCaptor;
    private IUdfpsOverlayController mOverlayController;
    @Captor private ArgumentCaptor<UdfpsView.OnTouchListener> mTouchListenerCaptor;
    @Captor private ArgumentCaptor<Runnable> mRunAfterShowingScrimAndDotCaptor;

    @Before
    public void setUp() {
        setUpResources();
        when(mLayoutInflater.inflate(R.layout.udfps_view, null, false)).thenReturn(mUdfpsView);
        final List<FingerprintSensorPropertiesInternal> props = new ArrayList<>();
        props.add(new FingerprintSensorPropertiesInternal(TEST_UDFPS_SENSOR_ID,
                SensorProperties.STRENGTH_STRONG,
                5 /* maxEnrollmentsPerUser */,
                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                true /* resetLockoutRequiresHardwareAuthToken */));
        when(mFingerprintManager.getSensorPropertiesInternal()).thenReturn(props);
        mSystemSettings = new FakeSettings();
        mFgExecutor = new FakeExecutor(new FakeSystemClock());
        mUdfpsController = new UdfpsController(
                mContext,
                mResources,
                mLayoutInflater,
                mFingerprintManager,
                mDisplayManager,
                mWindowManager,
                mSystemSettings,
                mStatusBarStateController,
                mFgExecutor);
        verify(mFingerprintManager).setUdfpsOverlayController(mOverlayCaptor.capture());
        mOverlayController = mOverlayCaptor.getValue();

        assertEquals(TEST_UDFPS_SENSOR_ID, mUdfpsController.mSensorProps.sensorId);
    }

    private void setUpResources() {
        when(mBrightnessValues.length()).thenReturn(2);
        when(mBrightnessValues.getFloat(0, PowerManager.BRIGHTNESS_OFF_FLOAT)).thenReturn(1f);
        when(mBrightnessValues.getFloat(1, PowerManager.BRIGHTNESS_OFF_FLOAT)).thenReturn(2f);
        when(mResources.obtainTypedArray(com.android.internal.R.array.config_screenBrightnessNits))
                .thenReturn(mBrightnessValues);
        when(mBrightnessBacklight.length()).thenReturn(2);
        when(mBrightnessBacklight.getFloat(0, PowerManager.BRIGHTNESS_OFF_FLOAT)).thenReturn(1f);
        when(mBrightnessBacklight.getFloat(1, PowerManager.BRIGHTNESS_OFF_FLOAT)).thenReturn(2f);
        when(mResources.obtainTypedArray(
                com.android.internal.R.array.config_autoBrightnessDisplayValuesNits))
                .thenReturn(mBrightnessBacklight);
        when(mResources.getIntArray(com.android.internal.R.array.config_screenBrightnessBacklight))
                .thenReturn(new int[]{1, 2});
    }

    @Test
    public void dozeTimeTick() {
        mUdfpsController.dozeTimeTick();
        verify(mUdfpsView).dozeTimeTick();
    }

    @Test
    public void showUdfpsOverlay_addsViewToWindow() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH);
        mFgExecutor.runAllReady();
        verify(mWindowManager).addView(eq(mUdfpsView), any());
    }

    @Test
    public void hideUdfpsOverlay_removesViewFromWindow() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH);
        mOverlayController.hideUdfpsOverlay(TEST_UDFPS_SENSOR_ID);
        mFgExecutor.runAllReady();
        verify(mWindowManager).removeView(eq(mUdfpsView));
    }

    @Test
    public void fingerDown() throws RemoteException {
        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isShowScrimAndDot()).thenReturn(false);
        when(mUdfpsView.isValidTouch(anyFloat(), anyFloat(), anyFloat())).thenReturn(true);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH);
        mFgExecutor.runAllReady();
        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, event);
        event.recycle();
        // THEN the scrim and dot is shown
        verify(mUdfpsView).showScrimAndDot();
        // AND a runnable that passes the event to FingerprintManager is set on the view
        verify(mUdfpsView).setRunAfterShowingScrimAndDot(
                mRunAfterShowingScrimAndDotCaptor.capture());
        mRunAfterShowingScrimAndDotCaptor.getValue().run();
        verify(mFingerprintManager).onPointerDown(eq(mUdfpsController.mSensorProps.sensorId), eq(0),
                eq(0), eq(0f), eq(0f));
    }

    @Test
    public void aodInterrupt() throws RemoteException {
        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH);
        mFgExecutor.runAllReady();
        // WHEN fingerprint is requested because of AOD interrupt
        mUdfpsController.onAodInterrupt(0, 0, 2f, 3f);
        // THEN the scrim and dot is shown
        verify(mUdfpsView).showScrimAndDot();
        // AND a runnable that passes the event to FingerprintManager is set on the view
        verify(mUdfpsView).setRunAfterShowingScrimAndDot(
                mRunAfterShowingScrimAndDotCaptor.capture());
        mRunAfterShowingScrimAndDotCaptor.getValue().run();
        verify(mFingerprintManager).onPointerDown(eq(mUdfpsController.mSensorProps.sensorId), eq(0),
                eq(0), eq(3f) /* minor */, eq(2f) /* major */);
    }

    @Test
    public void cancelAodInterrupt() throws RemoteException {
        // GIVEN AOD interrupt
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH);
        mFgExecutor.runAllReady();
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);
        // WHEN it is cancelled
        mUdfpsController.onCancelAodInterrupt();
        // THEN the scrim and dot is hidden
        verify(mUdfpsView).hideScrimAndDot();
    }

    @Test
    public void aodInterruptTimeout() throws RemoteException {
        // GIVEN AOD interrupt
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH);
        mFgExecutor.runAllReady();
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);
        // WHEN it times out
        mFgExecutor.advanceClockToNext();
        mFgExecutor.runAllReady();
        // THEN the scrim and dot is hidden
        verify(mUdfpsView).hideScrimAndDot();
    }
}
