/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef STORAGE_MANAGER_H
#define STORAGE_MANAGER_H

#include <android/util/ProtoOutputStream.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include "packages/UidMap.h"

namespace android {
namespace os {
namespace statsd {

using android::util::ProtoOutputStream;

class StorageManager : public virtual RefBase {
public:
    /**
     * Writes a given byte array as a file to the specified file path.
     */
    static void writeFile(const char* file, const void* buffer, int numBytes);

    /**
     * Deletes a single file given a file name.
     */
    static void deleteFile(const char* file);

    /**
     * Deletes all files in a given directory.
     */
    static void deleteAllFiles(const char* path);

    /**
     * Deletes all files whose name matches with a provided prefix.
     */
    static void deletePrefixedFiles(const char* path, const char* prefix);

    /**
     * Send broadcasts to relevant receiver for each data stored on disk.
     */
    static void sendBroadcast(const char* path,
                              const std::function<void(const ConfigKey&)>& sendBroadcast);

    /**
     * Appends ConfigMetricsReport found on disk to the specific proto and
     * delete it.
     */
    static void appendConfigMetricsReport(const char* path, ProtoOutputStream& proto);

    /**
     * Call to load the saved configs from disk.
     */
    static void readConfigFromDisk(std::map<ConfigKey, StatsdConfig>& configsMap);
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STORAGE_MANAGER_H
