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

package android.service.games;

import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Slog;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.function.pooled.PooledLambda;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * An active game session, providing a facility for the implementation to interact with the game.
 *
 * A Game Service provider should extend the {@link GameSession} to provide their own implementation
 * which is then returned when a game session is created via
 * {@link GameSessionService#onNewSession(CreateGameSessionRequest)}.
 *
 * This class exposes various lifecycle methods which are guaranteed to be called in the following
 * fashion:
 *
 * {@link #onCreate()}: Will always be the first lifecycle method to be called, once the game
 * session is created.
 *
 * {@link #onGameTaskFocusChanged(boolean)}: Will be called after {@link #onCreate()} with
 * focused=true when the game task first comes into focus (if it does). If the game task is focused
 * when the game session is created, this method will be called immediately after
 * {@link #onCreate()} with focused=true. After this method is called with focused=true, it will be
 * called again with focused=false when the task goes out of focus. If this method is ever called
 * with focused=true, it is guaranteed to be called again with focused=false before
 * {@link #onDestroy()} is called. If the game task never comes into focus during the session
 * lifetime, this method will never be called.
 *
 * {@link #onDestroy()}: Will always be called after {@link #onCreate()}. If the game task ever
 * comes into focus before the game session is destroyed, then this method will be called after one
 * or more pairs of calls to {@link #onGameTaskFocusChanged(boolean)}.
 *
 * @hide
 */
@SystemApi
public abstract class GameSession {
    private static final String TAG = "GameSession";
    private static final boolean DEBUG = false;

    final IGameSession mInterface = new IGameSession.Stub() {
        @Override
        public void onDestroyed() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    GameSession::doDestroy, GameSession.this));
        }

        @Override
        public void onTaskFocusChanged(boolean focused) {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    GameSession::moveToState, GameSession.this,
                    focused ? LifecycleState.TASK_FOCUSED : LifecycleState.TASK_UNFOCUSED));
        }
    };

    /**
     * @hide
     */
    @VisibleForTesting
    public enum LifecycleState {
        // Initial state; may transition to CREATED.
        INITIALIZED,
        // May transition to TASK_FOCUSED or DESTROYED.
        CREATED,
        // May transition to TASK_UNFOCUSED.
        TASK_FOCUSED,
        // May transition to TASK_FOCUSED or DESTROYED.
        TASK_UNFOCUSED,
        // May not transition once reached.
        DESTROYED
    }

    private LifecycleState mLifecycleState = LifecycleState.INITIALIZED;
    private IGameSessionController mGameSessionController;
    private int mTaskId;
    private GameSessionRootView mGameSessionRootView;
    private SurfaceControlViewHost mSurfaceControlViewHost;

    /**
     * @hide
     */
    @VisibleForTesting
    public void attach(
            IGameSessionController gameSessionController,
            int taskId,
            @NonNull Context context,
            @NonNull SurfaceControlViewHost surfaceControlViewHost,
            int widthPx,
            int heightPx) {
        mGameSessionController = gameSessionController;
        mTaskId = taskId;
        mSurfaceControlViewHost = surfaceControlViewHost;
        mGameSessionRootView = new GameSessionRootView(context, mSurfaceControlViewHost);
        surfaceControlViewHost.setView(mGameSessionRootView, widthPx, heightPx);
    }

    @Hide
    void doCreate() {
        moveToState(LifecycleState.CREATED);
    }

    @Hide
    void doDestroy() {
        mSurfaceControlViewHost.release();
        moveToState(LifecycleState.DESTROYED);
    }

    /**
     * @hide
     */
    @VisibleForTesting
    @MainThread
    public void moveToState(LifecycleState newLifecycleState) {
        if (DEBUG) {
            Slog.d(TAG, "moveToState: " + mLifecycleState + " -> " + newLifecycleState);
        }

        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new RuntimeException("moveToState should be used only from the main thread");
        }

        if (mLifecycleState == newLifecycleState) {
            // Nothing to do.
            return;
        }

        switch (mLifecycleState) {
            case INITIALIZED:
                if (newLifecycleState == LifecycleState.CREATED) {
                    onCreate();
                } else if (newLifecycleState == LifecycleState.DESTROYED) {
                    onCreate();
                    onDestroy();
                } else {
                    if (DEBUG) {
                        Slog.d(TAG, "Ignoring moveToState: INITIALIZED -> " + newLifecycleState);
                    }
                    return;
                }
                break;
            case CREATED:
                if (newLifecycleState == LifecycleState.TASK_FOCUSED) {
                    onGameTaskFocusChanged(/*focused=*/ true);
                } else if (newLifecycleState == LifecycleState.DESTROYED) {
                    onDestroy();
                } else {
                    if (DEBUG) {
                        Slog.d(TAG, "Ignoring moveToState: CREATED -> " + newLifecycleState);
                    }
                    return;
                }
                break;
            case TASK_FOCUSED:
                if (newLifecycleState == LifecycleState.TASK_UNFOCUSED) {
                    onGameTaskFocusChanged(/*focused=*/ false);
                } else if (newLifecycleState == LifecycleState.DESTROYED) {
                    onGameTaskFocusChanged(/*focused=*/ false);
                    onDestroy();
                } else {
                    if (DEBUG) {
                        Slog.d(TAG, "Ignoring moveToState: TASK_FOCUSED -> " + newLifecycleState);
                    }
                    return;
                }
                break;
            case TASK_UNFOCUSED:
                if (newLifecycleState == LifecycleState.TASK_FOCUSED) {
                    onGameTaskFocusChanged(/*focused=*/ true);
                } else if (newLifecycleState == LifecycleState.DESTROYED) {
                    onDestroy();
                } else {
                    if (DEBUG) {
                        Slog.d(TAG, "Ignoring moveToState: TASK_UNFOCUSED -> " + newLifecycleState);
                    }
                    return;
                }
                break;
            case DESTROYED:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring moveToState: DESTROYED -> " + newLifecycleState);
                }
                return;
        }

        mLifecycleState = newLifecycleState;
    }

    /**
     * Initializer called when the game session is starting.
     *
     * This should be used perform any setup required now that the game session is created.
     */
    public void onCreate() {
    }

    /**
     * Finalizer called when the game session is ending. This method will always be called after a
     * call to {@link #onCreate()}. If the game task is ever in focus, this method will be called
     * after one or more pairs of calls to {@link #onGameTaskFocusChanged(boolean)}.
     *
     * This should be used to perform any cleanup before the game session is destroyed.
     */
    public void onDestroy() {
    }

    /**
     * Called when the game task for this session is or unfocused. The initial call to this method
     * will always come after a call to {@link #onCreate()} with focused=true (when the game task
     * first comes into focus after the session is created, or immediately after the session is
     * created if the game task is already focused).
     *
     * This should be used to perform any setup required when the game task comes into focus or any
     * cleanup that is required when the game task goes out of focus.
     *
     * @param focused True if the game task is focused, false if the game task is unfocused.
     */
    public void onGameTaskFocusChanged(boolean focused) {}

    /**
     * Sets the task overlay content to an explicit view. This view is placed directly into the game
     * session's task overlay view hierarchy. It can itself be a complex view hierarchy. The size
     * the task overlay view will always match the dimensions of the associated task's window. The
     * {@code View} may not be cleared once set, but may be replaced by invoking
     * {@link #setTaskOverlayView(View, ViewGroup.LayoutParams)} again.
     *
     * @param view         The desired content to display.
     * @param layoutParams Layout parameters for the view.
     */
    public void setTaskOverlayView(
            @NonNull View view,
            @NonNull ViewGroup.LayoutParams layoutParams) {
        mGameSessionRootView.removeAllViews();
        mGameSessionRootView.addView(view, layoutParams);
    }

    /**
     * Root view of the {@link SurfaceControlViewHost} associated with the {@link GameSession}
     * instance. It is responsible for observing changes in the size of the window and resizing
     * itself to match.
     */
    private static final class GameSessionRootView extends FrameLayout {
        private final SurfaceControlViewHost mSurfaceControlViewHost;

        GameSessionRootView(@NonNull Context context,
                SurfaceControlViewHost surfaceControlViewHost) {
            super(context);
            mSurfaceControlViewHost = surfaceControlViewHost;
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);

            // TODO(b/204504596): Investigate skipping the relayout in cases where the size has
            // not changed.
            Rect bounds = newConfig.windowConfiguration.getBounds();
            mSurfaceControlViewHost.relayout(bounds.width(), bounds.height());
        }
    }

    /**
     * Interface for returning screenshot outcome from calls to {@link #takeScreenshot}.
     */
    public interface ScreenshotCallback {

        /**
         * The status of a failed screenshot attempt provided by {@link #onFailure}.
         *
         * @hide
         */
        @IntDef(flag = false, prefix = {"ERROR_TAKE_SCREENSHOT_"}, value = {
                ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR, // 0
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface ScreenshotFailureStatus {
        }

        /**
         * An error code indicating that an internal error occurred when attempting to take a
         * screenshot of the game task. If this code is returned, the caller should verify that the
         * conditions for taking a screenshot are met (device screen is on and the game task is
         * visible). To do so, the caller can monitor the lifecycle methods for this session to
         * make sure that the game task is focused. If the conditions are met, then the caller may
         * try again immediately.
         */
        int ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR = 0;

        /**
         * Called when taking the screenshot failed.
         * @param statusCode Indicates the reason for failure.
         */
        void onFailure(@ScreenshotFailureStatus int statusCode);

        /**
         * Called when taking the screenshot succeeded.
         * @param bitmap The screenshot.
         */
        void onSuccess(@NonNull Bitmap bitmap);
    }

    /**
     * Takes a screenshot of the associated game. For this call to succeed, the device screen
     * must be turned on and the game task must be visible.
     *
     * If the callback is called with {@link ScreenshotCallback#onSuccess}, the provided {@link
     * Bitmap} may be used.
     *
     * If the callback is called with {@link ScreenshotCallback#onFailure}, the provided status
     * code should be checked.
     *
     * If the status code is {@link ScreenshotCallback#ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR},
     * then the caller should verify that the conditions for calling this method are met (device
     * screen is on and the game task is visible). To do so, the caller can monitor the lifecycle
     * methods for this session to make sure that the game task is focused. If the conditions are
     * met, then the caller may try again immediately.
     *
     * @param executor Executor on which to run the callback.
     * @param callback The callback invoked when taking screenshot has succeeded
     *                 or failed.
     * @throws IllegalStateException if this method is called prior to {@link #onCreate}.
     */
    public void takeScreenshot(@NonNull Executor executor, @NonNull ScreenshotCallback callback) {
        if (mGameSessionController == null) {
            throw new IllegalStateException("Can not call before onCreate()");
        }

        AndroidFuture<GameScreenshotResult> takeScreenshotResult =
                new AndroidFuture<GameScreenshotResult>().whenCompleteAsync((result, error) -> {
                    handleScreenshotResult(callback, result, error);
                }, executor);

        try {
            mGameSessionController.takeScreenshot(mTaskId, takeScreenshotResult);
        } catch (RemoteException ex) {
            takeScreenshotResult.completeExceptionally(ex);
        }
    }

    private void handleScreenshotResult(
            @NonNull ScreenshotCallback callback,
            @NonNull GameScreenshotResult result,
            @NonNull Throwable error) {
        if (error != null) {
            Slog.w(TAG, error.getMessage(), error.getCause());
            callback.onFailure(
                    ScreenshotCallback.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR);
            return;
        }

        @GameScreenshotResult.GameScreenshotStatus int status = result.getStatus();
        switch (status) {
            case GameScreenshotResult.GAME_SCREENSHOT_SUCCESS:
                callback.onSuccess(result.getBitmap());
                break;
            case GameScreenshotResult.GAME_SCREENSHOT_ERROR_INTERNAL_ERROR:
                Slog.w(TAG, "Error taking screenshot");
                callback.onFailure(
                        ScreenshotCallback.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR);
                break;
        }
    }
}
