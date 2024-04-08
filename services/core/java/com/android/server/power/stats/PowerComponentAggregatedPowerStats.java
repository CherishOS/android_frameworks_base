/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.power.stats;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;

import com.android.internal.os.PowerStats;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.IntConsumer;

/**
 * Aggregated power stats for a specific power component (e.g. CPU, WiFi, etc). This class
 * treats stats as arrays of nonspecific longs. Subclasses contain specific logic to interpret those
 * longs and use them for calculations such as power attribution. They may use meta-data supplied
 * as part of the {@link PowerStats.Descriptor}.
 */
class PowerComponentAggregatedPowerStats {
    static final String XML_TAG_POWER_COMPONENT = "power_component";
    static final String XML_ATTR_ID = "id";
    private static final String XML_TAG_DEVICE_STATS = "device-stats";
    private static final String XML_TAG_STATE_STATS = "state-stats";
    private static final String XML_ATTR_KEY = "key";
    private static final String XML_TAG_UID_STATS = "uid-stats";
    private static final String XML_ATTR_UID = "uid";
    private static final long UNKNOWN = -1;

    public final int powerComponentId;
    private final MultiStateStats.States[] mDeviceStateConfig;
    private final MultiStateStats.States[] mUidStateConfig;
    @NonNull
    private final AggregatedPowerStatsConfig.PowerComponent mConfig;
    private final int[] mDeviceStates;

    private MultiStateStats.Factory mStatsFactory;
    private MultiStateStats.Factory mStateStatsFactory;
    private MultiStateStats.Factory mUidStatsFactory;
    private PowerStats.Descriptor mPowerStatsDescriptor;
    private long mPowerStatsTimestamp;
    private MultiStateStats mDeviceStats;
    private final SparseArray<MultiStateStats> mStateStats = new SparseArray<>();
    private final SparseArray<UidStats> mUidStats = new SparseArray<>();

    private static class UidStats {
        public int[] states;
        public MultiStateStats stats;
    }

    PowerComponentAggregatedPowerStats(AggregatedPowerStatsConfig.PowerComponent config) {
        mConfig = config;
        powerComponentId = config.getPowerComponentId();
        mDeviceStateConfig = config.getDeviceStateConfig();
        mUidStateConfig = config.getUidStateConfig();
        mDeviceStates = new int[mDeviceStateConfig.length];
        mPowerStatsTimestamp = UNKNOWN;
    }

    @NonNull
    public AggregatedPowerStatsConfig.PowerComponent getConfig() {
        return mConfig;
    }

    @Nullable
    public PowerStats.Descriptor getPowerStatsDescriptor() {
        return mPowerStatsDescriptor;
    }

    void setState(@AggregatedPowerStatsConfig.TrackedState int stateId, int state,
            long timestampMs) {
        if (mDeviceStats == null) {
            createDeviceStats(timestampMs);
        }

        mDeviceStates[stateId] = state;

        if (mDeviceStateConfig[stateId].isTracked()) {
            if (mDeviceStats != null) {
                mDeviceStats.setState(stateId, state, timestampMs);
            }
            for (int i = mStateStats.size() - 1; i >= 0; i--) {
                MultiStateStats stateStats = mStateStats.valueAt(i);
                stateStats.setState(stateId, state, timestampMs);
            }
        }

        if (mUidStateConfig[stateId].isTracked()) {
            for (int i = mUidStats.size() - 1; i >= 0; i--) {
                PowerComponentAggregatedPowerStats.UidStats uidStats = mUidStats.valueAt(i);
                if (uidStats.stats == null) {
                    createUidStats(uidStats, timestampMs);
                }

                uidStats.states[stateId] = state;
                if (uidStats.stats != null) {
                    uidStats.stats.setState(stateId, state, timestampMs);
                }
            }
        }
    }

    void setUidState(int uid, @AggregatedPowerStatsConfig.TrackedState int stateId, int state,
            long timestampMs) {
        if (!mUidStateConfig[stateId].isTracked()) {
            return;
        }

        UidStats uidStats = getUidStats(uid);
        if (uidStats.stats == null) {
            createUidStats(uidStats, timestampMs);
        }

        uidStats.states[stateId] = state;

        if (uidStats.stats != null) {
            uidStats.stats.setState(stateId, state, timestampMs);
        }
    }

