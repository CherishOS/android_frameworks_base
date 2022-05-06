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

package com.android.server.input

import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayViewport
import android.hardware.input.InputManagerInternal
import android.os.IInputConstants
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
import android.view.Display
import android.view.PointerIcon
import androidx.test.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.junit.MockitoJUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for {@link InputManagerService}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:InputManagerServiceTests
 */
@Presubmit
class InputManagerServiceTests {

    @get:Rule
    val rule = MockitoJUnit.rule()!!

    @Mock
    private lateinit var native: NativeInputManagerService

    @Mock
    private lateinit var wmCallbacks: InputManagerService.WindowManagerCallbacks

    private lateinit var service: InputManagerService
    private lateinit var localService: InputManagerInternal
    private lateinit var context: Context
    private lateinit var testLooper: TestLooper

    @Before
    fun setup() {
        context = spy(ContextWrapper(InstrumentationRegistry.getContext()))
        testLooper = TestLooper()
        service =
            InputManagerService(object : InputManagerService.Injector(context, testLooper.looper) {
                override fun getNativeService(
                    service: InputManagerService?
                ): NativeInputManagerService {
                    return native
                }

                override fun registerLocalService(service: InputManagerInternal?) {
                    localService = service!!
                }
            })
        assertTrue("Local service must be registered", this::localService.isInitialized)
        service.setWindowManagerCallbacks(wmCallbacks)
    }

    @Test
    fun testPointerDisplayUpdatesWhenDisplayViewportsChanged() {
        val displayId = 123
        `when`(wmCallbacks.pointerDisplayId).thenReturn(displayId)
        val viewports = listOf<DisplayViewport>()
        localService.setDisplayViewports(viewports)
        verify(native).setDisplayViewports(any(Array<DisplayViewport>::class.java))
        verify(native).setPointerDisplayId(displayId)

        val x = 42f
        val y = 314f
        service.onPointerDisplayIdChanged(displayId, x, y)
        testLooper.dispatchNext()
        verify(wmCallbacks).notifyPointerDisplayIdChanged(displayId, x, y)
    }

    @Test
    fun testSetVirtualMousePointerDisplayId() {
        // Set the virtual mouse pointer displayId, and ensure that the calling thread is blocked
        // until the native callback happens.
        var countDownLatch = CountDownLatch(1)
        val overrideDisplayId = 123
        Thread {
            assertTrue("Setting virtual pointer display should succeed",
                localService.setVirtualMousePointerDisplayId(overrideDisplayId))
            countDownLatch.countDown()
        }.start()
        assertFalse("Setting virtual pointer display should block",
            countDownLatch.await(100, TimeUnit.MILLISECONDS))

        val x = 42f
        val y = 314f
        service.onPointerDisplayIdChanged(overrideDisplayId, x, y)
        testLooper.dispatchNext()
        verify(wmCallbacks).notifyPointerDisplayIdChanged(overrideDisplayId, x, y)
        assertTrue("Native callback unblocks calling thread",
            countDownLatch.await(100, TimeUnit.MILLISECONDS))
        verify(native).setPointerDisplayId(overrideDisplayId)

        // Ensure that setting the same override again succeeds immediately.
        assertTrue("Setting the same virtual mouse pointer displayId again should succeed",
            localService.setVirtualMousePointerDisplayId(overrideDisplayId))

        // Ensure that we did not query WM for the pointerDisplayId when setting the override
        verify(wmCallbacks, never()).pointerDisplayId

        // Unset the virtual mouse pointer displayId, and ensure that we query WM for the new
        // pointer displayId and the calling thread is blocked until the native callback happens.
        countDownLatch = CountDownLatch(1)
        val pointerDisplayId = 42
        `when`(wmCallbacks.pointerDisplayId).thenReturn(pointerDisplayId)
        Thread {
            assertTrue("Unsetting virtual mouse pointer displayId should succeed",
                localService.setVirtualMousePointerDisplayId(Display.INVALID_DISPLAY))
            countDownLatch.countDown()
        }.start()
        assertFalse("Unsetting virtual mouse pointer displayId should block",
            countDownLatch.await(100, TimeUnit.MILLISECONDS))

        service.onPointerDisplayIdChanged(pointerDisplayId, x, y)
        testLooper.dispatchNext()
        verify(wmCallbacks).notifyPointerDisplayIdChanged(pointerDisplayId, x, y)
        assertTrue("Native callback unblocks calling thread",
            countDownLatch.await(100, TimeUnit.MILLISECONDS))
        verify(native).setPointerDisplayId(pointerDisplayId)
    }

