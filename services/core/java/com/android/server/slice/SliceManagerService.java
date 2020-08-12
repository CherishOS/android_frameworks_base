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

package com.android.server.slice;

import static android.app.usage.UsageEvents.Event.SLICE_PINNED;
import static android.app.usage.UsageEvents.Event.SLICE_PINNED_PRIV;
import static android.content.ContentProvider.getUriWithoutUserId;
import static android.content.ContentProvider.getUserIdFromUri;
import static android.content.ContentProvider.maybeAddUserId;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.app.slice.ISliceManager;
import android.app.slice.SliceSpec;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml.Encoding;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.AssistUtils;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

public class SliceManagerService extends ISliceManager.Stub {

    private static final String TAG = "SliceManagerService";
    private final Object mLock = new Object();

    private final Context mContext;
    private final AppOpsManager mAppOps;
    private final AssistUtils mAssistUtils;

    @GuardedBy("mLock")
    private final ArrayMap<Uri, PinnedSliceState> mPinnedSlicesByUri = new ArrayMap<>();
    @GuardedBy("mLock")
    private final SparseArray<String> mLastAssistantPackage = new SparseArray<>();
    private final Handler mHandler;

    private final SlicePermissionManager mPermissions;
    private final UsageStatsManagerInternal mAppUsageStats;

    public SliceManagerService(Context context) {
        this(context, createHandler().getLooper());
    }

