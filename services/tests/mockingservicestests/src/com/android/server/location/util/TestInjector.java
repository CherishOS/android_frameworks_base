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

package com.android.server.location.util;

public class TestInjector implements Injector {

    private final LocationEventLog mLocationEventLog;
    private final FakeUserInfoHelper mUserInfoHelper;
    private final FakeAlarmHelper mAlarmHelper;
    private final FakeAppOpsHelper mAppOpsHelper;
    private final FakeLocationPermissionsHelper mLocationPermissionsHelper;
    private final FakeSettingsHelper mSettingsHelper;
    private final FakeAppForegroundHelper mAppForegroundHelper;
    private final FakeLocationPowerSaveModeHelper mLocationPowerSaveModeHelper;
    private final FakeScreenInteractiveHelper mScreenInteractiveHelper;
    private final LocationAttributionHelper mLocationAttributionHelper;
    private final LocationUsageLogger mLocationUsageLogger;

    public TestInjector() {
        mLocationEventLog = new LocationEventLog();
        mUserInfoHelper = new FakeUserInfoHelper();
        mAlarmHelper = new FakeAlarmHelper();
        mAppOpsHelper = new FakeAppOpsHelper();
        mLocationPermissionsHelper = new FakeLocationPermissionsHelper(mAppOpsHelper);
        mSettingsHelper = new FakeSettingsHelper();
        mAppForegroundHelper = new FakeAppForegroundHelper();
        mLocationPowerSaveModeHelper = new FakeLocationPowerSaveModeHelper(mLocationEventLog);
        mScreenInteractiveHelper = new FakeScreenInteractiveHelper();
        mLocationAttributionHelper = new LocationAttributionHelper(mAppOpsHelper);
        mLocationUsageLogger = new LocationUsageLogger();
    }

    @Override
    public FakeUserInfoHelper getUserInfoHelper() {
        return mUserInfoHelper;
    }

    @Override
    public FakeAlarmHelper getAlarmHelper() {
        return mAlarmHelper;
    }

    @Override
    public FakeAppOpsHelper getAppOpsHelper() {
        return mAppOpsHelper;
    }

    @Override
    public FakeLocationPermissionsHelper getLocationPermissionsHelper() {
        return mLocationPermissionsHelper;
    }

    @Override
    public FakeSettingsHelper getSettingsHelper() {
        return mSettingsHelper;
    }

    @Override
    public FakeAppForegroundHelper getAppForegroundHelper() {
        return mAppForegroundHelper;
    }

    @Override
    public FakeLocationPowerSaveModeHelper getLocationPowerSaveModeHelper() {
        return mLocationPowerSaveModeHelper;
    }

    @Override
    public FakeScreenInteractiveHelper getScreenInteractiveHelper() {
        return mScreenInteractiveHelper;
    }

    @Override
    public LocationAttributionHelper getLocationAttributionHelper() {
        return mLocationAttributionHelper;
    }

    @Override
    public LocationUsageLogger getLocationUsageLogger() {
        return mLocationUsageLogger;
    }

    @Override
    public LocationEventLog getLocationEventLog() {
        return mLocationEventLog;
    }
}
