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

package android.window;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.OnBackInvokedCallback;
import android.view.OnBackInvokedDispatcher;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link WindowOnBackInvokedDispatcherTest}
 *
 * <p>Build/Install/Run:
 * atest FrameworksCoreTests:WindowOnBackInvokedDispatcherTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class WindowOnBackInvokedDispatcherTest {
    @Mock
    private IWindowSession mWindowSession;
    @Mock
    private IWindow mWindow;
    private WindowOnBackInvokedDispatcher mDispatcher;
    @Mock
    private OnBackInvokedCallback mCallback1;
    @Mock
    private OnBackInvokedCallback mCallback2;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDispatcher = new WindowOnBackInvokedDispatcher();
        mDispatcher.attachToWindow(mWindowSession, mWindow);
    }

    private void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void propagatesTopCallback_samePriority() throws RemoteException {
        ArgumentCaptor<IOnBackInvokedCallback> captor =
                ArgumentCaptor.forClass(IOnBackInvokedCallback.class);

        mDispatcher.registerOnBackInvokedCallback(
                mCallback1, OnBackInvokedDispatcher.PRIORITY_DEFAULT);
        mDispatcher.registerOnBackInvokedCallback(
                mCallback2, OnBackInvokedDispatcher.PRIORITY_DEFAULT);

        verify(mWindowSession, times(2))
                .setOnBackInvokedCallback(Mockito.eq(mWindow), captor.capture());
        captor.getAllValues().get(0).onBackStarted();
        waitForIdle();
        verify(mCallback1).onBackStarted();
        verifyZeroInteractions(mCallback2);

        captor.getAllValues().get(1).onBackStarted();
        waitForIdle();
        verify(mCallback2).onBackStarted();
        verifyNoMoreInteractions(mCallback1);
    }

    @Test
    public void propagatesTopCallback_differentPriority() throws RemoteException {
        ArgumentCaptor<IOnBackInvokedCallback> captor =
                ArgumentCaptor.forClass(IOnBackInvokedCallback.class);

        mDispatcher.registerOnBackInvokedCallback(
                mCallback1, OnBackInvokedDispatcher.PRIORITY_OVERLAY);
        mDispatcher.registerOnBackInvokedCallback(
                mCallback2, OnBackInvokedDispatcher.PRIORITY_DEFAULT);

        verify(mWindowSession)
                .setOnBackInvokedCallback(Mockito.eq(mWindow), captor.capture());
        verifyNoMoreInteractions(mWindowSession);
        captor.getValue().onBackStarted();
        waitForIdle();
        verify(mCallback1).onBackStarted();
    }

    @Test
    public void propagatesTopCallback_withRemoval() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(
                mCallback1, OnBackInvokedDispatcher.PRIORITY_DEFAULT);
        mDispatcher.registerOnBackInvokedCallback(
                mCallback2, OnBackInvokedDispatcher.PRIORITY_DEFAULT);

        reset(mWindowSession);
        mDispatcher.unregisterOnBackInvokedCallback(mCallback1);
        verifyZeroInteractions(mWindowSession);

        mDispatcher.unregisterOnBackInvokedCallback(mCallback2);
        verify(mWindowSession).setOnBackInvokedCallback(Mockito.eq(mWindow), isNull());
    }


    @Test
    public void propagatesTopCallback_sameInstanceAddedTwice() throws RemoteException {
        ArgumentCaptor<IOnBackInvokedCallback> captor =
                ArgumentCaptor.forClass(IOnBackInvokedCallback.class);

        mDispatcher.registerOnBackInvokedCallback(mCallback1,
                OnBackInvokedDispatcher.PRIORITY_OVERLAY);
        mDispatcher.registerOnBackInvokedCallback(
                mCallback2, OnBackInvokedDispatcher.PRIORITY_DEFAULT);
        mDispatcher.registerOnBackInvokedCallback(
                mCallback1, OnBackInvokedDispatcher.PRIORITY_DEFAULT);

        reset(mWindowSession);
        mDispatcher.registerOnBackInvokedCallback(
                mCallback2, OnBackInvokedDispatcher.PRIORITY_OVERLAY);
        verify(mWindowSession)
                .setOnBackInvokedCallback(Mockito.eq(mWindow), captor.capture());
        captor.getValue().onBackStarted();
        waitForIdle();
        verify(mCallback2).onBackStarted();
    }
}
