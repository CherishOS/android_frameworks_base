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

#define DEBUG true  // STOPSHIP if true
#include "Log.h"

#include "storage/StorageManager.h"
#include "android-base/stringprintf.h"

#include <android-base/file.h>
#include <dirent.h>

namespace android {
namespace os {
namespace statsd {

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_MESSAGE;
using std::map;

#define STATS_SERVICE_DIR "/data/misc/stats-service"

// for ConfigMetricsReportList
const int FIELD_ID_REPORTS = 2;

using android::base::StringPrintf;
using std::unique_ptr;

void StorageManager::writeFile(const char* file, const void* buffer, int numBytes) {
    int fd = open(file, O_WRONLY | O_CREAT | O_CLOEXEC, S_IRUSR | S_IWUSR);
    if (fd == -1) {
        VLOG("Attempt to access %s but failed", file);
        return;
    }

    int result = write(fd, buffer, numBytes);
    if (result == numBytes) {
        VLOG("Successfully wrote %s", file);
    } else {
        VLOG("Failed to write %s", file);
    }
    close(fd);
}

void StorageManager::deleteFile(const char* file) {
    if (remove(file) != 0) {
        VLOG("Attempt to delete %s but is not found", file);
    } else {
        VLOG("Successfully deleted %s", file);
    }
}

void StorageManager::deleteAllFiles(const char* path) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(path), closedir);
    if (dir == NULL) {
        VLOG("Directory does not exist: %s", path);
        return;
    }

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') continue;
        deleteFile(StringPrintf("%s/%s", path, name).c_str());
    }
}

void StorageManager::deletePrefixedFiles(const char* path, const char* prefix) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(path), closedir);
    if (dir == NULL) {
        VLOG("Directory does not exist: %s", path);
        return;
    }

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.' || strncmp(name, prefix, strlen(prefix)) != 0) {
            continue;
        }
        deleteFile(StringPrintf("%s/%s", path, name).c_str());
    }
}

void StorageManager::sendBroadcast(const char* path,
                                   const std::function<void(const ConfigKey&)>& sendBroadcast) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(path), closedir);
    if (dir == NULL) {
        VLOG("no stats-data directory on disk");
        return;
    }

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') continue;
        VLOG("file %s", name);

        int index = 0;
        int uid = 0;
        string configName;
        char* substr = strtok(name, "-");
        // Timestamp lives at index 2 but we skip parsing it as it's not needed.
        while (substr != nullptr && index < 2) {
            if (index) {
                uid = atoi(substr);
            } else {
                configName = substr;
            }
            index++;
        }
        if (index < 2) continue;

        sendBroadcast(ConfigKey(uid, configName));
    }
}

void StorageManager::appendConfigMetricsReport(const char* path, ProtoOutputStream& proto) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(path), closedir);
    if (dir == NULL) {
        VLOG("Path %s does not exist", path);
        return;
    }

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') continue;
        VLOG("file %s", name);

        int index = 0;
        int uid = 0;
        string configName;
        char* substr = strtok(name, "-");
        // Timestamp lives at index 2 but we skip parsing it as it's not needed.
        while (substr != nullptr && index < 2) {
            if (index) {
                uid = atoi(substr);
            } else {
                configName = substr;
            }
            index++;
        }
        if (index < 2) continue;
        string file_name = StringPrintf("%s/%s", path, name);
        int fd = open(file_name.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd != -1) {
            string content;
            if (android::base::ReadFdToString(fd, &content)) {
                proto.write(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_REPORTS,
                            content.c_str());
            }
            close(fd);
        }

        // Remove file from disk after reading.
        remove(file_name.c_str());
    }
}

void StorageManager::readConfigFromDisk(map<ConfigKey, StatsdConfig>& configsMap) {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(STATS_SERVICE_DIR), closedir);
    if (dir == NULL) {
        VLOG("no default config on disk");
        return;
    }

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') continue;
        VLOG("file %s", name);

        int index = 0;
        int uid = 0;
        string configName;
        char* substr = strtok(name, "-");
        // Timestamp lives at index 2 but we skip parsing it as it's not needed.
        while (substr != nullptr && index < 2) {
            if (index) {
                uid = atoi(substr);
            } else {
                configName = substr;
            }
            index++;
        }
        if (index < 2) continue;
        string file_name = StringPrintf("%s/%s", STATS_SERVICE_DIR, name);
        VLOG("full file %s", file_name.c_str());
        int fd = open(file_name.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd != -1) {
            string content;
            if (android::base::ReadFdToString(fd, &content)) {
                StatsdConfig config;
                if (config.ParseFromString(content)) {
                    configsMap[ConfigKey(uid, configName)] = config;
                    VLOG("map key uid=%d|name=%s", uid, name);
                }
            }
            close(fd);
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
