/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_INVALID;

import static com.android.systemui.shared.system.NavigationBarCompat.FLAG_DISABLE_QUICK_SCRUB;
import static com.android.systemui.shared.system.NavigationBarCompat.FLAG_SHOW_OVERVIEW_BUTTON;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_BACK;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_DEAD_ZONE;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_HOME;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_NONE;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_OVERVIEW;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_ROTATION;

import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.DrawableRes;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.DockedStackExistsListener;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.statusbar.phone.NavGesture;
import com.android.systemui.plugins.statusbar.phone.NavGesture.GestureHelper;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsOnboarding;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.NavigationBarCompat;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.statusbar.phone.NavigationPrototypeController.GestureAction;
import com.android.systemui.statusbar.phone.NavigationPrototypeController.OnPrototypeChangedListener;
import com.android.systemui.statusbar.policy.DeadZone;
import com.android.systemui.statusbar.policy.KeyButtonDrawable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.function.Consumer;

public class NavigationBarView extends FrameLayout implements PluginListener<NavGesture> {
    final static boolean DEBUG = false;
    final static String TAG = "StatusBar/NavBarView";

    // slippery nav bar when everything is disabled, e.g. during setup
    final static boolean SLIPPERY_WHEN_DISABLED = true;

    final static boolean ALTERNATE_CAR_MODE_UI = false;

    View mCurrentView = null;
    View[] mRotatedViews = new View[4];

    boolean mVertical;
    private int mCurrentRotation = -1;

    boolean mLongClickableAccessibilityButton;
    int mDisabledFlags = 0;
    int mNavigationIconHints = 0;

    private @NavigationBarCompat.HitTarget int mDownHitTarget = HIT_TARGET_NONE;
    private Rect mHomeButtonBounds = new Rect();
    private Rect mBackButtonBounds = new Rect();
    private Rect mRecentsButtonBounds = new Rect();
    private Rect mRotationButtonBounds = new Rect();
    private int[] mTmpPosition = new int[2];
    private Rect mTmpRect = new Rect();

    private KeyButtonDrawable mBackIcon;
    private KeyButtonDrawable mHomeDefaultIcon;
    private KeyButtonDrawable mRecentIcon;
    private KeyButtonDrawable mDockedIcon;

    private GestureHelper mGestureHelper;
    private final DeadZone mDeadZone;
    private boolean mDeadZoneConsuming = false;
    private final NavigationBarTransitions mBarTransitions;
    private final OverviewProxyService mOverviewProxyService;

    // workaround for LayoutTransitions leaving the nav buttons in a weird state (bug 5549288)
    final static boolean WORKAROUND_INVALID_LAYOUT = true;
    final static int MSG_CHECK_INVALID_LAYOUT = 8686;

    // performs manual animation in sync with layout transitions
    private final NavTransitionListener mTransitionListener = new NavTransitionListener();

    private OnVerticalChangedListener mOnVerticalChangedListener;
    private boolean mLayoutTransitionsEnabled = true;
    private boolean mWakeAndUnlocking;
    private boolean mUseCarModeUi = false;
    private boolean mInCarMode = false;
    private boolean mDockedStackExists;

    private final SparseArray<ButtonDispatcher> mButtonDispatchers = new SparseArray<>();
    private final ContextualButtonGroup mContextualButtonGroup;
    private Configuration mConfiguration;
    private Configuration mTmpLastConfiguration;

    private NavigationBarInflaterView mNavigationInflaterView;
    private RecentsOnboarding mRecentsOnboarding;
    private NotificationPanelView mPanelView;

    private NavBarTintController mColorAdaptionController;
    private NavigationPrototypeController mPrototypeController;
    private NavigationGestureAction[] mDefaultGestureMap;
    private QuickScrubAction mQuickScrubAction;
    private QuickStepAction mQuickStepAction;
    private NavigationBackAction mBackAction;
    private QuickSwitchAction mQuickSwitchAction;
    private NavigationAssistantAction mAssistantAction;

    /**
     * Helper that is responsible for showing the right toast when a disallowed activity operation
     * occurred. In pinned mode, we show instructions on how to break out of this mode, whilst in
     * fully locked mode we only show that unlocking is blocked.
     */
    private ScreenPinningNotify mScreenPinningNotify;

    private class NavTransitionListener implements TransitionListener {
        private boolean mBackTransitioning;
        private boolean mHomeAppearing;
        private long mStartDelay;
        private long mDuration;
        private TimeInterpolator mInterpolator;

        @Override
        public void startTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (view.getId() == R.id.back) {
                mBackTransitioning = true;
            } else if (view.getId() == R.id.home && transitionType == LayoutTransition.APPEARING) {
                mHomeAppearing = true;
                mStartDelay = transition.getStartDelay(transitionType);
                mDuration = transition.getDuration(transitionType);
                mInterpolator = transition.getInterpolator(transitionType);
            }
        }

