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

package com.android.server.soundtrigger_middleware;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.soundtrigger.ModelParameter;
import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseRecognitionEvent;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.RecognitionEvent;
import android.media.soundtrigger.RecognitionStatus;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger.Status;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.os.RemoteException;
import android.util.Pair;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class SoundTriggerMiddlewareImplTest {
    @Mock public ISoundTriggerHal mHalDriver = mock(ISoundTriggerHal.class);

    @Mock private final SoundTriggerMiddlewareImpl.AudioSessionProvider mAudioSessionProvider =
            mock(SoundTriggerMiddlewareImpl.AudioSessionProvider.class);

    private SoundTriggerMiddlewareImpl mService;

    private static ISoundTriggerCallback createCallbackMock() {
        return mock(ISoundTriggerCallback.Stub.class, Mockito.CALLS_REAL_METHODS);
    }

    private Pair<Integer, SoundTriggerHwCallback> loadGenericModel(ISoundTriggerModule module,
            int hwHandle) throws RemoteException {
        SoundModel model = TestUtil.createGenericSoundModel();
        ArgumentCaptor<SoundModel> modelCaptor = ArgumentCaptor.forClass(SoundModel.class);
        ArgumentCaptor<ISoundTriggerHal.ModelCallback> callbackCaptor = ArgumentCaptor.forClass(
                ISoundTriggerHal.ModelCallback.class);

        when(mHalDriver.loadSoundModel(any(), any())).thenReturn(hwHandle);
        when(mAudioSessionProvider.acquireSession()).thenReturn(
                new SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession(101, 102, 103));

        int handle = module.loadModel(model);
        verify(mHalDriver).loadSoundModel(modelCaptor.capture(), callbackCaptor.capture());
        verify(mAudioSessionProvider).acquireSession();
        assertEquals(model, modelCaptor.getValue());
        return new Pair<>(handle, new SoundTriggerHwCallback(callbackCaptor.getValue()));
    }

    private Pair<Integer, SoundTriggerHwCallback> loadPhraseModel(ISoundTriggerModule module,
            int hwHandle) throws RemoteException {
        PhraseSoundModel model = TestUtil.createPhraseSoundModel();
        ArgumentCaptor<PhraseSoundModel> modelCaptor = ArgumentCaptor.forClass(
                PhraseSoundModel.class);
        ArgumentCaptor<ISoundTriggerHal.ModelCallback> callbackCaptor = ArgumentCaptor.forClass(
                ISoundTriggerHal.ModelCallback.class);

        when(mHalDriver.loadPhraseSoundModel(any(), any())).thenReturn(hwHandle);
        when(mAudioSessionProvider.acquireSession()).thenReturn(
                new SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession(101, 102, 103));

        int handle = module.loadPhraseModel(model);
        verify(mHalDriver).loadPhraseSoundModel(modelCaptor.capture(), callbackCaptor.capture());
        verify(mAudioSessionProvider).acquireSession();
        assertEquals(model, modelCaptor.getValue());
        return new Pair<>(handle, new SoundTriggerHwCallback(callbackCaptor.getValue()));
    }

    private void unloadModel(ISoundTriggerModule module, int handle, int hwHandle)
            throws RemoteException {
        module.unloadModel(handle);
        verify(mHalDriver).unloadSoundModel(hwHandle);
        verify(mAudioSessionProvider).releaseSession(101);
    }

    private void startRecognition(ISoundTriggerModule module, int handle, int hwHandle)
            throws RemoteException {
        ArgumentCaptor<RecognitionConfig> configCaptor = ArgumentCaptor.forClass(
                RecognitionConfig.class);

        RecognitionConfig config = TestUtil.createRecognitionConfig();

        module.startRecognition(handle, config);
        verify(mHalDriver).startRecognition(eq(hwHandle), eq(103), eq(102), configCaptor.capture());
        assertEquals(config, configCaptor.getValue());
    }

    private void stopRecognition(ISoundTriggerModule module, int handle, int hwHandle)
            throws RemoteException {
        module.stopRecognition(handle);
        verify(mHalDriver).stopRecognition(hwHandle);
    }

    @Before
    public void setUp() throws Exception {
        clearInvocations(mHalDriver);
        clearInvocations(mAudioSessionProvider);
        when(mHalDriver.getProperties()).thenReturn(TestUtil.createDefaultProperties());
        mService = new SoundTriggerMiddlewareImpl(() -> mHalDriver, mAudioSessionProvider);
    }

    @After
    public void tearDown() {
        verify(mHalDriver, never()).reboot();
    }

    @Test
    public void testSetUpAndTearDown() {
    }

    @Test
    public void testListModules() {
        // Note: input and output properties are NOT the same type, even though they are in any way
        // equivalent. One is a type that's exposed by the HAL and one is a type that's exposed by
        // the service. The service actually performs a (trivial) conversion between the two.
        SoundTriggerModuleDescriptor[] allDescriptors = mService.listModules();
        assertEquals(1, allDescriptors.length);

        Properties properties = allDescriptors[0].properties;
        assertEquals(TestUtil.createDefaultProperties(), properties);
    }

    @Test
    public void testAttachDetach() throws Exception {
        // Normal attachment / detachment.
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        assertNotNull(module);
        module.detach();
    }

    @Test
    public void testLoadUnloadModel() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        final int hwHandle = 7;
        int handle = loadGenericModel(module, hwHandle).first;
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testLoadPreemptModel() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        final int hwHandle = 7;
        Pair<Integer, SoundTriggerHwCallback> loadResult = loadGenericModel(module, hwHandle);

        int handle = loadResult.first;
        SoundTriggerHwCallback hwCallback = loadResult.second;

        // Signal preemption.
        hwCallback.sendUnloadEvent(hwHandle);

        verify(callback).onModelUnloaded(handle);

        module.detach();
    }

    @Test
    public void testLoadUnloadPhraseModel() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        final int hwHandle = 73;
        int handle = loadPhraseModel(module, hwHandle).first;
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testStartStopRecognition() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 7;
        int handle = loadGenericModel(module, hwHandle).first;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                RecognitionEvent.class);
        verify(callback).onRecognition(eq(handle), eventCaptor.capture(), eq(101));
        assertEquals(RecognitionStatus.ABORTED, eventCaptor.getValue().status);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testStartRecognitionBusy() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 7;
        int handle = loadGenericModel(module, hwHandle).first;

        // Start the model.
        doThrow(new RecoverableException(Status.RESOURCE_CONTENTION)).when(
                mHalDriver).startRecognition(eq(7), eq(103), eq(102), any());

        try {
            RecognitionConfig config = TestUtil.createRecognitionConfig();
            module.startRecognition(handle, config);
            fail("Expected an exception");
        } catch (RecoverableException e) {
            assertEquals(Status.RESOURCE_CONTENTION, e.errorCode);
        }

        verify(mHalDriver).startRecognition(eq(7), eq(103), eq(102), any());
    }

    @Test
    public void testStartStopPhraseRecognition() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 67;
        int handle = loadPhraseModel(module, hwHandle).first;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        ArgumentCaptor<PhraseRecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                PhraseRecognitionEvent.class);
        verify(callback).onPhraseRecognition(eq(handle), eventCaptor.capture(), eq(101));
        assertEquals(RecognitionStatus.ABORTED, eventCaptor.getValue().common.status);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testRecognition() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 7;
        Pair<Integer, SoundTriggerHwCallback> modelHandles = loadGenericModel(module, hwHandle);
        int handle = modelHandles.first;
        SoundTriggerHwCallback hwCallback = modelHandles.second;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        {
            // Signal a capture from the driver (with "still active").
            RecognitionEvent event = hwCallback.sendRecognitionEvent(hwHandle,
                    RecognitionStatus.SUCCESS, true);

            ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                    RecognitionEvent.class);
            verify(callback).onRecognition(eq(handle), eventCaptor.capture(), eq(101));

            // Validate the event.
            assertEquals(event, eventCaptor.getValue());
        }

        {
            // Signal a capture from the driver (without "still active").
            RecognitionEvent event = hwCallback.sendRecognitionEvent(hwHandle,
                    RecognitionStatus.SUCCESS, false);

            ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                    RecognitionEvent.class);
            verify(callback, times(2)).onRecognition(eq(handle), eventCaptor.capture(), eq(101));

            // Validate the event.
            assertEquals(event, eventCaptor.getValue());
        }

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testPhraseRecognition() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 7;
        Pair<Integer, SoundTriggerHwCallback> modelHandles = loadPhraseModel(module, hwHandle);
        int handle = modelHandles.first;
        SoundTriggerHwCallback hwCallback = modelHandles.second;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Signal a capture from the driver.
        PhraseRecognitionEvent event = hwCallback.sendPhraseRecognitionEvent(hwHandle,
                RecognitionStatus.SUCCESS, false);

        ArgumentCaptor<PhraseRecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                PhraseRecognitionEvent.class);
        verify(callback).onPhraseRecognition(eq(handle), eventCaptor.capture(), eq(101));

        // Validate the event.
        assertEquals(event, eventCaptor.getValue());

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testForceRecognition() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 17;
        Pair<Integer, SoundTriggerHwCallback> modelHandles = loadGenericModel(module, hwHandle);
        int handle = modelHandles.first;
        SoundTriggerHwCallback hwCallback = modelHandles.second;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Force a trigger.
        module.forceRecognitionEvent(handle);
        verify(mHalDriver).forceRecognitionEvent(hwHandle);

        // Signal a capture from the driver.
        RecognitionEvent event = hwCallback.sendRecognitionEvent(hwHandle,
                RecognitionStatus.FORCED, true);

        ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                RecognitionEvent.class);
        verify(callback).onRecognition(eq(handle), eventCaptor.capture(), eq(101));

        // Validate the event.
        assertEquals(event, eventCaptor.getValue());

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testForceRecognitionNotSupported() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 17;
        Pair<Integer, SoundTriggerHwCallback> modelHandles = loadGenericModel(module, hwHandle);
        int handle = modelHandles.first;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Force a trigger.
        doThrow(new RecoverableException(Status.OPERATION_NOT_SUPPORTED)).when(
                mHalDriver).forceRecognitionEvent(hwHandle);
        try {
            module.forceRecognitionEvent(handle);
            fail("Expected an exception");
        } catch (RecoverableException e) {
            assertEquals(Status.OPERATION_NOT_SUPPORTED, e.errorCode);
        }

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testForcePhraseRecognition() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 17;
        Pair<Integer, SoundTriggerHwCallback> modelHandles = loadPhraseModel(module, hwHandle);
        int handle = modelHandles.first;
        SoundTriggerHwCallback hwCallback = modelHandles.second;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Force a trigger.
        module.forceRecognitionEvent(handle);
        verify(mHalDriver).forceRecognitionEvent(hwHandle);

        // Signal a capture from the driver.
        PhraseRecognitionEvent event = hwCallback.sendPhraseRecognitionEvent(hwHandle,
                RecognitionStatus.FORCED, true);

        ArgumentCaptor<PhraseRecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                PhraseRecognitionEvent.class);
        verify(callback).onPhraseRecognition(eq(handle), eventCaptor.capture(), eq(101));

        // Validate the event.
        assertEquals(event, eventCaptor.getValue());

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testForcePhraseRecognitionNotSupported() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 17;
        Pair<Integer, SoundTriggerHwCallback> modelHandles = loadPhraseModel(module, hwHandle);
        int handle = modelHandles.first;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Force a trigger.
        doThrow(new RecoverableException(Status.OPERATION_NOT_SUPPORTED)).when(
                mHalDriver).forceRecognitionEvent(hwHandle);
        try {
            module.forceRecognitionEvent(handle);
            fail("Expected an exception");
        } catch (RecoverableException e) {
            assertEquals(Status.OPERATION_NOT_SUPPORTED, e.errorCode);
        }

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testAbortRecognition() throws Exception {
        // Make sure the HAL doesn't support concurrent capture.
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 11;
        Pair<Integer, SoundTriggerHwCallback> loadResult = loadGenericModel(module, hwHandle);
        int handle = loadResult.first;
        SoundTriggerHwCallback hwCallback = loadResult.second;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Abort.
        hwCallback.sendRecognitionEvent(hwHandle, RecognitionStatus.ABORTED, false);

        ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                RecognitionEvent.class);
        verify(callback).onRecognition(eq(handle), eventCaptor.capture(), eq(101));

        // Validate the event.
        assertEquals(RecognitionStatus.ABORTED, eventCaptor.getValue().status);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testAbortPhraseRecognition() throws Exception {
        // Make sure the HAL doesn't support concurrent capture.
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 11;
        Pair<Integer, SoundTriggerHwCallback> loadResult = loadPhraseModel(module, hwHandle);
        int handle = loadResult.first;
        SoundTriggerHwCallback hwCallback = loadResult.second;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Abort.
        hwCallback.sendPhraseRecognitionEvent(hwHandle, RecognitionStatus.ABORTED, false);

        ArgumentCaptor<PhraseRecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                PhraseRecognitionEvent.class);
        verify(callback).onPhraseRecognition(eq(handle), eventCaptor.capture(), eq(101));

        // Validate the event.
        assertEquals(RecognitionStatus.ABORTED, eventCaptor.getValue().common.status);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testParameterSupported() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        final int hwHandle = 12;
        int modelHandle = loadGenericModel(module, hwHandle).first;

        ModelParameterRange halRange = new ModelParameterRange();
        halRange.minInclusive = 23;
        halRange.maxInclusive = 45;

        when(mHalDriver.queryParameter(eq(hwHandle),
                eq(ModelParameter.THRESHOLD_FACTOR))).thenReturn(halRange);

        ModelParameterRange range = module.queryModelParameterSupport(modelHandle,
                ModelParameter.THRESHOLD_FACTOR);

        verify(mHalDriver).queryParameter(eq(hwHandle), eq(ModelParameter.THRESHOLD_FACTOR));

        assertEquals(halRange, range);
    }

    @Test
    public void testParameterNotSupported() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        final int hwHandle = 13;
        int modelHandle = loadGenericModel(module, hwHandle).first;

        when(mHalDriver.queryParameter(eq(hwHandle),
                eq(ModelParameter.THRESHOLD_FACTOR))).thenReturn(null);

        ModelParameterRange range = module.queryModelParameterSupport(modelHandle,
                ModelParameter.THRESHOLD_FACTOR);

        verify(mHalDriver).queryParameter(eq(hwHandle), eq(ModelParameter.THRESHOLD_FACTOR));

        assertNull(range);
    }

    @Test
    public void testGetParameter() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        final int hwHandle = 14;
        int modelHandle = loadGenericModel(module, hwHandle).first;

        when(mHalDriver.getModelParameter(hwHandle, ModelParameter.THRESHOLD_FACTOR)).thenReturn(
                234);

        int value = module.getModelParameter(modelHandle, ModelParameter.THRESHOLD_FACTOR);

        verify(mHalDriver).getModelParameter(hwHandle, ModelParameter.THRESHOLD_FACTOR);

        assertEquals(234, value);
    }

    @Test
    public void testSetParameter() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        final int hwHandle = 17;
        int modelHandle = loadGenericModel(module, hwHandle).first;

        module.setModelParameter(modelHandle, ModelParameter.THRESHOLD_FACTOR, 456);

        verify(mHalDriver).setModelParameter(hwHandle, ModelParameter.THRESHOLD_FACTOR, 456);
    }

    private static class SoundTriggerHwCallback {
        private final ISoundTriggerHal.ModelCallback mCallback;

        SoundTriggerHwCallback(ISoundTriggerHal.ModelCallback callback) {
            mCallback = callback;
        }

        private RecognitionEvent sendRecognitionEvent(int hwHandle, @RecognitionStatus int status,
                boolean recognitionStillActive) {
            RecognitionEvent event = TestUtil.createRecognitionEvent(status,
                    recognitionStillActive);
            mCallback.recognitionCallback(hwHandle, event);
            return event;
        }

        private PhraseRecognitionEvent sendPhraseRecognitionEvent(int hwHandle,
                @RecognitionStatus int status, boolean recognitionStillActive) {
            PhraseRecognitionEvent event = TestUtil.createPhraseRecognitionEvent(status,
                    recognitionStillActive);
            mCallback.phraseRecognitionCallback(hwHandle, event);
            return event;
        }

        private void sendUnloadEvent(int hwHandle) {
            mCallback.modelUnloaded(hwHandle);
        }
    }
}
