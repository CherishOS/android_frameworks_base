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
import android.hardware.BatteryState.STATUS_CHARGING
import android.hardware.BatteryState.STATUS_FULL
import android.hardware.BatteryState.STATUS_UNKNOWN
import android.hardware.input.IInputDeviceBatteryListener
import android.hardware.input.IInputDeviceBatteryState
import android.hardware.input.IInputDevicesChangedListener
import android.hardware.input.IInputManager
import android.hardware.input.InputManager
import android.os.Binder
import android.os.IBinder
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
import android.view.InputDevice
import androidx.test.InstrumentationRegistry
import com.android.server.input.BatteryController.POLLING_PERIOD_MILLIS
import com.android.server.input.BatteryController.UEventManager
import com.android.server.input.BatteryController.UEventManager.UEventBatteryListener
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers
import org.hamcrest.TypeSafeMatcher
import org.hamcrest.core.IsEqual.equalTo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.notNull
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.mockito.hamcrest.MockitoHamcrest
import org.mockito.junit.MockitoJUnit
import org.mockito.verification.VerificationMode

private fun createInputDevice(
    deviceId: Int,
    hasBattery: Boolean = true,
    supportsUsi: Boolean = false,
    generation: Int = -1,
): InputDevice =
    InputDevice.Builder()
        .setId(deviceId)
        .setName("Device $deviceId")
        .setDescriptor("descriptor $deviceId")
        .setExternal(true)
        .setHasBattery(hasBattery)
        .setSupportsUsi(supportsUsi)
        .setGeneration(generation)
        .build()

// Returns a matcher that helps match member variables of a class.
private fun <T, U> memberMatcher(
    member: String,
    memberProvider: (T) -> U,
    match: Matcher<U>
): TypeSafeMatcher<T> =
    object : TypeSafeMatcher<T>() {

        override fun matchesSafely(item: T?): Boolean {
            return match.matches(memberProvider(item!!))
        }

        override fun describeMismatchSafely(item: T?, mismatchDescription: Description?) {
            match.describeMismatch(item, mismatchDescription)
        }

        override fun describeTo(description: Description?) {
            match.describeTo(description?.appendText("matches member $member"))
        }
    }

// Returns a matcher for IInputDeviceBatteryState that optionally matches some arguments.
private fun matchesState(
    deviceId: Int,
    isPresent: Boolean = true,
    status: Int? = null,
    capacity: Float? = null,
    eventTime: Long? = null
): Matcher<IInputDeviceBatteryState> {
    val batteryStateMatchers = mutableListOf<Matcher<IInputDeviceBatteryState>>(
        memberMatcher("deviceId", { it.deviceId }, equalTo(deviceId)),
        memberMatcher("isPresent", { it.isPresent }, equalTo(isPresent))
    )
    if (eventTime != null) {
        batteryStateMatchers.add(memberMatcher("updateTime", { it.updateTime }, equalTo(eventTime)))
    }
    if (status != null) {
        batteryStateMatchers.add(memberMatcher("status", { it.status }, equalTo(status)))
    }
    if (capacity != null) {
        batteryStateMatchers.add(memberMatcher("capacity", { it.capacity }, equalTo(capacity)))
    }
    return Matchers.allOf(batteryStateMatchers)
}

private fun isInvalidBatteryState(deviceId: Int): Matcher<IInputDeviceBatteryState> =
    matchesState(deviceId, isPresent = false, status = STATUS_UNKNOWN, capacity = Float.NaN)

// Helpers used to verify interactions with a mocked battery listener.
private fun IInputDeviceBatteryListener.verifyNotified(
    deviceId: Int,
    mode: VerificationMode = times(1),
    isPresent: Boolean = true,
    status: Int? = null,
    capacity: Float? = null,
    eventTime: Long? = null
) {
    verifyNotified(matchesState(deviceId, isPresent, status, capacity, eventTime), mode)
}

private fun IInputDeviceBatteryListener.verifyNotified(
    matcher: Matcher<IInputDeviceBatteryState>,
    mode: VerificationMode = times(1)
) {
    verify(this, mode).onBatteryStateChanged(MockitoHamcrest.argThat(matcher))
}

