/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.security;

import static android.security.attestationverification.AttestationVerificationManager.RESULT_UNKNOWN;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelDuration;
import android.os.RemoteException;
import android.security.attestationverification.AttestationProfile;
import android.security.attestationverification.IAttestationVerificationManagerService;
import android.security.attestationverification.IVerificationResult;
import android.security.attestationverification.VerificationToken;
import android.util.ExceptionUtils;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;
import com.android.server.SystemService;

/**
 * A {@link SystemService} which provides functionality related to verifying attestations of
 * (usually) remote computing environments.
 *
 * @hide
 */
public class AttestationVerificationManagerService extends SystemService {

    private static final String TAG = "AVF";

    public AttestationVerificationManagerService(final Context context) {
        super(context);
    }

    private final IBinder mService = new IAttestationVerificationManagerService.Stub() {
        @Override
        public void verifyAttestation(
                AttestationProfile profile,
                int localBindingType,
                Bundle requirements,
                byte[] attestation,
                AndroidFuture resultCallback) throws RemoteException {
            try {
                Slog.d(TAG, "verifyAttestation");
                verifyAttestationForAllVerifiers(profile, localBindingType, requirements,
                        attestation, resultCallback);
            } catch (Throwable t) {
                Slog.e(TAG, "failed to verify attestation", t);
                throw ExceptionUtils.propagate(t, RemoteException.class);
            }
        }

        @Override
        public void verifyToken(VerificationToken token, ParcelDuration parcelDuration,
                AndroidFuture resultCallback) throws RemoteException {
            // TODO(b/201696614): Implement
            resultCallback.complete(RESULT_UNKNOWN);
        }
    };

    private void verifyAttestationForAllVerifiers(
            AttestationProfile profile, int localBindingType, Bundle requirements,
            byte[] attestation, AndroidFuture<IVerificationResult> resultCallback) {
        // TODO(b/201696614): Implement
        IVerificationResult result = new IVerificationResult();
        result.resultCode = RESULT_UNKNOWN;
        result.token = null;
        resultCallback.complete(result);
    }

    @Override
    public void onStart() {
        Slog.d(TAG, "Started");
        publishBinderService(Context.ATTESTATION_VERIFICATION_SERVICE, mService);
    }
}
