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

package com.android.server.backup.restore;

import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_VERSION;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_MANIFEST_PACKAGE_NAME;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_OLD_VERSION;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_POLICY_ALLOW_APKS;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_APK_NOT_INSTALLED;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_CANNOT_RESTORE_WITHOUT_APK;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_EXPECTED_DIFFERENT_PACKAGE;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE_ALLOW_BACKUP_FALSE;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE_SIGNATURE_MISMATCH;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_MISSING_SIGNATURE;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_RESTORE_ANY_VERSION;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_SYSTEM_APP_NO_AGENT;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_VERSIONS_MATCH;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER;

import android.app.ApplicationThreadConstants;
import android.app.IBackupAgent;
import android.app.PackageInstallObserver;
import android.app.backup.BackupAgent;
import android.app.backup.BackupManagerMonitor;
import android.app.backup.FullBackup;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IFullBackupRestoreObserver;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.FileMetadata;
import com.android.server.backup.RefactoredBackupManagerService;
import com.android.server.backup.fullbackup.FullBackupObbConnection;
import com.android.server.backup.utils.AppBackupUtils;
import com.android.server.backup.utils.BackupManagerMonitorUtils;
import com.android.server.backup.utils.TarBackupReader;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full restore engine, used by both adb restore and transport-based full restore.
 */
public class FullRestoreEngine extends RestoreEngine {

    private RefactoredBackupManagerService backupManagerService;
    // Task in charge of monitoring timeouts
    BackupRestoreTask mMonitorTask;

    // Dedicated observer, if any
    IFullBackupRestoreObserver mObserver;

    IBackupManagerMonitor mMonitor;

    // Where we're delivering the file data as we go
    IBackupAgent mAgent;

    // Are we permitted to only deliver a specific package's metadata?
    PackageInfo mOnlyPackage;

    boolean mAllowApks;
    boolean mAllowObbs;

    // Which package are we currently handling data for?
    String mAgentPackage;

    // Info for working with the target app process
    ApplicationInfo mTargetApp;

    // Machinery for restoring OBBs
    FullBackupObbConnection mObbConnection = null;

    // possible handling states for a given package in the restore dataset
    final HashMap<String, RestorePolicy> mPackagePolicies
            = new HashMap<>();

    // installer package names for each encountered app, derived from the manifests
    final HashMap<String, String> mPackageInstallers = new HashMap<>();

    // Signatures for a given package found in its manifest file
    final HashMap<String, Signature[]> mManifestSignatures
            = new HashMap<>();

    // Packages we've already wiped data on when restoring their first file
    final HashSet<String> mClearedPackages = new HashSet<>();

    // How much data have we moved?
    long mBytes;

    // Working buffer
    byte[] mBuffer;

    // Pipes for moving data
    ParcelFileDescriptor[] mPipes = null;

    // Widget blob to be restored out-of-band
    byte[] mWidgetData = null;

    private final int mEphemeralOpToken;

    // Runner that can be placed in a separate thread to do in-process
    // invocations of the full restore API asynchronously. Used by adb restore.
    class RestoreFileRunnable implements Runnable {

        IBackupAgent mAgent;
        FileMetadata mInfo;
        ParcelFileDescriptor mSocket;
        int mToken;

        RestoreFileRunnable(IBackupAgent agent, FileMetadata info,
                ParcelFileDescriptor socket, int token) throws IOException {
            mAgent = agent;
            mInfo = info;
            mToken = token;

            // This class is used strictly for process-local binder invocations.  The
            // semantics of ParcelFileDescriptor differ in this case; in particular, we
            // do not automatically get a 'dup'ed descriptor that we can can continue
            // to use asynchronously from the caller.  So, we make sure to dup it ourselves
            // before proceeding to do the restore.
            mSocket = ParcelFileDescriptor.dup(socket.getFileDescriptor());
        }

        @Override
        public void run() {
            try {
                mAgent.doRestoreFile(mSocket, mInfo.size, mInfo.type,
                        mInfo.domain, mInfo.path, mInfo.mode, mInfo.mtime,
                        mToken, backupManagerService.getBackupManagerBinder());
            } catch (RemoteException e) {
                // never happens; this is used strictly for local binder calls
            }
        }
    }

    public FullRestoreEngine(RefactoredBackupManagerService backupManagerService,
            BackupRestoreTask monitorTask, IFullBackupRestoreObserver observer,
            IBackupManagerMonitor monitor, PackageInfo onlyPackage, boolean allowApks,
            boolean allowObbs, int ephemeralOpToken) {
        this.backupManagerService = backupManagerService;
        mEphemeralOpToken = ephemeralOpToken;
        mMonitorTask = monitorTask;
        mObserver = observer;
        mMonitor = monitor;
        mOnlyPackage = onlyPackage;
        mAllowApks = allowApks;
        mAllowObbs = allowObbs;
        mBuffer = new byte[32 * 1024];
        mBytes = 0;
    }

    public IBackupAgent getAgent() {
        return mAgent;
    }

    public byte[] getWidgetData() {
        return mWidgetData;
    }

