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
#define DEBUG false
#include "Log.h"

#include "Section.h"

#include <dirent.h>
#include <errno.h>

#include <mutex>
#include <set>

#include <android-base/file.h>
#include <android-base/properties.h>
#include <android-base/stringprintf.h>
#include <android/util/protobuf.h>
#include <android/util/ProtoOutputStream.h>
#include <binder/IServiceManager.h>
#include <debuggerd/client.h>
#include <dumputils/dump_utils.h>
#include <log/log_event_list.h>
#include <log/log_read.h>
#include <log/logprint.h>
#include <private/android_logger.h>

#include "FdBuffer.h"
#include "Privacy.h"
#include "frameworks/base/core/proto/android/os/backtrace.proto.h"
#include "frameworks/base/core/proto/android/os/data.proto.h"
#include "frameworks/base/core/proto/android/util/log.proto.h"
#include "incidentd_util.h"

namespace android {
namespace os {
namespace incidentd {

using namespace android::base;
using namespace android::util;

// special section ids
const int FIELD_ID_INCIDENT_METADATA = 2;

// incident section parameters
const char INCIDENT_HELPER[] = "/system/bin/incident_helper";
const char* GZIP[] = {"/system/bin/gzip", NULL};

static pid_t fork_execute_incident_helper(const int id, Fpipe* p2cPipe, Fpipe* c2pPipe) {
    const char* ihArgs[]{INCIDENT_HELPER, "-s", String8::format("%d", id).string(), NULL};
    return fork_execute_cmd(const_cast<char**>(ihArgs), p2cPipe, c2pPipe);
}

bool section_requires_specific_mention(int sectionId) {
    switch (sectionId) {
        case 3025: // restricted_images
            return true;
        case 3026: // system_trace
            return true;
        default:
            return false;
    }
}

// ================================================================================
Section::Section(int i, int64_t timeoutMs)
    : id(i),
      timeoutMs(timeoutMs) {
}

Section::~Section() {}

// ================================================================================
static inline bool isSysfs(const char* filename) { return strncmp(filename, "/sys/", 5) == 0; }

FileSection::FileSection(int id, const char* filename, const int64_t timeoutMs)
    : Section(id, timeoutMs), mFilename(filename) {
    name = "file ";
    name += filename;
    mIsSysfs = isSysfs(filename);
}

FileSection::~FileSection() {}

status_t FileSection::Execute(ReportWriter* writer) const {
    // read from mFilename first, make sure the file is available
    // add O_CLOEXEC to make sure it is closed when exec incident helper
    unique_fd fd(open(mFilename, O_RDONLY | O_CLOEXEC));
    if (fd.get() == -1) {
        ALOGW("[%s] failed to open file", this->name.string());
        // There may be some devices/architectures that won't have the file.
        // Just return here without an error.
        return NO_ERROR;
    }

    FdBuffer buffer;
    Fpipe p2cPipe;
    Fpipe c2pPipe;
    // initiate pipes to pass data to/from incident_helper
    if (!p2cPipe.init() || !c2pPipe.init()) {
        ALOGW("[%s] failed to setup pipes", this->name.string());
        return -errno;
    }

    pid_t pid = fork_execute_incident_helper(this->id, &p2cPipe, &c2pPipe);
    if (pid == -1) {
        ALOGW("[%s] failed to fork", this->name.string());
        return -errno;
    }

    // parent process
    status_t readStatus = buffer.readProcessedDataInStream(fd.get(), std::move(p2cPipe.writeFd()),
                                                           std::move(c2pPipe.readFd()),
                                                           this->timeoutMs, mIsSysfs);
    writer->setSectionStats(buffer);
    if (readStatus != NO_ERROR || buffer.timedOut()) {
        ALOGW("[%s] failed to read data from incident helper: %s, timedout: %s",
              this->name.string(), strerror(-readStatus), buffer.timedOut() ? "true" : "false");
        kill_child(pid);
        return readStatus;
    }

    status_t ihStatus = wait_child(pid);
    if (ihStatus != NO_ERROR) {
        ALOGW("[%s] abnormal child process: %s", this->name.string(), strerror(-ihStatus));
        return ihStatus;
    }

    return writer->writeSection(buffer);
}
// ================================================================================
GZipSection::GZipSection(int id, const char* filename, ...) : Section(id) {
    va_list args;
    va_start(args, filename);
    mFilenames = varargs(filename, args);
    va_end(args);
    name = "gzip";
    for (int i = 0; mFilenames[i] != NULL; i++) {
        name += " ";
        name += mFilenames[i];
    }
}

GZipSection::~GZipSection() { free(mFilenames); }

status_t GZipSection::Execute(ReportWriter* writer) const {
    // Reads the files in order, use the first available one.
    int index = 0;
    unique_fd fd;
    while (mFilenames[index] != NULL) {
        fd.reset(open(mFilenames[index], O_RDONLY | O_CLOEXEC));
        if (fd.get() != -1) {
            break;
        }
        ALOGW("GZipSection failed to open file %s", mFilenames[index]);
        index++;  // look at the next file.
    }
    if (fd.get() == -1) {
        ALOGW("[%s] can't open all the files", this->name.string());
        return NO_ERROR;  // e.g. LAST_KMSG will reach here in user build.
    }
    FdBuffer buffer;
    Fpipe p2cPipe;
    Fpipe c2pPipe;
    // initiate pipes to pass data to/from gzip
    if (!p2cPipe.init() || !c2pPipe.init()) {
        ALOGW("[%s] failed to setup pipes", this->name.string());
        return -errno;
    }

    pid_t pid = fork_execute_cmd((char* const*)GZIP, &p2cPipe, &c2pPipe);
    if (pid == -1) {
        ALOGW("[%s] failed to fork", this->name.string());
        return -errno;
    }
    // parent process

    // construct Fdbuffer to output GZippedfileProto, the reason to do this instead of using
    // ProtoOutputStream is to avoid allocation of another buffer inside ProtoOutputStream.
    sp<EncodedBuffer> internalBuffer = buffer.data();
    internalBuffer->writeHeader((uint32_t)GZippedFileProto::FILENAME, WIRE_TYPE_LENGTH_DELIMITED);
    size_t fileLen = strlen(mFilenames[index]);
    internalBuffer->writeRawVarint32(fileLen);
    for (size_t i = 0; i < fileLen; i++) {
        internalBuffer->writeRawByte(mFilenames[index][i]);
    }
    internalBuffer->writeHeader((uint32_t)GZippedFileProto::GZIPPED_DATA,
                                WIRE_TYPE_LENGTH_DELIMITED);
    size_t editPos = internalBuffer->wp()->pos();
    internalBuffer->wp()->move(8);  // reserve 8 bytes for the varint of the data size.
    size_t dataBeginAt = internalBuffer->wp()->pos();
    VLOG("[%s] editPos=%zu, dataBeginAt=%zu", this->name.string(), editPos, dataBeginAt);

    status_t readStatus = buffer.readProcessedDataInStream(
            fd.get(), std::move(p2cPipe.writeFd()), std::move(c2pPipe.readFd()), this->timeoutMs,
            isSysfs(mFilenames[index]));
    writer->setSectionStats(buffer);
    if (readStatus != NO_ERROR || buffer.timedOut()) {
        ALOGW("[%s] failed to read data from gzip: %s, timedout: %s", this->name.string(),
              strerror(-readStatus), buffer.timedOut() ? "true" : "false");
        kill_child(pid);
        return readStatus;
    }

    status_t gzipStatus = wait_child(pid);
    if (gzipStatus != NO_ERROR) {
        ALOGW("[%s] abnormal child process: %s", this->name.string(), strerror(-gzipStatus));
        return gzipStatus;
    }
    // Revisit the actual size from gzip result and edit the internal buffer accordingly.
    size_t dataSize = buffer.size() - dataBeginAt;
    internalBuffer->wp()->rewind()->move(editPos);
    internalBuffer->writeRawVarint32(dataSize);
    internalBuffer->copy(dataBeginAt, dataSize);

    return writer->writeSection(buffer);
}

// ================================================================================
struct WorkerThreadData : public virtual RefBase {
    const WorkerThreadSection* section;
    Fpipe pipe;

