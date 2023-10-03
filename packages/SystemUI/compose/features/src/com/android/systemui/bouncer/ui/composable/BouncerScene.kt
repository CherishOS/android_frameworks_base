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
 */

package com.android.systemui.bouncer.ui.composable

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.android.compose.PlatformButton
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.systemui.bouncer.ui.viewmodel.AuthMethodBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.BouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PasswordBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PatternBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel
import com.android.systemui.common.shared.model.Text.Companion.loadText
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.composable.ComposableScene
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object Bouncer {
    object Elements {
        val Background = ElementKey("BouncerBackground")
        val Content = ElementKey("BouncerContent")
    }
}

/** The bouncer scene displays authentication challenges like PIN, password, or pattern. */
@SysUISingleton
class BouncerScene
@Inject
constructor(
    private val viewModel: BouncerViewModel,
    private val dialogFactory: BouncerSceneDialogFactory,
) : ComposableScene {
    override val key = SceneKey.Bouncer

    override val destinationScenes: StateFlow<Map<UserAction, SceneModel>> =
        MutableStateFlow(
                mapOf(
                    UserAction.Back to SceneModel(SceneKey.Lockscreen),
                    UserAction.Swipe(Direction.DOWN) to SceneModel(SceneKey.Lockscreen),
                )
            )
            .asStateFlow()

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) = BouncerScene(viewModel, dialogFactory, modifier)
}

@Composable
private fun SceneScope.BouncerScene(
    viewModel: BouncerViewModel,
    dialogFactory: BouncerSceneDialogFactory,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = MaterialTheme.colorScheme.surface
    val windowSizeClass = LocalWindowSizeClass.current

    Box(modifier) {
        Canvas(Modifier.element(Bouncer.Elements.Background).fillMaxSize()) {
            drawRect(color = backgroundColor)
        }

        val childModifier = Modifier.element(Bouncer.Elements.Content).fillMaxSize()

        when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Expanded ->
                SideBySide(
                    viewModel = viewModel,
                    dialogFactory = dialogFactory,
                    modifier = childModifier,
                )
            WindowWidthSizeClass.Medium ->
                Stacked(
                    viewModel = viewModel,
                    dialogFactory = dialogFactory,
                    modifier = childModifier,
                )
            else ->
                Bouncer(
                    viewModel = viewModel,
                    dialogFactory = dialogFactory,
                    modifier = childModifier,
                )
        }
    }
}

/**
 * Renders the contents of the actual bouncer UI, the area that takes user input to do an
 * authentication attempt, including all messaging UI (directives, reasoning, errors, etc.).
 */
