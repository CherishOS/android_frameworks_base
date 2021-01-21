/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.Display.INVALID_DISPLAY;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ADD_REMOVE;
import static com.android.internal.protolog.ProtoLogGroup.WM_ERROR;

import android.annotation.NonNull;
import android.app.IWindowToken;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;

import java.util.Map;
import java.util.Objects;

/**
 * A controller to register/unregister {@link WindowContainerListener} for
 * {@link android.app.WindowContext}.
 *
 * <ul>
 *   <li>When a {@link android.app.WindowContext} is created, it registers the listener via
 *     {@link WindowManagerService#registerWindowContextListener(IBinder, int, int, Bundle)}
 *     automatically.</li>
 *   <li>When the {@link android.app.WindowContext} adds the first window to the screen via
 *     {@link android.view.WindowManager#addView(View, android.view.ViewGroup.LayoutParams)},
 *     {@link WindowManagerService} then updates the {@link WindowContextListenerImpl} to listen
 *     to corresponding {@link WindowToken} via this controller.</li>
 *   <li>When the {@link android.app.WindowContext} is GCed, it unregisters the previously
 *     registered listener via
 *     {@link WindowManagerService#unregisterWindowContextListener(IBinder)}.
 *     {@link WindowManagerService} is also responsible for removing the
 *     {@link android.app.WindowContext} created {@link WindowToken}.</li>
 * </ul>
 * <p>Note that the listener may be removed earlier than the
 * {@link #unregisterWindowContainerListener(IBinder)} if the listened {@link WindowContainer} was
 * removed. An example is that the {@link DisplayArea} is removed when users unfold the
 * foldable devices. Another example is that the associated external display is detached.</p>
 */
class WindowContextListenerController {
    @VisibleForTesting
    final Map<IBinder, WindowContextListenerImpl> mListeners = new ArrayMap<>();

    /**
     * Registers the listener to a {@code container} which is associated with
     * a {@code clientToken}, which is a {@link android.app.WindowContext} representation. If the
     * listener associated with {@code clientToken} hasn't been initialized yet, create one
     * {@link WindowContextListenerImpl}. Otherwise, the listener associated with
     * {@code clientToken} switches to listen to the {@code container}.
     *
     * @param clientToken the token to associate with the listener
     * @param container the {@link WindowContainer} which the listener is going to listen to.
     * @param ownerUid the caller UID
     */
    void registerWindowContainerListener(@NonNull IBinder clientToken,
            @NonNull WindowContainer container, int ownerUid) {
        WindowContextListenerImpl listener = mListeners.get(clientToken);
        if (listener == null) {
            listener = new WindowContextListenerImpl(clientToken, container, ownerUid);
            listener.register();
        } else {
            listener.updateContainer(container);
        }
    }

    void unregisterWindowContainerListener(IBinder clientToken) {
        final WindowContextListenerImpl listener = mListeners.get(clientToken);
        // Listeners may be removed earlier. An example is the display where the listener is
        // located is detached. In this case, all window containers on the display, as well as
        // their listeners will be removed before their listeners are unregistered.
        if (listener == null) {
            return;
        }
        listener.unregister();
    }

    boolean assertCallerCanRemoveListener(IBinder clientToken, boolean callerCanManageAppTokens,
            int callingUid) {
        final WindowContextListenerImpl listener = mListeners.get(clientToken);
        if (listener == null) {
            ProtoLog.i(WM_DEBUG_ADD_REMOVE, "The listener does not exist.");
            return false;
        }
        if (callerCanManageAppTokens) {
            return true;
        }
        if (callingUid != listener.mOwnerUid) {
            throw new UnsupportedOperationException("Uid mismatch. Caller uid is " + callingUid
                    + ", while the listener's owner is from " + listener.mOwnerUid);
        }
        return true;
    }