    @Test
    fun testSetVirtualMousePointerDisplayId_unsuccessfulUpdate() {
        // Set the virtual mouse pointer displayId, and ensure that the calling thread is blocked
        // until the native callback happens.
        val countDownLatch = CountDownLatch(1)
        val overrideDisplayId = 123
        Thread {
            assertFalse("Setting virtual pointer display should be unsuccessful",
                localService.setVirtualMousePointerDisplayId(overrideDisplayId))
            countDownLatch.countDown()
        }.start()
        assertFalse("Setting virtual pointer display should block",
            countDownLatch.await(100, TimeUnit.MILLISECONDS))

        val x = 42f
        val y = 314f
        // Assume the native callback updates the pointerDisplayId to the incorrect value.
        service.onPointerDisplayIdChanged(Display.INVALID_DISPLAY, x, y)
        testLooper.dispatchNext()
        verify(wmCallbacks).notifyPointerDisplayIdChanged(Display.INVALID_DISPLAY, x, y)
        assertTrue("Native callback unblocks calling thread",
            countDownLatch.await(100, TimeUnit.MILLISECONDS))
        verify(native).setPointerDisplayId(overrideDisplayId)
    }

    @Test
    fun testSetVirtualMousePointerDisplayId_competingRequests() {
        val firstRequestSyncLatch = CountDownLatch(1)
        doAnswer {
            firstRequestSyncLatch.countDown()
        }.`when`(native).setPointerDisplayId(anyInt())

        val firstRequestLatch = CountDownLatch(1)
        val firstOverride = 123
        Thread {
            assertFalse("Setting virtual pointer display from thread 1 should be unsuccessful",
                localService.setVirtualMousePointerDisplayId(firstOverride))
            firstRequestLatch.countDown()
        }.start()
        assertFalse("Setting virtual pointer display should block",
            firstRequestLatch.await(100, TimeUnit.MILLISECONDS))

        assertTrue("Wait for first thread's request should succeed",
            firstRequestSyncLatch.await(100, TimeUnit.MILLISECONDS))

        val secondRequestLatch = CountDownLatch(1)
        val secondOverride = 42
        Thread {
            assertTrue("Setting virtual mouse pointer from thread 2 should be successful",
                localService.setVirtualMousePointerDisplayId(secondOverride))
            secondRequestLatch.countDown()
        }.start()
        assertFalse("Setting virtual mouse pointer should block",
            secondRequestLatch.await(100, TimeUnit.MILLISECONDS))

        val x = 42f
        val y = 314f
        // Assume the native callback updates directly to the second request.
        service.onPointerDisplayIdChanged(secondOverride, x, y)
        testLooper.dispatchNext()
        verify(wmCallbacks).notifyPointerDisplayIdChanged(secondOverride, x, y)
        assertTrue("Native callback unblocks first thread",
            firstRequestLatch.await(100, TimeUnit.MILLISECONDS))
        assertTrue("Native callback unblocks second thread",
            secondRequestLatch.await(100, TimeUnit.MILLISECONDS))
        verify(native, times(2)).setPointerDisplayId(anyInt())
    }

    @Test
    fun onDisplayRemoved_resetAllAdditionalInputProperties() {
        localService.setVirtualMousePointerDisplayId(10)
        localService.setPointerIconVisible(false, 10)
        verify(native).setPointerIconType(eq(PointerIcon.TYPE_NULL))
        localService.setPointerAcceleration(5f, 10)
        verify(native).setPointerAcceleration(eq(5f))

        service.onDisplayRemoved(10)
        verify(native).displayRemoved(eq(10))
        verify(native).setPointerIconType(eq(PointerIcon.TYPE_NOT_SPECIFIED))
        verify(native).setPointerAcceleration(
            eq(IInputConstants.DEFAULT_POINTER_ACCELERATION.toFloat()))

        localService.setVirtualMousePointerDisplayId(10)
        verify(native).setPointerDisplayId(eq(10))
        verifyNoMoreInteractions(native)
    }
}
