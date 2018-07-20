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
 * limitations under the License
 */

package com.android.server.backup.testing;

import static com.google.common.truth.Truth.assertThat;

import com.android.server.testing.shadows.ShadowEventLog;

import org.robolectric.shadows.ShadowLog;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

public class TestUtils {
    /** Reset logcat with {@link ShadowLog#reset()} before the test case. */
    public static void assertLogcatAtMost(String tag, int level) {
        assertThat(ShadowLog.getLogsForTag(tag).stream().allMatch(logItem -> logItem.type <= level))
                .named("All logs <= " + level)
                .isTrue();
    }

    /** Reset logcat with {@link ShadowLog#reset()} before the test case. */
    public static void assertLogcatAtLeast(String tag, int level) {
        assertThat(ShadowLog.getLogsForTag(tag).stream().anyMatch(logItem -> logItem.type >= level))
                .named("Any log >= " + level)
                .isTrue();
    }

    public static void assertLogcatContains(String tag, Predicate<ShadowLog.LogItem> predicate) {
        assertThat(ShadowLog.getLogsForTag(tag).stream().anyMatch(predicate)).isTrue();
    }

    /** Declare shadow {@link ShadowEventLog} to use this. */
    public static void assertEventLogged(int tag, Object... values) {
        assertThat(ShadowEventLog.getEntries())
                .named("Event logs")
                .contains(new ShadowEventLog.Entry(tag, Arrays.asList(values)));
    }

    /** Declare shadow {@link ShadowEventLog} to use this. */
    public static void assertEventNotLogged(int tag, Object... values) {
        assertThat(ShadowEventLog.getEntries())
                .named("Event logs")
                .doesNotContain(new ShadowEventLog.Entry(tag, Arrays.asList(values)));
    }

    /**
     * Calls {@link Runnable#run()} and returns if no exception is thrown. Otherwise, if the
     * exception is unchecked, rethrow it; if it's checked wrap in a {@link RuntimeException} and
     * throw.
     *
     * <p><b>Warning:</b>DON'T use this outside tests. A wrapped checked exception is just a failure
     * in a test.
     */
    public static void uncheck(ThrowingRunnable runnable) {
        try {
            runnable.runOrThrow();
        } catch (Exception e) {
            throw wrapIfChecked(e);
        }
    }

    /**
     * Calls {@link Callable#call()} and returns the value if no exception is thrown. Otherwise, if
     * the exception is unchecked, rethrow it; if it's checked wrap in a {@link RuntimeException}
     * and throw.
     *
     * <p><b>Warning:</b>DON'T use this outside tests. A wrapped checked exception is just a failure
     * in a test.
     */
    public static <T> T uncheck(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw wrapIfChecked(e);
        }
    }

    /**
     * Wrap {@code e} in a {@link RuntimeException} only if it's not one already, in which case it's
     * returned.
     */
    public static RuntimeException wrapIfChecked(Exception e) {
        if (e instanceof RuntimeException) {
            return (RuntimeException) e;
        }
        return new RuntimeException(e);
    }

    /** An equivalent of {@link Runnable} that allows throwing checked exceptions. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void runOrThrow() throws Exception;
    }

    private TestUtils() {}
}
