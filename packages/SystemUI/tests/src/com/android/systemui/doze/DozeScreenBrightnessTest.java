/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.doze;

import static com.android.systemui.doze.DozeMachine.State.DOZE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSED;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSE_DONE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSING;
import static com.android.systemui.doze.DozeMachine.State.DOZE_REQUEST_PULSE;
import static com.android.systemui.doze.DozeMachine.State.FINISH;
import static com.android.systemui.doze.DozeMachine.State.INITIALIZED;
import static com.android.systemui.doze.DozeMachine.State.UNINITIALIZED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.PowerManager;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.utils.hardware.FakeSensorManager;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Ignore
public class DozeScreenBrightnessTest extends SysuiTestCase {

    DozeServiceFake mServiceFake;
    DozeScreenBrightness mScreen;
    FakeSensorManager.FakeGenericSensor mSensor;
    FakeSensorManager mSensorManager;

    @Before
    public void setUp() throws Exception {
        mServiceFake = new DozeServiceFake();
        mSensorManager = new FakeSensorManager(mContext);
        mSensor = mSensorManager.getFakeLightSensor();
        mScreen = new DozeScreenBrightness(mContext, mServiceFake, mSensorManager,
                mSensor.getSensor(), null /* handler */);
    }

    @Test
    public void testInitialize_setsScreenBrightnessToValidValue() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        assertNotEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightness);
        assertTrue(mServiceFake.screenBrightness <= PowerManager.BRIGHTNESS_ON);
    }

    @Test
    public void testAod_usesLightSensor() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mSensor.sendSensorEvent(1000);

        assertEquals(1000, mServiceFake.screenBrightness);
    }

    @Test
    public void testPausingAod_pausesLightSensor() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mSensor.sendSensorEvent(1000);

        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSED);

        mSensor.sendSensorEvent(1001);

        assertNotEquals(1001, mServiceFake.screenBrightness);
    }

    @Test
    public void testPausingAod_resetsBrightness() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mSensor.sendSensorEvent(1000);

        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSED);

        assertNotEquals(1000, mServiceFake.screenBrightness);
    }

    @Test
    public void testPulsing_usesLightSensor() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);
        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);

        mSensor.sendSensorEvent(1000);

        assertEquals(1000, mServiceFake.screenBrightness);
    }

    @Test
    public void testDozingAfterPulsing_pausesLightSensor() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);
        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);
        mScreen.transitionTo(DOZE_REQUEST_PULSE, DOZE_PULSING);
        mScreen.transitionTo(DOZE_PULSING, DOZE_PULSE_DONE);
        mScreen.transitionTo(DOZE_PULSE_DONE, DOZE);

        mSensor.sendSensorEvent(1000);

        assertNotEquals(1000, mServiceFake.screenBrightness);
    }

    @Test
    public void testNullSensor() throws Exception {
        mScreen = new DozeScreenBrightness(mContext, mServiceFake, mSensorManager,
                null /* sensor */, null /* handler */);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSED);
    }

    @Test
    public void testNoBrightnessDeliveredAfterFinish() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, FINISH);

        mSensor.sendSensorEvent(1000);

        assertNotEquals(1000, mServiceFake.screenBrightness);
    }

    @Test
    public void testBrightness_atLeastOne() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mSensor.sendSensorEvent(0);

        assertTrue("Brightness must be at least 1, but was " + mServiceFake.screenBrightness,
                mServiceFake.screenBrightness >= 1);
    }
}