/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.res.Resources
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.LEFT
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.RIGHT
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.core.content.res.ResourcesCompat
import com.android.systemui.R
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordancesCombinedViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.VibratorHelper
import javax.inject.Inject

class AlignShortcutsToUdfpsSection
@Inject
constructor(
    @Main private val resources: Resources,
    private val featureFlags: FeatureFlags,
    private val keyguardQuickAffordancesCombinedViewModel:
        KeyguardQuickAffordancesCombinedViewModel,
    private val keyguardRootViewModel: KeyguardRootViewModel,
    private val falsingManager: FalsingManager,
    private val indicationController: KeyguardIndicationController,
    private val vibratorHelper: VibratorHelper,
) : KeyguardSection() {
    private var leftShortcutHandle: KeyguardQuickAffordanceViewBinder.Binding? = null
    private var rightShortcutHandle: KeyguardQuickAffordanceViewBinder.Binding? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (featureFlags.isEnabled(Flags.MIGRATE_SPLIT_KEYGUARD_BOTTOM_AREA)) {
            addLeftShortcut(constraintLayout)
            addRightShortcut(constraintLayout)
        }
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (featureFlags.isEnabled(Flags.MIGRATE_SPLIT_KEYGUARD_BOTTOM_AREA)) {
            leftShortcutHandle =
                KeyguardQuickAffordanceViewBinder.bind(
                    constraintLayout.requireViewById(R.id.start_button),
                    keyguardQuickAffordancesCombinedViewModel.startButton,
                    keyguardRootViewModel.alpha,
                    falsingManager,
                    vibratorHelper,
                ) {
                    indicationController.showTransientIndication(it)
                }
            rightShortcutHandle =
                KeyguardQuickAffordanceViewBinder.bind(
                    constraintLayout.requireViewById(R.id.end_button),
                    keyguardQuickAffordancesCombinedViewModel.endButton,
                    keyguardRootViewModel.alpha,
                    falsingManager,
                    vibratorHelper,
                ) {
                    indicationController.showTransientIndication(it)
                }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val width = resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_width)
        val height = resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height)

        constraintSet.apply {
            constrainWidth(R.id.start_button, width)
            constrainHeight(R.id.start_button, height)
            connect(R.id.start_button, LEFT, PARENT_ID, LEFT)
            connect(R.id.start_button, RIGHT, R.id.lock_icon_view, LEFT)
            connect(R.id.start_button, TOP, R.id.lock_icon_view, TOP)
            connect(R.id.start_button, BOTTOM, R.id.lock_icon_view, BOTTOM)

            constrainWidth(R.id.end_button, width)
            constrainHeight(R.id.end_button, height)
            connect(R.id.end_button, RIGHT, PARENT_ID, RIGHT)
            connect(R.id.end_button, LEFT, R.id.lock_icon_view, RIGHT)
            connect(R.id.end_button, TOP, R.id.lock_icon_view, TOP)
            connect(R.id.end_button, BOTTOM, R.id.lock_icon_view, BOTTOM)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        leftShortcutHandle?.destroy()
        rightShortcutHandle?.destroy()
        constraintLayout.removeView(R.id.start_button)
        constraintLayout.removeView(R.id.end_button)
    }

    private fun addLeftShortcut(constraintLayout: ConstraintLayout) {
        val padding =
            constraintLayout.resources.getDimensionPixelSize(
                R.dimen.keyguard_affordance_fixed_padding
            )
        val view =
            LaunchableImageView(constraintLayout.context, null).apply {
                id = R.id.start_button
                scaleType = ImageView.ScaleType.FIT_CENTER
                background =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_bg,
                        context.theme
                    )
                foreground =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_selected_border,
                        context.theme
                    )
                visibility = View.INVISIBLE
                setPadding(padding, padding, padding, padding)
            }
        constraintLayout.addView(view)
    }

    private fun addRightShortcut(constraintLayout: ConstraintLayout) {
        if (constraintLayout.findViewById<View>(R.id.end_button) != null) return

        val padding =
            constraintLayout.resources.getDimensionPixelSize(
                R.dimen.keyguard_affordance_fixed_padding
            )
        val view =
            LaunchableImageView(constraintLayout.context, null).apply {
                id = R.id.end_button
                scaleType = ImageView.ScaleType.FIT_CENTER
                background =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_bg,
                        context.theme
                    )
                foreground =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_selected_border,
                        context.theme
                    )
                visibility = View.INVISIBLE
                setPadding(padding, padding, padding, padding)
            }
        constraintLayout.addView(view)
    }
}
