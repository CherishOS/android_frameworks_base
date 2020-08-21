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

package com.android.location.timezone.provider;

import android.annotation.NonNull;

import com.android.internal.location.timezone.LocationTimeZoneProviderRequest;

import java.util.Objects;

/**
 * This class is an interface to LocationTimeZoneProviderRequest for provider implementations.
 *
 * <p>IMPORTANT: This class is effectively a public API for unbundled code, and must remain API
 * stable.
 *
 * @hide
 */
public final class LocationTimeZoneProviderRequestUnbundled {

    private final LocationTimeZoneProviderRequest mRequest;

    public LocationTimeZoneProviderRequestUnbundled(
            @NonNull LocationTimeZoneProviderRequest request) {
        mRequest = Objects.requireNonNull(request);
    }

    public boolean getReportLocationTimeZone() {
        return mRequest.getReportLocationTimeZone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LocationTimeZoneProviderRequestUnbundled that =
                (LocationTimeZoneProviderRequestUnbundled) o;
        return mRequest.equals(that.mRequest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRequest);
    }

    @Override
    public String toString() {
        return mRequest.toString();
    }
}
