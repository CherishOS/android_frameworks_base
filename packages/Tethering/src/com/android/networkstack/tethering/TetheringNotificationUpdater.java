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

package com.android.networkstack.tethering;

import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.text.TextUtils.isEmpty;

import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.ArrayRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.IntDef;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

/**
 * A class to display tethering-related notifications.
 *
 * <p>This class is not thread safe, it is intended to be used only from the tethering handler
 * thread. However the constructor is an exception, as it is called on another thread ;
 * therefore for thread safety all members of this class MUST either be final or initialized
 * to their default value (0, false or null).
 *
 * @hide
 */
public class TetheringNotificationUpdater {
    private static final String TAG = TetheringNotificationUpdater.class.getSimpleName();
    private static final String CHANNEL_ID = "TETHERING_STATUS";
    private static final String WIFI_DOWNSTREAM = "WIFI";
    private static final String USB_DOWNSTREAM = "USB";
    private static final String BLUETOOTH_DOWNSTREAM = "BT";
    @VisibleForTesting
    static final String ACTION_DISABLE_TETHERING =
            "com.android.server.connectivity.tethering.DISABLE_TETHERING";
    private static final boolean NOTIFY_DONE = true;
    private static final boolean NO_NOTIFY = false;
    @VisibleForTesting
    static final int EVENT_SHOW_NO_UPSTREAM = 1;
    // Id to update and cancel enable notification. Must be unique within the tethering app.
    @VisibleForTesting
    static final int ENABLE_NOTIFICATION_ID = 1000;
    // Id to update and cancel restricted notification. Must be unique within the tethering app.
    @VisibleForTesting
    static final int RESTRICTED_NOTIFICATION_ID = 1001;
    // Id to update and cancel no upstream notification. Must be unique within the tethering app.
    @VisibleForTesting
    static final int NO_UPSTREAM_NOTIFICATION_ID = 1002;
    @VisibleForTesting
    static final int NO_ICON_ID = 0;
    @VisibleForTesting
    static final int DOWNSTREAM_NONE = 0;
    // Refer to TelephonyManager#getSimCarrierId for more details about carrier id.
    @VisibleForTesting
    static final int VERIZON_CARRIER_ID = 1839;
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final NotificationChannel mChannel;
    private final Handler mHandler;

    // WARNING : the constructor is called on a different thread. Thread safety therefore
    // relies on these values being initialized to 0 or false, and not any other value. If you need
    // to change this, you will need to change the thread where the constructor is invoked,
    // or to introduce synchronization.
    // Downstream type is one of ConnectivityManager.TETHERING_* constants, 0 1 or 2.
    // This value has to be made 1 2 and 4, and OR'd with the others.
    private int mDownstreamTypesMask = DOWNSTREAM_NONE;
    private boolean mNoUpstream = false;

    // WARNING : this value is not able to being initialized to 0 and must have volatile because
    // telephony service is not guaranteed that is up before tethering service starts. If telephony
    // is up later than tethering, TetheringNotificationUpdater will use incorrect and valid
    // subscription id(0) to query resources. Therefore, initialized subscription id must be
    // INVALID_SUBSCRIPTION_ID.
    private volatile int mActiveDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    @IntDef({ENABLE_NOTIFICATION_ID, RESTRICTED_NOTIFICATION_ID, NO_UPSTREAM_NOTIFICATION_ID})
    @interface NotificationId {}

    private static final class MccMncOverrideInfo {
        public final String visitedMccMnc;
        public final int homeMcc;
        public final int homeMnc;
        MccMncOverrideInfo(String visitedMccMnc, int mcc, int mnc) {
            this.visitedMccMnc = visitedMccMnc;
            this.homeMcc = mcc;
            this.homeMnc = mnc;
        }
    }

    private static final SparseArray<MccMncOverrideInfo> sCarrierIdToMccMnc = new SparseArray<>();

    static {
        sCarrierIdToMccMnc.put(VERIZON_CARRIER_ID, new MccMncOverrideInfo("20404", 311, 480));
    }

    public TetheringNotificationUpdater(@NonNull final Context context,
            @NonNull final Looper looper) {
        mContext = context;
        mNotificationManager = (NotificationManager) context.createContextAsUser(UserHandle.ALL, 0)
                .getSystemService(Context.NOTIFICATION_SERVICE);
        mChannel = new NotificationChannel(
                CHANNEL_ID,
                context.getResources().getString(R.string.notification_channel_tethering_status),
                NotificationManager.IMPORTANCE_LOW);
        mNotificationManager.createNotificationChannel(mChannel);
        mHandler = new NotificationHandler(looper);
    }

