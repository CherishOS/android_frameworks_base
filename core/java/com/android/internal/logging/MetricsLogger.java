/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.internal.logging;

import android.content.Context;
import android.os.Build;
import android.view.View;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

/**
 * Log all the things.
 *
 * @hide
 */
public class MetricsLogger {
    // define metric categories in frameworks/base/proto/src/metrics_constants.proto.

    public static final int VIEW_UNKNOWN = MetricsEvent.VIEW_UNKNOWN;

    public static void visible(Context context, int category) throws IllegalArgumentException {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        EventLogTags.writeSysuiViewVisibility(category, 100);
        EventLogTags.writeSysuiMultiAction(
                new LogBuilder(category)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .serialize());
    }

    public static void hidden(Context context, int category) throws IllegalArgumentException {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        EventLogTags.writeSysuiViewVisibility(category, 0);
        EventLogTags.writeSysuiMultiAction(
                new LogBuilder(category)
                        .setType(MetricsEvent.TYPE_CLOSE)
                        .serialize());
    }

    public static void visibility(Context context, int category, boolean visibile)
            throws IllegalArgumentException {
        if (visibile) {
            visible(context, category);
        } else {
            hidden(context, category);
        }
    }

    public static void visibility(Context context, int category, int vis)
            throws IllegalArgumentException {
        visibility(context, category, vis == View.VISIBLE);
    }

    public static void action(Context context, int category) {
        EventLogTags.writeSysuiAction(category, "");
        EventLogTags.writeSysuiMultiAction(
                new LogBuilder(category)
                        .setType(MetricsEvent.TYPE_ACTION)
                        .serialize());
    }

    public static void action(Context context, int category, int value) {
        EventLogTags.writeSysuiAction(category, Integer.toString(value));
        EventLogTags.writeSysuiMultiAction(
                new LogBuilder(category)
                        .setType(MetricsEvent.TYPE_ACTION)
                        .setSubtype(value)
                        .serialize());
    }

    public static void action(Context context, int category, boolean value) {
        EventLogTags.writeSysuiAction(category, Boolean.toString(value));
        EventLogTags.writeSysuiMultiAction(
                new LogBuilder(category)
                        .setType(MetricsEvent.TYPE_ACTION)
                        .setSubtype(value ? 1 : 0)
                        .serialize());
    }

    public static void action(LogBuilder content) {
        //EventLog.writeEvent(524292, content.serialize());
        // Below would be the *right* way to do this, using the generated
        // EventLogTags method, but that doesn't work.
        if (content.getType() == MetricsEvent.TYPE_UNKNOWN) {
            content.setType(MetricsEvent.TYPE_ACTION);
        }
        EventLogTags.writeSysuiMultiAction(content.serialize());
    }


    public static void action(Context context, int category, String pkg) {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        EventLogTags.writeSysuiAction(category, pkg);
        EventLogTags.writeSysuiMultiAction(new LogBuilder(category)
                .setType(MetricsEvent.TYPE_ACTION)
                .setPackageName(pkg)
                .serialize());
    }

    /** Add an integer value to the monotonically increasing counter with the given name. */
    public static void count(Context context, String name, int value) {
        EventLogTags.writeSysuiCount(name, value);
        EventLogTags.writeSysuiMultiAction(
                new LogBuilder(MetricsEvent.RESERVED_FOR_LOGBUILDER_COUNTER)
                        .setCounterName(name)
                        .setCounterValue(value)
                        .serialize());
    }

    /** Increment the bucket with the integer label on the histogram with the given name. */
    public static void histogram(Context context, String name, int bucket) {
        EventLogTags.writeSysuiHistogram(name, bucket);
        EventLogTags.writeSysuiMultiAction(
                new LogBuilder(MetricsEvent.RESERVED_FOR_LOGBUILDER_HISTOGRAM)
                        .setCounterName(name)
                        .setCounterBucket(bucket)
                        .setCounterValue(1)
                        .serialize());
    }
}
