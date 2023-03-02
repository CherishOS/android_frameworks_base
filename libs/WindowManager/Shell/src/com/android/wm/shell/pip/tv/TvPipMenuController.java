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

package com.android.wm.shell.pip.tv;

import static android.view.WindowManager.SHELL_ROOT_LAYER_PIP;

import android.app.ActivityManager;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManagerGlobal;
import android.window.SurfaceSyncGroup;

import androidx.annotation.Nullable;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.pip.PipMenuController;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.List;

/**
 * Manages the visibility of the PiP Menu as user interacts with PiP.
 */
public class TvPipMenuController implements PipMenuController, TvPipMenuView.Listener {
    private static final String TAG = "TvPipMenuController";
    private static final String BACKGROUND_WINDOW_TITLE = "PipBackgroundView";

    private final Context mContext;
    private final SystemWindows mSystemWindows;
    private final TvPipBoundsState mTvPipBoundsState;
    private final Handler mMainHandler;
    private TvPipActionsProvider mTvPipActionsProvider;

    private Delegate mDelegate;
    private SurfaceControl mLeash;
    private TvPipMenuView mPipMenuView;
    private View mPipBackgroundView;

    private boolean mMenuIsOpen;
    // User can actively move the PiP via the DPAD.
    private boolean mInMoveMode;
    // Used when only showing the move menu since we want to close the menu completely when
    // exiting the move menu instead of showing the regular button menu.
    private boolean mCloseAfterExitMoveMenu;

