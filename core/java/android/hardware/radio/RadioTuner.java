/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.hardware.radio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.graphics.Bitmap;
import android.os.Handler;

import java.util.List;

/**
 * RadioTuner interface provides methods to control a radio tuner on the device: selecting and
 * configuring the active band, muting/unmuting, scanning and tuning, etc...
 *
 * Obtain a RadioTuner interface by calling {@link RadioManager#openTuner(int,
 * RadioManager.BandConfig, boolean, RadioTuner.Callback, Handler)}.
 * @hide
 */
@SystemApi
public abstract class RadioTuner {

    /** Scanning direction UP for {@link #step(int, boolean)}, {@link #scan(int, boolean)} */
    public static final int DIRECTION_UP      = 0;

    /** Scanning directions DOWN for {@link #step(int, boolean)}, {@link #scan(int, boolean)} */
    public static final int DIRECTION_DOWN    = 1;

    /**
     * Close the tuner interface. The {@link Callback} callback will not be called
     * anymore and associated resources will be released.
     * Must be called when the tuner is not needed to make hardware resources available to others.
     * */
    public abstract void close();

    /**
     * Set the active band configuration for this module.
     * Must be a valid configuration obtained via buildConfig() from a valid BandDescriptor listed
     * in the ModuleProperties of the module with the specified ID.
     * @param config The desired band configuration (FmBandConfig or AmBandConfig).
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int setConfiguration(RadioManager.BandConfig config);

    /**
     * Get current configuration.
     * @param config a BandConfig array of lengh 1 where the configuration is returned.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int getConfiguration(RadioManager.BandConfig[] config);


    /**
     * Set mute state. When muted, the radio tuner audio source is not available for playback on
     * any audio device. when unmuted, the radio tuner audio source is output as a media source
     * and renderd over the audio device selected for media use case.
     * The radio tuner audio source is muted by default when the tuner is first attached.
     * Only effective if the tuner is attached with audio enabled.
     *
     * @param mute the requested mute state.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int setMute(boolean mute);

    /**
     * Get mute state.
     *
     * @return {@code true} if the radio tuner audio source is muted or a problem occured
     * retrieving the mute state, {@code false} otherwise.
     */
    public abstract boolean getMute();

    /**
     * Step up or down by one channel spacing.
     * The operation is asynchronous and {@link Callback}
     * onProgramInfoChanged() will be called when step completes or
     * onError() when cancelled or timeout.
     * @param direction {@link #DIRECTION_UP} or {@link #DIRECTION_DOWN}.
     * @param skipSubChannel indicates to skip sub channels when the configuration currently
     * selected supports sub channel (e.g HD Radio). N/A otherwise.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int step(int direction, boolean skipSubChannel);

    /**
     * Scan up or down to next valid station.
     * The operation is asynchronous and {@link Callback}
     * onProgramInfoChanged() will be called when scan completes or
     * onError() when cancelled or timeout.
     * @param direction {@link #DIRECTION_UP} or {@link #DIRECTION_DOWN}.
     * @param skipSubChannel indicates to skip sub channels when the configuration currently
     * selected supports sub channel (e.g HD Radio). N/A otherwise.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int scan(int direction, boolean skipSubChannel);

    /**
     * Tune to a specific frequency.
     * The operation is asynchronous and {@link Callback}
     * onProgramInfoChanged() will be called when tune completes or
     * onError() when cancelled or timeout.
     * @param channel the specific channel or frequency to tune to.
     * @param subChannel the specific sub-channel to tune to. N/A if the selected configuration
     * does not support cub channels.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     * @deprecated Use {@link tune(ProgramSelector)} instead.
     */
    @Deprecated
    public abstract int tune(int channel, int subChannel);

    /**
     * Tune to a program.
     *
     * The operation is asynchronous and {@link Callback} onProgramInfoChanged() will be called
     * when tune completes or onError() when cancelled or on timeout.
     *
     * @thows IllegalArgumentException if the provided selector is invalid
     */
    public abstract void tune(@NonNull ProgramSelector selector);

