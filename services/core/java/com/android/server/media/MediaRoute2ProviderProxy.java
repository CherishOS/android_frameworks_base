/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.server.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.IMediaRoute2Provider;
import android.media.IMediaRoute2ProviderClient;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRoute2ProviderService;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * Maintains a connection to a particular media route provider service.
 */
final class MediaRoute2ProviderProxy implements ServiceConnection {
    private static final String TAG = "MediaRoute2Provider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final ComponentName mComponentName;
    private final String mUniqueId;
    private final int mUserId;
    private final Handler mHandler;

    private Callback mCallback;

    private MediaRoute2ProviderInfo mProviderInfo;

    // Connection state
    private boolean mRunning;
    private boolean mBound;
    private Connection mActiveConnection;
    private boolean mConnectionReady;

    MediaRoute2ProviderProxy(@NonNull Context context, @NonNull ComponentName componentName,
            int userId) {
        mContext = Objects.requireNonNull(context, "Context must not be null.");
        mComponentName = Objects.requireNonNull(componentName, "Component name must not be null.");
        mUniqueId = componentName.flattenToShortString();
        mUserId = userId;
        mHandler = new Handler();
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "Proxy");
        pw.println(prefix + "  mUserId=" + mUserId);
        pw.println(prefix + "  mRunning=" + mRunning);
        pw.println(prefix + "  mBound=" + mBound);
        pw.println(prefix + "  mActiveConnection=" + mActiveConnection);
        pw.println(prefix + "  mConnectionReady=" + mConnectionReady);
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public void selectRoute(String packageName, String routeId) {
        if (mConnectionReady) {
            mActiveConnection.selectRoute(packageName, routeId);
            updateBinding();
        }
    }

    public void unselectRoute(String packageName, String routeId) {
        if (mConnectionReady) {
            mActiveConnection.unselectRotue(packageName, routeId);
            updateBinding();
        }
    }

    public void sendControlRequest(MediaRoute2Info route, Intent request) {
        if (mConnectionReady) {
            mActiveConnection.sendControlRequest(route.getId(), request);
            updateBinding();
        }
    }

    public void requestSetVolume(MediaRoute2Info route, int volume) {
        if (mConnectionReady) {
            mActiveConnection.requestSetVolume(route.getId(), volume);
            updateBinding();
        }
    }

    public void requestUpdateVolume(MediaRoute2Info route, int delta) {
        if (mConnectionReady) {
            mActiveConnection.requestUpdateVolume(route.getId(), delta);
            updateBinding();
        }
    }

    @Nullable
    public MediaRoute2ProviderInfo getProviderInfo() {
        return mProviderInfo;
    }

    public boolean hasComponentName(String packageName, String className) {
        return mComponentName.getPackageName().equals(packageName)
                && mComponentName.getClassName().equals(className);
    }

