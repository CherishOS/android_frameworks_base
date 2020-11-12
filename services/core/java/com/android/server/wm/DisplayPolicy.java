/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.content.res.Configuration.UI_MODE_TYPE_CAR;
import static android.content.res.Configuration.UI_MODE_TYPE_MASK;
import static android.view.Display.TYPE_INTERNAL;
import static android.view.InsetsState.ITYPE_BOTTOM_DISPLAY_CUTOUT;
import static android.view.InsetsState.ITYPE_BOTTOM_GESTURES;
import static android.view.InsetsState.ITYPE_BOTTOM_TAPPABLE_ELEMENT;
import static android.view.InsetsState.ITYPE_CAPTION_BAR;
import static android.view.InsetsState.ITYPE_CLIMATE_BAR;
import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_LEFT_DISPLAY_CUTOUT;
import static android.view.InsetsState.ITYPE_LEFT_GESTURES;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_RIGHT_DISPLAY_CUTOUT;
import static android.view.InsetsState.ITYPE_RIGHT_GESTURES;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.InsetsState.ITYPE_TOP_DISPLAY_CUTOUT;
import static android.view.InsetsState.ITYPE_TOP_GESTURES;
import static android.view.InsetsState.ITYPE_TOP_TAPPABLE_ELEMENT;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_SWIPE;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_TOUCH;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.INPUT_CONSUMER_NAVIGATION;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_SCREEN_DECOR;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManagerGlobal.ADD_OKAY;
import static android.view.WindowManagerPolicyConstants.ACTION_HDMI_PLUGGED;
import static android.view.WindowManagerPolicyConstants.ALT_BAR_BOTTOM;
import static android.view.WindowManagerPolicyConstants.ALT_BAR_LEFT;
import static android.view.WindowManagerPolicyConstants.ALT_BAR_RIGHT;
import static android.view.WindowManagerPolicyConstants.ALT_BAR_TOP;
import static android.view.WindowManagerPolicyConstants.ALT_BAR_UNKNOWN;
import static android.view.WindowManagerPolicyConstants.EXTRA_HDMI_PLUGGED_STATE;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_BOTTOM;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_LEFT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_RIGHT;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_SCREEN_ON;
import static com.android.server.policy.PhoneWindowManager.TOAST_WINDOW_TIMEOUT;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_ENTER;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_EXIT;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_HIDE;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_SHOW;
import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.LID_ABSENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.LoadedApk;
import android.app.ResourcesManager;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.hardware.power.Boost;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InsetsFlags;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.InsetsState.InternalInsetsType;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.View;
import android.view.ViewDebug;
import android.view.WindowInsets.Side;
import android.view.WindowInsets.Side.InsetsSide;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicyConstants;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.GestureNavigationSettingsObserver;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ScreenshotHelper;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.view.AppearanceRegion;
import com.android.internal.widget.PointerLocationView;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.policy.WindowManagerPolicy.NavigationBarPosition;
import com.android.server.policy.WindowManagerPolicy.ScreenOnListener;
import com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs;
import com.android.server.policy.WindowOrientationListener;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wallpaper.WallpaperManagerInternal;
import com.android.server.wm.InputMonitor.EventReceiverInputConsumer;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * The policy that provides the basic behaviors and states of a display to show UI.
 */
public class DisplayPolicy {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "DisplayPolicy" : TAG_WM;

    private static final boolean ALTERNATE_CAR_MODE_NAV_SIZE = false;

    // The panic gesture may become active only after the keyguard is dismissed and the immersive
    // app shows again. If that doesn't happen for 30s we drop the gesture.
    private static final long PANIC_GESTURE_EXPIRATION = 30000;

    // Controls navigation bar opacity depending on which workspace stacks are currently
    // visible.
    // Nav bar is always opaque when either the freeform stack or docked stack is visible.
    private static final int NAV_BAR_OPAQUE_WHEN_FREEFORM_OR_DOCKED = 0;
    // Nav bar is always translucent when the freeform stack is visible, otherwise always opaque.
    private static final int NAV_BAR_TRANSLUCENT_WHEN_FREEFORM_OPAQUE_OTHERWISE = 1;
    // Nav bar is never forced opaque.
    private static final int NAV_BAR_FORCE_TRANSPARENT = 2;

    /** Don't apply window animation (see {@link #selectAnimation}). */
    static final int ANIMATION_NONE = -1;
    /** Use the transit animation in style resource (see {@link #selectAnimation}). */
    static final int ANIMATION_STYLEABLE = 0;

    private static final int[] SHOW_TYPES_FOR_SWIPE = {ITYPE_NAVIGATION_BAR, ITYPE_STATUS_BAR,
            ITYPE_CLIMATE_BAR, ITYPE_EXTRA_NAVIGATION_BAR};
    private static final int[] SHOW_TYPES_FOR_PANIC = {ITYPE_NAVIGATION_BAR};

    private final WindowManagerService mService;
    private final Context mContext;
    private final Context mUiContext;
    private final DisplayContent mDisplayContent;
    private final Object mLock;
    private final Handler mHandler;

    private Resources mCurrentUserResources;

    private final boolean mCarDockEnablesAccelerometer;
    private final boolean mDeskDockEnablesAccelerometer;
    private final AccessibilityManager mAccessibilityManager;
    private final ImmersiveModeConfirmation mImmersiveModeConfirmation;
    private final ScreenshotHelper mScreenshotHelper;

    private final Object mServiceAcquireLock = new Object();
    private StatusBarManagerInternal mStatusBarManagerInternal;

    @Px
    private int mBottomGestureAdditionalInset;
    @Px
    private int mLeftGestureInset;
    @Px
    private int mRightGestureInset;

    private boolean mNavButtonForcedVisible;

    StatusBarManagerInternal getStatusBarManagerInternal() {
        synchronized (mServiceAcquireLock) {
            if (mStatusBarManagerInternal == null) {
                mStatusBarManagerInternal =
                        LocalServices.getService(StatusBarManagerInternal.class);
            }
            return mStatusBarManagerInternal;
        }
    }

    private final SystemGesturesPointerEventListener mSystemGestures;

    private volatile int mLidState = LID_ABSENT;
    private volatile int mDockMode = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    private volatile boolean mHdmiPlugged;

    private volatile boolean mHasStatusBar;
    private volatile boolean mHasNavigationBar;
    // Can the navigation bar ever move to the side?
    private volatile boolean mNavigationBarCanMove;
    private volatile boolean mNavigationBarLetsThroughTaps;
    private volatile boolean mNavigationBarAlwaysShowOnSideGesture;

    // Written by vr manager thread, only read in this class.
    private volatile boolean mPersistentVrModeEnabled;

    private volatile boolean mAwake;
    private volatile boolean mScreenOnEarly;
    private volatile boolean mScreenOnFully;
    private volatile ScreenOnListener mScreenOnListener;

    private volatile boolean mKeyguardDrawComplete;
    private volatile boolean mWindowManagerDrawComplete;

    private final ArraySet<WindowState> mScreenDecorWindows = new ArraySet<>();
    private WindowState mStatusBar = null;
    private WindowState mNotificationShade = null;
    private final int[] mStatusBarHeightForRotation = new int[4];
    private WindowState mNavigationBar = null;
    @NavigationBarPosition
    private int mNavigationBarPosition = NAV_BAR_BOTTOM;
    private int[] mNavigationBarHeightForRotationDefault = new int[4];
    private int[] mNavigationBarWidthForRotationDefault = new int[4];
    private int[] mNavigationBarHeightForRotationInCarMode = new int[4];
    private int[] mNavigationBarWidthForRotationInCarMode = new int[4];

    // Alternative status bar for when flexible insets mapping is used to place the status bar on
    // another side of the screen.
    private WindowState mStatusBarAlt = null;
    @WindowManagerPolicy.AltBarPosition
    private int mStatusBarAltPosition = ALT_BAR_UNKNOWN;
    // Alternative navigation bar for when flexible insets mapping is used to place the navigation
    // bar elsewhere on the screen.
    private WindowState mNavigationBarAlt = null;
    @WindowManagerPolicy.AltBarPosition
    private int mNavigationBarAltPosition = ALT_BAR_UNKNOWN;
    // Alternative climate bar for when flexible insets mapping is used to place a climate bar on
    // the screen.
    private WindowState mClimateBarAlt = null;
    @WindowManagerPolicy.AltBarPosition
    private int mClimateBarAltPosition = ALT_BAR_UNKNOWN;
    // Alternative extra nav bar for when flexible insets mapping is used to place an extra nav bar
    // on the screen.
    private WindowState mExtraNavBarAlt = null;
    @WindowManagerPolicy.AltBarPosition
    private int mExtraNavBarAltPosition = ALT_BAR_UNKNOWN;

    /** See {@link #getNavigationBarFrameHeight} */
    private int[] mNavigationBarFrameHeightForRotationDefault = new int[4];

    private boolean mIsFreeformWindowOverlappingWithNavBar;

    private boolean mLastImmersiveMode;

    private StatusBarManagerInternal mStatusBarInternal;
    private final BarController mStatusBarController;
    private final BarController mNavigationBarController;

    // The windows we were told about in focusChanged.
    private WindowState mFocusedWindow;
    private WindowState mLastFocusedWindow;

    private WindowState mSystemUiControllingWindow;

    // The states of decor windows from the last layout. These are used to generate another display
    // layout in different bounds but with the same states.
    private boolean mLastNavVisible;
    private boolean mLastNavTranslucent;
    private boolean mLastNavAllowedHidden;

    private int mLastDisableFlags;
    private int mLastAppearance;
    private int mLastFullscreenAppearance;
    private int mLastDockedAppearance;
    private int mLastBehavior;
    private final Rect mNonDockedStackBounds = new Rect();
    private final Rect mDockedStackBounds = new Rect();
    private final Rect mLastNonDockedStackBounds = new Rect();
    private final Rect mLastDockedStackBounds = new Rect();

    // What we last reported to system UI about whether the focused window is fullscreen/immersive.
    private boolean mLastFocusIsFullscreen = false;
    private boolean mLastFocusIsImmersive = false;

    // If nonzero, a panic gesture was performed at that time in uptime millis and is still pending.
    private long mPendingPanicGestureUptime;

    private static final Rect sTmpDisplayCutoutSafeExceptMaybeBarsRect = new Rect();
    private static final Rect sTmpRect = new Rect();
    private static final Rect sTmpNavFrame = new Rect();
    private static final Rect sTmpStatusFrame = new Rect();
    private static final Rect sTmpScreenDecorFrame = new Rect();
    private static final Rect sTmpLastParentFrame = new Rect();
    private static final Rect sTmpDisplayFrameBounds = new Rect();

    private WindowState mTopFullscreenOpaqueWindowState;
    private WindowState mTopFullscreenOpaqueOrDimmingWindowState;
    private WindowState mTopDockedOpaqueWindowState;
    private WindowState mTopDockedOpaqueOrDimmingWindowState;
    private boolean mTopIsFullscreen;
    private boolean mForceStatusBar;
    private int mNavBarOpacityMode = NAV_BAR_OPAQUE_WHEN_FREEFORM_OR_DOCKED;
    private boolean mForcingShowNavBar;
    private int mForcingShowNavBarLayer;
    private boolean mForceShowSystemBars;

    private boolean mShowingDream;
    private boolean mLastShowingDream;
    private boolean mDreamingLockscreen;
    private boolean mAllowLockscreenWhenOn;

    @VisibleForTesting
    EventReceiverInputConsumer mInputConsumer;

    private PointerLocationView mPointerLocationView;

    /**
     * The area covered by system windows which belong to another display. Forwarded insets is set
     * in case this is a virtual display, this is displayed on another display that has insets, and
     * the bounds of this display is overlapping with the insets of the host display (e.g. IME is
     * displayed on the host display, and it covers a part of this virtual display.)
     * The forwarded insets is used to compute display frames of this virtual display, which will
     * be then used to layout windows in the virtual display.
     */
    @NonNull private Insets mForwardedInsets = Insets.NONE;

    private RefreshRatePolicy mRefreshRatePolicy;

    // -------- PolicyHandler --------
    private static final int MSG_REQUEST_TRANSIENT_BARS = 2;
    private static final int MSG_DISPOSE_INPUT_CONSUMER = 3;
    private static final int MSG_ENABLE_POINTER_LOCATION = 4;
    private static final int MSG_DISABLE_POINTER_LOCATION = 5;

    private static final int MSG_REQUEST_TRANSIENT_BARS_ARG_STATUS = 0;
    private static final int MSG_REQUEST_TRANSIENT_BARS_ARG_NAVIGATION = 1;

    private final GestureNavigationSettingsObserver mGestureNavigationSettingsObserver;

    private final WindowManagerInternal.AppTransitionListener mAppTransitionListener;

    private class PolicyHandler extends Handler {

        PolicyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REQUEST_TRANSIENT_BARS:
                    synchronized (mLock) {
                        WindowState targetBar = (msg.arg1 == MSG_REQUEST_TRANSIENT_BARS_ARG_STATUS)
                                ? getStatusBar() : getNavigationBar();
                        if (targetBar != null) {
                            requestTransientBars(targetBar);
                        }
                    }
                    break;
                case MSG_DISPOSE_INPUT_CONSUMER:
                    disposeInputConsumer((EventReceiverInputConsumer) msg.obj);
                    break;
                case MSG_ENABLE_POINTER_LOCATION:
                    enablePointerLocation();
                    break;
                case MSG_DISABLE_POINTER_LOCATION:
                    disablePointerLocation();
                    break;
            }
        }
    }

    DisplayPolicy(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mContext = displayContent.isDefaultDisplay ? service.mContext
                : service.mContext.createDisplayContext(displayContent.getDisplay());
        mUiContext = displayContent.isDefaultDisplay ? service.mAtmService.mUiContext
                : service.mAtmService.mSystemThread
                        .createSystemUiContext(displayContent.getDisplayId());
        mDisplayContent = displayContent;
        mLock = service.getWindowManagerLock();

        final int displayId = displayContent.getDisplayId();

        mStatusBarController = new BarController(TYPE_STATUS_BAR);
        mNavigationBarController = new BarController(TYPE_NAVIGATION_BAR);

        final Resources r = mContext.getResources();
        mCarDockEnablesAccelerometer = r.getBoolean(R.bool.config_carDockEnablesAccelerometer);
        mDeskDockEnablesAccelerometer = r.getBoolean(R.bool.config_deskDockEnablesAccelerometer);

        mAccessibilityManager = (AccessibilityManager) mContext.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        if (!displayContent.isDefaultDisplay) {
            mAwake = true;
            mScreenOnEarly = true;
            mScreenOnFully = true;
        }

        final Looper looper = UiThread.getHandler().getLooper();
        mHandler = new PolicyHandler(looper);
        mSystemGestures = new SystemGesturesPointerEventListener(mContext, mHandler,
                new SystemGesturesPointerEventListener.Callbacks() {
                    @Override
                    public void onSwipeFromTop() {
                        synchronized (mLock) {
                            if (mStatusBar != null) {
                                requestTransientBars(mStatusBar);
                            }
                            checkAltBarSwipeForTransientBars(ALT_BAR_TOP);
                        }
                    }

                    @Override
                    public void onSwipeFromBottom() {
                        synchronized (mLock) {
                            if (mNavigationBar != null
                                    && mNavigationBarPosition == NAV_BAR_BOTTOM) {
                                requestTransientBars(mNavigationBar);
                            }
                            checkAltBarSwipeForTransientBars(ALT_BAR_BOTTOM);
                        }
                    }

                    @Override
                    public void onSwipeFromRight() {
                        final Region excludedRegion = Region.obtain();
                        synchronized (mLock) {
                            mDisplayContent.calculateSystemGestureExclusion(
                                    excludedRegion, null /* outUnrestricted */);
                            final boolean sideAllowed = mNavigationBarAlwaysShowOnSideGesture
                                    || mNavigationBarPosition == NAV_BAR_RIGHT;
                            if (mNavigationBar != null && sideAllowed
                                    && !mSystemGestures.currentGestureStartedInRegion(
                                            excludedRegion)) {
                                requestTransientBars(mNavigationBar);
                            }
                            checkAltBarSwipeForTransientBars(ALT_BAR_RIGHT);
                        }
                        excludedRegion.recycle();
                    }

                    @Override
                    public void onSwipeFromLeft() {
                        final Region excludedRegion = Region.obtain();
                        synchronized (mLock) {
                            mDisplayContent.calculateSystemGestureExclusion(
                                    excludedRegion, null /* outUnrestricted */);
                            final boolean sideAllowed = mNavigationBarAlwaysShowOnSideGesture
                                    || mNavigationBarPosition == NAV_BAR_LEFT;
                            if (mNavigationBar != null && sideAllowed
                                    && !mSystemGestures.currentGestureStartedInRegion(
                                            excludedRegion)) {
                                requestTransientBars(mNavigationBar);
                            }
                            checkAltBarSwipeForTransientBars(ALT_BAR_LEFT);
                        }
                        excludedRegion.recycle();
                    }

                    @Override
                    public void onFling(int duration) {
                        if (mService.mPowerManagerInternal != null) {
                            mService.mPowerManagerInternal.setPowerBoost(
                                    Boost.INTERACTION, duration);
                        }
                    }

                    @Override
                    public void onDebug() {
                        // no-op
                    }

                    private WindowOrientationListener getOrientationListener() {
                        final DisplayRotation rotation = mDisplayContent.getDisplayRotation();
                        return rotation != null ? rotation.getOrientationListener() : null;
                    }

                    @Override
                    public void onDown() {
                        final WindowOrientationListener listener = getOrientationListener();
                        if (listener != null) {
                            listener.onTouchStart();
                        }
                    }

                    @Override
                    public void onUpOrCancel() {
                        final WindowOrientationListener listener = getOrientationListener();
                        if (listener != null) {
                            listener.onTouchEnd();
                        }
                    }

                    @Override
                    public void onMouseHoverAtTop() {
                        mHandler.removeMessages(MSG_REQUEST_TRANSIENT_BARS);
                        Message msg = mHandler.obtainMessage(MSG_REQUEST_TRANSIENT_BARS);
                        msg.arg1 = MSG_REQUEST_TRANSIENT_BARS_ARG_STATUS;
                        mHandler.sendMessageDelayed(msg, 500 /* delayMillis */);
                    }

                    @Override
                    public void onMouseHoverAtBottom() {
                        mHandler.removeMessages(MSG_REQUEST_TRANSIENT_BARS);
                        Message msg = mHandler.obtainMessage(MSG_REQUEST_TRANSIENT_BARS);
                        msg.arg1 = MSG_REQUEST_TRANSIENT_BARS_ARG_NAVIGATION;
                        mHandler.sendMessageDelayed(msg, 500 /* delayMillis */);
                    }

                    @Override
                    public void onMouseLeaveFromEdge() {
                        mHandler.removeMessages(MSG_REQUEST_TRANSIENT_BARS);
                    }
                });
        displayContent.registerPointerEventListener(mSystemGestures);
        mAppTransitionListener = new WindowManagerInternal.AppTransitionListener() {

            private Runnable mAppTransitionPending = () -> {
                StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
                if (statusBar != null) {
                    statusBar.appTransitionPending(displayId);
                }
            };

            private Runnable mAppTransitionCancelled = () -> {
                StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
                if (statusBar != null) {
                    statusBar.appTransitionCancelled(displayId);
                }
            };

            private Runnable mAppTransitionFinished = () -> {
                StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
                if (statusBar != null) {
                    statusBar.appTransitionFinished(displayId);
                }
            };

            @Override
            public void onAppTransitionPendingLocked() {
                mHandler.post(mAppTransitionPending);
            }

            @Override
            public int onAppTransitionStartingLocked(int transit, long duration,
                    long statusBarAnimationStartTime, long statusBarAnimationDuration) {
                mHandler.post(() -> {
                    StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
                    if (statusBar != null) {
                        statusBar.appTransitionStarting(mContext.getDisplayId(),
                                statusBarAnimationStartTime, statusBarAnimationDuration);
                    }
                });
                return 0;
            }

            @Override
            public void onAppTransitionCancelledLocked(int transit) {
                mHandler.post(mAppTransitionCancelled);
            }

            @Override
            public void onAppTransitionFinishedLocked(IBinder token) {
                mHandler.post(mAppTransitionFinished);
            }
        };
        displayContent.mAppTransition.registerListenerLocked(mAppTransitionListener);
        mImmersiveModeConfirmation = new ImmersiveModeConfirmation(mContext, looper,
                mService.mVrModeEnabled);

        // TODO: Make it can take screenshot on external display
        mScreenshotHelper = displayContent.isDefaultDisplay
                ? new ScreenshotHelper(mContext) : null;

        if (mDisplayContent.isDefaultDisplay) {
            mHasStatusBar = true;
            mHasNavigationBar = mContext.getResources().getBoolean(R.bool.config_showNavigationBar);

            // Allow a system property to override this. Used by the emulator.
            // See also hasNavigationBar().
            String navBarOverride = SystemProperties.get("qemu.hw.mainkeys");
            if ("1".equals(navBarOverride)) {
                mHasNavigationBar = false;
            } else if ("0".equals(navBarOverride)) {
                mHasNavigationBar = true;
            }
        } else {
            mHasStatusBar = false;
            mHasNavigationBar = mDisplayContent.supportsSystemDecorations();
        }

        mRefreshRatePolicy = new RefreshRatePolicy(mService,
                mDisplayContent.getDisplayInfo(),
                mService.mHighRefreshRateDenylist);

        mGestureNavigationSettingsObserver = new GestureNavigationSettingsObserver(mHandler,
                mContext, () -> {
            synchronized (mLock) {
                onConfigurationChanged();
                mSystemGestures.onConfigurationChanged();
                mDisplayContent.updateSystemGestureExclusion();
            }
        });
        mHandler.post(mGestureNavigationSettingsObserver::register);
    }

    private void checkAltBarSwipeForTransientBars(@WindowManagerPolicy.AltBarPosition int pos) {
        if (mStatusBarAlt != null && mStatusBarAltPosition == pos) {
            requestTransientBars(mStatusBarAlt);
        }
        if (mNavigationBarAlt != null && mNavigationBarAltPosition == pos) {
            requestTransientBars(mNavigationBarAlt);
        }
        if (mClimateBarAlt != null && mClimateBarAltPosition == pos) {
            requestTransientBars(mClimateBarAlt);
        }
        if (mExtraNavBarAlt != null && mExtraNavBarAltPosition == pos) {
            requestTransientBars(mExtraNavBarAlt);
        }
    }

    void systemReady() {
        mSystemGestures.systemReady();
        if (mService.mPointerLocationEnabled) {
            setPointerLocationEnabled(true);
        }
    }

    private int getDisplayId() {
        return mDisplayContent.getDisplayId();
    }

    public void setHdmiPlugged(boolean plugged) {
        setHdmiPlugged(plugged, false /* force */);
    }

    public void setHdmiPlugged(boolean plugged, boolean force) {
        if (force || mHdmiPlugged != plugged) {
            mHdmiPlugged = plugged;
            mService.updateRotation(true /* alwaysSendConfiguration */, true /* forceRelayout */);
            final Intent intent = new Intent(ACTION_HDMI_PLUGGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(EXTRA_HDMI_PLUGGED_STATE, plugged);
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    boolean isHdmiPlugged() {
        return mHdmiPlugged;
    }

    boolean isCarDockEnablesAccelerometer() {
        return mCarDockEnablesAccelerometer;
    }

    boolean isDeskDockEnablesAccelerometer() {
        return mDeskDockEnablesAccelerometer;
    }

    public void setPersistentVrModeEnabled(boolean persistentVrModeEnabled) {
        mPersistentVrModeEnabled = persistentVrModeEnabled;
    }

    public boolean isPersistentVrModeEnabled() {
        return mPersistentVrModeEnabled;
    }

    public void setDockMode(int dockMode) {
        mDockMode = dockMode;
    }

    public int getDockMode() {
        return mDockMode;
    }

    public boolean hasNavigationBar() {
        return mHasNavigationBar;
    }

    public boolean hasStatusBar() {
        return mHasStatusBar;
    }

    boolean hasSideGestures() {
        return mHasNavigationBar && (mLeftGestureInset > 0 || mRightGestureInset > 0);
    }

    public boolean navigationBarCanMove() {
        return mNavigationBarCanMove;
    }

    public void setLidState(int lidState) {
        mLidState = lidState;
    }

    public int getLidState() {
        return mLidState;
    }

    public void setAwake(boolean awake) {
        mAwake = awake;
    }

    public boolean isAwake() {
        return mAwake;
    }

    public boolean isScreenOnEarly() {
        return mScreenOnEarly;
    }

    public boolean isScreenOnFully() {
        return mScreenOnFully;
    }

    public boolean isKeyguardDrawComplete() {
        return mKeyguardDrawComplete;
    }

    public boolean isWindowManagerDrawComplete() {
        return mWindowManagerDrawComplete;
    }

    public ScreenOnListener getScreenOnListener() {
        return mScreenOnListener;
    }

    public void screenTurnedOn(ScreenOnListener screenOnListener) {
        synchronized (mLock) {
            mScreenOnEarly = true;
            mScreenOnFully = false;
            mKeyguardDrawComplete = false;
            mWindowManagerDrawComplete = false;
            mScreenOnListener = screenOnListener;
        }
    }

    public void screenTurnedOff() {
        synchronized (mLock) {
            mScreenOnEarly = false;
            mScreenOnFully = false;
            mKeyguardDrawComplete = false;
            mWindowManagerDrawComplete = false;
            mScreenOnListener = null;
        }
    }

    /** Return false if we are not awake yet or we have already informed of this event. */
    public boolean finishKeyguardDrawn() {
        synchronized (mLock) {
            if (!mScreenOnEarly || mKeyguardDrawComplete) {
                return false;
            }

            mKeyguardDrawComplete = true;
            mWindowManagerDrawComplete = false;
        }
        return true;
    }

    /** Return false if screen is not turned on or we did already handle this case earlier. */
    public boolean finishWindowsDrawn() {
        synchronized (mLock) {
            if (!mScreenOnEarly || mWindowManagerDrawComplete) {
                return false;
            }

            mWindowManagerDrawComplete = true;
        }
        return true;
    }

    /** Return false if it is not ready to turn on. */
    public boolean finishScreenTurningOn() {
        synchronized (mLock) {
            ProtoLog.d(WM_DEBUG_SCREEN_ON,
                            "finishScreenTurningOn: mAwake=%b, mScreenOnEarly=%b, "
                                    + "mScreenOnFully=%b, mKeyguardDrawComplete=%b, "
                                    + "mWindowManagerDrawComplete=%b",
                            mAwake, mScreenOnEarly, mScreenOnFully, mKeyguardDrawComplete,
                            mWindowManagerDrawComplete);

            if (mScreenOnFully || !mScreenOnEarly || !mWindowManagerDrawComplete
                    || (mAwake && !mKeyguardDrawComplete)) {
                return false;
            }

            ProtoLog.i(WM_DEBUG_SCREEN_ON, "Finished screen turning on...");
            mScreenOnListener = null;
            mScreenOnFully = true;
        }
        return true;
    }

    private boolean hasStatusBarServicePermission(int pid, int uid) {
        return mContext.checkPermission(permission.STATUS_BAR_SERVICE, pid, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Sanitize the layout parameters coming from a client.  Allows the policy
     * to do things like ensure that windows of a specific type can't take
     * input focus.
     *
     * @param attrs The window layout parameters to be modified.  These values
     * are modified in-place.
     */
    public void adjustWindowParamsLw(WindowState win, WindowManager.LayoutParams attrs,
            int callingPid, int callingUid) {

        final boolean isScreenDecor = (attrs.privateFlags & PRIVATE_FLAG_IS_SCREEN_DECOR) != 0;
        if (mScreenDecorWindows.contains(win)) {
            if (!isScreenDecor) {
                // No longer has the flag set, so remove from the set.
                mScreenDecorWindows.remove(win);
            }
        } else if (isScreenDecor && hasStatusBarServicePermission(callingPid, callingUid)) {
            mScreenDecorWindows.add(win);
        }

        switch (attrs.type) {
            case TYPE_SYSTEM_OVERLAY:
            case TYPE_SECURE_SYSTEM_OVERLAY:
                // These types of windows can't receive input events.
                attrs.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                attrs.flags &= ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
                break;
            case TYPE_WALLPAPER:
                // Dreams and wallpapers don't have an app window token and can thus not be
                // letterboxed. Hence always let them extend under the cutout.
                attrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                break;
            case TYPE_NOTIFICATION_SHADE:
                // If the Keyguard is in a hidden state (occluded by another window), we force to
                // remove the wallpaper and keyguard flag so that any change in-flight after setting
                // the keyguard as occluded wouldn't set these flags again.
                // See {@link #processKeyguardSetHiddenResultLw}.
                if (mService.mPolicy.isKeyguardOccluded()) {
                    attrs.flags &= ~WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
                }
                break;

            case TYPE_TOAST:
                // While apps should use the dedicated toast APIs to add such windows
                // it possible legacy apps to add the window directly. Therefore, we
                // make windows added directly by the app behave as a toast as much
                // as possible in terms of timeout and animation.
                if (attrs.hideTimeoutMilliseconds < 0
                        || attrs.hideTimeoutMilliseconds > TOAST_WINDOW_TIMEOUT) {
                    attrs.hideTimeoutMilliseconds = TOAST_WINDOW_TIMEOUT;
                }
                // Accessibility users may need longer timeout duration. This api compares
                // original timeout with user's preference and return longer one. It returns
                // original timeout if there's no preference.
                attrs.hideTimeoutMilliseconds = mAccessibilityManager.getRecommendedTimeoutMillis(
                        (int) attrs.hideTimeoutMilliseconds,
                        AccessibilityManager.FLAG_CONTENT_TEXT);
                // Toasts can't be clickable
                attrs.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                break;

            case TYPE_BASE_APPLICATION:

                // A non-translucent main app window isn't allowed to fit insets, as it would create
                // a hole on the display!
                if (attrs.isFullscreen() && win.mActivityRecord != null
                        && win.mActivityRecord.fillsParent()
                        && (win.mAttrs.privateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) != 0
                        && attrs.getFitInsetsTypes() != 0) {
                    throw new RuntimeException("Illegal attributes: Main activity window that isn't"
                            + " translucent trying to fit insets: "
                            + attrs.getFitInsetsTypes()
                            + " attrs=" + attrs);
                }
                break;
        }

        // Check if alternate bars positions were updated.
        if (mStatusBarAlt == win) {
            mStatusBarAltPosition = getAltBarPosition(attrs);
        }
        if (mNavigationBarAlt == win) {
            mNavigationBarAltPosition = getAltBarPosition(attrs);
        }
        if (mClimateBarAlt == win) {
            mClimateBarAltPosition = getAltBarPosition(attrs);
        }
        if (mExtraNavBarAlt == win) {
            mExtraNavBarAltPosition = getAltBarPosition(attrs);
        }
    }

    /**
     * Check if a window can be added to the system.
     *
     * Currently enforces that two window types are singletons per display:
     * <ul>
     * <li>{@link WindowManager.LayoutParams#TYPE_STATUS_BAR}</li>
     * <li>{@link WindowManager.LayoutParams#TYPE_NOTIFICATION_SHADE}</li>
     * <li>{@link WindowManager.LayoutParams#TYPE_NAVIGATION_BAR}</li>
     * </ul>
     *
     * @param attrs Information about the window to be added.
     *
     * @return If ok, WindowManagerImpl.ADD_OKAY.  If too many singletons,
     * WindowManagerImpl.ADD_MULTIPLE_SINGLETON
     */
    int validateAddingWindowLw(WindowManager.LayoutParams attrs, int callingPid, int callingUid) {
        if ((attrs.privateFlags & PRIVATE_FLAG_IS_SCREEN_DECOR) != 0) {
            mContext.enforcePermission(
                    android.Manifest.permission.STATUS_BAR_SERVICE, callingPid, callingUid,
                    "DisplayPolicy");
        }
        if ((attrs.privateFlags & PRIVATE_FLAG_TRUSTED_OVERLAY) != 0) {
            mContext.enforcePermission(
                    android.Manifest.permission.INTERNAL_SYSTEM_WINDOW, callingPid, callingUid,
                    "DisplayPolicy");
        }
        if ((attrs.privateFlags & PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP) != 0) {
            ActivityTaskManagerService.enforceTaskPermission("DisplayPolicy");
        }

        switch (attrs.type) {
            case TYPE_STATUS_BAR:
                mContext.enforcePermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE, callingPid, callingUid,
                        "DisplayPolicy");
                if ((mStatusBar != null && mStatusBar.isAlive())
                        || (mStatusBarAlt != null && mStatusBarAlt.isAlive())) {
                    return WindowManagerGlobal.ADD_MULTIPLE_SINGLETON;
                }
                break;
            case TYPE_NOTIFICATION_SHADE:
                mContext.enforcePermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE, callingPid, callingUid,
                        "DisplayPolicy");
                if (mNotificationShade != null) {
                    if (mNotificationShade.isAlive()) {
                        return WindowManagerGlobal.ADD_MULTIPLE_SINGLETON;
                    }
                }
                break;
            case TYPE_NAVIGATION_BAR:
                mContext.enforcePermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE, callingPid, callingUid,
                        "DisplayPolicy");
                if ((mNavigationBar != null && mNavigationBar.isAlive())
                        || (mNavigationBarAlt != null && mNavigationBarAlt.isAlive())) {
                    return WindowManagerGlobal.ADD_MULTIPLE_SINGLETON;
                }
                break;
            case TYPE_NAVIGATION_BAR_PANEL:
                // Check for permission if the caller is not the recents component.
                if (!mService.mAtmInternal.isCallerRecents(callingUid)) {
                    mContext.enforcePermission(
                            android.Manifest.permission.STATUS_BAR_SERVICE, callingPid, callingUid,
                            "DisplayPolicy");
                }
                break;
            case TYPE_STATUS_BAR_ADDITIONAL:
            case TYPE_STATUS_BAR_SUB_PANEL:
            case TYPE_VOICE_INTERACTION_STARTING:
                mContext.enforcePermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE, callingPid, callingUid,
                        "DisplayPolicy");
                break;
            case TYPE_STATUS_BAR_PANEL:
                return WindowManagerGlobal.ADD_INVALID_TYPE;
        }

        if (attrs.providesInsetsTypes != null) {
            // Recents component is allowed to add inset types.
            if (!mService.mAtmInternal.isCallerRecents(callingUid)) {
                mContext.enforcePermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE, callingPid, callingUid,
                        "DisplayPolicy");
            }
            enforceSingleInsetsTypeCorrespondingToWindowType(attrs.providesInsetsTypes);

            for (@InternalInsetsType int insetType : attrs.providesInsetsTypes) {
                switch (insetType) {
                    case ITYPE_STATUS_BAR:
                        if ((mStatusBar != null && mStatusBar.isAlive())
                                || (mStatusBarAlt != null && mStatusBarAlt.isAlive())) {
                            return WindowManagerGlobal.ADD_MULTIPLE_SINGLETON;
                        }
                        break;
                    case ITYPE_NAVIGATION_BAR:
                        if ((mNavigationBar != null && mNavigationBar.isAlive())
                                || (mNavigationBarAlt != null && mNavigationBarAlt.isAlive())) {
                            return WindowManagerGlobal.ADD_MULTIPLE_SINGLETON;
                        }
                        break;
                    case ITYPE_CLIMATE_BAR:
                        if (mClimateBarAlt != null && mClimateBarAlt.isAlive()) {
                            return WindowManagerGlobal.ADD_MULTIPLE_SINGLETON;
                        }
                        break;
                    case ITYPE_EXTRA_NAVIGATION_BAR:
                        if (mExtraNavBarAlt != null && mExtraNavBarAlt.isAlive()) {
                            return WindowManagerGlobal.ADD_MULTIPLE_SINGLETON;
                        }
                        break;
                }
            }
        }
        return ADD_OKAY;
    }

    private void getRotatedWindowBounds(DisplayFrames displayFrames, WindowState windowState,
            Rect outBounds) {
        outBounds.set(windowState.getBounds());

        int windowRotation = windowState.getWindowConfiguration().getRotation();
        if (windowRotation == displayFrames.mRotation) {
            return;
        }

        // Get displayFrames bounds
        sTmpDisplayFrameBounds.set(0, 0, displayFrames.mDisplayWidth, displayFrames.mDisplayHeight);
        // Rotate the WindowState's bounds based on the displayFrames rotation
        mDisplayContent.rotateBounds(sTmpDisplayFrameBounds, windowRotation,
                displayFrames.mRotation, outBounds);
    }

    /**
     * Called when a window is being added to the system.  Must not throw an exception.
     *
     * @param win The window being added.
     * @param attrs Information about the window to be added.
     */
    void addWindowLw(WindowState win, WindowManager.LayoutParams attrs) {
        if ((attrs.privateFlags & PRIVATE_FLAG_IS_SCREEN_DECOR) != 0) {
            mScreenDecorWindows.add(win);
        }

        switch (attrs.type) {
            case TYPE_NOTIFICATION_SHADE:
                mNotificationShade = win;
                if (mDisplayContent.isDefaultDisplay) {
                    mService.mPolicy.setKeyguardCandidateLw(win);
                }
                break;
            case TYPE_STATUS_BAR:
                mStatusBar = win;
                final TriConsumer<DisplayFrames, WindowState, Rect> frameProvider =
                        (displayFrames, windowState, rect) -> {
                            rect.bottom = rect.top + getStatusBarHeight(displayFrames);
                        };
                mDisplayContent.setInsetProvider(ITYPE_STATUS_BAR, win, frameProvider);
                mDisplayContent.setInsetProvider(ITYPE_TOP_GESTURES, win, frameProvider);
                mDisplayContent.setInsetProvider(ITYPE_TOP_TAPPABLE_ELEMENT, win, frameProvider);
                break;
            case TYPE_NAVIGATION_BAR:
                mNavigationBar = win;
                mDisplayContent.setInsetProvider(ITYPE_NAVIGATION_BAR, win,
                        (displayFrames, windowState, inOutFrame) -> {

                            // In Gesture Nav, navigation bar frame is larger than frame to
                            // calculate inset.
                            if (navigationBarPosition(displayFrames.mDisplayWidth,
                                    displayFrames.mDisplayHeight,
                                    displayFrames.mRotation) == NAV_BAR_BOTTOM
                                    && !mNavButtonForcedVisible) {
                                sTmpRect.set(inOutFrame);
                                sTmpRect.intersectUnchecked(displayFrames.mDisplayCutoutSafe);
                                inOutFrame.top = sTmpRect.bottom
                                        - getNavigationBarHeight(displayFrames.mRotation,
                                        mDisplayContent.getConfiguration().uiMode);
                            }
                        },

                        // For IME we use regular frame.
                        (displayFrames, windowState, inOutFrame) ->
                                inOutFrame.set(windowState.getFrame()));

                mDisplayContent.setInsetProvider(ITYPE_BOTTOM_GESTURES, win,
                        (displayFrames, windowState, inOutFrame) -> {
                            inOutFrame.top -= mBottomGestureAdditionalInset;
                        });
                mDisplayContent.setInsetProvider(ITYPE_LEFT_GESTURES, win,
                        (displayFrames, windowState, inOutFrame) -> {
                            final int leftSafeInset =
                                    Math.max(displayFrames.mDisplayCutoutSafe.left, 0);
                            inOutFrame.left = 0;
                            inOutFrame.top = 0;
                            inOutFrame.bottom = displayFrames.mDisplayHeight;
                            inOutFrame.right = leftSafeInset + mLeftGestureInset;
                        });
                mDisplayContent.setInsetProvider(ITYPE_RIGHT_GESTURES, win,
                        (displayFrames, windowState, inOutFrame) -> {
                            final int rightSafeInset =
                                    Math.min(displayFrames.mDisplayCutoutSafe.right,
                                            displayFrames.mUnrestricted.right);
                            inOutFrame.left = rightSafeInset - mRightGestureInset;
                            inOutFrame.top = 0;
                            inOutFrame.bottom = displayFrames.mDisplayHeight;
                            inOutFrame.right = displayFrames.mDisplayWidth;
                        });
                mDisplayContent.setInsetProvider(ITYPE_BOTTOM_TAPPABLE_ELEMENT, win,
                        (displayFrames, windowState, inOutFrame) -> {
                            if ((windowState.getAttrs().flags & FLAG_NOT_TOUCHABLE) != 0
                                    || mNavigationBarLetsThroughTaps) {
                                inOutFrame.setEmpty();
                            }
                        });
                if (DEBUG_LAYOUT) Slog.i(TAG, "NAVIGATION BAR: " + mNavigationBar);
                break;
            default:
                if (attrs.providesInsetsTypes != null) {
                    for (@InternalInsetsType int insetsType : attrs.providesInsetsTypes) {
                        switch (insetsType) {
                            case ITYPE_STATUS_BAR:
                                mStatusBarAlt = win;
                                mStatusBarAltPosition = getAltBarPosition(attrs);
                                break;
                            case ITYPE_NAVIGATION_BAR:
                                mNavigationBarAlt = win;
                                mNavigationBarAltPosition = getAltBarPosition(attrs);
                                break;
                            case ITYPE_CLIMATE_BAR:
                                mClimateBarAlt = win;
                                mClimateBarAltPosition = getAltBarPosition(attrs);
                                break;
                            case ITYPE_EXTRA_NAVIGATION_BAR:
                                mExtraNavBarAlt = win;
                                mExtraNavBarAltPosition = getAltBarPosition(attrs);
                                break;
                        }
                        mDisplayContent.setInsetProvider(insetsType, win, null);
                    }
                }
                break;
        }
    }

    @WindowManagerPolicy.AltBarPosition
    private int getAltBarPosition(WindowManager.LayoutParams params) {
        switch (params.gravity) {
            case Gravity.LEFT:
                return ALT_BAR_LEFT;
            case Gravity.RIGHT:
                return ALT_BAR_RIGHT;
            case Gravity.BOTTOM:
                return ALT_BAR_BOTTOM;
            case Gravity.TOP:
                return ALT_BAR_TOP;
            default:
                return ALT_BAR_UNKNOWN;
        }
    }

    TriConsumer<DisplayFrames, WindowState, Rect> getImeSourceFrameProvider() {
        return (displayFrames, windowState, inOutFrame) -> {
            if (mNavigationBar != null && navigationBarPosition(displayFrames.mDisplayWidth,
                    displayFrames.mDisplayHeight,
                    displayFrames.mRotation) == NAV_BAR_BOTTOM) {
                // In gesture navigation, nav bar frame is larger than frame to calculate insets.
                // IME should not provide frame which is smaller than the nav bar frame. Otherwise,
                // nav bar might be overlapped with the content of the client when IME is shown.
                sTmpRect.set(inOutFrame);
                sTmpRect.intersectUnchecked(mNavigationBar.getFrame());
                inOutFrame.inset(windowState.mGivenContentInsets);
                inOutFrame.union(sTmpRect);
            } else {
                inOutFrame.inset(windowState.mGivenContentInsets);
            }
        };
    }

    private static void enforceSingleInsetsTypeCorrespondingToWindowType(int[] insetsTypes) {
        int count = 0;
        for (int insetsType : insetsTypes) {
            switch (insetsType) {
                case ITYPE_NAVIGATION_BAR:
                case ITYPE_STATUS_BAR:
                case ITYPE_CLIMATE_BAR:
                case ITYPE_EXTRA_NAVIGATION_BAR:
                case ITYPE_CAPTION_BAR:
                    if (++count > 1) {
                        throw new IllegalArgumentException(
                                "Multiple InsetsTypes corresponding to Window type");
                    }
            }
        }
    }

    /**
     * Called when a window is being removed from a window manager.  Must not
     * throw an exception -- clean up as much as possible.
     *
     * @param win The window being removed.
     */
    void removeWindowLw(WindowState win) {
        if (mStatusBar == win || mStatusBarAlt == win) {
            mStatusBar = null;
            mStatusBarAlt = null;
            mDisplayContent.setInsetProvider(ITYPE_STATUS_BAR, null, null);
        } else if (mNavigationBar == win || mNavigationBarAlt == win) {
            mNavigationBar = null;
            mNavigationBarAlt = null;
            mDisplayContent.setInsetProvider(ITYPE_NAVIGATION_BAR, null, null);
        } else if (mNotificationShade == win) {
            mNotificationShade = null;
            if (mDisplayContent.isDefaultDisplay) {
                mService.mPolicy.setKeyguardCandidateLw(null);
            }
        } else if (mClimateBarAlt == win) {
            mClimateBarAlt = null;
            mDisplayContent.setInsetProvider(ITYPE_CLIMATE_BAR, null, null);
        } else if (mExtraNavBarAlt == win) {
            mExtraNavBarAlt = null;
            mDisplayContent.setInsetProvider(ITYPE_EXTRA_NAVIGATION_BAR, null, null);
        }
        if (mLastFocusedWindow == win) {
            mLastFocusedWindow = null;
        }
        mScreenDecorWindows.remove(win);
    }

    private int getStatusBarHeight(DisplayFrames displayFrames) {
        return Math.max(mStatusBarHeightForRotation[displayFrames.mRotation],
                displayFrames.mDisplayCutoutSafe.top);
    }

    @VisibleForTesting
    BarController getStatusBarController() {
        return mStatusBarController;
    }

    WindowState getStatusBar() {
        return mStatusBar != null ? mStatusBar : mStatusBarAlt;
    }

    WindowState getNotificationShade() {
        return mNotificationShade;
    }

    WindowState getNavigationBar() {
        return mNavigationBar != null ? mNavigationBar : mNavigationBarAlt;
    }

    /**
     * Control the animation to run when a window's state changes.  Return a positive number to
     * force the animation to a specific resource ID, {@link #ANIMATION_STYLEABLE} to use the
     * style resource defining the animation, or {@link #ANIMATION_NONE} for no animation.
     *
     * @param win The window that is changing.
     * @param transit What is happening to the window:
     *                {@link com.android.server.policy.WindowManagerPolicy#TRANSIT_ENTER},
     *                {@link com.android.server.policy.WindowManagerPolicy#TRANSIT_EXIT},
     *                {@link com.android.server.policy.WindowManagerPolicy#TRANSIT_SHOW}, or
     *                {@link com.android.server.policy.WindowManagerPolicy#TRANSIT_HIDE}.
     *
     * @return Resource ID of the actual animation to use, or {@link #ANIMATION_NONE} for none.
     */
    int selectAnimation(WindowState win, int transit) {
        if (DEBUG_ANIM) Slog.i(TAG, "selectAnimation in " + win
                + ": transit=" + transit);
        if (win == mStatusBar) {
            if (transit == TRANSIT_EXIT
                    || transit == TRANSIT_HIDE) {
                return R.anim.dock_top_exit;
            } else if (transit == TRANSIT_ENTER
                    || transit == TRANSIT_SHOW) {
                return R.anim.dock_top_enter;
            }
        } else if (win == mNavigationBar) {
            if (win.getAttrs().windowAnimations != 0) {
                return ANIMATION_STYLEABLE;
            }
            // This can be on either the bottom or the right or the left.
            if (mNavigationBarPosition == NAV_BAR_BOTTOM) {
                if (transit == TRANSIT_EXIT
                        || transit == TRANSIT_HIDE) {
                    if (mService.mPolicy.isKeyguardShowingAndNotOccluded()) {
                        return R.anim.dock_bottom_exit_keyguard;
                    } else {
                        return R.anim.dock_bottom_exit;
                    }
                } else if (transit == TRANSIT_ENTER
                        || transit == TRANSIT_SHOW) {
                    return R.anim.dock_bottom_enter;
                }
            } else if (mNavigationBarPosition == NAV_BAR_RIGHT) {
                if (transit == TRANSIT_EXIT
                        || transit == TRANSIT_HIDE) {
                    return R.anim.dock_right_exit;
                } else if (transit == TRANSIT_ENTER
                        || transit == TRANSIT_SHOW) {
                    return R.anim.dock_right_enter;
                }
            } else if (mNavigationBarPosition == NAV_BAR_LEFT) {
                if (transit == TRANSIT_EXIT
                        || transit == TRANSIT_HIDE) {
                    return R.anim.dock_left_exit;
                } else if (transit == TRANSIT_ENTER
                        || transit == TRANSIT_SHOW) {
                    return R.anim.dock_left_enter;
                }
            }
        } else if (win == mStatusBarAlt || win == mNavigationBarAlt || win == mClimateBarAlt
                || win == mExtraNavBarAlt) {
            if (win.getAttrs().windowAnimations != 0) {
                return ANIMATION_STYLEABLE;
            }

            int pos = (win == mStatusBarAlt) ? mStatusBarAltPosition : mNavigationBarAltPosition;

            boolean isExitOrHide = transit == TRANSIT_EXIT || transit == TRANSIT_HIDE;
            boolean isEnterOrShow = transit == TRANSIT_ENTER || transit == TRANSIT_SHOW;

            switch (pos) {
                case ALT_BAR_LEFT:
                    if (isExitOrHide) {
                        return R.anim.dock_left_exit;
                    } else if (isEnterOrShow) {
                        return R.anim.dock_left_enter;
                    }
                    break;
                case ALT_BAR_RIGHT:
                    if (isExitOrHide) {
                        return R.anim.dock_right_exit;
                    } else if (isEnterOrShow) {
                        return R.anim.dock_right_enter;
                    }
                    break;
                case ALT_BAR_BOTTOM:
                    if (isExitOrHide) {
                        return R.anim.dock_bottom_exit;
                    } else if (isEnterOrShow) {
                        return R.anim.dock_bottom_enter;
                    }
                    break;
                case ALT_BAR_TOP:
                    if (isExitOrHide) {
                        return R.anim.dock_top_exit;
                    } else if (isEnterOrShow) {
                        return R.anim.dock_top_enter;
                    }
                    break;
            }
        }

        if (transit == TRANSIT_PREVIEW_DONE) {
            if (win.hasAppShownWindows()) {
                if (win.isActivityTypeHome()) {
                    // Dismiss the starting window as soon as possible to avoid the crossfade out
                    // with old content because home is easier to have different UI states.
                    return ANIMATION_NONE;
                }
                if (DEBUG_ANIM) Slog.i(TAG, "**** STARTING EXIT");
                return R.anim.app_starting_exit;
            }
        }

        return ANIMATION_STYLEABLE;
    }

    /**
     * @return true if the system bars are forced to stay visible
     */
    public boolean areSystemBarsForcedShownLw(WindowState windowState) {
        return mForceShowSystemBars;
    }

    // TODO: Should probably be moved into DisplayFrames.
    /**
     * Return the layout hints for a newly added window. These values are computed on the
     * most recent layout, so they are not guaranteed to be correct.
     *
     * @param attrs The LayoutParams of the window.
     * @param windowToken The token of the window.
     * @param outFrame The frame of the window.
     * @param outDisplayCutout The area that has been cut away from the display.
     * @param outInsetsState The insets state of this display from the client's perspective.
     * @param localClient Whether the client is from the our process.
     * @return Whether to always consume the system bars.
     *         See {@link #areSystemBarsForcedShownLw(WindowState)}.
     */
    boolean getLayoutHint(LayoutParams attrs, WindowToken windowToken, Rect outFrame,
            DisplayCutout.ParcelableWrapper outDisplayCutout, InsetsState outInsetsState,
            boolean localClient) {
        final boolean isFixedRotationTransforming =
                windowToken != null && windowToken.isFixedRotationTransforming();
        final ActivityRecord activity = windowToken != null ? windowToken.asActivityRecord() : null;
        final Task task = activity != null ? activity.getTask() : null;
        final Rect taskBounds = isFixedRotationTransforming
                // Use token (activity) bounds if it is rotated because its task is not rotated.
                ? windowToken.getBounds()
                : (task != null ? task.getBounds() : null);
        final InsetsState state =
                mDisplayContent.getInsetsPolicy().getInsetsForWindowMetrics(attrs);
        computeWindowBounds(attrs, state, outFrame);
        if (taskBounds != null) {
            outFrame.intersect(taskBounds);
        }

        final int fl = attrs.flags;
        final int pfl = attrs.privateFlags;
        final boolean layoutInScreenAndInsetDecor = (fl & FLAG_LAYOUT_IN_SCREEN) != 0
                && (fl & FLAG_LAYOUT_INSET_DECOR) != 0;
        final boolean screenDecor = (pfl & PRIVATE_FLAG_IS_SCREEN_DECOR) != 0;
        final DisplayFrames displayFrames = isFixedRotationTransforming
                ? windowToken.getFixedRotationTransformDisplayFrames()
                : mDisplayContent.mDisplayFrames;
        if (layoutInScreenAndInsetDecor && !screenDecor) {
            outDisplayCutout.set(
                    displayFrames.mDisplayCutout.calculateRelativeTo(outFrame).getDisplayCutout());
        } else {
            outDisplayCutout.set(DisplayCutout.NO_CUTOUT);
        }

        final boolean inSizeCompatMode = WindowState.inSizeCompatMode(attrs, windowToken);
        outInsetsState.set(state, inSizeCompatMode || localClient);
        if (inSizeCompatMode) {
            final float compatScale = windowToken != null
                    ? windowToken.getSizeCompatScale()
                    : mDisplayContent.mCompatibleScreenScale;
            outInsetsState.scale(1f / compatScale);
        }
        return mForceShowSystemBars;
    }

    /**
     * Input handler used while nav bar is hidden.  Captures any touch on the screen,
     * to determine when the nav bar should be shown and prevent applications from
     * receiving those touches.
     */
    private final class HideNavInputEventReceiver extends InputEventReceiver {
        HideNavInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            try {
                if (event instanceof MotionEvent
                        && (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
                    final MotionEvent motionEvent = (MotionEvent) event;
                    if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                        // When the user taps down, we re-show the nav bar.
                        boolean changed = false;
                        synchronized (mLock) {
                            if (mInputConsumer == null) {
                                return;
                            }
                            showSystemBars();
                        }
                    }
                }
            } finally {
                finishInputEvent(event, false /* handled */);
            }
        }

        private void showSystemBars() {
            final InsetsSourceProvider provider = mDisplayContent.getInsetsStateController()
                    .peekSourceProvider(ITYPE_NAVIGATION_BAR);
            final InsetsControlTarget target =
                    provider != null ? provider.getControlTarget() : null;
            if (target != null) {
                target.showInsets(Type.systemBars(), false /* fromIme */);
            }
        }
    }

    private void simulateLayoutDecorWindow(WindowState win, DisplayFrames displayFrames,
            InsetsState insetsState, WindowFrames simulatedWindowFrames,
            SparseArray<Rect> contentFrames, Consumer<Rect> layout) {
        win.setSimulatedWindowFrames(simulatedWindowFrames);
        final Rect contentFrame = new Rect();
        try {
            layout.accept(contentFrame);
        } finally {
            win.setSimulatedWindowFrames(null);
        }
        contentFrames.put(win.mAttrs.type, contentFrame);
        mDisplayContent.getInsetsStateController().computeSimulatedState(insetsState, win,
                displayFrames, simulatedWindowFrames);
    }

    /**
     * Computes the frames of display (its logical size, rotation and cutout should already be set)
     * used to layout window. The result of display frames and insets state should be the same as
     * using {@link #beginLayoutLw}, but this method only changes the given display frames, insets
     * state and some temporal states. In other words, it doesn't change the window frames used to
     * show on screen.
     */
    void simulateLayoutDisplay(DisplayFrames displayFrames, InsetsState insetsState,
            SparseArray<Rect> barContentFrames) {
        displayFrames.onBeginLayout();
        updateInsetsStateForDisplayCutout(displayFrames, insetsState);
        insetsState.setDisplayFrame(displayFrames.mUnrestricted);
        final WindowFrames simulatedWindowFrames = new WindowFrames();
        if (mNavigationBar != null) {
            simulateLayoutDecorWindow(mNavigationBar, displayFrames, insetsState,
                    simulatedWindowFrames, barContentFrames,
                    contentFrame -> layoutNavigationBar(displayFrames,
                            mDisplayContent.getConfiguration().uiMode, mLastNavVisible,
                            mLastNavTranslucent, mLastNavAllowedHidden,
                            contentFrame));
        }
        if (mStatusBar != null) {
            simulateLayoutDecorWindow(mStatusBar, displayFrames, insetsState,
                    simulatedWindowFrames, barContentFrames,
                    contentFrame -> layoutStatusBar(displayFrames, mLastAppearance,
                            contentFrame));
        }
        layoutScreenDecorWindows(displayFrames, simulatedWindowFrames);
        postAdjustDisplayFrames(displayFrames);
    }

    /**
     * Called when layout of the windows is about to start.
     *
     * @param displayFrames frames of the display we are doing layout on.
     * @param uiMode The current uiMode in configuration.
     */
    public void beginLayoutLw(DisplayFrames displayFrames, int uiMode) {
        displayFrames.onBeginLayout();
        final InsetsState state = mDisplayContent.getInsetsStateController().getRawInsetsState();
        updateInsetsStateForDisplayCutout(displayFrames, state);
        state.setDisplayFrame(displayFrames.mUnrestricted);
        mSystemGestures.screenWidth = displayFrames.mUnrestricted.width();
        mSystemGestures.screenHeight = displayFrames.mUnrestricted.height();

        // For purposes of putting out fake window up to steal focus, we will
        // drive nav being hidden only by whether it is requested.
        final int appearance = mLastAppearance;
        final int behavior = mLastBehavior;
        final InsetsSourceProvider provider =
                mDisplayContent.getInsetsStateController().peekSourceProvider(ITYPE_NAVIGATION_BAR);
        boolean navVisible = provider != null ? provider.isClientVisible()
                : InsetsState.getDefaultVisibility(ITYPE_NAVIGATION_BAR);
        boolean navTranslucent = (appearance & APPEARANCE_OPAQUE_NAVIGATION_BARS) == 0;
        boolean immersive = (behavior & BEHAVIOR_SHOW_BARS_BY_SWIPE) != 0;
        boolean immersiveSticky = (behavior & BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) != 0;
        boolean navAllowedHidden = immersive || immersiveSticky;
        navTranslucent &= !immersiveSticky;  // transient trumps translucent

        updateHideNavInputEventReceiver();

        // For purposes of positioning and showing the nav bar, if we have decided that it can't
        // be hidden (because of the screen aspect ratio), then take that into account.
        navVisible |= !canHideNavigationBar();

        layoutNavigationBar(displayFrames, uiMode, navVisible,
                navTranslucent, navAllowedHidden, null /* simulatedContentFrame */);
        if (DEBUG_LAYOUT) Slog.i(TAG, "mDock rect:" + displayFrames.mDock);
        layoutStatusBar(displayFrames, appearance, null /* simulatedContentFrame */);
        layoutScreenDecorWindows(displayFrames, null /* simulatedFrames */);
        postAdjustDisplayFrames(displayFrames);
        mLastNavVisible = navVisible;
        mLastNavTranslucent = navTranslucent;
        mLastNavAllowedHidden = navAllowedHidden;
    }

    void updateHideNavInputEventReceiver() {
        final InsetsSourceProvider provider = mDisplayContent.getInsetsStateController()
                .peekSourceProvider(ITYPE_NAVIGATION_BAR);
        final InsetsControlTarget navControlTarget =
                provider != null ? provider.getControlTarget() : null;
        final WindowState navControllingWin =
                navControlTarget instanceof WindowState ? (WindowState) navControlTarget : null;
        final boolean navVisible = navControllingWin != null
                ? navControllingWin.getRequestedVisibility(ITYPE_NAVIGATION_BAR)
                : InsetsState.getDefaultVisibility(ITYPE_NAVIGATION_BAR);
        final boolean showBarsByTouch = navControllingWin != null
                && navControllingWin.mAttrs.insetsFlags.behavior == BEHAVIOR_SHOW_BARS_BY_TOUCH;
        // When the navigation bar isn't visible, we put up a fake input window to catch all
        // touch events. This way we can detect when the user presses anywhere to bring back the
        // nav bar and ensure the application doesn't see the event.
        if (navVisible || !showBarsByTouch) {
            if (mInputConsumer != null) {
                mInputConsumer.dismiss();
                mHandler.sendMessage(
                        mHandler.obtainMessage(MSG_DISPOSE_INPUT_CONSUMER, mInputConsumer));
                mInputConsumer = null;
                Slog.v(TAG, INPUT_CONSUMER_NAVIGATION + " dismissed.");
            }
        } else if (mInputConsumer == null && getStatusBar() != null && canHideNavigationBar()) {
            mInputConsumer = mDisplayContent.getInputMonitor().createInputConsumer(
                    mHandler.getLooper(),
                    INPUT_CONSUMER_NAVIGATION,
                    HideNavInputEventReceiver::new);
            Slog.v(TAG, INPUT_CONSUMER_NAVIGATION + " created.");
            // As long as mInputConsumer is active, hover events are not dispatched to the app
            // and the pointer icon is likely to become stale. Hide it to avoid confusion.
            InputManager.getInstance().setPointerIconType(PointerIcon.TYPE_NULL);
        }
    }

    private static void updateInsetsStateForDisplayCutout(DisplayFrames displayFrames,
            InsetsState state) {
        if (displayFrames.mDisplayCutout.getDisplayCutout().isEmpty()) {
            state.removeSource(ITYPE_LEFT_DISPLAY_CUTOUT);
            state.removeSource(ITYPE_TOP_DISPLAY_CUTOUT);
            state.removeSource(ITYPE_RIGHT_DISPLAY_CUTOUT);
            state.removeSource(ITYPE_BOTTOM_DISPLAY_CUTOUT);
            return;
        }
        final Rect u = displayFrames.mUnrestricted;
        final Rect s = displayFrames.mDisplayCutoutSafe;
        state.getSource(ITYPE_LEFT_DISPLAY_CUTOUT).setFrame(u.left, u.top, s.left, u.bottom);
        state.getSource(ITYPE_TOP_DISPLAY_CUTOUT).setFrame(u.left, u.top, u.right, s.top);
        state.getSource(ITYPE_RIGHT_DISPLAY_CUTOUT).setFrame(s.right, u.top, u.right, u.bottom);
        state.getSource(ITYPE_BOTTOM_DISPLAY_CUTOUT).setFrame(u.left, s.bottom, u.right, u.bottom);
    }

    /** Enforces the last layout policy for display frames. */
    private void postAdjustDisplayFrames(DisplayFrames displayFrames) {
        if (displayFrames.mDisplayCutoutSafe.top > displayFrames.mUnrestricted.top) {
            // Make sure that the zone we're avoiding for the cutout is at least as tall as the
            // status bar; otherwise fullscreen apps will end up cutting halfway into the status
            // bar.
            displayFrames.mDisplayCutoutSafe.top = Math.max(displayFrames.mDisplayCutoutSafe.top,
                    displayFrames.mStable.top);
        }

        // In case this is a virtual display, and the host display has insets that overlap this
        // virtual display, apply the insets of the overlapped area onto the current and content
        // frame of this virtual display. This let us layout windows in the virtual display as
        // expected when the window needs to avoid overlap with the system windows.
        // TODO: Generalize the forwarded insets, so that we can handle system windows other than
        // IME.
        displayFrames.mCurrent.inset(mForwardedInsets);
        displayFrames.mContent.inset(mForwardedInsets);
    }

    /**
     * Layout the decor windows with {@link #PRIVATE_FLAG_IS_SCREEN_DECOR}.
     *
     * @param displayFrames The display frames to be layouted.
     * @param simulatedFrames Non-null if the caller only needs the result of display frames (see
     *                        {@link WindowState#mSimulatedWindowFrames}).
     */
    private void layoutScreenDecorWindows(DisplayFrames displayFrames,
            WindowFrames simulatedFrames) {
        if (mScreenDecorWindows.isEmpty()) {
            return;
        }

        sTmpRect.setEmpty();
        final int displayId = displayFrames.mDisplayId;
        final Rect dockFrame = displayFrames.mDock;
        final int displayHeight = displayFrames.mDisplayHeight;
        final int displayWidth = displayFrames.mDisplayWidth;

        for (int i = mScreenDecorWindows.size() - 1; i >= 0; --i) {
            final WindowState w = mScreenDecorWindows.valueAt(i);
            if (w.getDisplayId() != displayId || !w.isVisible()) {
                // Skip if not on the same display or not visible.
                continue;
            }

            final boolean isSimulatedLayout = simulatedFrames != null;
            if (isSimulatedLayout) {
                w.setSimulatedWindowFrames(simulatedFrames);
            }
            getRotatedWindowBounds(displayFrames, w, sTmpScreenDecorFrame);
            final WindowFrames windowFrames = w.getLayoutingWindowFrames();
            windowFrames.setFrames(sTmpScreenDecorFrame /* parentFrame */,
                    sTmpScreenDecorFrame /* displayFrame */,
                    sTmpScreenDecorFrame /* contentFrame */,
                    sTmpScreenDecorFrame /* visibleFrame */, sTmpRect /* decorFrame */,
                    sTmpScreenDecorFrame /* stableFrame */);
            try {
                w.computeFrame(displayFrames);
            } finally {
                if (isSimulatedLayout) {
                    w.setSimulatedWindowFrames(null);
                }
            }
            final Rect frame = windowFrames.mFrame;

            if (frame.left <= 0 && frame.top <= 0) {
                // Docked at left or top.
                if (frame.bottom >= displayHeight) {
                    // Docked left.
                    dockFrame.left = Math.max(frame.right, dockFrame.left);
                } else if (frame.right >= displayWidth) {
                    // Docked top.
                    dockFrame.top = Math.max(frame.bottom, dockFrame.top);
                } else {
                    Slog.w(TAG, "layoutScreenDecorWindows: Ignoring decor win=" + w
                            + " not docked on left or top of display. frame=" + frame
                            + " displayWidth=" + displayWidth + " displayHeight=" + displayHeight);
                }
            } else if (frame.right >= displayWidth && frame.bottom >= displayHeight) {
                // Docked at right or bottom.
                if (frame.top <= 0) {
                    // Docked right.
                    dockFrame.right = Math.min(frame.left, dockFrame.right);
                } else if (frame.left <= 0) {
                    // Docked bottom.
                    dockFrame.bottom = Math.min(frame.top, dockFrame.bottom);
                } else {
                    Slog.w(TAG, "layoutScreenDecorWindows: Ignoring decor win=" + w
                            + " not docked on right or bottom" + " of display. frame=" + frame
                            + " displayWidth=" + displayWidth + " displayHeight=" + displayHeight);
                }
            } else {
                // Screen decor windows are required to be docked on one of the sides of the screen.
                Slog.w(TAG, "layoutScreenDecorWindows: Ignoring decor win=" + w
                        + " not docked on one of the sides of the display. frame=" + frame
                        + " displayWidth=" + displayWidth + " displayHeight=" + displayHeight);
            }
        }

        displayFrames.mRestricted.set(dockFrame);
        displayFrames.mCurrent.set(dockFrame);
        displayFrames.mVoiceContent.set(dockFrame);
        displayFrames.mSystem.set(dockFrame);
        displayFrames.mContent.set(dockFrame);
    }

    private void layoutStatusBar(DisplayFrames displayFrames, int appearance,
            Rect simulatedContentFrame) {
        // decide where the status bar goes ahead of time
        if (mStatusBar == null) {
            return;
        }
        // apply any status bar insets
        getRotatedWindowBounds(displayFrames, mStatusBar, sTmpStatusFrame);
        sTmpRect.setEmpty();
        final WindowFrames windowFrames = mStatusBar.getLayoutingWindowFrames();
        windowFrames.setFrames(sTmpStatusFrame /* parentFrame */,
                sTmpStatusFrame /* displayFrame */, sTmpStatusFrame /* contentFrame */,
                sTmpStatusFrame /* visibleFrame */, sTmpRect /* decorFrame */,
                sTmpStatusFrame /* stableFrame */);
        // Let the status bar determine its size.
        mStatusBar.computeFrame(displayFrames);

        // For layout, the status bar is always at the top with our fixed height.
        displayFrames.mStable.top = displayFrames.mUnrestricted.top
                + mStatusBarHeightForRotation[displayFrames.mRotation];
        // Make sure the status bar covers the entire cutout height
        displayFrames.mStable.top = Math.max(displayFrames.mStable.top,
                displayFrames.mDisplayCutoutSafe.top);

        // Tell the bar controller where the collapsed status bar content is.
        sTmpRect.set(windowFrames.mContentFrame);
        sTmpRect.intersect(displayFrames.mDisplayCutoutSafe);
        sTmpRect.top = windowFrames.mContentFrame.top; // Ignore top display cutout inset
        sTmpRect.bottom = displayFrames.mStable.top; // Use collapsed status bar size
        if (simulatedContentFrame != null) {
            simulatedContentFrame.set(sTmpRect);
        } else {
            mStatusBarController.setContentFrame(sTmpRect);
        }

        boolean statusBarTransient =
                mDisplayContent.getInsetsPolicy().isTransient(ITYPE_STATUS_BAR);
        boolean statusBarTranslucent = (appearance & APPEARANCE_OPAQUE_STATUS_BARS) == 0;

        // If the status bar is hidden, we don't want to cause windows behind it to scroll.
        if (mStatusBar.isVisible() && !statusBarTransient) {
            // Status bar may go away, so the screen area it occupies is available to apps but just
            // covering them when the status bar is visible.
            final Rect dockFrame = displayFrames.mDock;
            dockFrame.top = displayFrames.mStable.top;
            displayFrames.mContent.set(dockFrame);
            displayFrames.mVoiceContent.set(dockFrame);
            displayFrames.mCurrent.set(dockFrame);

            if (DEBUG_LAYOUT) Slog.v(TAG, "Status bar: " + String.format(
                    "dock=%s content=%s cur=%s", dockFrame.toString(),
                    displayFrames.mContent.toString(), displayFrames.mCurrent.toString()));

            if (!statusBarTranslucent && !mStatusBar.isAnimatingLw()) {

                // If the opaque status bar is currently requested to be visible, and not in the
                // process of animating on or off, then we can tell the app that it is covered by
                // it.
                displayFrames.mSystem.top = displayFrames.mStable.top;
            }
        }
    }

    private void layoutNavigationBar(DisplayFrames displayFrames, int uiMode, boolean navVisible,
            boolean navTranslucent, boolean navAllowedHidden, Rect simulatedContentFrame) {
        if (mNavigationBar == null) {
            return;
        }

        final Rect navigationFrame = sTmpNavFrame;
        boolean navBarTransient =
                mDisplayContent.getInsetsPolicy().isTransient(ITYPE_NAVIGATION_BAR);
        // Force the navigation bar to its appropriate place and size. We need to do this directly,
        // instead of relying on it to bubble up from the nav bar, because this needs to change
        // atomically with screen rotations.
        final int rotation = displayFrames.mRotation;
        final int displayHeight = displayFrames.mDisplayHeight;
        final int displayWidth = displayFrames.mDisplayWidth;
        final Rect dockFrame = displayFrames.mDock;
        final int navBarPosition = navigationBarPosition(displayWidth, displayHeight, rotation);

        getRotatedWindowBounds(displayFrames, mNavigationBar, navigationFrame);

        final Rect cutoutSafeUnrestricted = sTmpRect;
        cutoutSafeUnrestricted.set(displayFrames.mUnrestricted);
        cutoutSafeUnrestricted.intersectUnchecked(displayFrames.mDisplayCutoutSafe);

        if (navBarPosition == NAV_BAR_BOTTOM) {
            // It's a system nav bar or a portrait screen; nav bar goes on bottom.
            final int topNavBar = Math.min(cutoutSafeUnrestricted.bottom, navigationFrame.bottom)
                    - getNavigationBarFrameHeight(rotation, uiMode);
            final int top = mNavButtonForcedVisible ? topNavBar :
                    Math.min(cutoutSafeUnrestricted.bottom, navigationFrame.bottom)
                            - getNavigationBarHeight(rotation, uiMode);
            navigationFrame.top = topNavBar;
            displayFrames.mStable.bottom = displayFrames.mStableFullscreen.bottom = top;
            if (navVisible && !navBarTransient) {
                dockFrame.bottom = displayFrames.mRestricted.bottom = top;
            }
            if (navVisible && !navTranslucent && !navAllowedHidden
                    && !mNavigationBar.isAnimatingLw()) {
                // If the opaque nav bar is currently requested to be visible and not in the process
                // of animating on or off, then we can tell the app that it is covered by it.
                displayFrames.mSystem.bottom = top;
            }
        } else if (navBarPosition == NAV_BAR_RIGHT) {
            // Landscape screen; nav bar goes to the right.
            final int left = Math.min(cutoutSafeUnrestricted.right, navigationFrame.right)
                    - getNavigationBarWidth(rotation, uiMode);
            navigationFrame.left = left;
            displayFrames.mStable.right = displayFrames.mStableFullscreen.right = left;
            if (navVisible && !navBarTransient) {
                dockFrame.right = displayFrames.mRestricted.right = left;
            }
            if (navVisible && !navTranslucent && !navAllowedHidden
                    && !mNavigationBar.isAnimatingLw()) {
                // If the nav bar is currently requested to be visible, and not in the process of
                // animating on or off, then we can tell the app that it is covered by it.
                displayFrames.mSystem.right = left;
            }
        } else if (navBarPosition == NAV_BAR_LEFT) {
            // Seascape screen; nav bar goes to the left.
            final int right = Math.max(cutoutSafeUnrestricted.left, navigationFrame.left)
                    + getNavigationBarWidth(rotation, uiMode);
            navigationFrame.right = right;
            displayFrames.mStable.left = displayFrames.mStableFullscreen.left = right;
            if (navVisible && !navBarTransient) {
                dockFrame.left = displayFrames.mRestricted.left = right;
            }
            if (navVisible && !navTranslucent && !navAllowedHidden
                    && !mNavigationBar.isAnimatingLw()) {
                // If the nav bar is currently requested to be visible, and not in the process of
                // animating on or off, then we can tell the app that it is covered by it.
                displayFrames.mSystem.left = right;
            }
        }

        // Make sure the content and current rectangles are updated to account for the restrictions
        // from the navigation bar.
        displayFrames.mCurrent.set(dockFrame);
        displayFrames.mVoiceContent.set(dockFrame);
        displayFrames.mContent.set(dockFrame);
        // And compute the final frame.
        sTmpRect.setEmpty();
        final WindowFrames windowFrames = mNavigationBar.getLayoutingWindowFrames();
        windowFrames.setFrames(navigationFrame /* parentFrame */,
                navigationFrame /* displayFrame */,
                displayFrames.mDisplayCutoutSafe /* contentFrame */,
                navigationFrame /* visibleFrame */, sTmpRect /* decorFrame */,
                navigationFrame /* stableFrame */);
        mNavigationBar.computeFrame(displayFrames);
        if (simulatedContentFrame != null) {
            simulatedContentFrame.set(windowFrames.mContentFrame);
        } else {
            mNavigationBarPosition = navBarPosition;
            mNavigationBarController.setContentFrame(windowFrames.mContentFrame);
        }

        if (DEBUG_LAYOUT) Slog.i(TAG, "mNavigationBar frame: " + navigationFrame);
    }

    private boolean canReceiveInput(WindowState win) {
        boolean notFocusable =
                (win.getAttrs().flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0;
        boolean altFocusableIm =
                (win.getAttrs().flags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) != 0;
        boolean notFocusableForIm = notFocusable ^ altFocusableIm;
        return !notFocusableForIm;
    }

    private void computeWindowBounds(WindowManager.LayoutParams attrs, InsetsState state,
            Rect outBounds) {
        final @InsetsType int typesToFit = attrs.getFitInsetsTypes();
        final @InsetsSide int sidesToFit = attrs.getFitInsetsSides();
        final ArraySet<Integer> types = InsetsState.toInternalType(typesToFit);
        final Rect dfu = state.getDisplayFrame();
        Insets insets = Insets.of(0, 0, 0, 0);
        for (int i = types.size() - 1; i >= 0; i--) {
            final InsetsSource source = state.peekSource(types.valueAt(i));
            if (source == null) {
                continue;
            }
            insets = Insets.max(insets, source.calculateInsets(
                    dfu, attrs.isFitInsetsIgnoringVisibility()));
        }
        final int left = (sidesToFit & Side.LEFT) != 0 ? insets.left : 0;
        final int top = (sidesToFit & Side.TOP) != 0 ? insets.top : 0;
        final int right = (sidesToFit & Side.RIGHT) != 0 ? insets.right : 0;
        final int bottom = (sidesToFit & Side.BOTTOM) != 0 ? insets.bottom : 0;
        outBounds.set(dfu.left + left, dfu.top + top, dfu.right - right, dfu.bottom - bottom);
    }

    /**
     * Called for each window attached to the window manager as layout is proceeding. The
     * implementation of this function must take care of setting the window's frame, either here or
     * in finishLayout().
     *
     * @param win The window being positioned.
     * @param attached For sub-windows, the window it is attached to; this
     *                 window will already have had layoutWindow() called on it
     *                 so you can use its Rect.  Otherwise null.
     * @param displayFrames The display frames.
     */
    public void layoutWindowLw(WindowState win, WindowState attached, DisplayFrames displayFrames) {
        // We've already done the navigation bar, status bar, and all screen decor windows. If the
        // status bar can receive input, we need to layout it again to accommodate for the IME
        // window.
        if ((win == mStatusBar && !canReceiveInput(win)) || win == mNavigationBar
                || mScreenDecorWindows.contains(win)) {
            return;
        }
        final WindowManager.LayoutParams attrs = win.getAttrs();

        final int type = attrs.type;
        final int fl = attrs.flags;
        final int pfl = attrs.privateFlags;
        final int sim = attrs.softInputMode;

        displayFrames = win.getDisplayFrames(displayFrames);
        final WindowFrames windowFrames = win.getWindowFrames();

        sTmpLastParentFrame.set(windowFrames.mParentFrame);
        final Rect pf = windowFrames.mParentFrame;
        final Rect df = windowFrames.mDisplayFrame;
        final Rect cf = windowFrames.mContentFrame;
        final Rect vf = windowFrames.mVisibleFrame;
        final Rect dcf = windowFrames.mDecorFrame;
        final Rect sf = windowFrames.mStableFrame;
        dcf.setEmpty();
        windowFrames.setParentFrameWasClippedByDisplayCutout(false);

        final int adjust = sim & SOFT_INPUT_MASK_ADJUST;

        final boolean layoutInScreen = (fl & FLAG_LAYOUT_IN_SCREEN) == FLAG_LAYOUT_IN_SCREEN;
        final boolean layoutInsetDecor = (fl & FLAG_LAYOUT_INSET_DECOR) == FLAG_LAYOUT_INSET_DECOR;

        sf.set(displayFrames.mStable);

        final InsetsState state = mDisplayContent.getInsetsPolicy().getInsetsForWindow(win);
        computeWindowBounds(attrs, state, df);
        if (attached == null) {
            pf.set(df);
            if ((pfl & PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME) != 0) {
                final InsetsSource source = state.peekSource(ITYPE_IME);
                if (source != null) {
                    pf.inset(source.calculateInsets(pf, false /* ignoreVisibility */));
                }
            }
            vf.set(adjust != SOFT_INPUT_ADJUST_NOTHING
                    ? displayFrames.mCurrent : displayFrames.mDock);
        } else {
            pf.set((fl & FLAG_LAYOUT_IN_SCREEN) == 0 ? attached.getFrame() : df);
            vf.set(attached.getVisibleFrame());
        }
        cf.set(adjust != SOFT_INPUT_ADJUST_RESIZE
                ? displayFrames.mDock : displayFrames.mContent);
        dcf.set(displayFrames.mSystem);

        final int cutoutMode = attrs.layoutInDisplayCutoutMode;
        // Ensure that windows with a DEFAULT or NEVER display cutout mode are laid out in
        // the cutout safe zone.
        if (cutoutMode != LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
            final boolean attachedInParent = attached != null && !layoutInScreen;
            final boolean requestedFullscreen = !win.getRequestedVisibility(ITYPE_STATUS_BAR);
            final boolean requestedHideNavigation =
                    !win.getRequestedVisibility(ITYPE_NAVIGATION_BAR);

            // TYPE_BASE_APPLICATION windows are never considered floating here because they don't
            // get cropped / shifted to the displayFrame in WindowState.
            final boolean floatingInScreenWindow = !attrs.isFullscreen() && layoutInScreen
                    && type != TYPE_BASE_APPLICATION;
            final Rect displayCutoutSafeExceptMaybeBars = sTmpDisplayCutoutSafeExceptMaybeBarsRect;
            displayCutoutSafeExceptMaybeBars.set(displayFrames.mDisplayCutoutSafe);
            if (cutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                if (displayFrames.mDisplayWidth < displayFrames.mDisplayHeight) {
                    displayCutoutSafeExceptMaybeBars.top = Integer.MIN_VALUE;
                    displayCutoutSafeExceptMaybeBars.bottom = Integer.MAX_VALUE;
                } else {
                    displayCutoutSafeExceptMaybeBars.left = Integer.MIN_VALUE;
                    displayCutoutSafeExceptMaybeBars.right = Integer.MAX_VALUE;
                }
            }

            if (layoutInScreen && layoutInsetDecor && !requestedFullscreen
                    && (cutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    || cutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES)) {
                // At the top we have the status bar, so apps that are
                // LAYOUT_IN_SCREEN | LAYOUT_INSET_DECOR but not FULLSCREEN
                // already expect that there's an inset there and we don't need to exclude
                // the window from that area.
                displayCutoutSafeExceptMaybeBars.top = Integer.MIN_VALUE;
            }
            if (layoutInScreen && layoutInsetDecor && !requestedHideNavigation
                    && (cutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    || cutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES)) {
                // Same for the navigation bar.
                switch (mNavigationBarPosition) {
                    case NAV_BAR_BOTTOM:
                        displayCutoutSafeExceptMaybeBars.bottom = Integer.MAX_VALUE;
                        break;
                    case NAV_BAR_RIGHT:
                        displayCutoutSafeExceptMaybeBars.right = Integer.MAX_VALUE;
                        break;
                    case NAV_BAR_LEFT:
                        displayCutoutSafeExceptMaybeBars.left = Integer.MIN_VALUE;
                        break;
                }
            }
            if (type == TYPE_INPUT_METHOD && mNavigationBarPosition == NAV_BAR_BOTTOM) {
                // The IME can always extend under the bottom cutout if the navbar is there.
                displayCutoutSafeExceptMaybeBars.bottom = Integer.MAX_VALUE;
            }
            // Windows that are attached to a parent and laid out in said parent already avoid
            // the cutout according to that parent and don't need to be further constrained.
            // Floating IN_SCREEN windows get what they ask for and lay out in the full screen.
            // They will later be cropped or shifted using the displayFrame in WindowState,
            // which prevents overlap with the DisplayCutout.
            if (!attachedInParent && !floatingInScreenWindow) {
                getRotatedWindowBounds(displayFrames, win, sTmpRect);
                sTmpRect.set(pf);
                pf.intersectUnchecked(displayCutoutSafeExceptMaybeBars);
                windowFrames.setParentFrameWasClippedByDisplayCutout(!sTmpRect.equals(pf));
            }
            // Make sure that NO_LIMITS windows clipped to the display don't extend under the
            // cutout.
            df.intersectUnchecked(displayCutoutSafeExceptMaybeBars);
        }

        // Content should never appear in the cutout.
        cf.intersectUnchecked(displayFrames.mDisplayCutoutSafe);

        // TYPE_SYSTEM_ERROR is above the NavigationBar so it can't be allowed to extend over it.
        // Also, we don't allow windows in multi-window mode to extend out of the screen.
        if ((fl & FLAG_LAYOUT_NO_LIMITS) != 0 && type != TYPE_SYSTEM_ERROR
                && !win.inMultiWindowMode()) {
            df.left = df.top = -10000;
            df.right = df.bottom = 10000;
            if (type != TYPE_WALLPAPER) {
                cf.left = cf.top = vf.left = vf.top = -10000;
                cf.right = cf.bottom = vf.right = vf.bottom = 10000;
            }
        }

        if (DEBUG_LAYOUT) Slog.v(TAG, "Compute frame " + attrs.getTitle()
                + ": sim=#" + Integer.toHexString(sim)
                + " attach=" + attached + " type=" + type
                + String.format(" flags=0x%08x", fl)
                + " pf=" + pf.toShortString() + " df=" + df.toShortString()
                + " cf=" + cf.toShortString() + " vf=" + vf.toShortString()
                + " dcf=" + dcf.toShortString()
                + " sf=" + sf.toShortString());

        if (!sTmpLastParentFrame.equals(pf)) {
            windowFrames.setContentChanged(true);
        }

        win.computeFrame(displayFrames);
        // Dock windows carve out the bottom of the screen, so normal windows
        // can't appear underneath them.
        if (type == TYPE_INPUT_METHOD && win.isVisible() && !win.mGivenInsetsPending) {
            offsetInputMethodWindowLw(win, displayFrames);
        }
        if (type == TYPE_VOICE_INTERACTION && win.isVisible() && !win.mGivenInsetsPending) {
            offsetVoiceInputWindowLw(win, displayFrames);
        }
    }

    private void offsetInputMethodWindowLw(WindowState win, DisplayFrames displayFrames) {
        final int rotation = displayFrames.mRotation;
        final int navBarPosition = navigationBarPosition(displayFrames.mDisplayWidth,
                displayFrames.mDisplayHeight, rotation);

        int top = Math.max(win.getDisplayFrame().top, win.getContentFrame().top);
        top += win.mGivenContentInsets.top;
        displayFrames.mContent.bottom = Math.min(displayFrames.mContent.bottom, top);
        if (navBarPosition == NAV_BAR_BOTTOM) {
            // Always account for the nav bar frame height on the bottom since in all navigation
            // modes we make room to show the dismiss-ime button, even if the IME does not report
            // insets (ie. when floating)
            final int uimode = mService.mPolicy.getUiMode();
            final int navFrameHeight = getNavigationBarFrameHeight(rotation, uimode);
            displayFrames.mContent.bottom = Math.min(displayFrames.mContent.bottom,
                    displayFrames.mUnrestricted.bottom - navFrameHeight);
        }
        displayFrames.mVoiceContent.bottom = Math.min(displayFrames.mVoiceContent.bottom, top);
        top = win.getVisibleFrame().top;
        top += win.mGivenVisibleInsets.top;
        displayFrames.mCurrent.bottom = Math.min(displayFrames.mCurrent.bottom, top);
        if (DEBUG_LAYOUT) Slog.v(TAG, "Input method: mDockBottom="
                + displayFrames.mDock.bottom + " mContentBottom="
                + displayFrames.mContent.bottom + " mCurBottom=" + displayFrames.mCurrent.bottom);
    }

    private void offsetVoiceInputWindowLw(WindowState win, DisplayFrames displayFrames) {
        int top = Math.max(win.getDisplayFrame().top, win.getContentFrame().top);
        top += win.mGivenContentInsets.top;
        displayFrames.mVoiceContent.bottom = Math.min(displayFrames.mVoiceContent.bottom, top);
    }

    WindowState getTopFullscreenOpaqueWindow() {
        return mTopFullscreenOpaqueWindowState;
    }

    boolean isTopLayoutFullscreen() {
        return mTopIsFullscreen;
    }

    /**
     * Called following layout of all windows before each window has policy applied.
     */
    public void beginPostLayoutPolicyLw() {
        mTopFullscreenOpaqueWindowState = null;
        mTopFullscreenOpaqueOrDimmingWindowState = null;
        mTopDockedOpaqueWindowState = null;
        mTopDockedOpaqueOrDimmingWindowState = null;
        mForceStatusBar = false;
        mForcingShowNavBar = false;
        mForcingShowNavBarLayer = -1;

        mAllowLockscreenWhenOn = false;
        mShowingDream = false;
        mIsFreeformWindowOverlappingWithNavBar = false;
    }

    /**
     * Called following layout of all window to apply policy to each window.
     *
     * @param win The window being positioned.
     * @param attrs The LayoutParams of the window.
     * @param attached For sub-windows, the window it is attached to. Otherwise null.
     */
    public void applyPostLayoutPolicyLw(WindowState win, WindowManager.LayoutParams attrs,
            WindowState attached, WindowState imeTarget) {
        final boolean affectsSystemUi = win.canAffectSystemUiFlags();
        if (DEBUG_LAYOUT) Slog.i(TAG, "Win " + win + ": affectsSystemUi=" + affectsSystemUi);
        applyKeyguardPolicy(win, imeTarget);
        final int fl = attrs.flags;
        if (mTopFullscreenOpaqueWindowState == null && affectsSystemUi
                && attrs.type == TYPE_INPUT_METHOD) {
            mForcingShowNavBar = true;
            mForcingShowNavBarLayer = win.getSurfaceLayer();
        }

        boolean appWindow = attrs.type >= FIRST_APPLICATION_WINDOW
                && attrs.type < FIRST_SYSTEM_WINDOW;
        final int windowingMode = win.getWindowingMode();
        final boolean inFullScreenOrSplitScreenSecondaryWindowingMode =
                windowingMode == WINDOWING_MODE_FULLSCREEN
                        || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
        if (mTopFullscreenOpaqueWindowState == null && affectsSystemUi) {
            if ((fl & FLAG_FORCE_NOT_FULLSCREEN) != 0) {
                mForceStatusBar = true;
            }
            if (win.isDreamWindow()) {
                // If the lockscreen was showing when the dream started then wait
                // for the dream to draw before hiding the lockscreen.
                if (!mDreamingLockscreen || (win.isVisible() && win.hasDrawn())) {
                    mShowingDream = true;
                    appWindow = true;
                }
            }

            // For app windows that are not attached, we decide if all windows in the app they
            // represent should be hidden or if we should hide the lockscreen. For attached app
            // windows we defer the decision to the window it is attached to.
            if (appWindow && attached == null) {
                if (attrs.isFullscreen() && inFullScreenOrSplitScreenSecondaryWindowingMode) {
                    if (DEBUG_LAYOUT) Slog.v(TAG, "Fullscreen window: " + win);
                    mTopFullscreenOpaqueWindowState = win;
                    if (mTopFullscreenOpaqueOrDimmingWindowState == null) {
                        mTopFullscreenOpaqueOrDimmingWindowState = win;
                    }
                    if ((fl & FLAG_ALLOW_LOCK_WHILE_SCREEN_ON) != 0) {
                        mAllowLockscreenWhenOn = true;
                    }
                }
            }
        }

        // Voice interaction overrides both top fullscreen and top docked.
        if (affectsSystemUi && attrs.type == TYPE_VOICE_INTERACTION) {
            if (mTopFullscreenOpaqueWindowState == null) {
                mTopFullscreenOpaqueWindowState = win;
                if (mTopFullscreenOpaqueOrDimmingWindowState == null) {
                    mTopFullscreenOpaqueOrDimmingWindowState = win;
                }
            }
            if (mTopDockedOpaqueWindowState == null) {
                mTopDockedOpaqueWindowState = win;
                if (mTopDockedOpaqueOrDimmingWindowState == null) {
                    mTopDockedOpaqueOrDimmingWindowState = win;
                }
            }
        }

        // Keep track of the window if it's dimming but not necessarily fullscreen.
        if (mTopFullscreenOpaqueOrDimmingWindowState == null && affectsSystemUi
                && win.isDimming() && inFullScreenOrSplitScreenSecondaryWindowingMode) {
            mTopFullscreenOpaqueOrDimmingWindowState = win;
        }

        // We need to keep track of the top "fullscreen" opaque window for the docked stack
        // separately, because both the "real fullscreen" opaque window and the one for the docked
        // stack can control View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.
        if (mTopDockedOpaqueWindowState == null && affectsSystemUi && appWindow && attached == null
                && attrs.isFullscreen() && windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            mTopDockedOpaqueWindowState = win;
            if (mTopDockedOpaqueOrDimmingWindowState == null) {
                mTopDockedOpaqueOrDimmingWindowState = win;
            }
        }

        // Check if the freeform window overlaps with the navigation bar area.
        final WindowState navBarWin = hasNavigationBar() ? mNavigationBar : null;
        if (!mIsFreeformWindowOverlappingWithNavBar && win.inFreeformWindowingMode()
                && isOverlappingWithNavBar(win, navBarWin)) {
            mIsFreeformWindowOverlappingWithNavBar = true;
        }

        // Also keep track of any windows that are dimming but not necessarily fullscreen in the
        // docked stack.
        if (mTopDockedOpaqueOrDimmingWindowState == null && affectsSystemUi && win.isDimming()
                && windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            mTopDockedOpaqueOrDimmingWindowState = win;
        }
    }

    /**
     * Called following layout of all windows and after policy has been applied
     * to each window. If in this function you do
     * something that may have modified the animation state of another window,
     * be sure to return non-zero in order to perform another pass through layout.
     *
     * @return Return any bit set of
     *         {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_LAYOUT},
     *         {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_CONFIG},
     *         {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_WALLPAPER}, or
     *         {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_ANIM}.
     */
    public int finishPostLayoutPolicyLw() {
        int changes = 0;
        boolean topIsFullscreen = false;

        // If we are not currently showing a dream then remember the current
        // lockscreen state.  We will use this to determine whether the dream
        // started while the lockscreen was showing and remember this state
        // while the dream is showing.
        if (!mShowingDream) {
            mDreamingLockscreen = mService.mPolicy.isKeyguardShowingAndNotOccluded();
        }

        if (getStatusBar() != null) {
            if (DEBUG_LAYOUT) Slog.i(TAG, "force=" + mForceStatusBar
                    + " top=" + mTopFullscreenOpaqueWindowState);
            final boolean forceShowStatusBar = (getStatusBar().getAttrs().privateFlags
                    & PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR) != 0;

            boolean topAppHidesStatusBar = topAppHidesStatusBar();
            if (mForceStatusBar || forceShowStatusBar) {
                if (DEBUG_LAYOUT) Slog.v(TAG, "Showing status bar: forced");
                // Maintain fullscreen layout until incoming animation is complete.
                topIsFullscreen = mTopIsFullscreen && mStatusBar.isAnimatingLw();
            } else if (mTopFullscreenOpaqueWindowState != null) {
                topIsFullscreen = topAppHidesStatusBar;
                // The subtle difference between the window for mTopFullscreenOpaqueWindowState
                // and mTopIsFullscreen is that mTopIsFullscreen is set only if the window
                // requests to hide the status bar.  Not sure if there is another way that to be the
                // case though.
                if (!topIsFullscreen || mDisplayContent.getDefaultTaskDisplayArea()
                        .isStackVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)) {
                    topAppHidesStatusBar = false;
                }
            }
            StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
            if (statusBar != null) {
                statusBar.setTopAppHidesStatusBar(topAppHidesStatusBar);
            }
        }

        if (mTopIsFullscreen != topIsFullscreen) {
            if (!topIsFullscreen) {
                // Force another layout when status bar becomes fully shown.
                changes |= FINISH_LAYOUT_REDO_LAYOUT;
            }
            mTopIsFullscreen = topIsFullscreen;
        }

        if (updateSystemUiVisibilityLw()) {
            // If the navigation bar has been hidden or shown, we need to do another
            // layout pass to update that window.
            changes |= FINISH_LAYOUT_REDO_LAYOUT;
        }

        if (mShowingDream != mLastShowingDream) {
            mLastShowingDream = mShowingDream;
            mService.notifyShowingDreamChanged();
        }

        mService.mPolicy.setAllowLockscreenWhenOn(getDisplayId(), mAllowLockscreenWhenOn);
        return changes;
    }

    /**
     * Applies the keyguard policy to a specific window.
     *
     * @param win The window to apply the keyguard policy.
     * @param imeTarget The current IME target window.
     */
    private void applyKeyguardPolicy(WindowState win, WindowState imeTarget) {
        if (mService.mPolicy.canBeHiddenByKeyguardLw(win)) {
            if (shouldBeHiddenByKeyguard(win, imeTarget)) {
                win.hide(false /* doAnimation */, true /* requestAnim */);
            } else {
                win.show(false /* doAnimation */, true /* requestAnim */);
            }
        }
    }

    private boolean shouldBeHiddenByKeyguard(WindowState win, WindowState imeTarget) {
        // If AOD is showing, the IME should be hidden. However, sometimes the AOD is considered
        // hidden because it's in the process of hiding, but it's still being shown on screen.
        // In that case, we want to continue hiding the IME until the windows have completed
        // drawing. This way, we know that the IME can be safely shown since the other windows are
        // now shown.
        final boolean hideIme = win.mIsImWindow
                && (mService.mAtmService.mKeyguardController.isAodShowing()
                        || (mDisplayContent.isDefaultDisplay && !mWindowManagerDrawComplete));
        if (hideIme) {
            return true;
        }

        if (!mDisplayContent.isDefaultDisplay || !isKeyguardShowing()) {
            return false;
        }

        // Show IME over the keyguard if the target allows it.
        final boolean showImeOverKeyguard = imeTarget != null && imeTarget.isVisible()
                && win.mIsImWindow && (imeTarget.canShowWhenLocked()
                        || !mService.mPolicy.canBeHiddenByKeyguardLw(imeTarget));
        if (showImeOverKeyguard) {
            return false;
        }

        // Show SHOW_WHEN_LOCKED windows if keyguard is occluded.
        final boolean allowShowWhenLocked = isKeyguardOccluded()
                // Show error dialogs over apps that are shown on keyguard.
                && (win.canShowWhenLocked()
                        || (win.mAttrs.privateFlags & LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR) != 0);
        return !allowShowWhenLocked;
    }

    /**
     * @return Whether the top app should hide the statusbar based on the top fullscreen opaque
     *         window.
     */
    boolean topAppHidesStatusBar() {
        if (mTopFullscreenOpaqueWindowState == null || mForceShowSystemBars) {
            return false;
        }
        return !mTopFullscreenOpaqueWindowState.getRequestedVisibility(ITYPE_STATUS_BAR);
    }

    /**
     * Called when the user is switched.
     */
    public void switchUser() {
        updateCurrentUserResources();
    }

    /**
     * Called when the resource overlays change.
     */
    public void onOverlayChangedLw() {
        updateCurrentUserResources();
        onConfigurationChanged();
        mSystemGestures.onConfigurationChanged();
    }

    /**
     * Called when the configuration has changed, and it's safe to load new values from resources.
     */
    public void onConfigurationChanged() {
        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();

        final Resources res = getCurrentUserResources();
        final int portraitRotation = displayRotation.getPortraitRotation();
        final int upsideDownRotation = displayRotation.getUpsideDownRotation();
        final int landscapeRotation = displayRotation.getLandscapeRotation();
        final int seascapeRotation = displayRotation.getSeascapeRotation();
        final int uiMode = mService.mPolicy.getUiMode();

        if (hasStatusBar()) {
            mStatusBarHeightForRotation[portraitRotation] =
                    mStatusBarHeightForRotation[upsideDownRotation] =
                            res.getDimensionPixelSize(R.dimen.status_bar_height_portrait);
            mStatusBarHeightForRotation[landscapeRotation] =
                    mStatusBarHeightForRotation[seascapeRotation] =
                            res.getDimensionPixelSize(R.dimen.status_bar_height_landscape);
        } else {
            mStatusBarHeightForRotation[portraitRotation] =
                    mStatusBarHeightForRotation[upsideDownRotation] =
                            mStatusBarHeightForRotation[landscapeRotation] =
                                    mStatusBarHeightForRotation[seascapeRotation] = 0;
        }

        // Height of the navigation bar when presented horizontally at bottom
        mNavigationBarHeightForRotationDefault[portraitRotation] =
        mNavigationBarHeightForRotationDefault[upsideDownRotation] =
                res.getDimensionPixelSize(R.dimen.navigation_bar_height);
        mNavigationBarHeightForRotationDefault[landscapeRotation] =
        mNavigationBarHeightForRotationDefault[seascapeRotation] =
                res.getDimensionPixelSize(R.dimen.navigation_bar_height_landscape);

        // Height of the navigation bar frame when presented horizontally at bottom
        mNavigationBarFrameHeightForRotationDefault[portraitRotation] =
        mNavigationBarFrameHeightForRotationDefault[upsideDownRotation] =
                res.getDimensionPixelSize(R.dimen.navigation_bar_frame_height);
        mNavigationBarFrameHeightForRotationDefault[landscapeRotation] =
        mNavigationBarFrameHeightForRotationDefault[seascapeRotation] =
                res.getDimensionPixelSize(R.dimen.navigation_bar_frame_height_landscape);

        // Width of the navigation bar when presented vertically along one side
        mNavigationBarWidthForRotationDefault[portraitRotation] =
        mNavigationBarWidthForRotationDefault[upsideDownRotation] =
        mNavigationBarWidthForRotationDefault[landscapeRotation] =
        mNavigationBarWidthForRotationDefault[seascapeRotation] =
                res.getDimensionPixelSize(R.dimen.navigation_bar_width);

        if (ALTERNATE_CAR_MODE_NAV_SIZE) {
            // Height of the navigation bar when presented horizontally at bottom
            mNavigationBarHeightForRotationInCarMode[portraitRotation] =
            mNavigationBarHeightForRotationInCarMode[upsideDownRotation] =
                    res.getDimensionPixelSize(R.dimen.navigation_bar_height_car_mode);
            mNavigationBarHeightForRotationInCarMode[landscapeRotation] =
            mNavigationBarHeightForRotationInCarMode[seascapeRotation] =
                    res.getDimensionPixelSize(R.dimen.navigation_bar_height_landscape_car_mode);

            // Width of the navigation bar when presented vertically along one side
            mNavigationBarWidthForRotationInCarMode[portraitRotation] =
            mNavigationBarWidthForRotationInCarMode[upsideDownRotation] =
            mNavigationBarWidthForRotationInCarMode[landscapeRotation] =
            mNavigationBarWidthForRotationInCarMode[seascapeRotation] =
                    res.getDimensionPixelSize(R.dimen.navigation_bar_width_car_mode);
        }

        mNavBarOpacityMode = res.getInteger(R.integer.config_navBarOpacityMode);
        mLeftGestureInset = mGestureNavigationSettingsObserver.getLeftSensitivity(res);
        mRightGestureInset = mGestureNavigationSettingsObserver.getRightSensitivity(res);
        mNavButtonForcedVisible =
                mGestureNavigationSettingsObserver.areNavigationButtonForcedVisible();
        mNavigationBarLetsThroughTaps = res.getBoolean(R.bool.config_navBarTapThrough);
        mNavigationBarAlwaysShowOnSideGesture =
                res.getBoolean(R.bool.config_navBarAlwaysShowOnSideEdgeGesture);

        // This should calculate how much above the frame we accept gestures.
        mBottomGestureAdditionalInset =
                res.getDimensionPixelSize(R.dimen.navigation_bar_gesture_height)
                        - getNavigationBarFrameHeight(portraitRotation, uiMode);

        updateConfigurationAndScreenSizeDependentBehaviors();
    }

    void updateConfigurationAndScreenSizeDependentBehaviors() {
        final Resources res = getCurrentUserResources();
        mNavigationBarCanMove =
                mDisplayContent.mBaseDisplayWidth != mDisplayContent.mBaseDisplayHeight
                        && res.getBoolean(R.bool.config_navBarCanMove);
        mDisplayContent.getDisplayRotation().updateUserDependentConfiguration(res);
    }

    /**
     * Updates the current user's resources to pick up any changes for the current user (including
     * overlay paths)
     */
    private void updateCurrentUserResources() {
        final int userId = mService.mAmInternal.getCurrentUserId();
        final Context uiContext = getSystemUiContext();

        if (userId == UserHandle.USER_SYSTEM) {
            // Skip the (expensive) recreation of resources for the system user below and just
            // use the resources from the system ui context
            mCurrentUserResources = uiContext.getResources();
            return;
        }

        // For non-system users, ensure that the resources are loaded from the current
        // user's package info (see ContextImpl.createDisplayContext)
        final LoadedApk pi = ActivityThread.currentActivityThread().getPackageInfo(
                uiContext.getPackageName(), null, 0, userId);
        mCurrentUserResources = ResourcesManager.getInstance().getResources(null,
                pi.getResDir(),
                null /* splitResDirs */,
                pi.getOverlayDirs(),
                pi.getApplicationInfo().sharedLibraryFiles,
                mDisplayContent.getDisplayId(),
                null /* overrideConfig */,
                uiContext.getResources().getCompatibilityInfo(),
                null /* classLoader */,
                null /* loaders */);
    }

    @VisibleForTesting
    Resources getCurrentUserResources() {
        if (mCurrentUserResources == null) {
            updateCurrentUserResources();
        }
        return mCurrentUserResources;
    }

    @VisibleForTesting
    Context getContext() {
        return mContext;
    }

    Context getSystemUiContext() {
        return mUiContext;
    }

    private int getNavigationBarWidth(int rotation, int uiMode) {
        if (ALTERNATE_CAR_MODE_NAV_SIZE && (uiMode & UI_MODE_TYPE_MASK) == UI_MODE_TYPE_CAR) {
            return mNavigationBarWidthForRotationInCarMode[rotation];
        } else {
            return mNavigationBarWidthForRotationDefault[rotation];
        }
    }

    void notifyDisplayReady() {
        mHandler.post(() -> {
            final int displayId = getDisplayId();
            getStatusBarManagerInternal().onDisplayReady(displayId);
            final WallpaperManagerInternal wpMgr = LocalServices
                    .getService(WallpaperManagerInternal.class);
            if (wpMgr != null) {
                wpMgr.onDisplayReady(displayId);
            }
        });
    }

    /**
     * Return the display width available after excluding any screen
     * decorations that could never be removed in Honeycomb. That is, system bar or
     * button bar.
     */
    public int getNonDecorDisplayWidth(int fullWidth, int fullHeight, int rotation, int uiMode,
            DisplayCutout displayCutout) {
        int width = fullWidth;
        if (hasNavigationBar()) {
            final int navBarPosition = navigationBarPosition(fullWidth, fullHeight, rotation);
            if (navBarPosition == NAV_BAR_LEFT || navBarPosition == NAV_BAR_RIGHT) {
                width -= getNavigationBarWidth(rotation, uiMode);
            }
        }
        if (displayCutout != null) {
            width -= displayCutout.getSafeInsetLeft() + displayCutout.getSafeInsetRight();
        }
        return width;
    }

    private int getNavigationBarHeight(int rotation, int uiMode) {
        if (ALTERNATE_CAR_MODE_NAV_SIZE && (uiMode & UI_MODE_TYPE_MASK) == UI_MODE_TYPE_CAR) {
            return mNavigationBarHeightForRotationInCarMode[rotation];
        } else {
            return mNavigationBarHeightForRotationDefault[rotation];
        }
    }

    /**
     * Get the Navigation Bar Frame height. This dimension is the height of the navigation bar that
     * is used for spacing to show additional buttons on the navigation bar (such as the ime
     * switcher when ime is visible) while {@link #getNavigationBarHeight} is used for the visible
     * height that we send to the app as content insets that can be smaller.
     * <p>
     * In car mode it will return the same height as {@link #getNavigationBarHeight}
     *
     * @param rotation specifies rotation to return dimension from
     * @param uiMode to determine if in car mode
     * @return navigation bar frame height
     */
    private int getNavigationBarFrameHeight(int rotation, int uiMode) {
        if (ALTERNATE_CAR_MODE_NAV_SIZE && (uiMode & UI_MODE_TYPE_MASK) == UI_MODE_TYPE_CAR) {
            return mNavigationBarHeightForRotationInCarMode[rotation];
        } else {
            return mNavigationBarFrameHeightForRotationDefault[rotation];
        }
    }

    /**
     * Return the display height available after excluding any screen
     * decorations that could never be removed in Honeycomb. That is, system bar or
     * button bar.
     */
    public int getNonDecorDisplayHeight(int fullWidth, int fullHeight, int rotation, int uiMode,
            DisplayCutout displayCutout) {
        int height = fullHeight;
        if (hasNavigationBar()) {
            final int navBarPosition = navigationBarPosition(fullWidth, fullHeight, rotation);
            if (navBarPosition == NAV_BAR_BOTTOM) {
                height -= getNavigationBarHeight(rotation, uiMode);
            }
        }
        if (displayCutout != null) {
            height -= displayCutout.getSafeInsetTop() + displayCutout.getSafeInsetBottom();
        }
        return height;
    }

    /**
     * Return the available screen width that we should report for the
     * configuration.  This must be no larger than
     * {@link #getNonDecorDisplayWidth(int, int, int, int, DisplayCutout)}; it may be smaller
     * than that to account for more transient decoration like a status bar.
     */
    public int getConfigDisplayWidth(int fullWidth, int fullHeight, int rotation, int uiMode,
            DisplayCutout displayCutout) {
        return getNonDecorDisplayWidth(fullWidth, fullHeight, rotation, uiMode, displayCutout);
    }

    /**
     * Return the available screen height that we should report for the
     * configuration.  This must be no larger than
     * {@link #getNonDecorDisplayHeight(int, int, int, int, DisplayCutout)}; it may be smaller
     * than that to account for more transient decoration like a status bar.
     */
    public int getConfigDisplayHeight(int fullWidth, int fullHeight, int rotation, int uiMode,
            DisplayCutout displayCutout) {
        // There is a separate status bar at the top of the display.  We don't count that as part
        // of the fixed decor, since it can hide; however, for purposes of configurations,
        // we do want to exclude it since applications can't generally use that part
        // of the screen.
        int statusBarHeight = mStatusBarHeightForRotation[rotation];
        if (displayCutout != null) {
            // If there is a cutout, it may already have accounted for some part of the status
            // bar height.
            statusBarHeight = Math.max(0, statusBarHeight - displayCutout.getSafeInsetTop());
        }
        return getNonDecorDisplayHeight(fullWidth, fullHeight, rotation, uiMode, displayCutout)
                - statusBarHeight;
    }

    /**
     * Return corner radius in pixels that should be used on windows in order to cover the display.
     *
     * The radius is only valid for internal displays, since the corner radius of external displays
     * is not known at build time when window corners are configured.
     */
    float getWindowCornerRadius() {
        return mDisplayContent.getDisplay().getType() == TYPE_INTERNAL
                ? ScreenDecorationsUtils.getWindowCornerRadius(mContext.getResources()) : 0f;
    }

    boolean isShowingDreamLw() {
        return mShowingDream;
    }

    /**
     * Calculates the stable insets if we already have the non-decor insets.
     *
     * @param inOutInsets The known non-decor insets. It will be modified to stable insets.
     * @param rotation The current display rotation.
     */
    void convertNonDecorInsetsToStableInsets(Rect inOutInsets, int rotation) {
        inOutInsets.top = Math.max(inOutInsets.top, mStatusBarHeightForRotation[rotation]);
    }

    /**
     * Calculates the stable insets without running a layout.
     *
     * @param displayRotation the current display rotation
     * @param displayWidth the current display width
     * @param displayHeight the current display height
     * @param displayCutout the current display cutout
     * @param outInsets the insets to return
     */
    public void getStableInsetsLw(int displayRotation, int displayWidth, int displayHeight,
            DisplayCutout displayCutout, Rect outInsets) {
        outInsets.setEmpty();

        // Navigation bar and status bar.
        getNonDecorInsetsLw(displayRotation, displayWidth, displayHeight, displayCutout, outInsets);
        convertNonDecorInsetsToStableInsets(outInsets, displayRotation);
    }

    /**
     * Calculates the insets for the areas that could never be removed in Honeycomb, i.e. system
     * bar or button bar. See {@link #getNonDecorDisplayWidth}.
     *
     * @param displayRotation the current display rotation
     * @param displayWidth the current display width
     * @param displayHeight the current display height
     * @param displayCutout the current display cutout
     * @param outInsets the insets to return
     */
    public void getNonDecorInsetsLw(int displayRotation, int displayWidth, int displayHeight,
            DisplayCutout displayCutout, Rect outInsets) {
        outInsets.setEmpty();

        // Only navigation bar
        if (hasNavigationBar()) {
            final int uiMode = mService.mPolicy.getUiMode();
            int position = navigationBarPosition(displayWidth, displayHeight, displayRotation);
            if (position == NAV_BAR_BOTTOM) {
                outInsets.bottom = getNavigationBarHeight(displayRotation, uiMode);
            } else if (position == NAV_BAR_RIGHT) {
                outInsets.right = getNavigationBarWidth(displayRotation, uiMode);
            } else if (position == NAV_BAR_LEFT) {
                outInsets.left = getNavigationBarWidth(displayRotation, uiMode);
            }
        }

        if (displayCutout != null) {
            outInsets.left += displayCutout.getSafeInsetLeft();
            outInsets.top += displayCutout.getSafeInsetTop();
            outInsets.right += displayCutout.getSafeInsetRight();
            outInsets.bottom += displayCutout.getSafeInsetBottom();
        }
    }

    /**
     * @see IWindowManager#setForwardedInsets
     */
    public void setForwardedInsets(@NonNull Insets forwardedInsets) {
        mForwardedInsets = forwardedInsets;
    }

    @NonNull
    public Insets getForwardedInsets() {
        return mForwardedInsets;
    }

    @NavigationBarPosition
    int navigationBarPosition(int displayWidth, int displayHeight, int displayRotation) {
        if (navigationBarCanMove() && displayWidth > displayHeight) {
            if (displayRotation == Surface.ROTATION_270) {
                return NAV_BAR_LEFT;
            } else if (displayRotation == Surface.ROTATION_90) {
                return NAV_BAR_RIGHT;
            }
        }
        return NAV_BAR_BOTTOM;
    }

    /**
     * @return The side of the screen where navigation bar is positioned.
     * @see WindowManagerPolicyConstants#NAV_BAR_LEFT
     * @see WindowManagerPolicyConstants#NAV_BAR_RIGHT
     * @see WindowManagerPolicyConstants#NAV_BAR_BOTTOM
     */
    @NavigationBarPosition
    public int getNavBarPosition() {
        return mNavigationBarPosition;
    }

    @WindowManagerPolicy.AltBarPosition
    int getAlternateStatusBarPosition() {
        return mStatusBarAltPosition;
    }

    @WindowManagerPolicy.AltBarPosition
    int getAlternateNavBarPosition() {
        return mNavigationBarAltPosition;
    }

    /**
     * A new window has been focused.
     */
    public int focusChangedLw(WindowState lastFocus, WindowState newFocus) {
        mFocusedWindow = newFocus;
        mLastFocusedWindow = lastFocus;
        if (mDisplayContent.isDefaultDisplay) {
            mService.mPolicy.onDefaultDisplayFocusChangedLw(newFocus);
        }
        if (updateSystemUiVisibilityLw()) {
            // If the navigation bar has been hidden or shown, we need to do another
            // layout pass to update that window.
            return FINISH_LAYOUT_REDO_LAYOUT;
        }
        return 0;
    }

    private void requestTransientBars(WindowState swipeTarget) {
        if (!mService.mPolicy.isUserSetupComplete()) {
            // Swipe-up for navigation bar is disabled during setup
            return;
        }
        final InsetsSourceProvider provider = swipeTarget.getControllableInsetProvider();
        final InsetsControlTarget controlTarget = provider != null
                ? provider.getControlTarget() : null;

        if (controlTarget == null || controlTarget == getNotificationShade()) {
            // No transient mode on lockscreen (in notification shade window).
            return;
        }

        final @InsetsType int restorePositionTypes =
                (controlTarget.getRequestedVisibility(ITYPE_NAVIGATION_BAR)
                        ? Type.navigationBars() : 0)
                | (controlTarget.getRequestedVisibility(ITYPE_STATUS_BAR)
                        ? Type.statusBars() : 0)
                | (mExtraNavBarAlt != null && controlTarget.getRequestedVisibility(
                                ITYPE_EXTRA_NAVIGATION_BAR)
                        ? Type.navigationBars() : 0)
                | (mClimateBarAlt != null && controlTarget.getRequestedVisibility(
                                ITYPE_CLIMATE_BAR)
                        ? Type.statusBars() : 0);

        if (swipeTarget == mNavigationBar
                && (restorePositionTypes & Type.navigationBars()) != 0) {
            // Don't show status bar when swiping on already visible navigation bar.
            // But restore the position of navigation bar if it has been moved by the control
            // target.
            controlTarget.showInsets(Type.navigationBars(), false);
            return;
        }

        if (controlTarget.canShowTransient()) {
            // Show transient bars if they are hidden; restore position if they are visible.
            mDisplayContent.getInsetsPolicy().showTransient(SHOW_TYPES_FOR_SWIPE);
            controlTarget.showInsets(restorePositionTypes, false);
        } else {
            // Restore visibilities and positions of system bars.
            controlTarget.showInsets(Type.statusBars() | Type.navigationBars(), false);
        }
        mImmersiveModeConfirmation.confirmCurrentPrompt();
    }

    private void disposeInputConsumer(EventReceiverInputConsumer inputConsumer) {
        if (inputConsumer != null) {
            inputConsumer.dispose();
        }
    }

    boolean isKeyguardShowing() {
        return mService.mPolicy.isKeyguardShowing();
    }
    private boolean isKeyguardOccluded() {
        // TODO (b/113840485): Handle per display keyguard.
        return mService.mPolicy.isKeyguardOccluded();
    }

    InsetsPolicy getInsetsPolicy() {
        return mDisplayContent.getInsetsPolicy();
    }

    void resetSystemUiVisibilityLw() {
        mLastDisableFlags = 0;
        updateSystemUiVisibilityLw();
    }

    /**
     * @return {@code true} if the update may affect the layout.
     */
    boolean updateSystemUiVisibilityLw() {
        // If there is no window focused, there will be nobody to handle the events
        // anyway, so just hang on in whatever state we're in until things settle down.
        WindowState winCandidate = mFocusedWindow != null ? mFocusedWindow
                : mTopFullscreenOpaqueWindowState;
        if (winCandidate == null) {
            return false;
        }

        // The immersive mode confirmation should never affect the system bar visibility, otherwise
        // it will unhide the navigation bar and hide itself.
        if (winCandidate.getAttrs().token == mImmersiveModeConfirmation.getWindowToken()) {

            // The immersive mode confirmation took the focus from mLastFocusedWindow which was
            // controlling the system ui visibility. So if mLastFocusedWindow can still receive
            // keys, we let it keep controlling the visibility.
            final boolean lastFocusCanReceiveKeys =
                    (mLastFocusedWindow != null && mLastFocusedWindow.canReceiveKeys());
            winCandidate = isKeyguardShowing() && !isKeyguardOccluded() ? mNotificationShade
                    : lastFocusCanReceiveKeys ? mLastFocusedWindow
                            : mTopFullscreenOpaqueWindowState;
            if (winCandidate == null) {
                return false;
            }
        }
        final WindowState win = winCandidate;
        mSystemUiControllingWindow = win;

        mDisplayContent.getInsetsPolicy().updateBarControlTarget(win);

        final boolean inSplitScreen =
                mService.mRoot.getDefaultTaskDisplayArea().isSplitScreenModeActivated();
        if (inSplitScreen) {
            mService.getStackBounds(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD,
                    mDockedStackBounds);
        } else {
            mDockedStackBounds.setEmpty();
        }
        mService.getStackBounds(inSplitScreen ? WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                        : WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_UNDEFINED, mNonDockedStackBounds);
        final int fullscreenAppearance = updateLightStatusBarLw(0 /* appearance */,
                mTopFullscreenOpaqueWindowState, mTopFullscreenOpaqueOrDimmingWindowState,
                mNonDockedStackBounds);
        final int dockedAppearance = updateLightStatusBarLw(0 /* appearance */,
                mTopDockedOpaqueWindowState, mTopDockedOpaqueOrDimmingWindowState,
                mDockedStackBounds);
        final int disableFlags = win.getSystemUiVisibility() & StatusBarManager.DISABLE_MASK;
        final int opaqueAppearance = updateSystemBarsLw(win, disableFlags);
        final WindowState navColorWin = chooseNavigationColorWindowLw(
                mTopFullscreenOpaqueWindowState, mTopFullscreenOpaqueOrDimmingWindowState,
                mDisplayContent.mInputMethodWindow, mNavigationBarPosition);
        final boolean isNavbarColorManagedByIme =
                navColorWin != null && navColorWin == mDisplayContent.mInputMethodWindow;
        final int appearance = updateLightNavigationBarLw(
                win.mAttrs.insetsFlags.appearance, mTopFullscreenOpaqueWindowState,
                mTopFullscreenOpaqueOrDimmingWindowState,
                mDisplayContent.mInputMethodWindow, navColorWin) | opaqueAppearance;
        final int behavior = win.mAttrs.insetsFlags.behavior;
        final boolean isImmersive = behavior == BEHAVIOR_SHOW_BARS_BY_SWIPE
                || behavior == BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
        final boolean isFullscreen = !win.getRequestedVisibility(ITYPE_STATUS_BAR)
                || !win.getRequestedVisibility(ITYPE_NAVIGATION_BAR);
        if (mLastDisableFlags == disableFlags
                && mLastAppearance == appearance
                && mLastFullscreenAppearance == fullscreenAppearance
                && mLastDockedAppearance == dockedAppearance
                && mLastBehavior == behavior
                && mLastFocusIsFullscreen == isFullscreen
                && mLastFocusIsImmersive == isImmersive
                && mLastNonDockedStackBounds.equals(mNonDockedStackBounds)
                && mLastDockedStackBounds.equals(mDockedStackBounds)) {
            return false;
        }

        mLastDisableFlags = disableFlags;
        mLastAppearance = appearance;
        mLastFullscreenAppearance = fullscreenAppearance;
        mLastDockedAppearance = dockedAppearance;
        mLastBehavior = behavior;
        mLastFocusIsFullscreen = isFullscreen;
        mLastFocusIsImmersive = isImmersive;
        mLastNonDockedStackBounds.set(mNonDockedStackBounds);
        mLastDockedStackBounds.set(mDockedStackBounds);
        final Rect fullscreenStackBounds = new Rect(mNonDockedStackBounds);
        final Rect dockedStackBounds = new Rect(mDockedStackBounds);
        final AppearanceRegion[] appearanceRegions = inSplitScreen
                ? new AppearanceRegion[]{
                        new AppearanceRegion(fullscreenAppearance, fullscreenStackBounds),
                        new AppearanceRegion(dockedAppearance, dockedStackBounds)}
                : new AppearanceRegion[]{
                        new AppearanceRegion(fullscreenAppearance, fullscreenStackBounds)};
        String cause = win.toString();
        mHandler.post(() -> {
            StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
            if (statusBar != null) {
                final int displayId = getDisplayId();
                statusBar.setDisableFlags(displayId, disableFlags, cause);
                statusBar.onSystemBarAppearanceChanged(displayId, appearance,
                        appearanceRegions, isNavbarColorManagedByIme);
                statusBar.topAppWindowChanged(displayId, isFullscreen, isImmersive);

            }
        });
        if (mDisplayContent.isDefaultDisplay) {
            mService.mInputManager.setSystemUiLightsOut(
                    isFullscreen || (appearance & APPEARANCE_LOW_PROFILE_BARS) != 0);
        }
        return true;
    }

    private int updateLightStatusBarLw(@Appearance int appearance, WindowState opaque,
            WindowState opaqueOrDimming, Rect stackBounds) {
        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();
        final int statusBarHeight = mStatusBarHeightForRotation[displayRotation.getRotation()];
        final boolean stackBoundsContainStatusBar =
                stackBounds.isEmpty() ? false : stackBounds.top < statusBarHeight;
        final boolean onKeyguard = isKeyguardShowing() && !isKeyguardOccluded();
        final WindowState statusColorWin = onKeyguard ? mNotificationShade : opaqueOrDimming;
        if (stackBoundsContainStatusBar && statusColorWin != null) {
            if (statusColorWin == opaque || onKeyguard) {
                // If the top fullscreen-or-dimming window is also the top fullscreen, respect
                // its light flag.
                appearance &= ~APPEARANCE_LIGHT_STATUS_BARS;
                appearance |= statusColorWin.mAttrs.insetsFlags.appearance
                        & APPEARANCE_LIGHT_STATUS_BARS;
            } else if (statusColorWin.isDimming()) {
                // Otherwise if it's dimming, clear the light flag.
                appearance &= ~APPEARANCE_LIGHT_STATUS_BARS;
            }
            if (!mStatusBarController.isLightAppearanceAllowed(statusColorWin)) {
                appearance &= ~APPEARANCE_LIGHT_STATUS_BARS;
            }
        }
        return appearance;
    }

    @VisibleForTesting
    @Nullable
    static WindowState chooseNavigationColorWindowLw(WindowState opaque,
            WindowState opaqueOrDimming, WindowState imeWindow,
            @NavigationBarPosition int navBarPosition) {
        // If the IME window is visible and FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS is set, then IME
        // window can be navigation color window.
        final boolean imeWindowCanNavColorWindow = imeWindow != null
                && imeWindow.isVisible()
                && navBarPosition == NAV_BAR_BOTTOM
                && (imeWindow.mAttrs.flags
                        & WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;

        if (opaque != null && opaqueOrDimming == opaque) {
            // If the top fullscreen-or-dimming window is also the top fullscreen, respect it
            // unless IME window is also eligible, since currently the IME window is always show
            // above the opaque fullscreen app window, regardless of the IME target window.
            // TODO(b/31559891): Maybe we need to revisit this condition once b/31559891 is fixed.
            return imeWindowCanNavColorWindow ? imeWindow : opaque;
        }

        if (opaqueOrDimming == null || !opaqueOrDimming.isDimming()) {
            // No dimming window is involved. Determine the result only with the IME window.
            return imeWindowCanNavColorWindow ? imeWindow : null;
        }

        if (!imeWindowCanNavColorWindow) {
            // No IME window is involved. Determine the result only with opaqueOrDimming.
            return opaqueOrDimming;
        }

        // The IME window and the dimming window are competing.  Check if the dimming window can be
        // IME target or not.
        if (LayoutParams.mayUseInputMethod(opaqueOrDimming.mAttrs.flags)) {
            // The IME window is above the dimming window.
            return imeWindow;
        } else {
            // The dimming window is above the IME window.
            return opaqueOrDimming;
        }
    }

    @VisibleForTesting
    int updateLightNavigationBarLw(int appearance, WindowState opaque,
            WindowState opaqueOrDimming, WindowState imeWindow, WindowState navColorWin) {

        if (navColorWin != null) {
            if (navColorWin == imeWindow || navColorWin == opaque) {
                // Respect the light flag.
                appearance &= ~APPEARANCE_LIGHT_NAVIGATION_BARS;
                appearance |= navColorWin.mAttrs.insetsFlags.appearance
                        & APPEARANCE_LIGHT_NAVIGATION_BARS;
            } else if (navColorWin == opaqueOrDimming && navColorWin.isDimming()) {
                // Clear the light flag for dimming window.
                appearance &= ~APPEARANCE_LIGHT_NAVIGATION_BARS;
            }
            if (!mNavigationBarController.isLightAppearanceAllowed(navColorWin)) {
                appearance &= ~APPEARANCE_LIGHT_NAVIGATION_BARS;
            }
        }
        return appearance;
    }

    private int updateSystemBarsLw(WindowState win, int disableFlags) {
        final boolean dockedStackVisible = mDisplayContent.getDefaultTaskDisplayArea()
                .isStackVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final boolean freeformStackVisible = mDisplayContent.getDefaultTaskDisplayArea()
                .isStackVisible(WINDOWING_MODE_FREEFORM);
        final boolean resizing = mDisplayContent.getDockedDividerController().isResizing();

        // We need to force system bars when the docked stack is visible, when the freeform stack
        // is focused but also when we are resizing for the transitions when docked stack
        // visibility changes.
        mForceShowSystemBars = dockedStackVisible || win.inFreeformWindowingMode() || resizing;
        final boolean forceOpaqueStatusBar = mForceShowSystemBars && !isKeyguardShowing();

        final boolean fullscreenDrawsStatusBarBackground =
                drawsStatusBarBackground(mTopFullscreenOpaqueWindowState);
        final boolean dockedDrawsStatusBarBackground =
                drawsStatusBarBackground(mTopDockedOpaqueWindowState);
        final boolean fullscreenDrawsNavBarBackground =
                drawsNavigationBarBackground(mTopFullscreenOpaqueWindowState);
        final boolean dockedDrawsNavigationBarBackground =
                drawsNavigationBarBackground(mTopDockedOpaqueWindowState);

        int appearance = APPEARANCE_OPAQUE_NAVIGATION_BARS | APPEARANCE_OPAQUE_STATUS_BARS;

        if (fullscreenDrawsStatusBarBackground && dockedDrawsStatusBarBackground) {
            appearance &= ~APPEARANCE_OPAQUE_STATUS_BARS;
        }

        appearance = configureNavBarOpacity(appearance, dockedStackVisible,
                freeformStackVisible, resizing, fullscreenDrawsNavBarBackground,
                dockedDrawsNavigationBarBackground);

        final boolean requestHideNavBar = !win.getRequestedVisibility(ITYPE_NAVIGATION_BAR);
        final long now = SystemClock.uptimeMillis();
        final boolean pendingPanic = mPendingPanicGestureUptime != 0
                && now - mPendingPanicGestureUptime <= PANIC_GESTURE_EXPIRATION;
        final DisplayPolicy defaultDisplayPolicy =
                mService.getDefaultDisplayContentLocked().getDisplayPolicy();
        if (pendingPanic && requestHideNavBar && win != mNotificationShade
                && getInsetsPolicy().isHidden(ITYPE_NAVIGATION_BAR)
                // TODO (b/111955725): Show keyguard presentation on all external displays
                && defaultDisplayPolicy.isKeyguardDrawComplete()) {
            // The user performed the panic gesture recently, we're about to hide the bars,
            // we're no longer on the Keyguard and the screen is ready. We can now request the bars.
            mPendingPanicGestureUptime = 0;
            if (!isNavBarEmpty(disableFlags)) {
                mDisplayContent.getInsetsPolicy().showTransient(SHOW_TYPES_FOR_PANIC);
            }
        }

        // update navigation bar
        boolean oldImmersiveMode = mLastImmersiveMode;
        boolean newImmersiveMode = isImmersiveMode(win);
        if (oldImmersiveMode != newImmersiveMode) {
            mLastImmersiveMode = newImmersiveMode;
            final String pkg = win.getOwningPackage();
            mImmersiveModeConfirmation.immersiveModeChangedLw(pkg, newImmersiveMode,
                    mService.mPolicy.isUserSetupComplete(),
                    isNavBarEmpty(disableFlags));
        }

        return appearance;
    }

    private boolean drawsBarBackground(WindowState win, BarController controller) {
        if (!controller.isTransparentAllowed(win)) {
            return false;
        }
        if (win == null) {
            return true;
        }

        final boolean drawsSystemBars =
                (win.getAttrs().flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
        final boolean forceDrawsSystemBars =
                (win.getAttrs().privateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) != 0;

        return forceDrawsSystemBars || drawsSystemBars;
    }

    private boolean drawsStatusBarBackground(WindowState win) {
        return drawsBarBackground(win, mStatusBarController);
    }

    private boolean drawsNavigationBarBackground(WindowState win) {
        return drawsBarBackground(win, mNavigationBarController);
    }

    /**
     * @return the current visibility flags with the nav-bar opacity related flags toggled based
     *         on the nav bar opacity rules chosen by {@link #mNavBarOpacityMode}.
     */
    private int configureNavBarOpacity(int appearance, boolean dockedStackVisible,
            boolean freeformStackVisible, boolean isDockedDividerResizing,
            boolean fullscreenDrawsBackground, boolean dockedDrawsNavigationBarBackground) {
        if (mNavBarOpacityMode == NAV_BAR_FORCE_TRANSPARENT) {
            if (fullscreenDrawsBackground && dockedDrawsNavigationBarBackground) {
                appearance = clearNavBarOpaqueFlag(appearance);
            } else if (dockedStackVisible) {
                appearance = setNavBarOpaqueFlag(appearance);
            }
        } else if (mNavBarOpacityMode == NAV_BAR_OPAQUE_WHEN_FREEFORM_OR_DOCKED) {
            if (dockedStackVisible || freeformStackVisible || isDockedDividerResizing) {
                if (mIsFreeformWindowOverlappingWithNavBar) {
                    appearance = clearNavBarOpaqueFlag(appearance);
                } else {
                    appearance = setNavBarOpaqueFlag(appearance);
                }
            } else if (fullscreenDrawsBackground) {
                appearance = clearNavBarOpaqueFlag(appearance);
            }
        } else if (mNavBarOpacityMode == NAV_BAR_TRANSLUCENT_WHEN_FREEFORM_OPAQUE_OTHERWISE) {
            if (isDockedDividerResizing) {
                appearance = setNavBarOpaqueFlag(appearance);
            } else if (freeformStackVisible) {
                appearance = clearNavBarOpaqueFlag(appearance);
            } else {
                appearance = setNavBarOpaqueFlag(appearance);
            }
        }

        return appearance;
    }

    private int setNavBarOpaqueFlag(int appearance) {
        return appearance | APPEARANCE_OPAQUE_NAVIGATION_BARS;
    }

    private int clearNavBarOpaqueFlag(int appearance) {
        return appearance & ~APPEARANCE_OPAQUE_NAVIGATION_BARS;
    }

    private boolean isImmersiveMode(WindowState win) {
        if (win == null) {
            return false;
        }
        final int behavior = win.mAttrs.insetsFlags.behavior;
        return getNavigationBar() != null
                && canHideNavigationBar()
                && (behavior == BEHAVIOR_SHOW_BARS_BY_SWIPE
                        || behavior == BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
                && getInsetsPolicy().isHidden(ITYPE_NAVIGATION_BAR)
                && win != getNotificationShade()
                && !win.isActivityTypeDream();
    }

    /**
     * @return whether the navigation bar can be hidden, e.g. the device has a navigation bar
     */
    private boolean canHideNavigationBar() {
        return hasNavigationBar();
    }

    private static boolean isNavBarEmpty(int systemUiFlags) {
        final int disableNavigationBar = (View.STATUS_BAR_DISABLE_HOME
                | View.STATUS_BAR_DISABLE_BACK
                | View.STATUS_BAR_DISABLE_RECENT);

        return (systemUiFlags & disableNavigationBar) == disableNavigationBar;
    }

    private final Runnable mHiddenNavPanic = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                if (!mService.mPolicy.isUserSetupComplete()) {
                    // Swipe-up for navigation bar is disabled during setup
                    return;
                }
                mPendingPanicGestureUptime = SystemClock.uptimeMillis();
                updateSystemUiVisibilityLw();
            }
        }
    };

    void onPowerKeyDown(boolean isScreenOn) {
        // Detect user pressing the power button in panic when an application has
        // taken over the whole screen.
        boolean panic = mImmersiveModeConfirmation.onPowerKeyDown(isScreenOn,
                SystemClock.elapsedRealtime(), isImmersiveMode(mSystemUiControllingWindow),
                isNavBarEmpty(mLastDisableFlags));
        if (panic) {
            mHandler.post(mHiddenNavPanic);
        }
    }

    void onVrStateChangedLw(boolean enabled) {
        mImmersiveModeConfirmation.onVrStateChangedLw(enabled);
    }

    /**
     * Called when the state of lock task mode changes. This should be used to disable immersive
     * mode confirmation.
     *
     * @param lockTaskState the new lock task mode state. One of
     *                      {@link ActivityManager#LOCK_TASK_MODE_NONE},
     *                      {@link ActivityManager#LOCK_TASK_MODE_LOCKED},
     *                      {@link ActivityManager#LOCK_TASK_MODE_PINNED}.
     */
    public void onLockTaskStateChangedLw(int lockTaskState) {
        mImmersiveModeConfirmation.onLockTaskModeChangedLw(lockTaskState);
    }

    /**
     * Request a screenshot be taken.
     *
     * @param screenshotType The type of screenshot, for example either
     *                       {@link WindowManager#TAKE_SCREENSHOT_FULLSCREEN} or
     *                       {@link WindowManager#TAKE_SCREENSHOT_SELECTED_REGION}
     * @param source Where the screenshot originated from (see WindowManager.ScreenshotSource)
     */
    public void takeScreenshot(int screenshotType, int source) {
        if (mScreenshotHelper != null) {
            mScreenshotHelper.takeScreenshot(screenshotType,
                    getStatusBar() != null && getStatusBar().isVisible(),
                    getNavigationBar() != null && getNavigationBar().isVisible(),
                    source, mHandler, null /* completionConsumer */);
        }
    }

    RefreshRatePolicy getRefreshRatePolicy() {
        return mRefreshRatePolicy;
    }

    void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.println("DisplayPolicy");
        prefix += "  ";
        pw.print(prefix);
        pw.print("mCarDockEnablesAccelerometer="); pw.print(mCarDockEnablesAccelerometer);
        pw.print(" mDeskDockEnablesAccelerometer=");
        pw.println(mDeskDockEnablesAccelerometer);
        pw.print(prefix); pw.print("mDockMode="); pw.print(Intent.dockStateToString(mDockMode));
        pw.print(" mLidState="); pw.println(WindowManagerFuncs.lidStateToString(mLidState));
        pw.print(prefix); pw.print("mAwake="); pw.print(mAwake);
        pw.print(" mScreenOnEarly="); pw.print(mScreenOnEarly);
        pw.print(" mScreenOnFully="); pw.println(mScreenOnFully);
        pw.print(prefix); pw.print("mKeyguardDrawComplete="); pw.print(mKeyguardDrawComplete);
        pw.print(" mWindowManagerDrawComplete="); pw.println(mWindowManagerDrawComplete);
        pw.print(prefix); pw.print("mHdmiPlugged="); pw.println(mHdmiPlugged);
        if (mLastDisableFlags != 0) {
            pw.print(prefix); pw.print("mLastDisableFlags=0x");
            pw.println(Integer.toHexString(mLastDisableFlags));
        }
        if (mLastAppearance != 0) {
            pw.print(prefix); pw.print("mLastAppearance=");
            pw.println(ViewDebug.flagsToString(InsetsFlags.class, "appearance", mLastAppearance));
        }
        if (mLastBehavior != 0) {
            pw.print(prefix); pw.print("mLastBehavior=");
            pw.println(ViewDebug.flagsToString(InsetsFlags.class, "behavior", mLastBehavior));
        }
        pw.print(prefix); pw.print("mShowingDream="); pw.print(mShowingDream);
        pw.print(" mDreamingLockscreen="); pw.println(mDreamingLockscreen);
        if (mStatusBar != null) {
            pw.print(prefix); pw.print("mStatusBar="); pw.println(mStatusBar);
        }
        if (mStatusBarAlt != null) {
            pw.print(prefix); pw.print("mStatusBarAlt="); pw.println(mStatusBarAlt);
            pw.print(prefix); pw.print("mStatusBarAltPosition=");
            pw.println(mStatusBarAltPosition);
        }
        if (mNotificationShade != null) {
            pw.print(prefix); pw.print("mExpandedPanel="); pw.println(mNotificationShade);
        }
        pw.print(prefix); pw.print("isKeyguardShowing="); pw.println(isKeyguardShowing());
        if (mNavigationBar != null) {
            pw.print(prefix); pw.print("mNavigationBar="); pw.println(mNavigationBar);
            pw.print(prefix); pw.print("mNavBarOpacityMode="); pw.println(mNavBarOpacityMode);
            pw.print(prefix); pw.print("mNavigationBarCanMove="); pw.println(mNavigationBarCanMove);
            pw.print(prefix); pw.print("mNavigationBarPosition=");
            pw.println(mNavigationBarPosition);
        }
        if (mNavigationBarAlt != null) {
            pw.print(prefix); pw.print("mNavigationBarAlt="); pw.println(mNavigationBarAlt);
            pw.print(prefix); pw.print("mNavigationBarAltPosition=");
            pw.println(mNavigationBarAltPosition);
        }
        if (mClimateBarAlt != null) {
            pw.print(prefix); pw.print("mClimateBarAlt="); pw.println(mClimateBarAlt);
            pw.print(prefix); pw.print("mClimateBarAltPosition=");
            pw.println(mClimateBarAltPosition);
        }
        if (mExtraNavBarAlt != null) {
            pw.print(prefix); pw.print("mExtraNavBarAlt="); pw.println(mExtraNavBarAlt);
            pw.print(prefix); pw.print("mExtraNavBarAltPosition=");
            pw.println(mExtraNavBarAltPosition);
        }
        if (mFocusedWindow != null) {
            pw.print(prefix); pw.print("mFocusedWindow="); pw.println(mFocusedWindow);
        }
        if (mTopFullscreenOpaqueWindowState != null) {
            pw.print(prefix); pw.print("mTopFullscreenOpaqueWindowState=");
            pw.println(mTopFullscreenOpaqueWindowState);
        }
        if (mTopFullscreenOpaqueOrDimmingWindowState != null) {
            pw.print(prefix); pw.print("mTopFullscreenOpaqueOrDimmingWindowState=");
            pw.println(mTopFullscreenOpaqueOrDimmingWindowState);
        }
        if (mForcingShowNavBar) {
            pw.print(prefix); pw.print("mForcingShowNavBar="); pw.println(mForcingShowNavBar);
            pw.print(prefix); pw.print("mForcingShowNavBarLayer=");
            pw.println(mForcingShowNavBarLayer);
        }
        pw.print(prefix); pw.print("mTopIsFullscreen="); pw.println(mTopIsFullscreen);
        pw.print(prefix); pw.print("mForceStatusBar="); pw.print(mForceStatusBar);
        pw.print(" mAllowLockscreenWhenOn="); pw.println(mAllowLockscreenWhenOn);
        pw.print(prefix); pw.print("mRemoteInsetsControllerControlsSystemBars=");
        pw.println(mDisplayContent.getInsetsPolicy().getRemoteInsetsControllerControlsSystemBars());

        pw.print(prefix); pw.println("Looper state:");
        mHandler.getLooper().dump(new PrintWriterPrinter(pw), prefix + "  ");
    }

    private boolean supportsPointerLocation() {
        return mDisplayContent.isDefaultDisplay || !mDisplayContent.isPrivate();
    }

    void setPointerLocationEnabled(boolean pointerLocationEnabled) {
        if (!supportsPointerLocation()) {
            return;
        }

        mHandler.sendEmptyMessage(pointerLocationEnabled
                ? MSG_ENABLE_POINTER_LOCATION : MSG_DISABLE_POINTER_LOCATION);
    }

    private void enablePointerLocation() {
        if (mPointerLocationView != null) {
            return;
        }

        mPointerLocationView = new PointerLocationView(mContext);
        mPointerLocationView.setPrintCoords(false);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            lp.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED;
        }
        lp.format = PixelFormat.TRANSLUCENT;
        lp.setTitle("PointerLocation - display " + getDisplayId());
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        wm.addView(mPointerLocationView, lp);
        mDisplayContent.registerPointerEventListener(mPointerLocationView);
    }

    private void disablePointerLocation() {
        if (mPointerLocationView == null) {
            return;
        }

        mDisplayContent.unregisterPointerEventListener(mPointerLocationView);
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        wm.removeView(mPointerLocationView);
        mPointerLocationView = null;
    }

    /**
     * Check if the window could be excluded from checking if the display has content.
     *
     * @param w WindowState to check if should be excluded.
     * @return True if the window type is PointerLocation which is excluded.
     */
    boolean isWindowExcludedFromContent(WindowState w) {
        if (w != null && mPointerLocationView != null) {
            return w.mClient == mPointerLocationView.getWindowToken();
        }

        return false;
    }

    void release() {
        mHandler.post(mGestureNavigationSettingsObserver::unregister);
    }

    @VisibleForTesting
    static boolean isOverlappingWithNavBar(WindowState targetWindow, WindowState navBarWindow) {
        if (navBarWindow == null || !navBarWindow.isVisible()
                || targetWindow.mActivityRecord == null || !targetWindow.isVisible()) {
            return false;
        }

        return Rect.intersects(targetWindow.getFrame(), navBarWindow.getFrame());
    }
}
