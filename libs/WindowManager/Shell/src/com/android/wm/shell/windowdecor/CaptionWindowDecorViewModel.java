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

package com.android.wm.shell.windowdecor;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.DesktopModeController;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter;
import com.android.wm.shell.transition.Transitions;

import java.util.function.Supplier;

/**
 * View model for the window decoration with a caption and shadows. Works with
 * {@link CaptionWindowDecoration}.
 */

public class CaptionWindowDecorViewModel implements WindowDecorViewModel {
    private static final String TAG = "CaptionViewModel";
    private final CaptionWindowDecoration.Factory mCaptionWindowDecorFactory;
    private final Supplier<InputManager> mInputManagerSupplier;
    private final ActivityTaskManager mActivityTaskManager;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final Context mContext;
    private final Handler mMainHandler;
    private final Choreographer mMainChoreographer;
    private final DisplayController mDisplayController;
    private final SyncTransactionQueue mSyncQueue;
    private FreeformTaskTransitionStarter mTransitionStarter;
    private DesktopModeController mDesktopModeController;
    private EventReceiver mEventReceiver;
    private InputMonitor mInputMonitor;
    private boolean mTransitionDragActive;

    private final SparseArray<CaptionWindowDecoration> mWindowDecorByTaskId = new SparseArray<>();
    private final DragStartListenerImpl mDragStartListener = new DragStartListenerImpl();
    private EventReceiverFactory mEventReceiverFactory = new EventReceiverFactory();

