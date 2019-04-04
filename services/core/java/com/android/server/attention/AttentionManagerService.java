/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.attention;

import static android.provider.DeviceConfig.NAMESPACE_ATTENTION_MANAGER_SERVICE;
import static android.provider.Settings.System.ADAPTIVE_SLEEP;
import static android.service.attention.AttentionService.ATTENTION_FAILURE_CANCELLED;
import static android.service.attention.AttentionService.ATTENTION_FAILURE_UNKNOWN;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.attention.AttentionManagerInternal;
import android.attention.AttentionManagerInternal.AttentionCallbackInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.service.attention.AttentionService;
import android.service.attention.AttentionService.AttentionFailureCodes;
import android.service.attention.IAttentionCallback;
import android.service.attention.IAttentionService;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.StatsLog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * An attention service implementation that runs in System Server process.
 * This service publishes a LocalService and reroutes calls to a {@link AttentionService} that it
 * manages.
 */
public class AttentionManagerService extends SystemService {
    private static final String LOG_TAG = "AttentionManagerService";

    /**
     * DeviceConfig flag name, allows a CTS to inject a fake implementation.
     *
     * @hide
     */
    @TestApi
    public static final String COMPONENT_NAME = "component_name";


    /** Default value in absence of {@link DeviceConfig} override. */
    private static final boolean DEFAULT_SERVICE_ENABLED = true;

    /** Service will unbind if connection is not used for that amount of time. */
    private static final long CONNECTION_TTL_MILLIS = 60_000;

    /** If the check attention called within that period - cached value will be returned. */
    private static final long STALE_AFTER_MILLIS = 5_000;

    /** DeviceConfig flag name, if {@code true}, enables AttentionManagerService features. */
    private static final String SERVICE_ENABLED = "service_enabled";
    private final Context mContext;
    private final PowerManager mPowerManager;
    private final Object mLock;
    @GuardedBy("mLock")
    private final SparseArray<UserState> mUserStates = new SparseArray<>();
    private final AttentionHandler mAttentionHandler;

    private ComponentName mComponentName;

