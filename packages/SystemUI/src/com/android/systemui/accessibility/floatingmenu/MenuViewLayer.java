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

package com.android.systemui.accessibility.floatingmenu;

import static com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType.INVISIBLE_TOGGLE;
import static com.android.internal.accessibility.util.AccessibilityUtils.getAccessibilityServiceFragmentType;
import static com.android.internal.accessibility.util.AccessibilityUtils.setAccessibilityServiceState;
import static com.android.systemui.accessibility.floatingmenu.MenuMessageView.Index;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.IntDef;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.PluralsMessageFormatter;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.wm.shell.bubbles.DismissView;
import com.android.wm.shell.common.magnetictarget.MagnetizedObject;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The basic interactions with the child views {@link MenuView}, {@link DismissView}, and
 * {@link MenuMessageView}. When dragging the menu view, the dismissed view would be shown at the
 * same time. If the menu view overlaps on the dismissed circle view and drops out, the menu
 * message view would be shown and allowed users to undo it.
 */
@SuppressLint("ViewConstructor")
class MenuViewLayer extends FrameLayout {
    private static final int SHOW_MESSAGE_DELAY_MS = 3000;

    private final MenuView mMenuView;
    private final MenuMessageView mMessageView;
    private final DismissView mDismissView;
    private final MenuAnimationController mMenuAnimationController;
    private final AccessibilityManager mAccessibilityManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final IAccessibilityFloatingMenu mFloatingMenu;
    private final DismissAnimationController mDismissAnimationController;

    @IntDef({
            LayerIndex.MENU_VIEW,
            LayerIndex.DISMISS_VIEW,
            LayerIndex.MESSAGE_VIEW,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface LayerIndex {
        int MENU_VIEW = 0;
        int DISMISS_VIEW = 1;
        int MESSAGE_VIEW = 2;
    }

    @VisibleForTesting
    final Runnable mDismissMenuAction = new Runnable() {
        @Override
        public void run() {
            Settings.Secure.putStringForUser(getContext().getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, /* value= */ "",
                    UserHandle.USER_CURRENT);

            // Should disable the corresponding service when the fragment type is
            // INVISIBLE_TOGGLE, which will enable service when the shortcut is on.
            final List<AccessibilityServiceInfo> serviceInfoList =
                    mAccessibilityManager.getEnabledAccessibilityServiceList(
                            AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            serviceInfoList.forEach(info -> {
                if (getAccessibilityServiceFragmentType(info) == INVISIBLE_TOGGLE) {
                    setAccessibilityServiceState(mContext, info.getComponentName(), /* enabled= */
                            false);
                }
            });

            mFloatingMenu.hide();
        }
    };

    MenuViewLayer(@NonNull Context context, WindowManager windowManager,
            AccessibilityManager accessibilityManager, IAccessibilityFloatingMenu floatingMenu) {
        super(context);

        mAccessibilityManager = accessibilityManager;
        mFloatingMenu = floatingMenu;

        final MenuViewModel menuViewModel = new MenuViewModel(context);
        final MenuViewAppearance menuViewAppearance = new MenuViewAppearance(context,
                windowManager);
        mMenuView = new MenuView(context, menuViewModel, menuViewAppearance);
        mMenuAnimationController = mMenuView.getMenuAnimationController();
        mMenuAnimationController.setDismissCallback(this::hideMenuAndShowMessage);

        mDismissView = new DismissView(context);
        mDismissAnimationController = new DismissAnimationController(mDismissView, mMenuView);
        mDismissAnimationController.setMagnetListener(new MagnetizedObject.MagnetListener() {
            @Override
            public void onStuckToTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                mDismissAnimationController.animateDismissMenu(/* scaleUp= */ true);
            }

            @Override
            public void onUnstuckFromTarget(@NonNull MagnetizedObject.MagneticTarget target,
                    float velocityX, float velocityY, boolean wasFlungOut) {
                mDismissAnimationController.animateDismissMenu(/* scaleUp= */ false);
            }

            @Override
            public void onReleasedInTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                hideMenuAndShowMessage();
                mDismissView.hide();
                mDismissAnimationController.animateDismissMenu(/* scaleUp= */ false);
            }
        });

        final MenuListViewTouchHandler menuListViewTouchHandler = new MenuListViewTouchHandler(
                mMenuAnimationController, mDismissAnimationController);
        mMenuView.addOnItemTouchListenerToList(menuListViewTouchHandler);

        mMessageView = new MenuMessageView(context);

        mMenuView.setOnTargetFeaturesChangeListener(newTargetFeatures -> {
            if (newTargetFeatures.size() < 1) {
                return;
            }

            // During the undo action period, the pending action will be canceled and undo back
            // to the previous state if users did any action related to the accessibility features.
            if (mMessageView.getVisibility() == VISIBLE) {
                undo();
            }

            final TextView messageText = (TextView) mMessageView.getChildAt(Index.TEXT_VIEW);
            messageText.setText(getMessageText(newTargetFeatures));
        });

        addView(mMenuView, LayerIndex.MENU_VIEW);
        addView(mDismissView, LayerIndex.DISMISS_VIEW);
        addView(mMessageView, LayerIndex.MESSAGE_VIEW);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDismissView.updateResources();
    }

    private String getMessageText(List<AccessibilityTarget> newTargetFeatures) {
        Preconditions.checkArgument(newTargetFeatures.size() > 0,
                "The list should at least have one feature.");

        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("count", newTargetFeatures.size());
        arguments.put("label", newTargetFeatures.get(0).getLabel());
        return PluralsMessageFormatter.format(getResources(), arguments,
                R.string.accessibility_floating_button_undo_message_text);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mMenuView.maybeMoveOutEdgeAndShow((int) event.getX(), (int) event.getY())) {
            return true;
        }

        return super.onInterceptTouchEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mMenuView.show();
        mMessageView.setUndoListener(view -> undo());
        mContext.registerComponentCallbacks(mDismissAnimationController);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mMenuView.hide();
        mHandler.removeCallbacksAndMessages(/* token= */ null);
        mContext.unregisterComponentCallbacks(mDismissAnimationController);
    }

    private void hideMenuAndShowMessage() {
        final int delayTime = mAccessibilityManager.getRecommendedTimeoutMillis(
                SHOW_MESSAGE_DELAY_MS,
                AccessibilityManager.FLAG_CONTENT_TEXT
                        | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        mHandler.postDelayed(mDismissMenuAction, delayTime);
        mMessageView.setVisibility(VISIBLE);
        mMenuAnimationController.startShrinkAnimation(() -> mMenuView.setVisibility(GONE));
    }

    private void undo() {
        mHandler.removeCallbacksAndMessages(/* token= */ null);
        mMessageView.setVisibility(GONE);
        mMenuView.onEdgeChanged();
        mMenuView.onPositionChanged();
        mMenuView.setVisibility(VISIBLE);
        mMenuAnimationController.startGrowAnimation();
    }
}
