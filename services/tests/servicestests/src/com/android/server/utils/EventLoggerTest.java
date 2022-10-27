/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.utils;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Parameterized;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collection;

@SmallTest
@RunWith(Enclosed.class)
public class EventLoggerTest {

    private static final int EVENTS_LOGGER_SIZE = 3;
    private static final String EVENTS_LOGGER_TAG = "TestLogger";

    private static final TestEvent TEST_EVENT_1 = new TestEvent();
    private static final TestEvent TEST_EVENT_2 = new TestEvent();
    private static final TestEvent TEST_EVENT_3 = new TestEvent();
    private static final TestEvent TEST_EVENT_4 = new TestEvent();
    private static final TestEvent TEST_EVENT_5 = new TestEvent();

    @RunWith(JUnit4.class)
    public static class BasicOperationsTest {

        private StringWriter mTestStringWriter;
        private PrintWriter mTestPrintWriter;

        private EventLogger mEventLogger;

        @Before
        public void setUp() {
            mTestStringWriter = new StringWriter();
            mTestPrintWriter = new PrintWriter(mTestStringWriter);
            mEventLogger = new EventLogger(EVENTS_LOGGER_SIZE, EVENTS_LOGGER_TAG);
        }

        @Test
        public void testThatConsumeOfEmptyLoggerProducesEmptyList() {
            mEventLogger.dump(mTestPrintWriter);
            assertThat(mTestStringWriter.toString()).isEmpty();
        }
    }

    @RunWith(Parameterized.class)
    public static class LoggingOperationTest {

        @Parameterized.Parameters
        public static Collection<Object[]> data() {
            return Arrays.asList(new Object[][] {
                    {
                        // insertion order, max size is 3
                        new EventLogger.Event[] { TEST_EVENT_1, TEST_EVENT_2 },
                        // expected events
                        new EventLogger.Event[] { TEST_EVENT_2, TEST_EVENT_1 }
                    },
                    {
                        // insertion order, max size is 3
                        new EventLogger.Event[] { TEST_EVENT_1, TEST_EVENT_3, TEST_EVENT_2 },
                        // expected events
                        new EventLogger.Event[] { TEST_EVENT_2, TEST_EVENT_3, TEST_EVENT_1 }
                    },
                    {
                        // insertion order, max size is 3
                        new EventLogger.Event[] { TEST_EVENT_1, TEST_EVENT_2, TEST_EVENT_3,
                            TEST_EVENT_4 },
                        // expected events
                        new EventLogger.Event[] { TEST_EVENT_4, TEST_EVENT_3, TEST_EVENT_2 }
                    },
                    {
                        // insertion order, max size is 3
                        new EventLogger.Event[] { TEST_EVENT_1, TEST_EVENT_2, TEST_EVENT_3,
                            TEST_EVENT_4, TEST_EVENT_5 },
                        // expected events
                        new EventLogger.Event[] { TEST_EVENT_5, TEST_EVENT_4, TEST_EVENT_3 }
                    }
            });
        }

        private EventLogger mEventLogger;

        private final StringWriter mTestStringWriter;
        private final PrintWriter mTestPrintWriter;
        private final EventLogger.Event[] mEventsToInsert;
        private final EventLogger.Event[] mExpectedEvents;

        public LoggingOperationTest(EventLogger.Event[] eventsToInsert,
                EventLogger.Event[] expectedEvents) {
            mTestStringWriter = new StringWriter();
            mTestPrintWriter = new PrintWriter(mTestStringWriter);
            mEventsToInsert = eventsToInsert;
            mExpectedEvents = expectedEvents;
        }

        @Before
        public void setUp() {
            mEventLogger = new EventLogger(EVENTS_LOGGER_SIZE, EVENTS_LOGGER_TAG);
        }

        @Test
        public void testThatLoggingWorksAsExpected() {
            for (EventLogger.Event event: mEventsToInsert) {
                mEventLogger.enqueue(event);
            }

            mEventLogger.dump(mTestPrintWriter);

            assertThat(mTestStringWriter.toString())
                    .isEqualTo(convertEventsToString(mExpectedEvents));
        }

    }

    private static String convertEventsToString(EventLogger.Event[] events) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);

        printWriter.println("Events log: " + EVENTS_LOGGER_TAG);

        for (EventLogger.Event event: events) {
            printWriter.println(event.toString());
        }

        return stringWriter.toString();
    }

    private static class TestEvent extends EventLogger.Event {

        @Override
        public String eventToString() {
            return getClass().getName() + "@" + Integer.toHexString(hashCode());
        }
    }
}
