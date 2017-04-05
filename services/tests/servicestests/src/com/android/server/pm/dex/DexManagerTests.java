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
 * limitations under the License
 */

package com.android.server.pm.dex;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.UserHandle;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import dalvik.system.VMRuntime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static com.android.server.pm.dex.PackageDexUsage.PackageUseInfo;
import static com.android.server.pm.dex.PackageDexUsage.DexUseInfo;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DexManagerTests {
    private DexManager mDexManager;

    private TestData mFooUser0;
    private TestData mBarUser0;
    private TestData mBarUser1;
    private TestData mInvalidIsa;
    private TestData mDoesNotExist;

    private int mUser0;
    private int mUser1;

    @Before
    public void setup() {
        mUser0 = 0;
        mUser1 = 1;

        String isa = VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]);
        String foo = "foo";
        String bar = "bar";

        mFooUser0 = new TestData(foo, isa, mUser0);
        mBarUser0 = new TestData(bar, isa, mUser0);
        mBarUser1 = new TestData(bar, isa, mUser1);
        mInvalidIsa = new TestData("INVALID", "INVALID_ISA", mUser0);
        mDoesNotExist = new TestData("DOES.NOT.EXIST", isa, mUser1);

        mDexManager = new DexManager(null, null, null, null);

        // Foo and Bar are available to user0.
        // Only Bar is available to user1;
        Map<Integer, List<PackageInfo>> existingPackages = new HashMap<>();
        existingPackages.put(mUser0, Arrays.asList(mFooUser0.mPackageInfo, mBarUser0.mPackageInfo));
        existingPackages.put(mUser1, Arrays.asList(mBarUser1.mPackageInfo));
        mDexManager.load(existingPackages);
    }

    @Test
    public void testNotifyPrimaryUse() {
        // The main dex file and splits are re-loaded by the app.
        notifyDexLoad(mFooUser0, mFooUser0.getBaseAndSplitDexPaths(), mUser0);

        // Package is not used by others, so we should get nothing back.
        assertNull(getPackageUseInfo(mFooUser0));
    }

    @Test
    public void testNotifyPrimaryForeignUse() {
        // Foo loads Bar main apks.
        notifyDexLoad(mFooUser0, mBarUser0.getBaseAndSplitDexPaths(), mUser0);

        // Bar is used by others now and should be in our records
        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertNotNull(pui);
        assertTrue(pui.isUsedByOtherApps());
        assertTrue(pui.getDexUseInfoMap().isEmpty());
    }

    @Test
    public void testNotifySecondary() {
        // Foo loads its own secondary files.
        List<String> fooSecondaries = mFooUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, fooSecondaries, mUser0);

        PackageUseInfo pui = getPackageUseInfo(mFooUser0);
        assertNotNull(pui);
        assertFalse(pui.isUsedByOtherApps());
        assertEquals(fooSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(mFooUser0, pui, fooSecondaries, /*isUsedByOtherApps*/false, mUser0);
    }

    @Test
    public void testNotifySecondaryForeign() {
        // Foo loads bar secondary files.
        List<String> barSecondaries = mBarUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, barSecondaries, mUser0);

        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertNotNull(pui);
        assertFalse(pui.isUsedByOtherApps());
        assertEquals(barSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(mFooUser0, pui, barSecondaries, /*isUsedByOtherApps*/true, mUser0);
    }

    @Test
    public void testNotifySequence() {
        // Foo loads its own secondary files.
        List<String> fooSecondaries = mFooUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, fooSecondaries, mUser0);
        // Foo loads Bar own secondary files.
        List<String> barSecondaries = mBarUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, barSecondaries, mUser0);
        // Foo loads Bar primary files.
        notifyDexLoad(mFooUser0, mBarUser0.getBaseAndSplitDexPaths(), mUser0);
        // Bar loads its own secondary files.
        notifyDexLoad(mBarUser0, barSecondaries, mUser0);
        // Bar loads some own secondary files which foo didn't load.
        List<String> barSecondariesForOwnUse = mBarUser0.getSecondaryDexPathsForOwnUse();
        notifyDexLoad(mBarUser0, barSecondariesForOwnUse, mUser0);

        // Check bar usage. Should be used by other app (for primary and barSecondaries).
        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertNotNull(pui);
        assertTrue(pui.isUsedByOtherApps());
        assertEquals(barSecondaries.size() + barSecondariesForOwnUse.size(),
                pui.getDexUseInfoMap().size());

        assertSecondaryUse(mFooUser0, pui, barSecondaries, /*isUsedByOtherApps*/true, mUser0);
        assertSecondaryUse(mFooUser0, pui, barSecondariesForOwnUse,
                /*isUsedByOtherApps*/false, mUser0);

        // Check foo usage. Should not be used by other app.
        pui = getPackageUseInfo(mFooUser0);
        assertNotNull(pui);
        assertFalse(pui.isUsedByOtherApps());
        assertEquals(fooSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(mFooUser0, pui, fooSecondaries, /*isUsedByOtherApps*/false, mUser0);
    }

    @Test
    public void testPackageUseInfoNotFound() {
        // Assert we don't get back data we did not previously record.
        assertNull(getPackageUseInfo(mFooUser0));
    }

    @Test
    public void testInvalidIsa() {
        // Notifying with an invalid ISA should be ignored.
        notifyDexLoad(mInvalidIsa, mInvalidIsa.getSecondaryDexPaths(), mUser0);
        assertNull(getPackageUseInfo(mInvalidIsa));
    }

    @Test
    public void testNotExistingPackate() {
        // Notifying about the load of a package which was previously not
        // register in DexManager#load should be ignored.
        notifyDexLoad(mDoesNotExist, mDoesNotExist.getBaseAndSplitDexPaths(), mUser0);
        assertNull(getPackageUseInfo(mDoesNotExist));
    }

    @Test
    public void testCrossUserAttempt() {
        // Bar from User1 tries to load secondary dex files from User0 Bar.
        // Request should be ignored.
        notifyDexLoad(mBarUser1, mBarUser0.getSecondaryDexPaths(), mUser1);
        assertNull(getPackageUseInfo(mBarUser1));
    }

    @Test
    public void testPackageNotInstalledForUser() {
        // User1 tries to load Foo which is installed for User0 but not for User1.
        // Note that the PackageManagerService already filters this out but we
        // still check that nothing goes unexpected in DexManager.
        notifyDexLoad(mBarUser0, mFooUser0.getBaseAndSplitDexPaths(), mUser1);
        assertNull(getPackageUseInfo(mBarUser1));
    }

    @Test
    public void testNotifyPackageInstallUsedByOther() {
        TestData newPackage = new TestData("newPackage",
                VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]), mUser0);

        List<String> newSecondaries = newPackage.getSecondaryDexPaths();
        // Before we notify about the installation of the newPackage if mFoo
        // is trying to load something from it we should not find it.
        notifyDexLoad(mFooUser0, newSecondaries, mUser0);
        assertNull(getPackageUseInfo(newPackage));

        // Notify about newPackage install and let mFoo load its dexes.
        mDexManager.notifyPackageInstalled(newPackage.mPackageInfo, mUser0);
        notifyDexLoad(mFooUser0, newSecondaries, mUser0);

        // We should get back the right info.
        PackageUseInfo pui = getPackageUseInfo(newPackage);
        assertNotNull(pui);
        assertFalse(pui.isUsedByOtherApps());
        assertEquals(newSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(newPackage, pui, newSecondaries, /*isUsedByOtherApps*/true, mUser0);
    }

    @Test
    public void testNotifyPackageInstallSelfUse() {
        TestData newPackage = new TestData("newPackage",
                VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]), mUser0);

        List<String> newSecondaries = newPackage.getSecondaryDexPaths();
        // Packages should be able to find their own dex files even if the notification about
        // their installation is delayed.
        notifyDexLoad(newPackage, newSecondaries, mUser0);

        PackageUseInfo pui = getPackageUseInfo(newPackage);
        assertNotNull(pui);
        assertFalse(pui.isUsedByOtherApps());
        assertEquals(newSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(newPackage, pui, newSecondaries, /*isUsedByOtherApps*/false, mUser0);
    }

    @Test
    public void testNotifyPackageUpdated() {
        // Foo loads Bar main apks.
        notifyDexLoad(mFooUser0, mBarUser0.getBaseAndSplitDexPaths(), mUser0);

        // Bar is used by others now and should be in our records.
        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertNotNull(pui);
        assertTrue(pui.isUsedByOtherApps());
        assertTrue(pui.getDexUseInfoMap().isEmpty());

        // Notify that bar is updated.
        mDexManager.notifyPackageUpdated(mBarUser0.getPackageName(),
                mBarUser0.mPackageInfo.applicationInfo.sourceDir,
                mBarUser0.mPackageInfo.applicationInfo.splitSourceDirs);

        // The usedByOtherApps flag should be clear now.
        pui = getPackageUseInfo(mBarUser0);
        assertNotNull(pui);
        assertFalse(pui.isUsedByOtherApps());
    }

    @Test
    public void testNotifyPackageUpdatedCodeLocations() {
        // Simulate a split update.
        String newSplit = mBarUser0.replaceLastSplit();
        List<String> newSplits = new ArrayList<>();
        newSplits.add(newSplit);

        // We shouldn't find yet the new split as we didn't notify the package update.
        notifyDexLoad(mFooUser0, newSplits, mUser0);
        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertNull(pui);

        // Notify that bar is updated. splitSourceDirs will contain the updated path.
        mDexManager.notifyPackageUpdated(mBarUser0.getPackageName(),
                mBarUser0.mPackageInfo.applicationInfo.sourceDir,
                mBarUser0.mPackageInfo.applicationInfo.splitSourceDirs);

        // Now, when the split is loaded we will find it and we should mark Bar as usedByOthers.
        notifyDexLoad(mFooUser0, newSplits, mUser0);
        pui = getPackageUseInfo(mBarUser0);
        assertNotNull(pui);
        assertTrue(pui.isUsedByOtherApps());
    }

    @Test
    public void testNotifyPackageDataDestroyForOne() {
        // Bar loads its own secondary files.
        notifyDexLoad(mBarUser0, mBarUser0.getSecondaryDexPaths(), mUser0);
        notifyDexLoad(mBarUser1, mBarUser1.getSecondaryDexPaths(), mUser1);

        mDexManager.notifyPackageDataDestroyed(mBarUser0.getPackageName(), mUser0);

        // Bar should not be around since it was removed for all users.
        PackageUseInfo pui = getPackageUseInfo(mBarUser1);
        assertNotNull(pui);
        assertSecondaryUse(mBarUser1, pui, mBarUser1.getSecondaryDexPaths(),
                /*isUsedByOtherApps*/false, mUser1);
    }

    @Test
    public void testNotifyPackageDataDestroyForeignUse() {
        // Foo loads its own secondary files.
        List<String> fooSecondaries = mFooUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, fooSecondaries, mUser0);

        // Bar loads Foo main apks.
        notifyDexLoad(mBarUser0, mFooUser0.getBaseAndSplitDexPaths(), mUser0);

        mDexManager.notifyPackageDataDestroyed(mFooUser0.getPackageName(), mUser0);

        // Foo should still be around since it's used by other apps but with no
        // secondary dex info.
        PackageUseInfo pui = getPackageUseInfo(mFooUser0);
        assertNotNull(pui);
        assertTrue(pui.isUsedByOtherApps());
        assertTrue(pui.getDexUseInfoMap().isEmpty());
    }

    @Test
    public void testNotifyPackageDataDestroyComplete() {
        // Foo loads its own secondary files.
        List<String> fooSecondaries = mFooUser0.getSecondaryDexPaths();
        notifyDexLoad(mFooUser0, fooSecondaries, mUser0);

        mDexManager.notifyPackageDataDestroyed(mFooUser0.getPackageName(), mUser0);

        // Foo should not be around since all its secondary dex info were deleted
        // and it is not used by other apps.
        PackageUseInfo pui = getPackageUseInfo(mFooUser0);
        assertNull(pui);
    }

    @Test
    public void testNotifyPackageDataDestroyForAll() {
        // Foo loads its own secondary files.
        notifyDexLoad(mBarUser0, mBarUser0.getSecondaryDexPaths(), mUser0);
        notifyDexLoad(mBarUser1, mBarUser1.getSecondaryDexPaths(), mUser1);

        mDexManager.notifyPackageDataDestroyed(mBarUser0.getPackageName(), UserHandle.USER_ALL);

        // Bar should not be around since it was removed for all users.
        PackageUseInfo pui = getPackageUseInfo(mBarUser0);
        assertNull(pui);
    }

    @Test
    public void testNotifyFrameworkLoad() {
        String frameworkDex = "/system/framework/com.android.location.provider.jar";
        // Load a dex file from framework.
        notifyDexLoad(mFooUser0, Arrays.asList(frameworkDex), mUser0);
        // The dex file should not be recognized as a package.
        assertNull(mDexManager.getPackageUseInfo(frameworkDex));
    }

    @Test
    public void testNotifySecondaryFromProtected() {
        // Foo loads its own secondary files.
        List<String> fooSecondaries = mFooUser0.getSecondaryDexPathsFromProtectedDirs();
        notifyDexLoad(mFooUser0, fooSecondaries, mUser0);

        PackageUseInfo pui = getPackageUseInfo(mFooUser0);
        assertNotNull(pui);
        assertFalse(pui.isUsedByOtherApps());
        assertEquals(fooSecondaries.size(), pui.getDexUseInfoMap().size());
        assertSecondaryUse(mFooUser0, pui, fooSecondaries, /*isUsedByOtherApps*/false, mUser0);
    }

    private void assertSecondaryUse(TestData testData, PackageUseInfo pui,
            List<String> secondaries, boolean isUsedByOtherApps, int ownerUserId) {
        for (String dex : secondaries) {
            DexUseInfo dui = pui.getDexUseInfoMap().get(dex);
            assertNotNull(dui);
            assertEquals(isUsedByOtherApps, dui.isUsedByOtherApps());
            assertEquals(ownerUserId, dui.getOwnerUserId());
            assertEquals(1, dui.getLoaderIsas().size());
            assertTrue(dui.getLoaderIsas().contains(testData.mLoaderIsa));
        }
    }

    private void notifyDexLoad(TestData testData, List<String> dexPaths, int loaderUserId) {
        mDexManager.notifyDexLoad(testData.mPackageInfo.applicationInfo, dexPaths,
                testData.mLoaderIsa, loaderUserId);
    }

    private PackageUseInfo getPackageUseInfo(TestData testData) {
        return mDexManager.getPackageUseInfo(testData.mPackageInfo.packageName);
    }

    private static PackageInfo getMockPackageInfo(String packageName, int userId) {
        PackageInfo pi = new PackageInfo();
        pi.packageName = packageName;
        pi.applicationInfo = getMockApplicationInfo(packageName, userId);
        return pi;
    }

    private static ApplicationInfo getMockApplicationInfo(String packageName, int userId) {
        ApplicationInfo ai = new ApplicationInfo();
        String codeDir = "/data/app/" + packageName;
        ai.setBaseCodePath(codeDir + "/base.dex");
        ai.setSplitCodePaths(new String[] {codeDir + "/split-1.dex", codeDir + "/split-2.dex"});
        ai.dataDir = "/data/user/" + userId + "/" + packageName;
        ai.deviceProtectedDataDir = "/data/user_de/" + userId + "/" + packageName;
        ai.credentialProtectedDataDir = "/data/user_ce/" + userId + "/" + packageName;
        ai.packageName = packageName;
        return ai;
    }

    private static class TestData {
        private final PackageInfo mPackageInfo;
        private final String mLoaderIsa;

        private TestData(String  packageName, String loaderIsa, int userId) {
            mPackageInfo = getMockPackageInfo(packageName, userId);
            mLoaderIsa = loaderIsa;
        }

        private String getPackageName() {
            return mPackageInfo.packageName;
        }

        List<String> getSecondaryDexPaths() {
            List<String> paths = new ArrayList<>();
            paths.add(mPackageInfo.applicationInfo.dataDir + "/secondary1.dex");
            paths.add(mPackageInfo.applicationInfo.dataDir + "/secondary2.dex");
            paths.add(mPackageInfo.applicationInfo.dataDir + "/secondary3.dex");
            return paths;
        }

        List<String> getSecondaryDexPathsForOwnUse() {
            List<String> paths = new ArrayList<>();
            paths.add(mPackageInfo.applicationInfo.dataDir + "/secondary4.dex");
            paths.add(mPackageInfo.applicationInfo.dataDir + "/secondary5.dex");
            return paths;
        }

        List<String> getSecondaryDexPathsFromProtectedDirs() {
            List<String> paths = new ArrayList<>();
            paths.add(mPackageInfo.applicationInfo.dataDir + "/secondary6.dex");
            paths.add(mPackageInfo.applicationInfo.dataDir + "/secondary7.dex");
            return paths;
        }

        List<String> getBaseAndSplitDexPaths() {
            List<String> paths = new ArrayList<>();
            paths.add(mPackageInfo.applicationInfo.sourceDir);
            for (String split : mPackageInfo.applicationInfo.splitSourceDirs) {
                paths.add(split);
            }
            return paths;
        }

        String replaceLastSplit() {
            int length = mPackageInfo.applicationInfo.splitSourceDirs.length;
            // Add an extra bogus dex extension to simulate a new split name.
            mPackageInfo.applicationInfo.splitSourceDirs[length - 1] += ".dex";
            return mPackageInfo.applicationInfo.splitSourceDirs[length - 1];
        }
    }
}
