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

import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.Context.BIND_NOT_FOREGROUND;
import static android.content.Context.BIND_NOT_VISIBLE;
import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AUTO;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;

import android.annotation.BoolRes;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.content.PackageMonitor;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

/**
 * Maintains a binding to the best service that matches the given intent information. Bind and
 * unbind callbacks, as well as all binder operations, will all be run on a single thread.
 */
public class ServiceWatcher implements ServiceConnection {

    private static final String TAG = "ServiceWatcher";
    private static final boolean D = Log.isLoggable(TAG, Log.DEBUG);

    private static final String EXTRA_SERVICE_VERSION = "serviceVersion";
    private static final String EXTRA_SERVICE_IS_MULTIUSER = "serviceIsMultiuser";

    private static final long RETRY_DELAY_MS = 15 * 1000;

    /** Function to run on binder interface. */
    public interface BinderRunner {
        /** Called to run client code with the binder. */
        void run(IBinder binder) throws RemoteException;
        /**
         * Called if an error occurred and the function could not be run. This callback is only
         * intended for resource deallocation and cleanup in response to a single binder operation,
         * it should not be used to propagate errors further.
         */
        default void onError() {}
    }

    /** Function to run on binder interface when first bound. */
    public interface OnBindRunner {
        /** Called to run client code with the binder. */
        void run(IBinder binder, ComponentName service) throws RemoteException;
    }

    /**
     * Information on the service ServiceWatcher has selected as the best option for binding.
     */
    private static final class ServiceInfo implements Comparable<ServiceInfo> {

        public static final ServiceInfo NONE = new ServiceInfo(Integer.MIN_VALUE, null,
                UserHandle.USER_NULL, false);

        public final int version;
        @Nullable public final ComponentName component;
        @UserIdInt public final int userId;
        public final boolean serviceIsMultiuser;

        ServiceInfo(ResolveInfo resolveInfo, int currentUserId) {
            Preconditions.checkArgument(resolveInfo.serviceInfo.getComponentName() != null);

            Bundle metadata = resolveInfo.serviceInfo.metaData;
            if (metadata != null) {
                version = metadata.getInt(EXTRA_SERVICE_VERSION, Integer.MIN_VALUE);
                serviceIsMultiuser = metadata.getBoolean(EXTRA_SERVICE_IS_MULTIUSER, false);
            } else {
                version = Integer.MIN_VALUE;
                serviceIsMultiuser = false;
            }

            component = resolveInfo.serviceInfo.getComponentName();
            userId = serviceIsMultiuser ? UserHandle.USER_SYSTEM : currentUserId;
        }

        private ServiceInfo(int version, @Nullable ComponentName component, int userId,
                boolean serviceIsMultiuser) {
            Preconditions.checkArgument(component != null || version == Integer.MIN_VALUE);
            this.version = version;
            this.component = component;
            this.userId = userId;
            this.serviceIsMultiuser = serviceIsMultiuser;
        }

