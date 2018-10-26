/*
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

package com.android.systemui.appops;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.AppOpsManager;
import android.os.UserHandle;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationPresenter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AppOpsControllerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;
    private static final int TEST_UID_OTHER = 500000;

    @Mock private NotificationPresenter mPresenter;
    @Mock private AppOpsManager mAppOpsManager;
    @Mock private AppOpsController.Callback mCallback;

    private AppOpsControllerImpl mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        getContext().addMockSystemService(AppOpsManager.class, mAppOpsManager);

        mController = new AppOpsControllerImpl(mContext, Dependency.get(Dependency.BG_LOOPER));
    }

    @Test
    public void testOnlyListenForFewOps() {
        mController.setListening(true);
        verify(mAppOpsManager, times(1)).startWatchingActive(AppOpsControllerImpl.OPS, mController);
    }

    @Test
    public void testStopListening() {
        mController.setListening(false);
        verify(mAppOpsManager, times(1)).stopWatchingActive(mController);
    }

    @Test
    public void addCallback_includedCode() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mController.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        verify(mCallback).onActiveStateChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
    }

    @Test
    public void addCallback_notIncludedCode() {
        mController.addCallback(new int[]{AppOpsManager.OP_FINE_LOCATION}, mCallback);
        mController.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        verify(mCallback, never()).onActiveStateChanged(
                anyInt(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    public void removeCallback_sameCode() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mController.removeCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mController.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        verify(mCallback, never()).onActiveStateChanged(
                anyInt(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    public void addCallback_notSameCode() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mController.removeCallback(new int[]{AppOpsManager.OP_FINE_LOCATION}, mCallback);
        mController.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        verify(mCallback).onActiveStateChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
    }

    @Test
    public void getActiveItems_sameDetails() {
        mController.onOpActiveChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
        mController.onOpActiveChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
        assertEquals(1, mController.getActiveAppOps().size());
    }

    @Test
    public void getActiveItems_differentDetails() {
        mController.onOpActiveChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
        mController.onOpActiveChanged(AppOpsManager.OP_CAMERA,
                TEST_UID, TEST_PACKAGE_NAME, true);
        assertEquals(2, mController.getActiveAppOps().size());
    }

    @Test public void getActiveItemsForUser() {
        mController.onOpActiveChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
        mController.onOpActiveChanged(AppOpsManager.OP_CAMERA,
                TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
        assertEquals(1,
                mController.getActiveAppOpsForUser(UserHandle.getUserId(TEST_UID)).size());
        assertEquals(1,
                mController.getActiveAppOpsForUser(UserHandle.getUserId(TEST_UID)).size());
    }
}
