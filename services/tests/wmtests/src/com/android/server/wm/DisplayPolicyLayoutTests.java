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

import static android.view.Gravity.BOTTOM;
import static android.view.Gravity.LEFT;
import static android.view.Gravity.RIGHT;
import static android.view.Gravity.TOP;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.View.SYSTEM_UI_FLAG_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_SCREEN_DECOR;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.utils.WmDisplayCutout;

import org.junit.Before;
import org.junit.Test;

@SmallTest
@Presubmit
public class DisplayPolicyLayoutTests extends DisplayPolicyTestsBase {

    private DisplayFrames mFrames;
    private WindowState mWindow;
    private int mRotation = ROTATION_0;
    private boolean mHasDisplayCutout;
    private static final int DECOR_WINDOW_INSET = 50;

    @Before
    public void setUp() throws Exception {
        updateDisplayFrames();

        mWindow = spy(createWindow(null, TYPE_APPLICATION, "window"));
        // We only test window frames set by DisplayPolicy, so here prevents computeFrameLw from
        // changing those frames.
        doNothing().when(mWindow).computeFrameLw();

        final WindowManager.LayoutParams attrs = mWindow.mAttrs;
        attrs.width = MATCH_PARENT;
        attrs.height = MATCH_PARENT;
        attrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        attrs.format = PixelFormat.TRANSLUCENT;
    }

    public void setRotation(int rotation) {
        mRotation = rotation;
        updateDisplayFrames();
    }

    public void addDisplayCutout() {
        mHasDisplayCutout = true;
        updateDisplayFrames();
    }

    private void updateDisplayFrames() {
        final Pair<DisplayInfo, WmDisplayCutout> info = displayInfoAndCutoutForRotation(mRotation,
                mHasDisplayCutout);
        mFrames = new DisplayFrames(mDisplayContent.getDisplayId(), info.first, info.second);
    }

    @Test
    public void addingWindow_doesNotTamperWithSysuiFlags() {
        mWindow.mAttrs.flags |= FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        addWindow(mWindow);

        assertEquals(0, mWindow.mAttrs.systemUiVisibility);
        assertEquals(0, mWindow.mAttrs.subtreeSystemUiVisibility);
    }

    @Test
    public void layoutWindowLw_appDrawsBars() {
        synchronized (mWm.mGlobalLock) {
            mWindow.mAttrs.flags |= FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_appWontDrawBars() {
        synchronized (mWm.mGlobalLock) {
            mWindow.mAttrs.flags &= ~FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, NAV_BAR_HEIGHT);
        }
    }

    @Test
    public void layoutWindowLw_appWontDrawBars_forceStatusAndNav() throws Exception {
        synchronized (mWm.mGlobalLock) {
            mWindow.mAttrs.flags &= ~FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
            mWindow.mAttrs.privateFlags |= PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_keyguardDialog_hideNav() {
        synchronized (mWm.mGlobalLock) {
            mWindow.mAttrs.type = TYPE_KEYGUARD_DIALOG;
            mWindow.mAttrs.flags |= FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
            mWindow.mAttrs.systemUiVisibility = SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* uiMode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null /* attached */, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();

            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_never() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();

            mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), STATUS_BAR_HEIGHT, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_layoutFullscreen() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();

            mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreen() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();

            mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_FULLSCREEN;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), STATUS_BAR_HEIGHT, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreenInCutout() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();

            mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_FULLSCREEN;
            mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, 0);
        }
    }