    @VisibleForTesting
    SliceManagerService(Context context, Looper looper) {
        mContext = context;
        mAppOps = context.getSystemService(AppOpsManager.class);
        mAssistUtils = new AssistUtils(context);
        mHandler = new Handler(looper);

        mAppUsageStats = LocalServices.getService(UsageStatsManagerInternal.class);

        mPermissions = new SlicePermissionManager(mContext, looper);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, filter, null, mHandler);
    }

    ///  ----- Lifecycle stuff -----
    private void systemReady() {
    }

    private void onUnlockUser(int userId) {
    }

    private void onStopUser(int userId) {
        synchronized (mLock) {
            mPinnedSlicesByUri.values().removeIf(s -> getUserIdFromUri(s.getUri()) == userId);
        }
    }

    ///  ----- ISliceManager stuff -----
    @Override
    public Uri[] getPinnedSlices(String pkg) {
        verifyCaller(pkg);
        int callingUser = Binder.getCallingUserHandle().getIdentifier();
        ArrayList<Uri> ret = new ArrayList<>();
        synchronized (mLock) {
            for (PinnedSliceState state : mPinnedSlicesByUri.values()) {
                if (Objects.equals(pkg, state.getPkg())) {
                    Uri uri = state.getUri();
                    int userId = ContentProvider.getUserIdFromUri(uri, callingUser);
                    if (userId == callingUser) {
                        ret.add(ContentProvider.getUriWithoutUserId(uri));
                    }
                }
            }
        }
        return ret.toArray(new Uri[ret.size()]);
    }

    @Override
    public void pinSlice(String pkg, Uri uri, SliceSpec[] specs, IBinder token)
            throws RemoteException {
        verifyCaller(pkg);
        enforceAccess(pkg, uri);
        int user = Binder.getCallingUserHandle().getIdentifier();
        uri = maybeAddUserId(uri, user);
        String slicePkg = getProviderPkg(uri, user);
        getOrCreatePinnedSlice(uri, slicePkg).pin(pkg, specs, token);

        mHandler.post(() -> {
            if (slicePkg != null && !Objects.equals(pkg, slicePkg)) {
                mAppUsageStats.reportEvent(slicePkg, user,
                        isAssistant(pkg, user) ? SLICE_PINNED_PRIV : SLICE_PINNED);
            }
        });
    }

    @Override
    public void unpinSlice(String pkg, Uri uri, IBinder token) throws RemoteException {
        verifyCaller(pkg);
        enforceAccess(pkg, uri);
        uri = maybeAddUserId(uri, Binder.getCallingUserHandle().getIdentifier());
        try {
            PinnedSliceState slice = getPinnedSlice(uri);
            if (slice != null && slice.unpin(pkg, token)) {
                removePinnedSlice(uri);
            }
        } catch (IllegalStateException exception) {
            Slog.w(TAG, exception.getMessage());
        }
    }

    @Override
    public boolean hasSliceAccess(String pkg) throws RemoteException {
        verifyCaller(pkg);
        return hasFullSliceAccess(pkg, Binder.getCallingUserHandle().getIdentifier());
    }

    @Override
    public SliceSpec[] getPinnedSpecs(Uri uri, String pkg) throws RemoteException {
        verifyCaller(pkg);
        enforceAccess(pkg, uri);
        uri = maybeAddUserId(uri, Binder.getCallingUserHandle().getIdentifier());
        return getPinnedSlice(uri).getSpecs();
    }

    @Override
    public void grantSlicePermission(String pkg, String toPkg, Uri uri) throws RemoteException {
        verifyCaller(pkg);
        int user = Binder.getCallingUserHandle().getIdentifier();
        enforceOwner(pkg, uri, user);
        mPermissions.grantSliceAccess(toPkg, user, pkg, user, uri);
    }

    @Override
    public void revokeSlicePermission(String pkg, String toPkg, Uri uri) throws RemoteException {
        verifyCaller(pkg);
        int user = Binder.getCallingUserHandle().getIdentifier();
        enforceOwner(pkg, uri, user);
        mPermissions.revokeSliceAccess(toPkg, user, pkg, user, uri);
    }

    @Override
    public int checkSlicePermission(Uri uri, String callingPkg, String pkg, int pid, int uid,
            String[] autoGrantPermissions) {
        int userId = UserHandle.getUserId(uid);
        if (pkg == null) {
            for (String p : mContext.getPackageManager().getPackagesForUid(uid)) {
                if (checkSlicePermission(uri, callingPkg, p, pid, uid, autoGrantPermissions)
                        == PERMISSION_GRANTED) {
                    return PERMISSION_GRANTED;
                }
            }
            return PERMISSION_DENIED;
        }
        if (hasFullSliceAccess(pkg, userId)) {
            return PackageManager.PERMISSION_GRANTED;
        }
        if (mPermissions.hasPermission(pkg, userId, uri)) {
            return PackageManager.PERMISSION_GRANTED;
        }
        if (autoGrantPermissions != null && callingPkg != null) {
            // Need to own the Uri to call in with permissions to grant.
            enforceOwner(callingPkg, uri, userId);
            for (String perm : autoGrantPermissions) {
                if (mContext.checkPermission(perm, pid, uid) == PERMISSION_GRANTED) {
                    int providerUser = ContentProvider.getUserIdFromUri(uri, userId);
                    String providerPkg = getProviderPkg(uri, providerUser);
                    mPermissions.grantSliceAccess(pkg, userId, providerPkg, providerUser, uri);
                    return PackageManager.PERMISSION_GRANTED;
                }
            }
        }
        // Fallback to allowing uri permissions through.
        if (mContext.checkUriPermission(uri, pid, uid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                == PERMISSION_GRANTED) {
            return PackageManager.PERMISSION_GRANTED;
        }
        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public void grantPermissionFromUser(Uri uri, String pkg, String callingPkg, boolean allSlices) {
        verifyCaller(callingPkg);
        getContext().enforceCallingOrSelfPermission(permission.MANAGE_SLICE_PERMISSIONS,
                "Slice granting requires MANAGE_SLICE_PERMISSIONS");
        int userId = Binder.getCallingUserHandle().getIdentifier();
        if (allSlices) {
            mPermissions.grantFullAccess(pkg, userId);
        } else {
            // When granting, grant to all slices in the provider.
            Uri grantUri = uri.buildUpon()
                    .path("")
                    .build();
            int providerUser = ContentProvider.getUserIdFromUri(grantUri, userId);
            String providerPkg = getProviderPkg(grantUri, providerUser);
            mPermissions.grantSliceAccess(pkg, userId, providerPkg, providerUser, grantUri);
        }
        long ident = Binder.clearCallingIdentity();
        try {
            mContext.getContentResolver().notifyChange(uri, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // Backup/restore interface
    @Override
    public byte[] getBackupPayload(int user) {
        if (Binder.getCallingUid() != SYSTEM_UID) {
            throw new SecurityException("Caller must be system");
        }
        //TODO: http://b/22388012
        if (user != UserHandle.USER_SYSTEM) {
            Slog.w(TAG, "getBackupPayload: cannot backup policy for user " + user);
            return null;
        }
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            XmlSerializer out = XmlPullParserFactory.newInstance().newSerializer();
            out.setOutput(baos, Encoding.UTF_8.name());

            mPermissions.writeBackup(out);

            out.flush();
            return baos.toByteArray();
        } catch (IOException | XmlPullParserException e) {
            Slog.w(TAG, "getBackupPayload: error writing payload for user " + user, e);
        }
        return null;
    }

    @Override
    public void applyRestore(byte[] payload, int user) {
        if (Binder.getCallingUid() != SYSTEM_UID) {
            throw new SecurityException("Caller must be system");
        }
        if (payload == null) {
            Slog.w(TAG, "applyRestore: no payload to restore for user " + user);
            return;
        }
        //TODO: http://b/22388012
        if (user != UserHandle.USER_SYSTEM) {
            Slog.w(TAG, "applyRestore: cannot restore policy for user " + user);
            return;
        }
        final ByteArrayInputStream bais = new ByteArrayInputStream(payload);
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(bais, Encoding.UTF_8.name());
            mPermissions.readRestore(parser);
        } catch (NumberFormatException | XmlPullParserException | IOException e) {
            Slog.w(TAG, "applyRestore: error reading payload", e);
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new SliceShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    ///  ----- internal code -----
    private void enforceOwner(String pkg, Uri uri, int user) {
        if (!Objects.equals(getProviderPkg(uri, user), pkg) || pkg == null) {
            throw new SecurityException("Caller must own " + uri);
        }
    }

    protected void removePinnedSlice(Uri uri) {
        synchronized (mLock) {
            mPinnedSlicesByUri.remove(uri).destroy();
        }
    }

    private PinnedSliceState getPinnedSlice(Uri uri) {
        synchronized (mLock) {
            PinnedSliceState manager = mPinnedSlicesByUri.get(uri);
            if (manager == null) {
                throw new IllegalStateException(String.format("Slice %s not pinned",
                        uri.toString()));
            }
            return manager;
        }
    }

    private PinnedSliceState getOrCreatePinnedSlice(Uri uri, String pkg) {
        synchronized (mLock) {
            PinnedSliceState manager = mPinnedSlicesByUri.get(uri);
            if (manager == null) {
                manager = createPinnedSlice(uri, pkg);
                mPinnedSlicesByUri.put(uri, manager);
            }
            return manager;
        }
    }

    @VisibleForTesting
    protected PinnedSliceState createPinnedSlice(Uri uri, String pkg) {
        return new PinnedSliceState(this, uri, pkg);
    }

    public Object getLock() {
        return mLock;
    }

    public Context getContext() {
        return mContext;
    }

    public Handler getHandler() {
        return mHandler;
    }

    protected int checkAccess(String pkg, Uri uri, int uid, int pid) {
        return checkSlicePermission(uri, null, pkg, pid, uid, null);
    }

    private String getProviderPkg(Uri uri, int user) {
        long ident = Binder.clearCallingIdentity();
        try {
            String providerName = getUriWithoutUserId(uri).getAuthority();
            ProviderInfo provider = mContext.getPackageManager().resolveContentProviderAsUser(
                    providerName, 0, getUserIdFromUri(uri, user));
            return provider.packageName;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void enforceCrossUser(String pkg, Uri uri) {
        int user = Binder.getCallingUserHandle().getIdentifier();
        if (getUserIdFromUri(uri, user) != user) {
            getContext().enforceCallingOrSelfPermission(permission.INTERACT_ACROSS_USERS_FULL,
                    "Slice interaction across users requires INTERACT_ACROSS_USERS_FULL");
        }
    }

    private void enforceAccess(String pkg, Uri uri) throws RemoteException {
        if (checkAccess(pkg, uri, Binder.getCallingUid(), Binder.getCallingPid())
                != PERMISSION_GRANTED) {
            int userId = ContentProvider.getUserIdFromUri(uri,
                    Binder.getCallingUserHandle().getIdentifier());
            if (!Objects.equals(pkg, getProviderPkg(uri, userId))) {
                throw new SecurityException("Access to slice " + uri + " is required");
            }
        }
        enforceCrossUser(pkg, uri);
    }

    private void verifyCaller(String pkg) {
        mAppOps.checkPackage(Binder.getCallingUid(), pkg);
    }

    private boolean hasFullSliceAccess(String pkg, int userId) {
        long ident = Binder.clearCallingIdentity();
        try {
            return isAssistant(pkg, userId) || isGrantedFullAccess(pkg, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean isAssistant(String pkg, int userId) {
        if (pkg == null) return false;
        if (!pkg.equals(mLastAssistantPackage.get(userId))) {
            // Failed on cached value, try updating.
            mLastAssistantPackage.put(userId, getAssistant(userId));
        }
        return pkg.equals(mLastAssistantPackage.get(userId));
    }

    private String getAssistant(int userId) {
        final ComponentName cn = mAssistUtils.getAssistComponentForUser(userId);
        if (cn == null) {
            return null;
        }
        return cn.getPackageName();
    }

    private boolean isGrantedFullAccess(String pkg, int userId) {
        return mPermissions.hasFullAccess(pkg, userId);
    }

    private static ServiceThread createHandler() {
        ServiceThread handlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
        handlerThread.start();
        return handlerThread;
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId  = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (userId == UserHandle.USER_NULL) {
                Slog.w(TAG, "Intent broadcast does not contain user handle: " + intent);
                return;
            }
            Uri data = intent.getData();
            String pkg = data != null ? data.getSchemeSpecificPart() : null;
            if (pkg == null) {
                Slog.w(TAG, "Intent broadcast does not contain package name: " + intent);
                return;
            }
            switch (intent.getAction()) {
                case Intent.ACTION_PACKAGE_REMOVED:
                    final boolean replacing =
                            intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                    if (!replacing) {
                        mPermissions.removePkg(pkg, userId);
                    }
                    break;
                case Intent.ACTION_PACKAGE_DATA_CLEARED:
                    mPermissions.removePkg(pkg, userId);
                    break;
            }
        }
    };

    public String[] getAllPackagesGranted(String authority) {
        String pkg = getProviderPkg(new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .build(), 0);
        return mPermissions.getAllPackagesGranted(pkg);
    }

    public static class Lifecycle extends SystemService {
        private SliceManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new SliceManagerService(getContext());
            publishBinderService(Context.SLICE_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mService.systemReady();
            }
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            mService.onUnlockUser(user.getUserIdentifier());
        }

        @Override
        public void onUserStopping(@NonNull TargetUser user) {
            mService.onStopUser(user.getUserIdentifier());
        }
    }

    private class SliceGrant {
        private final Uri mUri;
        private final String mPkg;
        private final int mUserId;

        public SliceGrant(Uri uri, String pkg, int userId) {
            mUri = uri;
            mPkg = pkg;
            mUserId = userId;
        }

        @Override
        public int hashCode() {
            return mUri.hashCode() + mPkg.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof SliceGrant)) return false;
            SliceGrant other = (SliceGrant) obj;
            return Objects.equals(other.mUri, mUri) && Objects.equals(other.mPkg, mPkg)
                    && (other.mUserId == mUserId);
        }
    }
}
