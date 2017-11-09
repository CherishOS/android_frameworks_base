/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.usage;

import android.app.usage.AppStandby;
import android.os.FileUtils;
import android.test.AndroidTestCase;

import java.io.File;

import static android.app.usage.AppStandby.*;

public class AppIdleHistoryTests extends AndroidTestCase {

    File mStorageDir;

    final static String PACKAGE_1 = "com.android.testpackage1";
    final static String PACKAGE_2 = "com.android.testpackage2";

    final static int USER_ID = 0;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mStorageDir = new File(getContext().getFilesDir(), "appidle");
        mStorageDir.mkdirs();
    }

    @Override
    protected void tearDown() throws Exception {
        FileUtils.deleteContents(mStorageDir);
        super.tearDown();
    }

    public void testFilesCreation() {
        AppIdleHistory aih = new AppIdleHistory(mStorageDir, 0);

        aih.updateDisplay(true, /* elapsedRealtime= */ 1000);
        aih.updateDisplay(false, /* elapsedRealtime= */ 2000);
        // Screen On time file should be written right away
        assertTrue(aih.getScreenOnTimeFile().exists());

        aih.writeAppIdleTimes(USER_ID);
        // stats file should be written now
        assertTrue(new File(new File(mStorageDir, "users/" + USER_ID),
                AppIdleHistory.APP_IDLE_FILENAME).exists());
    }

    public void testScreenOnTime() {
        AppIdleHistory aih = new AppIdleHistory(mStorageDir, 1000);
        aih.updateDisplay(false, 2000);
        assertEquals(aih.getScreenOnTime(2000), 0);
        aih.updateDisplay(true, 3000);
        assertEquals(aih.getScreenOnTime(4000), 1000);
        assertEquals(aih.getScreenOnTime(5000), 2000);
        aih.updateDisplay(false, 6000);
        // Screen on time should not keep progressing with screen is off
        assertEquals(aih.getScreenOnTime(7000), 3000);
        assertEquals(aih.getScreenOnTime(8000), 3000);
        aih.writeAppIdleDurations();

        // Check if the screen on time is persisted across instantiations
        AppIdleHistory aih2 = new AppIdleHistory(mStorageDir, 0);
        assertEquals(aih2.getScreenOnTime(11000), 3000);
        aih2.updateDisplay(true, 4000);
        aih2.updateDisplay(false, 5000);
        assertEquals(aih2.getScreenOnTime(13000), 4000);
    }

    public void testBuckets() {
        AppIdleHistory aih = new AppIdleHistory(mStorageDir, 1000);

        aih.setAppStandbyBucket(PACKAGE_1, USER_ID, 1000, STANDBY_BUCKET_ACTIVE,
                AppStandby.REASON_USAGE);
        // ACTIVE means not idle
        assertFalse(aih.isIdle(PACKAGE_1, USER_ID, 2000));

        aih.setAppStandbyBucket(PACKAGE_2, USER_ID, 2000, STANDBY_BUCKET_ACTIVE,
                AppStandby.REASON_USAGE);
        aih.setAppStandbyBucket(PACKAGE_1, USER_ID, 3000, STANDBY_BUCKET_RARE,
                REASON_TIMEOUT);

        assertEquals(aih.getAppStandbyBucket(PACKAGE_1, USER_ID, 3000), STANDBY_BUCKET_RARE);
        assertEquals(aih.getAppStandbyBucket(PACKAGE_2, USER_ID, 3000), STANDBY_BUCKET_ACTIVE);
        assertEquals(aih.getAppStandbyReason(PACKAGE_1, USER_ID, 3000), REASON_TIMEOUT);

        // RARE is considered idle
        assertTrue(aih.isIdle(PACKAGE_1, USER_ID, 3000));
        assertFalse(aih.isIdle(PACKAGE_2, USER_ID, 3000));

        // Check persistence
        aih.writeAppIdleDurations();
        aih.writeAppIdleTimes(USER_ID);
        aih = new AppIdleHistory(mStorageDir, 4000);
        assertEquals(aih.getAppStandbyBucket(PACKAGE_1, USER_ID, 5000), STANDBY_BUCKET_RARE);
        assertEquals(aih.getAppStandbyBucket(PACKAGE_2, USER_ID, 5000), STANDBY_BUCKET_ACTIVE);
        assertEquals(aih.getAppStandbyReason(PACKAGE_1, USER_ID, 5000), REASON_TIMEOUT);

        assertTrue(aih.shouldInformListeners(PACKAGE_1, USER_ID, 5000, STANDBY_BUCKET_RARE));
        assertFalse(aih.shouldInformListeners(PACKAGE_1, USER_ID, 5000, STANDBY_BUCKET_RARE));
        assertTrue(aih.shouldInformListeners(PACKAGE_1, USER_ID, 5000, STANDBY_BUCKET_FREQUENT));
    }
}