    @Test
    public void layoutWindowLw_withDisplayCutout_landscape() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();
            setRotation(ROTATION_90);
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
            assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
            assertInsetBy(mWindow.getContentFrameLw(),
                    DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
            assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
            assertInsetBy(mWindow.getDisplayFrameLw(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_seascape() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();
            setRotation(ROTATION_270);
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetBy(mWindow.getParentFrame(), 0, 0, DISPLAY_CUTOUT_HEIGHT, 0);
            assertInsetBy(mWindow.getStableFrameLw(), NAV_BAR_HEIGHT, STATUS_BAR_HEIGHT, 0, 0);
            assertInsetBy(mWindow.getContentFrameLw(),
                    NAV_BAR_HEIGHT, STATUS_BAR_HEIGHT, DISPLAY_CUTOUT_HEIGHT, 0);
            assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
            assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, DISPLAY_CUTOUT_HEIGHT, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreen_landscape() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();
            setRotation(ROTATION_90);

            mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
            assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
            assertInsetBy(mWindow.getContentFrameLw(),
                    DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
            assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_floatingInScreen() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();

            mWindow.mAttrs.flags = FLAG_LAYOUT_IN_SCREEN;
            mWindow.mAttrs.type = TYPE_APPLICATION_OVERLAY;
            mWindow.mAttrs.width = DISPLAY_WIDTH;
            mWindow.mAttrs.height = DISPLAY_HEIGHT;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreenInCutout_landscape() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();
            setRotation(ROTATION_90);

            mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
            assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
            assertInsetBy(mWindow.getContentFrameLw(),
                    DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
            assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_withForwardInset_SoftInputAdjustResize() {
        synchronized (mWm.mGlobalLock) {
            mWindow.mAttrs.softInputMode = SOFT_INPUT_ADJUST_RESIZE;
            addWindow(mWindow);

            final int forwardedInsetBottom = 50;
            mDisplayPolicy.setForwardedInsets(Insets.of(0, 0, 0, forwardedInsetBottom));
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(),
                    STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT + forwardedInsetBottom);
            assertInsetByTopBottom(mWindow.getVisibleFrameLw(),
                    STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT + forwardedInsetBottom);
            assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
            assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_withForwardInset_SoftInputAdjustNothing() {
        synchronized (mWm.mGlobalLock) {
            mWindow.mAttrs.softInputMode = SOFT_INPUT_ADJUST_NOTHING;
            addWindow(mWindow);

            final int forwardedInsetBottom = 50;
            mDisplayPolicy.setForwardedInsets(Insets.of(0, 0, 0, forwardedInsetBottom));
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getVisibleFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
            assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, 0, 0);
        }
    }

    @Test
    public void layoutHint_appWindow() {
        synchronized (mWm.mGlobalLock) {
            // Initialize DisplayFrames
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);

            final Rect outFrame = new Rect();
            final Rect outContentInsets = new Rect();
            final Rect outStableInsets = new Rect();
            final Rect outOutsets = new Rect();
            final DisplayCutout.ParcelableWrapper outDisplayCutout =
                    new DisplayCutout.ParcelableWrapper();

            mDisplayPolicy.getLayoutHintLw(mWindow.mAttrs, null, mFrames,
                    false /* floatingStack */, outFrame, outContentInsets, outStableInsets,
                    outOutsets, outDisplayCutout);

            assertThat(outFrame, is(mFrames.mUnrestricted));
            assertThat(outContentInsets, is(new Rect(0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT)));
            assertThat(outStableInsets, is(new Rect(0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT)));
            assertThat(outOutsets, is(new Rect()));
            assertThat(outDisplayCutout, is(new DisplayCutout.ParcelableWrapper()));
        }
    }

    @Test
    public void layoutHint_appWindowInTask() {
        synchronized (mWm.mGlobalLock) {
            // Initialize DisplayFrames
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);

            final Rect taskBounds = new Rect(100, 100, 200, 200);

            final Rect outFrame = new Rect();
            final Rect outContentInsets = new Rect();
            final Rect outStableInsets = new Rect();
            final Rect outOutsets = new Rect();
            final DisplayCutout.ParcelableWrapper outDisplayCutout =
                    new DisplayCutout.ParcelableWrapper();

            mDisplayPolicy.getLayoutHintLw(mWindow.mAttrs, taskBounds, mFrames,
                    false /* floatingStack */, outFrame, outContentInsets, outStableInsets,
                    outOutsets, outDisplayCutout);

            assertThat(outFrame, is(taskBounds));
            assertThat(outContentInsets, is(new Rect()));
            assertThat(outStableInsets, is(new Rect()));
            assertThat(outOutsets, is(new Rect()));
            assertThat(outDisplayCutout, is(new DisplayCutout.ParcelableWrapper()));
        }
    }

    @Test
    public void layoutHint_appWindowInTask_outsideContentFrame() {
        synchronized (mWm.mGlobalLock) {
            // Initialize DisplayFrames
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);

            // Task is in the nav bar area (usually does not happen, but this is similar enough to
            // the possible overlap with the IME)
            final Rect taskBounds = new Rect(100, mFrames.mContent.bottom + 1,
                    200, mFrames.mContent.bottom + 10);

            final Rect outFrame = new Rect();
            final Rect outContentInsets = new Rect();
            final Rect outStableInsets = new Rect();
            final Rect outOutsets = new Rect();
            final DisplayCutout.ParcelableWrapper outDisplayCutout =
                    new DisplayCutout.ParcelableWrapper();

            mDisplayPolicy.getLayoutHintLw(mWindow.mAttrs, taskBounds, mFrames,
                    true /* floatingStack */, outFrame, outContentInsets, outStableInsets,
                    outOutsets, outDisplayCutout);

            assertThat(outFrame, is(taskBounds));
            assertThat(outContentInsets, is(new Rect()));
            assertThat(outStableInsets, is(new Rect()));
            assertThat(outOutsets, is(new Rect()));
            assertThat(outDisplayCutout, is(new DisplayCutout.ParcelableWrapper()));
        }
    }

    @Test
    public void forceShowSystemBars_clearsSystemUIFlags() {
        synchronized (mWm.mGlobalLock) {
            mDisplayPolicy.mLastSystemUiFlags |= SYSTEM_UI_FLAG_FULLSCREEN;
            mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            mWindow.mAttrs.flags |= FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
            mWindow.mSystemUiVisibility = SYSTEM_UI_FLAG_FULLSCREEN;
            mDisplayPolicy.setForceShowSystemBars(true);
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);
            // triggers updateSystemUiVisibilityLw which will reset the flags as needed
            int finishPostLayoutPolicyLw = mDisplayPolicy.focusChangedLw(mWindow, mWindow);

            assertEquals(WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT, finishPostLayoutPolicyLw);
            assertEquals(0, mDisplayPolicy.mLastSystemUiFlags);
            assertEquals(0, mWindow.mAttrs.systemUiVisibility);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        }
    }

    @Test
    public void testScreenDecorWindows() {
        synchronized (mWm.mGlobalLock) {
            final WindowState decorWindow = createWindow(null, TYPE_APPLICATION_OVERLAY,
                    "decorWindow");
            decorWindow.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
            decorWindow.mAttrs.privateFlags |= PRIVATE_FLAG_IS_SCREEN_DECOR;
            addWindow(decorWindow);
            addWindow(mWindow);

            // Decor on top
            updateDecorWindow(decorWindow, MATCH_PARENT, DECOR_WINDOW_INSET, TOP);
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), DECOR_WINDOW_INSET, NAV_BAR_HEIGHT);

            // Decor on bottom
            updateDecorWindow(decorWindow, MATCH_PARENT, DECOR_WINDOW_INSET, BOTTOM);
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT,
                    DECOR_WINDOW_INSET);