    void setDeviceStats(@AggregatedPowerStatsConfig.TrackedState int[] states, long[] values) {
        mDeviceStats.setStats(states, values);
    }

    void setUidStats(int uid, @AggregatedPowerStatsConfig.TrackedState int[] states,
            long[] values) {
        UidStats uidStats = getUidStats(uid);
        uidStats.stats.setStats(states, values);
    }

    boolean isCompatible(PowerStats powerStats) {
        return mPowerStatsDescriptor == null || mPowerStatsDescriptor.equals(powerStats.descriptor);
    }

    void addPowerStats(PowerStats powerStats, long timestampMs) {
        mPowerStatsDescriptor = powerStats.descriptor;

        if (mDeviceStats == null) {
            createDeviceStats(timestampMs);
        }

        for (int i = powerStats.stateStats.size() - 1; i >= 0; i--) {
            int key = powerStats.stateStats.keyAt(i);
            MultiStateStats stateStats = mStateStats.get(key);
            if (stateStats == null) {
                stateStats = createStateStats(key, timestampMs);
            }
            stateStats.increment(powerStats.stateStats.valueAt(i), timestampMs);
        }
        mDeviceStats.increment(powerStats.stats, timestampMs);

        for (int i = powerStats.uidStats.size() - 1; i >= 0; i--) {
            int uid = powerStats.uidStats.keyAt(i);
            PowerComponentAggregatedPowerStats.UidStats uidStats = getUidStats(uid);
            if (uidStats.stats == null) {
                createUidStats(uidStats, timestampMs);
            }
            uidStats.stats.increment(powerStats.uidStats.valueAt(i), timestampMs);
        }

        mPowerStatsTimestamp = timestampMs;
    }

    void reset() {
        mStatsFactory = null;
        mUidStatsFactory = null;
        mDeviceStats = null;
        mStateStats.clear();
        for (int i = mUidStats.size() - 1; i >= 0; i--) {
            mUidStats.valueAt(i).stats = null;
        }
    }

    private UidStats getUidStats(int uid) {
        UidStats uidStats = mUidStats.get(uid);
        if (uidStats == null) {
            uidStats = new UidStats();
            uidStats.states = new int[mUidStateConfig.length];
            for (int stateId = 0; stateId < mUidStateConfig.length; stateId++) {
                if (mUidStateConfig[stateId].isTracked()
                        && stateId < mDeviceStateConfig.length
                        && mDeviceStateConfig[stateId].isTracked()) {
                    uidStats.states[stateId] = mDeviceStates[stateId];
                }
            }
            mUidStats.put(uid, uidStats);
        }
        return uidStats;
    }

    void collectUids(Collection<Integer> uids) {
        for (int i = mUidStats.size() - 1; i >= 0; i--) {
            if (mUidStats.valueAt(i).stats != null) {
                uids.add(mUidStats.keyAt(i));
            }
        }
    }

    boolean getDeviceStats(long[] outValues, int[] deviceStates) {
        if (deviceStates.length != mDeviceStateConfig.length) {
            throw new IllegalArgumentException(
                    "Invalid number of tracked states: " + deviceStates.length
                    + " expected: " + mDeviceStateConfig.length);
        }
        if (mDeviceStats != null) {
            mDeviceStats.getStats(outValues, deviceStates);
            return true;
        }
        return false;
    }

    boolean getStateStats(long[] outValues, int key, int[] deviceStates) {
        if (deviceStates.length != mDeviceStateConfig.length) {
            throw new IllegalArgumentException(
                    "Invalid number of tracked states: " + deviceStates.length
                            + " expected: " + mDeviceStateConfig.length);
        }
        MultiStateStats stateStats = mStateStats.get(key);
        if (stateStats != null) {
            stateStats.getStats(outValues, deviceStates);
            return true;
        }
        return false;
    }

    void forEachStateStatsKey(IntConsumer consumer) {
        for (int i = mStateStats.size() - 1; i >= 0; i--) {
            consumer.accept(mStateStats.keyAt(i));
        }
    }

