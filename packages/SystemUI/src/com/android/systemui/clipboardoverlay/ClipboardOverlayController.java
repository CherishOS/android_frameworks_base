/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay;

import static android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;

import static java.util.Objects.requireNonNull;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.MainThread;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.policy.PhoneWindow;
import com.android.systemui.R;
import com.android.systemui.screenshot.FloatingWindowUtil;
import com.android.systemui.screenshot.ScreenshotActionChip;
import com.android.systemui.screenshot.TimeoutHandler;

import java.io.IOException;

/**
 * Controls state and UI for the overlay that appears when something is added to the clipboard
 */
public class ClipboardOverlayController {
    private static final String TAG = "ClipboardOverlayCtrlr";
    private static final String REMOTE_COPY_ACTION = "android.intent.action.REMOTE_COPY";

    /** Constants for screenshot/copy deconflicting */
    public static final String SCREENSHOT_ACTION = "com.android.systemui.SCREENSHOT";
    public static final String SELF_PERMISSION = "com.android.systemui.permission.SELF";
    public static final String COPY_OVERLAY_ACTION = "com.android.systemui.COPY";

    private static final int CLIPBOARD_DEFAULT_TIMEOUT_MILLIS = 6000;

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final WindowManager mWindowManager;
    private final WindowManager.LayoutParams mWindowLayoutParams;
    private final PhoneWindow mWindow;
    private final TimeoutHandler mTimeoutHandler;

    private final DraggableConstraintLayout mView;
    private final ImageView mImagePreview;
    private final TextView mTextPreview;
    private final ScreenshotActionChip mEditChip;
    private final ScreenshotActionChip mRemoteCopyChip;
    private final View mActionContainerBackground;

    private Runnable mOnSessionCompleteListener;

    private InputEventReceiver mInputEventReceiver;

    private BroadcastReceiver mCloseDialogsReceiver;
    private BroadcastReceiver mScreenshotReceiver;

    private boolean mBlockAttach = false;

