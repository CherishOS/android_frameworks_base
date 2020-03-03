/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.server.tv.tunerresourcemanager;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.media.tv.ITvInputManager;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.media.tv.tuner.frontend.FrontendSettings;
import android.media.tv.tunerresourcemanager.ResourceClientProfile;
import android.media.tv.tunerresourcemanager.TunerFrontendInfo;
import android.media.tv.tunerresourcemanager.TunerFrontendRequest;
import android.media.tv.tunerresourcemanager.TunerResourceManager;
import android.os.RemoteException;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.google.common.truth.Correspondence;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests for {@link TunerResourceManagerService} class.
 */
@SmallTest
@RunWith(JUnit4.class)
public class TunerResourceManagerServiceTest {
    private static final String TAG = "TunerResourceManagerServiceTest";
    private Context mContextSpy;
    @Mock private ITvInputManager mITvInputManagerMock;
    private TunerResourceManagerService mTunerResourceManagerService;
    private int mReclaimingId;

    // A correspondence to compare a FrontendResource and a TunerFrontendInfo.
    private static final Correspondence<FrontendResource, TunerFrontendInfo> FR_TFI_COMPARE =
            new Correspondence<FrontendResource, TunerFrontendInfo>() {
            @Override
            public boolean compare(FrontendResource actual, TunerFrontendInfo expected) {
                if (actual == null || expected == null) {
                    return (actual == null) && (expected == null);
                }

                return actual.getId() == expected.getId()
                        && actual.getType() == expected.getFrontendType()
                        && actual.getExclusiveGroupId() == expected.getExclusiveGroupId();
            }

            @Override
            public String toString() {
                return "is correctly configured from ";
            }
        };

    private static <T> List<T> sparseArrayToList(SparseArray<T> sparseArray) {
        if (sparseArray == null) {
            return null;
        }
        List<T> arrayList = new ArrayList<T>(sparseArray.size());
        for (int i = 0; i < sparseArray.size(); i++) {
            arrayList.add(sparseArray.valueAt(i));
        }
        return arrayList;
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        TvInputManager tvInputManager = new TvInputManager(mITvInputManagerMock, 0);
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        when(mContextSpy.getSystemService(Context.TV_INPUT_SERVICE)).thenReturn(tvInputManager);
        mTunerResourceManagerService = new TunerResourceManagerService(mContextSpy) {
            @Override
            protected void reclaimFrontendResource(int reclaimingId) {
                mReclaimingId = reclaimingId;
            }
        };
        mTunerResourceManagerService.onStart(true /*isForTesting*/);
        mReclaimingId = -1;
    }

    @Test
    public void setFrontendListTest_addFrontendResources_noExclusiveGroupId() {
        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        SparseArray<FrontendResource> resources =
                mTunerResourceManagerService.getFrontendResources();
        for (int id = 0; id < infos.length; id++) {
            assertThat(resources.get(infos[id].getId())
                    .getExclusiveGroupMemberFeIds().size()).isEqualTo(0);
        }
        for (int id = 0; id < infos.length; id++) {
            assertThat(resources.get(infos[id].getId())
                    .getExclusiveGroupMemberFeIds().size()).isEqualTo(0);
        }
        assertThat(sparseArrayToList(resources)).comparingElementsUsing(FR_TFI_COMPARE)
                .containsExactlyElementsIn(Arrays.asList(infos));
    }

