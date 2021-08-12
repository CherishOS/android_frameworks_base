/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.apex.ApexSessionInfo;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionInfo.StagedSessionErrorCode;
import android.os.SystemProperties;
import android.os.storage.IStorageManager;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.content.PackageHelper;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.Preconditions;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

@Presubmit
@RunWith(JUnit4.class)
public class StagingManagerTest {
    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Mock private Context mContext;
    @Mock private IStorageManager mStorageManager;
    @Mock private ApexManager mApexManager;

    private File mTmpDir;
    private StagingManager mStagingManager;

    private MockitoSession mMockitoSession;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(eq(Context.POWER_SERVICE))).thenReturn(null);

        mMockitoSession = ExtendedMockito.mockitoSession()
                    .strictness(Strictness.LENIENT)
                    .mockStatic(SystemProperties.class)
                    .mockStatic(PackageHelper.class)
                    .startMocking();

        when(mStorageManager.supportsCheckpoint()).thenReturn(true);
        when(mStorageManager.needsCheckpoint()).thenReturn(true);
        when(PackageHelper.getStorageManager()).thenReturn(mStorageManager);

        when(SystemProperties.get(eq("ro.apex.updatable"))).thenReturn("true");
        when(SystemProperties.get(eq("ro.apex.updatable"), anyString())).thenReturn("true");

        mTmpDir = mTemporaryFolder.newFolder("StagingManagerTest");
        mStagingManager = new StagingManager(mContext, null, mApexManager);
    }

    @After
    public void tearDown() throws Exception {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    /**
     * Tests that sessions committed later shouldn't cause earlier ones to fail the overlapping
     * check.
     */
    @Test
    public void checkNonOverlappingWithStagedSessions_laterSessionShouldNotFailEarlierOnes()
            throws Exception {
        // Create 2 sessions with overlapping packages
        StagingManager.StagedSession session1 = createSession(111, "com.foo", 1);
        StagingManager.StagedSession session2 = createSession(222, "com.foo", 2);

        mStagingManager.createSession(session1);
        mStagingManager.createSession(session2);
        // Session1 should not fail in spite of the overlapping packages
        mStagingManager.checkNonOverlappingWithStagedSessions(session1);
        // Session2 should fail due to overlapping packages
        assertThrows(PackageManagerException.class,
                () -> mStagingManager.checkNonOverlappingWithStagedSessions(session2));
    }

    @Test
    public void restoreSessions_nonParentSession_throwsIAE() throws Exception {
        FakeStagedSession session = new FakeStagedSession(239);
        session.setParentSessionId(1543);

        assertThrows(IllegalArgumentException.class,
                () -> mStagingManager.restoreSessions(Arrays.asList(session), false));
    }

    @Test
    public void restoreSessions_nonCommittedSession_throwsIAE() throws Exception {
        FakeStagedSession session = new FakeStagedSession(239);

        assertThrows(IllegalArgumentException.class,
                () -> mStagingManager.restoreSessions(Arrays.asList(session), false));
    }

    @Test
    public void restoreSessions_terminalSession_throwsIAE() throws Exception {
        FakeStagedSession session = new FakeStagedSession(239);
        session.setCommitted(true);
        session.setSessionApplied();

        assertThrows(IllegalArgumentException.class,
                () -> mStagingManager.restoreSessions(Arrays.asList(session), false));
    }

    @Test
    public void restoreSessions_deviceUpgrading_failsAllSessions() throws Exception {
        FakeStagedSession session1 = new FakeStagedSession(37);
        session1.setCommitted(true);
        FakeStagedSession session2 = new FakeStagedSession(57);
        session2.setCommitted(true);

        mStagingManager.restoreSessions(Arrays.asList(session1, session2), true);

        assertThat(session1.getErrorCode()).isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(session1.getErrorMessage()).isEqualTo("Build fingerprint has changed");

        assertThat(session2.getErrorCode()).isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(session2.getErrorMessage()).isEqualTo("Build fingerprint has changed");
    }

    @Test
    public void restoreSessions_multipleSessions_deviceWithoutFsCheckpointSupport_throwISE()
            throws Exception {
        FakeStagedSession session1 = new FakeStagedSession(37);
        session1.setCommitted(true);
        FakeStagedSession session2 = new FakeStagedSession(57);
        session2.setCommitted(true);

        when(mStorageManager.supportsCheckpoint()).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> mStagingManager.restoreSessions(Arrays.asList(session1, session2), false));
    }

    @Test
    public void restoreSessions_handlesDestroyedAndNotReadySessions() throws Exception {
        FakeStagedSession destroyedApkSession = new FakeStagedSession(23);
        destroyedApkSession.setCommitted(true);
        destroyedApkSession.setDestroyed(true);

        FakeStagedSession destroyedApexSession = new FakeStagedSession(37);
        destroyedApexSession.setCommitted(true);
        destroyedApexSession.setDestroyed(true);
        destroyedApexSession.setIsApex(true);

        FakeStagedSession nonReadyApkSession = new FakeStagedSession(57);
        nonReadyApkSession.setCommitted(true);

        FakeStagedSession nonReadyApexSession = new FakeStagedSession(73);
        nonReadyApexSession.setCommitted(true);
        nonReadyApexSession.setIsApex(true);

        FakeStagedSession destroyedNonReadySession = new FakeStagedSession(101);
        destroyedNonReadySession.setCommitted(true);
        destroyedNonReadySession.setDestroyed(true);

        FakeStagedSession regularApkSession = new FakeStagedSession(239);
        regularApkSession.setCommitted(true);
        regularApkSession.setSessionReady();

        List<StagingManager.StagedSession> sessions = new ArrayList<>();
        sessions.add(destroyedApkSession);
        sessions.add(destroyedApexSession);
        sessions.add(nonReadyApkSession);
        sessions.add(nonReadyApexSession);
        sessions.add(destroyedNonReadySession);
        sessions.add(regularApkSession);

        mStagingManager.restoreSessions(sessions, false);

        assertThat(sessions).containsExactly(regularApkSession);
        assertThat(destroyedApkSession.isDestroyed()).isTrue();
        assertThat(destroyedApexSession.isDestroyed()).isTrue();
        assertThat(destroyedNonReadySession.isDestroyed()).isTrue();

        mStagingManager.onBootCompletedBroadcastReceived();
        assertThat(nonReadyApkSession.hasPreRebootVerificationStarted()).isTrue();
        assertThat(nonReadyApexSession.hasPreRebootVerificationStarted()).isTrue();
    }

    @Test
    public void restoreSessions_unknownApexSession_failsAllSessions() throws Exception {
        FakeStagedSession apkSession = new FakeStagedSession(239);
        apkSession.setCommitted(true);
        apkSession.setSessionReady();

        FakeStagedSession apexSession = new FakeStagedSession(1543);
        apexSession.setCommitted(true);
        apexSession.setIsApex(true);
        apexSession.setSessionReady();

        List<StagingManager.StagedSession> sessions = new ArrayList<>();
        sessions.add(apkSession);
        sessions.add(apexSession);

        when(mApexManager.getSessions()).thenReturn(new SparseArray<>());
        mStagingManager.restoreSessions(sessions, false);

        // Validate checkpoint wasn't aborted.
        verify(mStorageManager, never()).abortChanges(eq("abort-staged-install"), eq(false));

        assertThat(apexSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apexSession.getErrorMessage()).isEqualTo("apexd did not know anything about a "
                + "staged session supposed to be activated");

        assertThat(apkSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apkSession.getErrorMessage()).isEqualTo("Another apex session failed");
    }

    @Test
    public void restoreSessions_failedApexSessions_failsAllSessions() throws Exception {
        FakeStagedSession apkSession = new FakeStagedSession(239);
        apkSession.setCommitted(true);
        apkSession.setSessionReady();

        FakeStagedSession apexSession1 = new FakeStagedSession(1543);
        apexSession1.setCommitted(true);
        apexSession1.setIsApex(true);
        apexSession1.setSessionReady();

        FakeStagedSession apexSession2 = new FakeStagedSession(101);
        apexSession2.setCommitted(true);
        apexSession2.setIsApex(true);
        apexSession2.setSessionReady();

        FakeStagedSession apexSession3 = new FakeStagedSession(57);
        apexSession3.setCommitted(true);
        apexSession3.setIsApex(true);
        apexSession3.setSessionReady();

        ApexSessionInfo activationFailed = new ApexSessionInfo();
        activationFailed.sessionId = 1543;
        activationFailed.isActivationFailed = true;
        activationFailed.errorMessage = "Failed for test";

        ApexSessionInfo staged = new ApexSessionInfo();
        staged.sessionId = 101;
        staged.isStaged = true;

        SparseArray<ApexSessionInfo> apexdSessions = new SparseArray<>();
        apexdSessions.put(1543, activationFailed);
        apexdSessions.put(101, staged);
        when(mApexManager.getSessions()).thenReturn(apexdSessions);

        List<StagingManager.StagedSession> sessions = new ArrayList<>();
        sessions.add(apkSession);
        sessions.add(apexSession1);
        sessions.add(apexSession2);
        sessions.add(apexSession3);

        mStagingManager.restoreSessions(sessions, false);

        // Validate checkpoint wasn't aborted.
        verify(mStorageManager, never()).abortChanges(eq("abort-staged-install"), eq(false));

        assertThat(apexSession1.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apexSession1.getErrorMessage()).isEqualTo("APEX activation failed. "
                + "Error: Failed for test");

        assertThat(apexSession2.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apexSession2.getErrorMessage()).isEqualTo("Staged session 101 at boot didn't "
                + "activate nor fail. Marking it as failed anyway.");

        assertThat(apexSession3.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apexSession3.getErrorMessage()).isEqualTo("apexd did not know anything about a "
                + "staged session supposed to be activated");

        assertThat(apkSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apkSession.getErrorMessage()).isEqualTo("Another apex session failed");
    }

    @Test
    public void restoreSessions_stagedApexSession_failsAllSessions() throws Exception {
        FakeStagedSession apkSession = new FakeStagedSession(239);
        apkSession.setCommitted(true);
        apkSession.setSessionReady();

        FakeStagedSession apexSession = new FakeStagedSession(1543);
        apexSession.setCommitted(true);
        apexSession.setIsApex(true);
        apexSession.setSessionReady();

        ApexSessionInfo staged = new ApexSessionInfo();
        staged.sessionId = 1543;
        staged.isStaged = true;

        SparseArray<ApexSessionInfo> apexdSessions = new SparseArray<>();
        apexdSessions.put(1543, staged);
        when(mApexManager.getSessions()).thenReturn(apexdSessions);

        List<StagingManager.StagedSession> sessions = new ArrayList<>();
        sessions.add(apkSession);
        sessions.add(apexSession);

        mStagingManager.restoreSessions(sessions, false);

        // Validate checkpoint wasn't aborted.
        verify(mStorageManager, never()).abortChanges(eq("abort-staged-install"), eq(false));

        assertThat(apexSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apexSession.getErrorMessage()).isEqualTo("Staged session 1543 at boot didn't "
                + "activate nor fail. Marking it as failed anyway.");

        assertThat(apkSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apkSession.getErrorMessage()).isEqualTo("Another apex session failed");
    }

    @Test
    public void restoreSessions_failedAndActivatedApexSessions_abortsCheckpoint() throws Exception {
        FakeStagedSession apkSession = new FakeStagedSession(239);
        apkSession.setCommitted(true);
        apkSession.setSessionReady();

        FakeStagedSession apexSession1 = new FakeStagedSession(1543);
        apexSession1.setCommitted(true);
        apexSession1.setIsApex(true);
        apexSession1.setSessionReady();

        FakeStagedSession apexSession2 = new FakeStagedSession(101);
        apexSession2.setCommitted(true);
        apexSession2.setIsApex(true);
        apexSession2.setSessionReady();

        FakeStagedSession apexSession3 = new FakeStagedSession(57);
        apexSession3.setCommitted(true);
        apexSession3.setIsApex(true);
        apexSession3.setSessionReady();

        FakeStagedSession apexSession4 = new FakeStagedSession(37);
        apexSession4.setCommitted(true);
        apexSession4.setIsApex(true);
        apexSession4.setSessionReady();

        ApexSessionInfo activationFailed = new ApexSessionInfo();
        activationFailed.sessionId = 1543;
        activationFailed.isActivationFailed = true;

        ApexSessionInfo activated = new ApexSessionInfo();
        activated.sessionId = 101;
        activated.isActivated = true;

        ApexSessionInfo staged = new ApexSessionInfo();
        staged.sessionId = 57;
        staged.isActivationFailed = true;

        SparseArray<ApexSessionInfo> apexdSessions = new SparseArray<>();
        apexdSessions.put(1543, activationFailed);
        apexdSessions.put(101, activated);
        apexdSessions.put(57, staged);
        when(mApexManager.getSessions()).thenReturn(apexdSessions);

        List<StagingManager.StagedSession> sessions = new ArrayList<>();
        sessions.add(apkSession);
        sessions.add(apexSession1);
        sessions.add(apexSession2);
        sessions.add(apexSession3);
        sessions.add(apexSession4);

        mStagingManager.restoreSessions(sessions, false);

        // Validate checkpoint was aborted.
        verify(mStorageManager, times(1)).abortChanges(eq("abort-staged-install"), eq(false));
    }

    @Test
    public void restoreSessions_apexSessionInImpossibleState_failsAllSessions() throws Exception {
        FakeStagedSession apkSession = new FakeStagedSession(239);
        apkSession.setCommitted(true);
        apkSession.setSessionReady();

        FakeStagedSession apexSession = new FakeStagedSession(1543);
        apexSession.setCommitted(true);
        apexSession.setIsApex(true);
        apexSession.setSessionReady();

        ApexSessionInfo impossible  = new ApexSessionInfo();
        impossible.sessionId = 1543;

        SparseArray<ApexSessionInfo> apexdSessions = new SparseArray<>();
        apexdSessions.put(1543, impossible);
        when(mApexManager.getSessions()).thenReturn(apexdSessions);

        List<StagingManager.StagedSession> sessions = new ArrayList<>();
        sessions.add(apkSession);
        sessions.add(apexSession);

        mStagingManager.restoreSessions(sessions, false);

        // Validate checkpoint wasn't aborted.
        verify(mStorageManager, never()).abortChanges(eq("abort-staged-install"), eq(false));

        assertThat(apexSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apexSession.getErrorMessage()).isEqualTo("Impossible state");

        assertThat(apkSession.getErrorCode())
                .isEqualTo(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED);
        assertThat(apkSession.getErrorMessage()).isEqualTo("Another apex session failed");
    }

    @Test
    public void getSessionIdByPackageName() throws Exception {
        FakeStagedSession session = new FakeStagedSession(239);
        session.setCommitted(true);
        session.setSessionReady();
        session.setPackageName("com.foo");

        mStagingManager.createSession(session);
        assertThat(mStagingManager.getSessionIdByPackageName("com.foo")).isEqualTo(239);
    }

    @Test
    public void getSessionIdByPackageName_appliedSession_ignores() throws Exception {
        FakeStagedSession session = new FakeStagedSession(37);
        session.setCommitted(true);
        session.setSessionApplied();
        session.setPackageName("com.foo");

        mStagingManager.createSession(session);
        assertThat(mStagingManager.getSessionIdByPackageName("com.foo")).isEqualTo(-1);
    }

    @Test
    public void getSessionIdByPackageName_failedSession_ignores() throws Exception {
        FakeStagedSession session = new FakeStagedSession(73);
        session.setCommitted(true);
        session.setSessionFailed(1, "whatevs");
        session.setPackageName("com.foo");

        mStagingManager.createSession(session);
        assertThat(mStagingManager.getSessionIdByPackageName("com.foo")).isEqualTo(-1);
    }

    @Test
    public void getSessionIdByPackageName_destroyedSession_ignores() throws Exception {
        FakeStagedSession session = new FakeStagedSession(23);
        session.setCommitted(true);
        session.setDestroyed(true);
        session.setPackageName("com.foo");

        mStagingManager.createSession(session);
        assertThat(mStagingManager.getSessionIdByPackageName("com.foo")).isEqualTo(-1);
    }

    @Test
    public void getSessionIdByPackageName_noSessions() throws Exception {
        assertThat(mStagingManager.getSessionIdByPackageName("com.foo")).isEqualTo(-1);
    }

    @Test
    public void getSessionIdByPackageName_noSessionHasThisPackage() throws Exception {
        FakeStagedSession session = new FakeStagedSession(37);
        session.setCommitted(true);
        session.setSessionApplied();
        session.setPackageName("com.foo");

        mStagingManager.createSession(session);
        assertThat(mStagingManager.getSessionIdByPackageName("com.bar")).isEqualTo(-1);
    }

    private StagingManager.StagedSession createSession(int sessionId, String packageName,
            long committedMillis) {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.isStaged = true;

        InstallSource installSource = InstallSource.create("testInstallInitiator",
                "testInstallOriginator", "testInstaller", "testAttributionTag");

        PackageInstallerSession session = new PackageInstallerSession(
                /* callback */ null,
                /* context */ null,
                /* pm */ null,
                /* sessionProvider */ null,
                /* silentUpdatePolicy */ null,
                /* looper */ BackgroundThread.getHandler().getLooper(),
                /* stagingManager */ null,
                /* sessionId */ sessionId,
                /* userId */ 456,
                /* installerUid */ -1,
                /* installSource */ installSource,
                /* sessionParams */ params,
                /* createdMillis */ 0L,
                /* committedMillis */ committedMillis,
                /* stageDir */ mTmpDir,
                /* stageCid */ null,
                /* files */ null,
                /* checksums */ null,
                /* prepared */ true,
                /* committed */ true,
                /* destroyed */ false,
                /* sealed */ false,  // Setting to true would trigger some PM logic.
                /* childSessionIds */ null,
                /* parentSessionId */ -1,
                /* isReady */ false,
                /* isFailed */ false,
                /* isApplied */false,
                /* stagedSessionErrorCode */ PackageInstaller.SessionInfo.STAGED_SESSION_NO_ERROR,
                /* stagedSessionErrorMessage */ "no error");

        StagingManager.StagedSession stagedSession = spy(session.mStagedSession);
        doReturn(packageName).when(stagedSession).getPackageName();
        doAnswer(invocation -> {
            Predicate<StagingManager.StagedSession> filter = invocation.getArgument(0);
            return filter.test(stagedSession);
        }).when(stagedSession).sessionContains(any());
        return stagedSession;
    }

    private static final class FakeStagedSession implements StagingManager.StagedSession {
        private final int mSessionId;
        private boolean mIsApex = false;
        private boolean mIsCommitted = false;
        private boolean mIsReady = false;
        private boolean mIsApplied = false;
        private boolean mIsFailed = false;
        private @StagedSessionErrorCode int mErrorCode = -1;
        private String mErrorMessage;
        private boolean mIsDestroyed = false;
        private int mParentSessionId = -1;
        private String mPackageName;
        private boolean mIsAbandonded = false;
        private boolean mPreRebootVerificationStarted = false;
        private final List<StagingManager.StagedSession> mChildSessions = new ArrayList<>();

        private FakeStagedSession(int sessionId) {
            mSessionId = sessionId;
        }

        private void setParentSessionId(int parentSessionId) {
            mParentSessionId = parentSessionId;
        }

        private void setCommitted(boolean isCommitted) {
            mIsCommitted = isCommitted;
        }

        private void setIsApex(boolean isApex) {
            mIsApex = isApex;
        }

        private void setDestroyed(boolean isDestroyed) {
            mIsDestroyed = isDestroyed;
        }

        private void setPackageName(String packageName) {
            mPackageName = packageName;
        }

        private boolean isAbandonded() {
            return mIsAbandonded;
        }

        private boolean hasPreRebootVerificationStarted() {
            return mPreRebootVerificationStarted;
        }

        private FakeStagedSession addChildSession(FakeStagedSession session) {
            mChildSessions.add(session);
            session.setParentSessionId(sessionId());
            return this;
        }

        private @StagedSessionErrorCode int getErrorCode() {
            return mErrorCode;
        }

        private String getErrorMessage() {
            return mErrorMessage;
        }

        @Override
        public boolean isMultiPackage() {
            return !mChildSessions.isEmpty();
        }

        @Override
        public boolean isApexSession() {
            return mIsApex;
        }

        @Override
        public boolean isCommitted() {
            return mIsCommitted;
        }

        @Override
        public boolean isInTerminalState() {
            return isSessionApplied() || isSessionFailed();
        }

        @Override
        public boolean isDestroyed() {
            return mIsDestroyed;
        }

        @Override
        public boolean isSessionReady() {
            return mIsReady;
        }

        @Override
        public boolean isSessionApplied() {
            return mIsApplied;
        }

        @Override
        public boolean isSessionFailed() {
            return mIsFailed;
        }

        @Override
        public List<StagingManager.StagedSession> getChildSessions() {
            return mChildSessions;
        }

        @Override
        public String getPackageName() {
            return mPackageName;
        }

        @Override
        public int getParentSessionId() {
            return mParentSessionId;
        }

        @Override
        public int sessionId() {
            return mSessionId;
        }

        @Override
        public PackageInstaller.SessionParams sessionParams() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean sessionContains(Predicate<StagingManager.StagedSession> filter) {
            return filter.test(this);
        }

        @Override
        public boolean containsApkSession() {
            Preconditions.checkState(!hasParentSessionId(), "Child session");
            if (!isMultiPackage()) {
                return !isApexSession();
            }
            for (StagingManager.StagedSession session : mChildSessions) {
                if (!session.isApexSession()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean containsApexSession() {
            Preconditions.checkState(!hasParentSessionId(), "Child session");
            if (!isMultiPackage()) {
                return isApexSession();
            }
            for (StagingManager.StagedSession session : mChildSessions) {
                if (session.isApexSession()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void setSessionReady() {
            mIsReady = true;
        }

        @Override
        public void setSessionFailed(@StagedSessionErrorCode int errorCode, String errorMessage) {
            Preconditions.checkState(!mIsApplied, "Already marked as applied");
            mIsFailed = true;
            mErrorCode = errorCode;
            mErrorMessage = errorMessage;
        }

        @Override
        public void setSessionApplied() {
            Preconditions.checkState(!mIsFailed, "Already marked as failed");
            mIsApplied = true;
        }

        @Override
        public void installSession(IntentSender statusReceiver) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasParentSessionId() {
            return mParentSessionId != -1;
        }

        @Override
        public long getCommittedMillis() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abandon() {
            mIsAbandonded = true;
        }

        @Override
        public boolean notifyStartPreRebootVerification() {
            mPreRebootVerificationStarted = true;
            // TODO(ioffe): change to true when tests for pre-reboot verification are added.
            return false;
        }

        @Override
        public void notifyEndPreRebootVerification() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void verifySession() {
            throw new UnsupportedOperationException();
        }
    }
}