    public ClipboardOverlayController(Context context, TimeoutHandler timeoutHandler) {
        mDisplayManager = requireNonNull(context.getSystemService(DisplayManager.class));
        final Context displayContext = context.createDisplayContext(getDefaultDisplay());
        mContext = displayContext.createWindowContext(TYPE_SCREENSHOT, null);

        mWindowManager = mContext.getSystemService(WindowManager.class);

        mTimeoutHandler = timeoutHandler;
        mTimeoutHandler.setDefaultTimeoutMillis(CLIPBOARD_DEFAULT_TIMEOUT_MILLIS);

        // Setup the window that we are going to use
        mWindowLayoutParams = FloatingWindowUtil.getFloatingWindowParams();
        mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mWindowLayoutParams.height = WRAP_CONTENT;
        mWindowLayoutParams.gravity = Gravity.BOTTOM;
        mWindowLayoutParams.setTitle("ClipboardOverlay");
        mWindow = FloatingWindowUtil.getFloatingWindow(mContext);
        mWindow.setWindowManager(mWindowManager, null, null);

        mView = (DraggableConstraintLayout)
                LayoutInflater.from(mContext).inflate(R.layout.clipboard_overlay, null);
        mActionContainerBackground = requireNonNull(
                mView.findViewById(R.id.actions_container_background));
        mImagePreview = requireNonNull(mView.findViewById(R.id.image_preview));
        mTextPreview = requireNonNull(mView.findViewById(R.id.text_preview));
        mEditChip = requireNonNull(mView.findViewById(R.id.edit_chip));
        mRemoteCopyChip = requireNonNull(mView.findViewById(R.id.remote_copy_chip));

        mView.setOnDismissCallback(this::hideImmediate);
        mView.setOnInteractionCallback(() -> mTimeoutHandler.resetTimeout());

        mEditChip.setIcon(Icon.createWithResource(mContext, R.drawable.ic_screenshot_edit), true);
        mRemoteCopyChip.setIcon(
                Icon.createWithResource(mContext, R.drawable.ic_baseline_devices_24), true);

        // Only show remote copy if it's available.
        PackageManager packageManager = mContext.getPackageManager();
        if (packageManager.resolveActivity(getRemoteCopyIntent(), 0) != null) {
            mRemoteCopyChip.setOnClickListener((v) -> {
                showNearby();
            });
            mRemoteCopyChip.setAlpha(1f);
        } else {
            mRemoteCopyChip.setVisibility(View.GONE);
        }

        attachWindow();
        withWindowAttached(() -> {
            mWindow.setContentView(mView);
            updateInsets(mWindowManager.getCurrentWindowMetrics().getWindowInsets());
            getEnterAnimation().start();
        });

        mTimeoutHandler.setOnTimeoutRunnable(() -> animateOut());

        mCloseDialogsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                    animateOut();
                }
            }
        };
        mContext.registerReceiver(mCloseDialogsReceiver,
                new IntentFilter(ACTION_CLOSE_SYSTEM_DIALOGS));

        mScreenshotReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (SCREENSHOT_ACTION.equals(intent.getAction())) {
                    animateOut();
                }
            }
        };
        mContext.registerReceiver(mScreenshotReceiver, new IntentFilter(SCREENSHOT_ACTION),
                SELF_PERMISSION, null);
        monitorOutsideTouches();

        mContext.sendBroadcast(new Intent(COPY_OVERLAY_ACTION), SELF_PERMISSION);
    }

    void setClipData(ClipData clipData) {
        reset();

        if (clipData == null || clipData.getItemCount() == 0) {
            showTextPreview(
                    mContext.getResources().getString(R.string.clipboard_overlay_text_copied));
        } else if (!TextUtils.isEmpty(clipData.getItemAt(0).getText())) {
            showEditableText(clipData.getItemAt(0).getText());
        } else if (clipData.getItemAt(0).getUri() != null) {
            // How to handle non-image URIs?
            showEditableImage(clipData.getItemAt(0).getUri());
        } else {
            showTextPreview(
                    mContext.getResources().getString(R.string.clipboard_overlay_text_copied));
        }

        mTimeoutHandler.resetTimeout();
    }

    void setOnSessionCompleteListener(Runnable runnable) {
        mOnSessionCompleteListener = runnable;
    }

    private void monitorOutsideTouches() {
        InputManager inputManager = mContext.getSystemService(InputManager.class);
        InputMonitor monitor = inputManager.monitorGestureInput("clipboard overlay", 0);
        mInputEventReceiver = new InputEventReceiver(monitor.getInputChannel(),
                Looper.getMainLooper()) {
            @Override
            public void onInputEvent(InputEvent event) {
                if (event instanceof MotionEvent) {
                    MotionEvent motionEvent = (MotionEvent) event;
                    if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        int[] pt = new int[2];
                        mView.getLocationOnScreen(pt);
                        Rect rect = new Rect(pt[0], pt[1], pt[0] + mView.getWidth(),
                                pt[1] + mView.getHeight());
                        if (!rect.contains((int) motionEvent.getRawX(),
                                (int) motionEvent.getRawY())) {
                            animateOut();
                        }
                    }
                }
                finishInputEvent(event, true /* handled */);
            }
        };
    }

    private void editImage(Uri uri) {
        String editorPackage = mContext.getString(R.string.config_screenshotEditor);
        Intent editIntent = new Intent(Intent.ACTION_EDIT);
        if (!TextUtils.isEmpty(editorPackage)) {
            editIntent.setComponent(ComponentName.unflattenFromString(editorPackage));
        }
        editIntent.setDataAndType(uri, "image/*");
        editIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        editIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivity(editIntent);
        animateOut();
    }

    private void editText() {
        Intent editIntent = new Intent(mContext, EditTextActivity.class);
        editIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivity(editIntent);
        animateOut();
    }

    private void showNearby() {
        mContext.startActivity(getRemoteCopyIntent());
        animateOut();
    }

    private void showTextPreview(CharSequence text) {
        mTextPreview.setVisibility(View.VISIBLE);
        mImagePreview.setVisibility(View.GONE);
        mTextPreview.setText(text);
        mEditChip.setVisibility(View.GONE);
    }

    private void showEditableText(CharSequence text) {
        showTextPreview(text);
        mEditChip.setVisibility(View.VISIBLE);
        mEditChip.setAlpha(1f);
        View.OnClickListener listener = v -> editText();
        mEditChip.setOnClickListener(listener);
        mTextPreview.setOnClickListener(listener);
    }

    private void showEditableImage(Uri uri) {
        mTextPreview.setVisibility(View.GONE);
        mImagePreview.setVisibility(View.VISIBLE);
        mEditChip.setAlpha(1f);
        ContentResolver resolver = mContext.getContentResolver();
        try {
            int size = mContext.getResources().getDimensionPixelSize(R.dimen.screenshot_x_scale);
            // The width of the view is capped, height maintains aspect ratio, so allow it to be
            // taller if needed.
            Bitmap thumbnail = resolver.loadThumbnail(uri, new Size(size, size * 4), null);
            mImagePreview.setImageBitmap(thumbnail);
        } catch (IOException e) {
            Log.e(TAG, "Thumbnail loading failed", e);
        }
        View.OnClickListener listener = v -> editImage(uri);
        mEditChip.setOnClickListener(listener);
        mImagePreview.setOnClickListener(listener);
    }

    private Intent getRemoteCopyIntent() {
        Intent nearbyIntent = new Intent(REMOTE_COPY_ACTION);
        nearbyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return nearbyIntent;
    }

    private void animateOut() {
        getExitAnimation().start();
    }

    private ValueAnimator getEnterAnimation() {
        ValueAnimator anim = ValueAnimator.ofFloat(0, 1);

        mView.setAlpha(0);
        final View previewBorder = requireNonNull(mView.findViewById(R.id.preview_border));
        final View actionBackground = requireNonNull(
                mView.findViewById(R.id.actions_container_background));
        mImagePreview.setVisibility(View.VISIBLE);
        mActionContainerBackground.setVisibility(View.VISIBLE);

        anim.addUpdateListener(animation -> {
            mView.setAlpha(animation.getAnimatedFraction());
            float scale = 0.6f + 0.4f * animation.getAnimatedFraction();
            mView.setPivotY(mView.getHeight() - previewBorder.getHeight() / 2f);
            mView.setPivotX(actionBackground.getWidth() / 2f);
            mView.setScaleX(scale);
            mView.setScaleY(scale);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mView.setAlpha(1);
                mTimeoutHandler.resetTimeout();
            }
        });
        return anim;
    }

    private ValueAnimator getExitAnimation() {
        ValueAnimator anim = ValueAnimator.ofFloat(0, 1);

        anim.addUpdateListener(animation -> {
            mView.setAlpha(1 - animation.getAnimatedFraction());
            final View actionBackground = requireNonNull(
                    mView.findViewById(R.id.actions_container_background));
            mView.setTranslationX(
                    -animation.getAnimatedFraction() * actionBackground.getWidth() / 2);
        });

        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                hideImmediate();
            }
        });

        return anim;
    }

    private void hideImmediate() {
        // Note this may be called multiple times if multiple dismissal events happen at the same
        // time.
        mTimeoutHandler.cancelTimeout();
        final View decorView = mWindow.peekDecorView();
        if (decorView != null && decorView.isAttachedToWindow()) {
            mWindowManager.removeViewImmediate(decorView);
        }
        if (mCloseDialogsReceiver != null) {
            mContext.unregisterReceiver(mCloseDialogsReceiver);
            mCloseDialogsReceiver = null;
        }
        if (mScreenshotReceiver != null) {
            mContext.unregisterReceiver(mScreenshotReceiver);
            mScreenshotReceiver = null;
        }
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mOnSessionCompleteListener != null) {
            mOnSessionCompleteListener.run();
        }
    }

    private void reset() {
        mView.setTranslationX(0);
        mView.setAlpha(1);
        mTimeoutHandler.cancelTimeout();
    }

    @MainThread
    private void attachWindow() {
        View decorView = mWindow.getDecorView();
        if (decorView.isAttachedToWindow() || mBlockAttach) {
            return;
        }
        mBlockAttach = true;
        mWindowManager.addView(decorView, mWindowLayoutParams);
        decorView.requestApplyInsets();
        mView.requestApplyInsets();
        decorView.getViewTreeObserver().addOnWindowAttachListener(
                new ViewTreeObserver.OnWindowAttachListener() {
                    @Override
                    public void onWindowAttached() {
                        mBlockAttach = false;
                    }

                    @Override
                    public void onWindowDetached() {
                    }
                }
        );
    }

    private void withWindowAttached(Runnable action) {
        View decorView = mWindow.getDecorView();
        if (decorView.isAttachedToWindow()) {
            action.run();
        } else {
            decorView.getViewTreeObserver().addOnWindowAttachListener(
                    new ViewTreeObserver.OnWindowAttachListener() {
                        @Override
                        public void onWindowAttached() {
                            mBlockAttach = false;
                            decorView.getViewTreeObserver().removeOnWindowAttachListener(this);
                            action.run();
                        }

                        @Override
                        public void onWindowDetached() {
                        }
                    });
        }
    }

    private void updateInsets(WindowInsets insets) {
        int orientation = mContext.getResources().getConfiguration().orientation;
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) mView.getLayoutParams();
        if (p == null) {
            return;
        }
        DisplayCutout cutout = insets.getDisplayCutout();
        Insets navBarInsets = insets.getInsets(WindowInsets.Type.navigationBars());
        if (cutout == null) {
            p.setMargins(0, 0, 0, navBarInsets.bottom);
        } else {
            Insets waterfall = cutout.getWaterfallInsets();
            if (orientation == ORIENTATION_PORTRAIT) {
                p.setMargins(
                        waterfall.left,
                        Math.max(cutout.getSafeInsetTop(), waterfall.top),
                        waterfall.right,
                        Math.max(cutout.getSafeInsetBottom(),
                                Math.max(navBarInsets.bottom, waterfall.bottom)));
            } else {
                p.setMargins(
                        Math.max(cutout.getSafeInsetLeft(), waterfall.left),
                        waterfall.top,
                        Math.max(cutout.getSafeInsetRight(), waterfall.right),
                        Math.max(navBarInsets.bottom, waterfall.bottom));
            }
        }
        mView.setLayoutParams(p);
        mView.requestLayout();
    }

    private Display getDefaultDisplay() {
        return mDisplayManager.getDisplay(DEFAULT_DISPLAY);
    }
}