@Composable
private fun Bouncer(
    viewModel: BouncerViewModel,
    dialogFactory: BouncerSceneDialogFactory,
    modifier: Modifier = Modifier,
) {
    val message: BouncerViewModel.MessageViewModel by viewModel.message.collectAsState()
    val authMethodViewModel: AuthMethodBouncerViewModel? by
        viewModel.authMethodViewModel.collectAsState()
    val dialogMessage: String? by viewModel.throttlingDialogMessage.collectAsState()
    var dialog: Dialog? by remember { mutableStateOf(null) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(60.dp),
        modifier = modifier.padding(start = 32.dp, top = 92.dp, end = 32.dp, bottom = 92.dp)
    ) {
        Crossfade(
            targetState = message,
            label = "Bouncer message",
            animationSpec = if (message.isUpdateAnimated) tween() else snap(),
        ) { message ->
            Text(
                text = message.text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Box(Modifier.weight(1f)) {
            when (val nonNullViewModel = authMethodViewModel) {
                is PinBouncerViewModel ->
                    PinBouncer(
                        viewModel = nonNullViewModel,
                        modifier = Modifier.align(Alignment.Center),
                    )
                is PasswordBouncerViewModel ->
                    PasswordBouncer(
                        viewModel = nonNullViewModel,
                        modifier = Modifier.align(Alignment.Center),
                    )
                is PatternBouncerViewModel ->
                    PatternBouncer(
                        viewModel = nonNullViewModel,
                        modifier =
                            Modifier.aspectRatio(1f, matchHeightConstraintsFirst = false)
                                .align(Alignment.BottomCenter),
                    )
                else -> Unit
            }
        }

        if (viewModel.isEmergencyButtonVisible) {
            Button(
                onClick = viewModel::onEmergencyServicesButtonClicked,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
            ) {
                Text(
                    text = stringResource(com.android.internal.R.string.lockscreen_emergency_call),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (dialogMessage != null) {
            if (dialog == null) {
                dialog =
                    dialogFactory().apply {
                        setMessage(dialogMessage)
                        setButton(
                            DialogInterface.BUTTON_NEUTRAL,
                            context.getString(R.string.ok),
                        ) { _, _ ->
                            viewModel.onThrottlingDialogDismissed()
                        }
                        setCancelable(false)
                        setCanceledOnTouchOutside(false)
                        show()
                    }
            }
        } else {
            dialog?.dismiss()
            dialog = null
        }
    }
}

/** Renders the UI of the user switcher that's displayed on large screens next to the bouncer UI. */
@Composable
private fun UserSwitcher(
    viewModel: BouncerViewModel,
    modifier: Modifier = Modifier,
) {
    val selectedUserImage by viewModel.selectedUserImage.collectAsState(null)
    val dropdownItems by viewModel.userSwitcherDropdown.collectAsState(emptyList())

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier,
    ) {
        selectedUserImage?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(SelectedUserImageSize),
            )
        }

        UserSwitcherDropdown(
            items = dropdownItems,
        )
    }
}

@Composable
private fun UserSwitcherDropdown(
    items: List<BouncerViewModel.UserSwitcherDropdownItemViewModel>,
) {
    val (isDropdownExpanded, setDropdownExpanded) = remember { mutableStateOf(false) }

    items.firstOrNull()?.let { firstDropdownItem ->
        Spacer(modifier = Modifier.height(40.dp))

        Box {
            PlatformButton(
                modifier =
                    Modifier
                        // Remove the built-in padding applied inside PlatformButton:
                        .padding(vertical = 0.dp)
                        .width(UserSwitcherDropdownWidth)
                        .height(UserSwitcherDropdownHeight),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                onClick = { setDropdownExpanded(!isDropdownExpanded) },
            ) {
                val context = LocalContext.current
                Text(
                    text = checkNotNull(firstDropdownItem.text.loadText(context)),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }

            UserSwitcherDropdownMenu(
                isExpanded = isDropdownExpanded,
                items = items,
                onDismissed = { setDropdownExpanded(false) },
            )
        }
    }
}

@Composable
private fun UserSwitcherDropdownMenu(
    isExpanded: Boolean,
    items: List<BouncerViewModel.UserSwitcherDropdownItemViewModel>,
    onDismissed: () -> Unit,
) {
    val context = LocalContext.current

    // TODO(b/303071855): once the FR is fixed, remove this composition local override.
    MaterialTheme(
        colorScheme =
            MaterialTheme.colorScheme.copy(
                surface = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(28.dp)),
    ) {
        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = onDismissed,
            offset =
                DpOffset(
                    x = 0.dp,
                    y = -UserSwitcherDropdownHeight,
                ),
            modifier = Modifier.width(UserSwitcherDropdownWidth),
        ) {
            items.forEach { userSwitcherDropdownItem ->
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            icon = userSwitcherDropdownItem.icon,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    },
                    text = {
                        Text(
                            text = checkNotNull(userSwitcherDropdownItem.text.loadText(context)),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        onDismissed()
                        userSwitcherDropdownItem.onClick()
                    },
                )
            }
        }
    }
}

/**
 * Arranges the bouncer contents and user switcher contents side-by-side, supporting a double tap
 * anywhere on the background to flip their positions.
 */
@Composable
private fun SideBySide(
    viewModel: BouncerViewModel,
    dialogFactory: BouncerSceneDialogFactory,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    val isLeftToRight = layoutDirection == LayoutDirection.Ltr
    val (isUserSwitcherFirst, setUserSwitcherFirst) =
        rememberSaveable(isLeftToRight) { mutableStateOf(isLeftToRight) }

    Row(
        modifier =
            modifier.pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        // Depending on where the user double tapped, switch the elements such that
                        // the bouncer contents element is closer to the side that was double
                        // tapped.
                        setUserSwitcherFirst(offset.x > size.width / 2)
                    }
                )
            },
    ) {
        val animatedOffset by
            animateFloatAsState(
                targetValue =
                    if (isUserSwitcherFirst) {
                        // When the user switcher is first, both elements have their natural
                        // placement so they are not offset in any way.
                        0f
                    } else if (isLeftToRight) {
                        // Since the user switcher is not first, the elements have to be swapped
                        // horizontally. In the case of LTR locales, this means pushing the user
                        // switcher to the right, hence the positive number.
                        1f
                    } else {
                        // Since the user switcher is not first, the elements have to be swapped
                        // horizontally. In the case of RTL locale, this means pushing the user
                        // switcher to the left, hence the negative number.
                        -1f
                    },
                label = "offset",
            )

        UserSwitcher(
            viewModel = viewModel,
            modifier =
                Modifier.fillMaxHeight().weight(1f).graphicsLayer {
                    translationX = size.width * animatedOffset
                    alpha = animatedAlpha(animatedOffset)
                },
        )
        Box(
            modifier =
                Modifier.fillMaxHeight().weight(1f).graphicsLayer {
                    // A negative sign is used to make sure this is offset in the direction that's
                    // opposite of the direction that the user switcher is pushed in.
                    translationX = -size.width * animatedOffset
                    alpha = animatedAlpha(animatedOffset)
                }
        ) {
            Bouncer(
                viewModel = viewModel,
                dialogFactory = dialogFactory,
                modifier = Modifier.widthIn(max = 400.dp).align(Alignment.BottomCenter),
            )
        }
    }
}

