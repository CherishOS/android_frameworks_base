/*
 * Copyright 2017 The Android Open Source Project
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

package android.privacy.internal.rappor;

import android.privacy.DifferentialPrivacyEncoder;

import com.google.android.rappor.Encoder;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Differential privacy encoder by using
 * <a href="https://research.google.com/pubs/pub42852.html">RAPPOR</a>
 * algorithm.
 *
 * @hide
 */
public class RapporEncoder implements DifferentialPrivacyEncoder {

    // Hard-coded seed and secret for insecure encoder
    private static final long INSECURE_RANDOM_SEED = 0x12345678L;
    private static final byte[] INSECURE_SECRET = new byte[]{
            (byte) 0xD7, (byte) 0x68, (byte) 0x99, (byte) 0x93,
            (byte) 0x94, (byte) 0x13, (byte) 0x53, (byte) 0x54,
            (byte) 0xFE, (byte) 0xD0, (byte) 0x7E, (byte) 0x54,
            (byte) 0xFE, (byte) 0xD0, (byte) 0x7E, (byte) 0x54,
            (byte) 0xD7, (byte) 0x68, (byte) 0x99, (byte) 0x93,
            (byte) 0x94, (byte) 0x13, (byte) 0x53, (byte) 0x54,
            (byte) 0xFE, (byte) 0xD0, (byte) 0x7E, (byte) 0x54,
            (byte) 0xFE, (byte) 0xD0, (byte) 0x7E, (byte) 0x54,
            (byte) 0xD7, (byte) 0x68, (byte) 0x99, (byte) 0x93,
            (byte) 0x94, (byte) 0x13, (byte) 0x53, (byte) 0x54,
            (byte) 0xFE, (byte) 0xD0, (byte) 0x7E, (byte) 0x54,
            (byte) 0xFE, (byte) 0xD0, (byte) 0x7E, (byte) 0x54
    };
    private static final SecureRandom sSecureRandom = new SecureRandom();

    private final RapporConfig mConfig;

    // Rappor encoder
    private final Encoder mEncoder;
    // True if encoder is secure (seed is securely randomized)
    private final boolean mIsSecure;


    private RapporEncoder(RapporConfig config, boolean secureEncoder, byte[] userSecret) {
        mConfig = config;
        mIsSecure = secureEncoder;
        final Random random;
        if (secureEncoder) {
            // Use SecureRandom as random generator.
            random = sSecureRandom;
        } else {
            // Hard-coded random generator, to have deterministic result.
            random = new Random(INSECURE_RANDOM_SEED);
            userSecret = INSECURE_SECRET;
        }
        mEncoder = new Encoder(random, null, null,
                userSecret, config.mEncoderId, config.mNumBits,
                config.mProbabilityF, config.mProbabilityP, config.mProbabilityQ,
                config.mNumCohorts, config.mNumBloomHashes);
    }

    /**
     * Create {@link RapporEncoder} with {@link RapporConfig} and user secret provided.
     *
     * @param config     Rappor parameters to encode input.
     * @param userSecret Per device unique secret key.
     * @return {@link RapporEncoder} instance.
     */
    public static RapporEncoder createEncoder(RapporConfig config, byte[] userSecret) {
        return new RapporEncoder(config, true, userSecret);
    }

    /**
     * Create <strong>insecure</strong> {@link RapporEncoder} with {@link RapporConfig} provided.
     * Should not use it to process sensitive data.
     *
     * @param config Rappor parameters to encode input.
     * @return {@link RapporEncoder} instance.
     */
    public static RapporEncoder createInsecureEncoderForTest(RapporConfig config) {
        return new RapporEncoder(config, false, null);
    }

    @Override
    public byte[] encodeString(String original) {
        return mEncoder.encodeString(original);
    }

    @Override
    public byte[] encodeBoolean(boolean original) {
        return mEncoder.encodeBoolean(original);
    }

    @Override
    public byte[] encodeBits(byte[] bits) {
        return mEncoder.encodeBits(bits);
    }

    @Override
    public RapporConfig getConfig() {
        return mConfig;
    }

    @Override
    public boolean isInsecureEncoderForTest() {
        return !mIsSecure;
    }
}
