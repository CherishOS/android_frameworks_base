package com.android.systemui.screenshot

import android.net.Uri
import android.os.Trace
import android.util.Log
import android.view.Display
import android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE
import com.android.internal.logging.UiEventLogger
import com.android.internal.util.ScreenshotRequest
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.screenshot.TakeScreenshotService.RequestCallback
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Receives the signal to take a screenshot from [TakeScreenshotService], and calls back with the
 * result.
 *
 * Captures a screenshot for each [Display] available.
 */
@SysUISingleton
class TakeScreenshotExecutor
@Inject
constructor(
    private val screenshotControllerFactory: ScreenshotController.Factory,
    displayRepository: DisplayRepository,
    @Application private val mainScope: CoroutineScope,
    private val screenshotRequestProcessor: ScreenshotRequestProcessor,
    private val uiEventLogger: UiEventLogger
) {

    private lateinit var displays: StateFlow<Set<Display>>
    private val displaysCollectionJob: Job =
        mainScope.launch {
            displays = displayRepository.displays.stateIn(this, SharingStarted.Eagerly, emptySet())
        }

    private val screenshotControllers = mutableMapOf<Int, ScreenshotController>()

    /**
     * Executes the [ScreenshotRequest].
     *
     * [onSaved] is invoked only on the default display result. [RequestCallback.onFinish] is
     * invoked only when both screenshot UIs are removed.
     */
    suspend fun executeScreenshots(
        screenshotRequest: ScreenshotRequest,
        onSaved: (Uri) -> Unit,
        requestCallback: RequestCallback
    ) {
        val displayIds = getDisplaysToScreenshot(screenshotRequest.type)
        val resultCallbackWrapper = MultiResultCallbackWrapper(requestCallback)
        screenshotRequest.oneForEachDisplay(displayIds).forEach { screenshotData: ScreenshotData ->
            dispatchToController(
                screenshotData = screenshotData,
                onSaved =
                    if (screenshotData.displayId == Display.DEFAULT_DISPLAY) onSaved else { _ -> },
                callback = resultCallbackWrapper.createCallbackForId(screenshotData.displayId)
            )
        }
    }

    /** Creates a [ScreenshotData] for each display. */
    private suspend fun ScreenshotRequest.oneForEachDisplay(
        displayIds: List<Int>
    ): List<ScreenshotData> {
        return displayIds
            .map { displayId -> ScreenshotData.fromRequest(this, displayId) }
            .map { screenshotData: ScreenshotData ->
                screenshotRequestProcessor.process(screenshotData)
            }
    }

    private fun dispatchToController(
        screenshotData: ScreenshotData,
        onSaved: (Uri) -> Unit,
        callback: RequestCallback
    ) {
        uiEventLogger.log(
            ScreenshotEvent.getScreenshotSource(screenshotData.source),
            0,
            screenshotData.packageNameString
        )
        Log.d(TAG, "Screenshot request: $screenshotData")
        getScreenshotController(screenshotData.displayId)
            .handleScreenshot(screenshotData, onSaved, callback)
    }

    private fun getDisplaysToScreenshot(requestType: Int): List<Int> {
        return if (requestType == TAKE_SCREENSHOT_PROVIDED_IMAGE) {
            // If this is a provided image, let's show the UI on the default display only.
            listOf(Display.DEFAULT_DISPLAY)
        } else {
            displays.value.filter { it.type in ALLOWED_DISPLAY_TYPES }.map { it.displayId }
        }
    }

    /**
     * Propagates the close system dialog signal to all controllers.
     *
     * TODO(b/295143676): Move the receiver in this class once the flag is flipped.
     */
    fun onCloseSystemDialogsReceived() {
        screenshotControllers.forEach { (_, screenshotController) ->
            if (!screenshotController.isPendingSharedTransition) {
                screenshotController.dismissScreenshot(ScreenshotEvent.SCREENSHOT_DISMISSED_OTHER)
            }
        }
    }

    /** Removes all screenshot related windows. */
    fun removeWindows() {
        screenshotControllers.forEach { (_, screenshotController) ->
            screenshotController.removeWindow()
        }
    }

    /**
     * Destroys the executor. Afterwards, this class is not expected to work as intended anymore.
     */
    fun onDestroy() {
        screenshotControllers.forEach { (_, screenshotController) ->
            screenshotController.onDestroy()
        }
        screenshotControllers.clear()
        displaysCollectionJob.cancel()
    }

    private fun getScreenshotController(id: Int): ScreenshotController {
        return screenshotControllers.computeIfAbsent(id) {
            screenshotControllerFactory.create(id, /* showUIOnExternalDisplay= */ false)
        }
    }

    /** For java compatibility only. see [executeScreenshots] */
    fun executeScreenshotsAsync(
        screenshotRequest: ScreenshotRequest,
        onSaved: Consumer<Uri>,
        requestCallback: RequestCallback
    ) {
        mainScope.launch {
            executeScreenshots(screenshotRequest, { uri -> onSaved.accept(uri) }, requestCallback)
        }
    }

    /**
     * Returns a [RequestCallback] that wraps [originalCallback].
     *
     * Each [RequestCallback] created with [createCallbackForId] is expected to be used with either
     * [reportError] or [onFinish]. Once they are both called:
     * - If any finished with an error, [reportError] of [originalCallback] is called
     * - Otherwise, [onFinish] is called.
     */
    private class MultiResultCallbackWrapper(
        private val originalCallback: RequestCallback,
    ) {
        private val idsPending = mutableSetOf<Int>()
        private val idsWithErrors = mutableSetOf<Int>()

        /**
         * Creates a callback for [id].
         *
         * [originalCallback]'s [onFinish] will be called only when this (and the other created)
         * callback's [onFinish] have been called.
         */
        fun createCallbackForId(id: Int): RequestCallback {
            Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_APP, TAG, "Waiting for id=$id", id)
            idsPending += id
            return object : RequestCallback {
                override fun reportError() {
                    endTrace("reportError id=$id")
                    idsWithErrors += id
                    idsPending -= id
                    reportToOriginalIfNeeded()
                }

                override fun onFinish() {
                    endTrace("onFinish id=$id")
                    idsPending -= id
                    reportToOriginalIfNeeded()
                }

                private fun endTrace(reason: String) {
                    Log.d(TAG, "Finished waiting for id=$id. $reason")
                    Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, TAG, id)
                    Trace.instantForTrack(Trace.TRACE_TAG_APP, TAG, reason)
                }
            }
        }

        private fun reportToOriginalIfNeeded() {
            if (idsPending.isNotEmpty()) return
            if (idsWithErrors.isEmpty()) {
                originalCallback.onFinish()
            } else {
                originalCallback.reportError()
            }
        }
    }

    private companion object {
        val TAG = LogConfig.logTag(TakeScreenshotService::class.java)

        val ALLOWED_DISPLAY_TYPES =
            listOf(
                Display.TYPE_EXTERNAL,
                Display.TYPE_INTERNAL,
                Display.TYPE_OVERLAY,
                Display.TYPE_WIFI
            )
    }
}
