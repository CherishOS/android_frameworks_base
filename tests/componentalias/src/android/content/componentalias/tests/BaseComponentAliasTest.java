/*
 * Copyright (C) 2021 The Android Open Source Project
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
package android.content.componentalias.tests;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.provider.DeviceConfig;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.DeviceConfigStateHelper;
import com.android.compatibility.common.util.ShellUtils;
import com.android.compatibility.common.util.TestUtils;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;

import java.util.function.Consumer;

public class BaseComponentAliasTest {
    protected static final Context sContext = InstrumentationRegistry.getTargetContext();

    protected static final DeviceConfigStateHelper sDeviceConfig = new DeviceConfigStateHelper(
            DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_COMPONENT_ALIAS);
    @Before
    public void enableComponentAliasWithCompatFlag() throws Exception {
        Assume.assumeTrue(Build.isDebuggable());
        ShellUtils.runShellCommand(
                "am compat enable --no-kill USE_EXPERIMENTAL_COMPONENT_ALIAS android");
        sDeviceConfig.set("enable_experimental_component_alias", "");
        sDeviceConfig.set("component_alias_overrides", "");

        // Make sure the feature is actually enabled, and the aliases are loaded.
        TestUtils.waitUntil("Wait until component alias is actually enabled", () -> {
            String out = ShellUtils.runShellCommand("dumpsys activity component-alias");

            return out.contains("Enabled: true")
                    && out.contains("android.content.componentalias.tests/.b.Alias04")
                    && out.contains("android.content.componentalias.tests/.s.Alias04");
        });
        ShellUtils.runShellCommand("am wait-for-broadcast-idle");
    }

    @AfterClass
    public static void restoreDeviceConfig() throws Exception {
        ShellUtils.runShellCommand(
                "am compat disable --no-kill USE_EXPERIMENTAL_COMPONENT_ALIAS android");
        sDeviceConfig.close();
    }

    protected static void log(String message) {
        Log.i(ComponentAliasTestCommon.TAG, "[" + sContext.getPackageName() + "] " + message);
    }

    /**
     * Defines a test target.
     */
    public static class Combo {
        public final ComponentName alias;
        public final ComponentName target;
        public final String action;

        public Combo(ComponentName alias, ComponentName target, String action) {
            this.alias = alias;
            this.target = target;
            this.action = action;
        }

        @Override
        public String toString() {
            return "Combo{"
                    + "alias=" + toString(alias)
                    + ", target=" + toString(target)
                    + ", action='" + action + '\''
                    + '}';
        }

        private static String toString(ComponentName cn) {
            return cn == null ? "[null]" : cn.flattenToShortString();
        }

        public void apply(Consumer<Combo> callback) {
            log("Testing for: " + this);
            callback.accept(this);
        }
    }
}
