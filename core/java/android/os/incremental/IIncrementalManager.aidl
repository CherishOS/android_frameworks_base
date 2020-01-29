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

package android.os.incremental;

import android.content.pm.FileSystemControlParcel;
import android.content.pm.DataLoaderParamsParcel;
import android.content.pm.IDataLoaderStatusListener;

/**
 * Binder service to receive calls from native Incremental Service and handle Java tasks such as
 * looking up data loader service package names, binding and talking to the data loader service.
 * @hide
 */
interface IIncrementalManager {
    boolean prepareDataLoader(int mountId,
        in FileSystemControlParcel control,
        in DataLoaderParamsParcel params,
        in IDataLoaderStatusListener listener);
    boolean startDataLoader(int mountId);
    void showHealthBlockedUI(int mountId);
    void destroyDataLoader(int mountId);

    // fileId is a 16 byte long identifier.
    void newFileForDataLoader(int mountId, in byte[] fileId, in byte[] metadata);
}
