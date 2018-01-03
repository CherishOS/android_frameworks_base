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

package com.android.server.backup.internal;

import static android.app.backup.BackupTransport.TRANSPORT_ERROR;
import static android.app.backup.BackupTransport.TRANSPORT_OK;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.app.backup.IBackupObserver;
import android.os.DeadObjectException;
import android.platform.test.annotations.Presubmit;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.RefactoredBackupManagerService;
import com.android.server.backup.TransportManager;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.transport.TransportNotAvailableException;
import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderClasses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;
import java.util.List;

@RunWith(FrameworkRobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 26)
@SystemLoaderClasses({PerformInitializeTaskTest.class, TransportManager.class})
@Presubmit
public class PerformInitializeTaskTest {
    private static final String[] TRANSPORT_NAMES = {
        "android/com.android.internal.backup.LocalTransport",
        "com.google.android.gms/.backup.migrate.service.D2dTransport",
        "com.google.android.gms/.backup.BackupTransportService"
    };

    private static final String TRANSPORT_NAME = TRANSPORT_NAMES[0];

    @Mock private RefactoredBackupManagerService mBackupManagerService;
    @Mock private TransportManager mTransportManager;
    @Mock private OnTaskFinishedListener mListener;
    @Mock private IBackupTransport mTransport;
    @Mock private IBackupObserver mObserver;
    @Mock private AlarmManager mAlarmManager;
    @Mock private PendingIntent mRunInitIntent;
    private File mBaseStateDir;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Application context = RuntimeEnvironment.application;
        mBaseStateDir = new File(context.getCacheDir(), "base_state_dir");
        assertThat(mBaseStateDir.mkdir()).isTrue();