    @Test
    public void setFrontendListTest_addFrontendResources_underTheSameExclusiveGroupId() {
        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[4];
        infos[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[2] =
                new TunerFrontendInfo(2 /*id*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        infos[3] =
                new TunerFrontendInfo(3 /*id*/, FrontendSettings.TYPE_ATSC, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        SparseArray<FrontendResource> resources =
                mTunerResourceManagerService.getFrontendResources();
        assertThat(sparseArrayToList(resources)).comparingElementsUsing(FR_TFI_COMPARE)
                .containsExactlyElementsIn(Arrays.asList(infos));

        assertThat(resources.get(0).getExclusiveGroupMemberFeIds())
                .isEqualTo(new ArrayList<Integer>());
        assertThat(resources.get(1).getExclusiveGroupMemberFeIds())
                .isEqualTo(new ArrayList<Integer>(Arrays.asList(2, 3)));
        assertThat(resources.get(2).getExclusiveGroupMemberFeIds())
                .isEqualTo(new ArrayList<Integer>(Arrays.asList(1, 3)));
        assertThat(resources.get(3).getExclusiveGroupMemberFeIds())
                .isEqualTo(new ArrayList<Integer>(Arrays.asList(1, 2)));
    }

    @Test
    public void setFrontendListTest_updateExistingFrontendResources() {
        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);

        mTunerResourceManagerService.setFrontendInfoListInternal(infos);
        SparseArray<FrontendResource> resources0 =
                mTunerResourceManagerService.getFrontendResources();

        mTunerResourceManagerService.setFrontendInfoListInternal(infos);
        SparseArray<FrontendResource> resources1 =
                mTunerResourceManagerService.getFrontendResources();

        assertThat(resources0).isEqualTo(resources1);
    }

    @Test
    public void setFrontendListTest_removeFrontendResources_noExclusiveGroupId() {
        // Init frontend resources.
        TunerFrontendInfo[] infos0 = new TunerFrontendInfo[3];
        infos0[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos0[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos0[2] =
                new TunerFrontendInfo(2 /*id*/, FrontendSettings.TYPE_DVBS, 2 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos0);

        TunerFrontendInfo[] infos1 = new TunerFrontendInfo[1];
        infos1[0] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos1);

        SparseArray<FrontendResource> resources =
                mTunerResourceManagerService.getFrontendResources();
        for (int id = 0; id < infos1.length; id++) {
            assertThat(resources.get(infos1[id].getId())
                    .getExclusiveGroupMemberFeIds().size()).isEqualTo(0);
        }
        assertThat(sparseArrayToList(resources)).comparingElementsUsing(FR_TFI_COMPARE)
                .containsExactlyElementsIn(Arrays.asList(infos1));
    }

    @Test
    public void setFrontendListTest_removeFrontendResources_underTheSameExclusiveGroupId() {
        // Init frontend resources.
        TunerFrontendInfo[] infos0 = new TunerFrontendInfo[3];
        infos0[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos0[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos0[2] =
                new TunerFrontendInfo(2 /*id*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos0);

        TunerFrontendInfo[] infos1 = new TunerFrontendInfo[1];
        infos1[0] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos1);

        SparseArray<FrontendResource> resources =
                mTunerResourceManagerService.getFrontendResources();
        for (int id = 0; id < infos1.length; id++) {
            assertThat(resources.get(infos1[id].getId())
                    .getExclusiveGroupMemberFeIds().size()).isEqualTo(0);
        }
        assertThat(sparseArrayToList(resources)).comparingElementsUsing(FR_TFI_COMPARE)
                .containsExactlyElementsIn(Arrays.asList(infos1));
    }

    @Test
    public void requestFrontendTest_ClientNotRegistered() {
        TunerFrontendRequest request =
                new TunerFrontendRequest(0 /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendId = new int[1];
        try {
            assertThat(mTunerResourceManagerService.requestFrontendInternal(request, frontendId))
                    .isFalse();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        assertThat(frontendId[0]).isEqualTo(TunerResourceManager.INVALID_FRONTEND_ID);
    }

    @Test
    public void requestFrontendTest_NoFrontendWithGiveTypeAvailable() {
        ResourceClientProfile profile = new ResourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId = new int[1];
        mTunerResourceManagerService.registerClientProfileInternal(
                profile, null /*listener*/, clientId);
        assertThat(clientId[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[1];
        infos[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBS, 0 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                new TunerFrontendRequest(clientId[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendId = new int[1];
        try {
            assertThat(mTunerResourceManagerService.requestFrontendInternal(request, frontendId))
                    .isFalse();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        assertThat(frontendId[0]).isEqualTo(TunerResourceManager.INVALID_FRONTEND_ID);
    }

    @Test
    public void requestFrontendTest_FrontendWithNoExclusiveGroupAvailable() {
        ResourceClientProfile profile = new ResourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId = new int[1];
        mTunerResourceManagerService.registerClientProfileInternal(
                profile, null /*listener*/, clientId);
        assertThat(clientId[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[3];
        infos[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[2] =
                new TunerFrontendInfo(2 /*id*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                new TunerFrontendRequest(clientId[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendId = new int[1];
        try {
            assertThat(mTunerResourceManagerService.requestFrontendInternal(request, frontendId))
                    .isTrue();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        assertThat(frontendId[0]).isEqualTo(0);
    }

    @Test
    public void requestFrontendTest_FrontendWithExclusiveGroupAvailable() {
        ResourceClientProfile profile0 = new ResourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        ResourceClientProfile profile1 = new ResourceClientProfile("1" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId0 = new int[1];
        int[] clientId1 = new int[1];
        mTunerResourceManagerService.registerClientProfileInternal(
                profile0, null /*listener*/, clientId0);
        mTunerResourceManagerService.registerClientProfileInternal(
                profile1, null /*listener*/, clientId1);
        assertThat(clientId0[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        assertThat(clientId1[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[3];
        infos[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[2] =
                new TunerFrontendInfo(2 /*id*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        int[] frontendId = new int[1];
        TunerFrontendRequest request =
                new TunerFrontendRequest(clientId1[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        try {
            assertThat(mTunerResourceManagerService.requestFrontendInternal(request, frontendId))
                    .isTrue();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        assertThat(frontendId[0]).isEqualTo(infos[0].getId());

        request =
                new TunerFrontendRequest(clientId0[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        try {
            assertThat(mTunerResourceManagerService.requestFrontendInternal(request, frontendId))
                    .isTrue();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        assertThat(frontendId[0]).isEqualTo(infos[1].getId());
        assertThat(mTunerResourceManagerService.getFrontendResources().get(infos[1].getId())
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResources().get(infos[2].getId())
                .isInUse()).isTrue();
    }

    @Test
    public void requestFrontendTest_NoFrontendAvailable_RequestWithLowerPriority() {
        // Register clients
        ResourceClientProfile[] profiles = new ResourceClientProfile[2];
        profiles[0] = new ResourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        profiles[1] = new ResourceClientProfile("1" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientPriorities = {100, 50};
        int[] clientId0 = new int[1];
        int[] clientId1 = new int[1];
        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[0], null /*listener*/, clientId0);
        assertThat(clientId0[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.getClientProfiles().get(clientId0[0])
                .setPriority(clientPriorities[0]);
        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[1], null /*listener*/, clientId1);
        assertThat(clientId1[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.getClientProfiles().get(clientId1[0])
                .setPriority(clientPriorities[1]);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                new TunerFrontendRequest(clientId0[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendId = new int[1];
        try {
            assertThat(mTunerResourceManagerService.requestFrontendInternal(request, frontendId))
                    .isTrue();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        request =
                new TunerFrontendRequest(clientId1[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        try {
            assertThat(mTunerResourceManagerService.requestFrontendInternal(request, frontendId))
                    .isFalse();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        assertThat(mReclaimingId).isEqualTo(-1);

        request =
                new TunerFrontendRequest(clientId1[0] /*clientId*/, FrontendSettings.TYPE_DVBS);
        try {
            assertThat(mTunerResourceManagerService.requestFrontendInternal(request, frontendId))
                    .isFalse();
            assertThat(mReclaimingId).isEqualTo(-1);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Test
    public void requestFrontendTest_NoFrontendAvailable_RequestWithHigherPriority() {
        // Register clients
        ResourceClientProfile[] profiles = new ResourceClientProfile[2];
        profiles[0] = new ResourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        profiles[1] = new ResourceClientProfile("1" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientPriorities = {100, 500};
        int[] clientId0 = new int[1];
        int[] clientId1 = new int[1];
        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[0], null /*listener*/, clientId0);
        assertThat(clientId0[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.getClientProfiles().get(clientId0[0])
                .setPriority(clientPriorities[0]);
        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[1], null /*listener*/, clientId1);
        assertThat(clientId1[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.getClientProfiles().get(clientId1[0])
                .setPriority(clientPriorities[1]);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                new TunerFrontendRequest(clientId0[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendId = new int[1];
        try {
            assertThat(mTunerResourceManagerService.requestFrontendInternal(request, frontendId))
                    .isTrue();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        assertThat(frontendId[0]).isEqualTo(infos[0].getId());

        request =
                new TunerFrontendRequest(clientId1[0] /*clientId*/, FrontendSettings.TYPE_DVBS);
        try {
            assertThat(mTunerResourceManagerService.requestFrontendInternal(request, frontendId))
                    .isTrue();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        assertThat(frontendId[0]).isEqualTo(infos[1].getId());
        assertThat(mTunerResourceManagerService.getFrontendResources().get(infos[0].getId())
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResources().get(infos[1].getId())
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResources()
                .get(infos[0].getId()).getOwnerClientId()).isEqualTo(clientId1[0]);
        assertThat(mTunerResourceManagerService.getFrontendResources()
                .get(infos[1].getId()).getOwnerClientId()).isEqualTo(clientId1[0]);
        assertThat(mReclaimingId).isEqualTo(clientId0[0]);
    }

    @Test
    public void unregisterClientTest_usingFrontend() {
        // Register client
        ResourceClientProfile profile = new ResourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId = new int[1];
        mTunerResourceManagerService.registerClientProfileInternal(
                profile, null /*listener*/, clientId);
        assertThat(clientId[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                new TunerFrontendRequest(clientId[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendId = new int[1];
        try {
            assertThat(mTunerResourceManagerService.requestFrontendInternal(request, frontendId))
                    .isTrue();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        assertThat(frontendId[0]).isEqualTo(infos[0].getId());
        assertThat(mTunerResourceManagerService.getFrontendResources().get(infos[0].getId())
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResources().get(infos[1].getId())
                .isInUse()).isTrue();

        // Unregister client when using frontend
        mTunerResourceManagerService.unregisterClientProfileInternal(clientId[0]);
        assertThat(mTunerResourceManagerService.getFrontendResources().get(infos[0].getId())
                .isInUse()).isFalse();
        assertThat(mTunerResourceManagerService.getFrontendResources().get(infos[1].getId())
                .isInUse()).isFalse();

    }
}