        @Override
        public void endTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (view.getId() == R.id.back) {
                mBackTransitioning = false;
            } else if (view.getId() == R.id.home && transitionType == LayoutTransition.APPEARING) {
                mHomeAppearing = false;
            }
        }

        public void onBackAltCleared() {
            ButtonDispatcher backButton = getBackButton();

            // When dismissing ime during unlock, force the back button to run the same appearance
            // animation as home (if we catch this condition early enough).
            if (!mBackTransitioning && backButton.getVisibility() == VISIBLE
                    && mHomeAppearing && getHomeButton().getAlpha() == 0) {
                getBackButton().setAlpha(0);
                ValueAnimator a = ObjectAnimator.ofFloat(backButton, "alpha", 0, 1);
                a.setStartDelay(mStartDelay);
                a.setDuration(mDuration);
                a.setInterpolator(mInterpolator);
                a.start();
            }
        }
    }

    private final OnClickListener mImeSwitcherClickListener = new OnClickListener() {
        @Override
        public void onClick(View view) {
            mContext.getSystemService(InputMethodManager.class).showInputMethodPickerFromSystem(
                    true /* showAuxiliarySubtypes */, getContext().getDisplayId());
        }
    };

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_CHECK_INVALID_LAYOUT:
                    final String how = "" + m.obj;
                    final int w = getWidth();
                    final int h = getHeight();
                    final int vw = getCurrentView().getWidth();
                    final int vh = getCurrentView().getHeight();

                    if (h != vh || w != vw) {
                        Log.w(TAG, String.format(
                            "*** Invalid layout in navigation bar (%s this=%dx%d cur=%dx%d)",
                            how, w, h, vw, vh));
                        if (WORKAROUND_INVALID_LAYOUT) {
                            requestLayout();
                        }
                    }
                    break;
            }
        }
    }

    private final AccessibilityDelegate mQuickStepAccessibilityDelegate
            = new AccessibilityDelegate() {
        private AccessibilityAction mToggleOverviewAction;

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            if (mToggleOverviewAction == null) {
                mToggleOverviewAction = new AccessibilityAction(R.id.action_toggle_overview,
                    getContext().getString(R.string.quick_step_accessibility_toggle_overview));
            }
            info.addAction(mToggleOverviewAction);
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == R.id.action_toggle_overview) {
                SysUiServiceProvider.getComponent(getContext(), Recents.class)
                        .toggleRecentApps();
            } else {
                return super.performAccessibilityAction(host, action, args);
            }
            return true;
        }
    };

    private OnPrototypeChangedListener mPrototypeListener = new OnPrototypeChangedListener() {
        @Override
        public void onGestureRemap(int[] actions) {
            updateNavigationGestures();
        }

        @Override
        public void onBackButtonVisibilityChanged(boolean visible) {
            if (!inScreenPinning()) {
                getBackButton().setVisibility(visible ? VISIBLE : GONE);
            }
        }

        @Override
        public void onHomeButtonVisibilityChanged(boolean visible) {
            getHomeButton().setVisibility(visible ? VISIBLE : GONE);
        }

        @Override
        public void onColorAdaptChanged(boolean enabled) {
            if (enabled) {
                mColorAdaptionController.start();
            } else {
                mColorAdaptionController.end();
            }
        }
    };

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mVertical = false;
        mLongClickableAccessibilityButton = false;

        // Set up the context group of buttons
        mContextualButtonGroup = new ContextualButtonGroup(R.id.menu_container);
        final ContextualButton menuButton = new ContextualButton(R.id.menu,
                R.drawable.ic_sysbar_menu);
        final ContextualButton imeSwitcherButton = new ContextualButton(R.id.ime_switcher,
                R.drawable.ic_ime_switcher_default);
        final RotationContextButton rotateSuggestionButton = new RotationContextButton(
                R.id.rotate_suggestion, R.drawable.ic_sysbar_rotate_button, getContext(),
                R.style.RotateButtonCCWStart90);
        final ContextualButton accessibilityButton =
                new ContextualButton(R.id.accessibility_button,
                        R.drawable.ic_sysbar_accessibility_button);
        mContextualButtonGroup.addButton(menuButton);
        mContextualButtonGroup.addButton(imeSwitcherButton);
        mContextualButtonGroup.addButton(rotateSuggestionButton);
        mContextualButtonGroup.addButton(accessibilityButton);

        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
        mRecentsOnboarding = new RecentsOnboarding(context, mOverviewProxyService);

        mConfiguration = new Configuration();
        mTmpLastConfiguration = new Configuration();
        mConfiguration.updateFrom(context.getResources().getConfiguration());

        mScreenPinningNotify = new ScreenPinningNotify(mContext);
        mBarTransitions = new NavigationBarTransitions(this);

        mButtonDispatchers.put(R.id.back, new ButtonDispatcher(R.id.back));
        mButtonDispatchers.put(R.id.home, new ButtonDispatcher(R.id.home));
        mButtonDispatchers.put(R.id.recent_apps, new ButtonDispatcher(R.id.recent_apps));
        mButtonDispatchers.put(R.id.menu, menuButton);
        mButtonDispatchers.put(R.id.ime_switcher, imeSwitcherButton);
        mButtonDispatchers.put(R.id.accessibility_button, accessibilityButton);
        mButtonDispatchers.put(R.id.rotate_suggestion, rotateSuggestionButton);
        mButtonDispatchers.put(R.id.menu_container, mContextualButtonGroup);
        mDeadZone = new DeadZone(this);

        mQuickScrubAction = new QuickScrubAction(this, mOverviewProxyService);
        mQuickStepAction = new QuickStepAction(this, mOverviewProxyService);
        mBackAction = new NavigationBackAction(this, mOverviewProxyService);
        mQuickSwitchAction = new QuickSwitchAction(this, mOverviewProxyService);
        mDefaultGestureMap = new NavigationGestureAction[] {
                mQuickStepAction, null /* swipeDownAction*/, null /* swipeLeftAction */,
                mQuickScrubAction, null /* swipeLeftEdgeAction */, null /* swipeRightEdgeAction */
        };

        mPrototypeController = new NavigationPrototypeController(mHandler, mContext);
        mPrototypeController.register();
        mPrototypeController.setOnPrototypeChangedListener(mPrototypeListener);
        mColorAdaptionController = new NavBarTintController(this, getLightTransitionsController());
    }

    public NavBarTintController getColorAdaptionController() {
        return mColorAdaptionController;
    }

    public BarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    public LightBarTransitionsController getLightTransitionsController() {
        return mBarTransitions.getLightTransitionsController();
    }

    public void setComponents(NotificationPanelView panel, AssistManager assistManager) {
        mPanelView = panel;
        if (mAssistantAction == null) {
            mAssistantAction = new NavigationAssistantAction(this, mOverviewProxyService,
                    assistManager);
        }
        if (mGestureHelper instanceof QuickStepController) {
            ((QuickStepController) mGestureHelper).setComponents(this);
            updateNavigationGestures();
        }
    }

    private void updateNavigationGestures() {
        if (mGestureHelper instanceof QuickStepController) {
            final int[] assignedMap = mPrototypeController.getGestureActionMap();
            ((QuickStepController) mGestureHelper).setGestureActions(
                    getNavigationActionFromType(assignedMap[0], mDefaultGestureMap[0]),
                    getNavigationActionFromType(assignedMap[1], mDefaultGestureMap[1]),
                    getNavigationActionFromType(assignedMap[2], mDefaultGestureMap[2]),
                    getNavigationActionFromType(assignedMap[3], mDefaultGestureMap[3]),
                    getNavigationActionFromType(assignedMap[4], mDefaultGestureMap[4]),
                    getNavigationActionFromType(assignedMap[5], mDefaultGestureMap[5]));
        }
    }

    private NavigationGestureAction getNavigationActionFromType(@GestureAction int actionType,
            NavigationGestureAction defaultAction) {
        switch(actionType) {
            case NavigationPrototypeController.ACTION_QUICKSTEP:
                return mQuickStepAction;
            case NavigationPrototypeController.ACTION_QUICKSCRUB:
                return mQuickScrubAction;
            case NavigationPrototypeController.ACTION_BACK:
                return mBackAction;
            case NavigationPrototypeController.ACTION_QUICKSWITCH:
                return mQuickSwitchAction;
            case NavigationPrototypeController.ACTION_ASSISTANT:
                return mAssistantAction;
            case NavigationPrototypeController.ACTION_NOTHING:
                return null;
            default:
                return defaultAction;
        }
    }

    public void setOnVerticalChangedListener(OnVerticalChangedListener onVerticalChangedListener) {
        mOnVerticalChangedListener = onVerticalChangedListener;
        notifyVerticalChangedListener(mVertical);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        final boolean deadZoneConsumed = shouldDeadZoneConsumeTouchEvents(event);
        switch (event.getActionMasked()) {
            case ACTION_DOWN:
                int x = (int) event.getX();
                int y = (int) event.getY();
                mDownHitTarget = HIT_TARGET_NONE;
                if (deadZoneConsumed) {
                    mDownHitTarget = HIT_TARGET_DEAD_ZONE;
                } else if (getBackButton().isVisible() && mBackButtonBounds.contains(x, y)) {
                    mDownHitTarget = HIT_TARGET_BACK;
                } else if (getHomeButton().isVisible() && mHomeButtonBounds.contains(x, y)) {
                    mDownHitTarget = HIT_TARGET_HOME;
                } else if (getRecentsButton().isVisible() && mRecentsButtonBounds.contains(x, y)) {
                    mDownHitTarget = HIT_TARGET_OVERVIEW;
                } else if (getRotateSuggestionButton().isVisible()
                        && mRotationButtonBounds.contains(x, y)) {
                    mDownHitTarget = HIT_TARGET_ROTATION;
                }
                break;
        }
        return mGestureHelper.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        shouldDeadZoneConsumeTouchEvents(event);
        if (mGestureHelper != null && mGestureHelper.onTouchEvent(event)) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    private boolean shouldDeadZoneConsumeTouchEvents(MotionEvent event) {
        if (mDeadZone.onTouchEvent(event) || mDeadZoneConsuming) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Allow gestures starting in the deadzone to be slippery
                    setSlippery(true);
                    mDeadZoneConsuming = true;
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    // When a gesture started in the deadzone is finished, restore slippery state
                    updateSlippery();
                    mDeadZoneConsuming = false;
                    break;
            }
            return true;
        }
        return false;
    }

    public @NavigationBarCompat.HitTarget int getDownHitTarget() {
        return mDownHitTarget;
    }

    public void abortCurrentGesture() {
        getHomeButton().abortCurrentGesture();
    }

    private H mHandler = new H();

    public View getCurrentView() {
        return mCurrentView;
    }

    public View[] getAllViews() {
        return mRotatedViews;
    }

    public ButtonDispatcher getRecentsButton() {
        return mButtonDispatchers.get(R.id.recent_apps);
    }

    public ButtonDispatcher getMenuButton() {
        return mButtonDispatchers.get(R.id.menu);
    }

    public ButtonDispatcher getBackButton() {
        return mButtonDispatchers.get(R.id.back);
    }

    public ButtonDispatcher getHomeButton() {
        return mButtonDispatchers.get(R.id.home);
    }

    public ButtonDispatcher getImeSwitchButton() {
        return mButtonDispatchers.get(R.id.ime_switcher);
    }

    public ButtonDispatcher getAccessibilityButton() {
        return mButtonDispatchers.get(R.id.accessibility_button);
    }

    public RotationContextButton getRotateSuggestionButton() {
        return (RotationContextButton) mContextualButtonGroup
                .getContextButton(R.id.rotate_suggestion);
    }

    public SparseArray<ButtonDispatcher> getButtonDispatchers() {
        return mButtonDispatchers;
    }

    public boolean isRecentsButtonVisible() {
        return getRecentsButton().getVisibility() == View.VISIBLE;
    }

    public boolean isOverviewEnabled() {
        return (mDisabledFlags & View.STATUS_BAR_DISABLE_RECENT) == 0;
    }

    public boolean isQuickStepSwipeUpEnabled() {
        return mOverviewProxyService.shouldShowSwipeUpUI() && isOverviewEnabled();
    }

    public boolean isQuickScrubEnabled() {
        return SystemProperties.getBoolean("persist.quickstep.scrub.enabled", true)
                && mOverviewProxyService.isEnabled() && isOverviewEnabled()
                && ((mOverviewProxyService.getInteractionFlags() & FLAG_DISABLE_QUICK_SCRUB) == 0);
    }

    private void reloadNavIcons() {
        updateIcons(Configuration.EMPTY);
    }

    private void updateIcons(Configuration oldConfig) {
        final boolean orientationChange = oldConfig.orientation != mConfiguration.orientation;
        final boolean densityChange = oldConfig.densityDpi != mConfiguration.densityDpi;
        final boolean dirChange = oldConfig.getLayoutDirection() != mConfiguration.getLayoutDirection();

        if (orientationChange || densityChange) {
            mDockedIcon = getDrawable(R.drawable.ic_sysbar_docked);
            mHomeDefaultIcon = getHomeDrawable();
        }
        if (densityChange || dirChange) {
            mRecentIcon = getDrawable(R.drawable.ic_sysbar_recent);
            mContextualButtonGroup.updateIcons();
        }
        if (orientationChange || densityChange || dirChange) {
            mBackIcon = getBackDrawable();
        }
    }

    public KeyButtonDrawable getBackDrawable() {
        KeyButtonDrawable drawable = chooseNavigationIconDrawable(R.drawable.ic_sysbar_back,
                R.drawable.ic_sysbar_back_quick_step);
        orientBackButton(drawable);
        return drawable;
    }

    public KeyButtonDrawable getHomeDrawable() {
        final boolean quickStepEnabled = mOverviewProxyService.shouldShowSwipeUpUI();
        KeyButtonDrawable drawable = quickStepEnabled
                ? getDrawable(R.drawable.ic_sysbar_home_quick_step)
                : getDrawable(R.drawable.ic_sysbar_home);
        orientHomeButton(drawable);
        return drawable;
    }

    private void orientBackButton(KeyButtonDrawable drawable) {
        final boolean useAltBack =
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        final boolean isRtl = mConfiguration.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float degrees = useAltBack
                ? (isRtl ? 270 : -90)
                : (isRtl ? 180 : 0);
        if (drawable.getRotation() == degrees) {
            return;
        }

        // Animate the back button's rotation to the new degrees and only in portrait move up the
        // back button to line up with the other buttons
        float targetY = !mOverviewProxyService.shouldShowSwipeUpUI() && !mVertical && useAltBack
                ? - getResources().getDimension(R.dimen.navbar_back_button_ime_offset)
                : 0;
        ObjectAnimator navBarAnimator = ObjectAnimator.ofPropertyValuesHolder(drawable,
                PropertyValuesHolder.ofFloat(KeyButtonDrawable.KEY_DRAWABLE_ROTATE, degrees),
                PropertyValuesHolder.ofFloat(KeyButtonDrawable.KEY_DRAWABLE_TRANSLATE_Y, targetY));
        navBarAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        navBarAnimator.setDuration(200);
        navBarAnimator.start();
    }

    private void orientHomeButton(KeyButtonDrawable drawable) {
        drawable.setRotation(mVertical ? 90 : 0);
    }

    private KeyButtonDrawable chooseNavigationIconDrawable(@DrawableRes int icon,
            @DrawableRes int quickStepIcon) {
        final boolean quickStepEnabled = mOverviewProxyService.shouldShowSwipeUpUI();
        return quickStepEnabled ? getDrawable(quickStepIcon) : getDrawable(icon);
    }

    private KeyButtonDrawable getDrawable(@DrawableRes int icon) {
        return KeyButtonDrawable.create(mContext, icon, true /* hasShadow */);
    }

    private KeyButtonDrawable getDrawable(@DrawableRes int icon, boolean hasShadow) {
        return KeyButtonDrawable.create(mContext, icon, hasShadow);
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        reloadNavIcons();

        super.setLayoutDirection(layoutDirection);
    }

    public void setNavigationIconHints(int hints) {
        if (hints == mNavigationIconHints) return;
        final boolean backAlt = (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        if ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0 && !backAlt) {
            mTransitionListener.onBackAltCleared();
        }
        if (DEBUG) {
            android.widget.Toast.makeText(getContext(),
                "Navigation icon hints = " + hints,
                500).show();
        }
        mNavigationIconHints = hints;
        updateNavButtonIcons();
    }

    public void setDisabledFlags(int disabledFlags) {
        if (mDisabledFlags == disabledFlags) return;

        final boolean overviewEnabledBefore = isOverviewEnabled();
        mDisabledFlags = disabledFlags;

        // Update icons if overview was just enabled to ensure the correct icons are present
        if (!overviewEnabledBefore && isOverviewEnabled()) {
            reloadNavIcons();
        }

        updateNavButtonIcons();
        updateSlippery();
        setUpSwipeUpOnboarding(isQuickStepSwipeUpEnabled());
    }

    public void updateNavButtonIcons() {
        // We have to replace or restore the back and home button icons when exiting or entering
        // carmode, respectively. Recents are not available in CarMode in nav bar so change
        // to recent icon is not required.
        final boolean useAltBack =
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        KeyButtonDrawable backIcon = mBackIcon;
        orientBackButton(backIcon);
        KeyButtonDrawable homeIcon = mHomeDefaultIcon;
        if (!mUseCarModeUi) {
            orientHomeButton(homeIcon);
        }
        getHomeButton().setImageDrawable(homeIcon);
        getBackButton().setImageDrawable(backIcon);

        updateRecentsIcon();

        // Update IME button visibility, a11y and rotate button always overrides the appearance
        mContextualButtonGroup.setButtonVisiblity(R.id.ime_switcher,
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) != 0);

        mBarTransitions.reapplyDarkIntensity();

        boolean disableHome = ((mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);

        // TODO(b/113914868): investigation log for disappearing home button
        Log.i(TAG, "updateNavButtonIcons (b/113914868): home disabled=" + disableHome
                + " mDisabledFlags=" + mDisabledFlags);
        disableHome |= mPrototypeController.hideHomeButton();

        // Always disable recents when alternate car mode UI is active and for secondary displays.
        boolean disableRecent = isRecentsButtonDisabled();

        boolean disableBack = QuickStepController.shouldhideBackButton(getContext())
                || (((mDisabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0) && !useAltBack);

        // When screen pinning, don't hide back and home when connected service or back and
        // recents buttons when disconnected from launcher service in screen pinning mode,
        // as they are used for exiting.
        final boolean pinningActive = ActivityManagerWrapper.getInstance().isScreenPinningActive();
        if (mOverviewProxyService.isEnabled()) {
            // Use interaction flags to show/hide navigation buttons but will be shown if required
            // to exit screen pinning.
            final int flags = mOverviewProxyService.getInteractionFlags();
            disableRecent |= (flags & FLAG_SHOW_OVERVIEW_BUTTON) == 0;
            if (pinningActive) {
                disableBack = disableHome = false;
            }
        } else if (pinningActive) {
            disableBack = disableRecent = false;
        }

        ViewGroup navButtons = (ViewGroup) getCurrentView().findViewById(R.id.nav_buttons);
        if (navButtons != null) {
            LayoutTransition lt = navButtons.getLayoutTransition();
            if (lt != null) {
                if (!lt.getTransitionListeners().contains(mTransitionListener)) {
                    lt.addTransitionListener(mTransitionListener);
                }
            }
        }

        getBackButton().setVisibility(disableBack      ? View.INVISIBLE : View.VISIBLE);
        getHomeButton().setVisibility(disableHome      ? View.INVISIBLE : View.VISIBLE);
        getRecentsButton().setVisibility(disableRecent ? View.INVISIBLE : View.VISIBLE);
    }

    @VisibleForTesting
    boolean isRecentsButtonDisabled() {
        return mUseCarModeUi || !isOverviewEnabled()
                || getContext().getDisplayId() != Display.DEFAULT_DISPLAY;
    }

    private Display getContextDisplay() {
        return getContext().getDisplay();
    }

    public boolean inScreenPinning() {
        return ActivityManagerWrapper.getInstance().isScreenPinningActive();
    }

    public void setLayoutTransitionsEnabled(boolean enabled) {
        mLayoutTransitionsEnabled = enabled;
        updateLayoutTransitionsEnabled();
    }

    public void setWakeAndUnlocking(boolean wakeAndUnlocking) {
        setUseFadingAnimations(wakeAndUnlocking);
        mWakeAndUnlocking = wakeAndUnlocking;
        updateLayoutTransitionsEnabled();
    }

    private void updateLayoutTransitionsEnabled() {
        boolean enabled = !mWakeAndUnlocking && mLayoutTransitionsEnabled;
        ViewGroup navButtons = (ViewGroup) getCurrentView().findViewById(R.id.nav_buttons);
        LayoutTransition lt = navButtons.getLayoutTransition();
        if (lt != null) {
            if (enabled) {
                lt.enableTransitionType(LayoutTransition.APPEARING);
                lt.enableTransitionType(LayoutTransition.DISAPPEARING);
                lt.enableTransitionType(LayoutTransition.CHANGE_APPEARING);
                lt.enableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            } else {
                lt.disableTransitionType(LayoutTransition.APPEARING);
                lt.disableTransitionType(LayoutTransition.DISAPPEARING);
                lt.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
                lt.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            }
        }
    }

    private void setUseFadingAnimations(boolean useFadingAnimations) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) ((ViewGroup) getParent())
                .getLayoutParams();
        if (lp != null) {
            boolean old = lp.windowAnimations != 0;
            if (!old && useFadingAnimations) {
                lp.windowAnimations = R.style.Animation_NavigationBarFadeIn;
            } else if (old && !useFadingAnimations) {
                lp.windowAnimations = 0;
            } else {
                return;
            }
            WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout((View) getParent(), lp);
        }
    }

    public void onNavigationButtonLongPress(View v) {
        if (mGestureHelper != null) {
            mGestureHelper.onNavigationButtonLongPress(v);
        }
    }

    public void onPanelExpandedChange(boolean expanded) {
        updateSlippery();
    }

    public void updateStates() {
        final boolean showSwipeUpUI = mOverviewProxyService.shouldShowSwipeUpUI();

        if (mNavigationInflaterView != null) {
            // Reinflate the navbar if needed, no-op unless the swipe up state changes
            mNavigationInflaterView.onLikelyDefaultLayoutChange();
        }

        updateSlippery();
        reloadNavIcons();
        updateNavButtonIcons();
        setUpSwipeUpOnboarding(isQuickStepSwipeUpEnabled());
        WindowManagerWrapper.getInstance().setNavBarVirtualKeyHapticFeedbackEnabled(!showSwipeUpUI);
        getHomeButton().setAccessibilityDelegate(
                showSwipeUpUI ? mQuickStepAccessibilityDelegate : null);
    }

    public boolean isNotificationsFullyCollapsed() {
        return mPanelView.isFullyCollapsed();
    }

    /**
     * Updates the {@link WindowManager.LayoutParams.FLAG_SLIPPERY} state dependent on if swipe up
     * is enabled, or the notifications is fully opened without being in an animated state. If
     * slippery is enabled, touch events will leave the nav bar window and enter into the fullscreen
     * app/home window, if not nav bar will receive a cancelled touch event once gesture leaves bar.
     */
    public void updateSlippery() {
        setSlippery(!isQuickStepSwipeUpEnabled() ||
                (mPanelView.isFullyExpanded() && !mPanelView.isCollapsing()));
    }

    private void setSlippery(boolean slippery) {
        boolean changed = false;
        final ViewGroup navbarView = ((ViewGroup) getParent());
        final WindowManager.LayoutParams lp = (WindowManager.LayoutParams) navbarView
                .getLayoutParams();
        if (lp == null) {
            return;
        }
        if (slippery && (lp.flags & WindowManager.LayoutParams.FLAG_SLIPPERY) == 0) {
            lp.flags |= WindowManager.LayoutParams.FLAG_SLIPPERY;
            changed = true;
        } else if (!slippery && (lp.flags & WindowManager.LayoutParams.FLAG_SLIPPERY) != 0) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_SLIPPERY;
            changed = true;
        }
        if (changed) {
            WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(navbarView, lp);
        }
    }

    public void setMenuVisibility(final boolean show) {
        mContextualButtonGroup.setButtonVisiblity(R.id.menu, show);
    }

    public void setAccessibilityButtonState(final boolean visible, final boolean longClickable) {
        mLongClickableAccessibilityButton = longClickable;
        getAccessibilityButton().setLongClickable(longClickable);
        mContextualButtonGroup.setButtonVisiblity(R.id.accessibility_button, visible);
    }

    void hideRecentsOnboarding() {
        mRecentsOnboarding.hide(true);
    }

    @Override
    public void onFinishInflate() {
        mNavigationInflaterView = findViewById(R.id.navigation_inflater);
        mNavigationInflaterView.setButtonDispatchers(mButtonDispatchers);

        getImeSwitchButton().setOnClickListener(mImeSwitcherClickListener);

        DockedStackExistsListener.register(mDockedListener);
        updateRotatedViews();
        reloadNavIcons();
    }

    public void onDarkIntensityChange(float intensity) {
        if (mGestureHelper != null) {
            mGestureHelper.onDarkIntensityChange(intensity);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mGestureHelper != null) {
            mGestureHelper.onDraw(canvas);
        }
        mDeadZone.onDraw(canvas);
        super.onDraw(canvas);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateButtonLocationOnScreen(getBackButton(), mBackButtonBounds);
        updateButtonLocationOnScreen(getHomeButton(), mHomeButtonBounds);
        updateButtonLocationOnScreen(getRecentsButton(), mRecentsButtonBounds);
        updateButtonLocationOnScreen(getRotateSuggestionButton(), mRotationButtonBounds);
        if (mGestureHelper != null) {
            mGestureHelper.onLayout(changed, left, top, right, bottom);
        }
        mRecentsOnboarding.setNavBarHeight(getMeasuredHeight());
    }

    private void updateButtonLocationOnScreen(ButtonDispatcher button, Rect buttonBounds) {
        View view = button.getCurrentView();
        if (view == null) {
            buttonBounds.setEmpty();
            return;
        }
        // Temporarily reset the translation back to origin to get the position in window
        final float posX = view.getTranslationX();
        final float posY = view.getTranslationY();
        view.setTranslationX(0);
        view.setTranslationY(0);
        view.getLocationInWindow(mTmpPosition);
        buttonBounds.set(mTmpPosition[0], mTmpPosition[1],
                mTmpPosition[0] + view.getMeasuredWidth(),
                mTmpPosition[1] + view.getMeasuredHeight());
        view.setTranslationX(posX);
        view.setTranslationY(posY);
    }

    private void updateRotatedViews() {
        mRotatedViews[Surface.ROTATION_0] =
                mRotatedViews[Surface.ROTATION_180] = findViewById(R.id.rot0);
        mRotatedViews[Surface.ROTATION_270] =
                mRotatedViews[Surface.ROTATION_90] = findViewById(R.id.rot90);

        updateCurrentView();
    }

    public boolean needsReorient(int rotation) {
        return mCurrentRotation != rotation;
    }

    private void updateCurrentView() {
        final int rot = getContextDisplay().getRotation();
        for (int i=0; i<4; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }
        mCurrentView = mRotatedViews[rot];
        mCurrentView.setVisibility(View.VISIBLE);
        mNavigationInflaterView.setAlternativeOrder(rot == Surface.ROTATION_90);
        mNavigationInflaterView.updateButtonDispatchersCurrentView();
        updateLayoutTransitionsEnabled();
        mCurrentRotation = rot;
    }

    private void updateRecentsIcon() {
        mDockedIcon.setRotation(mDockedStackExists && mVertical ? 90 : 0);
        getRecentsButton().setImageDrawable(mDockedStackExists ? mDockedIcon : mRecentIcon);
        mBarTransitions.reapplyDarkIntensity();
    }

    public void showPinningEnterExitToast(boolean entering) {
        if (entering) {
            mScreenPinningNotify.showPinningStartToast();

            // TODO(b/112934365): remove after prototype finished, only needed to escape from pin
            getBackButton().setVisibility(VISIBLE);
            getHomeButton().setVisibility(VISIBLE);
        } else {
            mScreenPinningNotify.showPinningExitToast();
        }
    }

    public void showPinningEscapeToast() {
        mScreenPinningNotify.showEscapeToast(isRecentsButtonVisible());
    }

    public boolean isVertical() {
        return mVertical;
    }

    public void reorient() {
        updateCurrentView();

        ((NavigationBarFrame) getRootView()).setDeadZone(mDeadZone);
        mDeadZone.onConfigurationChanged(mCurrentRotation);

        // force the low profile & disabled states into compliance
        mBarTransitions.init();

        if (DEBUG) {
            Log.d(TAG, "reorient(): rot=" + mCurrentRotation);
        }

        // Resolve layout direction if not resolved since components changing layout direction such
        // as changing languages will recreate this view and the direction will be resolved later
        if (!isLayoutDirectionResolved()) {
            resolveLayoutDirection();
        }
        updateTaskSwitchHelper();
        updateNavButtonIcons();

        getHomeButton().setVertical(mVertical);
    }

    private void updateTaskSwitchHelper() {
        if (mGestureHelper == null) return;
        boolean isRtl = (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        int navBarPos = NAV_BAR_INVALID;
        try {
            navBarPos = WindowManagerGlobal.getWindowManagerService().getNavBarPosition(
                    getContext().getDisplayId());
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get nav bar position.", e);
        }
        mGestureHelper.setBarState(isRtl, navBarPos);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (DEBUG) Log.d(TAG, String.format(
                    "onSizeChanged: (%dx%d) old: (%dx%d)", w, h, oldw, oldh));

        final boolean newVertical = w > 0 && h > w;
        if (newVertical != mVertical) {
            mVertical = newVertical;
            //Log.v(TAG, String.format("onSizeChanged: h=%d, w=%d, vert=%s", h, w, mVertical?"y":"n"));
            reorient();
            notifyVerticalChangedListener(newVertical);
        }

        postCheckForInvalidLayout("sizeChanged");
        super.onSizeChanged(w, h, oldw, oldh);
    }

    private void notifyVerticalChangedListener(boolean newVertical) {
        if (mOnVerticalChangedListener != null) {
            mOnVerticalChangedListener.onVerticalChanged(newVertical);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mTmpLastConfiguration.updateFrom(mConfiguration);
        mConfiguration.updateFrom(newConfig);
        boolean uiCarModeChanged = updateCarMode();
        updateTaskSwitchHelper();
        updateIcons(mTmpLastConfiguration);
        updateRecentsIcon();
        mRecentsOnboarding.onConfigurationChanged(mConfiguration);
        if (uiCarModeChanged || mTmpLastConfiguration.densityDpi != mConfiguration.densityDpi
                || mTmpLastConfiguration.getLayoutDirection() != mConfiguration.getLayoutDirection()) {
            // If car mode or density changes, we need to reset the icons.
            updateNavButtonIcons();
        }
    }

    /**
     * If the configuration changed, update the carmode and return that it was updated.
     */
    private boolean updateCarMode() {
        boolean uiCarModeChanged = false;
        if (mConfiguration != null) {
            int uiMode = mConfiguration.uiMode & Configuration.UI_MODE_TYPE_MASK;
            final boolean isCarMode = (uiMode == Configuration.UI_MODE_TYPE_CAR);

            if (isCarMode != mInCarMode) {
                mInCarMode = isCarMode;
                if (ALTERNATE_CAR_MODE_UI) {
                    mUseCarModeUi = isCarMode;
                    uiCarModeChanged = true;
                } else {
                    // Don't use car mode behavior if ALTERNATE_CAR_MODE_UI not set.
                    mUseCarModeUi = false;
                }
            }
        }
        return uiCarModeChanged;
    }

    /*
    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
        if (DEBUG) Log.d(TAG, String.format(
                    "onLayout: %s (%d,%d,%d,%d)",
                    changed?"changed":"notchanged", left, top, right, bottom));
        super.onLayout(changed, left, top, right, bottom);
    }

    // uncomment this for extra defensiveness in WORKAROUND_INVALID_LAYOUT situations: if all else
    // fails, any touch on the display will fix the layout.
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Log.d(TAG, "onInterceptTouchEvent: " + ev.toString());
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            postCheckForInvalidLayout("touch");
        }
        return super.onInterceptTouchEvent(ev);
    }
    */


    private String getResourceName(int resId) {
        if (resId != 0) {
            final android.content.res.Resources res = getContext().getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

    private void postCheckForInvalidLayout(final String how) {
        mHandler.obtainMessage(MSG_CHECK_INVALID_LAYOUT, 0, 0, how).sendToTarget();
    }

    private static String visibilityToString(int vis) {
        switch (vis) {
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.GONE:
                return "GONE";
            default:
                return "VISIBLE";
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestApplyInsets();
        reorient();
        onPluginDisconnected(null); // Create default gesture helper
        Dependency.get(PluginManager.class).addPluginListener(this,
                NavGesture.class, false /* Only one */);
        setUpSwipeUpOnboarding(isQuickStepSwipeUpEnabled());
        mColorAdaptionController.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(PluginManager.class).removePluginListener(this);
        if (mGestureHelper != null) {
            mGestureHelper.destroy();
        }
        mPrototypeController.unregister();
        mColorAdaptionController.stop();
        setUpSwipeUpOnboarding(false);
        for (int i = 0; i < mButtonDispatchers.size(); ++i) {
            mButtonDispatchers.valueAt(i).onDestroy();
        }
    }

    private void setUpSwipeUpOnboarding(boolean connectedToOverviewProxy) {
        if (connectedToOverviewProxy) {
            mRecentsOnboarding.onConnectedToLauncher();
        } else {
            mRecentsOnboarding.onDisconnectedFromLauncher();
        }
    }

    @Override
    public void onPluginConnected(NavGesture plugin, Context context) {
        mGestureHelper = plugin.getGestureHelper();
        updateTaskSwitchHelper();
    }

    @Override
    public void onPluginDisconnected(NavGesture plugin) {
        QuickStepController defaultHelper = new QuickStepController(getContext());
        defaultHelper.setComponents(this);
        if (mGestureHelper != null) {
            mGestureHelper.destroy();
        }
        mGestureHelper = defaultHelper;
        updateTaskSwitchHelper();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NavigationBarView {");
        final Rect r = new Rect();
        final Point size = new Point();
        getContextDisplay().getRealSize(size);

        pw.println(String.format("      this: " + StatusBar.viewInfo(this)
                        + " " + visibilityToString(getVisibility())));

        getWindowVisibleDisplayFrame(r);
        final boolean offscreen = r.right > size.x || r.bottom > size.y;
        pw.println("      window: "
                + r.toShortString()
                + " " + visibilityToString(getWindowVisibility())
                + (offscreen ? " OFFSCREEN!" : ""));

        pw.println(String.format("      mCurrentView: id=%s (%dx%d) %s %f",
                        getResourceName(getCurrentView().getId()),
                        getCurrentView().getWidth(), getCurrentView().getHeight(),
                        visibilityToString(getCurrentView().getVisibility()),
                        getCurrentView().getAlpha()));

        pw.println(String.format("      disabled=0x%08x vertical=%s menu=%s darkIntensity=%.2f",
                        mDisabledFlags,
                        mVertical ? "true" : "false",
                        getMenuButton().isVisible() ? "true" : "false",
                        getLightTransitionsController().getCurrentDarkIntensity()));

        dumpButton(pw, "back", getBackButton());
        dumpButton(pw, "home", getHomeButton());
        dumpButton(pw, "rcnt", getRecentsButton());
        dumpButton(pw, "menu", getMenuButton());
        dumpButton(pw, "rota", getRotateSuggestionButton());
        dumpButton(pw, "a11y", getAccessibilityButton());

        pw.println("    }");

        mContextualButtonGroup.dump(pw);
        if (mGestureHelper != null) {
            pw.println("Navigation Gesture Actions {");
            pw.print("    "); pw.println("QuickScrub Enabled=" + mQuickScrubAction.isEnabled());
            pw.print("    "); pw.println("QuickScrub Active=" + mQuickScrubAction.isActive());
            pw.print("    "); pw.println("QuickStep Enabled=" + mQuickStepAction.isEnabled());
            pw.print("    "); pw.println("QuickStep Active=" + mQuickStepAction.isActive());
            pw.print("    "); pw.println("Back Gesture Enabled=" + mBackAction.isEnabled());
            pw.print("    "); pw.println("Back Gesture Active=" + mBackAction.isActive());
            pw.println("}");
            mGestureHelper.dump(pw);
        }
        mRecentsOnboarding.dump(pw);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        return super.onApplyWindowInsets(insets);
    }

    private static void dumpButton(PrintWriter pw, String caption, ButtonDispatcher button) {
        pw.print("      " + caption + ": ");
        if (button == null) {
            pw.print("null");
        } else {
            pw.print(visibilityToString(button.getVisibility())
                    + " alpha=" + button.getAlpha()
                    );
        }
        pw.println();
    }

    public interface OnVerticalChangedListener {
        void onVerticalChanged(boolean isVertical);
    }

    private final Consumer<Boolean> mDockedListener = exists -> mHandler.post(() -> {
        mDockedStackExists = exists;
        updateRecentsIcon();
    });
}
