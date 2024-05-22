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

package com.android.systemui.qs.panels.shared.model

/**
 * Grid type for a QS grid layout.
 *
 * Used to inject grid layouts with Dagger and the [GridLayoutTypeKey] annotation.
 */
interface GridLayoutType

/** Grid type representing a scrollable vertical grid. */
data object InfiniteGridLayoutType : GridLayoutType

/**
 * Grid type representing a scrollable vertical grid where tiles will stretch to fill in empty
 * spaces.
 */
data object StretchedGridLayoutType : GridLayoutType

/** Grid type grouping large tiles on top and icon tiles at the bottom. */
data object PartitionedGridLayoutType : GridLayoutType
