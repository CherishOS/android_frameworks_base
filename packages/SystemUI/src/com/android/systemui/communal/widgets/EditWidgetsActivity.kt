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

package com.android.systemui.communal.widgets

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.IWindowManager
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.ui.viewmodel.CommunalEditModeViewModel
import com.android.systemui.compose.ComposeFacade.setCommunalEditWidgetActivityContent
import javax.inject.Inject

/** An Activity for editing the widgets that appear in hub mode. */
class EditWidgetsActivity
@Inject
constructor(
    private val communalViewModel: CommunalEditModeViewModel,
    private val communalInteractor: CommunalInteractor,
    private var windowManagerService: IWindowManager? = null,
) : ComponentActivity() {
    companion object {
        private const val EXTRA_IS_PENDING_WIDGET_DRAG = "is_pending_widget_drag"
        private const val EXTRA_FILTER_STRATEGY = "filter_strategy"
        private const val FILTER_STRATEGY_GLANCEABLE_HUB = 1
        private const val TAG = "EditWidgetsActivity"
    }

    private val addWidgetActivityLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(StartActivityForResult()) { result ->
            when (result.resultCode) {
                RESULT_OK -> {
                    result.data?.let { intent ->
                        val isPendingWidgetDrag =
                            intent.getBooleanExtra(EXTRA_IS_PENDING_WIDGET_DRAG, false)
                        // Nothing to do when a widget is being dragged & dropped. The drop
                        // target in the communal grid will receive the widget to be added (if
                        // the user drops it over).
                        if (!isPendingWidgetDrag) {
                            intent
                                .getParcelableExtra(
                                    Intent.EXTRA_COMPONENT_NAME,
                                    ComponentName::class.java
                                )
                                ?.let { communalInteractor.addWidget(it, 0) }
                                ?: run { Log.w(TAG, "No AppWidgetProviderInfo found in result.") }
                        }
                    }
                        ?: run { Log.w(TAG, "No data in result.") }
                }
                else ->
                    Log.w(
                        TAG,
                        "Failed to receive result from widget picker, code=${result.resultCode}"
                    )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val windowInsetsController = window.decorView.windowInsetsController
        windowInsetsController?.hide(WindowInsets.Type.systemBars())
        window.setDecorFitsSystemWindows(false)

        setCommunalEditWidgetActivityContent(
            activity = this,
            viewModel = communalViewModel,
            onOpenWidgetPicker = {
                val localPackageManager: PackageManager = getPackageManager()
                val intent =
                    Intent(Intent.ACTION_MAIN).also { it.addCategory(Intent.CATEGORY_HOME) }
                localPackageManager
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo
                    ?.packageName
                    ?.let { packageName ->
                        try {
                            addWidgetActivityLauncher.launch(
                                Intent(Intent.ACTION_PICK).also {
                                    it.setPackage(packageName)
                                    it.putExtra(
                                        EXTRA_FILTER_STRATEGY,
                                        FILTER_STRATEGY_GLANCEABLE_HUB
                                    )
                                }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to launch widget picker activity", e)
                        }
                    }
                    ?: run { Log.e(TAG, "Couldn't resolve launcher package name") }
            },
            onEditDone = {
                try {
                    checkNotNull(windowManagerService).lockNow(/* options */ null)
                    finish()
                } catch (e: RemoteException) {
                    Log.e(TAG, "Couldn't lock the device as WindowManager is dead.")
                }
            }
        )
    }
}
