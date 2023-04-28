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

@file:JvmName("CommonAssertions")

package com.android.wm.shell.flicker

import android.tools.common.Rotation
import android.tools.common.datatypes.Region
import android.tools.common.flicker.subject.layers.LayerTraceEntrySubject
import android.tools.common.flicker.subject.layers.LayersTraceSubject
import android.tools.common.traces.component.IComponentMatcher
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.helpers.WindowUtils

fun FlickerTest.appPairsDividerIsVisibleAtEnd() {
    assertLayersEnd { this.isVisible(APP_PAIR_SPLIT_DIVIDER_COMPONENT) }
}

fun FlickerTest.appPairsDividerIsInvisibleAtEnd() {
    assertLayersEnd { this.notContains(APP_PAIR_SPLIT_DIVIDER_COMPONENT) }
}

fun FlickerTest.appPairsDividerBecomesVisible() {
    assertLayers {
        this.isInvisible(DOCKED_STACK_DIVIDER_COMPONENT)
            .then()
            .isVisible(DOCKED_STACK_DIVIDER_COMPONENT)
    }
}

fun FlickerTest.splitScreenEntered(
    component1: IComponentMatcher,
    component2: IComponentMatcher,
    fromOtherApp: Boolean,
    appExistAtStart: Boolean = true
) {
    if (fromOtherApp) {
        if (appExistAtStart) {
            appWindowIsInvisibleAtStart(component1)
        } else {
            appWindowIsNotContainAtStart(component1)
        }
    } else {
        appWindowIsVisibleAtStart(component1)
    }
    if (appExistAtStart) {
        appWindowIsInvisibleAtStart(component2)
    } else {
        appWindowIsNotContainAtStart(component2)
    }
    splitScreenDividerIsInvisibleAtStart()

    appWindowIsVisibleAtEnd(component1)
    appWindowIsVisibleAtEnd(component2)
    splitScreenDividerIsVisibleAtEnd()
}

fun FlickerTest.splitScreenDismissed(
    component1: IComponentMatcher,
    component2: IComponentMatcher,
    toHome: Boolean
) {
    appWindowIsVisibleAtStart(component1)
    appWindowIsVisibleAtStart(component2)
    splitScreenDividerIsVisibleAtStart()

    appWindowIsInvisibleAtEnd(component1)
    if (toHome) {
        appWindowIsInvisibleAtEnd(component2)
    } else {
        appWindowIsVisibleAtEnd(component2)
    }
    splitScreenDividerIsInvisibleAtEnd()
}

fun FlickerTest.splitScreenDividerIsVisibleAtStart() {
    assertLayersStart { this.isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT) }
}

fun FlickerTest.splitScreenDividerIsVisibleAtEnd() {
    assertLayersEnd { this.isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT) }
}

fun FlickerTest.splitScreenDividerIsInvisibleAtStart() {
    assertLayersStart { this.isInvisible(SPLIT_SCREEN_DIVIDER_COMPONENT) }
}

fun FlickerTest.splitScreenDividerIsInvisibleAtEnd() {
    assertLayersEnd { this.isInvisible(SPLIT_SCREEN_DIVIDER_COMPONENT) }
}

fun FlickerTest.splitScreenDividerBecomesVisible() {
    layerBecomesVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
}

fun FlickerTest.splitScreenDividerBecomesInvisible() {
    assertLayers {
        this.isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
            .then()
            .isInvisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
    }
}

fun FlickerTest.layerBecomesVisible(component: IComponentMatcher) {
    assertLayers { this.isInvisible(component).then().isVisible(component) }
}

fun FlickerTest.layerBecomesInvisible(component: IComponentMatcher) {
    assertLayers { this.isVisible(component).then().isInvisible(component) }
}

fun FlickerTest.layerIsVisibleAtEnd(component: IComponentMatcher) {
    assertLayersEnd { this.isVisible(component) }
}

fun FlickerTest.layerKeepVisible(component: IComponentMatcher) {
    assertLayers { this.isVisible(component) }
}

fun FlickerTest.splitAppLayerBoundsBecomesVisible(
    component: IComponentMatcher,
    landscapePosLeft: Boolean,
    portraitPosTop: Boolean
) {
    assertLayers {
        this.notContains(SPLIT_SCREEN_DIVIDER_COMPONENT.or(component), isOptional = true)
            .then()
            .isInvisible(SPLIT_SCREEN_DIVIDER_COMPONENT.or(component))
            .then()
            .splitAppLayerBoundsSnapToDivider(
                component,
                landscapePosLeft,
                portraitPosTop,
                scenario.endRotation
            )
    }
}

fun FlickerTest.splitAppLayerBoundsBecomesVisibleByDrag(component: IComponentMatcher) {
    assertLayers {
        this.notContains(SPLIT_SCREEN_DIVIDER_COMPONENT.or(component), isOptional = true)
            .then()
            .isInvisible(SPLIT_SCREEN_DIVIDER_COMPONENT.or(component))
            .then()
            // TODO(b/245472831): Verify the component should snap to divider.
            .isVisible(component)
    }
}

