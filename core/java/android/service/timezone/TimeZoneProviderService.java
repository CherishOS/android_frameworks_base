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

package android.service.timezone;

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.os.BackgroundThread;

import java.util.Objects;

/**
 * A service to generate time zone callbacks to the platform. Developers must extend this class.
 *
 * <p>Provider implementations are started via a call to {@link #onStartUpdates(long)} and stopped
 * via a call to {@link #onStopUpdates()}.
 *
 * <p>Once started, providers are expected to detect the time zone if possible, and report the
 * result via {@link #reportSuggestion(TimeZoneProviderSuggestion)} or {@link
 * #reportUncertain()}. Providers may also report that they have permanently failed
 * by calling {@link #reportPermanentFailure(Throwable)}. See the javadocs for each
 * method for details.
 *
 * <p>After starting, providers are expected to issue their first callback within the timeout
 * duration specified in {@link #onStartUpdates(long)}, or they will be implicitly considered to be
 * uncertain.
 *
 * <p>Once stopped or failed, providers are required to stop generating callbacks.
 *
 * <p>Provider discovery:
 *
 * <p>You must declare the service in your manifest file with the
 * {@link android.Manifest.permission#BIND_TIME_ZONE_PROVIDER_SERVICE} permission,
 * and include an intent filter with the necessary action indicating what type of provider it is.
 *
 * <p>Device configuration can influence how {@link TimeZoneProviderService}s are discovered.
 * In one mode, there can be multiple {@link TimeZoneProviderService}s configured with the same
 * action, and the one with the highest "serviceVersion" metadata will be used.
 *
 * <p>{@link TimeZoneProviderService}s may be deployed into processes that run once-per-user
 * or once-per-device (i.e. they service multiple users). The "serviceIsMultiuser" metadata must
 * be set accordingly.
 *
 * <p>Provider types:
 *
 * <p>Android supports up to two <em>location-derived</em> time zone providers. These are called the
 * "primary" and "secondary" location time zone provider. The primary location time zone provider is
 * started first and will be used until it becomes uncertain or fails, at which point the secondary
 * provider will be started.
 *
 * <p>Location-derived time zone providers are configured using {@link
 * #PRIMARY_LOCATION_TIME_ZONE_PROVIDER_SERVICE_INTERFACE} and {@link
 * #SECONDARY_LOCATION_TIME_ZONE_PROVIDER_SERVICE_INTERFACE} intent-filter actions respectively.
 * Besides declaring the android:permission attribute mentioned above, the application supplying a
 * location provider must be granted the {@link
 * android.Manifest.permission#INSTALL_LOCATION_TIME_ZONE_PROVIDER_SERVICE} permission to be
 * accepted by the system server.
 *
 * <p>For example:
 * <pre>
 *   &lt;uses-permission
 *       android:name="android.permission.INSTALL_LOCATION_TIME_ZONE_PROVIDER_SERVICE"/&gt;
 *
 * ...
 *
 *     &lt;service android:name=".FooTimeZoneProviderService"
 *             android:exported="true"
 *             android:permission="android.permission.BIND_TIME_ZONE_PROVIDER_SERVICE"&gt;
 *         &lt;intent-filter&gt;
 *             &lt;action
 *             android:name="android.service.timezone.SecondaryLocationTimeZoneProviderService"
 *             /&gt;
 *         &lt;/intent-filter&gt;
 *         &lt;meta-data android:name="serviceVersion" android:value="1" /&gt;
 *         &lt;meta-data android:name="serviceIsMultiuser" android:value="true" /&gt;
 *     &lt;/service&gt;
 * </pre>
 *
 * <p>Threading:
 *
 * <p>Calls to {@code report} methods can be made on on any thread and will be passed asynchronously
 * to the system server. Calls to {@link #onStartUpdates(long)} and {@link #onStopUpdates()} will
 * occur on a single thread.
 *
 * @hide
 */
@SystemApi
public abstract class TimeZoneProviderService extends Service {

    private static final String TAG = "TimeZoneProviderService";

    /**
     * The test command result key indicating whether a command succeeded. Value type: boolean
     * @hide
     */
    public static final String TEST_COMMAND_RESULT_SUCCESS_KEY = "SUCCESS";

