/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.accessibility;

import static android.view.InputDevice.SOURCE_TOUCHSCREEN;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.ACTION_UP;

import static com.android.server.accessibility.GestureUtils.distance;

import static java.lang.Math.abs;
import static java.util.Arrays.asList;
import static java.util.Arrays.copyOfRange;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;
import android.util.MathUtils;
import android.util.Slog;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.annotations.VisibleForTesting;

/**
 * This class handles magnification in response to touch events.
 *
 * The behavior is as follows:
 *
 * 1. Triple tap toggles permanent screen magnification which is magnifying
 *    the area around the location of the triple tap. One can think of the
 *    location of the triple tap as the center of the magnified viewport.
 *    For example, a triple tap when not magnified would magnify the screen
 *    and leave it in a magnified state. A triple tapping when magnified would
 *    clear magnification and leave the screen in a not magnified state.
 *
 * 2. Triple tap and hold would magnify the screen if not magnified and enable
 *    viewport dragging mode until the finger goes up. One can think of this
 *    mode as a way to move the magnified viewport since the area around the
 *    moving finger will be magnified to fit the screen. For example, if the
 *    screen was not magnified and the user triple taps and holds the screen
 *    would magnify and the viewport will follow the user's finger. When the
 *    finger goes up the screen will zoom out. If the same user interaction
 *    is performed when the screen is magnified, the viewport movement will
 *    be the same but when the finger goes up the screen will stay magnified.
 *    In other words, the initial magnified state is sticky.
 *
 * 3. Magnification can optionally be "triggered" by some external shortcut
 *    affordance. When this occurs via {@link #notifyShortcutTriggered()} a
 *    subsequent tap in a magnifiable region will engage permanent screen
 *    magnification as described in #1. Alternatively, a subsequent long-press
 *    or drag will engage magnification with viewport dragging as described in
 *    #2. Once magnified, all following behaviors apply whether magnification
 *    was engaged via a triple-tap or by a triggered shortcut.
 *
 * 4. Pinching with any number of additional fingers when viewport dragging
 *    is enabled, i.e. the user triple tapped and holds, would adjust the
 *    magnification scale which will become the current default magnification
 *    scale. The next time the user magnifies the same magnification scale
 *    would be used.
 *
 * 5. When in a permanent magnified state the user can use two or more fingers
 *    to pan the viewport. Note that in this mode the content is panned as
 *    opposed to the viewport dragging mode in which the viewport is moved.
 *
 * 6. When in a permanent magnified state the user can use two or more
 *    fingers to change the magnification scale which will become the current
 *    default magnification scale. The next time the user magnifies the same
 *    magnification scale would be used.
 *
 * 7. The magnification scale will be persisted in settings and in the cloud.
 */
@SuppressWarnings("WeakerAccess")
class MagnificationGestureHandler implements EventStreamTransformation {
    private static final String LOG_TAG = "MagnificationEventHandler";

    private static final boolean DEBUG_ALL = false;
    private static final boolean DEBUG_STATE_TRANSITIONS = false || DEBUG_ALL;
    private static final boolean DEBUG_DETECTING = false || DEBUG_ALL;
    private static final boolean DEBUG_PANNING = false || DEBUG_ALL;

    /** @see #handleMotionEventStateDelegating */
    @VisibleForTesting static final int STATE_DELEGATING = 1;
    /** @see DetectingStateHandler */
    @VisibleForTesting static final int STATE_DETECTING = 2;
    /** @see ViewportDraggingStateHandler */
    @VisibleForTesting static final int STATE_VIEWPORT_DRAGGING = 3;
    /** @see PanningScalingStateHandler */
    @VisibleForTesting static final int STATE_PANNING_SCALING = 4;

    private static final float MIN_SCALE = 2.0f;
    private static final float MAX_SCALE = 5.0f;

    @VisibleForTesting final MagnificationController mMagnificationController;

    @VisibleForTesting final DetectingStateHandler mDetectingStateHandler;
    @VisibleForTesting final PanningScalingStateHandler mPanningScalingStateHandler;
    @VisibleForTesting final ViewportDraggingStateHandler mViewportDraggingStateHandler;

    private final ScreenStateReceiver mScreenStateReceiver;

