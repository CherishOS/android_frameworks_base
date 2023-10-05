/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.telephony.tests;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.telephony.SubscriptionManager;

import com.android.internal.telephony.util.TelephonyUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class TelephonyUtilsTest {
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    // Mocked classes
    @Mock
    private Context mContext;
    @Mock
    private SubscriptionManager mSubscriptionManager;

    @Before
    public void setup() {
        doReturn(mSubscriptionManager).when(mContext)
                .getSystemService(eq(SubscriptionManager.class));
    }


    @Test
    public void getSubscriptionUserHandle_subId_invalid() {
        int invalidSubId = -10;
        doReturn(false).when(mSubscriptionManager).isActiveSubscriptionId(eq(invalidSubId));

        TelephonyUtils.getSubscriptionUserHandle(mContext, invalidSubId);

        // getSubscriptionUserHandle should not be called if subID is inactive.
        verify(mSubscriptionManager, never()).getSubscriptionUserHandle(eq(invalidSubId));
    }

    @Test
    public void getSubscriptionUserHandle_subId_valid() {
        int activeSubId = 1;
        doReturn(true).when(mSubscriptionManager).isActiveSubscriptionId(eq(activeSubId));

        TelephonyUtils.getSubscriptionUserHandle(mContext, activeSubId);

        // getSubscriptionUserHandle should be called if subID is active.
        verify(mSubscriptionManager, times(1)).getSubscriptionUserHandle(eq(activeSubId));
    }
}


