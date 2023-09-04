/*
 * Copyright (C) 2023 The Android Open Source Project
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
package android.content.pm;

import android.content.IntentSender;
import android.os.UserHandle;

/** {@hide} */
interface IPackageArchiverService {

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf={android.Manifest.permission.DELETE_PACKAGES,android.Manifest.permission.REQUEST_DELETE_PACKAGES})")
    void requestArchive(String packageName, String callerPackageName, in IntentSender statusReceiver, in UserHandle userHandle);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf={android.Manifest.permission.INSTALL_PACKAGES,android.Manifest.permission.REQUEST_INSTALL_PACKAGES})")
    void requestUnarchive(String packageName, String callerPackageName, in UserHandle userHandle);
}