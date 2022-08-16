/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.server.pm;

import static android.os.UserHandle.USER_NULL;
import static android.os.UserHandle.USER_SYSTEM;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.ActivityManager;
import android.app.IStopUserCallback;
import android.app.UserSwitchObserver;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserManager;
import android.platform.test.annotations.Postsubmit;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.FunctionalUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * To run the test:
 * atest FrameworksServicesTests:com.android.server.pm.UserLifecycleStressTest
 */
@Postsubmit
@RunWith(AndroidJUnit4.class)
@LargeTest
public class UserLifecycleStressTest {
    private static final String TAG = "UserLifecycleStressTest";
    // TODO: Make this smaller once we have improved it.
    private static final int TIMEOUT_IN_SECOND = 40;
    private static final int NUM_ITERATIONS = 10;
    private static final int WAIT_BEFORE_STOP_USER_IN_SECOND = 3;

    private Context mContext;
    private UserManager mUserManager;
    private ActivityManager mActivityManager;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mUserManager = mContext.getSystemService(UserManager.class);
        mActivityManager = mContext.getSystemService(ActivityManager.class);
    }

    /**
     * Create and stop user {@link #NUM_ITERATIONS} times in a row. Check stop user can be finished
     * in a reasonable amount of time.
     */
    @Test
    public void stopManagedProfileStressTest() throws RemoteException, InterruptedException {
        for (int i = 0; i < NUM_ITERATIONS; i++) {
            final UserInfo userInfo = mUserManager.createProfileForUser("TestUser",
                    UserManager.USER_TYPE_PROFILE_MANAGED, 0, mActivityManager.getCurrentUser());
            assertThat(userInfo).isNotNull();
            try {
                assertWithMessage("Failed to start the profile")
                        .that(ActivityManager.getService().startUserInBackground(userInfo.id))
                        .isTrue();
                // Seems the broadcast queue is getting more busy if we wait a few seconds before
                // stopping the user.
                TimeUnit.SECONDS.sleep(WAIT_BEFORE_STOP_USER_IN_SECOND);
                stopUser(userInfo.id);
            } finally {
                mUserManager.removeUser(userInfo.id);
            }
        }
    }

    /**
     * Starts over the guest user {@link #NUM_ITERATIONS} times in a row.
     *
     * Starting over the guest means the following:
     * 1. While the guest user is in foreground, mark it for deletion.
     * 2. Create a new guest. (This wouldn't be possible if the old one wasn't marked for deletion)
     * 3. Switch to newly created guest.
     * 4. Remove the previous guest before waiting for switch to complete.
     **/
    @Test
    public void switchToExistingGuestAndStartOverStressTest() throws Exception {
        if (ActivityManager.getCurrentUser() != USER_SYSTEM) {
            switchUser(USER_SYSTEM);
        }

        final UserInfo foundGuest = mUserManager.findCurrentGuestUser();
        int nextGuestId = foundGuest == null ? USER_NULL : foundGuest.id;

        for (int i = 0; i < NUM_ITERATIONS; i++) {
            final int currentGuestId = nextGuestId;

            Log.d(TAG, "switchToExistingGuestAndStartOverStressTest"
                    + " - Run " + (i + 1) + " / " + NUM_ITERATIONS);

            if (currentGuestId != USER_NULL) {
                Log.d(TAG, "Switching to the existing guest");
                switchUser(currentGuestId);

                Log.d(TAG, "Marking current guest for deletion");
                assertWithMessage("Couldn't mark guest for deletion")
                        .that(mUserManager.markGuestForDeletion(currentGuestId))
                        .isTrue();
            }

            Log.d(TAG, "Creating a new guest");
            final UserInfo newGuest = mUserManager.createGuest(mContext);
            assertWithMessage("Couldn't create new guest")
                    .that(newGuest)
                    .isNotNull();

            Log.d(TAG, "Switching to the new guest");
            switchUserThenRun(newGuest.id, () -> {
                if (currentGuestId != USER_NULL) {
                    Log.d(TAG, "Removing the previous guest before waiting for switch to complete");
                    assertWithMessage("Couldn't remove guest")
                            .that(mUserManager.removeUser(currentGuestId))
                            .isTrue();
                }
            });
            Log.d(TAG, "Switching back to the system user");
            switchUser(USER_SYSTEM);

            nextGuestId = newGuest.id;
        }
        if (nextGuestId != USER_NULL) {
            Log.d(TAG, "Removing the last created guest user");
            mUserManager.removeUser(nextGuestId);
        }
        Log.d(TAG, "testSwitchToExistingGuestAndStartOver - End");
    }

    /** Stops the given user and waits for the stop to finish. */
    private void stopUser(int userId) throws RemoteException, InterruptedException {
        runWithLatch("stop user", countDownLatch -> {
            ActivityManager.getService()
                    .stopUser(userId, /* force= */ true, new IStopUserCallback.Stub() {
                        @Override
                        public void userStopped(int userId) {
                            countDownLatch.countDown();
                        }

                        @Override
                        public void userStopAborted(int i) throws RemoteException {

                        }
                    });
        });
    }

    /** Starts the given user in the foreground and waits for the switch to finish. */
    private void switchUser(int userId) throws RemoteException, InterruptedException {
        switchUserThenRun(userId, null);
    }

    /**
     * Starts the given user in the foreground. And runs the given Runnable right after
     * am.switchUser call, before waiting for the actual user switch to be complete.
     **/
    private void switchUserThenRun(int userId, Runnable runAfterSwitchBeforeWait)
            throws RemoteException, InterruptedException {
        runWithLatch("switch user", countDownLatch -> {
            ActivityManager.getService().registerUserSwitchObserver(
                    new UserSwitchObserver() {
                        @Override
                        public void onUserSwitchComplete(int newUserId) {
                            if (userId == newUserId) {
                                countDownLatch.countDown();
                            }
                        }
                    }, TAG);
            Log.d(TAG, "Switching to user " + userId);
            assertWithMessage("Failed to switch to user")
                    .that(mActivityManager.switchUser(userId))
                    .isTrue();
            if (runAfterSwitchBeforeWait != null) {
                runAfterSwitchBeforeWait.run();
            }
        });
    }

    /**
     * Calls the given consumer with a CountDownLatch parameter, and expects it's countDown() method
     * to be called before timeout, or fails the test otherwise.
     */
    private void runWithLatch(String tag,
            FunctionalUtils.RemoteExceptionIgnoringConsumer<CountDownLatch> consumer)
            throws RemoteException, InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final long startTime = System.currentTimeMillis();

        consumer.acceptOrThrow(countDownLatch);
        final boolean doneBeforeTimeout = countDownLatch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
        assertWithMessage("Took more than " + TIMEOUT_IN_SECOND + "s to " + tag)
                .that(doneBeforeTimeout)
                .isTrue();

        final long elapsedTime = System.currentTimeMillis() - startTime;
        Log.d(TAG, tag + " takes " + elapsedTime + " ms");
    }
}

