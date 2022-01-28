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

package com.android.systemui.dreams;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.systemui.CoreStartable;
import com.android.systemui.dreams.complication.Complication;
import com.android.systemui.dreams.complication.ComplicationLayoutParams;
import com.android.systemui.dreams.complication.ComplicationViewModel;
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController;

import javax.inject.Inject;

/**
 * {@link SmartSpaceComplication} embodies the SmartSpace view found on the lockscreen as a
 * {@link Complication}
 */
public class SmartSpaceComplication implements Complication {
    /**
     * {@link CoreStartable} responsbile for registering {@link SmartSpaceComplication} with
     * SystemUI.
     */
    public static class Registrant extends CoreStartable {
        private final LockscreenSmartspaceController mSmartSpaceController;
        private final DreamOverlayStateController mDreamOverlayStateController;
        private final SmartSpaceComplication mComplication;

        /**
         * Default constructor for {@link SmartSpaceComplication}.
         */
        @Inject
        public Registrant(Context context,
                DreamOverlayStateController dreamOverlayStateController,
                SmartSpaceComplication smartSpaceComplication,
                LockscreenSmartspaceController smartSpaceController) {
            super(context);
            mDreamOverlayStateController = dreamOverlayStateController;
            mComplication = smartSpaceComplication;
            mSmartSpaceController = smartSpaceController;
        }

        @Override
        public void start() {
            if (mSmartSpaceController.isEnabled()) {
                mDreamOverlayStateController.addComplication(mComplication);
            }
        }
    }

    private static class SmartSpaceComplicationViewHolder implements ViewHolder {
        private final LockscreenSmartspaceController mSmartSpaceController;
        private final Context mContext;

        protected SmartSpaceComplicationViewHolder(
                Context context,
                LockscreenSmartspaceController smartSpaceController) {
            mSmartSpaceController = smartSpaceController;
            mContext = context;
        }

        @Override
        public View getView() {
            final FrameLayout smartSpaceContainer = new FrameLayout(mContext);
            smartSpaceContainer.addView(
                    mSmartSpaceController.buildAndConnectView(smartSpaceContainer),
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));

            return smartSpaceContainer;
        }

        @Override
        public ComplicationLayoutParams getLayoutParams() {
            return new ComplicationLayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ComplicationLayoutParams.POSITION_TOP | ComplicationLayoutParams.POSITION_START,
                    ComplicationLayoutParams.DIRECTION_DOWN,
                    0, true);
        }
    }

    private final LockscreenSmartspaceController mSmartSpaceController;
    private final Context mContext;

    @Inject
    public SmartSpaceComplication(Context context,
            LockscreenSmartspaceController smartSpaceController) {
        mContext = context;
        mSmartSpaceController = smartSpaceController;
    }

    @Override
    public ViewHolder createView(ComplicationViewModel model) {
        return new SmartSpaceComplicationViewHolder(mContext, mSmartSpaceController);
    }
}
