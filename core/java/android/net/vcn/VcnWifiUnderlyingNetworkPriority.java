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
package android.net.vcn;

import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.PersistableBundle;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

// TODO: Add documents
/** @hide */
public final class VcnWifiUnderlyingNetworkPriority extends VcnUnderlyingNetworkPriority {
    private static final String SSID_KEY = "mSsid";
    @Nullable private final String mSsid;

    private VcnWifiUnderlyingNetworkPriority(
            int networkQuality, boolean allowMetered, String ssid) {
        super(NETWORK_PRIORITY_TYPE_WIFI, networkQuality, allowMetered);
        mSsid = ssid;

        validate();
    }

    /** @hide */
    @NonNull
    @VisibleForTesting(visibility = Visibility.PROTECTED)
    public static VcnWifiUnderlyingNetworkPriority fromPersistableBundle(
            @NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle is null");

        final int networkQuality = in.getInt(NETWORK_QUALITY_KEY);
        final boolean allowMetered = in.getBoolean(ALLOW_METERED_KEY);
        final String ssid = in.getString(SSID_KEY);
        return new VcnWifiUnderlyingNetworkPriority(networkQuality, allowMetered, ssid);
    }

    /** @hide */
    @Override
    @NonNull
    @VisibleForTesting(visibility = Visibility.PROTECTED)
    public PersistableBundle toPersistableBundle() {
        final PersistableBundle result = super.toPersistableBundle();
        result.putString(SSID_KEY, mSsid);
        return result;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mSsid);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!super.equals(other)) {
            return false;
        }

        if (!(other instanceof VcnWifiUnderlyingNetworkPriority)) {
            return false;
        }

        final VcnWifiUnderlyingNetworkPriority rhs = (VcnWifiUnderlyingNetworkPriority) other;
        return mSsid == rhs.mSsid;
    }

    /** Retrieve the required SSID, or {@code null} if there is no requirement on SSID. */
    @Nullable
    public String getSsid() {
        return mSsid;
    }

    /** This class is used to incrementally build VcnWifiUnderlyingNetworkPriority objects. */
    public static class Builder extends VcnUnderlyingNetworkPriority.Builder<Builder> {
        @Nullable private String mSsid;

        /** Construct a Builder object. */
        public Builder() {}

        /**
         * Set the required SSID.
         *
         * @param ssid the required SSID, or {@code null} if any SSID is acceptable.
         */
        @NonNull
        public Builder setSsid(@Nullable String ssid) {
            mSsid = ssid;
            return this;
        }

        /** Build the VcnWifiUnderlyingNetworkPriority. */
        @NonNull
        public VcnWifiUnderlyingNetworkPriority build() {
            return new VcnWifiUnderlyingNetworkPriority(mNetworkQuality, mAllowMetered, mSsid);
        }

        /** @hide */
        @Override
        Builder self() {
            return this;
        }
    }
}
