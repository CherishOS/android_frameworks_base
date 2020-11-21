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

package com.android.frameworks.core.powerstatsviewer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.loader.app.LoaderManager;
import androidx.loader.app.LoaderManager.LoaderCallbacks;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.os.BatteryStatsHelper;
import com.android.settingslib.utils.AsyncLoaderCompat;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class PowerStatsViewerActivity extends ComponentActivity {
    private static final int POWER_STATS_REFRESH_RATE_MILLIS = 60 * 1000;
    public static final String PREF_SELECTED_POWER_CONSUMER = "powerConsumerId";
    private static final String LOADER_ARG_POWER_CONSUMER_ID = "powerConsumerId";

    private PowerStatsDataAdapter mPowerStatsDataAdapter;
    private Runnable mPowerStatsRefresh = this::periodicPowerStatsRefresh;
    private SharedPreferences mSharedPref;
    private String mPowerConsumerId;
    private TextView mTitleView;
    private TextView mDetailsView;
    private ImageView mIconView;
    private TextView mPackagesView;
    private RecyclerView mPowerStatsDataView;
    private View mLoadingView;
    private View mEmptyView;
    private ActivityResultLauncher<Void> mStartAppPicker = registerForActivityResult(
            PowerConsumerPickerActivity.CONTRACT, this::onApplicationSelected);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mSharedPref = getPreferences(Context.MODE_PRIVATE);

        setContentView(R.layout.power_stats_viewer_layout);

        View appCard = findViewById(R.id.app_card);
        appCard.setOnClickListener((e) -> startAppPicker());

        mTitleView = findViewById(android.R.id.title);
        mDetailsView = findViewById(R.id.details);
        mIconView = findViewById(android.R.id.icon);
        mPackagesView = findViewById(R.id.packages);

        mPowerStatsDataView = findViewById(R.id.power_stats_data_view);
        mPowerStatsDataView.setLayoutManager(new LinearLayoutManager(this));
        mPowerStatsDataAdapter = new PowerStatsDataAdapter();
        mPowerStatsDataView.setAdapter(mPowerStatsDataAdapter);

        mLoadingView = findViewById(R.id.loading_view);
        mEmptyView = findViewById(R.id.empty_view);

        mPowerConsumerId = mSharedPref.getString(PREF_SELECTED_POWER_CONSUMER, null);
        loadPowerStats();
        if (mPowerConsumerId == null) {
            startAppPicker();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        periodicPowerStatsRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getMainThreadHandler().removeCallbacks(mPowerStatsRefresh);
    }

    private void startAppPicker() {
        mStartAppPicker.launch(null);
    }

    private void onApplicationSelected(String powerConsumerId) {
        if (powerConsumerId == null) {
            if (mPowerConsumerId == null) {
                finish();
            }
        } else {
            mPowerConsumerId = powerConsumerId;
            mSharedPref.edit().putString(PREF_SELECTED_POWER_CONSUMER, mPowerConsumerId).apply();
            mLoadingView.setVisibility(View.VISIBLE);
            loadPowerStats();
        }
    }

    private void periodicPowerStatsRefresh() {
        loadPowerStats();
        getMainThreadHandler().postDelayed(mPowerStatsRefresh, POWER_STATS_REFRESH_RATE_MILLIS);
    }

    private void loadPowerStats() {
        Bundle args = new Bundle();
        args.putString(LOADER_ARG_POWER_CONSUMER_ID, mPowerConsumerId);
        LoaderManager.getInstance(this).restartLoader(0, args, new PowerStatsDataLoaderCallbacks());
    }

    private static class PowerStatsDataLoader extends AsyncLoaderCompat<PowerStatsData> {
        private final String mPowerConsumerId;
        private final BatteryStatsHelper mBatteryStatsHelper;
        private final UserManager mUserManager;

        PowerStatsDataLoader(Context context, String powerConsumerId) {
            super(context);
            mPowerConsumerId = powerConsumerId;
            mUserManager = context.getSystemService(UserManager.class);
            mBatteryStatsHelper = new BatteryStatsHelper(context,
                    false /* collectBatteryBroadcast */);
            mBatteryStatsHelper.create((Bundle) null);
            mBatteryStatsHelper.clearStats();
        }

        @Override
        public PowerStatsData loadInBackground() {
            mBatteryStatsHelper.refreshStats(BatteryStats.STATS_SINCE_CHARGED,
                    UserHandle.myUserId());
            return new PowerStatsData(getContext(), mBatteryStatsHelper, mPowerConsumerId);
        }

        @Override
        protected void onDiscardResult(PowerStatsData result) {
        }
    }

    private class PowerStatsDataLoaderCallbacks implements LoaderCallbacks<PowerStatsData> {
        @NonNull
        @Override
        public Loader<PowerStatsData> onCreateLoader(int id, Bundle args) {
            return new PowerStatsDataLoader(PowerStatsViewerActivity.this,
                    args.getString(LOADER_ARG_POWER_CONSUMER_ID));
        }

        @Override
        public void onLoadFinished(@NonNull Loader<PowerStatsData> loader,
                PowerStatsData powerStatsData) {

            PowerConsumerInfoHelper.PowerConsumerInfo
                    powerConsumerInfo = powerStatsData.getPowerConsumerInfo();
            if (powerConsumerInfo == null) {
                mTitleView.setText("Power consumer not found");
                mPackagesView.setVisibility(View.GONE);
            } else {
                mTitleView.setText(powerConsumerInfo.label);
                if (powerConsumerInfo.details != null) {
                    mDetailsView.setText(powerConsumerInfo.details);
                    mDetailsView.setVisibility(View.VISIBLE);
                } else {
                    mDetailsView.setVisibility(View.GONE);
                }
                mIconView.setImageDrawable(
                        powerConsumerInfo.iconInfo.loadIcon(getPackageManager()));

                if (powerConsumerInfo.packages != null) {
                    mPackagesView.setText(powerConsumerInfo.packages);
                    mPackagesView.setVisibility(View.VISIBLE);
                } else {
                    mPackagesView.setVisibility(View.GONE);
                }
            }

            mPowerStatsDataAdapter.setEntries(powerStatsData.getEntries());
            if (powerStatsData.getEntries().isEmpty()) {
                mEmptyView.setVisibility(View.VISIBLE);
                mPowerStatsDataView.setVisibility(View.GONE);
            } else {
                mEmptyView.setVisibility(View.GONE);
                mPowerStatsDataView.setVisibility(View.VISIBLE);
            }

            mLoadingView.setVisibility(View.GONE);
        }

        @Override
        public void onLoaderReset(@NonNull Loader<PowerStatsData> loader) {
        }
    }

    private static class PowerStatsDataAdapter extends
            RecyclerView.Adapter<PowerStatsDataAdapter.ViewHolder> {
        public static class ViewHolder extends RecyclerView.ViewHolder {
            public TextView titleTextView;
            public TextView amountTextView;
            public TextView percentTextView;

            ViewHolder(View itemView) {
                super(itemView);

                titleTextView = itemView.findViewById(R.id.title);
                amountTextView = itemView.findViewById(R.id.amount);
                percentTextView = itemView.findViewById(R.id.percent);
            }
        }

        private List<PowerStatsData.Entry> mEntries = Collections.emptyList();

        public void setEntries(List<PowerStatsData.Entry> entries) {
            mEntries = entries;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mEntries.size();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int position) {
            LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
            View itemView = layoutInflater.inflate(R.layout.power_stats_entry_layout, parent,
                    false);
            return new ViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder viewHolder, int position) {
            PowerStatsData.Entry entry = mEntries.get(position);
            switch (entry.entryType) {
                case POWER:
                    viewHolder.titleTextView.setText(entry.title);
                    viewHolder.amountTextView.setText(
                            String.format(Locale.getDefault(), "%.1f mAh", entry.value));
                    break;
                case DURATION:
                    viewHolder.titleTextView.setText(entry.title);
                    viewHolder.amountTextView.setText(
                            String.format(Locale.getDefault(), "%,d ms", (long) entry.value));
                    break;
            }

            double proportion = entry.total != 0 ? entry.value * 100 / entry.total : 0;
            viewHolder.percentTextView.setText(String.format(Locale.getDefault(), "%.1f%%",
                    proportion));
        }
    }
}