private fun createMockListener(): IInputDeviceBatteryListener {
    val listener = mock(IInputDeviceBatteryListener::class.java)
    val binder = mock(Binder::class.java)
    `when`(listener.asBinder()).thenReturn(binder)
    return listener
}

/**
 * Tests for {@link InputDeviceBatteryController}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:InputDeviceBatteryControllerTests
 */
@Presubmit
class BatteryControllerTests {
    companion object {
        const val PID = 42
        const val DEVICE_ID = 13
        const val SECOND_DEVICE_ID = 11
        const val USI_DEVICE_ID = 101
        const val SECOND_USI_DEVICE_ID = 102
        const val TIMESTAMP = 123456789L
    }

    @get:Rule
    val rule = MockitoJUnit.rule()!!

    @Mock
    private lateinit var native: NativeInputManagerService
    @Mock
    private lateinit var iInputManager: IInputManager
    @Mock
    private lateinit var uEventManager: UEventManager

    private lateinit var batteryController: BatteryController
    private lateinit var context: Context
    private lateinit var testLooper: TestLooper
    private lateinit var devicesChangedListener: IInputDevicesChangedListener
    private val deviceGenerationMap = mutableMapOf<Int /*deviceId*/, Int /*generation*/>()

    @Before
    fun setup() {
        context = spy(ContextWrapper(InstrumentationRegistry.getContext()))
        testLooper = TestLooper()
        val inputManager = InputManager.resetInstance(iInputManager)
        `when`(context.getSystemService(eq(Context.INPUT_SERVICE))).thenReturn(inputManager)
        `when`(iInputManager.inputDeviceIds).then {
            deviceGenerationMap.keys.toIntArray()
        }
        addInputDevice(DEVICE_ID)
        addInputDevice(SECOND_DEVICE_ID)

        batteryController = BatteryController(context, native, testLooper.looper, uEventManager)
        batteryController.systemRunning()
        val listenerCaptor = ArgumentCaptor.forClass(IInputDevicesChangedListener::class.java)
        verify(iInputManager).registerInputDevicesChangedListener(listenerCaptor.capture())
        devicesChangedListener = listenerCaptor.value
    }

    private fun notifyDeviceChanged(
            deviceId: Int,
        hasBattery: Boolean = true,
        supportsUsi: Boolean = false
    ) {
        val generation = deviceGenerationMap[deviceId]?.plus(1)
            ?: throw IllegalArgumentException("Device $deviceId was never added!")
        deviceGenerationMap[deviceId] = generation

        `when`(iInputManager.getInputDevice(deviceId))
            .thenReturn(createInputDevice(deviceId, hasBattery, supportsUsi, generation))
        val list = deviceGenerationMap.flatMap { listOf(it.key, it.value) }
        if (::devicesChangedListener.isInitialized) {
            devicesChangedListener.onInputDevicesChanged(list.toIntArray())
        }
    }

    private fun addInputDevice(
            deviceId: Int,
        hasBattery: Boolean = true,
        supportsUsi: Boolean = false
    ) {
        deviceGenerationMap[deviceId] = 0
        notifyDeviceChanged(deviceId, hasBattery, supportsUsi)
    }

    @After
    fun tearDown() {
        InputManager.clearInstance()
    }

    @Test
    fun testRegisterAndUnregisterBinderLifecycle() {
        val listener = createMockListener()
        // Ensure the binder lifecycle is tracked when registering a listener.
        batteryController.registerBatteryListener(DEVICE_ID, listener, PID)
        verify(listener.asBinder()).linkToDeath(notNull(), anyInt())
        batteryController.registerBatteryListener(SECOND_DEVICE_ID, listener, PID)
        verify(listener.asBinder(), times(1)).linkToDeath(notNull(), anyInt())

        // Ensure the binder lifecycle stops being tracked when all devices stopped being monitored.
        batteryController.unregisterBatteryListener(SECOND_DEVICE_ID, listener, PID)
        verify(listener.asBinder(), never()).unlinkToDeath(notNull(), anyInt())
        batteryController.unregisterBatteryListener(DEVICE_ID, listener, PID)
        verify(listener.asBinder()).unlinkToDeath(notNull(), anyInt())
    }