    boolean getUidStats(long[] outValues, int uid, int[] uidStates) {
        if (uidStates.length != mUidStateConfig.length) {
            throw new IllegalArgumentException(
                    "Invalid number of tracked states: " + uidStates.length
                    + " expected: " + mUidStateConfig.length);
        }
        UidStats uidStats = mUidStats.get(uid);
        if (uidStats != null && uidStats.stats != null) {
            uidStats.stats.getStats(outValues, uidStates);
            return true;
        }
        return false;
    }

    private void createDeviceStats(long timestampMs) {
        if (mStatsFactory == null) {
            if (mPowerStatsDescriptor == null) {
                return;
            }
            mStatsFactory = new MultiStateStats.Factory(
                    mPowerStatsDescriptor.statsArrayLength, mDeviceStateConfig);
        }

        mDeviceStats = mStatsFactory.create();
        if (mPowerStatsTimestamp != UNKNOWN) {
            timestampMs = mPowerStatsTimestamp;
        }
        if (timestampMs != UNKNOWN) {
            for (int stateId = 0; stateId < mDeviceStateConfig.length; stateId++) {
                int state = mDeviceStates[stateId];
                mDeviceStats.setState(stateId, state, timestampMs);
                for (int i = mStateStats.size() - 1; i >= 0; i--) {
                    MultiStateStats stateStats = mStateStats.valueAt(i);
                    stateStats.setState(stateId, state, timestampMs);
                }
            }
        }
    }

    private MultiStateStats createStateStats(int key, long timestampMs) {
        if (mStateStatsFactory == null) {
            if (mPowerStatsDescriptor == null) {
                return null;
            }
            mStateStatsFactory = new MultiStateStats.Factory(
                    mPowerStatsDescriptor.stateStatsArrayLength, mDeviceStateConfig);
        }

        MultiStateStats stateStats = mStateStatsFactory.create();
        mStateStats.put(key, stateStats);
        if (mDeviceStats != null) {
            stateStats.copyStatesFrom(mDeviceStats);
        }

        return stateStats;
    }

    private void createUidStats(UidStats uidStats, long timestampMs) {
        if (mUidStatsFactory == null) {
            if (mPowerStatsDescriptor == null) {
                return;
            }
            mUidStatsFactory = new MultiStateStats.Factory(
                    mPowerStatsDescriptor.uidStatsArrayLength, mUidStateConfig);
        }

        uidStats.stats = mUidStatsFactory.create();

        if (mPowerStatsTimestamp != UNKNOWN) {
            timestampMs = mPowerStatsTimestamp;
        }
        if (timestampMs != UNKNOWN) {
            for (int stateId = 0; stateId < mUidStateConfig.length; stateId++) {
                uidStats.stats.setState(stateId, uidStats.states[stateId], timestampMs);
            }
        }
    }

    public void writeXml(TypedXmlSerializer serializer) throws IOException {
        // No stats aggregated - can skip writing XML altogether
        if (mPowerStatsDescriptor == null) {
            return;
        }

        serializer.startTag(null, XML_TAG_POWER_COMPONENT);
        serializer.attributeInt(null, XML_ATTR_ID, powerComponentId);
        mPowerStatsDescriptor.writeXml(serializer);

        if (mDeviceStats != null) {
            serializer.startTag(null, XML_TAG_DEVICE_STATS);
            mDeviceStats.writeXml(serializer);
            serializer.endTag(null, XML_TAG_DEVICE_STATS);
        }

        for (int i = 0; i < mStateStats.size(); i++) {
            serializer.startTag(null, XML_TAG_STATE_STATS);
            serializer.attributeInt(null, XML_ATTR_KEY, mStateStats.keyAt(i));
            mStateStats.valueAt(i).writeXml(serializer);
            serializer.endTag(null, XML_TAG_STATE_STATS);
        }

        for (int i = mUidStats.size() - 1; i >= 0; i--) {
            int uid = mUidStats.keyAt(i);
            UidStats uidStats = mUidStats.valueAt(i);
            if (uidStats.stats != null) {
                serializer.startTag(null, XML_TAG_UID_STATS);
                serializer.attributeInt(null, XML_ATTR_UID, uid);
                uidStats.stats.writeXml(serializer);
                serializer.endTag(null, XML_TAG_UID_STATS);
            }
        }

        serializer.endTag(null, XML_TAG_POWER_COMPONENT);
        serializer.flush();
    }

