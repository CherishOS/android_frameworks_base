/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.getNetworkTypeName;
import static android.net.NetworkCapabilities.*;

import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.CaptivePortal;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.ConnectivityManager.PacketKeepalive;
import android.net.ConnectivityManager.PacketKeepaliveCallback;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MatchAllNetworkSpecifier;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkConfig;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkMisc;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.RouteInfo;
import android.net.StringNetworkSpecifier;
import android.net.metrics.IpConnectivityLog;
import android.net.util.MultinetworkPolicyTracker;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.Messenger;
import android.os.MessageQueue.IdleHandler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.TextUtils;
import android.util.Log;
import android.util.LogPrinter;

import com.android.internal.util.WakeupMessage;
import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.connectivity.MockableSystemProperties;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.connectivity.NetworkMonitor;
import com.android.server.connectivity.NetworkMonitor.CaptivePortalProbeResult;
import com.android.server.net.NetworkPinner;
import com.android.server.net.NetworkPolicyManagerInternal;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Tests for {@link ConnectivityService}.
 *
 * Build, install and run with:
 *  runtest frameworks-services -c com.android.server.ConnectivityServiceTest
 */
public class ConnectivityServiceTest extends AndroidTestCase {
    private static final String TAG = "ConnectivityServiceTest";

    private static final int TIMEOUT_MS = 500;
    private static final int TEST_LINGER_DELAY_MS = 120;

    private MockContext mServiceContext;
    private WrappedConnectivityService mService;
    private WrappedConnectivityManager mCm;
    private MockNetworkAgent mWiFiNetworkAgent;
    private MockNetworkAgent mCellNetworkAgent;
    private MockNetworkAgent mEthernetNetworkAgent;

    // This class exists to test bindProcessToNetwork and getBoundNetworkForProcess. These methods
    // do not go through ConnectivityService but talk to netd directly, so they don't automatically
    // reflect the state of our test ConnectivityService.
    private class WrappedConnectivityManager extends ConnectivityManager {
        private Network mFakeBoundNetwork;

        public synchronized boolean bindProcessToNetwork(Network network) {
            mFakeBoundNetwork = network;
            return true;
        }

        public synchronized Network getBoundNetworkForProcess() {
            return mFakeBoundNetwork;
        }

        public WrappedConnectivityManager(Context context, ConnectivityService service) {
            super(context, service);
        }
    }

    private class MockContext extends BroadcastInterceptingContext {
        private final MockContentResolver mContentResolver;

        @Spy private Resources mResources;
        private final LinkedBlockingQueue<Intent> mStartedActivities = new LinkedBlockingQueue<>();

        MockContext(Context base) {
            super(base);

            mResources = spy(base.getResources());
            when(mResources.getStringArray(com.android.internal.R.array.networkAttributes)).
                    thenReturn(new String[] {
                            "wifi,1,1,1,-1,true",
                            "mobile,0,0,0,-1,true",
                            "mobile_mms,2,0,2,60000,true",
                    });

            mContentResolver = new MockContentResolver();
            mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        }

        @Override
        public void startActivityAsUser(Intent intent, UserHandle handle) {
            mStartedActivities.offer(intent);
        }

        public Intent expectStartActivityIntent(int timeoutMs) {
            Intent intent = null;
            try {
                intent = mStartedActivities.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {}
            assertNotNull("Did not receive sign-in intent after " + timeoutMs + "ms", intent);
            return intent;
        }

        public void expectNoStartActivityIntent(int timeoutMs) {
            try {
                assertNull("Received unexpected Intent to start activity",
                        mStartedActivities.poll(timeoutMs, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {}
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.CONNECTIVITY_SERVICE.equals(name)) return mCm;
            if (Context.NOTIFICATION_SERVICE.equals(name)) return mock(NotificationManager.class);
            return super.getSystemService(name);
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }

        @Override
        public Resources getResources() {
            return mResources;
        }
    }

    /**
     * Block until the given handler becomes idle, or until timeoutMs has passed.
     */
    private static void waitForIdleHandler(HandlerThread handlerThread, int timeoutMs) {
        final ConditionVariable cv = new ConditionVariable();
        final Handler handler = new Handler(handlerThread.getLooper());
        handler.post(() -> cv.open());
        if (!cv.block(timeoutMs)) {
            fail("HandlerThread " + handlerThread.getName() +
                    " did not become idle after " + timeoutMs + " ms");
        }
    }

    @SmallTest
    public void testWaitForIdle() {
        final int attempts = 50;  // Causes the test to take about 200ms on bullhead-eng.

        // Tests that waitForIdle returns immediately if the service is already idle.
        for (int i = 0; i < attempts; i++) {
            mService.waitForIdle();
        }

        // Bring up a network that we can use to send messages to ConnectivityService.
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        Network n = mWiFiNetworkAgent.getNetwork();
        assertNotNull(n);

        // Tests that calling waitForIdle waits for messages to be processed.
        for (int i = 0; i < attempts; i++) {
            mWiFiNetworkAgent.setSignalStrength(i);
            mService.waitForIdle();
            assertEquals(i, mCm.getNetworkCapabilities(n).getSignalStrength());
        }
    }

    // This test has an inherent race condition in it, and cannot be enabled for continuous testing
    // or presubmit tests. It is kept for manual runs and documentation purposes.
    public void verifyThatNotWaitingForIdleCausesRaceConditions() {
        // Bring up a network that we can use to send messages to ConnectivityService.
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        Network n = mWiFiNetworkAgent.getNetwork();
        assertNotNull(n);

        // Ensure that not calling waitForIdle causes a race condition.
        final int attempts = 50;  // Causes the test to take about 200ms on bullhead-eng.
        for (int i = 0; i < attempts; i++) {
            mWiFiNetworkAgent.setSignalStrength(i);
            if (i != mCm.getNetworkCapabilities(n).getSignalStrength()) {
                // We hit a race condition, as expected. Pass the test.
                return;
            }
        }

        // No race? There is a bug in this test.
        fail("expected race condition at least once in " + attempts + " attempts");
    }

    private class MockNetworkAgent {
        private final WrappedNetworkMonitor mWrappedNetworkMonitor;
        private final NetworkInfo mNetworkInfo;
        private final NetworkCapabilities mNetworkCapabilities;
        private final HandlerThread mHandlerThread;
        private final ConditionVariable mDisconnected = new ConditionVariable();
        private final ConditionVariable mNetworkStatusReceived = new ConditionVariable();
        private final ConditionVariable mPreventReconnectReceived = new ConditionVariable();
        private int mScore;
        private NetworkAgent mNetworkAgent;
        private int mStartKeepaliveError = PacketKeepalive.ERROR_HARDWARE_UNSUPPORTED;
        private int mStopKeepaliveError = PacketKeepalive.NO_KEEPALIVE;
        private Integer mExpectedKeepaliveSlot = null;
        // Contains the redirectUrl from networkStatus(). Before reading, wait for
        // mNetworkStatusReceived.
        private String mRedirectUrl;

        MockNetworkAgent(int transport) {
            final int type = transportToLegacyType(transport);
            final String typeName = ConnectivityManager.getNetworkTypeName(type);
            mNetworkInfo = new NetworkInfo(type, 0, typeName, "Mock");
            mNetworkCapabilities = new NetworkCapabilities();
            mNetworkCapabilities.addTransportType(transport);
            switch (transport) {
                case TRANSPORT_ETHERNET:
                    mScore = 70;
                    break;
                case TRANSPORT_WIFI:
                    mScore = 60;
                    break;
                case TRANSPORT_CELLULAR:
                    mScore = 50;
                    break;
                default:
                    throw new UnsupportedOperationException("unimplemented network type");
            }
            mHandlerThread = new HandlerThread("Mock-" + typeName);
            mHandlerThread.start();
            mNetworkAgent = new NetworkAgent(mHandlerThread.getLooper(), mServiceContext,
                    "Mock-" + typeName, mNetworkInfo, mNetworkCapabilities,
                    new LinkProperties(), mScore, new NetworkMisc()) {
                @Override
                public void unwanted() { mDisconnected.open(); }

                @Override
                public void startPacketKeepalive(Message msg) {
                    int slot = msg.arg1;
                    if (mExpectedKeepaliveSlot != null) {
                        assertEquals((int) mExpectedKeepaliveSlot, slot);
                    }
                    onPacketKeepaliveEvent(slot, mStartKeepaliveError);
                }

                @Override
                public void stopPacketKeepalive(Message msg) {
                    onPacketKeepaliveEvent(msg.arg1, mStopKeepaliveError);
                }

                @Override
                public void networkStatus(int status, String redirectUrl) {
                    mRedirectUrl = redirectUrl;
                    mNetworkStatusReceived.open();
                }

                @Override
                protected void preventAutomaticReconnect() {
                    mPreventReconnectReceived.open();
                }
            };
            // Waits for the NetworkAgent to be registered, which includes the creation of the
            // NetworkMonitor.
            mService.waitForIdle();
            mWrappedNetworkMonitor = mService.getLastCreatedWrappedNetworkMonitor();
        }

        public void waitForIdle(int timeoutMs) {
            waitForIdleHandler(mHandlerThread, timeoutMs);
        }

        public void waitForIdle() {
            waitForIdle(TIMEOUT_MS);
        }

        public void adjustScore(int change) {
            mScore += change;
            mNetworkAgent.sendNetworkScore(mScore);
        }

        public void addCapability(int capability) {
            mNetworkCapabilities.addCapability(capability);
            mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
        }

        public void removeCapability(int capability) {
            mNetworkCapabilities.removeCapability(capability);
            mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
        }

        public void setSignalStrength(int signalStrength) {
            mNetworkCapabilities.setSignalStrength(signalStrength);
            mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
        }

        public void setNetworkSpecifier(NetworkSpecifier networkSpecifier) {
            mNetworkCapabilities.setNetworkSpecifier(networkSpecifier);
            mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
        }

        public void connectWithoutInternet() {
            mNetworkInfo.setDetailedState(DetailedState.CONNECTED, null, null);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }

        /**
         * Transition this NetworkAgent to CONNECTED state with NET_CAPABILITY_INTERNET.
         * @param validated Indicate if network should pretend to be validated.
         */
        public void connect(boolean validated) {
            assertEquals("MockNetworkAgents can only be connected once",
                    mNetworkInfo.getDetailedState(), DetailedState.IDLE);
            assertFalse(mNetworkCapabilities.hasCapability(NET_CAPABILITY_INTERNET));

            NetworkCallback callback = null;
            final ConditionVariable validatedCv = new ConditionVariable();
            if (validated) {
                mWrappedNetworkMonitor.gen204ProbeResult = 204;
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(mNetworkCapabilities.getTransportTypes()[0])
                        .build();
                callback = new NetworkCallback() {
                    public void onCapabilitiesChanged(Network network,
                            NetworkCapabilities networkCapabilities) {
                        if (network.equals(getNetwork()) &&
                            networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED)) {
                            validatedCv.open();
                        }
                    }
                };
                mCm.registerNetworkCallback(request, callback);
            }
            addCapability(NET_CAPABILITY_INTERNET);

            connectWithoutInternet();

            if (validated) {
                // Wait for network to validate.
                waitFor(validatedCv);
                mWrappedNetworkMonitor.gen204ProbeResult = 500;
            }

            if (callback != null) mCm.unregisterNetworkCallback(callback);
        }

        public void connectWithCaptivePortal(String redirectUrl) {
            mWrappedNetworkMonitor.gen204ProbeResult = 200;
            mWrappedNetworkMonitor.gen204ProbeRedirectUrl = redirectUrl;
            connect(false);
        }

        public void suspend() {
            mNetworkInfo.setDetailedState(DetailedState.SUSPENDED, null, null);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }

        public void disconnect() {
            mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED, null, null);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }

        public Network getNetwork() {
            return new Network(mNetworkAgent.netId);
        }

        public ConditionVariable getPreventReconnectReceived() {
            return mPreventReconnectReceived;
        }

        public ConditionVariable getDisconnectedCV() {
            return mDisconnected;
        }

        public WrappedNetworkMonitor getWrappedNetworkMonitor() {
            return mWrappedNetworkMonitor;
        }

        public void sendLinkProperties(LinkProperties lp) {
            mNetworkAgent.sendLinkProperties(lp);
        }

        public void setStartKeepaliveError(int error) {
            mStartKeepaliveError = error;
        }

        public void setStopKeepaliveError(int error) {
            mStopKeepaliveError = error;
        }

        public void setExpectedKeepaliveSlot(Integer slot) {
            mExpectedKeepaliveSlot = slot;
        }

        public String waitForRedirectUrl() {
            assertTrue(mNetworkStatusReceived.block(TIMEOUT_MS));
            return mRedirectUrl;
        }
    }

    /**
     * A NetworkFactory that allows tests to wait until any in-flight NetworkRequest add or remove
     * operations have been processed. Before ConnectivityService can add or remove any requests,
     * the factory must be told to expect those operations by calling expectAddRequests or
     * expectRemoveRequests.
     */
    private static class MockNetworkFactory extends NetworkFactory {
        private final ConditionVariable mNetworkStartedCV = new ConditionVariable();
        private final ConditionVariable mNetworkStoppedCV = new ConditionVariable();
        private final AtomicBoolean mNetworkStarted = new AtomicBoolean(false);