    @Test
    fun testOneListenerPerProcess() {
        val listener1 = createMockListener()
        batteryController.registerBatteryListener(DEVICE_ID, listener1, PID)
        verify(listener1.asBinder()).linkToDeath(notNull(), anyInt())

        // If a second listener is added for the same process, a security exception is thrown.
        val listener2 = createMockListener()
        try {
            batteryController.registerBatteryListener(DEVICE_ID, listener2, PID)
            fail("Expected security exception when registering more than one listener per process")
        } catch (ignored: SecurityException) {
        }
    }

    @Test
    fun testProcessDeathRemovesListener() {
        val deathRecipient = ArgumentCaptor.forClass(IBinder.DeathRecipient::class.java)
        val listener = createMockListener()
        batteryController.registerBatteryListener(DEVICE_ID, listener, PID)
        verify(listener.asBinder()).linkToDeath(deathRecipient.capture(), anyInt())

        // When the binder dies, the callback is unregistered.
        deathRecipient.value!!.binderDied(listener.asBinder())
        verify(listener.asBinder()).unlinkToDeath(notNull(), anyInt())

        // It is now possible to register the same listener again.
        batteryController.registerBatteryListener(DEVICE_ID, listener, PID)
        verify(listener.asBinder(), times(2)).linkToDeath(notNull(), anyInt())
    }

    @Test
    fun testRegisteringListenerNotifiesStateImmediately() {
        `when`(native.getBatteryStatus(DEVICE_ID)).thenReturn(STATUS_FULL)
        `when`(native.getBatteryCapacity(DEVICE_ID)).thenReturn(100)
        val listener = createMockListener()
        batteryController.registerBatteryListener(DEVICE_ID, listener, PID)
        listener.verifyNotified(DEVICE_ID, status = STATUS_FULL, capacity = 1.0f)

        `when`(native.getBatteryStatus(SECOND_DEVICE_ID)).thenReturn(STATUS_CHARGING)
        `when`(native.getBatteryCapacity(SECOND_DEVICE_ID)).thenReturn(78)
        batteryController.registerBatteryListener(SECOND_DEVICE_ID, listener, PID)
        listener.verifyNotified(SECOND_DEVICE_ID, status = STATUS_CHARGING, capacity = 0.78f)
    }

    @Test
    fun testListenersNotifiedOnUEventNotification() {
        `when`(native.getBatteryDevicePath(DEVICE_ID)).thenReturn("/sys/dev/test/device1")
        `when`(native.getBatteryStatus(DEVICE_ID)).thenReturn(STATUS_CHARGING)
        `when`(native.getBatteryCapacity(DEVICE_ID)).thenReturn(78)
        val listener = createMockListener()
        val uEventListener = ArgumentCaptor.forClass(UEventBatteryListener::class.java)
        batteryController.registerBatteryListener(DEVICE_ID, listener, PID)
        // The device paths for UEvent notifications do not include the "/sys" prefix, so verify
        // that the added listener is configured to match the path without that prefix.
        verify(uEventManager)
            .addListener(uEventListener.capture(), eq("DEVPATH=/dev/test/device1"))
        listener.verifyNotified(DEVICE_ID, status = STATUS_CHARGING, capacity = 0.78f)

        // If the battery state has changed when an UEvent is sent, the listeners are notified.
        `when`(native.getBatteryCapacity(DEVICE_ID)).thenReturn(80)
        uEventListener.value!!.onBatteryUEvent(TIMESTAMP)
        listener.verifyNotified(DEVICE_ID, status = STATUS_CHARGING, capacity = 0.80f,
            eventTime = TIMESTAMP)

        // If the battery state has not changed when an UEvent is sent, the listeners are not
        // notified.
        clearInvocations(listener)
        uEventListener.value!!.onBatteryUEvent(TIMESTAMP + 1)
        verifyNoMoreInteractions(listener)

        batteryController.unregisterBatteryListener(DEVICE_ID, listener, PID)
        verify(uEventManager).removeListener(uEventListener.capture())
        assertEquals("The same observer must be registered and unregistered",
            uEventListener.allValues[0], uEventListener.allValues[1])
    }

