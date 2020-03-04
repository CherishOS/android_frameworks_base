/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.os.CancellationSignal;
import android.view.WindowInsets.Type.InsetsType;
import android.view.animation.Interpolator;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;

/**
 * An insets controller that keeps track of pending requests. This is such that an app can freely
 * use {@link WindowInsetsController} before the view root is attached during activity startup.
 * @hide
 */
public class PendingInsetsController implements WindowInsetsController {

    private static final int KEEP_BEHAVIOR = -1;
    private final ArrayList<PendingRequest> mRequests = new ArrayList<>();
    private @Appearance int mAppearance;
    private @Appearance int mAppearanceMask;
    private @Behavior int mBehavior = KEEP_BEHAVIOR;
    private final InsetsState mDummyState = new InsetsState();
    private InsetsController mReplayedInsetsController;

    @Override
    public void show(int types) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.show(types);
        } else {
            mRequests.add(new ShowRequest(types));
        }
    }

    @Override
    public void hide(int types) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.hide(types);
        } else {
            mRequests.add(new HideRequest(types));
        }
    }

    @Override
    public CancellationSignal controlWindowInsetsAnimation(int types, long durationMillis,
            Interpolator interpolator,
            WindowInsetsAnimationControlListener listener) {
        if (mReplayedInsetsController != null) {
            return mReplayedInsetsController.controlWindowInsetsAnimation(types, durationMillis,
                    interpolator, listener);
        } else {
            listener.onCancelled();
            CancellationSignal cancellationSignal = new CancellationSignal();
            cancellationSignal.cancel();
            return cancellationSignal;
        }
    }

    @Override
    public void setSystemBarsAppearance(int appearance, int mask) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.setSystemBarsAppearance(appearance, mask);
        } else {
            mAppearance = (mAppearance & ~mask) | (appearance & mask);
            mAppearanceMask |= mask;
        }
    }

    @Override
    public int getSystemBarsAppearance() {
        if (mReplayedInsetsController != null) {
            return mReplayedInsetsController.getSystemBarsAppearance();
        }
        return mAppearance;
    }

    @Override
    public void setSystemBarsBehavior(int behavior) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.setSystemBarsBehavior(behavior);
        } else {
            mBehavior = behavior;
        }
    }

    @Override
    public int getSystemBarsBehavior() {
        if (mReplayedInsetsController != null) {
            return mReplayedInsetsController.getSystemBarsBehavior();
        }
        return mBehavior;
    }

    @Override
    public InsetsState getState() {
        return mDummyState;
    }

    /**
     * Replays the commands on {@code controller} and attaches it to this instance such that any
     * calls will be forwarded to the real instance in the future.
     */
    @VisibleForTesting
    public void replayAndAttach(InsetsController controller) {
        if (mBehavior != KEEP_BEHAVIOR) {
            controller.setSystemBarsBehavior(mBehavior);
        }
        if (mAppearanceMask != 0) {
            controller.setSystemBarsAppearance(mAppearance, mAppearanceMask);
        }
        int size = mRequests.size();
        for (int i = 0; i < size; i++) {
            mRequests.get(i).replay(controller);
        }

        // Reset all state so it doesn't get applied twice just in case
        mRequests.clear();
        mBehavior = KEEP_BEHAVIOR;
        mAppearance = 0;
        mAppearanceMask = 0;

        // After replaying, we forward everything directly to the replayed instance.
        mReplayedInsetsController = controller;
    }

    /**
     * Detaches the controller to no longer forward calls to the real instance.
     */
    @VisibleForTesting
    public void detach() {
        mReplayedInsetsController = null;
    }

    private interface PendingRequest {
        void replay(InsetsController controller);
    }

    private static class ShowRequest implements PendingRequest {

        private final @InsetsType int mTypes;

        public ShowRequest(int types) {
            mTypes = types;
        }

        @Override
        public void replay(InsetsController controller) {
            controller.show(mTypes);
        }
    }

    private static class HideRequest implements PendingRequest {

        private final @InsetsType int mTypes;

        public HideRequest(int types) {
            mTypes = types;
        }

        @Override
        public void replay(InsetsController controller) {
            controller.hide(mTypes);
        }
    }
}
