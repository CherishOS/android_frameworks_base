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

package com.android.server.pm;

import android.annotation.NonNull;
import android.apex.ApexInfo;
import android.apex.ApexInfoList;
import android.apex.ApexSessionInfo;
import android.apex.IApexService;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.PackageParser.SigningDetails;
import android.content.pm.PackageParser.SigningDetails.SignatureSchemeVersion;
import android.content.pm.ParceledListSlice;
import android.content.pm.Signature;
import android.content.rollback.IRollbackManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * This class handles staged install sessions, i.e. install sessions that require packages to
 * be installed only after a reboot.
 */
public class StagingManager {

    private static final String TAG = "StagingManager";

    private final PackageInstallerService mPi;
    private final PackageManagerService mPm;
    private final Handler mBgHandler;

    @GuardedBy("mStagedSessions")
    private final SparseArray<PackageInstallerSession> mStagedSessions = new SparseArray<>();

    StagingManager(PackageManagerService pm, PackageInstallerService pi) {
        mPm = pm;
        mPi = pi;
        mBgHandler = BackgroundThread.getHandler();
    }

    private void updateStoredSession(@NonNull PackageInstallerSession sessionInfo) {
        synchronized (mStagedSessions) {
            PackageInstallerSession storedSession = mStagedSessions.get(sessionInfo.sessionId);
            // storedSession might be null if a call to abortSession was made before the session
            // is updated.
            if (storedSession != null) {
                mStagedSessions.put(sessionInfo.sessionId, sessionInfo);
            }
        }
    }

    ParceledListSlice<PackageInstaller.SessionInfo> getSessions() {
        final List<PackageInstaller.SessionInfo> result = new ArrayList<>();
        synchronized (mStagedSessions) {
            for (int i = 0; i < mStagedSessions.size(); i++) {
                result.add(mStagedSessions.valueAt(i).generateInfo(false));
            }
        }
        return new ParceledListSlice<>(result);
    }

    private static boolean validateApexSignature(String apexPath, String packageName) {
        final SigningDetails signingDetails;
        try {
            signingDetails = ApkSignatureVerifier.verify(apexPath, SignatureSchemeVersion.JAR);
        } catch (PackageParserException e) {
            Slog.e(TAG, "Unable to parse APEX package: " + apexPath, e);
            return false;
        }

        final IApexService apex = IApexService.Stub.asInterface(
                ServiceManager.getService("apexservice"));
        final ApexInfo apexInfo;
        try {
            apexInfo = apex.getActivePackage(packageName);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact APEXD", re);
            return false;
        }

        if (apexInfo == null || TextUtils.isEmpty(apexInfo.packageName)) {
            // TODO: What is the right thing to do here ? This implies there's no active package
            // with the given name. This should never be the case in production (where we only
            // accept updates to existing APEXes) but may be required for testing.
            return true;
        }

        final SigningDetails existingSigningDetails;
        try {
            existingSigningDetails = ApkSignatureVerifier.verify(
                apexInfo.packagePath, SignatureSchemeVersion.JAR);
        } catch (PackageParserException e) {
            Slog.e(TAG, "Unable to parse APEX package: " + apexInfo.packagePath, e);
            return false;
        }

        // Now that we have both sets of signatures, demand that they're an exact match.
        if (Signature.areExactMatch(existingSigningDetails.signatures, signingDetails.signatures)) {
            return true;
        }

        return false;
    }

    private static boolean submitSessionToApexService(@NonNull PackageInstallerSession session,
                                                      List<PackageInstallerSession> childSessions,
                                                      ApexInfoList apexInfoList) {
        return sendSubmitStagedSessionRequest(
                session.sessionId,
                childSessions != null
                        ? childSessions.stream().mapToInt(s -> s.sessionId).toArray() :
                        new int[]{},
                apexInfoList);
    }

    private static boolean sendSubmitStagedSessionRequest(
            int sessionId, int[] childSessionIds, ApexInfoList apexInfoList) {
        final IApexService apex = IApexService.Stub.asInterface(
                ServiceManager.getService("apexservice"));
        boolean success;
        try {
            success = apex.submitStagedSession(sessionId, childSessionIds, apexInfoList);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            return false;
        }
        return success;
    }

    private static boolean sendMarkStagedSessionReadyRequest(int sessionId) {
        final IApexService apex = IApexService.Stub.asInterface(
                ServiceManager.getService("apexservice"));
        boolean success;
        try {
            success = apex.markStagedSessionReady(sessionId);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            return false;
        }
        return success;
    }

    private static boolean isApexSession(@NonNull PackageInstallerSession session) {
        return (session.params.installFlags & PackageManager.INSTALL_APEX) != 0;
    }

