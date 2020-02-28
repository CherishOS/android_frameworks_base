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

package com.android.server.wm;

import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.view.InsetsController.ANIMATION_TYPE_HIDE;
import static android.view.InsetsController.ANIMATION_TYPE_SHOW;
import static android.view.InsetsController.LAYOUT_INSETS_DURING_ANIMATION_HIDDEN;
import static android.view.InsetsController.LAYOUT_INSETS_DURING_ANIMATION_SHOWN;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.SyncRtSurfaceTransactionApplier.applyParams;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;

import android.annotation.Nullable;
import android.app.StatusBarManager;
import android.util.IntArray;
import android.util.SparseArray;
import android.view.InsetsAnimationControlCallbacks;
import android.view.InsetsAnimationControlImpl;
import android.view.InsetsController;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.InsetsState.InternalInsetsType;
import android.view.SurfaceControl;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.ViewRootImpl;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsAnimationControlListener;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.DisplayThread;

/**
 * Policy that implements who gets control over the windows generating insets.
 */
class InsetsPolicy {

    private final InsetsStateController mStateController;
    private final DisplayContent mDisplayContent;
    private final DisplayPolicy mPolicy;
    private final TransientControlTarget mTransientControlTarget = new TransientControlTarget();
    private final IntArray mShowingTransientTypes = new IntArray();

    private WindowState mFocusedWin;
    private BarWindow mStatusBar = new BarWindow(StatusBarManager.WINDOW_STATUS_BAR);
    private BarWindow mNavBar = new BarWindow(StatusBarManager.WINDOW_NAVIGATION_BAR);
    private boolean mAnimatingShown;
    private final float[] mTmpFloat9 = new float[9];

    InsetsPolicy(InsetsStateController stateController, DisplayContent displayContent) {
        mStateController = stateController;
        mDisplayContent = displayContent;
        mPolicy = displayContent.getDisplayPolicy();
    }

    /** Updates the target which can control system bars. */
    void updateBarControlTarget(@Nullable WindowState focusedWin) {
        mFocusedWin = focusedWin;
        mStateController.onBarControlTargetChanged(getStatusControlTarget(focusedWin),
                getFakeStatusControlTarget(focusedWin),
                getNavControlTarget(focusedWin),
                getFakeNavControlTarget(focusedWin));
        if (ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL) {
            return;
        }
        mStatusBar.setVisible(focusedWin == null
                || focusedWin != getStatusControlTarget(focusedWin)
                || focusedWin.getRequestedInsetsState().getSource(ITYPE_STATUS_BAR).isVisible());
        mNavBar.setVisible(focusedWin == null
                || focusedWin != getNavControlTarget(focusedWin)
                || focusedWin.getRequestedInsetsState().getSource(ITYPE_NAVIGATION_BAR)
                        .isVisible());
    }

    boolean isHidden(@InternalInsetsType int type) {
        final InsetsSourceProvider provider =  mStateController.peekSourceProvider(type);
        return provider != null && provider.hasWindow() && !provider.getSource().isVisible();
    }

    void showTransient(IntArray types) {
        boolean changed = false;
        for (int i = types.size() - 1; i >= 0; i--) {
            final int type = types.get(i);
            if (mShowingTransientTypes.indexOf(type) != -1) {
                continue;
            }
            if (!isHidden(type)) {
                continue;
            }
            mShowingTransientTypes.add(type);
            changed = true;
        }
        if (changed) {
            mPolicy.getStatusBarManagerInternal().showTransient(mDisplayContent.getDisplayId(),
                    mShowingTransientTypes.toArray());
            updateBarControlTarget(mFocusedWin);
            startAnimation(true /* show */, () -> {
                synchronized (mDisplayContent.mWmService.mGlobalLock) {
                    mStateController.notifyInsetsChanged();
                }
            });
        }
    }

    void hideTransient() {
        if (mShowingTransientTypes.size() == 0) {
            return;
        }
        startAnimation(false /* show */, () -> {
            synchronized (mDisplayContent.mWmService.mGlobalLock) {
                mShowingTransientTypes.clear();
                mStateController.notifyInsetsChanged();
                updateBarControlTarget(mFocusedWin);
            }
        });
    }

    boolean isTransient(@InternalInsetsType int type) {
        return mShowingTransientTypes.indexOf(type) != -1;
    }

    /**
     * @see InsetsStateController#getInsetsForDispatch
     */
    InsetsState getInsetsForDispatch(WindowState target) {
        InsetsState state = mStateController.getInsetsForDispatch(target);
        if (mShowingTransientTypes.size() == 0) {
            return state;
        }
        for (int i = mShowingTransientTypes.size() - 1; i >= 0; i--) {
            state.setSourceVisible(mShowingTransientTypes.get(i), false);
        }
        return state;
    }

