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

#include "StatsService.h"
#include "android-base/stringprintf.h"
#include "config/ConfigKey.h"
#include "config/ConfigManager.h"
#include "guardrail/MemoryLeakTrackUtil.h"
#include "guardrail/StatsdStats.h"
#include "storage/StorageManager.h"

#include <android-base/file.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <dirent.h>
#include <frameworks/base/cmds/statsd/src/statsd_config.pb.h>
#include <private/android_filesystem_config.h>
#include <utils/Looper.h>
#include <utils/String16.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/system_properties.h>
#include <unistd.h>

using namespace android;

namespace android {
namespace os {
namespace statsd {

constexpr const char* kPermissionDump = "android.permission.DUMP";
#define STATS_SERVICE_DIR "/data/misc/stats-service"

// ======================================================================
/**
 * Watches for the death of the stats companion (system process).
 */
class CompanionDeathRecipient : public IBinder::DeathRecipient {
public:
    CompanionDeathRecipient(const sp<AnomalyMonitor>& anomalyMonitor);
    virtual void binderDied(const wp<IBinder>& who);

private:
    const sp<AnomalyMonitor> mAnomalyMonitor;
};

CompanionDeathRecipient::CompanionDeathRecipient(const sp<AnomalyMonitor>& anomalyMonitor)
    : mAnomalyMonitor(anomalyMonitor) {
}

void CompanionDeathRecipient::binderDied(const wp<IBinder>& who) {
    ALOGW("statscompanion service died");
    mAnomalyMonitor->setStatsCompanionService(nullptr);
}

// ======================================================================
StatsService::StatsService(const sp<Looper>& handlerLooper)
    : mAnomalyMonitor(new AnomalyMonitor(MIN_DIFF_TO_UPDATE_REGISTERED_ALARM_SECS))
{
    mUidMap = new UidMap();
    mConfigManager = new ConfigManager();
    mProcessor = new StatsLogProcessor(mUidMap, mAnomalyMonitor, time(nullptr), [this](const ConfigKey& key) {
        sp<IStatsCompanionService> sc = getStatsCompanionService();
        auto receiver = mConfigManager->GetConfigReceiver(key);
        if (sc == nullptr) {
            VLOG("Could not find StatsCompanionService");
        } else if (receiver.first.size() == 0) {
            VLOG("Statscompanion could not find a broadcast receiver for %s",
                 key.ToString().c_str());
        } else {
            sc->sendBroadcast(String16(receiver.first.c_str()),
                              String16(receiver.second.c_str()));
        }
    });

    mConfigManager->AddListener(mProcessor);

    init_system_properties();
}

StatsService::~StatsService() {
}

void StatsService::init_system_properties() {
    mEngBuild = false;
    const prop_info* buildType = __system_property_find("ro.build.type");
    if (buildType != NULL) {
        __system_property_read_callback(buildType, init_build_type_callback, this);
    }
}

void StatsService::init_build_type_callback(void* cookie, const char* /*name*/, const char* value,
                                            uint32_t serial) {
    if (0 == strcmp("eng", value) || 0 == strcmp("userdebug", value)) {
        reinterpret_cast<StatsService*>(cookie)->mEngBuild = true;
    }
}

/**
 * Implement our own because the default binder implementation isn't
 * properly handling SHELL_COMMAND_TRANSACTION.
 */
status_t StatsService::onTransact(uint32_t code, const Parcel& data, Parcel* reply,
                                  uint32_t flags) {
    status_t err;

    switch (code) {
        case SHELL_COMMAND_TRANSACTION: {
            int in = data.readFileDescriptor();
            int out = data.readFileDescriptor();
            int err = data.readFileDescriptor();
            int argc = data.readInt32();
            Vector<String8> args;
            for (int i = 0; i < argc && data.dataAvail() > 0; i++) {
                args.add(String8(data.readString16()));
            }
            sp<IShellCallback> shellCallback = IShellCallback::asInterface(data.readStrongBinder());
            sp<IResultReceiver> resultReceiver =
                    IResultReceiver::asInterface(data.readStrongBinder());

            FILE* fin = fdopen(in, "r");
            FILE* fout = fdopen(out, "w");
            FILE* ferr = fdopen(err, "w");

            if (fin == NULL || fout == NULL || ferr == NULL) {
                resultReceiver->send(NO_MEMORY);
            } else {
                err = command(fin, fout, ferr, args);
                resultReceiver->send(err);
            }

            if (fin != NULL) {
                fflush(fin);
                fclose(fin);
            }
            if (fout != NULL) {
                fflush(fout);
                fclose(fout);
            }
            if (fout != NULL) {
                fflush(ferr);
                fclose(ferr);
            }

            return NO_ERROR;
        }
        default: { return BnStatsManager::onTransact(code, data, reply, flags); }
    }
}

/**
 * Write debugging data about statsd.
 */
status_t StatsService::dump(int fd, const Vector<String16>& args) {
    FILE* out = fdopen(fd, "w");
    if (out == NULL) {
        return NO_MEMORY;  // the fd is already open
    }

    // TODO: Proto format for incident reports
    dump_impl(out);

    fclose(out);
    return NO_ERROR;
}

/**
 * Write debugging data about statsd in text format.
 */
void StatsService::dump_impl(FILE* out) {
    mConfigManager->Dump(out);
    StatsdStats::getInstance().dumpStats(out);
}

/**
 * Implementation of the adb shell cmd stats command.
 */
status_t StatsService::command(FILE* in, FILE* out, FILE* err, Vector<String8>& args) {
    // TODO: Permission check

    const int argCount = args.size();
    if (argCount >= 1) {
        // adb shell cmd stats config ...
        if (!args[0].compare(String8("config"))) {
            return cmd_config(in, out, err, args);
        }

        if (!args[0].compare(String8("print-uid-map"))) {
            return cmd_print_uid_map(out, args);
        }

        if (!args[0].compare(String8("dump-report"))) {
            return cmd_dump_report(out, err, args);
        }

        if (!args[0].compare(String8("pull-source")) && args.size() > 1) {
            return cmd_print_pulled_metrics(out, args);
        }

        if (!args[0].compare(String8("send-broadcast"))) {
            return cmd_trigger_broadcast(out, args);
        }

        if (!args[0].compare(String8("print-stats"))) {
            return cmd_print_stats(out, args);
        }

        if (!args[0].compare(String8("meminfo"))) {
            return cmd_dump_memory_info(out);
        }

        if (!args[0].compare(String8("write-to-disk"))) {
            return cmd_write_data_to_disk(out);
        }
    }

    print_cmd_help(out);
    return NO_ERROR;
}

void StatsService::print_cmd_help(FILE* out) {
    fprintf(out,
            "usage: adb shell cmd stats print-stats-log [tag_required] "
            "[timestamp_nsec_optional]\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats meminfo\n");
    fprintf(out, "\n");
    fprintf(out, "  Prints the malloc debug information. You need to run the following first: \n");
    fprintf(out, "   # adb shell stop\n");
    fprintf(out, "   # adb shell setprop libc.debug.malloc.program statsd \n");
    fprintf(out, "   # adb shell setprop libc.debug.malloc.options backtrace \n");
    fprintf(out, "   # adb shell start\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats print-uid-map [PKG]\n");
    fprintf(out, "\n");
    fprintf(out, "  Prints the UID, app name, version mapping.\n");
    fprintf(out, "  PKG           Optional package name to print the uids of the package\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats pull-source [int] \n");
    fprintf(out, "\n");
    fprintf(out, "  Prints the output of a pulled metrics source (int indicates source)\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats write-to-disk \n");
    fprintf(out, "\n");
    fprintf(out, "  Flushes all data on memory to disk.\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats config remove [UID] [NAME]\n");
    fprintf(out, "usage: adb shell cmd stats config update [UID] NAME\n");
    fprintf(out, "\n");
    fprintf(out, "  Adds, updates or removes a configuration. The proto should be in\n");
    fprintf(out, "  wire-encoded protobuf format and passed via stdin. If no UID and name is\n");
    fprintf(out, "  provided, then all configs will be removed from memory and disk.\n");
    fprintf(out, "\n");
    fprintf(out, "  UID           The uid to use. It is only possible to pass the UID\n");
    fprintf(out, "                parameter on eng builds. If UID is omitted the calling\n");
    fprintf(out, "                uid is used.\n");
    fprintf(out, "  NAME          The per-uid name to use\n");
    fprintf(out, "\n");
    fprintf(out, "\n              *Note: If both UID and NAME are omitted then all configs will\n");
    fprintf(out, "\n                     be removed from memory and disk!\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats dump-report [UID] NAME [--proto]\n");
    fprintf(out, "  Dump all metric data for a configuration.\n");
    fprintf(out, "  UID           The uid of the configuration. It is only possible to pass\n");
    fprintf(out, "                the UID parameter on eng builds. If UID is omitted the\n");
    fprintf(out, "                calling uid is used.\n");
    fprintf(out, "  NAME          The name of the configuration\n");
    fprintf(out, "  --proto       Print proto binary.\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats send-broadcast [UID] NAME\n");
    fprintf(out, "  Send a broadcast that triggers the subscriber to fetch metrics.\n");
    fprintf(out, "  UID           The uid of the configuration. It is only possible to pass\n");
    fprintf(out, "                the UID parameter on eng builds. If UID is omitted the\n");
    fprintf(out, "                calling uid is used.\n");
    fprintf(out, "  NAME          The name of the configuration\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: adb shell cmd stats print-stats\n");
    fprintf(out, "  Prints some basic stats.\n");
}

status_t StatsService::cmd_trigger_broadcast(FILE* out, Vector<String8>& args) {
    string name;
    bool good = false;
    int uid;
    const int argCount = args.size();
    if (argCount == 2) {
        // Automatically pick the UID
        uid = IPCThreadState::self()->getCallingUid();
        // TODO: What if this isn't a binder call? Should we fail?
        name.assign(args[1].c_str(), args[1].size());
        good = true;
    } else if (argCount == 3) {
        // If it's a userdebug or eng build, then the shell user can
        // impersonate other uids.
        if (mEngBuild) {
            const char* s = args[1].c_str();
            if (*s != '\0') {
                char* end = NULL;
                uid = strtol(s, &end, 0);
                if (*end == '\0') {
                    name.assign(args[2].c_str(), args[2].size());
                    good = true;
                }
            }
        } else {
            fprintf(out,
                    "The metrics can only be dumped for other UIDs on eng or userdebug "
                            "builds.\n");
        }
    }
    if (!good) {
        print_cmd_help(out);
        return UNKNOWN_ERROR;
    }
    auto receiver = mConfigManager->GetConfigReceiver(ConfigKey(uid, StrToInt64(name)));
    sp<IStatsCompanionService> sc = getStatsCompanionService();
    if (sc != nullptr) {
        sc->sendBroadcast(String16(receiver.first.c_str()), String16(receiver.second.c_str()));
        VLOG("StatsService::trigger broadcast succeeded to %s, %s", args[1].c_str(),
             args[2].c_str());
    } else {
        VLOG("Could not access statsCompanion");
    }

    return NO_ERROR;
}

status_t StatsService::cmd_config(FILE* in, FILE* out, FILE* err, Vector<String8>& args) {
    const int argCount = args.size();
    if (argCount >= 2) {
        if (args[1] == "update" || args[1] == "remove") {
            bool good = false;
            int uid = -1;
            string name;

            if (argCount == 3) {
                // Automatically pick the UID
                uid = IPCThreadState::self()->getCallingUid();
                // TODO: What if this isn't a binder call? Should we fail?
                name.assign(args[2].c_str(), args[2].size());
                good = true;
            } else if (argCount == 4) {
                // If it's a userdebug or eng build, then the shell user can
                // impersonate other uids.
                if (mEngBuild) {
                    const char* s = args[2].c_str();
                    if (*s != '\0') {
                        char* end = NULL;
                        uid = strtol(s, &end, 0);
                        if (*end == '\0') {
                            name.assign(args[3].c_str(), args[3].size());
                            good = true;
                        }
                    }
                } else {
                    fprintf(err,
                            "The config can only be set for other UIDs on eng or userdebug "
                            "builds.\n");
                }
            } else if (argCount == 2 && args[1] == "remove") {
                good = true;
            }

            if (!good) {
                // If arg parsing failed, print the help text and return an error.
                print_cmd_help(out);
                return UNKNOWN_ERROR;
            }

            if (args[1] == "update") {
                // Read stream into buffer.
                string buffer;
                if (!android::base::ReadFdToString(fileno(in), &buffer)) {
                    fprintf(err, "Error reading stream for StatsConfig.\n");
                    return UNKNOWN_ERROR;
                }

                // Parse buffer.
                StatsdConfig config;
                if (!config.ParseFromString(buffer)) {
                    fprintf(err, "Error parsing proto stream for StatsConfig.\n");
                    return UNKNOWN_ERROR;
                }

                // Add / update the config.
                mConfigManager->UpdateConfig(ConfigKey(uid, StrToInt64(name)), config);
            } else {
                if (argCount == 2) {
                    cmd_remove_all_configs(out);
                } else {
                    // Remove the config.
                    mConfigManager->RemoveConfig(ConfigKey(uid, StrToInt64(name)));
                }
            }

            return NO_ERROR;
        }
    }
    print_cmd_help(out);
    return UNKNOWN_ERROR;
}

status_t StatsService::cmd_dump_report(FILE* out, FILE* err, const Vector<String8>& args) {
    if (mProcessor != nullptr) {
        int argCount = args.size();
        bool good = false;
        bool proto = false;
        int uid;
        string name;
        if (!std::strcmp("--proto", args[argCount-1].c_str())) {
            proto = true;
            argCount -= 1;
        }
        if (argCount == 2) {
            // Automatically pick the UID
            uid = IPCThreadState::self()->getCallingUid();
            // TODO: What if this isn't a binder call? Should we fail?
            name.assign(args[1].c_str(), args[1].size());
            good = true;
        } else if (argCount == 3) {
            // If it's a userdebug or eng build, then the shell user can
            // impersonate other uids.
            if (mEngBuild) {
                const char* s = args[1].c_str();
                if (*s != '\0') {
                    char* end = NULL;
                    uid = strtol(s, &end, 0);
                    if (*end == '\0') {
                        name.assign(args[2].c_str(), args[2].size());
                        good = true;
                    }
                }
            } else {
                fprintf(out,
                        "The metrics can only be dumped for other UIDs on eng or userdebug "
                        "builds.\n");
            }
        }
        if (good) {
            vector<uint8_t> data;
            mProcessor->onDumpReport(ConfigKey(uid, StrToInt64(name)), &data);
            // TODO: print the returned StatsLogReport to file instead of printing to logcat.
            if (proto) {
                for (size_t i = 0; i < data.size(); i ++) {
                    fprintf(out, "%c", data[i]);
                }
            } else {
                fprintf(out, "Dump report for Config [%d,%s]\n", uid, name.c_str());
                fprintf(out, "See the StatsLogReport in logcat...\n");
            }
            return android::OK;
        } else {
            // If arg parsing failed, print the help text and return an error.
            print_cmd_help(out);
            return UNKNOWN_ERROR;
        }
    } else {
        fprintf(out, "Log processor does not exist...\n");
        return UNKNOWN_ERROR;
    }
}

status_t StatsService::cmd_print_stats(FILE* out, const Vector<String8>& args) {
    vector<ConfigKey> configs = mConfigManager->GetAllConfigKeys();
    for (const ConfigKey& key : configs) {
        fprintf(out, "Config %s uses %zu bytes\n", key.ToString().c_str(),
                mProcessor->GetMetricsSize(key));
    }
    StatsdStats& statsdStats = StatsdStats::getInstance();
    statsdStats.dumpStats(out);
    return NO_ERROR;
}

status_t StatsService::cmd_print_uid_map(FILE* out, const Vector<String8>& args) {
    if (args.size() > 1) {
        string pkg;
        pkg.assign(args[1].c_str(), args[1].size());
        auto uids = mUidMap->getAppUid(pkg);
        fprintf(out, "%s -> [ ", pkg.c_str());
        for (const auto& uid : uids) {
            fprintf(out, "%d ", uid);
        }
        fprintf(out, "]\n");
    } else {
        mUidMap->printUidMap(out);
    }
    return NO_ERROR;
}

status_t StatsService::cmd_write_data_to_disk(FILE* out) {
    fprintf(out, "Writing data to disk\n");
    mProcessor->WriteDataToDisk();
    return NO_ERROR;
}

status_t StatsService::cmd_print_pulled_metrics(FILE* out, const Vector<String8>& args) {
    int s = atoi(args[1].c_str());
    vector<shared_ptr<LogEvent> > stats;
    if (mStatsPullerManager.Pull(s, &stats)) {
        for (const auto& it : stats) {
            fprintf(out, "Pull from %d: %s\n", s, it->ToString().c_str());
        }
        fprintf(out, "Pull from %d: Received %zu elements\n", s, stats.size());
        return NO_ERROR;
    }
    return UNKNOWN_ERROR;
}

status_t StatsService::cmd_remove_all_configs(FILE* out) {
    fprintf(out, "Removing all configs...\n");
    VLOG("StatsService::cmd_remove_all_configs was called");
    mConfigManager->RemoveAllConfigs();
    StorageManager::deleteAllFiles(STATS_SERVICE_DIR);
    return NO_ERROR;
}

status_t StatsService::cmd_dump_memory_info(FILE* out) {
    std::string s = dumpMemInfo(100);
    fprintf(out, "Memory Info\n");
    fprintf(out, "%s", s.c_str());
    return NO_ERROR;
}

Status StatsService::informAllUidData(const vector<int32_t>& uid, const vector<int64_t>& version,
                                      const vector<String16>& app) {
    VLOG("StatsService::informAllUidData was called");

    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call informAllUidData");
    }

