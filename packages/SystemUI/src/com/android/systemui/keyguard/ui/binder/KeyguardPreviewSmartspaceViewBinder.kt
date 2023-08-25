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

package com.android.systemui.keyguard.ui.binder

import android.view.View
import androidx.core.view.isInvisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewSmartspaceViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.launch

/** Binder for the small clock view, large clock view and smartspace. */
object KeyguardPreviewSmartspaceViewBinder {

    @JvmStatic
    fun bind(
        smartspace: View,
        viewModel: KeyguardPreviewSmartspaceViewModel,
    ) {
        smartspace.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.smartspaceTopPadding.collect { smartspace.setTopPadding(it) } }

                launch { viewModel.shouldHideSmartspace.collect { smartspace.isInvisible = it } }
            }
        }
    }

    private fun View.setTopPadding(padding: Int) {
        setPaddingRelative(paddingStart, padding, paddingEnd, paddingBottom)
    }
}
