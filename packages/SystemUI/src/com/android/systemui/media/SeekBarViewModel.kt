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

package com.android.systemui.media

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData

import com.android.systemui.util.concurrency.DelayableExecutor

private const val POSITION_UPDATE_INTERVAL_MILLIS = 100L

private fun PlaybackState.isInMotion(): Boolean {
    return this.state == PlaybackState.STATE_PLAYING ||
            this.state == PlaybackState.STATE_FAST_FORWARDING ||
            this.state == PlaybackState.STATE_REWINDING
}

/**
 * Gets the playback position while accounting for the time since the [PlaybackState] was last
 * retrieved.
 *
 * This method closely follows the implementation of
 * [MediaSessionRecord#getStateWithUpdatedPosition].
 */
private fun PlaybackState.computePosition(duration: Long): Long {
    var currentPosition = this.position
    if (this.isInMotion()) {
        val updateTime = this.getLastPositionUpdateTime()
        val currentTime = SystemClock.elapsedRealtime()
        if (updateTime > 0) {
            var position = (this.playbackSpeed * (currentTime - updateTime)).toLong() +
                    this.getPosition()
            if (duration >= 0 && position > duration) {
                position = duration.toLong()
            } else if (position < 0) {
                position = 0
            }
            currentPosition = position
        }
    }
    return currentPosition
}

/** ViewModel for seek bar in QS media player. */
class SeekBarViewModel(val bgExecutor: DelayableExecutor) {

    private var _data = Progress(false, false, null, null)
        set(value) {
            field = value
            _progress.postValue(value)
        }
    private val _progress = MutableLiveData<Progress>().apply {
        postValue(_data)
    }
    val progress: LiveData<Progress>
        get() = _progress
    private var controller: MediaController? = null
        set(value) {
            if (field?.sessionToken != value?.sessionToken) {
                field?.unregisterCallback(callback)
                value?.registerCallback(callback)
                field = value
            }
        }
    private var playbackState: PlaybackState? = null
    private var callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState) {
            playbackState = state
            if (shouldPollPlaybackPosition()) {
                checkPlaybackPosition()
            }
        }
    }

    /** Listening state (QS open or closed) is used to control polling of progress. */
    var listening = true
        set(value) {
            if (value) {
                checkPlaybackPosition()
            }
        }

    /**
     * Handle request to change the current position in the media track.
     * @param position Place to seek to in the track.
     */
    @WorkerThread
    fun onSeek(position: Long) {
        controller?.transportControls?.seekTo(position)
        // Invalidate the cached playbackState to avoid the thumb jumping back to the previous
        // position.
        playbackState = null
    }

    /**
     * Updates media information.
     * @param mediaController controller for media session
     */
    @WorkerThread
    fun updateController(mediaController: MediaController?) {
        controller = mediaController
        playbackState = controller?.playbackState
        val mediaMetadata = controller?.metadata
        val seekAvailable = ((playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO) != 0L
        val position = playbackState?.position?.toInt()
        val duration = mediaMetadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.toInt()
        val enabled = if (playbackState == null ||
                playbackState?.getState() == PlaybackState.STATE_NONE ||
                (duration != null && duration <= 0)) false else true
        _data = Progress(enabled, seekAvailable, position, duration)
        if (shouldPollPlaybackPosition()) {
            checkPlaybackPosition()
        }
    }

    /**
     * Puts the seek bar into a resumption state.
     *
     * This should be called when the media session behind the controller has been destroyed.
     */
    @AnyThread
    fun clearController() = bgExecutor.execute {
        controller = null
        playbackState = null
        _data = _data.copy(enabled = false)
    }

    /**
     * Call to clean up any resources.
     */
    @AnyThread
    fun onDestroy() {
        controller = null
        playbackState = null
    }

    @AnyThread
    private fun checkPlaybackPosition(): Runnable = bgExecutor.executeDelayed({
        val duration = _data.duration ?: -1
        val currentPosition = playbackState?.computePosition(duration.toLong())?.toInt()
        if (currentPosition != null && _data.elapsedTime != currentPosition) {
            _data = _data.copy(elapsedTime = currentPosition)
        }
        if (shouldPollPlaybackPosition()) {
            checkPlaybackPosition()
        }
    }, POSITION_UPDATE_INTERVAL_MILLIS)

    @WorkerThread
    private fun shouldPollPlaybackPosition(): Boolean {
        return listening && playbackState?.isInMotion() ?: false
    }

    /** Gets a listener to attach to the seek bar to handle seeking. */
    val seekBarListener: SeekBar.OnSeekBarChangeListener
        get() {
            return SeekBarChangeListener(this, bgExecutor)
        }

    /** Gets a listener to attach to the seek bar to disable touch intercepting. */
    val seekBarTouchListener: View.OnTouchListener
        get() {
            return SeekBarTouchListener()
        }

    private class SeekBarChangeListener(
        val viewModel: SeekBarViewModel,
        val bgExecutor: DelayableExecutor
    ) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                bgExecutor.execute {
                    viewModel.onSeek(progress.toLong())
                }
            }
        }
        override fun onStartTrackingTouch(bar: SeekBar) {
        }
        override fun onStopTrackingTouch(bar: SeekBar) {
            val pos = bar.progress.toLong()
            bgExecutor.execute {
                viewModel.onSeek(pos)
            }
        }
    }

    private class SeekBarTouchListener : View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            view.parent.requestDisallowInterceptTouchEvent(true)
            return view.onTouchEvent(event)
        }
    }

    /** State seen by seek bar UI. */
    data class Progress(
        val enabled: Boolean,
        val seekAvailable: Boolean,
        val elapsedTime: Int?,
        val duration: Int?
    )
}
