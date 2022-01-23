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

package com.android.server.app;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.server.app.GameServiceProviderInstanceImplTest.FakeGameService.GameServiceState;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.ITaskStackListener;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.service.games.CreateGameSessionRequest;
import android.service.games.CreateGameSessionResult;
import android.service.games.GameScreenshotResult;
import android.service.games.GameSessionViewHostConfiguration;
import android.service.games.GameStartedEvent;
import android.service.games.IGameService;
import android.service.games.IGameServiceController;
import android.service.games.IGameSession;
import android.service.games.IGameSessionController;
import android.service.games.IGameSessionService;
import android.view.SurfaceControlViewHost.SurfacePackage;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.FunctionalUtils.ThrowingConsumer;
import com.android.internal.util.Preconditions;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;


/**
 * Unit tests for the {@link GameServiceProviderInstanceImpl}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public final class GameServiceProviderInstanceImplTest {

    private static final GameSessionViewHostConfiguration
            DEFAULT_GAME_SESSION_VIEW_HOST_CONFIGURATION =
            new GameSessionViewHostConfiguration(1, 500, 800);
    private static final int USER_ID = 10;
    private static final String APP_A_PACKAGE = "com.package.app.a";
    private static final ComponentName APP_A_MAIN_ACTIVITY =
            new ComponentName(APP_A_PACKAGE, "com.package.app.a.MainActivity");

    private static final String GAME_A_PACKAGE = "com.package.game.a";
    private static final ComponentName GAME_A_MAIN_ACTIVITY =
            new ComponentName(GAME_A_PACKAGE, "com.package.game.a.MainActivity");

    private static final Bitmap TEST_BITMAP = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);

    private MockitoSession mMockingSession;
    private GameServiceProviderInstance mGameServiceProviderInstance;
    @Mock
    private IActivityTaskManager mMockActivityTaskManager;
    @Mock
    private WindowManagerService mMockWindowManagerService;
    @Mock
    private WindowManagerInternal mMockWindowManagerInternal;
    private FakeGameClassifier mFakeGameClassifier;
    private FakeGameService mFakeGameService;
    private FakeServiceConnector<IGameService> mFakeGameServiceConnector;
    private FakeGameSessionService mFakeGameSessionService;
    private FakeServiceConnector<IGameSessionService> mFakeGameSessionServiceConnector;
    private ArrayList<ITaskStackListener> mTaskStackListeners;
    private ArrayList<RunningTaskInfo> mRunningTaskInfos;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException, RemoteException {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();

        mFakeGameClassifier = new FakeGameClassifier();
        mFakeGameClassifier.recordGamePackage(GAME_A_PACKAGE);

        mFakeGameService = new FakeGameService();
        mFakeGameServiceConnector = new FakeServiceConnector<>(mFakeGameService);
        mFakeGameSessionService = new FakeGameSessionService();
        mFakeGameSessionServiceConnector = new FakeServiceConnector<>(mFakeGameSessionService);

        mTaskStackListeners = new ArrayList<>();
        doAnswer(invocation -> {
            mTaskStackListeners.add(invocation.getArgument(0));
            return null;
        }).when(mMockActivityTaskManager).registerTaskStackListener(any());

        mRunningTaskInfos = new ArrayList<>();
        when(mMockActivityTaskManager.getTasks(anyInt(), anyBoolean(), anyBoolean())).thenReturn(
                mRunningTaskInfos);

        doAnswer(invocation -> {
            mTaskStackListeners.remove(invocation.getArgument(0));
            return null;
        }).when(mMockActivityTaskManager).unregisterTaskStackListener(any());

        mGameServiceProviderInstance = new GameServiceProviderInstanceImpl(
                new UserHandle(USER_ID),
                ConcurrentUtils.DIRECT_EXECUTOR,
                mFakeGameClassifier,
                mMockActivityTaskManager,
                mMockWindowManagerService,
                mMockWindowManagerInternal,
                mFakeGameServiceConnector,
                mFakeGameSessionServiceConnector);
    }

    @After
    public void tearDown() {
        mMockingSession.finishMocking();
    }

    @Test
    public void start_startsGameSession() throws Exception {
        mGameServiceProviderInstance.start();

        assertThat(mFakeGameService.getState()).isEqualTo(GameServiceState.CONNECTED);
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void start_multipleTimes_startsGameSessionOnce() throws Exception {
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.start();

        assertThat(mFakeGameService.getState()).isEqualTo(GameServiceState.CONNECTED);
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void stop_neverStarted_doesNothing() throws Exception {
        mGameServiceProviderInstance.stop();


        assertThat(mFakeGameService.getState()).isEqualTo(GameServiceState.DISCONNECTED);
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(0);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void startAndStop_startsAndStopsGameSession() throws Exception {
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.stop();

        assertThat(mFakeGameService.getState()).isEqualTo(GameServiceState.DISCONNECTED);
        assertThat(mFakeGameService.getConnectedCount()).isEqualTo(1);
        assertThat(mFakeGameServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void startAndStop_multipleTimes_startsAndStopsGameSessionMultipleTimes()
            throws Exception {
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.stop();
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.stop();

        assertThat(mFakeGameService.getState()).isEqualTo(GameServiceState.DISCONNECTED);
        assertThat(mFakeGameService.getConnectedCount()).isEqualTo(2);
        assertThat(mFakeGameServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(2);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void stop_stopMultipleTimes_stopsGameSessionOnce() throws Exception {
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.stop();
        mGameServiceProviderInstance.stop();

        assertThat(mFakeGameService.getState()).isEqualTo(GameServiceState.DISCONNECTED);
        assertThat(mFakeGameService.getConnectedCount()).isEqualTo(1);
        assertThat(mFakeGameServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void gameTaskStarted_neverStarted_doesNothing() throws Exception {
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);

        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(0);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void gameTaskRemoved_neverStarted_doesNothing() throws Exception {
        dispatchTaskRemoved(10);

        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(0);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void gameTaskStarted_afterStopped_doesNotSendGameStartedEvent() throws Exception {
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.stop();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);

        assertThat(mFakeGameService.getGameStartedEvents()).isEmpty();
    }

    @Test
    public void appTaskStarted_doesNotSendGameStartedEvent() throws Exception {
        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, APP_A_MAIN_ACTIVITY);

        assertThat(mFakeGameService.getGameStartedEvents()).isEmpty();
    }

    @Test
    public void taskStarted_nullComponentName_ignoresAndDoesNotCrash() throws Exception {
        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, null);

        assertThat(mFakeGameService.getGameStartedEvents()).isEmpty();
    }

    @Test
    public void gameSessionRequested_withoutTaskDispatch_doesNotCrashAndDoesNotCreateGameSession()
            throws Exception {
        mGameServiceProviderInstance.start();

        mFakeGameService.requestCreateGameSession(10);

        assertThat(mFakeGameSessionService.getCapturedCreateInvocations()).isEmpty();
    }

    @Test
    public void gameTaskStarted_noSessionRequest_callsStartGame() throws Exception {
        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);

        GameStartedEvent expectedGameStartedEvent = new GameStartedEvent(10, GAME_A_PACKAGE);
        assertThat(mFakeGameService.getGameStartedEvents())
                .containsExactly(expectedGameStartedEvent).inOrder();
    }

    @Test
    public void gameTaskStarted_requestToCreateGameSessionIncludesTaskConfiguration()
            throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);

        mFakeGameService.requestCreateGameSession(10);

        FakeGameSessionService.CapturedCreateInvocation capturedCreateInvocation =
                getOnlyElement(mFakeGameSessionService.getCapturedCreateInvocations());
        assertThat(capturedCreateInvocation.mGameSessionViewHostConfiguration)
                .isEqualTo(DEFAULT_GAME_SESSION_VIEW_HOST_CONFIGURATION);
    }

    @Test
    public void gameTaskStarted_failsToDetermineTaskOverlayConfiguration_gameSessionNotCreated()
            throws Exception {
        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);

        mFakeGameService.requestCreateGameSession(10);

        assertThat(mFakeGameSessionService.getCapturedCreateInvocations()).isEmpty();
    }

    @Test
    public void gameTaskStartedAndSessionRequested_createsGameSession() throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        assertThat(gameSession10.mIsDestroyed).isFalse();
        assertThat(gameSession10.mIsFocused).isFalse();
    }

    @Test
    public void gameTaskStartedAndSessionRequested_secondSessionRequest_ignoredAndDoesNotCrash()
            throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);

        mFakeGameService.requestCreateGameSession(10);
        mFakeGameService.requestCreateGameSession(10);

        CreateGameSessionRequest expectedCreateGameSessionRequest = new CreateGameSessionRequest(10,
                GAME_A_PACKAGE);
        assertThat(getOnlyElement(
                mFakeGameSessionService.getCapturedCreateInvocations()).mCreateGameSessionRequest)
                .isEqualTo(expectedCreateGameSessionRequest);
    }

    @Test
    public void gameSessionSuccessfullyCreated_createsTaskOverlay() throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        verify(mMockWindowManagerInternal).addTaskOverlay(eq(10), eq(mockSurfacePackage10));
        verifyNoMoreInteractions(mMockWindowManagerInternal);
    }

    @Test
    public void gameTaskFocused_propagatedToGameSession() throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        assertThat(gameSession10.mIsFocused).isFalse();

        dispatchTaskFocused(10, /*focused=*/ true);
        assertThat(gameSession10.mIsFocused).isTrue();

        dispatchTaskFocused(10, /*focused=*/ false);
        assertThat(gameSession10.mIsFocused).isFalse();
    }

    @Test
    public void gameTaskAlreadyFocusedWhenGameSessionCreated_propagatedToGameSession()
            throws Exception {
        ActivityTaskManager.RootTaskInfo gameATaskInfo = new ActivityTaskManager.RootTaskInfo();
        gameATaskInfo.taskId = 10;
        when(mMockActivityTaskManager.getFocusedRootTaskInfo()).thenReturn(gameATaskInfo);

        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        assertThat(gameSession10.mIsFocused).isTrue();
    }

    @Test
    public void gameTaskRemoved_whileAwaitingGameSessionAttached_destroysGameSession()
            throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        dispatchTaskRemoved(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        assertThat(gameSession10.mIsDestroyed).isTrue();
    }

    @Test
    public void gameTaskRemoved_whileGameSessionAttached_destroysGameSession() throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        dispatchTaskRemoved(10);

        assertThat(gameSession10.mIsDestroyed).isTrue();
    }

    @Test
    public void gameTaskRemoved_removesTaskOverlay() throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        stopTask(10);

        verify(mMockWindowManagerInternal).addTaskOverlay(eq(10), eq(mockSurfacePackage10));
        verify(mMockWindowManagerInternal).removeTaskOverlay(eq(10), eq(mockSurfacePackage10));
        verifyNoMoreInteractions(mMockWindowManagerInternal);
    }

    @Test
    public void gameTaskStartedAndSessionRequested_multipleTimes_createsMultipleGameSessions()
            throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        startTask(11, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        assertThat(gameSession10.mIsDestroyed).isFalse();
        assertThat(gameSession11.mIsDestroyed).isFalse();
    }

    @Test
    public void gameTaskStartedTwice_sessionRequestedSecondTimeOnly_createsOneGameSession()
            throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        startTask(11, GAME_A_MAIN_ACTIVITY);

        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        assertThat(gameSession10.mIsDestroyed).isFalse();
        assertThat(mFakeGameSessionService.getCapturedCreateInvocations()).hasSize(1);
    }

    @Test
    public void gameTaskRemoved_multipleSessions_destroysOnlyThatGameSession()
            throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        startTask(11, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        dispatchTaskRemoved(10);

        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isFalse();
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isTrue();
    }

    @Test
    public void allGameTasksRemoved_destroysAllGameSessionsAndGameSessionServiceIsDisconnected() {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        startTask(11, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        dispatchTaskRemoved(10);
        dispatchTaskRemoved(11);

        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isTrue();
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isFalse();
    }

    @Test
    public void createSessionRequested_afterAllPreviousSessionsDestroyed_createsSession()
            throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        startTask(11, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        dispatchTaskRemoved(10);
        dispatchTaskRemoved(11);

        startTask(12, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(12);

        FakeGameSession gameSession12 = new FakeGameSession();
        SurfacePackage mockSurfacePackage12 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(12)
                .complete(new CreateGameSessionResult(gameSession12, mockSurfacePackage12));

        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isTrue();
        assertThat(gameSession12.mIsDestroyed).isFalse();
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isTrue();
    }

    @Test
    public void stop_severalActiveGameSessions_destroysGameSessionsAndUnbinds() throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        startTask(11, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        mGameServiceProviderInstance.stop();

        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isTrue();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isFalse();
    }

    @Test
    public void takeScreenshot_failureNoBitmapCaptured() throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        IGameSessionController gameSessionController = getOnlyElement(
                mFakeGameSessionService.getCapturedCreateInvocations()).mGameSessionController;
        AndroidFuture<GameScreenshotResult> resultFuture = new AndroidFuture<>();
        gameSessionController.takeScreenshot(10, resultFuture);

        GameScreenshotResult result = resultFuture.get();
        assertEquals(GameScreenshotResult.GAME_SCREENSHOT_ERROR_INTERNAL_ERROR,
                result.getStatus());
        verify(mMockWindowManagerService).captureTaskBitmap(10);
    }

    @Test
    public void takeScreenshot_success() throws Exception {
        when(mMockWindowManagerService.captureTaskBitmap(10)).thenReturn(TEST_BITMAP);

        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        IGameSessionController gameSessionController = getOnlyElement(
                mFakeGameSessionService.getCapturedCreateInvocations()).mGameSessionController;
        AndroidFuture<GameScreenshotResult> resultFuture = new AndroidFuture<>();
        gameSessionController.takeScreenshot(10, resultFuture);

        GameScreenshotResult result = resultFuture.get();
        assertEquals(GameScreenshotResult.GAME_SCREENSHOT_SUCCESS, result.getStatus());
        assertEquals(TEST_BITMAP, result.getBitmap());
    }

    private void startTask(int taskId, ComponentName componentName) {
        RunningTaskInfo runningTaskInfo = new RunningTaskInfo();
        runningTaskInfo.taskId = taskId;
        runningTaskInfo.displayId = 1;
        runningTaskInfo.configuration.windowConfiguration.setBounds(new Rect(0, 0, 500, 800));
        mRunningTaskInfos.add(runningTaskInfo);

        dispatchTaskCreated(taskId, componentName);
    }

    private void stopTask(int taskId) {
        mRunningTaskInfos.removeIf(runningTaskInfo -> runningTaskInfo.taskId == taskId);
        dispatchTaskRemoved(taskId);
    }


    private void dispatchTaskRemoved(int taskId) {
        dispatchTaskChangeEvent(taskStackListener -> {
            taskStackListener.onTaskRemoved(taskId);
        });
    }

    private void dispatchTaskCreated(int taskId, @Nullable ComponentName componentName) {
        dispatchTaskChangeEvent(taskStackListener -> {
            taskStackListener.onTaskCreated(taskId, componentName);
        });
    }

    private void dispatchTaskFocused(int taskId, boolean focused) {
        dispatchTaskChangeEvent(taskStackListener -> {
            taskStackListener.onTaskFocusChanged(taskId, focused);
        });
    }

    private void dispatchTaskChangeEvent(
            ThrowingConsumer<ITaskStackListener> taskStackListenerConsumer) {
        for (ITaskStackListener taskStackListener : mTaskStackListeners) {
            taskStackListenerConsumer.accept(taskStackListener);
        }
    }

    static final class FakeGameService extends IGameService.Stub {
        private IGameServiceController mGameServiceController;

        public enum GameServiceState {
            DISCONNECTED,
            CONNECTED,
        }

        private ArrayList<GameStartedEvent> mGameStartedEvents = new ArrayList<>();
        private int mConnectedCount = 0;
        private GameServiceState mGameServiceState = GameServiceState.DISCONNECTED;

        public GameServiceState getState() {
            return mGameServiceState;
        }

        public int getConnectedCount() {
            return mConnectedCount;
        }

        public ArrayList<GameStartedEvent> getGameStartedEvents() {
            return mGameStartedEvents;
        }

        @Override
        public void connected(IGameServiceController gameServiceController) {
            Preconditions.checkState(mGameServiceState == GameServiceState.DISCONNECTED);

            mGameServiceState = GameServiceState.CONNECTED;
            mConnectedCount += 1;
            mGameServiceController = gameServiceController;
        }

        @Override
        public void disconnected() {
            Preconditions.checkState(mGameServiceState == GameServiceState.CONNECTED);

            mGameServiceState = GameServiceState.DISCONNECTED;
            mGameServiceController = null;
        }

        @Override
        public void gameStarted(GameStartedEvent gameStartedEvent) {
            Preconditions.checkState(mGameServiceState == GameServiceState.CONNECTED);

            mGameStartedEvents.add(gameStartedEvent);
        }

        public void requestCreateGameSession(int task) {
            Preconditions.checkState(mGameServiceState == GameServiceState.CONNECTED);

            try {
                mGameServiceController.createGameSession(task);
            } catch (RemoteException ex) {
                throw new AssertionError(ex);
            }
        }
    }

    static final class FakeGameSessionService extends IGameSessionService.Stub {

        private final ArrayList<CapturedCreateInvocation> mCapturedCreateInvocations =
                new ArrayList<>();
        private final HashMap<Integer, AndroidFuture<CreateGameSessionResult>>
                mPendingCreateGameSessionResultFutures =
                new HashMap<>();

        public static final class CapturedCreateInvocation {
            private final IGameSessionController mGameSessionController;
            private final CreateGameSessionRequest mCreateGameSessionRequest;
            private final GameSessionViewHostConfiguration mGameSessionViewHostConfiguration;

            CapturedCreateInvocation(
                    IGameSessionController gameSessionController,
                    CreateGameSessionRequest createGameSessionRequest,
                    GameSessionViewHostConfiguration gameSessionViewHostConfiguration) {
                mGameSessionController = gameSessionController;
                mCreateGameSessionRequest = createGameSessionRequest;
                mGameSessionViewHostConfiguration = gameSessionViewHostConfiguration;
            }
        }

        public ArrayList<CapturedCreateInvocation> getCapturedCreateInvocations() {
            return mCapturedCreateInvocations;
        }

        public AndroidFuture<CreateGameSessionResult> removePendingFutureForTaskId(int taskId) {
            return mPendingCreateGameSessionResultFutures.remove(taskId);
        }

        @Override
        public void create(
                IGameSessionController gameSessionController,
                CreateGameSessionRequest createGameSessionRequest,
                GameSessionViewHostConfiguration gameSessionViewHostConfiguration,
                AndroidFuture createGameSessionResultFuture) {

            mCapturedCreateInvocations.add(
                    new CapturedCreateInvocation(
                            gameSessionController,
                            createGameSessionRequest,
                            gameSessionViewHostConfiguration));

            Preconditions.checkState(!mPendingCreateGameSessionResultFutures.containsKey(
                    createGameSessionRequest.getTaskId()));
            mPendingCreateGameSessionResultFutures.put(
                    createGameSessionRequest.getTaskId(),
                    createGameSessionResultFuture);
        }
    }

    private static class FakeGameSession extends IGameSession.Stub {
        boolean mIsDestroyed = false;
        boolean mIsFocused = false;

        @Override
        public void onDestroyed() {
            mIsDestroyed = true;
        }

        @Override
        public void onTaskFocusChanged(boolean focused) {
            mIsFocused = focused;
        }
    }
}