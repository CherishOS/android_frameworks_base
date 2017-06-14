/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.Icon;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.statusbar.phone.SignalDrawable;

import java.util.Objects;

// Exists to provide easy way to add sim icon to cell tile
// TODO Find a better way to handle this and remove it.
public class CellTileView extends SignalTileView {

    private final SignalDrawable mSignalDrawable;

    public CellTileView(Context context) {
        super(context);
        mSignalDrawable = new SignalDrawable(mContext);
        mSignalDrawable.setDarkIntensity(isDark(mContext));
        mSignalDrawable.setIntrinsicSize(context.getResources().getDimensionPixelSize(
                R.dimen.qs_tile_icon_size));
    }

    protected void updateIcon(ImageView iv, State state) {
        if (!Objects.equals(state.icon, iv.getTag(R.id.qs_icon_tag))) {
            mSignalDrawable.setLevel(((SignalIcon) state.icon).getState());
            iv.setImageDrawable(mSignalDrawable);
            iv.setTag(R.id.qs_icon_tag, state.icon);
        }
    }

    private static int isDark(Context context) {
        return Utils.getColorAttr(context, android.R.attr.colorForeground) == 0xff000000 ? 1 : 0;
    }

    public static class SignalIcon extends Icon {

        private final int mState;

        public SignalIcon(int state) {
            mState = state;
        }

        public int getState() {
            return mState;
        }

        @Override
        public Drawable getDrawable(Context context) {
            //TODO: Not the optimal solution to create this drawable
            SignalDrawable d = new SignalDrawable(context);
            d.setDarkIntensity(isDark(context));
            d.setLevel(getState());
            return d;
        }
    }
}
