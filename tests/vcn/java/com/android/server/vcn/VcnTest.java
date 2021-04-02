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

package com.android.server.vcn;

import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_MMS;
import static android.net.vcn.VcnManager.VCN_STATUS_CODE_ACTIVE;
import static android.net.vcn.VcnManager.VCN_STATUS_CODE_INACTIVE;
import static android.net.vcn.VcnManager.VCN_STATUS_CODE_SAFE_MODE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.NetworkRequest;
import android.net.vcn.VcnConfig;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnGatewayConnectionConfigTest;
import android.os.ParcelUuid;
import android.os.test.TestLooper;
import android.util.ArraySet;

import com.android.server.VcnManagementService.VcnCallback;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.Vcn.VcnGatewayStatusCallback;
import com.android.server.vcn.VcnNetworkProvider.NetworkRequestListener;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class VcnTest {
    private static final String PKG_NAME = VcnTest.class.getPackage().getName();
    private static final ParcelUuid TEST_SUB_GROUP = new ParcelUuid(new UUID(0, 0));
    private static final int NETWORK_SCORE = 0;
    private static final int PROVIDER_ID = 5;
    private static final int[][] TEST_CAPS =
            new int[][] {
                new int[] {NET_CAPABILITY_MMS, NET_CAPABILITY_INTERNET},
                new int[] {NET_CAPABILITY_DUN}
            };

    private Context mContext;
    private VcnContext mVcnContext;
    private TelephonySubscriptionSnapshot mSubscriptionSnapshot;
    private VcnNetworkProvider mVcnNetworkProvider;
    private VcnCallback mVcnCallback;
    private Vcn.Dependencies mDeps;

    private ArgumentCaptor<VcnGatewayStatusCallback> mGatewayStatusCallbackCaptor;

    private TestLooper mTestLooper;
    private VcnGatewayConnectionConfig mGatewayConnectionConfig;
    private VcnConfig mConfig;
    private Vcn mVcn;

    @Before
    public void setUp() {
        mContext = mock(Context.class);
        mVcnContext = mock(VcnContext.class);
        mSubscriptionSnapshot = mock(TelephonySubscriptionSnapshot.class);
        mVcnNetworkProvider = mock(VcnNetworkProvider.class);
        mVcnCallback = mock(VcnCallback.class);
        mDeps = mock(Vcn.Dependencies.class);

        mTestLooper = new TestLooper();

        doReturn(PKG_NAME).when(mContext).getOpPackageName();
        doReturn(mContext).when(mVcnContext).getContext();
        doReturn(mTestLooper.getLooper()).when(mVcnContext).getLooper();
        doReturn(mVcnNetworkProvider).when(mVcnContext).getVcnNetworkProvider();

        // Setup VcnGatewayConnection instance generation
        doAnswer((invocation) -> {
            // Mock-within a doAnswer is safe, because it doesn't actually run nested.
            return mock(VcnGatewayConnection.class);
        }).when(mDeps).newVcnGatewayConnection(any(), any(), any(), any(), any());

        mGatewayStatusCallbackCaptor = ArgumentCaptor.forClass(VcnGatewayStatusCallback.class);

        final VcnConfig.Builder configBuilder = new VcnConfig.Builder(mContext);
        for (final int[] caps : TEST_CAPS) {
            configBuilder.addGatewayConnectionConfig(
                    VcnGatewayConnectionConfigTest.buildTestConfigWithExposedCaps(caps));
        }

        mConfig = configBuilder.build();
        mVcn =
                new Vcn(
                        mVcnContext,
                        TEST_SUB_GROUP,
                        mConfig,
                        mSubscriptionSnapshot,
                        mVcnCallback,
                        mDeps);
    }

    private NetworkRequestListener verifyAndGetRequestListener() {
        ArgumentCaptor<NetworkRequestListener> mNetworkRequestListenerCaptor =
                ArgumentCaptor.forClass(NetworkRequestListener.class);
        verify(mVcnNetworkProvider).registerListener(mNetworkRequestListenerCaptor.capture());

        return mNetworkRequestListenerCaptor.getValue();
    }

    private void startVcnGatewayWithCapabilities(
            NetworkRequestListener requestListener, int... netCapabilities) {
        final NetworkRequest.Builder requestBuilder = new NetworkRequest.Builder();
        for (final int netCapability : netCapabilities) {
            requestBuilder.addCapability(netCapability);
        }

        requestListener.onNetworkRequested(requestBuilder.build(), NETWORK_SCORE, PROVIDER_ID);
        mTestLooper.dispatchAll();
    }

    private void verifyUpdateSubscriptionSnapshotNotifiesGatewayConnections(int status) {
        final NetworkRequestListener requestListener = verifyAndGetRequestListener();
        startVcnGatewayWithCapabilities(requestListener, TEST_CAPS[0]);

        final Set<VcnGatewayConnection> gatewayConnections = mVcn.getVcnGatewayConnections();
        assertFalse(gatewayConnections.isEmpty());

        final TelephonySubscriptionSnapshot updatedSnapshot =
                mock(TelephonySubscriptionSnapshot.class);

        mVcn.setStatus(status);

        mVcn.updateSubscriptionSnapshot(updatedSnapshot);
        mTestLooper.dispatchAll();

        for (final VcnGatewayConnection gateway : gatewayConnections) {
            verify(gateway, status == VCN_STATUS_CODE_ACTIVE ? times(1) : never())
                    .updateSubscriptionSnapshot(eq(updatedSnapshot));
        }
    }

    @Test
    public void testSubscriptionSnapshotUpdatesVcnGatewayConnections() {
        verifyUpdateSubscriptionSnapshotNotifiesGatewayConnections(VCN_STATUS_CODE_ACTIVE);
    }

    @Test
    public void testSubscriptionSnapshotUpdatesVcnGatewayConnectionsInSafeMode() {
        verifyUpdateSubscriptionSnapshotNotifiesGatewayConnections(VCN_STATUS_CODE_SAFE_MODE);
    }

    private void triggerVcnRequestListeners(NetworkRequestListener requestListener) {
        for (final int[] caps : TEST_CAPS) {
            startVcnGatewayWithCapabilities(requestListener, caps);
        }
    }

    public Set<VcnGatewayConnection> startGatewaysAndGetGatewayConnections(
            NetworkRequestListener requestListener) {
        triggerVcnRequestListeners(requestListener);

        final int numExpectedGateways = TEST_CAPS.length;

        final Set<VcnGatewayConnection> gatewayConnections = mVcn.getVcnGatewayConnections();
        assertEquals(numExpectedGateways, gatewayConnections.size());
        verify(mDeps, times(numExpectedGateways))
                .newVcnGatewayConnection(
                        eq(mVcnContext),
                        eq(TEST_SUB_GROUP),
                        eq(mSubscriptionSnapshot),
                        any(),
                        mGatewayStatusCallbackCaptor.capture());

        return gatewayConnections;
    }

    private void verifySafeMode(
            NetworkRequestListener requestListener,
            Set<VcnGatewayConnection> expectedGatewaysTornDown) {
        assertEquals(VCN_STATUS_CODE_SAFE_MODE, mVcn.getStatus());
        for (final VcnGatewayConnection gatewayConnection : expectedGatewaysTornDown) {
            verify(gatewayConnection).teardownAsynchronously();
        }
        verify(mVcnNetworkProvider).unregisterListener(requestListener);
        verify(mVcnCallback).onEnteredSafeMode();
    }

    @Test
    public void testGatewayEnteringSafeModeNotifiesVcn() {
        final NetworkRequestListener requestListener = verifyAndGetRequestListener();
        final Set<VcnGatewayConnection> gatewayConnections =
                startGatewaysAndGetGatewayConnections(requestListener);

        // Doesn't matter which callback this gets - any Gateway entering Safemode should shut down
        // all Gateways
        final VcnGatewayStatusCallback statusCallback = mGatewayStatusCallbackCaptor.getValue();
        statusCallback.onEnteredSafeMode();
        mTestLooper.dispatchAll();

        verifySafeMode(requestListener, gatewayConnections);
    }

    @Test
    public void testGatewayQuit() {
        final NetworkRequestListener requestListener = verifyAndGetRequestListener();
        final Set<VcnGatewayConnection> gatewayConnections =
                new ArraySet<>(startGatewaysAndGetGatewayConnections(requestListener));

        final VcnGatewayStatusCallback statusCallback = mGatewayStatusCallbackCaptor.getValue();
        statusCallback.onQuit();
        mTestLooper.dispatchAll();

        // Verify that the VCN requests the networkRequests be resent
        assertEquals(1, mVcn.getVcnGatewayConnections().size());
        verify(mVcnNetworkProvider).resendAllRequests(requestListener);

        // Verify that the VcnGatewayConnection is restarted
        triggerVcnRequestListeners(requestListener);
        mTestLooper.dispatchAll();
        assertEquals(2, mVcn.getVcnGatewayConnections().size());
        verify(mDeps, times(gatewayConnections.size() + 1))
                .newVcnGatewayConnection(
                        eq(mVcnContext),
                        eq(TEST_SUB_GROUP),
                        eq(mSubscriptionSnapshot),
                        any(),
                        mGatewayStatusCallbackCaptor.capture());
    }

    @Test
    public void testGatewayQuitWhileInactive() {
        final NetworkRequestListener requestListener = verifyAndGetRequestListener();
        final Set<VcnGatewayConnection> gatewayConnections =
                new ArraySet<>(startGatewaysAndGetGatewayConnections(requestListener));

        mVcn.teardownAsynchronously();
        mTestLooper.dispatchAll();

        final VcnGatewayStatusCallback statusCallback = mGatewayStatusCallbackCaptor.getValue();
        statusCallback.onQuit();
        mTestLooper.dispatchAll();

        // Verify that the VCN requests the networkRequests be resent
        assertEquals(1, mVcn.getVcnGatewayConnections().size());
        verify(mVcnNetworkProvider, never()).resendAllRequests(requestListener);
    }

    @Test
    public void testUpdateConfigReevaluatesGatewayConnections() {
        final NetworkRequestListener requestListener = verifyAndGetRequestListener();
        startGatewaysAndGetGatewayConnections(requestListener);
        assertEquals(2, mVcn.getVcnGatewayConnectionConfigMap().size());

        // Create VcnConfig with only one VcnGatewayConnectionConfig so a gateway connection is torn
        // down. Reuse existing VcnGatewayConnectionConfig so that the gateway connection name
        // matches.
        final List<VcnGatewayConnectionConfig> currentConfigs =
                new ArrayList<>(mVcn.getVcnGatewayConnectionConfigMap().keySet());
        final VcnGatewayConnectionConfig activeConfig = currentConfigs.get(0);
        final VcnGatewayConnectionConfig removedConfig = currentConfigs.get(1);
        final VcnConfig updatedConfig =
                new VcnConfig.Builder(mContext).addGatewayConnectionConfig(activeConfig).build();

        mVcn.updateConfig(updatedConfig);
        mTestLooper.dispatchAll();

        final VcnGatewayConnection activeGatewayConnection =
                mVcn.getVcnGatewayConnectionConfigMap().get(activeConfig);
        final VcnGatewayConnection removedGatewayConnection =
                mVcn.getVcnGatewayConnectionConfigMap().get(removedConfig);
        verify(activeGatewayConnection, never()).teardownAsynchronously();
        verify(removedGatewayConnection).teardownAsynchronously();
        verify(mVcnNetworkProvider).resendAllRequests(requestListener);
    }

    @Test
    public void testUpdateConfigExitsSafeMode() {
        final NetworkRequestListener requestListener = verifyAndGetRequestListener();
        final Set<VcnGatewayConnection> gatewayConnections =
                new ArraySet<>(startGatewaysAndGetGatewayConnections(requestListener));

        final VcnGatewayStatusCallback statusCallback = mGatewayStatusCallbackCaptor.getValue();
        statusCallback.onEnteredSafeMode();
        mTestLooper.dispatchAll();
        verifySafeMode(requestListener, gatewayConnections);

        doAnswer(invocation -> {
            final NetworkRequestListener listener = invocation.getArgument(0);
            triggerVcnRequestListeners(listener);
            return null;
        }).when(mVcnNetworkProvider).registerListener(eq(requestListener));

        mVcn.updateConfig(mConfig);
        mTestLooper.dispatchAll();

        // Registered on start, then re-registered with new configs
        verify(mVcnNetworkProvider, times(2)).registerListener(eq(requestListener));
        assertEquals(VCN_STATUS_CODE_ACTIVE, mVcn.getStatus());
        for (final int[] caps : TEST_CAPS) {
            // Expect each gateway connection created only on initial startup
            verify(mDeps)
                    .newVcnGatewayConnection(
                            eq(mVcnContext),
                            eq(TEST_SUB_GROUP),
                            eq(mSubscriptionSnapshot),
                            argThat(config -> Arrays.equals(caps, config.getExposedCapabilities())),
                            any());
        }
    }

    @Test
    public void testIgnoreNetworkRequestWhileInactive() {
        mVcn.setStatus(VCN_STATUS_CODE_INACTIVE);

        final NetworkRequestListener requestListener = verifyAndGetRequestListener();
        triggerVcnRequestListeners(requestListener);

        verify(mDeps, never()).newVcnGatewayConnection(any(), any(), any(), any(), any());
    }
}
