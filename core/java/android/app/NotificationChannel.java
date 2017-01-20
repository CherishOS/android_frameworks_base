/*
 * Copyright (C) 2016 The Android Open Source Project
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

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import android.annotation.SystemApi;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.io.IOException;
import java.util.Arrays;

/**
 * A representation of settings that apply to a collection of similarly themed notifications.
 */
public final class NotificationChannel implements Parcelable {

    /**
     * The id of the default channel for an app. All notifications posted without a notification
     * channel specified are posted to this channel.
     */
    public static final String DEFAULT_CHANNEL_ID = "miscellaneous";

    private static final String TAG_CHANNEL = "channel";
    private static final String ATT_NAME = "name";
    private static final String ATT_ID = "id";
    private static final String ATT_DELETED = "deleted";
    private static final String ATT_PRIORITY = "priority";
    private static final String ATT_VISIBILITY = "visibility";
    private static final String ATT_IMPORTANCE = "importance";
    private static final String ATT_LIGHTS = "lights";
    private static final String ATT_VIBRATION = "vibration";
    private static final String ATT_VIBRATION_ENABLED = "vibration_enabled";
    private static final String ATT_SOUND = "sound";
    //TODO: add audio attributes support
    private static final String ATT_AUDIO_ATTRIBUTES = "audio_attributes";
    private static final String ATT_SHOW_BADGE = "show_badge";
    private static final String ATT_USER_LOCKED = "locked";
    private static final String DELIMITER = ",";

    /**
     * @hide
     */
    @SystemApi
    public static final int USER_LOCKED_PRIORITY = 0x00000001;
    /**
     * @hide
     */
    @SystemApi
    public static final int USER_LOCKED_VISIBILITY = 0x00000002;
    /**
     * @hide
     */
    @SystemApi
    public static final int USER_LOCKED_IMPORTANCE = 0x00000004;
    /**
     * @hide
     */
    @SystemApi
    public static final int USER_LOCKED_LIGHTS = 0x00000008;
    /**
     * @hide
     */
    @SystemApi
    public static final int USER_LOCKED_VIBRATION = 0x00000010;
    /**
     * @hide
     */
    @SystemApi
    public static final int USER_LOCKED_SOUND = 0x00000020;

    /**
     * @hide
     */
    @SystemApi
    public static final int USER_LOCKED_ALLOWED = 0x00000040;

    /**
     * @hide
     */
    @SystemApi
    public static final int USER_LOCKED_SHOW_BADGE = 0x00000080;

    /**
     * @hide
     */
    @SystemApi
    public static final int[] LOCKABLE_FIELDS = new int[] {
            USER_LOCKED_PRIORITY,
            USER_LOCKED_VISIBILITY,
            USER_LOCKED_IMPORTANCE,
            USER_LOCKED_LIGHTS,
            USER_LOCKED_VIBRATION,
            USER_LOCKED_SOUND,
            USER_LOCKED_ALLOWED,
            USER_LOCKED_SHOW_BADGE
    };


    private static final int DEFAULT_VISIBILITY =
            NotificationManager.VISIBILITY_NO_OVERRIDE;
    private static final int DEFAULT_IMPORTANCE =
            NotificationManager.IMPORTANCE_UNSPECIFIED;
    private static final boolean DEFAULT_DELETED = false;

    private final String mId;
    private CharSequence mName;
    private int mImportance = DEFAULT_IMPORTANCE;
    private boolean mBypassDnd;
    private int mLockscreenVisibility = DEFAULT_VISIBILITY;
    private Uri mSound;
    private boolean mLights;
    private long[] mVibration;
    private int mUserLockedFields;
    private boolean mVibrationEnabled;
    private boolean mShowBadge;
    private boolean mDeleted = DEFAULT_DELETED;

    /**
     * Creates a notification channel.
     *
     * @param id The id of the channel. Must be unique per package.
     * @param name The user visible name of the channel.
     * @param importance The importance of the channel. This controls how interruptive notifications
     *                   posted to this channel are. See e.g.
     *                   {@link NotificationManager#IMPORTANCE_DEFAULT}.
     */
    public NotificationChannel(String id, CharSequence name, int importance) {
        this.mId = id;
        this.mName = name;
        this.mImportance = importance;
    }