    // Lock protects these fields
    mutex lock;
    bool workerDone;
    status_t workerError;

    explicit WorkerThreadData(const WorkerThreadSection* section);
    virtual ~WorkerThreadData();
};

WorkerThreadData::WorkerThreadData(const WorkerThreadSection* sec)
    : section(sec), workerDone(false), workerError(NO_ERROR) {}

WorkerThreadData::~WorkerThreadData() {}

// ================================================================================
WorkerThreadSection::WorkerThreadSection(int id, const int64_t timeoutMs)
    : Section(id, timeoutMs) {}

WorkerThreadSection::~WorkerThreadSection() {}

void sigpipe_handler(int signum) {
    if (signum == SIGPIPE) {
        ALOGE("Wrote to a broken pipe\n");
    } else {
        ALOGE("Received unexpected signal: %d\n", signum);
    }
}

static void* worker_thread_func(void* cookie) {
    // Don't crash the service if we write to a closed pipe (which can happen if
    // dumping times out).
    signal(SIGPIPE, sigpipe_handler);

    WorkerThreadData* data = (WorkerThreadData*)cookie;
    status_t err = data->section->BlockingCall(data->pipe.writeFd().get());

    {
        unique_lock<mutex> lock(data->lock);
        data->workerDone = true;
        data->workerError = err;
    }

    data->pipe.writeFd().reset();
    data->decStrong(data->section);
    // data might be gone now. don't use it after this point in this thread.
    return NULL;
}

status_t WorkerThreadSection::Execute(ReportWriter* writer) const {
    status_t err = NO_ERROR;
    pthread_t thread;
    pthread_attr_t attr;
    bool workerDone = false;
    FdBuffer buffer;

    // Data shared between this thread and the worker thread.
    sp<WorkerThreadData> data = new WorkerThreadData(this);

    // Create the pipe
    if (!data->pipe.init()) {
        return -errno;
    }

    // Create the thread
    err = pthread_attr_init(&attr);
    if (err != 0) {
        return -err;
    }
    // TODO: Do we need to tweak thread priority?
    err = pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (err != 0) {
        pthread_attr_destroy(&attr);
        return -err;
    }

    // The worker thread needs a reference and we can't let the count go to zero
    // if that thread is slow to start.
    data->incStrong(this);

    err = pthread_create(&thread, &attr, worker_thread_func, (void*)data.get());
    pthread_attr_destroy(&attr);
    if (err != 0) {
        data->decStrong(this);
        return -err;
    }

    // Loop reading until either the timeout or the worker side is done (i.e. eof).
    err = buffer.read(data->pipe.readFd().get(), this->timeoutMs);
    if (err != NO_ERROR) {
        ALOGE("[%s] reader failed with error '%s'", this->name.string(), strerror(-err));
    }

    // Done with the read fd. The worker thread closes the write one so
    // we never race and get here first.
    data->pipe.readFd().reset();

    // If the worker side is finished, then return its error (which may overwrite
    // our possible error -- but it's more interesting anyway). If not, then we timed out.
    {
        unique_lock<mutex> lock(data->lock);
        if (data->workerError != NO_ERROR) {
            err = data->workerError;
            ALOGE("[%s] worker failed with error '%s'", this->name.string(), strerror(-err));
        }
        workerDone = data->workerDone;
    }

    writer->setSectionStats(buffer);
    if (err != NO_ERROR) {
        char errMsg[128];
        snprintf(errMsg, 128, "[%s] failed with error '%s'",
            this->name.string(), strerror(-err));
        writer->error(this, err, "WorkerThreadSection failed.");
        return NO_ERROR;
    }
    if (buffer.truncated()) {
        ALOGW("[%s] too large, truncating", this->name.string());
        // Do not write a truncated section. It won't pass through the PrivacyFilter.
        return NO_ERROR;
    }
    if (!workerDone || buffer.timedOut()) {
        ALOGW("[%s] timed out", this->name.string());
        return NO_ERROR;
    }

    // Write the data that was collected
    return writer->writeSection(buffer);
}

// ================================================================================
CommandSection::CommandSection(int id, const int64_t timeoutMs, const char* command, ...)
    : Section(id, timeoutMs) {
    va_list args;
    va_start(args, command);
    mCommand = varargs(command, args);
    va_end(args);
    name = "cmd";
    for (int i = 0; mCommand[i] != NULL; i++) {
        name += " ";
        name += mCommand[i];
    }
}

CommandSection::CommandSection(int id, const char* command, ...) : Section(id) {
    va_list args;
    va_start(args, command);
    mCommand = varargs(command, args);
    va_end(args);
    name = "cmd";
    for (int i = 0; mCommand[i] != NULL; i++) {
        name += " ";
        name += mCommand[i];
    }
}

CommandSection::~CommandSection() { free(mCommand); }

status_t CommandSection::Execute(ReportWriter* writer) const {
    FdBuffer buffer;
    Fpipe cmdPipe;
    Fpipe ihPipe;

    if (!cmdPipe.init() || !ihPipe.init()) {
        ALOGW("[%s] failed to setup pipes", this->name.string());
        return -errno;
    }

    pid_t cmdPid = fork_execute_cmd((char* const*)mCommand, NULL, &cmdPipe);
    if (cmdPid == -1) {
        ALOGW("[%s] failed to fork", this->name.string());
        return -errno;
    }
    pid_t ihPid = fork_execute_incident_helper(this->id, &cmdPipe, &ihPipe);
    if (ihPid == -1) {
        ALOGW("[%s] failed to fork", this->name.string());
        return -errno;
    }

    cmdPipe.writeFd().reset();
    status_t readStatus = buffer.read(ihPipe.readFd().get(), this->timeoutMs);
    writer->setSectionStats(buffer);
    if (readStatus != NO_ERROR || buffer.timedOut()) {
        ALOGW("[%s] failed to read data from incident helper: %s, timedout: %s",
              this->name.string(), strerror(-readStatus), buffer.timedOut() ? "true" : "false");
        kill_child(cmdPid);
        kill_child(ihPid);
        return readStatus;
    }

    // Waiting for command here has one trade-off: the failed status of command won't be detected
    // until buffer timeout, but it has advatage on starting the data stream earlier.
    status_t cmdStatus = wait_child(cmdPid);
    status_t ihStatus = wait_child(ihPid);
    if (cmdStatus != NO_ERROR || ihStatus != NO_ERROR) {
        ALOGW("[%s] abnormal child processes, return status: command: %s, incident helper: %s",
              this->name.string(), strerror(-cmdStatus), strerror(-ihStatus));
        // Not a fatal error.
        return NO_ERROR;
    }

    return writer->writeSection(buffer);
}

// ================================================================================
DumpsysSection::DumpsysSection(int id, const char* service, ...)
    : WorkerThreadSection(id, REMOTE_CALL_TIMEOUT_MS), mService(service) {
    name = "dumpsys ";
    name += service;

    va_list args;
    va_start(args, service);
    while (true) {
        const char* arg = va_arg(args, const char*);
        if (arg == NULL) {
            break;
        }
        mArgs.add(String16(arg));
        name += " ";
        name += arg;
    }
    va_end(args);
}

DumpsysSection::~DumpsysSection() {}

status_t DumpsysSection::BlockingCall(int pipeWriteFd) const {
    // checkService won't wait for the service to show up like getService will.
    sp<IBinder> service = defaultServiceManager()->checkService(mService);

    if (service == NULL) {
        ALOGW("DumpsysSection: Can't lookup service: %s", String8(mService).string());
        return NAME_NOT_FOUND;
    }

    service->dump(pipeWriteFd, mArgs);

    return NO_ERROR;
}

// ================================================================================
// initialization only once in Section.cpp.
map<log_id_t, log_time> LogSection::gLastLogsRetrieved;

LogSection::LogSection(int id, log_id_t logID) : WorkerThreadSection(id), mLogID(logID) {
    name = "logcat ";
    name += android_log_id_to_name(logID);
    switch (logID) {
        case LOG_ID_EVENTS:
        case LOG_ID_STATS:
        case LOG_ID_SECURITY:
            mBinary = true;
            break;
        default:
            mBinary = false;
    }
}

LogSection::~LogSection() {}

static size_t trimTail(char const* buf, size_t len) {
    while (len > 0) {
        char c = buf[len - 1];
        if (c == '\0' || c == ' ' || c == '\n' || c == '\r' || c == ':') {
            len--;
        } else {
            break;
        }
    }
    return len;
}

static inline int32_t get4LE(uint8_t const* src) {
    return src[0] | (src[1] << 8) | (src[2] << 16) | (src[3] << 24);
}

status_t LogSection::BlockingCall(int pipeWriteFd) const {
    // Open log buffer and getting logs since last retrieved time if any.
    unique_ptr<logger_list, void (*)(logger_list*)> loggers(
            gLastLogsRetrieved.find(mLogID) == gLastLogsRetrieved.end()
                    ? android_logger_list_alloc(ANDROID_LOG_RDONLY | ANDROID_LOG_NONBLOCK, 0, 0)
                    : android_logger_list_alloc_time(ANDROID_LOG_RDONLY | ANDROID_LOG_NONBLOCK,
                                                     gLastLogsRetrieved[mLogID], 0),
            android_logger_list_free);

    if (android_logger_open(loggers.get(), mLogID) == NULL) {
        ALOGE("[%s] Can't get logger.", this->name.string());
        return -1;
    }

    log_msg msg;
    log_time lastTimestamp(0);

    ProtoOutputStream proto;
    while (true) {  // keeps reading until logd buffer is fully read.
        status_t err = android_logger_list_read(loggers.get(), &msg);
        // err = 0 - no content, unexpected connection drop or EOF.
        // err = +ive number - size of retrieved data from logger
        // err = -ive number, OS supplied error _except_ for -EAGAIN
        // err = -EAGAIN, graceful indication for ANDRODI_LOG_NONBLOCK that this is the end of data.
        if (err <= 0) {
            if (err != -EAGAIN) {
                ALOGW("[%s] fails to read a log_msg.\n", this->name.string());
            }
            // dump previous logs and don't consider this error a failure.
            break;
        }
        if (mBinary) {
            // remove the first uint32 which is tag's index in event log tags
            android_log_context context = create_android_log_parser(msg.msg() + sizeof(uint32_t),
                                                                    msg.len() - sizeof(uint32_t));
            ;
            android_log_list_element elem;

            lastTimestamp.tv_sec = msg.entry_v1.sec;
            lastTimestamp.tv_nsec = msg.entry_v1.nsec;

            // format a BinaryLogEntry
            uint64_t token = proto.start(LogProto::BINARY_LOGS);
            proto.write(BinaryLogEntry::SEC, msg.entry_v1.sec);
            proto.write(BinaryLogEntry::NANOSEC, msg.entry_v1.nsec);
            proto.write(BinaryLogEntry::UID, (int)msg.entry_v4.uid);
            proto.write(BinaryLogEntry::PID, msg.entry_v1.pid);
            proto.write(BinaryLogEntry::TID, msg.entry_v1.tid);
            proto.write(BinaryLogEntry::TAG_INDEX,
                        get4LE(reinterpret_cast<uint8_t const*>(msg.msg())));
            do {
                elem = android_log_read_next(context);
                uint64_t elemToken = proto.start(BinaryLogEntry::ELEMS);
                switch (elem.type) {
                    case EVENT_TYPE_INT:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_INT);
                        proto.write(BinaryLogEntry::Elem::VAL_INT32, (int)elem.data.int32);
                        break;
                    case EVENT_TYPE_LONG:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_LONG);
                        proto.write(BinaryLogEntry::Elem::VAL_INT64, (long long)elem.data.int64);
                        break;
                    case EVENT_TYPE_STRING:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_STRING);
                        proto.write(BinaryLogEntry::Elem::VAL_STRING, elem.data.string, elem.len);
                        break;
                    case EVENT_TYPE_FLOAT:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_FLOAT);
                        proto.write(BinaryLogEntry::Elem::VAL_FLOAT, elem.data.float32);
                        break;
                    case EVENT_TYPE_LIST:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_LIST);
                        break;
                    case EVENT_TYPE_LIST_STOP:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_LIST_STOP);
                        break;
                    case EVENT_TYPE_UNKNOWN:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_UNKNOWN);
                        break;
                }
                proto.end(elemToken);
            } while ((elem.type != EVENT_TYPE_UNKNOWN) && !elem.complete);
            proto.end(token);
            if (context) {
                android_log_destroy(&context);
            }
        } else {
            AndroidLogEntry entry;
            err = android_log_processLogBuffer(&msg.entry_v1, &entry);
            if (err != NO_ERROR) {
                ALOGW("[%s] fails to process to an entry.\n", this->name.string());
                break;
            }
            lastTimestamp.tv_sec = entry.tv_sec;
            lastTimestamp.tv_nsec = entry.tv_nsec;

            // format a TextLogEntry
            uint64_t token = proto.start(LogProto::TEXT_LOGS);
            proto.write(TextLogEntry::SEC, (long long)entry.tv_sec);
            proto.write(TextLogEntry::NANOSEC, (long long)entry.tv_nsec);
            proto.write(TextLogEntry::PRIORITY, (int)entry.priority);
            proto.write(TextLogEntry::UID, entry.uid);
            proto.write(TextLogEntry::PID, entry.pid);
            proto.write(TextLogEntry::TID, entry.tid);
            proto.write(TextLogEntry::TAG, entry.tag, trimTail(entry.tag, entry.tagLen));
            proto.write(TextLogEntry::LOG, entry.message,
                        trimTail(entry.message, entry.messageLen));
            proto.end(token);
        }
    }
    gLastLogsRetrieved[mLogID] = lastTimestamp;
    if (!proto.flush(pipeWriteFd) && errno == EPIPE) {
        ALOGE("[%s] wrote to a broken pipe\n", this->name.string());
        return EPIPE;
    }
    return NO_ERROR;
}