/** Arranges the bouncer contents and user switcher contents one on top of the other. */
@Composable
private fun Stacked(
    viewModel: BouncerViewModel,
    dialogFactory: BouncerSceneDialogFactory,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        UserSwitcher(
            viewModel = viewModel,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Bouncer(
            viewModel = viewModel,
            dialogFactory = dialogFactory,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

interface BouncerSceneDialogFactory {
    operator fun invoke(): AlertDialog
}

/**
 * Calculates an alpha for the user switcher and bouncer such that it's at `1` when the offset of
 * the two reaches a stopping point but `0` in the middle of the transition.
 */
private fun animatedAlpha(
    offset: Float,
): Float {
    // Describes a curve that is made of two parabolic U-shaped curves mirrored horizontally around
    // the y-axis. The U on the left runs between x = -1 and x = 0 while the U on the right runs
    // between x = 0 and x = 1.
    //
    // The minimum values of the curves are at -0.5 and +0.5.
    //
    // Both U curves are vertically scaled such that they reach the points (-1, 1) and (1, 1).
    //
    // Breaking it down, it's y = a×(|x|-m)²+b, where:
    // x: the offset
    // y: the alpha
    // m: x-axis center of the parabolic curves, where the minima are.
    // b: y-axis offset to apply to the entire curve so the animation spends more time with alpha =
    // 0.
    // a: amplitude to scale the parabolic curves to reach y = 1 at x = -1, x = 0, and x = +1.
    val m = 0.5f
    val b = -0.25
    val a = (1 - b) / m.pow(2)

    return max(0f, (a * (abs(offset) - m).pow(2) + b).toFloat())
}

private val SelectedUserImageSize = 190.dp
private val UserSwitcherDropdownWidth = SelectedUserImageSize + 2 * 29.dp
private val UserSwitcherDropdownHeight = 60.dp
