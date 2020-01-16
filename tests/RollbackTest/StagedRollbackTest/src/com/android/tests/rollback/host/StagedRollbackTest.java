/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.tests.rollback.host;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.testng.Assert.assertThrows;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Runs the staged rollback tests.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class StagedRollbackTest extends BaseHostJUnit4Test {
    private static final int NATIVE_CRASHES_THRESHOLD = 5;

    /**
     * Runs the given phase of a test by calling into the device.
     * Throws an exception if the test phase fails.
     * <p>
     * For example, <code>runPhase("testApkOnlyEnableRollback");</code>
     */
    private void runPhase(String phase) throws Exception {
        assertTrue(runDeviceTests("com.android.tests.rollback",
                    "com.android.tests.rollback.StagedRollbackTest",
                    phase));
    }

    private static final String APK_IN_APEX_TESTAPEX_NAME = "com.android.apex.apkrollback.test";

    @Before
    public void setUp() throws Exception {
        if (!getDevice().isAdbRoot()) {
            getDevice().enableAdbRoot();
        }
        getDevice().remountSystemWritable();
        getDevice().executeShellCommand(
                "rm -f /system/apex/" + APK_IN_APEX_TESTAPEX_NAME + "*.apex "
                        + "/data/apex/active/" + APK_IN_APEX_TESTAPEX_NAME + "*.apex");
        getDevice().reboot();
        runPhase("testCleanUp");
    }

    @After
    public void tearDown() throws Exception {
        runPhase("testCleanUp");

        if (!getDevice().isAdbRoot()) {
            getDevice().enableAdbRoot();
        }
        getDevice().remountSystemWritable();
        getDevice().executeShellCommand(
                "rm -f /system/apex/" + APK_IN_APEX_TESTAPEX_NAME + "*.apex "
                        + "/data/apex/active/" + APK_IN_APEX_TESTAPEX_NAME + "*.apex");
        getDevice().reboot();
    }

    /**
     * Tests watchdog triggered staged rollbacks involving only apks.
     */
    @Test
    public void testBadApkOnly() throws Exception {
        runPhase("testBadApkOnly_Phase1");
        getDevice().reboot();
        runPhase("testBadApkOnly_Phase2");

        assertThrows(AssertionError.class, () -> runPhase("testBadApkOnly_Phase3"));
        getDevice().waitForDeviceAvailable();

        runPhase("testBadApkOnly_Phase4");
    }

    @Test
    public void testNativeWatchdogTriggersRollback() throws Exception {
        runPhase("testNativeWatchdogTriggersRollback_Phase1");

        // Reboot device to activate staged package
        getDevice().reboot();

        runPhase("testNativeWatchdogTriggersRollback_Phase2");

        // crash system_server enough times to trigger a rollback
        crashProcess("system_server", NATIVE_CRASHES_THRESHOLD);

        // Rollback should be committed automatically now.
        // Give time for rollback to be committed. This could take a while,
        // because we need all of the following to happen:
        // 1. system_server comes back up and boot completes.
        // 2. Rollback health observer detects updatable crashing signal.
        // 3. Staged rollback session becomes ready.
        // 4. Device actually reboots.
        // So we give a generous timeout here.
        assertTrue(getDevice().waitForDeviceNotAvailable(TimeUnit.MINUTES.toMillis(5)));
        getDevice().waitForDeviceAvailable();

        // verify rollback committed
        runPhase("testNativeWatchdogTriggersRollback_Phase3");
    }

    @Test
    public void testNativeWatchdogTriggersRollbackForAll() throws Exception {
        // This test requires committing multiple staged rollbacks
        assumeTrue(isCheckpointSupported());

        // Install a package with rollback enabled.
        runPhase("testNativeWatchdogTriggersRollbackForAll_Phase1");
        getDevice().reboot();

        // Once previous staged install is applied, install another package
        runPhase("testNativeWatchdogTriggersRollbackForAll_Phase2");
        getDevice().reboot();

        // Verify the new staged install has also been applied successfully.
        runPhase("testNativeWatchdogTriggersRollbackForAll_Phase3");

        // crash system_server enough times to trigger a rollback
        crashProcess("system_server", NATIVE_CRASHES_THRESHOLD);

        // Rollback should be committed automatically now.
        // Give time for rollback to be committed. This could take a while,
        // because we need all of the following to happen:
        // 1. system_server comes back up and boot completes.
        // 2. Rollback health observer detects updatable crashing signal.
        // 3. Staged rollback session becomes ready.
        // 4. Device actually reboots.
        // So we give a generous timeout here.
        assertTrue(getDevice().waitForDeviceNotAvailable(TimeUnit.MINUTES.toMillis(5)));
        getDevice().waitForDeviceAvailable();

        // verify all available rollbacks have been committed
        runPhase("testNativeWatchdogTriggersRollbackForAll_Phase4");
    }

    /**
     * Tests failed network health check triggers watchdog staged rollbacks.
     */
    @Test
    public void testNetworkFailedRollback() throws Exception {
        try {
            // Disconnect internet so we can test network health triggered rollbacks
            getDevice().executeShellCommand("svc wifi disable");
            getDevice().executeShellCommand("svc data disable");

            runPhase("testNetworkFailedRollback_Phase1");
            // Reboot device to activate staged package
            getDevice().reboot();

            // Verify rollback was enabled
            runPhase("testNetworkFailedRollback_Phase2");
            assertThrows(AssertionError.class, () -> runPhase("testNetworkFailedRollback_Phase3"));

            getDevice().waitForDeviceAvailable();
            // Verify rollback was executed after health check deadline
            runPhase("testNetworkFailedRollback_Phase4");
        } finally {
            // Reconnect internet again so we won't break tests which assume internet available
            getDevice().executeShellCommand("svc wifi enable");
            getDevice().executeShellCommand("svc data enable");
        }
    }

    /**
     * Tests passed network health check does not trigger watchdog staged rollbacks.
     */
    @Test
    public void testNetworkPassedDoesNotRollback() throws Exception {
        runPhase("testNetworkPassedDoesNotRollback_Phase1");
        // Reboot device to activate staged package
        getDevice().reboot();

        // Verify rollback was enabled
        runPhase("testNetworkPassedDoesNotRollback_Phase2");

        // Connect to internet so network health check passes
        getDevice().executeShellCommand("svc wifi enable");
        getDevice().executeShellCommand("svc data enable");

        // Wait for device available because emulator device may restart after turning
        // on mobile data
        getDevice().waitForDeviceAvailable();

        // Verify rollback was not executed after health check deadline
        runPhase("testNetworkPassedDoesNotRollback_Phase3");
    }

    /**
     * Tests rolling back user data where there are multiple rollbacks for that package.
     */
    @Test
    public void testPreviouslyAbandonedRollbacks() throws Exception {
        runPhase("testPreviouslyAbandonedRollbacks_Phase1");
        getDevice().reboot();
        runPhase("testPreviouslyAbandonedRollbacks_Phase2");
        getDevice().reboot();
        runPhase("testPreviouslyAbandonedRollbacks_Phase3");
    }

    /**
     * Tests we can enable rollback for a whitelisted app.
     */
    @Test
    public void testRollbackWhitelistedApp() throws Exception {
        runPhase("testRollbackWhitelistedApp_Phase1");
        getDevice().reboot();
        runPhase("testRollbackWhitelistedApp_Phase2");
    }

    @Test
    public void testRollbackDataPolicy() throws Exception {
        runPhase("testRollbackDataPolicy_Phase1");
        getDevice().reboot();
        runPhase("testRollbackDataPolicy_Phase2");
        getDevice().reboot();
        runPhase("testRollbackDataPolicy_Phase3");
    }

    /**
     * Tests that userdata of apk-in-apex is restored when apex is rolled back.
     */
    @Test
    public void testRollbackApexWithApk() throws Exception {
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.A");
        CompatibilityBuildHelper buildHelper = new CompatibilityBuildHelper(getBuild());
        final String fileName = APK_IN_APEX_TESTAPEX_NAME + "_v1.apex";
        final File apex = buildHelper.getTestFile(fileName);
        if (!getDevice().isAdbRoot()) {
            getDevice().enableAdbRoot();
        }
        getDevice().remountSystemWritable();
        assertTrue(getDevice().pushFile(apex, "/system/apex/" + fileName));
        getDevice().reboot();
        runPhase("testRollbackApexWithApk_Phase1");
        getDevice().reboot();
        runPhase("testRollbackApexWithApk_Phase2");
        getDevice().reboot();
        runPhase("testRollbackApexWithApk_Phase3");
    }

    private void crashProcess(String processName, int numberOfCrashes) throws Exception {
        String pid = "";
        String lastPid = "invalid";
        for (int i = 0; i < numberOfCrashes; ++i) {
            // This condition makes sure before we kill the process, the process is running AND
            // the last crash was finished.
            while ("".equals(pid) || lastPid.equals(pid)) {
                pid = getDevice().executeShellCommand("pidof " + processName);
            }
            getDevice().executeShellCommand("kill " + pid);
            lastPid = pid;
        }
    }

    private String getNetworkStackPath() throws Exception {
        // Find the NetworkStack path (can be NetworkStack.apk or NetworkStackNext.apk)
        return getDevice().executeShellCommand("ls /system/priv-app/NetworkStack*/*.apk");
    }

    private boolean isCheckpointSupported() throws Exception {
        try {
            runPhase("isCheckpointSupported");
            return true;
        } catch (AssertionError ignore) {
            return false;
        }
    }
}
