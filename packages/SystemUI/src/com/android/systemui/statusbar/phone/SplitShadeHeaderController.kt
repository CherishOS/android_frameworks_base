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

package com.android.systemui.statusbar.phone

import android.view.View
import androidx.constraintlayout.motion.widget.MotionLayout
import com.android.settingslib.Utils
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.qs.ChipVisibilityListener
import com.android.systemui.qs.HeaderPrivacyIconsController
import com.android.systemui.qs.carrier.QSCarrierGroupController
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent.StatusBarScope
import com.android.systemui.statusbar.phone.dagger.StatusBarViewModule.SPLIT_SHADE_BATTERY_CONTROLLER
import com.android.systemui.statusbar.phone.dagger.StatusBarViewModule.SPLIT_SHADE_HEADER
import java.io.FileDescriptor
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Named

@StatusBarScope
class SplitShadeHeaderController @Inject constructor(
    @Named(SPLIT_SHADE_HEADER) private val statusBar: View,
    private val statusBarIconController: StatusBarIconController,
    private val privacyIconsController: HeaderPrivacyIconsController,
    qsCarrierGroupControllerBuilder: QSCarrierGroupController.Builder,
    featureFlags: FeatureFlags,
    @Named(SPLIT_SHADE_BATTERY_CONTROLLER) batteryMeterViewController: BatteryMeterViewController,
    dumpManager: DumpManager
) : Dumpable {

    companion object {
        private val HEADER_TRANSITION_ID = R.id.header_transition
        private val SPLIT_HEADER_TRANSITION_ID = R.id.split_header_transition
        private val QQS_HEADER_CONSTRAINT = R.id.qqs_header_constraint
        private val QS_HEADER_CONSTRAINT = R.id.qs_header_constraint
        private val SPLIT_HEADER_CONSTRAINT = R.id.split_header_constraint

        private fun Int.stateToString() = when (this) {
            QQS_HEADER_CONSTRAINT -> "QQS Header"
            QS_HEADER_CONSTRAINT -> "QS Header"
            SPLIT_HEADER_CONSTRAINT -> "Split Header"
            else -> "Unknown state"
        }
    }

    private val combinedHeaders = featureFlags.isEnabled(Flags.COMBINED_QS_HEADERS)
    private val iconManager: StatusBarIconController.TintedIconManager
    private val iconContainer: StatusIconContainer
    private val carrierIconSlots: List<String>
    private val qsCarrierGroupController: QSCarrierGroupController
    private var visible = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            updateListeners()
        }

    var shadeExpanded = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            onShadeExpandedChanged()
        }

    var splitShadeMode = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            onSplitShadeModeChanged()
        }

    var shadeExpandedFraction = -1f
        set(value) {
            if (visible && field != value) {
                statusBar.alpha = ShadeInterpolation.getContentAlpha(value)
                field = value
            }
        }

    var qsExpandedFraction = -1f
        set(value) {
            if (visible && field != value) {
                field = value
                updateVisibility()
                updatePosition()
            }
        }

    var qsScrollY = 0
        set(value) {
            if (field != value) {
                field = value
                updateScrollY()
            }
        }

    private val chipVisibilityListener: ChipVisibilityListener = object : ChipVisibilityListener {
        override fun onChipVisibilityRefreshed(visible: Boolean) {
            if (statusBar is MotionLayout) {
                val state = statusBar.getConstraintSet(QQS_HEADER_CONSTRAINT).apply {
                    setAlpha(R.id.statusIcons, if (visible) 0f else 1f)
                    setAlpha(R.id.batteryRemainingIcon, if (visible) 0f else 1f)
                }
                statusBar.updateState(QQS_HEADER_CONSTRAINT, state)
            }
        }
    }

    init {
        if (statusBar is MotionLayout) {
            val context = statusBar.context
            val resources = statusBar.resources
            statusBar.getConstraintSet(QQS_HEADER_CONSTRAINT)
                    .load(context, resources.getXml(R.xml.qqs_header))
            statusBar.getConstraintSet(QS_HEADER_CONSTRAINT)
                    .load(context, resources.getXml(R.xml.qs_header))
            statusBar.getConstraintSet(SPLIT_HEADER_CONSTRAINT)
                    .load(context, resources.getXml(R.xml.split_header))
            privacyIconsController.chipVisibilityListener = chipVisibilityListener
        }
    }

    init {
        batteryMeterViewController.init()
        val batteryIcon: BatteryMeterView = statusBar.findViewById(R.id.batteryRemainingIcon)

        // battery settings same as in QS icons
        batteryMeterViewController.ignoreTunerUpdates()
        batteryIcon.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE)

        iconContainer = statusBar.findViewById(R.id.statusIcons)
        iconManager = StatusBarIconController.TintedIconManager(iconContainer, featureFlags)
        iconManager.setTint(Utils.getColorAttrDefaultColor(statusBar.context,
                android.R.attr.textColorPrimary))

        carrierIconSlots = if (featureFlags.isEnabled(Flags.COMBINED_STATUS_BAR_SIGNAL_ICONS)) {
            listOf(
                statusBar.context.getString(com.android.internal.R.string.status_bar_no_calling),
                statusBar.context.getString(com.android.internal.R.string.status_bar_call_strength)
            )
        } else {
            listOf(statusBar.context.getString(com.android.internal.R.string.status_bar_mobile))
        }
        qsCarrierGroupController = qsCarrierGroupControllerBuilder
                .setQSCarrierGroup(statusBar.findViewById(R.id.carrier_group))
                .build()

        dumpManager.registerDumpable(this)

        updateVisibility()
        updateConstraints()
    }

    private fun updateScrollY() {
        if (!splitShadeMode && combinedHeaders) {
            statusBar.scrollY = qsScrollY
        }
    }

    private fun onShadeExpandedChanged() {
        if (shadeExpanded) {
            privacyIconsController.startListening()
        } else {
            privacyIconsController.stopListening()
        }
        updateVisibility()
        updatePosition()
    }

    private fun onSplitShadeModeChanged() {
        if (splitShadeMode || combinedHeaders) {
            privacyIconsController.onParentVisible()
        } else {
            privacyIconsController.onParentInvisible()
        }
        updateVisibility()
        updateConstraints()
    }

    private fun updateVisibility() {
        val visibility = if (!splitShadeMode && !combinedHeaders) {
            View.GONE
        } else if (shadeExpanded) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
        if (statusBar.visibility != visibility) {
            statusBar.visibility = visibility
            visible = visibility == View.VISIBLE
        }
    }

    private fun updateConstraints() {
        if (!combinedHeaders) {
            return
        }
        statusBar as MotionLayout
        if (splitShadeMode) {
            statusBar.setTransition(SPLIT_HEADER_TRANSITION_ID)
        } else {
            statusBar.setTransition(HEADER_TRANSITION_ID)
            statusBar.transitionToStart()
            updatePosition()
            updateScrollY()
        }
    }

    private fun updatePosition() {
        if (statusBar is MotionLayout && !splitShadeMode && visible) {
            statusBar.setProgress(qsExpandedFraction)
        }
    }

    private fun updateListeners() {
        qsCarrierGroupController.setListening(visible)
        if (visible) {
            updateSingleCarrier(qsCarrierGroupController.isSingleCarrier)
            qsCarrierGroupController.setOnSingleCarrierChangedListener { updateSingleCarrier(it) }
            statusBarIconController.addIconGroup(iconManager)
        } else {
            qsCarrierGroupController.setOnSingleCarrierChangedListener(null)
            statusBarIconController.removeIconGroup(iconManager)
        }
    }

    private fun updateSingleCarrier(singleCarrier: Boolean) {
        if (singleCarrier) {
            iconContainer.removeIgnoredSlots(carrierIconSlots)
        } else {
            iconContainer.addIgnoredSlots(carrierIconSlots)
        }
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.println("visible: $visible")
        pw.println("shadeExpanded: $shadeExpanded")
        pw.println("shadeExpandedFraction: $shadeExpandedFraction")
        pw.println("splitShadeMode: $splitShadeMode")
        pw.println("qsExpandedFraction: $qsExpandedFraction")
        pw.println("qsScrollY: $qsScrollY")
        if (combinedHeaders) {
            statusBar as MotionLayout
            pw.println("currentState: ${statusBar.currentState.stateToString()}")
        }
    }
}