        when(mBackupManagerService.getAlarmManager()).thenReturn(mAlarmManager);
        when(mBackupManagerService.getRunInitIntent()).thenReturn(mRunInitIntent);
    }

    @Test
    public void testRun_callsTransportCorrectly() throws Exception {
        setUpTransport(TRANSPORT_NAME);
        configureTransport(mTransport, TRANSPORT_OK, TRANSPORT_OK);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(TRANSPORT_NAME);

        performInitializeTask.run();

        verify(mTransport).initializeDevice();
        verify(mTransport).finishBackup();
    }

    @Test
    public void testRun_callsBackupManagerCorrectly() throws Exception {
        setUpTransport(TRANSPORT_NAME);
        configureTransport(mTransport, TRANSPORT_OK, TRANSPORT_OK);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(TRANSPORT_NAME);

        performInitializeTask.run();

        verify(mBackupManagerService)
                .recordInitPending(false, TRANSPORT_NAME, dirName(TRANSPORT_NAME));
        verify(mBackupManagerService)
                .resetBackupState(eq(new File(mBaseStateDir, dirName(TRANSPORT_NAME))));
    }

    @Test
    public void testRun_callsObserverAndListenerCorrectly() throws Exception {
        setUpTransport(TRANSPORT_NAME);
        configureTransport(mTransport, TRANSPORT_OK, TRANSPORT_OK);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(TRANSPORT_NAME);

        performInitializeTask.run();

        verify(mObserver).onResult(eq(TRANSPORT_NAME), eq(TRANSPORT_OK));
        verify(mObserver).backupFinished(eq(TRANSPORT_OK));
        verify(mListener).onFinished(any());
    }

    @Test
    public void testRun_whenInitializeDeviceFails() throws Exception {
        setUpTransport(TRANSPORT_NAME);
        configureTransport(mTransport, TRANSPORT_ERROR, 0);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(TRANSPORT_NAME);

        performInitializeTask.run();

        verify(mTransport).initializeDevice();
        verify(mTransport, never()).finishBackup();
        verify(mBackupManagerService)
                .recordInitPending(true, TRANSPORT_NAME, dirName(TRANSPORT_NAME));
    }

    @Test
    public void testRun_whenInitializeDeviceFails_callsObserverAndListenerCorrectly()
            throws Exception {
        setUpTransport(TRANSPORT_NAME);
        configureTransport(mTransport, TRANSPORT_ERROR, 0);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(TRANSPORT_NAME);

        performInitializeTask.run();

        verify(mObserver).onResult(eq(TRANSPORT_NAME), eq(TRANSPORT_ERROR));
        verify(mObserver).backupFinished(eq(TRANSPORT_ERROR));
        verify(mListener).onFinished(any());
    }

    @Test
    public void testRun_whenInitializeDeviceFails_schedulesAlarm() throws Exception {
        setUpTransport(TRANSPORT_NAME);
        configureTransport(mTransport, TRANSPORT_ERROR, 0);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(TRANSPORT_NAME);

        performInitializeTask.run();

        verify(mAlarmManager).set(anyInt(), anyLong(), eq(mRunInitIntent));
    }

    @Test
    public void testRun_whenFinishBackupFails() throws Exception {
        setUpTransport(TRANSPORT_NAME);
        configureTransport(mTransport, TRANSPORT_OK, TRANSPORT_ERROR);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(TRANSPORT_NAME);

        performInitializeTask.run();

        verify(mTransport).initializeDevice();
        verify(mTransport).finishBackup();
        verify(mBackupManagerService)
                .recordInitPending(true, TRANSPORT_NAME, dirName(TRANSPORT_NAME));
    }

    @Test
    public void testRun_whenFinishBackupFails_callsObserverAndListenerCorrectly() throws Exception {
        setUpTransport(TRANSPORT_NAME);
        configureTransport(mTransport, TRANSPORT_OK, TRANSPORT_ERROR);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(TRANSPORT_NAME);

        performInitializeTask.run();

        verify(mObserver).onResult(eq(TRANSPORT_NAME), eq(TRANSPORT_ERROR));
        verify(mObserver).backupFinished(eq(TRANSPORT_ERROR));
        verify(mListener).onFinished(any());
    }

    @Test
    public void testRun_whenFinishBackupFails_schedulesAlarm() throws Exception {
        setUpTransport(TRANSPORT_NAME);
        configureTransport(mTransport, TRANSPORT_OK, TRANSPORT_ERROR);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(TRANSPORT_NAME);

        performInitializeTask.run();

        verify(mAlarmManager).set(anyInt(), anyLong(), eq(mRunInitIntent));
    }

    @Test
    public void testRun_whenOnlyOneTransportFails() throws Exception {
        List<TransportData> transports = setUpTransports(TRANSPORT_NAMES[0], TRANSPORT_NAMES[1]);
        configureTransport(transports.get(0).transportMock, TRANSPORT_ERROR, 0);
        configureTransport(transports.get(1).transportMock, TRANSPORT_OK, TRANSPORT_OK);
        PerformInitializeTask performInitializeTask =
                createPerformInitializeTask(TRANSPORT_NAMES[0], TRANSPORT_NAMES[1]);

        performInitializeTask.run();

        verify(transports.get(1).transportMock).initializeDevice();
        verify(mObserver).onResult(eq(TRANSPORT_NAMES[0]), eq(TRANSPORT_ERROR));
        verify(mObserver).onResult(eq(TRANSPORT_NAMES[1]), eq(TRANSPORT_OK));
        verify(mObserver).backupFinished(eq(TRANSPORT_ERROR));
    }

    @Test
    public void testRun_withMultipleTransports() throws Exception {
        List<TransportData> transports = setUpTransports(TRANSPORT_NAMES);
        configureTransport(transports.get(0).transportMock, TRANSPORT_OK, TRANSPORT_OK);
        configureTransport(transports.get(1).transportMock, TRANSPORT_OK, TRANSPORT_OK);
        configureTransport(transports.get(2).transportMock, TRANSPORT_OK, TRANSPORT_OK);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(TRANSPORT_NAMES);

        performInitializeTask.run();

        for (TransportData transport : transports) {
            verify(mTransportManager).getTransportClient(eq(transport.transportName), any());
            verify(mTransportManager)
                    .disposeOfTransportClient(eq(transport.transportClientMock), any());
        }
    }

    @Test
    public void testRun_whenOnlyOneTransportFails_disposesAllTransports() throws Exception {
        List<TransportData> transports = setUpTransports(TRANSPORT_NAMES[0], TRANSPORT_NAMES[1]);
        configureTransport(transports.get(0).transportMock, TRANSPORT_ERROR, 0);
        configureTransport(transports.get(1).transportMock, TRANSPORT_OK, TRANSPORT_OK);
        PerformInitializeTask performInitializeTask =
                createPerformInitializeTask(TRANSPORT_NAMES[0], TRANSPORT_NAMES[1]);

        performInitializeTask.run();

        verify(mTransportManager)
                .disposeOfTransportClient(eq(transports.get(0).transportClientMock), any());
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transports.get(1).transportClientMock), any());
    }

    @Test
    public void testRun_whenTransportNotRegistered() throws Exception {
        setUpTransport(new TransportData(TRANSPORT_NAME, null, null));
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(TRANSPORT_NAME);

        performInitializeTask.run();

        verify(mTransportManager, never()).disposeOfTransportClient(any(), any());
        verify(mObserver, never()).onResult(any(), anyInt());
        verify(mObserver).backupFinished(eq(TRANSPORT_OK));
    }

    @Test
    public void testRun_whenOnlyOneTransportNotRegistered() throws Exception {
        List<TransportData> transports =
                setUpTransports(
                        new TransportData(TRANSPORT_NAMES[0], null, null),
                        new TransportData(TRANSPORT_NAMES[1]));
        String registeredTransportName = transports.get(1).transportName;
        IBackupTransport registeredTransport = transports.get(1).transportMock;
        TransportClient registeredTransportClient = transports.get(1).transportClientMock;
        PerformInitializeTask performInitializeTask =
                createPerformInitializeTask(TRANSPORT_NAMES[0], TRANSPORT_NAMES[1]);

        performInitializeTask.run();

        verify(registeredTransport).initializeDevice();
        verify(mTransportManager).disposeOfTransportClient(eq(registeredTransportClient), any());
        verify(mObserver).onResult(eq(registeredTransportName), eq(TRANSPORT_OK));
    }

    @Test
    public void testRun_whenTransportNotAvailable() throws Exception {
        TransportClient transportClient = mock(TransportClient.class);
        setUpTransport(new TransportData(TRANSPORT_NAME, null, transportClient));
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(TRANSPORT_NAME);

        performInitializeTask.run();

        verify(mTransportManager).disposeOfTransportClient(eq(transportClient), any());
        verify(mObserver).backupFinished(eq(TRANSPORT_ERROR));
        verify(mListener).onFinished(any());
    }

    @Test
    public void testRun_whenTransportThrowsDeadObjectException() throws Exception {
        TransportClient transportClient = mock(TransportClient.class);
        setUpTransport(new TransportData(TRANSPORT_NAME, mTransport, transportClient));
        when(mTransport.initializeDevice()).thenThrow(DeadObjectException.class);
        PerformInitializeTask performInitializeTask = createPerformInitializeTask(TRANSPORT_NAME);

        performInitializeTask.run();

        verify(mTransportManager).disposeOfTransportClient(eq(transportClient), any());
        verify(mObserver).backupFinished(eq(TRANSPORT_ERROR));
        verify(mListener).onFinished(any());
    }

    private PerformInitializeTask createPerformInitializeTask(String... transportNames) {
        return new PerformInitializeTask(
                mBackupManagerService,
                mTransportManager,
                transportNames,
                mObserver,
                mListener,
                mBaseStateDir);
    }

    private void configureTransport(
            IBackupTransport transportMock, int initializeDeviceStatus, int finishBackupStatus)
            throws Exception {
        when(transportMock.initializeDevice()).thenReturn(initializeDeviceStatus);
        when(transportMock.finishBackup()).thenReturn(finishBackupStatus);
    }

    private List<TransportData> setUpTransports(String... transportNames) throws Exception {
        return setUpTransports(
                Arrays.stream(transportNames)
                        .map(TransportData::new)
                        .toArray(TransportData[]::new));
    }

    /** @see #setUpTransport(TransportData) */
    private List<TransportData> setUpTransports(TransportData... transports) throws Exception {
        for (TransportData transport : transports) {
            setUpTransport(transport);
        }
        return Arrays.asList(transports);
    }

    private void setUpTransport(String transportName) throws Exception {
        setUpTransport(new TransportData(transportName, mTransport, mock(TransportClient.class)));
    }

    /**
     * Configures transport according to {@link TransportData}:
     *
     * <ul>
     *   <li>{@link TransportData#transportMock} {@code null} means {@link
     *       TransportClient#connectOrThrow(String)} throws {@link TransportNotAvailableException}.
     *   <li>{@link TransportData#transportClientMock} {@code null} means {@link
     *       TransportManager#getTransportClient(String, String)} returns {@code null}.
     * </ul>
     */
    private void setUpTransport(TransportData transport) throws Exception {
        String transportName = transport.transportName;
        String transportDirName = dirName(transportName);
        IBackupTransport transportMock = transport.transportMock;
        TransportClient transportClientMock = transport.transportClientMock;

        if (transportMock != null) {
            when(transportMock.name()).thenReturn(transportName);
            when(transportMock.transportDirName()).thenReturn(transportDirName);
        }

        if (transportClientMock != null) {
            when(transportClientMock.getTransportDirName()).thenReturn(transportDirName);
            if (transportMock != null) {
                when(transportClientMock.connectOrThrow(any())).thenReturn(transportMock);
            } else {
                when(transportClientMock.connectOrThrow(any()))
                        .thenThrow(TransportNotAvailableException.class);
            }
        }

        when(mTransportManager.getTransportClient(eq(transportName), any()))
                .thenReturn(transportClientMock);
    }

    private String dirName(String transportName) {
        return transportName + "_dir_name";
    }

    private static class TransportData {
        private final String transportName;
        @Nullable private final IBackupTransport transportMock;
        @Nullable private final TransportClient transportClientMock;

        private TransportData(
                String transportName,
                @Nullable IBackupTransport transportMock,
                @Nullable TransportClient transportClientMock) {
            this.transportName = transportName;
            this.transportMock = transportMock;
            this.transportClientMock = transportClientMock;
        }

        private TransportData(String transportName) {
            this(transportName, mock(IBackupTransport.class), mock(TransportClient.class));
        }
    }
}
