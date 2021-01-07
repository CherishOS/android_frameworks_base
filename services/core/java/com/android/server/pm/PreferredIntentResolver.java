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

package com.android.server.pm;

import android.annotation.NonNull;
import android.content.IntentFilter;

import com.android.server.WatchableIntentResolver;
import com.android.server.utils.Snappable;

import java.io.PrintWriter;
import java.util.ArrayList;

public class PreferredIntentResolver
        extends WatchableIntentResolver<PreferredActivity, PreferredActivity>
        implements Snappable {
    @Override
    protected PreferredActivity[] newArray(int size) {
        return new PreferredActivity[size];
    }

    @Override
    protected boolean isPackageForFilter(String packageName, PreferredActivity filter) {
        return packageName.equals(filter.mPref.mComponent.getPackageName());
    }

    @Override
    protected void dumpFilter(PrintWriter out, String prefix,
            PreferredActivity filter) {
        filter.mPref.dump(out, prefix, filter);
    }

    @Override
    protected IntentFilter getIntentFilter(@NonNull PreferredActivity input) {
        return input;
    }

    public boolean shouldAddPreferredActivity(PreferredActivity pa) {
        ArrayList<PreferredActivity> pal = findFilters(pa);
        if (pal == null || pal.isEmpty()) {
            return true;
        }
        if (!pa.mPref.mAlways) {
            return false;
        }
        final int activityCount = pal.size();
        for (int i = 0; i < activityCount; i++) {
            PreferredActivity cur = pal.get(i);
            if (cur.mPref.mAlways
                    && cur.mPref.mMatch == (pa.mPref.mMatch & IntentFilter.MATCH_CATEGORY_MASK)
                    && cur.mPref.sameSet(pa.mPref)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Return a snapshot of the current object.  The snapshot is a read-only copy suitable
     * for read-only methods.
     * @return A snapshot of the current object.
     */
    public PreferredIntentResolver snapshot() {
        PreferredIntentResolver result = new PreferredIntentResolver();
        result.copyFrom(this);
        return result;
    }
}