    public CaptionWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            Choreographer mainChoreographer,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            SyncTransactionQueue syncQueue,
            DesktopModeController desktopModeController) {
        this(
                context,
                mainHandler,
                mainChoreographer,
                taskOrganizer,
                displayController,
                syncQueue,
                desktopModeController,
                new CaptionWindowDecoration.Factory(),
                InputManager::getInstance);
    }

    public CaptionWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            Choreographer mainChoreographer,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            SyncTransactionQueue syncQueue,
            DesktopModeController desktopModeController,
            CaptionWindowDecoration.Factory captionWindowDecorFactory,
            Supplier<InputManager> inputManagerSupplier) {

        mContext = context;
        mMainHandler = mainHandler;
        mMainChoreographer = mainChoreographer;
        mActivityTaskManager = mContext.getSystemService(ActivityTaskManager.class);
        mTaskOrganizer = taskOrganizer;
        mDisplayController = displayController;
        mSyncQueue = syncQueue;
        mDesktopModeController = desktopModeController;

        mCaptionWindowDecorFactory = captionWindowDecorFactory;
        mInputManagerSupplier = inputManagerSupplier;
    }

    void setEventReceiverFactory(EventReceiverFactory eventReceiverFactory) {
        mEventReceiverFactory = eventReceiverFactory;
    }

    @Override
    public void setFreeformTaskTransitionStarter(FreeformTaskTransitionStarter transitionStarter) {
        mTransitionStarter = transitionStarter;
    }

    @Override
    public boolean onTaskOpening(
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        if (!shouldShowWindowDecor(taskInfo)) return false;
        createWindowDecoration(taskInfo, taskSurface, startT, finishT);
        return true;
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;

        decoration.relayout(taskInfo);
    }

    @Override
    public void onTaskChanging(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);

        if (!shouldShowWindowDecor(taskInfo)) {
            if (decoration != null) {
                destroyWindowDecoration(taskInfo);
            }
            return;
        }

        if (decoration == null) {
            createWindowDecoration(taskInfo, taskSurface, startT, finishT);
        } else {
            decoration.relayout(taskInfo, startT, finishT);
        }
    }

    @Override
    public void onTaskClosing(
            RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;

        decoration.relayout(taskInfo, startT, finishT);
    }

    @Override
    public void destroyWindowDecoration(RunningTaskInfo taskInfo) {
        final CaptionWindowDecoration decoration =
                mWindowDecorByTaskId.removeReturnOld(taskInfo.taskId);
        if (decoration == null) return;

        decoration.close();
    }

    private class CaptionTouchEventListener implements
            View.OnClickListener, View.OnTouchListener {

        private final int mTaskId;
        private final WindowContainerToken mTaskToken;
        private final DragResizeCallback mDragResizeCallback;
        private final DragDetector mDragDetector;

        private int mDragPointerId = -1;

        private CaptionTouchEventListener(
                RunningTaskInfo taskInfo,
                DragResizeCallback dragResizeCallback,
                DragDetector dragDetector) {
            mTaskId = taskInfo.taskId;
            mTaskToken = taskInfo.token;
            mDragResizeCallback = dragResizeCallback;
            mDragDetector = dragDetector;
        }

        @Override
        public void onClick(View v) {
            CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
            final int id = v.getId();
            if (id == R.id.close_window) {
                WindowContainerTransaction wct = new WindowContainerTransaction();
                wct.removeTask(mTaskToken);
                if (Transitions.ENABLE_SHELL_TRANSITIONS) {
                    mTransitionStarter.startRemoveTransition(wct);
                } else {
                    mSyncQueue.queue(wct);
                }
            } else if (id == R.id.back_button) {
                injectBackKey();
            } else if (id == R.id.caption_handle) {
                decoration.createHandleMenu();
            } else if (id == R.id.desktop_button) {
                mDesktopModeController.setDesktopModeActive(true);
                decoration.closeHandleMenu();
            } else if (id == R.id.fullscreen_button) {
                mDesktopModeController.setDesktopModeActive(false);
                decoration.closeHandleMenu();
                decoration.setButtonVisibility();
            }
        }

        private void injectBackKey() {
            sendBackEvent(KeyEvent.ACTION_DOWN);
            sendBackEvent(KeyEvent.ACTION_UP);
        }

        private void sendBackEvent(int action) {
            final long when = SystemClock.uptimeMillis();
            final KeyEvent ev = new KeyEvent(when, when, action, KeyEvent.KEYCODE_BACK,
                    0 /* repeat */, 0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0 /* scancode */, KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD);

            ev.setDisplayId(mContext.getDisplay().getDisplayId());
            if (!InputManager.getInstance()
                    .injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)) {
                Log.e(TAG, "Inject input event fail");
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            boolean isDrag = false;
            int id = v.getId();
            if (id != R.id.caption_handle && id != R.id.caption) {
                return false;
            }
            if (id == R.id.caption_handle) {
                isDrag = mDragDetector.detectDragEvent(e);
                handleEventForMove(e);
            }
            if (e.getAction() != MotionEvent.ACTION_DOWN) {
                return isDrag;
            }
            RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            if (taskInfo.isFocused) {
                return isDrag;
            }
            WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.reorder(mTaskToken, true /* onTop */);
            mSyncQueue.queue(wct);
            return true;
        }

        /**
         * @param e {@link MotionEvent} to process
         * @return {@code true} if a drag is happening; or {@code false} if it is not
         */
        private void handleEventForMove(MotionEvent e) {
            RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            int windowingMode = mDesktopModeController
                    .getDisplayAreaWindowingMode(taskInfo.displayId);
            if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
                return;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mDragPointerId = e.getPointerId(0);
                    mDragResizeCallback.onDragResizeStart(
                            0 /* ctrlType */, e.getRawX(0), e.getRawY(0));
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    mDragResizeCallback.onDragResizeMove(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    int statusBarHeight = mDisplayController.getDisplayLayout(taskInfo.displayId)
                            .stableInsets().top;
                    mDragResizeCallback.onDragResizeEnd(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    if (e.getRawY(dragPointerIdx) <= statusBarHeight
                            && DesktopModeStatus.isActive(mContext)) {
                        mDesktopModeController.setDesktopModeActive(false);
                    }
                    break;
                }
            }
        }
    }

    // InputEventReceiver to listen for touch input outside of caption bounds
    class EventReceiver extends InputEventReceiver {
        EventReceiver(InputChannel channel, Looper looper) {
            super(channel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            if (event instanceof MotionEvent) {
                handled = true;
                CaptionWindowDecorViewModel.this.handleReceivedMotionEvent((MotionEvent) event);
            }
            finishInputEvent(event, handled);
        }
    }

    class EventReceiverFactory {
        EventReceiver create(InputChannel channel, Looper looper) {
            return new EventReceiver(channel, looper);
        }
    }

    /**
     * Handle MotionEvents relevant to focused task's caption that don't directly touch it
     *
     * @param ev the {@link MotionEvent} received by {@link EventReceiver}
     */
    private void handleReceivedMotionEvent(MotionEvent ev) {
        if (!DesktopModeStatus.isActive(mContext)) {
            handleCaptionThroughStatusBar(ev);
        }
        handleEventOutsideFocusedCaption(ev);
        // Prevent status bar from reacting to a caption drag.
        if (mTransitionDragActive && !DesktopModeStatus.isActive(mContext)) {
            mInputMonitor.pilferPointers();
        }
    }

    // If an UP/CANCEL action is received outside of caption bounds, turn off handle menu
    private void handleEventOutsideFocusedCaption(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            CaptionWindowDecoration focusedDecor = getFocusedDecor();
            if (focusedDecor == null) {
                return;
            }

            if (!mTransitionDragActive) {
                focusedDecor.closeHandleMenuIfNeeded(ev);
            }
        }
    }

    /**
     * Perform caption actions if not able to through normal means.
     * Turn on desktop mode if handle is dragged below status bar.
     */
    private void handleCaptionThroughStatusBar(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // Begin drag through status bar if applicable.
                CaptionWindowDecoration focusedDecor = getFocusedDecor();
                if (focusedDecor != null && !DesktopModeStatus.isActive(mContext)
                        && focusedDecor.checkTouchEventInHandle(ev)) {
                    mTransitionDragActive = true;
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                CaptionWindowDecoration focusedDecor = getFocusedDecor();
                if (focusedDecor == null) {
                    mTransitionDragActive = false;
                    return;
                }
                if (mTransitionDragActive) {
                    mTransitionDragActive = false;
                    int statusBarHeight = mDisplayController
                            .getDisplayLayout(focusedDecor.mTaskInfo.displayId).stableInsets().top;
                    if (ev.getY() > statusBarHeight) {
                        mDesktopModeController.setDesktopModeActive(true);
                        return;
                    }
                }
                focusedDecor.checkClickEvent(ev);
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                mTransitionDragActive = false;
            }
        }
    }

    @Nullable
    private CaptionWindowDecoration getFocusedDecor() {
        int size = mWindowDecorByTaskId.size();
        CaptionWindowDecoration focusedDecor = null;
        for (int i = 0; i < size; i++) {
            CaptionWindowDecoration decor = mWindowDecorByTaskId.valueAt(i);
            if (decor != null && decor.isFocused()) {
                focusedDecor = decor;
                break;
            }
        }
        return focusedDecor;
    }

    private boolean shouldShowWindowDecor(RunningTaskInfo taskInfo) {
        if (taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) return true;
        return DesktopModeStatus.IS_SUPPORTED
                && taskInfo.getActivityType() == ACTIVITY_TYPE_STANDARD
                && mDisplayController.getDisplayContext(taskInfo.displayId)
                .getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    private void createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        CaptionWindowDecoration oldDecoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (oldDecoration != null) {
            // close the old decoration if it exists to avoid two window decorations being added
            oldDecoration.close();
        }
        final CaptionWindowDecoration windowDecoration =
                mCaptionWindowDecorFactory.create(
                        mContext,
                        mDisplayController,
                        mTaskOrganizer,
                        taskInfo,
                        taskSurface,
                        mMainHandler,
                        mMainChoreographer,
                        mSyncQueue);
        mWindowDecorByTaskId.put(taskInfo.taskId, windowDecoration);

        TaskPositioner taskPositioner =
                new TaskPositioner(mTaskOrganizer, windowDecoration, mDragStartListener);
        CaptionTouchEventListener touchEventListener =
                new CaptionTouchEventListener(
                        taskInfo, taskPositioner, windowDecoration.getDragDetector());
        windowDecoration.setCaptionListeners(touchEventListener, touchEventListener);
        windowDecoration.setDragResizeCallback(taskPositioner);
        windowDecoration.relayout(taskInfo, startT, finishT);
        if (mInputMonitor == null) {
            InputManager inputManager = mInputManagerSupplier.get();
            mInputMonitor =
                    inputManager.monitorGestureInput("caption-touch", mContext.getDisplayId());
            mEventReceiver =
                    mEventReceiverFactory.create(
                            mInputMonitor.getInputChannel(), Looper.myLooper());
        }
    }

    private class DragStartListenerImpl implements TaskPositioner.DragStartListener {
        @Override
        public void onDragStart(int taskId) {
            mWindowDecorByTaskId.get(taskId).closeHandleMenu();
        }
    }
}
