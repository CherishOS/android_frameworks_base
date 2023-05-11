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

package com.android.wm.shell.splitscreen;

import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static com.android.wm.shell.common.split.SplitScreenConstants.FLAG_IS_DIVIDER_BAR;
import static com.android.wm.shell.splitscreen.SplitScreen.stageTypeToString;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_DRAG_DIVIDER;
import static com.android.wm.shell.splitscreen.SplitScreenController.exitReasonToString;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_DISMISS;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_DISMISS_SNAP;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.RemoteTransition;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransactionCallback;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.split.SplitDecorManager;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.transition.OneShotRemoteHandler;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.util.TransitionUtil;

import java.util.ArrayList;

/** Manages transition animations for split-screen. */
class SplitScreenTransitions {
    private static final String TAG = "SplitScreenTransitions";

    private final TransactionPool mTransactionPool;
    private final Transitions mTransitions;
    private final Runnable mOnFinish;

    DismissSession mPendingDismiss = null;
    TransitSession mPendingEnter = null;
    TransitSession mPendingResize = null;

    private IBinder mAnimatingTransition = null;
    private OneShotRemoteHandler mActiveRemoteHandler = null;

    private final Transitions.TransitionFinishCallback mRemoteFinishCB = this::onFinish;

    /** Keeps track of currently running animations */
    private final ArrayList<Animator> mAnimations = new ArrayList<>();
    private final StageCoordinator mStageCoordinator;

    private Transitions.TransitionFinishCallback mFinishCallback = null;
    private SurfaceControl.Transaction mFinishTransaction;

    SplitScreenTransitions(@NonNull TransactionPool pool, @NonNull Transitions transitions,
            @NonNull Runnable onFinishCallback, StageCoordinator stageCoordinator) {
        mTransactionPool = pool;
        mTransitions = transitions;
        mOnFinish = onFinishCallback;
        mStageCoordinator = stageCoordinator;
    }

