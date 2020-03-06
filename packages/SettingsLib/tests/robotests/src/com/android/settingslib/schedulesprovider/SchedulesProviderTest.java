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
package com.android.settingslib.schedulesprovider;

import static com.google.common.truth.Truth.assertThat;

import static org.robolectric.Shadows.shadowOf;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
public class SchedulesProviderTest {
    private static final String INVALID_PACKAGE = "com.android.sunny";
    private static final String VALID_PACKAGE = "com.android.settings";
    private static final String INVALID_METHOD = "queryTestData";

    private final Context mContext = RuntimeEnvironment.application;

    private TestSchedulesProvider mProvider;

    @Before
    public void setUp() {
        mProvider = Robolectric.setupContentProvider(TestSchedulesProvider.class);
        shadowOf(mProvider).setCallingPackage(VALID_PACKAGE);
        mProvider.setScheduleInfos(TestSchedulesProvider.createOneValidScheduleInfo(mContext));
    }

    @Test
    public void call_invalidCallingPkg_returnNull() {
        shadowOf(mProvider).setCallingPackage(INVALID_PACKAGE);

        final Bundle bundle = mProvider.call(SchedulesProvider.METHOD_GENERATE_SCHEDULE_INFO_LIST,
                null /* arg */, null /* extras */);

        assertThat(bundle).isNull();
    }

    @Test
    public void call_invalidMethod_returnBundleWithoutScheduleInfoData() {
        final Bundle bundle = mProvider.call(INVALID_METHOD, null /* arg */, null /* extras */);

        assertThat(bundle).isNotNull();
        assertThat(bundle.containsKey(SchedulesProvider.BUNDLE_SCHEDULE_INFO_LIST)).isFalse();
    }

    @Test
    public void call_validMethod_returnScheduleInfoData() {
        final Bundle bundle = mProvider.call(SchedulesProvider.METHOD_GENERATE_SCHEDULE_INFO_LIST,
                null /* arg */, null /* extras */);

        assertThat(bundle).isNotNull();
        assertThat(bundle.containsKey(SchedulesProvider.BUNDLE_SCHEDULE_INFO_LIST)).isTrue();
        final ArrayList<ScheduleInfo> infos = bundle.getParcelableArrayList(
                SchedulesProvider.BUNDLE_SCHEDULE_INFO_LIST);
        assertThat(infos).hasSize(1);
    }

    @Test
    public void call_addTwoValidData_returnScheduleInfoData() {
        mProvider.setScheduleInfos(TestSchedulesProvider.createTwoValidScheduleInfos(mContext));
        final Bundle bundle = mProvider.call(SchedulesProvider.METHOD_GENERATE_SCHEDULE_INFO_LIST,
                null /* arg */, null /* extras */);

        assertThat(bundle).isNotNull();
        assertThat(bundle.containsKey(SchedulesProvider.BUNDLE_SCHEDULE_INFO_LIST)).isTrue();
        final ArrayList<ScheduleInfo> infos = bundle.getParcelableArrayList(
                SchedulesProvider.BUNDLE_SCHEDULE_INFO_LIST);
        assertThat(infos).hasSize(2);
    }

    @Test
    public void call_addTwoValidDataAndOneInvalidData_returnTwoScheduleInfoData() {
        mProvider.setScheduleInfos(
                TestSchedulesProvider.createTwoValidAndOneInvalidScheduleInfo(mContext));
        final Bundle bundle = mProvider.call(SchedulesProvider.METHOD_GENERATE_SCHEDULE_INFO_LIST,
                null /* arg */, null /* extras */);

        assertThat(bundle).isNotNull();
        assertThat(bundle.containsKey(SchedulesProvider.BUNDLE_SCHEDULE_INFO_LIST)).isTrue();
        final ArrayList<ScheduleInfo> infos = bundle.getParcelableArrayList(
                SchedulesProvider.BUNDLE_SCHEDULE_INFO_LIST);
        assertThat(infos).hasSize(2);
    }

    private static class TestSchedulesProvider extends SchedulesProvider {
        private ArrayList<ScheduleInfo> mScheduleInfos = new ArrayList<>();

        @Override
        public ArrayList<ScheduleInfo> getScheduleInfoList() {
            return mScheduleInfos;
        }

        void setScheduleInfos(ArrayList<ScheduleInfo> scheduleInfos) {
            mScheduleInfos = scheduleInfos;
        }

        private static ArrayList<ScheduleInfo> createOneValidScheduleInfo(Context context) {
            final ArrayList<ScheduleInfo> scheduleInfos = new ArrayList<>();

            final ScheduleInfo info = new ScheduleInfo.Builder().setTitle("Night Light").setSummary(
                    "This a sunny test").setPendingIntent(createTestPendingIntent(context,
                    "android.settings.NIGHT_DISPLAY_SETTINGS")).build();
            scheduleInfos.add(info);

            return scheduleInfos;
        }

        private static ArrayList<ScheduleInfo> createTwoValidScheduleInfos(Context context) {
            final ArrayList<ScheduleInfo> scheduleInfos = new ArrayList<>();
            ScheduleInfo info = new ScheduleInfo.Builder().setTitle("Night Light").setSummary(
                    "This a sunny test").setPendingIntent(createTestPendingIntent(context,
                    "android.settings.NIGHT_DISPLAY_SETTINGS")).build();
            scheduleInfos.add(info);

            info = new ScheduleInfo.Builder().setTitle("Display").setSummary(
                    "Display summary").setPendingIntent(
                    createTestPendingIntent(context, "android.settings.DISPLAY_SETTINGS")).build();
            scheduleInfos.add(info);

            return scheduleInfos;
        }

        private static ArrayList<ScheduleInfo> createTwoValidAndOneInvalidScheduleInfo(
                Context context) {
            final ArrayList<ScheduleInfo> scheduleInfos = new ArrayList<>();
            ScheduleInfo info = new ScheduleInfo.Builder().setTitle("Night Light").setSummary(
                    "This a sunny test").setPendingIntent(createTestPendingIntent(context,
                    "android.settings.NIGHT_DISPLAY_SETTINGS")).build();
            scheduleInfos.add(info);

            info = new ScheduleInfo.Builder().setTitle("Display").setSummary(
                    "Display summary").setPendingIntent(
                    createTestPendingIntent(context, "android.settings.DISPLAY_SETTINGS")).build();
            scheduleInfos.add(info);

            info = new ScheduleInfo.Builder().setTitle("").setSummary(
                    "Display summary").setPendingIntent(
                    createTestPendingIntent(context, "android.settings.DISPLAY_SETTINGS")).build();
            scheduleInfos.add(info);

            return scheduleInfos;
        }

        private static PendingIntent createTestPendingIntent(Context context, String action) {
            final Intent intent = new Intent(action).addCategory(Intent.CATEGORY_DEFAULT);
            return PendingIntent.getActivity(context, 0 /* requestCode */, intent, 0 /* flags */);
        }
    }
}
