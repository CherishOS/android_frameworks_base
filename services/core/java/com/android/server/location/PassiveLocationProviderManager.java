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

package com.android.server.location;

import android.annotation.Nullable;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;

import com.android.internal.location.ProviderRequest;
import com.android.internal.util.Preconditions;
import com.android.server.location.util.Injector;

import java.util.Collection;

class PassiveLocationProviderManager extends LocationProviderManager {

    PassiveLocationProviderManager(Context context, Injector injector) {
        super(context, injector, LocationManager.PASSIVE_PROVIDER, null);
    }

    @Override
    public void setRealProvider(AbstractLocationProvider provider) {
        Preconditions.checkArgument(provider instanceof PassiveProvider);
        super.setRealProvider(provider);
    }

    @Override
    public void setMockProvider(@Nullable MockProvider provider) {
        if (provider != null) {
            throw new IllegalArgumentException("Cannot mock the passive provider");
        }
    }

    public void updateLocation(Location location) {
        synchronized (mLock) {
            PassiveProvider passiveProvider = (PassiveProvider) mProvider.getProvider();
            Preconditions.checkState(passiveProvider != null);

            final long identity = Binder.clearCallingIdentity();
            try {
                passiveProvider.updateLocation(location);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    protected ProviderRequest mergeRegistrations(Collection<Registration> registrations) {
        boolean locationSettingsIgnored = false;
        for (Registration registration : registrations) {
            locationSettingsIgnored |= registration.getRequest().isLocationSettingsIgnored();
        }

        return new ProviderRequest.Builder()
                .setIntervalMillis(0)
                .setLocationSettingsIgnored(locationSettingsIgnored)
                .build();
    }

    @Override
    protected long calculateRequestDelayMillis(long newIntervalMs,
            Collection<Registration> registrations) {
        return 0;
    }
}