        public @Nullable String getPackageName() {
            return component != null ? component.getPackageName() : null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ServiceInfo)) {
                return false;
            }
            ServiceInfo that = (ServiceInfo) o;
            return version == that.version && userId == that.userId
                    && Objects.equals(component, that.component);
        }

        @Override
        public int hashCode() {
            return Objects.hash(version, component, userId);
        }

        @Override
        public int compareTo(ServiceInfo that) {
            // ServiceInfos with higher version numbers always win (having a version number >
            // MIN_VALUE implies having a non-null component). if version numbers are equal, a
            // non-null component wins over a null component. if the version numbers are equal and
            // both components exist then we prefer components that work for all users vs components
            // that only work for a single user at a time. otherwise everything's equal.
            int ret = Integer.compare(version, that.version);
            if (ret == 0) {
                if (component == null && that.component != null) {
                    ret = -1;
                } else if (component != null && that.component == null) {
                    ret = 1;
                } else {
                    if (userId != UserHandle.USER_SYSTEM && that.userId == UserHandle.USER_SYSTEM) {
                        ret = -1;
                    } else if (userId == UserHandle.USER_SYSTEM
                            && that.userId != UserHandle.USER_SYSTEM) {
                        ret = 1;
                    }
                }
            }
            return ret;
        }

        @Override
        public String toString() {
            if (component == null) {
                return "none";
            } else {
                return component.toShortString() + "@" + version + "[u" + userId + "]";
            }
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final Intent mIntent;

    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public boolean onPackageChanged(String packageName, int uid, String[] components) {
            return true;
        }

        @Override
        public void onSomePackagesChanged() {
            onBestServiceChanged(false);
        }
    };
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (userId == UserHandle.USER_NULL) {
                return;
            }

            switch (action) {
                case Intent.ACTION_USER_SWITCHED:
                    onUserSwitched(userId);
                    break;
                case Intent.ACTION_USER_UNLOCKED:
                    onUserUnlocked(userId);
                    break;
                default:
                    break;
            }

        }
    };

    @Nullable private final OnBindRunner mOnBind;
    @Nullable private final Runnable mOnUnbind;

    // write from caller thread only, read anywhere
    private volatile boolean mRegistered;

    // read/write from handler thread only
    private int mCurrentUserId;

    // write from handler thread only, read anywhere
    private volatile ServiceInfo mTargetService;
    private volatile IBinder mBinder;

    public ServiceWatcher(Context context, String action,
            @Nullable OnBindRunner onBind, @Nullable Runnable onUnbind,
            @BoolRes int enableOverlayResId, @StringRes int nonOverlayPackageResId) {
        this(context, FgThread.getHandler(), action, onBind, onUnbind, enableOverlayResId,
                nonOverlayPackageResId);
    }

    public ServiceWatcher(Context context, Handler handler, String action,
            @Nullable OnBindRunner onBind, @Nullable Runnable onUnbind,
            @BoolRes int enableOverlayResId, @StringRes int nonOverlayPackageResId) {
        mContext = context;
        mHandler = handler;
        mIntent = new Intent(Objects.requireNonNull(action));

        Resources resources = context.getResources();
        boolean enableOverlay = resources.getBoolean(enableOverlayResId);
        if (!enableOverlay) {
            mIntent.setPackage(resources.getString(nonOverlayPackageResId));
        }

        mOnBind = onBind;
        mOnUnbind = onUnbind;

        mCurrentUserId = UserHandle.USER_NULL;

        mTargetService = ServiceInfo.NONE;
        mBinder = null;
    }

    /**
     * Returns true if there is at least one component that could satisfy the ServiceWatcher's
     * constraints.
     */
    public boolean checkServiceResolves() {
        return !mContext.getPackageManager().queryIntentServicesAsUser(mIntent,
                MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE | MATCH_SYSTEM_ONLY,
                UserHandle.USER_SYSTEM).isEmpty();
    }

    /**
     * Starts the process of determining the best matching service and maintaining a binding to it.
     */
    public void register() {
        Preconditions.checkState(!mRegistered);

        mPackageMonitor.register(mContext, UserHandle.ALL, true, mHandler);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, intentFilter, null,
                mHandler);

        mCurrentUserId = ActivityManager.getCurrentUser();

        mRegistered = true;

        mHandler.post(() -> onBestServiceChanged(false));
    }

    /**
     * Stops the process of determining the best matching service and releases any binding.
     */
    public void unregister() {
        Preconditions.checkState(mRegistered);

        mRegistered = false;

        mPackageMonitor.unregister();
        mContext.unregisterReceiver(mBroadcastReceiver);

        mHandler.post(() -> onBestServiceChanged(false));
    }

    private void onBestServiceChanged(boolean forceRebind) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        ServiceInfo bestServiceInfo = ServiceInfo.NONE;

        if (mRegistered) {
            List<ResolveInfo> resolveInfos = mContext.getPackageManager().queryIntentServicesAsUser(
                    mIntent,
                    GET_META_DATA | MATCH_DIRECT_BOOT_AUTO | MATCH_SYSTEM_ONLY,
                    mCurrentUserId);
            for (ResolveInfo resolveInfo : resolveInfos) {
                ServiceInfo serviceInfo = new ServiceInfo(resolveInfo, mCurrentUserId);
                if (serviceInfo.compareTo(bestServiceInfo) > 0) {
                    bestServiceInfo = serviceInfo;
                }
            }
        }

        if (forceRebind || !bestServiceInfo.equals(mTargetService)) {
            rebind(bestServiceInfo);
        }
    }

    private void rebind(ServiceInfo newServiceInfo) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        if (!mTargetService.equals(ServiceInfo.NONE)) {
            if (D) {
                Log.d(TAG, "[" + mIntent.getAction() + "] unbinding from " + mTargetService);
            }

            mContext.unbindService(this);
            onServiceDisconnected(mTargetService.component);
            mTargetService = ServiceInfo.NONE;
        }

        mTargetService = newServiceInfo;
        if (mTargetService.equals(ServiceInfo.NONE)) {
            return;
        }

        Preconditions.checkState(mTargetService.component != null);

        Log.i(TAG, getLogPrefix() + " binding to " + mTargetService);

        Intent bindIntent = new Intent(mIntent).setComponent(mTargetService.component);
        if (!mContext.bindServiceAsUser(bindIntent, this,
                BIND_AUTO_CREATE | BIND_NOT_FOREGROUND | BIND_NOT_VISIBLE,
                mHandler, UserHandle.of(mTargetService.userId))) {
            mTargetService = ServiceInfo.NONE;
            Log.e(TAG, getLogPrefix() + " unexpected bind failure - retrying later");
            mHandler.postDelayed(() -> onBestServiceChanged(false), RETRY_DELAY_MS);
        }
    }

    @Override
    public final void onServiceConnected(ComponentName component, IBinder binder) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());
        Preconditions.checkState(mBinder == null);

        if (D) {
            Log.d(TAG, getLogPrefix() + " connected to " + component.toShortString());
        }

        mBinder = binder;
        if (mOnBind != null) {
            try {
                mOnBind.run(binder, component);
            } catch (RuntimeException | RemoteException e) {
                // binders may propagate some specific non-RemoteExceptions from the other side
                // through the binder as well - we cannot allow those to crash the system server
                Log.e(TAG, getLogPrefix() + " exception running on " + component, e);
            }
        }
    }

    @Override
    public final void onServiceDisconnected(ComponentName component) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        if (mBinder == null) {
            return;
        }

        if (D) {
            Log.d(TAG, getLogPrefix() + " disconnected from " + component.toShortString());
        }

        mBinder = null;
        if (mOnUnbind != null) {
            mOnUnbind.run();
        }
    }

    @Override
    public final void onBindingDied(ComponentName component) {
        Preconditions.checkState(Looper.myLooper() == mHandler.getLooper());

        Log.i(TAG, getLogPrefix() + " " + component.toShortString() + " died");

        onBestServiceChanged(true);
    }

    @Override
    public final void onNullBinding(ComponentName component) {
        Log.e(TAG, getLogPrefix() + " " + component.toShortString() + " has null binding");
    }

    void onUserSwitched(@UserIdInt int userId) {
        mCurrentUserId = userId;
        onBestServiceChanged(false);
    }

    void onUserUnlocked(@UserIdInt int userId) {
        if (userId == mCurrentUserId) {
            onBestServiceChanged(false);
        }
    }

    /**
     * Runs the given function asynchronously if and only if currently connected. Suppresses any
     * RemoteException thrown during execution.
     */
    public final void runOnBinder(BinderRunner runner) {
        mHandler.post(() -> {
            if (mBinder == null) {
                runner.onError();
                return;
            }

            try {
                runner.run(mBinder);
            } catch (RuntimeException | RemoteException e) {
                // binders may propagate some specific non-RemoteExceptions from the other side
                // through the binder as well - we cannot allow those to crash the system server
                Log.e(TAG, getLogPrefix() + " exception running on " + mTargetService, e);
                runner.onError();
            }
        });
    }

    private String getLogPrefix() {
        return "[" + mIntent.getAction() + "]";
    }

    @Override
    public String toString() {
        return mTargetService.toString();
    }

    /**
     * Dump for debugging.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("target service=" + mTargetService);
        pw.println("connected=" + (mBinder != null));
    }
}
