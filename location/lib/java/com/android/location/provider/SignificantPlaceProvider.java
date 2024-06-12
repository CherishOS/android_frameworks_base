/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.location.provider;

import android.annotation.Nullable;
import android.app.trust.TrustManager;
import android.hardware.location.ISignificantPlaceProvider;
import android.hardware.location.ISignificantPlaceProviderManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

/** @hide */
public abstract class SignificantPlaceProvider {

    public static final String ACTION = TrustManager.ACTION_BIND_SIGNIFICANT_PLACE_PROVIDER;

    private static final String TAG = "SignificantPlaceProvider";

    private final IBinder mBinder;

    // write locked on mBinder, read lock is optional depending on atomicity requirements
    @Nullable private volatile ISignificantPlaceProviderManager mManager;

    @GuardedBy("mBinder")
    private boolean mInSignificantPlace = false;

    public SignificantPlaceProvider() {
        mBinder = new Service();
        mManager = null;
    }

    public IBinder getBinder() {
        return mBinder;
    }

    /** Set whether the device is currently in a trusted location. */
    public void setInSignificantPlace(boolean inSignificantPlace) {
        synchronized (mBinder) {
            if (inSignificantPlace == mInSignificantPlace) {
                return;
            }

            mInSignificantPlace = inSignificantPlace;
        }

        ISignificantPlaceProviderManager manager = mManager;
        if (manager != null) {
            try {
                manager.setInSignificantPlace(inSignificantPlace);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /** Invoked when some client has checked whether the device is in a significant place. */
    public abstract void onSignificantPlaceCheck();

    private final class Service extends ISignificantPlaceProvider.Stub {

        Service() {}

        @Override
        public void setSignificantPlaceProviderManager(ISignificantPlaceProviderManager manager) {
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException();
            }

            synchronized (mBinder) {
                if (mInSignificantPlace) {
                    try {
                        manager.setInSignificantPlace(true);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }

                mManager = manager;
            }
        }

        @Override
        public void onSignificantPlaceCheck() {
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException();
            }

            try {
                SignificantPlaceProvider.this.onSignificantPlaceCheck();
            } catch (RuntimeException e) {
                // exceptions on one-way binder threads are dropped - move to a different thread
                Log.w(TAG, e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    throw new AssertionError(e);
                });
            }
        }
    }
}
