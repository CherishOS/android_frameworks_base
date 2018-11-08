/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.systemui.statusbar.NotificationRemoteInputManager.ENABLE_REMOTE_INPUT;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.R;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.statusbar.RemoteInputController.Callback;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Field;

/**
 * Encapsulates all logic for the status bar window state management.
 */
public class StatusBarWindowController implements Callback, Dumpable, ConfigurationListener {

    private static final String TAG = "StatusBarWindowController";

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final IActivityManager mActivityManager;
    private final DozeParameters mDozeParameters;
    private View mStatusBarView;
    private WindowManager.LayoutParams mLp;
    private WindowManager.LayoutParams mLpChanged;
    private boolean mHasTopUi;
    private boolean mHasTopUiChanged;
    private int mBarHeight;
    private final boolean mKeyguardScreenRotation;
    private float mScreenBrightnessDoze;
    private final State mCurrentState = new State();
    private OtherwisedCollapsedListener mListener;

    private final SysuiColorExtractor mColorExtractor = Dependency.get(SysuiColorExtractor.class);

    public StatusBarWindowController(Context context) {
        this(context, context.getSystemService(WindowManager.class), ActivityManager.getService(),
                DozeParameters.getInstance(context));
    }

    @VisibleForTesting
    StatusBarWindowController(Context context, WindowManager windowManager,
            IActivityManager activityManager, DozeParameters dozeParameters) {
        mContext = context;
        mWindowManager = windowManager;
        mActivityManager = activityManager;
        mKeyguardScreenRotation = shouldEnableKeyguardScreenRotation();
        mDozeParameters = dozeParameters;
        mScreenBrightnessDoze = mDozeParameters.getScreenBrightnessDoze();
        Dependency.get(StatusBarStateController.class).addListener(
                mStateListener, StatusBarStateController.RANK_STATUS_BAR_WINDOW_CONTROLLER);
        Dependency.get(ConfigurationController.class).addCallback(this);
    }

    private boolean shouldEnableKeyguardScreenRotation() {
        Resources res = mContext.getResources();
        return SystemProperties.getBoolean("lockscreen.rot_override", false)
                || res.getBoolean(R.bool.config_enableLockScreenRotation);
    }

