/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.compat.Compatibility;
import android.os.Process;
import android.util.Log;

import java.util.Arrays;

/**
 * App process implementation of the {@link Compatibility} API.
 *
 * @hide
 */
public final class AppCompatCallbacks extends Compatibility.Callbacks {

    private static final String TAG = "Compatibility";

    private final long[] mDisabledChanges;

    /**
     * Install this class into the current process.
     *
     * @param disabledChanges Set of compatibility changes that are disabled for this process.
     */
    public static void install(long[] disabledChanges) {
        Compatibility.setCallbacks(new AppCompatCallbacks(disabledChanges));
    }

    private AppCompatCallbacks(long[] disabledChanges) {
        mDisabledChanges = Arrays.copyOf(disabledChanges, disabledChanges.length);
        Arrays.sort(mDisabledChanges);
    }

    protected void reportChange(long changeId) {
        Log.d(TAG, "Compat change reported: " + changeId + "; UID " + Process.myUid());
        // TODO log via StatsLog
    }

    protected boolean isChangeEnabled(long changeId) {
        if (Arrays.binarySearch(mDisabledChanges, changeId) < 0) {
            // Not present in the disabled array
            reportChange(changeId);
            return true;
        }
        return false;
    }

}
