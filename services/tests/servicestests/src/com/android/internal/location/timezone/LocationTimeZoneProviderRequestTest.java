/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.internal.location.timezone;

import static android.location.timezone.ParcelableTestSupport.assertRoundTripParcelable;

import org.junit.Test;

import java.time.Duration;

public class LocationTimeZoneProviderRequestTest {

    @Test
    public void testParcelable() {
        LocationTimeZoneProviderRequest.Builder builder =
                new LocationTimeZoneProviderRequest.Builder()
                        .setReportLocationTimeZone(false);
        assertRoundTripParcelable(builder.build());

        builder.setReportLocationTimeZone(true)
                .setInitializationTimeoutMillis(Duration.ofMinutes(5).toMillis());

        assertRoundTripParcelable(builder.build());
    }
}
