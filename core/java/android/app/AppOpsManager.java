/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.app;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.database.ContentObserver;
import android.media.AudioAttributes.AttributeUsage;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import android.util.SparseArray;
import com.android.internal.app.IAppOpsActiveCallback;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsNotedCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * API for interacting with "application operation" tracking.
 *
 * <p>This API is not generally intended for third party application developers; most
 * features are only available to system applications.
 */
@SystemService(Context.APP_OPS_SERVICE)
public class AppOpsManager {
    /**
     * <p>App ops allows callers to:</p>
     *
     * <ul>
     * <li> Note when operations are happening, and find out if they are allowed for the current
     * caller.</li>
     * <li> Disallow specific apps from doing specific operations.</li>
     * <li> Collect all of the current information about operations that have been executed or
     * are not being allowed.</li>
     * <li> Monitor for changes in whether an operation is allowed.</li>
     * </ul>
     *
     * <p>Each operation is identified by a single integer; these integers are a fixed set of
     * operations, enumerated by the OP_* constants.
     *
     * <p></p>When checking operations, the result is a "mode" integer indicating the current
     * setting for the operation under that caller: MODE_ALLOWED, MODE_IGNORED (don't execute
     * the operation but fake its behavior enough so that the caller doesn't crash),
     * MODE_ERRORED (throw a SecurityException back to the caller; the normal operation calls
     * will do this for you).
     */

    final Context mContext;

    @UnsupportedAppUsage
    final IAppOpsService mService;

    @GuardedBy("mModeWatchers")
    private final ArrayMap<OnOpChangedListener, IAppOpsCallback> mModeWatchers =
            new ArrayMap<>();

    @GuardedBy("mActiveWatchers")
    private final ArrayMap<OnOpActiveChangedListener, IAppOpsActiveCallback> mActiveWatchers =
            new ArrayMap<>();

    @GuardedBy("mNotedWatchers")
    private final ArrayMap<OnOpNotedListener, IAppOpsNotedCallback> mNotedWatchers =
            new ArrayMap<>();