    /**
     * Cancel a pending scan or tune operation.
     * If an operation is pending, {@link Callback} onError() will be called with
     * {@link #ERROR_CANCELLED}.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int cancel();

    /**
     * Cancels traffic or emergency announcement.
     *
     * If there was no announcement to cancel, no action is taken.
     *
     * There is a race condition between calling cancelAnnouncement and the actual announcement
     * being finished, so onTrafficAnnouncement / onEmergencyAnnouncement callback should be
     * tracked with proper locking.
     */
    public abstract void cancelAnnouncement();

    /**
     * Get current station information.
     * @param info a ProgramInfo array of lengh 1 where the information is returned.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int getProgramInformation(RadioManager.ProgramInfo[] info);

    /**
     * Retrieves a {@link Bitmap} for the given image ID or null,
     * if the image was missing from the tuner.
     *
     * This involves doing a call to the tuner, so the bitmap should be cached
     * on the application side.
     *
     * If the method returns null for non-zero ID, it means the image was
     * updated on the tuner side. There is a race conditon between fetching
     * image for an old ID and tuner updating the image (and cleaning up the
     * old image). In such case, a new ProgramInfo with updated image id will
     * be sent with a {@link onProgramInfoChanged} callback.
     *
     * @param id The image identifier, retrieved with
     *           {@link RadioMetadata#getBitmapId(String)}.
     * @return A {@link Bitmap} or null.
     * @throws IllegalArgumentException if id==0
     * @hide This API is not thoroughly elaborated yet
     */
    public abstract @Nullable Bitmap getMetadataImage(int id);

    /**
     * Initiates a background scan to update internally cached program list.
     *
     * It may not be necessary to initiate the scan explicitly - the scan MAY be performed on boot.
     *
     * The operation is asynchronous and {@link Callback} backgroundScanComplete or onError will
     * be called if the return value of this call was {@code true}. As result of this call
     * programListChanged may be triggered (if the scanned list differs).
     *
     * @return {@code true} if the scan was properly scheduled, {@code false} if the scan feature
     * is unavailable; ie. temporarily due to ongoing foreground playback in single-tuner device
     * or permanently if the feature is not supported
     * (see ModuleProperties#isBackgroundScanningSupported()).
     */
    public abstract boolean startBackgroundScan();

    /**
     * Get the list of discovered radio stations.
     *
     * To get the full list, set filter to null or empty string. Otherwise, client application
     * must verify vendor product/name before setting this parameter to anything else.
     *
     * @param filter vendor-specific selector for radio stations.
     * @return a list of radio stations.
     * @throws IllegalStateException if the scan is in progress or has not been started,
     *         startBackgroundScan() call may fix it.
     * @throws IllegalArgumentException if the filter argument is not valid.
     */
    public abstract @NonNull List<RadioManager.ProgramInfo> getProgramList(@Nullable String filter);

    /**
     * Checks, if the analog playback is forced, see setAnalogForced.
     *
     * @throws IllegalStateException if the switch is not supported at current
     *         configuration.
     * @return {@code true} if analog is forced, {@code false} otherwise.
     */
    public abstract boolean isAnalogForced();

    /**
     * Forces the analog playback for the supporting radio technology.
     *
     * User may disable digital playback for FM HD Radio or hybrid FM/DAB with
     * this option. This is purely user choice, ie. does not reflect digital-
     * analog handover managed from the HAL implementation side.
     *
     * Some radio technologies may not support this, ie. DAB.
     *
     * @param isForced {@code true} to force analog, {@code false} for a default behaviour.
     * @throws IllegalStateException if the switch is not supported at current
     *         configuration.
     */
    public abstract void setAnalogForced(boolean isForced);

    /**
     * Get current antenna connection state for current configuration.
     * Only valid if a configuration has been applied.
     * @return {@code true} if the antenna is connected, {@code false} otherwise.
     */
    public abstract boolean isAntennaConnected();

    /**
     * Indicates if this client actually controls the tuner.
     * Control is always granted after
     * {@link RadioManager#openTuner(int,
     * RadioManager.BandConfig, boolean, Callback, Handler)}
     * returns a non null tuner interface.
     * Control is lost when another client opens an interface on the same tuner.
     * When this happens, {@link Callback#onControlChanged(boolean)} is received.
     * The client can either wait for control to be returned (which is indicated by the same
     * callback) or close and reopen the tuner interface.
     * @return {@code true} if this interface controls the tuner,
     * {@code false} otherwise or if a problem occured retrieving the state.
     */
    public abstract boolean hasControl();

