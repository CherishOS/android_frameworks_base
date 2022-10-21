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

package com.android.systemui.clipboardoverlay;

import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_DISMISS_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SHARE_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SWIPE_DISMISSED;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.content.ClipData;
import android.content.ClipDescription;
import android.net.Uri;
import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.screenshot.TimeoutHandler;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Ignore("b/254635291")
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ClipboardOverlayControllerTest extends SysuiTestCase {

    private ClipboardOverlayController mOverlayController;
    @Mock
    private ClipboardOverlayView mClipboardOverlayView;
    @Mock
    private ClipboardOverlayWindow mClipboardOverlayWindow;
    @Mock
    private BroadcastSender mBroadcastSender;
    @Mock
    private TimeoutHandler mTimeoutHandler;
    @Mock
    private UiEventLogger mUiEventLogger;

    @Mock
    private Animator mAnimator;

    private ClipData mSampleClipData;

    @Captor
    private ArgumentCaptor<ClipboardOverlayView.ClipboardOverlayCallbacks> mOverlayCallbacksCaptor;
    private ClipboardOverlayView.ClipboardOverlayCallbacks mCallbacks;


    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mClipboardOverlayView.getEnterAnimation()).thenReturn(mAnimator);
        when(mClipboardOverlayView.getExitAnimation()).thenReturn(mAnimator);

        mSampleClipData = new ClipData("Test", new String[]{"text/plain"},
                new ClipData.Item("Test Item"));

        mOverlayController = new ClipboardOverlayController(
                mContext,
                mClipboardOverlayView,
                mClipboardOverlayWindow,
                getFakeBroadcastDispatcher(),
                mBroadcastSender,
                mTimeoutHandler,
                mUiEventLogger);
        verify(mClipboardOverlayView).setCallbacks(mOverlayCallbacksCaptor.capture());
        mCallbacks = mOverlayCallbacksCaptor.getValue();
    }

    @Test
    public void test_setClipData_nullData() {
        ClipData clipData = null;
        mOverlayController.setClipData(clipData, "");

        verify(mClipboardOverlayView, times(1)).showDefaultTextPreview();
        verify(mClipboardOverlayView, times(0)).showShareChip();
        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_setClipData_invalidImageData() {
        ClipData clipData = new ClipData("", new String[]{"image/png"},
                new ClipData.Item(Uri.parse("")));

        mOverlayController.setClipData(clipData, "");

        verify(mClipboardOverlayView, times(1)).showDefaultTextPreview();
        verify(mClipboardOverlayView, times(0)).showShareChip();
        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_setClipData_textData() {
        mOverlayController.setClipData(mSampleClipData, "");

        verify(mClipboardOverlayView, times(1)).showTextPreview("Test Item", false);
        verify(mClipboardOverlayView, times(1)).showShareChip();
        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_setClipData_sensitiveTextData() {
        ClipDescription description = mSampleClipData.getDescription();
        PersistableBundle b = new PersistableBundle();
        b.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        description.setExtras(b);
        ClipData data = new ClipData(description, mSampleClipData.getItemAt(0));
        mOverlayController.setClipData(data, "");

        verify(mClipboardOverlayView, times(1)).showTextPreview("••••••", true);
        verify(mClipboardOverlayView, times(1)).showShareChip();
        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_setClipData_repeatedCalls() {
        when(mAnimator.isRunning()).thenReturn(true);

        mOverlayController.setClipData(mSampleClipData, "");
        mOverlayController.setClipData(mSampleClipData, "");

        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_viewCallbacks_onShareTapped() {
        mOverlayController.setClipData(mSampleClipData, "");

        mCallbacks.onShareButtonTapped();

        verify(mUiEventLogger, times(1)).log(CLIPBOARD_OVERLAY_SHARE_TAPPED);
        verify(mClipboardOverlayView, times(1)).getExitAnimation();
    }

    @Test
    public void test_viewCallbacks_onDismissTapped() {
        mOverlayController.setClipData(mSampleClipData, "");

        mCallbacks.onDismissButtonTapped();

        verify(mUiEventLogger, times(1)).log(CLIPBOARD_OVERLAY_DISMISS_TAPPED);
        verify(mClipboardOverlayView, times(1)).getExitAnimation();
    }

    @Test
    public void test_multipleDismissals_dismissesOnce() {
        mCallbacks.onSwipeDismissInitiated(mAnimator);
        mCallbacks.onDismissButtonTapped();
        mCallbacks.onSwipeDismissInitiated(mAnimator);
        mCallbacks.onDismissButtonTapped();

        verify(mUiEventLogger, times(1)).log(CLIPBOARD_OVERLAY_SWIPE_DISMISSED);
        verify(mUiEventLogger, never()).log(CLIPBOARD_OVERLAY_DISMISS_TAPPED);
    }
}
