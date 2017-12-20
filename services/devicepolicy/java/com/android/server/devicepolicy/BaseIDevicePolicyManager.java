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
 * limitations under the License.
 */
package com.android.server.devicepolicy;

import android.annotation.UserIdInt;
import android.app.admin.IDevicePolicyManager;
import android.content.ComponentName;
import android.os.PersistableBundle;
import android.security.keymaster.KeymasterCertificateChain;
import android.security.keystore.ParcelableKeyGenParameterSpec;

import com.android.internal.R;
import com.android.server.SystemService;

import java.util.List;

/**
 * Defines the required interface for IDevicePolicyManager implemenation.
 *
 * <p>The interface consists of public parts determined by {@link IDevicePolicyManager} and also
 * several package private methods required by internal infrastructure.
 *
 * <p>Whenever adding an AIDL method to {@link IDevicePolicyManager}, an empty override method
 * should be added here to avoid build breakage in downstream branches.
 */
abstract class BaseIDevicePolicyManager extends IDevicePolicyManager.Stub {
    /**
     * To be called by {@link DevicePolicyManagerService#Lifecycle} during the various boot phases.
     *
     * @see {@link SystemService#onBootPhase}.
     */
    abstract void systemReady(int phase);
    /**
     * To be called by {@link DevicePolicyManagerService#Lifecycle} when a new user starts.
     *
     * @see {@link SystemService#onStartUser}
     */
    abstract void handleStartUser(int userId);
    /**
     * To be called by {@link DevicePolicyManagerService#Lifecycle} when a user is being unlocked.
     *
     * @see {@link SystemService#onUnlockUser}
     */
    abstract void handleUnlockUser(int userId);
    /**
     * To be called by {@link DevicePolicyManagerService#Lifecycle} when a user is being stopped.
     *
     * @see {@link SystemService#onStopUser}
     */
    abstract void handleStopUser(int userId);

    public void setSystemSetting(ComponentName who, String setting, String value){}

    public void transferOwner(ComponentName admin, ComponentName target, PersistableBundle bundle) {}

    public boolean generateKeyPair(ComponentName who, String callerPackage, String algorithm,
            ParcelableKeyGenParameterSpec keySpec, KeymasterCertificateChain attestationChain) {
        return false;
    }

    @Override
    public boolean setPasswordBlacklist(ComponentName who, String name, List<String> blacklist,
            boolean parent) {
        return false;
    }

    @Override
    public String getPasswordBlacklistName(ComponentName who, @UserIdInt int userId,
            boolean parent) {
        return null;
    }

    @Override
    public boolean isPasswordBlacklisted(@UserIdInt int userId, String password) {
        return false;
    }

    public boolean isUsingUnifiedPassword(ComponentName who) {
        return true;
    }

    public boolean setKeyPairCertificate(ComponentName who, String callerPackage, String alias,
            byte[] cert, byte[] chain, boolean isUserSelectable) {
        return false;
    }
}