    void onInsetsModified(WindowState windowState, InsetsState state) {
        mStateController.onInsetsModified(windowState, state);
        checkAbortTransient(windowState, state);
        if (ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL) {
            return;
        }
        if (windowState == getStatusControlTarget(mFocusedWin)) {
            mStatusBar.setVisible(state.getSource(ITYPE_STATUS_BAR).isVisible());
        }
        if (windowState == getNavControlTarget(mFocusedWin)) {
            mNavBar.setVisible(state.getSource(ITYPE_NAVIGATION_BAR).isVisible());
        }
    }

    /**
     * Called when a window modified the insets state. If the window set a insets source to visible
     * while it is shown transiently, we need to abort the transient state.
     *
     * @param windowState who changed the insets state.
     * @param state the modified insets state.
     */
    private void checkAbortTransient(WindowState windowState, InsetsState state) {
        if (mShowingTransientTypes.size() != 0) {
            IntArray abortTypes = new IntArray();
            for (int i = mShowingTransientTypes.size() - 1; i >= 0; i--) {
                final int type = mShowingTransientTypes.get(i);
                if (mStateController.isFakeTarget(type, windowState)
                        && state.getSource(type).isVisible()) {
                    mShowingTransientTypes.remove(i);
                    abortTypes.add(type);
                }
            }
            if (abortTypes.size() > 0) {
                mPolicy.getStatusBarManagerInternal().abortTransient(mDisplayContent.getDisplayId(),
                        abortTypes.toArray());
                updateBarControlTarget(mFocusedWin);
            }
        }
    }

    private @Nullable InsetsControlTarget getFakeStatusControlTarget(
            @Nullable WindowState focused) {
        if (mShowingTransientTypes.indexOf(ITYPE_STATUS_BAR) != -1) {
            return focused;
        }
        return null;
    }

    private @Nullable InsetsControlTarget getFakeNavControlTarget(@Nullable WindowState focused) {
        if (mShowingTransientTypes.indexOf(ITYPE_NAVIGATION_BAR) != -1) {
            return focused;
        }
        return null;
    }

    private @Nullable InsetsControlTarget getStatusControlTarget(@Nullable WindowState focusedWin) {
        if (mShowingTransientTypes.indexOf(ITYPE_STATUS_BAR) != -1) {
            return mTransientControlTarget;
        }
        if (focusedWin == mPolicy.getNotificationShade()) {
            // Notification shade has control anyways, no reason to force anything.
            return focusedWin;
        }
        if (areSystemBarsForciblyVisible() || isKeyguardOrStatusBarForciblyVisible()) {
            return null;
        }
        return focusedWin;
    }

    private @Nullable InsetsControlTarget getNavControlTarget(@Nullable WindowState focusedWin) {
        if (mShowingTransientTypes.indexOf(ITYPE_NAVIGATION_BAR) != -1) {
            return mTransientControlTarget;
        }
        if (focusedWin == mPolicy.getNotificationShade()) {
            // Notification shade has control anyways, no reason to force anything.
            return focusedWin;
        }
        if (areSystemBarsForciblyVisible() || isNavBarForciblyVisible()) {
            return null;
        }
        return focusedWin;
    }

