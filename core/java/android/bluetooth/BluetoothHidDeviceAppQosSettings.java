/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents the Quality of Service (QoS) settings for a Bluetooth HID Device
 * application.
 *
 * The BluetoothHidDevice framework will update the L2CAP QoS settings for the
 * app during registration.
 *
 * {@see BluetoothHidDevice}
 *
 * {@hide}
 */
public final class BluetoothHidDeviceAppQosSettings implements Parcelable {

    public final int serviceType;
    public final int tokenRate;
    public final int tokenBucketSize;
    public final int peakBandwidth;
    public final int latency;
    public final int delayVariation;

    public static final int SERVICE_NO_TRAFFIC = 0x00;
    public static final int SERVICE_BEST_EFFORT = 0x01;
    public static final int SERVICE_GUARANTEED = 0x02;

    public static final int MAX = (int) 0xffffffff;

    public BluetoothHidDeviceAppQosSettings(int serviceType, int tokenRate, int tokenBucketSize,
            int peakBandwidth, int latency, int delayVariation) {
        this.serviceType = serviceType;
        this.tokenRate = tokenRate;
        this.tokenBucketSize = tokenBucketSize;
        this.peakBandwidth = peakBandwidth;
        this.latency = latency;
        this.delayVariation = delayVariation;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BluetoothHidDeviceAppQosSettings) {
            BluetoothHidDeviceAppQosSettings qos = (BluetoothHidDeviceAppQosSettings) o;
            return false;
        }
        return false;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<BluetoothHidDeviceAppQosSettings> CREATOR =
            new Parcelable.Creator<BluetoothHidDeviceAppQosSettings>() {

                @Override
                public BluetoothHidDeviceAppQosSettings createFromParcel(Parcel in) {

                    return new BluetoothHidDeviceAppQosSettings(
                            in.readInt(),
                            in.readInt(),
                            in.readInt(),
                            in.readInt(),
                            in.readInt(),
                            in.readInt());
                }

                @Override
                public BluetoothHidDeviceAppQosSettings[] newArray(int size) {
                    return new BluetoothHidDeviceAppQosSettings[size];
                }
            };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(serviceType);
        out.writeInt(tokenRate);
        out.writeInt(tokenBucketSize);
        out.writeInt(peakBandwidth);
        out.writeInt(latency);
        out.writeInt(delayVariation);
    }

    /** @return an int array representation of this instance */
    public int[] toArray() {
        return new int[] {
                serviceType, tokenRate, tokenBucketSize, peakBandwidth, latency, delayVariation
        };
    }
}
