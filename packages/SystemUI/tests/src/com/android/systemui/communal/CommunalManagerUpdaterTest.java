/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.communal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.communal.CommunalManager;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.settings.FakeSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class CommunalManagerUpdaterTest extends SysuiTestCase {
    private CommunalSourceMonitor mMonitor;
    @Mock
    private CommunalManager mCommunalManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(CommunalManager.class, mCommunalManager);

        mMonitor = new CommunalSourceMonitor(
                Handler.createAsync(TestableLooper.get(this).getLooper()),
                new FakeSettings());
        final CommunalManagerUpdater updater = new CommunalManagerUpdater(mContext, mMonitor);
        updater.start();
    }

    @Test
    public void testUpdateSystemService_false() {
        mMonitor.setSource(null);
        verify(mCommunalManager).setCommunalViewShowing(false);
    }

    @Test
    public void testUpdateSystemService_true() {
        final CommunalSource source = mock(CommunalSource.class);
        mMonitor.setSource(source);
        verify(mCommunalManager).setCommunalViewShowing(true);
    }
}