    private boolean isKeyguardOrStatusBarForciblyVisible() {
        final WindowState statusBar = mPolicy.getStatusBar();
        if (statusBar != null) {
            // TODO(b/118118435): Pretend to the app that it's still able to control it?
            if ((statusBar.mAttrs.privateFlags & PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR) != 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isNavBarForciblyVisible() {
        final WindowState notificationShade = mPolicy.getNotificationShade();
        if (notificationShade == null) {
            return false;
        }
        if ((notificationShade.mAttrs.privateFlags
                & PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION) != 0) {
            return true;
        }
        return false;
    }

    private boolean areSystemBarsForciblyVisible() {
        final boolean isDockedStackVisible =
                mDisplayContent.isStackVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final boolean isFreeformStackVisible =
                mDisplayContent.isStackVisible(WINDOWING_MODE_FREEFORM);
        final boolean isResizing = mDisplayContent.getDockedDividerController().isResizing();

        // We need to force system bars when the docked stack is visible, when the freeform stack
        // is visible but also when we are resizing for the transitions when docked stack
        // visibility changes.
        return isDockedStackVisible || isFreeformStackVisible || isResizing;
    }

    @VisibleForTesting
    void startAnimation(boolean show, Runnable callback) {
        int typesReady = 0;
        final SparseArray<InsetsSourceControl> controls = new SparseArray<>();
        final IntArray showingTransientTypes = mShowingTransientTypes;
        for (int i = showingTransientTypes.size() - 1; i >= 0; i--) {
            InsetsSourceProvider provider =
                    mStateController.getSourceProvider(showingTransientTypes.get(i));
            InsetsSourceControl control = provider.getControl(mTransientControlTarget);
            if (control == null || control.getLeash() == null) {
                continue;
            }
            typesReady |= InsetsState.toPublicType(showingTransientTypes.get(i));
            controls.put(control.getType(), new InsetsSourceControl(control));
        }
        controlAnimationUnchecked(typesReady, controls, show, callback);
    }

    private void controlAnimationUnchecked(int typesReady,
            SparseArray<InsetsSourceControl> controls, boolean show, Runnable callback) {
        InsetsPolicyAnimationControlListener listener =
                new InsetsPolicyAnimationControlListener(show, callback);
        listener.mControlCallbacks.controlAnimationUnchecked(typesReady, controls, show);
    }

    private class BarWindow {

        private final int mId;
        private  @StatusBarManager.WindowVisibleState int mState =
                StatusBarManager.WINDOW_STATE_SHOWING;

        BarWindow(int id) {
            mId = id;
        }

        private void setVisible(boolean visible) {
            final int state = visible ? WINDOW_STATE_SHOWING : WINDOW_STATE_HIDDEN;
            if (mState != state) {
                mState = state;
                mPolicy.getStatusBarManagerInternal().setWindowState(
                        mDisplayContent.getDisplayId(), mId, state);
            }
        }
    }

    private class InsetsPolicyAnimationControlListener extends
            InsetsController.InternalAnimationControlListener {
        Runnable mFinishCallback;
        InsetsPolicyAnimationControlCallbacks mControlCallbacks;

        InsetsPolicyAnimationControlListener(boolean show, Runnable finishCallback) {
            super(show);
            mFinishCallback = finishCallback;
            mControlCallbacks = new InsetsPolicyAnimationControlCallbacks(this);
        }

        @Override
        protected void onAnimationFinish() {
            super.onAnimationFinish();
            mControlCallbacks.mAnimationControl.finish(mAnimatingShown);
            DisplayThread.getHandler().post(mFinishCallback);
        }

        private class InsetsPolicyAnimationControlCallbacks implements
                InsetsAnimationControlCallbacks {
            private InsetsAnimationControlImpl mAnimationControl = null;
            private InsetsPolicyAnimationControlListener mListener;

            InsetsPolicyAnimationControlCallbacks(InsetsPolicyAnimationControlListener listener) {
                mListener = listener;
            }

            private void controlAnimationUnchecked(int typesReady,
                    SparseArray<InsetsSourceControl> controls, boolean show) {
                if (typesReady == 0) {
                    // nothing to animate.
                    return;
                }
                mAnimatingShown = show;

                mAnimationControl = new InsetsAnimationControlImpl(controls,
                        mFocusedWin.getDisplayContent().getBounds(), getState(),
                        mListener, typesReady, this, mListener.getDurationMs(),
                        InsetsController.INTERPOLATOR, true,
                        show ? LAYOUT_INSETS_DURING_ANIMATION_SHOWN
                                : LAYOUT_INSETS_DURING_ANIMATION_HIDDEN,
                        show ? ANIMATION_TYPE_SHOW : ANIMATION_TYPE_HIDE);
                SurfaceAnimationThread.getHandler().post(
                        () -> mListener.onReady(mAnimationControl, typesReady));
            }

            /** Called on SurfaceAnimationThread without global WM lock held. */
            @Override
            public void scheduleApplyChangeInsets() {
                InsetsState state = getState();
                if (mAnimationControl.applyChangeInsets(state)) {
                    mAnimationControl.finish(mAnimatingShown);
                }
            }

            @Override
            public void notifyFinished(InsetsAnimationControlImpl controller, boolean shown) {
                // Nothing's needed here. Finish steps is handled in the listener
                // onAnimationFinished callback.
            }

            /**
             * This method will return a state with fullscreen frame override. No need to make copy
             * after getting state from this method.
             * @return The client insets state with full display frame override.
             */
            private InsetsState getState() {
                // To animate the transient animation correctly, we need to let the state hold
                // the full display frame.
                InsetsState overrideState = new InsetsState(mFocusedWin.getRequestedInsetsState(),
                        true);
                overrideState.setDisplayFrame(mFocusedWin.getDisplayContent().getBounds());
                return overrideState;
            }

            /** Called on SurfaceAnimationThread without global WM lock held. */
            @Override
            public void applySurfaceParams(
                    final SyncRtSurfaceTransactionApplier.SurfaceParams... params) {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                for (int i = params.length - 1; i >= 0; i--) {
                    SyncRtSurfaceTransactionApplier.SurfaceParams surfaceParams = params[i];
                    applyParams(t, surfaceParams, mTmpFloat9);
                }
                t.apply();
            }

            @Override
            public void startAnimation(InsetsAnimationControlImpl controller,
                    WindowInsetsAnimationControlListener listener, int types,
                    WindowInsetsAnimation animation,
                    WindowInsetsAnimation.Bounds bounds,
                    int layoutDuringAnimation) {
            }
        }
    }

    private class TransientControlTarget implements InsetsControlTarget {

        @Override
        public void notifyInsetsControlChanged() {
        }
    }
}
