/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.IInstantAppResolver;
import android.app.InstantAppResolverService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.InstantAppResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.TimedRemoteCaller;

import com.android.internal.os.TransferPipe;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Represents a remote ephemeral resolver. It is responsible for binding to the remote
 * service and handling all interactions in a timely manner.
 * @hide
 */
final class EphemeralResolverConnection {
    // This is running in a critical section and the timeout must be sufficiently low
    private static final long BIND_SERVICE_TIMEOUT_MS =
            ("eng".equals(Build.TYPE)) ? 300 : 200;

    private final Object mLock = new Object();
    private final GetEphemeralResolveInfoCaller mGetEphemeralResolveInfoCaller =
            new GetEphemeralResolveInfoCaller();
    private final ServiceConnection mServiceConnection = new MyServiceConnection();
    private final Context mContext;
    /** Intent used to bind to the service */
    private final Intent mIntent;

    private volatile boolean mBindRequested;
    private IInstantAppResolver mRemoteInstance;

    public EphemeralResolverConnection(Context context, ComponentName componentName) {
        mContext = context;
        mIntent = new Intent(Intent.ACTION_RESOLVE_INSTANT_APP_PACKAGE).setComponent(componentName);
    }

    public final List<InstantAppResolveInfo> getInstantAppResolveInfoList(int hashPrefix[],
            String token) {
        throwIfCalledOnMainThread();
        try {
            return mGetEphemeralResolveInfoCaller.getEphemeralResolveInfoList(
                    getRemoteInstanceLazy(), hashPrefix, token);
        } catch (RemoteException re) {
        } catch (TimeoutException te) {
        } finally {
            synchronized (mLock) {
                mLock.notifyAll();
            }
        }
        return null;
    }

    public final void getInstantAppIntentFilterList(int hashPrefix[], String token,
            String hostName, PhaseTwoCallback callback, Handler callbackHandler,
            final long startTime) {
        final IRemoteCallback remoteCallback = new IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle data) throws RemoteException {
                final ArrayList<InstantAppResolveInfo> resolveList =
                        data.getParcelableArrayList(
                                InstantAppResolverService.EXTRA_RESOLVE_INFO);
                callbackHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onPhaseTwoResolved(resolveList, startTime);
                    }
                });
            }
        };
        try {
            getRemoteInstanceLazy()
                    .getInstantAppIntentFilterList(hashPrefix, token, hostName, remoteCallback);
        } catch (RemoteException re) {
        } catch (TimeoutException te) {
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String prefix) {
        synchronized (mLock) {
            pw.append(prefix).append("bound=")
                    .append((mRemoteInstance != null) ? "true" : "false").println();

            pw.flush();
            try {
                TransferPipe.dumpAsync(getRemoteInstanceLazy().asBinder(), fd,
                        new String[] { prefix });
            } catch (IOException | TimeoutException | RemoteException e) {
                pw.println("Failed to dump remote instance: " + e);
            }
        }
    }

    private IInstantAppResolver getRemoteInstanceLazy() throws TimeoutException {
        synchronized (mLock) {
            if (mRemoteInstance != null) {
                return mRemoteInstance;
            }
            bindLocked();
            return mRemoteInstance;
        }
    }

    private void bindLocked() throws TimeoutException {
        if (mRemoteInstance != null) {
            return;
        }

        if (!mBindRequested) {
            mBindRequested = true;
            mContext.bindServiceAsUser(mIntent, mServiceConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE, UserHandle.SYSTEM);
        }

        final long startMillis = SystemClock.uptimeMillis();
        while (true) {
            if (mRemoteInstance != null) {
                break;
            }
            final long elapsedMillis = SystemClock.uptimeMillis() - startMillis;
            final long remainingMillis = BIND_SERVICE_TIMEOUT_MS - elapsedMillis;
            if (remainingMillis <= 0) {
                throw new TimeoutException("Didn't bind to resolver in time.");
            }
            try {
                mLock.wait(remainingMillis);
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }

        mLock.notifyAll();
    }

    private void throwIfCalledOnMainThread() {
        if (Thread.currentThread() == mContext.getMainLooper().getThread()) {
            throw new RuntimeException("Cannot invoke on the main thread");
        }
    }

    /**
     * Asynchronous callback when results come back from ephemeral resolution phase two.
     */
    public abstract static class PhaseTwoCallback {
        abstract void onPhaseTwoResolved(
                List<InstantAppResolveInfo> instantAppResolveInfoList, long startTime);
    }

    private final class MyServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mRemoteInstance = IInstantAppResolver.Stub.asInterface(service);
                mLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mRemoteInstance = null;
            }
        }
    }

    private static final class GetEphemeralResolveInfoCaller
            extends TimedRemoteCaller<List<InstantAppResolveInfo>> {
        private final IRemoteCallback mCallback;

        public GetEphemeralResolveInfoCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new IRemoteCallback.Stub() {
                    @Override
                    public void sendResult(Bundle data) throws RemoteException {
                        final ArrayList<InstantAppResolveInfo> resolveList =
                                data.getParcelableArrayList(
                                        InstantAppResolverService.EXTRA_RESOLVE_INFO);
                        int sequence =
                                data.getInt(InstantAppResolverService.EXTRA_SEQUENCE, -1);
                        onRemoteMethodResult(resolveList, sequence);
                    }
            };
        }

        public List<InstantAppResolveInfo> getEphemeralResolveInfoList(
                IInstantAppResolver target, int hashPrefix[], String token)
                        throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.getInstantAppResolveInfoList(hashPrefix, token, sequence, mCallback);
            return getResultTimed(sequence);
        }
    }
}
