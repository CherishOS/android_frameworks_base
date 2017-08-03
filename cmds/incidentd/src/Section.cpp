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

#define LOG_TAG "incidentd"

#include "Section.h"
#include "protobuf.h"

#include <private/android_filesystem_config.h>
#include <binder/IServiceManager.h>
#include <mutex>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wait.h>
#include <unistd.h>

using namespace std;

const int   WAIT_MAX = 5;
const struct timespec WAIT_INTERVAL_NS = {0, 200 * 1000 * 1000};
const char* INCIDENT_HELPER = "/system/bin/incident_helper";

static pid_t
forkAndExecuteIncidentHelper(const int id, const char* name, Fpipe& p2cPipe, Fpipe& c2pPipe)
{
    const char* ihArgs[] { INCIDENT_HELPER, "-s", to_string(id).c_str(), NULL };

    // fork used in multithreaded environment, avoid adding unnecessary code in child process
    pid_t pid = fork();
    if (pid == 0) {
        // child process executes incident helper as nobody
        if (setgid(AID_NOBODY) == -1) {
            ALOGW("%s can't change gid: %s", name, strerror(errno));
            _exit(EXIT_FAILURE);
        }
        if (setuid(AID_NOBODY) == -1) {
            ALOGW("%s can't change uid: %s", name, strerror(errno));
            _exit(EXIT_FAILURE);
        }

        if (dup2(p2cPipe.readFd(),  STDIN_FILENO)  != 0 || !p2cPipe.close() ||
            dup2(c2pPipe.writeFd(), STDOUT_FILENO) != 1 || !c2pPipe.close()) {
            ALOGW("%s can't setup stdin and stdout for incident helper", name);
            _exit(EXIT_FAILURE);
        }

        execv(INCIDENT_HELPER, const_cast<char**>(ihArgs));

        ALOGW("%s failed in incident helper process: %s", name, strerror(errno));
        _exit(EXIT_FAILURE); // always exits with failure if any
    }
    // close the fds used in incident helper
    close(p2cPipe.readFd());
    close(c2pPipe.writeFd());
    return pid;
}

static status_t killChild(pid_t pid) {
    int status;
    kill(pid, SIGKILL);
    if (waitpid(pid, &status, 0) == -1) return -1;
    return WIFEXITED(status) == 0 ? NO_ERROR : -WEXITSTATUS(status);
}

static status_t waitForChild(pid_t pid) {
    int status;
    bool died = false;
    // wait for child to report status up to 1 seconds
    for(int loop = 0; !died && loop < WAIT_MAX; loop++) {
        if (waitpid(pid, &status, WNOHANG) == pid) died = true;
        // sleep for 0.2 second
        nanosleep(&WAIT_INTERVAL_NS, NULL);
    }
    if (!died) return killChild(pid);
    return WIFEXITED(status) == 0 ? NO_ERROR : -WEXITSTATUS(status);
}

// ================================================================================
Section::Section(int i, const int64_t timeoutMs)
    :id(i), timeoutMs(timeoutMs)
{
}

Section::~Section()
{
}

status_t
Section::WriteHeader(ReportRequestSet* requests, size_t size) const
{
    ssize_t amt;
    uint8_t buf[20];
    uint8_t* p = write_length_delimited_tag_header(buf, this->id, size);
    return requests->write(buf, p-buf);
}

// ================================================================================
FileSection::FileSection(int id, const char* filename, const int64_t timeoutMs)
        : Section(id, timeoutMs), mFilename(filename) {
    name = filename;
}

FileSection::~FileSection() {}

status_t FileSection::Execute(ReportRequestSet* requests) const {
    // read from mFilename first, make sure the file is available
    // add O_CLOEXEC to make sure it is closed when exec incident helper
    int fd = open(mFilename, O_RDONLY | O_CLOEXEC, 0444);
    if (fd == -1) {
       ALOGW("FileSection '%s' failed to open file", this->name.string());
       return -errno;
    }

    FdBuffer buffer;
    Fpipe p2cPipe;
    Fpipe c2pPipe;
    // initiate pipes to pass data to/from incident_helper
    if (!p2cPipe.init() || !c2pPipe.init()) {
        ALOGW("FileSection '%s' failed to setup pipes", this->name.string());
        return -errno;
    }

    pid_t pid = forkAndExecuteIncidentHelper(this->id, this->name.string(), p2cPipe, c2pPipe);
    if (pid == -1) {
        ALOGW("FileSection '%s' failed to fork", this->name.string());
        return -errno;
    }

    // parent process
    status_t readStatus = buffer.readProcessedDataInStream(fd, p2cPipe.writeFd(), c2pPipe.readFd(),
            this->timeoutMs);
    if (readStatus != NO_ERROR || buffer.timedOut()) {
        ALOGW("FileSection '%s' failed to read data from incident helper: %s, timedout: %s, kill: %s",
            this->name.string(), strerror(-readStatus), buffer.timedOut() ? "true" : "false",
            strerror(-killChild(pid)));
        return readStatus;
    }

    status_t ihStatus = waitForChild(pid);
    if (ihStatus != NO_ERROR) {
        ALOGW("FileSection '%s' abnormal child process: %s", this->name.string(), strerror(-ihStatus));
        return ihStatus;
    }

    ALOGD("FileSection '%s' wrote %zd bytes in %d ms", this->name.string(), buffer.size(),
            (int)buffer.durationMs());
    WriteHeader(requests, buffer.size());
    status_t err = buffer.write(requests);
    if (err != NO_ERROR) {
        ALOGW("FileSection '%s' failed writing: %s", this->name.string(), strerror(-err));
        return err;
    }

    return NO_ERROR;
}

