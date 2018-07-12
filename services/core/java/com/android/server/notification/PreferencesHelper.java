/**
 * Copyright (c) 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import static android.app.NotificationManager.IMPORTANCE_NONE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.metrics.LogMaker;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.RankingHelperProto;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class PreferencesHelper implements RankingConfig {
    private static final String TAG = "NotificationPrefHelper";
    private static final int XML_VERSION = 1;

    @VisibleForTesting
    static final String TAG_RANKING = "ranking";
    private static final String TAG_PACKAGE = "package";
    private static final String TAG_CHANNEL = "channel";
    private static final String TAG_GROUP = "channelGroup";

    private static final String ATT_VERSION = "version";
    private static final String ATT_NAME = "name";
    private static final String ATT_UID = "uid";
    private static final String ATT_ID = "id";
    private static final String ATT_PRIORITY = "priority";
    private static final String ATT_VISIBILITY = "visibility";
    private static final String ATT_IMPORTANCE = "importance";
    private static final String ATT_SHOW_BADGE = "show_badge";
    private static final String ATT_APP_USER_LOCKED_FIELDS = "app_user_locked_fields";

    private static final int DEFAULT_PRIORITY = Notification.PRIORITY_DEFAULT;
    private static final int DEFAULT_VISIBILITY = NotificationManager.VISIBILITY_NO_OVERRIDE;
    private static final int DEFAULT_IMPORTANCE = NotificationManager.IMPORTANCE_UNSPECIFIED;
    private static final boolean DEFAULT_SHOW_BADGE = true;
    /**
     * Default value for what fields are user locked. See {@link LockableAppFields} for all lockable
     * fields.
     */
    private static final int DEFAULT_LOCKED_APP_FIELDS = 0;

    /**
     * All user-lockable fields for a given application.
     */
    @IntDef({LockableAppFields.USER_LOCKED_IMPORTANCE})
    public @interface LockableAppFields {
        int USER_LOCKED_IMPORTANCE = 0x00000001;
    }

    // pkg|uid => PackagePreferences
    private final ArrayMap<String, PackagePreferences> mPackagePreferencess = new ArrayMap<>();
    // pkg => PackagePreferences
    private final ArrayMap<String, PackagePreferences> mRestoredWithoutUids = new ArrayMap<>();


    private final Context mContext;
    private final PackageManager mPm;
    private final RankingHandler mRankingHandler;
    private final ZenModeHelper mZenModeHelper;

    private SparseBooleanArray mBadgingEnabled;
    private boolean mAreChannelsBypassingDnd;


    public PreferencesHelper(Context context, PackageManager pm, RankingHandler rankingHandler,
            ZenModeHelper zenHelper) {
        mContext = context;
        mZenModeHelper = zenHelper;
        mRankingHandler = rankingHandler;
        mPm = pm;

        updateBadgingEnabled();

        mAreChannelsBypassingDnd = (mZenModeHelper.getNotificationPolicy().state &
                NotificationManager.Policy.STATE_CHANNELS_BYPASSING_DND) == 1;
        updateChannelsBypassingDnd();

    }

    public void readXml(XmlPullParser parser, boolean forRestore)
            throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type != XmlPullParser.START_TAG) return;
        String tag = parser.getName();
        if (!TAG_RANKING.equals(tag)) return;
        // Clobber groups and channels with the xml, but don't delete other data that wasn't present
        // at the time of serialization.
        mRestoredWithoutUids.clear();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            tag = parser.getName();
            if (type == XmlPullParser.END_TAG && TAG_RANKING.equals(tag)) {
                return;
            }
            if (type == XmlPullParser.START_TAG) {
                if (TAG_PACKAGE.equals(tag)) {
                    int uid = XmlUtils.readIntAttribute(parser, ATT_UID,
                            PackagePreferences.UNKNOWN_UID);
                    String name = parser.getAttributeValue(null, ATT_NAME);
                    if (!TextUtils.isEmpty(name)) {
                        if (forRestore) {
                            try {
                                //TODO: http://b/22388012
                                uid = mPm.getPackageUidAsUser(name,
                                        UserHandle.USER_SYSTEM);
                            } catch (PackageManager.NameNotFoundException e) {
                                // noop
                            }
                        }

                        PackagePreferences r = getOrCreatePackagePreferences(name, uid,
                                XmlUtils.readIntAttribute(
                                        parser, ATT_IMPORTANCE, DEFAULT_IMPORTANCE),
                                XmlUtils.readIntAttribute(parser, ATT_PRIORITY, DEFAULT_PRIORITY),
                                XmlUtils.readIntAttribute(
                                        parser, ATT_VISIBILITY, DEFAULT_VISIBILITY),
                                XmlUtils.readBooleanAttribute(
                                        parser, ATT_SHOW_BADGE, DEFAULT_SHOW_BADGE));
                        r.importance = XmlUtils.readIntAttribute(
                                parser, ATT_IMPORTANCE, DEFAULT_IMPORTANCE);
                        r.priority = XmlUtils.readIntAttribute(
                                parser, ATT_PRIORITY, DEFAULT_PRIORITY);
                        r.visibility = XmlUtils.readIntAttribute(
                                parser, ATT_VISIBILITY, DEFAULT_VISIBILITY);
                        r.showBadge = XmlUtils.readBooleanAttribute(
                                parser, ATT_SHOW_BADGE, DEFAULT_SHOW_BADGE);
                        r.lockedAppFields = XmlUtils.readIntAttribute(parser,
                                ATT_APP_USER_LOCKED_FIELDS, DEFAULT_LOCKED_APP_FIELDS);

                        final int innerDepth = parser.getDepth();
                        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                                && (type != XmlPullParser.END_TAG
                                || parser.getDepth() > innerDepth)) {
                            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                                continue;
                            }

                            String tagName = parser.getName();
                            // Channel groups
                            if (TAG_GROUP.equals(tagName)) {
                                String id = parser.getAttributeValue(null, ATT_ID);
                                CharSequence groupName = parser.getAttributeValue(null, ATT_NAME);
                                if (!TextUtils.isEmpty(id)) {
                                    NotificationChannelGroup group
                                            = new NotificationChannelGroup(id, groupName);
                                    group.populateFromXml(parser);
                                    r.groups.put(id, group);
                                }
                            }
                            // Channels
                            if (TAG_CHANNEL.equals(tagName)) {
                                String id = parser.getAttributeValue(null, ATT_ID);
                                String channelName = parser.getAttributeValue(null, ATT_NAME);
                                int channelImportance = XmlUtils.readIntAttribute(
                                        parser, ATT_IMPORTANCE, DEFAULT_IMPORTANCE);
                                if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(channelName)) {
                                    NotificationChannel channel = new NotificationChannel(id,
                                            channelName, channelImportance);
                                    if (forRestore) {
                                        channel.populateFromXmlForRestore(parser, mContext);
                                    } else {
                                        channel.populateFromXml(parser);
                                    }
                                    r.channels.put(id, channel);
                                }
                            }
                        }

                        try {
                            deleteDefaultChannelIfNeeded(r);
                        } catch (PackageManager.NameNotFoundException e) {
                            Slog.e(TAG, "deleteDefaultChannelIfNeeded - Exception: " + e);
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("Failed to reach END_DOCUMENT");
    }

    private PackagePreferences getPackagePreferences(String pkg, int uid) {
        final String key = packagePreferencesKey(pkg, uid);
        synchronized (mPackagePreferencess) {
            return mPackagePreferencess.get(key);
        }
    }

    private PackagePreferences getOrCreatePackagePreferences(String pkg, int uid) {
        return getOrCreatePackagePreferences(pkg, uid,
                DEFAULT_IMPORTANCE, DEFAULT_PRIORITY, DEFAULT_VISIBILITY, DEFAULT_SHOW_BADGE);
    }

    private PackagePreferences getOrCreatePackagePreferences(String pkg, int uid, int importance,
            int priority, int visibility, boolean showBadge) {
        final String key = packagePreferencesKey(pkg, uid);
        synchronized (mPackagePreferencess) {
            PackagePreferences
                    r = (uid == PackagePreferences.UNKNOWN_UID) ? mRestoredWithoutUids.get(pkg)
                    : mPackagePreferencess.get(key);
            if (r == null) {
                r = new PackagePreferences();
                r.pkg = pkg;
                r.uid = uid;
                r.importance = importance;
                r.priority = priority;
                r.visibility = visibility;
                r.showBadge = showBadge;

                try {
                    createDefaultChannelIfNeeded(r);
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.e(TAG, "createDefaultChannelIfNeeded - Exception: " + e);
                }

                if (r.uid == PackagePreferences.UNKNOWN_UID) {
                    mRestoredWithoutUids.put(pkg, r);
                } else {
                    mPackagePreferencess.put(key, r);
                }
            }
            return r;
        }
    }

    private boolean shouldHaveDefaultChannel(PackagePreferences r) throws
            PackageManager.NameNotFoundException {
        final int userId = UserHandle.getUserId(r.uid);
        final ApplicationInfo applicationInfo =
                mPm.getApplicationInfoAsUser(r.pkg, 0, userId);
        if (applicationInfo.targetSdkVersion >= Build.VERSION_CODES.O) {
            // O apps should not have the default channel.
            return false;
        }

        // Otherwise, this app should have the default channel.
        return true;
    }

    private void deleteDefaultChannelIfNeeded(PackagePreferences r) throws
            PackageManager.NameNotFoundException {
        if (!r.channels.containsKey(NotificationChannel.DEFAULT_CHANNEL_ID)) {
            // Not present
            return;
        }

        if (shouldHaveDefaultChannel(r)) {
            // Keep the default channel until upgraded.
            return;
        }

        // Remove Default Channel.
        r.channels.remove(NotificationChannel.DEFAULT_CHANNEL_ID);
    }

    private void createDefaultChannelIfNeeded(PackagePreferences r) throws
            PackageManager.NameNotFoundException {
        if (r.channels.containsKey(NotificationChannel.DEFAULT_CHANNEL_ID)) {
            r.channels.get(NotificationChannel.DEFAULT_CHANNEL_ID).setName(mContext.getString(
                    com.android.internal.R.string.default_notification_channel_label));
            return;
        }

        if (!shouldHaveDefaultChannel(r)) {
            // Keep the default channel until upgraded.
            return;
        }

        // Create Default Channel
        NotificationChannel channel;
        channel = new NotificationChannel(
                NotificationChannel.DEFAULT_CHANNEL_ID,
                mContext.getString(R.string.default_notification_channel_label),
                r.importance);
        channel.setBypassDnd(r.priority == Notification.PRIORITY_MAX);
        channel.setLockscreenVisibility(r.visibility);
        if (r.importance != NotificationManager.IMPORTANCE_UNSPECIFIED) {
            channel.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);
        }
        if (r.priority != DEFAULT_PRIORITY) {
            channel.lockFields(NotificationChannel.USER_LOCKED_PRIORITY);
        }
        if (r.visibility != DEFAULT_VISIBILITY) {
            channel.lockFields(NotificationChannel.USER_LOCKED_VISIBILITY);
        }
        r.channels.put(channel.getId(), channel);
    }

    public void writeXml(XmlSerializer out, boolean forBackup) throws IOException {
        out.startTag(null, TAG_RANKING);
        out.attribute(null, ATT_VERSION, Integer.toString(XML_VERSION));

        synchronized (mPackagePreferencess) {
            final int N = mPackagePreferencess.size();
            for (int i = 0; i < N; i++) {
                final PackagePreferences r = mPackagePreferencess.valueAt(i);
                //TODO: http://b/22388012
                if (forBackup && UserHandle.getUserId(r.uid) != UserHandle.USER_SYSTEM) {
                    continue;
                }
                final boolean hasNonDefaultSettings =
                        r.importance != DEFAULT_IMPORTANCE
                                || r.priority != DEFAULT_PRIORITY
                                || r.visibility != DEFAULT_VISIBILITY
                                || r.showBadge != DEFAULT_SHOW_BADGE
                                || r.lockedAppFields != DEFAULT_LOCKED_APP_FIELDS
                                || r.channels.size() > 0
                                || r.groups.size() > 0;
                if (hasNonDefaultSettings) {
                    out.startTag(null, TAG_PACKAGE);
                    out.attribute(null, ATT_NAME, r.pkg);
                    if (r.importance != DEFAULT_IMPORTANCE) {
                        out.attribute(null, ATT_IMPORTANCE, Integer.toString(r.importance));
                    }
                    if (r.priority != DEFAULT_PRIORITY) {
                        out.attribute(null, ATT_PRIORITY, Integer.toString(r.priority));
                    }
                    if (r.visibility != DEFAULT_VISIBILITY) {
                        out.attribute(null, ATT_VISIBILITY, Integer.toString(r.visibility));
                    }
                    out.attribute(null, ATT_SHOW_BADGE, Boolean.toString(r.showBadge));
                    out.attribute(null, ATT_APP_USER_LOCKED_FIELDS,
                            Integer.toString(r.lockedAppFields));

                    if (!forBackup) {
                        out.attribute(null, ATT_UID, Integer.toString(r.uid));
                    }

                    for (NotificationChannelGroup group : r.groups.values()) {
                        group.writeXml(out);
                    }

                    for (NotificationChannel channel : r.channels.values()) {
                        if (forBackup) {
                            if (!channel.isDeleted()) {
                                channel.writeXmlForBackup(out, mContext);
                            }
                        } else {
                            channel.writeXml(out);
                        }
                    }

                    out.endTag(null, TAG_PACKAGE);
                }
            }
        }
        out.endTag(null, TAG_RANKING);
    }

    /**
     * Gets importance.
     */
    @Override
    public int getImportance(String packageName, int uid) {
        return getOrCreatePackagePreferences(packageName, uid).importance;
    }


    /**
     * Returns whether the importance of the corresponding notification is user-locked and shouldn't
     * be adjusted by an assistant (via means of a blocking helper, for example). For the channel
     * locking field, see {@link NotificationChannel#USER_LOCKED_IMPORTANCE}.
     */
    public boolean getIsAppImportanceLocked(String packageName, int uid) {
        int userLockedFields = getOrCreatePackagePreferences(packageName, uid).lockedAppFields;
        return (userLockedFields & LockableAppFields.USER_LOCKED_IMPORTANCE) != 0;
    }

    @Override
    public boolean canShowBadge(String packageName, int uid) {
        return getOrCreatePackagePreferences(packageName, uid).showBadge;
    }

    @Override
    public void setShowBadge(String packageName, int uid, boolean showBadge) {
        getOrCreatePackagePreferences(packageName, uid).showBadge = showBadge;
        updateConfig();
    }

    @Override
    public boolean isGroupBlocked(String packageName, int uid, String groupId) {
        if (groupId == null) {
            return false;
        }
        PackagePreferences r = getOrCreatePackagePreferences(packageName, uid);
        NotificationChannelGroup group = r.groups.get(groupId);
        if (group == null) {
            return false;
        }
        return group.isBlocked();
    }

    int getPackagePriority(String pkg, int uid) {
        return getOrCreatePackagePreferences(pkg, uid).priority;
    }

    int getPackageVisibility(String pkg, int uid) {
        return getOrCreatePackagePreferences(pkg, uid).visibility;
    }

    @Override
    public void createNotificationChannelGroup(String pkg, int uid, NotificationChannelGroup group,
            boolean fromTargetApp) {
        Preconditions.checkNotNull(pkg);
        Preconditions.checkNotNull(group);
        Preconditions.checkNotNull(group.getId());
        Preconditions.checkNotNull(!TextUtils.isEmpty(group.getName()));
        PackagePreferences r = getOrCreatePackagePreferences(pkg, uid);
        if (r == null) {
            throw new IllegalArgumentException("Invalid package");
        }
        final NotificationChannelGroup oldGroup = r.groups.get(group.getId());
        if (!group.equals(oldGroup)) {
            // will log for new entries as well as name/description changes
            MetricsLogger.action(getChannelGroupLog(group.getId(), pkg));
        }
        if (oldGroup != null) {
            group.setChannels(oldGroup.getChannels());

            if (fromTargetApp) {
                group.setBlocked(oldGroup.isBlocked());
            }
        }
        r.groups.put(group.getId(), group);
    }

    @Override
    public void createNotificationChannel(String pkg, int uid, NotificationChannel channel,
            boolean fromTargetApp, boolean hasDndAccess) {
        Preconditions.checkNotNull(pkg);
        Preconditions.checkNotNull(channel);
        Preconditions.checkNotNull(channel.getId());
        Preconditions.checkArgument(!TextUtils.isEmpty(channel.getName()));
        PackagePreferences r = getOrCreatePackagePreferences(pkg, uid);
        if (r == null) {
            throw new IllegalArgumentException("Invalid package");
        }
        if (channel.getGroup() != null && !r.groups.containsKey(channel.getGroup())) {
            throw new IllegalArgumentException("NotificationChannelGroup doesn't exist");
        }
        if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(channel.getId())) {
            throw new IllegalArgumentException("Reserved id");
        }
        NotificationChannel existing = r.channels.get(channel.getId());
        // Keep most of the existing settings
        if (existing != null && fromTargetApp) {
            if (existing.isDeleted()) {
                existing.setDeleted(false);

                // log a resurrected channel as if it's new again
                MetricsLogger.action(getChannelLog(channel, pkg).setType(
                        com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_OPEN));
            }

            existing.setName(channel.getName().toString());
            existing.setDescription(channel.getDescription());
            existing.setBlockableSystem(channel.isBlockableSystem());
            if (existing.getGroup() == null) {
                existing.setGroup(channel.getGroup());
            }

            // Apps are allowed to downgrade channel importance if the user has not changed any
            // fields on this channel yet.
            if (existing.getUserLockedFields() == 0 &&
                    channel.getImportance() < existing.getImportance()) {
                existing.setImportance(channel.getImportance());
            }

            // system apps and dnd access apps can bypass dnd if the user hasn't changed any
            // fields on the channel yet
            if (existing.getUserLockedFields() == 0 && hasDndAccess) {
                boolean bypassDnd = channel.canBypassDnd();
                existing.setBypassDnd(bypassDnd);

                if (bypassDnd != mAreChannelsBypassingDnd) {
                    updateChannelsBypassingDnd();
                }
            }

            updateConfig();
            return;
        }
        if (channel.getImportance() < IMPORTANCE_NONE
                || channel.getImportance() > NotificationManager.IMPORTANCE_MAX) {
            throw new IllegalArgumentException("Invalid importance level");
        }

        // Reset fields that apps aren't allowed to set.
        if (fromTargetApp && !hasDndAccess) {
            channel.setBypassDnd(r.priority == Notification.PRIORITY_MAX);
        }
        if (fromTargetApp) {
            channel.setLockscreenVisibility(r.visibility);
        }
        clearLockedFields(channel);
        if (channel.getLockscreenVisibility() == Notification.VISIBILITY_PUBLIC) {
            channel.setLockscreenVisibility(
                    NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE);
        }
        if (!r.showBadge) {
            channel.setShowBadge(false);
        }

        r.channels.put(channel.getId(), channel);
        if (channel.canBypassDnd() != mAreChannelsBypassingDnd) {
            updateChannelsBypassingDnd();
        }
        MetricsLogger.action(getChannelLog(channel, pkg).setType(
                com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_OPEN));
    }

    void clearLockedFields(NotificationChannel channel) {
        channel.unlockFields(channel.getUserLockedFields());
    }

    @Override
    public void updateNotificationChannel(String pkg, int uid, NotificationChannel updatedChannel,
            boolean fromUser) {
        Preconditions.checkNotNull(updatedChannel);
        Preconditions.checkNotNull(updatedChannel.getId());
        PackagePreferences r = getOrCreatePackagePreferences(pkg, uid);
        if (r == null) {
            throw new IllegalArgumentException("Invalid package");
        }
        NotificationChannel channel = r.channels.get(updatedChannel.getId());
        if (channel == null || channel.isDeleted()) {
            throw new IllegalArgumentException("Channel does not exist");
        }
        if (updatedChannel.getLockscreenVisibility() == Notification.VISIBILITY_PUBLIC) {
            updatedChannel.setLockscreenVisibility(
                    NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE);
        }
        if (!fromUser) {
            updatedChannel.unlockFields(updatedChannel.getUserLockedFields());
        }
        if (fromUser) {
            updatedChannel.lockFields(channel.getUserLockedFields());
            lockFieldsForUpdate(channel, updatedChannel);
        }
        r.channels.put(updatedChannel.getId(), updatedChannel);

        if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(updatedChannel.getId())) {
            // copy settings to app level so they are inherited by new channels
            // when the app migrates
            r.importance = updatedChannel.getImportance();
            r.priority = updatedChannel.canBypassDnd()
                    ? Notification.PRIORITY_MAX : Notification.PRIORITY_DEFAULT;
            r.visibility = updatedChannel.getLockscreenVisibility();
            r.showBadge = updatedChannel.canShowBadge();
        }

        if (!channel.equals(updatedChannel)) {
            // only log if there are real changes
            MetricsLogger.action(getChannelLog(updatedChannel, pkg));
        }

        if (updatedChannel.canBypassDnd() != mAreChannelsBypassingDnd) {
            updateChannelsBypassingDnd();
        }
        updateConfig();
    }

    @Override
    public NotificationChannel getNotificationChannel(String pkg, int uid, String channelId,
            boolean includeDeleted) {
        Preconditions.checkNotNull(pkg);
        PackagePreferences r = getOrCreatePackagePreferences(pkg, uid);
        if (r == null) {
            return null;
        }
        if (channelId == null) {
            channelId = NotificationChannel.DEFAULT_CHANNEL_ID;
        }
        final NotificationChannel nc = r.channels.get(channelId);
        if (nc != null && (includeDeleted || !nc.isDeleted())) {
            return nc;
        }
        return null;
    }

    @Override
    public void deleteNotificationChannel(String pkg, int uid, String channelId) {
        PackagePreferences r = getPackagePreferences(pkg, uid);
        if (r == null) {
            return;
        }
        NotificationChannel channel = r.channels.get(channelId);
        if (channel != null) {
            channel.setDeleted(true);
            LogMaker lm = getChannelLog(channel, pkg);
            lm.setType(com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_CLOSE);
            MetricsLogger.action(lm);

            if (mAreChannelsBypassingDnd && channel.canBypassDnd()) {
                updateChannelsBypassingDnd();
            }
        }
    }

    @Override
    @VisibleForTesting
    public void permanentlyDeleteNotificationChannel(String pkg, int uid, String channelId) {
        Preconditions.checkNotNull(pkg);
        Preconditions.checkNotNull(channelId);
        PackagePreferences r = getPackagePreferences(pkg, uid);
        if (r == null) {
            return;
        }
        r.channels.remove(channelId);
    }

    @Override
    public void permanentlyDeleteNotificationChannels(String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        PackagePreferences r = getPackagePreferences(pkg, uid);
        if (r == null) {
            return;
        }
        int N = r.channels.size() - 1;
        for (int i = N; i >= 0; i--) {
            String key = r.channels.keyAt(i);
            if (!NotificationChannel.DEFAULT_CHANNEL_ID.equals(key)) {
                r.channels.remove(key);
            }
        }
    }

    public NotificationChannelGroup getNotificationChannelGroupWithChannels(String pkg,
            int uid, String groupId, boolean includeDeleted) {
        Preconditions.checkNotNull(pkg);
        PackagePreferences r = getPackagePreferences(pkg, uid);
        if (r == null || groupId == null || !r.groups.containsKey(groupId)) {
            return null;
        }
        NotificationChannelGroup group = r.groups.get(groupId).clone();
        group.setChannels(new ArrayList<>());
        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            final NotificationChannel nc = r.channels.valueAt(i);
            if (includeDeleted || !nc.isDeleted()) {
                if (groupId.equals(nc.getGroup())) {
                    group.addChannel(nc);
                }
            }
        }
        return group;
    }

    public NotificationChannelGroup getNotificationChannelGroup(String groupId, String pkg,
            int uid) {
        Preconditions.checkNotNull(pkg);
        PackagePreferences r = getPackagePreferences(pkg, uid);
        if (r == null) {
            return null;
        }
        return r.groups.get(groupId);
    }

    @Override
    public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroups(String pkg,
            int uid, boolean includeDeleted, boolean includeNonGrouped) {
        Preconditions.checkNotNull(pkg);
        Map<String, NotificationChannelGroup> groups = new ArrayMap<>();
        PackagePreferences r = getPackagePreferences(pkg, uid);
        if (r == null) {
            return ParceledListSlice.emptyList();
        }
        NotificationChannelGroup nonGrouped = new NotificationChannelGroup(null, null);
        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            final NotificationChannel nc = r.channels.valueAt(i);
            if (includeDeleted || !nc.isDeleted()) {
                if (nc.getGroup() != null) {
                    if (r.groups.get(nc.getGroup()) != null) {
                        NotificationChannelGroup ncg = groups.get(nc.getGroup());
                        if (ncg == null) {
                            ncg = r.groups.get(nc.getGroup()).clone();
                            ncg.setChannels(new ArrayList<>());
                            groups.put(nc.getGroup(), ncg);

                        }
                        ncg.addChannel(nc);
                    }
                } else {
                    nonGrouped.addChannel(nc);
                }
            }
        }
        if (includeNonGrouped && nonGrouped.getChannels().size() > 0) {
            groups.put(null, nonGrouped);
        }
        return new ParceledListSlice<>(new ArrayList<>(groups.values()));
    }

    public List<NotificationChannel> deleteNotificationChannelGroup(String pkg, int uid,
            String groupId) {
        List<NotificationChannel> deletedChannels = new ArrayList<>();
        PackagePreferences r = getPackagePreferences(pkg, uid);
        if (r == null || TextUtils.isEmpty(groupId)) {
            return deletedChannels;
        }

        r.groups.remove(groupId);

        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            final NotificationChannel nc = r.channels.valueAt(i);
            if (groupId.equals(nc.getGroup())) {
                nc.setDeleted(true);
                deletedChannels.add(nc);
            }
        }
        return deletedChannels;
    }

    @Override
    public Collection<NotificationChannelGroup> getNotificationChannelGroups(String pkg,
            int uid) {
        PackagePreferences r = getPackagePreferences(pkg, uid);
        if (r == null) {
            return new ArrayList<>();
        }
        return r.groups.values();
    }

    @Override
    public ParceledListSlice<NotificationChannel> getNotificationChannels(String pkg, int uid,
            boolean includeDeleted) {
        Preconditions.checkNotNull(pkg);
        List<NotificationChannel> channels = new ArrayList<>();
        PackagePreferences r = getPackagePreferences(pkg, uid);
        if (r == null) {
            return ParceledListSlice.emptyList();
        }
        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            final NotificationChannel nc = r.channels.valueAt(i);
            if (includeDeleted || !nc.isDeleted()) {
                channels.add(nc);
            }
        }
        return new ParceledListSlice<>(channels);
    }

    /**
     * True for pre-O apps that only have the default channel, or pre O apps that have no
     * channels yet. This method will create the default channel for pre-O apps that don't have it.
     * Should never be true for O+ targeting apps, but that's enforced on boot/when an app
     * upgrades.
     */
    public boolean onlyHasDefaultChannel(String pkg, int uid) {
        PackagePreferences r = getOrCreatePackagePreferences(pkg, uid);
        if (r.channels.size() == 1
                && r.channels.containsKey(NotificationChannel.DEFAULT_CHANNEL_ID)) {
            return true;
        }
        return false;
    }

    public int getDeletedChannelCount(String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        int deletedCount = 0;
        PackagePreferences r = getPackagePreferences(pkg, uid);
        if (r == null) {
            return deletedCount;
        }
        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            final NotificationChannel nc = r.channels.valueAt(i);
            if (nc.isDeleted()) {
                deletedCount++;
            }
        }
        return deletedCount;
    }

    public int getBlockedChannelCount(String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        int blockedCount = 0;
        PackagePreferences r = getPackagePreferences(pkg, uid);
        if (r == null) {
            return blockedCount;
        }
        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            final NotificationChannel nc = r.channels.valueAt(i);
            if (!nc.isDeleted() && IMPORTANCE_NONE == nc.getImportance()) {
                blockedCount++;
            }
        }
        return blockedCount;
    }

    public int getBlockedAppCount(int userId) {
        int count = 0;
        synchronized (mPackagePreferencess) {
            final int N = mPackagePreferencess.size();
            for (int i = 0; i < N; i++) {
                final PackagePreferences r = mPackagePreferencess.valueAt(i);
                if (userId == UserHandle.getUserId(r.uid)
                        && r.importance == IMPORTANCE_NONE) {
                    count++;
                }
            }
        }
        return count;
    }

    public void updateChannelsBypassingDnd() {
        synchronized (mPackagePreferencess) {
            final int numPackagePreferencess = mPackagePreferencess.size();
            for (int PackagePreferencesIndex = 0; PackagePreferencesIndex < numPackagePreferencess;
                    PackagePreferencesIndex++) {
                final PackagePreferences r = mPackagePreferencess.valueAt(PackagePreferencesIndex);
                final int numChannels = r.channels.size();

                for (int channelIndex = 0; channelIndex < numChannels; channelIndex++) {
                    NotificationChannel channel = r.channels.valueAt(channelIndex);
                    if (!channel.isDeleted() && channel.canBypassDnd()) {
                        // If any channel bypasses DND, synchronize state and return early.
                        if (!mAreChannelsBypassingDnd) {
                            mAreChannelsBypassingDnd = true;
                            updateZenPolicy(true);
                        }
                        return;
                    }
                }
            }
        }

        // If no channels bypass DND, update the zen policy once to disable DND bypass.
        if (mAreChannelsBypassingDnd) {
            mAreChannelsBypassingDnd = false;
            updateZenPolicy(false);
        }
    }

    public void updateZenPolicy(boolean areChannelsBypassingDnd) {
        NotificationManager.Policy policy = mZenModeHelper.getNotificationPolicy();
        mZenModeHelper.setNotificationPolicy(new NotificationManager.Policy(
                policy.priorityCategories, policy.priorityCallSenders,
                policy.priorityMessageSenders, policy.suppressedVisualEffects,
                (areChannelsBypassingDnd ? NotificationManager.Policy.STATE_CHANNELS_BYPASSING_DND
                        : 0)));
    }

    public boolean areChannelsBypassingDnd() {
        return mAreChannelsBypassingDnd;
    }

    /**
     * Sets importance.
     */
    @Override
    public void setImportance(String pkgName, int uid, int importance) {
        getOrCreatePackagePreferences(pkgName, uid).importance = importance;
        updateConfig();
    }

    public void setEnabled(String packageName, int uid, boolean enabled) {
        boolean wasEnabled = getImportance(packageName, uid) != IMPORTANCE_NONE;
        if (wasEnabled == enabled) {
            return;
        }
        setImportance(packageName, uid,
                enabled ? DEFAULT_IMPORTANCE : IMPORTANCE_NONE);
    }

    /**
     * Sets whether any notifications from the app, represented by the given {@code pkgName} and
     * {@code uid}, have their importance locked by the user. Locked notifications don't get
     * considered for sentiment adjustments (and thus never show a blocking helper).
     */
    public void setAppImportanceLocked(String packageName, int uid) {
        PackagePreferences PackagePreferences = getOrCreatePackagePreferences(packageName, uid);
        if ((PackagePreferences.lockedAppFields & LockableAppFields.USER_LOCKED_IMPORTANCE) != 0) {
            return;
        }

        PackagePreferences.lockedAppFields =
                PackagePreferences.lockedAppFields | LockableAppFields.USER_LOCKED_IMPORTANCE;
        updateConfig();
    }

    @VisibleForTesting
    void lockFieldsForUpdate(NotificationChannel original, NotificationChannel update) {
        if (original.canBypassDnd() != update.canBypassDnd()) {
            update.lockFields(NotificationChannel.USER_LOCKED_PRIORITY);
        }
        if (original.getLockscreenVisibility() != update.getLockscreenVisibility()) {
            update.lockFields(NotificationChannel.USER_LOCKED_VISIBILITY);
        }
        if (original.getImportance() != update.getImportance()) {
            update.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);
        }
        if (original.shouldShowLights() != update.shouldShowLights()
                || original.getLightColor() != update.getLightColor()) {
            update.lockFields(NotificationChannel.USER_LOCKED_LIGHTS);
        }
        if (!Objects.equals(original.getSound(), update.getSound())) {
            update.lockFields(NotificationChannel.USER_LOCKED_SOUND);
        }
        if (!Arrays.equals(original.getVibrationPattern(), update.getVibrationPattern())
                || original.shouldVibrate() != update.shouldVibrate()) {
            update.lockFields(NotificationChannel.USER_LOCKED_VIBRATION);
        }
        if (original.canShowBadge() != update.canShowBadge()) {
            update.lockFields(NotificationChannel.USER_LOCKED_SHOW_BADGE);
        }
    }

    public void dump(PrintWriter pw, String prefix,
            @NonNull NotificationManagerService.DumpFilter filter) {
        pw.print(prefix);
        pw.println("per-package config:");

        pw.println("PackagePreferencess:");
        synchronized (mPackagePreferencess) {
            dumpPackagePreferencess(pw, prefix, filter, mPackagePreferencess);
        }
        pw.println("Restored without uid:");
        dumpPackagePreferencess(pw, prefix, filter, mRestoredWithoutUids);
    }

    public void dump(ProtoOutputStream proto,
            @NonNull NotificationManagerService.DumpFilter filter) {
        synchronized (mPackagePreferencess) {
            dumpPackagePreferencess(proto, RankingHelperProto.RECORDS, filter,
                    mPackagePreferencess);
        }
        dumpPackagePreferencess(proto, RankingHelperProto.RECORDS_RESTORED_WITHOUT_UID, filter,
                mRestoredWithoutUids);
    }

    private static void dumpPackagePreferencess(PrintWriter pw, String prefix,
            @NonNull NotificationManagerService.DumpFilter filter,
            ArrayMap<String, PackagePreferences> PackagePreferencess) {
        final int N = PackagePreferencess.size();
        for (int i = 0; i < N; i++) {
            final PackagePreferences r = PackagePreferencess.valueAt(i);
            if (filter.matches(r.pkg)) {
                pw.print(prefix);
                pw.print("  AppSettings: ");
                pw.print(r.pkg);
                pw.print(" (");
                pw.print(r.uid == PackagePreferences.UNKNOWN_UID ? "UNKNOWN_UID"
                        : Integer.toString(r.uid));
                pw.print(')');
                if (r.importance != DEFAULT_IMPORTANCE) {
                    pw.print(" importance=");
                    pw.print(NotificationListenerService.Ranking.importanceToString(r.importance));
                }
                if (r.priority != DEFAULT_PRIORITY) {
                    pw.print(" priority=");
                    pw.print(Notification.priorityToString(r.priority));
                }
                if (r.visibility != DEFAULT_VISIBILITY) {
                    pw.print(" visibility=");
                    pw.print(Notification.visibilityToString(r.visibility));
                }
                pw.print(" showBadge=");
                pw.print(Boolean.toString(r.showBadge));
                pw.println();
                for (NotificationChannel channel : r.channels.values()) {
                    pw.print(prefix);
                    channel.dump(pw, "    ", filter.redact);
                }
                for (NotificationChannelGroup group : r.groups.values()) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.print("  ");
                    pw.println(group);
                }
            }
        }
    }

    private static void dumpPackagePreferencess(ProtoOutputStream proto, long fieldId,
            @NonNull NotificationManagerService.DumpFilter filter,
            ArrayMap<String, PackagePreferences> PackagePreferencess) {
        final int N = PackagePreferencess.size();
        long fToken;
        for (int i = 0; i < N; i++) {
            final PackagePreferences r = PackagePreferencess.valueAt(i);
            if (filter.matches(r.pkg)) {
                fToken = proto.start(fieldId);

                proto.write(RankingHelperProto.RecordProto.PACKAGE, r.pkg);
                proto.write(RankingHelperProto.RecordProto.UID, r.uid);
                proto.write(RankingHelperProto.RecordProto.IMPORTANCE, r.importance);
                proto.write(RankingHelperProto.RecordProto.PRIORITY, r.priority);
                proto.write(RankingHelperProto.RecordProto.VISIBILITY, r.visibility);
                proto.write(RankingHelperProto.RecordProto.SHOW_BADGE, r.showBadge);

                for (NotificationChannel channel : r.channels.values()) {
                    channel.writeToProto(proto, RankingHelperProto.RecordProto.CHANNELS);
                }
                for (NotificationChannelGroup group : r.groups.values()) {
                    group.writeToProto(proto, RankingHelperProto.RecordProto.CHANNEL_GROUPS);
                }

                proto.end(fToken);
            }
        }
    }

    public JSONObject dumpJson(NotificationManagerService.DumpFilter filter) {
        JSONObject ranking = new JSONObject();
        JSONArray PackagePreferencess = new JSONArray();
        try {
            ranking.put("noUid", mRestoredWithoutUids.size());
        } catch (JSONException e) {
            // pass
        }
        synchronized (mPackagePreferencess) {
            final int N = mPackagePreferencess.size();
            for (int i = 0; i < N; i++) {
                final PackagePreferences r = mPackagePreferencess.valueAt(i);
                if (filter == null || filter.matches(r.pkg)) {
                    JSONObject PackagePreferences = new JSONObject();
                    try {
                        PackagePreferences.put("userId", UserHandle.getUserId(r.uid));
                        PackagePreferences.put("packageName", r.pkg);
                        if (r.importance != DEFAULT_IMPORTANCE) {
                            PackagePreferences.put("importance",
                                    NotificationListenerService.Ranking.importanceToString(
                                            r.importance));
                        }
                        if (r.priority != DEFAULT_PRIORITY) {
                            PackagePreferences.put("priority",
                                    Notification.priorityToString(r.priority));
                        }
                        if (r.visibility != DEFAULT_VISIBILITY) {
                            PackagePreferences.put("visibility",
                                    Notification.visibilityToString(r.visibility));
                        }
                        if (r.showBadge != DEFAULT_SHOW_BADGE) {
                            PackagePreferences.put("showBadge", Boolean.valueOf(r.showBadge));
                        }
                        JSONArray channels = new JSONArray();
                        for (NotificationChannel channel : r.channels.values()) {
                            channels.put(channel.toJson());
                        }
                        PackagePreferences.put("channels", channels);
                        JSONArray groups = new JSONArray();
                        for (NotificationChannelGroup group : r.groups.values()) {
                            groups.put(group.toJson());
                        }
                        PackagePreferences.put("groups", groups);
                    } catch (JSONException e) {
                        // pass
                    }
                    PackagePreferencess.put(PackagePreferences);
                }
            }
        }
        try {
            ranking.put("PackagePreferencess", PackagePreferencess);
        } catch (JSONException e) {
            // pass
        }
        return ranking;
    }

    /**
     * Dump only the ban information as structured JSON for the stats collector.
     *
     * This is intentionally redundant with {#link dumpJson} because the old
     * scraper will expect this format.
     *
     * @param filter
     * @return
     */
    public JSONArray dumpBansJson(NotificationManagerService.DumpFilter filter) {
        JSONArray bans = new JSONArray();
        Map<Integer, String> packageBans = getPackageBans();
        for (Map.Entry<Integer, String> ban : packageBans.entrySet()) {
            final int userId = UserHandle.getUserId(ban.getKey());
            final String packageName = ban.getValue();
            if (filter == null || filter.matches(packageName)) {
                JSONObject banJson = new JSONObject();
                try {
                    banJson.put("userId", userId);
                    banJson.put("packageName", packageName);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                bans.put(banJson);
            }
        }
        return bans;
    }

    public Map<Integer, String> getPackageBans() {
        synchronized (mPackagePreferencess) {
            final int N = mPackagePreferencess.size();
            ArrayMap<Integer, String> packageBans = new ArrayMap<>(N);
            for (int i = 0; i < N; i++) {
                final PackagePreferences r = mPackagePreferencess.valueAt(i);
                if (r.importance == IMPORTANCE_NONE) {
                    packageBans.put(r.uid, r.pkg);
                }
            }

            return packageBans;
        }
    }

    /**
     * Dump only the channel information as structured JSON for the stats collector.
     *
     * This is intentionally redundant with {#link dumpJson} because the old
     * scraper will expect this format.
     *
     * @param filter
     * @return
     */
    public JSONArray dumpChannelsJson(NotificationManagerService.DumpFilter filter) {
        JSONArray channels = new JSONArray();
        Map<String, Integer> packageChannels = getPackageChannels();
        for (Map.Entry<String, Integer> channelCount : packageChannels.entrySet()) {
            final String packageName = channelCount.getKey();
            if (filter == null || filter.matches(packageName)) {
                JSONObject channelCountJson = new JSONObject();
                try {
                    channelCountJson.put("packageName", packageName);
                    channelCountJson.put("channelCount", channelCount.getValue());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                channels.put(channelCountJson);
            }
        }
        return channels;
    }

    private Map<String, Integer> getPackageChannels() {
        ArrayMap<String, Integer> packageChannels = new ArrayMap<>();
        synchronized (mPackagePreferencess) {
            for (int i = 0; i < mPackagePreferencess.size(); i++) {
                final PackagePreferences r = mPackagePreferencess.valueAt(i);
                int channelCount = 0;
                for (int j = 0; j < r.channels.size(); j++) {
                    if (!r.channels.valueAt(j).isDeleted()) {
                        channelCount++;
                    }
                }
                packageChannels.put(r.pkg, channelCount);
            }
        }
        return packageChannels;
    }

    public void onUserRemoved(int userId) {
        synchronized (mPackagePreferencess) {
            int N = mPackagePreferencess.size();
            for (int i = N - 1; i >= 0; i--) {
                PackagePreferences PackagePreferences = mPackagePreferencess.valueAt(i);
                if (UserHandle.getUserId(PackagePreferences.uid) == userId) {
                    mPackagePreferencess.removeAt(i);
                }
            }
        }
    }

    protected void onLocaleChanged(Context context, int userId) {
        synchronized (mPackagePreferencess) {
            int N = mPackagePreferencess.size();
            for (int i = 0; i < N; i++) {
                PackagePreferences PackagePreferences = mPackagePreferencess.valueAt(i);
                if (UserHandle.getUserId(PackagePreferences.uid) == userId) {
                    if (PackagePreferences.channels.containsKey(
                            NotificationChannel.DEFAULT_CHANNEL_ID)) {
                        PackagePreferences.channels.get(
                                NotificationChannel.DEFAULT_CHANNEL_ID).setName(
                                context.getResources().getString(
                                        R.string.default_notification_channel_label));
                    }
                }
            }
        }
    }

    public void onPackagesChanged(boolean removingPackage, int changeUserId, String[] pkgList,
            int[] uidList) {
        if (pkgList == null || pkgList.length == 0) {
            return; // nothing to do
        }
        boolean updated = false;
        if (removingPackage) {
            // Remove notification settings for uninstalled package
            int size = Math.min(pkgList.length, uidList.length);
            for (int i = 0; i < size; i++) {
                final String pkg = pkgList[i];
                final int uid = uidList[i];
                synchronized (mPackagePreferencess) {
                    mPackagePreferencess.remove(packagePreferencesKey(pkg, uid));
                }
                mRestoredWithoutUids.remove(pkg);
                updated = true;
            }
        } else {
            for (String pkg : pkgList) {
                // Package install
                final PackagePreferences r = mRestoredWithoutUids.get(pkg);
                if (r != null) {
                    try {
                        r.uid = mPm.getPackageUidAsUser(r.pkg, changeUserId);
                        mRestoredWithoutUids.remove(pkg);
                        synchronized (mPackagePreferencess) {
                            mPackagePreferencess.put(packagePreferencesKey(r.pkg, r.uid), r);
                        }
                        updated = true;
                    } catch (PackageManager.NameNotFoundException e) {
                        // noop
                    }
                }
                // Package upgrade
                try {
                    PackagePreferences fullPackagePreferences = getPackagePreferences(pkg,
                            mPm.getPackageUidAsUser(pkg, changeUserId));
                    if (fullPackagePreferences != null) {
                        createDefaultChannelIfNeeded(fullPackagePreferences);
                        deleteDefaultChannelIfNeeded(fullPackagePreferences);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                }
            }
        }

        if (updated) {
            updateConfig();
        }
    }

    private LogMaker getChannelLog(NotificationChannel channel, String pkg) {
        return new LogMaker(
                com.android.internal.logging.nano.MetricsProto.MetricsEvent
                        .ACTION_NOTIFICATION_CHANNEL)
                .setType(com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_UPDATE)
                .setPackageName(pkg)
                .addTaggedData(
                        com.android.internal.logging.nano.MetricsProto.MetricsEvent
                                .FIELD_NOTIFICATION_CHANNEL_ID,
                        channel.getId())
                .addTaggedData(
                        com.android.internal.logging.nano.MetricsProto.MetricsEvent
                                .FIELD_NOTIFICATION_CHANNEL_IMPORTANCE,
                        channel.getImportance());
    }

    private LogMaker getChannelGroupLog(String groupId, String pkg) {
        return new LogMaker(
                com.android.internal.logging.nano.MetricsProto.MetricsEvent
                        .ACTION_NOTIFICATION_CHANNEL_GROUP)
                .setType(com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_UPDATE)
                .addTaggedData(
                        com.android.internal.logging.nano.MetricsProto.MetricsEvent
                                .FIELD_NOTIFICATION_CHANNEL_GROUP_ID,
                        groupId)
                .setPackageName(pkg);
    }


    public void updateBadgingEnabled() {
        if (mBadgingEnabled == null) {
            mBadgingEnabled = new SparseBooleanArray();
        }
        boolean changed = false;
        // update the cached values
        for (int index = 0; index < mBadgingEnabled.size(); index++) {
            int userId = mBadgingEnabled.keyAt(index);
            final boolean oldValue = mBadgingEnabled.get(userId);
            final boolean newValue = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.NOTIFICATION_BADGING,
                    DEFAULT_SHOW_BADGE ? 1 : 0, userId) != 0;
            mBadgingEnabled.put(userId, newValue);
            changed |= oldValue != newValue;
        }
        if (changed) {
            updateConfig();
        }
    }

    public boolean badgingEnabled(UserHandle userHandle) {
        int userId = userHandle.getIdentifier();
        if (userId == UserHandle.USER_ALL) {
            return false;
        }
        if (mBadgingEnabled.indexOfKey(userId) < 0) {
            mBadgingEnabled.put(userId,
                    Settings.Secure.getIntForUser(mContext.getContentResolver(),
                            Settings.Secure.NOTIFICATION_BADGING,
                            DEFAULT_SHOW_BADGE ? 1 : 0, userId) != 0);
        }
        return mBadgingEnabled.get(userId, DEFAULT_SHOW_BADGE);
    }

    private void updateConfig() {
        mRankingHandler.requestSort();
    }

    private static String packagePreferencesKey(String pkg, int uid) {
        return pkg + "|" + uid;
    }

    private static class PackagePreferences {
        static int UNKNOWN_UID = UserHandle.USER_NULL;

        String pkg;
        int uid = UNKNOWN_UID;
        int importance = DEFAULT_IMPORTANCE;
        int priority = DEFAULT_PRIORITY;
        int visibility = DEFAULT_VISIBILITY;
        boolean showBadge = DEFAULT_SHOW_BADGE;
        int lockedAppFields = DEFAULT_LOCKED_APP_FIELDS;

        ArrayMap<String, NotificationChannel> channels = new ArrayMap<>();
        Map<String, NotificationChannelGroup> groups = new ConcurrentHashMap<>();
    }
}