    /** Indicates a failure of radio IC or driver.
     * The application must close and re open the tuner */
    public static final int ERROR_HARDWARE_FAILURE = 0;
    /** Indicates a failure of the radio service.
     * The application must close and re open the tuner */
    public static final  int ERROR_SERVER_DIED = 1;
    /** A pending seek or tune operation was cancelled */
    public static final  int ERROR_CANCELLED = 2;
    /** A pending seek or tune operation timed out */
    public static final  int ERROR_SCAN_TIMEOUT = 3;
    /** The requested configuration could not be applied */
    public static final  int ERROR_CONFIG = 4;
    /** Background scan was interrupted due to hardware becoming temporarily unavailable. */
    public static final int ERROR_BACKGROUND_SCAN_UNAVAILABLE = 5;
    /** Background scan failed due to other error, ie. HW failure. */
    public static final int ERROR_BACKGROUND_SCAN_FAILED = 6;

    /**
     * Callback provided by the client application when opening a {@link RadioTuner}
     * to receive asynchronous operation results, updates and error notifications.
     */
    public static abstract class Callback {
        /**
         * onError() is called when an error occured while performing an asynchronous
         * operation of when the hardware or system service experiences a problem.
         * status is one of {@link #ERROR_HARDWARE_FAILURE}, {@link #ERROR_SERVER_DIED},
         * {@link #ERROR_CANCELLED}, {@link #ERROR_SCAN_TIMEOUT},
         * {@link #ERROR_CONFIG}
         */
        public void onError(int status) {}
        /**
         * onConfigurationChanged() is called upon successful completion of
         * {@link RadioManager#openTuner(int, RadioManager.BandConfig, boolean, Callback, Handler)}
         * or {@link RadioTuner#setConfiguration(RadioManager.BandConfig)}
         */
        public void onConfigurationChanged(RadioManager.BandConfig config) {}

        /**
         * Called when program info (including metadata) for the current program has changed.
         *
         * It happens either upon successful completion of {@link RadioTuner#step(int, boolean)},
         * {@link RadioTuner#scan(int, boolean)}, {@link RadioTuner#tune(int, int)}; when
         * a switching to alternate frequency occurs; or when metadata is updated.
         */
        public void onProgramInfoChanged(RadioManager.ProgramInfo info) {}

        /**
         * Called when metadata is updated for the current program.
         *
         * @deprecated Use {@link #onProgramInfoChanged(RadioManager.ProgramInfo)} instead.
         */
        @Deprecated
        public void onMetadataChanged(RadioMetadata metadata) {}

        /**
         * onTrafficAnnouncement() is called when a traffic announcement starts and stops.
         */
        public void onTrafficAnnouncement(boolean active) {}
        /**
         * onEmergencyAnnouncement() is called when an emergency annoucement starts and stops.
         */
        public void onEmergencyAnnouncement(boolean active) {}
        /**
         * onAntennaState() is called when the antenna is connected or disconnected.
         */
        public void onAntennaState(boolean connected) {}
        /**
         * onControlChanged() is called when the client loses or gains control of the radio tuner.
         * The control is always granted after a successful call to
         * {@link RadioManager#openTuner(int, RadioManager.BandConfig, boolean, Callback, Handler)}.
         * If another client opens the same tuner, onControlChanged() will be called with
         * control set to {@code false} to indicate loss of control.
         * At this point, RadioTuner APIs other than getters will return
         * {@link RadioManager#STATUS_INVALID_OPERATION}.
         * When the other client releases the tuner, onControlChanged() will be called
         * with control set to {@code true}.
         */
        public void onControlChanged(boolean control) {}

        /**
         * onBackgroundScanAvailabilityChange() is called when background scan
         * feature becomes available or not.
         *
         * @param isAvailable true, if the tuner turned temporarily background-
         *                    capable, false in the other case.
         */
        public void onBackgroundScanAvailabilityChange(boolean isAvailable) {}

        /**
         * Called when a background scan completes successfully.
         */
        public void onBackgroundScanComplete() {}

        /**
         * Called when available program list changed.
         *
         * Use {@link RadioTuner#getProgramList(String)} to get an actual list.
         */
        public void onProgramListChanged() {}
    }

}

