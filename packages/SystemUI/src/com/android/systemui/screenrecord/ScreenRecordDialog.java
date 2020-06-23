/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC_AND_INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.NONE;

import android.app.Activity;
import android.app.PendingIntent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Activity to select screen recording options
 */
public class ScreenRecordDialog extends Activity {
    private static final long DELAY_MS = 3000;
    private static final long INTERVAL_MS = 1000;
    private static final String TAG = "ScreenRecordDialog";

    private final RecordingController mController;
    private Switch mTapsSwitch;
    private Switch mAudioSwitch;
    private Spinner mOptions;
    private List<ScreenRecordingAudioSource> mModes;
    private int mSelected;


    @Inject
    public ScreenRecordDialog(RecordingController controller) {
        mController = controller;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        // Inflate the decor view, so the attributes below are not overwritten by the theme.
        window.getDecorView();
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.TOP);
        setTitle(R.string.screenrecord_name);

        setContentView(R.layout.screen_record_dialog);

        Button cancelBtn = findViewById(R.id.button_cancel);
        cancelBtn.setOnClickListener(v -> {
            finish();
        });

        Button startBtn = findViewById(R.id.button_start);
        startBtn.setOnClickListener(v -> {
            requestScreenCapture();
            finish();
        });

        mModes = new ArrayList<>();
        mModes.add(MIC);
        mModes.add(INTERNAL);
        mModes.add(MIC_AND_INTERNAL);

        mAudioSwitch = findViewById(R.id.screenrecord_audio_switch);
        mTapsSwitch = findViewById(R.id.screenrecord_taps_switch);
        mOptions = findViewById(R.id.screen_recording_options);
        ArrayAdapter a = new ScreenRecordingAdapter(getApplicationContext(),
                android.R.layout.simple_spinner_dropdown_item,
                mModes);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mOptions.setAdapter(a);
        mOptions.setOnItemClickListenerInt((parent, view, position, id) -> {
            mAudioSwitch.setChecked(true);
        });

    }

    private void requestScreenCapture() {
        boolean showTaps = mTapsSwitch.isChecked();
        ScreenRecordingAudioSource audioMode = mAudioSwitch.isChecked()
                ? (ScreenRecordingAudioSource) mOptions.getSelectedItem()
                : NONE;
        PendingIntent startIntent = PendingIntent.getForegroundService(this,
                RecordingService.REQUEST_CODE,
                RecordingService.getStartIntent(
                        ScreenRecordDialog.this, RESULT_OK,
                        audioMode.ordinal(), showTaps),
                PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopIntent = PendingIntent.getService(this,
                RecordingService.REQUEST_CODE,
                RecordingService.getStopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT);
        mController.startCountdown(DELAY_MS, INTERVAL_MS, startIntent, stopIntent);
    }
}