    mUidMap->updateMap(uid, version, app);
    VLOG("StatsService::informAllUidData succeeded");

    return Status::ok();
}

Status StatsService::informOnePackage(const String16& app, int32_t uid, int64_t version) {
    VLOG("StatsService::informOnePackage was called");

    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call informOnePackage");
    }
    mUidMap->updateApp(app, uid, version);
    return Status::ok();
}

Status StatsService::informOnePackageRemoved(const String16& app, int32_t uid) {
    VLOG("StatsService::informOnePackageRemoved was called");

    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call informOnePackageRemoved");
    }
    mUidMap->removeApp(app, uid);
    return Status::ok();
}

Status StatsService::informAnomalyAlarmFired() {
    VLOG("StatsService::informAnomalyAlarmFired was called");

    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call informAnomalyAlarmFired");
    }

    VLOG("StatsService::informAnomalyAlarmFired succeeded");
    uint64_t currentTimeSec = time(nullptr);
    std::unordered_set<sp<const AnomalyAlarm>, SpHash<AnomalyAlarm>> anomalySet =
            mAnomalyMonitor->popSoonerThan(static_cast<uint32_t>(currentTimeSec));
    mProcessor->onAnomalyAlarmFired(currentTimeSec * NS_PER_SEC, anomalySet);
    return Status::ok();
}