    public AttentionManagerService(Context context) {
        super(context);
        mContext = Preconditions.checkNotNull(context);
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mLock = new Object();
        mAttentionHandler = new AttentionHandler();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.ATTENTION_SERVICE, new BinderService());
        publishLocalService(AttentionManagerInternal.class, new LocalService());
    }

    @Override
    public void onSwitchUser(int userId) {
        cancelAndUnbindLocked(peekUserStateLocked(userId));
    }

    /** Returns {@code true} if attention service is configured on this device. */
    public static boolean isServiceConfigured(Context context) {
        return !TextUtils.isEmpty(getServiceConfig(context));
    }

    /** Resolves and sets up the attention service if it had not been done yet. */
    private boolean isServiceAvailable() {
        if (mComponentName == null) {
            mComponentName = resolveAttentionService(mContext);
            if (mComponentName != null) {
                mContext.registerReceiver(new ScreenStateReceiver(),
                        new IntentFilter(Intent.ACTION_SCREEN_OFF));
            }
        }
        return mComponentName != null;
    }

    /**
     * Returns {@code true} if attention service is supported on this device.
     */
    private boolean isAttentionServiceSupported() {
        return isServiceEnabled() && isServiceAvailable();
    }

    private boolean isServiceEnabled() {
        return DeviceConfig.getBoolean(NAMESPACE_ATTENTION_MANAGER_SERVICE, SERVICE_ENABLED,
                DEFAULT_SERVICE_ENABLED);
    }

    /**
     * Checks whether user attention is at the screen and calls in the provided callback.
     *
     * @return {@code true} if the framework was able to send the provided callback to the service
     */
    private boolean checkAttention(long timeout, AttentionCallbackInternal callbackInternal) {
        Preconditions.checkNotNull(callbackInternal);

        if (!isAttentionServiceSupported()) {
            Slog.w(LOG_TAG, "Trying to call checkAttention() on an unsupported device.");
            return false;
        }

        // don't allow attention check in screen off state
        if (!mPowerManager.isInteractive()) {
            return false;
        }

        synchronized (mLock) {
            final long now = SystemClock.uptimeMillis();
            // schedule shutting down the connection if no one resets this timer
            freeIfInactiveLocked();

            final UserState userState = getOrCreateCurrentUserStateLocked();
            // lazily start the service, which should be very lightweight to start
            if (!userState.bindLocked()) {
                return false;
            }

            if (userState.mService == null) {
                // make sure every callback is called back
                if (userState.mPendingAttentionCheck != null) {
                    userState.mPendingAttentionCheck.cancel(
                            ATTENTION_FAILURE_CANCELLED);
                }
                // fire the check when the service is started
                userState.mPendingAttentionCheck = new PendingAttentionCheck(
                        callbackInternal, () -> checkAttention(timeout, callbackInternal));
            } else {
                try {
                    // throttle frequent requests
                    final AttentionCheckCache cache = userState.mAttentionCheckCache;
                    if (cache != null && now < cache.mLastComputed + STALE_AFTER_MILLIS) {
                        callbackInternal.onSuccess(cache.mResult, cache.mTimestamp);
                        return true;
                    }

                    // schedule request cancellation if not returned by that point yet
                    cancelAfterTimeoutLocked(timeout);

                    userState.mCurrentAttentionCheck = new AttentionCheck(callbackInternal,
                            new IAttentionCallback.Stub() {
                                @Override
                                public void onSuccess(int result, long timestamp) {
                                    callbackInternal.onSuccess(result, timestamp);
                                    synchronized (mLock) {
                                        userState.mAttentionCheckCache = new AttentionCheckCache(
                                                SystemClock.uptimeMillis(), result,
                                                timestamp);
                                        userState.mCurrentAttentionCheckIsFulfilled = true;
                                    }
                                    StatsLog.write(
                                            StatsLog.ATTENTION_MANAGER_SERVICE_RESULT_REPORTED,
                                            result);
                                }

                                @Override
                                public void onFailure(int error) {
                                    callbackInternal.onFailure(error);
                                    userState.mCurrentAttentionCheckIsFulfilled = true;
                                    StatsLog.write(
                                            StatsLog.ATTENTION_MANAGER_SERVICE_RESULT_REPORTED,
                                            error);
                                }
                            });
                    userState.mService.checkAttention(
                            userState.mCurrentAttentionCheck.mIAttentionCallback);
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Cannot call into the AttentionService");
                    return false;
                }
            }
            return true;
        }
    }

    /** Cancels the specified attention check. */
    private void cancelAttentionCheck(AttentionCallbackInternal callbackInternal) {
        synchronized (mLock) {
            final UserState userState = peekCurrentUserStateLocked();
            if (userState == null) {
                return;
            }
            if (userState.mService == null) {
                if (userState.mPendingAttentionCheck != null
                        && userState.mPendingAttentionCheck.mCallbackInternal.equals(
                        callbackInternal)) {
                    userState.mPendingAttentionCheck.cancel(ATTENTION_FAILURE_UNKNOWN);
                    userState.mPendingAttentionCheck = null;
                }
                return;
            }
            if (userState.mCurrentAttentionCheck.mCallbackInternal.equals(callbackInternal)) {
                try {
                    userState.mService.cancelAttentionCheck(
                            userState.mCurrentAttentionCheck.mIAttentionCallback);
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Cannot call into the AttentionService");
                }
            } else {
                Slog.e(LOG_TAG, "Cannot cancel a non-current request");
            }
        }
    }

    /** Disables service dependants. */
    private void disableSelf() {
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.System.putInt(mContext.getContentResolver(), ADAPTIVE_SLEEP, 0);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @GuardedBy("mLock")
    private void freeIfInactiveLocked() {
        // If we are called here, it means someone used the API again - reset the timer then.
        mAttentionHandler.removeMessages(AttentionHandler.CHECK_CONNECTION_EXPIRATION);

        // Schedule resources cleanup if no one calls the API again.
        mAttentionHandler.sendEmptyMessageDelayed(AttentionHandler.CHECK_CONNECTION_EXPIRATION,
                CONNECTION_TTL_MILLIS);
    }

    @GuardedBy("mLock")
    private void cancelAfterTimeoutLocked(long timeout) {
        mAttentionHandler.sendEmptyMessageDelayed(AttentionHandler.ATTENTION_CHECK_TIMEOUT,
                timeout);
    }


    @GuardedBy("mLock")
    private UserState getOrCreateCurrentUserStateLocked() {
        return getOrCreateUserStateLocked(ActivityManager.getCurrentUser());
    }

    @GuardedBy("mLock")
    private UserState getOrCreateUserStateLocked(int userId) {
        UserState result = mUserStates.get(userId);
        if (result == null) {
            result = new UserState(userId, mContext, mLock, mComponentName);
            mUserStates.put(userId, result);
        }
        return result;
    }

    @GuardedBy("mLock")
    @Nullable
    private UserState peekCurrentUserStateLocked() {
        return peekUserStateLocked(ActivityManager.getCurrentUser());
    }

    @GuardedBy("mLock")
    @Nullable
    private UserState peekUserStateLocked(int userId) {
        return mUserStates.get(userId);
    }

    private static String getServiceConfig(Context context) {
        return context.getString(R.string.config_defaultAttentionService);
    }

    /**
     * Provides attention service component name at runtime, making sure it's provided by the
     * system.
     */
    private static ComponentName resolveAttentionService(Context context) {
        final String flag = DeviceConfig.getProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                COMPONENT_NAME);

        final String componentNameString = flag != null ? flag : getServiceConfig(context);
        if (TextUtils.isEmpty(componentNameString)) {
            return null;
        }

        final ComponentName componentName = ComponentName.unflattenFromString(componentNameString);
        if (componentName == null) {
            return null;
        }

        final Intent intent = new Intent(AttentionService.SERVICE_INTERFACE).setPackage(
                componentName.getPackageName());

        // Make sure that only system apps can declare the AttentionService.
        final ResolveInfo resolveInfo = context.getPackageManager().resolveService(intent,
                PackageManager.MATCH_SYSTEM_ONLY);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            Slog.wtf(LOG_TAG, String.format("Service %s not found in package %s",
                    AttentionService.SERVICE_INTERFACE, componentName
            ));
            return null;
        }

        final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        final String permission = serviceInfo.permission;
        if (Manifest.permission.BIND_ATTENTION_SERVICE.equals(permission)) {
            return serviceInfo.getComponentName();
        }
        Slog.e(LOG_TAG, String.format(
                "Service %s should require %s permission. Found %s permission",
                serviceInfo.getComponentName(),
                Manifest.permission.BIND_ATTENTION_SERVICE,
                serviceInfo.permission));
        return null;
    }

    private void dumpInternal(IndentingPrintWriter ipw) {
        ipw.println("Attention Manager Service (dumpsys attention) state:\n");

        ipw.printPair("context", mContext);
        ipw.println();
        synchronized (mLock) {
            int size = mUserStates.size();
            ipw.print("Number user states: ");
            ipw.println(size);
            if (size > 0) {
                ipw.increaseIndent();
                for (int i = 0; i < size; i++) {
                    UserState userState = mUserStates.valueAt(i);
                    ipw.print(i);
                    ipw.print(":");
                    userState.dump(ipw);
                    ipw.println();
                }
                ipw.decreaseIndent();
            }
        }
    }

    private final class LocalService extends AttentionManagerInternal {
        @Override
        public boolean isAttentionServiceSupported() {
            return AttentionManagerService.this.isAttentionServiceSupported();
        }

        @Override
        public boolean checkAttention(long timeout, AttentionCallbackInternal callbackInternal) {
            return AttentionManagerService.this.checkAttention(timeout, callbackInternal);
        }

        @Override
        public void cancelAttentionCheck(AttentionCallbackInternal callbackInternal) {
            AttentionManagerService.this.cancelAttentionCheck(callbackInternal);
        }

        @Override
        public void disableSelf() {
            AttentionManagerService.this.disableSelf();
        }
    }

    private static final class AttentionCheckCache {
        private final long mLastComputed;
        private final int mResult;
        private final long mTimestamp;

        AttentionCheckCache(long lastComputed, @AttentionService.AttentionSuccessCodes int result,
                long timestamp) {
            mLastComputed = lastComputed;
            mResult = result;
            mTimestamp = timestamp;
        }
    }

    private static final class PendingAttentionCheck {
        private final AttentionCallbackInternal mCallbackInternal;
        private final Runnable mRunnable;

        PendingAttentionCheck(AttentionCallbackInternal callbackInternal,
                Runnable runnable) {
            mCallbackInternal = callbackInternal;
            mRunnable = runnable;
        }

        void cancel(@AttentionFailureCodes int failureCode) {
            mCallbackInternal.onFailure(failureCode);
        }

        void run() {
            mRunnable.run();
        }
    }

    private static final class AttentionCheck {
        private final AttentionCallbackInternal mCallbackInternal;
        private final IAttentionCallback mIAttentionCallback;

        AttentionCheck(AttentionCallbackInternal callbackInternal,
                IAttentionCallback iAttentionCallback) {
            mCallbackInternal = callbackInternal;
            mIAttentionCallback = iAttentionCallback;
        }
    }

    private static final class UserState {
        final ComponentName mComponentName;
        final AttentionServiceConnection mConnection = new AttentionServiceConnection();

        @GuardedBy("mLock")
        IAttentionService mService;
        @GuardedBy("mLock")
        boolean mBinding;
        @GuardedBy("mLock")
        AttentionCheck mCurrentAttentionCheck;
        @GuardedBy("mLock")
        boolean mCurrentAttentionCheckIsFulfilled;

        @GuardedBy("mLock")
        PendingAttentionCheck mPendingAttentionCheck;
        @GuardedBy("mLock")
        AttentionCheckCache mAttentionCheckCache;

        @UserIdInt
        final int mUserId;
        final Context mContext;
        final Object mLock;

        private UserState(int userId, Context context, Object lock, ComponentName componentName) {
            mUserId = userId;
            mContext = Preconditions.checkNotNull(context);
            mLock = Preconditions.checkNotNull(lock);
            mComponentName = Preconditions.checkNotNull(componentName);
        }


        @GuardedBy("mLock")
        private void handlePendingCallbackLocked() {
            if (mService != null && mPendingAttentionCheck != null) {
                mPendingAttentionCheck.run();
                mPendingAttentionCheck = null;
            }
        }

        /** Binds to the system's AttentionService which provides an actual implementation. */
        @GuardedBy("mLock")
        private boolean bindLocked() {
            // No need to bind if service is binding or has already been bound.
            if (mBinding || mService != null) {
                return true;
            }

            final boolean willBind;
            final long identity = Binder.clearCallingIdentity();

            try {
                final Intent mServiceIntent = new Intent(
                        AttentionService.SERVICE_INTERFACE).setComponent(
                        mComponentName);
                willBind = mContext.bindServiceAsUser(mServiceIntent, mConnection,
                        Context.BIND_AUTO_CREATE, UserHandle.CURRENT);
                mBinding = willBind;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return willBind;
        }

        private void dump(IndentingPrintWriter pw) {
            pw.printPair("context", mContext);
            pw.printPair("userId", mUserId);
            synchronized (mLock) {
                pw.printPair("binding", mBinding);
                pw.printPair("isAttentionCheckPending", mPendingAttentionCheck != null);
            }
        }

        private final class AttentionServiceConnection implements ServiceConnection {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                init(IAttentionService.Stub.asInterface(service));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                cleanupService();
            }

            @Override
            public void onBindingDied(ComponentName name) {
                cleanupService();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                cleanupService();
            }

            void cleanupService() {
                init(null);
            }

            private void init(@Nullable IAttentionService service) {
                synchronized (mLock) {
                    mService = service;
                    mBinding = false;
                    handlePendingCallbackLocked();
                }
            }
        }
    }

    private class AttentionHandler extends Handler {
        private static final int CHECK_CONNECTION_EXPIRATION = 1;
        private static final int ATTENTION_CHECK_TIMEOUT = 2;

        AttentionHandler() {
            super(Looper.myLooper());
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                // Do not occupy resources when not in use - unbind proactively.
                case CHECK_CONNECTION_EXPIRATION: {
                    for (int i = 0; i < mUserStates.size(); i++) {
                        cancelAndUnbindLocked(mUserStates.valueAt(i));
                    }
                }
                break;

                // Callee is no longer interested in the attention check result - cancel.
                case ATTENTION_CHECK_TIMEOUT: {
                    synchronized (mLock) {
                        final UserState userState = peekCurrentUserStateLocked();
                        if (userState != null) {
                            // If not called back already.
                            if (!userState.mCurrentAttentionCheckIsFulfilled) {
                                cancel(userState, AttentionService.ATTENTION_FAILURE_TIMED_OUT);
                            }

                        }
                    }
                }
                break;

                default:
                    break;
            }
        }
    }

    private void cancel(@NonNull UserState userState, @AttentionFailureCodes int failureCode) {
        if (userState.mService != null) {
            try {
                userState.mService.cancelAttentionCheck(
                        userState.mCurrentAttentionCheck.mIAttentionCallback);
            } catch (RemoteException e) {
                Slog.e(LOG_TAG, "Unable to cancel attention check");
            }

            if (userState.mPendingAttentionCheck != null) {
                userState.mPendingAttentionCheck.cancel(failureCode);
                userState.mPendingAttentionCheck = null;
            }
        }
    }

    @GuardedBy("mLock")
    private void cancelAndUnbindLocked(UserState userState) {
        synchronized (mLock) {
            if (userState == null) {
                return;
            }
            cancel(userState, ATTENTION_FAILURE_UNKNOWN);

            mContext.unbindService(userState.mConnection);
            userState.mConnection.cleanupService();
            mUserStates.remove(userState.mUserId);
        }
    }

    /**
     * Unbinds and stops the service when the screen off intent is received.
     * Attention service only makes sense when screen is ON; disconnect and stop service otherwise.
     */
    private final class ScreenStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                cancelAndUnbindLocked(peekCurrentUserStateLocked());
            }
        }
    }

    private final class BinderService extends Binder {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) {
                return;
            }

            dumpInternal(new IndentingPrintWriter(pw, "  "));
        }
    }
}