    public boolean restoreOneFile(InputStream instream, boolean mustKillAgent) {
        if (!isRunning()) {
            Slog.w(RefactoredBackupManagerService.TAG, "Restore engine used after halting");
            return false;
        }

        TarBackupReader tarBackupReader = new TarBackupReader(instream,
                new TarBackupReader.BytesReadListener() {
                    @Override
                    public void onBytesRead(long bytesRead) {
                        mBytes += bytesRead;
                    }
                });

        FileMetadata info;
        try {
            if (RefactoredBackupManagerService.MORE_DEBUG) {
                Slog.v(RefactoredBackupManagerService.TAG, "Reading tar header for restoring file");
            }
            info = tarBackupReader.readTarHeaders();
            if (info != null) {
                if (RefactoredBackupManagerService.MORE_DEBUG) {
                    dumpFileMetadata(info);
                }

                final String pkg = info.packageName;
                if (!pkg.equals(mAgentPackage)) {
                    // In the single-package case, it's a semantic error to expect
                    // one app's data but see a different app's on the wire
                    if (mOnlyPackage != null) {
                        if (!pkg.equals(mOnlyPackage.packageName)) {
                            Slog.w(RefactoredBackupManagerService.TAG,
                                    "Expected data for " + mOnlyPackage
                                            + " but saw " + pkg);
                            setResult(RestoreEngine.TRANSPORT_FAILURE);
                            setRunning(false);
                            return false;
                        }
                    }

                    // okay, change in package; set up our various
                    // bookkeeping if we haven't seen it yet
                    if (!mPackagePolicies.containsKey(pkg)) {
                        mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                    }

                    // Clean up the previous agent relationship if necessary,
                    // and let the observer know we're considering a new app.
                    if (mAgent != null) {
                        if (RefactoredBackupManagerService.DEBUG) {
                            Slog.d(RefactoredBackupManagerService.TAG,
                                    "Saw new package; finalizing old one");
                        }
                        // Now we're really done
                        tearDownPipes();
                        tearDownAgent(mTargetApp);
                        mTargetApp = null;
                        mAgentPackage = null;
                    }
                }

                if (info.path.equals(RefactoredBackupManagerService.BACKUP_MANIFEST_FILENAME)) {
                    mPackagePolicies.put(pkg, readAppManifest(info, instream));
                    mPackageInstallers.put(pkg, info.installerPackageName);
                    // We've read only the manifest content itself at this point,
                    // so consume the footer before looping around to the next
                    // input file
                    tarBackupReader.skipTarPadding(info.size);
                    sendOnRestorePackage(pkg);
                } else if (info.path.equals(
                        RefactoredBackupManagerService.BACKUP_METADATA_FILENAME)) {
                    // Metadata blobs!
                    readMetadata(info, instream);
                    tarBackupReader.skipTarPadding(info.size);
                } else {
                    // Non-manifest, so it's actual file data.  Is this a package
                    // we're ignoring?
                    boolean okay = true;
                    RestorePolicy policy = mPackagePolicies.get(pkg);
                    switch (policy) {
                        case IGNORE:
                            okay = false;
                            break;

                        case ACCEPT_IF_APK:
                            // If we're in accept-if-apk state, then the first file we
                            // see MUST be the apk.
                            if (info.domain.equals(FullBackup.APK_TREE_TOKEN)) {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "APK file; installing");
                                }
                                // Try to install the app.
                                String installerName = mPackageInstallers.get(pkg);
                                okay = installApk(info, installerName, instream);
                                // good to go; promote to ACCEPT
                                mPackagePolicies.put(pkg, (okay)
                                        ? RestorePolicy.ACCEPT
                                        : RestorePolicy.IGNORE);
                                // At this point we've consumed this file entry
                                // ourselves, so just strip the tar footer and
                                // go on to the next file in the input stream
                                tarBackupReader.skipTarPadding(info.size);
                                return true;
                            } else {
                                // File data before (or without) the apk.  We can't
                                // handle it coherently in this case so ignore it.
                                mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                                okay = false;
                            }
                            break;

                        case ACCEPT:
                            if (info.domain.equals(FullBackup.APK_TREE_TOKEN)) {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "apk present but ACCEPT");
                                }
                                // we can take the data without the apk, so we
                                // *want* to do so.  skip the apk by declaring this
                                // one file not-okay without changing the restore
                                // policy for the package.
                                okay = false;
                            }
                            break;

                        default:
                            // Something has gone dreadfully wrong when determining
                            // the restore policy from the manifest.  Ignore the
                            // rest of this package's data.
                            Slog.e(RefactoredBackupManagerService.TAG,
                                    "Invalid policy from manifest");
                            okay = false;
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                            break;
                    }

                    // Is it a *file* we need to drop?
                    if (!isRestorableFile(info)) {
                        okay = false;
                    }

                    // If the policy is satisfied, go ahead and set up to pipe the
                    // data to the agent.
                    if (RefactoredBackupManagerService.MORE_DEBUG && okay && mAgent != null) {
                        Slog.i(RefactoredBackupManagerService.TAG,
                                "Reusing existing agent instance");
                    }
                    if (okay && mAgent == null) {
                        if (RefactoredBackupManagerService.MORE_DEBUG) {
                            Slog.d(RefactoredBackupManagerService.TAG,
                                    "Need to launch agent for " + pkg);
                        }

                        try {
                            mTargetApp =
                                    backupManagerService.getPackageManager().getApplicationInfo(
                                            pkg, 0);

                            // If we haven't sent any data to this app yet, we probably
                            // need to clear it first.  Check that.
                            if (!mClearedPackages.contains(pkg)) {
                                // apps with their own backup agents are
                                // responsible for coherently managing a full
                                // restore.
                                if (mTargetApp.backupAgentName == null) {
                                    if (RefactoredBackupManagerService.DEBUG) {
                                        Slog.d(RefactoredBackupManagerService.TAG,
                                                "Clearing app data preparatory to full restore");
                                    }
                                    backupManagerService.clearApplicationDataSynchronous(pkg);
                                } else {
                                    if (RefactoredBackupManagerService.MORE_DEBUG) {
                                        Slog.d(RefactoredBackupManagerService.TAG, "backup agent ("
                                                + mTargetApp.backupAgentName + ") => no clear");
                                    }
                                }
                                mClearedPackages.add(pkg);
                            } else {
                                if (RefactoredBackupManagerService.MORE_DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "We've initialized this app already; no clear "
                                                    + "required");
                                }
                            }

                            // All set; now set up the IPC and launch the agent
                            setUpPipes();
                            mAgent = backupManagerService.bindToAgentSynchronous(mTargetApp,
                                    ApplicationThreadConstants.BACKUP_MODE_RESTORE_FULL);
                            mAgentPackage = pkg;
                        } catch (IOException e) {
                            // fall through to error handling
                        } catch (NameNotFoundException e) {
                            // fall through to error handling
                        }