    private class NotificationHandler extends Handler {
        NotificationHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case EVENT_SHOW_NO_UPSTREAM:
                    notifyTetheringNoUpstream();
                    break;
            }
        }
    }

    /** Called when downstream has changed */
    public void onDownstreamChanged(@IntRange(from = 0, to = 7) final int downstreamTypesMask) {
        if (mDownstreamTypesMask == downstreamTypesMask) return;
        mDownstreamTypesMask = downstreamTypesMask;
        updateEnableNotification();
        updateNoUpstreamNotification();
    }

    /** Called when active data subscription id changed */
    public void onActiveDataSubscriptionIdChanged(final int subId) {
        if (mActiveDataSubId == subId) return;
        mActiveDataSubId = subId;
        updateEnableNotification();
        updateNoUpstreamNotification();
    }

    /** Called when upstream network changed */
    public void onUpstreamNetworkChanged(@Nullable final Network network) {
        final boolean isNoUpstream = (network == null);
        if (mNoUpstream == isNoUpstream) return;
        mNoUpstream = isNoUpstream;
        updateNoUpstreamNotification();
    }

    @NonNull
    @VisibleForTesting
    final Handler getHandler() {
        return mHandler;
    }

    @NonNull
    @VisibleForTesting
    Resources getResourcesForSubId(@NonNull final Context context, final int subId) {
        final Resources res = SubscriptionManager.getResourcesForSubId(context, subId);
        final TelephonyManager tm =
                ((TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE))
                        .createForSubscriptionId(mActiveDataSubId);
        final int carrierId = tm.getSimCarrierId();
        final String mccmnc = tm.getSimOperator();
        final MccMncOverrideInfo overrideInfo = sCarrierIdToMccMnc.get(carrierId);
        if (overrideInfo != null && overrideInfo.visitedMccMnc.equals(mccmnc)) {
            // Re-configure MCC/MNC value to specific carrier to get right resources.
            final Configuration config = res.getConfiguration();
            config.mcc = overrideInfo.homeMcc;
            config.mnc = overrideInfo.homeMnc;
            return context.createConfigurationContext(config).getResources();
        }
        return res;
    }

    private void updateEnableNotification() {
        final boolean tetheringInactive = mDownstreamTypesMask == DOWNSTREAM_NONE;

        if (tetheringInactive || setupNotification() == NO_NOTIFY) {
            clearNotification(ENABLE_NOTIFICATION_ID);
        }
    }

    private void updateNoUpstreamNotification() {
        final boolean tetheringInactive = mDownstreamTypesMask == DOWNSTREAM_NONE;

        if (tetheringInactive
                || !mNoUpstream
                || setupNoUpstreamNotification() == NO_NOTIFY) {
            clearNotification(NO_UPSTREAM_NOTIFICATION_ID);
            mHandler.removeMessages(EVENT_SHOW_NO_UPSTREAM);
        }
    }

    @VisibleForTesting
    void tetheringRestrictionLifted() {
        clearNotification(RESTRICTED_NOTIFICATION_ID);
    }

    private void clearNotification(@NotificationId final int id) {
        mNotificationManager.cancel(null /* tag */, id);
    }

    @VisibleForTesting
    void notifyTetheringDisabledByRestriction() {
        final Resources res = getResourcesForSubId(mContext, mActiveDataSubId);
        final String title = res.getString(R.string.disable_tether_notification_title);
        final String message = res.getString(R.string.disable_tether_notification_message);
        if (isEmpty(title) || isEmpty(message)) return;

        final PendingIntent pi = PendingIntent.getActivity(
                mContext.createContextAsUser(UserHandle.CURRENT, 0 /* flags */),
                0 /* requestCode */,
                new Intent(Settings.ACTION_TETHER_SETTINGS),
                Intent.FLAG_ACTIVITY_NEW_TASK,
                null /* options */);

        showNotification(R.drawable.stat_sys_tether_general, title, message,
                RESTRICTED_NOTIFICATION_ID, pi, new Action[0]);
    }

    private void notifyTetheringNoUpstream() {
        final Resources res = getResourcesForSubId(mContext, mActiveDataSubId);
        final String title = res.getString(R.string.no_upstream_notification_title);
        final String message = res.getString(R.string.no_upstream_notification_message);
        final String disableButton =
                res.getString(R.string.no_upstream_notification_disable_button);
        if (isEmpty(title) || isEmpty(message) || isEmpty(disableButton)) return;

        final Intent intent = new Intent(ACTION_DISABLE_TETHERING);
        intent.setPackage(mContext.getPackageName());
        final PendingIntent pi = PendingIntent.getBroadcast(
                mContext.createContextAsUser(UserHandle.CURRENT, 0 /* flags */),
                0 /* requestCode */,
                intent,
                0 /* flags */);
        final Action action = new Action.Builder(NO_ICON_ID, disableButton, pi).build();

        showNotification(R.drawable.stat_sys_tether_general, title, message,
                NO_UPSTREAM_NOTIFICATION_ID, null /* pendingIntent */, action);
    }

    /**
     * Returns the downstream types mask which convert from given string.
     *
     * @param types This string has to be made by "WIFI", "USB", "BT", and OR'd with the others.
     *
     * @return downstream types mask value.
     */
    @VisibleForTesting
    @IntRange(from = 0, to = 7)
    int getDownstreamTypesMask(@NonNull final String types) {
        int downstreamTypesMask = DOWNSTREAM_NONE;
        final String[] downstreams = types.split("\\|");
        for (String downstream : downstreams) {
            if (USB_DOWNSTREAM.equals(downstream.trim())) {
                downstreamTypesMask |= (1 << TETHERING_USB);
            } else if (WIFI_DOWNSTREAM.equals(downstream.trim())) {
                downstreamTypesMask |= (1 << TETHERING_WIFI);
            } else if (BLUETOOTH_DOWNSTREAM.equals(downstream.trim())) {
                downstreamTypesMask |= (1 << TETHERING_BLUETOOTH);
            }
        }
        return downstreamTypesMask;
    }

    /**
     * Returns the icons {@link android.util.SparseArray} which get from given string-array resource
     * id.
     *
     * @param id String-array resource id
     *
     * @return {@link android.util.SparseArray} with downstream types and icon id info.
     */
    @NonNull
    @VisibleForTesting
    SparseArray<Integer> getIcons(@ArrayRes int id, @NonNull Resources res) {
        final String[] array = res.getStringArray(id);
        final SparseArray<Integer> icons = new SparseArray<>();
        for (String config : array) {
            if (isEmpty(config)) continue;

            final String[] elements = config.split(";");
            if (elements.length != 2) {
                Log.wtf(TAG,
                        "Unexpected format in Tethering notification configuration : " + config);
                continue;
            }

            final String[] types = elements[0].split(",");
            for (String type : types) {
                int mask = getDownstreamTypesMask(type);
                if (mask == DOWNSTREAM_NONE) continue;
                icons.put(mask, res.getIdentifier(
                        elements[1].trim(), null /* defType */, null /* defPackage */));
            }
        }
        return icons;
    }

    private boolean setupNoUpstreamNotification() {
        final Resources res = getResourcesForSubId(mContext, mActiveDataSubId);
        final int delayToShowUpstreamNotification =
                res.getInteger(R.integer.delay_to_show_no_upstream_after_no_backhaul);

        if (delayToShowUpstreamNotification < 0) return NO_NOTIFY;

        mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_SHOW_NO_UPSTREAM),
                delayToShowUpstreamNotification);
        return NOTIFY_DONE;
    }

    private boolean setupNotification() {
        final Resources res = getResourcesForSubId(mContext, mActiveDataSubId);
        final SparseArray<Integer> downstreamIcons =
                getIcons(R.array.tethering_notification_icons, res);

        final int iconId = downstreamIcons.get(mDownstreamTypesMask, NO_ICON_ID);
        if (iconId == NO_ICON_ID) return NO_NOTIFY;

        final String title = res.getString(R.string.tethering_notification_title);
        final String message = res.getString(R.string.tethering_notification_message);
        if (isEmpty(title) || isEmpty(message)) return NO_NOTIFY;

        final PendingIntent pi = PendingIntent.getActivity(
                mContext.createContextAsUser(UserHandle.CURRENT, 0 /* flags */),
                0 /* requestCode */,
                new Intent(Settings.ACTION_TETHER_SETTINGS),
                Intent.FLAG_ACTIVITY_NEW_TASK,
                null /* options */);

        showNotification(iconId, title, message, ENABLE_NOTIFICATION_ID, pi, new Action[0]);
        return NOTIFY_DONE;
    }

    private void showNotification(@DrawableRes final int iconId, @NonNull final String title,
            @NonNull final String message, @NotificationId final int id, @Nullable PendingIntent pi,
            @NonNull final Action... actions) {
        final Notification notification =
                new Notification.Builder(mContext, mChannel.getId())
                        .setSmallIcon(iconId)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setOngoing(true)
                        .setColor(mContext.getColor(
                                android.R.color.system_notification_accent_color))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setCategory(Notification.CATEGORY_STATUS)
                        .setContentIntent(pi)
                        .setActions(actions)
                        .build();

        mNotificationManager.notify(null /* tag */, id, notification);
    }
}
