/**
 * Copyright (c) 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package android.content.rollback;

import android.content.pm.ParceledListSlice;
import android.content.pm.StringParceledListSlice;
import android.content.rollback.RollbackInfo;
import android.content.IntentSender;

/** {@hide} */
interface IRollbackManager {

    RollbackInfo getAvailableRollback(String packageName);

    StringParceledListSlice getPackagesWithAvailableRollbacks();

    ParceledListSlice getRecentlyExecutedRollbacks();

    void executeRollback(in RollbackInfo rollback, String callerPackageName,
            in IntentSender statusReceiver);

    // Exposed for test purposes only.
    void reloadPersistedData();

    // Exposed for test purposes only.
    void expireRollbackForPackage(String packageName);
}
