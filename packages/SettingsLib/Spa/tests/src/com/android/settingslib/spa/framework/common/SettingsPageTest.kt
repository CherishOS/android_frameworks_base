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

package com.android.settingslib.spa.framework.common

import android.content.Context
import androidx.core.os.bundleOf
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spaLogger = SpaLoggerForTest()
    private val spaEnvironment = SpaEnvironmentForTest(context, logger = spaLogger)

    @Test
    fun testNullPage() {
        val page = SettingsPage.createNull()
        assertThat(page.id).isEqualTo(getUniquePageId("NULL"))
        assertThat(page.sppName).isEqualTo("NULL")
        assertThat(page.displayName).isEqualTo("NULL")
        assertThat(page.buildRoute()).isEqualTo("NULL")
        assertThat(page.isCreateBy("NULL")).isTrue()
        assertThat(page.isCreateBy("Spp")).isFalse()
        assertThat(page.hasRuntimeParam()).isFalse()
        assertThat(page.isBrowsable(context, MockActivity::class.java)).isFalse()
        assertThat(page.createBrowseIntent(context, MockActivity::class.java)).isNull()
        assertThat(page.createBrowseAdbCommand(context, MockActivity::class.java)).isNull()
    }

    @Test
    fun testRegularPage() {
        val page = SettingsPage.create("mySpp", "SppDisplayName")
        assertThat(page.id).isEqualTo(getUniquePageId("mySpp"))
        assertThat(page.sppName).isEqualTo("mySpp")
        assertThat(page.displayName).isEqualTo("SppDisplayName")
        assertThat(page.buildRoute()).isEqualTo("mySpp")
        assertThat(page.isCreateBy("NULL")).isFalse()
        assertThat(page.isCreateBy("mySpp")).isTrue()
        assertThat(page.hasRuntimeParam()).isFalse()
        assertThat(page.isBrowsable(context, MockActivity::class.java)).isTrue()
        assertThat(page.createBrowseIntent(context, MockActivity::class.java)).isNotNull()
        assertThat(page.createBrowseAdbCommand(context, MockActivity::class.java)).contains(
            "-e spaActivityDestination mySpp"
        )
    }

    @Test
    fun testParamPage() {
        val arguments = bundleOf(
            "string_param" to "myStr",
            "int_param" to 10,
        )
        val page = spaEnvironment.createPage("SppWithParam", arguments)
        assertThat(page.id).isEqualTo(getUniquePageId("SppWithParam", listOf(
            navArgument("string_param") { type = NavType.StringType },
            navArgument("int_param") { type = NavType.IntType },
        ), arguments))
        assertThat(page.sppName).isEqualTo("SppWithParam")
        assertThat(page.displayName).isEqualTo("SppWithParam")
        assertThat(page.buildRoute()).isEqualTo("SppWithParam/myStr/10")
        assertThat(page.isCreateBy("SppWithParam")).isTrue()
        assertThat(page.hasRuntimeParam()).isFalse()
        assertThat(page.isBrowsable(context, MockActivity::class.java)).isTrue()
        assertThat(page.createBrowseIntent(context, MockActivity::class.java)).isNotNull()
        assertThat(page.createBrowseAdbCommand(context, MockActivity::class.java)).contains(
            "-e spaActivityDestination SppWithParam/myStr/10"
        )
    }

    @Test
    fun testRtParamPage() {
        val arguments = bundleOf(
            "string_param" to "myStr",
            "int_param" to 10,
            "rt_param" to "rtStr",
        )
        val page = spaEnvironment.createPage("SppWithRtParam", arguments)
        assertThat(page.id).isEqualTo(getUniquePageId("SppWithRtParam", listOf(
            navArgument("string_param") { type = NavType.StringType },
            navArgument("int_param") { type = NavType.IntType },
            navArgument("rt_param") { type = NavType.StringType },
        ), arguments))
        assertThat(page.sppName).isEqualTo("SppWithRtParam")
        assertThat(page.displayName).isEqualTo("SppWithRtParam")
        assertThat(page.buildRoute()).isEqualTo("SppWithRtParam/myStr/10/rtStr")
        assertThat(page.isCreateBy("SppWithRtParam")).isTrue()
        assertThat(page.hasRuntimeParam()).isTrue()
        assertThat(page.isBrowsable(context, MockActivity::class.java)).isFalse()
        assertThat(page.createBrowseIntent(context, MockActivity::class.java)).isNull()
        assertThat(page.createBrowseAdbCommand(context, MockActivity::class.java)).isNull()
    }

    @Test
    fun testPageEvent() {
        spaLogger.reset()
        SpaEnvironmentFactory.reset(spaEnvironment)
        val page = spaEnvironment.createPage("SppHome")
        page.enterPage()
        page.leavePage()
        page.enterPage()
        assertThat(spaLogger.getEventCount(page.id, LogEvent.PAGE_ENTER, LogCategory.FRAMEWORK))
            .isEqualTo(2)
        assertThat(spaLogger.getEventCount(page.id, LogEvent.PAGE_LEAVE, LogCategory.FRAMEWORK))
            .isEqualTo(1)
    }
}
