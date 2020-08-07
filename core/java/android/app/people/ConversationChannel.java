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

package android.app.people;

import android.app.NotificationChannel;
import android.content.pm.ShortcutInfo;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The non-customized notification channel of a conversation. It contains the information to render
 * the conversation and allows the user to open and customize the conversation setting.
 *
 * @hide
 */
public final class ConversationChannel implements Parcelable {

    private ShortcutInfo mShortcutInfo;
    private NotificationChannel mParentNotificationChannel;
    private long mLastEventTimestamp;
    private boolean mHasActiveNotifications;

    public static final Creator<ConversationChannel> CREATOR = new Creator<ConversationChannel>() {
        @Override
        public ConversationChannel createFromParcel(Parcel in) {
            return new ConversationChannel(in);
        }

        @Override
        public ConversationChannel[] newArray(int size) {
            return new ConversationChannel[size];
        }
    };

    public ConversationChannel(ShortcutInfo shortcutInfo,
            NotificationChannel parentNotificationChannel, long lastEventTimestamp,
            boolean hasActiveNotifications) {
        mShortcutInfo = shortcutInfo;
        mParentNotificationChannel = parentNotificationChannel;
        mLastEventTimestamp = lastEventTimestamp;
        mHasActiveNotifications = hasActiveNotifications;
    }

    public ConversationChannel(Parcel in) {
        mShortcutInfo = in.readParcelable(ShortcutInfo.class.getClassLoader());
        mParentNotificationChannel = in.readParcelable(NotificationChannel.class.getClassLoader());
        mLastEventTimestamp = in.readLong();
        mHasActiveNotifications = in.readBoolean();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mShortcutInfo, flags);
        dest.writeParcelable(mParentNotificationChannel, flags);
        dest.writeLong(mLastEventTimestamp);
        dest.writeBoolean(mHasActiveNotifications);
    }

    public ShortcutInfo getShortcutInfo() {
        return mShortcutInfo;
    }

    public NotificationChannel getParentNotificationChannel() {
        return mParentNotificationChannel;
    }

    public long getLastEventTimestamp() {
        return mLastEventTimestamp;
    }

    /**
     * Whether this conversation has any active notifications. If it's true, the shortcut for this
     * conversation can't be uncached until all its active notifications are dismissed.
     */
    public boolean hasActiveNotifications() {
        return mHasActiveNotifications;
    }
}