// ================================================================================

TombstoneSection::TombstoneSection(int id, const char* type, const int64_t timeoutMs)
    : WorkerThreadSection(id, timeoutMs), mType(type) {
    name = "tombstone ";
    name += type;
}

TombstoneSection::~TombstoneSection() {}

status_t TombstoneSection::BlockingCall(int pipeWriteFd) const {
    std::unique_ptr<DIR, decltype(&closedir)> proc(opendir("/proc"), closedir);
    if (proc.get() == nullptr) {
        ALOGE("opendir /proc failed: %s\n", strerror(errno));
        return -errno;
    }

    const std::set<int> hal_pids = get_interesting_hal_pids();

    ProtoOutputStream proto;
    struct dirent* d;
    status_t err = NO_ERROR;
    while ((d = readdir(proc.get()))) {
        int pid = atoi(d->d_name);
        if (pid <= 0) {
            continue;
        }

        const std::string link_name = android::base::StringPrintf("/proc/%d/exe", pid);
        std::string exe;
        if (!android::base::Readlink(link_name, &exe)) {
            ALOGE("Section %s: Can't read '%s': %s\n", name.string(),
                    link_name.c_str(), strerror(errno));
            continue;
        }

        bool is_java_process;
        if (exe == "/system/bin/app_process32" || exe == "/system/bin/app_process64") {
            if (mType != "java") continue;
            // Don't bother dumping backtraces for the zygote.
            if (IsZygote(pid)) {
                VLOG("Skipping Zygote");
                continue;
            }

            is_java_process = true;
        } else if (should_dump_native_traces(exe.c_str())) {
            if (mType != "native") continue;
            is_java_process = false;
        } else if (hal_pids.find(pid) != hal_pids.end()) {
            if (mType != "hal") continue;
            is_java_process = false;
        } else {
            // Probably a native process we don't care about, continue.
            VLOG("Skipping %d", pid);
            continue;
        }

        Fpipe dumpPipe;
        if (!dumpPipe.init()) {
            ALOGW("[%s] failed to setup dump pipe", this->name.string());
            err = -errno;
            break;
        }

        const uint64_t start = Nanotime();
        pid_t child = fork();
        if (child < 0) {
            ALOGE("Failed to fork child process");
            break;
        } else if (child == 0) {
            // This is the child process.
            dumpPipe.readFd().reset();
            const int ret = dump_backtrace_to_file_timeout(
                    pid, is_java_process ? kDebuggerdJavaBacktrace : kDebuggerdNativeBacktrace,
                    is_java_process ? 5 : 20, dumpPipe.writeFd().get());
            if (ret == -1) {
                if (errno == 0) {
                    ALOGW("Dumping failed for pid '%d', likely due to a timeout\n", pid);
                } else {
                    ALOGE("Dumping failed for pid '%d': %s\n", pid, strerror(errno));
                }
            }
            dumpPipe.writeFd().reset();
            _exit(EXIT_SUCCESS);
        }
        dumpPipe.writeFd().reset();
        // Parent process.
        // Read from the pipe concurrently to avoid blocking the child.
        FdBuffer buffer;
        err = buffer.readFully(dumpPipe.readFd().get());
        // Wait on the child to avoid it becoming a zombie process.
        status_t cStatus = wait_child(child);
        if (err != NO_ERROR) {
            ALOGW("[%s] failed to read stack dump: %d", this->name.string(), err);
            dumpPipe.readFd().reset();
            break;
        }
        if (cStatus != NO_ERROR) {
            ALOGE("[%s] child had an issue: %s\n", this->name.string(), strerror(-cStatus));
        }

        auto dump = std::make_unique<char[]>(buffer.size());
        sp<ProtoReader> reader = buffer.data()->read();
        int i = 0;
        while (reader->hasNext()) {
            dump[i] = reader->next();
            i++;
        }
        uint64_t token = proto.start(android::os::BackTraceProto::TRACES);
        proto.write(android::os::BackTraceProto::Stack::PID, pid);
        proto.write(android::os::BackTraceProto::Stack::DUMP, dump.get(), i);
        proto.write(android::os::BackTraceProto::Stack::DUMP_DURATION_NS,
                    static_cast<long long>(Nanotime() - start));
        proto.end(token);
        dumpPipe.readFd().reset();
    }

    if (!proto.flush(pipeWriteFd) && errno == EPIPE) {
        ALOGE("[%s] wrote to a broken pipe\n", this->name.string());
        if (err != NO_ERROR) {
            return EPIPE;
        }
    }

    return err;
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
