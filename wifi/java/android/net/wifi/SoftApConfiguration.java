/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.wifi;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.MacAddress;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Configuration for a soft access point (a.k.a. Soft AP, SAP, Hotspot).
 *
 * This is input for the framework provided by a client app, i.e. it exposes knobs to instruct the
 * framework how it should configure a hotspot.
 *
 * System apps can use this to configure a tethered hotspot using
 * {@link WifiManager#startTetheredHotspot(SoftApConfiguration)} and
 * {@link WifiManager#setSoftApConfiguration(SoftApConfiguration)}
 * or local-only hotspot using
 * {@link WifiManager#startLocalOnlyHotspot(SoftApConfiguration, Executor,
 * WifiManager.LocalOnlyHotspotCallback)}.
 *
 * Instances of this class are immutable; use {@link SoftApConfiguration.Builder} and its methods to
 * create a new instance.
 *
 * @hide
 */
@SystemApi
public final class SoftApConfiguration implements Parcelable {

    /**
     * 2GHz band.
     * @hide
     */
    @SystemApi
    public static final int BAND_2GHZ = 0;

    /**
     * 5GHz band.
     * @hide
     */
    @SystemApi
    public static final int BAND_5GHZ = 1;

    /**
     * Device is allowed to choose the optimal band (2Ghz or 5Ghz) based on device capability,
     * operating country code and current radio conditions.
     * @hide
     */
    @SystemApi
    public static final int BAND_ANY = -1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "BAND_TYPE_" }, value = {
            BAND_2GHZ,
            BAND_5GHZ,
            BAND_ANY,
    })
    public @interface BandType {}

    /**
     * SSID for the AP, or null for a framework-determined SSID.
     */
    private final @Nullable String mSsid;

    /**
     * BSSID for the AP, or null to use a framework-determined BSSID.
     */
    private final @Nullable MacAddress mBssid;

    /**
     * Pre-shared key for WPA2-PSK encryption (non-null enables WPA2-PSK).
     */
    private final @Nullable String mWpa2Passphrase;

    /**
     * This is a network that does not broadcast its SSID, so an
     * SSID-specific probe request must be used for scans.
     */
    private final boolean mHiddenSsid;

    /**
     * The operating band of the AP.
     * One of the band types from {@link @BandType}.
     */
    private final @BandType int mBand;

    /**
     * The operating channel of the AP.
     */
    private final int mChannel;

    /**
     * The operating security type of the AP.
     * One of the security types from {@link @SecurityType}
     */
    private final @SecurityType int mSecurityType;

    /**
     * Security types we support.
     */
    /** @hide */
    @SystemApi
    public static final int SECURITY_TYPE_OPEN = 0;

    /** @hide */
    @SystemApi
    public static final int SECURITY_TYPE_WPA2_PSK = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SECURITY_TYPE" }, value = {
        SECURITY_TYPE_OPEN,
        SECURITY_TYPE_WPA2_PSK,
    })
    public @interface SecurityType {}

    /** Private constructor for Builder and Parcelable implementation. */
    private SoftApConfiguration(@Nullable String ssid, @Nullable MacAddress bssid,
            @Nullable String wpa2Passphrase, boolean hiddenSsid, @BandType int band, int channel,
            @SecurityType int securityType) {
        mSsid = ssid;
        mBssid = bssid;
        mWpa2Passphrase = wpa2Passphrase;
        mHiddenSsid = hiddenSsid;
        mBand = band;
        mChannel = channel;
        mSecurityType = securityType;
    }

    @Override
    public boolean equals(Object otherObj) {
        if (this == otherObj) {
            return true;
        }
        if (!(otherObj instanceof SoftApConfiguration)) {
            return false;
        }
        SoftApConfiguration other = (SoftApConfiguration) otherObj;
        return Objects.equals(mSsid, other.mSsid)
                && Objects.equals(mBssid, other.mBssid)
                && Objects.equals(mWpa2Passphrase, other.mWpa2Passphrase)
                && mHiddenSsid == other.mHiddenSsid
                && mBand == other.mBand
                && mChannel == other.mChannel
                && mSecurityType == other.mSecurityType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSsid, mBssid, mWpa2Passphrase, mHiddenSsid,
                mBand, mChannel, mSecurityType);
    }

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append("ssid=").append(mSsid);
        if (mBssid != null) sbuf.append(" \n bssid=").append(mBssid.toString());
        sbuf.append(" \n Wpa2Passphrase =").append(
                TextUtils.isEmpty(mWpa2Passphrase) ? "<empty>" : "<non-empty>");
        sbuf.append(" \n HiddenSsid =").append(mHiddenSsid);
        sbuf.append(" \n Band =").append(mBand);
        sbuf.append(" \n Channel =").append(mChannel);
        sbuf.append(" \n SecurityType=").append(getSecurityType());
        return sbuf.toString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mSsid);
        dest.writeParcelable(mBssid, flags);
        dest.writeString(mWpa2Passphrase);
        dest.writeBoolean(mHiddenSsid);
        dest.writeInt(mBand);
        dest.writeInt(mChannel);
        dest.writeInt(mSecurityType);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<SoftApConfiguration> CREATOR = new Creator<SoftApConfiguration>() {
        @Override
        public SoftApConfiguration createFromParcel(Parcel in) {
            return new SoftApConfiguration(
                    in.readString(),
                    in.readParcelable(MacAddress.class.getClassLoader()),
                    in.readString(), in.readBoolean(), in.readInt(), in.readInt(), in.readInt());
        }

        @Override
        public SoftApConfiguration[] newArray(int size) {
            return new SoftApConfiguration[size];
        }
    };

    /**
     * Return String set to be the SSID for the AP.
     * {@link #setSsid(String)}.
     */
    @Nullable
    public String getSsid() {
        return mSsid;
    }

    /**
     * Returns MAC address set to be BSSID for the AP.
     * {@link #setBssid(MacAddress)}.
     */
    @Nullable
    public MacAddress getBssid() {
        return mBssid;
    }

    /**
     * Returns String set to be passphrase for the WPA2-PSK AP.
     * {@link #setWpa2Passphrase(String)}.
     */
    @Nullable
    public String getWpa2Passphrase() {
        return mWpa2Passphrase;
    }

    /**
     * Returns Boolean set to be indicate hidden (true: doesn't broadcast its SSID) or
     * not (false: broadcasts its SSID) for the AP.
     * {@link #setHiddenSsid(boolean)}.
     */
    public boolean isHiddenSsid() {
        return mHiddenSsid;
    }

    /**
     * Returns {@link BandType} set to be the band for the AP.
     * {@link #setBand(@BandType int)}.
     */
    public @BandType int getBand() {
        return mBand;
    }

    /**
     * Returns Integer set to be the channel for the AP.
     * {@link #setChannel(int)}.
     */
    public int getChannel() {
        return mChannel;
    }

    /**
     * Get security type params which depends on which security passphrase to set.
     *
     * @return One of the security types from {@link SecurityType}.
     */
    public @SecurityType int getSecurityType() {
        return mSecurityType;
    }

    /**
     * Builds a {@link SoftApConfiguration}, which allows an app to configure various aspects of a
     * Soft AP.
     *
     * All fields are optional. By default, SSID and BSSID are automatically chosen by the
     * framework, and an open network is created.
     */
    public static final class Builder {
        private String mSsid;
        private MacAddress mBssid;
        private String mWpa2Passphrase;
        private boolean mHiddenSsid;
        private int mBand;
        private int mChannel;

        private int setSecurityType() {
            int securityType = SECURITY_TYPE_OPEN;
            if (!TextUtils.isEmpty(mWpa2Passphrase)) { // WPA2-PSK network.
                securityType = SECURITY_TYPE_WPA2_PSK;
            }
            return securityType;
        }

        private void clearAllPassphrase() {
            mWpa2Passphrase = null;
        }

        /**
         * Constructs a Builder with default values (see {@link Builder}).
         */
        public Builder() {
            mSsid = null;
            mBssid = null;
            mWpa2Passphrase = null;
            mHiddenSsid = false;
            mBand = BAND_2GHZ;
            mChannel = 0;
        }

        /**
         * Constructs a Builder initialized from an existing {@link SoftApConfiguration} instance.
         */
        public Builder(@NonNull SoftApConfiguration other) {
            Objects.requireNonNull(other);

            mSsid = other.mSsid;
            mBssid = other.mBssid;
            mWpa2Passphrase = other.mWpa2Passphrase;
            mHiddenSsid = other.mHiddenSsid;
            mBand = other.mBand;
            mChannel = other.mChannel;
        }

        /**
         * Builds the {@link SoftApConfiguration}.
         *
         * @return A new {@link SoftApConfiguration}, as configured by previous method calls.
         */
        @NonNull
        public SoftApConfiguration build() {
            return new SoftApConfiguration(mSsid, mBssid, mWpa2Passphrase,
                mHiddenSsid, mBand, mChannel, setSecurityType());
        }

        /**
         * Specifies an SSID for the AP.
         * <p>
         * Null SSID only support when configure a local-only hotspot.
         * <p>
         * <li>If not set, defaults to null.</li>
         *
         * @param ssid SSID of valid Unicode characters, or null to have the SSID automatically
         *             chosen by the framework.
         * @return Builder for chaining.
         * @throws IllegalArgumentException when the SSID is empty or not valid Unicode.
         */
        @NonNull
        public Builder setSsid(@Nullable String ssid) {
            if (ssid != null) {
                Preconditions.checkStringNotEmpty(ssid);
                Preconditions.checkArgument(StandardCharsets.UTF_8.newEncoder().canEncode(ssid));
            }
            mSsid = ssid;
            return this;
        }

        /**
         * Specifies a BSSID for the AP.
         * <p>
         * Only supported when configuring a local-only hotspot.
         * <p>
         * <li>If not set, defaults to null.</li>
         * @param bssid BSSID, or null to have the BSSID chosen by the framework. The caller is
         *              responsible for avoiding collisions.
         * @return Builder for chaining.
         * @throws IllegalArgumentException when the given BSSID is the all-zero or broadcast MAC
         *                                  address.
         */
        @NonNull
        public Builder setBssid(@Nullable MacAddress bssid) {
            if (bssid != null) {
                Preconditions.checkArgument(!bssid.equals(MacAddress.ALL_ZEROS_ADDRESS));
                Preconditions.checkArgument(!bssid.equals(MacAddress.BROADCAST_ADDRESS));
            }
            mBssid = bssid;
            return this;
        }

        /**
         * Specifies that this AP should use WPA2-PSK with the given ASCII WPA2 passphrase.
         * When set to null, an open network is created.
         * <p>
         *
         * @param passphrase The passphrase to use, or null to unset a previously-set WPA2-PSK
         *                   configuration.
         * @return Builder for chaining.
         * @throws IllegalArgumentException when the passphrase is the empty string
         */
        @NonNull
        public Builder setWpa2Passphrase(@Nullable String passphrase) {
            if (passphrase != null) {
                final CharsetEncoder asciiEncoder = StandardCharsets.US_ASCII.newEncoder();
                if (!asciiEncoder.canEncode(passphrase)) {
                    throw new IllegalArgumentException("passphrase not ASCII encodable");
                }
                Preconditions.checkStringNotEmpty(passphrase);
            }
            clearAllPassphrase();
            mWpa2Passphrase = passphrase;
            return this;
        }

        /**
         * Specifies whether the AP is hidden (doesn't broadcast its SSID) or
         * not (broadcasts its SSID).
         * <p>
         * <li>If not set, defaults to false (i.e not a hidden network).</li>
         *
         * @param hiddenSsid true for a hidden SSID, false otherwise.
         * @return Builder for chaining.
         */
        @NonNull
        public Builder setHiddenSsid(boolean hiddenSsid) {
            mHiddenSsid = hiddenSsid;
            return this;
        }

        /**
         * Specifies the band for the AP.
         * <p>
         * <li>If not set, defaults to BAND_2GHZ {@link @BandType}.</li>
         *
         * @param band One of the band types from {@link @BandType}.
         * @return Builder for chaining.
         */
        @NonNull
        public Builder setBand(@BandType int band) {
            switch (band) {
                case BAND_2GHZ:
                    break;
                case BAND_5GHZ:
                    break;
                case BAND_ANY:
                    break;
                default:
                    throw new IllegalArgumentException("Invalid band type");
            }
            mBand = band;
            return this;
        }

        /**
         * Specifies the channel for the AP.
         *
         * The channel which AP resides on. Valid channels are country dependent.
         * Use the special channel value 0 to have the framework auto-select a valid channel
         * from the band configured with {@link #setBand(@BandType int)}.
         *
         * <p>
         * <li>If not set, defaults to 0.</li>
         * @param channel operating channel of the AP.
         * @return Builder for chaining.
         */
        @NonNull
        public Builder setChannel(int channel) {
            mChannel = channel;
            return this;
        }
    }
}
