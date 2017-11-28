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

import static android.app.servertransaction.ActivityLifecycleItem.PAUSED;
import static android.app.servertransaction.ActivityLifecycleItem.RESUMED;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Trace;

import com.android.internal.content.ReferrerIntent;

import java.util.List;

/**
 * New intent message.
 * @hide
 */
public class NewIntentItem extends ClientTransactionItem {

    private final List<ReferrerIntent> mIntents;
    private final boolean mPause;

    public NewIntentItem(List<ReferrerIntent> intents, boolean pause) {
        mIntents = intents;
        mPause = pause;
    }

    @Override
    public int getPreExecutionState() {
        return PAUSED;
    }

    @Override
    public int getPostExecutionState() {
        return RESUMED;
    }

    @Override
    public void execute(android.app.ClientTransactionHandler client, IBinder token) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "activityNewIntent");
        client.handleNewIntent(token, mIntents, mPause);
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mPause);
        dest.writeTypedList(mIntents, flags);
    }

    /** Read from Parcel. */
    private NewIntentItem(Parcel in) {
        mPause = in.readBoolean();
        mIntents = in.createTypedArrayList(ReferrerIntent.CREATOR);
    }

    public static final Parcelable.Creator<NewIntentItem> CREATOR =
            new Parcelable.Creator<NewIntentItem>() {
        public NewIntentItem createFromParcel(Parcel in) {
            return new NewIntentItem(in);
        }

        public NewIntentItem[] newArray(int size) {
            return new NewIntentItem[size];
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
        final NewIntentItem other = (NewIntentItem) o;
        return mPause == other.mPause && mIntents.equals(other.mIntents);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (mPause ? 1 : 0);
        result = 31 * result + mIntents.hashCode();
        return result;
    }
}
