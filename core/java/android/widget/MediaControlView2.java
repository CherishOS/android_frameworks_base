/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.widget;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.session.MediaController;
import android.media.update.ApiLoader;
import android.media.update.MediaControlView2Provider;
import android.media.update.ViewProvider;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
/**
 * A View that contains the controls for MediaPlayer2.
 * It provides a wide range of UI including buttons such as "Play/Pause", "Rewind", "Fast Forward",
 * "Subtitle", "Full Screen", and it is also possible to add multiple custom buttons.
 *
 * <p>
 * <em> MediaControlView2 can be initialized in two different ways: </em>
 * 1) When VideoView2 is initialized, it automatically initializes a MediaControlView2 instance and
 * adds it to the view.
 * 2) Initialize MediaControlView2 programmatically and add it to a ViewGroup instance.
 *
 * In the first option, VideoView2 automatically connects MediaControlView2 to MediaController2,
 * which is necessary to communicate with MediaSession2. In the second option, however, the
 * developer needs to manually retrieve a MediaController2 instance and set it to MediaControlView2
 * by calling setController(MediaController2 controller).
 *
 * TODO PUBLIC API
 * @hide
 */
public class MediaControlView2 extends FrameLayout {
    /** @hide */
    @IntDef({
            BUTTON_PLAY_PAUSE,
            BUTTON_FFWD,
            BUTTON_REW,
            BUTTON_NEXT,
            BUTTON_PREV,
            BUTTON_SUBTITLE,
            BUTTON_FULL_SCREEN,
            BUTTON_OVERFLOW,
            BUTTON_MUTE,
            BUTTON_ASPECT_RATIO,
            BUTTON_SETTINGS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Button {}

    public static final int BUTTON_PLAY_PAUSE = 1;
    public static final int BUTTON_FFWD = 2;
    public static final int BUTTON_REW = 3;
    public static final int BUTTON_NEXT = 4;
    public static final int BUTTON_PREV = 5;
    public static final int BUTTON_SUBTITLE = 6;
    public static final int BUTTON_FULL_SCREEN = 7;
    public static final int BUTTON_OVERFLOW = 8;
    public static final int BUTTON_MUTE = 9;
    public static final int BUTTON_ASPECT_RATIO = 10;
    public static final int BUTTON_SETTINGS = 11;

    private final MediaControlView2Provider mProvider;

    public MediaControlView2(@NonNull Context context) {
        this(context, null);
    }

    public MediaControlView2(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MediaControlView2(@NonNull Context context, @Nullable AttributeSet attrs,
                            int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MediaControlView2(@NonNull Context context, @Nullable AttributeSet attrs,
                            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mProvider = ApiLoader.getProvider(context)
                .createMediaControlView2(this, new SuperProvider());
    }

    /**
     * @hide
     */
    public MediaControlView2Provider getProvider() {
        return mProvider;
    }

    /**
     * Sets MediaController2 instance to control corresponding MediaSession2.
     */
    public void setController(MediaController controller) {
        mProvider.setController_impl(controller);
    }

    /**
     * Shows the control view on screen. It will disappear automatically after 3 seconds of
     * inactivity.
     */
    public void show() {
        mProvider.show_impl();
    }

    /**
     * Shows the control view on screen. It will disappear automatically after {@code timeout}
     * milliseconds of inactivity.
     */
    public void show(int timeout) {
        mProvider.show_impl(timeout);
    }

    /**
     * Returns whether the control view is currently shown or hidden.
     */
    public boolean isShowing() {
        return mProvider.isShowing_impl();
    }

    /**
     * Hide the control view from the screen.
     */
    public void hide() {
        mProvider.hide_impl();
    }

    /**
     * If the media selected has a subtitle track, calling this method will display the subtitle at
     * the bottom of the view. If a media has multiple subtitle tracks, this method will select the
     * first one of them.
     */
    public void showSubtitle() {
        mProvider.showSubtitle_impl();
    }

    /**
     * Hides the currently displayed subtitle.
     */
    public void hideSubtitle() {
        mProvider.hideSubtitle_impl();
    }

    /**
     * Set listeners for previous and next buttons to customize the behavior of clicking them.
     * The UI for these buttons are provided as default and will be automatically displayed when
     * this method is called.
     *
     * @param next Listener for clicking next button
     * @param prev Listener for clicking previous button
     */
    public void setPrevNextListeners(View.OnClickListener next, View.OnClickListener prev) {
        mProvider.setPrevNextListeners_impl(next, prev);
    }

    /**
     * Hides the specified button from view.
     *
     * @param button the constant integer assigned to individual buttons
     * @param visible whether the button should be visible or not
     */
    public void setButtonVisibility(int button, boolean visible) {
        mProvider.setButtonVisibility_impl(button, visible);
    }

    @Override
    protected void onAttachedToWindow() {
        mProvider.onAttachedToWindow_impl();
    }

    @Override
    protected void onDetachedFromWindow() {
        mProvider.onDetachedFromWindow_impl();
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return mProvider.getAccessibilityClassName_impl();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return mProvider.onTouchEvent_impl(ev);
    }

    @Override
    public boolean onTrackballEvent(MotionEvent ev) {
        return mProvider.onTrackballEvent_impl(ev);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mProvider.onKeyDown_impl(keyCode, event);
    }

    @Override
    public void onFinishInflate() {
        mProvider.onFinishInflate_impl();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return mProvider.dispatchKeyEvent_impl(event);
    }

    @Override
    public void setEnabled(boolean enabled) {
        mProvider.setEnabled_impl(enabled);
    }

    private class SuperProvider implements ViewProvider {
        @Override
        public void onAttachedToWindow_impl() {
            MediaControlView2.super.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow_impl() {
            MediaControlView2.super.onDetachedFromWindow();
        }

        @Override
        public CharSequence getAccessibilityClassName_impl() {
            return MediaControlView2.super.getAccessibilityClassName();
        }

        @Override
        public boolean onTouchEvent_impl(MotionEvent ev) {
            return MediaControlView2.super.onTouchEvent(ev);
        }

        @Override
        public boolean onTrackballEvent_impl(MotionEvent ev) {
            return MediaControlView2.super.onTrackballEvent(ev);
        }

        @Override
        public boolean onKeyDown_impl(int keyCode, KeyEvent event) {
            return MediaControlView2.super.onKeyDown(keyCode, event);
        }

        @Override
        public void onFinishInflate_impl() {
            MediaControlView2.super.onFinishInflate();
        }

        @Override
        public boolean dispatchKeyEvent_impl(KeyEvent event) {
            return MediaControlView2.super.dispatchKeyEvent(event);
        }

        @Override
        public void setEnabled_impl(boolean enabled) {
            MediaControlView2.super.setEnabled(enabled);
        }
    }
}
