/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.media.taptotransfer

import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executor

@SmallTest
class MediaTttChipControllerTest : SysuiTestCase() {

    private lateinit var mediaTttChipController: MediaTttChipController

    private val inlineExecutor = Executor { command -> command.run() }
    private val commandRegistry = CommandRegistry(context, inlineExecutor)
    private val pw = PrintWriter(StringWriter())

    @Mock
    private lateinit var windowManager: WindowManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mediaTttChipController = MediaTttChipController(commandRegistry, context, windowManager)
    }

    @Test(expected = IllegalStateException::class)
    fun constructor_addCommmandAlreadyRegistered() {
        // Since creating the chip controller should automatically register the add command, it
        // should throw when registering it again.
        commandRegistry.registerCommand(
            MediaTttChipController.ADD_CHIP_COMMAND_TAG
        ) { EmptyCommand() }
    }

    @Test(expected = IllegalStateException::class)
    fun constructor_removeCommmandAlreadyRegistered() {
        // Since creating the chip controller should automatically register the remove command, it
        // should throw when registering it again.
        commandRegistry.registerCommand(
            MediaTttChipController.REMOVE_CHIP_COMMAND_TAG
        ) { EmptyCommand() }
    }

    @Test
    fun addChipCommand_chipAdded() {
        commandRegistry.onShellCommand(pw, getMoveCloserToTransferCommand())

        verify(windowManager).addView(any(), any())
    }

    @Test
    fun addChipCommand_twice_chipNotAddedTwice() {
        commandRegistry.onShellCommand(pw, getMoveCloserToTransferCommand())
        reset(windowManager)

        commandRegistry.onShellCommand(pw, getMoveCloserToTransferCommand())
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun removeChipCommand_chipRemoved() {
        // First, add the chip
        commandRegistry.onShellCommand(pw, getMoveCloserToTransferCommand())

        // Then, remove it
        commandRegistry.onShellCommand(pw, arrayOf(MediaTttChipController.REMOVE_CHIP_COMMAND_TAG))

        verify(windowManager).removeView(any())
    }

    @Test
    fun removeChipCommand_noAdd_viewNotRemoved() {
        commandRegistry.onShellCommand(pw, arrayOf(MediaTttChipController.REMOVE_CHIP_COMMAND_TAG))

        verify(windowManager, never()).removeView(any())
    }

    @Test
    fun moveCloserToTransfer_chipTextContainsDeviceName_noLoadingIcon_noUndo() {
        commandRegistry.onShellCommand(pw, getMoveCloserToTransferCommand())

        val chipView = getChipView()
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButtonVisibility()).isEqualTo(View.GONE)
    }

    @Test
    fun transferInitiated_chipTextContainsDeviceName_loadingIcon_noUndo() {
        commandRegistry.onShellCommand(pw, getTransferInitiatedCommand())

        val chipView = getChipView()
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
        assertThat(chipView.getUndoButtonVisibility()).isEqualTo(View.GONE)
    }

    @Test
    fun transferSucceeded_chipTextContainsDeviceName_noLoadingIcon_undo() {
        commandRegistry.onShellCommand(pw, getTransferSucceededCommand())

        val chipView = getChipView()
        assertThat(chipView.getChipText()).contains(DEVICE_NAME)
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButtonVisibility()).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromCloserToTransferToTransferInitiated_loadingIconAppears() {
        commandRegistry.onShellCommand(pw, getMoveCloserToTransferCommand())
        commandRegistry.onShellCommand(pw, getTransferInitiatedCommand())

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromTransferInitiatedToTransferSucceeded_loadingIconDisappears() {
        commandRegistry.onShellCommand(pw, getTransferInitiatedCommand())
        commandRegistry.onShellCommand(pw, getTransferSucceededCommand())

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.GONE)
    }

    @Test
    fun changeFromTransferInitiatedToTransferSucceeded_undoButtonAppears() {
        commandRegistry.onShellCommand(pw, getTransferInitiatedCommand())
        commandRegistry.onShellCommand(pw, getTransferSucceededCommand())

        assertThat(getChipView().getUndoButtonVisibility()).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromTransferSucceededToMoveCloser_undoButtonDisappears() {
        commandRegistry.onShellCommand(pw, getTransferSucceededCommand())
        commandRegistry.onShellCommand(pw, getMoveCloserToTransferCommand())

        assertThat(getChipView().getUndoButtonVisibility()).isEqualTo(View.GONE)
    }

    private fun getMoveCloserToTransferCommand(): Array<String> =
        arrayOf(
            MediaTttChipController.ADD_CHIP_COMMAND_TAG,
            DEVICE_NAME,
            MediaTttChipController.ChipType.MOVE_CLOSER_TO_TRANSFER.name
        )

    private fun getTransferInitiatedCommand(): Array<String> =
        arrayOf(
            MediaTttChipController.ADD_CHIP_COMMAND_TAG,
            DEVICE_NAME,
            MediaTttChipController.ChipType.TRANSFER_INITIATED.name
        )

    private fun getTransferSucceededCommand(): Array<String> =
        arrayOf(
            MediaTttChipController.ADD_CHIP_COMMAND_TAG,
            DEVICE_NAME,
            MediaTttChipController.ChipType.TRANSFER_SUCCEEDED.name
        )

    private fun LinearLayout.getChipText(): String =
        (this.requireViewById<TextView>(R.id.text)).text as String

    private fun LinearLayout.getLoadingIconVisibility(): Int =
        this.requireViewById<View>(R.id.loading).visibility

    private fun LinearLayout.getUndoButtonVisibility(): Int =
        this.requireViewById<View>(R.id.undo).visibility

    private fun getChipView(): LinearLayout {
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        return viewCaptor.value as LinearLayout
    }

    class EmptyCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
        }

        override fun help(pw: PrintWriter) {
        }
    }
}

private const val DEVICE_NAME = "My Tablet"