        // Used to expect that requests be removed or added on a separate thread, without sleeping.
        // Callers can call either expectAddRequests() or expectRemoveRequests() exactly once, then
        // cause some other thread to add or remove requests, then call waitForRequests(). We can
        // either expect requests to be added or removed, but not both, because CountDownLatch can
        // only count in one direction.
        private CountDownLatch mExpectations;

        // Whether we are currently expecting requests to be added or removed. Valid only if
        // mExpectations is non-null.
        private boolean mExpectingAdditions;

        public MockNetworkFactory(Looper looper, Context context, String logTag,
                NetworkCapabilities filter) {
            super(looper, context, logTag, filter);
        }

        public int getMyRequestCount() {
            return getRequestCount();
        }

        protected void startNetwork() {
            mNetworkStarted.set(true);
            mNetworkStartedCV.open();
        }

        protected void stopNetwork() {
            mNetworkStarted.set(false);
            mNetworkStoppedCV.open();
        }

        public boolean getMyStartRequested() {
            return mNetworkStarted.get();
        }

        public ConditionVariable getNetworkStartedCV() {
            mNetworkStartedCV.close();
            return mNetworkStartedCV;
        }

        public ConditionVariable getNetworkStoppedCV() {
            mNetworkStoppedCV.close();
            return mNetworkStoppedCV;
        }

        @Override
        protected void handleAddRequest(NetworkRequest request, int score) {
            // If we're expecting anything, we must be expecting additions.
            if (mExpectations != null && !mExpectingAdditions) {
                fail("Can't add requests while expecting requests to be removed");
            }

            // Add the request.
            super.handleAddRequest(request, score);

            // Reduce the number of request additions we're waiting for.
            if (mExpectingAdditions) {
                assertTrue("Added more requests than expected", mExpectations.getCount() > 0);
                mExpectations.countDown();
            }
        }

        @Override
        protected void handleRemoveRequest(NetworkRequest request) {
            // If we're expecting anything, we must be expecting removals.
            if (mExpectations != null && mExpectingAdditions) {
                fail("Can't remove requests while expecting requests to be added");
            }

            // Remove the request.
            super.handleRemoveRequest(request);

            // Reduce the number of request removals we're waiting for.
            if (!mExpectingAdditions) {
                assertTrue("Removed more requests than expected", mExpectations.getCount() > 0);
                mExpectations.countDown();
            }
        }

        private void assertNoExpectations() {
            if (mExpectations != null) {
                fail("Can't add expectation, " + mExpectations.getCount() + " already pending");
            }
        }

        // Expects that count requests will be added.
        public void expectAddRequests(final int count) {
            assertNoExpectations();
            mExpectingAdditions = true;
            mExpectations = new CountDownLatch(count);
        }

        // Expects that count requests will be removed.
        public void expectRemoveRequests(final int count) {
            assertNoExpectations();
            mExpectingAdditions = false;
            mExpectations = new CountDownLatch(count);
        }

        // Waits for the expected request additions or removals to happen within a timeout.
        public void waitForRequests() throws InterruptedException {
            assertNotNull("Nothing to wait for", mExpectations);
            mExpectations.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            final long count = mExpectations.getCount();
            final String msg = count + " requests still not " +
                    (mExpectingAdditions ? "added" : "removed") +
                    " after " + TIMEOUT_MS + " ms";
            assertEquals(msg, 0, count);
            mExpectations = null;
        }

        public void waitForNetworkRequests(final int count) throws InterruptedException {
            waitForRequests();
            assertEquals(count, getMyRequestCount());
        }
    }

    private class FakeWakeupMessage extends WakeupMessage {
        private static final int UNREASONABLY_LONG_WAIT = 1000;

        public FakeWakeupMessage(Context context, Handler handler, String cmdName, int cmd) {
            super(context, handler, cmdName, cmd);
        }

        public FakeWakeupMessage(Context context, Handler handler, String cmdName, int cmd,
                int arg1, int arg2, Object obj) {
            super(context, handler, cmdName, cmd, arg1, arg2, obj);
        }

        @Override
        public void schedule(long when) {
            long delayMs = when - SystemClock.elapsedRealtime();
            if (delayMs < 0) delayMs = 0;
            if (delayMs > UNREASONABLY_LONG_WAIT) {
                fail("Attempting to send msg more than " + UNREASONABLY_LONG_WAIT +
                        "ms into the future: " + delayMs);
            }
            Message msg = mHandler.obtainMessage(mCmd, mArg1, mArg2, mObj);
            mHandler.sendMessageDelayed(msg, delayMs);
        }

        @Override
        public void cancel() {
            mHandler.removeMessages(mCmd, mObj);
        }

        @Override
        public void onAlarm() {
            throw new AssertionError("Should never happen. Update this fake.");
        }
    }

    // NetworkMonitor implementation allowing overriding of Internet connectivity probe result.
    private class WrappedNetworkMonitor extends NetworkMonitor {
        // HTTP response code fed back to NetworkMonitor for Internet connectivity probe.
        public int gen204ProbeResult = 500;
        public String gen204ProbeRedirectUrl = null;

        public WrappedNetworkMonitor(Context context, Handler handler,
                NetworkAgentInfo networkAgentInfo, NetworkRequest defaultRequest,
                IpConnectivityLog log) {
            super(context, handler, networkAgentInfo, defaultRequest, log);
        }

        @Override
        protected CaptivePortalProbeResult isCaptivePortal() {
            if (!mIsCaptivePortalCheckEnabled) { return new CaptivePortalProbeResult(204); }
            return new CaptivePortalProbeResult(gen204ProbeResult, gen204ProbeRedirectUrl, null);
        }
    }

    private class WrappedMultinetworkPolicyTracker extends MultinetworkPolicyTracker {
        public volatile boolean configRestrictsAvoidBadWifi;
        public volatile int configMeteredMultipathPreference;

        public WrappedMultinetworkPolicyTracker(Context c, Handler h, Runnable r) {
            super(c, h, r);
        }

        @Override
        public boolean configRestrictsAvoidBadWifi() {
            return configRestrictsAvoidBadWifi;
        }

        @Override
        public int configMeteredMultipathPreference() {
            return configMeteredMultipathPreference;
        }
    }

    private class WrappedConnectivityService extends ConnectivityService {
        public WrappedMultinetworkPolicyTracker wrappedMultinetworkPolicyTracker;
        private WrappedNetworkMonitor mLastCreatedNetworkMonitor;
        private MockableSystemProperties mSystemProperties;

        public WrappedConnectivityService(Context context, INetworkManagementService netManager,
                INetworkStatsService statsService, INetworkPolicyManager policyManager,
                IpConnectivityLog log) {
            super(context, netManager, statsService, policyManager, log);
            mLingerDelayMs = TEST_LINGER_DELAY_MS;
        }

        @Override
        protected MockableSystemProperties getSystemProperties() {
            // Minimal approach to overriding system properties: let most calls fall through to real
            // device values, and only override ones values that are important to this test.
            mSystemProperties = spy(new MockableSystemProperties());
            when(mSystemProperties.getInt("net.tcp.default_init_rwnd", 0)).thenReturn(0);
            when(mSystemProperties.getBoolean("ro.radio.noril", false)).thenReturn(false);
            return mSystemProperties;
        }

        @Override
        protected int reserveNetId() {
            while (true) {
                final int netId = super.reserveNetId();

                // Don't overlap test NetIDs with real NetIDs as binding sockets to real networks
                // can have odd side-effects, like network validations succeeding.
                final Network[] networks = ConnectivityManager.from(getContext()).getAllNetworks();
                boolean overlaps = false;
                for (Network network : networks) {
                    if (netId == network.netId) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) continue;

                return netId;
            }
        }

        @Override
        public NetworkMonitor createNetworkMonitor(Context context, Handler handler,
                NetworkAgentInfo nai, NetworkRequest defaultRequest) {
            final WrappedNetworkMonitor monitor = new WrappedNetworkMonitor(
                    context, handler, nai, defaultRequest, mock(IpConnectivityLog.class));
            mLastCreatedNetworkMonitor = monitor;
            return monitor;
        }

        @Override
        public MultinetworkPolicyTracker createMultinetworkPolicyTracker(
                Context c, Handler h, Runnable r) {
            final WrappedMultinetworkPolicyTracker tracker = new WrappedMultinetworkPolicyTracker(c, h, r);
            return tracker;
        }

        public WrappedMultinetworkPolicyTracker getMultinetworkPolicyTracker() {
            return (WrappedMultinetworkPolicyTracker) mMultinetworkPolicyTracker;
        }

        @Override
        public WakeupMessage makeWakeupMessage(
                Context context, Handler handler, String cmdName, int cmd, Object obj) {
            return new FakeWakeupMessage(context, handler, cmdName, cmd, 0, 0, obj);
        }

        public WrappedNetworkMonitor getLastCreatedWrappedNetworkMonitor() {
            return mLastCreatedNetworkMonitor;
        }

        public void waitForIdle(int timeoutMs) {
            waitForIdleHandler(mHandlerThread, timeoutMs);
        }

        public void waitForIdle() {
            waitForIdle(TIMEOUT_MS);
        }
    }

    /**
     * Wait up to TIMEOUT_MS for {@code conditionVariable} to open.
     * Fails if TIMEOUT_MS goes by before {@code conditionVariable} opens.
     */
    static private void waitFor(ConditionVariable conditionVariable) {
        assertTrue(conditionVariable.block(TIMEOUT_MS));
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        // InstrumentationTestRunner prepares a looper, but AndroidJUnitRunner does not.
        // http://b/25897652 .
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mServiceContext = new MockContext(getContext());
        LocalServices.removeServiceForTest(NetworkPolicyManagerInternal.class);
        LocalServices.addService(
                NetworkPolicyManagerInternal.class, mock(NetworkPolicyManagerInternal.class));
        mService = new WrappedConnectivityService(mServiceContext,
                mock(INetworkManagementService.class),
                mock(INetworkStatsService.class),
                mock(INetworkPolicyManager.class),
                mock(IpConnectivityLog.class));

        mService.systemReady();
        mCm = new WrappedConnectivityManager(getContext(), mService);
        mCm.bindProcessToNetwork(null);

        // Ensure that the default setting for Captive Portals is used for most tests
        setCaptivePortalMode(Settings.Global.CAPTIVE_PORTAL_MODE_PROMPT);
    }

    public void tearDown() throws Exception {
        setMobileDataAlwaysOn(false);
        if (mCellNetworkAgent != null) { mCellNetworkAgent.disconnect(); }
        if (mWiFiNetworkAgent != null) { mWiFiNetworkAgent.disconnect(); }
        mCellNetworkAgent = mWiFiNetworkAgent = null;
        super.tearDown();
    }

    private int transportToLegacyType(int transport) {
        switch (transport) {
            case TRANSPORT_ETHERNET:
                return TYPE_ETHERNET;
            case TRANSPORT_WIFI:
                return TYPE_WIFI;
            case TRANSPORT_CELLULAR:
                return TYPE_MOBILE;
            default:
                throw new IllegalStateException("Unknown transport " + transport);
        }
    }

    private void verifyActiveNetwork(int transport) {
        // Test getActiveNetworkInfo()
        assertNotNull(mCm.getActiveNetworkInfo());
        assertEquals(transportToLegacyType(transport), mCm.getActiveNetworkInfo().getType());
        // Test getActiveNetwork()
        assertNotNull(mCm.getActiveNetwork());
        assertEquals(mCm.getActiveNetwork(), mCm.getActiveNetworkForUid(Process.myUid()));
        switch (transport) {
            case TRANSPORT_WIFI:
                assertEquals(mCm.getActiveNetwork(), mWiFiNetworkAgent.getNetwork());
                break;
            case TRANSPORT_CELLULAR:
                assertEquals(mCm.getActiveNetwork(), mCellNetworkAgent.getNetwork());
                break;
            default:
                throw new IllegalStateException("Unknown transport" + transport);
        }
        // Test getNetworkInfo(Network)
        assertNotNull(mCm.getNetworkInfo(mCm.getActiveNetwork()));
        assertEquals(transportToLegacyType(transport), mCm.getNetworkInfo(mCm.getActiveNetwork()).getType());
        // Test getNetworkCapabilities(Network)
        assertNotNull(mCm.getNetworkCapabilities(mCm.getActiveNetwork()));
        assertTrue(mCm.getNetworkCapabilities(mCm.getActiveNetwork()).hasTransport(transport));
    }

    private void verifyNoNetwork() {
        // Test getActiveNetworkInfo()
        assertNull(mCm.getActiveNetworkInfo());
        // Test getActiveNetwork()
        assertNull(mCm.getActiveNetwork());
        assertNull(mCm.getActiveNetworkForUid(Process.myUid()));
        // Test getAllNetworks()
        assertEquals(0, mCm.getAllNetworks().length);
    }

