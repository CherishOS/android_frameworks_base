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

package android.net.wifi.rtt;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.MacAddress;
import android.net.wifi.aware.PeerHandle;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;

/**
 * Ranging result for a request started by
 * {@link WifiRttManager#startRanging(RangingRequest, RangingResultCallback, Handler)}. Results are
 * returned in {@link RangingResultCallback#onRangingResults(List)}.
 * <p>
 * A ranging result is the distance measurement result for a single device specified in the
 * {@link RangingRequest}.
 */
public final class RangingResult implements Parcelable {
    private static final String TAG = "RangingResult";

    /** @hide */
    @IntDef({STATUS_SUCCESS, STATUS_FAIL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RangeResultStatus {
    }

    /**
     * Individual range request status, {@link #getStatus()}. Indicates ranging operation was
     * successful and distance value is valid.
     */
    public static final int STATUS_SUCCESS = 0;

    /**
     * Individual range request status, {@link #getStatus()}. Indicates ranging operation failed
     * and the distance value is invalid.
     */
    public static final int STATUS_FAIL = 1;

    private final int mStatus;
    private final MacAddress mMac;
    private final PeerHandle mPeerHandle;
    private final int mDistanceMm;
    private final int mDistanceStdDevMm;
    private final int mRssi;
    private final LocationConfigurationInformation mLci;
    private final LocationCivic mLcr;
    private final long mTimestamp;

    /** @hide */
    public RangingResult(@RangeResultStatus int status, @NonNull MacAddress mac, int distanceMm,
            int distanceStdDevMm, int rssi, LocationConfigurationInformation lci, LocationCivic lcr,
            long timestamp) {
        mStatus = status;
        mMac = mac;
        mPeerHandle = null;
        mDistanceMm = distanceMm;
        mDistanceStdDevMm = distanceStdDevMm;
        mRssi = rssi;
        mLci = lci;
        mLcr = lcr;
        mTimestamp = timestamp;
    }

    /** @hide */
    public RangingResult(@RangeResultStatus int status, PeerHandle peerHandle, int distanceMm,
            int distanceStdDevMm, int rssi, LocationConfigurationInformation lci, LocationCivic lcr,
            long timestamp) {
        mStatus = status;
        mMac = null;
        mPeerHandle = peerHandle;
        mDistanceMm = distanceMm;
        mDistanceStdDevMm = distanceStdDevMm;
        mRssi = rssi;
        mLci = lci;
        mLcr = lcr;
        mTimestamp = timestamp;
    }

    /**
     * @return The status of ranging measurement: {@link #STATUS_SUCCESS} in case of success, and
     * {@link #STATUS_FAIL} in case of failure.
     */
    @RangeResultStatus
    public int getStatus() {
        return mStatus;
    }

    /**
     * @return The MAC address of the device whose range measurement was requested. Will correspond
     * to the MAC address of the device in the {@link RangingRequest}.
     * <p>
     * Will return a {@code null} for results corresponding to requests issued using a {@code
     * PeerHandle}, i.e. using the {@link RangingRequest.Builder#addWifiAwarePeer(PeerHandle)} API.
     */
    @Nullable
    public MacAddress getMacAddress() {
        return mMac;
    }

    /**
     * @return The PeerHandle of the device whose reange measurement was requested. Will correspond
     * to the PeerHandle of the devices requested using
     * {@link RangingRequest.Builder#addWifiAwarePeer(PeerHandle)}.
     * <p>
     * Will return a {@code null} for results corresponding to requests issued using a MAC address.
     */
    @Nullable public PeerHandle getPeerHandle() {
        return mPeerHandle;
    }

    /**
     * @return The distance (in mm) to the device specified by {@link #getMacAddress()} or
     * {@link #getPeerHandle()}.
     * <p>
     * Only valid if {@link #getStatus()} returns {@link #STATUS_SUCCESS}, otherwise will throw an
     * exception.
     */
    public int getDistanceMm() {
        if (mStatus != STATUS_SUCCESS) {
            throw new IllegalStateException(
                    "getDistanceMm(): invoked on an invalid result: getStatus()=" + mStatus);
        }
        return mDistanceMm;
    }

    /**
     * @return The standard deviation of the measured distance (in mm) to the device specified by
     * {@link #getMacAddress()} or {@link #getPeerHandle()}. The standard deviation is calculated
     * over the measurements executed in a single RTT burst.
     * <p>
     * Only valid if {@link #getStatus()} returns {@link #STATUS_SUCCESS}, otherwise will throw an
     * exception.
     */
    public int getDistanceStdDevMm() {
        if (mStatus != STATUS_SUCCESS) {
            throw new IllegalStateException(
                    "getDistanceStdDevMm(): invoked on an invalid result: getStatus()=" + mStatus);
        }
        return mDistanceStdDevMm;
    }

    /**
     * @return The average RSSI (in units of -0.5dB) observed during the RTT measurement.
     * <p>
     * Only valid if {@link #getStatus()} returns {@link #STATUS_SUCCESS}, otherwise will throw an
     * exception.
     */
    public int getRssi() {
        if (mStatus != STATUS_SUCCESS) {
            throw new IllegalStateException(
                    "getRssi(): invoked on an invalid result: getStatus()=" + mStatus);
        }
        return mRssi;
    }

    /**
     * @return The Location Configuration Information (LCI) as self-reported by the peer.
     * <p>
     * Note: the information is NOT validated - use with caution. Consider validating it with
     * other sources of information before using it.
     */
    @Nullable
    public LocationConfigurationInformation getReportedLocationConfigurationInformation() {
        if (mStatus != STATUS_SUCCESS) {
            throw new IllegalStateException(
                    "getReportedLocationConfigurationInformation(): invoked on an invalid result: "
                            + "getStatus()=" + mStatus);
        }
        return mLci;
    }

    /**
     * @return The Location Civic report (LCR) as self-reported by the peer.
     * <p>
     * Note: the information is NOT validated - use with caution. Consider validating it with
     * other sources of information before using it.
     */
    @Nullable
    public LocationCivic getReportedLocationCivic() {
        if (mStatus != STATUS_SUCCESS) {
            throw new IllegalStateException(
                    "getReportedLocationCivic(): invoked on an invalid result: getStatus()="
                            + mStatus);
        }
        return mLcr;
    }

    /**
     * @return The timestamp, in us since boot, at which the ranging operation was performed.
     * <p>
     * Only valid if {@link #getStatus()} returns {@link #STATUS_SUCCESS}, otherwise will throw an
     * exception.
     */
    public long getRangingTimestampUs() {
        if (mStatus != STATUS_SUCCESS) {
            throw new IllegalStateException(
                    "getRangingTimestamp(): invoked on an invalid result: getStatus()=" + mStatus);
        }
        return mTimestamp;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mStatus);
        if (mMac == null) {
            dest.writeBoolean(false);
        } else {
            dest.writeBoolean(true);
            mMac.writeToParcel(dest, flags);
        }
        if (mPeerHandle == null) {
            dest.writeBoolean(false);
        } else {
            dest.writeBoolean(true);
            dest.writeInt(mPeerHandle.peerId);
        }
        dest.writeInt(mDistanceMm);
        dest.writeInt(mDistanceStdDevMm);
        dest.writeInt(mRssi);
        if (mLci == null) {
            dest.writeBoolean(false);
        } else {
            dest.writeBoolean(true);
            mLci.writeToParcel(dest, flags);
        }
        if (mLcr == null) {
            dest.writeBoolean(false);
        } else {
            dest.writeBoolean(true);
            mLcr.writeToParcel(dest, flags);
        }
        dest.writeLong(mTimestamp);
    }

    public static final Creator<RangingResult> CREATOR = new Creator<RangingResult>() {
        @Override
        public RangingResult[] newArray(int size) {
            return new RangingResult[size];
        }

        @Override
        public RangingResult createFromParcel(Parcel in) {
            int status = in.readInt();
            boolean macAddressPresent = in.readBoolean();
            MacAddress mac = null;
            if (macAddressPresent) {
                mac = MacAddress.CREATOR.createFromParcel(in);
            }
            boolean peerHandlePresent = in.readBoolean();
            PeerHandle peerHandle = null;
            if (peerHandlePresent) {
                peerHandle = new PeerHandle(in.readInt());
            }
            int distanceMm = in.readInt();
            int distanceStdDevMm = in.readInt();
            int rssi = in.readInt();
            boolean lciPresent = in.readBoolean();
            LocationConfigurationInformation lci = null;
            if (lciPresent) {
                lci = LocationConfigurationInformation.CREATOR.createFromParcel(in);
            }
            boolean lcrPresent = in.readBoolean();
            LocationCivic lcr = null;
            if (lcrPresent) {
                lcr = LocationCivic.CREATOR.createFromParcel(in);
            }
            long timestamp = in.readLong();
            if (peerHandlePresent) {
                return new RangingResult(status, peerHandle, distanceMm, distanceStdDevMm, rssi,
                        lci, lcr, timestamp);
            } else {
                return new RangingResult(status, mac, distanceMm, distanceStdDevMm, rssi,
                        lci, lcr, timestamp);
            }
        }
    };

    /** @hide */
    @Override
    public String toString() {
        return new StringBuilder("RangingResult: [status=").append(mStatus).append(", mac=").append(
                mMac).append(", peerHandle=").append(
                mPeerHandle == null ? "<null>" : mPeerHandle.peerId).append(", distanceMm=").append(
                mDistanceMm).append(", distanceStdDevMm=").append(mDistanceStdDevMm).append(
                ", rssi=").append(mRssi).append(", lci=").append(mLci).append(", lcr=").append(
                mLcr).append(", timestamp=").append(mTimestamp).append("]").toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof RangingResult)) {
            return false;
        }

        RangingResult lhs = (RangingResult) o;

        return mStatus == lhs.mStatus && Objects.equals(mMac, lhs.mMac) && Objects.equals(
                mPeerHandle, lhs.mPeerHandle) && mDistanceMm == lhs.mDistanceMm
                && mDistanceStdDevMm == lhs.mDistanceStdDevMm && mRssi == lhs.mRssi
                && Objects.equals(mLci, lhs.mLci) && Objects.equals(mLcr, lhs.mLcr)
                && mTimestamp == lhs.mTimestamp;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatus, mMac, mPeerHandle, mDistanceMm, mDistanceStdDevMm, mRssi,
                mLci, mLcr, mTimestamp);
    }
}
