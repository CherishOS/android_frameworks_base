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

package com.android.wm.shell.pip;

import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.util.RotationUtils.deltaRotation;
import static android.util.RotationUtils.rotateBounds;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_PIP;
import static android.view.WindowManager.transitTypeToString;
import static android.window.TransitionInfo.FLAG_IS_DISPLAY;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;
import static android.window.TransitionInfo.isIndependent;

import static com.android.wm.shell.pip.PipAnimationController.ANIM_TYPE_ALPHA;
import static com.android.wm.shell.pip.PipAnimationController.ANIM_TYPE_BOUNDS;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_LEAVE_PIP;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;
import static com.android.wm.shell.pip.PipAnimationController.isInPipDirection;
import static com.android.wm.shell.pip.PipAnimationController.isOutPipDirection;
import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP_TO_SPLIT;
import static com.android.wm.shell.transition.Transitions.TRANSIT_REMOVE_PIP;
import static com.android.wm.shell.transition.Transitions.isOpeningType;

import android.app.ActivityManager;
import android.app.TaskInfo;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.ArrayMap;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.util.CounterRotator;

import java.util.Optional;

/**
 * Implementation of transitions for PiP on phone. Responsible for enter (alpha, bounds) and
 * exit animation.
 */
public class PipTransition extends PipTransitionController {

    private static final String TAG = PipTransition.class.getSimpleName();

    private final Context mContext;
    private final PipTransitionState mPipTransitionState;
    private final int mEnterExitAnimationDuration;
    private final PipSurfaceTransactionHelper mSurfaceTransactionHelper;
    private final Optional<SplitScreenController> mSplitScreenOptional;
    private @PipAnimationController.AnimationType int mOneShotAnimationType = ANIM_TYPE_BOUNDS;
    private Transitions.TransitionFinishCallback mFinishCallback;
    private final Rect mExitDestinationBounds = new Rect();
    @Nullable
    private IBinder mExitTransition;
    /** The Task window that is currently in PIP windowing mode. */
    @Nullable
    private WindowContainerToken mCurrentPipTaskToken;

