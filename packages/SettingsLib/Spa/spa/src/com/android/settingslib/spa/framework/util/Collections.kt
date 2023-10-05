/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.framework.util

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Performs the given [action] on each element asynchronously.
 */
suspend inline fun <T> Iterable<T>.asyncForEach(crossinline action: (T) -> Unit) {
    coroutineScope {
        forEach {
            launch { action(it) }
        }
    }
}

/**
 * Returns a list containing the results of asynchronously applying the given [transform] function
 * to each element in the original collection.
 */
suspend inline fun <R, T> Iterable<T>.asyncMap(crossinline transform: (T) -> R): List<R> =
    coroutineScope {
        map { item ->
            async { transform(item) }
        }.awaitAll()
    }

/**
 * Returns a list containing only elements matching the given [predicate].
 *
 * The filter operation is done asynchronously.
 */
suspend inline fun <T> Iterable<T>.asyncFilter(crossinline predicate: (T) -> Boolean): List<T> =
    asyncMap { item -> item to predicate(item) }
        .filter { it.second }
        .map { it.first }
