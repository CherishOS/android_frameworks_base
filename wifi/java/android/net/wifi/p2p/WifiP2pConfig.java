/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net.wifi.p2p;

import android.annotation.IntDef;
import android.annotation.UnsupportedAppUsage;
import android.net.MacAddress;
import android.net.wifi.WpsInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class representing a Wi-Fi P2p configuration for setting up a connection
 *
 * {@see WifiP2pManager}
 */
public class WifiP2pConfig implements Parcelable {

    /**
     * The device MAC address uniquely identifies a Wi-Fi p2p device
     */
    public String deviceAddress = "";

    /**
     * Wi-Fi Protected Setup information
     */
    public WpsInfo wps;

    /**
     * The network name of a group, should be configured by helper method
     */
    /** @hide */
    public String networkName = "";

    /**
     * The passphrase of a group, should be configured by helper method
     */
    /** @hide */
    public String passphrase = "";

    /**
     * The required band for Group Owner
     */
    /** @hide */
    public int groupOwnerBand = GROUP_OWNER_BAND_AUTO;

    /** @hide */
    public static final int MAX_GROUP_OWNER_INTENT   =   15;
    /** @hide */
    @UnsupportedAppUsage
    public static final int MIN_GROUP_OWNER_INTENT   =   0;

    /** @hide */
    @IntDef(flag = false, prefix = { "GROUP_OWNER_BAND_" }, value = {
        GROUP_OWNER_BAND_AUTO,
        GROUP_OWNER_BAND_2GHZ,
        GROUP_OWNER_BAND_5GHZ
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GroupOwnerBandType {}

    /**
     * Recognized Group Owner required band.
     */
    public static final int GROUP_OWNER_BAND_AUTO = 0;
    public static final int GROUP_OWNER_BAND_2GHZ = 1;
    public static final int GROUP_OWNER_BAND_5GHZ = 2;

    /**
     * This is an integer value between 0 and 15 where 0 indicates the least
     * inclination to be a group owner and 15 indicates the highest inclination
     * to be a group owner.
     *
     * A value of -1 indicates the system can choose an appropriate value.
     */
    public int groupOwnerIntent = -1;

    /** @hide */
    @UnsupportedAppUsage
    public int netId = WifiP2pGroup.PERSISTENT_NET_ID;

    public WifiP2pConfig() {
        //set defaults
        wps = new WpsInfo();
        wps.setup = WpsInfo.PBC;
    }

    /** @hide */
    public void invalidate() {
        deviceAddress = "";
    }

    /** P2P-GO-NEG-REQUEST 42:fc:89:a8:96:09 dev_passwd_id=4 {@hide}*/
    @UnsupportedAppUsage
    public WifiP2pConfig(String supplicantEvent) throws IllegalArgumentException {
        String[] tokens = supplicantEvent.split(" ");

        if (tokens.length < 2 || !tokens[0].equals("P2P-GO-NEG-REQUEST")) {
            throw new IllegalArgumentException("Malformed supplicant event");
        }

        deviceAddress = tokens[1];
        wps = new WpsInfo();

        if (tokens.length > 2) {
            String[] nameVal = tokens[2].split("=");
            int devPasswdId;
            try {
                devPasswdId = Integer.parseInt(nameVal[1]);
            } catch (NumberFormatException e) {
                devPasswdId = 0;
            }
            //Based on definitions in wps/wps_defs.h
            switch (devPasswdId) {
                //DEV_PW_USER_SPECIFIED = 0x0001,
                case 0x01:
                    wps.setup = WpsInfo.DISPLAY;
                    break;
                //DEV_PW_PUSHBUTTON = 0x0004,
                case 0x04:
                    wps.setup = WpsInfo.PBC;
                    break;
                //DEV_PW_REGISTRAR_SPECIFIED = 0x0005
                case 0x05:
                    wps.setup = WpsInfo.KEYPAD;
                    break;
                default:
                    wps.setup = WpsInfo.PBC;
                    break;
            }
        }
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("\n address: ").append(deviceAddress);
        sbuf.append("\n wps: ").append(wps);
        sbuf.append("\n groupOwnerIntent: ").append(groupOwnerIntent);
        sbuf.append("\n persist: ").append(netId);
        sbuf.append("\n networkName: ").append(networkName);
        sbuf.append("\n passphrase: ").append(
                TextUtils.isEmpty(passphrase) ? "<empty>" : "<non-empty>");
        sbuf.append("\n groupOwnerBand: ").append(groupOwnerBand);
        return sbuf.toString();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** copy constructor */
    public WifiP2pConfig(WifiP2pConfig source) {
        if (source != null) {
            deviceAddress = source.deviceAddress;
            wps = new WpsInfo(source.wps);
            groupOwnerIntent = source.groupOwnerIntent;
            netId = source.netId;
            networkName = source.networkName;
            passphrase = source.passphrase;
            groupOwnerBand = source.groupOwnerBand;
        }
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(deviceAddress);
        dest.writeParcelable(wps, flags);
        dest.writeInt(groupOwnerIntent);
        dest.writeInt(netId);
        dest.writeString(networkName);
        dest.writeString(passphrase);
        dest.writeInt(groupOwnerBand);
    }

    /** Implement the Parcelable interface */
    public static final Creator<WifiP2pConfig> CREATOR =
        new Creator<WifiP2pConfig>() {
            public WifiP2pConfig createFromParcel(Parcel in) {
                WifiP2pConfig config = new WifiP2pConfig();
                config.deviceAddress = in.readString();
                config.wps = (WpsInfo) in.readParcelable(null);
                config.groupOwnerIntent = in.readInt();
                config.netId = in.readInt();
                config.networkName = in.readString();
                config.passphrase = in.readString();
                config.groupOwnerBand = in.readInt();
                return config;
            }

            public WifiP2pConfig[] newArray(int size) {
                return new WifiP2pConfig[size];
            }
        };

    /**
     * Builder used to build {@link WifiP2pConfig} objects for
     * creating or joining a group.
     */
    public static final class Builder {

        private static final MacAddress MAC_ANY_ADDRESS =
                MacAddress.fromString("00:00:00:00:00:00");

        private MacAddress mDeviceAddress = MAC_ANY_ADDRESS;
        private String mNetworkName = "";
        private String mPassphrase = "";
        private int mGroupOwnerBand = GROUP_OWNER_BAND_AUTO;
        private int mNetId = WifiP2pGroup.TEMPORARY_NET_ID;

        /**
         * Specify the peer's MAC address. If not set, the device will
         * try to find a peer whose SSID matches the network name as
         * specified by {@link #setNetworkName(String)}. Specifying null will
         * reset the peer's MAC address to "00:00:00:00:00:00".
         * <p>
         *     Optional. "00:00:00:00:00:00" by default.
         *
         * @param deviceAddress the peer's MAC address.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setDeviceAddress(MacAddress deviceAddress) {
            if (deviceAddress == null) {
                mDeviceAddress = MAC_ANY_ADDRESS;
            } else {
                mDeviceAddress = deviceAddress;
            }
            return this;
        }

        /**
         * Specify the network name, a.k.a. group name,
         * for creating or joining a group.
         * <p>
         *     Must be called - an empty network name is not valid.
         *
         * @param networkName network name of a group.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setNetworkName(String networkName) {
            if (TextUtils.isEmpty(networkName)) {
                throw new IllegalArgumentException(
                        "network name must be non-empty.");
            }
            mNetworkName = networkName;
            return this;
        }

        /**
         * Specify the passphrase for creating or joining a group.
         * <p>
         *     Must be called - an empty passphrase is not valid.
         *
         * @param passphrase the passphrase of a group.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setPassphrase(String passphrase) {
            if (TextUtils.isEmpty(passphrase)) {
                throw new IllegalArgumentException(
                        "passphrase must be non-empty.");
            }
            mPassphrase = passphrase;
            return this;
        }

        /**
         * Specify the band to use for creating the group. This method only applies when
         * creating a group as Group Owner using {@link WifiP2pManager#createGroup}.
         * The band should be {@link #GROUP_OWNER_BAND_2GHZ} or {@link #GROUP_OWNER_BAND_5GHZ},
         * or allow the system to pick the band by specifying {@link #GROUP_OWNER_BAND_AUTO}.
         * If the Group Owner cannot create a group in the specified band, the operation will fail.
         * <p>
         *     Optional. {@link #GROUP_OWNER_BAND_AUTO} by default.
         *
         * @param band the required band of group owner.
         *             This should be one of {@link #GROUP_OWNER_BAND_AUTO},
         *             {@link #GROUP_OWNER_BAND_2GHZ}, {@link #GROUP_OWNER_BAND_5GHZ}.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setGroupOwnerBand(int band) {
            mGroupOwnerBand = band;
            return this;
        }

        /**
         * Specify that the group configuration be persisted (i.e. saved).
         * By default the group configuration will not be saved.
         * <p>
         *     Optional. false by default.
         *
         * @param persistent is this group persistent group.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder enablePersistentMode(boolean persistent) {
            if (persistent) {
                mNetId = WifiP2pGroup.PERSISTENT_NET_ID;
            } else {
                mNetId = WifiP2pGroup.TEMPORARY_NET_ID;
            }
            return this;
        }

        /**
         * Build {@link WifiP2pConfig} given the current requests made on the builder.
         * @return {@link WifiP2pConfig} constructed based on builder method calls.
         */
        public WifiP2pConfig build() {
            if (TextUtils.isEmpty(mNetworkName)) {
                throw new IllegalStateException(
                        "network name must be non-empty.");
            }
            if (TextUtils.isEmpty(mPassphrase)) {
                throw new IllegalStateException(
                        "passphrase must be non-empty.");
            }

            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = mDeviceAddress.toString();
            config.networkName = mNetworkName;
            config.passphrase = mPassphrase;
            config.groupOwnerBand = mGroupOwnerBand;
            config.netId = mNetId;
            return config;
        }
    }
}
