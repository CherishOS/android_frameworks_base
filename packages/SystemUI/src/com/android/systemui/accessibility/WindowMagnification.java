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

package com.android.systemui.accessibility;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.statusbar.CommandQueue;

import javax.inject.Inject;

/**
 * Class to handle the interaction with
 * {@link com.android.server.accessibility.AccessibilityManagerService}. It invokes
 * {@link AccessibilityManager#setWindowMagnificationConnection(IWindowMagnificationConnection)}
 * when {@code IStatusBar#requestWindowMagnificationConnection(boolean)} is called.
 */
@SysUISingleton
public class WindowMagnification extends SystemUI implements WindowMagnifierCallback,
        CommandQueue.Callbacks {
    private static final String TAG = "WindowMagnification";
    private static final int CONFIG_MASK =
            ActivityInfo.CONFIG_DENSITY | ActivityInfo.CONFIG_ORIENTATION;

    @VisibleForTesting
    protected WindowMagnificationAnimationController mWindowMagnificationAnimationController;
    private final ModeSwitchesController mModeSwitchesController;
    private final Handler mHandler;
    private final AccessibilityManager mAccessibilityManager;
    private final CommandQueue mCommandQueue;

    private WindowMagnificationConnectionImpl mWindowMagnificationConnectionImpl;
    private Configuration mLastConfiguration;

    @Inject
    public WindowMagnification(Context context, @Main Handler mainHandler,
            CommandQueue commandQueue, ModeSwitchesController modeSwitchesController,
            NavigationModeController navigationModeController) {
        super(context);
        mHandler = mainHandler;
        mLastConfiguration = new Configuration(context.getResources().getConfiguration());
        mAccessibilityManager = (AccessibilityManager) mContext.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        mCommandQueue = commandQueue;
        mModeSwitchesController = modeSwitchesController;
        final WindowMagnificationController controller = new WindowMagnificationController(mContext,
                mHandler, new SfVsyncFrameCallbackProvider(), null,
                new SurfaceControl.Transaction(), this);
        final int navBarMode = navigationModeController.addListener(
                controller::onNavigationModeChanged);
        controller.onNavigationModeChanged(navBarMode);
        mWindowMagnificationAnimationController = new WindowMagnificationAnimationController(
                mContext, controller);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        final int configDiff = newConfig.diff(mLastConfiguration);
        if ((configDiff & CONFIG_MASK) == 0) {
            return;
        }
        mLastConfiguration.setTo(newConfig);
        mWindowMagnificationAnimationController.onConfigurationChanged(configDiff);
        if (mModeSwitchesController != null) {
            mModeSwitchesController.onConfigurationChanged(configDiff);
        }
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
    }

    @MainThread
    void enableWindowMagnification(int displayId, float scale, float centerX, float centerY,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        //TODO: b/144080869 support multi-display.
        mWindowMagnificationAnimationController.enableWindowMagnification(scale, centerX, centerY,
                callback);
    }

    @MainThread
    void setScale(int displayId, float scale) {
        //TODO: b/144080869 support multi-display.
        mWindowMagnificationAnimationController.setScale(scale);
    }

    @MainThread
    void moveWindowMagnifier(int displayId, float offsetX, float offsetY) {
        //TODO: b/144080869 support multi-display.
        mWindowMagnificationAnimationController.moveWindowMagnifier(offsetX, offsetY);
    }

    @MainThread
    void disableWindowMagnification(int displayId,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        //TODO: b/144080869 support multi-display.
        mWindowMagnificationAnimationController.deleteWindowMagnification(callback);
    }

    @Override
    public void onWindowMagnifierBoundsChanged(int displayId, Rect frame) {
        if (mWindowMagnificationConnectionImpl != null) {
            mWindowMagnificationConnectionImpl.onWindowMagnifierBoundsChanged(displayId, frame);
        }
    }

    @Override
    public void onSourceBoundsChanged(int displayId, Rect sourceBounds) {
        if (mWindowMagnificationConnectionImpl != null) {
            mWindowMagnificationConnectionImpl.onSourceBoundsChanged(displayId, sourceBounds);
        }
    }

    @Override
    public void requestWindowMagnificationConnection(boolean connect) {
        if (connect) {
            setWindowMagnificationConnection();
        } else {
            clearWindowMagnificationConnection();
        }
    }

    private void setWindowMagnificationConnection() {
        if (mWindowMagnificationConnectionImpl == null) {
            mWindowMagnificationConnectionImpl = new WindowMagnificationConnectionImpl(this,
                    mHandler, mModeSwitchesController);
        }
        mAccessibilityManager.setWindowMagnificationConnection(
                mWindowMagnificationConnectionImpl);
    }

    private void clearWindowMagnificationConnection() {
        mAccessibilityManager.setWindowMagnificationConnection(null);
        //TODO: destroy controllers.
    }

    private static class WindowMagnificationConnectionImpl extends
            IWindowMagnificationConnection.Stub {

        private static final String TAG = "WindowMagnificationConnectionImpl";

        private IWindowMagnificationConnectionCallback mConnectionCallback;
        private final WindowMagnification mWindowMagnification;
        private final Handler mHandler;
        private final ModeSwitchesController mModeSwitchesController;

        WindowMagnificationConnectionImpl(@NonNull WindowMagnification windowMagnification,
                @Main Handler mainHandler, ModeSwitchesController modeSwitchesController) {
            mWindowMagnification = windowMagnification;
            mHandler = mainHandler;
            mModeSwitchesController = modeSwitchesController;
        }

        @Override
        public void enableWindowMagnification(int displayId, float scale, float centerX,
                float centerY, IRemoteMagnificationAnimationCallback callback) {
            mHandler.post(
                    () -> mWindowMagnification.enableWindowMagnification(displayId, scale, centerX,
                            centerY, callback));
        }

        @Override
        public void setScale(int displayId, float scale) {
            mHandler.post(() -> mWindowMagnification.setScale(displayId, scale));
        }

        @Override
        public void disableWindowMagnification(int displayId,
                IRemoteMagnificationAnimationCallback callback) {
            mHandler.post(() -> mWindowMagnification.disableWindowMagnification(displayId,
                    callback));
        }

        @Override
        public void moveWindowMagnifier(int displayId, float offsetX, float offsetY) {
            mHandler.post(
                    () -> mWindowMagnification.moveWindowMagnifier(displayId, offsetX, offsetY));
        }

        @Override
        public void showMagnificationButton(int displayId, int magnificationMode) {
            mHandler.post(
                    () -> mModeSwitchesController.showButton(displayId, magnificationMode));
        }

        @Override
        public void removeMagnificationButton(int displayId) {
            mHandler.post(
                    () -> mModeSwitchesController.removeButton(displayId));
        }

        @Override
        public void setConnectionCallback(IWindowMagnificationConnectionCallback callback) {
            mConnectionCallback = callback;
        }

        void onWindowMagnifierBoundsChanged(int displayId, Rect frame) {
            if (mConnectionCallback != null) {
                try {
                    mConnectionCallback.onWindowMagnifierBoundsChanged(displayId, frame);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to inform bounds changed", e);
                }
            }
        }

        void onSourceBoundsChanged(int displayId, Rect sourceBounds) {
            if (mConnectionCallback != null) {
                try {
                    mConnectionCallback.onSourceBoundsChanged(displayId, sourceBounds);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to inform source bounds changed", e);
                }
            }
        }
    }
}
