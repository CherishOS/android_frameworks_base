/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.volume.panel.component.volume.ui.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.android.compose.PlatformSliderColors
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderViewModel

private const val EXPAND_DURATION_MILLIS = 500
private const val COLLAPSE_DURATION_MILLIS = 300
private const val SHRINK_FRACTION = 0.55f
private const val SCALE_FRACTION = 0.9f

/** Volume sliders laid out in a collapsable column */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ColumnVolumeSliders(
    viewModels: List<SliderViewModel>,
    isExpanded: Boolean,
    onExpandedChanged: (Boolean) -> Unit,
    sliderColors: PlatformSliderColors,
    isExpandable: Boolean,
    modifier: Modifier = Modifier,
) {
    require(viewModels.isNotEmpty())
    val transition = updateTransition(isExpanded, label = "CollapsableSliders")
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val sliderViewModel: SliderViewModel = viewModels.first()
            val sliderState by viewModels.first().slider.collectAsState()
            VolumeSlider(
                modifier = Modifier.weight(1f),
                state = sliderState,
                onValueChange = { newValue: Float ->
                    sliderViewModel.onValueChanged(sliderState, newValue)
                },
                onValueChangeFinished = { sliderViewModel.onValueChangeFinished() },
                onIconTapped = { sliderViewModel.toggleMuted(sliderState) },
                sliderColors = sliderColors,
            )

            val expandButtonStateDescription =
                if (isExpanded) stringResource(R.string.volume_panel_expanded_sliders)
                else stringResource(R.string.volume_panel_collapsed_sliders)
            if (isExpandable) {
                ExpandButton(
                    modifier =
                        Modifier.semantics {
                            role = Role.Switch
                            stateDescription = expandButtonStateDescription
                        },
                    isExpanded = isExpanded,
                    onExpandedChanged = onExpandedChanged,
                    sliderColors = sliderColors,
                )
            }
        }
        transition.AnimatedVisibility(
            visible = { it },
            enter =
                expandVertically(animationSpec = tween(durationMillis = EXPAND_DURATION_MILLIS)),
            exit =
                shrinkVertically(animationSpec = tween(durationMillis = COLLAPSE_DURATION_MILLIS)),
        ) {
            // This box allows sliders to slide towards top when the container is shrinking and
            // slide from top when the container is expanding.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                Column {
                    for (index in 1..viewModels.lastIndex) {
                        val sliderViewModel: SliderViewModel = viewModels[index]
                        val sliderState by sliderViewModel.slider.collectAsState()
                        transition.AnimatedVisibility(
                            modifier = Modifier.padding(top = 16.dp),
                            visible = { it },
                            enter = enterTransition(index = index, totalCount = viewModels.size),
                            exit = exitTransition(index = index, totalCount = viewModels.size)
                        ) {
                            VolumeSlider(
                                modifier = Modifier.fillMaxWidth(),
                                state = sliderState,
                                onValueChange = { newValue: Float ->
                                    sliderViewModel.onValueChanged(sliderState, newValue)
                                },
                                onValueChangeFinished = { sliderViewModel.onValueChangeFinished() },
                                onIconTapped = { sliderViewModel.toggleMuted(sliderState) },
                                sliderColors = sliderColors,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandButton(
    isExpanded: Boolean,
    onExpandedChanged: (Boolean) -> Unit,
    sliderColors: PlatformSliderColors,
    modifier: Modifier = Modifier,
) {
    IconButton(
        modifier = modifier.size(64.dp),
        onClick = { onExpandedChanged(!isExpanded) },
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = sliderColors.indicatorColor,
                contentColor = sliderColors.iconColor
            ),
    ) {
        Icon(
            painter =
                painterResource(
                    if (isExpanded) {
                        R.drawable.ic_filled_arrow_down
                    } else {
                        R.drawable.ic_filled_arrow_up
                    }
                ),
            contentDescription = null,
        )
    }
}

private fun enterTransition(index: Int, totalCount: Int): EnterTransition {
    val enterDelay = ((totalCount - index + 1) * 10).coerceAtLeast(0)
    val enterDuration = (EXPAND_DURATION_MILLIS - enterDelay).coerceAtLeast(100)
    return scaleIn(
        initialScale = SCALE_FRACTION,
        animationSpec = tween(durationMillis = enterDuration, delayMillis = enterDelay),
    ) +
        expandVertically(
            initialHeight = { (it * SHRINK_FRACTION).toInt() },
            animationSpec = tween(durationMillis = enterDuration, delayMillis = enterDelay),
            clip = false,
        ) +
        fadeIn(
            animationSpec = tween(durationMillis = enterDuration, delayMillis = enterDelay),
        )
}

private fun exitTransition(index: Int, totalCount: Int): ExitTransition {
    val exitDuration = (COLLAPSE_DURATION_MILLIS - (totalCount - index + 1) * 10).coerceAtLeast(100)
    return scaleOut(
        targetScale = SCALE_FRACTION,
        animationSpec = tween(durationMillis = exitDuration),
    ) +
        shrinkVertically(
            targetHeight = { (it * SHRINK_FRACTION).toInt() },
            animationSpec = tween(durationMillis = exitDuration),
            clip = false,
        ) +
        fadeOut(animationSpec = tween(durationMillis = exitDuration))
}
