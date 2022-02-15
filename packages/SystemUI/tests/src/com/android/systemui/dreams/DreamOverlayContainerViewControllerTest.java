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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.complication.ComplicationHostViewController;
import com.android.systemui.dreams.complication.dagger.ComplicationHostViewComponent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamOverlayContainerViewControllerTest extends SysuiTestCase {
    private static final int DREAM_OVERLAY_NOTIFICATIONS_DRAG_AREA_HEIGHT = 100;
    private static final int MAX_BURN_IN_OFFSET = 20;
    private static final long BURN_IN_PROTECTION_UPDATE_INTERVAL = 10;

    @Mock
    Resources mResources;

    @Mock
    ViewTreeObserver mViewTreeObserver;

    @Mock
    DreamOverlayStatusBarViewController mDreamOverlayStatusBarViewController;

    @Mock
    DreamOverlayContainerView mDreamOverlayContainerView;

    @Mock
    ComplicationHostViewController mComplicationHostViewController;

    @Mock
    ComplicationHostViewComponent.Factory mComplicationHostViewComponentFactory;

    @Mock
    ComplicationHostViewComponent mComplicationHostViewComponent;

    @Mock
    ViewGroup mDreamOverlayContentView;

    @Mock
    Handler mHandler;

    DreamOverlayContainerViewController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mResources.getDimensionPixelSize(
                R.dimen.dream_overlay_notifications_drag_area_height)).thenReturn(
                        DREAM_OVERLAY_NOTIFICATIONS_DRAG_AREA_HEIGHT);
        when(mDreamOverlayContainerView.getResources()).thenReturn(mResources);
        when(mDreamOverlayContainerView.getViewTreeObserver()).thenReturn(mViewTreeObserver);
        when(mComplicationHostViewComponentFactory.create())
                .thenReturn(mComplicationHostViewComponent);
        when(mComplicationHostViewComponent.getController())
                .thenReturn(mComplicationHostViewController);

        mController = new DreamOverlayContainerViewController(
                mDreamOverlayContainerView,
                mComplicationHostViewComponentFactory,
                mDreamOverlayContentView,
                mDreamOverlayStatusBarViewController,
                mHandler,
                MAX_BURN_IN_OFFSET,
                BURN_IN_PROTECTION_UPDATE_INTERVAL);
    }

    @Test
    public void testDreamOverlayStatusBarViewControllerInitialized() {
        mController.init();
        verify(mDreamOverlayStatusBarViewController).init();
    }

    @Test
    public void testSetsDreamOverlayNotificationsDragAreaHeight() {
        assertEquals(
                mController.getDreamOverlayNotificationsDragAreaHeight(),
                DREAM_OVERLAY_NOTIFICATIONS_DRAG_AREA_HEIGHT);
    }

    @Test
    public void testOnViewAttachedRegistersComputeInsetsListener() {
        mController.onViewAttached();
        verify(mViewTreeObserver).addOnComputeInternalInsetsListener(any());
    }

    @Test
    public void testOnViewDetachedUnregistersComputeInsetsListener() {
        mController.onViewDetached();
        verify(mViewTreeObserver).removeOnComputeInternalInsetsListener(any());
    }

    @Test
    public void testComputeInsetsListenerReturnsRegion() {
        final ArgumentCaptor<ViewTreeObserver.OnComputeInternalInsetsListener>
                computeInsetsListenerCapture =
                ArgumentCaptor.forClass(ViewTreeObserver.OnComputeInternalInsetsListener.class);
        mController.onViewAttached();
        verify(mViewTreeObserver).addOnComputeInternalInsetsListener(
                computeInsetsListenerCapture.capture());
        final ViewTreeObserver.InternalInsetsInfo info = new ViewTreeObserver.InternalInsetsInfo();
        computeInsetsListenerCapture.getValue().onComputeInternalInsets(info);
        assertNotNull(info.touchableRegion);
    }

    @Test
    public void testBurnInProtectionStartsWhenContentViewAttached() {
        mController.onViewAttached();
        verify(mHandler).postDelayed(any(Runnable.class), eq(BURN_IN_PROTECTION_UPDATE_INTERVAL));
    }

    @Test
    public void testBurnInProtectionStopsWhenContentViewDetached() {
        mController.onViewDetached();
        verify(mHandler).removeCallbacks(any(Runnable.class));
    }

    @Test
    public void testBurnInProtectionUpdatesPeriodically() {
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        mController.onViewAttached();
        verify(mHandler).postDelayed(
                runnableCaptor.capture(), eq(BURN_IN_PROTECTION_UPDATE_INTERVAL));
        runnableCaptor.getValue().run();
        verify(mDreamOverlayContainerView).setTranslationX(anyFloat());
        verify(mDreamOverlayContainerView).setTranslationY(anyFloat());
    }

    @Test
    public void testBurnInProtectionReschedulesUpdate() {
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        mController.onViewAttached();
        verify(mHandler).postDelayed(
                runnableCaptor.capture(), eq(BURN_IN_PROTECTION_UPDATE_INTERVAL));
        runnableCaptor.getValue().run();
        verify(mHandler).postDelayed(runnableCaptor.getValue(), BURN_IN_PROTECTION_UPDATE_INTERVAL);
    }
}