    protected NotificationChannel(Parcel in) {
        if (in.readByte() != 0) {
            mId = in.readString();
        } else {
            mId = null;
        }
        mName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mImportance = in.readInt();
        mBypassDnd = in.readByte() != 0;
        mLockscreenVisibility = in.readInt();
        if (in.readByte() != 0) {
            mSound = Uri.CREATOR.createFromParcel(in);
        } else {
            mSound = null;
        }
        mLights = in.readByte() != 0;
        mVibration = in.createLongArray();
        mUserLockedFields = in.readInt();
        mVibrationEnabled = in.readByte() != 0;
        mShowBadge = in.readByte() != 0;
        mDeleted = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mId != null) {
            dest.writeByte((byte) 1);
            dest.writeString(mId);
        } else {
            dest.writeByte((byte) 0);
        }
        TextUtils.writeToParcel(mName, dest, flags);
        dest.writeInt(mImportance);
        dest.writeByte(mBypassDnd ? (byte) 1 : (byte) 0);
        dest.writeInt(mLockscreenVisibility);
        if (mSound != null) {
            dest.writeByte((byte) 1);
            mSound.writeToParcel(dest, 0);
        } else {
            dest.writeByte((byte) 0);
        }
        dest.writeByte(mLights ? (byte) 1 : (byte) 0);
        dest.writeLongArray(mVibration);
        dest.writeInt(mUserLockedFields);
        dest.writeByte(mVibrationEnabled ? (byte) 1 : (byte) 0);
        dest.writeByte(mShowBadge ? (byte) 1 : (byte) 0);
        dest.writeByte(mDeleted ? (byte) 1 : (byte) 0);
    }

    /**
     * @hide
     */
    @SystemApi
    public void lockFields(int field) {
        mUserLockedFields |= field;
    }

    /**
     * @hide
     */
    @SystemApi
    public void setDeleted(boolean deleted) {
        mDeleted = deleted;
    }

    // Modifiable by a notification ranker.

    /**
     * Sets whether or not notifications posted to this channel can interrupt the user in
     * {@link android.app.NotificationManager.Policy#INTERRUPTION_FILTER_PRIORITY} mode.
     *
     * Only modifiable by the system and notification ranker.
     */
    public void setBypassDnd(boolean bypassDnd) {
        this.mBypassDnd = bypassDnd;
    }

    /**
     * Sets whether notifications posted to this channel appear on the lockscreen or not, and if so,
     * whether they appear in a redacted form. See e.g. {@link Notification#VISIBILITY_SECRET}.
     *
     * Only modifiable by the system and notification ranker.
     */
    public void setLockscreenVisibility(int lockscreenVisibility) {
        this.mLockscreenVisibility = lockscreenVisibility;
    }

    /**
     * Sets the level of interruption of this notification channel.
     *
     * Only modifiable by the system and notification ranker.
     *
     * @param importance the amount the user should be interrupted by notifications from this
     *                   channel. See e.g.
     *                   {@link android.app.NotificationManager#IMPORTANCE_DEFAULT}.
     */
    public void setImportance(int importance) {
        this.mImportance = importance;
    }

    // Modifiable by apps on channel creation.

    /**
     * Sets whether notifications posted to this channel can appear as application icon badges
     * in a Launcher.
     *
     * @param showBadge true if badges should be allowed to be shown.
     */
    public void setShowBadge(boolean showBadge) {
        this.mShowBadge = showBadge;
    }

    /**
     * Sets the sound that should be played for notifications posted to this channel if
     * the notifications don't supply a sound. Only modifiable before the channel is submitted
     * to the NotificationManager.
     */
    public void setSound(Uri sound) {
        this.mSound = sound;
    }

    /**
     * Sets whether notifications posted to this channel should display notification lights,
     * on devices that support that feature. Only modifiable before the channel is submitted to
     * the NotificationManager.
     */
    public void setLights(boolean lights) {
        this.mLights = lights;
    }

    /**
     * Sets whether notification posted to this channel should vibrate. The vibration pattern can
     * be set with {@link #setVibrationPattern(long[])}. Only modifiable before the channel is
     * submitted to the NotificationManager.
     */
    public void enableVibration(boolean vibration) {
        this.mVibrationEnabled = vibration;
    }

    /**
     * Sets whether notification posted to this channel should vibrate. Only modifiable before the
     * channel is submitted to the NotificationManager.
     */
    public void setVibrationPattern(long[] vibrationPattern) {
        this.mVibration = vibrationPattern;
    }

    /**
     * Returns the id of this channel.
     */
    public String getId() {
        return mId;
    }

    /**
     * Returns the user visible name of this channel.
     */
    public CharSequence getName() {
        return mName;
    }

    /**
     * Returns the user specified importance {e.g. @link NotificationManager#IMPORTANCE_LOW} for
     * notifications posted to this channel.
     */
    public int getImportance() {
        return mImportance;
    }

    /**
     * Whether or not notifications posted to this channel can bypass the Do Not Disturb
     * {@link NotificationManager#INTERRUPTION_FILTER_PRIORITY} mode.
     */
    public boolean canBypassDnd() {
        return mBypassDnd;
    }

    /**
     * Returns the notification sound for this channel.
     */
    public Uri getSound() {
        return mSound;
    }

    /**
     * Returns whether notifications posted to this channel trigger notification lights.
     */
    public boolean shouldShowLights() {
        return mLights;
    }

    /**
     * Returns whether notifications posted to this channel always vibrate.
     */
    public boolean shouldVibrate() {
        return mVibrationEnabled;
    }

    /**
     * Returns the vibration pattern for notifications posted to this channel. Will be ignored if
     * vibration is not enabled ({@link #shouldVibrate()}.
     */
    public long[] getVibrationPattern() {
        return mVibration;
    }

    /**
     * Returns whether or not notifications posted to this channel are shown on the lockscreen in
     * full or redacted form.
     */
    public int getLockscreenVisibility() {
        return mLockscreenVisibility;
    }

    /**
     * Returns whether notifications posted to this channel can appear as badges in a Launcher
     * application.
     */
    public boolean canShowBadge() {
        return mShowBadge;
    }

    /**
     * @hide
     */
    @SystemApi
    public boolean isDeleted() {
        return mDeleted;
    }

    /**
     * @hide
     */
    @SystemApi
    public int getUserLockedFields() {
        return mUserLockedFields;
    }

    /**
     * @hide
     */
    @SystemApi
    public void populateFromXml(XmlPullParser parser) {
        // Name, id, and importance are set in the constructor.
        setBypassDnd(Notification.PRIORITY_DEFAULT
                != safeInt(parser, ATT_PRIORITY, Notification.PRIORITY_DEFAULT));
        setLockscreenVisibility(safeInt(parser, ATT_VISIBILITY, DEFAULT_VISIBILITY));
        setSound(safeUri(parser, ATT_SOUND));
        setLights(safeBool(parser, ATT_LIGHTS, false));
        enableVibration(safeBool(parser, ATT_VIBRATION_ENABLED, false));
        setVibrationPattern(safeLongArray(parser, ATT_VIBRATION, null));
        setShowBadge(safeBool(parser, ATT_SHOW_BADGE, false));
        setDeleted(safeBool(parser, ATT_DELETED, false));
        lockFields(safeInt(parser, ATT_USER_LOCKED, 0));
    }

    /**
     * @hide
     */
    @SystemApi
    public void writeXml(XmlSerializer out) throws IOException {
        out.startTag(null, TAG_CHANNEL);
        out.attribute(null, ATT_ID, getId());
        out.attribute(null, ATT_NAME, getName().toString());
        if (getImportance() != DEFAULT_IMPORTANCE) {
            out.attribute(
                    null, ATT_IMPORTANCE, Integer.toString(getImportance()));
        }
        if (canBypassDnd()) {
            out.attribute(
                    null, ATT_PRIORITY, Integer.toString(Notification.PRIORITY_MAX));
        }
        if (getLockscreenVisibility() != DEFAULT_VISIBILITY) {
            out.attribute(null, ATT_VISIBILITY,
                    Integer.toString(getLockscreenVisibility()));
        }
        if (getSound() != null) {
            out.attribute(null, ATT_SOUND, getSound().toString());
        }
        if (shouldShowLights()) {
            out.attribute(null, ATT_LIGHTS, Boolean.toString(shouldShowLights()));
        }
        if (shouldVibrate()) {
            out.attribute(null, ATT_VIBRATION_ENABLED, Boolean.toString(shouldVibrate()));
        }
        if (getVibrationPattern() != null) {
            out.attribute(null, ATT_VIBRATION, longArrayToString(getVibrationPattern()));
        }
        if (getUserLockedFields() != 0) {
            out.attribute(null, ATT_USER_LOCKED, Integer.toString(getUserLockedFields()));
        }
        if (canShowBadge()) {
            out.attribute(null, ATT_SHOW_BADGE, Boolean.toString(canShowBadge()));
        }
        if (isDeleted()) {
            out.attribute(null, ATT_DELETED, Boolean.toString(isDeleted()));
        }

        out.endTag(null, TAG_CHANNEL);
    }

    /**
     * @hide
     */
    @SystemApi
    public JSONObject toJson() throws JSONException {
        JSONObject record = new JSONObject();
        record.put(ATT_ID, getId());
        record.put(ATT_NAME, getName());
        if (getImportance() != DEFAULT_IMPORTANCE) {
            record.put(ATT_IMPORTANCE,
                    NotificationListenerService.Ranking.importanceToString(getImportance()));
        }
        if (canBypassDnd()) {
            record.put(ATT_PRIORITY, Notification.PRIORITY_MAX);
        }
        if (getLockscreenVisibility() != DEFAULT_VISIBILITY) {
            record.put(ATT_VISIBILITY, Notification.visibilityToString(getLockscreenVisibility()));
        }
        if (getSound() != null) {
            record.put(ATT_SOUND, getSound().toString());
        }
        record.put(ATT_LIGHTS, Boolean.toString(shouldShowLights()));
        record.put(ATT_VIBRATION_ENABLED, Boolean.toString(shouldVibrate()));
        record.put(ATT_USER_LOCKED, Integer.toString(getUserLockedFields()));
        record.put(ATT_VIBRATION, longArrayToString(getVibrationPattern()));
        record.put(ATT_SHOW_BADGE, Boolean.toString(canShowBadge()));
        record.put(ATT_DELETED, Boolean.toString(isDeleted()));
        return record;
    }

    private static Uri safeUri(XmlPullParser parser, String att) {
        final String val = parser.getAttributeValue(null, att);
        return val == null ? null : Uri.parse(val);
    }

    private static int safeInt(XmlPullParser parser, String att, int defValue) {
        final String val = parser.getAttributeValue(null, att);
        return tryParseInt(val, defValue);
    }

    private static int tryParseInt(String value, int defValue) {
        if (TextUtils.isEmpty(value)) return defValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    private static boolean safeBool(XmlPullParser parser, String att, boolean defValue) {
        final String value = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(value)) return defValue;
        return Boolean.parseBoolean(value);
    }

    private static long[] safeLongArray(XmlPullParser parser, String att, long[] defValue) {
        final String attributeValue = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(attributeValue)) return defValue;
        String[] values = attributeValue.split(DELIMITER);
        long[] longValues = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            try {
                longValues[i] = Long.parseLong(values[i]);
            } catch (NumberFormatException e) {
                longValues[i] = 0;
            }
        }
        return longValues;
    }

    private static String longArrayToString(long[] values) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < values.length - 1; i++) {
            sb.append(values[i]).append(DELIMITER);
        }
        sb.append(values[values.length - 1]);
        return sb.toString();
    }

    public static final Creator<NotificationChannel> CREATOR = new Creator<NotificationChannel>() {
        @Override
        public NotificationChannel createFromParcel(Parcel in) {
            return new NotificationChannel(in);
        }

        @Override
        public NotificationChannel[] newArray(int size) {
            return new NotificationChannel[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NotificationChannel that = (NotificationChannel) o;

        if (mImportance != that.mImportance) return false;
        if (mBypassDnd != that.mBypassDnd) return false;
        if (mLockscreenVisibility != that.mLockscreenVisibility) return false;
        if (mLights != that.mLights) return false;
        if (mUserLockedFields != that.mUserLockedFields) return false;
        if (mVibrationEnabled != that.mVibrationEnabled) return false;
        if (mShowBadge != that.mShowBadge) return false;
        if (mDeleted != that.mDeleted) return false;
        if (mId != null ? !mId.equals(that.mId) : that.mId != null) return false;
        if (mName != null ? !mName.equals(that.mName) : that.mName != null) return false;
        if (mSound != null ? !mSound.equals(that.mSound) : that.mSound != null) return false;
        return Arrays.equals(mVibration, that.mVibration);

    }

    @Override
    public int hashCode() {
        int result = mId != null ? mId.hashCode() : 0;
        result = 31 * result + (mName != null ? mName.hashCode() : 0);
        result = 31 * result + mImportance;
        result = 31 * result + (mBypassDnd ? 1 : 0);
        result = 31 * result + mLockscreenVisibility;
        result = 31 * result + (mSound != null ? mSound.hashCode() : 0);
        result = 31 * result + (mLights ? 1 : 0);
        result = 31 * result + Arrays.hashCode(mVibration);
        result = 31 * result + mUserLockedFields;
        result = 31 * result + (mVibrationEnabled ? 1 : 0);
        result = 31 * result + (mShowBadge ? 1 : 0);
        result = 31 * result + (mDeleted ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "NotificationChannel{" +
                "mId='" + mId + '\'' +
                ", mName=" + mName +
                ", mImportance=" + mImportance +
                ", mBypassDnd=" + mBypassDnd +
                ", mLockscreenVisibility=" + mLockscreenVisibility +
                ", mSound=" + mSound +
                ", mLights=" + mLights +
                ", mVibration=" + Arrays.toString(mVibration) +
                ", mUserLockedFields=" + mUserLockedFields +
                ", mVibrationEnabled=" + mVibrationEnabled +
                ", mShowBadge=" + mShowBadge +
                ", mDeleted=" + mDeleted +
                '}';
    }
}
