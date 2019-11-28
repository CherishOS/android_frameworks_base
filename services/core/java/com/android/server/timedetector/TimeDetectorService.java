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

package com.android.server.timedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.timedetector.ITimeDetectorService;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.PhoneTimeSuggestion;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.SystemService;
import com.android.server.timedetector.TimeDetectorStrategy.Callback;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

public final class TimeDetectorService extends ITimeDetectorService.Stub {
    private static final String TAG = "TimeDetectorService";

    public static class Lifecycle extends SystemService {

        public Lifecycle(@NonNull Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            TimeDetectorService service = TimeDetectorService.create(getContext());

            // Publish the binder service so it can be accessed from other (appropriately
            // permissioned) processes.
            publishBinderService(Context.TIME_DETECTOR_SERVICE, service);
        }
    }

    @NonNull private final Handler mHandler;
    @NonNull private final Context mContext;
    @NonNull private final Callback mCallback;
    @NonNull private final TimeDetectorStrategy mTimeDetectorStrategy;

    private static TimeDetectorService create(@NonNull Context context) {
        TimeDetectorStrategy timeDetector = new SimpleTimeDetectorStrategy();
        TimeDetectorStrategyCallbackImpl callback = new TimeDetectorStrategyCallbackImpl(context);
        timeDetector.initialize(callback);

        Handler handler = FgThread.getHandler();
        TimeDetectorService timeDetectorService =
                new TimeDetectorService(context, handler, callback, timeDetector);

        // Wire up event listening.
        ContentResolver contentResolver = context.getContentResolver();
        contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AUTO_TIME), true,
                new ContentObserver(handler) {
                    public void onChange(boolean selfChange) {
                        timeDetectorService.handleAutoTimeDetectionToggle();
                    }
                });

        return timeDetectorService;
    }

    @VisibleForTesting
    public TimeDetectorService(@NonNull Context context, @NonNull Handler handler,
            @NonNull Callback callback, @NonNull TimeDetectorStrategy timeDetectorStrategy) {
        mContext = Objects.requireNonNull(context);
        mHandler = Objects.requireNonNull(handler);
        mCallback = Objects.requireNonNull(callback);
        mTimeDetectorStrategy = Objects.requireNonNull(timeDetectorStrategy);
    }

    @Override
    public void suggestPhoneTime(@NonNull PhoneTimeSuggestion timeSignal) {
        enforceSuggestPhoneTimePermission();
        Objects.requireNonNull(timeSignal);

        mHandler.post(() -> mTimeDetectorStrategy.suggestPhoneTime(timeSignal));
    }

    @Override
    public void suggestManualTime(@NonNull ManualTimeSuggestion timeSignal) {
        enforceSuggestManualTimePermission();
        Objects.requireNonNull(timeSignal);

        mHandler.post(() -> mTimeDetectorStrategy.suggestManualTime(timeSignal));
    }

    @VisibleForTesting
    public void handleAutoTimeDetectionToggle() {
        mHandler.post(mTimeDetectorStrategy::handleAutoTimeDetectionChanged);
    }

    @Override
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        mTimeDetectorStrategy.dump(pw, args);
    }

    private void enforceSuggestPhoneTimePermission() {
        mContext.enforceCallingPermission(android.Manifest.permission.SET_TIME, "set time");
    }

    private void enforceSuggestManualTimePermission() {
        mContext.enforceCallingPermission(android.Manifest.permission.SET_TIME, "set time");
    }
}