                        if (mAgent == null) {
                            Slog.e(
                                    RefactoredBackupManagerService.TAG,
                                    "Unable to create agent for " + pkg);
                            okay = false;
                            tearDownPipes();
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                        }
                    }

                    // Sanity check: make sure we never give data to the wrong app.  This
                    // should never happen but a little paranoia here won't go amiss.
                    if (okay && !pkg.equals(mAgentPackage)) {
                        Slog.e(RefactoredBackupManagerService.TAG, "Restoring data for " + pkg
                                + " but agent is for " + mAgentPackage);
                        okay = false;
                    }

                    // At this point we have an agent ready to handle the full
                    // restore data as well as a pipe for sending data to
                    // that agent.  Tell the agent to start reading from the
                    // pipe.
                    if (okay) {
                        boolean agentSuccess = true;
                        long toCopy = info.size;
                        try {
                            backupManagerService.prepareOperationTimeout(mEphemeralOpToken,
                                    RefactoredBackupManagerService.TIMEOUT_FULL_BACKUP_INTERVAL,
                                    mMonitorTask,
                                    RefactoredBackupManagerService.OP_TYPE_RESTORE_WAIT);

                            if (info.domain.equals(FullBackup.OBB_TREE_TOKEN)) {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "Restoring OBB file for " + pkg
                                                    + " : " + info.path);
                                }
                                mObbConnection.restoreObbFile(pkg, mPipes[0],
                                        info.size, info.type, info.path, info.mode,
                                        info.mtime, mEphemeralOpToken,
                                        backupManagerService.getBackupManagerBinder());
                            } else {
                                if (RefactoredBackupManagerService.MORE_DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "Invoking agent to restore file "
                                                    + info.path);
                                }
                                // fire up the app's agent listening on the socket.  If
                                // the agent is running in the system process we can't
                                // just invoke it asynchronously, so we provide a thread
                                // for it here.
                                if (mTargetApp.processName.equals("system")) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "system process agent - spinning a thread");
                                    RestoreFileRunnable runner = new RestoreFileRunnable(
                                            mAgent, info, mPipes[0], mEphemeralOpToken);
                                    new Thread(runner, "restore-sys-runner").start();
                                } else {
                                    mAgent.doRestoreFile(mPipes[0], info.size, info.type,
                                            info.domain, info.path, info.mode, info.mtime,
                                            mEphemeralOpToken,
                                            backupManagerService.getBackupManagerBinder());
                                }
                            }
                        } catch (IOException e) {
                            // couldn't dup the socket for a process-local restore
                            Slog.d(RefactoredBackupManagerService.TAG,
                                    "Couldn't establish restore");
                            agentSuccess = false;
                            okay = false;
                        } catch (RemoteException e) {
                            // whoops, remote entity went away.  We'll eat the content
                            // ourselves, then, and not copy it over.
                            Slog.e(RefactoredBackupManagerService.TAG,
                                    "Agent crashed during full restore");
                            agentSuccess = false;
                            okay = false;
                        }

                        // Copy over the data if the agent is still good
                        if (okay) {
                            if (RefactoredBackupManagerService.MORE_DEBUG) {
                                Slog.v(RefactoredBackupManagerService.TAG,
                                        "  copying to restore agent: "
                                                + toCopy + " bytes");
                            }
                            boolean pipeOkay = true;
                            FileOutputStream pipe = new FileOutputStream(
                                    mPipes[1].getFileDescriptor());
                            while (toCopy > 0) {
                                int toRead = (toCopy > mBuffer.length)
                                        ? mBuffer.length : (int) toCopy;
                                int nRead = instream.read(mBuffer, 0, toRead);
                                if (nRead >= 0) {
                                    mBytes += nRead;
                                }
                                if (nRead <= 0) {
                                    break;
                                }
                                toCopy -= nRead;

                                // send it to the output pipe as long as things
                                // are still good
                                if (pipeOkay) {
                                    try {
                                        pipe.write(mBuffer, 0, nRead);
                                    } catch (IOException e) {
                                        Slog.e(RefactoredBackupManagerService.TAG,
                                                "Failed to write to restore pipe: "
                                                        + e.getMessage());
                                        pipeOkay = false;
                                    }
                                }
                            }

                            // done sending that file!  Now we just need to consume
                            // the delta from info.size to the end of block.
                            tarBackupReader.skipTarPadding(info.size);

                            // and now that we've sent it all, wait for the remote
                            // side to acknowledge receipt
                            agentSuccess = backupManagerService.waitUntilOperationComplete(
                                    mEphemeralOpToken);
                        }

                        // okay, if the remote end failed at any point, deal with
                        // it by ignoring the rest of the restore on it
                        if (!agentSuccess) {
                            Slog.w(RefactoredBackupManagerService.TAG,
                                    "Agent failure; ending restore");
                            backupManagerService.getBackupHandler().removeMessages(
                                    RefactoredBackupManagerService.MSG_RESTORE_OPERATION_TIMEOUT);
                            tearDownPipes();
                            tearDownAgent(mTargetApp);
                            mAgent = null;
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);

                            // If this was a single-package restore, we halt immediately
                            // with an agent error under these circumstances
                            if (mOnlyPackage != null) {
                                setResult(RestoreEngine.TARGET_FAILURE);
                                setRunning(false);
                                return false;
                            }
                        }
                    }

                    // Problems setting up the agent communication, an explicitly
                    // dropped file, or an already-ignored package: skip to the
                    // next stream entry by reading and discarding this file.
                    if (!okay) {
                        if (RefactoredBackupManagerService.MORE_DEBUG) {
                            Slog.d(RefactoredBackupManagerService.TAG, "[discarding file content]");
                        }
                        long bytesToConsume = (info.size + 511) & ~511;
                        while (bytesToConsume > 0) {
                            int toRead = (bytesToConsume > mBuffer.length)
                                    ? mBuffer.length : (int) bytesToConsume;
                            long nRead = instream.read(mBuffer, 0, toRead);
                            if (nRead >= 0) {
                                mBytes += nRead;
                            }
                            if (nRead <= 0) {
                                break;
                            }
                            bytesToConsume -= nRead;
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (RefactoredBackupManagerService.DEBUG) {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "io exception on restore socket read: " + e.getMessage());
            }
            setResult(RestoreEngine.TRANSPORT_FAILURE);
            info = null;
        }

        // If we got here we're either running smoothly or we've finished
        if (info == null) {
            if (RefactoredBackupManagerService.MORE_DEBUG) {
                Slog.i(RefactoredBackupManagerService.TAG,
                        "No [more] data for this package; tearing down");
            }
            tearDownPipes();
            setRunning(false);
            if (mustKillAgent) {
                tearDownAgent(mTargetApp);
            }
        }
        return (info != null);
    }

    void setUpPipes() throws IOException {
        mPipes = ParcelFileDescriptor.createPipe();
    }

    void tearDownPipes() {
        // Teardown might arise from the inline restore processing or from the asynchronous
        // timeout mechanism, and these might race.  Make sure we don't try to close and
        // null out the pipes twice.
        synchronized (this) {
            if (mPipes != null) {
                try {
                    mPipes[0].close();
                    mPipes[0] = null;
                    mPipes[1].close();
                    mPipes[1] = null;
                } catch (IOException e) {
                    Slog.w(RefactoredBackupManagerService.TAG, "Couldn't close agent pipes", e);
                }
                mPipes = null;
            }
        }
    }

    void tearDownAgent(ApplicationInfo app) {
        if (mAgent != null) {
            backupManagerService.tearDownAgentAndKill(app);
            mAgent = null;
        }
    }

    void handleTimeout() {
        tearDownPipes();
        setResult(RestoreEngine.TARGET_FAILURE);
        setRunning(false);
    }

    class RestoreInstallObserver extends PackageInstallObserver {

        final AtomicBoolean mDone = new AtomicBoolean();
        String mPackageName;
        int mResult;

        public void reset() {
            synchronized (mDone) {
                mDone.set(false);
            }
        }

        public void waitForCompletion() {
            synchronized (mDone) {
                while (mDone.get() == false) {
                    try {
                        mDone.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }

        int getResult() {
            return mResult;
        }

        @Override
        public void onPackageInstalled(String packageName, int returnCode,
                String msg, Bundle extras) {
            synchronized (mDone) {
                mResult = returnCode;
                mPackageName = packageName;
                mDone.set(true);
                mDone.notifyAll();
            }
        }
    }

    class RestoreDeleteObserver extends IPackageDeleteObserver.Stub {

        final AtomicBoolean mDone = new AtomicBoolean();
        int mResult;

        public void reset() {
            synchronized (mDone) {
                mDone.set(false);
            }
        }

        public void waitForCompletion() {
            synchronized (mDone) {
                while (mDone.get() == false) {
                    try {
                        mDone.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }

        @Override
        public void packageDeleted(String packageName, int returnCode) throws RemoteException {
            synchronized (mDone) {
                mResult = returnCode;
                mDone.set(true);
                mDone.notifyAll();
            }
        }
    }

    final RestoreInstallObserver mInstallObserver = new RestoreInstallObserver();
    final RestoreDeleteObserver mDeleteObserver = new RestoreDeleteObserver();

    boolean installApk(FileMetadata info, String installerPackage, InputStream instream) {
        boolean okay = true;

        if (RefactoredBackupManagerService.DEBUG) {
            Slog.d(RefactoredBackupManagerService.TAG,
                    "Installing from backup: " + info.packageName);
        }

        // The file content is an .apk file.  Copy it out to a staging location and
        // attempt to install it.
        File apkFile = new File(backupManagerService.getDataDir(), info.packageName);
        try {
            FileOutputStream apkStream = new FileOutputStream(apkFile);
            byte[] buffer = new byte[32 * 1024];
            long size = info.size;
            while (size > 0) {
                long toRead = (buffer.length < size) ? buffer.length : size;
                int didRead = instream.read(buffer, 0, (int) toRead);
                if (didRead >= 0) {
                    mBytes += didRead;
                }
                apkStream.write(buffer, 0, didRead);
                size -= didRead;
            }
            apkStream.close();

            // make sure the installer can read it
            apkFile.setReadable(true, false);

            // Now install it
            Uri packageUri = Uri.fromFile(apkFile);
            mInstallObserver.reset();
            backupManagerService.getPackageManager().installPackage(packageUri, mInstallObserver,
                    PackageManager.INSTALL_REPLACE_EXISTING | PackageManager.INSTALL_FROM_ADB,
                    installerPackage);
            mInstallObserver.waitForCompletion();

            if (mInstallObserver.getResult() != PackageManager.INSTALL_SUCCEEDED) {
                // The only time we continue to accept install of data even if the
                // apk install failed is if we had already determined that we could
                // accept the data regardless.
                if (mPackagePolicies.get(info.packageName) != RestorePolicy.ACCEPT) {
                    okay = false;
                }
            } else {
                // Okay, the install succeeded.  Make sure it was the right app.
                boolean uninstall = false;
                if (!mInstallObserver.mPackageName.equals(info.packageName)) {
                    Slog.w(RefactoredBackupManagerService.TAG,
                            "Restore stream claimed to include apk for "
                                    + info.packageName + " but apk was really "
                                    + mInstallObserver.mPackageName);
                    // delete the package we just put in place; it might be fraudulent
                    okay = false;
                    uninstall = true;
                } else {
                    try {
                        PackageInfo pkg = backupManagerService.getPackageManager().getPackageInfo(
                                info.packageName,
                                PackageManager.GET_SIGNATURES);
                        if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP)
                                == 0) {
                            Slog.w(RefactoredBackupManagerService.TAG,
                                    "Restore stream contains apk of package "
                                            + info.packageName
                                            + " but it disallows backup/restore");
                            okay = false;
                        } else {
                            // So far so good -- do the signatures match the manifest?
                            Signature[] sigs = mManifestSignatures.get(info.packageName);
                            if (AppBackupUtils.signaturesMatch(sigs, pkg)) {
                                // If this is a system-uid app without a declared backup agent,
                                // don't restore any of the file data.
                                if ((pkg.applicationInfo.uid < Process.FIRST_APPLICATION_UID)
                                        && (pkg.applicationInfo.backupAgentName == null)) {
                                    Slog.w(RefactoredBackupManagerService.TAG,
                                            "Installed app " + info.packageName
                                                    + " has restricted uid and no agent");
                                    okay = false;
                                }
                            } else {
                                Slog.w(RefactoredBackupManagerService.TAG,
                                        "Installed app " + info.packageName
                                                + " signatures do not match restore manifest");
                                okay = false;
                                uninstall = true;
                            }
                        }
                    } catch (NameNotFoundException e) {
                        Slog.w(RefactoredBackupManagerService.TAG,
                                "Install of package " + info.packageName
                                        + " succeeded but now not found");
                        okay = false;
                    }
                }

                // If we're not okay at this point, we need to delete the package
                // that we just installed.
                if (uninstall) {
                    mDeleteObserver.reset();
                    backupManagerService.getPackageManager().deletePackage(
                            mInstallObserver.mPackageName,
                            mDeleteObserver, 0);
                    mDeleteObserver.waitForCompletion();
                }
            }
        } catch (IOException e) {
            Slog.e(RefactoredBackupManagerService.TAG,
                    "Unable to transcribe restored apk for install");
            okay = false;
        } finally {
            apkFile.delete();
        }

        return okay;
    }

    // Read a widget metadata file, returning the restored blob
    void readMetadata(FileMetadata info, InputStream instream) throws IOException {
        // Fail on suspiciously large widget dump files
        if (info.size > 64 * 1024) {
            throw new IOException("Metadata too big; corrupt? size=" + info.size);
        }

        byte[] buffer = new byte[(int) info.size];
        if (TarBackupReader.readExactly(instream, buffer, 0, (int) info.size) == info.size) {
            mBytes += info.size;
        } else {
            throw new IOException("Unexpected EOF in widget data");
        }

        String[] str = new String[1];
        int offset = TarBackupReader.extractLine(buffer, 0, str);
        int version = Integer.parseInt(str[0]);
        if (version == RefactoredBackupManagerService.BACKUP_MANIFEST_VERSION) {
            offset = TarBackupReader.extractLine(buffer, offset, str);
            final String pkg = str[0];
            if (info.packageName.equals(pkg)) {
                // Data checks out -- the rest of the buffer is a concatenation of
                // binary blobs as described in the comment at writeAppWidgetData()
                ByteArrayInputStream bin = new ByteArrayInputStream(buffer,
                        offset, buffer.length - offset);
                DataInputStream in = new DataInputStream(bin);
                while (bin.available() > 0) {
                    int token = in.readInt();
                    int size = in.readInt();
                    if (size > 64 * 1024) {
                        throw new IOException("Datum "
                                + Integer.toHexString(token)
                                + " too big; corrupt? size=" + info.size);
                    }
                    switch (token) {
                        case RefactoredBackupManagerService.BACKUP_WIDGET_METADATA_TOKEN: {
                            if (RefactoredBackupManagerService.MORE_DEBUG) {
                                Slog.i(RefactoredBackupManagerService.TAG,
                                        "Got widget metadata for " + info.packageName);
                            }
                            mWidgetData = new byte[size];
                            in.read(mWidgetData);
                            break;
                        }
                        default: {
                            if (RefactoredBackupManagerService.DEBUG) {
                                Slog.i(RefactoredBackupManagerService.TAG, "Ignoring metadata blob "
                                        + Integer.toHexString(token)
                                        + " for " + info.packageName);
                            }
                            in.skipBytes(size);
                            break;
                        }
                    }
                }
            } else {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "Metadata mismatch: package " + info.packageName
                                + " but widget data for " + pkg);

                Bundle monitoringExtras = backupManagerService.putMonitoringExtra(null,
                        EXTRA_LOG_EVENT_PACKAGE_NAME, info.packageName);
                monitoringExtras = backupManagerService.putMonitoringExtra(monitoringExtras,
                        BackupManagerMonitor.EXTRA_LOG_WIDGET_PACKAGE_NAME, pkg);
                mMonitor = BackupManagerMonitorUtils.monitorEvent(mMonitor,
                        BackupManagerMonitor.LOG_EVENT_ID_WIDGET_METADATA_MISMATCH,
                        null,
                        LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        monitoringExtras);
            }
        } else {
            Slog.w(RefactoredBackupManagerService.TAG, "Unsupported metadata version " + version);

            Bundle monitoringExtras = backupManagerService
                    .putMonitoringExtra(null, EXTRA_LOG_EVENT_PACKAGE_NAME,
                            info.packageName);
            monitoringExtras = backupManagerService.putMonitoringExtra(monitoringExtras,
                    EXTRA_LOG_EVENT_PACKAGE_VERSION, version);
            mMonitor = BackupManagerMonitorUtils.monitorEvent(mMonitor,
                    BackupManagerMonitor.LOG_EVENT_ID_WIDGET_UNKNOWN_VERSION,
                    null,
                    LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                    monitoringExtras);
        }
    }

    // Returns a policy constant
    RestorePolicy readAppManifest(FileMetadata info, InputStream instream)
            throws IOException {
        // Fail on suspiciously large manifest files
        if (info.size > 64 * 1024) {
            throw new IOException("Restore manifest too big; corrupt? size=" + info.size);
        }

        byte[] buffer = new byte[(int) info.size];
        if (RefactoredBackupManagerService.MORE_DEBUG) {
            Slog.i(RefactoredBackupManagerService.TAG,
                    "   readAppManifest() looking for " + info.size + " bytes, "
                            + mBytes + " already consumed");
        }
        if (TarBackupReader.readExactly(instream, buffer, 0, (int) info.size) == info.size) {
            mBytes += info.size;
        } else {
            throw new IOException("Unexpected EOF in manifest");
        }

        RestorePolicy policy = RestorePolicy.IGNORE;
        String[] str = new String[1];
        int offset = 0;

        try {
            offset = TarBackupReader.extractLine(buffer, offset, str);
            int version = Integer.parseInt(str[0]);
            if (version == RefactoredBackupManagerService.BACKUP_MANIFEST_VERSION) {
                offset = TarBackupReader.extractLine(buffer, offset, str);
                String manifestPackage = str[0];
                // TODO: handle <original-package>
                if (manifestPackage.equals(info.packageName)) {
                    offset = TarBackupReader.extractLine(buffer, offset, str);
                    version = Integer.parseInt(str[0]);  // app version
                    offset = TarBackupReader.extractLine(buffer, offset, str);
                    // This is the platform version, which we don't use, but we parse it
                    // as a safety against corruption in the manifest.
                    Integer.parseInt(str[0]);
                    offset = TarBackupReader.extractLine(buffer, offset, str);
                    info.installerPackageName = (str[0].length() > 0) ? str[0] : null;
                    offset = TarBackupReader.extractLine(buffer, offset, str);
                    boolean hasApk = str[0].equals("1");
                    offset = TarBackupReader.extractLine(buffer, offset, str);
                    int numSigs = Integer.parseInt(str[0]);
                    if (numSigs > 0) {
                        Signature[] sigs = new Signature[numSigs];
                        for (int i = 0; i < numSigs; i++) {
                            offset = TarBackupReader.extractLine(buffer, offset, str);
                            sigs[i] = new Signature(str[0]);
                        }
                        mManifestSignatures.put(info.packageName, sigs);

                        // Okay, got the manifest info we need...
                        try {
                            PackageInfo pkgInfo =
                                    backupManagerService.getPackageManager().getPackageInfo(
                                            info.packageName, PackageManager.GET_SIGNATURES);
                            // Fall through to IGNORE if the app explicitly disallows backup
                            final int flags = pkgInfo.applicationInfo.flags;
                            if ((flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0) {
                                // Restore system-uid-space packages only if they have
                                // defined a custom backup agent
                                if ((pkgInfo.applicationInfo.uid
                                        >= Process.FIRST_APPLICATION_UID)
                                        || (pkgInfo.applicationInfo.backupAgentName != null)) {
                                    // Verify signatures against any installed version; if they
                                    // don't match, then we fall though and ignore the data.  The
                                    // signatureMatch() method explicitly ignores the signature
                                    // check for packages installed on the system partition, because
                                    // such packages are signed with the platform cert instead of
                                    // the app developer's cert, so they're different on every
                                    // device.
                                    if (AppBackupUtils.signaturesMatch(sigs,
                                            pkgInfo)) {
                                        if ((pkgInfo.applicationInfo.flags
                                                & ApplicationInfo.FLAG_RESTORE_ANY_VERSION) != 0) {
                                            Slog.i(RefactoredBackupManagerService.TAG,
                                                    "Package has restoreAnyVersion; taking data");
                                            mMonitor = BackupManagerMonitorUtils.monitorEvent(
                                                    mMonitor,
                                                    LOG_EVENT_ID_RESTORE_ANY_VERSION,
                                                    pkgInfo,
                                                    LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                                    null);
                                            policy = RestorePolicy.ACCEPT;
                                        } else if (pkgInfo.versionCode >= version) {
                                            Slog.i(RefactoredBackupManagerService.TAG,
                                                    "Sig + version match; taking data");
                                            policy = RestorePolicy.ACCEPT;
                                            mMonitor = BackupManagerMonitorUtils.monitorEvent(
                                                    mMonitor,
                                                    LOG_EVENT_ID_VERSIONS_MATCH,
                                                    pkgInfo,
                                                    LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                                    null);
                                        } else {
                                            // The data is from a newer version of the app than
                                            // is presently installed.  That means we can only
                                            // use it if the matching apk is also supplied.
                                            if (mAllowApks) {
                                                Slog.i(RefactoredBackupManagerService.TAG,
                                                        "Data version " + version
                                                                + " is newer than installed "
                                                                + "version "
                                                                + pkgInfo.versionCode
                                                                + " - requiring apk");
                                                policy = RestorePolicy.ACCEPT_IF_APK;
                                            } else {
                                                Slog.i(RefactoredBackupManagerService.TAG,
                                                        "Data requires newer version "
                                                                + version + "; ignoring");
                                                mMonitor = BackupManagerMonitorUtils
                                                        .monitorEvent(mMonitor,
                                                                LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER,
                                                                pkgInfo,
                                                                LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                                                backupManagerService
                                                                        .putMonitoringExtra(
                                                                                null,
                                                                                EXTRA_LOG_OLD_VERSION,
                                                                                version));

                                                policy = RestorePolicy.IGNORE;
                                            }
                                        }
                                    } else {
                                        Slog.w(RefactoredBackupManagerService.TAG,
                                                "Restore manifest signatures do not match "
                                                        + "installed application for "
                                                        + info.packageName);
                                        mMonitor = BackupManagerMonitorUtils.monitorEvent(
                                                mMonitor,
                                                LOG_EVENT_ID_FULL_RESTORE_SIGNATURE_MISMATCH,
                                                pkgInfo,
                                                LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                                null);
                                    }
                                } else {
                                    Slog.w(RefactoredBackupManagerService.TAG,
                                            "Package " + info.packageName
                                                    + " is system level with no agent");
                                    mMonitor = BackupManagerMonitorUtils.monitorEvent(mMonitor,
                                            LOG_EVENT_ID_SYSTEM_APP_NO_AGENT,
                                            pkgInfo,
                                            LOG_EVENT_CATEGORY_AGENT,
                                            null);
                                }
                            } else {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.i(RefactoredBackupManagerService.TAG,
                                            "Restore manifest from "
                                                    + info.packageName + " but allowBackup=false");
                                }
                                mMonitor = BackupManagerMonitorUtils.monitorEvent(mMonitor,
                                        LOG_EVENT_ID_FULL_RESTORE_ALLOW_BACKUP_FALSE,
                                        pkgInfo,
                                        LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                        null);
                            }
                        } catch (NameNotFoundException e) {
                            // Okay, the target app isn't installed.  We can process
                            // the restore properly only if the dataset provides the
                            // apk file and we can successfully install it.
                            if (mAllowApks) {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.i(RefactoredBackupManagerService.TAG,
                                            "Package " + info.packageName
                                                    + " not installed; requiring apk in dataset");
                                }
                                policy = RestorePolicy.ACCEPT_IF_APK;
                            } else {
                                policy = RestorePolicy.IGNORE;
                            }
                            Bundle monitoringExtras = backupManagerService.putMonitoringExtra(null,
                                    EXTRA_LOG_EVENT_PACKAGE_NAME, info.packageName);
                            monitoringExtras = backupManagerService.putMonitoringExtra(
                                    monitoringExtras,
                                    EXTRA_LOG_POLICY_ALLOW_APKS, mAllowApks);
                            mMonitor = BackupManagerMonitorUtils.monitorEvent(mMonitor,
                                    LOG_EVENT_ID_APK_NOT_INSTALLED,
                                    null,
                                    LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                    monitoringExtras);
                        }

                        if (policy == RestorePolicy.ACCEPT_IF_APK && !hasApk) {
                            Slog.i(RefactoredBackupManagerService.TAG,
                                    "Cannot restore package " + info.packageName
                                            + " without the matching .apk");
                            mMonitor = BackupManagerMonitorUtils.monitorEvent(mMonitor,
                                    LOG_EVENT_ID_CANNOT_RESTORE_WITHOUT_APK,
                                    null,
                                    LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                    backupManagerService.putMonitoringExtra(null,
                                            EXTRA_LOG_EVENT_PACKAGE_NAME, info.packageName));
                        }
                    } else {
                        Slog.i(RefactoredBackupManagerService.TAG,
                                "Missing signature on backed-up package "
                                        + info.packageName);
                        mMonitor = BackupManagerMonitorUtils.monitorEvent(mMonitor,
                                LOG_EVENT_ID_MISSING_SIGNATURE,
                                null,
                                LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                backupManagerService.putMonitoringExtra(null,
                                        EXTRA_LOG_EVENT_PACKAGE_NAME, info.packageName));
                    }
                } else {
                    Slog.i(RefactoredBackupManagerService.TAG,
                            "Expected package " + info.packageName
                                    + " but restore manifest claims " + manifestPackage);
                    Bundle monitoringExtras = backupManagerService.putMonitoringExtra(null,
                            EXTRA_LOG_EVENT_PACKAGE_NAME, info.packageName);
                    monitoringExtras = backupManagerService.putMonitoringExtra(monitoringExtras,
                            EXTRA_LOG_MANIFEST_PACKAGE_NAME, manifestPackage);
                    mMonitor = BackupManagerMonitorUtils.monitorEvent(mMonitor,
                            LOG_EVENT_ID_EXPECTED_DIFFERENT_PACKAGE,
                            null,
                            LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                            monitoringExtras);
                }
            } else {
                Slog.i(RefactoredBackupManagerService.TAG,
                        "Unknown restore manifest version " + version
                                + " for package " + info.packageName);
                Bundle monitoringExtras = backupManagerService.putMonitoringExtra(null,
                        EXTRA_LOG_EVENT_PACKAGE_NAME, info.packageName);
                monitoringExtras = backupManagerService.putMonitoringExtra(monitoringExtras,
                        EXTRA_LOG_EVENT_PACKAGE_VERSION, version);
                mMonitor = BackupManagerMonitorUtils.monitorEvent(mMonitor,
                        BackupManagerMonitor.LOG_EVENT_ID_UNKNOWN_VERSION,
                        null,
                        LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        monitoringExtras);

            }
        } catch (NumberFormatException e) {
            Slog.w(RefactoredBackupManagerService.TAG,
                    "Corrupt restore manifest for package " + info.packageName);
            mMonitor = BackupManagerMonitorUtils.monitorEvent(mMonitor,
                    BackupManagerMonitor.LOG_EVENT_ID_CORRUPT_MANIFEST,
                    null,
                    LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                    backupManagerService.putMonitoringExtra(null, EXTRA_LOG_EVENT_PACKAGE_NAME,
                            info.packageName));
        } catch (IllegalArgumentException e) {
            Slog.w(RefactoredBackupManagerService.TAG, e.getMessage());
        }

        return policy;
    }

    void dumpFileMetadata(FileMetadata info) {
        if (RefactoredBackupManagerService.MORE_DEBUG) {
            StringBuilder b = new StringBuilder(128);

            // mode string
            b.append((info.type == BackupAgent.TYPE_DIRECTORY) ? 'd' : '-');
            b.append(((info.mode & 0400) != 0) ? 'r' : '-');
            b.append(((info.mode & 0200) != 0) ? 'w' : '-');
            b.append(((info.mode & 0100) != 0) ? 'x' : '-');
            b.append(((info.mode & 0040) != 0) ? 'r' : '-');
            b.append(((info.mode & 0020) != 0) ? 'w' : '-');
            b.append(((info.mode & 0010) != 0) ? 'x' : '-');
            b.append(((info.mode & 0004) != 0) ? 'r' : '-');
            b.append(((info.mode & 0002) != 0) ? 'w' : '-');
            b.append(((info.mode & 0001) != 0) ? 'x' : '-');
            b.append(String.format(" %9d ", info.size));

            Date stamp = new Date(info.mtime);
            b.append(new SimpleDateFormat("MMM dd HH:mm:ss ").format(stamp));

            b.append(info.packageName);
            b.append(" :: ");
            b.append(info.domain);
            b.append(" :: ");
            b.append(info.path);

            Slog.i(RefactoredBackupManagerService.TAG, b.toString());
        }
    }

    private boolean isRestorableFile(FileMetadata info) {
        if (FullBackup.CACHE_TREE_TOKEN.equals(info.domain)) {
            if (RefactoredBackupManagerService.MORE_DEBUG) {
                Slog.i(RefactoredBackupManagerService.TAG, "Dropping cache file path " + info.path);
            }
            return false;
        }

        if (FullBackup.ROOT_TREE_TOKEN.equals(info.domain)) {
            // It's possible this is "no-backup" dir contents in an archive stream
            // produced on a device running a version of the OS that predates that
            // API.  Respect the no-backup intention and don't let the data get to
            // the app.
            if (info.path.startsWith("no_backup/")) {
                if (RefactoredBackupManagerService.MORE_DEBUG) {
                    Slog.i(RefactoredBackupManagerService.TAG,
                            "Dropping no_backup file path " + info.path);
                }
                return false;
            }
        }

        // The path needs to be canonical
        if (info.path.contains("..") || info.path.contains("//")) {
            if (RefactoredBackupManagerService.MORE_DEBUG) {
                Slog.w(RefactoredBackupManagerService.TAG, "Dropping invalid path " + info.path);
            }
            return false;
        }

        // Otherwise we think this file is good to go
        return true;
    }

    void sendStartRestore() {
        if (mObserver != null) {
            try {
                mObserver.onStartRestore();
            } catch (RemoteException e) {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "full restore observer went away: startRestore");
                mObserver = null;
            }
        }
    }

    void sendOnRestorePackage(String name) {
        if (mObserver != null) {
            try {
                // TODO: use a more user-friendly name string
                mObserver.onRestorePackage(name);
            } catch (RemoteException e) {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "full restore observer went away: restorePackage");
                mObserver = null;
            }
        }
    }

    void sendEndRestore() {
        if (mObserver != null) {
            try {
                mObserver.onEndRestore();
            } catch (RemoteException e) {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "full restore observer went away: endRestore");
                mObserver = null;
            }
        }
    }
}
