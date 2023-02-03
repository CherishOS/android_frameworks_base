/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.wifi.sharedconnectivity.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.sharedconnectivity.app.SharedConnectivitySettingsState}.
 */
@SmallTest
public class SharedConnectivitySettingsStateTest {
    private static final boolean INSTANT_TETHER_STATE = true;

    private static final boolean INSTANT_TETHER_STATE_1 = false;
    /**
     * Verifies parcel serialization/deserialization.
     */
    @Test
    public void testParcelOperation() {
        SharedConnectivitySettingsState state = buildSettingsStateBuilder().build();

        Parcel parcelW = Parcel.obtain();
        state.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        SharedConnectivitySettingsState fromParcel =
                SharedConnectivitySettingsState.CREATOR.createFromParcel(parcelR);

        assertEquals(state, fromParcel);
        assertEquals(state.hashCode(), fromParcel.hashCode());
    }

    /**
     * Verifies the Equals operation
     */
    @Test
    public void testEqualsOperation() {
        SharedConnectivitySettingsState state1 = buildSettingsStateBuilder().build();
        SharedConnectivitySettingsState state2 = buildSettingsStateBuilder().build();
        assertEquals(state1, state2);

        SharedConnectivitySettingsState.Builder builder = buildSettingsStateBuilder()
                .setInstantTetherEnabled(INSTANT_TETHER_STATE_1);
        assertNotEquals(state1, builder.build());
    }

    /**
     * Verifies the get methods return the expected data.
     */
    @Test
    public void testGetMethods() {
        SharedConnectivitySettingsState state = buildSettingsStateBuilder().build();
        assertEquals(state.isInstantTetherEnabled(), INSTANT_TETHER_STATE);
    }

    private SharedConnectivitySettingsState.Builder buildSettingsStateBuilder() {
        return new SharedConnectivitySettingsState.Builder()
                .setInstantTetherEnabled(INSTANT_TETHER_STATE);
    }
}