    public void start() {
        if (!mRunning) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Starting");
            }
            mRunning = true;
            updateBinding();
        }
    }

    public void stop() {
        if (mRunning) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Stopping");
            }
            mRunning = false;
            updateBinding();
        }
    }

    public void rebindIfDisconnected() {
        if (mActiveConnection == null && shouldBind()) {
            unbind();
            bind();
        }
    }

    private void updateBinding() {
        if (shouldBind()) {
            bind();
        } else {
            unbind();
        }
    }

    private boolean shouldBind() {
        //TODO: Binding could be delayed until it's necessary.
        if (mRunning) {
            return true;
        }
        return false;
    }

    private void bind() {
        if (!mBound) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Binding");
            }

            Intent service = new Intent(MediaRoute2ProviderService.SERVICE_INTERFACE);
            service.setComponent(mComponentName);
            try {
                mBound = mContext.bindServiceAsUser(service, this,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                        new UserHandle(mUserId));
                if (!mBound && DEBUG) {
                    Slog.d(TAG, this + ": Bind failed");
                }
            } catch (SecurityException ex) {
                if (DEBUG) {
                    Slog.d(TAG, this + ": Bind failed", ex);
                }
            }
        }
    }

    private void unbind() {
        if (mBound) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Unbinding");
            }

            mBound = false;
            disconnect();
            mContext.unbindService(this);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (DEBUG) {
            Slog.d(TAG, this + ": Connected");
        }

        if (mBound) {
            disconnect();

            IMediaRoute2Provider provider = IMediaRoute2Provider.Stub.asInterface(service);
            if (provider != null) {
                Connection connection = new Connection(provider);
                if (connection.register()) {
                    mActiveConnection = connection;
                } else {
                    if (DEBUG) {
                        Slog.d(TAG, this + ": Registration failed");
                    }
                }
            } else {
                Slog.e(TAG, this + ": Service returned invalid remote display provider binder");
            }
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (DEBUG) {
            Slog.d(TAG, this + ": Service disconnected");
        }
        disconnect();
    }

    private void onConnectionReady(Connection connection) {
        if (mActiveConnection == connection) {
            mConnectionReady = true;
        }
    }

    private void onConnectionDied(Connection connection) {
        if (mActiveConnection == connection) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Service connection died");
            }
            disconnect();
        }
    }

    private void onProviderInfoUpdated(Connection connection, MediaRoute2ProviderInfo info) {
        if (mActiveConnection != connection) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, this + ": State changed ");
        }
        setAndNotifyProviderInfo(info);
    }

    private void setAndNotifyProviderInfo(MediaRoute2ProviderInfo info) {
        //TODO: check if info is not updated
        if (info == null) {
            mProviderInfo = null;
        } else {
            mProviderInfo = new MediaRoute2ProviderInfo.Builder(info)
                .setUniqueId(mUniqueId)
                .build();
        }
        mHandler.post(mStateChanged);
    }

    private void disconnect() {
        if (mActiveConnection != null) {
            mConnectionReady = false;
            mActiveConnection.dispose();
            mActiveConnection = null;
            setAndNotifyProviderInfo(null);
        }
    }

    @Override
    public String toString() {
        return "Service connection " + mComponentName.flattenToShortString();
    }

    private final Runnable mStateChanged = new Runnable() {
        @Override
        public void run() {
            if (mCallback != null) {
                mCallback.onProviderStateChanged(MediaRoute2ProviderProxy.this);
            }
        }
    };

    public interface Callback {
        void onProviderStateChanged(MediaRoute2ProviderProxy provider);
    }

    private final class Connection implements DeathRecipient {
        private final IMediaRoute2Provider mProvider;
        private final ProviderClient mClient;

        Connection(IMediaRoute2Provider provider) {
            mProvider = provider;
            mClient = new ProviderClient(this);
        }

        public boolean register() {
            try {
                mProvider.asBinder().linkToDeath(this, 0);
                mProvider.setClient(mClient);
                mHandler.post(() -> onConnectionReady(Connection.this));
                return true;
            } catch (RemoteException ex) {
                binderDied();
            }
            return false;
        }

        public void dispose() {
            mProvider.asBinder().unlinkToDeath(this, 0);
            mClient.dispose();
        }

        public void selectRoute(String packageName, String routeId) {
            try {
                mProvider.selectRoute(packageName, routeId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to set discovery mode.", ex);
            }
        }

        public void unselectRotue(String packageName, String routeId) {
            try {
                mProvider.unselectRoute(packageName, routeId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to set discovery mode.", ex);
            }
        }

        public void sendControlRequest(String routeId, Intent request) {
            try {
                mProvider.notifyControlRequestSent(routeId, request);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to send control request.", ex);
            }
        }

        public void requestSetVolume(String routeId, int volume) {
            try {
                mProvider.requestSetVolume(routeId, volume);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to request set volume.", ex);
            }
        }

        public void requestUpdateVolume(String routeId, int delta) {
            try {
                mProvider.requestUpdateVolume(routeId, delta);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to request update volume.", ex);
            }
        }

        @Override
        public void binderDied() {
            mHandler.post(() -> onConnectionDied(Connection.this));
        }

        void postProviderInfoUpdated(MediaRoute2ProviderInfo info) {
            mHandler.post(() -> onProviderInfoUpdated(Connection.this, info));
        }
    }

    private static final class ProviderClient extends IMediaRoute2ProviderClient.Stub  {
        private final WeakReference<Connection> mConnectionRef;

        ProviderClient(Connection connection) {
            mConnectionRef = new WeakReference<>(connection);
        }

        public void dispose() {
            mConnectionRef.clear();
        }

        @Override
        public void updateProviderInfo(MediaRoute2ProviderInfo info) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postProviderInfoUpdated(info);
            }
        }
    }
}