    /**
     * Adds the status bar view to the window manager.
     *
     * @param statusBarView The view to add.
     * @param barHeight The height of the status bar in collapsed state.
     */
    public void add(View statusBarView, int barHeight) {

        // Now that the status bar window encompasses the sliding panel and its
        // translucent backdrop, the entire thing is made TRANSLUCENT and is
        // hardware-accelerated.
        mLp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeight,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT);
        mLp.token = new Binder();
        mLp.gravity = Gravity.TOP;
        mLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        mLp.setTitle("StatusBar");
        mLp.packageName = mContext.getPackageName();
        mLp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mStatusBarView = statusBarView;
        mBarHeight = barHeight;
        mWindowManager.addView(mStatusBarView, mLp);
        mLpChanged = new WindowManager.LayoutParams();
        mLpChanged.copyFrom(mLp);
        onThemeChanged();
    }

    public void setDozeScreenBrightness(int value) {
        mScreenBrightnessDoze = value / 255f;
    }

    private void setKeyguardDark(boolean dark) {
        int vis = mStatusBarView.getSystemUiVisibility();
        if (dark) {
            vis = vis | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            vis = vis | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            vis = vis & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            vis = vis & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        mStatusBarView.setSystemUiVisibility(vis);
    }

    private void applyKeyguardFlags(State state) {
        if (state.keyguardShowing) {
            mLpChanged.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
        } else {
            mLpChanged.privateFlags &= ~WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
        }

        final boolean scrimsOccludingWallpaper =
                state.scrimsVisibility == ScrimController.VISIBILITY_FULLY_OPAQUE;
        final boolean keyguardOrAod = state.keyguardShowing
                || (state.dozing && mDozeParameters.getAlwaysOn());
        if (keyguardOrAod && !state.backdropShowing && !scrimsOccludingWallpaper) {
            mLpChanged.flags |= WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        } else {
            mLpChanged.flags &= ~WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        }

        if (state.dozing) {
            mLpChanged.privateFlags |= LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        } else {
            mLpChanged.privateFlags &= ~LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        }
    }

    private void adjustScreenOrientation(State state) {
        if (state.isKeyguardShowingAndNotOccluded() || state.dozing) {
            if (mKeyguardScreenRotation) {
                mLpChanged.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_USER;
            } else {
                mLpChanged.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
            }
        } else {
            mLpChanged.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }

    private void applyFocusableFlag(State state) {
        boolean panelFocusable = state.statusBarFocusable && state.panelExpanded;
        if (state.bouncerShowing && (state.keyguardOccluded || state.keyguardNeedsInput)
                || ENABLE_REMOTE_INPUT && state.remoteInputActive) {
            mLpChanged.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            mLpChanged.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        } else if (state.isKeyguardShowingAndNotOccluded() || panelFocusable) {
            mLpChanged.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            mLpChanged.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        } else {
            mLpChanged.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            mLpChanged.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        }

        mLpChanged.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    }

    private void applyForceShowNavigationFlag(State state) {
        if (state.panelExpanded || state.bouncerShowing
                || ENABLE_REMOTE_INPUT && state.remoteInputActive) {
            mLpChanged.privateFlags |= LayoutParams.PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;
        } else {
            mLpChanged.privateFlags &= ~LayoutParams.PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;
        }
    }

    private void applyHeight(State state) {
        boolean expanded = isExpanded(state);
        if (state.forcePluginOpen) {
            mListener.setWouldOtherwiseCollapse(expanded);
            expanded = true;
        }
        if (expanded) {
            mLpChanged.height = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            mLpChanged.height = mBarHeight;
        }
    }

    private boolean isExpanded(State state) {
        return !state.forceCollapsed && (state.isKeyguardShowingAndNotOccluded()
                || state.panelVisible || state.keyguardFadingAway || state.bouncerShowing
                || state.headsUpShowing
                || state.scrimsVisibility != ScrimController.VISIBILITY_FULLY_TRANSPARENT);
    }

    private void applyFitsSystemWindows(State state) {
        boolean fitsSystemWindows = !state.isKeyguardShowingAndNotOccluded();
        if (mStatusBarView.getFitsSystemWindows() != fitsSystemWindows) {
            mStatusBarView.setFitsSystemWindows(fitsSystemWindows);
            mStatusBarView.requestApplyInsets();
        }
    }

    private void applyUserActivityTimeout(State state) {
        if (state.isKeyguardShowingAndNotOccluded()
                && state.statusBarState == StatusBarState.KEYGUARD
                && !state.qsExpanded) {
            mLpChanged.userActivityTimeout = KeyguardViewMediator.AWAKE_INTERVAL_DEFAULT_MS;
        } else {
            mLpChanged.userActivityTimeout = -1;
        }
    }

    private void applyInputFeatures(State state) {
        if (state.isKeyguardShowingAndNotOccluded()
                && state.statusBarState == StatusBarState.KEYGUARD
                && !state.qsExpanded && !state.forceUserActivity) {
            mLpChanged.inputFeatures |=
                    WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
        } else {
            mLpChanged.inputFeatures &=
                    ~WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
        }
    }

    private void apply(State state) {
        applyKeyguardFlags(state);
        applyForceStatusBarVisibleFlag(state);
        applyFocusableFlag(state);
        applyForceShowNavigationFlag(state);
        adjustScreenOrientation(state);
        applyHeight(state);
        applyUserActivityTimeout(state);
        applyInputFeatures(state);
        applyFitsSystemWindows(state);
        applyModalFlag(state);
        applyBrightness(state);
        applyHasTopUi(state);
        applySleepToken(state);
        applyNotTouchable(state);
        if (mLp.copyFrom(mLpChanged) != 0) {
            mWindowManager.updateViewLayout(mStatusBarView, mLp);
        }
        if (mHasTopUi != mHasTopUiChanged) {
            try {
                mActivityManager.setHasTopUi(mHasTopUiChanged);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to call setHasTopUi", e);
            }
            mHasTopUi = mHasTopUiChanged;
        }
    }

    private void applyForceStatusBarVisibleFlag(State state) {
        if (state.forceStatusBarVisible) {
            mLpChanged.privateFlags |= WindowManager
                    .LayoutParams.PRIVATE_FLAG_FORCE_STATUS_BAR_VISIBLE_TRANSPARENT;
        } else {
            mLpChanged.privateFlags &= ~WindowManager
                    .LayoutParams.PRIVATE_FLAG_FORCE_STATUS_BAR_VISIBLE_TRANSPARENT;
        }
    }

    private void applyModalFlag(State state) {
        if (state.headsUpShowing) {
            mLpChanged.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        } else {
            mLpChanged.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
    }

    private void applyBrightness(State state) {
        if (state.forceDozeBrightness) {
            mLpChanged.screenBrightness = mScreenBrightnessDoze;
        } else {
            mLpChanged.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
    }

    private void applyHasTopUi(State state) {
        mHasTopUiChanged = isExpanded(state);
    }

    private void applySleepToken(State state) {
        if (state.dozing) {
            mLpChanged.privateFlags |= LayoutParams.PRIVATE_FLAG_ACQUIRES_SLEEP_TOKEN;
        } else {
            mLpChanged.privateFlags &= ~LayoutParams.PRIVATE_FLAG_ACQUIRES_SLEEP_TOKEN;
        }
    }

    private void applyNotTouchable(State state) {
        if (state.notTouchable) {
            mLpChanged.flags |= LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            mLpChanged.flags &= ~LayoutParams.FLAG_NOT_TOUCHABLE;
        }
    }

    public void setKeyguardShowing(boolean showing) {
        mCurrentState.keyguardShowing = showing;
        apply(mCurrentState);
    }

    public void setKeyguardOccluded(boolean occluded) {
        mCurrentState.keyguardOccluded = occluded;
        apply(mCurrentState);
    }

    public void setKeyguardNeedsInput(boolean needsInput) {
        mCurrentState.keyguardNeedsInput = needsInput;
        apply(mCurrentState);
    }

    public void setPanelVisible(boolean visible) {
        mCurrentState.panelVisible = visible;
        mCurrentState.statusBarFocusable = visible;
        apply(mCurrentState);
    }

    public void setStatusBarFocusable(boolean focusable) {
        mCurrentState.statusBarFocusable = focusable;
        apply(mCurrentState);
    }

    public void setBouncerShowing(boolean showing) {
        mCurrentState.bouncerShowing = showing;
        apply(mCurrentState);
    }

    public void setBackdropShowing(boolean showing) {
        mCurrentState.backdropShowing = showing;
        apply(mCurrentState);
    }

    public void setKeyguardFadingAway(boolean keyguardFadingAway) {
        mCurrentState.keyguardFadingAway = keyguardFadingAway;
        apply(mCurrentState);
    }

    public void setQsExpanded(boolean expanded) {
        mCurrentState.qsExpanded = expanded;
        apply(mCurrentState);
    }

    public void setForceUserActivity(boolean forceUserActivity) {
        mCurrentState.forceUserActivity = forceUserActivity;
        apply(mCurrentState);
    }

    public void setScrimsVisibility(int scrimsVisibility) {
        mCurrentState.scrimsVisibility = scrimsVisibility;
        apply(mCurrentState);
    }

    public void setHeadsUpShowing(boolean showing) {
        mCurrentState.headsUpShowing = showing;
        apply(mCurrentState);
    }

    public void setWallpaperSupportsAmbientMode(boolean supportsAmbientMode) {
        mCurrentState.wallpaperSupportsAmbientMode = supportsAmbientMode;
        apply(mCurrentState);
    }

    /**
     * @param state The {@link StatusBarStateController} of the status bar.
     */
    private void setStatusBarState(int state) {
        mCurrentState.statusBarState = state;
        apply(mCurrentState);
    }

    public void setForceStatusBarVisible(boolean forceStatusBarVisible) {
        mCurrentState.forceStatusBarVisible = forceStatusBarVisible;
        apply(mCurrentState);
    }

    /**
     * Force the window to be collapsed, even if it should theoretically be expanded.
     * Used for when a heads-up comes in but we still need to wait for the touchable regions to
     * be computed.
     */
    public void setForceWindowCollapsed(boolean force) {
        mCurrentState.forceCollapsed = force;
        apply(mCurrentState);
    }

    public void setPanelExpanded(boolean isExpanded) {
        mCurrentState.panelExpanded = isExpanded;
        apply(mCurrentState);
    }

    @Override
    public void onRemoteInputActive(boolean remoteInputActive) {
        mCurrentState.remoteInputActive = remoteInputActive;
        apply(mCurrentState);
    }

    /**
     * Set whether the screen brightness is forced to the value we use for doze mode by the status
     * bar window.
     */
    public void setForceDozeBrightness(boolean forceDozeBrightness) {
        mCurrentState.forceDozeBrightness = forceDozeBrightness;
        apply(mCurrentState);
    }

    public void setDozing(boolean dozing) {
        mCurrentState.dozing = dozing;
        apply(mCurrentState);
    }

    public void setBarHeight(int barHeight) {
        mBarHeight = barHeight;
        apply(mCurrentState);
    }

    public void setForcePluginOpen(boolean forcePluginOpen) {
        mCurrentState.forcePluginOpen = forcePluginOpen;
        apply(mCurrentState);
    }

    public void setNotTouchable(boolean notTouchable) {
        mCurrentState.notTouchable = notTouchable;
        apply(mCurrentState);
    }

    public void setStateListener(OtherwisedCollapsedListener listener) {
        mListener = listener;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("StatusBarWindowController state:");
        pw.println(mCurrentState);
    }

    public boolean isShowingWallpaper() {
        return !mCurrentState.backdropShowing;
    }

    @Override
    public void onThemeChanged() {
        if (mStatusBarView == null) {
            return;
        }

        StatusBarStateController state = Dependency.get(StatusBarStateController.class);
        int which;
        if (state.getState() == StatusBarState.KEYGUARD
                || state.getState() == StatusBarState.SHADE_LOCKED) {
            which = WallpaperManager.FLAG_LOCK;
        } else {
            which = WallpaperManager.FLAG_SYSTEM;
        }
        final boolean useDarkText = mColorExtractor.getColors(which,
                true /* ignoreVisibility */).supportsDarkText();

        // Make sure we have the correct navbar/statusbar colors.
        setKeyguardDark(useDarkText);
    }

    private static class State {
        boolean keyguardShowing;
        boolean keyguardOccluded;
        boolean keyguardNeedsInput;
        boolean panelVisible;
        boolean panelExpanded;
        boolean statusBarFocusable;
        boolean bouncerShowing;
        boolean keyguardFadingAway;
        boolean qsExpanded;
        boolean headsUpShowing;
        boolean forceStatusBarVisible;
        boolean forceCollapsed;
        boolean forceDozeBrightness;
        boolean forceUserActivity;
        boolean backdropShowing;
        boolean wallpaperSupportsAmbientMode;
        boolean notTouchable;

        /**
         * The {@link StatusBar} state from the status bar.
         */
        int statusBarState;

        boolean remoteInputActive;
        boolean forcePluginOpen;
        boolean dozing;
        int scrimsVisibility;

        private boolean isKeyguardShowingAndNotOccluded() {
            return keyguardShowing && !keyguardOccluded;
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder();
            String newLine = "\n";
            result.append("Window State {");
            result.append(newLine);

            Field[] fields = this.getClass().getDeclaredFields();

            // Print field names paired with their values
            for (Field field : fields) {
                result.append("  ");
                try {
                    result.append(field.getName());
                    result.append(": ");
                    //requires access to private field:
                    result.append(field.get(this));
                } catch (IllegalAccessException ex) {
                }
                result.append(newLine);
            }
            result.append("}");

            return result.toString();
        }
    }

    private final StateListener mStateListener = new StateListener() {
        @Override
        public void onStateChanged(int newState) {
            setStatusBarState(newState);
        }

        @Override
        public void onDozingChanged(boolean isDozing) {
            setDozing(isDozing);
        }
    };

    /**
     * Custom listener to pipe data back to plugins about whether or not the status bar would be
     * collapsed if not for the plugin.
     * TODO: Find cleaner way to do this.
     */
    public interface OtherwisedCollapsedListener {
        void setWouldOtherwiseCollapse(boolean otherwiseCollapse);
    }
}