    /**
     * {@code true} if this detector should detect and respond to triple-tap
     * gestures for engaging and disengaging magnification,
     * {@code false} if it should ignore such gestures
     */
    final boolean mDetectTripleTap;

    /**
     * Whether {@link #mShortcutTriggered shortcut} is enabled
     */
    final boolean mDetectShortcutTrigger;

    EventStreamTransformation mNext;

    @VisibleForTesting int mCurrentState;
    @VisibleForTesting int mPreviousState;

    @VisibleForTesting boolean mShortcutTriggered;

    /**
     * Time of last {@link MotionEvent#ACTION_DOWN} while in {@link #STATE_DELEGATING}
     */
    long mDelegatingStateDownTime;

    private PointerCoords[] mTempPointerCoords;
    private PointerProperties[] mTempPointerProperties;

    /**
     * @param context Context for resolving various magnification-related resources
     * @param magnificationController the {@link MagnificationController}
     *
     * @param detectTripleTap {@code true} if this detector should detect and respond to triple-tap
     *                                gestures for engaging and disengaging magnification,
     *                                {@code false} if it should ignore such gestures
     * @param detectShortcutTrigger {@code true} if this detector should be "triggerable" by some
     *                           external shortcut invoking {@link #notifyShortcutTriggered},
     *                           {@code false} if it should ignore such triggers.
     */
    public MagnificationGestureHandler(Context context,
            MagnificationController magnificationController,
            boolean detectTripleTap,
            boolean detectShortcutTrigger) {
        mMagnificationController = magnificationController;

        mDetectingStateHandler = new DetectingStateHandler(context);
        mViewportDraggingStateHandler = new ViewportDraggingStateHandler();
        mPanningScalingStateHandler =
                new PanningScalingStateHandler(context);

        mDetectTripleTap = detectTripleTap;
        mDetectShortcutTrigger = detectShortcutTrigger;

        if (mDetectShortcutTrigger) {
            mScreenStateReceiver = new ScreenStateReceiver(context, this);
            mScreenStateReceiver.register();
        } else {
            mScreenStateReceiver = null;
        }

        transitionTo(STATE_DETECTING);
    }

