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
package com.android.wm.shell.draganddrop

import android.os.RemoteException
import android.util.Log
import android.view.DragEvent
import android.view.IWindowManager
import android.window.IUnhandledDragCallback
import android.window.IUnhandledDragListener
import androidx.annotation.VisibleForTesting
import com.android.internal.protolog.common.ProtoLog
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.protolog.ShellProtoLogGroup
import java.util.function.Consumer

/**
 * Manages the listener and callbacks for unhandled global drags.
 */
class UnhandledDragController(
    val wmService: IWindowManager,
    mainExecutor: ShellExecutor
) {
    private var callback: UnhandledDragAndDropCallback? = null

    private val unhandledDragListener: IUnhandledDragListener =
        object : IUnhandledDragListener.Stub() {
            override fun onUnhandledDrop(event: DragEvent, callback: IUnhandledDragCallback) {
                mainExecutor.execute() {
                    this@UnhandledDragController.onUnhandledDrop(event, callback)
                }
            }
        }

    /**
     * Listener called when an unhandled drag is started.
     */
    interface UnhandledDragAndDropCallback {
        /**
         * Called when a global drag is unhandled (ie. dropped outside of all visible windows, or
         * dropped on a window that does not want to handle it).
         *
         * The implementer _must_ call onFinishedCallback, and if it consumes the drop, then it is
         * also responsible for releasing up the drag surface provided via the drag event.
         */
        fun onUnhandledDrop(dragEvent: DragEvent, onFinishedCallback: Consumer<Boolean>) {}
    }

    /**
     * Sets a listener for callbacks when an unhandled drag happens.
     */
    fun setListener(listener: UnhandledDragAndDropCallback?) {
        val updateWm = (callback == null && listener != null)
                || (callback != null && listener == null)
        callback = listener
        if (updateWm) {
            try {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP,
                    "%s unhandled drag listener",
                    if (callback != null) "Registering" else "Unregistering")
                wmService.setUnhandledDragListener(
                    if (callback != null) unhandledDragListener else null)
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to set unhandled drag listener")
            }
        }
    }

    @VisibleForTesting
    fun onUnhandledDrop(dragEvent: DragEvent, wmCallback: IUnhandledDragCallback) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP,
            "onUnhandledDrop: %s", dragEvent)
        if (callback == null) {
            wmCallback.notifyUnhandledDropComplete(false)
        }

        callback?.onUnhandledDrop(dragEvent) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP,
                "Notifying onUnhandledDrop complete: %b", it)
            wmCallback.notifyUnhandledDropComplete(it)
        }
    }

    companion object {
        private val TAG = UnhandledDragController::class.java.simpleName
    }
}