    /**
     * Return a ConditionVariable that opens when {@code count} numbers of CONNECTIVITY_ACTION
     * broadcasts are received.
     */
    private ConditionVariable waitForConnectivityBroadcasts(final int count) {
        final ConditionVariable cv = new ConditionVariable();
        mServiceContext.registerReceiver(new BroadcastReceiver() {
                    private int remaining = count;
                    public void onReceive(Context context, Intent intent) {
                        if (--remaining == 0) {
                            cv.open();
                            mServiceContext.unregisterReceiver(this);
                        }
                    }
                }, new IntentFilter(CONNECTIVITY_ACTION));
        return cv;
    }

    public void testNetworkTypes() {
        // Ensure that our mocks for the networkAttributes config variable work as expected. If they
        // don't, then tests that depend on CONNECTIVITY_ACTION broadcasts for these network types
        // will fail. Failing here is much easier to debug.
        assertTrue(mCm.isNetworkSupported(TYPE_WIFI));
        assertTrue(mCm.isNetworkSupported(TYPE_MOBILE));
    }

    @SmallTest
    public void testLingering() throws Exception {
        verifyNoNetwork();
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        assertNull(mCm.getActiveNetworkInfo());
        assertNull(mCm.getActiveNetwork());
        // Test bringing up validated cellular.
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        assertEquals(2, mCm.getAllNetworks().length);
        assertTrue(mCm.getAllNetworks()[0].equals(mCm.getActiveNetwork()) ||
                mCm.getAllNetworks()[1].equals(mCm.getActiveNetwork()));
        assertTrue(mCm.getAllNetworks()[0].equals(mWiFiNetworkAgent.getNetwork()) ||
                mCm.getAllNetworks()[1].equals(mWiFiNetworkAgent.getNetwork()));
        // Test bringing up validated WiFi.
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertEquals(2, mCm.getAllNetworks().length);
        assertTrue(mCm.getAllNetworks()[0].equals(mCm.getActiveNetwork()) ||
                mCm.getAllNetworks()[1].equals(mCm.getActiveNetwork()));
        assertTrue(mCm.getAllNetworks()[0].equals(mCellNetworkAgent.getNetwork()) ||
                mCm.getAllNetworks()[1].equals(mCellNetworkAgent.getNetwork()));
        // Test cellular linger timeout.
        waitFor(mCellNetworkAgent.getDisconnectedCV());
        mService.waitForIdle();
        assertEquals(1, mCm.getAllNetworks().length);
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertEquals(1, mCm.getAllNetworks().length);
        assertEquals(mCm.getAllNetworks()[0], mCm.getActiveNetwork());
        // Test WiFi disconnect.
        cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verifyNoNetwork();
    }

    @SmallTest
    public void testValidatedCellularOutscoresUnvalidatedWiFi() throws Exception {
        // Test bringing up unvalidated WiFi
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up unvalidated cellular
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false);
        mService.waitForIdle();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test cellular disconnect.
        mCellNetworkAgent.disconnect();
        mService.waitForIdle();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up validated cellular
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        cv = waitForConnectivityBroadcasts(2);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test cellular disconnect.
        cv = waitForConnectivityBroadcasts(2);
        mCellNetworkAgent.disconnect();
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi disconnect.
        cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verifyNoNetwork();
    }

    @SmallTest
    public void testUnvalidatedWifiOutscoresUnvalidatedCellular() throws Exception {
        // Test bringing up unvalidated cellular.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up unvalidated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi disconnect.
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test cellular disconnect.
        cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.disconnect();
        waitFor(cv);
        verifyNoNetwork();
    }

    @SmallTest
    public void testUnlingeringDoesNotValidate() throws Exception {
        // Test bringing up unvalidated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        // Test bringing up validated cellular.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        cv = waitForConnectivityBroadcasts(2);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        // Test cellular disconnect.
        cv = waitForConnectivityBroadcasts(2);
        mCellNetworkAgent.disconnect();
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Unlingering a network should not cause it to be marked as validated.
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
    }

    @SmallTest
    public void testCellularOutscoresWeakWifi() throws Exception {
        // Test bringing up validated cellular.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi getting really weak.
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.adjustScore(-11);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test WiFi restoring signal strength.
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.adjustScore(11);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
    }

    @SmallTest
    public void testReapingNetwork() throws Exception {
        // Test bringing up WiFi without NET_CAPABILITY_INTERNET.
        // Expect it to be torn down immediately because it satisfies no requests.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        ConditionVariable cv = mWiFiNetworkAgent.getDisconnectedCV();
        mWiFiNetworkAgent.connectWithoutInternet();
        waitFor(cv);
        // Test bringing up cellular without NET_CAPABILITY_INTERNET.
        // Expect it to be torn down immediately because it satisfies no requests.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = mCellNetworkAgent.getDisconnectedCV();
        mCellNetworkAgent.connectWithoutInternet();
        waitFor(cv);
        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up unvalidated cellular.
        // Expect it to be torn down because it could never be the highest scoring network
        // satisfying the default request even if it validated.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        cv = mCellNetworkAgent.getDisconnectedCV();
        mCellNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        cv = mWiFiNetworkAgent.getDisconnectedCV();
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
    }

    @SmallTest
    public void testCellularFallback() throws Exception {
        // Test bringing up validated cellular.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Reevaluate WiFi (it'll instantly fail DNS).
        cv = waitForConnectivityBroadcasts(2);
        assertTrue(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        mCm.reportBadNetwork(mWiFiNetworkAgent.getNetwork());
        // Should quickly fall back to Cellular.
        waitFor(cv);
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Reevaluate cellular (it'll instantly fail DNS).
        cv = waitForConnectivityBroadcasts(2);
        assertTrue(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        mCm.reportBadNetwork(mCellNetworkAgent.getNetwork());
        // Should quickly fall back to WiFi.
        waitFor(cv);
        assertFalse(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        verifyActiveNetwork(TRANSPORT_WIFI);
    }

    @SmallTest
    public void testWiFiFallback() throws Exception {
        // Test bringing up unvalidated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up validated cellular.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        cv = waitForConnectivityBroadcasts(2);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Reevaluate cellular (it'll instantly fail DNS).
        cv = waitForConnectivityBroadcasts(2);
        assertTrue(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        mCm.reportBadNetwork(mCellNetworkAgent.getNetwork());
        // Should quickly fall back to WiFi.
        waitFor(cv);
        assertFalse(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        verifyActiveNetwork(TRANSPORT_WIFI);
    }

    enum CallbackState {
        NONE,
        AVAILABLE,
        NETWORK_CAPABILITIES,
        LINK_PROPERTIES,
        SUSPENDED,
        LOSING,
        LOST,
        UNAVAILABLE
    }

    private static class CallbackInfo {
        public final CallbackState state;
        public final Network network;
        public final Object arg;
        public CallbackInfo(CallbackState s, Network n, Object o) {
            state = s; network = n; arg = o;
        }
        public String toString() {
            return String.format("%s (%s) (%s)", state, network, arg);
        }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof CallbackInfo)) return false;
            // Ignore timeMs, since it's unpredictable.
            CallbackInfo other = (CallbackInfo) o;
            return (state == other.state) && Objects.equals(network, other.network);
        }
        @Override
        public int hashCode() {
            return Objects.hash(state, network);
        }
    }

    /**
     * Utility NetworkCallback for testing. The caller must explicitly test for all the callbacks
     * this class receives, by calling expectCallback() exactly once each time a callback is
     * received. assertNoCallback may be called at any time.
     */
    private class TestNetworkCallback extends NetworkCallback {
        // Chosen to be much less than the linger timeout. This ensures that we can distinguish
        // between a LOST callback that arrives immediately and a LOST callback that arrives after
        // the linger timeout.
        private final static int TIMEOUT_MS = 100;

        private final LinkedBlockingQueue<CallbackInfo> mCallbacks = new LinkedBlockingQueue<>();

        protected void setLastCallback(CallbackState state, Network network, Object o) {
            mCallbacks.offer(new CallbackInfo(state, network, o));
        }

        @Override
        public void onAvailable(Network network) {
            setLastCallback(CallbackState.AVAILABLE, network, null);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities netCap) {
            setLastCallback(CallbackState.NETWORK_CAPABILITIES, network, netCap);
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties linkProp) {
            setLastCallback(CallbackState.LINK_PROPERTIES, network, linkProp);
        }

        @Override
        public void onUnavailable() {
            setLastCallback(CallbackState.UNAVAILABLE, null, null);
        }

        @Override
        public void onNetworkSuspended(Network network) {
            setLastCallback(CallbackState.SUSPENDED, network, null);
        }

        @Override
        public void onLosing(Network network, int maxMsToLive) {
            setLastCallback(CallbackState.LOSING, network, maxMsToLive /* autoboxed int */);
        }

        @Override
        public void onLost(Network network) {
            setLastCallback(CallbackState.LOST, network, null);
        }

        CallbackInfo nextCallback(int timeoutMs) {
            CallbackInfo cb = null;
            try {
                cb = mCallbacks.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
            }
            if (cb == null) {
                // LinkedBlockingQueue.poll() returns null if it timeouts.
                fail("Did not receive callback after " + timeoutMs + "ms");
            }
            return cb;
        }

        CallbackInfo expectCallback(CallbackState state, MockNetworkAgent agent, int timeoutMs) {
            final Network expectedNetwork = (agent != null) ? agent.getNetwork() : null;
            CallbackInfo expected = new CallbackInfo(state, expectedNetwork, 0);
            CallbackInfo actual = nextCallback(timeoutMs);
            assertEquals("Unexpected callback:", expected, actual);

            if (state == CallbackState.LOSING) {
                String msg = String.format(
                        "Invalid linger time value %d, must be between %d and %d",
                        actual.arg, 0, TEST_LINGER_DELAY_MS);
                int maxMsToLive = (Integer) actual.arg;
                assertTrue(msg, 0 <= maxMsToLive && maxMsToLive <= TEST_LINGER_DELAY_MS);
            }

            return actual;
        }

        CallbackInfo expectCallback(CallbackState state, MockNetworkAgent agent) {
            return expectCallback(state, agent, TIMEOUT_MS);
        }

        void expectAvailableCallbacks(MockNetworkAgent agent, boolean expectSuspended, int timeoutMs) {
            expectCallback(CallbackState.AVAILABLE, agent, timeoutMs);
            if (expectSuspended) {
                expectCallback(CallbackState.SUSPENDED, agent, timeoutMs);
            }
            expectCallback(CallbackState.NETWORK_CAPABILITIES, agent, timeoutMs);
            expectCallback(CallbackState.LINK_PROPERTIES, agent, timeoutMs);
        }

        void expectAvailableCallbacks(MockNetworkAgent agent) {
            expectAvailableCallbacks(agent, false, TIMEOUT_MS);
        }

        void expectAvailableAndSuspendedCallbacks(MockNetworkAgent agent) {
            expectAvailableCallbacks(agent, true, TIMEOUT_MS);
        }

        void expectAvailableAndValidatedCallbacks(MockNetworkAgent agent) {
            expectAvailableCallbacks(agent, false, TIMEOUT_MS);
            expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, agent);
        }

        void expectCapabilitiesWith(int capability, MockNetworkAgent agent) {
            CallbackInfo cbi = expectCallback(CallbackState.NETWORK_CAPABILITIES, agent);
            NetworkCapabilities nc = (NetworkCapabilities) cbi.arg;
            assertTrue(nc.hasCapability(capability));
        }

        void expectCapabilitiesWithout(int capability, MockNetworkAgent agent) {
            CallbackInfo cbi = expectCallback(CallbackState.NETWORK_CAPABILITIES, agent);
            NetworkCapabilities nc = (NetworkCapabilities) cbi.arg;
            assertFalse(nc.hasCapability(capability));
        }

        void assertNoCallback() {
            mService.waitForIdle();
            CallbackInfo c = mCallbacks.peek();
            assertNull("Unexpected callback: " + c, c);
        }
    }

    // Can't be part of TestNetworkCallback because "cannot be declared static; static methods can
    // only be declared in a static or top level type".
    static void assertNoCallbacks(TestNetworkCallback ... callbacks) {
        for (TestNetworkCallback c : callbacks) {
            c.assertNoCallback();
        }
    }

    @SmallTest
    public void testStateChangeNetworkCallbacks() throws Exception {
        final TestNetworkCallback genericNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback wifiNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest genericRequest = new NetworkRequest.Builder()
                .clearCapabilities().build();
        final NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI).build();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.registerNetworkCallback(genericRequest, genericNetworkCallback);
        mCm.registerNetworkCallback(wifiRequest, wifiNetworkCallback);
        mCm.registerNetworkCallback(cellRequest, cellNetworkCallback);

        // Test unvalidated networks
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false);
        genericNetworkCallback.expectAvailableCallbacks(mCellNetworkAgent);
        cellNetworkCallback.expectAvailableCallbacks(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        waitFor(cv);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        // This should not trigger spurious onAvailable() callbacks, b/21762680.
        mCellNetworkAgent.adjustScore(-1);
        mService.waitForIdle();
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        genericNetworkCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        wifiNetworkCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        waitFor(cv);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        wifiNetworkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        waitFor(cv);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        waitFor(cv);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        // Test validated networks
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        genericNetworkCallback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        cellNetworkCallback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        // This should not trigger spurious onAvailable() callbacks, b/21762680.
        mCellNetworkAgent.adjustScore(-1);
        mService.waitForIdle();
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        genericNetworkCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        genericNetworkCallback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        genericNetworkCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        wifiNetworkCallback.expectAvailableAndValidatedCallbacks(mWiFiNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        mWiFiNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        wifiNetworkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        mCellNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);
    }

    @SmallTest
    public void testMultipleLingering() {
        NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities().addCapability(NET_CAPABILITY_NOT_METERED)
                .build();
        TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mEthernetNetworkAgent = new MockNetworkAgent(TRANSPORT_ETHERNET);

        mCellNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mEthernetNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);

        mCellNetworkAgent.connect(true);
        callback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        defaultCallback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        mWiFiNetworkAgent.connect(true);
        // We get AVAILABLE on wifi when wifi connects and satisfies our unmetered request.
        // We then get LOSING when wifi validates and cell is outscored.
        callback.expectAvailableCallbacks(mWiFiNetworkAgent);
        // TODO: Investigate sending validated before losing.
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        defaultCallback.expectAvailableAndValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        mEthernetNetworkAgent.connect(true);
        callback.expectAvailableCallbacks(mEthernetNetworkAgent);
        // TODO: Investigate sending validated before losing.
        callback.expectCallback(CallbackState.LOSING, mWiFiNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mEthernetNetworkAgent);
        defaultCallback.expectAvailableAndValidatedCallbacks(mEthernetNetworkAgent);
        assertEquals(mEthernetNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        mEthernetNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mEthernetNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mEthernetNetworkAgent);
        defaultCallback.expectAvailableCallbacks(mWiFiNetworkAgent);

        for (int i = 0; i < 4; i++) {
            MockNetworkAgent oldNetwork, newNetwork;
            if (i % 2 == 0) {
                mWiFiNetworkAgent.adjustScore(-15);
                oldNetwork = mWiFiNetworkAgent;
                newNetwork = mCellNetworkAgent;
            } else {
                mWiFiNetworkAgent.adjustScore(15);
                oldNetwork = mCellNetworkAgent;
                newNetwork = mWiFiNetworkAgent;

            }
            callback.expectCallback(CallbackState.LOSING, oldNetwork);
            // TODO: should we send an AVAILABLE callback to newNetwork, to indicate that it is no
            // longer lingering?
            defaultCallback.expectAvailableCallbacks(newNetwork);
            assertEquals(newNetwork.getNetwork(), mCm.getActiveNetwork());
        }
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Verify that if a network no longer satisfies a request, we send LOST and not LOSING, even
        // if the network is still up.
        mWiFiNetworkAgent.removeCapability(NET_CAPABILITY_NOT_METERED);
        // We expect a notification about the capabilities change, and nothing else.
        defaultCallback.expectCapabilitiesWithout(NET_CAPABILITY_NOT_METERED, mWiFiNetworkAgent);
        defaultCallback.assertNoCallback();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // Wifi no longer satisfies our listen, which is for an unmetered network.
        // But because its score is 55, it's still up (and the default network).
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Disconnect our test networks.
        mWiFiNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacks(mCellNetworkAgent);
        mCellNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);

