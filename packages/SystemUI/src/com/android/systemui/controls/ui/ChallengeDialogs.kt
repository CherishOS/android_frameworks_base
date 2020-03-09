/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.CommandAction
import android.service.controls.actions.ControlAction
import android.service.controls.actions.FloatAction
import android.service.controls.actions.ModeAction
import android.text.InputType
import android.util.Log
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText

import com.android.systemui.R

/**
 * Creates all dialogs for challengeValues that can occur from a call to
 * {@link ControlsProviderService#performControlAction}. The types of challenge
 * responses are listed in {@link ControlAction.ResponseResult}.
 */
object ChallengeDialogs {

    fun createPinDialog(cvh: ControlViewHolder): Dialog? {
        val lastAction = cvh.lastAction
        if (lastAction == null) {
            Log.e(ControlsUiController.TAG,
                "PIN Dialog attempted but no last action is set. Will not show")
            return null
        }
        val builder = AlertDialog.Builder(
            cvh.context,
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        ).apply {
            setTitle(R.string.controls_pin_verify)
            setView(R.layout.controls_dialog_pin)
            setPositiveButton(
                android.R.string.ok,
                DialogInterface.OnClickListener { dialog, _ ->
                    if (dialog is Dialog) {
                        dialog.requireViewById<EditText>(R.id.controls_pin_input)
                        val pin = dialog.requireViewById<EditText>(R.id.controls_pin_input)
                            .getText().toString()
                        cvh.action(addChallengeValue(lastAction, pin))
                        dialog.dismiss()
                    }
            })
            setNegativeButton(
                android.R.string.cancel,
                DialogInterface.OnClickListener { dialog, _ -> dialog.cancel() }
            )
        }
        return builder.create().apply {
            getWindow().apply {
                setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            }
            setOnShowListener(DialogInterface.OnShowListener { _ ->
                val editText = requireViewById<EditText>(R.id.controls_pin_input)
                requireViewById<CheckBox>(R.id.controls_pin_use_alpha).setOnClickListener { v ->
                    if ((v as CheckBox).isChecked) {
                        editText.setInputType(
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                    } else {
                        editText.setInputType(
                            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                    }
                }
                editText.requestFocus()
            })
        }
    }

    private fun addChallengeValue(action: ControlAction, challengeValue: String): ControlAction {
        val id = action.getTemplateId()
        return when (action) {
            is BooleanAction -> BooleanAction(id, action.getNewState(), challengeValue)
            is FloatAction -> FloatAction(id, action.getNewValue(), challengeValue)
            is CommandAction -> CommandAction(id, challengeValue)
            is ModeAction -> ModeAction(id, action.getNewMode(), challengeValue)
            else -> throw IllegalStateException("'action' is not a known type: $action")
        }
    }
}
