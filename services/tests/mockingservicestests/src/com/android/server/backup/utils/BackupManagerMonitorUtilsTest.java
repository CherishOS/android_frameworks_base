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

package com.android.server.backup.utils;

import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_CATEGORY;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_ID;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_LONG_VERSION;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_VERSION;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_OPERATION_TYPE;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_AGENT_LOGGING_RESULTS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.IBackupAgent;
import android.app.backup.BackupAnnotations;
import android.app.backup.BackupAnnotations.OperationType;
import android.app.backup.BackupManagerMonitor;
import android.app.backup.BackupRestoreEventLogger;
import android.app.backup.IBackupManagerMonitor;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.infra.AndroidFuture;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupManagerMonitorUtilsTest {
    @Mock private IBackupManagerMonitor mMonitorMock;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void monitorEvent_monitorIsNull_returnsNull() throws Exception {
        IBackupManagerMonitor result = BackupManagerMonitorUtils.monitorEvent(null, 0, null, 0,
                null);

        assertThat(result).isNull();
    }

    @Test
    public void monitorEvent_monitorOnEventThrows_returnsNull() throws Exception {
        doThrow(new RemoteException()).when(mMonitorMock).onEvent(any(Bundle.class));

        IBackupManagerMonitor result = BackupManagerMonitorUtils.monitorEvent(mMonitorMock, 0, null,
                0, null);

        verify(mMonitorMock).onEvent(any(Bundle.class));
        assertThat(result).isNull();
    }

    @Test
    public void monitorEvent_packageAndExtrasAreNull_fillsBundleCorrectly() throws Exception {
        IBackupManagerMonitor result = BackupManagerMonitorUtils.monitorEvent(mMonitorMock, 1, null,
                2, null);

        assertThat(result).isEqualTo(mMonitorMock);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mMonitorMock).onEvent(bundleCaptor.capture());
        Bundle eventBundle = bundleCaptor.getValue();
        assertThat(eventBundle.size()).isEqualTo(2);
        assertThat(eventBundle.getInt(EXTRA_LOG_EVENT_ID)).isEqualTo(1);
        assertThat(eventBundle.getInt(EXTRA_LOG_EVENT_CATEGORY)).isEqualTo(2);
    }

    @Test
    public void monitorEvent_packageAndExtrasAreNotNull_fillsBundleCorrectly() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test.package";
        packageInfo.versionCode = 3;
        Bundle extras = new Bundle();
        extras.putInt("key1", 4);
        extras.putString("key2", "value2");

        IBackupManagerMonitor result = BackupManagerMonitorUtils.monitorEvent(mMonitorMock, 1,
                packageInfo, 2, extras);

        assertThat(result).isEqualTo(mMonitorMock);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mMonitorMock).onEvent(bundleCaptor.capture());
        Bundle eventBundle = bundleCaptor.getValue();
        assertThat(eventBundle.size()).isEqualTo(7);
        assertThat(eventBundle.getInt(EXTRA_LOG_EVENT_ID)).isEqualTo(1);
        assertThat(eventBundle.getInt(EXTRA_LOG_EVENT_CATEGORY)).isEqualTo(2);
        assertThat(eventBundle.getString(EXTRA_LOG_EVENT_PACKAGE_NAME)).isEqualTo("test.package");
        assertThat(eventBundle.getInt(EXTRA_LOG_EVENT_PACKAGE_VERSION)).isEqualTo(3);
        assertThat(eventBundle.getLong(EXTRA_LOG_EVENT_PACKAGE_LONG_VERSION)).isEqualTo(3);
        assertThat(eventBundle.getInt("key1")).isEqualTo(4);
        assertThat(eventBundle.getString("key2")).isEqualTo("value2");
    }

    @Test
    public void monitorEvent_packageAndExtrasAreNotNull_fillsBundleCorrectlyLong() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test.package";
        packageInfo.versionCode = 3;
        packageInfo.versionCodeMajor = 10;
        Bundle extras = new Bundle();
        extras.putInt("key1", 4);
        extras.putString("key2", "value2");

        IBackupManagerMonitor result = BackupManagerMonitorUtils.monitorEvent(mMonitorMock, 1,
                packageInfo, 2, extras);

        assertThat(result).isEqualTo(mMonitorMock);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mMonitorMock).onEvent(bundleCaptor.capture());
        Bundle eventBundle = bundleCaptor.getValue();
        assertThat(eventBundle.size()).isEqualTo(7);
        assertThat(eventBundle.getInt(EXTRA_LOG_EVENT_ID)).isEqualTo(1);
        assertThat(eventBundle.getInt(EXTRA_LOG_EVENT_CATEGORY)).isEqualTo(2);
        assertThat(eventBundle.getString(EXTRA_LOG_EVENT_PACKAGE_NAME)).isEqualTo("test.package");
        assertThat(eventBundle.getInt(EXTRA_LOG_EVENT_PACKAGE_VERSION)).isEqualTo(3);
        assertThat(eventBundle.getLong(EXTRA_LOG_EVENT_PACKAGE_LONG_VERSION)).isEqualTo(
                (10L << 32) | 3);
        assertThat(eventBundle.getInt("key1")).isEqualTo(4);
        assertThat(eventBundle.getString("key2")).isEqualTo("value2");
    }

    @Test
    public void monitorAgentLoggingResults_onBackup_fillsBundleCorrectly() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test.package";
        // Mock an agent that returns a logging result.
        IBackupAgent agent = setUpLoggingAgentForOperation(OperationType.BACKUP);

        IBackupManagerMonitor monitor =
                BackupManagerMonitorUtils.monitorAgentLoggingResults(
                        mMonitorMock, packageInfo, agent);

        assertCorrectBundleSentToMonitor(monitor, OperationType.BACKUP);
    }

    @Test
    public void monitorAgentLoggingResults_onRestore_fillsBundleCorrectly() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test.package";
        // Mock an agent that returns a logging result.
        IBackupAgent agent = setUpLoggingAgentForOperation(OperationType.RESTORE);

        IBackupManagerMonitor monitor =
                BackupManagerMonitorUtils.monitorAgentLoggingResults(
                        mMonitorMock, packageInfo, agent);

        assertCorrectBundleSentToMonitor(monitor, OperationType.RESTORE);
    }

    private IBackupAgent setUpLoggingAgentForOperation(@OperationType int operationType)
            throws Exception {
        IBackupAgent agent = spy(IBackupAgent.class);
        List<BackupRestoreEventLogger.DataTypeResult> loggingResults = new ArrayList<>();
        loggingResults.add(new BackupRestoreEventLogger.DataTypeResult("testLoggingResult"));
        doAnswer(
                invocation -> {
                    AndroidFuture<List<BackupRestoreEventLogger.DataTypeResult>> in =
                            invocation.getArgument(0);
                    in.complete(loggingResults);
                    return null;
                })
                .when(agent)
                .getLoggerResults(any());
        doAnswer(
                invocation -> {
                    AndroidFuture<Integer> in = invocation.getArgument(0);
                    in.complete(operationType);
                    return null;
                })
                .when(agent)
                .getOperationType(any());
        return agent;
    }

    @Test
    public void sendAgentLoggingResults_onBackup_fillsBundleCorrectly() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test.package";
        List<BackupRestoreEventLogger.DataTypeResult> loggingResults = new ArrayList<>();
        loggingResults.add(new BackupRestoreEventLogger.DataTypeResult("testLoggingResult"));

        IBackupManagerMonitor monitor = BackupManagerMonitorUtils.sendAgentLoggingResults(
                mMonitorMock, packageInfo, loggingResults, OperationType.BACKUP);

        assertCorrectBundleSentToMonitor(monitor, OperationType.BACKUP);
    }

    @Test
    public void sendAgentLoggingResults_onRestore_fillsBundleCorrectly() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test.package";
        List<BackupRestoreEventLogger.DataTypeResult> loggingResults = new ArrayList<>();
        loggingResults.add(new BackupRestoreEventLogger.DataTypeResult("testLoggingResult"));

        IBackupManagerMonitor monitor = BackupManagerMonitorUtils.sendAgentLoggingResults(
                mMonitorMock, packageInfo, loggingResults, OperationType.RESTORE);

        assertCorrectBundleSentToMonitor(monitor, OperationType.RESTORE);
    }

    private void assertCorrectBundleSentToMonitor(IBackupManagerMonitor monitor,
            @OperationType int operationType) throws Exception {


        assertThat(monitor).isEqualTo(mMonitorMock);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mMonitorMock).onEvent(bundleCaptor.capture());
        Bundle eventBundle = bundleCaptor.getValue();
        assertThat(eventBundle.getInt(EXTRA_LOG_OPERATION_TYPE))
                .isEqualTo(operationType);
        assertThat(eventBundle.getInt(EXTRA_LOG_EVENT_ID))
                .isEqualTo(LOG_EVENT_ID_AGENT_LOGGING_RESULTS);
        assertThat(eventBundle.getInt(EXTRA_LOG_EVENT_CATEGORY))
                .isEqualTo(LOG_EVENT_CATEGORY_AGENT);
        assertThat(eventBundle.getString(EXTRA_LOG_EVENT_PACKAGE_NAME)).isEqualTo("test.package");
        List<BackupRestoreEventLogger.DataTypeResult> filledLoggingResults =
                eventBundle.getParcelableArrayList(
                        BackupManagerMonitor.EXTRA_LOG_AGENT_LOGGING_RESULTS,
                        BackupRestoreEventLogger.DataTypeResult.class);
        assertThat(filledLoggingResults.get(0).getDataType()).isEqualTo("testLoggingResult");
    }

    @Test
    public void putMonitoringExtraString_bundleExists_fillsBundleCorrectly() throws Exception {
        Bundle bundle = new Bundle();

        Bundle result = BackupManagerMonitorUtils.putMonitoringExtra(bundle, "key", "value");

        assertThat(result).isEqualTo(bundle);
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.getString("key")).isEqualTo("value");
    }

    @Test
    public void putMonitoringExtraString_bundleDoesNotExist_fillsBundleCorrectly()
            throws Exception {
        Bundle result = BackupManagerMonitorUtils.putMonitoringExtra(null, "key", "value");

        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.getString("key")).isEqualTo("value");
    }


    @Test
    public void putMonitoringExtraLong_bundleExists_fillsBundleCorrectly() throws Exception {
        Bundle bundle = new Bundle();

        Bundle result = BackupManagerMonitorUtils.putMonitoringExtra(bundle, "key", 123);

        assertThat(result).isEqualTo(bundle);
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.getLong("key")).isEqualTo(123);
    }

    @Test
    public void putMonitoringExtraLong_bundleDoesNotExist_fillsBundleCorrectly() throws Exception {
        Bundle result = BackupManagerMonitorUtils.putMonitoringExtra(null, "key", 123);

        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.getLong("key")).isEqualTo(123);
    }

    @Test
    public void putMonitoringExtraBoolean_bundleExists_fillsBundleCorrectly() throws Exception {
        Bundle bundle = new Bundle();

        Bundle result = BackupManagerMonitorUtils.putMonitoringExtra(bundle, "key", true);

        assertThat(result).isEqualTo(bundle);
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.getBoolean("key")).isTrue();
    }

    @Test
    public void putMonitoringExtraBoolean_bundleDoesNotExist_fillsBundleCorrectly()
            throws Exception {
        Bundle result = BackupManagerMonitorUtils.putMonitoringExtra(null, "key", true);

        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.getBoolean("key")).isTrue();
    }
}