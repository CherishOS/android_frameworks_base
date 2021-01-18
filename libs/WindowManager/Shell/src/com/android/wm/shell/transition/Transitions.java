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

package com.android.wm.shell.transition;

import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.TransitionInfo.FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.IRemoteTransition;
import android.window.ITransitionPlayer;
import android.window.TransitionFilter;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;
import android.window.WindowOrganizer;

import androidx.annotation.BinderThread;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;
import java.util.Arrays;

/** Plays transition animations */
public class Transitions {
    static final String TAG = "ShellTransitions";

    /** Set to {@code true} to enable shell transitions. */
    public static final boolean ENABLE_SHELL_TRANSITIONS =
            SystemProperties.getBoolean("persist.debug.shell_transit", false);

    private final WindowOrganizer mOrganizer;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mAnimExecutor;
    private final TransitionPlayerImpl mPlayerImpl;
    private final RemoteTransitionHandler mRemoteTransitionHandler;

    /** List of possible handlers. Ordered by specificity (eg. tapped back to front). */
    private final ArrayList<TransitionHandler> mHandlers = new ArrayList<>();

    private static final class ActiveTransition {
        TransitionHandler mFirstHandler = null;
    }

    /** Keeps track of currently tracked transitions and all the animations associated with each */
    private final ArrayMap<IBinder, ActiveTransition> mActiveTransitions = new ArrayMap<>();

    public Transitions(@NonNull WindowOrganizer organizer, @NonNull TransactionPool pool,
            @NonNull ShellExecutor mainExecutor, @NonNull ShellExecutor animExecutor) {
        mOrganizer = organizer;
        mMainExecutor = mainExecutor;
        mAnimExecutor = animExecutor;
        mPlayerImpl = new TransitionPlayerImpl();
        // The very last handler (0 in the list) should be the default one.
        mHandlers.add(new DefaultTransitionHandler(pool, mainExecutor, animExecutor));
        // Next lowest priority is remote transitions.
        mRemoteTransitionHandler = new RemoteTransitionHandler(mainExecutor);
        mHandlers.add(mRemoteTransitionHandler);
    }

    private Transitions() {
        mOrganizer = null;
        mMainExecutor = null;
        mAnimExecutor = null;
        mPlayerImpl = null;
        mRemoteTransitionHandler = null;
    }

    /** Create an empty/non-registering transitions object for system-ui tests. */
    @VisibleForTesting
    public static Transitions createEmptyForTesting() {
        return new Transitions();
    }

    /** Register this transition handler with Core */
    public void register(ShellTaskOrganizer taskOrganizer) {
        if (mPlayerImpl == null) return;
        taskOrganizer.registerTransitionPlayer(mPlayerImpl);
    }

    /**
     * Adds a handler candidate.
     * @see TransitionHandler
     */
    public void addHandler(@NonNull TransitionHandler handler) {
        mHandlers.add(handler);
    }

    public ShellExecutor getMainExecutor() {
        return mMainExecutor;
    }

    public ShellExecutor getAnimExecutor() {
        return mAnimExecutor;
    }

    /** Only use this in tests. This is used to avoid running animations during tests. */
    @VisibleForTesting
    void replaceDefaultHandlerForTest(TransitionHandler handler) {
        mHandlers.set(0, handler);
    }

    /** Register a remote transition to be used when `filter` matches an incoming transition */
    @ExternalThread
    public void registerRemote(@NonNull TransitionFilter filter,
            @NonNull IRemoteTransition remoteTransition) {
        mMainExecutor.execute(() -> mRemoteTransitionHandler.addFiltered(filter, remoteTransition));
    }

    /** Unregisters a remote transition and all associated filters */
    @ExternalThread
    public void unregisterRemote(@NonNull IRemoteTransition remoteTransition) {
        mMainExecutor.execute(() -> mRemoteTransitionHandler.removeFiltered(remoteTransition));
    }

    /** @return true if the transition was triggered by opening something vs closing something */
    public static boolean isOpeningType(@WindowManager.TransitionType int type) {
        return type == TRANSIT_OPEN
                || type == TRANSIT_TO_FRONT
                || type == WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
    }