    @Override
    public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if ((!mDetectTripleTap && !mDetectShortcutTrigger)
                || !event.isFromSource(SOURCE_TOUCHSCREEN)) {
            dispatchTransformedEvent(event, rawEvent, policyFlags);
            return;
        }
        // Local copy to avoid dispatching the same event to more than one state handler
        // in case mPanningScalingStateHandler changes mCurrentState
        int currentState = mCurrentState;
        mPanningScalingStateHandler.onMotionEvent(event, rawEvent, policyFlags);
        switch (currentState) {
            case STATE_DELEGATING: {
                handleMotionEventStateDelegating(event, rawEvent, policyFlags);
            }
            break;
            case STATE_DETECTING: {
                mDetectingStateHandler.onMotionEvent(event, rawEvent, policyFlags);
            }
            break;
            case STATE_VIEWPORT_DRAGGING: {
                mViewportDraggingStateHandler.onMotionEvent(event, rawEvent, policyFlags);
            }
            break;
            case STATE_PANNING_SCALING: {
                // mPanningScalingStateHandler handles events only
                // if this is the current state since it uses ScaleGestureDetector
                // and a GestureDetector which need well formed event stream.
            }
            break;
            default: {
                throw new IllegalStateException("Unknown state: " + currentState);
            }
        }
    }

    @Override
    public void onKeyEvent(KeyEvent event, int policyFlags) {
        if (mNext != null) {
            mNext.onKeyEvent(event, policyFlags);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (mNext != null) {
            mNext.onAccessibilityEvent(event);
        }
    }

    @Override
    public void setNext(EventStreamTransformation next) {
        mNext = next;
    }

    @Override
    public void clearEvents(int inputSource) {
        if (inputSource == SOURCE_TOUCHSCREEN) {
            clearAndTransitionToStateDetecting();
        }

        if (mNext != null) {
            mNext.clearEvents(inputSource);
        }
    }

    @Override
    public void onDestroy() {
        if (mScreenStateReceiver != null) {
            mScreenStateReceiver.unregister();
        }
        clearAndTransitionToStateDetecting();
    }

    void notifyShortcutTriggered() {
        if (mDetectShortcutTrigger) {
            boolean wasMagnifying = mMagnificationController.resetIfNeeded(/* animate */ true);
            if (wasMagnifying) {
                clearAndTransitionToStateDetecting();
            } else {
                toggleShortcutTriggered();
            }
        }
    }

    private void toggleShortcutTriggered() {
        setShortcutTriggered(!mShortcutTriggered);
    }

    private void setShortcutTriggered(boolean state) {
        if (mShortcutTriggered == state) {
            return;
        }

        mShortcutTriggered = state;
        mMagnificationController.setForceShowMagnifiableBounds(state);
    }

    void clearAndTransitionToStateDetecting() {
        setShortcutTriggered(false);
        mCurrentState = STATE_DETECTING;
        mDetectingStateHandler.clear();
        mViewportDraggingStateHandler.clear();
        mPanningScalingStateHandler.clear();
    }

    private void handleMotionEventStateDelegating(MotionEvent event,
            MotionEvent rawEvent, int policyFlags) {
        if (event.getActionMasked() == ACTION_UP) {
            transitionTo(STATE_DETECTING);
        }
        delegateEvent(event, rawEvent, policyFlags);
    }

    void delegateEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mDelegatingStateDownTime = event.getDownTime();
        }
        if (mNext != null) {
            // We cache some events to see if the user wants to trigger magnification.
            // If no magnification is triggered we inject these events with adjusted
            // time and down time to prevent subsequent transformations being confused
            // by stale events. After the cached events, which always have a down, are
            // injected we need to also update the down time of all subsequent non cached
            // events. All delegated events cached and non-cached are delivered here.
            event.setDownTime(mDelegatingStateDownTime);
            dispatchTransformedEvent(event, rawEvent, policyFlags);
        }
    }

    private void dispatchTransformedEvent(MotionEvent event, MotionEvent rawEvent,
            int policyFlags) {
        if (mNext == null) return; // Nowhere to dispatch to

        // If the touchscreen event is within the magnified portion of the screen we have
        // to change its location to be where the user thinks he is poking the
        // UI which may have been magnified and panned.
        if (mMagnificationController.isMagnifying()
                && event.isFromSource(SOURCE_TOUCHSCREEN)
                && mMagnificationController.magnificationRegionContains(
                        event.getX(), event.getY())) {
            final float scale = mMagnificationController.getScale();
            final float scaledOffsetX = mMagnificationController.getOffsetX();
            final float scaledOffsetY = mMagnificationController.getOffsetY();
            final int pointerCount = event.getPointerCount();
            PointerCoords[] coords = getTempPointerCoordsWithMinSize(pointerCount);
            PointerProperties[] properties = getTempPointerPropertiesWithMinSize(
                    pointerCount);
            for (int i = 0; i < pointerCount; i++) {
                event.getPointerCoords(i, coords[i]);
                coords[i].x = (coords[i].x - scaledOffsetX) / scale;
                coords[i].y = (coords[i].y - scaledOffsetY) / scale;
                event.getPointerProperties(i, properties[i]);
            }
            event = MotionEvent.obtain(event.getDownTime(),
                    event.getEventTime(), event.getAction(), pointerCount, properties,
                    coords, 0, 0, 1.0f, 1.0f, event.getDeviceId(), 0, event.getSource(),
                    event.getFlags());
        }
        mNext.onMotionEvent(event, rawEvent, policyFlags);
    }

    private PointerCoords[] getTempPointerCoordsWithMinSize(int size) {
        final int oldSize = (mTempPointerCoords != null) ? mTempPointerCoords.length : 0;
        if (oldSize < size) {
            PointerCoords[] oldTempPointerCoords = mTempPointerCoords;
            mTempPointerCoords = new PointerCoords[size];
            if (oldTempPointerCoords != null) {
                System.arraycopy(oldTempPointerCoords, 0, mTempPointerCoords, 0, oldSize);
            }
        }
        for (int i = oldSize; i < size; i++) {
            mTempPointerCoords[i] = new PointerCoords();
        }
        return mTempPointerCoords;
    }

    private PointerProperties[] getTempPointerPropertiesWithMinSize(int size) {
        final int oldSize = (mTempPointerProperties != null) ? mTempPointerProperties.length
                : 0;
        if (oldSize < size) {
            PointerProperties[] oldTempPointerProperties = mTempPointerProperties;
            mTempPointerProperties = new PointerProperties[size];
            if (oldTempPointerProperties != null) {
                System.arraycopy(oldTempPointerProperties, 0, mTempPointerProperties, 0,
                        oldSize);
            }
        }
        for (int i = oldSize; i < size; i++) {
            mTempPointerProperties[i] = new PointerProperties();
        }
        return mTempPointerProperties;
    }

    private void transitionTo(int state) {
        if (DEBUG_STATE_TRANSITIONS) {
            Slog.i(LOG_TAG, (stateToString(mCurrentState) + " -> " + stateToString(state)
                    + " at " + asList(copyOfRange(new RuntimeException().getStackTrace(), 1, 5)))
                    .replace(getClass().getName(), ""));
        }
        mPreviousState = mCurrentState;
        mCurrentState = state;
    }

    private static String stateToString(int state) {
        switch (state) {
            case STATE_DELEGATING: return "STATE_DELEGATING";
            case STATE_DETECTING: return "STATE_DETECTING";
            case STATE_VIEWPORT_DRAGGING: return "STATE_VIEWPORT_DRAGGING";
            case STATE_PANNING_SCALING: return "STATE_PANNING_SCALING";
            case 0: return "0";
            default: throw new IllegalArgumentException("Unknown state: " + state);
        }
    }

    private interface MotionEventHandler {

        void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags);

        void clear();
    }

    /**
     * This class determines if the user is performing a scale or pan gesture.
     *
     * @see #STATE_PANNING_SCALING
     */
    final class PanningScalingStateHandler extends SimpleOnGestureListener
            implements OnScaleGestureListener, MotionEventHandler {

        private final ScaleGestureDetector mScaleGestureDetector;
        private final GestureDetector mGestureDetector;
        final float mScalingThreshold;

        float mInitialScaleFactor = -1;
        boolean mScaling;

        public PanningScalingStateHandler(Context context) {
            final TypedValue scaleValue = new TypedValue();
            context.getResources().getValue(
                    com.android.internal.R.dimen.config_screen_magnification_scaling_threshold,
                    scaleValue, false);
            mScalingThreshold = scaleValue.getFloat();
            mScaleGestureDetector = new ScaleGestureDetector(context, this);
            mScaleGestureDetector.setQuickScaleEnabled(false);
            mGestureDetector = new GestureDetector(context, this);
        }

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            // Dispatches #onScaleBegin, #onScale, #onScaleEnd
            mScaleGestureDetector.onTouchEvent(event);
            // Dispatches #onScroll
            mGestureDetector.onTouchEvent(event);

            if (mCurrentState != STATE_PANNING_SCALING) {
                return;
            }

            int action = event.getActionMasked();
            if (action == ACTION_POINTER_UP
                    && event.getPointerCount() == 2 // includes the pointer currently being released
                    && mPreviousState == STATE_VIEWPORT_DRAGGING) {

                persistScaleAndTransitionTo(STATE_VIEWPORT_DRAGGING);

            } else if (action == ACTION_UP) {

                persistScaleAndTransitionTo(STATE_DETECTING);

            }
        }

        public void persistScaleAndTransitionTo(int state) {
            mMagnificationController.persistScale();
            clear();
            transitionTo(state);
        }

        @Override
        public boolean onScroll(MotionEvent first, MotionEvent second,
                float distanceX, float distanceY) {
            if (mCurrentState != STATE_PANNING_SCALING) {
                return true;
            }
            if (DEBUG_PANNING) {
                Slog.i(LOG_TAG, "Panned content by scrollX: " + distanceX
                        + " scrollY: " + distanceY);
            }
            mMagnificationController.offsetMagnifiedRegion(distanceX, distanceY,
                    AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (!mScaling) {
                if (mInitialScaleFactor < 0) {
                    mInitialScaleFactor = detector.getScaleFactor();
                    return false;
                }
                final float deltaScale = detector.getScaleFactor() - mInitialScaleFactor;
                if (abs(deltaScale) > mScalingThreshold) {
                    mScaling = true;
                    return true;
                } else {
                    return false;
                }
            }

            final float initialScale = mMagnificationController.getScale();
            final float targetScale = initialScale * detector.getScaleFactor();

            // Don't allow a gesture to move the user further outside the
            // desired bounds for gesture-controlled scaling.
            final float scale;
            if (targetScale > MAX_SCALE && targetScale > initialScale) {
                // The target scale is too big and getting bigger.
                scale = MAX_SCALE;
            } else if (targetScale < MIN_SCALE && targetScale < initialScale) {
                // The target scale is too small and getting smaller.
                scale = MIN_SCALE;
            } else {
                // The target scale may be outside our bounds, but at least
                // it's moving in the right direction. This avoids a "jump" if
                // we're at odds with some other service's desired bounds.
                scale = targetScale;
            }

            final float pivotX = detector.getFocusX();
            final float pivotY = detector.getFocusY();
            mMagnificationController.setScale(scale, pivotX, pivotY, false,
                    AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return (mCurrentState == STATE_PANNING_SCALING);
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            clear();
        }

        @Override
        public void clear() {
            mInitialScaleFactor = -1;
            mScaling = false;
        }

        @Override
        public String toString() {
            return "MagnifiedContentInteractionStateHandler{" +
                    "mInitialScaleFactor=" + mInitialScaleFactor +
                    ", mScaling=" + mScaling +
                    '}';
        }
    }

    /**
     * This class handles motion events when the event dispatcher has
     * determined that the user is performing a single-finger drag of the
     * magnification viewport.
     *
     * @see #STATE_VIEWPORT_DRAGGING
     */
    final class ViewportDraggingStateHandler implements MotionEventHandler {

        /** Whether to disable zoom after dragging ends */
        boolean mZoomedInBeforeDrag;
        private boolean mLastMoveOutsideMagnifiedRegion;

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            final int action = event.getActionMasked();
            switch (action) {
                case ACTION_POINTER_DOWN: {
                    clear();
                    transitionTo(STATE_PANNING_SCALING);
                }
                break;
                case ACTION_MOVE: {
                    if (event.getPointerCount() != 1) {
                        throw new IllegalStateException("Should have one pointer down.");
                    }
                    final float eventX = event.getX();
                    final float eventY = event.getY();
                    if (mMagnificationController.magnificationRegionContains(eventX, eventY)) {
                        mMagnificationController.setCenter(eventX, eventY,
                                /* animate */ mLastMoveOutsideMagnifiedRegion,
                                AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
                        mLastMoveOutsideMagnifiedRegion = false;
                    } else {
                        mLastMoveOutsideMagnifiedRegion = true;
                    }
                }
                break;
                case ACTION_UP: {
                    if (!mZoomedInBeforeDrag) zoomOff();
                    clear();
                    transitionTo(STATE_DETECTING);
                }
                break;

                case ACTION_DOWN:
                case ACTION_POINTER_UP: {
                    throw new IllegalArgumentException(
                            "Unexpected event type: " + MotionEvent.actionToString(action));
                }
            }
        }

        @Override
        public void clear() {
            mLastMoveOutsideMagnifiedRegion = false;
        }

        @Override
        public String toString() {
            return "ViewportDraggingStateHandler{" +
                    "mZoomedInBeforeDrag=" + mZoomedInBeforeDrag +
                    ", mLastMoveOutsideMagnifiedRegion=" + mLastMoveOutsideMagnifiedRegion +
                    '}';
        }
    }

    /**
     * This class handles motion events when the event dispatch has not yet
     * determined what the user is doing. It watches for various tap events.
     *
     * @see #STATE_DETECTING
     */
    final class DetectingStateHandler implements MotionEventHandler, Handler.Callback {

        private static final int MESSAGE_ON_TRIPLE_TAP_AND_HOLD = 1;
        private static final int MESSAGE_TRANSITION_TO_DELEGATING_STATE = 2;

        final int mLongTapMinDelay = ViewConfiguration.getJumpTapTimeout();
        final int mSwipeMinDistance;
        final int mMultiTapMaxDelay;
        final int mMultiTapMaxDistance;

        private MotionEventInfo mDelayedEventQueue;
        MotionEvent mLastDown;
        private MotionEvent mPreLastDown;
        private MotionEvent mLastUp;
        private MotionEvent mPreLastUp;

        Handler mHandler = new Handler(this);

        public DetectingStateHandler(Context context) {
            mMultiTapMaxDelay = ViewConfiguration.getDoubleTapTimeout()
                    + context.getResources().getInteger(
                    com.android.internal.R.integer.config_screen_magnification_multi_tap_adjustment);
            mSwipeMinDistance = ViewConfiguration.get(context).getScaledTouchSlop();
            mMultiTapMaxDistance = ViewConfiguration.get(context).getScaledDoubleTapSlop();
        }

        @Override
        public boolean handleMessage(Message message) {
            final int type = message.what;
            switch (type) {
                case MESSAGE_ON_TRIPLE_TAP_AND_HOLD: {
                    onTripleTapAndHold(/* down */ (MotionEvent) message.obj);
                }
                break;
                case MESSAGE_TRANSITION_TO_DELEGATING_STATE: {
                    transitionToDelegatingState(/* andClear */ true);
                }
                break;
                default: {
                    throw new IllegalArgumentException("Unknown message type: " + type);
                }
            }
            return true;
        }

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            cacheDelayedMotionEvent(event, rawEvent, policyFlags);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {

                    mHandler.removeMessages(MESSAGE_TRANSITION_TO_DELEGATING_STATE);

                    if (!mMagnificationController.magnificationRegionContains(
                            event.getX(), event.getY())) {

                        transitionToDelegatingState(/* andClear */ !mShortcutTriggered);

                    } else if (isMultiTapTriggered(2 /* taps */)) {

                        // 3tap and hold
                        delayedTransitionToDraggingState(event);

                    } else if (mDetectTripleTap
                            // If magnified, delay an ACTION_DOWN for mMultiTapMaxDelay
                            // to ensure reachability of
                            // STATE_PANNING_SCALING(triggerable with ACTION_POINTER_DOWN)
                            || mMagnificationController.isMagnifying()) {

                        delayedTransitionToDelegatingState();

                    } else {

                        // Delegate pending events without delay
                        transitionToDelegatingState(/* andClear */ true);
                    }
                }
                break;
                case ACTION_POINTER_DOWN: {
                    if (mMagnificationController.isMagnifying()) {
                        transitionTo(STATE_PANNING_SCALING);
                        clear();
                    } else {
                        transitionToDelegatingState(/* andClear */ true);
                    }
                }
                break;
                case ACTION_MOVE: {
                    if (isFingerDown()
                            && distance(mLastDown, /* move */ event) > mSwipeMinDistance
                            // For convenience, viewport dragging on 3tap&hold takes precedence
                            // over insta-delegating on 3tap&swipe
                            // (which is a rare combo to be used aside from magnification)
                            && !isMultiTapTriggered(2 /* taps */)) {

                        // Swipe detected - delegate skipping timeout
                        transitionToDelegatingState(/* andClear */ true);
                    }
                }
                break;
                case ACTION_UP: {

                    mHandler.removeMessages(MESSAGE_ON_TRIPLE_TAP_AND_HOLD);

                    if (!mMagnificationController.magnificationRegionContains(
                            event.getX(), event.getY())) {

                        transitionToDelegatingState(/* andClear */ !mShortcutTriggered);

                    } else if (isMultiTapTriggered(3 /* taps */)) {

                        onTripleTap(/* up */ event);

                    } else if (
                            // Possible to be false on: 3tap&drag -> scale -> PTR_UP -> UP
                            isFingerDown()
                                //TODO long tap should never happen here
                            && (timeBetween(mLastDown, /* mLastUp */ event) >= mLongTapMinDelay)
                                    || distance(mLastDown, /* mLastUp */ event)
                                            >= mSwipeMinDistance) {

                        transitionToDelegatingState(/* andClear */ true);

                    }
                }
                break;
            }
        }

        public boolean isMultiTapTriggered(int numTaps) {

            // Shortcut acts as the 2 initial taps
            if (mShortcutTriggered) return tapCount() + 2 >= numTaps;

            return mDetectTripleTap
                    && tapCount() >= numTaps
                    && isMultiTap(mPreLastDown, mLastDown)
                    && isMultiTap(mPreLastUp, mLastUp);
        }

        private boolean isMultiTap(MotionEvent first, MotionEvent second) {
            return GestureUtils.isMultiTap(first, second, mMultiTapMaxDelay, mMultiTapMaxDistance);
        }

        public boolean isFingerDown() {
            return mLastDown != null;
        }

        private long timeBetween(@Nullable MotionEvent a, @Nullable MotionEvent b) {
            if (a == null && b == null) return 0;
            return abs(timeOf(a) - timeOf(b));
        }

        /**
         * Nullsafe {@link MotionEvent#getEventTime} that interprets null event as something that
         * has happened long enough ago to be gone from the event queue.
         * Thus the time for a null event is a small number, that is below any other non-null
         * event's time.
         *
         * @return {@link MotionEvent#getEventTime}, or {@link Long#MIN_VALUE} if the event is null
         */
        private long timeOf(@Nullable MotionEvent event) {
            return event != null ? event.getEventTime() : Long.MIN_VALUE;
        }

        public int tapCount() {
            return MotionEventInfo.countOf(mDelayedEventQueue, ACTION_UP);
        }

        /** -> {@link #STATE_DELEGATING} */
        public void delayedTransitionToDelegatingState() {
            mHandler.sendEmptyMessageDelayed(
                    MESSAGE_TRANSITION_TO_DELEGATING_STATE,
                    mMultiTapMaxDelay);
        }

        /** -> {@link #STATE_VIEWPORT_DRAGGING} */
        public void delayedTransitionToDraggingState(MotionEvent event) {
            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(MESSAGE_ON_TRIPLE_TAP_AND_HOLD, event),
                    ViewConfiguration.getLongPressTimeout());
        }

        @Override
        public void clear() {
            setShortcutTriggered(false);
            mHandler.removeMessages(MESSAGE_ON_TRIPLE_TAP_AND_HOLD);
            mHandler.removeMessages(MESSAGE_TRANSITION_TO_DELEGATING_STATE);
            clearDelayedMotionEvents();
        }


        private void cacheDelayedMotionEvent(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            if (event.getActionMasked() == ACTION_DOWN) {
                mPreLastDown = mLastDown;
                mLastDown = event;
            } else if (event.getActionMasked() == ACTION_UP) {
                mPreLastUp = mLastUp;
                mLastUp = event;
            }

            MotionEventInfo info = MotionEventInfo.obtain(event, rawEvent,
                    policyFlags);
            if (mDelayedEventQueue == null) {
                mDelayedEventQueue = info;
            } else {
                MotionEventInfo tail = mDelayedEventQueue;
                while (tail.mNext != null) {
                    tail = tail.mNext;
                }
                tail.mNext = info;
            }
        }

        private void sendDelayedMotionEvents() {
            while (mDelayedEventQueue != null) {
                MotionEventInfo info = mDelayedEventQueue;
                mDelayedEventQueue = info.mNext;

                // Because MagnifiedInteractionStateHandler requires well-formed event stream
                mPanningScalingStateHandler.onMotionEvent(
                        info.event, info.rawEvent, info.policyFlags);

                delegateEvent(info.event, info.rawEvent, info.policyFlags);

                info.recycle();
            }
        }

        private void clearDelayedMotionEvents() {
            while (mDelayedEventQueue != null) {
                MotionEventInfo info = mDelayedEventQueue;
                mDelayedEventQueue = info.mNext;
                info.recycle();
            }
            mPreLastDown = null;
            mPreLastUp = null;
            mLastDown = null;
            mLastUp = null;
        }

        void transitionToDelegatingState(boolean andClear) {
            transitionTo(STATE_DELEGATING);
            sendDelayedMotionEvents();
            if (andClear) clear();
        }

        private void onTripleTap(MotionEvent up) {

            if (DEBUG_DETECTING) {
                Slog.i(LOG_TAG, "onTripleTap(); delayed: "
                        + MotionEventInfo.toString(mDelayedEventQueue));
            }
            clear();

            // Toggle zoom
            if (mMagnificationController.isMagnifying()) {
                zoomOff();
            } else {
                zoomOn(up.getX(), up.getY());
            }
        }

        void onTripleTapAndHold(MotionEvent down) {

            if (DEBUG_DETECTING) Slog.i(LOG_TAG, "onTripleTapAndHold()");
            clear();

            mViewportDraggingStateHandler.mZoomedInBeforeDrag =
                    mMagnificationController.isMagnifying();

            zoomOn(down.getX(), down.getY());

            transitionTo(STATE_VIEWPORT_DRAGGING);
        }

        @Override
        public String toString() {
            return "DetectingStateHandler{" +
                    "tapCount()=" + tapCount() +
                    ", mDelayedEventQueue=" + MotionEventInfo.toString(mDelayedEventQueue) +
                    '}';
        }
    }

    private void zoomOn(float centerX, float centerY) {
        final float scale = MathUtils.constrain(
                mMagnificationController.getPersistedScale(),
                MIN_SCALE, MAX_SCALE);
        mMagnificationController.setScaleAndCenter(
                scale, centerX, centerY,
                /* animate */ true,
                AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
    }

    private void zoomOff() {
        mMagnificationController.reset(/* animate */ true);
    }

    private static MotionEvent recycleAndNullify(@Nullable MotionEvent event) {
        if (event != null) {
            event.recycle();
        }
        return null;
    }

    @Override
    public String toString() {
        return "MagnificationGestureHandler{" +
                "mDetectingStateHandler=" + mDetectingStateHandler +
                ", mMagnifiedInteractionStateHandler=" + mPanningScalingStateHandler +
                ", mViewportDraggingStateHandler=" + mViewportDraggingStateHandler +
                ", mDetectTripleTap=" + mDetectTripleTap +
                ", mDetectShortcutTrigger=" + mDetectShortcutTrigger +
                ", mCurrentState=" + stateToString(mCurrentState) +
                ", mPreviousState=" + stateToString(mPreviousState) +
                ", mShortcutTriggered=" + mShortcutTriggered +
                ", mDelegatingStateDownTime=" + mDelegatingStateDownTime +
                ", mMagnificationController=" + mMagnificationController +
                '}';
    }

    private static final class MotionEventInfo {

        private static final int MAX_POOL_SIZE = 10;
        private static final Object sLock = new Object();
        private static MotionEventInfo sPool;
        private static int sPoolSize;

        private MotionEventInfo mNext;
        private boolean mInPool;

        public MotionEvent event;
        public MotionEvent rawEvent;
        public int policyFlags;

        public static MotionEventInfo obtain(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            synchronized (sLock) {
                MotionEventInfo info = obtainInternal();
                info.initialize(event, rawEvent, policyFlags);
                return info;
            }
        }

        @NonNull
        private static MotionEventInfo obtainInternal() {
            MotionEventInfo info;
            if (sPoolSize > 0) {
                sPoolSize--;
                info = sPool;
                sPool = info.mNext;
                info.mNext = null;
                info.mInPool = false;
            } else {
                info = new MotionEventInfo();
            }
            return info;
        }

        private void initialize(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            this.event = MotionEvent.obtain(event);
            this.rawEvent = MotionEvent.obtain(rawEvent);
            this.policyFlags = policyFlags;
        }

        public void recycle() {
            synchronized (sLock) {
                if (mInPool) {
                    throw new IllegalStateException("Already recycled.");
                }
                clear();
                if (sPoolSize < MAX_POOL_SIZE) {
                    sPoolSize++;
                    mNext = sPool;
                    sPool = this;
                    mInPool = true;
                }
            }
        }

        private void clear() {
            event = recycleAndNullify(event);
            rawEvent = recycleAndNullify(rawEvent);
            policyFlags = 0;
        }

        static int countOf(MotionEventInfo info, int eventType) {
            if (info == null) return 0;
            return (info.event.getAction() == eventType ? 1 : 0)
                    + countOf(info.mNext, eventType);
        }

        public static String toString(MotionEventInfo info) {
            return info == null
                    ? ""
                    : MotionEvent.actionToString(info.event.getAction()).replace("ACTION_", "")
                            + " " + MotionEventInfo.toString(info.mNext);
        }
    }

    /**
     * BroadcastReceiver used to cancel the magnification shortcut when the screen turns off
     */
    private static class ScreenStateReceiver extends BroadcastReceiver {
        private final Context mContext;
        private final MagnificationGestureHandler mGestureHandler;

        public ScreenStateReceiver(Context context, MagnificationGestureHandler gestureHandler) {
            mContext = context;
            mGestureHandler = gestureHandler;
        }

        public void register() {
            mContext.registerReceiver(this, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }

        public void unregister() {
            mContext.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mGestureHandler.setShortcutTriggered(false);
        }
    }
}
