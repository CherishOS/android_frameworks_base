/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.common.split;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.IBinder;
import android.view.IWindow;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;

import androidx.annotation.Nullable;

import com.android.wm.shell.R;

/**
 * Holds view hierarchy of a root surface and helps to inflate {@link DividerView} for a split.
 */
public final class SplitWindowManager extends WindowlessWindowManager {
    private static final String DIVIDER_WINDOW_TITLE = "SplitDivider";

    private Context mContext;
    private SurfaceControlViewHost mViewHost;

    public SplitWindowManager(Context context, Configuration config, SurfaceControl rootSurface) {
        super(config, rootSurface, null /* hostInputToken */);
        mContext = context.createConfigurationContext(config);
    }

    @Override
    public void setTouchRegion(IBinder window, Region region) {
        super.setTouchRegion(window, region);
    }

    @Override
    public SurfaceControl getSurfaceControl(IWindow window) {
        return super.getSurfaceControl(window);
    }

    @Override
    public void setConfiguration(Configuration configuration) {
        super.setConfiguration(configuration);
        mContext = mContext.createConfigurationContext(configuration);
    }

    /** Inflates {@link DividerView} on to the root surface. */
    void init(SplitLayout splitLayout) {
        if (mViewHost == null) {
            mViewHost = new SurfaceControlViewHost(mContext, mContext.getDisplay(), this);
        }

        final Rect dividerBounds = splitLayout.getDividerBounds();
        final DividerView dividerView = (DividerView) LayoutInflater.from(mContext)
                .inflate(R.layout.split_divider, null /* root */);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dividerBounds.width(), dividerBounds.height(), TYPE_DOCK_DIVIDER,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH
                        | FLAG_SPLIT_TOUCH | FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        lp.token = new Binder();
        lp.setTitle(DIVIDER_WINDOW_TITLE);
        lp.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION;
        mViewHost.setView(dividerView, lp);
        dividerView.setup(splitLayout, mViewHost, null /* dragListener */);
    }

    /**
     * Releases the surface control of the current {@link DividerView} and tear down the view
     * hierarchy.
     */
    void release() {
        if (mViewHost == null) return;
        mViewHost.release();
        mViewHost = null;
    }

    /**
     * Gets {@link SurfaceControl} of the surface holding divider view. @return {@code null} if not
     * feasible.
     */
    @Nullable
    SurfaceControl getSurfaceControl() {
        return mViewHost == null ? null : getSurfaceControl(mViewHost.getWindowToken());
    }
}
