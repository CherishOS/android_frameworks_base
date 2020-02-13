/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner;

import android.annotation.BytesLong;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.media.tv.TvInputService;
import android.media.tv.tuner.TunerConstants.Result;
import android.media.tv.tuner.dvr.DvrPlayback;
import android.media.tv.tuner.dvr.DvrRecorder;
import android.media.tv.tuner.dvr.OnPlaybackStatusChangedListener;
import android.media.tv.tuner.dvr.OnRecordStatusChangedListener;
import android.media.tv.tuner.filter.Filter;
import android.media.tv.tuner.filter.Filter.Subtype;
import android.media.tv.tuner.filter.Filter.Type;
import android.media.tv.tuner.filter.FilterCallback;
import android.media.tv.tuner.filter.TimeFilter;
import android.media.tv.tuner.frontend.FrontendInfo;
import android.media.tv.tuner.frontend.FrontendSettings;
import android.media.tv.tuner.frontend.FrontendStatus;
import android.media.tv.tuner.frontend.OnTuneEventListener;
import android.media.tv.tuner.frontend.ScanCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * This class is used to interact with hardware tuners devices.
 *
 * <p> Each TvInputService Session should create one instance of this class.
 *
 * <p> This class controls the TIS interaction with Tuner HAL.
 *
 * @hide
 */
@SystemApi
public class Tuner implements AutoCloseable  {
    private static final String TAG = "MediaTvTuner";
    private static final boolean DEBUG = false;

    private static final int MSG_ON_FILTER_EVENT = 2;
    private static final int MSG_ON_FILTER_STATUS = 3;
    private static final int MSG_ON_LNB_EVENT = 4;

    static {
        System.loadLibrary("media_tv_tuner");
        nativeInit();
    }

    private final Context mContext;

    private List<Integer> mFrontendIds;
    private Frontend mFrontend;
    private EventHandler mHandler;
    @Nullable
    private FrontendInfo mFrontendInfo;

    private List<Integer> mLnbIds;
    private Lnb mLnb;
    @Nullable
    private OnTuneEventListener mOnTuneEventListener;
    @Nullable
    private Executor mOnTunerEventExecutor;
    @Nullable
    private ScanCallback mScanCallback;
    @Nullable
    private Executor mScanCallbackExecutor;

    /**
     * Constructs a Tuner instance.
     *
     * @param context the context of the caller.
     * @param tvInputSessionId the session ID of the TV input.
     * @param useCase the use case of this Tuner instance.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public Tuner(@NonNull Context context, @NonNull String tvInputSessionId,
            @TvInputService.PriorityHintUseCaseType int useCase,
            @Nullable OnResourceLostListener listener) {
        nativeSetup();
        mContext = context;
    }

    /**
     * Shares the frontend resource with another Tuner instance
     *
     * @param tuner the Tuner instance to share frontend resource with.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public void shareFrontendFromTuner(@NonNull Tuner tuner) {
        // TODO: implementation.
    }

    /**
     * Updates client priority with an arbitrary value along with a nice value.
     *
     * <p>Tuner resource manager (TRM) uses the client priority value to decide whether it is able
     * to reclaim insufficient resources from another client.
     * <p>The nice value represents how much the client intends to give up the resource when an
     * insufficient resource situation happens.
     *
     * @param priority the new priority.
     * @param niceValue the nice value.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public void updateResourcePriority(int priority, int niceValue) {
        // TODO: implementation.
    }

    private long mNativeContext; // used by native jMediaTuner

    /**
     * Releases the Tuner instance.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Override
    public void close() {
        // TODO: implementation.
    }

    /**
     * Native Initialization.
     */
    private static native void nativeInit();

    /**
     * Native setup.
     */
    private native void nativeSetup();

    /**
     * Native method to get all frontend IDs.
     */
    private native List<Integer> nativeGetFrontendIds();

    /**
     * Native method to open frontend of the given ID.
     */
    private native Frontend nativeOpenFrontendById(int id);
    private native int nativeTune(int type, FrontendSettings settings);
    private native int nativeStopTune();
    private native int nativeScan(int settingsType, FrontendSettings settings, int scanType);
    private native int nativeStopScan();
    private native int nativeSetLnb(int lnbId);
    private native int nativeSetLna(boolean enable);
    private native FrontendStatus nativeGetFrontendStatus(int[] statusTypes);
    private native int nativeGetAvSyncHwId(Filter filter);
    private native long nativeGetAvSyncTime(int avSyncId);
    private native int nativeConnectCiCam(int ciCamId);
    private native int nativeDisconnectCiCam();
    private native FrontendInfo nativeGetFrontendInfo(int id);
    private native Filter nativeOpenFilter(int type, int subType, long bufferSize);
    private native TimeFilter nativeOpenTimeFilter();

