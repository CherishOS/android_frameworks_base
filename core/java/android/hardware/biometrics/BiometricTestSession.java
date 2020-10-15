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

package android.hardware.biometrics;

import static android.Manifest.permission.TEST_BIOMETRIC;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.content.Context;
import android.os.RemoteException;
import android.util.ArraySet;

/**
 * Common set of interfaces to test biometric-related APIs, including {@link BiometricPrompt} and
 * {@link android.hardware.fingerprint.FingerprintManager}.
 * @hide
 */
@TestApi
public class BiometricTestSession implements AutoCloseable {
    private final Context mContext;
    private final ITestSession mTestSession;

    // Keep track of users that were tested, which need to be cleaned up when finishing.
    private final ArraySet<Integer> mTestedUsers;

    /**
     * @hide
     */
    public BiometricTestSession(@NonNull Context context, @NonNull ITestSession testSession) {
        mContext = context;
        mTestSession = testSession;
        mTestedUsers = new ArraySet<>();
        enableTestHal(true);
    }

    /**
     * Switches the specified sensor to use a test HAL. In this mode, the framework will not invoke
     * any methods on the real HAL implementation. This allows the framework to test a substantial
     * portion of the framework code that would otherwise require human interaction. Note that
     * secure pathways such as HAT/Keystore are not testable, since they depend on the TEE or its
     * equivalent for the secret key.
     *
     * @param enableTestHal If true, enable testing with a fake HAL instead of the real HAL.
     * @hide
     */
    @RequiresPermission(TEST_BIOMETRIC)
    private void enableTestHal(boolean enableTestHal) {
        try {
            mTestSession.enableTestHal(enableTestHal);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Starts the enrollment process. This should generally be used when the test HAL is enabled.
     *
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void startEnroll(int userId) {
        try {
            mTestedUsers.add(userId);
            mTestSession.startEnroll(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Finishes the enrollment process. Simulates the HAL's callback.
     *
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void finishEnroll(int userId) {
        try {
            mTestedUsers.add(userId);
            mTestSession.finishEnroll(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Simulates a successful authentication, but does not provide a valid HAT.
     *
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void acceptAuthentication(int userId) {
        try {
            mTestSession.acceptAuthentication(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Simulates a rejected attempt.
     *
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void rejectAuthentication(int userId) {
        try {
            mTestSession.rejectAuthentication(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Simulates an acquired message from the HAL.
     *
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void notifyAcquired(int userId) {
        try {
            mTestSession.notifyAcquired(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Simulates an error message from the HAL.
     *
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void notifyError(int userId) {
        try {
            mTestSession.notifyError(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Matches the framework's cached enrollments against the HAL's enrollments. Any enrollment
     * that isn't known by both sides are deleted. This should generally be used when the test
     * HAL is disabled (e.g. to clean up after a test).
     *
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void cleanupInternalState(int userId) {
        try {
            mTestSession.cleanupInternalState(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @RequiresPermission(TEST_BIOMETRIC)
    public void close() {
        for (int user : mTestedUsers) {
            cleanupInternalState(user);
        }

        enableTestHal(false);
    }
}
