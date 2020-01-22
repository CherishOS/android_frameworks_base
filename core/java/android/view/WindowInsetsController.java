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

package android.view;

import static android.view.WindowInsets.Type.ime;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimationCallback.InsetsAnimation;
import android.view.animation.Interpolator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface to control windows that generate insets.
 *
 * TODO(118118435): Needs more information and examples once the API is more baked.
 */
public interface WindowInsetsController {

    /**
     * Makes status bars become opaque with solid dark background and light foreground.
     * @hide
     */
    int APPEARANCE_OPAQUE_STATUS_BARS = 1;

    /**
     * Makes navigation bars become opaque with solid dark background and light foreground.
     * @hide
     */
    int APPEARANCE_OPAQUE_NAVIGATION_BARS = 1 << 1;

    /**
     * Makes items on system bars become less noticeable without changing the layout of the bars.
     * @hide
     */
    int APPEARANCE_LOW_PROFILE_BARS = 1 << 2;

    /**
     * Changes the foreground color for light status bars so that the items on the bar can be read
     * clearly.
     */
    int APPEARANCE_LIGHT_STATUS_BARS = 1 << 3;

    /**
     * Changes the foreground color for light navigation bars so that the items on the bar can be
     * read clearly.
     */
    int APPEARANCE_LIGHT_NAVIGATION_BARS = 1 << 4;

    /**
     * Determines the appearance of system bars.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {APPEARANCE_OPAQUE_STATUS_BARS, APPEARANCE_OPAQUE_NAVIGATION_BARS,
            APPEARANCE_LOW_PROFILE_BARS, APPEARANCE_LIGHT_STATUS_BARS,
            APPEARANCE_LIGHT_NAVIGATION_BARS})
    @interface Appearance {
    }

    /**
     * The default option for {@link #setSystemBarsBehavior(int)}. System bars will be forcibly
     * shown on any user interaction on the corresponding display if navigation bars are hidden by
     * {@link #hide(int)} or
     * {@link WindowInsetsAnimationController#setInsetsAndAlpha(Insets, float, float)}.
     * @hide
     */
    int BEHAVIOR_SHOW_BARS_BY_TOUCH = 0;

    /**
     * Option for {@link #setSystemBarsBehavior(int)}: Window would like to remain interactive when
     * hiding navigation bars by calling {@link #hide(int)} or
     * {@link WindowInsetsAnimationController#setInsetsAndAlpha(Insets, float, float)}.
     *
     * <p>When system bars are hidden in this mode, they can be revealed with system gestures, such
     * as swiping from the edge of the screen where the bar is hidden from.</p>
     * @hide
     */
    int BEHAVIOR_SHOW_BARS_BY_SWIPE = 1;

    /**
     * Option for {@link #setSystemBarsBehavior(int)}: Window would like to remain interactive when
     * hiding navigation bars by calling {@link #hide(int)} or
     * {@link WindowInsetsAnimationController#setInsetsAndAlpha(Insets, float, float)}.
     *
     * <p>When system bars are hidden in this mode, they can be revealed temporarily with system
     * gestures, such as swiping from the edge of the screen where the bar is hidden from. These
     * transient system bars will overlay app’s content, may have some degree of transparency, and
     * will automatically hide after a short timeout.</p>
     * @hide
     */
    int BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2;

    /**
     * Determines the behavior of system bars when hiding them by calling {@link #hide}.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {BEHAVIOR_SHOW_BARS_BY_TOUCH, BEHAVIOR_SHOW_BARS_BY_SWIPE,
            BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE})
    @interface Behavior {
    }

    /**
     * Makes a set of windows that cause insets appear on screen.
     * <p>
     * Note that if the window currently doesn't have control over a certain type, it will apply the
     * change as soon as the window gains control. The app can listen to the event by observing
     * {@link View#onApplyWindowInsets} and checking visibility with {@link WindowInsets#isVisible}.
     *
     * @param types A bitmask of {@link InsetsType} specifying what windows the app
     *              would like to make appear on screen.
     * @hide
     */
    void show(@InsetsType int types);

    /**
     * Makes a set of windows causing insets disappear.
     * <p>
     * Note that if the window currently doesn't have control over a certain type, it will apply the
     * change as soon as the window gains control. The app can listen to the event by observing
     * {@link View#onApplyWindowInsets} and checking visibility with {@link WindowInsets#isVisible}.
     *
     * @param types A bitmask of {@link InsetsType} specifying what windows the app
     *              would like to make disappear.
     * @hide
     */
    void hide(@InsetsType int types);

