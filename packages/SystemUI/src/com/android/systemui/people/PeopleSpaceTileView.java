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

package com.android.systemui.people;

import android.app.people.PeopleSpaceTile;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * PeopleSpaceTileView renders an individual person's tile with associated status.
 */
public class PeopleSpaceTileView extends LinearLayout {

    private View mTileView;
    private TextView mNameView;
    private TextView mStatusView;
    private ImageView mPackageIconView;
    private ImageView mPersonIconView;

    public PeopleSpaceTileView(Context context, ViewGroup view, String shortcutId) {
        super(context);
        mTileView = view.findViewWithTag(shortcutId);
        if (mTileView == null) {
            LayoutInflater inflater = LayoutInflater.from(context);
            mTileView = inflater.inflate(R.layout.people_space_tile_view, view, false);
            view.addView(mTileView, LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT);
            mTileView.setTag(shortcutId);
        }
        mNameView = mTileView.findViewById(R.id.tile_view_name);
        mStatusView = mTileView.findViewById(R.id.tile_view_status);
        mPackageIconView = mTileView.findViewById(R.id.tile_view_package_icon);
        mPersonIconView = mTileView.findViewById(R.id.tile_view_person_icon);
    }

    /** Sets the name text on the tile. */
    public void setName(String name) {
        mNameView.setText(name);
    }

    /** Sets the status text on the tile. */
    public void setStatus(String status) {
        mStatusView.setText(status);
    }

    /** Sets the package drawable on the tile. */
    public void setPackageIcon(Drawable drawable) {
        mPackageIconView.setImageDrawable(drawable);
    }

    /** Sets the person bitmap on the tile. */
    public void setPersonIcon(Icon icon) {
        mPersonIconView.setImageIcon(icon);
    }

    /** Sets the click listener of the tile. */
    public void setOnClickListener(LauncherApps launcherApps, PeopleSpaceTile tile) {
        mTileView.setOnClickListener(v ->
                launcherApps.startShortcut(tile.getPackageName(), tile.getId(), null, null,
                        UserHandle.getUserHandleForUid(tile.getUid())));
    }
}
