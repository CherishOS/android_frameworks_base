/*
 * Copyright 2018 The Android Open Source Project
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

package android.app.activity;

import android.app.Activity;
import android.app.IApplicationThread;
import android.app.servertransaction.ActivityRelaunchItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.ClientTransactionItem;
import android.app.servertransaction.ResumeActivityItem;
import android.content.Intent;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.util.MergedConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test for verifying {@link android.app.ActivityThread} class.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class ActivityThreadTest {

    private final ActivityTestRule mActivityTestRule =
            new ActivityTestRule(TestActivity.class, true /* initialTouchMode */,
                    false /* launchActivity */);

    @Test
    public void testDoubleRelaunch() throws Exception {
        final Activity activity = mActivityTestRule.launchActivity(new Intent());
        final IApplicationThread appThread = activity.getActivityThread().getApplicationThread();

        appThread.scheduleTransaction(newRelaunchResumeTransaction(activity));
        appThread.scheduleTransaction(newRelaunchResumeTransaction(activity));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testResumeAfterRelaunch() throws Exception {
        final Activity activity = mActivityTestRule.launchActivity(new Intent());
        final IApplicationThread appThread = activity.getActivityThread().getApplicationThread();

        appThread.scheduleTransaction(newRelaunchResumeTransaction(activity));
        appThread.scheduleTransaction(newResumeTransaction(activity));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static ClientTransaction newRelaunchResumeTransaction(Activity activity) {
        final ClientTransactionItem callbackItem = ActivityRelaunchItem.obtain(null,
                null, 0, new MergedConfiguration(),
                false /* preserveWindow */);
        final ResumeActivityItem resumeStateRequest =
                ResumeActivityItem.obtain(true /* isForward */);
        final IApplicationThread appThread = activity.getActivityThread().getApplicationThread();
        final ClientTransaction transaction =
                ClientTransaction.obtain(appThread, activity.getActivityToken());
        transaction.addCallback(callbackItem);
        transaction.setLifecycleStateRequest(resumeStateRequest);

        return transaction;
    }

    private static ClientTransaction newResumeTransaction(Activity activity) {
        final ResumeActivityItem resumeStateRequest =
                ResumeActivityItem.obtain(true /* isForward */);
        final IApplicationThread appThread = activity.getActivityThread().getApplicationThread();
        final ClientTransaction transaction =
                ClientTransaction.obtain(appThread, activity.getActivityToken());
        transaction.setLifecycleStateRequest(resumeStateRequest);

        return transaction;
    }

    // Test activity
    public static class TestActivity extends Activity {
    }
}
