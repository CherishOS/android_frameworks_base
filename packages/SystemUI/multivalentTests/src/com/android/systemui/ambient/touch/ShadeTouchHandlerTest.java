/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.systemui.ambient.touch;


import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.statusbar.phone.CentralSurfaces;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ShadeTouchHandlerTest extends SysuiTestCase {
    @Mock
    CentralSurfaces mCentralSurfaces;

    @Mock
    TouchHandler.TouchSession mTouchSession;

    ShadeTouchHandler mTouchHandler;

    @Captor
    ArgumentCaptor<GestureDetector.OnGestureListener> mGestureListenerCaptor;
    @Captor
    ArgumentCaptor<InputChannelCompat.InputEventListener> mInputListenerCaptor;

    private static final int TOUCH_HEIGHT = 20;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mTouchHandler = new ShadeTouchHandler(Optional.of(mCentralSurfaces), TOUCH_HEIGHT);
    }

    // Verifies that a swipe down in the gesture region is captured by the shade touch handler.
    @Test
    public void testSwipeDown_captured() {
        final boolean captured = swipe(Direction.DOWN);

        assertThat(captured).isTrue();
    }

    // Verifies that a swipe in the upward direction is not catpured.
    @Test
    public void testSwipeUp_notCaptured() {
        final boolean captured = swipe(Direction.UP);

        // Motion events not captured as the swipe is going in the wrong direction.
        assertThat(captured).isFalse();
    }

    // Verifies that a swipe down forwards captured touches to the shade window for handling.
    @Test
    public void testSwipeDown_sentToShadeWindow() {
        swipe(Direction.DOWN);

        // Both motion events are sent for the shade window to process.
        verify(mCentralSurfaces, times(2)).handleExternalShadeWindowTouch(any());
    }

    // Verifies that a swipe down is not forwarded to the shade window.
    @Test
    public void testSwipeUp_touchesNotSent() {
        swipe(Direction.UP);

        // Motion events are not sent for the shade window to process as the swipe is going in the
        // wrong direction.
        verify(mCentralSurfaces, never()).handleExternalShadeWindowTouch(any());
    }

    /**
     * Simulates a swipe in the given direction and returns true if the touch was intercepted by the
     * touch handler's gesture listener.
     * <p>
     * Swipe down starts from a Y coordinate of 0 and goes downward. Swipe up starts from the edge
     * of the gesture region, {@link #TOUCH_HEIGHT}, and goes upward to 0.
     */
    private boolean swipe(Direction direction) {
        Mockito.clearInvocations(mTouchSession);
        mTouchHandler.onSessionStart(mTouchSession);

        verify(mTouchSession).registerGestureListener(mGestureListenerCaptor.capture());
        verify(mTouchSession).registerInputListener(mInputListenerCaptor.capture());

        final float startY = direction == Direction.UP ? TOUCH_HEIGHT : 0;
        final float endY = direction == Direction.UP ? 0 : TOUCH_HEIGHT;

        // Send touches to the input and gesture listener.
        final MotionEvent event1 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, startY, 0);
        final MotionEvent event2 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, endY, 0);
        mInputListenerCaptor.getValue().onInputEvent(event1);
        mInputListenerCaptor.getValue().onInputEvent(event2);
        final boolean captured = mGestureListenerCaptor.getValue().onScroll(event1, event2, 0,
                startY - endY);

        return captured;
    }

    private enum Direction {
        DOWN, UP,
    }
}