fun FlickerTest.splitAppLayerBoundsBecomesInvisible(
    component: IComponentMatcher,
    landscapePosLeft: Boolean,
    portraitPosTop: Boolean
) {
    assertLayers {
        this.splitAppLayerBoundsSnapToDivider(
                component,
                landscapePosLeft,
                portraitPosTop,
                scenario.endRotation
            )
            .then()
            .isVisible(component, true)
            .then()
            .isInvisible(component)
    }
}

fun FlickerTest.splitAppLayerBoundsIsVisibleAtEnd(
    component: IComponentMatcher,
    landscapePosLeft: Boolean,
    portraitPosTop: Boolean
) {
    assertLayersEnd {
        splitAppLayerBoundsSnapToDivider(
            component,
            landscapePosLeft,
            portraitPosTop,
            scenario.endRotation
        )
    }
}

fun FlickerTest.splitAppLayerBoundsKeepVisible(
    component: IComponentMatcher,
    landscapePosLeft: Boolean,
    portraitPosTop: Boolean
) {
    assertLayers {
        splitAppLayerBoundsSnapToDivider(
            component,
            landscapePosLeft,
            portraitPosTop,
            scenario.endRotation
        )
    }
}

fun FlickerTest.splitAppLayerBoundsChanges(
    component: IComponentMatcher,
    landscapePosLeft: Boolean,
    portraitPosTop: Boolean
) {
    assertLayers {
        if (landscapePosLeft) {
            splitAppLayerBoundsSnapToDivider(
                component,
                landscapePosLeft,
                portraitPosTop,
                scenario.endRotation
            )
        } else {
            splitAppLayerBoundsSnapToDivider(
                    component,
                    landscapePosLeft,
                    portraitPosTop,
                    scenario.endRotation
                )
                .then()
                .isInvisible(component)
                .then()
                .splitAppLayerBoundsSnapToDivider(
                    component,
                    landscapePosLeft,
                    portraitPosTop,
                    scenario.endRotation
                )
        }
    }
}

fun LayersTraceSubject.splitAppLayerBoundsSnapToDivider(
    component: IComponentMatcher,
    landscapePosLeft: Boolean,
    portraitPosTop: Boolean,
    rotation: Rotation
): LayersTraceSubject {
    return invoke("splitAppLayerBoundsSnapToDivider") {
        it.splitAppLayerBoundsSnapToDivider(component, landscapePosLeft, portraitPosTop, rotation)
    }
}

fun LayerTraceEntrySubject.splitAppLayerBoundsSnapToDivider(
    component: IComponentMatcher,
    landscapePosLeft: Boolean,
    portraitPosTop: Boolean,
    rotation: Rotation
): LayerTraceEntrySubject {
    val displayBounds = WindowUtils.getDisplayBounds(rotation)
    return invoke {
        val dividerRegion =
            layer(SPLIT_SCREEN_DIVIDER_COMPONENT)?.visibleRegion?.region
                ?: error("$SPLIT_SCREEN_DIVIDER_COMPONENT component not found")
        visibleRegion(component)
            .coversAtMost(
                if (displayBounds.width > displayBounds.height) {
                    if (landscapePosLeft) {
                        Region.from(
                            0,
                            0,
                            (dividerRegion.bounds.left + dividerRegion.bounds.right) / 2,
                            displayBounds.bounds.bottom
                        )
                    } else {
                        Region.from(
                            (dividerRegion.bounds.left + dividerRegion.bounds.right) / 2,
                            0,
                            displayBounds.bounds.right,
                            displayBounds.bounds.bottom
                        )
                    }
                } else {
                    if (portraitPosTop) {
                        Region.from(
                            0,
                            0,
                            displayBounds.bounds.right,
                            (dividerRegion.bounds.top + dividerRegion.bounds.bottom) / 2
                        )
                    } else {
                        Region.from(
                            0,
                            (dividerRegion.bounds.top + dividerRegion.bounds.bottom) / 2,
                            displayBounds.bounds.right,
                            displayBounds.bounds.bottom
                        )
                    }
                }
            )
    }
}

fun FlickerTest.appWindowBecomesVisible(component: IComponentMatcher) {
    assertWm {
        this.isAppWindowInvisible(component)
            .then()
            .notContains(component, isOptional = true)
            .then()
            .isAppWindowInvisible(component, isOptional = true)
            .then()
            .isAppWindowVisible(component)
    }
}

fun FlickerTest.appWindowBecomesInvisible(component: IComponentMatcher) {
    assertWm { this.isAppWindowVisible(component).then().isAppWindowInvisible(component) }
}

fun FlickerTest.appWindowIsVisibleAtStart(component: IComponentMatcher) {
    assertWmStart { this.isAppWindowVisible(component) }
}

fun FlickerTest.appWindowIsVisibleAtEnd(component: IComponentMatcher) {
    assertWmEnd { this.isAppWindowVisible(component) }
}