    @VisibleForTesting
    class WindowContextListenerImpl implements WindowContainerListener {
        @NonNull private final IBinder mClientToken;
        private final int mOwnerUid;
        @NonNull private WindowContainer mContainer;

        private DeathRecipient mDeathRecipient;

        private int mLastReportedDisplay = INVALID_DISPLAY;
        private Configuration mLastReportedConfig;

        private WindowContextListenerImpl(IBinder clientToken, WindowContainer container,
                int ownerUid) {
            mClientToken = clientToken;
            mContainer = Objects.requireNonNull(container);
            mOwnerUid = ownerUid;

            final DeathRecipient deathRecipient = new DeathRecipient();
            try {
                deathRecipient.linkToDeath();
                mDeathRecipient = deathRecipient;
            } catch (RemoteException e) {
                ProtoLog.e(WM_ERROR, "Could not register window container listener token=%s, "
                        + "container=%s", mClientToken, mContainer);
            }
        }

        /** TEST ONLY: returns the {@link WindowContainer} of the listener */
        @VisibleForTesting
        WindowContainer getWindowContainer() {
            return mContainer;
        }

        private void updateContainer(WindowContainer newContainer) {
            if (mContainer.equals(newContainer)) {
                return;
            }
            mContainer.unregisterWindowContainerListener(this);
            mContainer = newContainer;
            clear();
            register();
        }

        private void register() {
            if (mDeathRecipient == null) {
                throw new IllegalStateException("Invalid client token: " + mClientToken);
            }
            mListeners.putIfAbsent(mClientToken, this);
            mContainer.registerWindowContainerListener(this);
            reportConfigToWindowTokenClient();
        }

        private void unregister() {
            mContainer.unregisterWindowContainerListener(this);
            mListeners.remove(mClientToken);
        }

        private void clear() {
            mLastReportedConfig = null;
            mLastReportedDisplay = INVALID_DISPLAY;
        }

        @Override
        public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
            reportConfigToWindowTokenClient();
        }

        @Override
        public void onDisplayChanged(DisplayContent dc) {
            reportConfigToWindowTokenClient();
        }

        private void reportConfigToWindowTokenClient() {
            if (mDeathRecipient == null) {
                throw new IllegalStateException("Invalid client token: " + mClientToken);
            }

            if (mLastReportedConfig == null) {
                mLastReportedConfig = new Configuration();
            }
            final Configuration config = mContainer.getConfiguration();
            final int displayId = mContainer.getDisplayContent().getDisplayId();
            if (config.equals(mLastReportedConfig) && displayId == mLastReportedDisplay) {
                // No changes since last reported time.
                return;
            }

            mLastReportedConfig.setTo(config);
            mLastReportedDisplay = displayId;

            IWindowToken windowTokenClient = IWindowToken.Stub.asInterface(mClientToken);
            try {
                windowTokenClient.onConfigurationChanged(config, displayId);
            } catch (RemoteException e) {
                ProtoLog.w(WM_ERROR, "Could not report config changes to the window token client.");
            }
        }

        @Override
        public void onRemoved() {
            if (mDeathRecipient == null) {
                throw new IllegalStateException("Invalid client token: " + mClientToken);
            }
            mDeathRecipient.unlinkToDeath();
            IWindowToken windowTokenClient = IWindowToken.Stub.asInterface(mClientToken);
            try {
                windowTokenClient.onWindowTokenRemoved();
            } catch (RemoteException e) {
                ProtoLog.w(WM_ERROR, "Could not report token removal to the window token client.");
            }
            unregister();
        }

        private class DeathRecipient implements IBinder.DeathRecipient {
            @Override
            public void binderDied() {
                synchronized (mContainer.mWmService.mGlobalLock) {
                    mDeathRecipient = null;
                    unregister();
                }
            }

            void linkToDeath() throws RemoteException {
                mClientToken.linkToDeath(this, 0);
            }

            void unlinkToDeath() {
                mClientToken.unlinkToDeath(this, 0);
            }
        }
    }
}