    /**
     * Reparents all participants into a shared parent and orders them based on: the global transit
     * type, their transit mode, and their destination z-order.
     */
    private static void setupStartState(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t) {
        boolean isOpening = isOpeningType(info.getType());
        if (info.getRootLeash().isValid()) {
            t.show(info.getRootLeash());
        }
        // Put animating stuff above this line and put static stuff below it.
        int zSplitLine = info.getChanges().size();
        // changes should be ordered top-to-bottom in z
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final SurfaceControl leash = change.getLeash();
            final int mode = info.getChanges().get(i).getMode();

            // Don't move anything with an animating parent
            if (change.getParent() != null) {
                if (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT || mode == TRANSIT_CHANGE) {
                    t.show(leash);
                    t.setMatrix(leash, 1, 0, 0, 1);
                    t.setAlpha(leash, 1.f);
                    t.setPosition(leash, change.getEndRelOffset().x, change.getEndRelOffset().y);
                }
                continue;
            }

            t.reparent(leash, info.getRootLeash());
            t.setPosition(leash, change.getStartAbsBounds().left - info.getRootOffset().x,
                    change.getStartAbsBounds().top - info.getRootOffset().y);
            // Put all the OPEN/SHOW on top
            if (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT) {
                t.show(leash);
                t.setMatrix(leash, 1, 0, 0, 1);
                if (isOpening) {
                    // put on top with 0 alpha
                    t.setLayer(leash, zSplitLine + info.getChanges().size() - i);
                    if ((change.getFlags() & FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT) != 0) {
                        // This received a transferred starting window, so make it immediately
                        // visible.
                        t.setAlpha(leash, 1.f);
                    } else {
                        t.setAlpha(leash, 0.f);
                    }
                } else {
                    // put on bottom and leave it visible
                    t.setLayer(leash, zSplitLine - i);
                    t.setAlpha(leash, 1.f);
                }
            } else if (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK) {
                if (isOpening) {
                    // put on bottom and leave visible
                    t.setLayer(leash, zSplitLine - i);
                } else {
                    // put on top
                    t.setLayer(leash, zSplitLine + info.getChanges().size() - i);
                }
            } else { // CHANGE
                t.setLayer(leash, zSplitLine + info.getChanges().size() - i);
            }
        }
    }

    @VisibleForTesting
    void onTransitionReady(@NonNull IBinder transitionToken, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "onTransitionReady %s: %s",
                transitionToken, info);
        final ActiveTransition active = mActiveTransitions.get(transitionToken);
        if (active == null) {
            throw new IllegalStateException("Got transitionReady for non-active transition "
                    + transitionToken + ". expecting one of "
                    + Arrays.toString(mActiveTransitions.keySet().toArray()));
        }
        if (!info.getRootLeash().isValid()) {
            // Invalid root-leash implies that the transition is empty/no-op, so just do
            // housekeeping and return.
            t.apply();
            onFinish(transitionToken);
            return;
        }

        setupStartState(info, t);

        final Runnable finishRunnable = () -> onFinish(transitionToken);
        // If a handler chose to uniquely run this animation, try delegating to it.
        if (active.mFirstHandler != null && active.mFirstHandler.startAnimation(
                transitionToken, info, t, finishRunnable)) {
            return;
        }
        // Otherwise give every other handler a chance (in order)
        for (int i = mHandlers.size() - 1; i >= 0; --i) {
            if (mHandlers.get(i) == active.mFirstHandler) continue;
            if (mHandlers.get(i).startAnimation(transitionToken, info, t, finishRunnable)) {
                return;
            }
        }
        throw new IllegalStateException(
                "This shouldn't happen, maybe the default handler is broken.");
    }

    private void onFinish(IBinder transition) {
        if (!mActiveTransitions.containsKey(transition)) {
            Log.e(TAG, "Trying to finish a non-running transition. Maybe remote crashed?");
            return;
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                "Transition animations finished, notifying core %s", transition);
        mActiveTransitions.remove(transition);
        mOrganizer.finishTransition(transition, null, null);
    }

    void requestStartTransition(@NonNull IBinder transitionToken,
            @Nullable TransitionRequestInfo request) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Transition requested: %s %s",
                transitionToken, request);
        if (mActiveTransitions.containsKey(transitionToken)) {
            throw new RuntimeException("Transition already started " + transitionToken);
        }
        final ActiveTransition active = new ActiveTransition();
        WindowContainerTransaction wct = null;
        for (int i = mHandlers.size() - 1; i >= 0; --i) {
            wct = mHandlers.get(i).handleRequest(transitionToken, request);
            if (wct != null) {
                active.mFirstHandler = mHandlers.get(i);
                break;
            }
        }
        IBinder transition = mOrganizer.startTransition(
                request.getType(), transitionToken, wct);
        mActiveTransitions.put(transition, active);
    }

    /** Start a new transition directly. */
    public IBinder startTransition(@WindowManager.TransitionType int type,
            @NonNull WindowContainerTransaction wct, @Nullable TransitionHandler handler) {
        final ActiveTransition active = new ActiveTransition();
        active.mFirstHandler = handler;
        IBinder transition = mOrganizer.startTransition(type, null /* token */, wct);
        mActiveTransitions.put(transition, active);
        return transition;
    }

    /**
     * Interface for something which can handle a subset of transitions.
     */
    public interface TransitionHandler {
        /**
         * Starts a transition animation. This is always called if handleRequest returned non-null
         * for a particular transition. Otherwise, it is only called if no other handler before
         * it handled the transition.
         *
         * @param finishCallback Call this when finished. This MUST be called on main thread.
         * @return true if transition was handled, false if not (falls-back to default).
         */
        boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction t, @NonNull Runnable finishCallback);

        /**
         * Potentially handles a startTransition request.
         *
         * @param transition The transition whose start is being requested.
         * @param request Information about what is requested.
         * @return WCT to apply with transition-start or null. If a WCT is returned here, this
         *         handler will be the first in line to animate.
         */
        @Nullable
        WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                @NonNull TransitionRequestInfo request);
    }

    @BinderThread
    private class TransitionPlayerImpl extends ITransitionPlayer.Stub {
        @Override
        public void onTransitionReady(IBinder iBinder, TransitionInfo transitionInfo,
                SurfaceControl.Transaction transaction) throws RemoteException {
            mMainExecutor.execute(() -> {
                Transitions.this.onTransitionReady(iBinder, transitionInfo, transaction);
            });
        }

        @Override
        public void requestStartTransition(IBinder iBinder,
                TransitionRequestInfo request) throws RemoteException {
            mMainExecutor.execute(() -> Transitions.this.requestStartTransition(iBinder, request));
        }
    }
}
