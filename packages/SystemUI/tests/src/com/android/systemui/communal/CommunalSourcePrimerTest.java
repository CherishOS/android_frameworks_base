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

package com.android.systemui.communal;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.testing.AndroidTestingRunner;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class CommunalSourcePrimerTest extends SysuiTestCase {
    private static final String TEST_COMPONENT_NAME = "com.google.tests/.CommunalService";
    private static final int MAX_RETRIES = 5;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int CONNECTION_MIN_DURATION_MS = 5000;

    @Mock
    private Context mContext;

    @Mock
    private Resources mResources;

    private FakeSystemClock mFakeClock = new FakeSystemClock();
    private FakeExecutor mFakeExecutor = new FakeExecutor(mFakeClock);

    @Mock
    private CommunalSource mSource;

    @Mock
    private CommunalSourceMonitor mCommunalSourceMonitor;

    @Mock
    private CommunalSource.Connector mConnector;

    @Mock
    private CommunalSource.Observer mObserver;

    private CommunalSourcePrimer mPrimer;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mResources.getInteger(R.integer.config_communalSourceMaxReconnectAttempts))
                .thenReturn(MAX_RETRIES);
        when(mResources.getInteger(R.integer.config_communalSourceReconnectBaseDelay))
                .thenReturn(RETRY_DELAY_MS);
        when(mResources.getInteger(R.integer.config_communalSourceReconnectBaseDelay))
                .thenReturn(RETRY_DELAY_MS);
        when(mResources.getString(R.string.config_communalSourceComponent))
                .thenReturn(TEST_COMPONENT_NAME);
        when(mResources.getInteger(R.integer.config_connectionMinDuration))
                .thenReturn(CONNECTION_MIN_DURATION_MS);

        mPrimer = new CommunalSourcePrimer(mContext, mResources, mFakeClock, mFakeExecutor,
                mCommunalSourceMonitor, Optional.of(mConnector), Optional.of(mObserver));
    }

    @Test
    public void testConnect() {
        when(mConnector.connect()).thenReturn(
                CallbackToFutureAdapter.getFuture(completer -> {
                    completer.set(Optional.of(mSource));
                    return "test";
                }));

        mPrimer.onBootCompleted();
        mFakeExecutor.runAllReady();
        verify(mCommunalSourceMonitor).setSource(mSource);
    }

    @Test
    public void testRetryOnBindFailure() throws Exception {
        when(mConnector.connect()).thenReturn(
                CallbackToFutureAdapter.getFuture(completer -> {
                    completer.set(Optional.empty());
                    return "test";
                }));

        mPrimer.onBootCompleted();
        mFakeExecutor.runAllReady();

        // Verify attempts happen. Note that we account for the retries plus initial attempt, which
        // is not scheduled.
        for (int attemptCount = 0; attemptCount < MAX_RETRIES + 1; attemptCount++) {
            verify(mConnector, times(1)).connect();
            clearInvocations(mConnector);
            mFakeExecutor.advanceClockToNext();
            mFakeExecutor.runAllReady();
        }

        verify(mCommunalSourceMonitor, never()).setSource(Mockito.notNull());
    }

    @Test
    public void testRetryOnDisconnectFailure() throws Exception {
        when(mConnector.connect()).thenReturn(
                CallbackToFutureAdapter.getFuture(completer -> {
                    completer.set(Optional.of(mSource));
                    return "test";
                }));

        mPrimer.onBootCompleted();
        mFakeExecutor.runAllReady();

        // Verify attempts happen. Note that we account for the retries plus initial attempt, which
        // is not scheduled.
        for (int attemptCount = 0; attemptCount < MAX_RETRIES + 1; attemptCount++) {
            verify(mConnector, times(1)).connect();
            clearInvocations(mConnector);
            ArgumentCaptor<CommunalSource.Callback> callbackCaptor =
                    ArgumentCaptor.forClass(CommunalSource.Callback.class);
            verify(mSource).addCallback(callbackCaptor.capture());
            clearInvocations(mSource);
            verify(mCommunalSourceMonitor).setSource(Mockito.notNull());
            clearInvocations(mCommunalSourceMonitor);
            callbackCaptor.getValue().onDisconnected();
            mFakeExecutor.advanceClockToNext();
            mFakeExecutor.runAllReady();
        }

        verify(mConnector, never()).connect();
    }

    @Test
    public void testAttemptOnPackageChange() {
        when(mConnector.connect()).thenReturn(
                CallbackToFutureAdapter.getFuture(completer -> {
                    completer.set(Optional.empty());
                    return "test";
                }));

        mPrimer.onBootCompleted();
        mFakeExecutor.runAllReady();

        final ArgumentCaptor<CommunalSource.Observer.Callback> callbackCaptor =
                ArgumentCaptor.forClass(CommunalSource.Observer.Callback.class);
        verify(mObserver).addCallback(callbackCaptor.capture());

        clearInvocations(mConnector);
        callbackCaptor.getValue().onSourceChanged();

        verify(mConnector, times(1)).connect();
    }

    @Test
    public void testDisconnect() {
        final ArgumentCaptor<CommunalSource.Callback> callbackCaptor =
                ArgumentCaptor.forClass(CommunalSource.Callback.class);

        when(mConnector.connect()).thenReturn(
                CallbackToFutureAdapter.getFuture(completer -> {
                    completer.set(Optional.of(mSource));
                    return "test";
                }));

        mPrimer.onBootCompleted();
        mFakeExecutor.runAllReady();
        verify(mCommunalSourceMonitor).setSource(mSource);
        verify(mSource).addCallback(callbackCaptor.capture());

        clearInvocations(mConnector);
        mFakeClock.advanceTime(CONNECTION_MIN_DURATION_MS + 1);
        callbackCaptor.getValue().onDisconnected();
        mFakeExecutor.runAllReady();

        verify(mConnector).connect();
    }
}