    private native List<Integer> nativeGetLnbIds();
    private native Lnb nativeOpenLnbById(int id);

    private native Descrambler nativeOpenDescrambler();

    private native DvrRecorder nativeOpenDvrRecorder(long bufferSize);
    private native DvrPlayback nativeOpenDvrPlayback(long bufferSize);

    private static native DemuxCapabilities nativeGetDemuxCapabilities();


    /**
     * Listener for resource lost.
     *
     * <p>Insufficient resources are reclaimed by higher priority clients.
     */
    public interface OnResourceLostListener {
        /**
         * Invoked when resource lost.
         *
         * @param tuner the tuner instance whose resource is being reclaimed.
         */
        void onResourceLost(@NonNull Tuner tuner);
    }

    @Nullable
    private EventHandler createEventHandler() {
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            return new EventHandler(looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            return new EventHandler(looper);
        }
        return null;
    }

    private class EventHandler extends Handler {
        private EventHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_FILTER_STATUS: {
                    Filter filter = (Filter) msg.obj;
                    if (filter.getCallback() != null) {
                        filter.getCallback().onFilterStatusChanged(filter, msg.arg1);
                    }
                    break;
                }
                default:
                    // fall through
            }
        }
    }

    private class Frontend {
        private int mId;

        private Frontend(int id) {
            mId = id;
        }
    }

    /**
     * Listens for tune events.
     *
     * <p>
     * Tuner events are started when {@link #tune(FrontendSettings)} is called and end when {@link
     * #cancelTuning()} is called.
     *
     * @param eventListener receives tune events.
     * @throws SecurityException if the caller does not have appropriate permissions.
     * @see #tune(FrontendSettings)
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public void setOnTuneEventListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull OnTuneEventListener eventListener) {
        TunerUtils.checkTunerPermission(mContext);
        mOnTuneEventListener = eventListener;
        mOnTunerEventExecutor = executor;
    }

    /**
     * Clears the {@link OnTuneEventListener} and its associated {@link Executor}.
     *
     * @throws SecurityException if the caller does not have appropriate permissions.
     * @see #setOnTuneEventListener(Executor, OnTuneEventListener)
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public void clearOnTuneEventListener() {
        TunerUtils.checkTunerPermission(mContext);
        mOnTuneEventListener = null;
        mOnTunerEventExecutor = null;

    }

    /**
     * Tunes the frontend to using the settings given.
     *
     * <p>
     * This locks the frontend to a frequency by providing signal
     * delivery information. If previous tuning isn't completed, this stop the previous tuning, and
     * start a new tuning.
     *
     * <p>
     * Tune is an async call, with {@link OnTuneEventListener#SIGNAL_LOCKED} and {@link
     * OnTuneEventListener#SIGNAL_NO_SIGNAL} events sent to the {@link OnTuneEventListener}
     * specified in {@link #setOnTuneEventListener(Executor, OnTuneEventListener)}.
     *
     * @param settings Signal delivery information the frontend uses to
     *                 search and lock the signal.
     * @return result status of tune operation.
     * @throws SecurityException if the caller does not have appropriate permissions.
     * @see #setOnTuneEventListener(Executor, OnTuneEventListener)
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Result
    public int tune(@NonNull FrontendSettings settings) {
        TunerUtils.checkTunerPermission(mContext);
        mFrontendInfo = null;
        return nativeTune(settings.getType(), settings);
    }

    /**
     * Stops a previous tuning.
     *
     * <p>If the method completes successfully, the frontend is no longer tuned and no data
     * will be sent to attached filters.
     *
     * @return result status of the operation.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Result
    public int cancelTuning() {
        TunerUtils.checkTunerPermission(mContext);
        return nativeStopTune();
    }

    /**
     * Scan for channels.
     *
     * <p>Details for channels found are returned via {@link ScanCallback}.
     *
     * @param settings A {@link FrontendSettings} to configure the frontend.
     * @param scanType The scan type.
     * @throws SecurityException     if the caller does not have appropriate permissions.
     * @throws IllegalStateException if {@code scan} is called again before
     *                               {@link #cancelScanning()} is called.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Result
    public int scan(@NonNull FrontendSettings settings, @TunerConstants.ScanType int scanType,
            @NonNull @CallbackExecutor Executor executor, @NonNull ScanCallback scanCallback) {
        TunerUtils.checkTunerPermission(mContext);
        if (mScanCallback != null || mScanCallbackExecutor != null) {
            throw new IllegalStateException(
                    "Scan already in progress.  stopScan must be called before a new scan can be "
                            + "started.");
        }
        mScanCallback = scanCallback;
        mScanCallbackExecutor = executor;
        mFrontendInfo = null;
        return nativeScan(settings.getType(), settings, scanType);
    }

    /**
     * Stops a previous scanning.
     *
     * <p>
     * The {@link ScanCallback} and it's {@link Executor} will be removed.
     *
     * <p>
     * If the method completes successfully, the frontend stopped previous scanning.
     *
     * @throws SecurityException if the caller does not have appropriate permissions.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Result
    public int cancelScanning() {
        TunerUtils.checkTunerPermission(mContext);
        int retVal = nativeStopScan();
        mScanCallback = null;
        mScanCallbackExecutor = null;
        return retVal;
    }

    /**
     * Sets Low-Noise Block downconverter (LNB) for satellite frontend.
     *
     * <p>This assigns a hardware LNB resource to the satellite tuner. It can be
     * called multiple times to update LNB assignment.
     *
     * @param lnb the LNB instance.
     *
     * @return result status of the operation.
     */
    @Result
    private int setLnb(@NonNull Lnb lnb) {
        return nativeSetLnb(lnb.mId);
    }

    /**
     * Enable or Disable Low Noise Amplifier (LNA).
     *
     * @param enable {@code true} to activate LNA module; {@code false} to deactivate LNA.
     *
     * @return result status of the operation.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Result
    public int setLna(boolean enable) {
        TunerUtils.checkTunerPermission(mContext);
        return nativeSetLna(enable);
    }

    /**
     * Gets the statuses of the frontend.
     *
     * <p>This retrieve the statuses of the frontend for given status types.
     *
     * @param statusTypes an array of status types which the caller requests.
     * @return statuses which response the caller's requests.
     */
    @Nullable
    public FrontendStatus getFrontendStatus(@NonNull int[] statusTypes) {
        return nativeGetFrontendStatus(statusTypes);
    }

    /**
     * Gets hardware sync ID for audio and video.
     *
     * @param filter the filter instance for the hardware sync ID.
     * @return the id of hardware A/V sync.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public int getAvSyncHwId(@NonNull Filter filter) {
        TunerUtils.checkTunerPermission(mContext);
        return nativeGetAvSyncHwId(filter);
    }

    /**
     * Gets the current timestamp for Audio/Video sync
     *
     * <p>The timestamp is maintained by hardware. The timestamp based on 90KHz, and it's format is
     * the same as PTS (Presentation Time Stamp).
     *
     * @param avSyncHwId the hardware id of A/V sync.
     * @return the current timestamp of hardware A/V sync.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public long getAvSyncTime(int avSyncHwId) {
        TunerUtils.checkTunerPermission(mContext);
        return nativeGetAvSyncTime(avSyncHwId);
    }

    /**
     * Connects Conditional Access Modules (CAM) through Common Interface (CI)
     *
     * <p>The demux uses the output from the frontend as the input by default, and must change to
     * use the output from CI-CAM as the input after this call.
     *
     * @param ciCamId specify CI-CAM Id to connect.
     * @return result status of the operation.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Result
    public int connectCiCam(int ciCamId) {
        TunerUtils.checkTunerPermission(mContext);
        return nativeConnectCiCam(ciCamId);
    }

    /**
     * Disconnects Conditional Access Modules (CAM)
     *
     * <p>The demux will use the output from the frontend as the input after this call.
     *
     * @return result status of the operation.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Result
    public int disconnectCiCam() {
        TunerUtils.checkTunerPermission(mContext);
        return nativeDisconnectCiCam();
    }

    /**
     * Gets the frontend information.
     *
     * @return The frontend information. {@code null} if the operation failed.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Nullable
    public FrontendInfo getFrontendInfo() {
        TunerUtils.checkTunerPermission(mContext);
        if (mFrontend == null) {
            throw new IllegalStateException("frontend is not initialized");
        }
        if (mFrontendInfo == null) {
            mFrontendInfo = nativeGetFrontendInfo(mFrontend.mId);
        }
        return mFrontendInfo;
    }

    /**
     * Gets Demux capabilities.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Nullable
    public static DemuxCapabilities getDemuxCapabilities(@NonNull Context context) {
        TunerUtils.checkTunerPermission(context);
        return nativeGetDemuxCapabilities();
    }

    private List<Integer> getFrontendIds() {
        mFrontendIds = nativeGetFrontendIds();
        return mFrontendIds;
    }

    private Frontend openFrontendById(int id) {
        if (mFrontendIds == null) {
            mFrontendIds = getFrontendIds();
        }
        if (!mFrontendIds.contains(id)) {
            return null;
        }
        mFrontend = nativeOpenFrontendById(id);
        return mFrontend;
    }

    private void onFrontendEvent(int eventType) {
        if (mOnTunerEventExecutor != null && mOnTuneEventListener != null) {
            mOnTunerEventExecutor.execute(() -> mOnTuneEventListener.onTuneEvent(eventType));
        }
    }

    /**
     * Opens a filter object based on the given types and buffer size.
     *
     * @param mainType the main type of the filter.
     * @param subType the subtype of the filter.
     * @param bufferSize the buffer size of the filter to be opened in bytes. The buffer holds the
     * data output from the filter.
     * @param executor the executor on which callback will be invoked. The default event handler
     * executor is used if it's {@code null}.
     * @param cb the callback to receive notifications from filter.
     * @return the opened filter. {@code null} if the operation failed.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Nullable
    public Filter openFilter(@Type int mainType, @Subtype int subType,
            @BytesLong long bufferSize, @CallbackExecutor @Nullable Executor executor,
            @Nullable FilterCallback cb) {
        TunerUtils.checkTunerPermission(mContext);
        Filter filter = nativeOpenFilter(
                mainType, TunerUtils.getFilterSubtype(mainType, subType), bufferSize);
        if (filter != null) {
            filter.setCallback(cb);
            if (mHandler == null) {
                mHandler = createEventHandler();
            }
        }
        return filter;
    }

    /**
     * Opens an LNB (low-noise block downconverter) object.
     *
     * @param executor the executor on which callback will be invoked. The default event handler
     * executor is used if it's {@code null}.
     * @param cb the callback to receive notifications from LNB.
     * @return the opened LNB object. {@code null} if the operation failed.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Nullable
    public Lnb openLnb(@CallbackExecutor @Nullable Executor executor, @Nullable LnbCallback cb) {
        TunerUtils.checkTunerPermission(mContext);
        return openLnbByName(null, executor, cb);
    }

    /**
     * Opens an LNB (low-noise block downconverter) object.
     *
     * @param name the LNB name.
     * @param executor the executor on which callback will be invoked. The default event handler
     * executor is used if it's {@code null}.
     * @param cb the callback to receive notifications from LNB.
     * @return the opened LNB object. {@code null} if the operation failed.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Nullable
    public Lnb openLnbByName(@Nullable String name, @CallbackExecutor @Nullable Executor executor,
            @NonNull LnbCallback cb) {
        TunerUtils.checkTunerPermission(mContext);
        // TODO: use resource manager to get LNB ID.
        return new Lnb(0);
    }

    /**
     * Open a time filter object.
     *
     * @return the opened time filter object. {@code null} if the operation failed.
     */
    @Nullable
    public TimeFilter openTimeFilter() {
        return nativeOpenTimeFilter();
    }

    private List<Integer> getLnbIds() {
        mLnbIds = nativeGetLnbIds();
        return mLnbIds;
    }

    private Lnb openLnbById(int id) {
        if (mLnbIds == null) {
            mLnbIds = getLnbIds();
        }
        if (!mLnbIds.contains(id)) {
            return null;
        }
        mLnb = nativeOpenLnbById(id);
        return mLnb;
    }

    private void onLnbEvent(int eventType) {
        if (mHandler != null) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_ON_LNB_EVENT, eventType, 0));
        }
    }

    /**
     * Opens a Descrambler in tuner.
     *
     * @return  a {@link Descrambler} object.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_DESCRAMBLER)
    @Nullable
    public Descrambler openDescrambler() {
        TunerUtils.checkDescramblerPermission(mContext);
        return nativeOpenDescrambler();
    }

    /**
     * Open a DVR (Digital Video Record) recorder instance.
     *
     * @param bufferSize the buffer size of the output in bytes. It's used to hold output data of
     * the attached filters.
     * @param executor the executor on which callback will be invoked. The default event handler
     * executor is used if it's {@code null}.
     * @param l the listener to receive notifications from DVR recorder.
     * @return the opened DVR recorder object. {@code null} if the operation failed.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Nullable
    public DvrRecorder openDvrRecorder(
            @BytesLong long bufferSize,
            @CallbackExecutor @Nullable Executor executor,
            @Nullable OnRecordStatusChangedListener l) {
        TunerUtils.checkTunerPermission(mContext);
        DvrRecorder dvr = nativeOpenDvrRecorder(bufferSize);
        return dvr;
    }

    /**
     * Open a DVR (Digital Video Record) playback instance.
     *
     * @param bufferSize the buffer size of the output in bytes. It's used to hold output data of
     * the attached filters.
     * @param executor the executor on which callback will be invoked. The default event handler
     * executor is used if it's {@code null}.
     * @param l the listener to receive notifications from DVR recorder.
     * @return the opened DVR playback object. {@code null} if the operation failed.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Nullable
    public DvrPlayback openDvrPlayback(
            @BytesLong long bufferSize,
            @CallbackExecutor @Nullable Executor executor,
            @Nullable OnPlaybackStatusChangedListener l) {
        TunerUtils.checkTunerPermission(mContext);
        DvrPlayback dvr = nativeOpenDvrPlayback(bufferSize);
        return dvr;
    }
}
