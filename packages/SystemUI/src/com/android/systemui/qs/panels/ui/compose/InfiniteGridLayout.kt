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

package com.android.systemui.qs.panels.ui.compose

import android.graphics.drawable.Animatable
import android.text.TextUtils
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.background
import com.android.compose.theme.colorAttr
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.domain.interactor.IconTilesInteractor
import com.android.systemui.qs.panels.ui.viewmodel.ActiveTileColorAttributes
import com.android.systemui.qs.panels.ui.viewmodel.AvailableEditActions
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileColorAttributes
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor.Companion.POSITION_AT_END
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapLatest

@SysUISingleton
class InfiniteGridLayout @Inject constructor(private val iconTilesInteractor: IconTilesInteractor) :
    GridLayout {

    private object TileType

    @Composable
    override fun TileGrid(
        tiles: List<TileViewModel>,
        modifier: Modifier,
    ) {
        DisposableEffect(tiles) {
            val token = Any()
            tiles.forEach { it.startListening(token) }
            onDispose { tiles.forEach { it.stopListening(token) } }
        }
        val iconTilesSpecs by
            iconTilesInteractor.iconTilesSpecs.collectAsState(initial = emptySet())

        TileLazyGrid(modifier) {
            items(
                tiles.size,
                span = { index ->
                    val iconOnly = iconTilesSpecs.contains(tiles[index].spec)
                    if (iconOnly) {
                        GridItemSpan(1)
                    } else {
                        GridItemSpan(2)
                    }
                }
            ) { index ->
                Tile(
                    tiles[index],
                    iconTilesSpecs.contains(tiles[index].spec),
                    Modifier.height(dimensionResource(id = R.dimen.qs_tile_height))
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Composable
    private fun Tile(
        tile: TileViewModel,
        iconOnly: Boolean,
        modifier: Modifier,
    ) {
        val state: TileUiState by
            tile.state
                .mapLatest { it.toUiState() }
                .collectAsState(initial = tile.currentState.toUiState())
        val context = LocalContext.current

        Row(
            modifier = modifier.clickable { tile.onClick(null) }.tileModifier(state.colors),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = tileHorizontalArrangement(iconOnly)
        ) {
            val icon =
                remember(state.icon) {
                    state.icon.get().let {
                        if (it is QSTileImpl.ResourceIcon) {
                            Icon.Resource(it.resId, null)
                        } else {
                            Icon.Loaded(it.getDrawable(context), null)
                        }
                    }
                }
            TileContent(
                label = state.label.toString(),
                secondaryLabel = state.secondaryLabel.toString(),
                icon = icon,
                colors = state.colors,
                iconOnly = iconOnly
            )
        }
    }

    @Composable
    override fun EditTileGrid(
        tiles: List<EditTileViewModel>,
        modifier: Modifier,
        onAddTile: (TileSpec, Int) -> Unit,
        onRemoveTile: (TileSpec) -> Unit,
    ) {
        val (currentTiles, otherTiles) = tiles.partition { it.isCurrent }
        val (otherTilesStock, otherTilesCustom) = otherTiles.partition { it.appName == null }
        val addTileToEnd: (TileSpec) -> Unit by rememberUpdatedState {
            onAddTile(it, POSITION_AT_END)
        }
        val iconOnlySpecs by iconTilesInteractor.iconTilesSpecs.collectAsState(initial = emptySet())
        val isIconOnly: (TileSpec) -> Boolean =
            remember(iconOnlySpecs) { { tileSpec: TileSpec -> tileSpec in iconOnlySpecs } }

        TileLazyGrid(modifier = modifier) {
            // These Text are just placeholders to see the different sections. Not final UI.
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Current tiles", color = Color.White)
            }

            editTiles(
                currentTiles,
                ClickAction.REMOVE,
                onRemoveTile,
                isIconOnly,
                indicatePosition = true,
            )

            item(span = { GridItemSpan(maxLineSpan) }) { Text("Tiles to add", color = Color.White) }

            editTiles(
                otherTilesStock,
                ClickAction.ADD,
                addTileToEnd,
                isIconOnly,
            )

            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Custom tiles to add", color = Color.White)
            }

            editTiles(
                otherTilesCustom,
                ClickAction.ADD,
                addTileToEnd,
                isIconOnly,
            )
        }
    }

    private fun LazyGridScope.editTiles(
        tiles: List<EditTileViewModel>,
        clickAction: ClickAction,
        onClick: (TileSpec) -> Unit,
        isIconOnly: (TileSpec) -> Boolean,
        indicatePosition: Boolean = false,
    ) {
        items(
            count = tiles.size,
            key = { tiles[it].tileSpec.spec },
            span = { GridItemSpan(if (isIconOnly(tiles[it].tileSpec)) 1 else 2) },
            contentType = { TileType }
        ) {
            val viewModel = tiles[it]
            val canClick =
                when (clickAction) {
                    ClickAction.ADD -> AvailableEditActions.ADD in viewModel.availableEditActions
                    ClickAction.REMOVE ->
                        AvailableEditActions.REMOVE in viewModel.availableEditActions
                }
            val onClickActionName =
                when (clickAction) {
                    ClickAction.ADD ->
                        stringResource(id = R.string.accessibility_qs_edit_tile_add_action)
                    ClickAction.REMOVE ->
                        stringResource(id = R.string.accessibility_qs_edit_remove_tile_action)
                }
            val stateDescription =
                if (indicatePosition) {
                    stringResource(id = R.string.accessibility_qs_edit_position, it + 1)
                } else {
                    ""
                }

            Box(
                modifier =
                    Modifier.clickable(enabled = canClick) { onClick.invoke(viewModel.tileSpec) }
                        .animateItem()
                        .semantics {
                            onClick(onClickActionName) { false }
                            this.stateDescription = stateDescription
                        }
            ) {
                EditTile(
                    tileViewModel = viewModel,
                    isIconOnly(viewModel.tileSpec),
                    modifier = Modifier.height(dimensionResource(id = R.dimen.qs_tile_height))
                )
                if (canClick) {
                    Badge(clickAction, Modifier.align(Alignment.TopEnd))
                }
            }
        }
    }

    @Composable
    private fun Badge(action: ClickAction, modifier: Modifier = Modifier) {
        Box(modifier = modifier.size(16.dp).background(Color.Cyan, shape = CircleShape)) {
            Icon(
                imageVector =
                    when (action) {
                        ClickAction.ADD -> Icons.Filled.Add
                        ClickAction.REMOVE -> Icons.Filled.Remove
                    },
                "",
                tint = Color.Black,
            )
        }
    }

    @Composable
    private fun EditTile(
        tileViewModel: EditTileViewModel,
        iconOnly: Boolean,
        modifier: Modifier = Modifier,
    ) {
        val label = tileViewModel.label.load() ?: tileViewModel.tileSpec.spec
        val colors = ActiveTileColorAttributes

        Row(
            modifier = modifier.tileModifier(colors).semantics { this.contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = tileHorizontalArrangement(iconOnly)
        ) {
            TileContent(
                label = label,
                secondaryLabel = tileViewModel.appName?.load(),
                colors = colors,
                icon = tileViewModel.icon,
                iconOnly = iconOnly,
                animateIconToEnd = true,
            )
        }
    }

    private enum class ClickAction {
        ADD,
        REMOVE,
    }
}

@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
private fun TileIcon(
    icon: Icon,
    color: Color,
    animateToEnd: Boolean = false,
) {
    val modifier = Modifier.size(dimensionResource(id = R.dimen.qs_icon_size))
    val context = LocalContext.current
    val loadedDrawable =
        remember(icon, context) {
            when (icon) {
                is Icon.Loaded -> icon.drawable
                is Icon.Resource -> AppCompatResources.getDrawable(context, icon.res)
            }
        }
    if (loadedDrawable !is Animatable) {
        Icon(
            icon = icon,
            tint = color,
            modifier = modifier,
        )
    } else if (icon is Icon.Resource) {
        val image = AnimatedImageVector.animatedVectorResource(id = icon.res)
        val painter =
            if (animateToEnd) {
                rememberAnimatedVectorPainter(animatedImageVector = image, atEnd = true)
            } else {
                var atEnd by remember(icon.res) { mutableStateOf(false) }
                LaunchedEffect(key1 = icon.res) {
                    delay(350)
                    atEnd = true
                }
                rememberAnimatedVectorPainter(animatedImageVector = image, atEnd = atEnd)
            }
        Image(
            painter = painter,
            contentDescription = null,
            colorFilter = ColorFilter.tint(color = color),
            modifier = modifier
        )
    }
}

@Composable
private fun TileLazyGrid(
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns =
            GridCells.Fixed(integerResource(R.integer.quick_settings_infinite_grid_num_columns)),
        verticalArrangement = spacedBy(dimensionResource(R.dimen.qs_tile_margin_vertical)),
        horizontalArrangement = spacedBy(dimensionResource(R.dimen.qs_tile_margin_horizontal)),
        modifier = modifier,
        content = content,
    )
}

@Composable
private fun Modifier.tileModifier(colors: TileColorAttributes): Modifier {
    return fillMaxWidth()
        .clip(RoundedCornerShape(dimensionResource(R.dimen.qs_corner_radius)))
        .background(colorAttr(colors.background))
        .padding(horizontal = dimensionResource(id = R.dimen.qs_label_container_margin))
}

@Composable
private fun tileHorizontalArrangement(iconOnly: Boolean): Arrangement.Horizontal {
    val horizontalAlignment =
        if (iconOnly) {
            Alignment.CenterHorizontally
        } else {
            Alignment.Start
        }
    return spacedBy(
        space = dimensionResource(id = R.dimen.qs_label_container_margin),
        alignment = horizontalAlignment
    )
}

@Composable
private fun TileContent(
    label: String,
    secondaryLabel: String?,
    icon: Icon,
    colors: TileColorAttributes,
    iconOnly: Boolean,
    animateIconToEnd: Boolean = false,
) {
    TileIcon(icon, colorAttr(colors.icon), animateIconToEnd)

    if (!iconOnly) {
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxHeight()) {
            Text(
                label,
                color = colorAttr(colors.label),
                modifier = Modifier.basicMarquee(),
            )
            if (!TextUtils.isEmpty(secondaryLabel)) {
                Text(
                    secondaryLabel ?: "",
                    color = colorAttr(colors.secondaryLabel),
                    modifier = Modifier.basicMarquee(),
                )
            }
        }
    }
}