fun FlickerTest.appWindowIsInvisibleAtStart(component: IComponentMatcher) {
    assertWmStart { this.isAppWindowInvisible(component) }
}

fun FlickerTest.appWindowIsInvisibleAtEnd(component: IComponentMatcher) {
    assertWmEnd { this.isAppWindowInvisible(component) }
}

fun FlickerTest.appWindowIsNotContainAtStart(component: IComponentMatcher) {
    assertWmStart { this.notContains(component) }
}

fun FlickerTest.appWindowKeepVisible(component: IComponentMatcher) {
    assertWm { this.isAppWindowVisible(component) }
}

fun FlickerTest.dockedStackDividerIsVisibleAtEnd() {
    assertLayersEnd { this.isVisible(DOCKED_STACK_DIVIDER_COMPONENT) }
}

fun FlickerTest.dockedStackDividerBecomesVisible() {
    assertLayers {
        this.isInvisible(DOCKED_STACK_DIVIDER_COMPONENT)
            .then()
            .isVisible(DOCKED_STACK_DIVIDER_COMPONENT)
    }
}

fun FlickerTest.dockedStackDividerBecomesInvisible() {
    assertLayers {
        this.isVisible(DOCKED_STACK_DIVIDER_COMPONENT)
            .then()
            .isInvisible(DOCKED_STACK_DIVIDER_COMPONENT)
    }
}

fun FlickerTest.dockedStackDividerNotExistsAtEnd() {
    assertLayersEnd { this.notContains(DOCKED_STACK_DIVIDER_COMPONENT) }
}

fun FlickerTest.appPairsPrimaryBoundsIsVisibleAtEnd(
    rotation: Rotation,
    primaryComponent: IComponentMatcher
) {
    assertLayersEnd {
        val dividerRegion =
            layer(APP_PAIR_SPLIT_DIVIDER_COMPONENT)?.visibleRegion?.region
                ?: error("$APP_PAIR_SPLIT_DIVIDER_COMPONENT component not found")
        visibleRegion(primaryComponent).overlaps(getPrimaryRegion(dividerRegion, rotation))
    }
}

fun FlickerTest.dockedStackPrimaryBoundsIsVisibleAtEnd(
    rotation: Rotation,
    primaryComponent: IComponentMatcher
) {
    assertLayersEnd {
        val dividerRegion =
            layer(DOCKED_STACK_DIVIDER_COMPONENT)?.visibleRegion?.region
                ?: error("$DOCKED_STACK_DIVIDER_COMPONENT component not found")
        visibleRegion(primaryComponent).overlaps(getPrimaryRegion(dividerRegion, rotation))
    }
}

fun FlickerTest.appPairsSecondaryBoundsIsVisibleAtEnd(
    rotation: Rotation,
    secondaryComponent: IComponentMatcher
) {
    assertLayersEnd {
        val dividerRegion =
            layer(APP_PAIR_SPLIT_DIVIDER_COMPONENT)?.visibleRegion?.region
                ?: error("$APP_PAIR_SPLIT_DIVIDER_COMPONENT component not found")
        visibleRegion(secondaryComponent).overlaps(getSecondaryRegion(dividerRegion, rotation))
    }
}

fun FlickerTest.dockedStackSecondaryBoundsIsVisibleAtEnd(
    rotation: Rotation,
    secondaryComponent: IComponentMatcher
) {
    assertLayersEnd {
        val dividerRegion =
            layer(DOCKED_STACK_DIVIDER_COMPONENT)?.visibleRegion?.region
                ?: error("$DOCKED_STACK_DIVIDER_COMPONENT component not found")
        visibleRegion(secondaryComponent).overlaps(getSecondaryRegion(dividerRegion, rotation))
    }
}

fun getPrimaryRegion(dividerRegion: Region, rotation: Rotation): Region {
    val displayBounds = WindowUtils.getDisplayBounds(rotation)
    return if (rotation.isRotated()) {
        Region.from(
            0,
            0,
            dividerRegion.bounds.left + WindowUtils.dockedStackDividerInset,
            displayBounds.bounds.bottom
        )
    } else {
        Region.from(
            0,
            0,
            displayBounds.bounds.right,
            dividerRegion.bounds.top + WindowUtils.dockedStackDividerInset
        )
    }
}

fun getSecondaryRegion(dividerRegion: Region, rotation: Rotation): Region {
    val displayBounds = WindowUtils.getDisplayBounds(rotation)
    return if (rotation.isRotated()) {
        Region.from(
            dividerRegion.bounds.right - WindowUtils.dockedStackDividerInset,
            0,
            displayBounds.bounds.right,
            displayBounds.bounds.bottom
        )
    } else {
        Region.from(
            0,
            dividerRegion.bounds.bottom - WindowUtils.dockedStackDividerInset,
            displayBounds.bounds.right,
            displayBounds.bounds.bottom
        )
    }
}
