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
 * limitations under the License
 */

package com.android.server.backup.transport;

import static com.android.server.backup.TransportManager.SERVICE_ACTION_TRANSPORT_HOST;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import com.android.internal.backup.IBackupTransport;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 23)
@Presubmit
public class TransportClientTest {
    private static final String PACKAGE_NAME = "some.package.name";
    private static final ComponentName TRANSPORT_COMPONENT =
            new ComponentName(PACKAGE_NAME, PACKAGE_NAME + ".transport.Transport");

    @Mock private Context mContext;
    @Mock private TransportConnectionListener mTransportConnectionListener;
    @Mock private TransportConnectionListener mTransportConnectionListener2;
    @Mock private IBackupTransport.Stub mIBackupTransport;
    private TransportClient mTransportClient;
    private Intent mBindIntent;
    private ShadowLooper mShadowLooper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Looper mainLooper = Looper.getMainLooper();
        mShadowLooper = shadowOf(mainLooper);
        mBindIntent = new Intent(SERVICE_ACTION_TRANSPORT_HOST).setComponent(TRANSPORT_COMPONENT);
        mTransportClient =
                new TransportClient(
                        mContext, mBindIntent, TRANSPORT_COMPONENT, "1", new Handler(mainLooper));

        when(mContext.bindServiceAsUser(
                        eq(mBindIntent),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(UserHandle.class)))
                .thenReturn(true);
    }

    // TODO: Testing implementation? Remove?
    @Test
    public void testConnectAsync_callsBindService() throws Exception {
        mTransportClient.connectAsync(mTransportConnectionListener, "caller");

        verify(mContext)
                .bindServiceAsUser(
                        eq(mBindIntent),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(UserHandle.class));
    }

    @Test
    public void testConnectAsync_callsListenerWhenConnected() throws Exception {
        mTransportClient.connectAsync(mTransportConnectionListener, "caller");

        // Simulate framework connecting
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(TRANSPORT_COMPONENT, mIBackupTransport);

        mShadowLooper.runToEndOfTasks();
        verify(mTransportConnectionListener)
                .onTransportConnectionResult(any(IBackupTransport.class), eq(mTransportClient));
    }

    @Test
    public void testConnectAsync_whenPendingConnection_callsAllListenersWhenConnected()
            throws Exception {
        mTransportClient.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);

        mTransportClient.connectAsync(mTransportConnectionListener2, "caller2");

        connection.onServiceConnected(TRANSPORT_COMPONENT, mIBackupTransport);

        mShadowLooper.runToEndOfTasks();
        verify(mTransportConnectionListener)
                .onTransportConnectionResult(any(IBackupTransport.class), eq(mTransportClient));
        verify(mTransportConnectionListener2)
                .onTransportConnectionResult(any(IBackupTransport.class), eq(mTransportClient));
    }

    @Test
    public void testConnectAsync_whenAlreadyConnected_callsListener() throws Exception {
        mTransportClient.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(TRANSPORT_COMPONENT, mIBackupTransport);

        mTransportClient.connectAsync(mTransportConnectionListener2, "caller2");

        mShadowLooper.runToEndOfTasks();
        verify(mTransportConnectionListener2)
                .onTransportConnectionResult(any(IBackupTransport.class), eq(mTransportClient));
    }

    @Test
    public void testConnectAsync_whenFrameworkDoesntBind_callsListener() throws Exception {
        when(mContext.bindServiceAsUser(
                        eq(mBindIntent),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(UserHandle.class)))
                .thenReturn(false);

        mTransportClient.connectAsync(mTransportConnectionListener, "caller");

        mShadowLooper.runToEndOfTasks();
        verify(mTransportConnectionListener)
                .onTransportConnectionResult(isNull(), eq(mTransportClient));
    }

    @Test
    public void testConnectAsync_whenFrameworkDoesntBind_releasesConnection() throws Exception {
        when(mContext.bindServiceAsUser(
                        eq(mBindIntent),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(UserHandle.class)))
                .thenReturn(false);

        mTransportClient.connectAsync(mTransportConnectionListener, "caller");

        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        verify(mContext).unbindService(eq(connection));
    }

    @Test
    public void testConnectAsync_afterServiceDisconnectedBeforeNewConnection_callsListener()
            throws Exception {
        mTransportClient.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(TRANSPORT_COMPONENT, mIBackupTransport);
        connection.onServiceDisconnected(TRANSPORT_COMPONENT);

        mTransportClient.connectAsync(mTransportConnectionListener2, "caller1");

        verify(mTransportConnectionListener2)
                .onTransportConnectionResult(isNull(), eq(mTransportClient));
    }

    @Test
    public void testConnectAsync_afterServiceDisconnectedAfterNewConnection_callsListener()
            throws Exception {
        mTransportClient.connectAsync(mTransportConnectionListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onServiceConnected(TRANSPORT_COMPONENT, mIBackupTransport);
        connection.onServiceDisconnected(TRANSPORT_COMPONENT);
        connection.onServiceConnected(TRANSPORT_COMPONENT, mIBackupTransport);

        mTransportClient.connectAsync(mTransportConnectionListener2, "caller1");

        // Yes, it should return null because the object became unusable, check design doc
        verify(mTransportConnectionListener2)
                .onTransportConnectionResult(isNull(), eq(mTransportClient));
    }

    // TODO(b/69153972): Support SDK 26 API (ServiceConnection.inBindingDied) for transport tests
    /*@Test
    public void testConnectAsync_callsListenerIfBindingDies() throws Exception {
        mTransportClient.connectAsync(mTransportListener, "caller");

        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);
        connection.onBindingDied(TRANSPORT_COMPONENT);

        mShadowLooper.runToEndOfTasks();
        verify(mTransportListener).onTransportBound(isNull(), eq(mTransportClient));
    }

    @Test
    public void testConnectAsync_whenPendingConnection_callsListenersIfBindingDies()
            throws Exception {
        mTransportClient.connectAsync(mTransportListener, "caller1");
        ServiceConnection connection = verifyBindServiceAsUserAndCaptureServiceConnection(mContext);

        mTransportClient.connectAsync(mTransportListener2, "caller2");

        connection.onBindingDied(TRANSPORT_COMPONENT);

        mShadowLooper.runToEndOfTasks();
        verify(mTransportListener).onTransportBound(isNull(), eq(mTransportClient));
        verify(mTransportListener2).onTransportBound(isNull(), eq(mTransportClient));
    }*/

    private ServiceConnection verifyBindServiceAsUserAndCaptureServiceConnection(Context context) {
        ArgumentCaptor<ServiceConnection> connectionCaptor =
                ArgumentCaptor.forClass(ServiceConnection.class);
        verify(context)
                .bindServiceAsUser(
                        any(Intent.class),
                        connectionCaptor.capture(),
                        anyInt(),
                        any(UserHandle.class));
        return connectionCaptor.getValue();
    }
}
