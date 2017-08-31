/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define LOG_TAG "statsd"

#include "StatsService.h"

#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <cutils/log.h>
#include <private/android_filesystem_config.h>
#include <utils/Looper.h>
#include <utils/String16.h>

#include <unistd.h>
#include <stdio.h>

using namespace android;

// ================================================================================
StatsService::StatsService(const sp<Looper>& handlerLooper)
{
    ALOGD("stats service constructed");
}

StatsService::~StatsService()
{
}

// Implement our own because the default binder implementation isn't
// properly handling SHELL_COMMAND_TRANSACTION
status_t
StatsService::onTransact(uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
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
            sp<IShellCallback> shellCallback = IShellCallback::asInterface(
                    data.readStrongBinder());
            sp<IResultReceiver> resultReceiver = IResultReceiver::asInterface(
                    data.readStrongBinder());

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
        default: {
            return BnStatsManager::onTransact(code, data, reply, flags);
        }
    }
}

status_t
StatsService::dump(int fd, const Vector<String16>& args)
{
    FILE* out = fdopen(fd, "w");
    if (out == NULL) {
        return NO_MEMORY;  // the fd is already open
    }

    fprintf(out, "StatsService::dump:");
    ALOGD("StatsService::dump:");
    const int N = args.size();
    for (int i=0; i<N; i++) {
        fprintf(out, " %s", String8(args[i]).string());
        ALOGD("   %s", String8(args[i]).string());
    }
    fprintf(out, "\n");

    fclose(out);
    return NO_ERROR;
}

status_t
StatsService::command(FILE* in, FILE* out, FILE* err, Vector<String8>& args)
{
    fprintf(out, "StatsService::command:");
    ALOGD("StatsService::command:");
    const int N = args.size();
    for (int i=0; i<N; i++) {
        fprintf(out, " %s", String8(args[i]).string());
        ALOGD("   %s", String8(args[i]).string());
    }
    fprintf(out, "\n");

    return NO_ERROR;
}

Status
StatsService::systemRunning()
{
    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                "Only system uid can call systemRunning");
    }

    // When system_server is up and running, schedule the dropbox task to run.
    ALOGD("StatsService::systemRunning");

    return Status::ok();
}

