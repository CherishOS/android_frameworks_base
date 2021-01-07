/**
 * Copyright (c) 2020, The Android Open Source Project
 *
 * Licensed under the Apache License,  2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.service.notification;

import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_ALERTING;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_CONVERSATIONS;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_SILENT;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

/**
 * Specifies a filter for what types of notifications should be bridged to notification listeners.
 * Each requested listener will have their own filter instance.
 * @hide
 */
public class NotificationListenerFilter implements Parcelable {
    private int mAllowedNotificationTypes;
    private ArraySet<String> mDisallowedPackages;

    public NotificationListenerFilter() {
        mAllowedNotificationTypes = FLAG_FILTER_TYPE_CONVERSATIONS
                | FLAG_FILTER_TYPE_ALERTING
                | FLAG_FILTER_TYPE_SILENT;
        mDisallowedPackages = new ArraySet<>();
    }

    public NotificationListenerFilter(int types, ArraySet<String> pkgs) {
        mAllowedNotificationTypes = types;
        mDisallowedPackages = pkgs;
    }

    /**
     * @hide
     */
    protected NotificationListenerFilter(Parcel in) {
        mAllowedNotificationTypes = in.readInt();
        mDisallowedPackages = (ArraySet<String>) in.readArraySet(String.class.getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mAllowedNotificationTypes);
        dest.writeArraySet(mDisallowedPackages);
    }

    public static final Creator<NotificationListenerFilter> CREATOR =
            new Creator<NotificationListenerFilter>() {
                @Override
                public NotificationListenerFilter createFromParcel(Parcel in) {
                    return new NotificationListenerFilter(in);
                }

                @Override
                public NotificationListenerFilter[] newArray(int size) {
                    return new NotificationListenerFilter[size];
                }
            };

    public boolean isTypeAllowed(int type) {
        return (mAllowedNotificationTypes & type) != 0;
    }

    public boolean isPackageAllowed(String pkg) {
        return !mDisallowedPackages.contains(pkg);
    }

    public int getTypes() {
        return mAllowedNotificationTypes;
    }

    public ArraySet<String> getDisallowedPackages() {
        return mDisallowedPackages;
    }

    public void setTypes(int types) {
        mAllowedNotificationTypes = types;
    }

    public void setDisallowedPackages(ArraySet<String> pkgs) {
        mDisallowedPackages = pkgs;
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
