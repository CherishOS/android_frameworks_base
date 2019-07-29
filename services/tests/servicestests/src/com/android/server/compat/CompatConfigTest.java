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

package com.android.server.compat;

import static com.google.common.truth.Truth.assertThat;

import android.content.pm.ApplicationInfo;

import androidx.test.runner.AndroidJUnit4;

import com.android.compat.annotation.Change;
import com.android.compat.annotation.XmlWriter;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class CompatConfigTest {

    private ApplicationInfo makeAppInfo(String pName, int targetSdkVersion) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pName;
        ai.targetSdkVersion = targetSdkVersion;
        return ai;
    }

    private File createTempDir() {
        String base = System.getProperty("java.io.tmpdir");
        File dir = new File(base, UUID.randomUUID().toString());
        assertThat(dir.mkdirs()).isTrue();
        return dir;
    }

    private void writeChangesToFile(Change[] changes, File f) {
        XmlWriter writer = new XmlWriter();
        for (Change change: changes) {
            writer.addChange(change);
        }
        try {
            f.createNewFile();
            writer.write(new FileOutputStream(f));
        } catch (IOException e) {
            throw new RuntimeException(
                    "Encountered an error while writing compat config file", e);
        }
    }

    @Test
    public void testUnknownChangeEnabled() {
        CompatConfig pc = new CompatConfig();
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 1))).isTrue();
    }

    @Test
    public void testDisabledChangeDisabled() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, true));
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 1))).isFalse();
    }

    @Test
    public void testTargetSdkChangeDisabled() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", 2, false));
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 2))).isFalse();
    }

    @Test
    public void testTargetSdkChangeEnabled() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", 2, false));
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 3))).isTrue();
    }

    @Test
    public void testDisabledOverrideTargetSdkChange() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", 2, true));
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 3))).isFalse();
    }

    @Test
    public void testGetDisabledChanges() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, true));
        pc.addChange(new CompatChange(2345L, "OTHER_CHANGE", -1, false));
        assertThat(pc.getDisabledChanges(
                makeAppInfo("com.some.package", 2))).asList().containsExactly(1234L);
    }

    @Test
    public void testGetDisabledChangesSorted() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", 2, true));
        pc.addChange(new CompatChange(123L, "OTHER_CHANGE", 2, true));
        pc.addChange(new CompatChange(12L, "THIRD_CHANGE", 2, true));
        assertThat(pc.getDisabledChanges(
                makeAppInfo("com.some.package", 2))).asList().containsExactly(12L, 123L, 1234L);
    }

    @Test
    public void testPackageOverrideEnabled() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, true)); // disabled
        pc.addOverride(1234L, "com.some.package", true);
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 2))).isTrue();
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.other.package", 2))).isFalse();
    }

    @Test
    public void testPackageOverrideDisabled() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, false));
        pc.addOverride(1234L, "com.some.package", false);
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 2))).isFalse();
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.other.package", 2))).isTrue();
    }

    @Test
    public void testPackageOverrideUnknownPackage() {
        CompatConfig pc = new CompatConfig();
        pc.addOverride(1234L, "com.some.package", false);
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 2))).isFalse();
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.other.package", 2))).isTrue();
    }

    @Test
    public void testPackageOverrideUnknownChange() {
        CompatConfig pc = new CompatConfig();
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 1))).isTrue();
    }

    @Test
    public void testRemovePackageOverride() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, false));
        pc.addOverride(1234L, "com.some.package", false);
        pc.removeOverride(1234L, "com.some.package");
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 2))).isTrue();
    }

    @Test
    public void testLookupChangeId() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, false));
        pc.addChange(new CompatChange(2345L, "ANOTHER_CHANGE", -1, false));
        assertThat(pc.lookupChangeId("MY_CHANGE")).isEqualTo(1234L);
    }

    @Test
    public void testLookupChangeIdNotPresent() {
        CompatConfig pc = new CompatConfig();
        assertThat(pc.lookupChangeId("MY_CHANGE")).isEqualTo(-1L);
    }

    @Test
    public void testSystemAppDisabledChangeEnabled() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, true)); // disabled
        ApplicationInfo sysApp = makeAppInfo("system.app", 1);
        sysApp.flags |= ApplicationInfo.FLAG_SYSTEM;
        assertThat(pc.isChangeEnabled(1234L, sysApp)).isTrue();
    }

    @Test
    public void testSystemAppOverrideIgnored() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, false));
        pc.addOverride(1234L, "system.app", false);
        ApplicationInfo sysApp = makeAppInfo("system.app", 1);
        sysApp.flags |= ApplicationInfo.FLAG_SYSTEM;
        assertThat(pc.isChangeEnabled(1234L, sysApp)).isTrue();
    }

    @Test
    public void testSystemAppTargetSdkIgnored() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", 2, false));
        ApplicationInfo sysApp = makeAppInfo("system.app", 1);
        sysApp.flags |= ApplicationInfo.FLAG_SYSTEM;
        assertThat(pc.isChangeEnabled(1234L, sysApp)).isTrue();
    }

    @Test
    public void testReadConfig() {
        Change[] changes = {new Change(1234L, "MY_CHANGE1", false, 2), new Change(1235L,
                "MY_CHANGE2", true, null), new Change(1236L, "MY_CHANGE3", false, null)};

        File dir = createTempDir();
        writeChangesToFile(changes, new File(dir.getPath() + "/platform_compat_config.xml"));

        CompatConfig pc = new CompatConfig();
        pc.initConfigFromLib(dir);

        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 1))).isFalse();
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 3))).isTrue();
        assertThat(pc.isChangeEnabled(1235L, makeAppInfo("com.some.package", 5))).isFalse();
        assertThat(pc.isChangeEnabled(1236L, makeAppInfo("com.some.package", 1))).isTrue();
    }

    @Test
    public void testReadConfigMultipleFiles() {
        Change[] changes1 = {new Change(1234L, "MY_CHANGE1", false, 2)};
        Change[] changes2 = {new Change(1235L, "MY_CHANGE2", true, null), new Change(1236L,
                "MY_CHANGE3", false, null)};

        File dir = createTempDir();
        writeChangesToFile(changes1,
                new File(dir.getPath() + "/libcore_platform_compat_config.xml"));
        writeChangesToFile(changes2,
                new File(dir.getPath() + "/frameworks_platform_compat_config.xml"));


        CompatConfig pc = new CompatConfig();
        pc.initConfigFromLib(dir);

        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 1))).isFalse();
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 3))).isTrue();
        assertThat(pc.isChangeEnabled(1235L, makeAppInfo("com.some.package", 5))).isFalse();
        assertThat(pc.isChangeEnabled(1236L, makeAppInfo("com.some.package", 1))).isTrue();
    }
}