    @Test
    fun testBatteryPresenceChanged() {
        `when`(native.getBatteryDevicePath(DEVICE_ID)).thenReturn("/test/device1")
        `when`(native.getBatteryStatus(DEVICE_ID)).thenReturn(STATUS_CHARGING)
        `when`(native.getBatteryCapacity(DEVICE_ID)).thenReturn(78)
        val listener = createMockListener()
        val uEventListener = ArgumentCaptor.forClass(UEventBatteryListener::class.java)
        batteryController.registerBatteryListener(DEVICE_ID, listener, PID)
        verify(uEventManager).addListener(uEventListener.capture(), eq("DEVPATH=/test/device1"))
        listener.verifyNotified(DEVICE_ID, status = STATUS_CHARGING, capacity = 0.78f)

        // If the battery presence for the InputDevice changes, the listener is notified.
        notifyDeviceChanged(DEVICE_ID, hasBattery = false)
        testLooper.dispatchNext()
        listener.verifyNotified(isInvalidBatteryState(DEVICE_ID))
        // Since the battery is no longer present, the UEventListener should be removed.
        verify(uEventManager).removeListener(uEventListener.value)

        // If the battery becomes present again, the listener is notified.
        notifyDeviceChanged(DEVICE_ID, hasBattery = true)
        testLooper.dispatchNext()
        listener.verifyNotified(DEVICE_ID, mode = times(2), status = STATUS_CHARGING,
            capacity = 0.78f)
        // Ensure that a new UEventListener was added.
        verify(uEventManager, times(2))
            .addListener(uEventListener.capture(), eq("DEVPATH=/test/device1"))
    }

    @Test
    fun testStartPollingWhenListenerIsRegistered() {
        val listener = createMockListener()
        `when`(native.getBatteryCapacity(DEVICE_ID)).thenReturn(78)
        batteryController.registerBatteryListener(DEVICE_ID, listener, PID)
        listener.verifyNotified(DEVICE_ID, capacity = 0.78f)

        // Assume there is a change in the battery state. Ensure the listener is not notified
        // while the polling period has not elapsed.
        `when`(native.getBatteryCapacity(DEVICE_ID)).thenReturn(80)
        testLooper.moveTimeForward(1)
        testLooper.dispatchAll()
        listener.verifyNotified(DEVICE_ID, mode = never(), capacity = 0.80f)

        // Move the time forward so that the polling period has elapsed.
        // The listener should be notified.
        testLooper.moveTimeForward(POLLING_PERIOD_MILLIS - 1)
        assertTrue("There should be a polling callbacks posted to the handler", testLooper.isIdle)
        testLooper.dispatchNext()
        listener.verifyNotified(DEVICE_ID, capacity = 0.80f)

        // Move the time forward so that another polling period has elapsed.
        // The battery should still be polled, but there is no change so listeners are not notified.
        testLooper.moveTimeForward(POLLING_PERIOD_MILLIS)
        assertTrue("There should be a polling callbacks posted to the handler", testLooper.isIdle)
        testLooper.dispatchNext()
        listener.verifyNotified(DEVICE_ID, mode = times(1), capacity = 0.80f)
    }

    @Test
    fun testNoPollingWhenTheDeviceIsNotInteractive() {
        batteryController.onInteractiveChanged(false /*interactive*/)

        val listener = createMockListener()
        `when`(native.getBatteryCapacity(DEVICE_ID)).thenReturn(78)
        batteryController.registerBatteryListener(DEVICE_ID, listener, PID)
        listener.verifyNotified(DEVICE_ID, capacity = 0.78f)

        // The battery state changed, but we should not be polling for battery changes when the
        // device is not interactive.
        `when`(native.getBatteryCapacity(DEVICE_ID)).thenReturn(80)
        testLooper.moveTimeForward(POLLING_PERIOD_MILLIS)
        assertFalse("There should be no polling callbacks posted to the handler", testLooper.isIdle)
        testLooper.dispatchAll()
        listener.verifyNotified(DEVICE_ID, mode = never(), capacity = 0.80f)

        // The device is now interactive. Battery state polling begins immediately.
        batteryController.onInteractiveChanged(true /*interactive*/)
        testLooper.dispatchNext()
        listener.verifyNotified(DEVICE_ID, capacity = 0.80f)

        // Ensure that we continue to poll for battery changes.
        `when`(native.getBatteryCapacity(DEVICE_ID)).thenReturn(90)
        testLooper.moveTimeForward(POLLING_PERIOD_MILLIS)
        assertTrue("There should be a polling callbacks posted to the handler", testLooper.isIdle)
        testLooper.dispatchNext()
        listener.verifyNotified(DEVICE_ID, capacity = 0.90f)
    }

