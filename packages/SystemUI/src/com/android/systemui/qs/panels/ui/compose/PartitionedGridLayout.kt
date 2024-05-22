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

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.modifiers.background
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.domain.interactor.IconTilesInteractor
import com.android.systemui.qs.panels.domain.interactor.InfiniteGridSizeInteractor
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import javax.inject.Inject

@SysUISingleton
class PartitionedGridLayout
@Inject
constructor(
    private val iconTilesInteractor: IconTilesInteractor,
    private val gridSizeInteractor: InfiniteGridSizeInteractor,
) : GridLayout {
    @Composable
    override fun TileGrid(tiles: List<TileViewModel>, modifier: Modifier) {
        DisposableEffect(tiles) {
            val token = Any()
            tiles.forEach { it.startListening(token) }
            onDispose { tiles.forEach { it.stopListening(token) } }
        }
        val iconTilesSpecs by iconTilesInteractor.iconTilesSpecs.collectAsStateWithLifecycle()
        val columns by gridSizeInteractor.columns.collectAsStateWithLifecycle()
        val tileHeight = dimensionResource(id = R.dimen.qs_tile_height)
        val (smallTiles, largeTiles) = tiles.partition { iconTilesSpecs.contains(it.spec) }

        TileLazyGrid(modifier = modifier, columns = GridCells.Fixed(columns)) {
            // Large tiles
            items(largeTiles.size, span = { GridItemSpan(2) }) { index ->
                Tile(
                    tile = largeTiles[index],
                    iconOnly = false,
                    modifier = Modifier.height(tileHeight)
                )
            }
            fillUpRow(nTiles = largeTiles.size, columns = columns / 2)

            // Small tiles
            items(smallTiles.size) { index ->
                Tile(
                    tile = smallTiles[index],
                    iconOnly = true,
                    modifier = Modifier.height(tileHeight)
                )
            }
        }
    }

    @Composable
    override fun EditTileGrid(
        tiles: List<EditTileViewModel>,
        modifier: Modifier,
        onAddTile: (TileSpec, Int) -> Unit,
        onRemoveTile: (TileSpec) -> Unit
    ) {
        val iconOnlySpecs by iconTilesInteractor.iconTilesSpecs.collectAsStateWithLifecycle()
        val columns by gridSizeInteractor.columns.collectAsStateWithLifecycle()

        val (currentTiles, otherTiles) = tiles.partition { it.isCurrent }
        val addTileToEnd: (TileSpec) -> Unit by rememberUpdatedState {
            onAddTile(it, CurrentTilesInteractor.POSITION_AT_END)
        }
        val isIconOnly: (TileSpec) -> Boolean =
            remember(iconOnlySpecs) { { tileSpec: TileSpec -> tileSpec in iconOnlySpecs } }
        val tileHeight = dimensionResource(id = R.dimen.qs_tile_height)
        val tilePadding = dimensionResource(R.dimen.qs_tile_margin_vertical)

        Column(
            verticalArrangement = Arrangement.spacedBy(tilePadding),
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            CurrentTiles(
                tiles = currentTiles,
                tileHeight = tileHeight,
                tilePadding = tilePadding,
                onRemoveTile = onRemoveTile,
                isIconOnly = isIconOnly,
                columns = columns,
            )
            AvailableTiles(
                tiles = otherTiles,
                tileHeight = tileHeight,
                tilePadding = tilePadding,
                addTileToEnd = addTileToEnd,
                isIconOnly = isIconOnly,
                columns = columns,
            )
        }
    }

    @Composable
    private fun CurrentTiles(
        tiles: List<EditTileViewModel>,
        tileHeight: Dp,
        tilePadding: Dp,
        onRemoveTile: (TileSpec) -> Unit,
        isIconOnly: (TileSpec) -> Boolean,
        columns: Int,
    ) {
        val (smallTiles, largeTiles) = tiles.partition { isIconOnly(it.tileSpec) }

        val largeGridHeight = gridHeight(largeTiles.size, tileHeight, columns / 2, tilePadding)
        val smallGridHeight = gridHeight(smallTiles.size, tileHeight, columns, tilePadding)

        CurrentTilesContainer {
            TileLazyGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.height(largeGridHeight),
            ) {
                editTiles(largeTiles, ClickAction.REMOVE, onRemoveTile, { false }, true)
            }
        }
        CurrentTilesContainer {
            TileLazyGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.height(smallGridHeight),
            ) {
                editTiles(smallTiles, ClickAction.REMOVE, onRemoveTile, { true }, true)
            }
        }
    }

    @Composable
    private fun AvailableTiles(
        tiles: List<EditTileViewModel>,
        tileHeight: Dp,
        tilePadding: Dp,
        addTileToEnd: (TileSpec) -> Unit,
        isIconOnly: (TileSpec) -> Boolean,
        columns: Int,
    ) {
        val (tilesStock, tilesCustom) = tiles.partition { it.appName == null }
        val (smallTiles, largeTiles) = tilesStock.partition { isIconOnly(it.tileSpec) }

        val largeGridHeight = gridHeight(largeTiles.size, tileHeight, columns / 2, tilePadding)
        val smallGridHeight = gridHeight(smallTiles.size, tileHeight, columns, tilePadding)
        val largeGridHeightCustom =
            gridHeight(tilesCustom.size, tileHeight, columns / 2, tilePadding)

        // Add up the height of all three grids and add padding in between
        val gridHeight =
            largeGridHeight + smallGridHeight + largeGridHeightCustom + (tilePadding * 2)

        AvailableTilesContainer {
            TileLazyGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.height(gridHeight),
            ) {
                // Large tiles
                editTiles(largeTiles, ClickAction.ADD, addTileToEnd, isIconOnly)
                fillUpRow(nTiles = largeTiles.size, columns = columns / 2)

                // Small tiles
                editTiles(smallTiles, ClickAction.ADD, addTileToEnd, isIconOnly)
                fillUpRow(nTiles = smallTiles.size, columns = columns)

                // Custom tiles, all large
                editTiles(tilesCustom, ClickAction.ADD, addTileToEnd, isIconOnly)
            }
        }
    }

    @Composable
    private fun CurrentTilesContainer(content: @Composable () -> Unit) {
        Box(
            Modifier.fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onBackground,
                    shape = RoundedCornerShape(dimensionResource(R.dimen.qs_corner_radius))
                )
                .padding(dimensionResource(R.dimen.qs_tile_margin_vertical))
        ) {
            content()
        }
    }

    @Composable
    private fun AvailableTilesContainer(content: @Composable () -> Unit) {
        Box(
            Modifier.fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    alpha = { 1f },
                    shape = RoundedCornerShape(dimensionResource(R.dimen.qs_corner_radius))
                )
                .padding(dimensionResource(R.dimen.qs_tile_margin_vertical))
        ) {
            content()
        }
    }

    private fun gridHeight(nTiles: Int, tileHeight: Dp, columns: Int, padding: Dp): Dp {
        val rows = (nTiles + columns - 1) / columns
        return ((tileHeight + padding) * rows) - padding
    }

    /** Fill up the rest of the row if it's not complete. */
    private fun LazyGridScope.fillUpRow(nTiles: Int, columns: Int) {
        if (nTiles % columns != 0) {
            item(span = { GridItemSpan(maxCurrentLineSpan) }) { Spacer(Modifier) }
        }
    }
}
