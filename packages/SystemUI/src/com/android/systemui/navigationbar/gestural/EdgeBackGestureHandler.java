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
package com.android.systemui.navigationbar.gestural;

import static android.view.InputDevice.SOURCE_MOUSE;
import static android.view.InputDevice.SOURCE_TOUCHPAD;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION;

import static com.android.systemui.classifier.Classifier.BACK_GESTURE;
import static com.android.systemui.navigationbar.gestural.Utilities.isTrackpadScroll;
import static com.android.systemui.navigationbar.gestural.Utilities.isTrackpadThreeFingerSwipe;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.icu.text.SimpleDateFormat;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.ISystemGestureExclusionListener;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputMonitor;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.BackEvent;

import androidx.annotation.DimenRes;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.policy.GestureNavigationSettingsObserver;
import com.android.internal.util.cherish.CherishUtils;
import com.android.systemui.Dependency;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.NavigationEdgeBackPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.Assert;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.desktopmode.DesktopMode;
import com.android.wm.shell.pip.Pip;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Utility class to handle edge swipes for back gesture
 */
public class EdgeBackGestureHandler implements PluginListener<NavigationEdgeBackPlugin>,
        TunerService.Tunable {

    private static final String TAG = "EdgeBackGestureHandler";
    private static final int MAX_LONG_PRESS_TIMEOUT = SystemProperties.getInt(
            "gestures.back_timeout", 250);

    private static final String BACK_GESTURE_HEIGHT =
            "system:" + Settings.System.BACK_GESTURE_HEIGHT;

    private static final int MAX_NUM_LOGGED_PREDICTIONS = 10;
    private static final int MAX_NUM_LOGGED_GESTURES = 10;

    static final boolean DEBUG_MISSING_GESTURE = false;
    public static final String DEBUG_MISSING_GESTURE_TAG = "NoBackGesture";

    private ISystemGestureExclusionListener mGestureExclusionListener =
            new ISystemGestureExclusionListener.Stub() {
                @Override
                public void onSystemGestureExclusionChanged(int displayId,
                        Region systemGestureExclusion, Region unrestrictedOrNull) {
                    if (displayId == mDisplayId) {
                        mMainExecutor.execute(() -> {
                            mExcludeRegion.set(systemGestureExclusion);
                            mUnrestrictedExcludeRegion.set(unrestrictedOrNull != null
                                    ? unrestrictedOrNull : systemGestureExclusion);
                        });
                    }
                }
            };

    private OverviewProxyService.OverviewProxyListener mQuickSwitchListener =
            new OverviewProxyService.OverviewProxyListener() {
                @Override
                public void onPrioritizedRotation(@Surface.Rotation int rotation) {
                    mStartingQuickstepRotation = rotation;
                    updateDisabledForQuickstep(mLastReportedConfig);
                }
            };

    private TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onTaskStackChanged() {
            mGestureBlockingActivityRunning = isGestureBlockingActivityRunning();
        }
        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) {
            if (componentName != null) {
                mPackageName = componentName.getPackageName();
            } else {
                mPackageName = "_UNKNOWN";
            }
        }
    };

    private DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(DeviceConfig.Properties properties) {
                    if (DeviceConfig.NAMESPACE_SYSTEMUI.equals(properties.getNamespace())
                            && (properties.getKeyset().contains(
                                    SystemUiDeviceConfigFlags.BACK_GESTURE_ML_MODEL_THRESHOLD)
                            || properties.getKeyset().contains(
                                    SystemUiDeviceConfigFlags.USE_BACK_GESTURE_ML_MODEL)
                            || properties.getKeyset().contains(
                                    SystemUiDeviceConfigFlags.BACK_GESTURE_ML_MODEL_NAME))) {
                        updateMLModelState();
                    }
                }
            };

    private final VelocityTracker mVelocityTracker = VelocityTracker.obtain();
    private final Context mContext;
    private final UserTracker mUserTracker;
    private final OverviewProxyService mOverviewProxyService;
    private final SysUiState mSysUiState;
    private Runnable mStateChangeCallback;
    private Consumer<Boolean> mButtonForcedVisibleCallback;

    private final PluginManager mPluginManager;
    private final NavigationModeController mNavigationModeController;
    private final BackPanelController.Factory mBackPanelControllerFactory;
    private final ViewConfiguration mViewConfiguration;
    private final WindowManager mWindowManager;
    private final IWindowManager mWindowManagerService;
    private final InputManager mInputManager;
    private final Optional<Pip> mPipOptional;
    private final Optional<DesktopMode> mDesktopModeOptional;
    private final FalsingManager mFalsingManager;
    private final Configuration mLastReportedConfig = new Configuration();
    // Activities which should not trigger Back gesture.
    private final List<ComponentName> mGestureBlockingActivities = new ArrayList<>();

    private final Point mDisplaySize = new Point();
    private final int mDisplayId;

    private final Executor mMainExecutor;
    private final Handler mMainHandler;
    private final Executor mBackgroundExecutor;

    private final Rect mPipExcludedBounds = new Rect();
    private final Rect mNavBarOverlayExcludedBounds = new Rect();
    private final Region mExcludeRegion = new Region();
    private final Region mDesktopModeExcludeRegion = new Region();
    private final Region mUnrestrictedExcludeRegion = new Region();
    private final Provider<NavigationBarEdgePanel> mNavBarEdgePanelProvider;
    private final Provider<BackGestureTfClassifierProvider>
            mBackGestureTfClassifierProviderProvider;
    private final FeatureFlags mFeatureFlags;
    private final Provider<LightBarController> mLightBarControllerProvider;

    // The left side edge width where touch down is allowed
    private int mEdgeWidthLeft;
    // The right side edge width where touch down is allowed
    private int mEdgeWidthRight;
    // The bottom gesture area height
    private float mBottomGestureHeight;
    // The slop to distinguish between horizontal and vertical motion
    private float mTouchSlop;
    // The threshold for back swipe full progress.
    private float mBackSwipeLinearThreshold;
    private float mNonLinearFactor;
    // Duration after which we consider the event as longpress.
    private final int mLongPressTimeout;
    private int mStartingQuickstepRotation = -1;
    // We temporarily disable back gesture when user is quickswitching
    // between apps of different orientations
    private boolean mDisabledForQuickstep;
    // This gets updated when the value of PipTransitionState#isInPip changes.
    private boolean mIsInPip;

    private final PointF mDownPoint = new PointF();
    private final PointF mEndPoint = new PointF();
    private boolean mThresholdCrossed = false;
    private boolean mAllowGesture = false;
    private boolean mLogGesture = false;
    private boolean mInRejectedExclusion = false;
    private boolean mIsOnLeftEdge;
    private boolean mDeferSetIsOnLeftEdge;

    private int mLeftLongSwipeAction;
    private int mRightLongSwipeAction;
    private boolean mIsExtendedSwipe;
    private int mLeftVerticalSwipeAction;
    private int mRightVerticalSwipeAction;
    private Handler mHandler;
    private boolean mImeVisible;
    private float mStartX;
    private float mStartY;

    private boolean mIsAttached;
    private boolean mIsGestureHandlingEnabled;
    private boolean mIsTrackpadConnected;
    private boolean mInGestureNavMode;
    private boolean mUsingThreeButtonNav;
    private boolean mIsEnabled;
    private boolean mIsNavBarShownTransiently;
    private boolean mIsBackGestureAllowed;
    private boolean mGestureBlockingActivityRunning;
    private boolean mIsNewBackAffordanceEnabled;
    private boolean mIsTrackpadGestureFeaturesEnabled;
    private boolean mIsTrackpadThreeFingerSwipe;
    private boolean mIsButtonForcedVisible;

    private InputMonitor mInputMonitor;
    private InputChannelCompat.InputEventReceiver mInputEventReceiver;

    private NavigationEdgeBackPlugin mEdgeBackPlugin;
    private BackAnimation mBackAnimation;
    private int mLeftInset;
    private int mRightInset;
    private int mSysUiFlags;

    private int mEdgeHeight;
    private int mEdgeHeightSetting = 0;

    // For Tf-Lite model.
    private BackGestureTfClassifierProvider mBackGestureTfClassifierProvider;
    private Map<String, Integer> mVocab;
    private boolean mUseMLModel;
    private boolean mMLModelIsLoading;
    // minimum width below which we do not run the model
    private int mMLEnableWidth;
    private float mMLModelThreshold;
    private String mPackageName;
    private float mMLResults;

    // For debugging
    private LogArray mPredictionLog = new LogArray(MAX_NUM_LOGGED_PREDICTIONS);
    private LogArray mGestureLogInsideInsets = new LogArray(MAX_NUM_LOGGED_GESTURES);
    private LogArray mGestureLogOutsideInsets = new LogArray(MAX_NUM_LOGGED_GESTURES);
    private SimpleDateFormat mLogDateFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private Date mTmpLogDate = new Date();

    private final GestureNavigationSettingsObserver mGestureNavigationSettingsObserver;

    private boolean mIsBackGestureArrowEnabled;
    private boolean mIsEdgeHapticEnabled;
    private final Vibrator mVibrator;
    private boolean mBlockedGesturalNavigation;

    private final NavigationEdgeBackPlugin.BackCallback mBackCallback =
            new NavigationEdgeBackPlugin.BackCallback() {
                @Override
                public void triggerBack() {
                    // Notify FalsingManager that an intentional gesture has occurred.
                    mFalsingManager.isFalseTouch(BACK_GESTURE);
                    // Only inject back keycodes when ahead-of-time back dispatching is disabled.
                    if (mBackAnimation == null) {
                        boolean sendDown = sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
                        boolean sendUp = sendEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK);
                        if (DEBUG_MISSING_GESTURE) {
                            Log.d(DEBUG_MISSING_GESTURE_TAG, "Triggered back: down="
                                    + sendDown + ", up=" + sendUp);
                        }
                    } else {
                        mBackAnimation.setTriggerBack(true);
                    }

                    logGesture(mInRejectedExclusion
                            ? SysUiStatsLog.BACK_GESTURE__TYPE__COMPLETED_REJECTED
                            : SysUiStatsLog.BACK_GESTURE__TYPE__COMPLETED);
                }

                @Override
                public void cancelBack() {
                    if (mBackAnimation != null) {
                        mBackAnimation.setTriggerBack(false);
                    }
                    logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE);
                }

                @Override
                public void setTriggerBack(boolean triggerBack) {
                    if (mBackAnimation != null) {
                        mBackAnimation.setTriggerBack(triggerBack);
                    }
                }
            };

    private final SysUiState.SysUiStateCallback mSysUiStateCallback =
            new SysUiState.SysUiStateCallback() {
        @Override
        public void onSystemUiStateChanged(int sysUiFlags) {
            mSysUiFlags = sysUiFlags;
        }
    };

    private final Consumer<Boolean> mOnIsInPipStateChangedListener =
            (isInPip) -> mIsInPip = isInPip;

    private final Consumer<Region> mDesktopCornersChangedListener =
            (desktopExcludeRegion) -> mDesktopModeExcludeRegion.set(desktopExcludeRegion);

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    updateIsEnabled();
                    updateCurrentUserResources();
                }
            };

    private final InputManager.InputDeviceListener mInputDeviceListener =
            new InputManager.InputDeviceListener() {

        // Only one trackpad can be connected to a device at a time, since it takes over the
        // only USB port.
        private int mTrackpadDeviceId;

        @Override
        public void onInputDeviceAdded(int deviceId) {
            if (isTrackpadDevice(deviceId)) {
                mTrackpadDeviceId = deviceId;
                update(true /* isTrackpadConnected */);
            }
        }

        @Override
        public void onInputDeviceChanged(int deviceId) { }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            if (mTrackpadDeviceId == deviceId) {
                update(false /* isTrackpadConnected */);
            }
        }

        private void update(boolean isTrackpadConnected) {
            boolean isPreviouslyTrackpadConnected = mIsTrackpadConnected;
            mIsTrackpadConnected = isTrackpadConnected;
            if (isPreviouslyTrackpadConnected != mIsTrackpadConnected) {
                updateIsEnabled();
                updateCurrentUserResources();
            }
        }
    };

    EdgeBackGestureHandler(
            Context context,
            OverviewProxyService overviewProxyService,
            SysUiState sysUiState,
            PluginManager pluginManager,
            @Main Executor executor,
            @Main Handler handler,
            @Background Executor backgroundExecutor,
            UserTracker userTracker,
            NavigationModeController navigationModeController,
            BackPanelController.Factory backPanelControllerFactory,
            ViewConfiguration viewConfiguration,
            WindowManager windowManager,
            IWindowManager windowManagerService,
            InputManager inputManager,
            Optional<Pip> pipOptional,
            Optional<DesktopMode> desktopModeOptional,
            FalsingManager falsingManager,
            Provider<NavigationBarEdgePanel> navigationBarEdgePanelProvider,
            Provider<BackGestureTfClassifierProvider> backGestureTfClassifierProviderProvider,
            FeatureFlags featureFlags,
            Provider<LightBarController> lightBarControllerProvider) {
        mContext = context;
        mVibrator = context.getSystemService(Vibrator.class);
        mDisplayId = context.getDisplayId();
        mMainExecutor = executor;
        mMainHandler = handler;
        mBackgroundExecutor = backgroundExecutor;
        mUserTracker = userTracker;
        mOverviewProxyService = overviewProxyService;
        mSysUiState = sysUiState;
        mPluginManager = pluginManager;
        mNavigationModeController = navigationModeController;
        mBackPanelControllerFactory = backPanelControllerFactory;
        mViewConfiguration = viewConfiguration;
        mWindowManager = windowManager;
        mWindowManagerService = windowManagerService;
        mInputManager = inputManager;
        mPipOptional = pipOptional;
        mDesktopModeOptional = desktopModeOptional;
        mFalsingManager = falsingManager;
        mNavBarEdgePanelProvider = navigationBarEdgePanelProvider;
        mBackGestureTfClassifierProviderProvider = backGestureTfClassifierProviderProvider;
        mFeatureFlags = featureFlags;
        mLightBarControllerProvider = lightBarControllerProvider;
        mLastReportedConfig.setTo(mContext.getResources().getConfiguration());
        mIsTrackpadGestureFeaturesEnabled = mFeatureFlags.isEnabled(
                Flags.TRACKPAD_GESTURE_FEATURES);
        ComponentName recentsComponentName = ComponentName.unflattenFromString(
                context.getString(com.android.internal.R.string.config_recentsComponentName));
        if (recentsComponentName != null) {
            String recentsPackageName = recentsComponentName.getPackageName();
            PackageManager manager = context.getPackageManager();
            try {
                Resources resources = manager.getResourcesForApplication(
                        manager.getApplicationInfo(recentsPackageName,
                                PackageManager.MATCH_UNINSTALLED_PACKAGES
                                        | PackageManager.MATCH_DISABLED_COMPONENTS
                                        | PackageManager.GET_SHARED_LIBRARY_FILES));
                int resId = resources.getIdentifier(
                        "gesture_blocking_activities", "array", recentsPackageName);

                if (resId == 0) {
                    Log.e(TAG, "No resource found for gesture-blocking activities");
                } else {
                    String[] gestureBlockingActivities = resources.getStringArray(resId);
                    for (String gestureBlockingActivity : gestureBlockingActivities) {
                        mGestureBlockingActivities.add(
                                ComponentName.unflattenFromString(gestureBlockingActivity));
                    }
                }
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Failed to add gesture blocking activities", e);
            }
        }
        mLongPressTimeout = Math.min(MAX_LONG_PRESS_TIMEOUT,
                ViewConfiguration.getLongPressTimeout());

        mGestureNavigationSettingsObserver = new GestureNavigationSettingsObserver(
                mMainHandler, mContext, this::onNavigationSettingsChanged);

        mHandler = new Handler();
        updateCurrentUserResources();
    }

    private void updateEdgeHeightValue() {
        if (mDisplaySize == null) {
            return;
        }
        // mEdgeHeightSetting cant be range 0 - 3
        // 0 means full height
        // 1 measns half of the screen
        // 2 means lower third of the screen
        // 3 means lower sicth of the screen
        if (mEdgeHeightSetting == 0) {
            mEdgeHeight = mDisplaySize.y;
        } else if (mEdgeHeightSetting == 1) {
            mEdgeHeight = (mDisplaySize.y * 3) / 4;
        } else if (mEdgeHeightSetting == 2) {
            mEdgeHeight = mDisplaySize.y / 2;
        } else {
            mEdgeHeight = mDisplaySize.y / 4;
        }
    }

    public void setStateChangeCallback(Runnable callback) {
        mStateChangeCallback = callback;
    }

    public void setButtonForcedVisibleChangeCallback(Consumer<Boolean> callback) {
        mButtonForcedVisibleCallback = callback;
    }

    public int getEdgeWidthLeft() {
        return mEdgeWidthLeft;
    }

    public int getEdgeWidthRight() {
        return mEdgeWidthRight;
    }

    public void updateCurrentUserResources() {
        Resources res = mNavigationModeController.getCurrentUserContext().getResources();
        mEdgeWidthLeft = mGestureNavigationSettingsObserver.getLeftSensitivity(res);
        mEdgeWidthRight = mGestureNavigationSettingsObserver.getRightSensitivity(res);
        final boolean previousForcedVisible = mIsButtonForcedVisible;
        mIsButtonForcedVisible =
                mGestureNavigationSettingsObserver.areNavigationButtonForcedVisible();
        mIsBackGestureArrowEnabled = mGestureNavigationSettingsObserver.getBackArrowGesture();
        mIsEdgeHapticEnabled = mGestureNavigationSettingsObserver.getEdgeHapticEnabled();
        if (previousForcedVisible != mIsButtonForcedVisible
                && mButtonForcedVisibleCallback != null) {
            mButtonForcedVisibleCallback.accept(mIsButtonForcedVisible);
        }
        mIsBackGestureAllowed = !mIsButtonForcedVisible;

        mLeftLongSwipeAction = mGestureNavigationSettingsObserver.getLeftLongSwipeAction();
        mRightLongSwipeAction = mGestureNavigationSettingsObserver.getRightLongSwipeAction();
        mIsExtendedSwipe = mGestureNavigationSettingsObserver.getIsExtendedSwipe();
        mLeftVerticalSwipeAction = mGestureNavigationSettingsObserver.getLeftLSwipeAction();
        mRightVerticalSwipeAction = mGestureNavigationSettingsObserver.getRightLSwipeAction();
        if (mEdgeBackPlugin != null) {
            mEdgeBackPlugin.setLongSwipeEnabled(mIsExtendedSwipe);
        }

        final DisplayMetrics dm = res.getDisplayMetrics();
        final float defaultGestureHeight = res.getDimension(
                com.android.internal.R.dimen.navigation_bar_gesture_height) / dm.density;
        final float gestureHeight = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.BACK_GESTURE_BOTTOM_HEIGHT,
                defaultGestureHeight);
        mBottomGestureHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, gestureHeight,
                dm);

        // Set the minimum bounds to activate ML to 12dp or the minimum of configured values
        mMLEnableWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12.0f, dm);
        if (mMLEnableWidth > mEdgeWidthRight) mMLEnableWidth = mEdgeWidthRight;
        if (mMLEnableWidth > mEdgeWidthLeft) mMLEnableWidth = mEdgeWidthLeft;

        final TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, BACK_GESTURE_HEIGHT);

        // Reduce the default touch slop to ensure that we can intercept the gesture
        // before the app starts to react to it.
        // TODO(b/130352502) Tune this value and extract into a constant
        final float backGestureSlop = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_SYSTEMUI,
                        SystemUiDeviceConfigFlags.BACK_GESTURE_SLOP_MULTIPLIER, 0.75f);
        mTouchSlop = mViewConfiguration.getScaledTouchSlop() * backGestureSlop;
        mBackSwipeLinearThreshold = res.getDimension(
                R.dimen.navigation_edge_action_progress_threshold);
        mNonLinearFactor = getDimenFloat(res,
                R.dimen.back_progress_non_linear_factor);
        updateBackAnimationThresholds();
    }

    private float getDimenFloat(Resources res, @DimenRes int resId) {
        TypedValue typedValue = new TypedValue();
        res.getValue(resId, typedValue, true);
        return typedValue.getFloat();
    }

    public void updateNavigationBarOverlayExcludeRegion(Rect exclude) {
        mNavBarOverlayExcludedBounds.set(exclude);
    }

    private void onNavigationSettingsChanged() {
        boolean wasBackAllowed = isHandlingGestures();
        updateCurrentUserResources();
        if (mStateChangeCallback != null && wasBackAllowed != isHandlingGestures()) {
            mStateChangeCallback.run();
        }
    }

    /**
     * Called when the nav/task bar is attached.
     */
    public void onNavBarAttached() {
        mIsAttached = true;
        mOverviewProxyService.addCallback(mQuickSwitchListener);
        mSysUiState.addCallback(mSysUiStateCallback);
        if (mIsTrackpadGestureFeaturesEnabled) {
            mInputManager.registerInputDeviceListener(mInputDeviceListener, mMainHandler);
            int [] inputDevices = mInputManager.getInputDeviceIds();
            for (int inputDeviceId : inputDevices) {
                mInputDeviceListener.onInputDeviceAdded(inputDeviceId);
            }
        }
        updateIsEnabled();
        mUserTracker.addCallback(mUserChangedCallback, mMainExecutor);
    }

    /**
     * Called when the nav/task bar is detached.
     */
    public void onNavBarDetached() {
        mIsAttached = false;
        mOverviewProxyService.removeCallback(mQuickSwitchListener);
        mSysUiState.removeCallback(mSysUiStateCallback);
        mInputManager.unregisterInputDeviceListener(mInputDeviceListener);
        updateIsEnabled();
        mUserTracker.removeCallback(mUserChangedCallback);
    }

    /**
     * @see NavigationModeController.ModeChangedListener#onNavigationModeChanged
     */
    public void onNavigationModeChanged(int mode) {
        Trace.beginSection("EdgeBackGestureHandler#onNavigationModeChanged");
        try {
            mUsingThreeButtonNav = QuickStepContract.isLegacyMode(mode);
            mInGestureNavMode = QuickStepContract.isGesturalMode(mode);
            updateIsEnabled();
            updateCurrentUserResources();
        } finally {
            Trace.endSection();
        }
    }

    private void vibrateBack(boolean light) {
        AsyncTask.execute(() ->
            mVibrator.vibrate(VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK, true  /* fallback */)));
    }

    public void onNavBarTransientStateChanged(boolean isTransient) {
        mIsNavBarShownTransiently = isTransient;
    }

    public void setBlockedGesturalNavigation(boolean blocked) {
        mBlockedGesturalNavigation = blocked;
    }

    private void disposeInputChannel() {
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
    }

    private void updateIsEnabled() {
        try {
            Trace.beginSection("EdgeBackGestureHandler#updateIsEnabled");

            mIsGestureHandlingEnabled =
                    mInGestureNavMode || (mIsTrackpadGestureFeaturesEnabled && mUsingThreeButtonNav
                            && mIsTrackpadConnected);
            boolean isEnabled = mIsAttached && mIsGestureHandlingEnabled;
            if (isEnabled == mIsEnabled) {
                return;
            }
            mIsEnabled = isEnabled;
            disposeInputChannel();

            if (mEdgeBackPlugin != null) {
                mEdgeBackPlugin.onDestroy();
                mEdgeBackPlugin = null;
            }

            if (!mIsEnabled) {
                mGestureNavigationSettingsObserver.unregister();
                if (DEBUG_MISSING_GESTURE) {
                    Log.d(DEBUG_MISSING_GESTURE_TAG, "Unregister display listener");
                }
                mPluginManager.removePluginListener(this);
                TaskStackChangeListeners.getInstance().unregisterTaskStackListener(
                        mTaskStackListener);
                DeviceConfig.removeOnPropertiesChangedListener(mOnPropertiesChangedListener);
                mPipOptional.ifPresent(pip -> pip.setOnIsInPipStateChangedListener(null));

                try {
                    mWindowManagerService.unregisterSystemGestureExclusionListener(
                            mGestureExclusionListener, mDisplayId);
                } catch (RemoteException | IllegalArgumentException e) {
                    Log.e(TAG, "Failed to unregister window manager callbacks", e);
                }

            } else {
                mGestureNavigationSettingsObserver.register();
                updateDisplaySize();
                if (DEBUG_MISSING_GESTURE) {
                    Log.d(DEBUG_MISSING_GESTURE_TAG, "Register display listener");
                }
                TaskStackChangeListeners.getInstance().registerTaskStackListener(
                        mTaskStackListener);
                DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                        mMainExecutor::execute, mOnPropertiesChangedListener);
                mPipOptional.ifPresent(pip -> pip.setOnIsInPipStateChangedListener(
                        mOnIsInPipStateChangedListener));
                mDesktopModeOptional.ifPresent(
                        dm -> dm.addDesktopGestureExclusionRegionListener(
                                mDesktopCornersChangedListener, mMainExecutor));

                try {
                    mWindowManagerService.registerSystemGestureExclusionListener(
                            mGestureExclusionListener, mDisplayId);
                } catch (RemoteException | IllegalArgumentException e) {
                    Log.e(TAG, "Failed to register window manager callbacks", e);
                }

                // Register input event receiver
                mInputMonitor = mContext.getSystemService(InputManager.class).monitorGestureInput(
                        "edge-swipe", mDisplayId);
                mInputEventReceiver = new InputChannelCompat.InputEventReceiver(
                        mInputMonitor.getInputChannel(), Looper.getMainLooper(),
                        Choreographer.getInstance(), event -> {
                            if (!mBlockedGesturalNavigation) {
                                onInputEvent(event);
                            }
                        });

                // Add a nav bar panel window
                mIsNewBackAffordanceEnabled = mFeatureFlags.isEnabled(Flags.NEW_BACK_AFFORDANCE);
                resetEdgeBackPlugin();
                mPluginManager.addPluginListener(
                        this, NavigationEdgeBackPlugin.class, /*allowMultiple=*/ false);
            }
            // Update the ML model resources.
            updateMLModelState();
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void onPluginConnected(NavigationEdgeBackPlugin plugin, Context context) {
        setEdgeBackPlugin(plugin);
    }

    @Override
    public void onPluginDisconnected(NavigationEdgeBackPlugin plugin) {
        resetEdgeBackPlugin();
    }

    private void resetEdgeBackPlugin() {
        if (mIsNewBackAffordanceEnabled) {
            setEdgeBackPlugin(
                    mBackPanelControllerFactory.create(mContext));
        } else {
            setEdgeBackPlugin(mNavBarEdgePanelProvider.get());
        }
    }

    private void setEdgeBackPlugin(NavigationEdgeBackPlugin edgeBackPlugin) {
        try {
            Trace.beginSection("setEdgeBackPlugin");
            mEdgeBackPlugin = edgeBackPlugin;
            mEdgeBackPlugin.setBackCallback(mBackCallback);
            mEdgeBackPlugin.setLayoutParams(createLayoutParams());
            mEdgeBackPlugin.setLongSwipeEnabled(mIsExtendedSwipe);
            updateDisplaySize();
        } finally {
            Trace.endSection();
        }
    }

    public boolean isHandlingGestures() {
        return mIsEnabled && mIsBackGestureAllowed;
    }

    public boolean isButtonForcedVisible() {
        return mIsButtonForcedVisible;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (BACK_GESTURE_HEIGHT.equals(key)) {
            mEdgeHeightSetting = TunerService.parseInteger(newValue, 0);
            updateEdgeHeightValue();
        }
    }

    /**
     * Update the PiP bounds, used for exclusion calculation.
     */
    public void setPipStashExclusionBounds(Rect bounds) {
        mPipExcludedBounds.set(bounds);
    }

    private WindowManager.LayoutParams createLayoutParams() {
        Resources resources = mContext.getResources();
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.navigation_edge_panel_width),
                resources.getDimensionPixelSize(R.dimen.navigation_edge_panel_height),
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        layoutParams.accessibilityTitle = mContext.getString(R.string.nav_bar_edge_panel);
        layoutParams.windowAnimations = 0;
        layoutParams.privateFlags |=
                (WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION);
        layoutParams.setTitle(TAG + mContext.getDisplayId());
        layoutParams.setFitInsetsTypes(0 /* types */);
        layoutParams.setTrustedOverlay();
        return layoutParams;
    }

    private void onInputEvent(InputEvent ev) {
        if (!(ev instanceof MotionEvent)) return;
        MotionEvent event = (MotionEvent) ev;
        onMotionEvent(event);
    }

    private void updateMLModelState() {
        boolean newState = mIsGestureHandlingEnabled && DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.USE_BACK_GESTURE_ML_MODEL, false);

        if (newState == mUseMLModel) {
            return;
        }

        mUseMLModel = newState;

        if (mUseMLModel) {
            Assert.isMainThread();
            if (mMLModelIsLoading) {
                Log.d(TAG, "Model tried to load while already loading.");
                return;
            }
            mMLModelIsLoading = true;
            mBackgroundExecutor.execute(() -> loadMLModel());
        } else if (mBackGestureTfClassifierProvider != null) {
            mBackGestureTfClassifierProvider.release();
            mBackGestureTfClassifierProvider = null;
            mVocab = null;
        }
    }

    private void loadMLModel() {
        BackGestureTfClassifierProvider provider = mBackGestureTfClassifierProviderProvider.get();
        float threshold = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.BACK_GESTURE_ML_MODEL_THRESHOLD, 0.9f);
        Map<String, Integer> vocab = null;
        if (provider != null && !provider.isActive()) {
            provider.release();
            provider = null;
            Log.w(TAG, "Cannot load model because it isn't active");
        }
        if (provider != null) {
            Trace.beginSection("EdgeBackGestureHandler#loadVocab");
            vocab = provider.loadVocab(mContext.getAssets());
            Trace.endSection();
        }
        BackGestureTfClassifierProvider finalProvider = provider;
        Map<String, Integer> finalVocab = vocab;
        mMainExecutor.execute(() -> onMLModelLoadFinished(finalProvider, finalVocab, threshold));
    }

    private void onMLModelLoadFinished(BackGestureTfClassifierProvider provider,
            Map<String, Integer> vocab, float threshold) {
        Assert.isMainThread();
        mMLModelIsLoading = false;
        if (!mUseMLModel) {
            // This can happen if the user disables Gesture Nav while the model is loading.
            if (provider != null) {
                provider.release();
            }
            Log.d(TAG, "Model finished loading but isn't needed.");
            return;
        }
        mBackGestureTfClassifierProvider = provider;
        mVocab = vocab;
        mMLModelThreshold = threshold;
    }

    private int getBackGesturePredictionsCategory(int x, int y, int app) {
        BackGestureTfClassifierProvider provider = mBackGestureTfClassifierProvider;
        if (provider == null || app == -1) {
            return -1;
        }
        int distanceFromEdge;
        int location;
        if (x <= mDisplaySize.x / 2.0) {
            location = 1;  // left
            distanceFromEdge = x;
        } else {
            location = 2;  // right
            distanceFromEdge = mDisplaySize.x - x;
        }

        Object[] featuresVector = {
            new long[]{(long) mDisplaySize.x},
            new long[]{(long) distanceFromEdge},
            new long[]{(long) location},
            new long[]{(long) app},
            new long[]{(long) y},
        };

        mMLResults = provider.predict(featuresVector);
        if (mMLResults == -1) {
            return -1;
        }
        return mMLResults >= mMLModelThreshold ? 1 : 0;
    }

    private boolean isWithinInsets(int x, int y) {
        // Disallow if we are in the bottom gesture area
        if (y >= (mDisplaySize.y - mBottomGestureHeight)) {
            return false;
        }
        if (mEdgeHeight != 0) {
            if (y < (mDisplaySize.y - mBottomGestureHeight - mEdgeHeight)) {
                return false;
            }
        }
        // If the point is way too far (twice the margin), it is
        // not interesting to us for logging purposes, nor we
        // should process it.  Simply return false and keep
        // mLogGesture = false.
        if (x > 2 * (mEdgeWidthLeft + mLeftInset)
                && x < (mDisplaySize.x - 2 * (mEdgeWidthRight + mRightInset))) {
            return false;
        }
        return true;
    }

    private boolean isValidTrackpadBackGesture(boolean isTrackpadEvent) {
        if (!isTrackpadEvent) {
            return false;
        }
        // for trackpad gestures, unless the whole screen is excluded region, 3-finger swipe
        // gestures are allowed even if the cursor is in the excluded region.
        WindowInsets windowInsets = mWindowManager.getCurrentWindowMetrics().getWindowInsets();
        Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
        final Rect excludeBounds = mExcludeRegion.getBounds();
        return !excludeBounds.contains(insets.left, insets.top, mDisplaySize.x - insets.right,
                mDisplaySize.y - insets.bottom);
    }

    private boolean isTrackpadDevice(int deviceId) {
        InputDevice inputDevice = mInputManager.getInputDevice(deviceId);
        if (inputDevice == null) {
            return false;
        }
        return inputDevice.getSources() == (InputDevice.SOURCE_MOUSE
                | InputDevice.SOURCE_TOUCHPAD);
    }

    private boolean desktopExcludeRegionContains(int x, int y) {
        return mDesktopModeExcludeRegion.contains(x, y);
    }

    private boolean isWithinTouchRegion(int x, int y) {
        // If the point is inside the PiP or Nav bar overlay excluded bounds, then ignore the back
        // gesture
        final boolean isInsidePip = mIsInPip && mPipExcludedBounds.contains(x, y);
        final boolean isInDesktopExcludeRegion = desktopExcludeRegionContains(x, y);
        if (isInsidePip || isInDesktopExcludeRegion
                || mNavBarOverlayExcludedBounds.contains(x, y)) {
            return false;
        }

        int app = -1;
        if (mVocab != null) {
            app = mVocab.getOrDefault(mPackageName, -1);
        }

        // Denotes whether we should proceed with the gesture. Even if it is false, we may want to
        // log it assuming it is not invalid due to exclusion.
        boolean withinRange = x < mEdgeWidthLeft + mLeftInset
                || x >= (mDisplaySize.x - mEdgeWidthRight - mRightInset);
        if (withinRange) {
            int results = -1;

            // Check if we are within the tightest bounds beyond which we would not need to run the
            // ML model
            boolean withinMinRange = x < mMLEnableWidth + mLeftInset
                    || x >= (mDisplaySize.x - mMLEnableWidth - mRightInset);
            if (!withinMinRange && mUseMLModel && !mMLModelIsLoading
                    && (results = getBackGesturePredictionsCategory(x, y, app)) != -1) {
                withinRange = (results == 1);
            }
        }

        // For debugging purposes
        mPredictionLog.log(String.format("Prediction [%d,%d,%d,%d,%f,%d]",
                System.currentTimeMillis(), x, y, app, mMLResults, withinRange ? 1 : 0));

        // Always allow if the user is in a transient sticky immersive state
        if (mIsNavBarShownTransiently) {
            mLogGesture = true;
            return withinRange;
        }

        /* If Launcher is showing and wants to block back gesture, let's still trigger our custom
        swipe actions at the very bottom of the screen, because we are cool.*/
        boolean isInExcludedRegion = false;
        // still block extended swipe if keyboard is showing, to avoid conflicts with IME gestures
        if (!mImeVisible && (
                mIsExtendedSwipe
                || (mLeftLongSwipeAction != 0 && mIsOnLeftEdge)  || (mRightLongSwipeAction != 0 && !mIsOnLeftEdge))) {
            isInExcludedRegion= mExcludeRegion.contains(x, y)
                && y < ((mDisplaySize.y / 4) * 3);
        } else {
            isInExcludedRegion= mExcludeRegion.contains(x, y);
        }
        if (isInExcludedRegion) {
            if (withinRange) {
                // We don't have the end point for logging purposes.
                mEndPoint.x = -1;
                mEndPoint.y = -1;
                mLogGesture = true;
                logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_EXCLUDED);
            }
            return false;
        }

        mInRejectedExclusion = mUnrestrictedExcludeRegion.contains(x, y);
        mLogGesture = true;
        return withinRange;
    }

    public void setImeVisible(boolean visible) {
        mImeVisible = visible;
    }

    private void cancelGesture(MotionEvent ev) {
        // Send action cancel to reset all the touch events
        mHandler.removeCallbacksAndMessages(null);
        mAllowGesture = false;
        mLogGesture = false;
        mInRejectedExclusion = false;
        MotionEvent cancelEv = MotionEvent.obtain(ev);
        cancelEv.setAction(MotionEvent.ACTION_CANCEL);
        mEdgeBackPlugin.onMotionEvent(cancelEv);
        dispatchToBackAnimation(cancelEv);
        cancelEv.recycle();
    }

    private void logGesture(int backType) {
        if (!mLogGesture) {
            return;
        }
        mLogGesture = false;
        String logPackageName = "";
        Map<String, Integer> vocab = mVocab;
        // Due to privacy, only top 100 most used apps by all users can be logged.
        if (mUseMLModel && vocab != null && vocab.containsKey(mPackageName)
                && vocab.get(mPackageName) < 100) {
            logPackageName = mPackageName;
        }
        SysUiStatsLog.write(SysUiStatsLog.BACK_GESTURE_REPORTED_REPORTED, backType,
                (int) mDownPoint.y, mIsOnLeftEdge
                        ? SysUiStatsLog.BACK_GESTURE__X_LOCATION__LEFT
                        : SysUiStatsLog.BACK_GESTURE__X_LOCATION__RIGHT,
                (int) mDownPoint.x, (int) mDownPoint.y,
                (int) mEndPoint.x, (int) mEndPoint.y,
                mEdgeWidthLeft + mLeftInset,
                mDisplaySize.x - (mEdgeWidthRight + mRightInset),
                mUseMLModel ? mMLResults : -2, logPackageName,
                mIsTrackpadThreeFingerSwipe ? SysUiStatsLog.BACK_GESTURE__INPUT_TYPE__TRACKPAD
                        : SysUiStatsLog.BACK_GESTURE__INPUT_TYPE__TOUCH);
    }

    private void onMotionEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (DEBUG_MISSING_GESTURE) {
                Log.d(DEBUG_MISSING_GESTURE_TAG, "Start gesture: " + ev);
            }

            mIsTrackpadThreeFingerSwipe = isTrackpadThreeFingerSwipe(
                    mIsTrackpadGestureFeaturesEnabled, ev);

            // ACTION_UP or ACTION_CANCEL is not guaranteed to be called before a new
            // ACTION_DOWN, in that case we should just reuse the old instance.
            mVelocityTracker.clear();

            // Verify if this is in within the touch region and we aren't in immersive mode, and
            // either the bouncer is showing or the notification panel is hidden
            mInputEventReceiver.setBatchingEnabled(false);
            if (mIsTrackpadThreeFingerSwipe) {
                // Since trackpad gestures don't have zones, this will be determined later by the
                // direction of the gesture. {@code mIsOnLeftEdge} is set to false to begin with.
                mDeferSetIsOnLeftEdge = true;
                mIsOnLeftEdge = false;
            } else {
                mIsOnLeftEdge = ev.getX() <= mEdgeWidthLeft + mLeftInset;
            }
            mMLResults = 0;
            mLogGesture = false;
            mInRejectedExclusion = false;
            boolean isWithinInsets = isWithinInsets((int) ev.getX(), (int) ev.getY());
            boolean isBackAllowedCommon = !mDisabledForQuickstep && mIsBackGestureAllowed
                    && !mGestureBlockingActivityRunning
                    && !QuickStepContract.isBackGestureDisabled(mSysUiFlags,
                            mIsTrackpadThreeFingerSwipe)
                    && !isTrackpadScroll(mIsTrackpadGestureFeaturesEnabled, ev);
            if (mIsTrackpadThreeFingerSwipe) {
                // Trackpad back gestures don't have zones, so we don't need to check if the down
                // event is within insets.
                mAllowGesture = isBackAllowedCommon && isValidTrackpadBackGesture(
                        true /* isTrackpadEvent */);
            } else {
                mAllowGesture = isBackAllowedCommon && !mUsingThreeButtonNav && isWithinInsets
                    && isWithinTouchRegion((int) ev.getX(), (int) ev.getY())
                    && !isButtonPressFromTrackpad(ev);
            }
            if (mAllowGesture) {
                mEdgeBackPlugin.setIsLeftPanel(mIsOnLeftEdge);
                mEdgeBackPlugin.setBackArrowVisibility(mIsBackGestureArrowEnabled);
                mEdgeBackPlugin.setEdgeHapticEnabled(mIsEdgeHapticEnabled);
                mEdgeBackPlugin.onMotionEvent(ev);
                dispatchToBackAnimation(ev);
            }
            if (mLogGesture || mIsTrackpadThreeFingerSwipe) {
                mDownPoint.set(ev.getX(), ev.getY());
                mEndPoint.set(-1, -1);
                mThresholdCrossed = false;
            }

            // For debugging purposes, only log edge points
            long curTime = System.currentTimeMillis();
            mTmpLogDate.setTime(curTime);
            String curTimeStr = mLogDateFormat.format(mTmpLogDate);
            (isWithinInsets ? mGestureLogInsideInsets : mGestureLogOutsideInsets).log(String.format(
                    "Gesture [%d [%s],alw=%B, t3fs=%B, left=%B, defLeft=%B, backAlw=%B, disbld=%B,"
                            + " qsDisbld=%b, blkdAct=%B, pip=%B,"
                            + " disp=%s, wl=%d, il=%d, wr=%d, ir=%d, excl=%s]",
                    curTime, curTimeStr, mAllowGesture, mIsTrackpadThreeFingerSwipe,
                    mIsOnLeftEdge, mDeferSetIsOnLeftEdge, mIsBackGestureAllowed,
                    QuickStepContract.isBackGestureDisabled(mSysUiFlags,
                            mIsTrackpadThreeFingerSwipe),
                    mDisabledForQuickstep, mGestureBlockingActivityRunning, mIsInPip, mDisplaySize,
                    mEdgeWidthLeft, mLeftInset, mEdgeWidthRight, mRightInset, mExcludeRegion));
            mStartX = ev.getX();
            mStartY = ev.getY();
        } else if (mAllowGesture || mLogGesture) {
            if (!mThresholdCrossed) {
                // mThresholdCrossed is true only after the first move event
                // then other events will go straight to "forward touch" line
                mEndPoint.x = (int) ev.getX();
                mEndPoint.y = (int) ev.getY();
                if (action == MotionEvent.ACTION_POINTER_DOWN && !mIsTrackpadThreeFingerSwipe) {
                    if (mAllowGesture) {
                        logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_MULTI_TOUCH);
                        if (DEBUG_MISSING_GESTURE) {
                            Log.d(DEBUG_MISSING_GESTURE_TAG, "Cancel back: multitouch");
                        }
                        // We do not support multi touch for back gesture
                        cancelGesture(ev);
                    }
                    mLogGesture = false;
                    return;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    if (mIsTrackpadThreeFingerSwipe && mDeferSetIsOnLeftEdge) {
                        // mIsOnLeftEdge is determined by the relative position between the down
                        // and the current motion event for trackpad gestures instead of zoning.
                        mIsOnLeftEdge = mEndPoint.x > mDownPoint.x;
                        mEdgeBackPlugin.setIsLeftPanel(mIsOnLeftEdge);
                        mDeferSetIsOnLeftEdge = false;
                    }

                    int elapsedTime = (int)(ev.getEventTime() - ev.getDownTime());
                    if (elapsedTime > mLongPressTimeout) {
                        if (mAllowGesture) {
                            logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_LONG_PRESS);
                            cancelGesture(ev);
                            if (DEBUG_MISSING_GESTURE) {
                                Log.d(DEBUG_MISSING_GESTURE_TAG, "Cancel back [longpress]: "
                                        + ev.getEventTime()
                                        + "  " + ev.getDownTime()
                                        + "  " + mLongPressTimeout);
                            }
                        }
                        mLogGesture = false;
                        return;
                    }
                    float dx = Math.abs(ev.getX() - mDownPoint.x);
                    float dy = Math.abs(ev.getY() - mDownPoint.y);
                    if (dy > dx && dy > mTouchSlop) {
                        if (mAllowGesture) {
                            logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_VERTICAL_MOVE);
                            cancelGesture(ev);
                            if (DEBUG_MISSING_GESTURE) {
                                Log.d(DEBUG_MISSING_GESTURE_TAG, "Cancel back [vertical move]: "
                                        + dy + "  " + dx + "  " + mTouchSlop);
                            }
                        }
                        mLogGesture = false;
                        return;
                    } else if (dx > dy && dx > mTouchSlop) {
                        if (mAllowGesture) {
                            mThresholdCrossed = true;
                            // Capture inputs
                            mInputMonitor.pilferPointers();
                            if (mBackAnimation != null) {
                                mBackAnimation.onPilferPointers();
                                // Notify FalsingManager that an intentional gesture has occurred.
                                mFalsingManager.isFalseTouch(BACK_GESTURE);
                            }
                            mInputEventReceiver.setBatchingEnabled(true);
                        } else {
                            logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_FAR_FROM_EDGE);
                        }
                    }
                }
            }

            boolean isUp = action == MotionEvent.ACTION_UP;
            boolean isCancel = action == MotionEvent.ACTION_CANCEL;
            boolean isMove = action == MotionEvent.ACTION_MOVE;
            float longSwipeThreshold = mDisplaySize.x * 0.45f;
            float touchTranslation = Math.abs(ev.getX() - mStartX);
            boolean almostLongSwipe = mIsExtendedSwipe && (touchTranslation > longSwipeThreshold);
            if (isMove && almostLongSwipe) {
                float deltaX = Math.abs(ev.getX() - mDownPoint.x);
                float deltaY = Math.abs(ev.getY() - mDownPoint.y);
                // give priority to horizontal (X) swipe
                if (deltaX  > (int)((mDisplaySize.x / 4) * 2.5f)) {
                    mLongSwipeAction.setIsVertical(false);
                }
                if (deltaY  > (mDisplaySize.y / 4)) {
                    mLongSwipeAction.setIsVertical(true);
                }
            }
            if (isUp && almostLongSwipe) {
                mLongSwipeAction.run();
                mHandler.removeCallbacksAndMessages(null);
            } else if (isUp || isCancel) {
                mHandler.removeCallbacksAndMessages(null);
            }

            if (mAllowGesture) {
                // forward touch
                mEdgeBackPlugin.onMotionEvent(ev);
                dispatchToBackAnimation(ev);
            }
        }
    }

    private boolean isButtonPressFromTrackpad(MotionEvent ev) {
        // We don't allow back for button press from the trackpad, and yet we do with a mouse.
        int sources = InputManager.getInstance().getInputDevice(ev.getDeviceId()).getSources();
        int sourceTrackpad = (SOURCE_MOUSE | SOURCE_TOUCHPAD);
        return (sources & sourceTrackpad) == sourceTrackpad && ev.getButtonState() != 0;
    }

    private void dispatchToBackAnimation(MotionEvent event) {
        if (mBackAnimation != null) {
            mVelocityTracker.addMovement(event);

            final float velocityX;
            final float velocityY;
            if (event.getAction() == MotionEvent.ACTION_UP) {
                // Compute the current velocity is expensive (see computeCurrentVelocity), so we
                // are only doing it when the user completes the gesture.
                int unitPixelPerSecond = 1000;
                int maxVelocity = mViewConfiguration.getScaledMaximumFlingVelocity();
                mVelocityTracker.computeCurrentVelocity(unitPixelPerSecond, maxVelocity);
                velocityX = mVelocityTracker.getXVelocity();
                velocityY = mVelocityTracker.getYVelocity();
            } else {
                velocityX = Float.NaN;
                velocityY = Float.NaN;
            }

            mBackAnimation.onBackMotion(
                    /* touchX = */ event.getX(),
                    /* touchY = */ event.getY(),
                    /* velocityX = */ velocityX,
                    /* velocityY = */ velocityY,
                    /* keyAction = */ event.getActionMasked(),
                    /* swipeEdge = */ mIsOnLeftEdge ? BackEvent.EDGE_LEFT : BackEvent.EDGE_RIGHT);
        }
    }

    private SwipeRunnable mLongSwipeAction = new SwipeRunnable();
    private class SwipeRunnable implements Runnable {
        private boolean mIsVertical;

        public void setIsVertical(boolean vertical) {
            mIsVertical = vertical;
        }

        @Override
        public void run() {
            triggerAction(mIsVertical);
        }
    }

    private void prepareForAction() {
        // cancel touch event then trigger the action
        final long now = SystemClock.uptimeMillis();
        final MotionEvent ev = MotionEvent.obtain(now, now,
                MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
        cancelGesture(ev);
        if (mIsEdgeHapticEnabled) {
            vibrateBack(false /* HEAVY_CLICK */);
        }
    }

    private void triggerAction(boolean isVertical) {
        int action = mIsOnLeftEdge ? (isVertical ? mLeftVerticalSwipeAction : mLeftLongSwipeAction)
                : (isVertical ? mRightVerticalSwipeAction : mRightLongSwipeAction);

        if (action == 0) return;

        prepareForAction();

        switch (action) {
            case 0: // No action
            default:
                break;
            case 1: // Voice search
                CherishUtils.launchVoiceSearch(mContext);
                break;
            case 2: // Camera
                CherishUtils.launchCamera(mContext);
                break;
            case 3: // Flashlight
                CherishUtils.toggleCameraFlash();
                break;
            case 4: // Application
                launchApp(mContext, mIsOnLeftEdge, isVertical);
                break;
            case 5: // Volume panel
                CherishUtils.toggleVolumePanel(mContext);
                break;
            case 6: // Screen off
                CherishUtils.switchScreenOff(mContext);
                break;
            case 7: // Screenshot
                CherishUtils.takeScreenshot(true);
                break;
            case 8: // Notification panel
                CherishUtils.toggleNotifications();
                break;
            case 9: // QS panel
                CherishUtils.toggleQsPanel();
                break;
            case 10: // Clear notifications
                CherishUtils.clearAllNotifications();
                break;
            case 11: // Ringer modes
                CherishUtils.toggleRingerModes(mContext);
                break;
            case 12: // Kill app
                CherishUtils.killForegroundApp();
                break;
            case 13: // Switch recent app
                CherishUtils.switchToLastApp(mContext);
                break;
            case 14: // Powermenu
                CherishUtils.showPowerMenu();
                break;
            case 15: // Partial Screenshot
                CherishUtils.takeScreenshot(false);
                break;
            case 16: // Recents
                CherishUtils.sendKeycode(KeyEvent.KEYCODE_APP_SWITCH);
                break;
            case 17: // Go forward
                CherishUtils.sendKeycode(KeyEvent.KEYCODE_FORWARD);
                break;
            case 18: // Menu
                CherishUtils.sendKeycode(KeyEvent.KEYCODE_MENU);
                break;
        }
    }

    private void launchApp(Context context, boolean leftEdgeApp, boolean isVerticalSwipe) {
        Intent intent = null;
        String packageName = Settings.System.getStringForUser(context.getContentResolver(),
                leftEdgeApp ? (isVerticalSwipe ? Settings.System.LEFT_VERTICAL_BACK_SWIPE_APP_ACTION : Settings.System.LEFT_LONG_BACK_SWIPE_APP_ACTION)
                : (isVerticalSwipe ? Settings.System.RIGHT_VERTICAL_BACK_SWIPE_APP_ACTION : Settings.System.RIGHT_LONG_BACK_SWIPE_APP_ACTION),
                UserHandle.USER_CURRENT);
        String activity = Settings.System.getStringForUser(context.getContentResolver(),
                leftEdgeApp ? (isVerticalSwipe ? Settings.System.LEFT_VERTICAL_BACK_SWIPE_APP_ACTIVITY_ACTION : Settings.System.LEFT_LONG_BACK_SWIPE_APP_ACTIVITY_ACTION)
                : (isVerticalSwipe ? Settings.System.RIGHT_VERTICAL_BACK_SWIPE_APP_ACTIVITY_ACTION : Settings.System.RIGHT_LONG_BACK_SWIPE_APP_ACTIVITY_ACTION),
                UserHandle.USER_CURRENT);
        boolean launchActivity = activity != null && !TextUtils.equals("NONE", activity);
        try {
            if (launchActivity) {
                intent = new Intent(Intent.ACTION_MAIN);
                intent.setClassName(packageName, activity);
            } else {
                intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Exception e) {}
    }

    private void updateDisabledForQuickstep(Configuration newConfig) {
        int rotation = newConfig.windowConfiguration.getRotation();
        mDisabledForQuickstep = mStartingQuickstepRotation > -1 &&
                mStartingQuickstepRotation != rotation;
    }

    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        if (mStartingQuickstepRotation > -1) {
            updateDisabledForQuickstep(newConfig);
        }

        // TODO(b/243765256): Disable this logging once b/243765256 is fixed.
        Log.i(DEBUG_MISSING_GESTURE_TAG, "Config changed: newConfig=" + newConfig
                + " lastReportedConfig=" + mLastReportedConfig);
        mLastReportedConfig.updateFrom(newConfig);
        updateDisplaySize();
    }

    private void updateDisplaySize() {
        Rect bounds = mLastReportedConfig.windowConfiguration.getMaxBounds();
        mDisplaySize.set(bounds.width(), bounds.height());
        if (DEBUG_MISSING_GESTURE) {
            Log.d(DEBUG_MISSING_GESTURE_TAG, "Update display size: mDisplaySize=" + mDisplaySize);
        }

        if (mEdgeBackPlugin != null) {
            mEdgeBackPlugin.setDisplaySize(mDisplaySize);
        }
        updateBackAnimationThresholds();
        updateEdgeHeightValue();
    }

    private void updateBackAnimationThresholds() {
        if (mBackAnimation == null) {
            return;
        }
        int maxDistance = mDisplaySize.x;
        float linearDistance = Math.min(maxDistance, mBackSwipeLinearThreshold);
        mBackAnimation.setSwipeThresholds(linearDistance, maxDistance, mNonLinearFactor);
    }

    private boolean sendEvent(int action, int code) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(when, when, action, code, 0 /* repeat */,
                0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_NAVIGATION_BAR);

        ev.setDisplayId(mContext.getDisplay().getDisplayId());
        return mContext.getSystemService(InputManager.class)
                .injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    public void setInsets(int leftInset, int rightInset) {
        mLeftInset = leftInset;
        mRightInset = rightInset;
        if (mEdgeBackPlugin != null) {
            mEdgeBackPlugin.setInsets(leftInset, rightInset);
        }
    }

    public void dump(PrintWriter pw) {
        pw.println("EdgeBackGestureHandler:");
        pw.println("  mIsEnabled=" + mIsEnabled);
        pw.println("  mIsAttached=" + mIsAttached);
        pw.println("  mIsBackGestureAllowed=" + mIsBackGestureAllowed);
        pw.println("  mIsGestureHandlingEnabled=" + mIsGestureHandlingEnabled);
        pw.println("  mIsNavBarShownTransiently=" + mIsNavBarShownTransiently);
        pw.println("  mGestureBlockingActivityRunning=" + mGestureBlockingActivityRunning);
        pw.println("  mAllowGesture=" + mAllowGesture);
        pw.println("  mUseMLModel=" + mUseMLModel);
        pw.println("  mDisabledForQuickstep=" + mDisabledForQuickstep);
        pw.println("  mStartingQuickstepRotation=" + mStartingQuickstepRotation);
        pw.println("  mInRejectedExclusion=" + mInRejectedExclusion);
        pw.println("  mExcludeRegion=" + mExcludeRegion);
        pw.println("  mUnrestrictedExcludeRegion=" + mUnrestrictedExcludeRegion);
        pw.println("  mIsInPip=" + mIsInPip);
        pw.println("  mPipExcludedBounds=" + mPipExcludedBounds);
        pw.println("  mDesktopModeExclusionRegion=" + mDesktopModeExcludeRegion);
        pw.println("  mNavBarOverlayExcludedBounds=" + mNavBarOverlayExcludedBounds);
        pw.println("  mEdgeWidthLeft=" + mEdgeWidthLeft);
        pw.println("  mEdgeWidthRight=" + mEdgeWidthRight);
        pw.println("  mLeftInset=" + mLeftInset);
        pw.println("  mRightInset=" + mRightInset);
        pw.println("  mMLEnableWidth=" + mMLEnableWidth);
        pw.println("  mMLModelThreshold=" + mMLModelThreshold);
        pw.println("  mTouchSlop=" + mTouchSlop);
        pw.println("  mBottomGestureHeight=" + mBottomGestureHeight);
        pw.println("  mPredictionLog=" + String.join("\n", mPredictionLog));
        pw.println("  mGestureLogInsideInsets=" + String.join("\n", mGestureLogInsideInsets));
        pw.println("  mGestureLogOutsideInsets=" + String.join("\n", mGestureLogOutsideInsets));
        pw.println("  mIsTrackpadConnected=" + mIsTrackpadConnected);
        pw.println("  mUsingThreeButtonNav=" + mUsingThreeButtonNav);
        pw.println("  mEdgeBackPlugin=" + mEdgeBackPlugin);
        if (mEdgeBackPlugin != null) {
            mEdgeBackPlugin.dump(pw);
        }
    }

    private boolean isGestureBlockingActivityRunning() {
        ActivityManager.RunningTaskInfo runningTask =
                ActivityManagerWrapper.getInstance().getRunningTask();
        ComponentName topActivity = runningTask == null ? null : runningTask.topActivity;
        if (topActivity != null) {
            mPackageName = topActivity.getPackageName();
        } else {
            mPackageName = "_UNKNOWN";
        }
        return topActivity != null && mGestureBlockingActivities.contains(topActivity);
    }

    public void setBackAnimation(BackAnimation backAnimation) {
        mBackAnimation = backAnimation;
        updateBackAnimationThresholds();
        if (mLightBarControllerProvider.get() != null) {
            mBackAnimation.setStatusBarCustomizer((appearance) -> {
                mMainExecutor.execute(() ->
                        mLightBarControllerProvider.get()
                                .customizeStatusBarAppearance(appearance));
            });
        }
    }

    /**
     * Injectable instance to create a new EdgeBackGestureHandler.
     *
     * Necessary because we don't have good handling of per-display contexts at the moment. With
     * this, you can pass in a specific context that knows what display it is in.
     */
    public static class Factory {
        private final OverviewProxyService mOverviewProxyService;
        private final SysUiState mSysUiState;
        private final PluginManager mPluginManager;
        private final Executor mExecutor;
        private final Handler mHandler;
        private final Executor mBackgroundExecutor;
        private final UserTracker mUserTracker;
        private final NavigationModeController mNavigationModeController;
        private final BackPanelController.Factory mBackPanelControllerFactory;
        private final ViewConfiguration mViewConfiguration;
        private final WindowManager mWindowManager;
        private final IWindowManager mWindowManagerService;
        private final InputManager mInputManager;
        private final Optional<Pip> mPipOptional;
        private final Optional<DesktopMode> mDesktopModeOptional;
        private final FalsingManager mFalsingManager;
        private final Provider<NavigationBarEdgePanel> mNavBarEdgePanelProvider;
        private final Provider<BackGestureTfClassifierProvider>
                mBackGestureTfClassifierProviderProvider;
        private final FeatureFlags mFeatureFlags;
        private final Provider<LightBarController> mLightBarControllerProvider;

        @Inject
        public Factory(OverviewProxyService overviewProxyService,
                       SysUiState sysUiState,
                       PluginManager pluginManager,
                       @Main Executor executor,
                       @Main Handler handler,
                       @Background Executor backgroundExecutor,
                       UserTracker userTracker,
                       NavigationModeController navigationModeController,
                       BackPanelController.Factory backPanelControllerFactory,
                       ViewConfiguration viewConfiguration,
                       WindowManager windowManager,
                       IWindowManager windowManagerService,
                       InputManager inputManager,
                       Optional<Pip> pipOptional,
                       Optional<DesktopMode> desktopModeOptional,
                       FalsingManager falsingManager,
                       Provider<NavigationBarEdgePanel> navBarEdgePanelProvider,
                       Provider<BackGestureTfClassifierProvider>
                               backGestureTfClassifierProviderProvider,
                       FeatureFlags featureFlags,
                       Provider<LightBarController> lightBarControllerProvider) {
            mOverviewProxyService = overviewProxyService;
            mSysUiState = sysUiState;
            mPluginManager = pluginManager;
            mExecutor = executor;
            mHandler = handler;
            mBackgroundExecutor = backgroundExecutor;
            mUserTracker = userTracker;
            mNavigationModeController = navigationModeController;
            mBackPanelControllerFactory = backPanelControllerFactory;
            mViewConfiguration = viewConfiguration;
            mWindowManager = windowManager;
            mWindowManagerService = windowManagerService;
            mInputManager = inputManager;
            mPipOptional = pipOptional;
            mDesktopModeOptional = desktopModeOptional;
            mFalsingManager = falsingManager;
            mNavBarEdgePanelProvider = navBarEdgePanelProvider;
            mBackGestureTfClassifierProviderProvider = backGestureTfClassifierProviderProvider;
            mFeatureFlags = featureFlags;
            mLightBarControllerProvider = lightBarControllerProvider;
        }

        /** Construct a {@link EdgeBackGestureHandler}. */
        public EdgeBackGestureHandler create(Context context) {
            return new EdgeBackGestureHandler(
                    context,
                    mOverviewProxyService,
                    mSysUiState,
                    mPluginManager,
                    mExecutor,
                    mHandler,
                    mBackgroundExecutor,
                    mUserTracker,
                    mNavigationModeController,
                    mBackPanelControllerFactory,
                    mViewConfiguration,
                    mWindowManager,
                    mWindowManagerService,
                    mInputManager,
                    mPipOptional,
                    mDesktopModeOptional,
                    mFalsingManager,
                    mNavBarEdgePanelProvider,
                    mBackGestureTfClassifierProviderProvider,
                    mFeatureFlags,
                    mLightBarControllerProvider);
        }
    }

    private static class LogArray extends ArrayDeque<String> {
        private final int mLength;

        LogArray(int length) {
            mLength = length;
        }

        void log(String message) {
            if (size() >= mLength) {
                removeFirst();
            }
            addLast(message);
            if (DEBUG_MISSING_GESTURE) {
                Log.d(DEBUG_MISSING_GESTURE_TAG, message);
            }
        }
    }
}
