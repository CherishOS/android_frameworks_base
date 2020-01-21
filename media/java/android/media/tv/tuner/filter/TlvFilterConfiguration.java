/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.filter;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.media.tv.tuner.TunerUtils;

/**
 * Filter configuration for a TLV filter.
 *
 * @hide
 */
@SystemApi
public class TlvFilterConfiguration extends FilterConfiguration {
    private final int mPacketType;
    private final boolean mIsCompressedIpPacket;
    private final boolean mPassthrough;

    private TlvFilterConfiguration(Settings settings, int packetType, boolean isCompressed,
            boolean passthrough) {
        super(settings);
        mPacketType = packetType;
        mIsCompressedIpPacket = isCompressed;
        mPassthrough = passthrough;
    }

    @Override
    public int getType() {
        return Filter.TYPE_TLV;
    }

    /**
     * Gets packet type.
     */
    @FilterConfiguration.PacketType
    public int getPacketType() {
        return mPacketType;
    }
    /**
     * Checks whether the data is compressed IP packet.
     *
     * @return {@code true} if the filtered data is compressed IP packet; {@code false} otherwise.
     */
    public boolean isCompressedIpPacket() {
        return mIsCompressedIpPacket;
    }
    /**
     * Checks whether it's passthrough.
     *
     * @return {@code true} if the data from TLV subtype go to next filter directly;
     *         {@code false} otherwise.
     */
    public boolean isPassthrough() {
        return mPassthrough;
    }

    /**
     * Creates a builder for {@link TlvFilterConfiguration}.
     *
     * @param context the context of the caller.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @NonNull
    public static Builder builder(@NonNull Context context) {
        TunerUtils.checkTunerPermission(context);
        return new Builder();
    }

    /**
     * Builder for {@link TlvFilterConfiguration}.
     */
    public static class Builder extends FilterConfiguration.Builder<Builder> {
        private int mPacketType;
        private boolean mIsCompressedIpPacket;
        private boolean mPassthrough;

        private Builder() {
        }

        /**
         * Sets packet type.
         */
        @NonNull
        public Builder setPacketType(@FilterConfiguration.PacketType int packetType) {
            mPacketType = packetType;
            return this;
        }
        /**
         * Sets whether the data is compressed IP packet.
         */
        @NonNull
        public Builder setIsCompressedIpPacket(boolean isCompressedIpPacket) {
            mIsCompressedIpPacket = isCompressedIpPacket;
            return this;
        }
        /**
         * Sets whether it's passthrough.
         */
        @NonNull
        public Builder setPassthrough(boolean passthrough) {
            mPassthrough = passthrough;
            return this;
        }

        /**
         * Builds a {@link TlvFilterConfiguration} object.
         */
        @NonNull
        public TlvFilterConfiguration build() {
            return new TlvFilterConfiguration(
                    mSettings, mPacketType, mIsCompressedIpPacket, mPassthrough);
        }

        @Override
        Builder self() {
            return this;
        }
    }
}
