/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageManager;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.overlay.OverlayPaths;
import android.content.pm.pkg.PackageUserState;
import android.os.PersistableBundle;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.pm.pkg.PackageStateUnserialized;
import com.android.server.pm.pkg.PackageUserStateInternalImpl;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageUserStateTest {

    @Test
    public void testPackageUserState01() {
        final PackageUserStateInternalImpl testUserState = new PackageUserStateInternalImpl();
        PackageUserStateInternalImpl oldUserState;

        oldUserState = new PackageUserStateInternalImpl();
        assertThat(testUserState.equals(null), is(false));
        assertThat(testUserState.equals(testUserState), is(true));
        assertThat(testUserState.equals(oldUserState), is(true));

        oldUserState = new PackageUserStateInternalImpl();
        oldUserState.setCeDataInode(4000L);
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateInternalImpl();
        oldUserState.setEnabledState(COMPONENT_ENABLED_STATE_ENABLED);
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateInternalImpl();
        oldUserState.setHidden(true);
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateInternalImpl();
        oldUserState.setInstalled(false);
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateInternalImpl();
        oldUserState.setNotLaunched(true);
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateInternalImpl();
        oldUserState.setStopped(true);
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateInternalImpl();
        oldUserState.setSuspended(true);
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateInternalImpl();
        oldUserState.setUninstallReason(PackageManager.UNINSTALL_REASON_USER_TYPE);
        assertThat(testUserState.equals(oldUserState), is(false));
    }

    @Test
    public void testPackageUserState02() {
        final PackageUserStateInternalImpl testUserState01 = new PackageUserStateInternalImpl();
        PackageUserStateInternalImpl oldUserState;

        oldUserState = new PackageUserStateInternalImpl();
        oldUserState.setLastDisableAppCaller("unit_test");
        assertThat(testUserState01.equals(oldUserState), is(false));

        final PackageUserStateInternalImpl testUserState02 = new PackageUserStateInternalImpl();
        testUserState02.setLastDisableAppCaller("unit_test");
        assertThat(testUserState02.equals(oldUserState), is(true));

        final PackageUserStateInternalImpl testUserState03 = new PackageUserStateInternalImpl();
        testUserState03.setLastDisableAppCaller("unit_test_00");
        assertThat(testUserState03.equals(oldUserState), is(false));
    }

    @Test
    public void testPackageUserState03() {
        final PackageUserStateInternalImpl oldUserState = new PackageUserStateInternalImpl();

        // only new user state has array defined; different
        final PackageUserStateInternalImpl testUserState01 = new PackageUserStateInternalImpl();
        testUserState01.setDisabledComponents(new ArraySet<>());
        assertThat(testUserState01.equals(oldUserState), is(false));

        // only old user state has array defined; different
        final PackageUserStateInternalImpl testUserState02 = new PackageUserStateInternalImpl();
        oldUserState.setDisabledComponents(new ArraySet<>());
        assertThat(testUserState02.equals(oldUserState), is(false));

        // both states have array defined; not different
        final PackageUserStateInternalImpl testUserState03 = new PackageUserStateInternalImpl();
        testUserState03.setDisabledComponents(new ArraySet<>());
        assertThat(testUserState03.equals(oldUserState), is(true));
        // fewer elements in old user state; different
        testUserState03.getDisabledComponentsNoCopy().add("com.android.unit_test01");
        testUserState03.getDisabledComponentsNoCopy().add("com.android.unit_test02");
        testUserState03.getDisabledComponentsNoCopy().add("com.android.unit_test03");
        oldUserState.getDisabledComponentsNoCopy().add("com.android.unit_test03");
        oldUserState.getDisabledComponentsNoCopy().add("com.android.unit_test02");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // same elements in old user state; not different
        oldUserState.getDisabledComponentsNoCopy().add("com.android.unit_test01");
        assertThat(testUserState03.equals(oldUserState), is(true));
        // more elements in old user state; different
        oldUserState.getDisabledComponentsNoCopy().add("com.android.unit_test04");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // different elements in old user state; different
        testUserState03.getDisabledComponentsNoCopy().add("com.android.unit_test_04");
        assertThat(testUserState03.equals(oldUserState), is(false));
    }

    @Test
    public void testPackageUserState04() {
        final PackageUserStateInternalImpl oldUserState = new PackageUserStateInternalImpl();

        // only new user state has array defined; different
        final PackageUserStateInternalImpl testUserState01 = new PackageUserStateInternalImpl();
        testUserState01.setEnabledComponents(new ArraySet<>());
        assertThat(testUserState01.equals(oldUserState), is(false));

        // only old user state has array defined; different
        final PackageUserStateInternalImpl testUserState02 = new PackageUserStateInternalImpl();
        oldUserState.setEnabledComponents(new ArraySet<>());
        assertThat(testUserState02.equals(oldUserState), is(false));

        // both states have array defined; not different
        final PackageUserStateInternalImpl testUserState03 = new PackageUserStateInternalImpl();
        testUserState03.setEnabledComponents(new ArraySet<>());
        assertThat(testUserState03.equals(oldUserState), is(true));
        // fewer elements in old user state; different
        testUserState03.getEnabledComponentsNoCopy().add("com.android.unit_test01");
        testUserState03.getEnabledComponentsNoCopy().add("com.android.unit_test02");
        testUserState03.getEnabledComponentsNoCopy().add("com.android.unit_test03");
        oldUserState.getEnabledComponentsNoCopy().add("com.android.unit_test03");
        oldUserState.getEnabledComponentsNoCopy().add("com.android.unit_test02");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // same elements in old user state; not different
        oldUserState.getEnabledComponentsNoCopy().add("com.android.unit_test01");
        assertThat(testUserState03.equals(oldUserState), is(true));
        // more elements in old user state; different
        oldUserState.getEnabledComponentsNoCopy().add("com.android.unit_test04");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // different elements in old user state; different
        testUserState03.getEnabledComponentsNoCopy().add("com.android.unit_test_04");
        assertThat(testUserState03.equals(oldUserState), is(false));
    }

    private static PackageUserState.SuspendParams createSuspendParams(SuspendDialogInfo dialogInfo,
            PersistableBundle appExtras, PersistableBundle launcherExtras) {
        return PackageUserState.SuspendParams.getInstanceOrNull(
                dialogInfo, appExtras, launcherExtras);
    }

    private static PersistableBundle createPersistableBundle(String lKey, long lValue, String sKey,
            String sValue, String dKey, double dValue) {
        final PersistableBundle result = new PersistableBundle(3);
        if (lKey != null) {
            result.putLong("com.unit_test." + lKey, lValue);
        }
        if (sKey != null) {
            result.putString("com.unit_test." + sKey, sValue);
        }
        if (dKey != null) {
            result.putDouble("com.unit_test." + dKey, dValue);
        }
        return result;
    }

    @Test
    public void testPackageUserState05() {
        final PersistableBundle appExtras1 = createPersistableBundle("appExtraId", 1, null, null,
                null, 0);
        final PersistableBundle appExtras2 = createPersistableBundle("appExtraId", 2, null, null,
                null, 0);

        final PersistableBundle launcherExtras1 = createPersistableBundle(null, 0, "name",
                "launcherExtras1", null, 0);
        final PersistableBundle launcherExtras2 = createPersistableBundle(null, 0, "name",
                "launcherExtras2", null, 0);

        final String suspendingPackage1 = "package1";
        final String suspendingPackage2 = "package2";

        final SuspendDialogInfo dialogInfo1 = new SuspendDialogInfo.Builder()
                .setMessage("dialogMessage1")
                .build();
        final SuspendDialogInfo dialogInfo2 = new SuspendDialogInfo.Builder()
                .setMessage("dialogMessage2")
                .build();

        final ArrayMap<String, PackageUserState.SuspendParams> paramsMap1 = new ArrayMap<>();
        paramsMap1.put(suspendingPackage1, createSuspendParams(dialogInfo1, appExtras1,
                launcherExtras1));
        final ArrayMap<String, PackageUserState.SuspendParams> paramsMap2 = new ArrayMap<>();
        paramsMap2.put(suspendingPackage2, createSuspendParams(dialogInfo2,
                appExtras2, launcherExtras2));


        final PackageUserStateInternalImpl testUserState1 = new PackageUserStateInternalImpl();
        testUserState1.setSuspended(true);
        testUserState1.setSuspendParams(paramsMap1);

        PackageUserStateInternalImpl testUserState2 =
                new PackageUserStateInternalImpl(testUserState1);
        assertThat(testUserState1.equals(testUserState2), is(true));
        testUserState2.setSuspendParams(paramsMap2);
        // Should not be equal since suspendParams maps are different
        assertThat(testUserState1.equals(testUserState2), is(false));
    }

    @Test
    public void testPackageUserState06() {
        final PackageUserStateInternalImpl userState1 = new PackageUserStateInternalImpl();
        assertThat(userState1.getDistractionFlags(), is(PackageManager.RESTRICTION_NONE));
        userState1.setDistractionFlags(PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS);

        final PackageUserStateInternalImpl copyOfUserState1 =
                new PackageUserStateInternalImpl(userState1);
        assertThat(userState1.getDistractionFlags(), is(copyOfUserState1.getDistractionFlags()));
        assertThat(userState1.equals(copyOfUserState1), is(true));

        final PackageUserStateInternalImpl userState2 =
                new PackageUserStateInternalImpl(userState1);
        userState2.setDistractionFlags(PackageManager.RESTRICTION_HIDE_NOTIFICATIONS);
        assertThat(userState1.equals(userState2), is(false));
    }

    @Test
    public void testPackageUserState07() {
        final PersistableBundle appExtras1 = createPersistableBundle("appExtraId", 1, null, null,
                null, 0);
        final PersistableBundle appExtras2 = createPersistableBundle("appExtraId", 2, null, null,
                null, 0);

        final PersistableBundle launcherExtras1 = createPersistableBundle(null, 0, "name",
                "launcherExtras1", null, 0);
        final PersistableBundle launcherExtras2 = createPersistableBundle(null, 0, "name",
                "launcherExtras2", null, 0);

        final SuspendDialogInfo dialogInfo1 = new SuspendDialogInfo.Builder()
                .setMessage("dialogMessage1")
                .build();
        final SuspendDialogInfo dialogInfo2 = new SuspendDialogInfo.Builder()
                .setMessage("dialogMessage2")
                .build();

        final PackageUserState.SuspendParams params1;
        PackageUserState.SuspendParams params2;
        params1 = createSuspendParams(dialogInfo1, appExtras1, launcherExtras1);
        params2 = createSuspendParams(dialogInfo1, appExtras1, launcherExtras1);
        // Everything is same
        assertThat(params1.equals(params2), is(true));

        params2 = createSuspendParams(dialogInfo2, appExtras1, launcherExtras1);
        // DialogInfo is different
        assertThat(params1.equals(params2), is(false));

        params2 = createSuspendParams(dialogInfo1, appExtras2, launcherExtras1);
        // app extras are different
        assertThat(params1.equals(params2), is(false));

        params2 = createSuspendParams(dialogInfo1, appExtras1, launcherExtras2);
        // Launcher extras are different
        assertThat(params1.equals(params2), is(false));

        params2 = createSuspendParams(dialogInfo2, appExtras2, launcherExtras2);
        // Everything is different
        assertThat(params1.equals(params2), is(false));
    }

    /**
     * Test fix for b/149772100.
     */
    private static void assertLastPackageUsageUnset(
            PackageStateUnserialized state) throws Exception {
        for (int i = state.getLastPackageUsageTimeInMills().length - 1; i >= 0; --i) {
            assertEquals(0L, state.getLastPackageUsageTimeInMills()[i]);
        }
    }
    private static void assertLastPackageUsageSet(
            PackageStateUnserialized state, int reason, long value) throws Exception {
        for (int i = state.getLastPackageUsageTimeInMills().length - 1; i >= 0; --i) {
            if (i == reason) {
                assertEquals(value, state.getLastPackageUsageTimeInMills()[i]);
            } else {
                assertEquals(0L, state.getLastPackageUsageTimeInMills()[i]);
            }
        }
    }
    @Test
    public void testPackageUseReasons() throws Exception {
        final PackageStateUnserialized testState1 = new PackageStateUnserialized();
        testState1.setLastPackageUsageTimeInMills(-1, 10L);
        assertLastPackageUsageUnset(testState1);

        final PackageStateUnserialized testState2 = new PackageStateUnserialized();
        testState2.setLastPackageUsageTimeInMills(
                PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT, 20L);
        assertLastPackageUsageUnset(testState2);

        final PackageStateUnserialized testState3 = new PackageStateUnserialized();
        testState3.setLastPackageUsageTimeInMills(Integer.MAX_VALUE, 30L);
        assertLastPackageUsageUnset(testState3);

        final PackageStateUnserialized testState4 = new PackageStateUnserialized();
        testState4.setLastPackageUsageTimeInMills(0, 40L);
        assertLastPackageUsageSet(testState4, 0, 40L);

        final PackageStateUnserialized testState5 = new PackageStateUnserialized();
        testState5.setLastPackageUsageTimeInMills(
                PackageManager.NOTIFY_PACKAGE_USE_CONTENT_PROVIDER, 50L);
        assertLastPackageUsageSet(
                testState5, PackageManager.NOTIFY_PACKAGE_USE_CONTENT_PROVIDER, 50L);

        final PackageStateUnserialized testState6 = new PackageStateUnserialized();
        testState6.setLastPackageUsageTimeInMills(
                PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT - 1, 60L);
        assertLastPackageUsageSet(
                testState6, PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT - 1, 60L);
    }

    @Test
    public void testOverlayPaths() {
        final PackageUserStateInternalImpl testState = new PackageUserStateInternalImpl();
        assertFalse(testState.setOverlayPaths(null));
        assertFalse(testState.setOverlayPaths(new OverlayPaths.Builder().build()));

        assertTrue(testState.setOverlayPaths(new OverlayPaths.Builder()
                .addApkPath("/path/to/some.apk").build()));
        assertFalse(testState.setOverlayPaths(new OverlayPaths.Builder()
                .addApkPath("/path/to/some.apk").build()));

        assertTrue(testState.setOverlayPaths(new OverlayPaths.Builder().build()));
        assertFalse(testState.setOverlayPaths(null));
    }
    @Test
    public void testSharedLibOverlayPaths() {
        final PackageUserStateInternalImpl testState = new PackageUserStateInternalImpl();
        final String LIB_ONE = "lib1";
        final String LIB_TW0 = "lib2";
        assertFalse(testState.setSharedLibraryOverlayPaths(LIB_ONE, null));
        assertFalse(testState.setSharedLibraryOverlayPaths(LIB_ONE,
                new OverlayPaths.Builder().build()));

        assertTrue(testState.setSharedLibraryOverlayPaths(LIB_ONE, new OverlayPaths.Builder()
                .addApkPath("/path/to/some.apk").build()));
        assertFalse(testState.setSharedLibraryOverlayPaths(LIB_ONE, new OverlayPaths.Builder()
                .addApkPath("/path/to/some.apk").build()));
        assertTrue(testState.setSharedLibraryOverlayPaths(LIB_TW0, new OverlayPaths.Builder()
                .addApkPath("/path/to/some.apk").build()));

        assertTrue(testState.setSharedLibraryOverlayPaths(LIB_ONE,
                new OverlayPaths.Builder().build()));
        assertFalse(testState.setSharedLibraryOverlayPaths(LIB_ONE, null));
    }

}
