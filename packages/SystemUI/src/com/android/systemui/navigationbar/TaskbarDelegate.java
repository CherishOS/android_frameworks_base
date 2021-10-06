/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.systemui.navigationbar;

import static android.app.StatusBarManager.NAVIGATION_HINT_BACK_ALT;
import static android.app.StatusBarManager.NAVIGATION_HINT_IME_SHOWN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BACK_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_HOME_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SWITCHER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_OVERVIEW_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;

import android.app.StatusBarManager;
import android.app.StatusBarManager.WindowVisibleState;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.view.Display;
import android.view.InsetsVisibilities;
import android.view.View;
import android.view.WindowInsetsController.Behavior;

import androidx.annotation.NonNull;

import com.android.internal.view.AppearanceRegion;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.CommandQueue;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TaskbarDelegate implements CommandQueue.Callbacks,
        OverviewProxyService.OverviewProxyListener, NavigationModeController.ModeChangedListener,
        ComponentCallbacks, Dumpable {

    private final EdgeBackGestureHandler mEdgeBackGestureHandler;

    private CommandQueue mCommandQueue;
    private OverviewProxyService mOverviewProxyService;
    private NavigationBarA11yHelper mNavigationBarA11yHelper;
    private NavigationModeController mNavigationModeController;
    private SysUiState mSysUiState;
    private int mDisplayId;
    private int mNavigationIconHints;
    private final NavigationBarA11yHelper.NavA11yEventListener mNavA11yEventListener =
            this::updateSysuiFlags;
    private int mDisabledFlags;
    private @WindowVisibleState int mTaskBarWindowState = WINDOW_STATE_SHOWING;
    private @Behavior int mBehavior;
    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private Context mWindowContext;

    @Inject
    public TaskbarDelegate(Context context) {
        mEdgeBackGestureHandler = Dependency.get(EdgeBackGestureHandler.Factory.class)
                .create(context);
        mContext = context;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
    }

    public void setOverviewProxyService(CommandQueue commandQueue,
            OverviewProxyService overviewProxyService,
            NavigationBarA11yHelper navigationBarA11yHelper,
            NavigationModeController navigationModeController,
            SysUiState sysUiState, DumpManager dumpManager) {
        // TODO: adding this in the ctor results in a dagger dependency cycle :(
        mCommandQueue = commandQueue;
        mOverviewProxyService = overviewProxyService;
        mNavigationBarA11yHelper = navigationBarA11yHelper;
        mNavigationModeController = navigationModeController;
        mSysUiState = sysUiState;
        dumpManager.registerDumpable(this);
    }

    public void destroy() {
        mCommandQueue.removeCallback(this);
        mOverviewProxyService.removeCallback(this);
        mNavigationModeController.removeListener(this);
        mNavigationBarA11yHelper.removeA11yEventListener(mNavA11yEventListener);
        mEdgeBackGestureHandler.onNavBarDetached();
        if (mWindowContext != null) {
            mWindowContext.unregisterComponentCallbacks(this);
            mWindowContext = null;
        }
    }

    public void init(int displayId) {
        mDisplayId = displayId;
        mCommandQueue.addCallback(this);
        mOverviewProxyService.addCallback(this);
        mEdgeBackGestureHandler.onNavigationModeChanged(
                mNavigationModeController.addListener(this));
        mNavigationBarA11yHelper.registerA11yEventListener(mNavA11yEventListener);
        mEdgeBackGestureHandler.onNavBarAttached();
        // Initialize component callback
        Display display = mDisplayManager.getDisplay(displayId);
        mWindowContext = mContext.createWindowContext(display, TYPE_APPLICATION, null);
        mWindowContext.registerComponentCallbacks(this);
        // Set initial state for any listeners
        updateSysuiFlags();
    }

    private void updateSysuiFlags() {
        int a11yFlags = mNavigationBarA11yHelper.getA11yButtonState();
        boolean clickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
        boolean longClickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;

        mSysUiState.setFlag(SYSUI_STATE_A11Y_BUTTON_CLICKABLE, clickable)
                .setFlag(SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE, longClickable)
                .setFlag(SYSUI_STATE_IME_SHOWING,
                        (mNavigationIconHints & NAVIGATION_HINT_BACK_ALT) != 0)
                .setFlag(SYSUI_STATE_IME_SWITCHER_SHOWING,
                        (mNavigationIconHints & NAVIGATION_HINT_IME_SHOWN) != 0)
                .setFlag(SYSUI_STATE_OVERVIEW_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0)
                .setFlag(SYSUI_STATE_HOME_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0)
                .setFlag(SYSUI_STATE_BACK_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                .setFlag(SYSUI_STATE_NAV_BAR_HIDDEN, !isWindowVisible())
                .setFlag(SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY,
                        allowSystemGestureIgnoringBarVisibility())
                .setFlag(SYSUI_STATE_SCREEN_PINNING,
                        ActivityManagerWrapper.getInstance().isScreenPinningActive())
                .commitUpdate(mDisplayId);
    }

    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        boolean imeShown = (vis & InputMethodService.IME_VISIBLE) != 0;
        int hints = Utilities.calculateBackDispositionHints(mNavigationIconHints, backDisposition,
                imeShown, showImeSwitcher);
        if (hints != mNavigationIconHints) {
            mNavigationIconHints = hints;
            updateSysuiFlags();
        }
    }

    @Override
    public void setWindowState(int displayId, int window, int state) {
        if (displayId == mDisplayId
                && window == StatusBarManager.WINDOW_NAVIGATION_BAR
                && mTaskBarWindowState != state) {
            mTaskBarWindowState = state;
            updateSysuiFlags();
        }
    }

    @Override
    public void onRotationProposal(int rotation, boolean isValid) {
        mOverviewProxyService.onRotationProposal(rotation, isValid);
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        mDisabledFlags = state1;
        updateSysuiFlags();
        mOverviewProxyService.disable(displayId, state1, state2, animate);
    }

    @Override
    public void onSystemBarAttributesChanged(int displayId, int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme, int behavior,
            InsetsVisibilities requestedVisibilities, String packageName) {
        mOverviewProxyService.onSystemBarAttributesChanged(displayId, behavior);
        if (mBehavior != behavior) {
            mBehavior = behavior;
            updateSysuiFlags();
        }
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mEdgeBackGestureHandler.onNavigationModeChanged(mode);
    }

    private boolean isWindowVisible() {
        return mTaskBarWindowState == WINDOW_STATE_SHOWING;
    }

    private boolean allowSystemGestureIgnoringBarVisibility() {
        return mBehavior != BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        mEdgeBackGestureHandler.onConfigurationChanged(configuration);
    }

    @Override
    public void onLowMemory() {}

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("TaskbarDelegate (displayId=" + mDisplayId + "):");
        pw.println("  mNavigationIconHints=" + mNavigationIconHints);
        pw.println("  mDisabledFlags=" + mDisabledFlags);
        pw.println("  mTaskBarWindowState=" + mTaskBarWindowState);
        pw.println("  mBehavior=" + mBehavior);
        mEdgeBackGestureHandler.dump(pw);
    }
}
