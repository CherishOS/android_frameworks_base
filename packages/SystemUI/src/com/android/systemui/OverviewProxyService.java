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

package com.android.systemui;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.SurfaceControl;

import com.android.systemui.OverviewProxyService.OverviewProxyListener;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.recents.ISystemUiProxy;
import com.android.systemui.shared.system.GraphicBufferCompat;
import com.android.systemui.statusbar.policy.CallbackController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Class to send information from overview to launcher with a binder.
 */
public class OverviewProxyService implements CallbackController<OverviewProxyListener>, Dumpable {

    private static final String TAG = "OverviewProxyService";
    private static final long BACKOFF_MILLIS = 5000;

    private final Context mContext;
    private final Handler mHandler;
    private final Runnable mConnectionRunnable = this::internalConnectToCurrentUser;
    private final ComponentName mLauncherComponentName;
    private final DeviceProvisionedController mDeviceProvisionedController
            = Dependency.get(DeviceProvisionedController.class);
    private final List<OverviewProxyListener> mConnectionCallbacks = new ArrayList<>();

    private IOverviewProxy mOverviewProxy;
    private int mConnectionBackoffAttempts;

    private ISystemUiProxy mSysUiProxy = new ISystemUiProxy.Stub() {
        public GraphicBufferCompat screenshot(Rect sourceCrop, int width, int height, int minLayer,
                int maxLayer, boolean useIdentityTransform, int rotation) {
            long token = Binder.clearCallingIdentity();
            try {
                return new GraphicBufferCompat(SurfaceControl.screenshotToBuffer(sourceCrop, width,
                        height, minLayer, maxLayer, useIdentityTransform, rotation));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    private final BroadcastReceiver mLauncherAddedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Reconnect immediately, instead of waiting for resume to arrive.
            startConnectionToCurrentUser();
        }
    };

    private final ServiceConnection mOverviewServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service != null) {
                mConnectionBackoffAttempts = 0;
                mOverviewProxy = IOverviewProxy.Stub.asInterface(service);
                // Listen for launcher's death
                try {
                    service.linkToDeath(mOverviewServiceDeathRcpt, 0);
                } catch (RemoteException e) {
                    Log.e(TAG, "Lost connection to launcher service", e);
                }
                try {
                    mOverviewProxy.onBind(mSysUiProxy);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to call onBind()", e);
                }
                notifyConnectionChanged();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Do nothing
        }
    };

    private final DeviceProvisionedListener mDeviceProvisionedCallback =
                new DeviceProvisionedListener() {
            @Override
            public void onUserSetupChanged() {
                if (mDeviceProvisionedController.isCurrentUserSetup()) {
                    internalConnectToCurrentUser();
                }
            }

            @Override
            public void onUserSwitched() {
                mConnectionBackoffAttempts = 0;
                internalConnectToCurrentUser();
            }
        };

    // This is the death handler for the binder from the launcher service
    private final IBinder.DeathRecipient mOverviewServiceDeathRcpt
            = this::startConnectionToCurrentUser;

    public OverviewProxyService(Context context) {
        mContext = context;
        mHandler = new Handler();
        mConnectionBackoffAttempts = 0;
        mLauncherComponentName = ComponentName
                .unflattenFromString(context.getString(R.string.config_overviewServiceComponent));
        mDeviceProvisionedController.addCallback(mDeviceProvisionedCallback);

        // Listen for the package update changes.
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart(mLauncherComponentName.getPackageName(),
                PatternMatcher.PATTERN_LITERAL);
        mContext.registerReceiver(mLauncherAddedReceiver, filter);
    }

    public void startConnectionToCurrentUser() {
        if (mHandler.getLooper() != Looper.myLooper()) {
            mHandler.post(mConnectionRunnable);
        } else {
            internalConnectToCurrentUser();
        }
    }

    private void internalConnectToCurrentUser() {
        disconnectFromLauncherService();

        // If user has not setup yet or already connected, do not try to connect
        if (!mDeviceProvisionedController.isCurrentUserSetup()) {
            return;
        }
        mHandler.removeCallbacks(mConnectionRunnable);
        Intent launcherServiceIntent = new Intent();
        launcherServiceIntent.setComponent(mLauncherComponentName);
        boolean bound = mContext.bindServiceAsUser(launcherServiceIntent,
                mOverviewServiceConnection, Context.BIND_AUTO_CREATE,
                UserHandle.getUserHandleForUid(mDeviceProvisionedController.getCurrentUser()));
        if (!bound) {
            // Retry after exponential backoff timeout
            final long timeoutMs = (long) Math.scalb(BACKOFF_MILLIS, mConnectionBackoffAttempts);
            mHandler.postDelayed(mConnectionRunnable, timeoutMs);
            mConnectionBackoffAttempts++;
        }
    }

    @Override
    public void addCallback(OverviewProxyListener listener) {
        mConnectionCallbacks.add(listener);
        listener.onConnectionChanged(mOverviewProxy != null);
    }

    @Override
    public void removeCallback(OverviewProxyListener listener) {
        mConnectionCallbacks.remove(listener);
    }

    public IOverviewProxy getProxy() {
        return mOverviewProxy;
    }

    private void disconnectFromLauncherService() {
        if (mOverviewProxy != null) {
            mOverviewProxy.asBinder().unlinkToDeath(mOverviewServiceDeathRcpt, 0);
            mContext.unbindService(mOverviewServiceConnection);
            mOverviewProxy = null;
            notifyConnectionChanged();
        }
    }

    private void notifyConnectionChanged() {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onConnectionChanged(mOverviewProxy != null);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(TAG + " state:");
        pw.print("  mConnectionBackoffAttempts="); pw.println(mConnectionBackoffAttempts);
        pw.print("  isCurrentUserSetup="); pw.println(mDeviceProvisionedController
                .isCurrentUserSetup());
        pw.print("  isConnected="); pw.println(mOverviewProxy != null);
    }

    public interface OverviewProxyListener {
        void onConnectionChanged(boolean isConnected);
    }
}
