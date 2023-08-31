/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_DIMMER;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

import com.android.server.wm.SurfaceAnimator.AnimationType;
import com.android.server.testutils.StubTransaction;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 *  atest WmTests:DimmerTests
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class DimmerTests extends WindowTestsBase {

    private static class TestWindowContainer extends WindowContainer<TestWindowContainer> {
        final SurfaceControl mControl = mock(SurfaceControl.class);
        final SurfaceControl.Transaction mPendingTransaction = spy(StubTransaction.class);
        final SurfaceControl.Transaction mSyncTransaction = spy(StubTransaction.class);

        TestWindowContainer(WindowManagerService wm) {
            super(wm);
        }

        @Override
        public SurfaceControl getSurfaceControl() {
            return mControl;
        }

        @Override
        public SurfaceControl.Transaction getSyncTransaction() {
            return mSyncTransaction;
        }

        @Override
        public SurfaceControl.Transaction getPendingTransaction() {
            return mPendingTransaction;
        }
    }

    private static class MockSurfaceBuildingContainer extends WindowContainer<TestWindowContainer> {
        final SurfaceSession mSession = new SurfaceSession();
        final SurfaceControl mHostControl = mock(SurfaceControl.class);
        final SurfaceControl.Transaction mHostTransaction = spy(StubTransaction.class);

        MockSurfaceBuildingContainer(WindowManagerService wm) {
            super(wm);
        }

        class MockSurfaceBuilder extends SurfaceControl.Builder {
            MockSurfaceBuilder(SurfaceSession ss) {
                super(ss);
            }

            @Override
            public SurfaceControl build() {
                SurfaceControl mSc = mock(SurfaceControl.class);
                when(mSc.isValid()).thenReturn(true);
                return mSc;
            }
        }

        @Override
        SurfaceControl.Builder makeChildSurface(WindowContainer child) {
            return new MockSurfaceBuilder(mSession);
        }

        @Override
        public SurfaceControl getSurfaceControl() {
            return mHostControl;
        }

        @Override
        public SurfaceControl.Transaction getSyncTransaction() {
            return mHostTransaction;
        }

        @Override
        public SurfaceControl.Transaction getPendingTransaction() {
            return mHostTransaction;
        }
    }

    private MockSurfaceBuildingContainer mHost;
    private Dimmer mDimmer;
    private SurfaceControl.Transaction mTransaction;
    private Dimmer.SurfaceAnimatorStarter mSurfaceAnimatorStarter;

    private static class SurfaceAnimatorStarterImpl implements Dimmer.SurfaceAnimatorStarter {
        @Override
        public void startAnimation(SurfaceAnimator surfaceAnimator, SurfaceControl.Transaction t,
                AnimationAdapter anim, boolean hidden, @AnimationType int type) {
            surfaceAnimator.mStaticAnimationFinishedCallback.onAnimationFinished(type, anim);
        }
    }

    @Before
    public void setUp() throws Exception {
        mHost = new MockSurfaceBuildingContainer(mWm);
        mSurfaceAnimatorStarter = spy(new SurfaceAnimatorStarterImpl());
        mTransaction = spy(StubTransaction.class);
        mDimmer = new Dimmer(mHost, mSurfaceAnimatorStarter);
    }

    @Test
    public void testUpdateDimsAppliesCrop() {
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(child, alpha);

        int width = 100;
        int height = 300;
        mDimmer.mDimState.mDimBounds.set(0, 0, width, height);
        mDimmer.updateDims(mTransaction);

        verify(mTransaction).setWindowCrop(getDimLayer(), width, height);
        verify(mTransaction).show(getDimLayer());
    }

    @Test
    public void testDimAboveWithChildCreatesSurfaceAboveChild() {
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(child, alpha);
        SurfaceControl dimLayer = getDimLayer();

        assertNotNull("Dimmer should have created a surface", dimLayer);

        verify(mHost.getPendingTransaction()).setAlpha(dimLayer, alpha);
        verify(mHost.getPendingTransaction()).setRelativeLayer(dimLayer, child.mControl, 1);
    }

    @Test
    public void testDimBelowWithChildSurfaceCreatesSurfaceBelowChild() {
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimBelow(child, alpha, 0);
        SurfaceControl dimLayer = getDimLayer();

        assertNotNull("Dimmer should have created a surface", dimLayer);

        verify(mHost.getPendingTransaction()).setAlpha(dimLayer, alpha);
        verify(mHost.getPendingTransaction()).setRelativeLayer(dimLayer, child.mControl, -1);
    }

    @Test
    public void testDimBelowWithChildSurfaceDestroyedWhenReset() {
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(child, alpha);
        SurfaceControl dimLayer = getDimLayer();
        mDimmer.resetDimStates();

        mDimmer.updateDims(mTransaction);
        verify(mSurfaceAnimatorStarter).startAnimation(any(SurfaceAnimator.class), any(
                SurfaceControl.Transaction.class), any(AnimationAdapter.class), anyBoolean(),
                eq(ANIMATION_TYPE_DIMMER));
        verify(mHost.getPendingTransaction()).remove(dimLayer);
    }

    @Test
    public void testDimBelowWithChildSurfaceNotDestroyedWhenPersisted() {
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(child, alpha);
        SurfaceControl dimLayer = getDimLayer();
        mDimmer.resetDimStates();
        mDimmer.dimAbove(child, alpha);

        mDimmer.updateDims(mTransaction);
        verify(mTransaction).show(dimLayer);
        verify(mTransaction, never()).remove(dimLayer);
    }

    @Test
    public void testDimUpdateWhileDimming() {
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(child, alpha);
        final Rect bounds = mDimmer.mDimState.mDimBounds;

        SurfaceControl dimLayer = getDimLayer();
        bounds.set(0, 0, 10, 10);
        mDimmer.updateDims(mTransaction);
        verify(mTransaction).setWindowCrop(dimLayer, bounds.width(), bounds.height());
        verify(mTransaction, times(1)).show(dimLayer);
        verify(mTransaction).setPosition(dimLayer, 0, 0);

        bounds.set(10, 10, 30, 30);
        mDimmer.updateDims(mTransaction);
        verify(mTransaction).setWindowCrop(dimLayer, bounds.width(), bounds.height());
        verify(mTransaction).setPosition(dimLayer, 10, 10);
    }

    @Test
    public void testRemoveDimImmediately() {
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        mDimmer.dimAbove(child, 1);
        SurfaceControl dimLayer = getDimLayer();
        mDimmer.updateDims(mTransaction);
        verify(mTransaction, times(1)).show(dimLayer);

        reset(mSurfaceAnimatorStarter);
        mDimmer.dontAnimateExit();
        mDimmer.resetDimStates();
        mDimmer.updateDims(mTransaction);
        verify(mSurfaceAnimatorStarter, never()).startAnimation(any(SurfaceAnimator.class), any(
                SurfaceControl.Transaction.class), any(AnimationAdapter.class), anyBoolean(),
                eq(ANIMATION_TYPE_DIMMER));
        verify(mTransaction).remove(dimLayer);
    }

    @Test
    public void testDimmerWithBlurUpdatesTransaction() {
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        final int blurRadius = 50;
        mDimmer.dimBelow(child, 0, blurRadius);
        SurfaceControl dimLayer = getDimLayer();

        assertNotNull("Dimmer should have created a surface", dimLayer);

        verify(mHost.getPendingTransaction()).setBackgroundBlurRadius(dimLayer, blurRadius);
        verify(mHost.getPendingTransaction()).setRelativeLayer(dimLayer, child.mControl, -1);
    }

    private SurfaceControl getDimLayer() {
        return mDimmer.mDimState.mDimLayer;
    }
}