    static IBinder sToken;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "HISTORICAL_MODE_" }, value = {
            HISTORICAL_MODE_DISABLED,
            HISTORICAL_MODE_ENABLED_ACTIVE,
            HISTORICAL_MODE_ENABLED_PASSIVE
    })
    public @interface HistoricalMode {}

    /**
     * Mode in which app op history is completely disabled.
     * @hide
     */
    @TestApi
    public static final int HISTORICAL_MODE_DISABLED = 0;

    /**
     * Mode in which app op history is enabled and app ops performed by apps would
     * be tracked. This is the mode in which the feature is completely enabled.
     * @hide
     */
    @TestApi
    public static final int HISTORICAL_MODE_ENABLED_ACTIVE = 1;

    /**
     * Mode in which app op history is enabled but app ops performed by apps would
     * not be tracked and the only way to add ops to the history is via explicit calls
     * to dedicated APIs. This mode is useful for testing to allow full control of
     * the historical content.
     * @hide
     */
    @TestApi
    public static final int HISTORICAL_MODE_ENABLED_PASSIVE = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "MODE_" }, value = {
            MODE_ALLOWED,
            MODE_IGNORED,
            MODE_ERRORED,
            MODE_DEFAULT,
            MODE_FOREGROUND
    })
    public @interface Mode {}

    /**
     * Result from {@link #checkOp}, {@link #noteOp}, {@link #startOp}: the given caller is
     * allowed to perform the given operation.
     */
    public static final int MODE_ALLOWED = 0;

    /**
     * Result from {@link #checkOp}, {@link #noteOp}, {@link #startOp}: the given caller is
     * not allowed to perform the given operation, and this attempt should
     * <em>silently fail</em> (it should not cause the app to crash).
     */
    public static final int MODE_IGNORED = 1;

    /**
     * Result from {@link #checkOpNoThrow}, {@link #noteOpNoThrow}, {@link #startOpNoThrow}: the
     * given caller is not allowed to perform the given operation, and this attempt should
     * cause it to have a fatal error, typically a {@link SecurityException}.
     */
    public static final int MODE_ERRORED = 2;

    /**
     * Result from {@link #checkOp}, {@link #noteOp}, {@link #startOp}: the given caller should
     * use its default security check.  This mode is not normally used; it should only be used
     * with appop permissions, and callers must explicitly check for it and deal with it.
     */
    public static final int MODE_DEFAULT = 3;

    /**
     * Special mode that means "allow only when app is in foreground."  This is <b>not</b>
     * returned from {@link #checkOp}, {@link #noteOp}, {@link #startOp}; rather, when this
     * mode is set, these functions will return {@link #MODE_ALLOWED} when the app being
     * checked is currently in the foreground, otherwise {@link #MODE_IGNORED}.
     */
    public static final int MODE_FOREGROUND = 4;

    /**
     * Flag for {@link #startWatchingMode(String, String, int, OnOpChangedListener)}:
     * Also get reports if the foreground state of an op's uid changes.  This only works
     * when watching a particular op, not when watching a package.
     */
    public static final int WATCH_FOREGROUND_CHANGES = 1 << 0;

    /**
     * @hide
     */
    public static final String[] MODE_NAMES = new String[] {
            "allow",        // MODE_ALLOWED
            "ignore",       // MODE_IGNORED
            "deny",         // MODE_ERRORED
            "default",      // MODE_DEFAULT
            "foreground",   // MODE_FOREGROUND
    };

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "UID_STATE_" }, value = {
            UID_STATE_PERSISTENT,
            UID_STATE_TOP,
            UID_STATE_FOREGROUND_SERVICE,
            UID_STATE_FOREGROUND,
            UID_STATE_BACKGROUND,
            UID_STATE_CACHED
    })
    public @interface UidState {}

    /**
     * Invalid UID state.
     * @hide
     */
    public static final int UID_STATE_INVALID = -1;

    /**
     * Metrics about an op when its uid is persistent.
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int UID_STATE_PERSISTENT = 0;

    /**
     * Metrics about an op when its uid is at the top.
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int UID_STATE_TOP = 1;

    /**
     * Metrics about an op when its uid is running a foreground service.
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int UID_STATE_FOREGROUND_SERVICE = 2;

    /**
     * Last UID state in which we don't restrict what an op can do.
     * @hide
     */
    public static final int UID_STATE_LAST_NON_RESTRICTED = UID_STATE_FOREGROUND_SERVICE;

    /**
     * Metrics about an op when its uid is in the foreground for any other reasons.
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int UID_STATE_FOREGROUND = 3;

    /**
     * Metrics about an op when its uid is in the background for any reason.
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int UID_STATE_BACKGROUND = 4;

    /**
     * Metrics about an op when its uid is cached.
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int UID_STATE_CACHED = 5;

    /**
     * Number of uid states we track.
     * @hide
     */
    public static final int _NUM_UID_STATE = 6;

    // when adding one of these:
    //  - increment _NUM_OP
    //  - define an OPSTR_* constant (marked as @SystemApi)
    //  - add rows to sOpToSwitch, sOpToString, sOpNames, sOpToPerms, sOpDefault
    //  - add descriptive strings to Settings/res/values/arrays.xml
    //  - add the op to the appropriate template in AppOpsState.OpsTemplate (settings app)

    /** @hide No operation specified. */
    @UnsupportedAppUsage
    public static final int OP_NONE = -1;
    /** @hide Access to coarse location information. */
    @TestApi
    public static final int OP_COARSE_LOCATION = 0;
    /** @hide Access to fine location information. */
    @UnsupportedAppUsage
    public static final int OP_FINE_LOCATION = 1;
    /** @hide Causing GPS to run. */
    @UnsupportedAppUsage
    public static final int OP_GPS = 2;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_VIBRATE = 3;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_READ_CONTACTS = 4;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_CONTACTS = 5;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_READ_CALL_LOG = 6;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_CALL_LOG = 7;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_READ_CALENDAR = 8;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_CALENDAR = 9;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WIFI_SCAN = 10;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_POST_NOTIFICATION = 11;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_NEIGHBORING_CELLS = 12;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_CALL_PHONE = 13;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_READ_SMS = 14;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_SMS = 15;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_RECEIVE_SMS = 16;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_RECEIVE_EMERGECY_SMS = 17;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_RECEIVE_MMS = 18;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_RECEIVE_WAP_PUSH = 19;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_SEND_SMS = 20;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_READ_ICC_SMS = 21;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_ICC_SMS = 22;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_SETTINGS = 23;
    /** @hide Required to draw on top of other apps. */
    @TestApi
    public static final int OP_SYSTEM_ALERT_WINDOW = 24;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_ACCESS_NOTIFICATIONS = 25;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_CAMERA = 26;
    /** @hide */
    @TestApi
    public static final int OP_RECORD_AUDIO = 27;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_PLAY_AUDIO = 28;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_READ_CLIPBOARD = 29;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_CLIPBOARD = 30;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_TAKE_MEDIA_BUTTONS = 31;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_TAKE_AUDIO_FOCUS = 32;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_MASTER_VOLUME = 33;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_VOICE_VOLUME = 34;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_RING_VOLUME = 35;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_MEDIA_VOLUME = 36;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_ALARM_VOLUME = 37;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_NOTIFICATION_VOLUME = 38;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_BLUETOOTH_VOLUME = 39;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WAKE_LOCK = 40;
    /** @hide Continually monitoring location data. */
    @UnsupportedAppUsage
    public static final int OP_MONITOR_LOCATION = 41;
    /** @hide Continually monitoring location data with a relatively high power request. */
    @UnsupportedAppUsage
    public static final int OP_MONITOR_HIGH_POWER_LOCATION = 42;
    /** @hide Retrieve current usage stats via {@link UsageStatsManager}. */
    @UnsupportedAppUsage
    public static final int OP_GET_USAGE_STATS = 43;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_MUTE_MICROPHONE = 44;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_TOAST_WINDOW = 45;
    /** @hide Capture the device's display contents and/or audio */
    @UnsupportedAppUsage
    public static final int OP_PROJECT_MEDIA = 46;
    /** @hide Activate a VPN connection without user intervention. */
    @UnsupportedAppUsage
    public static final int OP_ACTIVATE_VPN = 47;
    /** @hide Access the WallpaperManagerAPI to write wallpapers. */
    @UnsupportedAppUsage
    public static final int OP_WRITE_WALLPAPER = 48;
    /** @hide Received the assist structure from an app. */
    @UnsupportedAppUsage
    public static final int OP_ASSIST_STRUCTURE = 49;
    /** @hide Received a screenshot from assist. */
    @UnsupportedAppUsage
    public static final int OP_ASSIST_SCREENSHOT = 50;
    /** @hide Read the phone state. */
    @UnsupportedAppUsage
    public static final int OP_READ_PHONE_STATE = 51;
    /** @hide Add voicemail messages to the voicemail content provider. */
    @UnsupportedAppUsage
    public static final int OP_ADD_VOICEMAIL = 52;
    /** @hide Access APIs for SIP calling over VOIP or WiFi. */
    @UnsupportedAppUsage
    public static final int OP_USE_SIP = 53;
    /** @hide Intercept outgoing calls. */
    @UnsupportedAppUsage
    public static final int OP_PROCESS_OUTGOING_CALLS = 54;
    /** @hide User the fingerprint API. */
    @UnsupportedAppUsage
    public static final int OP_USE_FINGERPRINT = 55;
    /** @hide Access to body sensors such as heart rate, etc. */
    @UnsupportedAppUsage
    public static final int OP_BODY_SENSORS = 56;
    /** @hide Read previously received cell broadcast messages. */
    @UnsupportedAppUsage
    public static final int OP_READ_CELL_BROADCASTS = 57;
    /** @hide Inject mock location into the system. */
    @UnsupportedAppUsage
    public static final int OP_MOCK_LOCATION = 58;
    /** @hide Read external storage. */
    @UnsupportedAppUsage
    public static final int OP_READ_EXTERNAL_STORAGE = 59;
    /** @hide Write external storage. */
    @UnsupportedAppUsage
    public static final int OP_WRITE_EXTERNAL_STORAGE = 60;
    /** @hide Turned on the screen. */
    @UnsupportedAppUsage
    public static final int OP_TURN_SCREEN_ON = 61;
    /** @hide Get device accounts. */
    @UnsupportedAppUsage
    public static final int OP_GET_ACCOUNTS = 62;
    /** @hide Control whether an application is allowed to run in the background. */
    @UnsupportedAppUsage
    public static final int OP_RUN_IN_BACKGROUND = 63;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_ACCESSIBILITY_VOLUME = 64;
    /** @hide Read the phone number. */
    @UnsupportedAppUsage
    public static final int OP_READ_PHONE_NUMBERS = 65;
    /** @hide Request package installs through package installer */
    @UnsupportedAppUsage
    public static final int OP_REQUEST_INSTALL_PACKAGES = 66;
    /** @hide Enter picture-in-picture. */
    @UnsupportedAppUsage
    public static final int OP_PICTURE_IN_PICTURE = 67;
    /** @hide Instant app start foreground service. */
    @UnsupportedAppUsage
    public static final int OP_INSTANT_APP_START_FOREGROUND = 68;
    /** @hide Answer incoming phone calls */
    @UnsupportedAppUsage
    public static final int OP_ANSWER_PHONE_CALLS = 69;
    /** @hide Run jobs when in background */
    @UnsupportedAppUsage
    public static final int OP_RUN_ANY_IN_BACKGROUND = 70;
    /** @hide Change Wi-Fi connectivity state */
    @UnsupportedAppUsage
    public static final int OP_CHANGE_WIFI_STATE = 71;
    /** @hide Request package deletion through package installer */
    @UnsupportedAppUsage
    public static final int OP_REQUEST_DELETE_PACKAGES = 72;
    /** @hide Bind an accessibility service. */
    @UnsupportedAppUsage
    public static final int OP_BIND_ACCESSIBILITY_SERVICE = 73;
    /** @hide Continue handover of a call from another app */
    @UnsupportedAppUsage
    public static final int OP_ACCEPT_HANDOVER = 74;
    /** @hide Create and Manage IPsec Tunnels */
    @UnsupportedAppUsage
    public static final int OP_MANAGE_IPSEC_TUNNELS = 75;
    /** @hide Any app start foreground service. */
    @UnsupportedAppUsage
    public static final int OP_START_FOREGROUND = 76;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_BLUETOOTH_SCAN = 77;
    /** @hide Use the BiometricPrompt/BiometricManager APIs. */
    public static final int OP_USE_BIOMETRIC = 78;
    /** @hide Physical activity recognition. */
    public static final int OP_ACTIVITY_RECOGNITION = 79;
    /** @hide Financial app sms read. */
    public static final int OP_SMS_FINANCIAL_TRANSACTIONS = 80;
    /** @hide Read media of audio type. */
    public static final int OP_READ_MEDIA_AUDIO = 81;
    /** @hide Write media of audio type. */
    public static final int OP_WRITE_MEDIA_AUDIO = 82;
    /** @hide Read media of video type. */
    public static final int OP_READ_MEDIA_VIDEO = 83;
    /** @hide Write media of video type. */
    public static final int OP_WRITE_MEDIA_VIDEO = 84;
    /** @hide Read media of image type. */
    public static final int OP_READ_MEDIA_IMAGES = 85;
    /** @hide Write media of image type. */
    public static final int OP_WRITE_MEDIA_IMAGES = 86;
    /** @hide Has a legacy (non-isolated) view of storage. */
    public static final int OP_LEGACY_STORAGE = 87;
    /** @hide */
    @UnsupportedAppUsage
    public static final int _NUM_OP = 88;

    /** Access to coarse location information. */
    public static final String OPSTR_COARSE_LOCATION = "android:coarse_location";
    /** Access to fine location information. */
    public static final String OPSTR_FINE_LOCATION =
            "android:fine_location";
    /** Continually monitoring location data. */
    public static final String OPSTR_MONITOR_LOCATION
            = "android:monitor_location";
    /** Continually monitoring location data with a relatively high power request. */
    public static final String OPSTR_MONITOR_HIGH_POWER_LOCATION
            = "android:monitor_location_high_power";
    /** Access to {@link android.app.usage.UsageStatsManager}. */
    public static final String OPSTR_GET_USAGE_STATS
            = "android:get_usage_stats";
    /** Activate a VPN connection without user intervention. @hide */
    @SystemApi @TestApi
    public static final String OPSTR_ACTIVATE_VPN
            = "android:activate_vpn";
    /** Allows an application to read the user's contacts data. */
    public static final String OPSTR_READ_CONTACTS
            = "android:read_contacts";
    /** Allows an application to write to the user's contacts data. */
    public static final String OPSTR_WRITE_CONTACTS
            = "android:write_contacts";
    /** Allows an application to read the user's call log. */
    public static final String OPSTR_READ_CALL_LOG
            = "android:read_call_log";
    /** Allows an application to write to the user's call log. */
    public static final String OPSTR_WRITE_CALL_LOG
            = "android:write_call_log";
    /** Allows an application to read the user's calendar data. */
    public static final String OPSTR_READ_CALENDAR
            = "android:read_calendar";
    /** Allows an application to write to the user's calendar data. */
    public static final String OPSTR_WRITE_CALENDAR
            = "android:write_calendar";
    /** Allows an application to initiate a phone call. */
    public static final String OPSTR_CALL_PHONE
            = "android:call_phone";
    /** Allows an application to read SMS messages. */
    public static final String OPSTR_READ_SMS
            = "android:read_sms";
    /** Allows an application to receive SMS messages. */
    public static final String OPSTR_RECEIVE_SMS
            = "android:receive_sms";
    /** Allows an application to receive MMS messages. */
    public static final String OPSTR_RECEIVE_MMS
            = "android:receive_mms";
    /** Allows an application to receive WAP push messages. */
    public static final String OPSTR_RECEIVE_WAP_PUSH
            = "android:receive_wap_push";
    /** Allows an application to send SMS messages. */
    public static final String OPSTR_SEND_SMS
            = "android:send_sms";
    /** Required to be able to access the camera device. */
    public static final String OPSTR_CAMERA
            = "android:camera";
    /** Required to be able to access the microphone device. */
    public static final String OPSTR_RECORD_AUDIO
            = "android:record_audio";
    /** Required to access phone state related information. */
    public static final String OPSTR_READ_PHONE_STATE
            = "android:read_phone_state";
    /** Required to access phone state related information. */
    public static final String OPSTR_ADD_VOICEMAIL
            = "android:add_voicemail";
    /** Access APIs for SIP calling over VOIP or WiFi */
    public static final String OPSTR_USE_SIP
            = "android:use_sip";
    /** Access APIs for diverting outgoing calls */
    public static final String OPSTR_PROCESS_OUTGOING_CALLS
            = "android:process_outgoing_calls";
    /** Use the fingerprint API. */
    public static final String OPSTR_USE_FINGERPRINT
            = "android:use_fingerprint";
    /** Access to body sensors such as heart rate, etc. */
    public static final String OPSTR_BODY_SENSORS
            = "android:body_sensors";
    /** Read previously received cell broadcast messages. */
    public static final String OPSTR_READ_CELL_BROADCASTS
            = "android:read_cell_broadcasts";
    /** Inject mock location into the system. */
    public static final String OPSTR_MOCK_LOCATION
            = "android:mock_location";
    /** Read external storage. */
    public static final String OPSTR_READ_EXTERNAL_STORAGE
            = "android:read_external_storage";
    /** Write external storage. */
    public static final String OPSTR_WRITE_EXTERNAL_STORAGE
            = "android:write_external_storage";
    /** Required to draw on top of other apps. */
    public static final String OPSTR_SYSTEM_ALERT_WINDOW
            = "android:system_alert_window";
    /** Required to write/modify/update system settingss. */
    public static final String OPSTR_WRITE_SETTINGS
            = "android:write_settings";
    /** @hide Get device accounts. */
    @SystemApi @TestApi
    public static final String OPSTR_GET_ACCOUNTS
            = "android:get_accounts";
    public static final String OPSTR_READ_PHONE_NUMBERS
            = "android:read_phone_numbers";
    /** Access to picture-in-picture. */
    public static final String OPSTR_PICTURE_IN_PICTURE
            = "android:picture_in_picture";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_INSTANT_APP_START_FOREGROUND
            = "android:instant_app_start_foreground";
    /** Answer incoming phone calls */
    public static final String OPSTR_ANSWER_PHONE_CALLS
            = "android:answer_phone_calls";
    /**
     * Accept call handover
     * @hide
     */
    @SystemApi @TestApi
    public static final String OPSTR_ACCEPT_HANDOVER
            = "android:accept_handover";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_GPS = "android:gps";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_VIBRATE = "android:vibrate";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_WIFI_SCAN = "android:wifi_scan";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_POST_NOTIFICATION = "android:post_notification";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_NEIGHBORING_CELLS = "android:neighboring_cells";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_WRITE_SMS = "android:write_sms";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_RECEIVE_EMERGENCY_BROADCAST =
            "android:receive_emergency_broadcast";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_READ_ICC_SMS = "android:read_icc_sms";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_WRITE_ICC_SMS = "android:write_icc_sms";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_ACCESS_NOTIFICATIONS = "android:access_notifications";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_PLAY_AUDIO = "android:play_audio";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_READ_CLIPBOARD = "android:read_clipboard";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_WRITE_CLIPBOARD = "android:write_clipboard";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_TAKE_MEDIA_BUTTONS = "android:take_media_buttons";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_TAKE_AUDIO_FOCUS = "android:take_audio_focus";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_AUDIO_MASTER_VOLUME = "android:audio_master_volume";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_AUDIO_VOICE_VOLUME = "android:audio_voice_volume";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_AUDIO_RING_VOLUME = "android:audio_ring_volume";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_AUDIO_MEDIA_VOLUME = "android:audio_media_volume";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_AUDIO_ALARM_VOLUME = "android:audio_alarm_volume";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_AUDIO_NOTIFICATION_VOLUME =
            "android:audio_notification_volume";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_AUDIO_BLUETOOTH_VOLUME = "android:audio_bluetooth_volume";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_WAKE_LOCK = "android:wake_lock";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_MUTE_MICROPHONE = "android:mute_microphone";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_TOAST_WINDOW = "android:toast_window";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_PROJECT_MEDIA = "android:project_media";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_WRITE_WALLPAPER = "android:write_wallpaper";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_ASSIST_STRUCTURE = "android:assist_structure";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_ASSIST_SCREENSHOT = "android:assist_screenshot";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_TURN_SCREEN_ON = "android:turn_screen_on";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_RUN_IN_BACKGROUND = "android:run_in_background";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_AUDIO_ACCESSIBILITY_VOLUME =
            "android:audio_accessibility_volume";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_REQUEST_INSTALL_PACKAGES = "android:request_install_packages";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_CHANGE_WIFI_STATE = "android:change_wifi_state";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_REQUEST_DELETE_PACKAGES = "android:request_delete_packages";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_BIND_ACCESSIBILITY_SERVICE =
            "android:bind_accessibility_service";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_MANAGE_IPSEC_TUNNELS = "android:manage_ipsec_tunnels";
    /** @hide */
    @SystemApi @TestApi
    public static final String OPSTR_START_FOREGROUND = "android:start_foreground";
    /** @hide */
    public static final String OPSTR_BLUETOOTH_SCAN = "android:bluetooth_scan";

    /** @hide Use the BiometricPrompt/BiometricManager APIs. */
    public static final String OPSTR_USE_BIOMETRIC = "android:use_biometric";

    /** @hide Recognize physical activity. */
    public static final String OPSTR_ACTIVITY_RECOGNITION = "android:activity_recognition";

    /** @hide Financial app read sms. */
    public static final String OPSTR_SMS_FINANCIAL_TRANSACTIONS =
            "android:sms_financial_transactions";

    /** @hide Read media of audio type. */
    public static final String OPSTR_READ_MEDIA_AUDIO = "android:read_media_audio";
    /** @hide Write media of audio type. */
    public static final String OPSTR_WRITE_MEDIA_AUDIO = "android:write_media_audio";
    /** @hide Read media of video type. */
    public static final String OPSTR_READ_MEDIA_VIDEO = "android:read_media_video";
    /** @hide Write media of video type. */
    public static final String OPSTR_WRITE_MEDIA_VIDEO = "android:write_media_video";
    /** @hide Read media of image type. */
    public static final String OPSTR_READ_MEDIA_IMAGES = "android:read_media_images";
    /** @hide Write media of image type. */
    public static final String OPSTR_WRITE_MEDIA_IMAGES = "android:write_media_images";
    /** @hide Has a legacy (non-isolated) view of storage. */
    public static final String OPSTR_LEGACY_STORAGE = "android:legacy_storage";

    // Warning: If an permission is added here it also has to be added to
    // com.android.packageinstaller.permission.utils.EventLogger
    private static final int[] RUNTIME_AND_APPOP_PERMISSIONS_OPS = {
            // RUNTIME PERMISSIONS
            // Contacts
            OP_READ_CONTACTS,
            OP_WRITE_CONTACTS,
            OP_GET_ACCOUNTS,
            // Calendar
            OP_READ_CALENDAR,
            OP_WRITE_CALENDAR,
            // SMS
            OP_SEND_SMS,
            OP_RECEIVE_SMS,
            OP_READ_SMS,
            OP_RECEIVE_WAP_PUSH,
            OP_RECEIVE_MMS,
            OP_READ_CELL_BROADCASTS,
            // Storage
            OP_READ_EXTERNAL_STORAGE,
            OP_WRITE_EXTERNAL_STORAGE,
            // Location
            OP_COARSE_LOCATION,
            OP_FINE_LOCATION,
            // Phone
            OP_READ_PHONE_STATE,
            OP_READ_PHONE_NUMBERS,
            OP_CALL_PHONE,
            OP_READ_CALL_LOG,
            OP_WRITE_CALL_LOG,
            OP_ADD_VOICEMAIL,
            OP_USE_SIP,
            OP_PROCESS_OUTGOING_CALLS,
            OP_ANSWER_PHONE_CALLS,
            OP_ACCEPT_HANDOVER,
            // Microphone
            OP_RECORD_AUDIO,
            // Camera
            OP_CAMERA,
            // Body sensors
            OP_BODY_SENSORS,
            // Activity recognition
            OP_ACTIVITY_RECOGNITION,
            // Aural
            OP_READ_MEDIA_AUDIO,
            OP_WRITE_MEDIA_AUDIO,
            // Visual
            OP_READ_MEDIA_VIDEO,
            OP_WRITE_MEDIA_VIDEO,
            OP_READ_MEDIA_IMAGES,
            OP_WRITE_MEDIA_IMAGES,

            // APPOP PERMISSIONS
            OP_ACCESS_NOTIFICATIONS,
            OP_SYSTEM_ALERT_WINDOW,
            OP_WRITE_SETTINGS,
            OP_REQUEST_INSTALL_PACKAGES,
            OP_START_FOREGROUND,
            OP_SMS_FINANCIAL_TRANSACTIONS,
    };

    /**
     * This maps each operation to the operation that serves as the
     * switch to determine whether it is allowed.  Generally this is
     * a 1:1 mapping, but for some things (like location) that have
     * multiple low-level operations being tracked that should be
     * presented to the user as one switch then this can be used to
     * make them all controlled by the same single operation.
     */
    private static int[] sOpToSwitch = new int[] {
            OP_COARSE_LOCATION,                 // COARSE_LOCATION
            OP_COARSE_LOCATION,                 // FINE_LOCATION
            OP_COARSE_LOCATION,                 // GPS
            OP_VIBRATE,                         // VIBRATE
            OP_READ_CONTACTS,                   // READ_CONTACTS
            OP_WRITE_CONTACTS,                  // WRITE_CONTACTS
            OP_READ_CALL_LOG,                   // READ_CALL_LOG
            OP_WRITE_CALL_LOG,                  // WRITE_CALL_LOG
            OP_READ_CALENDAR,                   // READ_CALENDAR
            OP_WRITE_CALENDAR,                  // WRITE_CALENDAR
            OP_COARSE_LOCATION,                 // WIFI_SCAN
            OP_POST_NOTIFICATION,               // POST_NOTIFICATION
            OP_COARSE_LOCATION,                 // NEIGHBORING_CELLS
            OP_CALL_PHONE,                      // CALL_PHONE
            OP_READ_SMS,                        // READ_SMS
            OP_WRITE_SMS,                       // WRITE_SMS
            OP_RECEIVE_SMS,                     // RECEIVE_SMS
            OP_RECEIVE_SMS,                     // RECEIVE_EMERGECY_SMS
            OP_RECEIVE_MMS,                     // RECEIVE_MMS
            OP_RECEIVE_WAP_PUSH,                // RECEIVE_WAP_PUSH
            OP_SEND_SMS,                        // SEND_SMS
            OP_READ_SMS,                        // READ_ICC_SMS
            OP_WRITE_SMS,                       // WRITE_ICC_SMS
            OP_WRITE_SETTINGS,                  // WRITE_SETTINGS
            OP_SYSTEM_ALERT_WINDOW,             // SYSTEM_ALERT_WINDOW
            OP_ACCESS_NOTIFICATIONS,            // ACCESS_NOTIFICATIONS
            OP_CAMERA,                          // CAMERA
            OP_RECORD_AUDIO,                    // RECORD_AUDIO
            OP_PLAY_AUDIO,                      // PLAY_AUDIO
            OP_READ_CLIPBOARD,                  // READ_CLIPBOARD
            OP_WRITE_CLIPBOARD,                 // WRITE_CLIPBOARD
            OP_TAKE_MEDIA_BUTTONS,              // TAKE_MEDIA_BUTTONS
            OP_TAKE_AUDIO_FOCUS,                // TAKE_AUDIO_FOCUS
            OP_AUDIO_MASTER_VOLUME,             // AUDIO_MASTER_VOLUME
            OP_AUDIO_VOICE_VOLUME,              // AUDIO_VOICE_VOLUME
            OP_AUDIO_RING_VOLUME,               // AUDIO_RING_VOLUME
            OP_AUDIO_MEDIA_VOLUME,              // AUDIO_MEDIA_VOLUME
            OP_AUDIO_ALARM_VOLUME,              // AUDIO_ALARM_VOLUME
            OP_AUDIO_NOTIFICATION_VOLUME,       // AUDIO_NOTIFICATION_VOLUME
            OP_AUDIO_BLUETOOTH_VOLUME,          // AUDIO_BLUETOOTH_VOLUME
            OP_WAKE_LOCK,                       // WAKE_LOCK
            OP_COARSE_LOCATION,                 // MONITOR_LOCATION
            OP_COARSE_LOCATION,                 // MONITOR_HIGH_POWER_LOCATION
            OP_GET_USAGE_STATS,                 // GET_USAGE_STATS
            OP_MUTE_MICROPHONE,                 // MUTE_MICROPHONE
            OP_TOAST_WINDOW,                    // TOAST_WINDOW
            OP_PROJECT_MEDIA,                   // PROJECT_MEDIA
            OP_ACTIVATE_VPN,                    // ACTIVATE_VPN
            OP_WRITE_WALLPAPER,                 // WRITE_WALLPAPER
            OP_ASSIST_STRUCTURE,                // ASSIST_STRUCTURE
            OP_ASSIST_SCREENSHOT,               // ASSIST_SCREENSHOT
            OP_READ_PHONE_STATE,                // READ_PHONE_STATE
            OP_ADD_VOICEMAIL,                   // ADD_VOICEMAIL
            OP_USE_SIP,                         // USE_SIP
            OP_PROCESS_OUTGOING_CALLS,          // PROCESS_OUTGOING_CALLS
            OP_USE_FINGERPRINT,                 // USE_FINGERPRINT
            OP_BODY_SENSORS,                    // BODY_SENSORS
            OP_READ_CELL_BROADCASTS,            // READ_CELL_BROADCASTS
            OP_MOCK_LOCATION,                   // MOCK_LOCATION
            OP_READ_EXTERNAL_STORAGE,           // READ_EXTERNAL_STORAGE
            OP_WRITE_EXTERNAL_STORAGE,          // WRITE_EXTERNAL_STORAGE
            OP_TURN_SCREEN_ON,                  // TURN_SCREEN_ON
            OP_GET_ACCOUNTS,                    // GET_ACCOUNTS
            OP_RUN_IN_BACKGROUND,               // RUN_IN_BACKGROUND
            OP_AUDIO_ACCESSIBILITY_VOLUME,      // AUDIO_ACCESSIBILITY_VOLUME
            OP_READ_PHONE_NUMBERS,              // READ_PHONE_NUMBERS
            OP_REQUEST_INSTALL_PACKAGES,        // REQUEST_INSTALL_PACKAGES
            OP_PICTURE_IN_PICTURE,              // ENTER_PICTURE_IN_PICTURE_ON_HIDE
            OP_INSTANT_APP_START_FOREGROUND,    // INSTANT_APP_START_FOREGROUND
            OP_ANSWER_PHONE_CALLS,              // ANSWER_PHONE_CALLS
            OP_RUN_ANY_IN_BACKGROUND,           // OP_RUN_ANY_IN_BACKGROUND
            OP_CHANGE_WIFI_STATE,               // OP_CHANGE_WIFI_STATE
            OP_REQUEST_DELETE_PACKAGES,         // OP_REQUEST_DELETE_PACKAGES
            OP_BIND_ACCESSIBILITY_SERVICE,      // OP_BIND_ACCESSIBILITY_SERVICE
            OP_ACCEPT_HANDOVER,                 // ACCEPT_HANDOVER
            OP_MANAGE_IPSEC_TUNNELS,            // MANAGE_IPSEC_HANDOVERS
            OP_START_FOREGROUND,                // START_FOREGROUND
            OP_COARSE_LOCATION,                 // BLUETOOTH_SCAN
            OP_USE_BIOMETRIC,                   // BIOMETRIC
            OP_ACTIVITY_RECOGNITION,            // ACTIVITY_RECOGNITION
            OP_SMS_FINANCIAL_TRANSACTIONS,      // SMS_FINANCIAL_TRANSACTIONS
            OP_READ_MEDIA_AUDIO,                // READ_MEDIA_AUDIO
            OP_WRITE_MEDIA_AUDIO,               // WRITE_MEDIA_AUDIO
            OP_READ_MEDIA_VIDEO,                // READ_MEDIA_VIDEO
            OP_WRITE_MEDIA_VIDEO,               // WRITE_MEDIA_VIDEO
            OP_READ_MEDIA_IMAGES,               // READ_MEDIA_IMAGES
            OP_WRITE_MEDIA_IMAGES,              // WRITE_MEDIA_IMAGES
            OP_LEGACY_STORAGE,                  // LEGACY_STORAGE
    };

    /**
     * This maps each operation to the public string constant for it.
     */
    private static String[] sOpToString = new String[]{
            OPSTR_COARSE_LOCATION,
            OPSTR_FINE_LOCATION,
            OPSTR_GPS,
            OPSTR_VIBRATE,
            OPSTR_READ_CONTACTS,
            OPSTR_WRITE_CONTACTS,
            OPSTR_READ_CALL_LOG,
            OPSTR_WRITE_CALL_LOG,
            OPSTR_READ_CALENDAR,
            OPSTR_WRITE_CALENDAR,
            OPSTR_WIFI_SCAN,
            OPSTR_POST_NOTIFICATION,
            OPSTR_NEIGHBORING_CELLS,
            OPSTR_CALL_PHONE,
            OPSTR_READ_SMS,
            OPSTR_WRITE_SMS,
            OPSTR_RECEIVE_SMS,
            OPSTR_RECEIVE_EMERGENCY_BROADCAST,
            OPSTR_RECEIVE_MMS,
            OPSTR_RECEIVE_WAP_PUSH,
            OPSTR_SEND_SMS,
            OPSTR_READ_ICC_SMS,
            OPSTR_WRITE_ICC_SMS,
            OPSTR_WRITE_SETTINGS,
            OPSTR_SYSTEM_ALERT_WINDOW,
            OPSTR_ACCESS_NOTIFICATIONS,
            OPSTR_CAMERA,
            OPSTR_RECORD_AUDIO,
            OPSTR_PLAY_AUDIO,
            OPSTR_READ_CLIPBOARD,
            OPSTR_WRITE_CLIPBOARD,
            OPSTR_TAKE_MEDIA_BUTTONS,
            OPSTR_TAKE_AUDIO_FOCUS,
            OPSTR_AUDIO_MASTER_VOLUME,
            OPSTR_AUDIO_VOICE_VOLUME,
            OPSTR_AUDIO_RING_VOLUME,
            OPSTR_AUDIO_MEDIA_VOLUME,
            OPSTR_AUDIO_ALARM_VOLUME,
            OPSTR_AUDIO_NOTIFICATION_VOLUME,
            OPSTR_AUDIO_BLUETOOTH_VOLUME,
            OPSTR_WAKE_LOCK,
            OPSTR_MONITOR_LOCATION,
            OPSTR_MONITOR_HIGH_POWER_LOCATION,
            OPSTR_GET_USAGE_STATS,
            OPSTR_MUTE_MICROPHONE,
            OPSTR_TOAST_WINDOW,
            OPSTR_PROJECT_MEDIA,
            OPSTR_ACTIVATE_VPN,
            OPSTR_WRITE_WALLPAPER,
            OPSTR_ASSIST_STRUCTURE,
            OPSTR_ASSIST_SCREENSHOT,
            OPSTR_READ_PHONE_STATE,
            OPSTR_ADD_VOICEMAIL,
            OPSTR_USE_SIP,
            OPSTR_PROCESS_OUTGOING_CALLS,
            OPSTR_USE_FINGERPRINT,
            OPSTR_BODY_SENSORS,
            OPSTR_READ_CELL_BROADCASTS,
            OPSTR_MOCK_LOCATION,
            OPSTR_READ_EXTERNAL_STORAGE,
            OPSTR_WRITE_EXTERNAL_STORAGE,
            OPSTR_TURN_SCREEN_ON,
            OPSTR_GET_ACCOUNTS,
            OPSTR_RUN_IN_BACKGROUND,
            OPSTR_AUDIO_ACCESSIBILITY_VOLUME,
            OPSTR_READ_PHONE_NUMBERS,
            OPSTR_REQUEST_INSTALL_PACKAGES,
            OPSTR_PICTURE_IN_PICTURE,
            OPSTR_INSTANT_APP_START_FOREGROUND,
            OPSTR_ANSWER_PHONE_CALLS,
            OPSTR_RUN_ANY_IN_BACKGROUND,
            OPSTR_CHANGE_WIFI_STATE,
            OPSTR_REQUEST_DELETE_PACKAGES,
            OPSTR_BIND_ACCESSIBILITY_SERVICE,
            OPSTR_ACCEPT_HANDOVER,
            OPSTR_MANAGE_IPSEC_TUNNELS,
            OPSTR_START_FOREGROUND,
            OPSTR_BLUETOOTH_SCAN,
            OPSTR_USE_BIOMETRIC,
            OPSTR_ACTIVITY_RECOGNITION,
            OPSTR_SMS_FINANCIAL_TRANSACTIONS,
            OPSTR_READ_MEDIA_AUDIO,
            OPSTR_WRITE_MEDIA_AUDIO,
            OPSTR_READ_MEDIA_VIDEO,
            OPSTR_WRITE_MEDIA_VIDEO,
            OPSTR_READ_MEDIA_IMAGES,
            OPSTR_WRITE_MEDIA_IMAGES,
            OPSTR_LEGACY_STORAGE,
    };

    /**
     * This provides a simple name for each operation to be used
     * in debug output.
     */
    private static String[] sOpNames = new String[] {
            "COARSE_LOCATION",
            "FINE_LOCATION",
            "GPS",
            "VIBRATE",
            "READ_CONTACTS",
            "WRITE_CONTACTS",
            "READ_CALL_LOG",
            "WRITE_CALL_LOG",
            "READ_CALENDAR",
            "WRITE_CALENDAR",
            "WIFI_SCAN",
            "POST_NOTIFICATION",
            "NEIGHBORING_CELLS",
            "CALL_PHONE",
            "READ_SMS",
            "WRITE_SMS",
            "RECEIVE_SMS",
            "RECEIVE_EMERGECY_SMS",
            "RECEIVE_MMS",
            "RECEIVE_WAP_PUSH",
            "SEND_SMS",
            "READ_ICC_SMS",
            "WRITE_ICC_SMS",
            "WRITE_SETTINGS",
            "SYSTEM_ALERT_WINDOW",
            "ACCESS_NOTIFICATIONS",
            "CAMERA",
            "RECORD_AUDIO",
            "PLAY_AUDIO",
            "READ_CLIPBOARD",
            "WRITE_CLIPBOARD",
            "TAKE_MEDIA_BUTTONS",
            "TAKE_AUDIO_FOCUS",
            "AUDIO_MASTER_VOLUME",
            "AUDIO_VOICE_VOLUME",
            "AUDIO_RING_VOLUME",
            "AUDIO_MEDIA_VOLUME",
            "AUDIO_ALARM_VOLUME",
            "AUDIO_NOTIFICATION_VOLUME",
            "AUDIO_BLUETOOTH_VOLUME",
            "WAKE_LOCK",
            "MONITOR_LOCATION",
            "MONITOR_HIGH_POWER_LOCATION",
            "GET_USAGE_STATS",
            "MUTE_MICROPHONE",
            "TOAST_WINDOW",
            "PROJECT_MEDIA",
            "ACTIVATE_VPN",
            "WRITE_WALLPAPER",
            "ASSIST_STRUCTURE",
            "ASSIST_SCREENSHOT",
            "READ_PHONE_STATE",
            "ADD_VOICEMAIL",
            "USE_SIP",
            "PROCESS_OUTGOING_CALLS",
            "USE_FINGERPRINT",
            "BODY_SENSORS",
            "READ_CELL_BROADCASTS",
            "MOCK_LOCATION",
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "TURN_ON_SCREEN",
            "GET_ACCOUNTS",
            "RUN_IN_BACKGROUND",
            "AUDIO_ACCESSIBILITY_VOLUME",
            "READ_PHONE_NUMBERS",
            "REQUEST_INSTALL_PACKAGES",
            "PICTURE_IN_PICTURE",
            "INSTANT_APP_START_FOREGROUND",
            "ANSWER_PHONE_CALLS",
            "RUN_ANY_IN_BACKGROUND",
            "CHANGE_WIFI_STATE",
            "REQUEST_DELETE_PACKAGES",
            "BIND_ACCESSIBILITY_SERVICE",
            "ACCEPT_HANDOVER",
            "MANAGE_IPSEC_TUNNELS",
            "START_FOREGROUND",
            "BLUETOOTH_SCAN",
            "USE_BIOMETRIC",
            "ACTIVITY_RECOGNITION",
            "SMS_FINANCIAL_TRANSACTIONS",
            "READ_MEDIA_AUDIO",
            "WRITE_MEDIA_AUDIO",
            "READ_MEDIA_VIDEO",
            "WRITE_MEDIA_VIDEO",
            "READ_MEDIA_IMAGES",
            "WRITE_MEDIA_IMAGES",
            "LEGACY_STORAGE",
    };

    /**
     * This optionally maps a permission to an operation.  If there
     * is no permission associated with an operation, it is null.
     */
    @UnsupportedAppUsage
    private static String[] sOpPerms = new String[] {
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            null,
            android.Manifest.permission.VIBRATE,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.WRITE_CALL_LOG,
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            null, // no permission required for notifications
            null, // neighboring cells shares the coarse location perm
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_SMS,
            null, // no permission required for writing sms
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.RECEIVE_EMERGENCY_BROADCAST,
            android.Manifest.permission.RECEIVE_MMS,
            android.Manifest.permission.RECEIVE_WAP_PUSH,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_SMS,
            null, // no permission required for writing icc sms
            android.Manifest.permission.WRITE_SETTINGS,
            android.Manifest.permission.SYSTEM_ALERT_WINDOW,
            android.Manifest.permission.ACCESS_NOTIFICATIONS,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            null, // no permission for playing audio
            null, // no permission for reading clipboard
            null, // no permission for writing clipboard
            null, // no permission for taking media buttons
            null, // no permission for taking audio focus
            null, // no permission for changing master volume
            null, // no permission for changing voice volume
            null, // no permission for changing ring volume
            null, // no permission for changing media volume
            null, // no permission for changing alarm volume
            null, // no permission for changing notification volume
            null, // no permission for changing bluetooth volume
            android.Manifest.permission.WAKE_LOCK,
            null, // no permission for generic location monitoring
            null, // no permission for high power location monitoring
            android.Manifest.permission.PACKAGE_USAGE_STATS,
            null, // no permission for muting/unmuting microphone
            null, // no permission for displaying toasts
            null, // no permission for projecting media
            null, // no permission for activating vpn
            null, // no permission for supporting wallpaper
            null, // no permission for receiving assist structure
            null, // no permission for receiving assist screenshot
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ADD_VOICEMAIL,
            Manifest.permission.USE_SIP,
            Manifest.permission.PROCESS_OUTGOING_CALLS,
            Manifest.permission.USE_FINGERPRINT,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.READ_CELL_BROADCASTS,
            null,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            null, // no permission for turning the screen on
            Manifest.permission.GET_ACCOUNTS,
            null, // no permission for running in background
            null, // no permission for changing accessibility volume
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.REQUEST_INSTALL_PACKAGES,
            null, // no permission for entering picture-in-picture on hide
            Manifest.permission.INSTANT_APP_FOREGROUND_SERVICE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            null, // no permission for OP_RUN_ANY_IN_BACKGROUND
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.REQUEST_DELETE_PACKAGES,
            Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
            Manifest.permission.ACCEPT_HANDOVER,
            null, // no permission for OP_MANAGE_IPSEC_TUNNELS
            Manifest.permission.FOREGROUND_SERVICE,
            null, // no permission for OP_BLUETOOTH_SCAN
            Manifest.permission.USE_BIOMETRIC,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.SMS_FINANCIAL_TRANSACTIONS,
            Manifest.permission.READ_MEDIA_AUDIO,
            null, // no permission for OP_WRITE_MEDIA_AUDIO
            Manifest.permission.READ_MEDIA_VIDEO,
            null, // no permission for OP_WRITE_MEDIA_VIDEO
            Manifest.permission.READ_MEDIA_IMAGES,
            null, // no permission for OP_WRITE_MEDIA_IMAGES
            null, // no permission for OP_LEGACY_STORAGE
    };

    /**
     * Specifies whether an Op should be restricted by a user restriction.
     * Each Op should be filled with a restriction string from UserManager or
     * null to specify it is not affected by any user restriction.
     */
    private static String[] sOpRestrictions = new String[] {
            UserManager.DISALLOW_SHARE_LOCATION, //COARSE_LOCATION
            UserManager.DISALLOW_SHARE_LOCATION, //FINE_LOCATION
            UserManager.DISALLOW_SHARE_LOCATION, //GPS
            null, //VIBRATE
            null, //READ_CONTACTS
            null, //WRITE_CONTACTS
            UserManager.DISALLOW_OUTGOING_CALLS, //READ_CALL_LOG
            UserManager.DISALLOW_OUTGOING_CALLS, //WRITE_CALL_LOG
            null, //READ_CALENDAR
            null, //WRITE_CALENDAR
            UserManager.DISALLOW_SHARE_LOCATION, //WIFI_SCAN
            null, //POST_NOTIFICATION
            null, //NEIGHBORING_CELLS
            null, //CALL_PHONE
            UserManager.DISALLOW_SMS, //READ_SMS
            UserManager.DISALLOW_SMS, //WRITE_SMS
            UserManager.DISALLOW_SMS, //RECEIVE_SMS
            null, //RECEIVE_EMERGENCY_SMS
            UserManager.DISALLOW_SMS, //RECEIVE_MMS
            null, //RECEIVE_WAP_PUSH
            UserManager.DISALLOW_SMS, //SEND_SMS
            UserManager.DISALLOW_SMS, //READ_ICC_SMS
            UserManager.DISALLOW_SMS, //WRITE_ICC_SMS
            null, //WRITE_SETTINGS
            UserManager.DISALLOW_CREATE_WINDOWS, //SYSTEM_ALERT_WINDOW
            null, //ACCESS_NOTIFICATIONS
            UserManager.DISALLOW_CAMERA, //CAMERA
            UserManager.DISALLOW_RECORD_AUDIO, //RECORD_AUDIO
            null, //PLAY_AUDIO
            null, //READ_CLIPBOARD
            null, //WRITE_CLIPBOARD
            null, //TAKE_MEDIA_BUTTONS
            null, //TAKE_AUDIO_FOCUS
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_MASTER_VOLUME
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_VOICE_VOLUME
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_RING_VOLUME
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_MEDIA_VOLUME
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_ALARM_VOLUME
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_NOTIFICATION_VOLUME
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_BLUETOOTH_VOLUME
            null, //WAKE_LOCK
            UserManager.DISALLOW_SHARE_LOCATION, //MONITOR_LOCATION
            UserManager.DISALLOW_SHARE_LOCATION, //MONITOR_HIGH_POWER_LOCATION
            null, //GET_USAGE_STATS
            UserManager.DISALLOW_UNMUTE_MICROPHONE, // MUTE_MICROPHONE
            UserManager.DISALLOW_CREATE_WINDOWS, // TOAST_WINDOW
            null, //PROJECT_MEDIA
            null, // ACTIVATE_VPN
            UserManager.DISALLOW_WALLPAPER, // WRITE_WALLPAPER
            null, // ASSIST_STRUCTURE
            null, // ASSIST_SCREENSHOT
            null, // READ_PHONE_STATE
            null, // ADD_VOICEMAIL
            null, // USE_SIP
            null, // PROCESS_OUTGOING_CALLS
            null, // USE_FINGERPRINT
            null, // BODY_SENSORS
            null, // READ_CELL_BROADCASTS
            null, // MOCK_LOCATION
            null, // READ_EXTERNAL_STORAGE
            null, // WRITE_EXTERNAL_STORAGE
            null, // TURN_ON_SCREEN
            null, // GET_ACCOUNTS
            null, // RUN_IN_BACKGROUND
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_ACCESSIBILITY_VOLUME
            null, // READ_PHONE_NUMBERS
            null, // REQUEST_INSTALL_PACKAGES
            null, // ENTER_PICTURE_IN_PICTURE_ON_HIDE
            null, // INSTANT_APP_START_FOREGROUND
            null, // ANSWER_PHONE_CALLS
            null, // OP_RUN_ANY_IN_BACKGROUND
            null, // OP_CHANGE_WIFI_STATE
            null, // REQUEST_DELETE_PACKAGES
            null, // OP_BIND_ACCESSIBILITY_SERVICE
            null, // ACCEPT_HANDOVER
            null, // MANAGE_IPSEC_TUNNELS
            null, // START_FOREGROUND
            null, // maybe should be UserManager.DISALLOW_SHARE_LOCATION, //BLUETOOTH_SCAN
            null, // USE_BIOMETRIC
            null, // ACTIVITY_RECOGNITION
            UserManager.DISALLOW_SMS, // SMS_FINANCIAL_TRANSACTIONS
            null, // READ_MEDIA_AUDIO
            null, // WRITE_MEDIA_AUDIO
            null, // READ_MEDIA_VIDEO
            null, // WRITE_MEDIA_VIDEO
            null, // READ_MEDIA_IMAGES
            null, // WRITE_MEDIA_IMAGES
            null, // LEGACY_STORAGE
    };

    /**
     * This specifies whether each option should allow the system
     * (and system ui) to bypass the user restriction when active.
     */
    private static boolean[] sOpAllowSystemRestrictionBypass = new boolean[] {
            true, //COARSE_LOCATION
            true, //FINE_LOCATION
            false, //GPS
            false, //VIBRATE
            false, //READ_CONTACTS
            false, //WRITE_CONTACTS
            false, //READ_CALL_LOG
            false, //WRITE_CALL_LOG
            false, //READ_CALENDAR
            false, //WRITE_CALENDAR
            true, //WIFI_SCAN
            false, //POST_NOTIFICATION
            false, //NEIGHBORING_CELLS
            false, //CALL_PHONE
            false, //READ_SMS
            false, //WRITE_SMS
            false, //RECEIVE_SMS
            false, //RECEIVE_EMERGECY_SMS
            false, //RECEIVE_MMS
            false, //RECEIVE_WAP_PUSH
            false, //SEND_SMS
            false, //READ_ICC_SMS
            false, //WRITE_ICC_SMS
            false, //WRITE_SETTINGS
            true, //SYSTEM_ALERT_WINDOW
            false, //ACCESS_NOTIFICATIONS
            false, //CAMERA
            false, //RECORD_AUDIO
            false, //PLAY_AUDIO
            false, //READ_CLIPBOARD
            false, //WRITE_CLIPBOARD
            false, //TAKE_MEDIA_BUTTONS
            false, //TAKE_AUDIO_FOCUS
            false, //AUDIO_MASTER_VOLUME
            false, //AUDIO_VOICE_VOLUME
            false, //AUDIO_RING_VOLUME
            false, //AUDIO_MEDIA_VOLUME
            false, //AUDIO_ALARM_VOLUME
            false, //AUDIO_NOTIFICATION_VOLUME
            false, //AUDIO_BLUETOOTH_VOLUME
            false, //WAKE_LOCK
            false, //MONITOR_LOCATION
            false, //MONITOR_HIGH_POWER_LOCATION
            false, //GET_USAGE_STATS
            false, //MUTE_MICROPHONE
            true, //TOAST_WINDOW
            false, //PROJECT_MEDIA
            false, //ACTIVATE_VPN
            false, //WALLPAPER
            false, //ASSIST_STRUCTURE
            false, //ASSIST_SCREENSHOT
            false, //READ_PHONE_STATE
            false, //ADD_VOICEMAIL
            false, // USE_SIP
            false, // PROCESS_OUTGOING_CALLS
            false, // USE_FINGERPRINT
            false, // BODY_SENSORS
            false, // READ_CELL_BROADCASTS
            false, // MOCK_LOCATION
            false, // READ_EXTERNAL_STORAGE
            false, // WRITE_EXTERNAL_STORAGE
            false, // TURN_ON_SCREEN
            false, // GET_ACCOUNTS
            false, // RUN_IN_BACKGROUND
            false, // AUDIO_ACCESSIBILITY_VOLUME
            false, // READ_PHONE_NUMBERS
            false, // REQUEST_INSTALL_PACKAGES
            false, // ENTER_PICTURE_IN_PICTURE_ON_HIDE
            false, // INSTANT_APP_START_FOREGROUND
            false, // ANSWER_PHONE_CALLS
            false, // OP_RUN_ANY_IN_BACKGROUND
            false, // OP_CHANGE_WIFI_STATE
            false, // OP_REQUEST_DELETE_PACKAGES
            false, // OP_BIND_ACCESSIBILITY_SERVICE
            false, // ACCEPT_HANDOVER
            false, // MANAGE_IPSEC_HANDOVERS
            false, // START_FOREGROUND
            true, // BLUETOOTH_SCAN
            false, // USE_BIOMETRIC
            false, // ACTIVITY_RECOGNITION
            false, // SMS_FINANCIAL_TRANSACTIONS
            false, // READ_MEDIA_AUDIO
            false, // WRITE_MEDIA_AUDIO
            false, // READ_MEDIA_VIDEO
            false, // WRITE_MEDIA_VIDEO
            false, // READ_MEDIA_IMAGES
            false, // WRITE_MEDIA_IMAGES
            false, // LEGACY_STORAGE
    };

    /**
     * This specifies the default mode for each operation.
     */
    private static int[] sOpDefaultMode = new int[] {
            AppOpsManager.MODE_ALLOWED, // COARSE_LOCATION
            AppOpsManager.MODE_ALLOWED, // FINE_LOCATION
            AppOpsManager.MODE_ALLOWED, // GPS
            AppOpsManager.MODE_ALLOWED, // VIBRATE
            AppOpsManager.MODE_ALLOWED, // READ_CONTACTS
            AppOpsManager.MODE_ALLOWED, // WRITE_CONTACTS
            AppOpsManager.MODE_ALLOWED, // READ_CALL_LOG
            AppOpsManager.MODE_ALLOWED, // WRITE_CALL_LOG
            AppOpsManager.MODE_ALLOWED, // READ_CALENDAR
            AppOpsManager.MODE_ALLOWED, // WRITE_CALENDAR
            AppOpsManager.MODE_ALLOWED, // WIFI_SCAN
            AppOpsManager.MODE_ALLOWED, // POST_NOTIFICATION
            AppOpsManager.MODE_ALLOWED, // NEIGHBORING_CELLS
            AppOpsManager.MODE_ALLOWED, // CALL_PHONE
            AppOpsManager.MODE_ALLOWED, // READ_SMS
            AppOpsManager.MODE_IGNORED, // WRITE_SMS
            AppOpsManager.MODE_ALLOWED, // RECEIVE_SMS
            AppOpsManager.MODE_ALLOWED, // RECEIVE_EMERGENCY_BROADCAST
            AppOpsManager.MODE_ALLOWED, // RECEIVE_MMS
            AppOpsManager.MODE_ALLOWED, // RECEIVE_WAP_PUSH
            AppOpsManager.MODE_ALLOWED, // SEND_SMS
            AppOpsManager.MODE_ALLOWED, // READ_ICC_SMS
            AppOpsManager.MODE_ALLOWED, // WRITE_ICC_SMS
            AppOpsManager.MODE_DEFAULT, // WRITE_SETTINGS
            AppOpsManager.MODE_DEFAULT, // SYSTEM_ALERT_WINDOW
            AppOpsManager.MODE_ALLOWED, // ACCESS_NOTIFICATIONS
            AppOpsManager.MODE_ALLOWED, // CAMERA
            AppOpsManager.MODE_ALLOWED, // RECORD_AUDIO
            AppOpsManager.MODE_ALLOWED, // PLAY_AUDIO
            AppOpsManager.MODE_ALLOWED, // READ_CLIPBOARD
            AppOpsManager.MODE_ALLOWED, // WRITE_CLIPBOARD
            AppOpsManager.MODE_ALLOWED, // TAKE_MEDIA_BUTTONS
            AppOpsManager.MODE_ALLOWED, // TAKE_AUDIO_FOCUS
            AppOpsManager.MODE_ALLOWED, // AUDIO_MASTER_VOLUME
            AppOpsManager.MODE_ALLOWED, // AUDIO_VOICE_VOLUME
            AppOpsManager.MODE_ALLOWED, // AUDIO_RING_VOLUME
            AppOpsManager.MODE_ALLOWED, // AUDIO_MEDIA_VOLUME
            AppOpsManager.MODE_ALLOWED, // AUDIO_ALARM_VOLUME
            AppOpsManager.MODE_ALLOWED, // AUDIO_NOTIFICATION_VOLUME
            AppOpsManager.MODE_ALLOWED, // AUDIO_BLUETOOTH_VOLUME
            AppOpsManager.MODE_ALLOWED, // WAKE_LOCK
            AppOpsManager.MODE_ALLOWED, // MONITOR_LOCATION
            AppOpsManager.MODE_ALLOWED, // MONITOR_HIGH_POWER_LOCATION
            AppOpsManager.MODE_DEFAULT, // GET_USAGE_STATS
            AppOpsManager.MODE_ALLOWED, // MUTE_MICROPHONE
            AppOpsManager.MODE_ALLOWED, // TOAST_WINDOW
            AppOpsManager.MODE_IGNORED, // PROJECT_MEDIA
            AppOpsManager.MODE_IGNORED, // ACTIVATE_VPN
            AppOpsManager.MODE_ALLOWED, // WRITE_WALLPAPER
            AppOpsManager.MODE_ALLOWED, // ASSIST_STRUCTURE
            AppOpsManager.MODE_ALLOWED, // ASSIST_SCREENSHOT
            AppOpsManager.MODE_ALLOWED, // READ_PHONE_STATE
            AppOpsManager.MODE_ALLOWED, // ADD_VOICEMAIL
            AppOpsManager.MODE_ALLOWED, // USE_SIP
            AppOpsManager.MODE_ALLOWED, // PROCESS_OUTGOING_CALLS
            AppOpsManager.MODE_ALLOWED, // USE_FINGERPRINT
            AppOpsManager.MODE_ALLOWED, // BODY_SENSORS
            AppOpsManager.MODE_ALLOWED, // READ_CELL_BROADCASTS
            AppOpsManager.MODE_ERRORED, // MOCK_LOCATION
            AppOpsManager.MODE_ALLOWED, // READ_EXTERNAL_STORAGE
            AppOpsManager.MODE_ALLOWED, // WRITE_EXTERNAL_STORAGE
            AppOpsManager.MODE_ALLOWED, // TURN_SCREEN_ON
            AppOpsManager.MODE_ALLOWED, // GET_ACCOUNTS
            AppOpsManager.MODE_ALLOWED, // RUN_IN_BACKGROUND
            AppOpsManager.MODE_ALLOWED, // AUDIO_ACCESSIBILITY_VOLUME
            AppOpsManager.MODE_ALLOWED, // READ_PHONE_NUMBERS
            AppOpsManager.MODE_DEFAULT, // REQUEST_INSTALL_PACKAGES
            AppOpsManager.MODE_ALLOWED, // PICTURE_IN_PICTURE
            AppOpsManager.MODE_DEFAULT, // INSTANT_APP_START_FOREGROUND
            AppOpsManager.MODE_ALLOWED, // ANSWER_PHONE_CALLS
            AppOpsManager.MODE_ALLOWED, // RUN_ANY_IN_BACKGROUND
            AppOpsManager.MODE_ALLOWED, // CHANGE_WIFI_STATE
            AppOpsManager.MODE_ALLOWED, // REQUEST_DELETE_PACKAGES
            AppOpsManager.MODE_ALLOWED, // BIND_ACCESSIBILITY_SERVICE
            AppOpsManager.MODE_ALLOWED, // ACCEPT_HANDOVER
            AppOpsManager.MODE_ERRORED, // MANAGE_IPSEC_TUNNELS
            AppOpsManager.MODE_ALLOWED, // START_FOREGROUND
            AppOpsManager.MODE_ALLOWED, // BLUETOOTH_SCAN
            AppOpsManager.MODE_ALLOWED, // USE_BIOMETRIC
            AppOpsManager.MODE_ALLOWED, // ACTIVITY_RECOGNITION
            AppOpsManager.MODE_DEFAULT, // SMS_FINANCIAL_TRANSACTIONS
            AppOpsManager.MODE_ALLOWED, // READ_MEDIA_AUDIO
            AppOpsManager.MODE_ERRORED, // WRITE_MEDIA_AUDIO
            AppOpsManager.MODE_ALLOWED, // READ_MEDIA_VIDEO
            AppOpsManager.MODE_ERRORED, // WRITE_MEDIA_VIDEO
            AppOpsManager.MODE_ALLOWED, // READ_MEDIA_IMAGES
            AppOpsManager.MODE_ERRORED, // WRITE_MEDIA_IMAGES
            AppOpsManager.MODE_DEFAULT, // LEGACY_STORAGE
    };

    /**
     * This specifies whether each option is allowed to be reset
     * when resetting all app preferences.  Disable reset for
     * app ops that are under strong control of some part of the
     * system (such as OP_WRITE_SMS, which should be allowed only
     * for whichever app is selected as the current SMS app).
     */
    private static boolean[] sOpDisableReset = new boolean[] {
            false, // COARSE_LOCATION
            false, // FINE_LOCATION
            false, // GPS
            false, // VIBRATE
            false, // READ_CONTACTS
            false, // WRITE_CONTACTS
            false, // READ_CALL_LOG
            false, // WRITE_CALL_LOG
            false, // READ_CALENDAR
            false, // WRITE_CALENDAR
            false, // WIFI_SCAN
            false, // POST_NOTIFICATION
            false, // NEIGHBORING_CELLS
            false, // CALL_PHONE
            true, // READ_SMS
            true, // WRITE_SMS
            true, // RECEIVE_SMS
            false, // RECEIVE_EMERGENCY_BROADCAST
            false, // RECEIVE_MMS
            true, // RECEIVE_WAP_PUSH
            true, // SEND_SMS
            false, // READ_ICC_SMS
            false, // WRITE_ICC_SMS
            false, // WRITE_SETTINGS
            false, // SYSTEM_ALERT_WINDOW
            false, // ACCESS_NOTIFICATIONS
            false, // CAMERA
            false, // RECORD_AUDIO
            false, // PLAY_AUDIO
            false, // READ_CLIPBOARD
            false, // WRITE_CLIPBOARD
            false, // TAKE_MEDIA_BUTTONS
            false, // TAKE_AUDIO_FOCUS
            false, // AUDIO_MASTER_VOLUME
            false, // AUDIO_VOICE_VOLUME
            false, // AUDIO_RING_VOLUME
            false, // AUDIO_MEDIA_VOLUME
            false, // AUDIO_ALARM_VOLUME
            false, // AUDIO_NOTIFICATION_VOLUME
            false, // AUDIO_BLUETOOTH_VOLUME
            false, // WAKE_LOCK
            false, // MONITOR_LOCATION
            false, // MONITOR_HIGH_POWER_LOCATION
            false, // GET_USAGE_STATS
            false, // MUTE_MICROPHONE
            false, // TOAST_WINDOW
            false, // PROJECT_MEDIA
            false, // ACTIVATE_VPN
            false, // WRITE_WALLPAPER
            false, // ASSIST_STRUCTURE
            false, // ASSIST_SCREENSHOT
            false, // READ_PHONE_STATE
            false, // ADD_VOICEMAIL
            false, // USE_SIP
            false, // PROCESS_OUTGOING_CALLS
            false, // USE_FINGERPRINT
            false, // BODY_SENSORS
            true, // READ_CELL_BROADCASTS
            false, // MOCK_LOCATION
            false, // READ_EXTERNAL_STORAGE
            false, // WRITE_EXTERNAL_STORAGE
            false, // TURN_SCREEN_ON
            false, // GET_ACCOUNTS
            false, // RUN_IN_BACKGROUND
            false, // AUDIO_ACCESSIBILITY_VOLUME
            false, // READ_PHONE_NUMBERS
            false, // REQUEST_INSTALL_PACKAGES
            false, // PICTURE_IN_PICTURE
            false, // INSTANT_APP_START_FOREGROUND
            false, // ANSWER_PHONE_CALLS
            false, // RUN_ANY_IN_BACKGROUND
            false, // CHANGE_WIFI_STATE
            false, // REQUEST_DELETE_PACKAGES
            false, // BIND_ACCESSIBILITY_SERVICE
            false, // ACCEPT_HANDOVER
            false, // MANAGE_IPSEC_TUNNELS
            false, // START_FOREGROUND
            false, // BLUETOOTH_SCAN
            false, // USE_BIOMETRIC
            false, // ACTIVITY_RECOGNITION
            false, // SMS_FINANCIAL_TRANSACTIONS
            false, // READ_MEDIA_AUDIO
            false, // WRITE_MEDIA_AUDIO
            false, // READ_MEDIA_VIDEO
            false, // WRITE_MEDIA_VIDEO
            false, // READ_MEDIA_IMAGES
            false, // WRITE_MEDIA_IMAGES
            false, // LEGACY_STORAGE
    };

    /**
     * Mapping from an app op name to the app op code.
     */
    private static HashMap<String, Integer> sOpStrToOp = new HashMap<>();

    /**
     * Mapping from a permission to the corresponding app op.
     */
    private static HashMap<String, Integer> sPermToOp = new HashMap<>();

    static {
        if (sOpToSwitch.length != _NUM_OP) {
            throw new IllegalStateException("sOpToSwitch length " + sOpToSwitch.length
                    + " should be " + _NUM_OP);
        }
        if (sOpToString.length != _NUM_OP) {
            throw new IllegalStateException("sOpToString length " + sOpToString.length
                    + " should be " + _NUM_OP);
        }
        if (sOpNames.length != _NUM_OP) {
            throw new IllegalStateException("sOpNames length " + sOpNames.length
                    + " should be " + _NUM_OP);
        }
        if (sOpPerms.length != _NUM_OP) {
            throw new IllegalStateException("sOpPerms length " + sOpPerms.length
                    + " should be " + _NUM_OP);
        }
        if (sOpDefaultMode.length != _NUM_OP) {
            throw new IllegalStateException("sOpDefaultMode length " + sOpDefaultMode.length
                    + " should be " + _NUM_OP);
        }
        if (sOpDisableReset.length != _NUM_OP) {
            throw new IllegalStateException("sOpDisableReset length " + sOpDisableReset.length
                    + " should be " + _NUM_OP);
        }
        if (sOpRestrictions.length != _NUM_OP) {
            throw new IllegalStateException("sOpRestrictions length " + sOpRestrictions.length
                    + " should be " + _NUM_OP);
        }
        if (sOpAllowSystemRestrictionBypass.length != _NUM_OP) {
            throw new IllegalStateException("sOpAllowSYstemRestrictionsBypass length "
                    + sOpRestrictions.length + " should be " + _NUM_OP);
        }
        for (int i=0; i<_NUM_OP; i++) {
            if (sOpToString[i] != null) {
                sOpStrToOp.put(sOpToString[i], i);
            }
        }
        for (int op : RUNTIME_AND_APPOP_PERMISSIONS_OPS) {
            if (sOpPerms[op] != null) {
                sPermToOp.put(sOpPerms[op], op);
            }
        }
    }

    /** @hide */
    public static final String KEY_HISTORICAL_OPS = "historical_ops";

    /**
     * Retrieve the op switch that controls the given operation.
     * @hide
     */
    @UnsupportedAppUsage
    public static int opToSwitch(int op) {
        return sOpToSwitch[op];
    }

    /**
     * Retrieve a non-localized name for the operation, for debugging output.
     * @hide
     */
    @UnsupportedAppUsage
    public static String opToName(int op) {
        if (op == OP_NONE) return "NONE";
        return op < sOpNames.length ? sOpNames[op] : ("Unknown(" + op + ")");
    }

    /**
     * @hide
     */
    public static int strDebugOpToOp(String op) {
        for (int i=0; i<sOpNames.length; i++) {
            if (sOpNames[i].equals(op)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown operation string: " + op);
    }

    /**
     * Retrieve the permission associated with an operation, or null if there is not one.
     * @hide
     */
    @TestApi
    public static String opToPermission(int op) {
        return sOpPerms[op];
    }

    /**
     * Retrieve the permission associated with an operation, or null if there is not one.
     *
     * @param op The operation name.
     *
     * @hide
     */
    @Nullable
    @SystemApi
    public static String opToPermission(@NonNull String op) {
        return opToPermission(strOpToOp(op));
    }

    /**
     * Retrieve the user restriction associated with an operation, or null if there is not one.
     * @hide
     */
    public static String opToRestriction(int op) {
        return sOpRestrictions[op];
    }

    /**
     * Retrieve the app op code for a permission, or null if there is not one.
     * This API is intended to be used for mapping runtime or appop permissions
     * to the corresponding app op.
     * @hide
     */
    @TestApi
    public static int permissionToOpCode(String permission) {
        Integer boxedOpCode = sPermToOp.get(permission);
        return boxedOpCode != null ? boxedOpCode : OP_NONE;
    }

    /**
     * Retrieve whether the op allows the system (and system ui) to
     * bypass the user restriction.
     * @hide
     */
    public static boolean opAllowSystemBypassRestriction(int op) {
        return sOpAllowSystemRestrictionBypass[op];
    }

    /**
     * Retrieve the default mode for the operation.
     * @hide
     */
    public static @Mode int opToDefaultMode(int op) {
        // STOPSHIP b/118520006: Hardcode the default values once the feature is stable.
        switch (op) {
            // SMS permissions
            case AppOpsManager.OP_SEND_SMS:
            case AppOpsManager.OP_RECEIVE_SMS:
            case AppOpsManager.OP_READ_SMS:
            case AppOpsManager.OP_RECEIVE_WAP_PUSH:
            case AppOpsManager.OP_RECEIVE_MMS:
            case AppOpsManager.OP_READ_CELL_BROADCASTS:
            // CallLog permissions
            case AppOpsManager.OP_READ_CALL_LOG:
            case AppOpsManager.OP_WRITE_CALL_LOG:
            case AppOpsManager.OP_PROCESS_OUTGOING_CALLS: {
                if (sSmsAndCallLogRestrictionEnabled.get() == 1) {
                    return AppOpsManager.MODE_DEFAULT;
                }
            }
        }
        return sOpDefaultMode[op];
    }

    // STOPSHIP b/118520006: Hardcode the default values once the feature is stable.
    private static final AtomicInteger sSmsAndCallLogRestrictionEnabled = new AtomicInteger(-1);

    // STOPSHIP b/118520006: Hardcode the default values once the feature is stable.
    static {
        final Context context = ActivityThread.currentApplication();
        if (context != null) {
            sSmsAndCallLogRestrictionEnabled.set(ActivityThread.currentActivityThread()
                        .getIntCoreSetting(Settings.Global.SMS_ACCESS_RESTRICTION_ENABLED, 0));

            final Uri uri =
                    Settings.Global.getUriFor(Settings.Global.SMS_ACCESS_RESTRICTION_ENABLED);
            context.getContentResolver().registerContentObserver(uri, false, new ContentObserver(
                    context.getMainThreadHandler()) {
                @Override
                public void onChange(boolean selfChange) {
                    sSmsAndCallLogRestrictionEnabled.set(Settings.Global.getInt(
                            context.getContentResolver(),
                            Settings.Global.SMS_ACCESS_RESTRICTION_ENABLED, 0));
                }
            });
        }
    }

    /**
     * Retrieve the default mode for the app op.
     *
     * @param appOp The app op name
     *
     * @return the default mode for the app op
     *
     * @hide
     */
    @SystemApi
    public static int opToDefaultMode(@NonNull String appOp) {
        return opToDefaultMode(strOpToOp(appOp));
    }

    /**
     * Retrieve the human readable mode.
     * @hide
     */
    public static String modeToName(@Mode int mode) {
        if (mode >= 0 && mode < MODE_NAMES.length) {
            return MODE_NAMES[mode];
        }
        return "mode=" + mode;
    }

    /**
     * Retrieve whether the op allows itself to be reset.
     * @hide
     */
    public static boolean opAllowsReset(int op) {
        return !sOpDisableReset[op];
    }

    /**
     * Class holding all of the operation information associated with an app.
     * @hide
     */
    @SystemApi
    public static final class PackageOps implements Parcelable {
        private final String mPackageName;
        private final int mUid;
        private final List<OpEntry> mEntries;

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public PackageOps(String packageName, int uid, List<OpEntry> entries) {
            mPackageName = packageName;
            mUid = uid;
            mEntries = entries;
        }

        public String getPackageName() {
            return mPackageName;
        }

        public int getUid() {
            return mUid;
        }

        public List<OpEntry> getOps() {
            return mEntries;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mPackageName);
            dest.writeInt(mUid);
            dest.writeInt(mEntries.size());
            for (int i=0; i<mEntries.size(); i++) {
                mEntries.get(i).writeToParcel(dest, flags);
            }
        }

        PackageOps(Parcel source) {
            mPackageName = source.readString();
            mUid = source.readInt();
            mEntries = new ArrayList<OpEntry>();
            final int N = source.readInt();
            for (int i=0; i<N; i++) {
                mEntries.add(OpEntry.CREATOR.createFromParcel(source));
            }
        }

        public static final Creator<PackageOps> CREATOR = new Creator<PackageOps>() {
            @Override public PackageOps createFromParcel(Parcel source) {
                return new PackageOps(source);
            }

            @Override public PackageOps[] newArray(int size) {
                return new PackageOps[size];
            }
        };
    }

    /**
     * Class holding the information about one unique operation of an application.
     * @hide
     */
    @SystemApi
    public static final class OpEntry implements Parcelable {
        private final int mOp;
        private final @Mode int mMode;
        private final long[] mTimes;
        private final long[] mRejectTimes;
        private final int mDuration;
        private final int mProxyUid;
        private final boolean mRunning;
        private final String mProxyPackageName;

        /**
         * @hide
         */
        public OpEntry(int op, @Mode int mode, long time, long rejectTime, int duration,
                int proxyUid, String proxyPackage) {
            mOp = op;
            mMode = mode;
            mTimes = new long[_NUM_UID_STATE];
            mRejectTimes = new long[_NUM_UID_STATE];
            mTimes[0] = time;
            mRejectTimes[0] = rejectTime;
            mDuration = duration;
            mRunning = duration == -1;
            mProxyUid = proxyUid;
            mProxyPackageName = proxyPackage;
        }

        /**
         * @hide
         */
        public OpEntry(int op, @Mode int mode, long[] times, long[] rejectTimes, int duration,
                boolean running, int proxyUid, String proxyPackage) {
            mOp = op;
            mMode = mode;
            mTimes = new long[_NUM_UID_STATE];
            mRejectTimes = new long[_NUM_UID_STATE];
            System.arraycopy(times, 0, mTimes, 0, _NUM_UID_STATE);
            System.arraycopy(rejectTimes, 0, mRejectTimes, 0, _NUM_UID_STATE);
            mDuration = duration;
            mRunning = running;
            mProxyUid = proxyUid;
            mProxyPackageName = proxyPackage;
        }

        /**
         * @hide
         */
        public OpEntry(int op, @Mode int mode, long[] times, long[] rejectTimes, int duration,
                int proxyUid, String proxyPackage) {
            this(op, mode, times, rejectTimes, duration, duration == -1, proxyUid, proxyPackage);
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public int getOp() {
            return mOp;
        }

        /**
         * Return this entry's op string name, such as {@link #OPSTR_COARSE_LOCATION}.
         */
        public String getOpStr() {
            return sOpToString[mOp];
        }

        /**
         * Return this entry's current mode, such as {@link #MODE_ALLOWED}.
         */
        public @Mode int getMode() {
            return mMode;
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public long getTime() {
            return maxTime(mTimes, 0, _NUM_UID_STATE);
        }

        /**
         * Return the last wall clock time this op was accessed by the app.
         */
        public long getLastAccessTime() {
            return maxTime(mTimes, 0, _NUM_UID_STATE);
        }

        /**
         * Return the last wall clock time this op was accessed by the app while in the foreground.
         */
        public long getLastAccessForegroundTime() {
            return maxTime(mTimes, UID_STATE_PERSISTENT, UID_STATE_LAST_NON_RESTRICTED + 1);
        }

        /**
         * Return the last wall clock time this op was accessed by the app while in the background.
         */
        public long getLastAccessBackgroundTime() {
            return maxTime(mTimes, UID_STATE_LAST_NON_RESTRICTED + 1, _NUM_UID_STATE);
        }

        /**
         * @hide
         */
        public long getLastTimeFor(int uidState) {
            return mTimes[uidState];
        }

        /**
         * @hide
         */
        public long getRejectTime() {
            return maxTime(mRejectTimes, 0, _NUM_UID_STATE);
        }

        /**
         * Return the last wall clock time the app made an attempt to access this op but
         * was rejected.
         */
        public long getLastRejectTime() {
            return maxTime(mRejectTimes, 0, _NUM_UID_STATE);
        }

        /**
         * Return the last wall clock time the app made an attempt to access this op while in
         * the foreground but was rejected.
         */
        public long getLastRejectForegroundTime() {
            return maxTime(mRejectTimes, UID_STATE_PERSISTENT, UID_STATE_LAST_NON_RESTRICTED + 1);
        }

        /**
         * Return the last wall clock time the app made an attempt to access this op while in
         * the background but was rejected.
         */
        public long getLastRejectBackgroundTime() {
            return maxTime(mRejectTimes, UID_STATE_LAST_NON_RESTRICTED + 1, _NUM_UID_STATE);
        }

        /**
         * @hide
         */
        public long getLastRejectTimeFor(int uidState) {
            return mRejectTimes[uidState];
        }

        public boolean isRunning() {
            return mRunning;
        }

        public int getDuration() {
            return mDuration;
        }

        public int getProxyUid() {
            return  mProxyUid;
        }

        public String getProxyPackageName() {
            return mProxyPackageName;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mOp);
            dest.writeInt(mMode);
            dest.writeLongArray(mTimes);
            dest.writeLongArray(mRejectTimes);
            dest.writeInt(mDuration);
            dest.writeBoolean(mRunning);
            dest.writeInt(mProxyUid);
            dest.writeString(mProxyPackageName);
        }

        OpEntry(Parcel source) {
            mOp = source.readInt();
            mMode = source.readInt();
            mTimes = source.createLongArray();
            mRejectTimes = source.createLongArray();
            mDuration = source.readInt();
            mRunning = source.readBoolean();
            mProxyUid = source.readInt();
            mProxyPackageName = source.readString();
        }

        public static final Creator<OpEntry> CREATOR = new Creator<OpEntry>() {
            @Override public OpEntry createFromParcel(Parcel source) {
                return new OpEntry(source);
            }

            @Override public OpEntry[] newArray(int size) {
                return new OpEntry[size];
            }
        };
    }

    /** @hide */
    public interface HistoricalOpsVisitor {
        void visitHistoricalOps(@NonNull HistoricalOps ops);
        void visitHistoricalUidOps(@NonNull HistoricalUidOps ops);
        void visitHistoricalPackageOps(@NonNull HistoricalPackageOps ops);
        void visitHistoricalOp(@NonNull HistoricalOp ops);
    }

    /**
     * This class represents historical app op state of all UIDs for a given time interval.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public static final class HistoricalOps implements Parcelable {
        private long mBeginTimeMillis;
        private long mEndTimeMillis;
        private @Nullable SparseArray<HistoricalUidOps> mHistoricalUidOps;

        /** @hide */
        @TestApi
        public HistoricalOps(long beginTimeMillis, long endTimeMillis) {
            Preconditions.checkState(beginTimeMillis <= endTimeMillis);
            mBeginTimeMillis = beginTimeMillis;
            mEndTimeMillis = endTimeMillis;
        }

        /** @hide */
        public HistoricalOps(@NonNull HistoricalOps other) {
            mBeginTimeMillis = other.mBeginTimeMillis;
            mEndTimeMillis = other.mEndTimeMillis;
            Preconditions.checkState(mBeginTimeMillis <= mEndTimeMillis);
            if (other.mHistoricalUidOps != null) {
                final int opCount = other.getUidCount();
                for (int i = 0; i < opCount; i++) {
                    final HistoricalUidOps origOps = other.getUidOpsAt(i);
                    final HistoricalUidOps clonedOps = new HistoricalUidOps(origOps);
                    if (mHistoricalUidOps == null) {
                        mHistoricalUidOps = new SparseArray<>(opCount);
                    }
                    mHistoricalUidOps.put(clonedOps.getUid(), clonedOps);
                }
            }
        }

        private HistoricalOps(Parcel parcel) {
            mBeginTimeMillis = parcel.readLong();
            mEndTimeMillis = parcel.readLong();
            final int[] uids = parcel.createIntArray();
            if (!ArrayUtils.isEmpty(uids)) {
                final ParceledListSlice<HistoricalUidOps> listSlice = parcel.readParcelable(
                        HistoricalOps.class.getClassLoader());
                final List<HistoricalUidOps> uidOps = (listSlice != null)
                        ? listSlice.getList() : null;
                if (uidOps == null) {
                    return;
                }
                for (int i = 0; i < uids.length; i++) {
                    if (mHistoricalUidOps == null) {
                        mHistoricalUidOps = new SparseArray<>();
                    }
                    mHistoricalUidOps.put(uids[i], uidOps.get(i));
                }
            }
        }

        /**
         * Splice a piece from the beginning of these ops.
         *
         * @param splicePoint The fraction of the data to be spliced off.
         *
         * @hide
         */
        public @NonNull HistoricalOps spliceFromBeginning(double splicePoint) {
            return splice(splicePoint, true);
        }

        /**
         * Splice a piece from the end of these ops.
         *
         * @param fractionToRemove The fraction of the data to be spliced off.
         *
         * @hide
         */
        public @NonNull HistoricalOps spliceFromEnd(double fractionToRemove) {
            return splice(fractionToRemove, false);
        }

        /**
         * Splice a piece from the beginning or end of these ops.
         *
         * @param fractionToRemove The fraction of the data to be spliced off.
         * @param beginning Whether to splice off the beginning or the end.
         *
         * @return The spliced off part.
         *
         * @hide
         */
        private @Nullable HistoricalOps splice(double fractionToRemove, boolean beginning) {
            final long spliceBeginTimeMills;
            final long spliceEndTimeMills;
            if (beginning) {
                spliceBeginTimeMills = mBeginTimeMillis;
                spliceEndTimeMills = (long) (mBeginTimeMillis
                        + getDurationMillis() * fractionToRemove);
                mBeginTimeMillis = spliceEndTimeMills;
            } else {
                spliceBeginTimeMills = (long) (mEndTimeMillis
                        - getDurationMillis() * fractionToRemove);
                spliceEndTimeMills = mEndTimeMillis;
                mEndTimeMillis = spliceBeginTimeMills;
            }

            HistoricalOps splice = null;
            final int uidCount = getUidCount();
            for (int i = 0; i < uidCount; i++) {
                final HistoricalUidOps origOps = getUidOpsAt(i);
                final HistoricalUidOps spliceOps = origOps.splice(fractionToRemove);
                if (spliceOps != null) {
                    if (splice == null) {
                        splice = new HistoricalOps(spliceBeginTimeMills, spliceEndTimeMills);
                    }
                    if (splice.mHistoricalUidOps == null) {
                        splice.mHistoricalUidOps = new SparseArray<>();
                    }
                    splice.mHistoricalUidOps.put(spliceOps.getUid(), spliceOps);
                }
            }
            return splice;
        }

        /**
         * Merge the passed ops into the current ones. The time interval is a
         * union of the current and passed in one and the passed in data is
         * folded into the data of this instance.
         *
         * @hide
         */
        public void merge(@NonNull HistoricalOps other) {
            mBeginTimeMillis = Math.min(mBeginTimeMillis, other.mBeginTimeMillis);
            mEndTimeMillis = Math.max(mEndTimeMillis, other.mEndTimeMillis);
            final int uidCount = other.getUidCount();
            for (int i = 0; i < uidCount; i++) {
                final HistoricalUidOps otherUidOps = other.getUidOpsAt(i);
                final HistoricalUidOps thisUidOps = getUidOps(otherUidOps.getUid());
                if (thisUidOps != null) {
                    thisUidOps.merge(otherUidOps);
                } else {
                    if (mHistoricalUidOps == null) {
                        mHistoricalUidOps = new SparseArray<>();
                    }
                    mHistoricalUidOps.put(otherUidOps.getUid(), otherUidOps);
                }
            }
        }

        /**
         * AppPermissionUsage the ops to leave only the data we filter for.
         *
         * @param uid Uid to filter for or {@link android.os.Process#INCIDENTD_UID} for all.
         * @param packageName Package to filter for or null for all.
         * @param opNames Ops to filter for or null for all.
         * @param beginTimeMillis The begin time to filter for or {@link Long#MIN_VALUE} for all.
         * @param endTimeMillis The end time to filter for or {@link Long#MAX_VALUE} for all.
         *
         * @hide
         */
        public void filter(int uid, @Nullable String packageName, @Nullable String[] opNames,
                long beginTimeMillis, long endTimeMillis) {
            final long durationMillis = getDurationMillis();
            mBeginTimeMillis = Math.max(mBeginTimeMillis, beginTimeMillis);
            mEndTimeMillis = Math.min(mEndTimeMillis, endTimeMillis);
            final double scaleFactor = Math.min((double) (endTimeMillis - beginTimeMillis)
                    / (double) durationMillis, 1);
            final int uidCount = getUidCount();
            for (int i = uidCount - 1; i >= 0; i--) {
                final HistoricalUidOps uidOp = mHistoricalUidOps.valueAt(i);
                if (uid != Process.INVALID_UID && uid != uidOp.getUid()) {
                    mHistoricalUidOps.removeAt(i);
                } else {
                    uidOp.filter(packageName, opNames, scaleFactor);
                }
            }
        }

        /** @hide */
        public boolean isEmpty() {
            if (getBeginTimeMillis() >= getEndTimeMillis()) {
                return true;
            }
            final int uidCount = getUidCount();
            for (int i = uidCount - 1; i >= 0; i--) {
                final HistoricalUidOps uidOp = mHistoricalUidOps.valueAt(i);
                if (!uidOp.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        /** @hide */
        public long getDurationMillis() {
            return mEndTimeMillis - mBeginTimeMillis;
        }

        /** @hide */
        @TestApi
        public void increaseAccessCount(int opCode, int uid, @NonNull String packageName,
                @UidState int uidState, long increment) {
            getOrCreateHistoricalUidOps(uid).increaseAccessCount(opCode,
                    packageName, uidState, increment);
        }

        /** @hide */
        @TestApi
        public void increaseRejectCount(int opCode, int uid, @NonNull String packageName,
                @UidState int uidState, long increment) {
            getOrCreateHistoricalUidOps(uid).increaseRejectCount(opCode,
                    packageName, uidState, increment);
        }

        /** @hide */
        @TestApi
        public void increaseAccessDuration(int opCode, int uid, @NonNull String packageName,
                @UidState int uidState, long increment) {
            getOrCreateHistoricalUidOps(uid).increaseAccessDuration(opCode,
                    packageName, uidState, increment);
        }

        /** @hide */
        @TestApi
        public void offsetBeginAndEndTime(long offsetMillis) {
            mBeginTimeMillis += offsetMillis;
            mEndTimeMillis += offsetMillis;
        }

        /** @hide */
        public void setBeginAndEndTime(long beginTimeMillis, long endTimeMillis) {
            mBeginTimeMillis = beginTimeMillis;
            mEndTimeMillis = endTimeMillis;
        }

        /** @hide */
        public void setBeginTime(long beginTimeMillis) {
            mBeginTimeMillis = beginTimeMillis;
        }

        /** @hide */
        public void setEndTime(long endTimeMillis) {
            mEndTimeMillis = endTimeMillis;
        }

        /**
         * @return The beginning of the interval in milliseconds since
         *    epoch start (January 1, 1970 00:00:00.000 GMT - Gregorian).
         */
        public long getBeginTimeMillis() {
            return mBeginTimeMillis;
        }

        /**
         * @return The end of the interval in milliseconds since
         *    epoch start (January 1, 1970 00:00:00.000 GMT - Gregorian).
         */
        public long getEndTimeMillis() {
            return mEndTimeMillis;
        }

        /**
         * Gets number of UIDs with historical ops.
         *
         * @return The number of UIDs with historical ops.
         *
         * @see #getUidOpsAt(int)
         */
        public int getUidCount() {
            if (mHistoricalUidOps == null) {
                return 0;
            }
            return mHistoricalUidOps.size();
        }

        /**
         * Gets the historical UID ops at a given index.
         *
         * @param index The index.
         *
         * @return The historical UID ops at the given index.
         *
         * @see #getUidCount()
         */
        public @NonNull HistoricalUidOps getUidOpsAt(int index) {
            if (mHistoricalUidOps == null) {
                throw new IndexOutOfBoundsException();
            }
            return mHistoricalUidOps.valueAt(index);
        }

        /**
         * Gets the historical UID ops for a given UID.
         *
         * @param uid The UID.
         *
         * @return The historical ops for the UID.
         */
        public @Nullable HistoricalUidOps getUidOps(int uid) {
            if (mHistoricalUidOps == null) {
                return null;
            }
            return mHistoricalUidOps.get(uid);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeLong(mBeginTimeMillis);
            parcel.writeLong(mEndTimeMillis);
            if (mHistoricalUidOps != null) {
                final int uidCount = mHistoricalUidOps.size();
                parcel.writeInt(uidCount);
                for (int i = 0; i < uidCount; i++) {
                    parcel.writeInt(mHistoricalUidOps.keyAt(i));
                }
                final List<HistoricalUidOps> opsList = new ArrayList<>(uidCount);
                for (int i = 0; i < uidCount; i++) {
                    opsList.add(mHistoricalUidOps.valueAt(i));
                }
                parcel.writeParcelable(new ParceledListSlice<>(opsList), flags);
            } else {
                parcel.writeInt(-1);
            }
        }

        /**
         * Accepts a visitor to traverse the ops tree.
         *
         * @param visitor The visitor.
         *
         * @hide
         */
        public void accept(@NonNull HistoricalOpsVisitor visitor) {
            visitor.visitHistoricalOps(this);
            final int uidCount = getUidCount();
            for (int i = 0; i < uidCount; i++) {
                getUidOpsAt(i).accept(visitor);
            }
        }

        private @NonNull HistoricalUidOps getOrCreateHistoricalUidOps(int uid) {
            if (mHistoricalUidOps == null) {
                mHistoricalUidOps = new SparseArray<>();
            }
            HistoricalUidOps historicalUidOp = mHistoricalUidOps.get(uid);
            if (historicalUidOp == null) {
                historicalUidOp = new HistoricalUidOps(uid);
                mHistoricalUidOps.put(uid, historicalUidOp);
            }
            return historicalUidOp;
        }

        /**
         * @return Rounded value up at the 0.5 boundary.
         *
         * @hide
         */
        public static double round(double value) {
            final BigDecimal decimalScale = new BigDecimal(value);
            return decimalScale.setScale(0, RoundingMode.HALF_UP).doubleValue();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final HistoricalOps other = (HistoricalOps) obj;
            if (mBeginTimeMillis != other.mBeginTimeMillis) {
                return false;
            }
            if (mEndTimeMillis != other.mEndTimeMillis) {
                return false;
            }
            if (mHistoricalUidOps == null) {
                if (other.mHistoricalUidOps != null) {
                    return false;
                }
            } else if (!mHistoricalUidOps.equals(other.mHistoricalUidOps)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = (int) (mBeginTimeMillis ^ (mBeginTimeMillis >>> 32));
            result = 31 * result + mHistoricalUidOps.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "[from:"
                    + mBeginTimeMillis + " to:" + mEndTimeMillis + "]";
        }

        public static final Creator<HistoricalOps> CREATOR = new Creator<HistoricalOps>() {
            @Override
            public @NonNull HistoricalOps createFromParcel(@NonNull Parcel parcel) {
                return new HistoricalOps(parcel);
            }

            @Override
            public @NonNull HistoricalOps[] newArray(int size) {
                return new HistoricalOps[size];
            }
        };
    }

    /**
     * This class represents historical app op state for a UID.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public static final class HistoricalUidOps implements Parcelable {
        private final int mUid;
        private @Nullable ArrayMap<String, HistoricalPackageOps> mHistoricalPackageOps;

        /** @hide */
        public HistoricalUidOps(int uid) {
            mUid = uid;
        }

        private HistoricalUidOps(@NonNull HistoricalUidOps other) {
            mUid = other.mUid;
            final int opCount = other.getPackageCount();
            for (int i = 0; i < opCount; i++) {
                final HistoricalPackageOps origOps = other.getPackageOpsAt(i);
                final HistoricalPackageOps cloneOps = new HistoricalPackageOps(origOps);
                if (mHistoricalPackageOps == null) {
                    mHistoricalPackageOps = new ArrayMap<>(opCount);
                }
                mHistoricalPackageOps.put(cloneOps.getPackageName(), cloneOps);
            }
        }

        private HistoricalUidOps(@NonNull Parcel parcel) {
            // No arg check since we always read from a trusted source.
            mUid = parcel.readInt();
            mHistoricalPackageOps = parcel.createTypedArrayMap(HistoricalPackageOps.CREATOR);
        }

        private @Nullable HistoricalUidOps splice(double fractionToRemove) {
            HistoricalUidOps splice = null;
            final int packageCount = getPackageCount();
            for (int i = 0; i < packageCount; i++) {
                final HistoricalPackageOps origOps = getPackageOpsAt(i);
                final HistoricalPackageOps spliceOps = origOps.splice(fractionToRemove);
                if (spliceOps != null) {
                    if (splice == null) {
                        splice = new HistoricalUidOps(mUid);
                    }
                    if (splice.mHistoricalPackageOps == null) {
                        splice.mHistoricalPackageOps = new ArrayMap<>();
                    }
                    splice.mHistoricalPackageOps.put(spliceOps.getPackageName(), spliceOps);
                }
            }
            return splice;
        }

        private void merge(@NonNull HistoricalUidOps other) {
            final int packageCount = other.getPackageCount();
            for (int i = 0; i < packageCount; i++) {
                final HistoricalPackageOps otherPackageOps = other.getPackageOpsAt(i);
                final HistoricalPackageOps thisPackageOps = getPackageOps(
                        otherPackageOps.getPackageName());
                if (thisPackageOps != null) {
                    thisPackageOps.merge(otherPackageOps);
                } else {
                    if (mHistoricalPackageOps == null) {
                        mHistoricalPackageOps = new ArrayMap<>();
                    }
                    mHistoricalPackageOps.put(otherPackageOps.getPackageName(), otherPackageOps);
                }
            }
        }

        private void filter(@Nullable String packageName, @Nullable String[] opNames,
                double fractionToRemove) {
            final int packageCount = getPackageCount();
            for (int i = packageCount - 1; i >= 0; i--) {
                final HistoricalPackageOps packageOps = getPackageOpsAt(i);
                if (packageName != null && !packageName.equals(packageOps.getPackageName())) {
                    mHistoricalPackageOps.removeAt(i);
                } else {
                    packageOps.filter(opNames, fractionToRemove);
                }
            }
        }

        private boolean isEmpty() {
            final int packageCount = getPackageCount();
            for (int i = packageCount - 1; i >= 0; i--) {
                final HistoricalPackageOps packageOps = mHistoricalPackageOps.valueAt(i);
                if (!packageOps.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private void increaseAccessCount(int opCode, @NonNull String packageName,
                @UidState int uidState, long increment) {
            getOrCreateHistoricalPackageOps(packageName).increaseAccessCount(
                    opCode, uidState, increment);
        }

        private void increaseRejectCount(int opCode, @NonNull String packageName,
                @UidState int uidState, long increment) {
            getOrCreateHistoricalPackageOps(packageName).increaseRejectCount(
                    opCode, uidState, increment);
        }

        private void increaseAccessDuration(int opCode, @NonNull String packageName,
                @UidState int uidState, long increment) {
            getOrCreateHistoricalPackageOps(packageName).increaseAccessDuration(
                    opCode, uidState, increment);
        }

        /**
         * @return The UID for which the data is related.
         */
        public int getUid() {
            return mUid;
        }

        /**
         * Gets number of packages with historical ops.
         *
         * @return The number of packages with historical ops.
         *
         * @see #getPackageOpsAt(int)
         */
        public int getPackageCount() {
            if (mHistoricalPackageOps == null) {
                return 0;
            }
            return mHistoricalPackageOps.size();
        }

        /**
         * Gets the historical package ops at a given index.
         *
         * @param index The index.
         *
         * @return The historical package ops at the given index.
         *
         * @see #getPackageCount()
         */
        public @NonNull HistoricalPackageOps getPackageOpsAt(int index) {
            if (mHistoricalPackageOps == null) {
                throw new IndexOutOfBoundsException();
            }
            return mHistoricalPackageOps.valueAt(index);
        }

        /**
         * Gets the historical package ops for a given package.
         *
         * @param packageName The package.
         *
         * @return The historical ops for the package.
         */
        public @Nullable HistoricalPackageOps getPackageOps(@NonNull String packageName) {
            if (mHistoricalPackageOps == null) {
                return null;
            }
            return mHistoricalPackageOps.get(packageName);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeInt(mUid);
            parcel.writeTypedArrayMap(mHistoricalPackageOps, flags);
        }

        private void accept(@NonNull HistoricalOpsVisitor visitor) {
            visitor.visitHistoricalUidOps(this);
            final int packageCount = getPackageCount();
            for (int i = 0; i < packageCount; i++) {
                getPackageOpsAt(i).accept(visitor);
            }
        }

        private @NonNull HistoricalPackageOps getOrCreateHistoricalPackageOps(
                @NonNull String packageName) {
            if (mHistoricalPackageOps == null) {
                mHistoricalPackageOps = new ArrayMap<>();
            }
            HistoricalPackageOps historicalPackageOp = mHistoricalPackageOps.get(packageName);
            if (historicalPackageOp == null) {
                historicalPackageOp = new HistoricalPackageOps(packageName);
                mHistoricalPackageOps.put(packageName, historicalPackageOp);
            }
            return historicalPackageOp;
        }


        public static final Creator<HistoricalUidOps> CREATOR = new Creator<HistoricalUidOps>() {
            @Override
            public @NonNull HistoricalUidOps createFromParcel(@NonNull Parcel parcel) {
                return new HistoricalUidOps(parcel);
            }

            @Override
            public @NonNull HistoricalUidOps[] newArray(int size) {
                return new HistoricalUidOps[size];
            }
        };

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final HistoricalUidOps other = (HistoricalUidOps) obj;
            if (mUid != other.mUid) {
                return false;
            }
            if (mHistoricalPackageOps == null) {
                if (other.mHistoricalPackageOps != null) {
                    return false;
                }
            } else if (!mHistoricalPackageOps.equals(other.mHistoricalPackageOps)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = mUid;
            result = 31 * result + (mHistoricalPackageOps != null
                    ? mHistoricalPackageOps.hashCode() : 0);
            return result;
        }
    }

    /**
     * This class represents historical app op information about a package.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public static final class HistoricalPackageOps implements Parcelable {
        private final @NonNull String mPackageName;
        private @Nullable ArrayMap<String, HistoricalOp> mHistoricalOps;

        /** @hide */
        public HistoricalPackageOps(@NonNull String packageName) {
            mPackageName = packageName;
        }

        private HistoricalPackageOps(@NonNull HistoricalPackageOps other) {
            mPackageName = other.mPackageName;
            final int opCount = other.getOpCount();
            for (int i = 0; i < opCount; i++) {
                final HistoricalOp origOp = other.getOpAt(i);
                final HistoricalOp cloneOp = new HistoricalOp(origOp);
                if (mHistoricalOps == null) {
                    mHistoricalOps = new ArrayMap<>(opCount);
                }
                mHistoricalOps.put(cloneOp.getOpName(), cloneOp);
            }
        }

        private HistoricalPackageOps(@NonNull Parcel parcel) {
            mPackageName = parcel.readString();
            mHistoricalOps = parcel.createTypedArrayMap(HistoricalOp.CREATOR);
        }

        private @Nullable HistoricalPackageOps splice(double fractionToRemove) {
            HistoricalPackageOps splice = null;
            final int opCount = getOpCount();
            for (int i = 0; i < opCount; i++) {
                final HistoricalOp origOps = getOpAt(i);
                final HistoricalOp spliceOps = origOps.splice(fractionToRemove);
                if (spliceOps != null) {
                    if (splice == null) {
                        splice = new HistoricalPackageOps(mPackageName);
                    }
                    if (splice.mHistoricalOps == null) {
                        splice.mHistoricalOps = new ArrayMap<>();
                    }
                    splice.mHistoricalOps.put(spliceOps.getOpName(), spliceOps);
                }
            }
            return splice;
        }

        private void merge(@NonNull HistoricalPackageOps other) {
            final int opCount = other.getOpCount();
            for (int i = 0; i < opCount; i++) {
                final HistoricalOp otherOp = other.getOpAt(i);
                final HistoricalOp thisOp = getOp(otherOp.getOpName());
                if (thisOp != null) {
                    thisOp.merge(otherOp);
                } else {
                    if (mHistoricalOps == null) {
                        mHistoricalOps = new ArrayMap<>();
                    }
                    mHistoricalOps.put(otherOp.getOpName(), otherOp);
                }
            }
        }

        private void filter(@Nullable String[] opNames, double scaleFactor) {
            final int opCount = getOpCount();
            for (int i = opCount - 1; i >= 0; i--) {
                final HistoricalOp op = mHistoricalOps.valueAt(i);
                if (opNames != null && !ArrayUtils.contains(opNames, op.getOpName())) {
                    mHistoricalOps.removeAt(i);
                } else {
                    op.filter(scaleFactor);
                }
            }
        }

        private boolean isEmpty() {
            final int opCount = getOpCount();
            for (int i = opCount - 1; i >= 0; i--) {
                final HistoricalOp op = mHistoricalOps.valueAt(i);
                if (!op.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private void increaseAccessCount(int opCode, @UidState int uidState, long increment) {
            getOrCreateHistoricalOp(opCode).increaseAccessCount(uidState, increment);
        }

        private void increaseRejectCount(int opCode, @UidState int uidState, long increment) {
            getOrCreateHistoricalOp(opCode).increaseRejectCount(uidState, increment);
        }

        private void increaseAccessDuration(int opCode, @UidState int uidState, long increment) {
            getOrCreateHistoricalOp(opCode).increaseAccessDuration(uidState, increment);
        }

        /**
         * Gets the package name which the data represents.
         *
         * @return The package name which the data represents.
         */
        public @NonNull String getPackageName() {
            return mPackageName;
        }

        /**
         * Gets number historical app ops.
         *
         * @return The number historical app ops.
         *
         * @see #getOpAt(int)
         */
        public int getOpCount() {
            if (mHistoricalOps == null) {
                return 0;
            }
            return mHistoricalOps.size();
        }

        /**
         * Gets the historical op at a given index.
         *
         * @param index The index to lookup.
         *
         * @return The op at the given index.
         *
         * @see #getOpCount()
         */
        public @NonNull HistoricalOp getOpAt(int index) {
            if (mHistoricalOps == null) {
                throw new IndexOutOfBoundsException();
            }
            return mHistoricalOps.valueAt(index);
        }

        /**
         * Gets the historical entry for a given op name.
         *
         * @param opName The op name.
         *
         * @return The historical entry for that op name.
         */
        public @Nullable HistoricalOp getOp(@NonNull String opName) {
            if (mHistoricalOps == null) {
                return null;
            }
            return mHistoricalOps.get(opName);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeString(mPackageName);
            parcel.writeTypedArrayMap(mHistoricalOps, flags);
        }

        private void accept(@NonNull HistoricalOpsVisitor visitor) {
            visitor.visitHistoricalPackageOps(this);
            final int opCount = getOpCount();
            for (int i = 0; i < opCount; i++) {
                getOpAt(i).accept(visitor);
            }
        }

        private @NonNull HistoricalOp getOrCreateHistoricalOp(int opCode) {
            if (mHistoricalOps == null) {
                mHistoricalOps = new ArrayMap<>();
            }
            final String opStr = sOpToString[opCode];
            HistoricalOp op = mHistoricalOps.get(opStr);
            if (op == null) {
                op = new HistoricalOp(opCode);
                mHistoricalOps.put(opStr, op);
            }
            return op;
        }

        public static final Creator<HistoricalPackageOps> CREATOR =
                new Creator<HistoricalPackageOps>() {
            @Override
            public @NonNull HistoricalPackageOps createFromParcel(@NonNull Parcel parcel) {
                return new HistoricalPackageOps(parcel);
            }

            @Override
            public @NonNull HistoricalPackageOps[] newArray(int size) {
                return new HistoricalPackageOps[size];
            }
        };

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final HistoricalPackageOps other = (HistoricalPackageOps) obj;
            if (!mPackageName.equals(other.mPackageName)) {
                return false;
            }
            if (mHistoricalOps == null) {
                if (other.mHistoricalOps != null) {
                    return false;
                }
            } else if (!mHistoricalOps.equals(other.mHistoricalOps)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = mPackageName != null ? mPackageName.hashCode() : 0;
            result = 31 * result + (mHistoricalOps != null ? mHistoricalOps.hashCode() : 0);
            return result;
        }
    }

    /**
     * This class represents historical information about an app op.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public static final class HistoricalOp implements Parcelable {
        private final int mOp;
        private @Nullable long[] mAccessCount;
        private @Nullable long[] mRejectCount;
        private @Nullable long[] mAccessDuration;

        /** @hide */
        public HistoricalOp(int op) {
            mOp = op;
            mAccessCount = new long[_NUM_UID_STATE];
            mRejectCount = new long[_NUM_UID_STATE];
            mAccessDuration = new long[_NUM_UID_STATE];
        }

        private HistoricalOp(@NonNull HistoricalOp other) {
            mOp = other.mOp;
            if (other.mAccessCount != null) {
                System.arraycopy(other.mAccessCount, 0, getOrCreateAccessCount(),
                        0, other.mAccessCount.length);
            }
            if (other.mRejectCount != null) {
                System.arraycopy(other.mRejectCount, 0, getOrCreateRejectCount(),
                        0, other.mRejectCount.length);
            }
            if (other.mAccessDuration != null) {
                System.arraycopy(other.mAccessDuration, 0, getOrCreateAccessDuration(),
                        0, other.mAccessDuration.length);
            }
        }

        private HistoricalOp(@NonNull Parcel parcel) {
            mOp = parcel.readInt();
            mAccessCount = parcel.createLongArray();
            mRejectCount = parcel.createLongArray();
            mAccessDuration = parcel.createLongArray();
        }

        private void filter(double scaleFactor) {
            scale(mAccessCount, scaleFactor);
            scale(mRejectCount, scaleFactor);
            scale(mAccessDuration, scaleFactor);
        }

        private boolean isEmpty() {
            return !hasData(mAccessCount)
                    && !hasData(mRejectCount)
                    && !hasData(mAccessDuration);
        }

        private boolean hasData(@NonNull long[] array) {
            for (long value : array) {
                if (value != 0) {
                    return true;
                }
            }
            return false;
        }

        private @Nullable HistoricalOp splice(double fractionToRemove) {
            HistoricalOp splice = null;
            if (mAccessCount != null) {
                for (int i = 0; i < _NUM_UID_STATE; i++) {
                    final long spliceAccessCount = Math.round(
                            mAccessCount[i] * fractionToRemove);
                    if (spliceAccessCount > 0) {
                        if (splice == null) {
                            splice = new HistoricalOp(mOp);
                        }
                        splice.getOrCreateAccessCount()[i] = spliceAccessCount;
                        mAccessCount[i] -= spliceAccessCount;
                    }
                }
            }

            if (mRejectCount != null) {
                for (int i = 0; i < _NUM_UID_STATE; i++) {
                    final long spliceRejectCount = Math.round(
                            mRejectCount[i] * fractionToRemove);

                    if (spliceRejectCount > 0) {
                        if (splice == null) {
                            splice = new HistoricalOp(mOp);
                        }
                        splice.getOrCreateRejectCount()[i] = spliceRejectCount;
                        mRejectCount[i] -= spliceRejectCount;
                    }
                }
            }

            if (mAccessDuration != null) {
                for (int i = 0; i < _NUM_UID_STATE; i++) {
                    final long spliceAccessDuration =  Math.round(
                            mAccessDuration[i] * fractionToRemove);
                    if (spliceAccessDuration > 0) {
                        if (splice == null) {
                            splice = new HistoricalOp(mOp);
                        }
                        splice.getOrCreateAccessDuration()[i] = spliceAccessDuration;
                        mAccessDuration[i] -= spliceAccessDuration;
                    }
                }
            }
            return splice;
        }

        private void merge(@NonNull HistoricalOp other) {
            if (other.mAccessCount != null) {
                for (int i = 0; i < _NUM_UID_STATE; i++) {
                    getOrCreateAccessCount()[i] += other.mAccessCount[i];
                }
            }
            if (other.mRejectCount != null) {
                for (int i = 0; i < _NUM_UID_STATE; i++) {
                    getOrCreateRejectCount()[i] += other.mRejectCount[i];
                }
            }
            if (other.mAccessDuration != null) {
                for (int i = 0; i < _NUM_UID_STATE; i++) {
                    getOrCreateAccessDuration()[i] += other.mAccessDuration[i];
                }
            }
        }

        private void increaseAccessCount(@UidState int uidState, long increment) {
            getOrCreateAccessCount()[uidState] += increment;
        }

        private void increaseRejectCount(@UidState int uidState, long increment) {
            getOrCreateRejectCount()[uidState] += increment;
        }

        private void increaseAccessDuration(@UidState int uidState, long increment) {
            getOrCreateAccessDuration()[uidState] += increment;
        }

        /**
         * Gets the op name.
         *
         * @return The op name.
         */
        public @NonNull String getOpName() {
            return sOpToString[mOp];
        }

        /** @hide */
        public int getOpCode() {
            return mOp;
        }

        /**
         * Gets the number times the op was accessed (performed) in the foreground.
         *
         * @return The times the op was accessed in the foreground.
         *
         * @see #getBackgroundAccessCount()
         * @see #getAccessCount(int)
         */
        public long getForegroundAccessCount() {
            if (mAccessCount == null) {
                return 0;
            }
            return sum(mAccessCount, UID_STATE_PERSISTENT, UID_STATE_LAST_NON_RESTRICTED + 1);
        }

        /**
         * Gets the number times the op was accessed (performed) in the background.
         *
         * @return The times the op was accessed in the background.
         *
         * @see #getForegroundAccessCount()
         * @see #getAccessCount(int)
         */
        public long getBackgroundAccessCount() {
            if (mAccessCount == null) {
                return 0;
            }
            return sum(mAccessCount, UID_STATE_LAST_NON_RESTRICTED + 1, _NUM_UID_STATE);
        }

        /**
         * Gets the number times the op was accessed (performed) for a given uid state.
         *
         * @param uidState The UID state for which to query. Could be one of
         * {@link #UID_STATE_PERSISTENT}, {@link #UID_STATE_TOP},
         * {@link #UID_STATE_FOREGROUND_SERVICE}, {@link #UID_STATE_FOREGROUND},
         * {@link #UID_STATE_BACKGROUND}, {@link #UID_STATE_CACHED}.
         *
         * @return The times the op was accessed for the given UID state.
         *
         * @see #getForegroundAccessCount()
         * @see #getBackgroundAccessCount()
         */
        public long getAccessCount(@UidState int uidState) {
            if (mAccessCount == null) {
                return 0;
            }
            return mAccessCount[uidState];
        }

        /**
         * Gets the number times the op was rejected in the foreground.
         *
         * @return The times the op was rejected in the foreground.
         *
         * @see #getBackgroundRejectCount()
         * @see #getRejectCount(int)
         */
        public long getForegroundRejectCount() {
            if (mRejectCount == null) {
                return 0;
            }
            return sum(mRejectCount, UID_STATE_PERSISTENT, UID_STATE_LAST_NON_RESTRICTED + 1);
        }

        /**
         * Gets the number times the op was rejected in the background.
         *
         * @return The times the op was rejected in the background.
         *
         * @see #getForegroundRejectCount()
         * @see #getRejectCount(int)
         */
        public long getBackgroundRejectCount() {
            if (mRejectCount == null) {
                return 0;
            }
            return sum(mRejectCount, UID_STATE_LAST_NON_RESTRICTED + 1, _NUM_UID_STATE);
        }

        /**
         * Gets the number times the op was rejected for a given uid state.
         *
         * @param uidState The UID state for which to query. Could be one of
         * {@link #UID_STATE_PERSISTENT}, {@link #UID_STATE_TOP},
         * {@link #UID_STATE_FOREGROUND_SERVICE}, {@link #UID_STATE_FOREGROUND},
         * {@link #UID_STATE_BACKGROUND}, {@link #UID_STATE_CACHED}.
         *
         * @return The times the op was rejected for the given UID state.
         *
         * @see #getForegroundRejectCount()
         * @see #getBackgroundRejectCount()
         */
        public long getRejectCount(@UidState int uidState) {
            if (mRejectCount == null) {
                return 0;
            }
            return mRejectCount[uidState];
        }

        /**
         * Gets the total duration the app op was accessed (performed) in the foreground.
         *
         * @return The total duration the app op was accessed in the foreground.
         *
         * @see #getBackgroundAccessDuration()
         * @see #getAccessDuration(int)
         */
        public long getForegroundAccessDuration() {
            if (mAccessDuration == null) {
                return 0;
            }
            return sum(mAccessDuration, UID_STATE_PERSISTENT, UID_STATE_LAST_NON_RESTRICTED + 1);
        }

        /**
         * Gets the total duration the app op was accessed (performed) in the background.
         *
         * @return The total duration the app op was accessed in the background.
         *
         * @see #getForegroundAccessDuration()
         * @see #getAccessDuration(int)
         */
        public long getBackgroundAccessDuration() {
            if (mAccessDuration == null) {
                return 0;
            }
            return sum(mAccessDuration, UID_STATE_LAST_NON_RESTRICTED + 1, _NUM_UID_STATE);
        }

        /**
         * Gets the total duration the app op was accessed (performed) for a given UID state.
         *
         * @param uidState The UID state for which to query. Could be one of
         * {@link #UID_STATE_PERSISTENT}, {@link #UID_STATE_TOP},
         * {@link #UID_STATE_FOREGROUND_SERVICE}, {@link #UID_STATE_FOREGROUND},
         * {@link #UID_STATE_BACKGROUND}, {@link #UID_STATE_CACHED}.
         *
         * @return The total duration the app op was accessed for the given UID state.
         *
         * @see #getForegroundAccessDuration()
         * @see #getBackgroundAccessDuration()
         */
        public long getAccessDuration(@UidState int uidState) {
            if (mAccessDuration == null) {
                return 0;
            }
            return mAccessDuration[uidState];
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeInt(mOp);
            parcel.writeLongArray(mAccessCount);
            parcel.writeLongArray(mRejectCount);
            parcel.writeLongArray(mAccessDuration);
        }

        private void accept(@NonNull HistoricalOpsVisitor visitor) {
            visitor.visitHistoricalOp(this);
        }

        private @NonNull long[] getOrCreateAccessCount() {
            if (mAccessCount == null) {
                mAccessCount = new long[_NUM_UID_STATE];
            }
            return mAccessCount;
        }

        private @NonNull long[] getOrCreateRejectCount() {
            if (mRejectCount == null) {
                mRejectCount = new long[_NUM_UID_STATE];
            }
            return mRejectCount;
        }

        private @NonNull long[] getOrCreateAccessDuration() {
            if (mAccessDuration == null) {
                mAccessDuration = new long[_NUM_UID_STATE];
            }
            return mAccessDuration;
        }

        /**
         *
         * Computes the sum given the start and end index.
         *
         * @param counts The data array.
         * @param start The start index (inclusive)
         * @param end The end index (exclusive)
         * @return The sum.
         */
        private static long sum(@NonNull long[] counts, int start, int end) {
            long totalCount = 0;
            for (int i = start; i < end; i++) {
                totalCount += counts[i];
            }
            return totalCount;
        }

        /**
         * Multiplies the entries in the array with the passed in scale factor and
         * rounds the result at up 0.5 boundary.
         *
         * @param data The data to scale.
         * @param scaleFactor The scale factor.
         */
        private static void scale(@NonNull long[] data, double scaleFactor) {
            if (data != null) {
                for (int i = 0; i < _NUM_UID_STATE; i++) {
                    data[i] = (long) HistoricalOps.round((double) data[i] * scaleFactor);
                }
            }
        }

        public static final Creator<HistoricalOp> CREATOR = new Creator<HistoricalOp>() {
            @Override
            public @NonNull HistoricalOp createFromParcel(@NonNull Parcel source) {
                return new HistoricalOp(source);
            }

            @Override
            public @NonNull HistoricalOp[] newArray(int size) {
                return new HistoricalOp[size];
            }
        };

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final HistoricalOp other = (HistoricalOp) obj;
            if (mOp != other.mOp) {
                return false;
            }
            if (!Arrays.equals(mAccessCount, other.mAccessCount)) {
                return false;
            }
            if (!Arrays.equals(mRejectCount, other.mRejectCount)) {
                return false;
            }
            return Arrays.equals(mAccessDuration, other.mAccessDuration);
        }

        @Override
        public int hashCode() {
            int result = mOp;
            result = 31 * result + Arrays.hashCode(mAccessCount);
            result = 31 * result + Arrays.hashCode(mRejectCount);
            result = 31 * result + Arrays.hashCode(mAccessDuration);
            return result;
        }
    }

    /**
     * Callback for notification of changes to operation state.
     */
    public interface OnOpChangedListener {
        public void onOpChanged(String op, String packageName);
    }

    /**
     * Callback for notification of changes to operation active state.
     *
     * @hide
     */
    @TestApi
    public interface OnOpActiveChangedListener {
        /**
         * Called when the active state of an app op changes.
         *
         * @param code The op code.
         * @param uid The UID performing the operation.
         * @param packageName The package performing the operation.
         * @param active Whether the operation became active or inactive.
         */
        void onOpActiveChanged(int code, int uid, String packageName, boolean active);
    }

    /**
     * Callback for notification of an op being noted.
     *
     * @hide
     */
    public interface OnOpNotedListener {
        /**
         * Called when an op was noted.
         *
         * @param code The op code.
         * @param uid The UID performing the operation.
         * @param packageName The package performing the operation.
         * @param result The result of the note.
         */
        void onOpNoted(int code, int uid, String packageName, int result);
    }

    /**
     * Callback for notification of changes to operation state.
     * This allows you to see the raw op codes instead of strings.
     * @hide
     */
    public static class OnOpChangedInternalListener implements OnOpChangedListener {
        public void onOpChanged(String op, String packageName) { }
        public void onOpChanged(int op, String packageName) { }
    }

    AppOpsManager(Context context, IAppOpsService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Retrieve current operation state for all applications.
     *
     * @param ops The set of operations you are interested in, or null if you want all of them.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GET_APP_OPS_STATS)
    public @NonNull List<AppOpsManager.PackageOps> getPackagesForOps(@Nullable String[] ops) {
        final int opCount = ops.length;
        final int[] opCodes = new int[opCount];
        for (int i = 0; i < opCount; i++) {
            opCodes[i] = sOpStrToOp.get(ops[i]);
        }
        final List<AppOpsManager.PackageOps> result = getPackagesForOps(opCodes);
        return (result != null) ? result : Collections.emptyList();
    }

    /**
     * Retrieve current operation state for all applications.
     *
     * @param ops The set of operations you are interested in, or null if you want all of them.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.GET_APP_OPS_STATS)
    @UnsupportedAppUsage
    public List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops) {
        try {
            return mService.getPackagesForOps(ops);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve current operation state for one application.
     *
     * @param uid The uid of the application of interest.
     * @param packageName The name of the application of interest.
     * @param ops The set of operations you are interested in, or null if you want all of them.
     *
     * @deprecated The int op codes are not stable and you should use the string based op
     * names which are stable and namespaced. Use
     * {@link #getOpsForPackage(int, String, String...)})}.
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GET_APP_OPS_STATS)
    public List<PackageOps> getOpsForPackage(int uid, String packageName, int[] ops) {
        try {
            return mService.getOpsForPackage(uid, packageName, ops);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve current operation state for one application. The UID and the
     * package must match.
     *
     * @param uid The uid of the application of interest.
     * @param packageName The name of the application of interest.
     * @param ops The set of operations you are interested in, or null if you want all of them.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GET_APP_OPS_STATS)
    public @NonNull List<AppOpsManager.PackageOps> getOpsForPackage(int uid,
            @NonNull String packageName, @Nullable String... ops) {
        int[] opCodes = null;
        if (ops != null) {
            opCodes = new int[ops.length];
            for (int i = 0; i < ops.length; i++) {
                opCodes[i] = strOpToOp(ops[i]);
            }
        }
        try {
            final List<PackageOps> result = mService.getOpsForPackage(uid, packageName, opCodes);
            if (result == null) {
                return Collections.emptyList();
            }
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve historical app op stats for a period.
     *
     * <p>Historical data can be obtained
     * for a specific package by specifying the <code>packageName</code> argument,
     * for a specific UID if specifying the <code>uid</code> argument, for a
     * specific package in a UID by specifying the <code>packageName</code>
     * and the <code>uid</code> arguments, for all packages by passing
     * {@link android.os.Process#INVALID_UID} and <code>null</code> for the
     *  <code>uid</code> and <code>packageName</code> arguments, respectively.
     *  Similarly, you can specify the <code>opNames</code> argument to get
     *  data only for these ops or <code>null</code> for all ops.
     *
     * @param uid The UID to query for.
     * @param packageName The package to query for.
     * @param beginTimeMillis The beginning of the interval in milliseconds since
     *     epoch start (January 1, 1970 00:00:00.000 GMT - Gregorian). Must be non
     *     negative.
     * @param endTimeMillis The end of the interval in milliseconds since
     *     epoch start (January 1, 1970 00:00:00.000 GMT - Gregorian). Must be after
     *     {@code beginTimeMillis}. Pass {@link Long#MAX_VALUE} to get the most recent
     *     history including ops that happen while this call is in flight.
     * @param opNames The ops to query for. Pass {@code null} for all ops.
     * @param executor Executor on which to run the callback. If <code>null</code>
     *     the callback is executed on the default executor running on the main thread.
     * @param callback Callback on which to deliver the result.
     *
     * @throws IllegalArgumentException If any of the argument contracts is violated.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GET_APP_OPS_STATS)
    public void getHistoricalOps(int uid, @Nullable String packageName,
            @Nullable String[] opNames, long beginTimeMillis, long endTimeMillis,
            @NonNull Executor executor, @NonNull Consumer<HistoricalOps> callback) {
        Preconditions.checkNotNull(executor, "executor cannot be null");
        Preconditions.checkNotNull(callback, "callback cannot be null");
        try {
            mService.getHistoricalOps(uid, packageName, opNames, beginTimeMillis, endTimeMillis,
                    new RemoteCallback((result) -> {
                final HistoricalOps ops = result.getParcelable(KEY_HISTORICAL_OPS);
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> callback.accept(ops));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve historical app op stats for a period.
     *
     * <p>Historical data can be obtained
     * for a specific package by specifying the <code>packageName</code> argument,
     * for a specific UID if specifying the <code>uid</code> argument, for a
     * specific package in a UID by specifying the <code>packageName</code>
     * and the <code>uid</code> arguments, for all packages by passing
     * {@link android.os.Process#INVALID_UID} and <code>null</code> for the
     *  <code>uid</code> and <code>packageName</code> arguments, respectively.
     *  Similarly, you can specify the <code>opNames</code> argument to get
     *  data only for these ops or <code>null</code> for all ops.
     *  <p>
     *  This method queries only the on disk state and the returned ops are raw,
     *  which is their times are relative to the history start as opposed to the
     *  epoch start.
     *
     * @param uid The UID to query for.
     * @param packageName The package to query for.
     * @param beginTimeMillis The beginning of the interval in milliseconds since
     *      history start. History time grows as one goes into the past.
     * @param endTimeMillis The end of the interval in milliseconds since
     *      history start. History time grows as one goes into the past. Must be after
     *     {@code beginTimeMillis}.
     * @param opNames The ops to query for. Pass {@code null} for all ops.
     * @param executor Executor on which to run the callback. If <code>null</code>
     *     the callback is executed on the default executor running on the main thread.
     * @param callback Callback on which to deliver the result.
     *
     * @throws IllegalArgumentException If any of the argument contracts is violated.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.GET_APP_OPS_STATS)
    public void getHistoricalOpsFromDiskRaw(int uid, @Nullable String packageName,
            @Nullable String[] opNames, long beginTimeMillis, long endTimeMillis,
            @Nullable Executor executor, @NonNull Consumer<HistoricalOps> callback) {
        Preconditions.checkNotNull(executor, "executor cannot be null");
        Preconditions.checkNotNull(callback, "callback cannot be null");
        try {
            mService.getHistoricalOpsFromDiskRaw(uid, packageName, opNames, beginTimeMillis,
                    endTimeMillis, new RemoteCallback((result) -> {
               final HistoricalOps ops = result.getParcelable(KEY_HISTORICAL_OPS);
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> callback.accept(ops));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets given app op in the specified mode for app ops in the UID.
     * This applies to all apps currently in the UID or installed in
     * this UID in the future.
     *
     * @param code The app op.
     * @param uid The UID for which to set the app.
     * @param mode The app op mode to set.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_APP_OPS_MODES)
    public void setUidMode(int code, int uid, @Mode int mode) {
        try {
            mService.setUidMode(code, uid, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets given app op in the specified mode for app ops in the UID.
     * This applies to all apps currently in the UID or installed in
     * this UID in the future.
     *
     * @param appOp The app op.
     * @param uid The UID for which to set the app.
     * @param mode The app op mode to set.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_APP_OPS_MODES)
    public void setUidMode(String appOp, int uid, @Mode int mode) {
        try {
            mService.setUidMode(AppOpsManager.strOpToOp(appOp), uid, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setUserRestriction(int code, boolean restricted, IBinder token) {
        setUserRestriction(code, restricted, token, /*exceptionPackages*/null);
    }

    /** @hide */
    public void setUserRestriction(int code, boolean restricted, IBinder token,
            String[] exceptionPackages) {
        setUserRestrictionForUser(code, restricted, token, exceptionPackages, mContext.getUserId());
    }

    /** @hide */
    public void setUserRestrictionForUser(int code, boolean restricted, IBinder token,
            String[] exceptionPackages, int userId) {
        try {
            mService.setUserRestriction(code, restricted, token, userId, exceptionPackages);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_APP_OPS_MODES)
    public void setMode(int code, int uid, String packageName, @Mode int mode) {
        try {
            mService.setMode(code, uid, packageName, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Change the operating mode for the given op in the given app package.  You must pass
     * in both the uid and name of the application whose mode is being modified; if these
     * do not match, the modification will not be applied.
     *
     * @param op The operation to modify.  One of the OPSTR_* constants.
     * @param uid The user id of the application whose mode will be changed.
     * @param packageName The name of the application package name whose mode will
     * be changed.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_APP_OPS_MODES)
    public void setMode(String op, int uid, String packageName, @Mode int mode) {
        try {
            mService.setMode(strOpToOp(op), uid, packageName, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set a non-persisted restriction on an audio operation at a stream-level.
     * Restrictions are temporary additional constraints imposed on top of the persisted rules
     * defined by {@link #setMode}.
     *
     * @param code The operation to restrict.
     * @param usage The {@link android.media.AudioAttributes} usage value.
     * @param mode The restriction mode (MODE_IGNORED,MODE_ERRORED) or MODE_ALLOWED to unrestrict.
     * @param exceptionPackages Optional list of packages to exclude from the restriction.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_APP_OPS_MODES)
    @UnsupportedAppUsage
    public void setRestriction(int code, @AttributeUsage int usage, @Mode int mode,
            String[] exceptionPackages) {
        try {
            final int uid = Binder.getCallingUid();
            mService.setAudioRestriction(code, usage, uid, mode, exceptionPackages);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @RequiresPermission(android.Manifest.permission.MANAGE_APP_OPS_MODES)
    @UnsupportedAppUsage
    public void resetAllModes() {
        try {
            mService.resetAllModes(mContext.getUserId(), null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the app op name associated with a given permission.
     * The app op name is one of the public constants defined
     * in this class such as {@link #OPSTR_COARSE_LOCATION}.
     * This API is intended to be used for mapping runtime
     * permissions to the corresponding app op.
     *
     * @param permission The permission.
     * @return The app op associated with the permission or null.
     */
    public static String permissionToOp(String permission) {
        final Integer opCode = sPermToOp.get(permission);
        if (opCode == null) {
            return null;
        }
        return sOpToString[opCode];
    }

    /**
     * Monitor for changes to the operating mode for the given op in the given app package.
     * You can watch op changes only for your UID.
     *
     * @param op The operation to monitor, one of OPSTR_*.
     * @param packageName The name of the application to monitor.
     * @param callback Where to report changes.
     */
    public void startWatchingMode(String op, String packageName,
            final OnOpChangedListener callback) {
        startWatchingMode(strOpToOp(op), packageName, callback);
    }

    /**
     * Monitor for changes to the operating mode for the given op in the given app package.
     * You can watch op changes only for your UID.
     *
     * @param op The operation to monitor, one of OPSTR_*.
     * @param packageName The name of the application to monitor.
     * @param flags Option flags: any combination of {@link #WATCH_FOREGROUND_CHANGES} or 0.
     * @param callback Where to report changes.
     */
    public void startWatchingMode(String op, String packageName, int flags,
            final OnOpChangedListener callback) {
        startWatchingMode(strOpToOp(op), packageName, flags, callback);
    }

    /**
     * Monitor for changes to the operating mode for the given op in the given app package.
     *
     * <p> If you don't hold the {@link android.Manifest.permission#WATCH_APPOPS} permission
     * you can watch changes only for your UID.
     *
     * @param op The operation to monitor, one of OP_*.
     * @param packageName The name of the application to monitor.
     * @param callback Where to report changes.
     * @hide
     */
    @RequiresPermission(value=android.Manifest.permission.WATCH_APPOPS, conditional=true)
    public void startWatchingMode(int op, String packageName, final OnOpChangedListener callback) {
        startWatchingMode(op, packageName, 0, callback);
    }

    /**
     * Monitor for changes to the operating mode for the given op in the given app package.
     *
     * <p> If you don't hold the {@link android.Manifest.permission#WATCH_APPOPS} permission
     * you can watch changes only for your UID.
     *
     * @param op The operation to monitor, one of OP_*.
     * @param packageName The name of the application to monitor.
     * @param flags Option flags: any combination of {@link #WATCH_FOREGROUND_CHANGES} or 0.
     * @param callback Where to report changes.
     * @hide
     */
    @RequiresPermission(value=android.Manifest.permission.WATCH_APPOPS, conditional=true)
    public void startWatchingMode(int op, String packageName, int flags,
            final OnOpChangedListener callback) {
        synchronized (mModeWatchers) {
            IAppOpsCallback cb = mModeWatchers.get(callback);
            if (cb == null) {
                cb = new IAppOpsCallback.Stub() {
                    public void opChanged(int op, int uid, String packageName) {
                        if (callback instanceof OnOpChangedInternalListener) {
                            ((OnOpChangedInternalListener)callback).onOpChanged(op, packageName);
                        }
                        if (sOpToString[op] != null) {
                            callback.onOpChanged(sOpToString[op], packageName);
                        }
                    }
                };
                mModeWatchers.put(callback, cb);
            }
            try {
                mService.startWatchingModeWithFlags(op, packageName, flags, cb);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Stop monitoring that was previously started with {@link #startWatchingMode}.  All
     * monitoring associated with this callback will be removed.
     */
    public void stopWatchingMode(OnOpChangedListener callback) {
        synchronized (mModeWatchers) {
            IAppOpsCallback cb = mModeWatchers.remove(callback);
            if (cb != null) {
                try {
                    mService.stopWatchingMode(cb);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Start watching for changes to the active state of app ops. An app op may be
     * long running and it has a clear start and stop delimiters. If an op is being
     * started or stopped by any package you will get a callback. To change the
     * watched ops for a registered callback you need to unregister and register it
     * again.
     *
     * <p> If you don't hold the {@link android.Manifest.permission#WATCH_APPOPS} permission
     * you can watch changes only for your UID.
     *
     * @param ops The ops to watch.
     * @param callback Where to report changes.
     *
     * @see #isOperationActive(int, int, String)
     * @see #stopWatchingActive(OnOpActiveChangedListener)
     * @see #startOp(int, int, String)
     * @see #finishOp(int, int, String)
     *
     * @hide
     */
    @TestApi
    // TODO: Uncomment below annotation once b/73559440 is fixed
    // @RequiresPermission(value=Manifest.permission.WATCH_APPOPS, conditional=true)
    public void startWatchingActive(@NonNull int[] ops,
            @NonNull OnOpActiveChangedListener callback) {
        Preconditions.checkNotNull(ops, "ops cannot be null");
        Preconditions.checkNotNull(callback, "callback cannot be null");
        IAppOpsActiveCallback cb;
        synchronized (mActiveWatchers) {
            cb = mActiveWatchers.get(callback);
            if (cb != null) {
                return;
            }
            cb = new IAppOpsActiveCallback.Stub() {
                @Override
                public void opActiveChanged(int op, int uid, String packageName, boolean active) {
                    callback.onOpActiveChanged(op, uid, packageName, active);
                }
            };
            mActiveWatchers.put(callback, cb);
        }
        try {
            mService.startWatchingActive(ops, cb);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop watching for changes to the active state of an app op. An app op may be
     * long running and it has a clear start and stop delimiters. Unregistering a
     * non-registered callback has no effect.
     *
     * @see #isOperationActive#(int, int, String)
     * @see #startWatchingActive(int[], OnOpActiveChangedListener)
     * @see #startOp(int, int, String)
     * @see #finishOp(int, int, String)
     *
     * @hide
     */
    @TestApi
    public void stopWatchingActive(@NonNull OnOpActiveChangedListener callback) {
        synchronized (mActiveWatchers) {
            final IAppOpsActiveCallback cb = mActiveWatchers.remove(callback);
            if (cb != null) {
                try {
                    mService.stopWatchingActive(cb);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Start watching for noted app ops. An app op may be immediate or long running.
     * Immediate ops are noted while long running ones are started and stopped. This
     * method allows registering a listener to be notified when an app op is noted. If
     * an op is being noted by any package you will get a callback. To change the
     * watched ops for a registered callback you need to unregister and register it again.
     *
     * <p> If you don't hold the {@link android.Manifest.permission#WATCH_APPOPS} permission
     * you can watch changes only for your UID.
     *
     * @param ops The ops to watch.
     * @param callback Where to report changes.
     *
     * @see #startWatchingActive(int[], OnOpActiveChangedListener)
     * @see #stopWatchingNoted(OnOpNotedListener)
     * @see #noteOp(String, int, String)
     *
     * @hide
     */
    @RequiresPermission(value=Manifest.permission.WATCH_APPOPS, conditional=true)
    public void startWatchingNoted(@NonNull int[] ops, @NonNull OnOpNotedListener callback) {
        IAppOpsNotedCallback cb;
        synchronized (mNotedWatchers) {
            cb = mNotedWatchers.get(callback);
            if (cb != null) {
                return;
            }
            cb = new IAppOpsNotedCallback.Stub() {
                @Override
                public void opNoted(int op, int uid, String packageName, int mode) {
                    callback.onOpNoted(op, uid, packageName, mode);
                }
            };
            mNotedWatchers.put(callback, cb);
        }
        try {
            mService.startWatchingNoted(ops, cb);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop watching for noted app ops. An app op may be immediate or long running.
     * Unregistering a non-registered callback has no effect.
     *
     * @see #startWatchingNoted(int[], OnOpNotedListener)
     * @see #noteOp(String, int, String)
     *
     * @hide
     */
    public void stopWatchingNoted(@NonNull OnOpNotedListener callback) {
        synchronized (mNotedWatchers) {
            final IAppOpsNotedCallback cb = mNotedWatchers.get(callback);
            if (cb != null) {
                try {
                    mService.stopWatchingNoted(cb);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    private String buildSecurityExceptionMsg(int op, int uid, String packageName) {
        return packageName + " from uid " + uid + " not allowed to perform " + sOpNames[op];
    }

    /**
     * {@hide}
     */
    @TestApi
    public static int strOpToOp(String op) {
        Integer val = sOpStrToOp.get(op);
        if (val == null) {
            throw new IllegalArgumentException("Unknown operation string: " + op);
        }
        return val;
    }

    /**
     * Do a quick check for whether an application might be able to perform an operation.
     * This is <em>not</em> a security check; you must use {@link #noteOp(String, int, String)}
     * or {@link #startOp(String, int, String)} for your actual security checks, which also
     * ensure that the given uid and package name are consistent.  This function can just be
     * used for a quick check to see if an operation has been disabled for the application,
     * as an early reject of some work.  This does not modify the time stamp or other data
     * about the operation.
     *
     * <p>Important things this will not do (which you need to ultimate use
     * {@link #noteOp(String, int, String)} or {@link #startOp(String, int, String)} to cover):</p>
     * <ul>
     *     <li>Verifying the uid and package are consistent, so callers can't spoof
     *     their identity.</li>
     *     <li>Taking into account the current foreground/background state of the
     *     app; apps whose mode varies by this state will always be reported
     *     as {@link #MODE_ALLOWED}.</li>
     * </ul>
     *
     * @param op The operation to check.  One of the OPSTR_* constants.
     * @param uid The user id of the application attempting to perform the operation.
     * @param packageName The name of the application attempting to perform the operation.
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     * @throws SecurityException If the app has been configured to crash on this op.
     */
    public int unsafeCheckOp(String op, int uid, String packageName) {
        return checkOp(strOpToOp(op), uid, packageName);
    }

    /**
     * @deprecated Renamed to {@link #unsafeCheckOp(String, int, String)}.
     */
    @Deprecated
    public int checkOp(String op, int uid, String packageName) {
        return checkOp(strOpToOp(op), uid, packageName);
    }

    /**
     * Like {@link #checkOp} but instead of throwing a {@link SecurityException} it
     * returns {@link #MODE_ERRORED}.
     */
    public int unsafeCheckOpNoThrow(String op, int uid, String packageName) {
        return checkOpNoThrow(strOpToOp(op), uid, packageName);
    }

    /**
     * @deprecated Renamed to {@link #unsafeCheckOpNoThrow(String, int, String)}.
     */
    @Deprecated
    public int checkOpNoThrow(String op, int uid, String packageName) {
        return checkOpNoThrow(strOpToOp(op), uid, packageName);
    }

    /**
     * Like {@link #checkOp} but returns the <em>raw</em> mode associated with the op.
     * Does not throw a security exception, does not translate {@link #MODE_FOREGROUND}.
     */
    public int unsafeCheckOpRaw(String op, int uid, String packageName) {
        try {
            return mService.checkOperationRaw(strOpToOp(op), uid, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Make note of an application performing an operation.  Note that you must pass
     * in both the uid and name of the application to be checked; this function will verify
     * that these two match, and if not, return {@link #MODE_IGNORED}.  If this call
     * succeeds, the last execution time of the operation for this app will be updated to
     * the current time.
     * @param op The operation to note.  One of the OPSTR_* constants.
     * @param uid The user id of the application attempting to perform the operation.
     * @param packageName The name of the application attempting to perform the operation.
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     * @throws SecurityException If the app has been configured to crash on this op.
     */
    public int noteOp(String op, int uid, String packageName) {
        return noteOp(strOpToOp(op), uid, packageName);
    }

    /**
     * Like {@link #noteOp} but instead of throwing a {@link SecurityException} it
     * returns {@link #MODE_ERRORED}.
     */
    public int noteOpNoThrow(String op, int uid, String packageName) {
        return noteOpNoThrow(strOpToOp(op), uid, packageName);
    }

    /**
     * Make note of an application performing an operation on behalf of another
     * application when handling an IPC. Note that you must pass the package name
     * of the application that is being proxied while its UID will be inferred from
     * the IPC state; this function will verify that the calling uid and proxied
     * package name match, and if not, return {@link #MODE_IGNORED}. If this call
     * succeeds, the last execution time of the operation for the proxied app and
     * your app will be updated to the current time.
     * @param op The operation to note.  One of the OPSTR_* constants.
     * @param proxiedPackageName The name of the application calling into the proxy application.
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     * @throws SecurityException If the app has been configured to crash on this op.
     */
    public int noteProxyOp(String op, String proxiedPackageName) {
        return noteProxyOp(strOpToOp(op), proxiedPackageName);
    }

    /**
     * Like {@link #noteProxyOp(String, String)} but instead
     * of throwing a {@link SecurityException} it returns {@link #MODE_ERRORED}.
     */
    public int noteProxyOpNoThrow(String op, String proxiedPackageName) {
        return noteProxyOpNoThrow(strOpToOp(op), proxiedPackageName);
    }

    /**
     * Report that an application has started executing a long-running operation.  Note that you
     * must pass in both the uid and name of the application to be checked; this function will
     * verify that these two match, and if not, return {@link #MODE_IGNORED}.  If this call
     * succeeds, the last execution time of the operation for this app will be updated to
     * the current time and the operation will be marked as "running".  In this case you must
     * later call {@link #finishOp(String, int, String)} to report when the application is no
     * longer performing the operation.
     * @param op The operation to start.  One of the OPSTR_* constants.
     * @param uid The user id of the application attempting to perform the operation.
     * @param packageName The name of the application attempting to perform the operation.
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     * @throws SecurityException If the app has been configured to crash on this op.
     */
    public int startOp(String op, int uid, String packageName) {
        return startOp(strOpToOp(op), uid, packageName);
    }

    /**
     * Like {@link #startOp} but instead of throwing a {@link SecurityException} it
     * returns {@link #MODE_ERRORED}.
     */
    public int startOpNoThrow(String op, int uid, String packageName) {
        return startOpNoThrow(strOpToOp(op), uid, packageName);
    }

    /**
     * Report that an application is no longer performing an operation that had previously
     * been started with {@link #startOp(String, int, String)}.  There is no validation of input
     * or result; the parameters supplied here must be the exact same ones previously passed
     * in when starting the operation.
     */
    public void finishOp(String op, int uid, String packageName) {
        finishOp(strOpToOp(op), uid, packageName);
    }

    /**
     * Do a quick check for whether an application might be able to perform an operation.
     * This is <em>not</em> a security check; you must use {@link #noteOp(int, int, String)}
     * or {@link #startOp(int, int, String)} for your actual security checks, which also
     * ensure that the given uid and package name are consistent.  This function can just be
     * used for a quick check to see if an operation has been disabled for the application,
     * as an early reject of some work.  This does not modify the time stamp or other data
     * about the operation.
     *
     * <p>Important things this will not do (which you need to ultimate use
     * {@link #noteOp(int, int, String)} or {@link #startOp(int, int, String)} to cover):</p>
     * <ul>
     *     <li>Verifying the uid and package are consistent, so callers can't spoof
     *     their identity.</li>
     *     <li>Taking into account the current foreground/background state of the
     *     app; apps whose mode varies by this state will always be reported
     *     as {@link #MODE_ALLOWED}.</li>
     * </ul>
     *
     * @param op The operation to check.  One of the OP_* constants.
     * @param uid The user id of the application attempting to perform the operation.
     * @param packageName The name of the application attempting to perform the operation.
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     * @throws SecurityException If the app has been configured to crash on this op.
     * @hide
     */
    @UnsupportedAppUsage
    public int checkOp(int op, int uid, String packageName) {
        try {
            int mode = mService.checkOperation(op, uid, packageName);
            if (mode == MODE_ERRORED) {
                throw new SecurityException(buildSecurityExceptionMsg(op, uid, packageName));
            }
            return mode;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Like {@link #checkOp} but instead of throwing a {@link SecurityException} it
     * returns {@link #MODE_ERRORED}.
     * @hide
     */
    @UnsupportedAppUsage
    public int checkOpNoThrow(int op, int uid, String packageName) {
        try {
            int mode = mService.checkOperation(op, uid, packageName);
            return mode == AppOpsManager.MODE_FOREGROUND ? AppOpsManager.MODE_ALLOWED : mode;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Do a quick check to validate if a package name belongs to a UID.
     *
     * @throws SecurityException if the package name doesn't belong to the given
     *             UID, or if ownership cannot be verified.
     */
    public void checkPackage(int uid, String packageName) {
        try {
            if (mService.checkPackage(uid, packageName) != MODE_ALLOWED) {
                throw new SecurityException(
                        "Package " + packageName + " does not belong to " + uid);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Like {@link #checkOp} but at a stream-level for audio operations.
     * @hide
     */
    public int checkAudioOp(int op, int stream, int uid, String packageName) {
        try {
            final int mode = mService.checkAudioOperation(op, stream, uid, packageName);
            if (mode == MODE_ERRORED) {
                throw new SecurityException(buildSecurityExceptionMsg(op, uid, packageName));
            }
            return mode;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Like {@link #checkAudioOp} but instead of throwing a {@link SecurityException} it
     * returns {@link #MODE_ERRORED}.
     * @hide
     */
    public int checkAudioOpNoThrow(int op, int stream, int uid, String packageName) {
        try {
            return mService.checkAudioOperation(op, stream, uid, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Make note of an application performing an operation.  Note that you must pass
     * in both the uid and name of the application to be checked; this function will verify
     * that these two match, and if not, return {@link #MODE_IGNORED}.  If this call
     * succeeds, the last execution time of the operation for this app will be updated to
     * the current time.
     * @param op The operation to note.  One of the OP_* constants.
     * @param uid The user id of the application attempting to perform the operation.
     * @param packageName The name of the application attempting to perform the operation.
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     * @throws SecurityException If the app has been configured to crash on this op.
     * @hide
     */
    @UnsupportedAppUsage
    public int noteOp(int op, int uid, String packageName) {
        final int mode = noteOpNoThrow(op, uid, packageName);
        if (mode == MODE_ERRORED) {
            throw new SecurityException(buildSecurityExceptionMsg(op, uid, packageName));
        }
        return mode;
    }

    /**
     * Make note of an application performing an operation on behalf of another
     * application when handling an IPC. Note that you must pass the package name
     * of the application that is being proxied while its UID will be inferred from
     * the IPC state; this function will verify that the calling uid and proxied
     * package name match, and if not, return {@link #MODE_IGNORED}. If this call
     * succeeds, the last execution time of the operation for the proxied app and
     * your app will be updated to the current time.
     * @param op The operation to note. One of the OPSTR_* constants.
     * @param proxiedPackageName The name of the application calling into the proxy application.
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     * @throws SecurityException If the proxy or proxied app has been configured to
     * crash on this op.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int noteProxyOp(int op, String proxiedPackageName) {
        int mode = noteProxyOpNoThrow(op, proxiedPackageName);
        if (mode == MODE_ERRORED) {
            throw new SecurityException("Proxy package " + mContext.getOpPackageName()
                    + " from uid " + Process.myUid() + " or calling package "
                    + proxiedPackageName + " from uid " + Binder.getCallingUid()
                    + " not allowed to perform " + sOpNames[op]);
        }
        return mode;
    }

    /**
     * Like {@link #noteProxyOp(int, String)} but instead
     * of throwing a {@link SecurityException} it returns {@link #MODE_ERRORED}.
     * @hide
     */
    public int noteProxyOpNoThrow(int op, String proxiedPackageName) {
        try {
            return mService.noteProxyOperation(op, Process.myUid(), mContext.getOpPackageName(),
                    Binder.getCallingUid(), proxiedPackageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Like {@link #noteOp} but instead of throwing a {@link SecurityException} it
     * returns {@link #MODE_ERRORED}.
     * @hide
     */
    @UnsupportedAppUsage
    public int noteOpNoThrow(int op, int uid, String packageName) {
        try {
            return mService.noteOperation(op, uid, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    public int noteOp(int op) {
        return noteOp(op, Process.myUid(), mContext.getOpPackageName());
    }

    /** @hide */
    @UnsupportedAppUsage
    public static IBinder getToken(IAppOpsService service) {
        synchronized (AppOpsManager.class) {
            if (sToken != null) {
                return sToken;
            }
            try {
                sToken = service.getToken(new Binder());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            return sToken;
        }
    }

    /** @hide */
    public int startOp(int op) {
        return startOp(op, Process.myUid(), mContext.getOpPackageName());
    }

    /**
     * Report that an application has started executing a long-running operation.  Note that you
     * must pass in both the uid and name of the application to be checked; this function will
     * verify that these two match, and if not, return {@link #MODE_IGNORED}.  If this call
     * succeeds, the last execution time of the operation for this app will be updated to
     * the current time and the operation will be marked as "running".  In this case you must
     * later call {@link #finishOp(int, int, String)} to report when the application is no
     * longer performing the operation.
     *
     * @param op The operation to start.  One of the OP_* constants.
     * @param uid The user id of the application attempting to perform the operation.
     * @param packageName The name of the application attempting to perform the operation.
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     * @throws SecurityException If the app has been configured to crash on this op.
     * @hide
     */
    public int startOp(int op, int uid, String packageName) {
        return startOp(op, uid, packageName, false);
    }

    /**
     * Report that an application has started executing a long-running operation. Similar
     * to {@link #startOp(String, int, String) except that if the mode is {@link #MODE_DEFAULT}
     * the operation should succeed since the caller has performed its standard permission
     * checks which passed and would perform the protected operation for this mode.
     *
     * @param op The operation to start.  One of the OP_* constants.
     * @param uid The user id of the application attempting to perform the operation.
     * @param packageName The name of the application attempting to perform the operation.
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     * @param startIfModeDefault Whether to start if mode is {@link #MODE_DEFAULT}.
     *
     * @throws SecurityException If the app has been configured to crash on this op or
     * the package is not in the passed in UID.
     *
     * @hide
     */
    public int startOp(int op, int uid, String packageName, boolean startIfModeDefault) {
        final int mode = startOpNoThrow(op, uid, packageName, startIfModeDefault);
        if (mode == MODE_ERRORED) {
            throw new SecurityException(buildSecurityExceptionMsg(op, uid, packageName));
        }
        return mode;
    }

    /**
     * Like {@link #startOp} but instead of throwing a {@link SecurityException} it
     * returns {@link #MODE_ERRORED}.
     * @hide
     */
    public int startOpNoThrow(int op, int uid, String packageName) {
        return startOpNoThrow(op, uid, packageName, false);
    }

    /**
     * Like {@link #startOp(int, int, String, boolean)} but instead of throwing a
     * {@link SecurityException} it returns {@link #MODE_ERRORED}.
     *
     * @param op The operation to start.  One of the OP_* constants.
     * @param uid The user id of the application attempting to perform the operation.
     * @param packageName The name of the application attempting to perform the operation.
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     * @param startIfModeDefault Whether to start if mode is {@link #MODE_DEFAULT}.
     *
     * @hide
     */
    public int startOpNoThrow(int op, int uid, String packageName, boolean startIfModeDefault) {
        try {
            return mService.startOperation(getToken(mService), op, uid, packageName,
                    startIfModeDefault);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Report that an application is no longer performing an operation that had previously
     * been started with {@link #startOp(int, int, String)}.  There is no validation of input
     * or result; the parameters supplied here must be the exact same ones previously passed
     * in when starting the operation.
     * @hide
     */
    public void finishOp(int op, int uid, String packageName) {
        try {
            mService.finishOperation(getToken(mService), op, uid, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void finishOp(int op) {
        finishOp(op, Process.myUid(), mContext.getOpPackageName());
    }

    /**
     * Checks whether the given op for a UID and package is active.
     *
     * <p> If you don't hold the {@link android.Manifest.permission#WATCH_APPOPS} permission
     * you can query only for your UID.
     *
     * @see #startWatchingActive(int[], OnOpActiveChangedListener)
     * @see #stopWatchingMode(OnOpChangedListener)
     * @see #finishOp(int)
     * @see #startOp(int)
     *
     * @hide */
    @TestApi
    // TODO: Uncomment below annotation once b/73559440 is fixed
    // @RequiresPermission(value=Manifest.permission.WATCH_APPOPS, conditional=true)
    public boolean isOperationActive(int code, int uid, String packageName) {
        try {
            return mService.isOperationActive(code, uid, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Configures the app ops persistence for testing.
     *
     * @param mode The mode in which the historical registry operates.
     * @param baseSnapshotInterval The base interval on which we would be persisting a snapshot of
     *   the historical data. The history is recursive where every subsequent step encompasses
     *   {@code compressionStep} longer interval with {@code compressionStep} distance between
     *    snapshots.
     * @param compressionStep The compression step in every iteration.
     *
     * @see #HISTORICAL_MODE_DISABLED
     * @see #HISTORICAL_MODE_ENABLED_ACTIVE
     * @see #HISTORICAL_MODE_ENABLED_PASSIVE
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void setHistoryParameters(@HistoricalMode int mode, long baseSnapshotInterval,
            int compressionStep) {
        try {
            mService.setHistoryParameters(mode, baseSnapshotInterval, compressionStep);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Offsets the history by the given duration.
     *
     * @param offsetMillis The offset duration.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void offsetHistory(long offsetMillis) {
        try {
            mService.offsetHistory(offsetMillis);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adds ops to the history directly. This could be useful for testing especially
     * when the historical registry operates in {@link #HISTORICAL_MODE_ENABLED_PASSIVE}
     * mode.
     *
     * @param ops The ops to add to the history.
     *
     * @see #setHistoryParameters(int, long, int)
     * @see #HISTORICAL_MODE_ENABLED_PASSIVE
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void addHistoricalOps(@NonNull HistoricalOps ops) {
        try {
            mService.addHistoricalOps(ops);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resets the app ops persistence for testing.
     *
     * @see #setHistoryParameters(int, long, int)
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void resetHistoryParameters() {
        try {
            mService.resetHistoryParameters();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clears all app ops history.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void clearHistory() {
        try {
            mService.clearHistory();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all supported operation names.
     * @hide
     */
    @SystemApi
    @TestApi
    public static String[] getOpStrs() {
        return Arrays.copyOf(sOpToString, sOpToString.length);
    }


    /**
     * @return number of App ops
     * @hide
     */
    @TestApi
    public static int getNumOps() {
        return _NUM_OP;
    }

    /**
     * @hide
     */
    public static long maxTime(long[] times, int start, int end) {
        long time = 0;
        for (int i = start; i < end; i++) {
            if (times[i] > time) {
                time = times[i];
            }
        }
        return time;
    }

    /** @hide */
    public static String uidStateToString(@UidState int uidState) {
        switch (uidState) {
            case UID_STATE_PERSISTENT: {
                return "UID_STATE_PERSISTENT";
            }
            case UID_STATE_TOP: {
                return "UID_STATE_TOP";
            }
            case UID_STATE_FOREGROUND_SERVICE: {
                return "UID_STATE_FOREGROUND_SERVICE";
            }
            case UID_STATE_FOREGROUND: {
                return "UID_STATE_FOREGROUND";
            }
            case UID_STATE_BACKGROUND: {
                return "UID_STATE_BACKGROUND";
            }
            case UID_STATE_CACHED: {
                return "UID_STATE_CACHED";
            }
            default: {
                return "UNKNOWN";
            }
        }
    }

    /** @hide */
    public static int parseHistoricalMode(@NonNull String mode) {
        switch (mode) {
            case "HISTORICAL_MODE_ENABLED_ACTIVE": {
                return HISTORICAL_MODE_ENABLED_ACTIVE;
            }
            case "HISTORICAL_MODE_ENABLED_PASSIVE": {
                return HISTORICAL_MODE_ENABLED_PASSIVE;
            }
            default: {
                return HISTORICAL_MODE_DISABLED;
            }
        }
    }

    /** @hide */
    public static String historicalModeToString(@HistoricalMode int mode) {
        switch (mode) {
            case HISTORICAL_MODE_DISABLED: {
                return "HISTORICAL_MODE_DISABLED";
            }
            case HISTORICAL_MODE_ENABLED_ACTIVE: {
                return "HISTORICAL_MODE_ENABLED_ACTIVE";
            }
            case HISTORICAL_MODE_ENABLED_PASSIVE: {
                return "HISTORICAL_MODE_ENABLED_PASSIVE";
            }
            default: {
                return "UNKNOWN";
            }
        }
    }
}