    public boolean readFromXml(TypedXmlPullParser parser) throws XmlPullParserException,
            IOException {
        String outerTag = parser.getName();
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT
                && !(eventType == XmlPullParser.END_TAG && parser.getName().equals(outerTag))) {
            if (eventType == XmlPullParser.START_TAG) {
                switch (parser.getName()) {
                    case PowerStats.Descriptor.XML_TAG_DESCRIPTOR:
                        mPowerStatsDescriptor = PowerStats.Descriptor.createFromXml(parser);
                        if (mPowerStatsDescriptor == null) {
                            return false;
                        }
                        break;
                    case XML_TAG_DEVICE_STATS:
                        if (mDeviceStats == null) {
                            createDeviceStats(UNKNOWN);
                        }
                        if (!mDeviceStats.readFromXml(parser)) {
                            return false;
                        }
                        break;
                    case XML_TAG_STATE_STATS:
                        int key = parser.getAttributeInt(null, XML_ATTR_KEY);
                        MultiStateStats stats = mStateStats.get(key);
                        if (stats == null) {
                            stats = createStateStats(key, UNKNOWN);
                        }
                        if (!stats.readFromXml(parser)) {
                            return false;
                        }
                        break;
                    case XML_TAG_UID_STATS:
                        int uid = parser.getAttributeInt(null, XML_ATTR_UID);
                        UidStats uidStats = getUidStats(uid);
                        if (uidStats.stats == null) {
                            createUidStats(uidStats, UNKNOWN);
                        }
                        if (!uidStats.stats.readFromXml(parser)) {
                            return false;
                        }
                        break;
                }
            }
            eventType = parser.next();
        }
        return true;
    }

    void dumpDevice(IndentingPrintWriter ipw) {
        if (mDeviceStats != null) {
            ipw.println(mPowerStatsDescriptor.name);
            ipw.increaseIndent();
            mDeviceStats.dump(ipw, stats ->
                    mConfig.getProcessor().deviceStatsToString(mPowerStatsDescriptor, stats));
            ipw.decreaseIndent();
        }

        if (mStateStats.size() != 0) {
            ipw.increaseIndent();
            ipw.println(mPowerStatsDescriptor.name + " states");
            ipw.increaseIndent();
            for (int i = 0; i < mStateStats.size(); i++) {
                int key = mStateStats.keyAt(i);
                MultiStateStats stateStats = mStateStats.valueAt(i);
                stateStats.dump(ipw, stats ->
                        mConfig.getProcessor().stateStatsToString(mPowerStatsDescriptor, key,
                                stats));
            }
            ipw.decreaseIndent();
            ipw.decreaseIndent();
        }
    }

    void dumpUid(IndentingPrintWriter ipw, int uid) {
        UidStats uidStats = mUidStats.get(uid);
        if (uidStats != null && uidStats.stats != null) {
            ipw.println(mPowerStatsDescriptor.name);
            ipw.increaseIndent();
            uidStats.stats.dump(ipw, stats ->
                    mConfig.getProcessor().uidStatsToString(mPowerStatsDescriptor, stats));
            ipw.decreaseIndent();
        }
    }

    @Override
    public String toString() {
        StringWriter sw = new StringWriter();
        IndentingPrintWriter ipw = new IndentingPrintWriter(sw);
        ipw.increaseIndent();
        dumpDevice(ipw);
        ipw.decreaseIndent();

        int[] uids = new int[mUidStats.size()];
        for (int i = uids.length - 1; i >= 0; i--) {
            uids[i] = mUidStats.keyAt(i);
        }
        Arrays.sort(uids);
        for (int uid : uids) {
            ipw.println(UserHandle.formatUid(uid));
            ipw.increaseIndent();
            dumpUid(ipw, uid);
            ipw.decreaseIndent();
        }

        ipw.flush();

        return sw.toString();
    }
}
