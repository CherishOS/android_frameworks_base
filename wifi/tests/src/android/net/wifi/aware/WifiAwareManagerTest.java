/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.aware;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.wifi.RttManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Unit test harness for WifiAwareManager class.
 */
@SmallTest
public class WifiAwareManagerTest {
    private WifiAwareManager mDut;
    private TestLooper mMockLooper;
    private Handler mMockLooperHandler;

    @Rule
    public ErrorCollector collector = new ErrorCollector();

    @Mock
    public Context mockContext;

    @Mock
    public AttachCallback mockCallback;

    @Mock
    public DiscoverySessionCallback mockSessionCallback;

    @Mock
    public IWifiAwareManager mockAwareService;

    @Mock
    public PublishDiscoverySession mockPublishSession;

    @Mock
    public SubscribeDiscoverySession mockSubscribeSession;

    @Mock
    public RttManager.RttListener mockRttListener;

    private static final int AWARE_STATUS_ERROR = -1;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mDut = new WifiAwareManager(mockContext, mockAwareService);
        mMockLooper = new TestLooper();
        mMockLooperHandler = new Handler(mMockLooper.getLooper());
    }

    /*
     * Straight pass-through tests
     */

    /**
     * Validate pass-through of isUsageEnabled() API.
     */
    @Test
    public void testIsUsageEnable() throws Exception {
        mDut.isAvailable();

        verify(mockAwareService).isUsageEnabled();
    }

    /**
     * Validate pass-through of getCharacteristics() API.
     */
    @Test
    public void testGetCharacteristics() throws Exception {
        mDut.getCharacteristics();

        verify(mockAwareService).getCharacteristics();
    }

    /*
     * WifiAwareEventCallbackProxy Tests
     */

    /**
     * Validate the successful connect flow: (1) connect + success (2) publish, (3) disconnect
     * (4) try publishing on old session (5) connect again
     */
    @Test
    public void testConnectFlow() throws Exception {
        final int clientId = 4565;

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IBinder> binder = ArgumentCaptor.forClass(IBinder.class);

        // (1) connect + success
        mDut.attach(mockCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).connect(binder.capture(), any(),
                clientProxyCallback.capture(), isNull(), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (2) publish - should succeed
        PublishConfig publishConfig = new PublishConfig.Builder().build();
        session.publish(publishConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).publish(eq(clientId), eq(publishConfig), any());

        // (3) disconnect
        session.close();
        inOrder.verify(mockAwareService).disconnect(eq(clientId), eq(binder.getValue()));

        // (4) try publishing again - fails silently
        session.publish(new PublishConfig.Builder().build(), mockSessionCallback,
                mMockLooperHandler);

        // (5) connect
        mDut.attach(mockCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).connect(binder.capture(), any(), any(), isNull(),
                eq(false));

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService);
    }

    /**
     * Validate the failed connect flow: (1) connect + failure, (2) connect + success (3) subscribe
     */
    @Test
    public void testConnectFailure() throws Exception {
        final int clientId = 4565;
        final int reason = AWARE_STATUS_ERROR;

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService);
        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);

        // (1) connect + failure
        mDut.attach(mockCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                isNull(), eq(false));
        clientProxyCallback.getValue().onConnectFail(reason);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttachFailed();

        // (2) connect + success
        mDut.attach(mockCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                isNull(), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (4) subscribe: should succeed
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();
        session.subscribe(subscribeConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).subscribe(eq(clientId), eq(subscribeConfig), any());

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService);
    }

    /**
     * Validate that can call connect to create multiple sessions: (1) connect
     * + success, (2) try connect again
     */
    @Test
    public void testInvalidConnectSequence() throws Exception {
        final int clientId = 4565;

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);

        // (1) connect + success
        mDut.attach(mockCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                isNull(), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(any());

        // (2) connect + success
        mDut.attach(mockCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                isNull(), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId + 1);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(any());

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService);
    }

    /*
     * WifiAwareDiscoverySessionCallbackProxy Tests
     */

    /**
     * Validate the publish flow: (0) connect + success, (1) publish, (2)
     * success creates session, (3) pass through everything, (4) update publish
     * through session, (5) terminate locally, (6) try another command -
     * ignored.
     */
    @Test
    public void testPublishFlow() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();
        final PeerHandle peerHandle = new PeerHandle(873);
        final String string1 = "hey from here...";
        final byte[] matchFilter = { 1, 12, 2, 31, 32 };
        final int messageId = 2123;
        final int reason = AWARE_STATUS_ERROR;

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession);
        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<IWifiAwareDiscoverySessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<PublishDiscoverySession> publishSession = ArgumentCaptor
                .forClass(PublishDiscoverySession.class);
        ArgumentCaptor<PeerHandle> peerIdCaptor = ArgumentCaptor.forClass(PeerHandle.class);
        ArgumentCaptor<List<byte[]>> matchFilterCaptor = ArgumentCaptor.forClass(
                (Class) List.class);

        // (0) connect + success
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (1) publish
        session.publish(publishConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).publish(eq(clientId), eq(publishConfig),
                sessionProxyCallback.capture());

        // (2) publish session created
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onPublishStarted(publishSession.capture());

        // (3) ...
        publishSession.getValue().sendMessage(peerHandle, messageId, string1.getBytes());
        sessionProxyCallback.getValue().onMatch(peerHandle.peerId, string1.getBytes(), matchFilter);
        sessionProxyCallback.getValue().onMessageReceived(peerHandle.peerId, string1.getBytes());
        sessionProxyCallback.getValue().onMessageSendFail(messageId, reason);
        sessionProxyCallback.getValue().onMessageSendSuccess(messageId);
        mMockLooper.dispatchAll();

        inOrder.verify(mockAwareService).sendMessage(eq(clientId), eq(sessionId),
                eq(peerHandle.peerId), eq(string1.getBytes()), eq(messageId), eq(0));
        inOrder.verify(mockSessionCallback).onServiceDiscovered(peerIdCaptor.capture(),
                eq(string1.getBytes()),
                matchFilterCaptor.capture());

        // note: need to capture/compare elements since the Mockito eq() is a shallow comparator
        List<byte[]> parsedMatchFilter = new TlvBufferUtils.TlvIterable(0, 1, matchFilter).toList();
        collector.checkThat("match-filter-size", parsedMatchFilter.size(),
                equalTo(matchFilterCaptor.getValue().size()));
        collector.checkThat("match-filter-entry0", parsedMatchFilter.get(0),
                equalTo(matchFilterCaptor.getValue().get(0)));
        collector.checkThat("match-filter-entry1", parsedMatchFilter.get(1),
                equalTo(matchFilterCaptor.getValue().get(1)));

        assertEquals(peerIdCaptor.getValue().peerId, peerHandle.peerId);
        inOrder.verify(mockSessionCallback).onMessageReceived(peerIdCaptor.capture(),
                eq(string1.getBytes()));
        assertEquals(peerIdCaptor.getValue().peerId, peerHandle.peerId);
        inOrder.verify(mockSessionCallback).onMessageSendFailed(eq(messageId));
        inOrder.verify(mockSessionCallback).onMessageSendSucceeded(eq(messageId));

        // (4) update publish
        publishSession.getValue().updatePublish(publishConfig);
        sessionProxyCallback.getValue().onSessionConfigFail(reason);
        mMockLooper.dispatchAll();
        inOrder.verify(mockAwareService).updatePublish(eq(clientId), eq(sessionId),
                eq(publishConfig));
        inOrder.verify(mockSessionCallback).onSessionConfigFailed();

        // (5) terminate
        publishSession.getValue().close();
        mMockLooper.dispatchAll();
        inOrder.verify(mockAwareService).terminateSession(clientId, sessionId);

        // (6) try an update (nothing)
        publishSession.getValue().updatePublish(publishConfig);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession);
    }

    /**
     * Validate race condition of session terminate and session action: (1)
     * connect, (2) publish success + terminate, (3) update.
     */
    @Test
    public void testPublishRemoteTerminate() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession);
        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<IWifiAwareDiscoverySessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<PublishDiscoverySession> publishSession = ArgumentCaptor
                .forClass(PublishDiscoverySession.class);

        // (1) connect successfully
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (2) publish: successfully - then terminated
        session.publish(publishConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).publish(eq(clientId), eq(publishConfig),
                sessionProxyCallback.capture());
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        sessionProxyCallback.getValue().onSessionTerminated(0);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onPublishStarted(publishSession.capture());
        inOrder.verify(mockSessionCallback).onSessionTerminated();

        // (3) failure when trying to update: NOP
        publishSession.getValue().updatePublish(publishConfig);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession);
    }

    /**
     * Validate the subscribe flow: (0) connect + success, (1) subscribe, (2)
     * success creates session, (3) pass through everything, (4) update
     * subscribe through session, (5) terminate locally, (6) try another command
     * - ignored.
     */
    @Test
    public void testSubscribeFlow() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();
        final PeerHandle peerHandle = new PeerHandle(873);
        final String string1 = "hey from here...";
        final byte[] matchFilter = { 1, 12, 3, 31, 32 }; // bad data!
        final int messageId = 2123;
        final int reason = AWARE_STATUS_ERROR;

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockSubscribeSession);
        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<IWifiAwareDiscoverySessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<SubscribeDiscoverySession> subscribeSession = ArgumentCaptor
                .forClass(SubscribeDiscoverySession.class);
        ArgumentCaptor<PeerHandle> peerIdCaptor = ArgumentCaptor.forClass(PeerHandle.class);

        // (0) connect + success
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (1) subscribe
        session.subscribe(subscribeConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).subscribe(eq(clientId), eq(subscribeConfig),
                sessionProxyCallback.capture());

        // (2) subscribe session created
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSubscribeStarted(subscribeSession.capture());

        // (3) ...
        subscribeSession.getValue().sendMessage(peerHandle, messageId, string1.getBytes());
        sessionProxyCallback.getValue().onMatch(peerHandle.peerId, string1.getBytes(), matchFilter);
        sessionProxyCallback.getValue().onMessageReceived(peerHandle.peerId, string1.getBytes());
        sessionProxyCallback.getValue().onMessageSendFail(messageId, reason);
        sessionProxyCallback.getValue().onMessageSendSuccess(messageId);
        mMockLooper.dispatchAll();

        inOrder.verify(mockAwareService).sendMessage(eq(clientId), eq(sessionId),
                eq(peerHandle.peerId), eq(string1.getBytes()), eq(messageId), eq(0));
        inOrder.verify(mockSessionCallback).onServiceDiscovered(peerIdCaptor.capture(),
                eq(string1.getBytes()), (List<byte[]>) isNull());
        assertEquals((peerIdCaptor.getValue()).peerId, peerHandle.peerId);
        inOrder.verify(mockSessionCallback).onMessageReceived(peerIdCaptor.capture(),
                eq(string1.getBytes()));
        assertEquals((peerIdCaptor.getValue()).peerId, peerHandle.peerId);
        inOrder.verify(mockSessionCallback).onMessageSendFailed(eq(messageId));
        inOrder.verify(mockSessionCallback).onMessageSendSucceeded(eq(messageId));

        // (4) update subscribe
        subscribeSession.getValue().updateSubscribe(subscribeConfig);
        sessionProxyCallback.getValue().onSessionConfigFail(reason);
        mMockLooper.dispatchAll();
        inOrder.verify(mockAwareService).updateSubscribe(eq(clientId), eq(sessionId),
                eq(subscribeConfig));
        inOrder.verify(mockSessionCallback).onSessionConfigFailed();

        // (5) terminate
        subscribeSession.getValue().close();
        mMockLooper.dispatchAll();
        inOrder.verify(mockAwareService).terminateSession(clientId, sessionId);

        // (6) try an update (nothing)
        subscribeSession.getValue().updateSubscribe(subscribeConfig);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService,
                mockSubscribeSession);
    }

    /**
     * Validate race condition of session terminate and session action: (1)
     * connect, (2) subscribe success + terminate, (3) update.
     */
    @Test
    public void testSubscribeRemoteTerminate() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockSubscribeSession);
        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<IWifiAwareDiscoverySessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<SubscribeDiscoverySession> subscribeSession = ArgumentCaptor
                .forClass(SubscribeDiscoverySession.class);

        // (1) connect successfully
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (2) subscribe: successfully - then terminated
        session.subscribe(subscribeConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).subscribe(eq(clientId), eq(subscribeConfig),
                sessionProxyCallback.capture());
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        sessionProxyCallback.getValue().onSessionTerminated(0);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSubscribeStarted(subscribeSession.capture());
        inOrder.verify(mockSessionCallback).onSessionTerminated();

        // (3) failure when trying to update: NOP
        subscribeSession.getValue().updateSubscribe(subscribeConfig);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService,
                mockSubscribeSession);
    }

    /*
     * ConfigRequest Tests
     */

    @Test
    public void testConfigRequestBuilderDefaults() {
        ConfigRequest configRequest = new ConfigRequest.Builder().build();

        collector.checkThat("mClusterHigh", ConfigRequest.CLUSTER_ID_MAX,
                equalTo(configRequest.mClusterHigh));
        collector.checkThat("mClusterLow", ConfigRequest.CLUSTER_ID_MIN,
                equalTo(configRequest.mClusterLow));
        collector.checkThat("mMasterPreference", 0,
                equalTo(configRequest.mMasterPreference));
        collector.checkThat("mSupport5gBand", false, equalTo(configRequest.mSupport5gBand));
        collector.checkThat("mDiscoveryWindowInterval.length", 2,
                equalTo(configRequest.mDiscoveryWindowInterval.length));
        collector.checkThat("mDiscoveryWindowInterval[2.4GHz]", ConfigRequest.DW_INTERVAL_NOT_INIT,
                equalTo(configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_24GHZ]));
        collector.checkThat("mDiscoveryWindowInterval[5Hz]", ConfigRequest.DW_INTERVAL_NOT_INIT,
                equalTo(configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_5GHZ]));
    }

    @Test
    public void testConfigRequestBuilder() {
        final int clusterHigh = 100;
        final int clusterLow = 5;
        final int masterPreference = 55;
        final boolean supportBand5g = true;
        final int dwWindow5GHz = 3;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterHigh(clusterHigh)
                .setClusterLow(clusterLow).setMasterPreference(masterPreference)
                .setSupport5gBand(supportBand5g)
                .setDiscoveryWindowInterval(ConfigRequest.NAN_BAND_5GHZ, dwWindow5GHz)
                .build();

        collector.checkThat("mClusterHigh", clusterHigh, equalTo(configRequest.mClusterHigh));
        collector.checkThat("mClusterLow", clusterLow, equalTo(configRequest.mClusterLow));
        collector.checkThat("mMasterPreference", masterPreference,
                equalTo(configRequest.mMasterPreference));
        collector.checkThat("mSupport5gBand", supportBand5g, equalTo(configRequest.mSupport5gBand));
        collector.checkThat("mDiscoveryWindowInterval.length", 2,
                equalTo(configRequest.mDiscoveryWindowInterval.length));
        collector.checkThat("mDiscoveryWindowInterval[2.4GHz]", ConfigRequest.DW_INTERVAL_NOT_INIT,
                equalTo(configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_24GHZ]));
        collector.checkThat("mDiscoveryWindowInterval[5GHz]", dwWindow5GHz,
                equalTo(configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_5GHZ]));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderMasterPrefNegative() {
        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setMasterPreference(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderMasterPrefReserved1() {
        new ConfigRequest.Builder().setMasterPreference(1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderMasterPrefReserved255() {
        new ConfigRequest.Builder().setMasterPreference(255);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderMasterPrefTooLarge() {
        new ConfigRequest.Builder().setMasterPreference(256);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterLowNegative() {
        new ConfigRequest.Builder().setClusterLow(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterHighNegative() {
        new ConfigRequest.Builder().setClusterHigh(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterLowAboveMax() {
        new ConfigRequest.Builder().setClusterLow(ConfigRequest.CLUSTER_ID_MAX + 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterHighAboveMax() {
        new ConfigRequest.Builder().setClusterHigh(ConfigRequest.CLUSTER_ID_MAX + 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterLowLargerThanHigh() {
        new ConfigRequest.Builder().setClusterLow(100).setClusterHigh(5).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderDwIntervalInvalidBand() {
        new ConfigRequest.Builder().setDiscoveryWindowInterval(5, 1).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderDwIntervalInvalidValueZero() {
        new ConfigRequest.Builder().setDiscoveryWindowInterval(ConfigRequest.NAN_BAND_24GHZ,
                0).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderDwIntervalInvalidValueLarge() {
        new ConfigRequest.Builder().setDiscoveryWindowInterval(ConfigRequest.NAN_BAND_5GHZ,
                6).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderDwIntervalInvalidValueLargeValidate() {
        ConfigRequest cr = new ConfigRequest.Builder().build();
        cr.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_5GHZ] = 6;
        cr.validate();
    }

    @Test
    public void testConfigRequestParcel() {
        final int clusterHigh = 189;
        final int clusterLow = 25;
        final int masterPreference = 177;
        final boolean supportBand5g = true;
        final int dwWindow24GHz = 1;
        final int dwWindow5GHz = 5;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterHigh(clusterHigh)
                .setClusterLow(clusterLow).setMasterPreference(masterPreference)
                .setSupport5gBand(supportBand5g)
                .setDiscoveryWindowInterval(ConfigRequest.NAN_BAND_24GHZ, dwWindow24GHz)
                .setDiscoveryWindowInterval(ConfigRequest.NAN_BAND_5GHZ, dwWindow5GHz)
                .build();

        Parcel parcelW = Parcel.obtain();
        configRequest.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        ConfigRequest rereadConfigRequest = ConfigRequest.CREATOR.createFromParcel(parcelR);

        assertEquals(configRequest, rereadConfigRequest);
    }

    /*
     * SubscribeConfig Tests
     */

    @Test
    public void testSubscribeConfigBuilderDefaults() {
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        collector.checkThat("mServiceName", subscribeConfig.mServiceName, equalTo(null));
        collector.checkThat("mSubscribeType", subscribeConfig.mSubscribeType,
                equalTo(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE));
        collector.checkThat("mTtlSec", subscribeConfig.mTtlSec, equalTo(0));
        collector.checkThat("mEnableTerminateNotification",
                subscribeConfig.mEnableTerminateNotification, equalTo(true));
    }

    @Test
    public void testSubscribeConfigBuilder() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] matchFilter = { 1, 16, 1, 22 };
        final int subscribeType = SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE;
        final int subscribeCount = 10;
        final int subscribeTtl = 15;
        final boolean enableTerminateNotification = false;

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo.getBytes()).setMatchFilter(
                        new TlvBufferUtils.TlvIterable(0, 1, matchFilter).toList())
                .setSubscribeType(subscribeType)
                .setTtlSec(subscribeTtl)
                .setTerminateNotificationEnabled(enableTerminateNotification).build();

        collector.checkThat("mServiceName", serviceName.getBytes(),
                equalTo(subscribeConfig.mServiceName));
        collector.checkThat("mServiceSpecificInfo",
                serviceSpecificInfo.getBytes(), equalTo(subscribeConfig.mServiceSpecificInfo));
        collector.checkThat("mMatchFilter", matchFilter, equalTo(subscribeConfig.mMatchFilter));
        collector.checkThat("mSubscribeType", subscribeType,
                equalTo(subscribeConfig.mSubscribeType));
        collector.checkThat("mTtlSec", subscribeTtl, equalTo(subscribeConfig.mTtlSec));
        collector.checkThat("mEnableTerminateNotification", enableTerminateNotification,
                equalTo(subscribeConfig.mEnableTerminateNotification));
    }

    @Test
    public void testSubscribeConfigParcel() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] matchFilter = { 1, 16, 1, 22 };
        final int subscribeType = SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE;
        final int subscribeTtl = 15;
        final boolean enableTerminateNotification = true;

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo.getBytes()).setMatchFilter(
                        new TlvBufferUtils.TlvIterable(0, 1, matchFilter).toList())
                .setSubscribeType(subscribeType)
                .setTtlSec(subscribeTtl)
                .setTerminateNotificationEnabled(enableTerminateNotification).build();

        Parcel parcelW = Parcel.obtain();
        subscribeConfig.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        SubscribeConfig rereadSubscribeConfig = SubscribeConfig.CREATOR.createFromParcel(parcelR);

        assertEquals(subscribeConfig, rereadSubscribeConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSubscribeConfigBuilderBadSubscribeType() {
        new SubscribeConfig.Builder().setSubscribeType(10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSubscribeConfigBuilderNegativeTtl() {
        new SubscribeConfig.Builder().setTtlSec(-100);
    }

    /*
     * PublishConfig Tests
     */

    @Test
    public void testPublishConfigBuilderDefaults() {
        PublishConfig publishConfig = new PublishConfig.Builder().build();

        collector.checkThat("mServiceName", publishConfig.mServiceName, equalTo(null));
        collector.checkThat("mPublishType", publishConfig.mPublishType,
                equalTo(PublishConfig.PUBLISH_TYPE_UNSOLICITED));
        collector.checkThat("mTtlSec", publishConfig.mTtlSec, equalTo(0));
        collector.checkThat("mEnableTerminateNotification",
                publishConfig.mEnableTerminateNotification, equalTo(true));
    }

    @Test
    public void testPublishConfigBuilder() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] matchFilter = { 1, 16, 1, 22 };
        final int publishType = PublishConfig.PUBLISH_TYPE_SOLICITED;
        final int publishCount = 10;
        final int publishTtl = 15;
        final boolean enableTerminateNotification = false;

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo.getBytes()).setMatchFilter(
                        new TlvBufferUtils.TlvIterable(0, 1, matchFilter).toList())
                .setPublishType(publishType)
                .setTtlSec(publishTtl)
                .setTerminateNotificationEnabled(enableTerminateNotification).build();

        collector.checkThat("mServiceName", serviceName.getBytes(),
                equalTo(publishConfig.mServiceName));
        collector.checkThat("mServiceSpecificInfo",
                serviceSpecificInfo.getBytes(), equalTo(publishConfig.mServiceSpecificInfo));
        collector.checkThat("mMatchFilter", matchFilter, equalTo(publishConfig.mMatchFilter));
        collector.checkThat("mPublishType", publishType, equalTo(publishConfig.mPublishType));
        collector.checkThat("mTtlSec", publishTtl, equalTo(publishConfig.mTtlSec));
        collector.checkThat("mEnableTerminateNotification", enableTerminateNotification,
                equalTo(publishConfig.mEnableTerminateNotification));
    }

    @Test
    public void testPublishConfigParcel() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] matchFilter = { 1, 16, 1, 22 };
        final int publishType = PublishConfig.PUBLISH_TYPE_SOLICITED;
        final int publishCount = 10;
        final int publishTtl = 15;
        final boolean enableTerminateNotification = false;

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo.getBytes()).setMatchFilter(
                        new TlvBufferUtils.TlvIterable(0, 1, matchFilter).toList())
                .setPublishType(publishType)
                .setTtlSec(publishTtl)
                .setTerminateNotificationEnabled(enableTerminateNotification).build();

        Parcel parcelW = Parcel.obtain();
        publishConfig.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        PublishConfig rereadPublishConfig = PublishConfig.CREATOR.createFromParcel(parcelR);

        assertEquals(publishConfig, rereadPublishConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPublishConfigBuilderBadPublishType() {
        new PublishConfig.Builder().setPublishType(5);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPublishConfigBuilderNegativeTtl() {
        new PublishConfig.Builder().setTtlSec(-10);
    }

    /*
     * Ranging tests
     */

    /**
     * Validate ranging + success flow: (1) connect, (2) create a (publish) session, (3) start
     * ranging, (4) ranging success callback, (5) ranging aborted callback ignored (since
     * listener removed).
     */
    @Test
    public void testRangingCallbacks() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final int rangingId = 3482;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();
        final RttManager.RttParams rttParams = new RttManager.RttParams();
        rttParams.deviceType = RttManager.RTT_PEER_NAN;
        rttParams.bssid = Integer.toString(1234);
        final RttManager.RttResult rttResults = new RttManager.RttResult();
        rttResults.distance = 10;

        when(mockAwareService.startRanging(anyInt(), anyInt(), any())).thenReturn(rangingId);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession, mockRttListener);
        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<IWifiAwareDiscoverySessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<PublishDiscoverySession> publishSession = ArgumentCaptor
                .forClass(PublishDiscoverySession.class);
        ArgumentCaptor<RttManager.ParcelableRttParams> rttParamCaptor = ArgumentCaptor
                .forClass(RttManager.ParcelableRttParams.class);
        ArgumentCaptor<RttManager.RttResult[]> rttResultsCaptor = ArgumentCaptor
                .forClass(RttManager.RttResult[].class);

        // (1) connect successfully
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (2) publish successfully
        session.publish(publishConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).publish(eq(clientId), eq(publishConfig),
                sessionProxyCallback.capture());
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onPublishStarted(publishSession.capture());

        // (3) start ranging
        publishSession.getValue().startRanging(new RttManager.RttParams[]{rttParams},
                mockRttListener);
        inOrder.verify(mockAwareService).startRanging(eq(clientId), eq(sessionId),
                rttParamCaptor.capture());
        collector.checkThat("RttParams.deviceType", rttParams.deviceType,
                equalTo(rttParamCaptor.getValue().mParams[0].deviceType));
        collector.checkThat("RttParams.bssid", rttParams.bssid,
                equalTo(rttParamCaptor.getValue().mParams[0].bssid));

        // (4) ranging success callback
        clientProxyCallback.getValue().onRangingSuccess(rangingId,
                new RttManager.ParcelableRttResults(new RttManager.RttResult[] { rttResults }));
        mMockLooper.dispatchAll();
        inOrder.verify(mockRttListener).onSuccess(rttResultsCaptor.capture());
        collector.checkThat("RttResult.distance", rttResults.distance,
                equalTo(rttResultsCaptor.getValue()[0].distance));

        // (5) ranging aborted callback (should be ignored since listener cleared on first callback)
        clientProxyCallback.getValue().onRangingAborted(rangingId);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession, mockRttListener);
    }

    /*
     * Data-path tests
     */

    /**
     * Validate that correct network specifier is generated for client-based data-path.
     */
    @Test
    public void testNetworkSpecifierWithClient() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final PeerHandle peerHandle = new PeerHandle(123412);
        final int role = WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER;
        final byte[] pmk = "Some arbitrary byte array".getBytes();
        final String passphrase = "A really bad password";
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();

        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<IWifiAwareDiscoverySessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<PublishDiscoverySession> publishSession = ArgumentCaptor
                .forClass(PublishDiscoverySession.class);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession, mockRttListener);

        // (1) connect successfully
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (2) publish successfully
        session.publish(publishConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).publish(eq(clientId), eq(publishConfig),
                sessionProxyCallback.capture());
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onPublishStarted(publishSession.capture());

        // (3) request an open (unencrypted) network specifier from the session
        WifiAwareNetworkSpecifier ns =
                (WifiAwareNetworkSpecifier) publishSession.getValue().createNetworkSpecifierOpen(
                        peerHandle);

        // validate format
        collector.checkThat("role", role, equalTo(ns.role));
        collector.checkThat("client_id", clientId, equalTo(ns.clientId));
        collector.checkThat("session_id", sessionId, equalTo(ns.sessionId));
        collector.checkThat("peer_id", peerHandle.peerId, equalTo(ns.peerId));

        // (4) request an encrypted (PMK) network specifier from the session
        ns = (WifiAwareNetworkSpecifier) publishSession.getValue().createNetworkSpecifierPmk(
                peerHandle, pmk);

        // validate format
        collector.checkThat("role", role, equalTo(ns.role));
        collector.checkThat("client_id", clientId, equalTo(ns.clientId));
        collector.checkThat("session_id", sessionId, equalTo(ns.sessionId));
        collector.checkThat("peer_id", peerHandle.peerId, equalTo(ns.peerId));
        collector.checkThat("pmk", pmk , equalTo(ns.pmk));

        // (5) request an encrypted (Passphrase) network specifier from the session
        ns = (WifiAwareNetworkSpecifier) publishSession.getValue().createNetworkSpecifierPassphrase(
                peerHandle, passphrase);

        // validate format
        collector.checkThat("role", role, equalTo(ns.role));
        collector.checkThat("client_id", clientId, equalTo(ns.clientId));
        collector.checkThat("session_id", sessionId, equalTo(ns.sessionId));
        collector.checkThat("peer_id", peerHandle.peerId, equalTo(ns.peerId));
        collector.checkThat("passphrase", passphrase, equalTo(ns.passphrase));

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession, mockRttListener);
    }

    /**
     * Validate that correct network specifier is generated for a direct data-path (i.e.
     * specifying MAC address as opposed to a client-based oqaque specification).
     */
    @Test
    public void testNetworkSpecifierDirect() throws Exception {
        final int clientId = 134;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final byte[] someMac = HexEncoding.decode("000102030405".toCharArray(), false);
        final int role = WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_INITIATOR;
        final byte[] pmk = "Some arbitrary pmk data".getBytes();
        final String passphrase = "A really bad password";

        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession, mockRttListener);

        // (1) connect successfully
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (2) request an open (unencrypted) direct network specifier
        WifiAwareNetworkSpecifier ns =
                (WifiAwareNetworkSpecifier) session.createNetworkSpecifierOpen(role, someMac);

        // validate format
        collector.checkThat("role", role, equalTo(ns.role));
        collector.checkThat("client_id", clientId, equalTo(ns.clientId));
        collector.checkThat("peer_mac", someMac, equalTo(ns.peerMac));

        // (3) request an encrypted (PMK) direct network specifier
        ns = (WifiAwareNetworkSpecifier) session.createNetworkSpecifierPmk(role, someMac, pmk);

        // validate format
        collector.checkThat("role", role, equalTo(ns.role));
        collector.checkThat("client_id", clientId, equalTo(ns.clientId));
        collector.checkThat("peer_mac", someMac, equalTo(ns.peerMac));
        collector.checkThat("pmk", pmk, equalTo(ns.pmk));

        // (4) request an encrypted (Passphrase) direct network specifier
        ns = (WifiAwareNetworkSpecifier) session.createNetworkSpecifierPassphrase(role, someMac,
                passphrase);

        // validate format
        collector.checkThat("role", role, equalTo(ns.role));
        collector.checkThat("client_id", clientId, equalTo(ns.clientId));
        collector.checkThat("peer_mac", someMac, equalTo(ns.peerMac));
        collector.checkThat("passphrase", passphrase, equalTo(ns.passphrase));

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession, mockRttListener);
    }
}
