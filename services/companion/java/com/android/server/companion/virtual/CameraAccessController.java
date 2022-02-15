/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.virtual;

import static android.hardware.camera2.CameraInjectionSession.InjectionStatusCallback.ERROR_INJECTION_UNSUPPORTED;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraInjectionSession;
import android.hardware.camera2.CameraManager;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

/**
 * Handles blocking access to the camera for apps running on virtual devices.
 */
class CameraAccessController extends CameraManager.AvailabilityCallback {
    private static final String TAG = "CameraAccessController";

    private final Object mLock = new Object();

    private final Context mContext;
    private VirtualDeviceManagerInternal mVirtualDeviceManagerInternal;
    CameraAccessBlockedCallback mBlockedCallback;
    private CameraManager mCameraManager;
    private boolean mListeningForCameraEvents;
    private PackageManager mPackageManager;

    @GuardedBy("mLock")
    private ArrayMap<String, InjectionSessionData> mPackageToSessionData = new ArrayMap<>();

    static class InjectionSessionData {
        public int appUid;
        public ArrayMap<String, CameraInjectionSession> cameraIdToSession = new ArrayMap<>();
    }

    interface CameraAccessBlockedCallback {
        /**
         * Called whenever an app was blocked from accessing a camera.
         * @param appUid uid for the app which was blocked
         */
        void onCameraAccessBlocked(int appUid);
    }

    CameraAccessController(Context context,
            VirtualDeviceManagerInternal virtualDeviceManagerInternal,
            CameraAccessBlockedCallback blockedCallback) {
        mContext = context;
        mVirtualDeviceManagerInternal = virtualDeviceManagerInternal;
        mBlockedCallback = blockedCallback;
        mCameraManager = mContext.getSystemService(CameraManager.class);
        mPackageManager = mContext.getPackageManager();
    }

    /**
     * Starts watching for camera access by uids running on a virtual device, if we were not
     * already doing so.
     */
    public void startObservingIfNeeded() {
        synchronized (mLock) {
            if (!mListeningForCameraEvents) {
                mCameraManager.registerAvailabilityCallback(mContext.getMainExecutor(), this);
                mListeningForCameraEvents = true;
            }
        }
    }

    /**
     * Stop watching for camera access.
     */
    public void stopObserving() {
        synchronized (mLock) {
            mCameraManager.unregisterAvailabilityCallback(this);
            mListeningForCameraEvents = false;
        }
    }

    @Override
    public void onCameraOpened(@NonNull String cameraId, @NonNull String packageName) {
        synchronized (mLock) {
            try {
                final ApplicationInfo ainfo =
                        mPackageManager.getApplicationInfo(packageName, 0);
                InjectionSessionData data = mPackageToSessionData.get(packageName);
                if (!mVirtualDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(ainfo.uid)) {
                    CameraInjectionSession existingSession =
                            (data != null) ? data.cameraIdToSession.get(cameraId) : null;
                    if (existingSession != null) {
                        existingSession.close();
                        data.cameraIdToSession.remove(cameraId);
                        if (data.cameraIdToSession.isEmpty()) {
                            mPackageToSessionData.remove(packageName);
                        }
                    }
                    return;
                }
                if (data == null) {
                    data = new InjectionSessionData();
                    data.appUid = ainfo.uid;
                    mPackageToSessionData.put(packageName, data);
                }
                if (data.cameraIdToSession.containsKey(cameraId)) {
                    return;
                }
                startBlocking(packageName, cameraId);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG, "onCameraOpened - unknown package " + packageName, e);
                return;
            }
        }
    }

    @Override
    public void onCameraClosed(@NonNull String cameraId) {
        synchronized (mLock) {
            for (int i = mPackageToSessionData.size() - 1; i >= 0; i--) {
                InjectionSessionData data = mPackageToSessionData.valueAt(i);
                CameraInjectionSession session = data.cameraIdToSession.get(cameraId);
                if (session != null) {
                    session.close();
                    data.cameraIdToSession.remove(cameraId);
                    if (data.cameraIdToSession.isEmpty()) {
                        mPackageToSessionData.removeAt(i);
                    }
                }
            }
        }
    }

    /**
     * Turns on blocking for a particular camera and package.
     */
    private void startBlocking(String packageName, String cameraId) {
        try {
            mCameraManager.injectCamera(packageName, cameraId, /* externalCamId */ "",
                    mContext.getMainExecutor(),
                    new CameraInjectionSession.InjectionStatusCallback() {
                        @Override
                        public void onInjectionSucceeded(
                                @NonNull CameraInjectionSession session) {
                            CameraAccessController.this.onInjectionSucceeded(cameraId, packageName,
                                    session);
                        }

                        @Override
                        public void onInjectionError(@NonNull int errorCode) {
                            CameraAccessController.this.onInjectionError(cameraId, packageName,
                                    errorCode);
                        }
                    });
        } catch (CameraAccessException e) {
            Slog.e(TAG,
                    "Failed to injectCamera for cameraId:" + cameraId + " package:" + packageName,
                    e);
        }
    }

    private void onInjectionSucceeded(String cameraId, String packageName,
            @NonNull CameraInjectionSession session) {
        synchronized (mLock) {
            InjectionSessionData data = mPackageToSessionData.get(packageName);
            if (data == null) {
                Slog.e(TAG, "onInjectionSucceeded didn't find expected entry for package "
                        + packageName);
                session.close();
                return;
            }
            CameraInjectionSession existingSession = data.cameraIdToSession.put(cameraId, session);
            if (existingSession != null) {
                Slog.e(TAG, "onInjectionSucceeded found unexpected existing session for camera "
                        + cameraId);
                existingSession.close();
            }
        }
    }

    private void onInjectionError(String cameraId, String packageName, @NonNull int errorCode) {
        if (errorCode != ERROR_INJECTION_UNSUPPORTED) {
            // ERROR_INJECTION_UNSUPPORTED means that there wasn't an external camera to map to the
            // internal camera, which is expected when using the injection interface as we are in
            // this class to simply block camera access. Any other error is unexpected.
            Slog.e(TAG, "Unexpected injection error code:" + errorCode + " for camera:" + cameraId
                    + " and package:" + packageName);
            return;
        }
        synchronized (mLock) {
            InjectionSessionData data = mPackageToSessionData.get(packageName);
            if (data != null) {
                mBlockedCallback.onCameraAccessBlocked(data.appUid);
            }
        }
    }
}
