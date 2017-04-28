package com.android.internal.os;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
        BatteryStatsDualTimerTest.class,
        BatteryStatsDurationTimerTest.class,
        BatteryStatsSamplingTimerTest.class,
        BatteryStatsServTest.class,
        BatteryStatsStopwatchTimerTest.class,
        BatteryStatsTimeBaseTest.class,
        BatteryStatsTimerTest.class,
        BatteryStatsUidTest.class,
        BatteryStatsSensorTest.class,
        BatteryStatsBackgroundStatsTest.class,
        BatteryStatsNoteTest.class,
    })
public class BatteryStatsTests {
}