    public TvPipMenuController(Context context, TvPipBoundsState tvPipBoundsState,
            SystemWindows systemWindows, Handler mainHandler) {
        mContext = context;
        mTvPipBoundsState = tvPipBoundsState;
        mSystemWindows = systemWindows;
        mMainHandler = mainHandler;

        // We need to "close" the menu the platform call for all the system dialogs to close (for
        // example, on the Home button press).
        final BroadcastReceiver closeSystemDialogsBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                closeMenu();
            }
        };
        context.registerReceiverForAllUsers(closeSystemDialogsBroadcastReceiver,
                new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), null /* permission */,
                mainHandler, Context.RECEIVER_EXPORTED);
    }

    void setDelegate(Delegate delegate) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: setDelegate(), delegate=%s", TAG, delegate);
        if (mDelegate != null) {
            throw new IllegalStateException(
                    "The delegate has already been set and should not change.");
        }
        if (delegate == null) {
            throw new IllegalArgumentException("The delegate must not be null.");
        }

        mDelegate = delegate;
    }

    void setTvPipActionsProvider(TvPipActionsProvider tvPipActionsProvider) {
        mTvPipActionsProvider = tvPipActionsProvider;
    }

    @Override
    public void attach(SurfaceControl leash) {
        if (mDelegate == null) {
            throw new IllegalStateException("Delegate is not set.");
        }

        mLeash = leash;
        attachPipMenu();
    }

    private void attachPipMenu() {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: attachPipMenu()", TAG);

        if (mPipMenuView != null) {
            detachPipMenu();
        }

        attachPipBackgroundView();
        attachPipMenuView();

        int pipEduTextHeight = mContext.getResources()
                .getDimensionPixelSize(R.dimen.pip_menu_edu_text_view_height);
        int pipMenuBorderWidth = mContext.getResources()
                .getDimensionPixelSize(R.dimen.pip_menu_border_width);
        mTvPipBoundsState.setPipMenuPermanentDecorInsets(Insets.of(-pipMenuBorderWidth,
                -pipMenuBorderWidth, -pipMenuBorderWidth, -pipMenuBorderWidth));
        mTvPipBoundsState.setPipMenuTemporaryDecorInsets(Insets.of(0, 0, 0, -pipEduTextHeight));
    }

    private void attachPipMenuView() {
        if (mTvPipActionsProvider == null) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Actions provider is not set", TAG);
            return;
        }
        mPipMenuView = new TvPipMenuView(mContext, mMainHandler, this, mTvPipActionsProvider);
        setUpViewSurfaceZOrder(mPipMenuView, 1);
        addPipMenuViewToSystemWindows(mPipMenuView, MENU_WINDOW_TITLE);
    }

    private void attachPipBackgroundView() {
        mPipBackgroundView = LayoutInflater.from(mContext)
                .inflate(R.layout.tv_pip_menu_background, null);
        setUpViewSurfaceZOrder(mPipBackgroundView, -1);
        addPipMenuViewToSystemWindows(mPipBackgroundView, BACKGROUND_WINDOW_TITLE);
    }

    private void setUpViewSurfaceZOrder(View v, int zOrderRelativeToPip) {
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.getViewRootImpl().addSurfaceChangedCallback(
                        new PipMenuSurfaceChangedCallback(v, zOrderRelativeToPip));
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
            }
        });
    }

    private void addPipMenuViewToSystemWindows(View v, String title) {
        mSystemWindows.addView(v, getPipMenuLayoutParams(mContext, title, 0 /* width */,
                0 /* height */), 0 /* displayId */, SHELL_ROOT_LAYER_PIP);
    }

    void onPipTransitionFinished(boolean enterTransition) {
        // There is a race between when this is called and when the last frame of the pip transition
        // is drawn. To ensure that view updates are applied only when the animation has fully drawn
        // and the menu view has been fully remeasured and relaid out, we add a small delay here by
        // posting on the handler.
        mMainHandler.post(() -> {
            if (mPipMenuView != null) {
                mPipMenuView.onPipTransitionFinished(enterTransition);
            }
        });
    }

    void showMovementMenu() {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: showMovementMenuOnly()", TAG);
        setInMoveMode(true);
        if (mMenuIsOpen) {
            mPipMenuView.showMoveMenu(mTvPipBoundsState.getTvPipGravity());
        } else {
            mCloseAfterExitMoveMenu = true;
            showMenuInternal();
        }
    }

    @Override
    public void showMenu() {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: showMenu()", TAG);
        setInMoveMode(false);
        mCloseAfterExitMoveMenu = false;
        showMenuInternal();
    }

    private void showMenuInternal() {
        if (mPipMenuView == null) {
            return;
        }

        mMenuIsOpen = true;
        grantPipMenuFocus(true);
        if (mInMoveMode) {
            mPipMenuView.showMoveMenu(mTvPipBoundsState.getTvPipGravity());
        } else {
            mPipMenuView.showButtonsMenu(/* exitingMoveMode= */ false);
        }
        mPipMenuView.updateBounds(mTvPipBoundsState.getBounds());
    }

    void onPipTransitionToTargetBoundsStarted(Rect targetBounds) {
        if (mPipMenuView != null) {
            mPipMenuView.onPipTransitionToTargetBoundsStarted(targetBounds);
        }
    }

    void updateGravity(int gravity) {
        if (mInMoveMode) {
            mPipMenuView.showMovementHints(gravity);
        }
    }

    private Rect calculateMenuSurfaceBounds(Rect pipBounds) {
        return mPipMenuView.getPipMenuContainerBounds(pipBounds);
    }

    void closeMenu() {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: closeMenu()", TAG);

        if (mPipMenuView == null) {
            return;
        }

        mMenuIsOpen = false;
        mPipMenuView.hideAllUserControls();
        grantPipMenuFocus(false);
        mDelegate.onMenuClosed();
    }

    boolean isInMoveMode() {
        return mInMoveMode;
    }

    private void setInMoveMode(boolean moveMode) {
        if (mInMoveMode == moveMode) {
            return;
        }
        mInMoveMode = moveMode;
        if (mDelegate != null) {
            mDelegate.onInMoveModeChanged();
        }
    }

    @Override
    public boolean onExitMoveMode() {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: onExitMoveMode - %b, close when exiting move menu: %b",
                TAG, mInMoveMode, mCloseAfterExitMoveMenu);

        if (mInMoveMode) {
            setInMoveMode(false);
            if (mCloseAfterExitMoveMenu) {
                mCloseAfterExitMoveMenu = false;
                closeMenu();
            } else {
                mPipMenuView.showButtonsMenu(/* exitingMoveMode= */ true);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onPipMovement(int keycode) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: onPipMovement - %b", TAG, mInMoveMode);
        if (mInMoveMode) {
            mDelegate.movePip(keycode);
        }
        return mInMoveMode;
    }

    @Override
    public void detach() {
        closeMenu();
        detachPipMenu();
        mLeash = null;
    }

    @Override
    public void setAppActions(List<RemoteAction> actions, RemoteAction closeAction) {
        // NOOP - handled via the TvPipActionsProvider
    }

    @Override
    public boolean isMenuVisible() {
        return true;
    }

    /**
     * Does an immediate window crop of the PiP menu.
     */
    @Override
    public void resizePipMenu(@Nullable SurfaceControl pipLeash,
            @Nullable SurfaceControl.Transaction pipTx,
            Rect pipBounds) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: resizePipMenu: %s", TAG, pipBounds.toShortString());
        if (pipBounds.isEmpty()) {
            return;
        }

        if (!isMenuReadyToMove()) {
            return;
        }


        final SurfaceControl frontSurface = getSurfaceControl(mPipMenuView);
        final SurfaceControl backSurface = getSurfaceControl(mPipBackgroundView);
        final Rect menuBounds = calculateMenuSurfaceBounds(pipBounds);
        if (pipTx == null) {
            pipTx = new SurfaceControl.Transaction();
        }
        pipTx.setWindowCrop(frontSurface, menuBounds.width(), menuBounds.height());
        pipTx.setWindowCrop(backSurface, menuBounds.width(), menuBounds.height());

        // Synchronize drawing the content in the front and back surfaces together with the pip
        // transaction and the window crop for the front and back surfaces
        final SurfaceSyncGroup syncGroup = new SurfaceSyncGroup("TvPip");
        syncGroup.add(mPipMenuView.getRootSurfaceControl(), null);
        syncGroup.add(mPipBackgroundView.getRootSurfaceControl(), null);
        updateMenuBounds(pipBounds);
        syncGroup.addTransaction(pipTx);
        syncGroup.markSyncReady();
    }

    private SurfaceControl getSurfaceControl(View v) {
        return mSystemWindows.getViewSurface(v);
    }

    @Override
    public void movePipMenu(SurfaceControl pipLeash, SurfaceControl.Transaction pipTx,
            Rect pipBounds) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: movePipMenu: %s", TAG, pipBounds.toShortString());

        if (pipBounds.isEmpty()) {
            if (pipTx == null) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: no transaction given", TAG);
            }
            return;
        }
        if (!isMenuReadyToMove()) {
            return;
        }

        final SurfaceControl frontSurface = getSurfaceControl(mPipMenuView);
        final SurfaceControl backSurface = getSurfaceControl(mPipBackgroundView);
        final Rect menuDestBounds = calculateMenuSurfaceBounds(pipBounds);
        if (pipTx == null) {
            pipTx = new SurfaceControl.Transaction();
        }
        pipTx.setPosition(frontSurface, menuDestBounds.left, menuDestBounds.top);
        pipTx.setPosition(backSurface, menuDestBounds.left, menuDestBounds.top);

        // Synchronize drawing the content in the front and back surfaces together with the pip
        // transaction and the position change for the front and back surfaces
        final SurfaceSyncGroup syncGroup = new SurfaceSyncGroup("TvPip");
        syncGroup.add(mPipMenuView.getRootSurfaceControl(), null);
        syncGroup.add(mPipBackgroundView.getRootSurfaceControl(), null);
        updateMenuBounds(pipBounds);
        syncGroup.addTransaction(pipTx);
        syncGroup.markSyncReady();
    }

    private boolean isMenuReadyToMove() {
        final boolean ready = mPipMenuView != null && mPipMenuView.getViewRootImpl() != null
                && mPipBackgroundView != null && mPipBackgroundView.getViewRootImpl() != null;
        if (!ready) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Not going to move PiP, either menu or its parent is not created.", TAG);
        }
        return ready;
    }

    private void detachPipMenu() {
        if (mPipMenuView != null) {
            mSystemWindows.removeView(mPipMenuView);
            mPipMenuView = null;
        }

        if (mPipBackgroundView != null) {
            mSystemWindows.removeView(mPipBackgroundView);
            mPipBackgroundView = null;
        }
    }

    @Override
    public void updateMenuBounds(Rect pipBounds) {
        final Rect menuBounds = calculateMenuSurfaceBounds(pipBounds);
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: updateMenuBounds: %s", TAG, menuBounds.toShortString());
        mSystemWindows.updateViewLayout(mPipBackgroundView,
                getPipMenuLayoutParams(mContext, BACKGROUND_WINDOW_TITLE, menuBounds.width(),
                        menuBounds.height()));
        mSystemWindows.updateViewLayout(mPipMenuView,
                getPipMenuLayoutParams(mContext, MENU_WINDOW_TITLE, menuBounds.width(),
                        menuBounds.height()));
        if (mPipMenuView != null) {
            mPipMenuView.updateBounds(pipBounds);
        }
    }

    @Override
    public void onFocusTaskChanged(ActivityManager.RunningTaskInfo taskInfo) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: onFocusTaskChanged", TAG);
    }

    @Override
    public void onBackPress() {
        if (!onExitMoveMode()) {
            closeMenu();
        }
    }

    @Override
    public void onCloseEduText() {
        mTvPipBoundsState.setPipMenuTemporaryDecorInsets(Insets.NONE);
        mDelegate.closeEduText();
    }

    interface Delegate {
        void movePip(int keycode);

        void onInMoveModeChanged();

        void onMenuClosed();

        void closeEduText();
    }

    private void grantPipMenuFocus(boolean grantFocus) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: grantWindowFocus(%b)", TAG, grantFocus);

        try {
            WindowManagerGlobal.getWindowSession().grantEmbeddedWindowFocus(null /* window */,
                    mSystemWindows.getFocusGrantToken(mPipMenuView), grantFocus);
        } catch (Exception e) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Unable to update focus, %s", TAG, e);
        }
    }

    private class PipMenuSurfaceChangedCallback implements ViewRootImpl.SurfaceChangedCallback {
        private final View mView;
        private final int mZOrder;

        PipMenuSurfaceChangedCallback(View v, int zOrder) {
            mView = v;
            mZOrder = zOrder;
        }

        @Override
        public void surfaceCreated(SurfaceControl.Transaction t) {
            final SurfaceControl sc = getSurfaceControl(mView);
            if (sc != null) {
                t.setRelativeLayer(sc, mLeash, mZOrder);
            }
        }

        @Override
        public void surfaceReplaced(SurfaceControl.Transaction t) {
        }

        @Override
        public void surfaceDestroyed() {
        }
    }
}
