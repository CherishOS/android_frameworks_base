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

package com.android.systemui.statusbar.phone

import android.view.InsetsFlags
import android.view.ViewDebug
import android.view.WindowInsets.Type.InsetsType
import android.view.WindowInsetsController.Appearance
import android.view.WindowInsetsController.Behavior
import com.android.internal.statusbar.LetterboxDetails
import com.android.internal.view.AppearanceRegion
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.SysuiStatusBarStateController
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Top-level listener of system attributes changed. This class is __always the first__ one to be
 * notified about changes.
 *
 * It is responsible for modifying any attributes if necessary, and then notifying the other
 * downstream listeners.
 */
@SysUISingleton
class SystemBarAttributesListener
@Inject
internal constructor(
    private val centralSurfaces: CentralSurfaces,
    private val letterboxAppearanceCalculator: LetterboxAppearanceCalculator,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val lightBarController: LightBarController,
    dumpManager: DumpManager,
) : Dumpable, StatusBarBoundsProvider.BoundsChangeListener {

    private var lastLetterboxAppearance: LetterboxAppearance? = null
    private var lastSystemBarAttributesParams: SystemBarAttributesParams? = null

    init {
        dumpManager.registerCriticalDumpable(this)
    }

    override fun onStatusBarBoundsChanged() {
        val params = lastSystemBarAttributesParams
        if (params != null && shouldUseLetterboxAppearance(params.letterboxesArray)) {
            onSystemBarAttributesChanged(
                params.displayId,
                params.appearance,
                params.appearanceRegionsArray,
                params.navbarColorManagedByIme,
                params.behavior,
                params.requestedVisibleTypes,
                params.packageName,
                params.letterboxesArray)
        }
    }

    fun onSystemBarAttributesChanged(
            displayId: Int,
            @Appearance originalAppearance: Int,
            originalAppearanceRegions: Array<AppearanceRegion>,
            navbarColorManagedByIme: Boolean,
            @Behavior behavior: Int,
            @InsetsType requestedVisibleTypes: Int,
            packageName: String,
            letterboxDetails: Array<LetterboxDetails>
    ) {
        lastSystemBarAttributesParams =
            SystemBarAttributesParams(
                displayId,
                originalAppearance,
                originalAppearanceRegions.toList(),
                navbarColorManagedByIme,
                behavior,
                requestedVisibleTypes,
                packageName,
                letterboxDetails.toList())

        val (appearance, appearanceRegions) =
            modifyAppearanceIfNeeded(
                originalAppearance, originalAppearanceRegions, letterboxDetails)

        val barModeChanged = centralSurfaces.setAppearance(appearance)

        lightBarController.onStatusBarAppearanceChanged(
            appearanceRegions, barModeChanged, centralSurfaces.barMode, navbarColorManagedByIme)

        centralSurfaces.updateBubblesVisibility()
        statusBarStateController.setSystemBarAttributes(
            appearance, behavior, requestedVisibleTypes, packageName)
    }

    private fun modifyAppearanceIfNeeded(
        appearance: Int,
        appearanceRegions: Array<AppearanceRegion>,
        letterboxDetails: Array<LetterboxDetails>
    ): Pair<Int, Array<AppearanceRegion>> =
        if (shouldUseLetterboxAppearance(letterboxDetails)) {
            val letterboxAppearance =
                letterboxAppearanceCalculator.getLetterboxAppearance(
                    appearance, appearanceRegions, letterboxDetails)
            lastLetterboxAppearance = letterboxAppearance
            Pair(letterboxAppearance.appearance, letterboxAppearance.appearanceRegions)
        } else {
            lastLetterboxAppearance = null
            Pair(appearance, appearanceRegions)
        }

    private fun shouldUseLetterboxAppearance(letterboxDetails: Array<LetterboxDetails>) =
        letterboxDetails.isNotEmpty()

    override fun dump(printWriter: PrintWriter, strings: Array<String>) {
        printWriter.println("lastSystemBarAttributesParams: $lastSystemBarAttributesParams")
        printWriter.println("lastLetterboxAppearance: $lastLetterboxAppearance")
    }
}

/**
 * Keeps track of the parameters passed in
 * [SystemBarAttributesListener.onSystemBarAttributesChanged].
 */
private data class SystemBarAttributesParams(
        val displayId: Int,
        @Appearance val appearance: Int,
        val appearanceRegions: List<AppearanceRegion>,
        val navbarColorManagedByIme: Boolean,
        @Behavior val behavior: Int,
        @InsetsType val requestedVisibleTypes: Int,
        val packageName: String,
        val letterboxes: List<LetterboxDetails>,
) {
    val letterboxesArray = letterboxes.toTypedArray()
    val appearanceRegionsArray = appearanceRegions.toTypedArray()
    override fun toString(): String {
        val appearanceToString =
                ViewDebug.flagsToString(InsetsFlags::class.java, "appearance", appearance)
        return """SystemBarAttributesParams(
            displayId=$displayId,
            appearance=$appearanceToString,
            appearanceRegions=$appearanceRegions,
            navbarColorManagedByIme=$navbarColorManagedByIme,
            behavior=$behavior,
            requestedVisibleTypes=$requestedVisibleTypes,
            packageName='$packageName',
            letterboxes=$letterboxes,
            letterboxesArray=${letterboxesArray.contentToString()},
            appearanceRegionsArray=${appearanceRegionsArray.contentToString()}
            )""".trimMargin()
    }
}