Status StatsService::informPollAlarmFired() {
    VLOG("StatsService::informPollAlarmFired was called");

    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call informPollAlarmFired");
    }

    mStatsPullerManager.OnAlarmFired();

    VLOG("StatsService::informPollAlarmFired succeeded");

    return Status::ok();
}

Status StatsService::systemRunning() {
    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call systemRunning");
    }

    // When system_server is up and running, schedule the dropbox task to run.
    VLOG("StatsService::systemRunning");

    sayHiToStatsCompanion();

    return Status::ok();
}

Status StatsService::writeDataToDisk() {
    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call systemRunning");
    }

    VLOG("StatsService::writeDataToDisk");

    mProcessor->WriteDataToDisk();

    return Status::ok();
}

void StatsService::sayHiToStatsCompanion() {
    // TODO: This method needs to be private. It is temporarily public and unsecured for testing
    // purposes.
    sp<IStatsCompanionService> statsCompanion = getStatsCompanionService();
    if (statsCompanion != nullptr) {
        VLOG("Telling statsCompanion that statsd is ready");
        statsCompanion->statsdReady();
    } else {
        VLOG("Could not access statsCompanion");
    }
}

sp<IStatsCompanionService> StatsService::getStatsCompanionService() {
    sp<IStatsCompanionService> statsCompanion = nullptr;
    // Get statscompanion service from service manager
    const sp<IServiceManager> sm(defaultServiceManager());
    if (sm != nullptr) {
        const String16 name("statscompanion");
        statsCompanion = interface_cast<IStatsCompanionService>(sm->checkService(name));
        if (statsCompanion == nullptr) {
            ALOGW("statscompanion service unavailable!");
            return nullptr;
        }
    }
    return statsCompanion;
}

