/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.flicker

import android.graphics.Region
import android.view.Surface
import com.android.server.wm.flicker.dsl.EventLogAssertion
import com.android.server.wm.flicker.dsl.LayersAssertion
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.wm.shell.flicker.FlickerTestBase.Companion.DOCKED_STACK_DIVIDER

@JvmOverloads
fun LayersAssertion.appPairsDividerIsVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    end("appPairsDividerIsVisible", bugId, enabled) {
        this.showsLayer(FlickerTestBase.SPLIT_DIVIDER)
    }
}

@JvmOverloads
fun LayersAssertion.appPairsDividerIsInvisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    end("appPairsDividerIsInVisible", bugId, enabled) {
        this.hasNotLayer(FlickerTestBase.SPLIT_DIVIDER)
    }
}

@JvmOverloads
fun LayersAssertion.appPairsDividerBecomesVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("dividerLayerBecomesVisible") {
        this.hidesLayer(FlickerTestBase.DOCKED_STACK_DIVIDER)
                .then()
                .showsLayer(FlickerTestBase.DOCKED_STACK_DIVIDER)
    }
}

@JvmOverloads
fun LayersAssertion.dockedStackDividerIsVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    end("dockedStackDividerIsVisible", bugId, enabled) {
        this.showsLayer(FlickerTestBase.DOCKED_STACK_DIVIDER)
    }
}

@JvmOverloads
fun LayersAssertion.dockedStackDividerBecomesVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("dividerLayerBecomesVisible") {
        this.hidesLayer(DOCKED_STACK_DIVIDER)
                .then()
                .showsLayer(DOCKED_STACK_DIVIDER)
    }
}

@JvmOverloads
fun LayersAssertion.dockedStackDividerBecomesInvisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("dividerLayerBecomesInvisible") {
        this.showsLayer(DOCKED_STACK_DIVIDER)
                .then()
                .hidesLayer(DOCKED_STACK_DIVIDER)
    }
}

@JvmOverloads
fun LayersAssertion.dockedStackDividerIsInvisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    end("dockedStackDividerIsInvisible", bugId, enabled) {
        this.hasNotLayer(FlickerTestBase.DOCKED_STACK_DIVIDER)
    }
}

@JvmOverloads
fun LayersAssertion.appPairsPrimaryBoundsIsVisible(
    rotation: Int,
    primaryLayerName: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    end("PrimaryAppBounds", bugId, enabled) {
        val entry = this.trace.entries.firstOrNull()
                ?: throw IllegalStateException("Trace is empty")
        val dividerRegion = entry.getVisibleBounds(FlickerTestBase.SPLIT_DIVIDER)
        this.hasVisibleRegion(primaryLayerName, getPrimaryRegion(dividerRegion, rotation))
    }
}

@JvmOverloads
fun LayersAssertion.appPairsSecondaryBoundsIsVisible(
    rotation: Int,
    secondaryLayerName: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    end("SecondaryAppBounds", bugId, enabled) {
        val entry = this.trace.entries.firstOrNull()
                ?: throw IllegalStateException("Trace is empty")
        val dividerRegion = entry.getVisibleBounds(FlickerTestBase.SPLIT_DIVIDER)
        this.hasVisibleRegion(secondaryLayerName, getSecondaryRegion(dividerRegion, rotation))
    }
}

@JvmOverloads
fun LayersAssertion.dockedStackPrimaryBoundsIsVisible(
    rotation: Int,
    primaryLayerName: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    end("PrimaryAppBounds", bugId, enabled) {
        val entry = this.trace.entries.firstOrNull()
                ?: throw IllegalStateException("Trace is empty")
        val dividerRegion = entry.getVisibleBounds(FlickerTestBase.DOCKED_STACK_DIVIDER)
        this.hasVisibleRegion(primaryLayerName, getPrimaryRegion(dividerRegion, rotation))
    }
}

@JvmOverloads
fun LayersAssertion.dockedStackSecondaryBoundsIsVisible(
    rotation: Int,
    secondaryLayerName: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    end("SecondaryAppBounds", bugId, enabled) {
        val entry = this.trace.entries.firstOrNull()
                ?: throw IllegalStateException("Trace is empty")
        val dividerRegion = entry.getVisibleBounds(FlickerTestBase.DOCKED_STACK_DIVIDER)
        this.hasVisibleRegion(secondaryLayerName, getSecondaryRegion(dividerRegion, rotation))
    }
}

fun getPrimaryRegion(dividerRegion: Region, rotation: Int): Region {
    val displayBounds = WindowUtils.getDisplayBounds(rotation)
    return if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
        Region(0, 0, displayBounds.getBounds().right,
                dividerRegion.getBounds().bottom - WindowUtils.dockedStackDividerInset)
    } else {
        Region(0, 0, dividerRegion.getBounds().left,
                dividerRegion.getBounds().right - WindowUtils.dockedStackDividerInset)
    }
}

fun getSecondaryRegion(dividerRegion: Region, rotation: Int): Region {
    val displayBounds = WindowUtils.getDisplayBounds(rotation)
    return if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
        Region(0,
                dividerRegion.getBounds().bottom - WindowUtils.dockedStackDividerInset,
                displayBounds.getBounds().right,
                displayBounds.getBounds().bottom - WindowUtils.dockedStackDividerInset)
    } else {
        Region(dividerRegion.getBounds().right, 0,
                displayBounds.getBounds().right,
                displayBounds.getBounds().bottom - WindowUtils.dockedStackDividerInset)
    }
}

fun EventLogAssertion.focusChanges(
    vararg windows: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all(enabled = enabled, bugId = bugId) {
        this.focusChanges(windows)
    }
}

fun EventLogAssertion.focusDoesNotChange(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all(enabled = enabled, bugId = bugId) {
        this.focusDoesNotChange()
    }
}
