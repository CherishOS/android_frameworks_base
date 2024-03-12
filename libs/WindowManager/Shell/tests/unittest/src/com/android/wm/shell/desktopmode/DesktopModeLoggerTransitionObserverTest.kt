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
package com.android.wm.shell.desktopmode

import android.app.ActivityManager
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.os.IBinder
import android.testing.AndroidTestingRunner
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_FLAG_IS_RECENTS
import android.view.WindowManager.TRANSIT_NONE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_SLEEP
import android.view.WindowManager.TRANSIT_TO_BACK
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.view.WindowManager.TRANSIT_WAKE
import android.window.IWindowContainerToken
import android.window.TransitionInfo
import android.window.TransitionInfo.Change
import android.window.WindowContainerToken
import androidx.test.filters.SmallTest
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.EnterReason
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.ExitReason
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.TransitionInfoBuilder
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.times

@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopModeLoggerTransitionObserverTest {

    @JvmField
    @Rule
    val extendedMockitoRule = ExtendedMockitoRule.Builder(this)
            .mockStatic(DesktopModeEventLogger::class.java)
            .mockStatic(DesktopModeStatus::class.java).build()!!

    @Mock
    lateinit var testExecutor: ShellExecutor
    @Mock
    private lateinit var mockShellInit: ShellInit
    @Mock
    private lateinit var transitions: Transitions

    private lateinit var transitionObserver: DesktopModeLoggerTransitionObserver
    private lateinit var shellInit: ShellInit
    private lateinit var desktopModeEventLogger: DesktopModeEventLogger

    @Before
    fun setup() {
        Mockito.`when`(DesktopModeStatus.isEnabled()).thenReturn(true)
        shellInit = Mockito.spy(ShellInit(testExecutor))
        desktopModeEventLogger = mock(DesktopModeEventLogger::class.java)

        transitionObserver = DesktopModeLoggerTransitionObserver(
            mockShellInit, transitions, desktopModeEventLogger)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            val initRunnableCaptor = ArgumentCaptor.forClass(
                Runnable::class.java)
            verify(mockShellInit).addInitCallback(initRunnableCaptor.capture(),
                same(transitionObserver))
            initRunnableCaptor.value.run()
        } else {
            transitionObserver.onInit()
        }
    }

    @Test
    fun testRegistersObserverAtInit() {
        verify(transitions)
                .registerObserver(same(
                    transitionObserver))
    }

    @Test
    fun taskCreated_notFreeformWindow_doesNotLogSessionEnterOrTaskAdded() {
        val change = createChange(TRANSIT_OPEN, createTaskInfo(1, WINDOWING_MODE_FULLSCREEN))
        val transitionInfo = TransitionInfoBuilder(TRANSIT_OPEN, 0).addChange(change).build()

        callOnTransitionReady(transitionInfo)

        verify(desktopModeEventLogger, never()).logSessionEnter(any(), any())
        verify(desktopModeEventLogger, never()).logTaskAdded(any(), any())
    }

    @Test
    fun taskCreated_FreeformWindowOpen_logSessionEnterAndTaskAdded() {
        val change = createChange(TRANSIT_OPEN, createTaskInfo(1, WINDOWING_MODE_FREEFORM))
        val transitionInfo = TransitionInfoBuilder(TRANSIT_OPEN, 0).addChange(change).build()

        callOnTransitionReady(transitionInfo)
        val sessionId = transitionObserver.getLoggerSessionId()

        assertThat(sessionId).isNotNull()
        verify(desktopModeEventLogger, times(1)).logSessionEnter(eq(sessionId!!),
            eq(EnterReason.APP_FREEFORM_INTENT))
        verify(desktopModeEventLogger, times(1)).logTaskAdded(eq(sessionId), any())
    }

    @Test
    fun taskChanged_taskMovedToDesktopByDrag_logSessionEnterAndTaskAdded() {
        val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(1, WINDOWING_MODE_FREEFORM))
        // task change is finalised when drag ends
        val transitionInfo = TransitionInfoBuilder(
            Transitions.TRANSIT_DESKTOP_MODE_END_DRAG_TO_DESKTOP, 0).addChange(change).build()

        callOnTransitionReady(transitionInfo)
        val sessionId = transitionObserver.getLoggerSessionId()

        assertThat(sessionId).isNotNull()
        verify(desktopModeEventLogger, times(1)).logSessionEnter(eq(sessionId!!),
            eq(EnterReason.APP_HANDLE_DRAG))
        verify(desktopModeEventLogger, times(1)).logTaskAdded(eq(sessionId), any())
    }

    @Test
    fun taskChanged_taskMovedToDesktopByButtonTap_logSessionEnterAndTaskAdded() {
        val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(1, WINDOWING_MODE_FREEFORM))
        val transitionInfo = TransitionInfoBuilder(Transitions.TRANSIT_MOVE_TO_DESKTOP, 0)
                .addChange(change).build()

        callOnTransitionReady(transitionInfo)
        val sessionId = transitionObserver.getLoggerSessionId()

        assertThat(sessionId).isNotNull()
        verify(desktopModeEventLogger, times(1)).logSessionEnter(eq(sessionId!!),
            eq(EnterReason.APP_HANDLE_MENU_BUTTON))
        verify(desktopModeEventLogger, times(1)).logTaskAdded(eq(sessionId), any())
    }

    @Test
    fun taskChanged_existingFreeformTaskMadeVisible_logSessionEnterAndTaskAdded() {
        val taskInfo = createTaskInfo(1, WINDOWING_MODE_FREEFORM)
        taskInfo.isVisibleRequested = true
        val change = createChange(TRANSIT_CHANGE, taskInfo)
        val transitionInfo = TransitionInfoBuilder(Transitions.TRANSIT_MOVE_TO_DESKTOP, 0)
                .addChange(change).build()

        callOnTransitionReady(transitionInfo)
        val sessionId = transitionObserver.getLoggerSessionId()

        assertThat(sessionId).isNotNull()
        verify(desktopModeEventLogger, times(1)).logSessionEnter(eq(sessionId!!),
            eq(EnterReason.APP_HANDLE_MENU_BUTTON))
        verify(desktopModeEventLogger, times(1)).logTaskAdded(eq(sessionId), any())
    }

    @Test
    fun taskToFront_screenWake_logSessionStartedAndTaskAdded() {
        val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(1, WINDOWING_MODE_FREEFORM))
        val transitionInfo = TransitionInfoBuilder(TRANSIT_WAKE, 0)
                .addChange(change).build()

        callOnTransitionReady(transitionInfo)
        val sessionId = transitionObserver.getLoggerSessionId()

        assertThat(sessionId).isNotNull()
        verify(desktopModeEventLogger, times(1)).logSessionEnter(eq(sessionId!!),
            eq(EnterReason.SCREEN_ON))
        verify(desktopModeEventLogger, times(1)).logTaskAdded(eq(sessionId), any())
    }

    @Test
    fun freeformTaskVisible_screenTurnOff_logSessionExitAndTaskRemoved_sessionIdNull() {
        val sessionId = 1
        // add a freeform task
        transitionObserver.addTaskInfosToCachedMap(createTaskInfo(1, WINDOWING_MODE_FREEFORM))
        transitionObserver.setLoggerSessionId(sessionId)

        val transitionInfo = TransitionInfoBuilder(TRANSIT_SLEEP).build()
        callOnTransitionReady(transitionInfo)

        verify(desktopModeEventLogger, times(1)).logTaskRemoved(eq(sessionId), any())
        verify(desktopModeEventLogger, times(1)).logSessionExit(eq(sessionId),
            eq(ExitReason.SCREEN_OFF))
        assertThat(transitionObserver.getLoggerSessionId()).isNull()
    }

    @Test
    fun freeformTaskVisible_exitDesktopUsingDrag_logSessionExitAndTaskRemoved_sessionIdNull() {
        val sessionId = 1
        // add a freeform task
        transitionObserver.addTaskInfosToCachedMap(createTaskInfo(1, WINDOWING_MODE_FREEFORM))
        transitionObserver.setLoggerSessionId(sessionId)

        // window mode changing from FREEFORM to FULLSCREEN
        val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(1, WINDOWING_MODE_FULLSCREEN))
        val transitionInfo = TransitionInfoBuilder(Transitions.TRANSIT_EXIT_DESKTOP_MODE)
                .addChange(change).build()
        callOnTransitionReady(transitionInfo)

        verify(desktopModeEventLogger, times(1)).logTaskRemoved(eq(sessionId), any())
        verify(desktopModeEventLogger, times(1)).logSessionExit(eq(sessionId),
            eq(ExitReason.DRAG_TO_EXIT))
        assertThat(transitionObserver.getLoggerSessionId()).isNull()
    }

    @Test
    fun freeformTaskVisible_exitDesktopBySwipeUp_logSessionExitAndTaskRemoved_sessionIdNull() {
        val sessionId = 1
        // add a freeform task
        transitionObserver.addTaskInfosToCachedMap(createTaskInfo(1, WINDOWING_MODE_FREEFORM))
        transitionObserver.setLoggerSessionId(sessionId)

        // recents transition
        val change = createChange(TRANSIT_TO_BACK, createTaskInfo(1, WINDOWING_MODE_FREEFORM))
        val transitionInfo = TransitionInfoBuilder(TRANSIT_TO_FRONT, TRANSIT_FLAG_IS_RECENTS)
                .addChange(change).build()
        callOnTransitionReady(transitionInfo)

        verify(desktopModeEventLogger, times(1)).logTaskRemoved(eq(sessionId), any())
        verify(desktopModeEventLogger, times(1)).logSessionExit(eq(sessionId),
            eq(ExitReason.RETURN_HOME_OR_OVERVIEW))
        assertThat(transitionObserver.getLoggerSessionId()).isNull()
    }

    @Test
    fun freeformTaskVisible_taskFinished_logSessionExitAndTaskRemoved_sessionIdNull() {
        val sessionId = 1
        // add a freeform task
        transitionObserver.addTaskInfosToCachedMap(createTaskInfo(1, WINDOWING_MODE_FREEFORM))
        transitionObserver.setLoggerSessionId(sessionId)

        // task closing
        val change = createChange(TRANSIT_CLOSE, createTaskInfo(1, WINDOWING_MODE_FULLSCREEN))
        val transitionInfo = TransitionInfoBuilder(TRANSIT_CLOSE).addChange(change).build()
        callOnTransitionReady(transitionInfo)

        verify(desktopModeEventLogger, times(1)).logTaskRemoved(eq(sessionId), any())
        verify(desktopModeEventLogger, times(1)).logSessionExit(eq(sessionId),
            eq(ExitReason.TASK_FINISHED))
        assertThat(transitionObserver.getLoggerSessionId()).isNull()
    }

    @Test
    fun sessionExitByRecents_cancelledAnimation_sessionRestored() {
        val sessionId = 1
        // add a freeform task to an existing session
        transitionObserver.addTaskInfosToCachedMap(createTaskInfo(1, WINDOWING_MODE_FREEFORM))
        transitionObserver.setLoggerSessionId(sessionId)

        // recents transition sent freeform window to back
        val change = createChange(TRANSIT_TO_BACK, createTaskInfo(1, WINDOWING_MODE_FREEFORM))
        val transitionInfo1 =
            TransitionInfoBuilder(TRANSIT_TO_FRONT, TRANSIT_FLAG_IS_RECENTS).addChange(change)
                    .build()
        callOnTransitionReady(transitionInfo1)
        verify(desktopModeEventLogger, times(1)).logTaskRemoved(eq(sessionId), any())
        verify(desktopModeEventLogger, times(1)).logSessionExit(eq(sessionId),
            eq(ExitReason.RETURN_HOME_OR_OVERVIEW))
        assertThat(transitionObserver.getLoggerSessionId()).isNull()

        val transitionInfo2 = TransitionInfoBuilder(TRANSIT_NONE).build()
        callOnTransitionReady(transitionInfo2)

        verify(desktopModeEventLogger, times(1)).logSessionEnter(any(), any())
        verify(desktopModeEventLogger, times(1)).logTaskAdded(any(), any())
    }

    @Test
    fun sessionAlreadyStarted_newFreeformTaskAdded_logsTaskAdded() {
        val sessionId = 1
        // add an existing freeform task
        transitionObserver.addTaskInfosToCachedMap(createTaskInfo(1, WINDOWING_MODE_FREEFORM))
        transitionObserver.setLoggerSessionId(sessionId)

        // new freeform task added
        val change = createChange(TRANSIT_OPEN, createTaskInfo(2, WINDOWING_MODE_FREEFORM))
        val transitionInfo = TransitionInfoBuilder(TRANSIT_OPEN, 0).addChange(change).build()
        callOnTransitionReady(transitionInfo)

        verify(desktopModeEventLogger, times(1)).logTaskAdded(eq(sessionId), any())
        verify(desktopModeEventLogger, never()).logSessionEnter(any(), any())
    }

    @Test
    fun sessionAlreadyStarted_freeformTaskRemoved_logsTaskRemoved() {
        val sessionId = 1
        // add two existing freeform tasks
        transitionObserver.addTaskInfosToCachedMap(createTaskInfo(1, WINDOWING_MODE_FREEFORM))
        transitionObserver.addTaskInfosToCachedMap(createTaskInfo(2, WINDOWING_MODE_FREEFORM))
        transitionObserver.setLoggerSessionId(sessionId)

        // new freeform task added
        val change = createChange(TRANSIT_CLOSE, createTaskInfo(2, WINDOWING_MODE_FREEFORM))
        val transitionInfo = TransitionInfoBuilder(TRANSIT_CLOSE, 0).addChange(change).build()
        callOnTransitionReady(transitionInfo)

        verify(desktopModeEventLogger, times(1)).logTaskRemoved(eq(sessionId), any())
        verify(desktopModeEventLogger, never()).logSessionExit(any(), any())
    }

    /**
     * Simulate calling the onTransitionReady() method
     */
    private fun callOnTransitionReady(transitionInfo: TransitionInfo) {
        val transition = mock(IBinder::class.java)
        val startT = mock(
            SurfaceControl.Transaction::class.java)
        val finishT = mock(
            SurfaceControl.Transaction::class.java)

        transitionObserver.onTransitionReady(transition, transitionInfo, startT, finishT)
    }

    companion object {
        fun createTaskInfo(taskId: Int, windowMode: Int): ActivityManager.RunningTaskInfo {
            val taskInfo = ActivityManager.RunningTaskInfo()
            taskInfo.taskId = taskId
            taskInfo.configuration.windowConfiguration.windowingMode = windowMode

            return taskInfo
        }

        fun createChange(mode: Int, taskInfo: ActivityManager.RunningTaskInfo): Change {
            val change = Change(
                WindowContainerToken(mock(
                    IWindowContainerToken::class.java)),
                mock(SurfaceControl::class.java))
            change.mode = mode
            change.taskInfo = taskInfo
            return change
        }
    }
}