            // Decor on the left
            updateDecorWindow(decorWindow, DECOR_WINDOW_INSET, MATCH_PARENT, LEFT);
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);
            assertInsetBy(mWindow.getContentFrameLw(), DECOR_WINDOW_INSET, STATUS_BAR_HEIGHT, 0,
                    NAV_BAR_HEIGHT);

            // Decor on the right
            updateDecorWindow(decorWindow, DECOR_WINDOW_INSET, MATCH_PARENT, RIGHT);
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);
            assertInsetBy(mWindow.getContentFrameLw(), 0, STATUS_BAR_HEIGHT, DECOR_WINDOW_INSET,
                    NAV_BAR_HEIGHT);

            // Decor not allowed as inset
            updateDecorWindow(decorWindow, DECOR_WINDOW_INSET, DECOR_WINDOW_INSET, TOP);
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        }
    }

    private void updateDecorWindow(WindowState decorWindow, int width, int height, int gravity) {
        decorWindow.mAttrs.width = width;
        decorWindow.mAttrs.height = height;
        decorWindow.mAttrs.gravity = gravity;
        decorWindow.setRequestedSize(width, height);
    }

    /**
     * Asserts that {@code actual} is inset by the given amounts from the full display rect.
     *
     * Convenience wrapper for when only the top and bottom inset are non-zero.
     */
    private void assertInsetByTopBottom(Rect actual, int expectedInsetTop,
            int expectedInsetBottom) {
        assertInsetBy(actual, 0, expectedInsetTop, 0, expectedInsetBottom);
    }

    /** Asserts that {@code actual} is inset by the given amounts from the full display rect. */
    private void assertInsetBy(Rect actual, int expectedInsetLeft, int expectedInsetTop,
            int expectedInsetRight, int expectedInsetBottom) {
        assertEquals(new Rect(expectedInsetLeft, expectedInsetTop,
                mFrames.mDisplayWidth - expectedInsetRight,
                mFrames.mDisplayHeight - expectedInsetBottom), actual);
    }
}
