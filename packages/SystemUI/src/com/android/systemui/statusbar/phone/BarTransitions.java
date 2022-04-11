/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;

import android.app.ActivityManager;
import android.content.pm.ResolveInfo;
import android.content.ComponentName;
import android.os.Handler;

import com.android.systemui.navigationbar.buttons.KeyButtonView;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.PackageManagerWrapper;
import com.android.systemui.shade.NotificationPanelViewController;

import java.util.ArrayList;
import java.util.List;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class BarTransitions {
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_COLORS = false;

    public static final int MODE_TRANSPARENT = 0;
    public static final int MODE_SEMI_TRANSPARENT = 1;
    public static final int MODE_TRANSLUCENT = 2;
    public static final int MODE_LIGHTS_OUT = 3;
    public static final int MODE_OPAQUE = 4;
    public static final int MODE_WARNING = 5;
    public static final int MODE_LIGHTS_OUT_TRANSPARENT = 6;

    @IntDef(flag = true, prefix = {"MODE_"}, value = {
            MODE_OPAQUE,
            MODE_SEMI_TRANSPARENT,
            MODE_TRANSLUCENT,
            MODE_LIGHTS_OUT,
            MODE_TRANSPARENT,
            MODE_WARNING,
            MODE_LIGHTS_OUT_TRANSPARENT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransitionMode {
    }

    public static final int LIGHTS_IN_DURATION = 250;
    public static final int LIGHTS_OUT_DURATION = 1500;
    public static final int BACKGROUND_DURATION = 200;

    private final String mTag;
    private final View mView;
    protected final BarBackgroundDrawable mBarBackground;

    private @TransitionMode
    int mMode;
    private boolean mAlwaysOpaque = false;

    public static Context mContext;

    public BarTransitions(View view, BarBackgroundDrawable barBackgroundDrawable) {
        mTag = "" + view.getClass().getSimpleName();
        mView = view;
        mBarBackground = barBackgroundDrawable;
        mContext = mView.getContext();
        mView.setBackground(mBarBackground);
    }

    public void destroy() {
        // To be overridden
    }

    public int getMode() {
        return mMode;
    }

    public void setAutoDim(boolean autoDim) {
        // Default is don't care.
    }

    /**
     * @param alwaysOpaque if {@code true}, the bar's background will always be opaque, regardless
     *                     of what mode it is currently set to.
     */
    public void setAlwaysOpaque(boolean alwaysOpaque) {
        mAlwaysOpaque = alwaysOpaque;
    }

    public boolean isAlwaysOpaque() {
        // Low-end devices do not support translucent modes, fallback to opaque
        return mAlwaysOpaque;
    }

    public void transitionTo(int mode, boolean animate) {
        if (isAlwaysOpaque() && (mode == MODE_SEMI_TRANSPARENT || mode == MODE_TRANSLUCENT
                || mode == MODE_TRANSPARENT)) {
            mode = MODE_OPAQUE;
        }
        if (isAlwaysOpaque() && (mode == MODE_LIGHTS_OUT_TRANSPARENT)) {
            mode = MODE_LIGHTS_OUT;
        }
        if (mMode == mode) return;
        int oldMode = mMode;
        mMode = mode;
        if (DEBUG) Log.d(mTag, String.format("%s -> %s animate=%s",
                modeToString(oldMode), modeToString(mode), animate));
        onTransition(oldMode, mMode, animate);
    }

    protected void onTransition(int oldMode, int newMode, boolean animate) {
        applyModeBackground(oldMode, newMode, animate);
    }

    protected void applyModeBackground(int oldMode, int newMode, boolean animate) {
        if (DEBUG) Log.d(mTag, String.format("applyModeBackground oldMode=%s newMode=%s animate=%s",
                modeToString(oldMode), modeToString(newMode), animate));
        mBarBackground.applyMode(/*oldMode,*/ newMode, animate);
    }

    public static String modeToString(int mode) {
        if (mode == MODE_OPAQUE) return "MODE_OPAQUE";
        if (mode == MODE_SEMI_TRANSPARENT) return "MODE_SEMI_TRANSPARENT";
        if (mode == MODE_TRANSLUCENT) return "MODE_TRANSLUCENT";
        if (mode == MODE_LIGHTS_OUT) return "MODE_LIGHTS_OUT";
        if (mode == MODE_TRANSPARENT) return "MODE_TRANSPARENT";
        if (mode == MODE_WARNING) return "MODE_WARNING";
        if (mode == MODE_LIGHTS_OUT_TRANSPARENT) return "MODE_LIGHTS_OUT_TRANSPARENT";
        throw new IllegalArgumentException("Unknown mode " + mode);
    }

    public void finishAnimations() {
        mBarBackground.finishAnimation();
    }

    protected boolean isLightsOut(int mode) {
        return mode == MODE_LIGHTS_OUT || mode == MODE_LIGHTS_OUT_TRANSPARENT;
    }

    protected static class BarBackgroundDrawable extends Drawable {
        private Context mContext;
        private Integer mColorOverride;
        private int mCurrentColor = 0;
        private int mCurrentGradientAlpha = 0;
        private int mCurrentMode = -1;
        private int mGradientResourceId;
        private final Handler mHandler = new Handler();
        private final int mOpaqueColorResourceId;
        private final int mSemiTransparentColorResourceId;
        private final int mTransparentColorResourceId;
        private final int mWarningColorResourceId;
        private Resources res;
        private Integer targetColor;

        private int mOpaque;
        private int mSemiTransparent;
        private int mTransparent;
        private int mWarning;
        private Drawable mGradient;

        private int mMode = -1;
        private boolean mAnimating;
        private long mStartTime;
        private long mEndTime;

        private int mGradientAlpha;
        private int mColor;
        private float mOverrideAlpha = 1f;
        private PorterDuffColorFilter mTintFilter;
        private Paint mPaint = new Paint();

        private int mGradientAlphaStart;
        private int mColorStart;
        private Rect mFrame;


        public BarBackgroundDrawable(Context context, int gradientResourceId,
                                     int opaqueColorResourceId, int semiTransparentColorResourceId,
                                     int transparentColorResourceId, int warningColorResourceId) {
            res = context.getResources();
            mContext = context;
            mGradientResourceId = gradientResourceId;
            mOpaqueColorResourceId = opaqueColorResourceId;
            mSemiTransparentColorResourceId = semiTransparentColorResourceId;
            mTransparentColorResourceId = transparentColorResourceId;
            mWarningColorResourceId = warningColorResourceId;
            updateResources(res);
        }

        public void setFrame(Rect frame) {
            mFrame = frame;
        }

        public void setOverrideAlpha(float overrideAlpha) {
            mOverrideAlpha = overrideAlpha;
            invalidateSelf();
        }

        public float getOverrideAlpha() {
            return mOverrideAlpha;
        }

        public int getColor() {
            return getTargetColor();
        }

        public Rect getFrame() {
            return mFrame;
        }

        @Override
        public void setAlpha(int alpha) {
            // noop
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            // noop
        }

        @Override
        public void setTint(int color) {
            PorterDuff.Mode targetMode = mTintFilter == null ? Mode.SRC_IN :
                    mTintFilter.getMode();
            if (mTintFilter == null || mTintFilter.getColor() != color) {
                mTintFilter = new PorterDuffColorFilter(color, targetMode);
            }
            invalidateSelf();
        }

        @Override
        public void setTintMode(Mode tintMode) {
            int targetColor = mTintFilter == null ? 0 : mTintFilter.getColor();
            if (mTintFilter == null || mTintFilter.getMode() != tintMode) {
                mTintFilter = new PorterDuffColorFilter(targetColor, tintMode);
            }
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            mGradient.setBounds(bounds);
        }

        public final synchronized void updateResources(Resources resources) {
            Rect bounds;
            mOpaque = resources.getColor(mOpaqueColorResourceId);
            mSemiTransparent = resources.getColor(mSemiTransparentColorResourceId);
            mOpaque = resources.getColor(mOpaqueColorResourceId);
            mSemiTransparent = resources.getColor(mSemiTransparentColorResourceId);
            mTransparent = resources.getColor(mTransparentColorResourceId);
            mWarning = mWarningColorResourceId;
            if (mGradient == null) {
                bounds = new Rect();
            } else {
                bounds = mGradient.getBounds();
            }
            mGradient = resources.getDrawable(mGradientResourceId, mContext.getTheme());
            mGradient.setBounds(bounds);
            setCurrentColor(getTargetColor());
            setCurrentGradientAlpha(getTargetGradientAlpha());
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        public final void finishAnimation() {
            mHandler.post(() -> {
                int getTargetColor = getTargetColor();
                int getTargetGradientAlpha = getTargetGradientAlpha();
                if (getTargetColor != mCurrentColor || getTargetGradientAlpha != mCurrentGradientAlpha) {
                    setCurrentColor(getTargetColor);
                    setCurrentGradientAlpha(getTargetGradientAlpha);
                    invalidateSelf();
                }
            });
        }

        @Override
        public void draw(Canvas canvas) {
            int currentColor = mCurrentColor;
            if (Color.alpha(currentColor) > 0) {
                mPaint.setColor(currentColor);
                if (mFrame != null) {
                    canvas.drawRect(mFrame, mPaint);
                    if (mColorOverride != null) {
                        targetColor = mColorOverride;
                    }
                    mPaint.setAlpha((int) (Color.alpha(currentColor) * mOverrideAlpha));
                } else {
                    canvas.drawPaint(mPaint);
                }
            }
            int currentGradientAlpha = mCurrentGradientAlpha;
            if (currentGradientAlpha > 0) {
                mGradient.setAlpha(currentGradientAlpha);
                mGradient.draw(canvas);
            }
        }

        protected int getColorOpaque() {
            return mOpaque;
        }

        protected int getColorwarning() {
            return mWarning;
        }

        protected int getColorSemiTransparent() {
            return mSemiTransparent;
        }

        protected int getColorTransparent() {
            return mTransparent;
        }

        protected int getGradientAlphaOpaque() {
            return 0;
        }

        protected int getGradientAlphaSemiTransparent() {
            return 0;
        }

        private final int getTargetColor() {
            return getTargetColor(mCurrentMode);
        }

        private boolean isena() {
            return BarBackgroundUpdater.mStatusEnabled || BarBackgroundUpdater.mNavigationEnabled;
        }

        public boolean ls() {
            return NotificationPanelViewController.mKeyguardShowingDsb;
        }

        private static ComponentName getCurrentDefaultHome() {
            List<ResolveInfo> homeActivities = new ArrayList<>();
            ComponentName defaultHome =
                    PackageManagerWrapper.getInstance().getHomeActivities(homeActivities);
            if (defaultHome != null) {
                return defaultHome;
            }

            int topPriority = Integer.MIN_VALUE;
            ComponentName topComponent = null;
            for (ResolveInfo resolveInfo : homeActivities) {
                if (resolveInfo.priority > topPriority) {
                    topComponent = resolveInfo.activityInfo.getComponentName();
                    topPriority = resolveInfo.priority;
                } else if (resolveInfo.priority == topPriority) {
                    topComponent = null;
                }
            }
            return topComponent;
        }

        public boolean ishome() {
            isMusic();
            ActivityManager.RunningTaskInfo runningTaskInfo = ActivityManagerWrapper.getInstance().getRunningTask();
            if (runningTaskInfo == null) {
                return false;
            } else {
                return runningTaskInfo.topActivity.equals(getCurrentDefaultHome());
            }
        }

        public boolean isMusic() {
            ActivityManager.RunningTaskInfo runningTaskInfo = ActivityManagerWrapper.getInstance().getRunningTask();
            if (runningTaskInfo == null) {
                return false;
            } else {
                return runningTaskInfo.topActivity.equals("com.sonyericsson.music");
            }
        }

        private final int getTargetColor(int mode) {
            switch (mode) {
                case MODE_SEMI_TRANSPARENT:
                    return isena() ? getColorOpaque() : getColorSemiTransparent();
                case MODE_TRANSLUCENT:
                    return isena() ? getColorOpaque() : getColorSemiTransparent();
                case MODE_TRANSPARENT:
                    return isena() ? getColorOpaque() : getColorTransparent();
                case MODE_WARNING:
                    return getColorOpaque();
                case MODE_LIGHTS_OUT_TRANSPARENT:
                    return isena() ? getColorOpaque() : getColorTransparent();
                default:
                    return getColorOpaque();
            }
        }

        private final int getTargetGradientAlpha() {
            return getTargetGradientAlpha(mCurrentMode);
        }

        private final int getTargetGradientAlpha(int mode) {
            switch (mode) {
                case MODE_SEMI_TRANSPARENT:
                    return getGradientAlphaSemiTransparent();
                case MODE_TRANSLUCENT:
                    return 0xff;
                case MODE_TRANSPARENT:
                    return 0;
                default:
                    return getGradientAlphaOpaque();
            }
        }

        protected final void setCurrentColor(int color) {
            mCurrentColor = color;
        }

        protected final void setCurrentGradientAlpha(int alpha) {
            mCurrentGradientAlpha = alpha;
        }

        public final synchronized void applyMode(final int mode, boolean animate) {
            mCurrentMode = mode;
            mHandler.post(() -> {
                int targetColor = (ishome() || ls()) ? getColorTransparent() : getTargetColor(mode);
                int targetGradientAlpha = getTargetGradientAlpha(mode);
                if (targetColor != mCurrentColor || targetGradientAlpha != mCurrentGradientAlpha) {
                    setCurrentColor(targetColor);
                    setCurrentGradientAlpha(targetGradientAlpha);
                    invalidateSelf();
                }
            });
        }

        public final void generateAnimator() {
            generateAnimator(mCurrentMode);
        }

        protected final void generateAnimator(int targetMode) {
            final int targetColor = (ishome() || ls()) ? getColorTransparent() : getTargetColor(targetMode);
            final int targetGradientAlpha = getTargetGradientAlpha(targetMode);
            if (targetColor != mCurrentColor || targetGradientAlpha != mCurrentGradientAlpha) {
                mHandler.post(() -> {
                    if (targetColor == mCurrentColor) {
                        setCurrentGradientAlpha(targetGradientAlpha);
                    }
                    if (targetGradientAlpha == mCurrentGradientAlpha) {
                        setCurrentColor(targetColor);
                    }
                    setCurrentColor(targetColor);
                    setCurrentGradientAlpha(targetGradientAlpha);
                    invalidateSelf();
                });
            }
        }
    }
}