// ================================================================================
struct WorkerThreadData : public virtual RefBase
{
    const WorkerThreadSection* section;
    int fds[2];

    // Lock protects these fields
    mutex lock;
    bool workerDone;
    status_t workerError;

    WorkerThreadData(const WorkerThreadSection* section);
    virtual ~WorkerThreadData();

    int readFd() { return fds[0]; }
    int writeFd() { return fds[1]; }
};

WorkerThreadData::WorkerThreadData(const WorkerThreadSection* sec)
    :section(sec),
     workerDone(false),
     workerError(NO_ERROR)
{
    fds[0] = -1;
    fds[1] = -1;
}

WorkerThreadData::~WorkerThreadData()
{
}

// ================================================================================
WorkerThreadSection::WorkerThreadSection(int id)
    :Section(id)
{
}

WorkerThreadSection::~WorkerThreadSection()
{
}

static void*
worker_thread_func(void* cookie)
{
    WorkerThreadData* data = (WorkerThreadData*)cookie;
    status_t err = data->section->BlockingCall(data->writeFd());

    {
        unique_lock<mutex> lock(data->lock);
        data->workerDone = true;
        data->workerError = err;
    }

    close(data->writeFd());
    data->decStrong(data->section);
    // data might be gone now. don't use it after this point in this thread.
    return NULL;
}

status_t
WorkerThreadSection::Execute(ReportRequestSet* requests) const
{
    status_t err = NO_ERROR;
    pthread_t thread;
    pthread_attr_t attr;
    bool timedOut = false;
    FdBuffer buffer;

    // Data shared between this thread and the worker thread.
    sp<WorkerThreadData> data = new WorkerThreadData(this);

    // Create the pipe
    err = pipe(data->fds);
    if (err != 0) {
        return -errno;
    }

    // The worker thread needs a reference and we can't let the count go to zero
    // if that thread is slow to start.
    data->incStrong(this);

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
    err = pthread_create(&thread, &attr, worker_thread_func, (void*)data.get());
    if (err != 0) {
        pthread_attr_destroy(&attr);
        return -err;
    }
    pthread_attr_destroy(&attr);

    // Loop reading until either the timeout or the worker side is done (i.e. eof).
    err = buffer.read(data->readFd(), this->timeoutMs);
    if (err != NO_ERROR) {
        // TODO: Log this error into the incident report.
        ALOGW("WorkerThreadSection '%s' reader failed with error '%s'", this->name.string(),
                strerror(-err));
    }

    // Done with the read fd. The worker thread closes the write one so
    // we never race and get here first.
    close(data->readFd());

    // If the worker side is finished, then return its error (which may overwrite
    // our possible error -- but it's more interesting anyway).  If not, then we timed out.
    {
        unique_lock<mutex> lock(data->lock);
        if (!data->workerDone) {
            // We timed out
            timedOut = true;
        } else {
            if (data->workerError != NO_ERROR) {
                err = data->workerError;
                // TODO: Log this error into the incident report.
                ALOGW("WorkerThreadSection '%s' worker failed with error '%s'", this->name.string(),
                        strerror(-err));
            }
        }
    }

    if (timedOut || buffer.timedOut()) {
        ALOGW("WorkerThreadSection '%s' timed out", this->name.string());
        return NO_ERROR;
    }

    if (buffer.truncated()) {
        // TODO: Log this into the incident report.
    }

    // TODO: There was an error with the command or buffering. Report that.  For now
    // just exit with a log messasge.
    if (err != NO_ERROR) {
        ALOGW("WorkerThreadSection '%s' failed with error '%s'", this->name.string(),
                strerror(-err));
        return NO_ERROR;
    }

    // Write the data that was collected
    ALOGD("WorkerThreadSection '%s' wrote %zd bytes in %d ms", name.string(), buffer.size(),
            (int)buffer.durationMs());
    WriteHeader(requests, buffer.size());
    err = buffer.write(requests);
    if (err != NO_ERROR) {
        ALOGW("WorkerThreadSection '%s' failed writing: '%s'", this->name.string(), strerror(-err));
        return err;
    }

    return NO_ERROR;
}