Status StatsService::statsCompanionReady() {
    VLOG("StatsService::statsCompanionReady was called");

    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Only system uid can call statsCompanionReady");
    }

    sp<IStatsCompanionService> statsCompanion = getStatsCompanionService();
    if (statsCompanion == nullptr) {
        return Status::fromExceptionCode(
                Status::EX_NULL_POINTER,
                "statscompanion unavailable despite it contacting statsd!");
    }
    VLOG("StatsService::statsCompanionReady linking to statsCompanion.");
    IInterface::asBinder(statsCompanion)->linkToDeath(new CompanionDeathRecipient(mAnomalyMonitor));
    mAnomalyMonitor->setStatsCompanionService(statsCompanion);

    return Status::ok();
}

void StatsService::Startup() {
    mConfigManager->Startup();
}

void StatsService::OnLogEvent(LogEvent* event) {
    mProcessor->OnLogEvent(event);
}

Status StatsService::getData(int64_t key, vector<uint8_t>* output) {
    IPCThreadState* ipc = IPCThreadState::self();
    VLOG("StatsService::getData with Pid %i, Uid %i", ipc->getCallingPid(), ipc->getCallingUid());
    if (checkCallingPermission(String16(kPermissionDump))) {
        ConfigKey configKey(ipc->getCallingUid(), key);
        mProcessor->onDumpReport(configKey, output);
        return Status::ok();
    } else {
        return Status::fromExceptionCode(binder::Status::EX_SECURITY);
    }
}

