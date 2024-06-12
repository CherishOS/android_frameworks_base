/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.compose.animation.scene

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ApproachLayoutModifierNode
import androidx.compose.ui.layout.ApproachMeasureScope
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachReversed
import com.android.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope

/** The type for the content of movable elements. */
internal typealias MovableElementContent = @Composable (@Composable () -> Unit) -> Unit

@Stable
internal class SceneTransitionLayoutImpl(
    internal val state: BaseSceneTransitionLayoutState,
    internal var density: Density,
    internal var swipeSourceDetector: SwipeSourceDetector,
    internal var transitionInterceptionThreshold: Float,
    builder: SceneTransitionLayoutScope.() -> Unit,
    internal val coroutineScope: CoroutineScope,
) {
    /**
     * The map of [Scene]s.
     *
     * TODO(b/317014852): Make this a normal MutableMap instead.
     */
    internal val scenes = SnapshotStateMap<SceneKey, Scene>()

    /**
     * The map of [Element]s.
     *
     * Important: [Element]s from this map should never be accessed during composition because the
     * Elements are added when the associated Modifier.element() node is attached to the Modifier
     * tree, i.e. after composition.
     */
    internal val elements = mutableMapOf<ElementKey, Element>()

    /**
     * The map of contents of movable elements.
     *
     * Note that given that this map is mutated directly during a composition, it has to be a
     * [SnapshotStateMap] to make sure that mutations are reverted if composition is cancelled.
     */
    private var _movableContents: SnapshotStateMap<ElementKey, MovableElementContent>? = null
    val movableContents: SnapshotStateMap<ElementKey, MovableElementContent>
        get() =
            _movableContents
                ?: SnapshotStateMap<ElementKey, MovableElementContent>().also {
                    _movableContents = it
                }

    /**
     * The different values of a shared value keyed by a a [ValueKey] and the different elements and
     * scenes it is associated to.
     */
    private var _sharedValues: MutableMap<ValueKey, MutableMap<ElementKey?, SharedValue<*, *>>>? =
        null
    internal val sharedValues: MutableMap<ValueKey, MutableMap<ElementKey?, SharedValue<*, *>>>
        get() =
            _sharedValues
                ?: mutableMapOf<ValueKey, MutableMap<ElementKey?, SharedValue<*, *>>>().also {
                    _sharedValues = it
                }

    // TODO(b/317958526): Lazily allocate scene gesture handlers the first time they are needed.
    private val horizontalDraggableHandler: DraggableHandlerImpl
    private val verticalDraggableHandler: DraggableHandlerImpl

    internal val elementStateScope = ElementStateScopeImpl(this)
    private var _userActionDistanceScope: UserActionDistanceScope? = null
    internal val userActionDistanceScope: UserActionDistanceScope
        get() =
            _userActionDistanceScope
                ?: UserActionDistanceScopeImpl(layoutImpl = this).also {
                    _userActionDistanceScope = it
                }

    /**
     * The [LookaheadScope] of this layout, that can be used to compute offsets relative to the
     * layout.
     */
    internal lateinit var lookaheadScope: LookaheadScope
        private set

    init {
        updateScenes(builder)

        // DraggableHandlerImpl must wait for the scenes to be initialized, in order to access the
        // current scene (required for SwipeTransition).
        horizontalDraggableHandler =
            DraggableHandlerImpl(
                layoutImpl = this,
                orientation = Orientation.Horizontal,
                coroutineScope = coroutineScope,
            )

        verticalDraggableHandler =
            DraggableHandlerImpl(
                layoutImpl = this,
                orientation = Orientation.Vertical,
                coroutineScope = coroutineScope,
            )

        // Make sure that the state is created on the same thread (most probably the main thread)
        // than this STLImpl.
        state.checkThread()
    }

    internal fun draggableHandler(orientation: Orientation): DraggableHandlerImpl =
        when (orientation) {
            Orientation.Vertical -> verticalDraggableHandler
            Orientation.Horizontal -> horizontalDraggableHandler
        }

    internal fun scene(key: SceneKey): Scene {
        return scenes[key] ?: error("Scene $key is not configured")
    }

    internal fun updateScenes(builder: SceneTransitionLayoutScope.() -> Unit) {
        // Keep a reference of the current scenes. After processing [builder], the scenes that were
        // not configured will be removed.
        val scenesToRemove = scenes.keys.toMutableSet()

        // The incrementing zIndex of each scene.
        var zIndex = 0f

        object : SceneTransitionLayoutScope {
                override fun scene(
                    key: SceneKey,
                    userActions: Map<UserAction, UserActionResult>,
                    content: @Composable SceneScope.() -> Unit,
                ) {
                    scenesToRemove.remove(key)

                    val scene = scenes[key]
                    if (scene != null) {
                        // Update an existing scene.
                        scene.content = content
                        scene.userActions = userActions
                        scene.zIndex = zIndex
                    } else {
                        // New scene.
                        scenes[key] =
                            Scene(
                                key,
                                this@SceneTransitionLayoutImpl,
                                content,
                                userActions,
                                zIndex,
                            )
                    }

                    zIndex++
                }
            }
            .builder()

        scenesToRemove.forEach { scenes.remove(it) }
    }

    @Composable
    internal fun Content(modifier: Modifier, swipeDetector: SwipeDetector) {
        Box(
            modifier
                // Handle horizontal and vertical swipes on this layout.
                // Note: order here is important and will give a slight priority to the vertical
                // swipes.
                .swipeToScene(horizontalDraggableHandler, swipeDetector)
                .swipeToScene(verticalDraggableHandler, swipeDetector)
                .then(LayoutElement(layoutImpl = this))
        ) {
            LookaheadScope {
                lookaheadScope = this

                BackHandler()

                scenesToCompose().fastForEach { scene -> key(scene.key) { scene.Content() } }
            }
        }
    }

    @Composable
    private fun BackHandler() {
        val targetSceneForBackOrNull =
            scene(state.transitionState.currentScene).userActions[Back]?.toScene
        BackHandler(enabled = targetSceneForBackOrNull != null) {
            targetSceneForBackOrNull?.let { targetSceneForBack ->
                // TODO(b/290184746): Handle predictive back and use result.distance if specified.
                if (state.canChangeScene(targetSceneForBack)) {
                    with(state) { coroutineScope.onChangeScene(targetSceneForBack) }
                }
            }
        }
    }

    private fun scenesToCompose(): List<Scene> {
        val transitions = state.currentTransitions
        return if (transitions.isEmpty()) {
            listOf(scene(state.transitionState.currentScene))
        } else {
            buildList {
                val visited = mutableSetOf<SceneKey>()
                fun maybeAdd(sceneKey: SceneKey) {
                    if (visited.add(sceneKey)) {
                        add(scene(sceneKey))
                    }
                }

                // Compose the new scene we are going to first.
                transitions.fastForEachReversed { transition ->
                    maybeAdd(transition.toScene)
                    maybeAdd(transition.fromScene)
                }
            }
        }
    }

    internal fun setScenesTargetSizeForTest(size: IntSize) {
        scenes.values.forEach { it.targetSize = size }
    }
}

