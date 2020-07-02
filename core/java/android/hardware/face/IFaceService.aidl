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
package android.hardware.face;

import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.face.IFaceServiceReceiver;
import android.hardware.face.Face;
import android.view.Surface;

/**
 * Communication channel from client to the face service. These methods are all require the
 * MANAGE_BIOMETRIC signature permission.
 * @hide
 */
interface IFaceService {
    // Authenticate the given sessionId with a face
    void authenticate(IBinder token, long operationId, int userid,
            IFaceServiceReceiver receiver, int flags, String opPackageName);

    // This method prepares the service to start authenticating, but doesn't start authentication.
    // This is protected by the MANAGE_BIOMETRIC signatuer permission. This method should only be
    // called from BiometricService. The additional uid, pid, userId arguments should be determined
    // by BiometricService. To start authentication after the clients are ready, use
    // startPreparedClient().
    void prepareForAuthentication(boolean requireConfirmation, IBinder token, long operationId,
            int userId, IBiometricSensorReceiver sensorReceiver, String opPackageName,
            int cookie, int callingUid, int callingPid, int callingUserId);

    // Starts authentication with the previously prepared client.
    void startPreparedClient(int cookie);

    // Cancel authentication for the given sessionId
    void cancelAuthentication(IBinder token, String opPackageName);

    // Same as above, with extra arguments.
    void cancelAuthenticationFromService(IBinder token, String opPackageName,
            int callingUid, int callingPid, int callingUserId);

    // Start face enrollment
    void enroll(int userId, IBinder token, in byte [] cryptoToken, IFaceServiceReceiver receiver,
            String opPackageName, in int [] disabledFeatures, in Surface surface);

    // Start remote face enrollment
    void enrollRemotely(int userId, IBinder token, in byte [] cryptoToken, IFaceServiceReceiver receiver,
            String opPackageName, in int [] disabledFeatures);

    // Cancel enrollment in progress
    void cancelEnrollment(IBinder token);

    // Any errors resulting from this call will be returned to the listener
    void remove(IBinder token, int faceId, int userId, IFaceServiceReceiver receiver,
            String opPackageName);

    // Get the enrolled face for user.
    List<Face> getEnrolledFaces(int userId, String opPackageName);

    // Determine if HAL is loaded and ready
    boolean isHardwareDetected(String opPackageName);

    // Get a pre-enrollment authentication token
    void generateChallenge(IBinder token, IFaceServiceReceiver receiver, String opPackageName);

    // Finish an enrollment sequence and invalidate the authentication token
    void revokeChallenge(IBinder token, String opPackageName);

    // Determine if a user has at least one enrolled face
    boolean hasEnrolledFaces(int userId, String opPackageName);

    // Return the LockoutTracker status for the specified user
    int getLockoutModeForUser(int userId);

    // Gets the authenticator ID for face
    long getAuthenticatorId(int callingUserId);

    // Reset the lockout when user authenticates with strong auth (e.g. PIN, pattern or password)
    void resetLockout(int userId, in byte [] token);

    // Add a callback which gets notified when the face lockout period expired.
    void addLockoutResetCallback(IBiometricServiceLockoutResetCallback callback);

    void setFeature(IBinder token, int userId, int feature, boolean enabled,
            in byte [] hardwareAuthToken, IFaceServiceReceiver receiver, String opPackageName);

    void getFeature(IBinder token, int userId, int feature, IFaceServiceReceiver receiver,
            String opPackageName);

    // Give FaceService its ID. See AuthService.java
    void initializeConfiguration(int sensorId);
}
