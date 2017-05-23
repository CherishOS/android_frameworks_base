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

package com.android.server.connectivity.tethering;

import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_MOBILE_DUN;
import static android.net.ConnectivityManager.TYPE_MOBILE_HIPRI;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static com.android.server.connectivity.tethering.TetheringConfiguration.DUN_NOT_REQUIRED;
import static com.android.server.connectivity.tethering.TetheringConfiguration.DUN_REQUIRED;
import static com.android.server.connectivity.tethering.TetheringConfiguration.DUN_UNSPECIFIED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.telephony.TelephonyManager;

import com.android.internal.util.test.BroadcastInterceptingContext;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class TetheringConfigurationTest {
    @Mock private Context mContext;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private Resources mResources;
    private Context mMockContext;
    private boolean mHasTelephonyManager;

    private class MockContext extends BroadcastInterceptingContext {
        MockContext(Context base) {
            super(base);
        }

        @Override
        public Resources getResources() { return mResources; }

        @Override
        public Object getSystemService(String name) {
            if (Context.TELEPHONY_SERVICE.equals(name)) {
                return mHasTelephonyManager ? mTelephonyManager : null;
            }
            return super.getSystemService(name);
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_dhcp_range))
                .thenReturn(new String[0]);
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_usb_regexs))
                .thenReturn(new String[0]);
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_wifi_regexs))
                .thenReturn(new String[]{ "test_wlan\\d" });
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_bluetooth_regexs))
                .thenReturn(new String[0]);
        mMockContext = new MockContext(mContext);
    }

    @Test
    public void testDunFromTelephonyManagerMeansDun() {
        when(mResources.getIntArray(com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[]{TYPE_MOBILE, TYPE_WIFI, TYPE_MOBILE_HIPRI});
        mHasTelephonyManager = true;
        when(mTelephonyManager.getTetherApnRequired()).thenReturn(DUN_REQUIRED);

        final TetheringConfiguration cfg = new TetheringConfiguration(mMockContext);
        assertTrue(cfg.isDunRequired);
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_DUN));
        assertFalse(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE));
        assertFalse(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_HIPRI));
        // Just to prove we haven't clobbered Wi-Fi:
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_WIFI));
    }

    @Test
    public void testDunNotRequiredFromTelephonyManagerMeansNoDun() {
        when(mResources.getIntArray(com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[]{TYPE_MOBILE_DUN, TYPE_WIFI});
        mHasTelephonyManager = true;
        when(mTelephonyManager.getTetherApnRequired()).thenReturn(DUN_NOT_REQUIRED);

        final TetheringConfiguration cfg = new TetheringConfiguration(mMockContext);
        assertFalse(cfg.isDunRequired);
        assertFalse(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_DUN));
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE));
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_HIPRI));
        // Just to prove we haven't clobbered Wi-Fi:
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_WIFI));
    }

    @Test
    public void testDunFromUpstreamConfigMeansDun() {
        when(mResources.getIntArray(com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[]{TYPE_MOBILE_DUN, TYPE_WIFI});
        mHasTelephonyManager = false;
        when(mTelephonyManager.getTetherApnRequired()).thenReturn(DUN_UNSPECIFIED);

        final TetheringConfiguration cfg = new TetheringConfiguration(mMockContext);
        assertTrue(cfg.isDunRequired);
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_DUN));
        // Just to prove we haven't clobbered Wi-Fi:
        assertTrue(cfg.preferredUpstreamIfaceTypes.contains(TYPE_WIFI));
        // Check that we have not added new cellular interface types
        assertFalse(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE));
        assertFalse(cfg.preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_HIPRI));
    }
}
