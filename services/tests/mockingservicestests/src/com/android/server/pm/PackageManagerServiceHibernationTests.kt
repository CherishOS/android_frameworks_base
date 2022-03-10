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

package com.android.server.pm

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.provider.DeviceConfig
import android.provider.DeviceConfig.NAMESPACE_APP_HIBERNATION
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import com.android.server.apphibernation.AppHibernationManagerInternal
import com.android.server.apphibernation.AppHibernationService
import com.android.server.extendedtestutils.wheneverStatic
import com.android.server.testutils.whenever
import org.junit.Assert
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class PackageManagerServiceHibernationTests {

    companion object {
        val TEST_PACKAGE_NAME = "test.package"
        val TEST_PACKAGE_2_NAME = "test.package2"
        val TEST_USER_ID = 0

        val KEY_APP_HIBERNATION_ENABLED = "app_hibernation_enabled"
    }

    @Rule
    @JvmField
    val rule = MockSystemRule()

    @Mock
    lateinit var appHibernationManager: AppHibernationManagerInternal
    @Mock
    lateinit var powerManager: PowerManager

    @Before
    @Throws(Exception::class)
    fun setup() {
        MockitoAnnotations.initMocks(this)
        wheneverStatic { DeviceConfig.getBoolean(
            NAMESPACE_APP_HIBERNATION, KEY_APP_HIBERNATION_ENABLED, false) }.thenReturn(true)
        AppHibernationService.sIsServiceEnabled = true
        rule.system().stageNominalSystemState()
        whenever(rule.mocks().injector.getLocalService(AppHibernationManagerInternal::class.java))
            .thenReturn(appHibernationManager)
        whenever(rule.mocks().injector.handler)
            .thenReturn(Handler(TestableLooper.get(this).looper))
        val injector = object : PackageDexOptimizer.Injector {
            override fun getAppHibernationManagerInternal(): AppHibernationManagerInternal {
                return appHibernationManager
            }

            override fun getPowerManager(context: Context?): PowerManager {
                return powerManager
            }
        }
        val packageDexOptimizer = PackageDexOptimizer(
            injector,
            rule.mocks().installer,
            rule.mocks().installLock,
            rule.mocks().context,
            "*dexopt*")
        whenever(rule.mocks().injector.packageDexOptimizer)
            .thenReturn(packageDexOptimizer)
        whenever(appHibernationManager.isOatArtifactDeletionEnabled).thenReturn(true)
    }

    @Test
    fun testExitForceStopExitsHibernation() {
        rule.system().stageScanExistingPackage(
            TEST_PACKAGE_NAME,
            1L,
            rule.system().dataAppDirectory)
        val pm = createPackageManagerService()
        rule.system().validateFinalState()

        TestableLooper.get(this).processAllMessages()

        whenever(appHibernationManager.isHibernatingForUser(TEST_PACKAGE_NAME, TEST_USER_ID))
            .thenReturn(true)

        pm.setPackageStoppedState(pm.snapshotComputer(), TEST_PACKAGE_NAME, false, TEST_USER_ID)

        TestableLooper.get(this).processAllMessages()

        verify(appHibernationManager).setHibernatingForUser(TEST_PACKAGE_NAME, TEST_USER_ID, false)
        verify(appHibernationManager).setHibernatingGlobally(TEST_PACKAGE_NAME, false)
    }

    @Test
    fun testExitForceStop_nonExistingAppHibernationManager_doesNotThrowException() {
        whenever(rule.mocks().injector.getLocalService(AppHibernationManagerInternal::class.java))
            .thenReturn(null)

        rule.system().stageScanExistingPackage(
            TEST_PACKAGE_NAME,
            1L,
            rule.system().dataAppDirectory)
        val pm = createPackageManagerService()
        rule.system().validateFinalState()

        TestableLooper.get(this).processAllMessages()

        whenever(appHibernationManager.isHibernatingForUser(TEST_PACKAGE_NAME, TEST_USER_ID))
            .thenReturn(true)

        try {
            pm.setPackageStoppedState(pm.snapshotComputer(), TEST_PACKAGE_NAME, false,
                TEST_USER_ID)
            TestableLooper.get(this).processAllMessages()
        } catch (e: Exception) {
            Assert.fail("Method throws exception when AppHibernationManager is not ready.\n$e")
        }
    }

    @Test
    fun testGetOptimizablePackages_ExcludesGloballyHibernatingPackages() {
        rule.system().stageScanExistingPackage(
            TEST_PACKAGE_NAME,
            1L,
            rule.system().dataAppDirectory,
            withPackage = { it.apply { isHasCode = true } })
        rule.system().stageScanExistingPackage(
            TEST_PACKAGE_2_NAME,
            1L,
            rule.system().dataAppDirectory,
            withPackage = { it.apply { isHasCode = true } })
        val pm = createPackageManagerService()
        rule.system().validateFinalState()
        whenever(appHibernationManager.isHibernatingGlobally(TEST_PACKAGE_2_NAME)).thenReturn(true)

        val optimizablePkgs = DexOptHelper(pm).getOptimizablePackages(pm.snapshotComputer())

        assertTrue(optimizablePkgs.contains(TEST_PACKAGE_NAME))
        assertFalse(optimizablePkgs.contains(TEST_PACKAGE_2_NAME))
    }

    private fun createPackageManagerService(): PackageManagerService {
        return PackageManagerService(rule.mocks().injector,
            false /*coreOnly*/,
            false /*factoryTest*/,
            MockSystem.DEFAULT_VERSION_INFO.fingerprint,
            false /*isEngBuild*/,
            false /*isUserDebugBuild*/,
            Build.VERSION_CODES.CUR_DEVELOPMENT,
            Build.VERSION.INCREMENTAL)
    }
}
