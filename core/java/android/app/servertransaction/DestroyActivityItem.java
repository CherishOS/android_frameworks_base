/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;

import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;

/**
 * Request to destroy an activity.
 * @hide
 */
public class DestroyActivityItem extends ActivityLifecycleItem {

    private final boolean mFinished;
    private final int mConfigChanges;

    public DestroyActivityItem(boolean finished, int configChanges) {
        mFinished = finished;
        mConfigChanges = configChanges;
    }

    @Override
    public void execute(ClientTransactionHandler client, IBinder token) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityDestroy");
        client.handleDestroyActivity(token, mFinished, mConfigChanges,
                false /* getNonConfigInstance */);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public int getTargetState() {
        return DESTROYED;
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mFinished);
        dest.writeInt(mConfigChanges);
    }

    /** Read from Parcel. */
    private DestroyActivityItem(Parcel in) {
        mFinished = in.readBoolean();
        mConfigChanges = in.readInt();
    }

    public static final Creator<DestroyActivityItem> CREATOR =
            new Creator<DestroyActivityItem>() {
        public DestroyActivityItem createFromParcel(Parcel in) {
            return new DestroyActivityItem(in);
        }

        public DestroyActivityItem[] newArray(int size) {
            return new DestroyActivityItem[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DestroyActivityItem other = (DestroyActivityItem) o;
        return mFinished == other.mFinished && mConfigChanges == other.mConfigChanges;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (mFinished ? 1 : 0);
        result = 31 * result + mConfigChanges;
        return result;
    }
}