// ================================================================================
void CommandSection::init(const char* command, va_list args)
{
    va_list copied_args;
    va_copy(copied_args, args);
    int numOfArgs = 0;
    while(va_arg(args, const char*) != NULL) {
        numOfArgs++;
    }

    // allocate extra 1 for command and 1 for NULL terminator
    mCommand = (const char**)malloc(sizeof(const char*) * (numOfArgs + 2));

    mCommand[0] = command;
    name = command;
    for (int i=0; i<numOfArgs; i++) {
        const char* arg = va_arg(copied_args, const char*);
        mCommand[i+1] = arg;
        name += " ";
        name += arg;
    }
    mCommand[numOfArgs+1] = NULL;
    va_end(copied_args);
}

CommandSection::CommandSection(int id, const int64_t timeoutMs, const char* command, ...)
        : Section(id, timeoutMs)
{
    va_list args;
    va_start(args, command);
    init(command, args);
    va_end(args);
}

CommandSection::CommandSection(int id, const char* command, ...)
        : Section(id)
{
    va_list args;
    va_start(args, command);
    init(command, args);
    va_end(args);
}

CommandSection::~CommandSection()
{
    free(mCommand);
}

status_t
CommandSection::Execute(ReportRequestSet* requests) const
{
    FdBuffer buffer;
    Fpipe cmdPipe;
    Fpipe ihPipe;

    if (!cmdPipe.init() || !ihPipe.init()) {
        ALOGW("CommandSection '%s' failed to setup pipes", this->name.string());
        return -errno;
    }

    pid_t cmdPid = fork();
    if (cmdPid == -1) {
        ALOGW("CommandSection '%s' failed to fork", this->name.string());
        return -errno;
    }
    // child process to execute the command as root
    if (cmdPid == 0) {
        // replace command's stdout with ihPipe's write Fd
        if (dup2(cmdPipe.writeFd(), STDOUT_FILENO) != 1 || !ihPipe.close() || !cmdPipe.close()) {
            ALOGW("CommandSection '%s' failed to set up stdout: %s", this->name.string(), strerror(errno));
            _exit(EXIT_FAILURE);
        }
        execv(this->mCommand[0], (char *const *) this->mCommand);
        int err = errno; // record command error code
        ALOGW("CommandSection '%s' failed in executing command: %s", this->name.string(), strerror(errno));
        _exit(err); // exit with command error code
    }
    pid_t ihPid = forkAndExecuteIncidentHelper(this->id, this->name.string(), cmdPipe, ihPipe);
    if (ihPid == -1) {
        ALOGW("CommandSection '%s' failed to fork", this->name.string());
        return -errno;
    }

    close(cmdPipe.writeFd());
    status_t readStatus = buffer.read(ihPipe.readFd(), this->timeoutMs);
    if (readStatus != NO_ERROR || buffer.timedOut()) {
        ALOGW("CommandSection '%s' failed to read data from incident helper: %s, "
            "timedout: %s, kill command: %s, kill incident helper: %s",
            this->name.string(), strerror(-readStatus), buffer.timedOut() ? "true" : "false",
            strerror(-killChild(cmdPid)), strerror(-killChild(ihPid)));
        return readStatus;
    }

    // TODO: wait for command here has one trade-off: the failed status of command won't be detected until
    //       buffer timeout, but it has advatage on starting the data stream earlier.
    status_t cmdStatus = waitForChild(cmdPid);
    status_t ihStatus  = waitForChild(ihPid);
    if (cmdStatus != NO_ERROR || ihStatus != NO_ERROR) {
        ALOGW("CommandSection '%s' abnormal child processes, return status: command: %s, incidnet helper: %s",
            this->name.string(), strerror(-cmdStatus), strerror(-ihStatus));
        return cmdStatus != NO_ERROR ? cmdStatus : ihStatus;
    }

    ALOGD("CommandSection '%s' wrote %zd bytes in %d ms", this->name.string(), buffer.size(),
            (int)buffer.durationMs());
    WriteHeader(requests, buffer.size());
    status_t err = buffer.write(requests);
    if (err != NO_ERROR) {
        ALOGW("CommandSection '%s' failed writing: %s", this->name.string(), strerror(-err));
        return err;
    }
    return NO_ERROR;
}

// ================================================================================
DumpsysSection::DumpsysSection(int id, const char* service, ...)
    :WorkerThreadSection(id),
     mService(service)
{
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

DumpsysSection::~DumpsysSection()
{
}

status_t
DumpsysSection::BlockingCall(int pipeWriteFd) const
{
    // checkService won't wait for the service to show up like getService will.
    sp<IBinder> service = defaultServiceManager()->checkService(mService);

    if (service == NULL) {
        // Returning an error interrupts the entire incident report, so just
        // log the failure.
        // TODO: have a meta record inside the report that would log this
        // failure inside the report, because the fact that we can't find
        // the service is good data in and of itself. This is running in
        // another thread so lock that carefully...
        ALOGW("DumpsysSection: Can't lookup service: %s", String8(mService).string());
        return NO_ERROR;
    }

    service->dump(pipeWriteFd, mArgs);

    return NO_ERROR;
}