    public PipTransition(Context context,
            PipBoundsState pipBoundsState,
            PipTransitionState pipTransitionState,
            PipMenuController pipMenuController,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipAnimationController pipAnimationController,
            Transitions transitions,
            @NonNull ShellTaskOrganizer shellTaskOrganizer,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<SplitScreenController> splitScreenOptional) {
        super(pipBoundsState, pipMenuController, pipBoundsAlgorithm,
                pipAnimationController, transitions, shellTaskOrganizer);
        mContext = context;
        mPipTransitionState = pipTransitionState;
        mEnterExitAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipResizeAnimationDuration);
        mSurfaceTransactionHelper = pipSurfaceTransactionHelper;
        mSplitScreenOptional = splitScreenOptional;
    }

    @Override
    public void setIsFullAnimation(boolean isFullAnimation) {
        setOneShotAnimationType(isFullAnimation ? ANIM_TYPE_BOUNDS : ANIM_TYPE_ALPHA);
    }

    /**
     * Sets the preferred animation type for one time.
     * This is typically used to set the animation type to
     * {@link PipAnimationController#ANIM_TYPE_ALPHA}.
     */
    private void setOneShotAnimationType(@PipAnimationController.AnimationType int animationType) {
        mOneShotAnimationType = animationType;
    }

    @Override
    public void startExitTransition(int type, WindowContainerTransaction out,
            @Nullable Rect destinationBounds) {
        if (destinationBounds != null) {
            mExitDestinationBounds.set(destinationBounds);
        }
        mExitTransition = mTransitions.startTransition(type, out, this);
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        // Exiting PIP.
        final int type = info.getType();
        if (transition.equals(mExitTransition)) {
            mExitDestinationBounds.setEmpty();
            mExitTransition = null;

            if (mFinishCallback != null) {
                mFinishCallback.onTransitionFinished(null, null);
                mFinishCallback = null;
                throw new RuntimeException("Previous callback not called, aborting exit PIP.");
            }

            final TransitionInfo.Change exitPipChange = findCurrentPipChange(info);
            if (exitPipChange == null) {
                throw new RuntimeException("Cannot find the pip window for exit-pip transition.");
            }

            switch (type) {
                case TRANSIT_EXIT_PIP:
                    startExitAnimation(info, startTransaction, finishCallback, exitPipChange);
                    break;
                case TRANSIT_EXIT_PIP_TO_SPLIT:
                    startExitToSplitAnimation(info, startTransaction, finishTransaction,
                            finishCallback, exitPipChange);
                    break;
                case TRANSIT_REMOVE_PIP:
                    removePipImmediately(info, startTransaction, finishTransaction, finishCallback,
                            exitPipChange);
                    break;
                default:
                    throw new IllegalStateException("mExitTransition with unexpected transit type="
                            + transitTypeToString(type));
            }
            mCurrentPipTaskToken = null;
            return true;
        }

        // Entering PIP.
        if (isEnteringPip(info, mCurrentPipTaskToken)) {
            return startEnterAnimation(info, startTransaction, finishTransaction, finishCallback);
        }

        // For transition that we don't animate, we may need to update the PIP surface, otherwise it
        // will be reset after the transition.
        updatePipForUnhandledTransition(info, startTransaction, finishTransaction);
        return false;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        if (request.getType() == TRANSIT_PIP) {
            WindowContainerTransaction wct = new WindowContainerTransaction();
            if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
                wct.setActivityWindowingMode(request.getTriggerTask().token,
                        WINDOWING_MODE_UNDEFINED);
                final Rect destinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
                wct.setBounds(request.getTriggerTask().token, destinationBounds);
            }
            return wct;
        } else {
            return null;
        }
    }

    @Override
    public void onTransitionMerged(@NonNull IBinder transition) {
        if (transition != mExitTransition) {
            return;
        }
        // This means an expand happened before enter-pip finished and we are now "merging" a
        // no-op transition that happens to match our exit-pip.
        boolean cancelled = false;
        if (mPipAnimationController.getCurrentAnimator() != null) {
            mPipAnimationController.getCurrentAnimator().cancel();
            cancelled = true;
        }
        // Unset exitTransition AFTER cancel so that finishResize knows we are merging.
        mExitTransition = null;
        if (!cancelled) return;
        final ActivityManager.RunningTaskInfo taskInfo = mPipOrganizer.getTaskInfo();
        if (taskInfo != null) {
            startExpandAnimation(taskInfo, mPipOrganizer.getSurfaceControl(),
                    new Rect(mExitDestinationBounds));
        }
        mExitDestinationBounds.setEmpty();
        mCurrentPipTaskToken = null;
    }

    @Override
    public void onFinishResize(TaskInfo taskInfo, Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction,
            @Nullable SurfaceControl.Transaction tx) {

        if (isInPipDirection(direction)) {
            mPipTransitionState.setTransitionState(PipTransitionState.ENTERED_PIP);
        }
        // If there is an expected exit transition, then the exit will be "merged" into this
        // transition so don't fire the finish-callback in that case.
        if (mExitTransition == null && mFinishCallback != null) {
            WindowContainerTransaction wct = new WindowContainerTransaction();
            prepareFinishResizeTransaction(taskInfo, destinationBounds,
                    direction, wct);
            if (tx != null) {
                wct.setBoundsChangeTransaction(taskInfo.token, tx);
            }
            mFinishCallback.onTransitionFinished(wct, null /* callback */);
            mFinishCallback = null;
        }
        finishResizeForMenu(destinationBounds);
    }

    @Override
    public void forceFinishTransition() {
        if (mFinishCallback == null) return;
        mFinishCallback.onTransitionFinished(null /* wct */, null /* callback */);
        mFinishCallback = null;
    }

    @Nullable
    private TransitionInfo.Change findCurrentPipChange(@NonNull TransitionInfo info) {
        if (mCurrentPipTaskToken == null) {
            return null;
        }
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (mCurrentPipTaskToken.equals(change.getContainer())) {
                return change;
            }
        }
        return null;
    }

    private void startExitAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback,
            @NonNull TransitionInfo.Change pipChange) {
        TransitionInfo.Change displayRotationChange = null;
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (change.getMode() == TRANSIT_CHANGE
                    && (change.getFlags() & FLAG_IS_DISPLAY) != 0
                    && change.getStartRotation() != change.getEndRotation()) {
                displayRotationChange = change;
                break;
            }
        }

        if (displayRotationChange != null) {
            // Exiting PIP to fullscreen with orientation change.
            startExpandAndRotationAnimation(info, startTransaction, finishCallback,
                    displayRotationChange, pipChange);
            return;
        }

        // When there is no rotation, we can simply expand the PIP window.
        mFinishCallback = (wct, wctCB) -> {
            mPipOrganizer.onExitPipFinished(pipChange.getTaskInfo());
            finishCallback.onTransitionFinished(wct, wctCB);
        };

        // Set the initial frame as scaling the end to the start.
        final Rect destinationBounds = new Rect(pipChange.getEndAbsBounds());
        final Point offset = pipChange.getEndRelOffset();
        destinationBounds.offset(-offset.x, -offset.y);
        startTransaction.setWindowCrop(pipChange.getLeash(), destinationBounds);
        mSurfaceTransactionHelper.scale(startTransaction, pipChange.getLeash(),
                destinationBounds, mPipBoundsState.getBounds());
        startTransaction.apply();
        startExpandAnimation(pipChange.getTaskInfo(), pipChange.getLeash(), destinationBounds);
    }

    private void startExpandAndRotationAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback,
            @NonNull TransitionInfo.Change displayRotationChange,
            @NonNull TransitionInfo.Change pipChange) {
        final int rotateDelta = deltaRotation(displayRotationChange.getStartRotation(),
                displayRotationChange.getEndRotation());
        final int displayW = displayRotationChange.getEndAbsBounds().width();
        final int displayH = displayRotationChange.getEndAbsBounds().height();

        // Counter-rotate all "going-away" things since they are still in the old orientation.
        final ArrayMap<WindowContainerToken, CounterRotator> counterRotators = new ArrayMap<>();
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (!Transitions.isClosingType(change.getMode())
                    || !isIndependent(change, info)
                    || change.getParent() == null) {
                continue;
            }
            CounterRotator crot = counterRotators.get(change.getParent());
            if (crot == null) {
                crot = new CounterRotator();
                crot.setup(startTransaction,
                        info.getChange(change.getParent()).getLeash(),
                        rotateDelta, displayW, displayH);
                if (crot.getSurface() != null) {
                    // Wallpaper should be placed at the bottom.
                    final int layer = (change.getFlags() & FLAG_IS_WALLPAPER) == 0
                            ? info.getChanges().size() - i
                            : -1;
                    startTransaction.setLayer(crot.getSurface(), layer);
                }
                counterRotators.put(change.getParent(), crot);
            }
            crot.addChild(startTransaction, change.getLeash());
        }
        mFinishCallback = (wct, wctCB) -> {
            for (int i = 0; i < counterRotators.size(); ++i) {
                counterRotators.valueAt(i).cleanUp(info.getRootLeash());
            }
            mPipOrganizer.onExitPipFinished(pipChange.getTaskInfo());
            finishCallback.onTransitionFinished(wct, wctCB);
        };

        // Get the start bounds in new orientation.
        final Rect startBounds = new Rect(pipChange.getStartAbsBounds());
        rotateBounds(startBounds, displayRotationChange.getStartAbsBounds(), rotateDelta);
        final Rect endBounds = new Rect(pipChange.getEndAbsBounds());
        final Point offset = pipChange.getEndRelOffset();
        startBounds.offset(-offset.x, -offset.y);
        endBounds.offset(-offset.x, -offset.y);

        // Reverse the rotation direction for expansion.
        final int pipRotateDelta = deltaRotation(rotateDelta, 0);

        // Set the start frame.
        final int degree, x, y;
        if (pipRotateDelta == ROTATION_90) {
            degree = 90;
            x = startBounds.right;
            y = startBounds.top;
        } else {
            degree = -90;
            x = startBounds.left;
            y = startBounds.bottom;
        }
        mSurfaceTransactionHelper.rotateAndScaleWithCrop(startTransaction, pipChange.getLeash(),
                endBounds, startBounds, new Rect(), degree, x, y, true /* isExpanding */,
                pipRotateDelta == ROTATION_270 /* clockwise */);
        startTransaction.apply();

        // Expand and rotate the pip window to fullscreen.
        final PipAnimationController.PipTransitionAnimator animator =
                mPipAnimationController.getAnimator(pipChange.getTaskInfo(), pipChange.getLeash(),
                        startBounds, startBounds, endBounds, null, TRANSITION_DIRECTION_LEAVE_PIP,
                        0 /* startingAngle */, pipRotateDelta);
        animator.setTransitionDirection(TRANSITION_DIRECTION_LEAVE_PIP)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(mEnterExitAnimationDuration)
                .start();
    }

    private void startExpandAnimation(final TaskInfo taskInfo, final SurfaceControl leash,
            final Rect destinationBounds) {
        PipAnimationController.PipTransitionAnimator animator =
                mPipAnimationController.getAnimator(taskInfo, leash, mPipBoundsState.getBounds(),
                        mPipBoundsState.getBounds(), destinationBounds, null,
                        TRANSITION_DIRECTION_LEAVE_PIP, 0 /* startingAngle */, Surface.ROTATION_0);

        animator.setTransitionDirection(TRANSITION_DIRECTION_LEAVE_PIP)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(mEnterExitAnimationDuration)
                .start();
    }

    /** For {@link Transitions#TRANSIT_REMOVE_PIP}, we just immediately remove the PIP Task. */
    private void removePipImmediately(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback,
            @NonNull TransitionInfo.Change pipChange) {
        startTransaction.apply();
        finishTransaction.setWindowCrop(info.getChanges().get(0).getLeash(),
                mPipBoundsState.getDisplayBounds());
        mPipOrganizer.onExitPipFinished(pipChange.getTaskInfo());
        finishCallback.onTransitionFinished(null, null);
    }

    /** Whether we should handle the given {@link TransitionInfo} animation as entering PIP. */
    private static boolean isEnteringPip(@NonNull TransitionInfo info,
            @Nullable WindowContainerToken currentPipTaskToken) {
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (change.getTaskInfo() != null
                    && change.getTaskInfo().getWindowingMode() == WINDOWING_MODE_PINNED
                    && !change.getContainer().equals(currentPipTaskToken)) {
                // We support TRANSIT_PIP type (from RootWindowContainer) or TRANSIT_OPEN (from apps
                // that enter PiP instantly on opening, mostly from CTS/Flicker tests)
                if (info.getType() == TRANSIT_PIP || info.getType() == TRANSIT_OPEN) {
                    return true;
                }
                // This can happen if the request to enter PIP happens when we are collecting for
                // another transition, such as TRANSIT_CHANGE (display rotation).
                if (info.getType() == TRANSIT_CHANGE) {
                    return true;
                }

                // Please file a bug to handle the unexpected transition type.
                throw new IllegalStateException("Entering PIP with unexpected transition type="
                        + transitTypeToString(info.getType()));
            }
        }
        return false;
    }

    private boolean startEnterAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        // Search for an Enter PiP transition (along with a show wallpaper one)
        TransitionInfo.Change enterPip = null;
        TransitionInfo.Change wallpaper = null;
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (change.getTaskInfo() != null
                    && change.getTaskInfo().getWindowingMode() == WINDOWING_MODE_PINNED) {
                enterPip = change;
            } else if ((change.getFlags() & FLAG_IS_WALLPAPER) != 0) {
                wallpaper = change;
            }
        }
        if (enterPip == null) {
            return false;
        }
        // Keep track of the PIP task.
        mCurrentPipTaskToken = enterPip.getContainer();

        if (mFinishCallback != null) {
            mFinishCallback.onTransitionFinished(null /* wct */, null /* callback */);
            mFinishCallback = null;
            throw new RuntimeException("Previous callback not called, aborting entering PIP.");
        }

        // Show the wallpaper if there is a wallpaper change.
        if (wallpaper != null) {
            startTransaction.show(wallpaper.getLeash());
            startTransaction.setAlpha(wallpaper.getLeash(), 1.f);
        }

        mPipTransitionState.setTransitionState(PipTransitionState.ENTERING_PIP);
        mFinishCallback = finishCallback;
        return startEnterAnimation(enterPip.getTaskInfo(), enterPip.getLeash(),
                startTransaction, finishTransaction, enterPip.getStartRotation(),
                enterPip.getEndRotation());
    }

    private boolean startEnterAnimation(final TaskInfo taskInfo, final SurfaceControl leash,
            final SurfaceControl.Transaction startTransaction,
            final SurfaceControl.Transaction finishTransaction,
            final int startRotation, final int endRotation) {
        setBoundsStateForEntry(taskInfo.topActivity, taskInfo.pictureInPictureParams,
                taskInfo.topActivityInfo);
        final Rect destinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
        final Rect currentBounds = taskInfo.configuration.windowConfiguration.getBounds();
        PipAnimationController.PipTransitionAnimator animator;
        // Set corner radius for entering pip.
        mSurfaceTransactionHelper
                .crop(finishTransaction, leash, destinationBounds)
                .round(finishTransaction, leash, true /* applyCornerRadius */);
        if (taskInfo.pictureInPictureParams != null
                && taskInfo.pictureInPictureParams.isAutoEnterEnabled()
                && mPipTransitionState.getInSwipePipToHomeTransition()) {
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;

            // PiP menu is attached late in the process here to avoid any artifacts on the leash
            // caused by addShellRoot when in gesture navigation mode.
            mPipMenuController.attach(leash);
            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            tx.setMatrix(leash, Matrix.IDENTITY_MATRIX, new float[9])
                    .setPosition(leash, destinationBounds.left, destinationBounds.top)
                    .setWindowCrop(leash, destinationBounds.width(), destinationBounds.height());
            startTransaction.merge(tx);
            startTransaction.apply();
            mPipBoundsState.setBounds(destinationBounds);
            onFinishResize(taskInfo, destinationBounds, TRANSITION_DIRECTION_TO_PIP, null /* tx */);
            sendOnPipTransitionFinished(TRANSITION_DIRECTION_TO_PIP);
            mPipTransitionState.setInSwipePipToHomeTransition(false);
            return true;
        }

        int rotationDelta = deltaRotation(endRotation, startRotation);
        if (rotationDelta != Surface.ROTATION_0) {
            Matrix tmpTransform = new Matrix();
            tmpTransform.postRotate(rotationDelta == Surface.ROTATION_90
                    ? Surface.ROTATION_270 : Surface.ROTATION_90);
            startTransaction.setMatrix(leash, tmpTransform, new float[9]);
        }
        if (mOneShotAnimationType == ANIM_TYPE_BOUNDS) {
            final Rect sourceHintRect =
                    PipBoundsAlgorithm.getValidSourceHintRect(
                            taskInfo.pictureInPictureParams, currentBounds);
            animator = mPipAnimationController.getAnimator(taskInfo, leash, currentBounds,
                    currentBounds, destinationBounds, sourceHintRect, TRANSITION_DIRECTION_TO_PIP,
                    0 /* startingAngle */, rotationDelta);
            if (sourceHintRect == null) {
                // We use content overlay when there is no source rect hint to enter PiP use bounds
                // animation.
                animator.setUseContentOverlay(mContext);
            }
        } else if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            startTransaction.setAlpha(leash, 0f);
            // PiP menu is attached late in the process here to avoid any artifacts on the leash
            // caused by addShellRoot when in gesture navigation mode.
            mPipMenuController.attach(leash);
            animator = mPipAnimationController.getAnimator(taskInfo, leash, destinationBounds,
                    0f, 1f);
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
        } else {
            throw new RuntimeException("Unrecognized animation type: "
                    + mOneShotAnimationType);
        }
        startTransaction.apply();
        animator.setTransitionDirection(TRANSITION_DIRECTION_TO_PIP)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(mEnterExitAnimationDuration)
                .start();

        return true;
    }

    private void startExitToSplitAnimation(TransitionInfo info,
            SurfaceControl.Transaction startTransaction,
            SurfaceControl.Transaction finishTransaction,
            Transitions.TransitionFinishCallback finishCallback,
            TransitionInfo.Change pipChange) {
        final int changeSize = info.getChanges().size();
        if (changeSize < 4) {
            throw new RuntimeException(
                    "Got an exit-pip-to-split transition with unexpected change-list");
        }
        for (int i = changeSize - 1; i >= 0; i--) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final int mode = change.getMode();

            if (mode == TRANSIT_CHANGE && change.getParent() != null) {
                // TODO: perform resize/expand animation for reparented child task.
                continue;
            }

            if (isOpeningType(mode) && change.getParent() == null) {
                final SurfaceControl leash = change.getLeash();
                final Rect endBounds = change.getEndAbsBounds();
                startTransaction
                        .show(leash)
                        .setAlpha(leash, 1f)
                        .setPosition(leash, endBounds.left, endBounds.top)
                        .setWindowCrop(leash, endBounds.width(), endBounds.height());
            }
        }
        mSplitScreenOptional.get().finishEnterSplitScreen(startTransaction);
        startTransaction.apply();

        mPipOrganizer.onExitPipFinished(pipChange.getTaskInfo());
        finishCallback.onTransitionFinished(null, null);
    }

    private void updatePipForUnhandledTransition(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        if (mCurrentPipTaskToken == null) {
            return;
        }
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (!mCurrentPipTaskToken.equals(change.getContainer())) {
                continue;
            }
            // When the PIP window is visible and being a part of the transition, such as display
            // rotation, we need to update its bounds and rounded corner.
            final SurfaceControl leash = change.getLeash();
            final Rect destBounds = mPipBoundsState.getBounds();
            final boolean isInPip = mPipTransitionState.isInPip();
            mSurfaceTransactionHelper
                    .crop(startTransaction, leash, destBounds)
                    .round(startTransaction, leash, isInPip);
            mSurfaceTransactionHelper
                    .crop(finishTransaction, leash, destBounds)
                    .round(finishTransaction, leash, isInPip);
            break;
        }
    }

    private void finishResizeForMenu(Rect destinationBounds) {
        mPipMenuController.movePipMenu(null, null, destinationBounds);
        mPipMenuController.updateMenuBounds(destinationBounds);
    }

    private void prepareFinishResizeTransaction(TaskInfo taskInfo, Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction,
            WindowContainerTransaction wct) {
        Rect taskBounds = null;
        if (isInPipDirection(direction)) {
            // If we are animating from fullscreen using a bounds animation, then reset the
            // activity windowing mode set by WM, and set the task bounds to the final bounds
            taskBounds = destinationBounds;
            wct.setActivityWindowingMode(taskInfo.token, WINDOWING_MODE_UNDEFINED);
            wct.scheduleFinishEnterPip(taskInfo.token, destinationBounds);
        } else if (isOutPipDirection(direction)) {
            // If we are animating to fullscreen, then we need to reset the override bounds
            // on the task to ensure that the task "matches" the parent's bounds.
            taskBounds = (direction == TRANSITION_DIRECTION_LEAVE_PIP)
                    ? null : destinationBounds;
            wct.setWindowingMode(taskInfo.token, getOutPipWindowingMode());
            // Simply reset the activity mode set prior to the animation running.
            wct.setActivityWindowingMode(taskInfo.token, WINDOWING_MODE_UNDEFINED);
        }

        wct.setBounds(taskInfo.token, taskBounds);
    }
}