    private void preRebootVerification(@NonNull PackageInstallerSession session) {
        boolean success = true;

        // STOPSHIP: TODO(b/123753157): Verify APKs through Package Verifier.
        if (!sessionContainsApex(session)) {
            // TODO: Decide whether we want to fail fast by detecting signature mismatches for APKs,
            // right away.
            session.setStagedSessionReady();
            return;
        }

        final ApexInfoList apexInfoList = new ApexInfoList();
        // APEX checks. For single-package sessions, check if they contain an APEX. For
        // multi-package sessions, find all the child sessions that contain an APEX.
        if (!session.isMultiPackage()
                && isApexSession(session)) {
            success = submitSessionToApexService(session, null, apexInfoList);

        } else if (session.isMultiPackage()) {
            List<PackageInstallerSession> childSessions =
                    Arrays.stream(session.getChildSessionIds())
                            // Retrieve cached sessions matching ids.
                            .mapToObj(i -> mStagedSessions.get(i))
                            // Filter only the ones containing APEX.
                            .filter(childSession -> isApexSession(childSession))
                            .collect(Collectors.toList());
            if (!childSessions.isEmpty()) {
                success = submitSessionToApexService(session, childSessions, apexInfoList);
            } // else this is a staged multi-package session with no APEX files.
        }

        if (!success) {
            session.setStagedSessionFailed(
                    SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                    "APEX staging failed, check logcat messages from apexd for more details.");
            return;
        }

        if (apexInfoList.apexInfos != null && apexInfoList.apexInfos.length > 0) {
            // For APEXes, we validate the signature here before we mark the session as ready,
            // so we fail the session early if there is a signature mismatch. For APKs, the
            // signature verification will be done by the package manager at the point at which
            // it applies the staged install.
            for (ApexInfo apexPackage : apexInfoList.apexInfos) {
                if (!validateApexSignature(apexPackage.packagePath,
                        apexPackage.packageName)) {
                    session.setStagedSessionFailed(SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                            "APK-container signature verification failed for package "
                                    + apexPackage.packageName + ". Signature of file "
                                    + apexPackage.packagePath + " does not match the signature of "
                                    + " the package already installed.");
                    // TODO(b/118865310): abort the session on apexd.
                    return;
                }
            }
        }

        if ((session.params.installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0) {
            // If rollback is enabled for this session, we call through to the RollbackManager
            // with the list of sessions it must enable rollback for. Note that notifyStagedSession
            // is a synchronous operation.
            final IRollbackManager rm = IRollbackManager.Stub.asInterface(
                    ServiceManager.getService(Context.ROLLBACK_SERVICE));
            try {
                // NOTE: To stay consistent with the non-staged install flow, we don't fail the
                // entire install if rollbacks can't be enabled.
                if (!rm.notifyStagedSession(session.sessionId)) {
                    Slog.e(TAG, "Unable to enable rollback for session: " + session.sessionId);
                }
            } catch (RemoteException re) {
                // Cannot happen, the rollback manager is in the same process.
            }
        }

        session.setStagedSessionReady();
        if (!sendMarkStagedSessionReadyRequest(session.sessionId)) {
            session.setStagedSessionFailed(SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                            "APEX staging failed, check logcat messages from apexd for more "
                            + "details.");
        }
    }

    private boolean sessionContainsApex(@NonNull PackageInstallerSession session) {
        if (!session.isMultiPackage()) {
            return isApexSession(session);
        }
        synchronized (mStagedSessions) {
            return !(Arrays.stream(session.getChildSessionIds())
                    // Retrieve cached sessions matching ids.
                    .mapToObj(i -> mStagedSessions.get(i))
                    // Filter only the ones containing APEX.
                    .filter(childSession -> isApexSession(childSession))
                    .collect(Collectors.toList())
                    .isEmpty());
        }
    }

    private void resumeSession(@NonNull PackageInstallerSession session) {
        if (sessionContainsApex(session)) {
            // Check with apexservice whether the apex
            // packages have been activated.
            final IApexService apex = IApexService.Stub.asInterface(
                    ServiceManager.getService("apexservice"));
            ApexSessionInfo apexSessionInfo;
            try {
                apexSessionInfo = apex.getStagedSessionInfo(session.sessionId);
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to contact apexservice", re);
                // TODO should we retry here? Mark the session as failed?
                return;
            }
            if (apexSessionInfo.isActivationFailed || apexSessionInfo.isUnknown) {
                session.setStagedSessionFailed(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED,
                        "APEX activation failed. Check logcat messages from apexd for "
                                + "more information.");
                return;
            }
            if (apexSessionInfo.isVerified) {
                // Session has been previously submitted to apexd, but didn't complete all the
                // pre-reboot verification, perhaps because the device rebooted in the meantime.
                // Greedily re-trigger the pre-reboot verification.
                Slog.d(TAG, "Found pending staged session " + session.sessionId + " still to be "
                        + "verified, resuming pre-reboot verification");
                mBgHandler.post(() -> preRebootVerification(session));
                return;
            }
            if (!apexSessionInfo.isActivated) {
                // In all the remaining cases apexd will try to apply the session again at next
                // boot. Nothing to do here for now.
                Slog.w(TAG, "Staged session " + session.sessionId + " scheduled to be applied "
                        + "at boot didn't activate nor fail. This usually means that apexd will "
                        + "retry at next reboot.");
                return;
            }
        }
        // The APEX part of the session is activated, proceed with the installation of APKs.
        if (!installApksInSession(session)) {
            session.setStagedSessionFailed(SessionInfo.STAGED_SESSION_ACTIVATION_FAILED,
                    "APEX activation failed. Check logcat messages from apexd for "
                            + "more information.");
            return;
        }
        session.setStagedSessionApplied();
    }

    private String findFirstAPKInDir(File stageDir) {
        if (stageDir != null && stageDir.exists()) {
            for (File file : stageDir.listFiles()) {
                if (file.getAbsolutePath().toLowerCase().endsWith(".apk")) {
                    return file.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private PackageInstallerSession createAndWriteApkSession(
            @NonNull PackageInstallerSession originalSession) {
        // TODO(b/123629153): support split APKs.
        if (originalSession.stageDir == null) {
            Slog.wtf(TAG, "Attempting to install a staged APK session with no staging dir");
            return null;
        }
        String apkFilePath = findFirstAPKInDir(originalSession.stageDir);
        if (apkFilePath == null) {
            Slog.w(TAG, "Can't find staged APK in " + originalSession.stageDir.getAbsolutePath());
            return null;
        }
        File apkFile = new File(apkFilePath);

        PackageInstaller.SessionParams params = originalSession.params.copy();
        params.isStaged = false;
        params.installFlags |= PackageManager.INSTALL_DISABLE_VERIFICATION;
        int apkSessionId = mPi.createSession(
                params, originalSession.getInstallerPackageName(), originalSession.userId);
        PackageInstallerSession apkSession = mPi.getSession(apkSessionId);

        try {
            apkSession.open();
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(apkFile,
                    ParcelFileDescriptor.MODE_READ_ONLY);
            long sizeBytes = pfd.getStatSize();
            if (sizeBytes < 0) {
                Slog.e(TAG, "Unable to get size of: " + apkFilePath);
                return null;
            }
            apkSession.write(apkFile.getName(), 0, sizeBytes, pfd);
        } catch (IOException e) {
            Slog.e(TAG, "Failure to install APK staged session " + originalSession.sessionId, e);
            return null;
        }
        return apkSession;
    }

    private boolean commitApkSession(@NonNull PackageInstallerSession apkSession,
                                     int originalSessionId) {

        if ((apkSession.params.installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0) {
            // If rollback is available for this session, notify the rollback
            // manager of the apk session so it can properly enable rollback.
            final IRollbackManager rm = IRollbackManager.Stub.asInterface(
                    ServiceManager.getService(Context.ROLLBACK_SERVICE));
            try {
                rm.notifyStagedApkSession(originalSessionId, apkSession.sessionId);
            } catch (RemoteException re) {
                // Cannot happen, the rollback manager is in the same process.
            }
        }

        final LocalIntentReceiver receiver = new LocalIntentReceiver();
        apkSession.commit(receiver.getIntentSender(), false);
        final Intent result = receiver.getResult();
        final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            return true;
        }
        Slog.e(TAG, "Failure to install APK staged session " + originalSessionId + " ["
                + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "]");
        return false;
    }

    private boolean installApksInSession(@NonNull PackageInstallerSession session) {
        if (!session.isMultiPackage() && !isApexSession(session)) {
            // APK single-packaged staged session. Do a regular install.
            PackageInstallerSession apkSession = createAndWriteApkSession(session);
            if (apkSession == null) {
                return false;
            }
            return commitApkSession(apkSession, session.sessionId);
        } else if (session.isMultiPackage()) {
            // For multi-package staged sessions containing APKs, we identify which child sessions
            // contain an APK, and with those then create a new multi-package group of sessions,
            // carrying over all the session parameters and unmarking them as staged. On commit the
            // sessions will be installed atomically.
            List<PackageInstallerSession> childSessions;
            synchronized (mStagedSessions) {
                childSessions =
                        Arrays.stream(session.getChildSessionIds())
                                // Retrieve cached sessions matching ids.
                                .mapToObj(i -> mStagedSessions.get(i))
                                // Filter only the ones containing APKs.s
                                .filter(childSession -> !isApexSession(childSession))
                                .collect(Collectors.toList());
            }
            if (childSessions.isEmpty()) {
                // APEX-only multi-package staged session, nothing to do.
                return true;
            }
            PackageInstaller.SessionParams params = session.params.copy();
            params.isStaged = false;
            int apkParentSessionId = mPi.createSession(
                    params, session.getInstallerPackageName(), session.userId);
            PackageInstallerSession apkParentSession = mPi.getSession(apkParentSessionId);
            try {
                apkParentSession.open();
            } catch (IOException e) {
                Slog.e(TAG, "Unable to prepare multi-package session for staged session "
                        + session.sessionId);
                return false;
            }

            for (PackageInstallerSession sessionToClone : childSessions) {
                PackageInstallerSession apkChildSession = createAndWriteApkSession(sessionToClone);
                if (apkChildSession == null) {
                    return false;
                }
                apkParentSession.addChildSessionId(apkChildSession.sessionId);
            }
            return commitApkSession(apkParentSession, session.sessionId);
        }
        // APEX single-package staged session, nothing to do.
        return true;
    }

    void commitSession(@NonNull PackageInstallerSession session) {
        updateStoredSession(session);
        mBgHandler.post(() -> preRebootVerification(session));
    }

    void createSession(@NonNull PackageInstallerSession sessionInfo) {
        synchronized (mStagedSessions) {
            mStagedSessions.append(sessionInfo.sessionId, sessionInfo);
        }
    }

    void abortSession(@NonNull PackageInstallerSession session) {
        synchronized (mStagedSessions) {
            updateStoredSession(session);
            mStagedSessions.remove(session.sessionId);
        }
    }

    @GuardedBy("mStagedSessions")
    private boolean isMultiPackageSessionComplete(@NonNull PackageInstallerSession session) {
        // This method assumes that the argument is either a parent session of a multi-package
        // i.e. isMultiPackage() returns true, or that it is a child session, i.e.
        // hasParentSessionId() returns true.
        if (session.isMultiPackage()) {
            // Parent session of a multi-package group. Check that we restored all the children.
            for (int childSession : session.getChildSessionIds()) {
                if (mStagedSessions.get(childSession) == null) {
                    return false;
                }
            }
            return true;
        }
        if (session.hasParentSessionId()) {
            PackageInstallerSession parent = mStagedSessions.get(session.getParentSessionId());
            if (parent == null) {
                return false;
            }
            return isMultiPackageSessionComplete(parent);
        }
        Slog.wtf(TAG, "Attempting to restore an invalid multi-package session.");
        return false;
    }

    void restoreSession(@NonNull PackageInstallerSession session) {
        PackageInstallerSession sessionToResume = session;
        synchronized (mStagedSessions) {
            mStagedSessions.append(session.sessionId, session);
            // For multi-package sessions, we don't know in which order they will be restored. We
            // need to wait until we have restored all the session in a group before restoring them.
            if (session.isMultiPackage() || session.hasParentSessionId()) {
                if (!isMultiPackageSessionComplete(session)) {
                    // Still haven't recovered all sessions of the group, return.
                    return;
                }
                // Group recovered, find the parent if necessary and resume the installation.
                if (session.hasParentSessionId()) {
                    sessionToResume = mStagedSessions.get(session.getParentSessionId());
                }
            }
        }
        checkStateAndResume(sessionToResume);
    }

    private void checkStateAndResume(@NonNull PackageInstallerSession session) {
        // Check the state of the session and decide what to do next.
        if (session.isStagedSessionFailed() || session.isStagedSessionApplied()) {
            // Final states, nothing to do.
            return;
        }
        if (!session.isStagedSessionReady()) {
            // The framework got restarted before the pre-reboot verification could complete,
            // restart the verification.
            mBgHandler.post(() -> preRebootVerification(session));
        } else {
            // Session had already being marked ready. Start the checks to verify if there is any
            // follow-up work.
            resumeSession(session);
        }
    }

    private static class LocalIntentReceiver {
        private final LinkedBlockingQueue<Intent> mResult = new LinkedBlockingQueue<>();

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                             IIntentReceiver finishedReceiver, String requiredPermission,
                             Bundle options) {
                try {
                    mResult.offer(intent, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        public Intent getResult() {
            try {
                return mResult.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
