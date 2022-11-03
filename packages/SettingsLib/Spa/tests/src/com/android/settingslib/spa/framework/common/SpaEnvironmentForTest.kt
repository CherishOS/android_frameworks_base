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

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settingslib.spa.framework.BrowseActivity

class SpaLoggerForTest : SpaLogger {
    data class MsgCountKey(val msg: String, val category: LogCategory)
    data class EventCountKey(val id: String, val event: LogEvent, val category: LogCategory)

    private val messageCount: MutableMap<MsgCountKey, Int> = mutableMapOf()
    private val eventCount: MutableMap<EventCountKey, Int> = mutableMapOf()

    override fun message(tag: String, msg: String, category: LogCategory) {
        val key = MsgCountKey("[$tag]$msg", category)
        messageCount[key] = messageCount.getOrDefault(key, 0) + 1
    }

    override fun event(id: String, event: LogEvent, category: LogCategory, details: String?) {
        val key = EventCountKey(id, event, category)
        eventCount[key] = eventCount.getOrDefault(key, 0) + 1
    }

    fun getMessageCount(tag: String, msg: String, category: LogCategory): Int {
        val key = MsgCountKey("[$tag]$msg", category)
        return messageCount.getOrDefault(key, 0)
    }

    fun getEventCount(id: String, event: LogEvent, category: LogCategory): Int {
        val key = EventCountKey(id, event, category)
        return eventCount.getOrDefault(key, 0)
    }

    fun reset() {
        messageCount.clear()
        eventCount.clear()
    }
}

class MockActivity : BrowseActivity()

object SppHome : SettingsPageProvider {
    override val name = "SppHome"

    override fun getTitle(arguments: Bundle?): String {
        return "TitleHome"
    }

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val owner = this.createSettingsPage()
        return listOf(
            SppLayer1.buildInject().setLink(fromPage = owner).build(),
        )
    }
}

object SppLayer1 : SettingsPageProvider {
    override val name = "SppLayer1"

    override fun getTitle(arguments: Bundle?): String {
        return "TitleLayer1"
    }

    fun buildInject(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(this.createSettingsPage())
    }

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val owner = this.createSettingsPage()
        return listOf(
            SettingsEntryBuilder.create(owner, "Layer1Entry1").build(),
            SppLayer2.buildInject().setLink(fromPage = owner).build(),
            SettingsEntryBuilder.create(owner, "Layer1Entry2").build(),
        )
    }
}

object SppLayer2 : SettingsPageProvider {
    override val name = "SppLayer2"

    fun buildInject(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(this.createSettingsPage())
    }

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val owner = this.createSettingsPage()
        return listOf(
            SettingsEntryBuilder.create(owner, "Layer2Entry1").build(),
            SettingsEntryBuilder.create(owner, "Layer2Entry2").build(),
        )
    }
}

class SpaEnvironmentForTest(
    context: Context,
    override val browseActivityClass: Class<out Activity>? = MockActivity::class.java,
    override val logger: SpaLogger = SpaLoggerForTest()
) : SpaEnvironment(context) {

    override val pageProviderRepository = lazy {
        SettingsPageProviderRepository(
            listOf(
                SppHome, SppLayer1, SppLayer2,
                object : SettingsPageProvider {
                    override val name = "SppWithParam"
                    override val parameter = listOf(
                        navArgument("string_param") { type = NavType.StringType },
                        navArgument("int_param") { type = NavType.IntType },
                    )
                },
                object : SettingsPageProvider {
                    override val name = "SppWithRtParam"
                    override val parameter = listOf(
                        navArgument("string_param") { type = NavType.StringType },
                        navArgument("int_param") { type = NavType.IntType },
                        navArgument("rt_param") { type = NavType.StringType },
                    )
                },
            ),
            listOf(SettingsPage.create("SppHome"))
        )
    }

    fun createPage(sppName: String, arguments: Bundle? = null): SettingsPage {
        return pageProviderRepository.value
            .getProviderOrNull(sppName)!!.createSettingsPage(arguments)
    }
}