private data class LayoutElement(private val layoutImpl: SceneTransitionLayoutImpl) :
    ModifierNodeElement<LayoutNode>() {
    override fun create(): LayoutNode = LayoutNode(layoutImpl)

    override fun update(node: LayoutNode) {
        node.layoutImpl = layoutImpl
    }
}

private class LayoutNode(var layoutImpl: SceneTransitionLayoutImpl) :
    Modifier.Node(), ApproachLayoutModifierNode {
    override fun isMeasurementApproachInProgress(lookaheadSize: IntSize): Boolean {
        return layoutImpl.state.isTransitioning()
    }

    @ExperimentalComposeUiApi
    override fun ApproachMeasureScope.approachMeasure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        // Measure content normally.
        val placeable = measurable.measure(constraints)

        val width: Int
        val height: Int
        val transition = layoutImpl.state.currentTransition
        if (transition == null) {
            width = placeable.width
            height = placeable.height
        } else {
            // Interpolate the size.
            val fromSize = layoutImpl.scene(transition.fromScene).targetSize
            val toSize = layoutImpl.scene(transition.toScene).targetSize

            // Optimization: make sure we don't read state.progress if fromSize ==
            // toSize to avoid running this code every frame when the layout size does
            // not change.
            if (fromSize == toSize) {
                width = fromSize.width
                height = fromSize.height
            } else {
                val overscrollSpec = transition.currentOverscrollSpec
                val progress =
                    when {
                        overscrollSpec == null -> transition.progress
                        overscrollSpec.scene == transition.toScene -> 1f
                        else -> 0f
                    }

                val size = lerp(fromSize, toSize, progress)
                width = size.width.coerceAtLeast(0)
                height = size.height.coerceAtLeast(0)
            }
        }

        return layout(width, height) { placeable.place(0, 0) }
    }
}
