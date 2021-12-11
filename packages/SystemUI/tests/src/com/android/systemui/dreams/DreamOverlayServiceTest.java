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

package com.android.systemui.dreams;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.IBinder;
import android.service.dreams.IDreamOverlay;
import android.service.dreams.IDreamOverlayCallback;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamOverlayServiceTest extends SysuiTestCase {
    private FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private FakeExecutor mMainExecutor = new FakeExecutor(mFakeSystemClock);

    @Rule
    public final LeakCheckedTest.SysuiLeakCheck mLeakCheck = new LeakCheckedTest.SysuiLeakCheck();

    @Rule
    public SysuiTestableContext mContext = new SysuiTestableContext(
            InstrumentationRegistry.getContext(), mLeakCheck);

    WindowManager.LayoutParams mWindowParams = new WindowManager.LayoutParams();

    @Mock
    IDreamOverlayCallback mDreamOverlayCallback;

    @Mock
    WindowManagerImpl mWindowManager;

    @Mock
    OverlayProvider mProvider;

    @Mock
    DreamOverlayStateController mDreamOverlayStateController;

    @Mock
    DreamOverlayComponent.Factory mDreamOverlayStatusBarViewComponentFactory;

    @Mock
    DreamOverlayComponent mDreamOverlayComponent;

    @Mock
    DreamOverlayStatusBarViewController mDreamOverlayStatusBarViewController;

    @Mock
    DreamOverlayContainerView mDreamOverlayContainerView;

    @Mock
    ViewGroup mDreamOverlayContentView;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(WindowManager.class, mWindowManager);

        when(mDreamOverlayComponent.getDreamOverlayContentView())
                .thenReturn(mDreamOverlayContentView);
        when(mDreamOverlayComponent.getDreamOverlayContainerView())
                .thenReturn(mDreamOverlayContainerView);
        when(mDreamOverlayComponent.getDreamOverlayStatusBarViewController())
                .thenReturn(mDreamOverlayStatusBarViewController);
        when(mDreamOverlayStatusBarViewComponentFactory.create())
                .thenReturn(mDreamOverlayComponent);

    }

    @Test
    public void testInteraction() throws Exception {
        final DreamOverlayService service = new DreamOverlayService(mContext, mMainExecutor,
                mDreamOverlayStateController, mDreamOverlayStatusBarViewComponentFactory);
        final IBinder proxy = service.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);
        clearInvocations(mWindowManager);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback);
        mMainExecutor.runAllReady();
        verify(mWindowManager).addView(any(), any());

        // Add overlay.
        service.addOverlay(mProvider);
        mMainExecutor.runAllReady();

        final ArgumentCaptor<OverlayHost.CreationCallback> creationCallbackCapture =
                ArgumentCaptor.forClass(OverlayHost.CreationCallback.class);
        final ArgumentCaptor<OverlayHost.InteractionCallback> interactionCallbackCapture =
                ArgumentCaptor.forClass(OverlayHost.InteractionCallback.class);

        // Ensure overlay provider is asked to create view.
        verify(mProvider).onCreateOverlay(any(), creationCallbackCapture.capture(),
                interactionCallbackCapture.capture());
        mMainExecutor.runAllReady();

        // Inform service of overlay view creation.
        final View view = new View(mContext);
        creationCallbackCapture.getValue().onCreated(view, new ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Ask service to exit.
        interactionCallbackCapture.getValue().onExit();
        mMainExecutor.runAllReady();

        // Ensure service informs dream host of exit.
        verify(mDreamOverlayCallback).onExitRequested();
    }

    @Test
    public void testListening() throws Exception {
        final DreamOverlayService service = new DreamOverlayService(mContext, mMainExecutor,
                mDreamOverlayStateController, mDreamOverlayStatusBarViewComponentFactory);

        final IBinder proxy = service.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback);
        mMainExecutor.runAllReady();

        // Verify overlay service registered as listener with DreamOverlayStateController
        // and inform callback of addition.
        final ArgumentCaptor<DreamOverlayStateController.Callback> callbackCapture =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);

        verify(mDreamOverlayStateController).addCallback(callbackCapture.capture());
        when(mDreamOverlayStateController.getOverlays()).thenReturn(Arrays.asList(mProvider));
        callbackCapture.getValue().onOverlayChanged();
        mMainExecutor.runAllReady();

        // Verify provider is asked to create overlay.
        verify(mProvider).onCreateOverlay(any(), any(), any());
    }

    @Test
    public void testDreamOverlayStatusBarViewControllerInitialized() throws Exception {
        final DreamOverlayService service = new DreamOverlayService(mContext, mMainExecutor,
                mDreamOverlayStateController, mDreamOverlayStatusBarViewComponentFactory);

        final IBinder proxy = service.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback);
        mMainExecutor.runAllReady();

        verify(mDreamOverlayStatusBarViewController).init();
    }
}
