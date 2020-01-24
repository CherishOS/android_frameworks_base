/**
 * Copyright 2019, The Android Open Source Project
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
package android.app.blob;

import android.app.blob.BlobHandle;
import android.app.blob.IBlobStoreSession;
import android.os.RemoteCallback;

/** {@hide} */
interface IBlobStoreManager {
    long createSession(in BlobHandle handle, in String packageName);
    IBlobStoreSession openSession(long sessionId, in String packageName);
    ParcelFileDescriptor openBlob(in BlobHandle handle, in String packageName);
    void deleteSession(long sessionId, in String packageName);

    void acquireLease(in BlobHandle handle, int descriptionResId, long leaseTimeout,
            in String packageName);
    void releaseLease(in BlobHandle handle, in String packageName);

    void waitForIdle(in RemoteCallback callback);
}