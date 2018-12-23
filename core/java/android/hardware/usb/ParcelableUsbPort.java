/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.usb;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.Immutable;

/**
 * A parcelable wrapper to send UsbPorts over binders.
 *
 * @hide
 */
@Immutable
public final class ParcelableUsbPort implements Parcelable {
    private final @NonNull String mId;
    private final int mSupportedModes;

    private ParcelableUsbPort(@NonNull String id, int supportedModes) {
        mId = id;
        mSupportedModes = supportedModes;
    }

    /**
     * Create the parcelable version of a {@link UsbPort}.
     *
     * @param port The port to create a parcealable version of
     *
     * @return The parcelable version of the port
     */
    public static @NonNull ParcelableUsbPort of(@NonNull UsbPort port) {
        return new ParcelableUsbPort(port.getId(), port.getSupportedModes());
    }

    /**
     * Create a {@link UsbPort} from this object.
     *
     * @param usbManager A link to the usbManager in the current context
     *
     * @return The UsbPort for this object
     */
    public @NonNull UsbPort getUsbPort(@NonNull UsbManager usbManager) {
        return new UsbPort(usbManager, mId, mSupportedModes);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeInt(mSupportedModes);
    }

    public static final Creator<ParcelableUsbPort> CREATOR =
            new Creator<ParcelableUsbPort>() {
                @Override
                public ParcelableUsbPort createFromParcel(Parcel in) {
                    String id = in.readString();
                    int supportedModes = in.readInt();
                    return new ParcelableUsbPort(id, supportedModes);
                }

                @Override
                public ParcelableUsbPort[] newArray(int size) {
                    return new ParcelableUsbPort[size];
                }
            };
}
