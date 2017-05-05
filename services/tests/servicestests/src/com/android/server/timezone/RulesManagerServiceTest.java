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
 * limitations under the License.
 */

package com.android.server.timezone;

import org.junit.Before;
import org.junit.Test;

import android.app.timezone.Callback;
import android.app.timezone.DistroRulesVersion;
import android.app.timezone.ICallback;
import android.app.timezone.RulesManager;
import android.app.timezone.RulesState;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import libcore.tzdata.shared2.DistroVersion;
import libcore.tzdata.shared2.StagedDistroOperation;
import libcore.tzdata.update2.TimeZoneDistroInstaller;

import static com.android.server.timezone.RulesManagerService.REQUIRED_UPDATER_PERMISSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * White box interaction / unit testing of the {@link RulesManagerService}.
 */
public class RulesManagerServiceTest {

    private RulesManagerService mRulesManagerService;

    private FakeExecutor mFakeExecutor;
    private PermissionHelper mMockPermissionHelper;
    private FileDescriptorHelper mMockFileDescriptorHelper;
    private PackageTracker mMockPackageTracker;
    private TimeZoneDistroInstaller mMockTimeZoneDistroInstaller;

    @Before
    public void setUp() {
        mFakeExecutor = new FakeExecutor();

        mMockFileDescriptorHelper = mock(FileDescriptorHelper.class);
        mMockPackageTracker = mock(PackageTracker.class);
        mMockPermissionHelper = mock(PermissionHelper.class);
        mMockTimeZoneDistroInstaller = mock(TimeZoneDistroInstaller.class);

        mRulesManagerService = new RulesManagerService(
                mMockPermissionHelper,
                mFakeExecutor,
                mMockFileDescriptorHelper,
                mMockPackageTracker,
                mMockTimeZoneDistroInstaller);
    }

    @Test(expected = SecurityException.class)
    public void getRulesState_noCallerPermission() throws Exception {
        configureCallerDoesNotHavePermission();
        mRulesManagerService.getRulesState();
    }

    @Test(expected = SecurityException.class)
    public void requestInstall_noCallerPermission() throws Exception {
        configureCallerDoesNotHavePermission();
        mRulesManagerService.requestInstall(null, null, null);
    }

    @Test(expected = SecurityException.class)
    public void requestUninstall_noCallerPermission() throws Exception {
        configureCallerDoesNotHavePermission();
        mRulesManagerService.requestUninstall(null, null);
    }

    @Test(expected = SecurityException.class)
    public void requestNothing_noCallerPermission() throws Exception {
        configureCallerDoesNotHavePermission();
        mRulesManagerService.requestNothing(null, true);
    }