Status StatsService::getMetadata(vector<uint8_t>* output) {
    IPCThreadState* ipc = IPCThreadState::self();
    VLOG("StatsService::getMetadata with Pid %i, Uid %i", ipc->getCallingPid(),
         ipc->getCallingUid());
    if (checkCallingPermission(String16(kPermissionDump))) {
        StatsdStats::getInstance().dumpStats(output, false); // Don't reset the counters.
        return Status::ok();
    } else {
        return Status::fromExceptionCode(binder::Status::EX_SECURITY);
    }
}

Status StatsService::addConfiguration(int64_t key,
                                      const vector <uint8_t>& config,
                                      const String16& package, const String16& cls,
                                      bool* success) {
    IPCThreadState* ipc = IPCThreadState::self();
    if (checkCallingPermission(String16(kPermissionDump))) {
        ConfigKey configKey(ipc->getCallingUid(), key);
        StatsdConfig cfg;
        if (!cfg.ParseFromArray(&config[0], config.size())) {
            *success = false;
            return Status::ok();
        }
        mConfigManager->UpdateConfig(configKey, cfg);
        mConfigManager->SetConfigReceiver(configKey, string(String8(package).string()),
                                          string(String8(cls).string()));
        *success = true;
        return Status::ok();
    } else {
        *success = false;
        return Status::fromExceptionCode(binder::Status::EX_SECURITY);
    }
}

Status StatsService::removeConfiguration(int64_t key, bool* success) {
    IPCThreadState* ipc = IPCThreadState::self();
    if (checkCallingPermission(String16(kPermissionDump))) {
        mConfigManager->RemoveConfig(ConfigKey(ipc->getCallingUid(), key));
        *success = true;
        return Status::ok();
    } else {
        *success = false;
        return Status::fromExceptionCode(binder::Status::EX_SECURITY);
    }
}

void StatsService::binderDied(const wp <IBinder>& who) {
}

}  // namespace statsd
}  // namespace os
}  // namespace android
