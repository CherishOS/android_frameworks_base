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

package android.util.proto;

import android.util.AggStats;
import android.util.Duration;

/**
 * This class contains a list of helper functions to write common proto in
 * //frameworks/base/core/proto/android/base directory
 */
public class ProtoUtils {

    /**
     * Dump AggStats to ProtoOutputStream
     * @hide
     */
    public static void toAggStatsProto(ProtoOutputStream proto, long fieldId,
            long min, long average, long max) {
        final long aggStatsToken = proto.start(fieldId);
        proto.write(AggStats.MIN, min);
        proto.write(AggStats.AVERAGE, average);
        proto.write(AggStats.MAX, max);
        proto.end(aggStatsToken);
    }

    /**
     * Dump Duration to ProtoOutputStream
     * @hide
     */
    public static void toDuration(ProtoOutputStream proto, long fieldId, long startMs, long endMs) {
        final long token = proto.start(fieldId);
        proto.write(Duration.START_MS, startMs);
        proto.write(Duration.END_MS, endMs);
        proto.end(token);
    }
}