        mCm.unregisterNetworkCallback(callback);
        mService.waitForIdle();

        // Check that a network is only lingered or torn down if it would not satisfy a request even
        // if it validated.
        request = new NetworkRequest.Builder().clearCapabilities().build();
        callback = new TestNetworkCallback();

        mCm.registerNetworkCallback(request, callback);

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false);   // Score: 10
        callback.expectAvailableCallbacks(mCellNetworkAgent);
        defaultCallback.expectAvailableCallbacks(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Bring up wifi with a score of 20.
        // Cell stays up because it would satisfy the default request if it validated.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);   // Score: 20
        callback.expectAvailableCallbacks(mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacks(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Bring up wifi with a score of 70.
        // Cell is lingered because it would not satisfy any request, even if it validated.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.adjustScore(50);
        mWiFiNetworkAgent.connect(false);   // Score: 70
        callback.expectAvailableCallbacks(mWiFiNetworkAgent);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        defaultCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Tear down wifi.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacks(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Bring up wifi, then validate it. Previous versions would immediately tear down cell, but
        // it's arguably correct to linger it, since it was the default network before it validated.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        callback.expectAvailableCallbacks(mWiFiNetworkAgent);
        // TODO: Investigate sending validated before losing.
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        defaultCallback.expectAvailableAndValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacks(mCellNetworkAgent);
        mCellNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);

        // If a network is lingering, and we add and remove a request from it, resume lingering.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        callback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        defaultCallback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        defaultCallback.expectAvailableAndValidatedCallbacks(mWiFiNetworkAgent);
        callback.expectAvailableCallbacks(mWiFiNetworkAgent);
        // TODO: Investigate sending validated before losing.
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);

        NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        NetworkCallback noopCallback = new NetworkCallback();
        mCm.requestNetwork(cellRequest, noopCallback);
        // TODO: should this cause an AVAILABLE callback, to indicate that the network is no longer
        // lingering?
        mCm.unregisterNetworkCallback(noopCallback);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);

        // Similar to the above: lingering can start even after the lingered request is removed.
        // Disconnect wifi and switch to cell.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacks(mCellNetworkAgent);

        // Cell is now the default network. Pin it with a cell-specific request.
        noopCallback = new NetworkCallback();  // Can't reuse NetworkCallbacks. http://b/20701525
        mCm.requestNetwork(cellRequest, noopCallback);

        // Now connect wifi, and expect it to become the default network.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        callback.expectAvailableAndValidatedCallbacks(mWiFiNetworkAgent);
        defaultCallback.expectAvailableAndValidatedCallbacks(mWiFiNetworkAgent);
        // The default request is lingering on cell, but nothing happens to cell, and we send no
        // callbacks for it, because it's kept up by cellRequest.
        callback.assertNoCallback();
        // Now unregister cellRequest and expect cell to start lingering.
        mCm.unregisterNetworkCallback(noopCallback);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);

        // Let linger run its course.
        callback.assertNoCallback();
        final int lingerTimeoutMs = TEST_LINGER_DELAY_MS + TEST_LINGER_DELAY_MS / 4;
        callback.expectCallback(CallbackState.LOST, mCellNetworkAgent, lingerTimeoutMs);

        // Clean up.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        mCm.unregisterNetworkCallback(callback);
        mCm.unregisterNetworkCallback(defaultCallback);
    }

    private void tryNetworkFactoryRequests(int capability) throws Exception {
        // Verify NOT_RESTRICTED is set appropriately
        final NetworkCapabilities nc = new NetworkRequest.Builder().addCapability(capability)
                .build().networkCapabilities;
        if (capability == NET_CAPABILITY_CBS || capability == NET_CAPABILITY_DUN ||
                capability == NET_CAPABILITY_EIMS || capability == NET_CAPABILITY_FOTA ||
                capability == NET_CAPABILITY_IA || capability == NET_CAPABILITY_IMS ||
                capability == NET_CAPABILITY_RCS || capability == NET_CAPABILITY_XCAP) {
            assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED));
        } else {
            assertTrue(nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED));
        }

        NetworkCapabilities filter = new NetworkCapabilities();
        filter.addCapability(capability);
        final HandlerThread handlerThread = new HandlerThread("testNetworkFactoryRequests");
        handlerThread.start();
        final MockNetworkFactory testFactory = new MockNetworkFactory(handlerThread.getLooper(),
                mServiceContext, "testFactory", filter);
        testFactory.setScoreFilter(40);
        ConditionVariable cv = testFactory.getNetworkStartedCV();
        testFactory.expectAddRequests(1);
        testFactory.register();
        testFactory.waitForNetworkRequests(1);
        int expectedRequestCount = 1;
        NetworkCallback networkCallback = null;
        // For non-INTERNET capabilities we cannot rely on the default request being present, so
        // add one.
        if (capability != NET_CAPABILITY_INTERNET) {
            assertFalse(testFactory.getMyStartRequested());
            NetworkRequest request = new NetworkRequest.Builder().addCapability(capability).build();
            networkCallback = new NetworkCallback();
            testFactory.expectAddRequests(1);
            mCm.requestNetwork(request, networkCallback);
            expectedRequestCount++;
            testFactory.waitForNetworkRequests(expectedRequestCount);
        }
        waitFor(cv);
        assertEquals(expectedRequestCount, testFactory.getMyRequestCount());
        assertTrue(testFactory.getMyStartRequested());

        // Now bring in a higher scored network.
        MockNetworkAgent testAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        // Rather than create a validated network which complicates things by registering it's
        // own NetworkRequest during startup, just bump up the score to cancel out the
        // unvalidated penalty.
        testAgent.adjustScore(40);
        cv = testFactory.getNetworkStoppedCV();

        // When testAgent connects, ConnectivityService will re-send us all current requests with
        // the new score. There are expectedRequestCount such requests, and we must wait for all of
        // them.
        testFactory.expectAddRequests(expectedRequestCount);
        testAgent.connect(false);
        testAgent.addCapability(capability);
        waitFor(cv);
        testFactory.waitForNetworkRequests(expectedRequestCount);
        assertFalse(testFactory.getMyStartRequested());

        // Bring in a bunch of requests.
        testFactory.expectAddRequests(10);
        assertEquals(expectedRequestCount, testFactory.getMyRequestCount());
        ConnectivityManager.NetworkCallback[] networkCallbacks =
                new ConnectivityManager.NetworkCallback[10];
        for (int i = 0; i< networkCallbacks.length; i++) {
            networkCallbacks[i] = new ConnectivityManager.NetworkCallback();
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addCapability(capability);
            mCm.requestNetwork(builder.build(), networkCallbacks[i]);
        }
        testFactory.waitForNetworkRequests(10 + expectedRequestCount);
        assertFalse(testFactory.getMyStartRequested());

        // Remove the requests.
        testFactory.expectRemoveRequests(10);
        for (int i = 0; i < networkCallbacks.length; i++) {
            mCm.unregisterNetworkCallback(networkCallbacks[i]);
        }
        testFactory.waitForNetworkRequests(expectedRequestCount);
        assertFalse(testFactory.getMyStartRequested());

        // Drop the higher scored network.
        cv = testFactory.getNetworkStartedCV();
        testAgent.disconnect();
        waitFor(cv);
        assertEquals(expectedRequestCount, testFactory.getMyRequestCount());
        assertTrue(testFactory.getMyStartRequested());

        testFactory.unregister();
        if (networkCallback != null) mCm.unregisterNetworkCallback(networkCallback);
        handlerThread.quit();
    }

    @SmallTest
    public void testNetworkFactoryRequests() throws Exception {
        tryNetworkFactoryRequests(NET_CAPABILITY_MMS);
        tryNetworkFactoryRequests(NET_CAPABILITY_SUPL);
        tryNetworkFactoryRequests(NET_CAPABILITY_DUN);
        tryNetworkFactoryRequests(NET_CAPABILITY_FOTA);
        tryNetworkFactoryRequests(NET_CAPABILITY_IMS);
        tryNetworkFactoryRequests(NET_CAPABILITY_CBS);
        tryNetworkFactoryRequests(NET_CAPABILITY_WIFI_P2P);
        tryNetworkFactoryRequests(NET_CAPABILITY_IA);
        tryNetworkFactoryRequests(NET_CAPABILITY_RCS);
        tryNetworkFactoryRequests(NET_CAPABILITY_XCAP);
        tryNetworkFactoryRequests(NET_CAPABILITY_EIMS);
        tryNetworkFactoryRequests(NET_CAPABILITY_NOT_METERED);
        tryNetworkFactoryRequests(NET_CAPABILITY_INTERNET);
        tryNetworkFactoryRequests(NET_CAPABILITY_TRUSTED);
        tryNetworkFactoryRequests(NET_CAPABILITY_NOT_VPN);
        // Skipping VALIDATED and CAPTIVE_PORTAL as they're disallowed.
    }

    @SmallTest
    public void testNoMutableNetworkRequests() throws Exception {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, new Intent("a"), 0);
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NET_CAPABILITY_VALIDATED);
        try {
            mCm.requestNetwork(builder.build(), new NetworkCallback());
            fail();
        } catch (IllegalArgumentException expected) {}
        try {
            mCm.requestNetwork(builder.build(), pendingIntent);
            fail();
        } catch (IllegalArgumentException expected) {}
        builder = new NetworkRequest.Builder();
        builder.addCapability(NET_CAPABILITY_CAPTIVE_PORTAL);
        try {
            mCm.requestNetwork(builder.build(), new NetworkCallback());
            fail();
        } catch (IllegalArgumentException expected) {}
        try {
            mCm.requestNetwork(builder.build(), pendingIntent);
            fail();
        } catch (IllegalArgumentException expected) {}
    }

    @SmallTest
    public void testMMSonWiFi() throws Exception {
        // Test bringing up cellular without MMS NetworkRequest gets reaped
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.addCapability(NET_CAPABILITY_MMS);
        ConditionVariable cv = mCellNetworkAgent.getDisconnectedCV();
        mCellNetworkAgent.connectWithoutInternet();
        waitFor(cv);
        mService.waitForIdle();
        assertEquals(0, mCm.getAllNetworks().length);
        verifyNoNetwork();
        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Register MMS NetworkRequest
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.requestNetwork(builder.build(), networkCallback);
        // Test bringing up unvalidated cellular with MMS
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.addCapability(NET_CAPABILITY_MMS);
        mCellNetworkAgent.connectWithoutInternet();
        networkCallback.expectAvailableCallbacks(mCellNetworkAgent);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test releasing NetworkRequest disconnects cellular with MMS
        cv = mCellNetworkAgent.getDisconnectedCV();
        mCm.unregisterNetworkCallback(networkCallback);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
    }

    @SmallTest
    public void testMMSonCell() throws Exception {
        // Test bringing up cellular without MMS
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Register MMS NetworkRequest
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.requestNetwork(builder.build(), networkCallback);
        // Test bringing up MMS cellular network
        MockNetworkAgent mmsNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mmsNetworkAgent.addCapability(NET_CAPABILITY_MMS);
        mmsNetworkAgent.connectWithoutInternet();
        networkCallback.expectAvailableCallbacks(mmsNetworkAgent);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test releasing MMS NetworkRequest does not disconnect main cellular NetworkAgent
        cv = mmsNetworkAgent.getDisconnectedCV();
        mCm.unregisterNetworkCallback(networkCallback);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
    }

    @SmallTest
    public void testCaptivePortal() {
        final TestNetworkCallback captivePortalCallback = new TestNetworkCallback();
        final NetworkRequest captivePortalRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build();
        mCm.registerNetworkCallback(captivePortalRequest, captivePortalCallback);

        final TestNetworkCallback validatedCallback = new TestNetworkCallback();
        final NetworkRequest validatedRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_VALIDATED).build();
        mCm.registerNetworkCallback(validatedRequest, validatedCallback);

        // Bring up a network with a captive portal.
        // Expect onAvailable callback of listen for NET_CAPABILITY_CAPTIVE_PORTAL.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        String firstRedirectUrl = "http://example.com/firstPath";
        mWiFiNetworkAgent.connectWithCaptivePortal(firstRedirectUrl);
        captivePortalCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.waitForRedirectUrl(), firstRedirectUrl);

        // Take down network.
        // Expect onLost callback.
        mWiFiNetworkAgent.disconnect();
        captivePortalCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // Bring up a network with a captive portal.
        // Expect onAvailable callback of listen for NET_CAPABILITY_CAPTIVE_PORTAL.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        String secondRedirectUrl = "http://example.com/secondPath";
        mWiFiNetworkAgent.connectWithCaptivePortal(secondRedirectUrl);
        captivePortalCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.waitForRedirectUrl(), secondRedirectUrl);

        // Make captive portal disappear then revalidate.
        // Expect onLost callback because network no longer provides NET_CAPABILITY_CAPTIVE_PORTAL.
        mWiFiNetworkAgent.getWrappedNetworkMonitor().gen204ProbeResult = 204;
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), true);
        captivePortalCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // Expect NET_CAPABILITY_VALIDATED onAvailable callback.
        validatedCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        // TODO: Investigate only sending available callbacks.
        validatedCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);

        // Break network connectivity.
        // Expect NET_CAPABILITY_VALIDATED onLost callback.
        mWiFiNetworkAgent.getWrappedNetworkMonitor().gen204ProbeResult = 500;
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), false);
        validatedCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
    }

    @SmallTest
    public void testCaptivePortalApp() {
        final TestNetworkCallback captivePortalCallback = new TestNetworkCallback();
        final NetworkRequest captivePortalRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build();
        mCm.registerNetworkCallback(captivePortalRequest, captivePortalCallback);

        final TestNetworkCallback validatedCallback = new TestNetworkCallback();
        final NetworkRequest validatedRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_VALIDATED).build();
        mCm.registerNetworkCallback(validatedRequest, validatedCallback);

        // Bring up wifi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        validatedCallback.expectAvailableAndValidatedCallbacks(mWiFiNetworkAgent);
        Network wifiNetwork = mWiFiNetworkAgent.getNetwork();

        // Check that calling startCaptivePortalApp does nothing.
        final int fastTimeoutMs = 100;
        mCm.startCaptivePortalApp(wifiNetwork);
        mServiceContext.expectNoStartActivityIntent(fastTimeoutMs);

        // Turn into a captive portal.
        mWiFiNetworkAgent.getWrappedNetworkMonitor().gen204ProbeResult = 302;
        mCm.reportNetworkConnectivity(wifiNetwork, false);
        captivePortalCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        validatedCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // Check that startCaptivePortalApp sends the expected intent.
        mCm.startCaptivePortalApp(wifiNetwork);
        Intent intent = mServiceContext.expectStartActivityIntent(TIMEOUT_MS);
        assertEquals(ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN, intent.getAction());
        assertEquals(wifiNetwork, intent.getExtra(ConnectivityManager.EXTRA_NETWORK));

        // Have the app report that the captive portal is dismissed, and check that we revalidate.
        mWiFiNetworkAgent.getWrappedNetworkMonitor().gen204ProbeResult = 204;
        CaptivePortal c = (CaptivePortal) intent.getExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL);
        c.reportCaptivePortalDismissed();
        validatedCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        captivePortalCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        mCm.unregisterNetworkCallback(validatedCallback);
        mCm.unregisterNetworkCallback(captivePortalCallback);
    }

    @SmallTest
    public void testAvoidOrIgnoreCaptivePortals() {
        final TestNetworkCallback captivePortalCallback = new TestNetworkCallback();
        final NetworkRequest captivePortalRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build();
        mCm.registerNetworkCallback(captivePortalRequest, captivePortalCallback);

        final TestNetworkCallback validatedCallback = new TestNetworkCallback();
        final NetworkRequest validatedRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_VALIDATED).build();
        mCm.registerNetworkCallback(validatedRequest, validatedCallback);

        setCaptivePortalMode(Settings.Global.CAPTIVE_PORTAL_MODE_AVOID);
        // Bring up a network with a captive portal.
        // Expect it to fail to connect and not result in any callbacks.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        String firstRedirectUrl = "http://example.com/firstPath";

        ConditionVariable disconnectCv = mWiFiNetworkAgent.getDisconnectedCV();
        ConditionVariable avoidCv = mWiFiNetworkAgent.getPreventReconnectReceived();
        mWiFiNetworkAgent.connectWithCaptivePortal(firstRedirectUrl);
        waitFor(disconnectCv);
        waitFor(avoidCv);

        assertNoCallbacks(captivePortalCallback, validatedCallback);

        // Now test ignore mode.
        setCaptivePortalMode(Settings.Global.CAPTIVE_PORTAL_MODE_IGNORE);

        // Bring up a network with a captive portal.
        // Since we're ignoring captive portals, the network will validate.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        String secondRedirectUrl = "http://example.com/secondPath";
        mWiFiNetworkAgent.connectWithCaptivePortal(secondRedirectUrl);

        // Expect NET_CAPABILITY_VALIDATED onAvailable callback.
        validatedCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        // But there should be no CaptivePortal callback.
        captivePortalCallback.assertNoCallback();
    }

    private NetworkRequest.Builder newWifiRequestBuilder() {
        return new NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI);
    }

    @SmallTest
    public void testNetworkSpecifier() {
        NetworkRequest rEmpty1 = newWifiRequestBuilder().build();
        NetworkRequest rEmpty2 = newWifiRequestBuilder().setNetworkSpecifier((String) null).build();
        NetworkRequest rEmpty3 = newWifiRequestBuilder().setNetworkSpecifier("").build();
        NetworkRequest rEmpty4 = newWifiRequestBuilder().setNetworkSpecifier(
            (NetworkSpecifier) null).build();
        NetworkRequest rFoo = newWifiRequestBuilder().setNetworkSpecifier("foo").build();
        NetworkRequest rBar = newWifiRequestBuilder().setNetworkSpecifier(
                new StringNetworkSpecifier("bar")).build();

        TestNetworkCallback cEmpty1 = new TestNetworkCallback();
        TestNetworkCallback cEmpty2 = new TestNetworkCallback();
        TestNetworkCallback cEmpty3 = new TestNetworkCallback();
        TestNetworkCallback cEmpty4 = new TestNetworkCallback();
        TestNetworkCallback cFoo = new TestNetworkCallback();
        TestNetworkCallback cBar = new TestNetworkCallback();
        TestNetworkCallback[] emptyCallbacks = new TestNetworkCallback[] {
                cEmpty1, cEmpty2, cEmpty3 };

        mCm.registerNetworkCallback(rEmpty1, cEmpty1);
        mCm.registerNetworkCallback(rEmpty2, cEmpty2);
        mCm.registerNetworkCallback(rEmpty3, cEmpty3);
        mCm.registerNetworkCallback(rEmpty4, cEmpty4);
        mCm.registerNetworkCallback(rFoo, cFoo);
        mCm.registerNetworkCallback(rBar, cBar);

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        cEmpty1.expectAvailableCallbacks(mWiFiNetworkAgent);
        cEmpty2.expectAvailableCallbacks(mWiFiNetworkAgent);
        cEmpty3.expectAvailableCallbacks(mWiFiNetworkAgent);
        cEmpty4.expectAvailableCallbacks(mWiFiNetworkAgent);
        assertNoCallbacks(cFoo, cBar);

        mWiFiNetworkAgent.setNetworkSpecifier(new StringNetworkSpecifier("foo"));
        cFoo.expectAvailableCallbacks(mWiFiNetworkAgent);
        for (TestNetworkCallback c: emptyCallbacks) {
            c.expectCallback(CallbackState.NETWORK_CAPABILITIES, mWiFiNetworkAgent);
        }
        cFoo.expectCallback(CallbackState.NETWORK_CAPABILITIES, mWiFiNetworkAgent);
        cFoo.assertNoCallback();

        mWiFiNetworkAgent.setNetworkSpecifier(new StringNetworkSpecifier("bar"));
        cFoo.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        cBar.expectAvailableCallbacks(mWiFiNetworkAgent);
        for (TestNetworkCallback c: emptyCallbacks) {
            c.expectCallback(CallbackState.NETWORK_CAPABILITIES, mWiFiNetworkAgent);
        }
        cBar.expectCallback(CallbackState.NETWORK_CAPABILITIES, mWiFiNetworkAgent);
        cBar.assertNoCallback();

        mWiFiNetworkAgent.setNetworkSpecifier(null);
        cBar.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        for (TestNetworkCallback c: emptyCallbacks) {
            c.expectCallback(CallbackState.NETWORK_CAPABILITIES, mWiFiNetworkAgent);
        }

        assertNoCallbacks(cEmpty1, cEmpty2, cEmpty3, cFoo, cBar);
    }

    @SmallTest
    public void testInvalidNetworkSpecifier() {
        try {
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.setNetworkSpecifier(new MatchAllNetworkSpecifier());
            fail("NetworkRequest builder with MatchAllNetworkSpecifier");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            NetworkCapabilities networkCapabilities = new NetworkCapabilities();
            networkCapabilities.addTransportType(TRANSPORT_WIFI)
                    .setNetworkSpecifier(new MatchAllNetworkSpecifier());
            mService.requestNetwork(networkCapabilities, null, 0, null,
                    ConnectivityManager.TYPE_WIFI);
            fail("ConnectivityService requestNetwork with MatchAllNetworkSpecifier");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        class NonParcelableSpecifier extends NetworkSpecifier {
            public boolean satisfiedBy(NetworkSpecifier other) { return false; }
        };
        class ParcelableSpecifier extends NonParcelableSpecifier implements Parcelable {
            @Override public int describeContents() { return 0; }
            @Override public void writeToParcel(Parcel p, int flags) {}
        }
        NetworkRequest.Builder builder;

        builder = new NetworkRequest.Builder().addTransportType(TRANSPORT_ETHERNET);
        try {
            builder.setNetworkSpecifier(new NonParcelableSpecifier());
            Parcel parcelW = Parcel.obtain();
            builder.build().writeToParcel(parcelW, 0);
            fail("Parceling a non-parcelable specifier did not throw an exception");
        } catch (Exception e) {
            // expected
        }

        builder = new NetworkRequest.Builder().addTransportType(TRANSPORT_ETHERNET);
        builder.setNetworkSpecifier(new ParcelableSpecifier());
        NetworkRequest nr = builder.build();
        assertNotNull(nr);

        try {
            Parcel parcelW = Parcel.obtain();
            nr.writeToParcel(parcelW, 0);
            byte[] bytes = parcelW.marshall();
            parcelW.recycle();

            Parcel parcelR = Parcel.obtain();
            parcelR.unmarshall(bytes, 0, bytes.length);
            parcelR.setDataPosition(0);
            NetworkRequest rereadNr = NetworkRequest.CREATOR.createFromParcel(parcelR);
            fail("Unparceling a non-framework NetworkSpecifier did not throw an exception");
        } catch (Exception e) {
            // expected
        }
    }

    @SmallTest
    public void testRegisterDefaultNetworkCallback() throws Exception {
        final TestNetworkCallback defaultNetworkCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultNetworkCallback);
        defaultNetworkCallback.assertNoCallback();

        // Create a TRANSPORT_CELLULAR request to keep the mobile interface up
        // whenever Wi-Fi is up. Without this, the mobile network agent is
        // reaped before any other activity can take place.
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.requestNetwork(cellRequest, cellNetworkCallback);
        cellNetworkCallback.assertNoCallback();

        // Bring up cell and expect CALLBACK_AVAILABLE.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        defaultNetworkCallback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);

        // Bring up wifi and expect CALLBACK_AVAILABLE.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        cellNetworkCallback.assertNoCallback();
        defaultNetworkCallback.expectAvailableAndValidatedCallbacks(mWiFiNetworkAgent);

        // Bring down cell. Expect no default network callback, since it wasn't the default.
        mCellNetworkAgent.disconnect();
        cellNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        defaultNetworkCallback.assertNoCallback();

        // Bring up cell. Expect no default network callback, since it won't be the default.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        defaultNetworkCallback.assertNoCallback();

        // Bring down wifi. Expect the default network callback to notified of LOST wifi
        // followed by AVAILABLE cell.
        mWiFiNetworkAgent.disconnect();
        cellNetworkCallback.assertNoCallback();
        defaultNetworkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultNetworkCallback.expectAvailableCallbacks(mCellNetworkAgent);
        mCellNetworkAgent.disconnect();
        cellNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        defaultNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
    }

    @SmallTest
    public void testAdditionalStateCallbacks() throws Exception {
        // File a network request for mobile.
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.requestNetwork(cellRequest, cellNetworkCallback);

        // Bring up the mobile network.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);

        // We should get onAvailable(), onCapabilitiesChanged(), and
        // onLinkPropertiesChanged() in rapid succession. Additionally, we
        // should get onCapabilitiesChanged() when the mobile network validates.
        cellNetworkCallback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();

        // Update LinkProperties.
        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("foonet_data0");
        mCellNetworkAgent.sendLinkProperties(lp);
        // We should get onLinkPropertiesChanged().
        cellNetworkCallback.expectCallback(CallbackState.LINK_PROPERTIES, mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();

        // Suspend the network.
        mCellNetworkAgent.suspend();
        cellNetworkCallback.expectCallback(CallbackState.SUSPENDED, mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();

        // Register a garden variety default network request.
        final TestNetworkCallback dfltNetworkCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(dfltNetworkCallback);
        // We should get onAvailable(), onCapabilitiesChanged(), onLinkPropertiesChanged(),
        // as well as onNetworkSuspended() in rapid succession.
        dfltNetworkCallback.expectAvailableAndSuspendedCallbacks(mCellNetworkAgent);
        dfltNetworkCallback.assertNoCallback();

        mCm.unregisterNetworkCallback(dfltNetworkCallback);
        mCm.unregisterNetworkCallback(cellNetworkCallback);
    }

    private void setCaptivePortalMode(int mode) {
        ContentResolver cr = mServiceContext.getContentResolver();
        Settings.Global.putInt(cr, Settings.Global.CAPTIVE_PORTAL_MODE, mode);
    }

    private void setMobileDataAlwaysOn(boolean enable) {
        ContentResolver cr = mServiceContext.getContentResolver();
        Settings.Global.putInt(cr, Settings.Global.MOBILE_DATA_ALWAYS_ON, enable ? 1 : 0);
        mService.updateMobileDataAlwaysOn();
        mService.waitForIdle();
    }

    private boolean isForegroundNetwork(MockNetworkAgent network) {
        NetworkCapabilities nc = mCm.getNetworkCapabilities(network.getNetwork());
        assertNotNull(nc);
        return nc.hasCapability(NET_CAPABILITY_FOREGROUND);
    }

    @SmallTest
    public void testBackgroundNetworks() throws Exception {
        // Create a background request. We can't do this ourselves because ConnectivityService
        // doesn't have an API for it. So just turn on mobile data always on.
        setMobileDataAlwaysOn(true);
        final NetworkRequest request = new NetworkRequest.Builder().build();
        final NetworkRequest fgRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_FOREGROUND).build();
        final TestNetworkCallback callback = new TestNetworkCallback();
        final TestNetworkCallback fgCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);
        mCm.registerNetworkCallback(fgRequest, fgCallback);

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        callback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        fgCallback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        assertTrue(isForegroundNetwork(mCellNetworkAgent));

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);

        // When wifi connects, cell lingers.
        callback.expectAvailableCallbacks(mWiFiNetworkAgent);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        fgCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        fgCallback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        fgCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        assertTrue(isForegroundNetwork(mCellNetworkAgent));
        assertTrue(isForegroundNetwork(mWiFiNetworkAgent));

        // When lingering is complete, cell is still there but is now in the background.
        mService.waitForIdle();
        int timeoutMs = TEST_LINGER_DELAY_MS + TEST_LINGER_DELAY_MS / 4;
        fgCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent, timeoutMs);
        // Expect a network capabilities update sans FOREGROUND.
        callback.expectCapabilitiesWithout(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent);
        assertFalse(isForegroundNetwork(mCellNetworkAgent));
        assertTrue(isForegroundNetwork(mWiFiNetworkAgent));

        // File a cell request and check that cell comes into the foreground.
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        final TestNetworkCallback cellCallback = new TestNetworkCallback();
        mCm.requestNetwork(cellRequest, cellCallback);
        // NOTE: This request causes the network's capabilities to change. This
        // is currently delivered before the onAvailable() callbacks.
        // TODO: Fix this.
        cellCallback.expectCapabilitiesWith(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent);
        cellCallback.expectAvailableCallbacks(mCellNetworkAgent);
        fgCallback.expectAvailableCallbacks(mCellNetworkAgent);
        // Expect a network capabilities update with FOREGROUND, because the most recent
        // request causes its state to change.
        callback.expectCapabilitiesWith(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent);
        assertTrue(isForegroundNetwork(mCellNetworkAgent));
        assertTrue(isForegroundNetwork(mWiFiNetworkAgent));

        // Release the request. The network immediately goes into the background, since it was not
        // lingering.
        mCm.unregisterNetworkCallback(cellCallback);
        fgCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        // Expect a network capabilities update sans FOREGROUND.
        callback.expectCapabilitiesWithout(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent);
        assertFalse(isForegroundNetwork(mCellNetworkAgent));
        assertTrue(isForegroundNetwork(mWiFiNetworkAgent));

        // Disconnect wifi and check that cell is foreground again.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        fgCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        fgCallback.expectAvailableCallbacks(mCellNetworkAgent);
        assertTrue(isForegroundNetwork(mCellNetworkAgent));

        mCm.unregisterNetworkCallback(callback);
        mCm.unregisterNetworkCallback(fgCallback);
    }

    @SmallTest
    public void testRequestBenchmark() throws Exception {
        // TODO: turn this unit test into a real benchmarking test.
        // Benchmarks connecting and switching performance in the presence of a large number of
        // NetworkRequests.
        // 1. File NUM_REQUESTS requests.
        // 2. Have a network connect. Wait for NUM_REQUESTS onAvailable callbacks to fire.
        // 3. Have a new network connect and outscore the previous. Wait for NUM_REQUESTS onLosing
        //    and NUM_REQUESTS onAvailable callbacks to fire.
        // See how long it took.
        final int NUM_REQUESTS = 90;
        final NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        final NetworkCallback[] callbacks = new NetworkCallback[NUM_REQUESTS];
        final CountDownLatch availableLatch = new CountDownLatch(NUM_REQUESTS);
        final CountDownLatch losingLatch = new CountDownLatch(NUM_REQUESTS);

        for (int i = 0; i < NUM_REQUESTS; i++) {
            callbacks[i] = new NetworkCallback() {
                @Override public void onAvailable(Network n) { availableLatch.countDown(); }
                @Override public void onLosing(Network n, int t) { losingLatch.countDown(); }
            };
        }

        final int REGISTER_TIME_LIMIT_MS = 180;
        assertTimeLimit("Registering callbacks", REGISTER_TIME_LIMIT_MS, () -> {
            for (NetworkCallback cb : callbacks) {
                mCm.registerNetworkCallback(request, cb);
            }
        });

        final int CONNECT_TIME_LIMIT_MS = 40;
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        // Don't request that the network validate, because otherwise connect() will block until
        // the network gets NET_CAPABILITY_VALIDATED, after all the callbacks below have fired,
        // and we won't actually measure anything.
        mCellNetworkAgent.connect(false);

        long onAvailableDispatchingDuration = durationOf(() -> {
            if (!awaitLatch(availableLatch, CONNECT_TIME_LIMIT_MS)) {
                fail(String.format("Only dispatched %d/%d onAvailable callbacks in %dms",
                        NUM_REQUESTS - availableLatch.getCount(), NUM_REQUESTS,
                        CONNECT_TIME_LIMIT_MS));
            }
        });
        Log.d(TAG, String.format("Connect, %d callbacks: %dms, acceptable %dms",
                NUM_REQUESTS, onAvailableDispatchingDuration, CONNECT_TIME_LIMIT_MS));

        final int SWITCH_TIME_LIMIT_MS = 40;
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        // Give wifi a high enough score that we'll linger cell when wifi comes up.
        mWiFiNetworkAgent.adjustScore(40);
        mWiFiNetworkAgent.connect(false);

        long onLostDispatchingDuration = durationOf(() -> {
            if (!awaitLatch(losingLatch, SWITCH_TIME_LIMIT_MS)) {
                fail(String.format("Only dispatched %d/%d onLosing callbacks in %dms",
                        NUM_REQUESTS - losingLatch.getCount(), NUM_REQUESTS, SWITCH_TIME_LIMIT_MS));
            }
        });
        Log.d(TAG, String.format("Linger, %d callbacks: %dms, acceptable %dms",
                NUM_REQUESTS, onLostDispatchingDuration, SWITCH_TIME_LIMIT_MS));

        final int UNREGISTER_TIME_LIMIT_MS = 10;
        assertTimeLimit("Unregistering callbacks", UNREGISTER_TIME_LIMIT_MS, () -> {
            for (NetworkCallback cb : callbacks) {
                mCm.unregisterNetworkCallback(cb);
            }
        });
    }

    private long durationOf(Runnable fn) {
        long startTime = SystemClock.elapsedRealtime();
        fn.run();
        return SystemClock.elapsedRealtime() - startTime;
    }

    private void assertTimeLimit(String descr, long timeLimit, Runnable fn) {
        long timeTaken = durationOf(fn);
        String msg = String.format("%s: took %dms, limit was %dms", descr, timeTaken, timeLimit);
        Log.d(TAG, msg);
        assertTrue(msg, timeTaken <= timeLimit);
    }

    private boolean awaitLatch(CountDownLatch l, long timeoutMs) {
        try {
            if (l.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {}
        return false;
    }

    @SmallTest
    public void testMobileDataAlwaysOn() throws Exception {
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.registerNetworkCallback(cellRequest, cellNetworkCallback);

        final HandlerThread handlerThread = new HandlerThread("MobileDataAlwaysOnFactory");
        handlerThread.start();
        NetworkCapabilities filter = new NetworkCapabilities()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET);
        final MockNetworkFactory testFactory = new MockNetworkFactory(handlerThread.getLooper(),
                mServiceContext, "testFactory", filter);
        testFactory.setScoreFilter(40);

        // Register the factory and expect it to start looking for a network.
        testFactory.expectAddRequests(1);
        testFactory.register();
        testFactory.waitForNetworkRequests(1);
        assertTrue(testFactory.getMyStartRequested());

        // Bring up wifi. The factory stops looking for a network.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        testFactory.expectAddRequests(2);  // Because the default request changes score twice.
        mWiFiNetworkAgent.connect(true);
        testFactory.waitForNetworkRequests(1);
        assertFalse(testFactory.getMyStartRequested());

        ContentResolver cr = mServiceContext.getContentResolver();

        // Turn on mobile data always on. The factory starts looking again.
        testFactory.expectAddRequests(1);
        setMobileDataAlwaysOn(true);
        testFactory.waitForNetworkRequests(2);
        assertTrue(testFactory.getMyStartRequested());

        // Bring up cell data and check that the factory stops looking.
        assertEquals(1, mCm.getAllNetworks().length);
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        testFactory.expectAddRequests(2);  // Because the cell request changes score twice.
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        testFactory.waitForNetworkRequests(2);
        assertFalse(testFactory.getMyStartRequested());  // Because the cell network outscores us.

        // Check that cell data stays up.
        mService.waitForIdle();
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertEquals(2, mCm.getAllNetworks().length);

        // Turn off mobile data always on and expect the request to disappear...
        testFactory.expectRemoveRequests(1);
        setMobileDataAlwaysOn(false);
        testFactory.waitForNetworkRequests(1);

        // ...  and cell data to be torn down.
        cellNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        assertEquals(1, mCm.getAllNetworks().length);

        testFactory.unregister();
        mCm.unregisterNetworkCallback(cellNetworkCallback);
        handlerThread.quit();
    }

    @SmallTest
    public void testAvoidBadWifiSetting() throws Exception {
        final ContentResolver cr = mServiceContext.getContentResolver();
        final WrappedMultinetworkPolicyTracker tracker = mService.getMultinetworkPolicyTracker();
        final String settingName = Settings.Global.NETWORK_AVOID_BAD_WIFI;

        tracker.configRestrictsAvoidBadWifi = false;
        String[] values = new String[] {null, "0", "1"};
        for (int i = 0; i < values.length; i++) {
            Settings.Global.putInt(cr, settingName, 1);
            tracker.reevaluate();
            mService.waitForIdle();
            String msg = String.format("config=false, setting=%s", values[i]);
            assertTrue(mService.avoidBadWifi());
            assertFalse(msg, tracker.shouldNotifyWifiUnvalidated());
        }

        tracker.configRestrictsAvoidBadWifi = true;

        Settings.Global.putInt(cr, settingName, 0);
        tracker.reevaluate();
        mService.waitForIdle();
        assertFalse(mService.avoidBadWifi());
        assertFalse(tracker.shouldNotifyWifiUnvalidated());

        Settings.Global.putInt(cr, settingName, 1);
        tracker.reevaluate();
        mService.waitForIdle();
        assertTrue(mService.avoidBadWifi());
        assertFalse(tracker.shouldNotifyWifiUnvalidated());

        Settings.Global.putString(cr, settingName, null);
        tracker.reevaluate();
        mService.waitForIdle();
        assertFalse(mService.avoidBadWifi());
        assertTrue(tracker.shouldNotifyWifiUnvalidated());
    }

    @SmallTest
    public void testAvoidBadWifi() throws Exception {
        final ContentResolver cr = mServiceContext.getContentResolver();
        final WrappedMultinetworkPolicyTracker tracker = mService.getMultinetworkPolicyTracker();

        // Pretend we're on a carrier that restricts switching away from bad wifi.
        tracker.configRestrictsAvoidBadWifi = true;

        // File a request for cell to ensure it doesn't go down.
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.requestNetwork(cellRequest, cellNetworkCallback);

        TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        NetworkRequest validatedWifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_VALIDATED)
                .build();
        TestNetworkCallback validatedWifiCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(validatedWifiRequest, validatedWifiCallback);

        Settings.Global.putInt(cr, Settings.Global.NETWORK_AVOID_BAD_WIFI, 0);
        tracker.reevaluate();

        // Bring up validated cell.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        defaultCallback.expectAvailableAndValidatedCallbacks(mCellNetworkAgent);
        Network cellNetwork = mCellNetworkAgent.getNetwork();

        // Bring up validated wifi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        defaultCallback.expectAvailableAndValidatedCallbacks(mWiFiNetworkAgent);
        validatedWifiCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        validatedWifiCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        Network wifiNetwork = mWiFiNetworkAgent.getNetwork();

        // Fail validation on wifi.
        mWiFiNetworkAgent.getWrappedNetworkMonitor().gen204ProbeResult = 599;
        mCm.reportNetworkConnectivity(wifiNetwork, false);
        defaultCallback.expectCapabilitiesWithout(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        validatedWifiCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // Because avoid bad wifi is off, we don't switch to cellular.
        defaultCallback.assertNoCallback();
        assertFalse(mCm.getNetworkCapabilities(wifiNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertTrue(mCm.getNetworkCapabilities(cellNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertEquals(mCm.getActiveNetwork(), wifiNetwork);

        // Simulate switching to a carrier that does not restrict avoiding bad wifi, and expect
        // that we switch back to cell.
        tracker.configRestrictsAvoidBadWifi = false;
        tracker.reevaluate();
        defaultCallback.expectAvailableCallbacks(mCellNetworkAgent);
        assertEquals(mCm.getActiveNetwork(), cellNetwork);

        // Switch back to a restrictive carrier.
        tracker.configRestrictsAvoidBadWifi = true;
        tracker.reevaluate();
        defaultCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        assertEquals(mCm.getActiveNetwork(), wifiNetwork);

        // Simulate the user selecting "switch" on the dialog, and check that we switch to cell.
        mCm.setAvoidUnvalidated(wifiNetwork);
        defaultCallback.expectAvailableCallbacks(mCellNetworkAgent);
        assertFalse(mCm.getNetworkCapabilities(wifiNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertTrue(mCm.getNetworkCapabilities(cellNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertEquals(mCm.getActiveNetwork(), cellNetwork);

        // Disconnect and reconnect wifi to clear the one-time switch above.
        mWiFiNetworkAgent.disconnect();
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        defaultCallback.expectAvailableAndValidatedCallbacks(mWiFiNetworkAgent);
        validatedWifiCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        validatedWifiCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        wifiNetwork = mWiFiNetworkAgent.getNetwork();

        // Fail validation on wifi and expect the dialog to appear.
        mWiFiNetworkAgent.getWrappedNetworkMonitor().gen204ProbeResult = 599;
        mCm.reportNetworkConnectivity(wifiNetwork, false);
        defaultCallback.expectCapabilitiesWithout(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        validatedWifiCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // Simulate the user selecting "switch" and checking the don't ask again checkbox.
        Settings.Global.putInt(cr, Settings.Global.NETWORK_AVOID_BAD_WIFI, 1);
        tracker.reevaluate();

        // We now switch to cell.
        defaultCallback.expectAvailableCallbacks(mCellNetworkAgent);
        assertFalse(mCm.getNetworkCapabilities(wifiNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertTrue(mCm.getNetworkCapabilities(cellNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertEquals(mCm.getActiveNetwork(), cellNetwork);

        // Simulate the user turning the cellular fallback setting off and then on.
        // We switch to wifi and then to cell.
        Settings.Global.putString(cr, Settings.Global.NETWORK_AVOID_BAD_WIFI, null);
        tracker.reevaluate();
        defaultCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        assertEquals(mCm.getActiveNetwork(), wifiNetwork);
        Settings.Global.putInt(cr, Settings.Global.NETWORK_AVOID_BAD_WIFI, 1);
        tracker.reevaluate();
        defaultCallback.expectAvailableCallbacks(mCellNetworkAgent);
        assertEquals(mCm.getActiveNetwork(), cellNetwork);

        // If cell goes down, we switch to wifi.
        mCellNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        defaultCallback.expectAvailableCallbacks(mWiFiNetworkAgent);
        validatedWifiCallback.assertNoCallback();

        mCm.unregisterNetworkCallback(cellNetworkCallback);
        mCm.unregisterNetworkCallback(validatedWifiCallback);
        mCm.unregisterNetworkCallback(defaultCallback);
    }

    @SmallTest
    public void testMeteredMultipathPreferenceSetting() throws Exception {
        final ContentResolver cr = mServiceContext.getContentResolver();
        final WrappedMultinetworkPolicyTracker tracker = mService.getMultinetworkPolicyTracker();
        final String settingName = Settings.Global.NETWORK_METERED_MULTIPATH_PREFERENCE;

        for (int config : Arrays.asList(0, 3, 2)) {
            for (String setting: Arrays.asList(null, "0", "2", "1")) {
                tracker.configMeteredMultipathPreference = config;
                Settings.Global.putString(cr, settingName, setting);
                tracker.reevaluate();
                mService.waitForIdle();

                final int expected = (setting != null) ? Integer.parseInt(setting) : config;
                String msg = String.format("config=%d, setting=%s", config, setting);
                assertEquals(msg, expected, mCm.getMultipathPreference(null));
            }
        }
    }

    /**
     * Validate that a satisfied network request does not trigger onUnavailable() once the
     * time-out period expires.
     */
    @SmallTest
    public void testSatisfiedNetworkRequestDoesNotTriggerOnUnavailable() {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        final int timeoutMs = 150;
        mCm.requestNetwork(nr, networkCallback, timeoutMs);

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        networkCallback.expectAvailableCallbacks(mWiFiNetworkAgent, false, timeoutMs);

        // pass timeout and validate that UNAVAILABLE is not called
        networkCallback.assertNoCallback();
    }

    /**
     * Validate that a satisfied network request followed by a disconnected (lost) network does
     * not trigger onUnavailable() once the time-out period expires.
     */
    @SmallTest
    public void testSatisfiedThenLostNetworkRequestDoesNotTriggerOnUnavailable() {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        final int requestTimeoutMs = 100;
        mCm.requestNetwork(nr, networkCallback, requestTimeoutMs);

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        final int assertTimeoutMs = 150;
        networkCallback.expectAvailableCallbacks(mWiFiNetworkAgent, false, assertTimeoutMs);
        sleepFor(20);
        mWiFiNetworkAgent.disconnect();
        networkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // pass timeout and validate that UNAVAILABLE is not called
        sleepFor(100);
        networkCallback.assertNoCallback();
    }

    /**
     * Validate that when a time-out is specified for a network request the onUnavailable()
     * callback is called when time-out expires. Then validate that if network request is
     * (somehow) satisfied - the callback isn't called later.
     */
    @SmallTest
    public void testTimedoutNetworkRequest() {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        final int timeoutMs = 10;
        mCm.requestNetwork(nr, networkCallback, timeoutMs);

        // pass timeout and validate that UNAVAILABLE is called
        networkCallback.expectCallback(CallbackState.UNAVAILABLE, null);

        // create a network satisfying request - validate that request not triggered
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        networkCallback.assertNoCallback();
    }

    /**
     * Validate that when a network request is unregistered (cancelled) the time-out for that
     * request doesn't trigger the onUnavailable() callback.
     */
    @SmallTest
    public void testTimedoutAfterUnregisteredNetworkRequest() {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        final int timeoutMs = 10;
        mCm.requestNetwork(nr, networkCallback, timeoutMs);

        // remove request
        mCm.unregisterNetworkCallback(networkCallback);

        // pass timeout and validate that no callbacks
        // Note: doesn't validate that nothing called from CS since even if called the CM already
        // unregisters the callback and won't pass it through!
        sleepFor(15);
        networkCallback.assertNoCallback();

        // create a network satisfying request - validate that request not triggered
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        networkCallback.assertNoCallback();
    }

    private static class TestKeepaliveCallback extends PacketKeepaliveCallback {

        public static enum CallbackType { ON_STARTED, ON_STOPPED, ON_ERROR };

        private class CallbackValue {
            public CallbackType callbackType;
            public int error;

            public CallbackValue(CallbackType type) {
                this.callbackType = type;
                this.error = PacketKeepalive.SUCCESS;
                assertTrue("onError callback must have error", type != CallbackType.ON_ERROR);
            }

            public CallbackValue(CallbackType type, int error) {
                this.callbackType = type;
                this.error = error;
                assertEquals("error can only be set for onError", type, CallbackType.ON_ERROR);
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof CallbackValue &&
                        this.callbackType == ((CallbackValue) o).callbackType &&
                        this.error == ((CallbackValue) o).error;
            }

            @Override
            public String toString() {
                return String.format("%s(%s, %d)", getClass().getSimpleName(), callbackType, error);
            }
        }

        private LinkedBlockingQueue<CallbackValue> mCallbacks = new LinkedBlockingQueue<>();

        @Override
        public void onStarted() {
            mCallbacks.add(new CallbackValue(CallbackType.ON_STARTED));
        }

        @Override
        public void onStopped() {
            mCallbacks.add(new CallbackValue(CallbackType.ON_STOPPED));
        }

        @Override
        public void onError(int error) {
            mCallbacks.add(new CallbackValue(CallbackType.ON_ERROR, error));
        }

        private void expectCallback(CallbackValue callbackValue) {
            try {
                assertEquals(
                        callbackValue,
                        mCallbacks.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                fail(callbackValue.callbackType + " callback not seen after " + TIMEOUT_MS + " ms");
            }
        }

        public void expectStarted() {
            expectCallback(new CallbackValue(CallbackType.ON_STARTED));
        }

        public void expectStopped() {
            expectCallback(new CallbackValue(CallbackType.ON_STOPPED));
        }

        public void expectError(int error) {
            expectCallback(new CallbackValue(CallbackType.ON_ERROR, error));
        }
    }

    private Network connectKeepaliveNetwork(LinkProperties lp) {
        // Ensure the network is disconnected before we do anything.
        if (mWiFiNetworkAgent != null) {
            assertNull(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()));
        }

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        mService.waitForIdle();
        return mWiFiNetworkAgent.getNetwork();
    }

    @SmallTest
    public void testPacketKeepalives() throws Exception {
        InetAddress myIPv4 = InetAddress.getByName("192.0.2.129");
        InetAddress notMyIPv4 = InetAddress.getByName("192.0.2.35");
        InetAddress myIPv6 = InetAddress.getByName("2001:db8::1");
        InetAddress dstIPv4 = InetAddress.getByName("8.8.8.8");
        InetAddress dstIPv6 = InetAddress.getByName("2001:4860:4860::8888");

        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("wlan12");
        lp.addLinkAddress(new LinkAddress(myIPv6, 64));
        lp.addLinkAddress(new LinkAddress(myIPv4, 25));
        lp.addRoute(new RouteInfo(InetAddress.getByName("fe80::1234")));
        lp.addRoute(new RouteInfo(InetAddress.getByName("192.0.2.254")));

        Network notMyNet = new Network(61234);
        Network myNet = connectKeepaliveNetwork(lp);

        TestKeepaliveCallback callback = new TestKeepaliveCallback();
        PacketKeepalive ka;

        // Attempt to start keepalives with invalid parameters and check for errors.
        ka = mCm.startNattKeepalive(notMyNet, 25, callback, myIPv4, 1234, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_NETWORK);

        ka = mCm.startNattKeepalive(myNet, 19, callback, notMyIPv4, 1234, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_INTERVAL);

        ka = mCm.startNattKeepalive(myNet, 25, callback, myIPv4, 1234, dstIPv6);
        callback.expectError(PacketKeepalive.ERROR_INVALID_IP_ADDRESS);

        ka = mCm.startNattKeepalive(myNet, 25, callback, myIPv6, 1234, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_IP_ADDRESS);

        ka = mCm.startNattKeepalive(myNet, 25, callback, myIPv6, 1234, dstIPv6);
        callback.expectError(PacketKeepalive.ERROR_INVALID_IP_ADDRESS);  // NAT-T is IPv4-only.

        ka = mCm.startNattKeepalive(myNet, 25, callback, myIPv4, 123456, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_PORT);

        ka = mCm.startNattKeepalive(myNet, 25, callback, myIPv4, 123456, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_PORT);

        ka = mCm.startNattKeepalive(myNet, 25, callback, myIPv4, 12345, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_HARDWARE_UNSUPPORTED);

        ka = mCm.startNattKeepalive(myNet, 25, callback, myIPv4, 12345, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_HARDWARE_UNSUPPORTED);

        // Check that a started keepalive can be stopped.
        mWiFiNetworkAgent.setStartKeepaliveError(PacketKeepalive.SUCCESS);
        ka = mCm.startNattKeepalive(myNet, 25, callback, myIPv4, 12345, dstIPv4);
        callback.expectStarted();
        mWiFiNetworkAgent.setStopKeepaliveError(PacketKeepalive.SUCCESS);
        ka.stop();
        callback.expectStopped();

        // Check that deleting the IP address stops the keepalive.
        LinkProperties bogusLp = new LinkProperties(lp);
        ka = mCm.startNattKeepalive(myNet, 25, callback, myIPv4, 12345, dstIPv4);
        callback.expectStarted();
        bogusLp.removeLinkAddress(new LinkAddress(myIPv4, 25));
        bogusLp.addLinkAddress(new LinkAddress(notMyIPv4, 25));
        mWiFiNetworkAgent.sendLinkProperties(bogusLp);
        callback.expectError(PacketKeepalive.ERROR_INVALID_IP_ADDRESS);
        mWiFiNetworkAgent.sendLinkProperties(lp);

        // Check that a started keepalive is stopped correctly when the network disconnects.
        ka = mCm.startNattKeepalive(myNet, 25, callback, myIPv4, 12345, dstIPv4);
        callback.expectStarted();
        mWiFiNetworkAgent.disconnect();
        waitFor(mWiFiNetworkAgent.getDisconnectedCV());
        callback.expectError(PacketKeepalive.ERROR_INVALID_NETWORK);

        // ... and that stopping it after that has no adverse effects.
        mService.waitForIdle();
        final Network myNetAlias = myNet;
        assertNull(mCm.getNetworkCapabilities(myNetAlias));
        ka.stop();

        // Reconnect.
        myNet = connectKeepaliveNetwork(lp);
        mWiFiNetworkAgent.setStartKeepaliveError(PacketKeepalive.SUCCESS);

        // Check things work as expected when the keepalive is stopped and the network disconnects.
        ka = mCm.startNattKeepalive(myNet, 25, callback, myIPv4, 12345, dstIPv4);
        callback.expectStarted();
        ka.stop();
        mWiFiNetworkAgent.disconnect();
        waitFor(mWiFiNetworkAgent.getDisconnectedCV());
        mService.waitForIdle();
        callback.expectStopped();

        // Reconnect.
        myNet = connectKeepaliveNetwork(lp);
        mWiFiNetworkAgent.setStartKeepaliveError(PacketKeepalive.SUCCESS);

        // Check that keepalive slots start from 1 and increment. The first one gets slot 1.
        mWiFiNetworkAgent.setExpectedKeepaliveSlot(1);
        ka = mCm.startNattKeepalive(myNet, 25, callback, myIPv4, 12345, dstIPv4);
        callback.expectStarted();

        // The second one gets slot 2.
        mWiFiNetworkAgent.setExpectedKeepaliveSlot(2);
        TestKeepaliveCallback callback2 = new TestKeepaliveCallback();
        PacketKeepalive ka2 = mCm.startNattKeepalive(myNet, 25, callback2, myIPv4, 6789, dstIPv4);
        callback2.expectStarted();

        // Now stop the first one and create a third. This also gets slot 1.
        ka.stop();
        callback.expectStopped();

        mWiFiNetworkAgent.setExpectedKeepaliveSlot(1);
        TestKeepaliveCallback callback3 = new TestKeepaliveCallback();
        PacketKeepalive ka3 = mCm.startNattKeepalive(myNet, 25, callback3, myIPv4, 9876, dstIPv4);
        callback3.expectStarted();

        ka2.stop();
        callback2.expectStopped();

        ka3.stop();
        callback3.expectStopped();
    }

    @SmallTest
    public void testGetCaptivePortalServerUrl() throws Exception {
        String url = mCm.getCaptivePortalServerUrl();
        assertEquals("http://connectivitycheck.gstatic.com/generate_204", url);
    }

    private static class TestNetworkPinner extends NetworkPinner {
        public static boolean awaitPin(int timeoutMs) {
            synchronized(sLock) {
                if (sNetwork == null) {
                    try {
                        sLock.wait(timeoutMs);
                    } catch (InterruptedException e) {}
                }
                return sNetwork != null;
            }
        }

        public static boolean awaitUnpin(int timeoutMs) {
            synchronized(sLock) {
                if (sNetwork != null) {
                    try {
                        sLock.wait(timeoutMs);
                    } catch (InterruptedException e) {}
                }
                return sNetwork == null;
            }
        }
    }

    private void assertPinnedToWifiWithCellDefault() {
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getBoundNetworkForProcess());
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
    }

    private void assertPinnedToWifiWithWifiDefault() {
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getBoundNetworkForProcess());
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
    }

    private void assertNotPinnedToWifi() {
        assertNull(mCm.getBoundNetworkForProcess());
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
    }

    @SmallTest
    public void testNetworkPinner() {
        NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .build();
        assertNull(mCm.getBoundNetworkForProcess());

        TestNetworkPinner.pin(mServiceContext, wifiRequest);
        assertNull(mCm.getBoundNetworkForProcess());

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);

        // When wi-fi connects, expect to be pinned.
        assertTrue(TestNetworkPinner.awaitPin(100));
        assertPinnedToWifiWithCellDefault();

        // Disconnect and expect the pin to drop.
        mWiFiNetworkAgent.disconnect();
        assertTrue(TestNetworkPinner.awaitUnpin(100));
        assertNotPinnedToWifi();

        // Reconnecting does not cause the pin to come back.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        assertFalse(TestNetworkPinner.awaitPin(100));
        assertNotPinnedToWifi();

        // Pinning while connected causes the pin to take effect immediately.
        TestNetworkPinner.pin(mServiceContext, wifiRequest);
        assertTrue(TestNetworkPinner.awaitPin(100));
        assertPinnedToWifiWithCellDefault();

        // Explicitly unpin and expect to use the default network again.
        TestNetworkPinner.unpin();
        assertNotPinnedToWifi();

        // Disconnect cell and wifi.
        ConditionVariable cv = waitForConnectivityBroadcasts(3);  // cell down, wifi up, wifi down.
        mCellNetworkAgent.disconnect();
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);

        // Pinning takes effect even if the pinned network is the default when the pin is set...
        TestNetworkPinner.pin(mServiceContext, wifiRequest);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        assertTrue(TestNetworkPinner.awaitPin(100));
        assertPinnedToWifiWithWifiDefault();

        // ... and is maintained even when that network is no longer the default.
        cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        assertPinnedToWifiWithCellDefault();
    }

    @SmallTest
    public void testNetworkRequestMaximum() {
        final int MAX_REQUESTS = 100;
        // Test that the limit is enforced when MAX_REQUESTS simultaneous requests are added.
        NetworkRequest networkRequest = new NetworkRequest.Builder().build();
        ArrayList<NetworkCallback> networkCallbacks = new ArrayList<NetworkCallback>();
        try {
            for (int i = 0; i < MAX_REQUESTS; i++) {
                NetworkCallback networkCallback = new NetworkCallback();
                mCm.requestNetwork(networkRequest, networkCallback);
                networkCallbacks.add(networkCallback);
            }
            fail("Registering " + MAX_REQUESTS + " NetworkRequests did not throw exception");
        } catch (IllegalArgumentException expected) {}
        for (NetworkCallback networkCallback : networkCallbacks) {
            mCm.unregisterNetworkCallback(networkCallback);
        }
        networkCallbacks.clear();

        try {
            for (int i = 0; i < MAX_REQUESTS; i++) {
                NetworkCallback networkCallback = new NetworkCallback();
                mCm.registerNetworkCallback(networkRequest, networkCallback);
                networkCallbacks.add(networkCallback);
            }
            fail("Registering " + MAX_REQUESTS + " NetworkCallbacks did not throw exception");
        } catch (IllegalArgumentException expected) {}
        for (NetworkCallback networkCallback : networkCallbacks) {
            mCm.unregisterNetworkCallback(networkCallback);
        }
        networkCallbacks.clear();

        ArrayList<PendingIntent> pendingIntents = new ArrayList<PendingIntent>();
        try {
            for (int i = 0; i < MAX_REQUESTS + 1; i++) {
                PendingIntent pendingIntent =
                        PendingIntent.getBroadcast(mContext, 0, new Intent("a" + i), 0);
                mCm.requestNetwork(networkRequest, pendingIntent);
                pendingIntents.add(pendingIntent);
            }
            fail("Registering " + MAX_REQUESTS +
                    " PendingIntent NetworkRequests did not throw exception");
        } catch (IllegalArgumentException expected) {}
        for (PendingIntent pendingIntent : pendingIntents) {
            mCm.unregisterNetworkCallback(pendingIntent);
        }
        pendingIntents.clear();

        try {
            for (int i = 0; i < MAX_REQUESTS + 1; i++) {
                PendingIntent pendingIntent =
                        PendingIntent.getBroadcast(mContext, 0, new Intent("a" + i), 0);
                mCm.registerNetworkCallback(networkRequest, pendingIntent);
                pendingIntents.add(pendingIntent);
            }
            fail("Registering " + MAX_REQUESTS +
                    " PendingIntent NetworkCallbacks did not throw exception");
        } catch (IllegalArgumentException expected) {}
        for (PendingIntent pendingIntent : pendingIntents) {
            mCm.unregisterNetworkCallback(pendingIntent);
        }
        pendingIntents.clear();
        mService.waitForIdle(5000);

        // Test that the limit is not hit when MAX_REQUESTS requests are added and removed.
        for (int i = 0; i < MAX_REQUESTS; i++) {
            NetworkCallback networkCallback = new NetworkCallback();
            mCm.requestNetwork(networkRequest, networkCallback);
            mCm.unregisterNetworkCallback(networkCallback);
        }
        mService.waitForIdle();
        for (int i = 0; i < MAX_REQUESTS; i++) {
            NetworkCallback networkCallback = new NetworkCallback();
            mCm.registerNetworkCallback(networkRequest, networkCallback);
            mCm.unregisterNetworkCallback(networkCallback);
        }
        mService.waitForIdle();
        for (int i = 0; i < MAX_REQUESTS; i++) {
            PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(mContext, 0, new Intent("b" + i), 0);
            mCm.requestNetwork(networkRequest, pendingIntent);
            mCm.unregisterNetworkCallback(pendingIntent);
        }
        mService.waitForIdle();
        for (int i = 0; i < MAX_REQUESTS; i++) {
            PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(mContext, 0, new Intent("c" + i), 0);
            mCm.registerNetworkCallback(networkRequest, pendingIntent);
            mCm.unregisterNetworkCallback(pendingIntent);
        }
    }

    /* test utilities */
    // TODO: eliminate all usages of sleepFor and replace by proper timeouts/waitForIdle.
    static private void sleepFor(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
        }
    }
}
