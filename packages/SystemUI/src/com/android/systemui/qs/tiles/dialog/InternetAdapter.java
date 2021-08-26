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

package com.android.systemui.qs.tiles.dialog;

import static com.android.wifitrackerlib.WifiEntry.SECURITY_NONE;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_OWE;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.Utils;
import com.android.settingslib.wifi.WifiUtils;
import com.android.systemui.R;
import com.android.wifitrackerlib.WifiEntry;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapter for showing Wi-Fi networks.
 */
public class InternetAdapter extends RecyclerView.Adapter<InternetAdapter.InternetViewHolder> {

    private static final String TAG = "InternetAdapter";
    private static final String ACTION_WIFI_DIALOG = "com.android.settings.WIFI_DIALOG";
    private static final String EXTRA_CHOSEN_WIFI_ENTRY_KEY = "key_chosen_wifientry_key";
    private static final String EXTRA_CONNECT_FOR_CALLER = "connect_for_caller";

    private final InternetDialogController mInternetDialogController;
    private List<WifiEntry> mWifiEntries;
    private int mWifiEntriesCount;

    protected View mHolderView;
    protected Context mContext;

    public InternetAdapter(InternetDialogController controller) {
        mInternetDialogController = controller;
    }

    @Override
    public InternetViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
            int viewType) {
        mContext = viewGroup.getContext();
        mHolderView = LayoutInflater.from(mContext).inflate(R.layout.internet_list_item,
                viewGroup, false);
        return new InternetViewHolder(mHolderView, mInternetDialogController);
    }

    @Override
    public void onBindViewHolder(@NonNull InternetViewHolder viewHolder, int position) {
        if (mWifiEntries == null || position >= mWifiEntriesCount) {
            return;
        }
        viewHolder.onBind(mWifiEntries.get(position));
    }

    /**
     * Updates the Wi-Fi networks.
     *
     * @param wifiEntries the updated Wi-Fi entries.
     * @param wifiEntriesCount the total number of Wi-Fi entries.
     */
    public void setWifiEntries(@Nullable List<WifiEntry> wifiEntries, int wifiEntriesCount) {
        mWifiEntries = wifiEntries;
        mWifiEntriesCount = wifiEntriesCount;
    }

    /**
     * Gets the total number of Wi-Fi networks.
     *
     * @return The total number of Wi-Fi entries.
     */
    @Override
    public int getItemCount() {
        return mWifiEntriesCount;
    }

    /**
     * ViewHolder for binding Wi-Fi view.
     */
    static class InternetViewHolder extends RecyclerView.ViewHolder {

        final LinearLayout mContainerLayout;
        final LinearLayout mWifiListLayout;
        final LinearLayout mWifiNetworkLayout;
        final ImageView mWifiIcon;
        final TextView mWifiTitleText;
        final TextView mWifiSummaryText;
        final ImageView mWifiLockedIcon;
        final Context mContext;
        final InternetDialogController mInternetDialogController;

        protected WifiUtils.InternetIconInjector mWifiIconInjector;

        InternetViewHolder(View view, InternetDialogController internetDialogController) {
            super(view);
            mContext = view.getContext();
            mInternetDialogController = internetDialogController;
            mContainerLayout = view.requireViewById(R.id.internet_container);
            mWifiListLayout = view.requireViewById(R.id.wifi_list);
            mWifiNetworkLayout = view.requireViewById(R.id.wifi_network_layout);
            mWifiIcon = view.requireViewById(R.id.wifi_icon);
            mWifiTitleText = view.requireViewById(R.id.wifi_title);
            mWifiSummaryText = view.requireViewById(R.id.wifi_summary);
            mWifiLockedIcon = view.requireViewById(R.id.wifi_locked_icon);
            mWifiIconInjector = mInternetDialogController.getWifiIconInjector();
        }

        void onBind(WifiEntry wifiEntry) {
            int security = wifiEntry.getSecurity();
            try {
                mWifiIcon.setImageDrawable(getWifiDrawable(wifiEntry));
                if (isOpenNetwork(security)) {
                    mWifiLockedIcon.setVisibility(View.GONE);
                } else {
                    mWifiLockedIcon.setVisibility(View.VISIBLE);
                    mWifiLockedIcon.setImageDrawable(
                            mContext.getDrawable(R.drawable.ic_friction_lock_closed));
                }
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }

            setWifiNetworkLayout(wifiEntry.getTitle(),
                    Html.fromHtml(wifiEntry.getSummary(false), Html.FROM_HTML_MODE_LEGACY));

            mWifiListLayout.setOnClickListener(v -> {
                if (wifiEntry.shouldEditBeforeConnect()) {
                    final Intent intent = new Intent(ACTION_WIFI_DIALOG);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    intent.putExtra(EXTRA_CHOSEN_WIFI_ENTRY_KEY, wifiEntry.getKey());
                    intent.putExtra(EXTRA_CONNECT_FOR_CALLER, false);
                    mContext.startActivity(intent);
                }
                mInternetDialogController.connect(wifiEntry);
            });
        }

        /** Return true if this is an open network AccessPoint. */
        boolean isOpenNetwork(int security) {
            return security == SECURITY_NONE
                    || security == SECURITY_OWE;
        }

        void setWifiNetworkLayout(CharSequence title, CharSequence summary) {
            mWifiNetworkLayout.setVisibility(View.VISIBLE);
            mWifiTitleText.setText(title);
            if (TextUtils.isEmpty(summary)) {
                mWifiSummaryText.setVisibility(View.GONE);
                return;
            } else {
                mWifiSummaryText.setVisibility(View.VISIBLE);
            }
            mWifiSummaryText.setText(summary);
        }

        Drawable getWifiDrawable(@NonNull WifiEntry wifiEntry) throws Throwable {
            if (wifiEntry.getLevel() == WifiEntry.WIFI_LEVEL_UNREACHABLE) {
                return null;
            }
            final Drawable drawable = mWifiIconInjector.getIcon(wifiEntry.shouldShowXLevelIcon(),
                    wifiEntry.getLevel());
            if (drawable == null) {
                return null;
            }
            drawable.setTint(
                    Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorTertiary));
            final AtomicReference<Drawable> shared = new AtomicReference<>();
            shared.set(drawable);
            return shared.get();
        }
    }
}