    void playAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback,
            @NonNull WindowContainerToken mainRoot, @NonNull WindowContainerToken sideRoot,
            @NonNull WindowContainerToken topRoot) {
        mFinishCallback = finishCallback;
        mAnimatingTransition = transition;
        mFinishTransaction = finishTransaction;

        final TransitSession pendingTransition = getPendingTransition(transition);
        if (pendingTransition != null) {
            if (pendingTransition.mCanceled) {
                // The pending transition was canceled, so skip playing animation.
                startTransaction.apply();
                onFinish(null /* wct */, null /* wctCB */);
                return;
            }

            if (pendingTransition.mRemoteHandler != null) {
                pendingTransition.mRemoteHandler.startAnimation(transition, info, startTransaction,
                        finishTransaction, mRemoteFinishCB);
                mActiveRemoteHandler = pendingTransition.mRemoteHandler;
                return;
            }
        }

        playInternalAnimation(transition, info, startTransaction, mainRoot, sideRoot, topRoot);
    }

    private void playInternalAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull WindowContainerToken mainRoot,
            @NonNull WindowContainerToken sideRoot, @NonNull WindowContainerToken topRoot) {
        // Play some place-holder fade animations
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final SurfaceControl leash = change.getLeash();
            final int mode = info.getChanges().get(i).getMode();

            if (mode == TRANSIT_CHANGE) {
                final int rootIdx = TransitionUtil.rootIndexFor(change, info);
                if (change.getParent() != null) {
                    // This is probably reparented, so we want the parent to be immediately visible
                    final TransitionInfo.Change parentChange = info.getChange(change.getParent());
                    t.show(parentChange.getLeash());
                    t.setAlpha(parentChange.getLeash(), 1.f);
                    // and then animate this layer outside the parent (since, for example, this is
                    // the home task animating from fullscreen to part-screen).
                    t.reparent(parentChange.getLeash(), info.getRoot(rootIdx).getLeash());
                    t.setLayer(parentChange.getLeash(), info.getChanges().size() - i);
                    // build the finish reparent/reposition
                    mFinishTransaction.reparent(leash, parentChange.getLeash());
                    mFinishTransaction.setPosition(leash,
                            change.getEndRelOffset().x, change.getEndRelOffset().y);
                }
            }
            boolean isRootOrSplitSideRoot = change.getParent() == null
                    || topRoot.equals(change.getParent());
            boolean isDivider = change.getFlags() == FLAG_IS_DIVIDER_BAR;
            // For enter or exit, we only want to animate side roots and the divider but not the
            // top-root.
            if (!isRootOrSplitSideRoot || topRoot.equals(change.getContainer()) || isDivider) {
                continue;
            }

            if (isPendingEnter(transition) && (mainRoot.equals(change.getContainer())
                    || sideRoot.equals(change.getContainer()))) {
                t.setPosition(leash, change.getEndAbsBounds().left, change.getEndAbsBounds().top);
                t.setWindowCrop(leash, change.getEndAbsBounds().width(),
                        change.getEndAbsBounds().height());
            } else if (isDivider) {
                t.setPosition(leash, change.getEndAbsBounds().left, change.getEndAbsBounds().top);
                t.setLayer(leash, Integer.MAX_VALUE);
                t.show(leash);
            }
            boolean isOpening = TransitionUtil.isOpeningType(info.getType());
            if (isOpening && (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT)) {
                // fade in
                startExampleAnimation(leash, true /* show */);
            } else if (!isOpening && (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK)) {
                // fade out
                if (info.getType() == TRANSIT_SPLIT_DISMISS_SNAP) {
                    // Dismissing via snap-to-top/bottom means that the dismissed task is already
                    // not-visible (usually cropped to oblivion) so immediately set its alpha to 0
                    // and don't animate it so it doesn't pop-in when reparented.
                    t.setAlpha(leash, 0.f);
                } else {
                    startExampleAnimation(leash, false /* show */);
                }
            }
        }
        t.apply();
        onFinish(null /* wct */, null /* wctCB */);
    }

    void playResizeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback,
            @NonNull WindowContainerToken mainRoot, @NonNull WindowContainerToken sideRoot,
            @NonNull SplitDecorManager mainDecor, @NonNull SplitDecorManager sideDecor) {
        mFinishCallback = finishCallback;
        mAnimatingTransition = transition;
        mFinishTransaction = finishTransaction;

        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (mainRoot.equals(change.getContainer()) || sideRoot.equals(change.getContainer())) {
                final SurfaceControl leash = change.getLeash();
                startTransaction.setPosition(leash, change.getEndAbsBounds().left,
                        change.getEndAbsBounds().top);
                startTransaction.setWindowCrop(leash, change.getEndAbsBounds().width(),
                        change.getEndAbsBounds().height());

                SplitDecorManager decor = mainRoot.equals(change.getContainer())
                        ? mainDecor : sideDecor;

                // This is to ensure onFinished be called after all animations ended.
                ValueAnimator va = new ValueAnimator();
                mAnimations.add(va);

                decor.setScreenshotIfNeeded(change.getSnapshot(), startTransaction);
                decor.onResized(startTransaction, () -> {
                    mTransitions.getMainExecutor().execute(() -> {
                        mAnimations.remove(va);
                        onFinish(null /* wct */, null /* wctCB */);
                    });
                });
            }
        }

        startTransaction.apply();
        onFinish(null /* wct */, null /* wctCB */);
    }

    boolean isPendingTransition(IBinder transition) {
        return getPendingTransition(transition) != null;
    }

    boolean isPendingEnter(IBinder transition) {
        return mPendingEnter != null && mPendingEnter.mTransition == transition;
    }

    boolean isPendingDismiss(IBinder transition) {
        return mPendingDismiss != null && mPendingDismiss.mTransition == transition;
    }

    boolean isPendingResize(IBinder transition) {
        return mPendingResize != null && mPendingResize.mTransition == transition;
    }

    @Nullable
    private TransitSession getPendingTransition(IBinder transition) {
        if (isPendingEnter(transition)) {
            return mPendingEnter;
        } else if (isPendingDismiss(transition)) {
            return mPendingDismiss;
        } else if (isPendingResize(transition)) {
            return mPendingResize;
        }

        return null;
    }

    void startFullscreenTransition(WindowContainerTransaction wct,
            @Nullable RemoteTransition handler) {
        mTransitions.startTransition(TRANSIT_OPEN, wct,
                new OneShotRemoteHandler(mTransitions.getMainExecutor(), handler));
    }


    /** Starts a transition to enter split with a remote transition animator. */
    IBinder startEnterTransition(
            @WindowManager.TransitionType int transitType,
            WindowContainerTransaction wct,
            @Nullable RemoteTransition remoteTransition,
            Transitions.TransitionHandler handler,
            @Nullable TransitionConsumedCallback consumedCallback,
            @Nullable TransitionFinishedCallback finishedCallback,
            int extraTransitType) {
        if (mPendingEnter != null) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "  splitTransition "
                    + " skip to start enter split transition since it already exist. ");
            return null;
        }
        final IBinder transition = mTransitions.startTransition(transitType, wct, handler);
        setEnterTransition(transition, remoteTransition, consumedCallback, finishedCallback,
                extraTransitType);
        return transition;
    }

    /** Sets a transition to enter split. */
    void setEnterTransition(@NonNull IBinder transition,
            @Nullable RemoteTransition remoteTransition,
            @Nullable TransitionConsumedCallback consumedCallback,
            @Nullable TransitionFinishedCallback finishedCallback,
            int extraTransitType) {
        mPendingEnter = new TransitSession(
                transition, consumedCallback, finishedCallback, remoteTransition, extraTransitType);

        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "  splitTransition "
                + " deduced Enter split screen");
    }

    /** Starts a transition to dismiss split. */
    IBinder startDismissTransition(WindowContainerTransaction wct,
            Transitions.TransitionHandler handler, @SplitScreen.StageType int dismissTop,
            @SplitScreenController.ExitReason int reason) {
        if (mPendingDismiss != null) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "  splitTransition "
                    + " skip to start dismiss split transition since it already exist. reason to "
                    + " dismiss = %s", exitReasonToString(reason));
            return null;
        }
        final int type = reason == EXIT_REASON_DRAG_DIVIDER
                ? TRANSIT_SPLIT_DISMISS_SNAP : TRANSIT_SPLIT_DISMISS;
        IBinder transition = mTransitions.startTransition(type, wct, handler);
        setDismissTransition(transition, dismissTop, reason);
        return transition;
    }

    /** Sets a transition to dismiss split. */
    void setDismissTransition(@NonNull IBinder transition, @SplitScreen.StageType int dismissTop,
            @SplitScreenController.ExitReason int reason) {
        mPendingDismiss = new DismissSession(transition, reason, dismissTop);

        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "  splitTransition "
                        + " deduced Dismiss due to %s. toTop=%s",
                exitReasonToString(reason), stageTypeToString(dismissTop));
    }

    IBinder startResizeTransition(WindowContainerTransaction wct,
            Transitions.TransitionHandler handler,
            @Nullable TransitionFinishedCallback finishCallback) {
        if (mPendingResize != null) {
            mPendingResize.cancel(null);
            mAnimations.clear();
            onFinish(null /* wct */, null /* wctCB */);
        }

        IBinder transition = mTransitions.startTransition(TRANSIT_CHANGE, wct, handler);
        setResizeTransition(transition, finishCallback);
        return transition;
    }

    void setResizeTransition(@NonNull IBinder transition,
            @Nullable TransitionFinishedCallback finishCallback) {
        mPendingResize = new TransitSession(transition, null /* consumedCb */, finishCallback);
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "  splitTransition "
                + " deduced Resize split screen");
    }

    void mergeAnimation(IBinder transition, TransitionInfo info, SurfaceControl.Transaction t,
            IBinder mergeTarget, Transitions.TransitionFinishCallback finishCallback) {
        if (mergeTarget != mAnimatingTransition) return;

        if (mActiveRemoteHandler != null) {
            mActiveRemoteHandler.mergeAnimation(transition, info, t, mergeTarget, finishCallback);
        } else {
            for (int i = mAnimations.size() - 1; i >= 0; --i) {
                final Animator anim = mAnimations.get(i);
                mTransitions.getAnimExecutor().execute(anim::end);
            }
        }
    }

    boolean end() {
        // If It's remote, there's nothing we can do right now.
        if (mActiveRemoteHandler != null) return false;
        for (int i = mAnimations.size() - 1; i >= 0; --i) {
            final Animator anim = mAnimations.get(i);
            mTransitions.getAnimExecutor().execute(anim::end);
        }
        return true;
    }

    void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
            @Nullable SurfaceControl.Transaction finishT) {
        if (isPendingEnter(transition)) {
            if (!aborted) {
                // An entering transition got merged, appends the rest operations to finish entering
                // split screen.
                mStageCoordinator.finishEnterSplitScreen(finishT);
            }

            mPendingEnter.onConsumed(aborted);
            mPendingEnter = null;
        } else if (isPendingDismiss(transition)) {
            mPendingDismiss.onConsumed(aborted);
            mPendingDismiss = null;
        } else if (isPendingResize(transition)) {
            mPendingResize.onConsumed(aborted);
            mPendingResize = null;
        }
    }

    void onFinish(WindowContainerTransaction wct, WindowContainerTransactionCallback wctCB) {
        if (!mAnimations.isEmpty()) return;

        if (wct == null) wct = new WindowContainerTransaction();
        if (isPendingEnter(mAnimatingTransition)) {
            mPendingEnter.onFinished(wct, mFinishTransaction);
            mPendingEnter = null;
        } else if (isPendingDismiss(mAnimatingTransition)) {
            mPendingDismiss.onFinished(wct, mFinishTransaction);
            mPendingDismiss = null;
        } else if (isPendingResize(mAnimatingTransition)) {
            mPendingResize.onFinished(wct, mFinishTransaction);
            mPendingResize = null;
        }

        mActiveRemoteHandler = null;
        mAnimatingTransition = null;

        mOnFinish.run();
        if (mFinishCallback != null) {
            mFinishCallback.onTransitionFinished(wct /* wct */, wctCB /* wctCB */);
            mFinishCallback = null;
        }
    }

    // TODO(shell-transitions): real animations
    private void startExampleAnimation(@NonNull SurfaceControl leash, boolean show) {
        final float end = show ? 1.f : 0.f;
        final float start = 1.f - end;
        final SurfaceControl.Transaction transaction = mTransactionPool.acquire();
        final ValueAnimator va = ValueAnimator.ofFloat(start, end);
        va.setDuration(500);
        va.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            transaction.setAlpha(leash, start * (1.f - fraction) + end * fraction);
            transaction.apply();
        });
        final Runnable finisher = () -> {
            transaction.setAlpha(leash, end);
            transaction.apply();
            mTransactionPool.release(transaction);
            mTransitions.getMainExecutor().execute(() -> {
                mAnimations.remove(va);
                onFinish(null /* wct */, null /* wctCB */);
            });
        };
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finisher.run();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                finisher.run();
            }
        });
        mAnimations.add(va);
        mTransitions.getAnimExecutor().execute(va::start);
    }

    // TODO(shell-transitions): real animations
    private void startExampleResizeAnimation(@NonNull SurfaceControl leash,
            @NonNull Rect startBounds, @NonNull Rect endBounds) {
        final SurfaceControl.Transaction transaction = mTransactionPool.acquire();
        final ValueAnimator va = ValueAnimator.ofFloat(0.f, 1.f);
        va.setDuration(500);
        va.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            transaction.setWindowCrop(leash,
                    (int) (startBounds.width() * (1.f - fraction) + endBounds.width() * fraction),
                    (int) (startBounds.height() * (1.f - fraction)
                            + endBounds.height() * fraction));
            transaction.setPosition(leash,
                    startBounds.left * (1.f - fraction) + endBounds.left * fraction,
                    startBounds.top * (1.f - fraction) + endBounds.top * fraction);
            transaction.apply();
        });
        final Runnable finisher = () -> {
            transaction.setWindowCrop(leash, 0, 0);
            transaction.setPosition(leash, endBounds.left, endBounds.top);
            transaction.apply();
            mTransactionPool.release(transaction);
            mTransitions.getMainExecutor().execute(() -> {
                mAnimations.remove(va);
                onFinish(null /* wct */, null /* wctCB */);
            });
        };
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finisher.run();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                finisher.run();
            }
        });
        mAnimations.add(va);
        mTransitions.getAnimExecutor().execute(va::start);
    }

    /** Calls when the transition got consumed. */
    interface TransitionConsumedCallback {
        void onConsumed(boolean aborted);
    }

    /** Calls when the transition finished. */
    interface TransitionFinishedCallback {
        void onFinished(WindowContainerTransaction wct, SurfaceControl.Transaction t);
    }

    /** Session for a transition and its clean-up callback. */
    class TransitSession {
        final IBinder mTransition;
        TransitionConsumedCallback mConsumedCallback;
        TransitionFinishedCallback mFinishedCallback;
        OneShotRemoteHandler mRemoteHandler;

        /** Whether the transition was canceled. */
        boolean mCanceled;

        /** A note for extra transit type, to help indicate custom transition. */
        final int mExtraTransitType;

        TransitSession(IBinder transition,
                @Nullable TransitionConsumedCallback consumedCallback,
                @Nullable TransitionFinishedCallback finishedCallback) {
            this(transition, consumedCallback, finishedCallback, null /* remoteTransition */, 0);
        }

        TransitSession(IBinder transition,
                @Nullable TransitionConsumedCallback consumedCallback,
                @Nullable TransitionFinishedCallback finishedCallback,
                @Nullable RemoteTransition remoteTransition, int extraTransitType) {
            mTransition = transition;
            mConsumedCallback = consumedCallback;
            mFinishedCallback = finishedCallback;

            if (remoteTransition != null) {
                // Wrapping the remote transition for ease-of-use. (OneShot handles all the binder
                // linking/death stuff)
                mRemoteHandler = new OneShotRemoteHandler(
                        mTransitions.getMainExecutor(), remoteTransition);
                mRemoteHandler.setTransition(transition);
            }
            mExtraTransitType = extraTransitType;
        }

        /** Sets transition consumed callback. */
        void setConsumedCallback(@Nullable TransitionConsumedCallback callback) {
            mConsumedCallback = callback;
        }

        /** Sets transition finished callback. */
        void setFinishedCallback(@Nullable TransitionFinishedCallback callback) {
            mFinishedCallback = callback;
        }

        /**
         * Cancels the transition. This should be called before playing animation. A canceled
         * transition will skip playing animation.
         *
         * @param finishedCb new finish callback to override.
         */
        void cancel(@Nullable TransitionFinishedCallback finishedCb) {
            mCanceled = true;
            setFinishedCallback(finishedCb);
        }

        void onConsumed(boolean aborted) {
            if (mConsumedCallback != null) {
                mConsumedCallback.onConsumed(aborted);
            }
        }

        void onFinished(WindowContainerTransaction finishWct,
                SurfaceControl.Transaction finishT) {
            if (mFinishedCallback != null) {
                mFinishedCallback.onFinished(finishWct, finishT);
            }
        }
    }

    /** Bundled information of dismiss transition. */
    class DismissSession extends TransitSession {
        final int mReason;
        final @SplitScreen.StageType int mDismissTop;

        DismissSession(IBinder transition, int reason, int dismissTop) {
            super(transition, null /* consumedCallback */, null /* finishedCallback */);
            this.mReason = reason;
            this.mDismissTop = dismissTop;
        }
    }
}