    /**
     * The test command result key for the error message present when {@link
     * #TEST_COMMAND_RESULT_SUCCESS_KEY} is false. Value type: string
     * @hide
     */
    public static final String TEST_COMMAND_RESULT_ERROR_KEY = "ERROR";

    /**
     * The Intent action that the primary location-derived time zone provider service must respond
     * to. Add it to the intent filter of the service in its manifest.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String PRIMARY_LOCATION_TIME_ZONE_PROVIDER_SERVICE_INTERFACE =
            "android.service.timezone.PrimaryLocationTimeZoneProviderService";

    /**
     * The Intent action that the secondary location-based time zone provider service must respond
     * to. Add it to the intent filter of the service in its manifest.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SECONDARY_LOCATION_TIME_ZONE_PROVIDER_SERVICE_INTERFACE =
            "android.service.timezone.SecondaryLocationTimeZoneProviderService";

    private final TimeZoneProviderServiceWrapper mWrapper = new TimeZoneProviderServiceWrapper();

    private final Handler mHandler = BackgroundThread.getHandler();

    /** Set by {@link #mHandler} thread. */
    @Nullable
    private ITimeZoneProviderManager mManager;

    @Override
    @NonNull
    public final IBinder onBind(@NonNull Intent intent) {
        return mWrapper;
    }

    /**
     * Indicates a successful time zone detection. See {@link TimeZoneProviderSuggestion} for
     * details.
     */
    public final void reportSuggestion(@NonNull TimeZoneProviderSuggestion suggestion) {
        Objects.requireNonNull(suggestion);

        mHandler.post(() -> {
            ITimeZoneProviderManager manager = mManager;
            if (manager != null) {
                try {
                    manager.onTimeZoneProviderSuggestion(suggestion);
                } catch (RemoteException | RuntimeException e) {
                    Log.w(TAG, e);
                }
            }
        });
    }

    /**
     * Indicates the time zone is not known because of an expected runtime state or error, e.g. when
     * the provider is unable to detect location, or there was a problem when resolving the location
     * to a time zone.
     */
    public final void reportUncertain() {
        mHandler.post(() -> {
            ITimeZoneProviderManager manager = mManager;
            if (manager != null) {
                try {
                    manager.onTimeZoneProviderUncertain();
                } catch (RemoteException | RuntimeException e) {
                    Log.w(TAG, e);
                }
            }
        });
    }

    /**
     * Indicates there was a permanent failure. This is not generally expected, and probably means a
     * required backend service has been turned down, or the client is unreasonably old.
     */
    public final void reportPermanentFailure(@NonNull Throwable cause) {
        Objects.requireNonNull(cause);

        mHandler.post(() -> {
            ITimeZoneProviderManager manager = mManager;
            if (manager != null) {
                try {
                    manager.onTimeZoneProviderPermanentFailure(cause.getMessage());
                } catch (RemoteException | RuntimeException e) {
                    Log.w(TAG, e);
                }
            }
        });
    }

    private void onStartUpdatesInternal(@NonNull ITimeZoneProviderManager manager,
            @DurationMillisLong long initializationTimeoutMillis) {
        mManager = manager;
        onStartUpdates(initializationTimeoutMillis);
    }

    /**
     * Starts the provider sending updates.
     */
    public abstract void onStartUpdates(@DurationMillisLong long initializationTimeoutMillis);

    private void onStopUpdatesInternal() {
        onStopUpdates();
        mManager = null;
    }

    /**
     * Stops the provider sending updates.
     */
    public abstract void onStopUpdates();

    private class TimeZoneProviderServiceWrapper extends ITimeZoneProvider.Stub {

        public void startUpdates(@NonNull ITimeZoneProviderManager manager,
                @DurationMillisLong long initializationTimeoutMillis) {
            Objects.requireNonNull(manager);
            mHandler.post(() -> onStartUpdatesInternal(manager, initializationTimeoutMillis));
        }

        public void stopUpdates() {
            mHandler.post(TimeZoneProviderService.this::onStopUpdatesInternal);
        }
    }
}
