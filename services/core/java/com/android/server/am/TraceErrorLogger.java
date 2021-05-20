/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.server.am;

import android.os.Trace;
import android.provider.DeviceConfig;

import java.util.UUID;

/**
 * Adds a unique id to a trace.
 *
 * @hide
 */
class TraceErrorLogger {
    private static final String COUNTER_PREFIX = "ErrorId:";
    private static final String ADD_ERROR_ID = "add_error_id";
    private static final int PLACEHOLDER_VALUE = 1;

    public boolean isAddErrorIdEnabled() {
        return DeviceConfig
                .getBoolean(DeviceConfig.NAMESPACE_TRACE_ERROR_LOGGER, ADD_ERROR_ID,
                        false);
    }

    /**
     * Generates a unique id with which to tag a trace.
     */
    public UUID generateErrorId() {
        return UUID.randomUUID();
    }

    /**
     * Pushes a counter containing a unique id and a label {@link #COUNTER_PREFIX} so that traces
     * can be uniquely identified. We also add the same id to the dropbox entry of the error, so
     * that we can join the trace and the error server-side.
     *
     * @param errorId The unique id with which to tag the trace.
     */
    public void addErrorIdToTrace(UUID errorId) {
        Trace.traceCounter(Trace.TRACE_TAG_ACTIVITY_MANAGER, COUNTER_PREFIX + errorId.toString(),
                PLACEHOLDER_VALUE);
    }
}