    @Test
    fun testGetBatteryState() {
        `when`(native.getBatteryStatus(DEVICE_ID)).thenReturn(STATUS_CHARGING)
        `when`(native.getBatteryCapacity(DEVICE_ID)).thenReturn(78)
        val batteryState = batteryController.getBatteryState(DEVICE_ID)
        assertThat("battery state matches", batteryState,
            matchesState(DEVICE_ID, status = STATUS_CHARGING, capacity = 0.78f))
    }

    @Test
    fun testGetBatteryStateWithListener() {
        val listener = createMockListener()
        `when`(native.getBatteryStatus(DEVICE_ID)).thenReturn(STATUS_CHARGING)
        `when`(native.getBatteryCapacity(DEVICE_ID)).thenReturn(78)
        batteryController.registerBatteryListener(DEVICE_ID, listener, PID)
        listener.verifyNotified(DEVICE_ID, status = STATUS_CHARGING, capacity = 0.78f)

        // If getBatteryState() is called when a listener is monitoring the device and there is a
        // change in the battery state, the listener is also notified.
        `when`(native.getBatteryCapacity(DEVICE_ID)).thenReturn(80)
        val batteryState = batteryController.getBatteryState(DEVICE_ID)
        assertThat("battery matches state", batteryState,
            matchesState(DEVICE_ID, status = STATUS_CHARGING, capacity = 0.80f))
        listener.verifyNotified(DEVICE_ID, status = STATUS_CHARGING, capacity = 0.80f)
    }

    @Test
    fun testUsiDeviceIsMonitoredPersistently() {
        `when`(native.getBatteryDevicePath(USI_DEVICE_ID)).thenReturn("/sys/dev/usi_device")
        addInputDevice(USI_DEVICE_ID, supportsUsi = true)
        testLooper.dispatchNext()

        // Even though there is no listener added for this device, it is being monitored.
        val uEventListener = ArgumentCaptor.forClass(UEventBatteryListener::class.java)
        verify(uEventManager)
            .addListener(uEventListener.capture(), eq("DEVPATH=/dev/usi_device"))

        // Add and remove a listener for the device.
        val listener = createMockListener()
        batteryController.registerBatteryListener(USI_DEVICE_ID, listener, PID)
        batteryController.unregisterBatteryListener(USI_DEVICE_ID, listener, PID)

        // The device is still being monitored.
        verify(uEventManager, never()).removeListener(uEventListener.value)
    }

    @Test
    fun testNoPollingWhenUsiDevicesAreMonitored() {
        `when`(native.getBatteryDevicePath(USI_DEVICE_ID)).thenReturn("/sys/dev/usi_device")
        addInputDevice(USI_DEVICE_ID, supportsUsi = true)
        testLooper.dispatchNext()
        `when`(native.getBatteryDevicePath(SECOND_USI_DEVICE_ID)).thenReturn("/sys/dev/usi_device2")
        addInputDevice(SECOND_USI_DEVICE_ID, supportsUsi = true)
        testLooper.dispatchNext()

        testLooper.moveTimeForward(POLLING_PERIOD_MILLIS)
        assertFalse("There should be no polling callbacks posted to the handler", testLooper.isIdle)

        // Add a listener.
        val listener = createMockListener()
        batteryController.registerBatteryListener(USI_DEVICE_ID, listener, PID)

        testLooper.moveTimeForward(POLLING_PERIOD_MILLIS)
        assertFalse("There should be no polling callbacks posted to the handler", testLooper.isIdle)
    }
}