    /**
     * Lets the application control window inset animations in a frame-by-frame manner by modifying
     * the position of the windows in the system causing insets directly.
     *
     * @param types The {@link InsetsType}s the application has requested to control.
     * @param durationMillis Duration of animation in
     *                       {@link java.util.concurrent.TimeUnit#MILLISECONDS}, or -1 if the
     *                       animation doesn't have a predetermined duration.T his value will be
     *                       passed to {@link InsetsAnimation#getDurationMillis()}
     * @param interpolator The interpolator used for this animation, or {@code null} if this
     *                     animation doesn't follow an interpolation curve. This value will be
     *                     passed to {@link InsetsAnimation#getInterpolator()} and used to calculate
     *                     {@link InsetsAnimation#getInterpolatedFraction()}.
     * @param listener The {@link WindowInsetsAnimationControlListener} that gets called when the
     *                 windows are ready to be controlled, among other callbacks.
     *
     * @see InsetsAnimation#getFraction()
     * @see InsetsAnimation#getInterpolatedFraction()
     * @see InsetsAnimation#getInterpolator()
     * @see InsetsAnimation#getDurationMillis()
     * @hide
     */
    void controlWindowInsetsAnimation(@InsetsType int types, long durationMillis,
            @Nullable Interpolator interpolator,
            @NonNull WindowInsetsAnimationControlListener listener);

    /**
     * Lets the application control the animation for showing the IME in a frame-by-frame manner by
     * modifying the position of the IME when it's causing insets.
     *
     * @param durationMillis Duration of the animation in
     *                       {@link java.util.concurrent.TimeUnit#MILLISECONDS}, or -1 if the
     *                       animation doesn't have a predetermined duration. This value will be
     *                       passed to {@link InsetsAnimation#getDurationMillis()}
     * @param interpolator The interpolator used for this animation, or {@code null} if this
     *                     animation doesn't follow an interpolation curve. This value will be
     *                     passed to {@link InsetsAnimation#getInterpolator()} and used to calculate
     *                     {@link InsetsAnimation#getInterpolatedFraction()}.
     * @param listener The {@link WindowInsetsAnimationControlListener} that gets called when the
     *                 IME are ready to be controlled, among other callbacks.
     *
     * @see InsetsAnimation#getFraction()
     * @see InsetsAnimation#getInterpolatedFraction()
     * @see InsetsAnimation#getInterpolator()
     * @see InsetsAnimation#getDurationMillis()
     */
    default void controlInputMethodAnimation(long durationMillis,
            @Nullable Interpolator interpolator,
            @NonNull WindowInsetsAnimationControlListener listener) {
        controlWindowInsetsAnimation(ime(), durationMillis, interpolator, listener);
    }

    /**
     * Makes the IME appear on screen.
     * <p>
     * Note that if the window currently doesn't have control over the IME, because it doesn't have
     * focus, it will apply the change as soon as the window gains control. The app can listen to
     * the event by observing {@link View#onApplyWindowInsets} and checking visibility with
     * {@link WindowInsets#isVisible}.
     *
     * @see #controlInputMethodAnimation
     * @see #hideInputMethod()
     */
    default void showInputMethod() {
        show(ime());
    }

    /**
     * Makes the IME disappear on screen.
     * <p>
     * Note that if the window currently doesn't have control over IME, because it doesn't have
     * focus, it will apply the change as soon as the window gains control. The app can listen to
     * the event by observing {@link View#onApplyWindowInsets} and checking visibility with
     * {@link WindowInsets#isVisible}.
     *
     * @see #controlInputMethodAnimation
     * @see #showInputMethod()
     */
    default void hideInputMethod() {
        hide(ime());
    }

    /**
     * Controls the appearance of system bars.
     * <p>
     * For example, the following statement adds {@link #APPEARANCE_LIGHT_STATUS_BARS}:
     * <pre>
     * setSystemBarsAppearance(APPEARANCE_LIGHT_STATUS_BARS, APPEARANCE_LIGHT_STATUS_BARS)
     * </pre>
     * And the following statement clears it:
     * <pre>
     * setSystemBarsAppearance(0, APPEARANCE_LIGHT_STATUS_BARS)
     * </pre>
     *
     * @param appearance Bitmask of {@link Appearance} flags.
     * @param mask Specifies which flags of appearance should be changed.
     * @see #getSystemBarsAppearance
     */
    void setSystemBarsAppearance(@Appearance int appearance, @Appearance int mask);

    /**
     * Retrieves the requested appearance of system bars.
     *
     * @return The requested bitmask of system bar appearance controlled by this window.
     * @see #setSystemBarsAppearance(int, int)
     */
    @Appearance int getSystemBarsAppearance();

    /**
     * Controls the behavior of system bars.
     *
     * @param behavior Determines how the bars behave when being hidden by the application.
     * @see #getSystemBarsBehavior
     */
    void setSystemBarsBehavior(@Behavior int behavior);

    /**
     * Retrieves the requested behavior of system bars.
     *
     * @return the system bar behavior controlled by this window.
     * @see #setSystemBarsBehavior(int)
     */
    @Behavior int getSystemBarsBehavior();

    /**
     * @hide
     */
    InsetsState getState();
}
