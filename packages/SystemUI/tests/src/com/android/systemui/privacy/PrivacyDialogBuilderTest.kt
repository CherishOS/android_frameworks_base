/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.privacy

import android.support.test.filters.SmallTest
import android.support.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class PrivacyDialogBuilderTest : SysuiTestCase() {

    @Test
    fun testGenerateAppsList() {
        val bar2 = PrivacyItem(Privacy.TYPE_CAMERA, PrivacyApplication(
                "Bar", context))
        val bar3 = PrivacyItem(Privacy.TYPE_LOCATION, PrivacyApplication(
                "Bar", context))
        val foo0 = PrivacyItem(Privacy.TYPE_CAMERA, PrivacyApplication(
                "Foo", context))
        val baz1 = PrivacyItem(Privacy.TYPE_CAMERA, PrivacyApplication(
                "Baz", context))

        val items = listOf(bar2, foo0, baz1, bar3)

        val textBuilder = PrivacyDialogBuilder(context, items)

        val list = textBuilder.appsAndTypes
        assertEquals(3, list.size)
        val appsList = list.map { it.first }
        val typesList = list.map { it.second }
        assertEquals(listOf("Bar", "Baz", "Foo"), appsList.map { it.packageName })
        assertEquals(listOf(Privacy.TYPE_CAMERA, Privacy.TYPE_LOCATION), typesList[0])
        assertEquals(listOf(Privacy.TYPE_CAMERA), typesList[1])
        assertEquals(listOf(Privacy.TYPE_CAMERA), typesList[2])
    }

    @Test
    fun testOrder() {
        // We want location to always go last, so it will go in the "+ other apps"
        val appCamera = PrivacyItem(PrivacyType.TYPE_CAMERA, PrivacyApplication("Camera", context))
        val appMicrophone =
                PrivacyItem(PrivacyType.TYPE_MICROPHONE, PrivacyApplication("Microphone", context))
        val appLocation =
                PrivacyItem(PrivacyType.TYPE_LOCATION, PrivacyApplication("Location", context))

        val items = listOf(appLocation, appMicrophone, appCamera)
        val textBuilder = PrivacyDialogBuilder(context, items)
        val appList = textBuilder.appsAndTypes.map { it.first }.map { it.packageName }
        assertEquals(listOf("Camera", "Microphone", "Location"), appList)
    }
}