    @Test
    public void getRulesState_systemRulesError() throws Exception {
        configureDeviceCannotReadSystemRulesVersion();

        assertNull(mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_stagedInstall() throws Exception {
        configureCallerHasPermission();

        configureDeviceSystemRulesVersion("2016a");

        DistroVersion stagedDistroVersion = new DistroVersion(
                DistroVersion.CURRENT_FORMAT_MAJOR_VERSION,
                DistroVersion.CURRENT_FORMAT_MINOR_VERSION - 1,
                "2016c",
                3);
        configureStagedInstall(stagedDistroVersion);

        DistroVersion installedDistroVersion = new DistroVersion(
                DistroVersion.CURRENT_FORMAT_MAJOR_VERSION,
                DistroVersion.CURRENT_FORMAT_MINOR_VERSION - 1,
                "2016b",
                4);
        configureInstalledDistroVersion(installedDistroVersion);

        DistroRulesVersion stagedDistroRulesVersion = new DistroRulesVersion(
                stagedDistroVersion.rulesVersion, stagedDistroVersion.revision);
        DistroRulesVersion installedDistroRulesVersion = new DistroRulesVersion(
                installedDistroVersion.rulesVersion, installedDistroVersion.revision);
        RulesState expectedRuleState = new RulesState(
                "2016a", RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                false /* operationInProgress */,
                RulesState.STAGED_OPERATION_INSTALL, stagedDistroRulesVersion,
                RulesState.DISTRO_STATUS_INSTALLED, installedDistroRulesVersion);
        assertEquals(expectedRuleState, mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_nothingStaged() throws Exception {
        configureCallerHasPermission();

        configureDeviceSystemRulesVersion("2016a");

        configureNoStagedOperation();

        DistroVersion installedDistroVersion = new DistroVersion(
                DistroVersion.CURRENT_FORMAT_MAJOR_VERSION,
                DistroVersion.CURRENT_FORMAT_MINOR_VERSION - 1,
                "2016b",
                4);
        configureInstalledDistroVersion(installedDistroVersion);

        DistroRulesVersion installedDistroRulesVersion = new DistroRulesVersion(
                installedDistroVersion.rulesVersion, installedDistroVersion.revision);
        RulesState expectedRuleState = new RulesState(
                "2016a", RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                false /* operationInProgress */,
                RulesState.STAGED_OPERATION_NONE, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_INSTALLED, installedDistroRulesVersion);
        assertEquals(expectedRuleState, mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_uninstallStaged() throws Exception {
        configureCallerHasPermission();

        configureDeviceSystemRulesVersion("2016a");

        configureStagedUninstall();

        DistroVersion installedDistroVersion = new DistroVersion(
                DistroVersion.CURRENT_FORMAT_MAJOR_VERSION,
                DistroVersion.CURRENT_FORMAT_MINOR_VERSION - 1,
                "2016b",
                4);
        configureInstalledDistroVersion(installedDistroVersion);

        DistroRulesVersion installedDistroRulesVersion = new DistroRulesVersion(
                installedDistroVersion.rulesVersion, installedDistroVersion.revision);
        RulesState expectedRuleState = new RulesState(
                "2016a", RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                false /* operationInProgress */,
                RulesState.STAGED_OPERATION_UNINSTALL, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_INSTALLED, installedDistroRulesVersion);
        assertEquals(expectedRuleState, mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_installedRulesError() throws Exception {
        configureCallerHasPermission();

        String systemRulesVersion = "2016a";
        configureDeviceSystemRulesVersion(systemRulesVersion);

        configureStagedUninstall();
        configureDeviceCannotReadInstalledDistroVersion();

        RulesState expectedRuleState = new RulesState(
                "2016a", RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                false /* operationInProgress */,
                RulesState.STAGED_OPERATION_UNINSTALL, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_UNKNOWN, null /* installedDistroRulesVersion */);
        assertEquals(expectedRuleState, mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_stagedRulesError() throws Exception {
        configureCallerHasPermission();

        String systemRulesVersion = "2016a";
        configureDeviceSystemRulesVersion(systemRulesVersion);

        configureDeviceCannotReadStagedDistroOperation();

        DistroVersion installedDistroVersion = new DistroVersion(
                DistroVersion.CURRENT_FORMAT_MAJOR_VERSION,
                DistroVersion.CURRENT_FORMAT_MINOR_VERSION - 1,
                "2016b",
                4);
        configureInstalledDistroVersion(installedDistroVersion);

        DistroRulesVersion installedDistroRulesVersion = new DistroRulesVersion(
                installedDistroVersion.rulesVersion, installedDistroVersion.revision);
        RulesState expectedRuleState = new RulesState(
                "2016a", RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                false /* operationInProgress */,
                RulesState.STAGED_OPERATION_UNKNOWN, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_INSTALLED, installedDistroRulesVersion);
        assertEquals(expectedRuleState, mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_noInstalledRules() throws Exception {
        configureCallerHasPermission();

        String systemRulesVersion = "2016a";
        configureDeviceSystemRulesVersion(systemRulesVersion);
        configureNoStagedOperation();
        configureInstalledDistroVersion(null);

        RulesState expectedRuleState = new RulesState(
                systemRulesVersion, RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                false /* operationInProgress */,
                RulesState.STAGED_OPERATION_NONE, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_NONE, null /* installedDistroRulesVersion */);
        assertEquals(expectedRuleState, mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_operationInProgress() throws Exception {
        configureCallerHasPermission();

        String systemRulesVersion = "2016a";
        String installedRulesVersion = "2016b";
        int revision = 3;

        configureDeviceSystemRulesVersion(systemRulesVersion);

        DistroVersion installedDistroVersion = new DistroVersion(
                DistroVersion.CURRENT_FORMAT_MAJOR_VERSION,
                DistroVersion.CURRENT_FORMAT_MINOR_VERSION - 1,
                installedRulesVersion,
                revision);
        configureInstalledDistroVersion(installedDistroVersion);

        byte[] expectedContent = createArbitraryBytes(1000);
        ParcelFileDescriptor parcelFileDescriptor = createFakeParcelFileDescriptor();
        configureParcelFileDescriptorReadSuccess(parcelFileDescriptor, expectedContent);

        // Start an async operation so there is one in progress. The mFakeExecutor won't actually
        // execute it.
        byte[] tokenBytes = createArbitraryTokenBytes();
        ICallback callback = new StubbedCallback();

        mRulesManagerService.requestInstall(parcelFileDescriptor, tokenBytes, callback);

        RulesState expectedRuleState = new RulesState(
                systemRulesVersion, RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                true /* operationInProgress */,
                RulesState.STAGED_OPERATION_UNKNOWN, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_UNKNOWN, null /* installedDistroRulesVersion */);
        assertEquals(expectedRuleState, mRulesManagerService.getRulesState());
    }

    @Test
    public void requestInstall_operationInProgress() throws Exception {
        configureCallerHasPermission();

        byte[] expectedContent = createArbitraryBytes(1000);
        ParcelFileDescriptor parcelFileDescriptor = createFakeParcelFileDescriptor();
        configureParcelFileDescriptorReadSuccess(parcelFileDescriptor, expectedContent);

        byte[] tokenBytes = createArbitraryTokenBytes();
        ICallback callback = new StubbedCallback();

        // First request should succeed.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestInstall(parcelFileDescriptor, tokenBytes, callback));

        // Something async should be enqueued. Clear it but do not execute it so we can detect the
        // second request does nothing.
        mFakeExecutor.getAndResetLastCommand();

        // Second request should fail.
        assertEquals(RulesManager.ERROR_OPERATION_IN_PROGRESS,
                mRulesManagerService.requestInstall(parcelFileDescriptor, tokenBytes, callback));

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
    }

    @Test
    public void requestInstall_badToken() throws Exception {
        configureCallerHasPermission();

        byte[] expectedContent = createArbitraryBytes(1000);
        ParcelFileDescriptor parcelFileDescriptor = createFakeParcelFileDescriptor();
        configureParcelFileDescriptorReadSuccess(parcelFileDescriptor, expectedContent);

        byte[] badTokenBytes = new byte[2];
        ICallback callback = new StubbedCallback();

        try {
            mRulesManagerService.requestInstall(parcelFileDescriptor, badTokenBytes, callback);
            fail();
        } catch (IllegalArgumentException expected) {
        }

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
    }

    @Test
    public void requestInstall_nullParcelFileDescriptor() throws Exception {
        configureCallerHasPermission();

        ParcelFileDescriptor parcelFileDescriptor = null;
        byte[] tokenBytes = createArbitraryTokenBytes();
        ICallback callback = new StubbedCallback();

        try {
            mRulesManagerService.requestInstall(parcelFileDescriptor, tokenBytes, callback);
            fail();
        } catch (NullPointerException expected) {}

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
    }

    @Test
    public void requestInstall_nullCallback() throws Exception {
        configureCallerHasPermission();

        ParcelFileDescriptor parcelFileDescriptor = createFakeParcelFileDescriptor();
        byte[] tokenBytes = createArbitraryTokenBytes();
        ICallback callback = null;

        try {
            mRulesManagerService.requestInstall(parcelFileDescriptor, tokenBytes, callback);
            fail();
        } catch (NullPointerException expected) {}

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
    }

    @Test
    public void requestInstall_asyncSuccess() throws Exception {
        configureCallerHasPermission();

        ParcelFileDescriptor parcelFileDescriptor = createFakeParcelFileDescriptor();
        byte[] expectedContent = createArbitraryBytes(1000);
        configureParcelFileDescriptorReadSuccess(parcelFileDescriptor, expectedContent);

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        TestCallback callback = new TestCallback();

        // Request the install.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestInstall(parcelFileDescriptor, tokenBytes, callback));

        // Assert nothing has happened yet.
        callback.assertNoResultReceived();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();

        // Set up the installer.
        configureStageInstallExpectation(expectedContent, TimeZoneDistroInstaller.INSTALL_SUCCESS);

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        // Verify the expected calls were made to other components.
        verifyStageInstallCalled(expectedContent);
        verifyPackageTrackerCalled(token, true /* success */);

        // Check the callback was called.
        callback.assertResultReceived(Callback.SUCCESS);
    }

    @Test
    public void requestInstall_nullTokenBytes() throws Exception {
        configureCallerHasPermission();

        ParcelFileDescriptor parcelFileDescriptor = createFakeParcelFileDescriptor();
        byte[] expectedContent = createArbitraryBytes(1000);
        configureParcelFileDescriptorReadSuccess(parcelFileDescriptor, expectedContent);

        TestCallback callback = new TestCallback();

        // Request the install.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestInstall(
                        parcelFileDescriptor, null /* tokenBytes */, callback));

        // Assert nothing has happened yet.
        verifyNoInstallerCallsMade();
        callback.assertNoResultReceived();

        // Set up the installer.
        configureStageInstallExpectation(expectedContent, TimeZoneDistroInstaller.INSTALL_SUCCESS);

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        // Verify the expected calls were made to other components.
        verifyStageInstallCalled(expectedContent);
        verifyPackageTrackerCalled(null /* expectedToken */, true /* success */);

        // Check the callback was received.
        callback.assertResultReceived(Callback.SUCCESS);
    }

    @Test
    public void requestInstall_asyncInstallFail() throws Exception {
        configureCallerHasPermission();

        byte[] expectedContent = createArbitraryBytes(1000);
        ParcelFileDescriptor parcelFileDescriptor = createFakeParcelFileDescriptor();
        configureParcelFileDescriptorReadSuccess(parcelFileDescriptor, expectedContent);

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        TestCallback callback = new TestCallback();

        // Request the install.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestInstall(parcelFileDescriptor, tokenBytes, callback));

        // Assert nothing has happened yet.
        verifyNoInstallerCallsMade();
        callback.assertNoResultReceived();

        // Set up the installer.
        configureStageInstallExpectation(
                expectedContent, TimeZoneDistroInstaller.INSTALL_FAIL_VALIDATION_ERROR);

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        // Verify the expected calls were made to other components.
        verifyStageInstallCalled(expectedContent);

        // Validation failure is treated like a successful check: repeating it won't improve things.
        boolean expectedSuccess = true;
        verifyPackageTrackerCalled(token, expectedSuccess);

        // Check the callback was received.
        callback.assertResultReceived(Callback.ERROR_INSTALL_VALIDATION_ERROR);
    }

    @Test
    public void requestInstall_asyncParcelFileDescriptorReadFail() throws Exception {
        configureCallerHasPermission();

        ParcelFileDescriptor parcelFileDescriptor = createFakeParcelFileDescriptor();
        configureParcelFileDescriptorReadFailure(parcelFileDescriptor);

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        TestCallback callback = new TestCallback();

        // Request the install.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestInstall(parcelFileDescriptor, tokenBytes, callback));

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        // Verify nothing else happened.
        verifyNoInstallerCallsMade();

        // A failure to read the ParcelFileDescriptor is treated as a failure. It might be the
        // result of a file system error. This is a fairly arbitrary choice.
        verifyPackageTrackerCalled(token, false /* success */);

        verifyNoPackageTrackerCallsMade();

        // Check the callback was received.
        callback.assertResultReceived(Callback.ERROR_UNKNOWN_FAILURE);
    }

    @Test
    public void requestUninstall_operationInProgress() throws Exception {
        configureCallerHasPermission();

        byte[] tokenBytes = createArbitraryTokenBytes();
        ICallback callback = new StubbedCallback();

        // First request should succeed.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestUninstall(tokenBytes, callback));

        // Something async should be enqueued. Clear it but do not execute it so we can detect the
        // second request does nothing.
        mFakeExecutor.getAndResetLastCommand();

        // Second request should fail.
        assertEquals(RulesManager.ERROR_OPERATION_IN_PROGRESS,
                mRulesManagerService.requestUninstall(tokenBytes, callback));

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
    }

    @Test
    public void requestUninstall_badToken() throws Exception {
        configureCallerHasPermission();

        byte[] badTokenBytes = new byte[2];
        ICallback callback = new StubbedCallback();

        try {
            mRulesManagerService.requestUninstall(badTokenBytes, callback);
            fail();
        } catch (IllegalArgumentException expected) {
        }

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
    }

    @Test
    public void requestUninstall_nullCallback() throws Exception {
        configureCallerHasPermission();

        byte[] tokenBytes = createArbitraryTokenBytes();
        ICallback callback = null;

        try {
            mRulesManagerService.requestUninstall(tokenBytes, callback);
            fail();
        } catch (NullPointerException expected) {}

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
    }

    @Test
    public void requestUninstall_asyncSuccess() throws Exception {
        configureCallerHasPermission();

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        TestCallback callback = new TestCallback();

        // Request the uninstall.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestUninstall(tokenBytes, callback));

        // Assert nothing has happened yet.
        callback.assertNoResultReceived();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();

        // Set up the installer.
        configureStageUninstallExpectation(true /* success */);

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        // Verify the expected calls were made to other components.
        verifyStageUninstallCalled();
        verifyPackageTrackerCalled(token, true /* success */);

        // Check the callback was called.
        callback.assertResultReceived(Callback.SUCCESS);
    }

    @Test
    public void requestUninstall_nullTokenBytes() throws Exception {
        configureCallerHasPermission();

        TestCallback callback = new TestCallback();

        // Request the uninstall.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestUninstall(null /* tokenBytes */, callback));

        // Assert nothing has happened yet.
        verifyNoInstallerCallsMade();
        callback.assertNoResultReceived();

        // Set up the installer.
        configureStageUninstallExpectation(true /* success */);

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        // Verify the expected calls were made to other components.
        verifyStageUninstallCalled();
        verifyPackageTrackerCalled(null /* expectedToken */, true /* success */);

        // Check the callback was received.
        callback.assertResultReceived(Callback.SUCCESS);
    }

    @Test
    public void requestUninstall_asyncUninstallFail() throws Exception {
        configureCallerHasPermission();

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        TestCallback callback = new TestCallback();

        // Request the uninstall.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestUninstall(tokenBytes, callback));

        // Assert nothing has happened yet.
        verifyNoInstallerCallsMade();
        callback.assertNoResultReceived();

        // Set up the installer.
        configureStageUninstallExpectation(false /* success */);

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        // Verify the expected calls were made to other components.
        verifyStageUninstallCalled();
        verifyPackageTrackerCalled(token, false /* success */);

        // Check the callback was received.
        callback.assertResultReceived(Callback.ERROR_UNKNOWN_FAILURE);
    }

    @Test
    public void requestNothing_operationInProgressOk() throws Exception {
        configureCallerHasPermission();

        // Set up a parallel operation.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestUninstall(null, new StubbedCallback()));
        // Something async should be enqueued. Clear it but do not execute it to simulate it still
        // being in progress.
        mFakeExecutor.getAndResetLastCommand();

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        // Make the call.
        mRulesManagerService.requestNothing(tokenBytes, true /* success */);

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();

        // Verify the expected calls were made to other components.
        verifyPackageTrackerCalled(token, true /* success */);
        verifyNoInstallerCallsMade();
    }

    @Test
    public void requestNothing_badToken() throws Exception {
        configureCallerHasPermission();

        byte[] badTokenBytes = new byte[2];

        try {
            mRulesManagerService.requestNothing(badTokenBytes, true /* success */);
            fail();
        } catch (IllegalArgumentException expected) {
        }

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();

        // Assert no other calls were made.
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
    }

    @Test
    public void requestNothing() throws Exception {
        configureCallerHasPermission();

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        // Make the call.
        mRulesManagerService.requestNothing(tokenBytes, false /* success */);

        // Assert everything required was done.
        verifyNoInstallerCallsMade();
        verifyPackageTrackerCalled(token, false /* success */);
    }

    @Test
    public void requestNothing_nullTokenBytes() throws Exception {
        configureCallerHasPermission();

        // Make the call.
        mRulesManagerService.requestNothing(null /* tokenBytes */, true /* success */);

        // Assert everything required was done.
        verifyNoInstallerCallsMade();
        verifyPackageTrackerCalled(null /* token */, true /* success */);
    }

    private void verifyNoPackageTrackerCallsMade() {
        verifyNoMoreInteractions(mMockPackageTracker);
        reset(mMockPackageTracker);
    }

    private void verifyPackageTrackerCalled(
            CheckToken expectedCheckToken, boolean expectedSuccess) {
        verify(mMockPackageTracker).recordCheckResult(expectedCheckToken, expectedSuccess);
        reset(mMockPackageTracker);
    }

    private void configureCallerHasPermission() throws Exception {
        doNothing()
                .when(mMockPermissionHelper)
                .enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);
    }

    private void configureCallerDoesNotHavePermission() {
        doThrow(new SecurityException("Simulated permission failure"))
                .when(mMockPermissionHelper)
                .enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);
    }

    private void configureParcelFileDescriptorReadSuccess(ParcelFileDescriptor parcelFileDescriptor,
            byte[] content) throws Exception {
        when(mMockFileDescriptorHelper.readFully(parcelFileDescriptor)).thenReturn(content);
    }

    private void configureParcelFileDescriptorReadFailure(ParcelFileDescriptor parcelFileDescriptor)
            throws Exception {
        when(mMockFileDescriptorHelper.readFully(parcelFileDescriptor))
                .thenThrow(new IOException("Simulated failure"));
    }

    private void configureStageInstallExpectation(byte[] expectedContent, int resultCode)
            throws Exception {
        when(mMockTimeZoneDistroInstaller.stageInstallWithErrorCode(eq(expectedContent)))
                .thenReturn(resultCode);
    }

    private void configureStageUninstallExpectation(boolean success) throws Exception {
        doReturn(success).when(mMockTimeZoneDistroInstaller).stageUninstall();
    }

    private void verifyStageInstallCalled(byte[] expectedContent) throws Exception {
        verify(mMockTimeZoneDistroInstaller).stageInstallWithErrorCode(eq(expectedContent));
        verifyNoMoreInteractions(mMockTimeZoneDistroInstaller);
        reset(mMockTimeZoneDistroInstaller);
    }

    private void verifyStageUninstallCalled() throws Exception {
        verify(mMockTimeZoneDistroInstaller).stageUninstall();
        verifyNoMoreInteractions(mMockTimeZoneDistroInstaller);
        reset(mMockTimeZoneDistroInstaller);
    }

    private void verifyNoInstallerCallsMade() {
        verifyNoMoreInteractions(mMockTimeZoneDistroInstaller);
        reset(mMockTimeZoneDistroInstaller);
    }

    private static byte[] createArbitraryBytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    private byte[] createArbitraryTokenBytes() {
        return createArbitraryToken().toByteArray();
    }

    private CheckToken createArbitraryToken() {
        return new CheckToken(1, new PackageVersions(1, 1));
    }

    private ParcelFileDescriptor createFakeParcelFileDescriptor() {
        return new ParcelFileDescriptor((ParcelFileDescriptor) null);
    }

    private void configureDeviceSystemRulesVersion(String systemRulesVersion) throws Exception {
        when(mMockTimeZoneDistroInstaller.getSystemRulesVersion()).thenReturn(systemRulesVersion);
    }

    private void configureInstalledDistroVersion(@Nullable DistroVersion installedDistroVersion)
            throws Exception {
        when(mMockTimeZoneDistroInstaller.getInstalledDistroVersion())
                .thenReturn(installedDistroVersion);
    }

    private void configureStagedInstall(DistroVersion stagedDistroVersion) throws Exception {
        when(mMockTimeZoneDistroInstaller.getStagedDistroOperation())
                .thenReturn(StagedDistroOperation.install(stagedDistroVersion));
    }

    private void configureStagedUninstall() throws Exception {
        when(mMockTimeZoneDistroInstaller.getStagedDistroOperation())
                .thenReturn(StagedDistroOperation.uninstall());
    }

    private void configureNoStagedOperation() throws Exception {
        when(mMockTimeZoneDistroInstaller.getStagedDistroOperation()).thenReturn(null);
    }

    private void configureDeviceCannotReadStagedDistroOperation() throws Exception {
        when(mMockTimeZoneDistroInstaller.getStagedDistroOperation())
                .thenThrow(new IOException("Simulated failure"));
    }

    private void configureDeviceCannotReadSystemRulesVersion() throws Exception {
        when(mMockTimeZoneDistroInstaller.getSystemRulesVersion())
                .thenThrow(new IOException("Simulated failure"));
    }

    private void configureDeviceCannotReadInstalledDistroVersion() throws Exception {
        when(mMockTimeZoneDistroInstaller.getInstalledDistroVersion())
                .thenThrow(new IOException("Simulated failure"));
    }

    private static class FakeExecutor implements Executor {

        private Runnable mLastCommand;

        @Override
        public void execute(Runnable command) {
            assertNull(mLastCommand);
            assertNotNull(command);
            mLastCommand = command;
        }

        public Runnable getAndResetLastCommand() {
            assertNotNull(mLastCommand);
            Runnable toReturn = mLastCommand;
            mLastCommand = null;
            return toReturn;
        }

        public void simulateAsyncExecutionOfLastCommand() {
            Runnable toRun = getAndResetLastCommand();
            toRun.run();
        }

        public void assertNothingQueued() {
            assertNull(mLastCommand);
        }
    }

    private static class TestCallback extends ICallback.Stub {

        private boolean mOnFinishedCalled;
        private int mLastError;

        @Override
        public void onFinished(int error) {
            assertFalse(mOnFinishedCalled);
            mOnFinishedCalled = true;
            mLastError = error;
        }

        public void assertResultReceived(int expectedResult) {
            assertTrue(mOnFinishedCalled);
            assertEquals(expectedResult, mLastError);
        }

        public void assertNoResultReceived() {
            assertFalse(mOnFinishedCalled);
        }
    }

    private static class StubbedCallback extends ICallback.Stub {
        @Override
        public void onFinished(int error) {
            fail("Unexpected call");
        }
    }
}
