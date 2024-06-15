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

#include <time.h>
#include <pthread.h>
#include <sys/timerfd.h>
#include <inttypes.h>
#include <sys/stat.h>

#include <algorithm>
#include <list>
#include <memory>
#include <set>
#include <string>
#include <vector>

#define LOG_TAG "AnrTimerService"
#define ATRACE_TAG ATRACE_TAG_ACTIVITY_MANAGER
#define ANR_TIMER_TRACK "AnrTimerTrack"

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"
#include "core_jni_helpers.h"

#include <processgroup/processgroup.h>
#include <utils/Log.h>
#include <utils/Mutex.h>
#include <utils/Timers.h>
#include <utils/Trace.h>

#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <android-base/unique_fd.h>

using ::android::base::StringPrintf;


// Native support is unavailable on WIN32 platforms.  This macro preemptively disables it.
#ifdef _WIN32
#define NATIVE_SUPPORT 0
#else
#define NATIVE_SUPPORT 1
#endif

namespace android {

// using namespace android;

// Almost nothing in this module needs to be in the android namespace.
namespace {

// If not on a Posix system, create stub timerfd methods.  These are defined to allow
// compilation.  They are not functional.  Also, they do not leak outside this compilation unit.
#ifdef _WIN32
int timer_create() {
  return -1;
}
int timer_settime(int, int, void const *, void *) {
  return -1;
}
#else
int timer_create() {
  return timerfd_create(CLOCK_MONOTONIC, TFD_CLOEXEC);
}
int timer_settime(int fd, int flags, const struct itimerspec *new_value,
                  struct itimerspec *_Nullable old_value) {
  return timerfd_settime(fd, flags, new_value, old_value);
}
#endif

// A local debug flag that gates a set of log messages for debug only.  This is normally const
// false so the debug statements are not included in the image.  The flag can be set true in a
// unit test image to debug test failures.
const bool DEBUG_TIMER = false;

// A local debug flag to debug the timer thread itself.
const bool DEBUG_TICKER = false;

// Enable error logging.
const bool DEBUG_ERROR = true;

// Return the current time in nanoseconds.  This time is relative to system boot.
nsecs_t now() {
    return systemTime(SYSTEM_TIME_MONOTONIC);
}

// Return true if the process exists and false if we cannot know.
bool processExists(pid_t pid) {
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "/proc/%d", pid);
    struct stat buff;
    return stat(path, &buff) == 0;
}

// Return the name of the process whose pid is the input.  If the process does not exist, the
// name will "notfound".
std::string getProcessName(pid_t pid) {
    char buffer[PATH_MAX];
    snprintf(buffer, sizeof(buffer), "/proc/%d/cmdline", pid);
    int fd = ::open(buffer, O_RDONLY);
    if (fd >= 0) {
        size_t pos = 0;
        ssize_t result;
        while (pos < sizeof(buffer)-1) {
            result = ::read(fd, buffer + pos, (sizeof(buffer) - pos) - 1);
            if (result <= 0) {
                break;
            }
        }
        ::close(fd);

        if (result >= 0) {
            buffer[pos] = 0;
        } else {
            snprintf(buffer, sizeof(buffer), "err: %s", strerror(errno));
        }
    } else {
        snprintf(buffer, sizeof(buffer), "notfound");
    }
    return std::string(buffer);
}

/**
 * This class encapsulates the anr timer service.  The service manages a list of individual
 * timers.  A timer is either Running or Expired.  Once started, a timer may be canceled or
 * accepted.  Both actions collect statistics about the timer and then delete it.  An expired
 * timer may also be discarded, which deletes the timer without collecting any statistics.
 *
 * All public methods in this class are thread-safe.
 */
class AnrTimerService {
  private:
    class ProcessStats;
    class Timer;

  public:

    // The class that actually runs the clock.
    class Ticker;

    // A timer is identified by a timer_id_t.  Timer IDs are unique in the moment.
    using timer_id_t = uint32_t;

    // A manifest constant.  No timer is ever created with this ID.
    static const timer_id_t NOTIMER = 0;

    // A notifier is called with a timer ID, the timer's tag, and the client's cookie.  The pid
    // and uid that were originally assigned to the timer are passed as well.  The elapsed time
    // is the time since the timer was scheduled.
    using notifier_t = bool (*)(timer_id_t, int pid, int uid, nsecs_t elapsed,
                                void* cookie, jweak object);

    enum Status {
        Invalid,
        Running,
        Expired,
        Canceled
    };

    /**
     * Create a timer service.  The service is initialized with a name used for logging.  The
     * constructor is also given the notifier callback, and two cookies for the callback: the
     * traditional void* and Java object pointer.  The remaining parameters are
     * configuration options.
     */
    AnrTimerService(char const* label, notifier_t notifier, void* cookie, jweak jtimer, Ticker*,
                    bool extend, bool freeze);

    // Delete the service and clean up memory.
    ~AnrTimerService();

    // Start a timer and return the associated timer ID.  It does not matter if the same pid/uid
    // are already in the running list.  Once start() is called, one of cancel(), accept(), or
    // discard() must be called to clean up the internal data structures.
    timer_id_t start(int pid, int uid, nsecs_t timeout);

    // Cancel a timer and remove it from all lists.  This is called when the event being timed
    // has occurred.  If the timer was Running, the function returns true.  The other
    // possibilities are that the timer was Expired or non-existent; in both cases, the function
    // returns false.
    bool cancel(timer_id_t timerId);

    // Accept a timer.  This is called when the upper layers accept that a timer has expired.
    // If the timer was Expired and its process was frozen, the timer is pushed to the expired
    // list and 'true' is returned.  Otherwise the function returns false.
    bool accept(timer_id_t timerId);

    // Discard a timer without collecting any statistics.  This is called when the upper layers
    // recognize that a timer expired but decide the expiration is not significant.  If the
    // timer was Expired, the function returns true.  The other possibilities are tha the timer
    // was Running or non-existing; in both cases, the function returns false.
    bool discard(timer_id_t timerId);

    // A timer has expired.
    void expire(timer_id_t);

    // Release a timer.  The timer must be in the expired list.
    bool release(timer_id_t);

    // Return the Java object associated with this instance.
    jweak jtimer() const {
        return notifierObject_;
    }

    // Return the per-instance statistics.
    std::vector<std::string> getDump() const;

  private:
    // The service cannot be copied.
    AnrTimerService(AnrTimerService const&) = delete;

    // Insert a timer into the running list.  The lock must be held by the caller.
    void insertLocked(const Timer&);

    // Remove a timer from the lists and return it. The lock must be held by the caller.
    Timer removeLocked(timer_id_t timerId);

    // Add a timer to the expired list.
    void addExpiredLocked(Timer const&);

    // Scrub the expired list by removing all entries for non-existent processes.  The expired
    // lock must be held by the caller.
    void scrubExpiredLocked();

    // Return a string representation of a status value.
    static const char* statusString(Status);

    // The name of this service, for logging.
    std::string const label_;

    // The callback that is invoked when a timer expires.
    notifier_t const notifier_;

    // The two cookies passed to the notifier.
    void* notifierCookie_;
    jweak notifierObject_;

    // True if extensions can be granted to expired timers.
    const bool extend_;

    // True if the service should freeze anr'ed processes.
    const bool freeze_;

    // The global lock
    mutable Mutex lock_;

    // The list of all timers that are still running.  This is sorted by ID for fast lookup.
    std::set<Timer> running_;

    // The list of all expired timers that are awaiting release.
    std::set<Timer> expired_;

    // The maximum number of active timers.
    size_t maxRunning_;

    // Simple counters
    struct Counters {
        // The number of timers started, canceled, accepted, discarded, and expired.
        size_t started;
        size_t canceled;
        size_t accepted;
        size_t discarded;
        size_t expired;
        size_t extended;
        size_t released;

        // The number of times there were zero active timers.
        size_t drained;

        // The number of times a protocol error was seen.
        size_t error;
    };

    Counters counters_;

    // The clock used by this AnrTimerService.
    Ticker *ticker_;
};

class AnrTimerService::ProcessStats {
  public:
    nsecs_t cpu_time;
    nsecs_t cpu_delay;

    ProcessStats() :
            cpu_time(0),
            cpu_delay(0) {
    }

    // Collect all statistics for a process.  Return true if the fill succeeded and false if it
    // did not.  If there is any problem, the statistics are zeroed.
    bool fill(int pid) {
        cpu_time = 0;
        cpu_delay = 0;

        char path[PATH_MAX];
        snprintf(path, sizeof(path), "/proc/%u/schedstat", pid);
        ::android::base::unique_fd fd(open(path, O_RDONLY | O_CLOEXEC));
        if (!fd.ok()) {
            return false;
        }
        char buffer[128];
        ssize_t len = read(fd, buffer, sizeof(buffer));
        if (len <= 0) {
            return false;
        }
        if (len >= sizeof(buffer)) {
            ALOGE("proc file too big: %s", path);
            return false;
        }
        buffer[len] = 0;
        unsigned long t1;
        unsigned long t2;
        if (sscanf(buffer, "%lu %lu", &t1, &t2) != 2) {
            return false;
        }
        cpu_time = t1;
        cpu_delay = t2;
        return true;
    }
};

class AnrTimerService::Timer {
  public:
    // A unique ID assigned when the Timer is created.
    timer_id_t const id;

    // The creation parameters.  The timeout is the original, relative timeout.
    int const pid;
    int const uid;
    nsecs_t const timeout;
    bool const extend;
    bool const freeze;

    // The state of this timer.
    Status status;

    // The time at which the timer was started.
    nsecs_t started;

    // The scheduled timeout.  This is an absolute time.  It may be extended.
    nsecs_t scheduled;

    // True if this timer has been extended.
    bool extended;

    // True if the process has been frozen.
    bool frozen;

    // Bookkeeping for extensions.  The initial state of the process.  This is collected only if
    // the timer is extensible.
    ProcessStats initial;

    // The default constructor is used to create timers that are Invalid, representing the "not
    // found" condition when a collection is searched.
    Timer() :
            id(NOTIMER),
            pid(0),
            uid(0),
            timeout(0),
            extend(false),
            freeze(false),
            status(Invalid),
            started(0),
            scheduled(0),
            extended(false),
            frozen(false) {
    }

    // This constructor creates a timer with the specified id.  This can be used as the argument
    // to find().
    Timer(timer_id_t id) :
            id(id),
            pid(0),
            uid(0),
            timeout(0),
            extend(false),
            freeze(false),
            status(Invalid),
            started(0),
            scheduled(0),
            extended(false),
            frozen(false) {
    }

    // Create a new timer.  This starts the timer.
    Timer(int pid, int uid, nsecs_t timeout, bool extend, bool freeze) :
            id(nextId()),
            pid(pid),
            uid(uid),
            timeout(timeout),
            extend(extend),
            freeze(pid != 0 && freeze),
            status(Running),
            started(now()),
            scheduled(started + timeout),
            extended(false),
            frozen(false) {
        if (extend && pid != 0) {
            initial.fill(pid);
        }
        // A zero-pid is odd but it means the upper layers will never ANR the process.  Freezing
        // is always disabled.  (It won't work anyway, but disabling it avoids error messages.)
        ALOGI_IF(DEBUG_ERROR && pid == 0, "error: zero-pid %s", toString().c_str());
    }

    // Start a timer.  This interface exists to generate log messages, if enabled.
    void start() {
        event("start", /* verbose= */ true);
    }

    // Cancel a timer.
    void cancel() {
        ALOGW_IF(DEBUG_ERROR && status != Running, "error: canceling %s", toString().c_str());
        status = Canceled;
        event("cancel");
    }

    // Expire a timer. Return true if the timer is expired and false otherwise.  The function
    // returns false if the timer is eligible for extension.  If the function returns false, the
    // scheduled time is updated.
    bool expire() {
        nsecs_t extension = 0;
        if (extend && !extended) {
            // Only one extension is permitted.
            extended = true;
            ProcessStats current;
            current.fill(pid);
            extension = current.cpu_delay - initial.cpu_delay;
            if (extension < 0) extension = 0;
            if (extension > timeout) extension = timeout;
        }
        if (extension == 0) {
            status = Expired;
            maybeFreezeProcess();
            event("expire");
        } else {
            scheduled += extension;
            event("extend");
        }
        return status == Expired;
    }

    // Accept a timeout.  This does nothing other than log the state machine change.
    void accept() {
        event("accept");
    }

    // Discard a timeout.
    void discard() {
        maybeUnfreezeProcess();
        status = Canceled;
        event("discard");
    }

    // Release the timer.
    void release() {
        // If timer represents a frozen process, unfreeze it at this time.
        maybeUnfreezeProcess();
        event("release");
    }

    // Return true if this timer corresponds to a running process.
    bool alive() const {
        return processExists(pid);
    }

    // Timers are sorted by id, which is unique.  This provides fast lookups.
    bool operator<(Timer const &r) const {
        return id < r.id;
    }

    bool operator==(timer_id_t r) const {
        return id == r;
    }

    std::string toString() const {
        return StringPrintf("id=%d pid=%d uid=%d status=%s",
                            id, pid, uid, statusString(status));
    }

    std::string toString(nsecs_t now) const {
        uint32_t ms = nanoseconds_to_milliseconds(now - scheduled);
        return StringPrintf("id=%d pid=%d uid=%d status=%s scheduled=%ums",
                            id, pid, uid, statusString(status), -ms);
    }

    static int maxId() {
        return idGen;
    }

  private:
    /**
     * Collect the name of the process.
     */
    std::string getName() const {
        return getProcessName(pid);
    }

    /**
     * Freeze the process identified here.  Failures are not logged, as they are primarily due
     * to a process having died (therefore failed to respond).
     */
    void maybeFreezeProcess() {
        if (!freeze || !alive()) return;

        // Construct a unique event ID.  The id*2 spans from the beginning of the freeze to the
        // end of the freeze.  The id*2+1 spans the period inside the freeze/unfreeze
        // operations.
        const uint32_t cookie = id << 1;

        char tag[PATH_MAX];
        snprintf(tag, sizeof(tag), "freeze(pid=%d,uid=%d)", pid, uid);
        ATRACE_ASYNC_FOR_TRACK_BEGIN(ANR_TIMER_TRACK, tag, cookie);
        if (SetProcessProfiles(uid, pid, {"Frozen"})) {
            ALOGI("freeze %s name=%s", toString().c_str(), getName().c_str());
            frozen = true;
            ATRACE_ASYNC_FOR_TRACK_BEGIN(ANR_TIMER_TRACK, "frozen", cookie+1);
        } else {
            ALOGE("error: freezing %s name=%s error=%s",
                  toString().c_str(), getName().c_str(), strerror(errno));
            ATRACE_ASYNC_FOR_TRACK_END(ANR_TIMER_TRACK, cookie);
        }
    }

    void maybeUnfreezeProcess() {
        if (!freeze || !frozen) return;

        // See maybeFreezeProcess for an explanation of the cookie.
        const uint32_t cookie = id << 1;

        ATRACE_ASYNC_FOR_TRACK_END(ANR_TIMER_TRACK, cookie+1);
        if (SetProcessProfiles(uid, pid, {"Unfrozen"})) {
            ALOGI("unfreeze %s name=%s", toString().c_str(), getName().c_str());
            frozen = false;
        } else {
            ALOGE("error: unfreezing %s name=%s error=%s",
                  toString().c_str(), getName().c_str(), strerror(errno));
        }
        ATRACE_ASYNC_FOR_TRACK_END(ANR_TIMER_TRACK, cookie);
    }

    // Get the next free ID.  NOTIMER is never returned.
    static timer_id_t nextId() {
        timer_id_t id = idGen.fetch_add(1);
        while (id == NOTIMER) {
            id = idGen.fetch_add(1);
        }
        return id;
    }

    // Log an event, non-verbose.
    void event(char const* tag) {
        event(tag, false);
    }

    // Log an event, guarded by the debug flag.
    void event(char const* tag, bool verbose) {
        if (verbose) {
            char name[PATH_MAX];
            ALOGI_IF(DEBUG_TIMER, "event %s %s name=%s",
                     tag, toString().c_str(), getName().c_str());
        } else {
            ALOGI_IF(DEBUG_TIMER, "event %s id=%u", tag, id);
        }
    }

    // IDs start at 1.  A zero ID is invalid.
    static std::atomic<timer_id_t> idGen;
};

// IDs start at 1.
std::atomic<AnrTimerService::timer_id_t> AnrTimerService::Timer::idGen(1);

/**
 * Manage a set of timers and notify clients when there is a timeout.
 */
class AnrTimerService::Ticker {
  private:
    struct Entry {
        const nsecs_t scheduled;
        const timer_id_t id;
        AnrTimerService* const service;

        Entry(nsecs_t scheduled, timer_id_t id, AnrTimerService* service) :
                scheduled(scheduled), id(id), service(service) {};

        bool operator<(const Entry &r) const {
            return scheduled == r.scheduled ? id < r.id : scheduled < r.scheduled;
        }
    };

  public:

    // Construct the ticker.  This creates the timerfd file descriptor and starts the monitor
    // thread.  The monitor thread is given a unique name.
    Ticker() :
            id_(idGen_.fetch_add(1))
    {
        timerFd_ = timer_create();
        if (timerFd_ < 0) {
            ALOGE("failed to create timerFd: %s", strerror(errno));
            return;
        }

        if (pthread_create(&watcher_, 0, run, this) != 0) {
            ALOGE("failed to start thread: %s", strerror(errno));
            watcher_ = 0;
            ::close(timerFd_);
            return;
        }

        // 16 is a magic number from the kernel.  Thread names may not be longer than this many
        // bytes, including the terminating null.  The snprintf() method will truncate properly.
        char name[16];
        snprintf(name, sizeof(name), "AnrTimerService");
        pthread_setname_np(watcher_, name);

        ready_ = true;
    }

    ~Ticker() {
        // Closing the file descriptor will close the monitor process, if any.
        if (timerFd_ >= 0) ::close(timerFd_);
        timerFd_ = -1;
        watcher_ = 0;
    }

    // Insert a timer.  Unless canceled, the timer will expire at the scheduled time.  If it
    // expires, the service will be notified with the id.
    void insert(nsecs_t scheduled, timer_id_t id, AnrTimerService *service) {
        Entry e(scheduled, id, service);
        AutoMutex _l(lock_);
        timer_id_t front = headTimerId();
        running_.insert(e);
        if (front != headTimerId()) restartLocked();
        maxRunning_ = std::max(maxRunning_, running_.size());
    }

    // Remove a timer.  The timer is identified by its scheduled timeout and id.  Technically,
    // the id is sufficient (because timer IDs are unique) but using the timeout is more
    // efficient.
    void remove(nsecs_t scheduled, timer_id_t id) {
        Entry key(scheduled, id, 0);
        AutoMutex _l(lock_);
        timer_id_t front = headTimerId();
        auto found = running_.find(key);
        if (found != running_.end()) running_.erase(found);
        if (running_.empty()) drained_++;
    }

    // Remove every timer associated with the service.
    void remove(AnrTimerService const* service) {
        AutoMutex _l(lock_);
        timer_id_t front = headTimerId();
        for (auto i = running_.begin(); i != running_.end(); ) {
            if (i->service == service) {
                i = running_.erase(i);
            } else {
                i++;
            }
        }
    }

    // The unique ID of this particular ticker. Used for debug and logging.
    size_t id() const {
        return id_;
    }

    // Return the number of timers still running.
    size_t running() const {
        AutoMutex _l(lock_);
        return running_.size();
    }

    // Return the high-water mark of timers running.
    size_t maxRunning() const {
        AutoMutex _l(lock_);
        return maxRunning_;
    }

  private:

    // Return the head of the running list.  The lock must be held by the caller.
    timer_id_t headTimerId() {
        return running_.empty() ? NOTIMER : running_.cbegin()->id;
    }

    // A simple wrapper that meets the requirements of pthread_create.
    static void* run(void* arg) {
        reinterpret_cast<Ticker*>(arg)->monitor();
        ALOGI_IF(DEBUG_TICKER, "monitor exited");
        return 0;
    }

    // Loop (almost) forever.  Whenever the timerfd expires, expire as many entries as
    // possible.  The loop terminates when the read fails; this generally indicates that the
    // file descriptor has been closed and the thread can exit.
    void monitor() {
        uint64_t token = 0;
        while (read(timerFd_, &token, sizeof(token)) == sizeof(token)) {
            // Move expired timers into the local ready list.  This is done inside
            // the lock.  Then, outside the lock, expire them.
            nsecs_t current = now();
            std::vector<Entry> ready;
            {
                AutoMutex _l(lock_);
                while (!running_.empty()) {
                    Entry timer = *(running_.begin());
                    if (timer.scheduled <= current) {
                        ready.push_back(timer);
                        running_.erase(running_.cbegin());
                    } else {
                        break;
                    }
                }
                restartLocked();
            }
            // Call the notifiers outside the lock.  Calling the notifiers with the lock held
            // can lead to deadlock, if the Java-side handler also takes a lock.  Note that the
            // timerfd is already running.
            for (auto i = ready.begin(); i != ready.end(); i++) {
                Entry e = *i;
                e.service->expire(e.id);
            }
        }
    }

    // Restart the ticker.  The caller must be holding the lock.  This method updates the
    // timerFd_ to expire at the time of the first Entry in the running list.  This method does
    // not check to see if the currently programmed expiration time is different from the
    // scheduled expiration time of the first entry.
    void restartLocked() {
        if (!running_.empty()) {
            Entry const x = *(running_.cbegin());
            nsecs_t delay = x.scheduled - now();
            // Force a minimum timeout of 10ns.
            if (delay < 10) delay = 10;
            time_t sec = nanoseconds_to_seconds(delay);
            time_t ns = delay - seconds_to_nanoseconds(sec);
            struct itimerspec setting = {
                .it_interval = { 0, 0 },
                .it_value = { sec, ns },
            };
            timer_settime(timerFd_, 0, &setting, nullptr);
            restarted_++;
            ALOGI_IF(DEBUG_TICKER, "restarted timerfd for %ld.%09ld", sec, ns);
        } else {
            const struct itimerspec setting = {
                .it_interval = { 0, 0 },
                .it_value = { 0, 0 },
            };
            timer_settime(timerFd_, 0, &setting, nullptr);
            drained_++;
            ALOGI_IF(DEBUG_TICKER, "drained timer list");
        }
    }

    // The usual lock.
    mutable Mutex lock_;

    // True if the object was initialized properly.  Android does not support throwing C++
    // exceptions, so clients should check this flag after constructing the object.  This is
    // effectively const after the instance has been created.
    bool ready_ = false;

    // The file descriptor of the timer.
    int timerFd_ = -1;

    // The thread that monitors the timer.
    pthread_t watcher_ = 0;

    // The number of times the timer was restarted.
    size_t restarted_ = 0;

    // The number of times the timer list was exhausted.
    size_t drained_ = 0;

    // The highwater mark of timers that are running.
    size_t maxRunning_ = 0;

    // The list of timers that are scheduled.  This set is sorted by timeout and then by timer
    // ID.  A set is sufficient (as opposed to a multiset) because timer IDs are unique.
    std::set<Entry> running_;

    // A unique ID assigned to this instance.
    const size_t id_;

    // The ID generator.
    static std::atomic<size_t> idGen_;
};

std::atomic<size_t> AnrTimerService::Ticker::idGen_;


AnrTimerService::AnrTimerService(char const* label, notifier_t notifier, void* cookie,
            jweak jtimer, Ticker* ticker, bool extend, bool freeze) :
        label_(label),
        notifier_(notifier),
        notifierCookie_(cookie),
        notifierObject_(jtimer),
        extend_(extend),
        freeze_(freeze),
        ticker_(ticker) {

    // Zero the statistics
    maxRunning_ = 0;
    memset(&counters_, 0, sizeof(counters_));

    ALOGI_IF(DEBUG_TIMER, "initialized %s", label);
}

AnrTimerService::~AnrTimerService() {
    AutoMutex _l(lock_);
    ticker_->remove(this);
}

const char* AnrTimerService::statusString(Status s) {
    switch (s) {
        case Invalid: return "invalid";
        case Running: return "running";
        case Expired: return "expired";
        case Canceled: return "canceled";
    }
    return "unknown";
}

AnrTimerService::timer_id_t AnrTimerService::start(int pid, int uid, nsecs_t timeout) {
    AutoMutex _l(lock_);
    Timer t(pid, uid, timeout, extend_, freeze_);
    insertLocked(t);
    t.start();
    counters_.started++;
    return t.id;
}

bool AnrTimerService::cancel(timer_id_t timerId) {
    if (timerId == NOTIMER) return false;
    AutoMutex _l(lock_);
    Timer timer = removeLocked(timerId);

    bool result = timer.status == Running;
    if (timer.status != Invalid) {
        timer.cancel();
    } else {
        counters_.error++;
    }
    counters_.canceled++;
    return result;
}

bool AnrTimerService::accept(timer_id_t timerId) {
    if (timerId == NOTIMER) return false;
    AutoMutex _l(lock_);
    Timer timer = removeLocked(timerId);

    bool result = false;
    if (timer.status == Expired) {
        timer.accept();
        if (timer.frozen) {
            addExpiredLocked(timer);
            result = true;
        }
    } else {
        counters_.error++;
    }
    counters_.accepted++;
    return result;
}

bool AnrTimerService::discard(timer_id_t timerId) {
    if (timerId == NOTIMER) return false;
    AutoMutex _l(lock_);
    Timer timer = removeLocked(timerId);

    bool result = timer.status == Expired;
    if (timer.status == Expired) {
        timer.discard();
    } else {
        counters_.error++;
    }
    counters_.discarded++;
    return result;
}

bool AnrTimerService::release(timer_id_t id) {
    if (id == NOTIMER) return true;

    Timer key(id);
    bool okay = false;
    AutoMutex _l(lock_);
    std::set<Timer>::iterator found = expired_.find(key);
    if (found != expired_.end()) {
        Timer t = *found;
        t.release();
        counters_.released++;
        expired_.erase(found);
        okay = true;
    } else {
        ALOGI_IF(DEBUG_ERROR, "error: unable to release (%u)", id);
        counters_.error++;
    }
    scrubExpiredLocked();
    return okay;
}

void AnrTimerService::addExpiredLocked(Timer const& timer) {
    scrubExpiredLocked();
    expired_.insert(timer);
}

void AnrTimerService::scrubExpiredLocked() {
    for (auto i = expired_.begin(); i != expired_.end(); ) {
        if (!i->alive()) {
            i = expired_.erase(i);
        } else {
            i++;
        }
    }
}

// Hold the lock in order to manage the running list.
// the listener.
void AnrTimerService::expire(timer_id_t timerId) {
    // Save the timer attributes for the notification
    int pid = 0;
    int uid = 0;
    nsecs_t elapsed = 0;
    bool expired = false;
    {
        AutoMutex _l(lock_);
        Timer t = removeLocked(timerId);
        expired = t.expire();
        if (t.status == Invalid) {
            ALOGW_IF(DEBUG_ERROR, "error: expired invalid timer %u", timerId);
            return;
        } else {
            // The timer is either Running (because it was extended) or expired (and is awaiting an
            // accept or discard).
            insertLocked(t);
        }
        pid = t.pid;
        uid = t.uid;
        elapsed = now() - t.started;
    }

    if (expired) {
        counters_.expired++;
    } else {
        counters_.extended++;
    }

    // Deliver the notification outside of the lock.
    if (expired) {
        if (!notifier_(timerId, pid, uid, elapsed, notifierCookie_, notifierObject_)) {
            AutoMutex _l(lock_);
            // Notification failed, which means the listener will never call accept() or
            // discard().  Do not reinsert the timer.
            discard(timerId);
        }
    }
}

void AnrTimerService::insertLocked(const Timer& t) {
    running_.insert(t);
    if (t.status == Running) {
        // Only forward running timers to the ticker.  Expired timers are handled separately.
        ticker_->insert(t.scheduled, t.id, this);
    }
    maxRunning_ = std::max(maxRunning_, running_.size());
}

AnrTimerService::Timer AnrTimerService::removeLocked(timer_id_t timerId) {
    Timer key(timerId);
    auto found = running_.find(key);
    if (found != running_.end()) {
        Timer result = *found;
        running_.erase(found);
        ticker_->remove(result.scheduled, result.id);
        if (running_.size() == 0) counters_.drained++;
        return result;
    }
    return Timer();
}

std::vector<std::string> AnrTimerService::getDump() const {
    std::vector<std::string> r;
    AutoMutex _l(lock_);
    r.push_back(StringPrintf("started:%zu canceled:%zu accepted:%zu discarded:%zu expired:%zu",
                             counters_.started,
                             counters_.canceled,
                             counters_.accepted,
                             counters_.discarded,
                             counters_.expired));
    r.push_back(StringPrintf("extended:%zu drained:%zu error:%zu running:%zu maxRunning:%zu",
                             counters_.extended,
                             counters_.drained,
                             counters_.error,
                             running_.size(),
                             maxRunning_));
    r.push_back(StringPrintf("released:%zu releasing:%zu",
                             counters_.released,
                             expired_.size()));
    r.push_back(StringPrintf("ticker:%zu ticking:%zu maxTicking:%zu",
                             ticker_->id(),
                             ticker_->running(),
                             ticker_->maxRunning()));
    return r;
}

/**
 * True if the native methods are supported in this process.  Native methods are supported only
 * if the initialization succeeds.
 */
bool nativeSupportEnabled = false;

/**
 * Singleton/globals for the anr timer.  Among other things, this includes a Ticker* and a use
 * count.  The JNI layer creates a single Ticker for all operational AnrTimers.  The Ticker is
 * created when the first AnrTimer is created; this means that the Ticker is only created if
 * native anr timers are used.
 */
static Mutex gAnrLock;
struct AnrArgs {
    jclass clazz = NULL;
    jmethodID func = NULL;
    JavaVM* vm = NULL;
    AnrTimerService::Ticker* ticker = nullptr;
};
static AnrArgs gAnrArgs;

// The cookie is the address of the AnrArgs object to which the notification should be sent.
static bool anrNotify(AnrTimerService::timer_id_t timerId, int pid, int uid, nsecs_t elapsed,
                      void* cookie, jweak jtimer) {
    AutoMutex _l(gAnrLock);
    AnrArgs* target = reinterpret_cast<AnrArgs* >(cookie);
    JNIEnv *env;
    if (target->vm->AttachCurrentThread(&env, 0) != JNI_OK) {
        ALOGE("failed to attach thread to JavaVM");
        return false;
    }
    jboolean r = false;
    jobject timer = env->NewGlobalRef(jtimer);
    if (timer != nullptr) {
        // Convert the elsapsed time from ns (native) to ms (Java)
        r = env->CallBooleanMethod(timer, target->func, timerId, pid, uid, ns2ms(elapsed));
        env->DeleteGlobalRef(timer);
    }
    target->vm->DetachCurrentThread();
    return r;
}

jboolean anrTimerSupported(JNIEnv* env, jclass) {
    return nativeSupportEnabled;
}

jlong anrTimerCreate(JNIEnv* env, jobject jtimer, jstring jname,
                     jboolean extend, jboolean freeze) {
    if (!nativeSupportEnabled) return 0;
    AutoMutex _l(gAnrLock);
    if (gAnrArgs.ticker == nullptr) {
        gAnrArgs.ticker = new AnrTimerService::Ticker();
    }

    ScopedUtfChars name(env, jname);
    jobject timer = env->NewWeakGlobalRef(jtimer);
    AnrTimerService* service = new AnrTimerService(name.c_str(),
            anrNotify, &gAnrArgs, timer, gAnrArgs.ticker, extend, freeze);
    return reinterpret_cast<jlong>(service);
}

AnrTimerService *toService(jlong pointer) {
    return reinterpret_cast<AnrTimerService*>(pointer);
}

jint anrTimerClose(JNIEnv* env, jclass, jlong ptr) {
    if (!nativeSupportEnabled) return -1;
    if (ptr == 0) return -1;
    AutoMutex _l(gAnrLock);
    AnrTimerService *s = toService(ptr);
    env->DeleteWeakGlobalRef(s->jtimer());
    delete s;
    return 0;
}

jint anrTimerStart(JNIEnv* env, jclass, jlong ptr, jint pid, jint uid, jlong timeout) {
    if (!nativeSupportEnabled) return 0;
    // On the Java side, timeouts are expressed in milliseconds and must be converted to
    // nanoseconds before being passed to the library code.
    return toService(ptr)->start(pid, uid, milliseconds_to_nanoseconds(timeout));
}

jboolean anrTimerCancel(JNIEnv* env, jclass, jlong ptr, jint timerId) {
    if (!nativeSupportEnabled) return false;
    return toService(ptr)->cancel(timerId);
}

jboolean anrTimerAccept(JNIEnv* env, jclass, jlong ptr, jint timerId) {
    if (!nativeSupportEnabled) return false;
    return toService(ptr)->accept(timerId);
}

jboolean anrTimerDiscard(JNIEnv* env, jclass, jlong ptr, jint timerId) {
    if (!nativeSupportEnabled) return false;
    return toService(ptr)->discard(timerId);
}

jboolean anrTimerRelease(JNIEnv* env, jclass, jlong ptr, jint timerId) {
    if (!nativeSupportEnabled) return false;
    return toService(ptr)->release(timerId);
}

jobjectArray anrTimerDump(JNIEnv *env, jclass, jlong ptr) {
    if (!nativeSupportEnabled) return nullptr;
    std::vector<std::string> stats = toService(ptr)->getDump();
    jclass sclass = env->FindClass("java/lang/String");
    jobjectArray r = env->NewObjectArray(stats.size(), sclass, nullptr);
    for (size_t i = 0; i < stats.size(); i++) {
        env->SetObjectArrayElement(r, i, env->NewStringUTF(stats[i].c_str()));
    }
    return r;
}

static const JNINativeMethod methods[] = {
    {"nativeAnrTimerSupported", "()Z",  (void*) anrTimerSupported},
    {"nativeAnrTimerCreate",   "(Ljava/lang/String;ZZ)J", (void*) anrTimerCreate},
    {"nativeAnrTimerClose",    "(J)I",     (void*) anrTimerClose},
    {"nativeAnrTimerStart",    "(JIIJ)I",  (void*) anrTimerStart},
    {"nativeAnrTimerCancel",   "(JI)Z",    (void*) anrTimerCancel},
    {"nativeAnrTimerAccept",   "(JI)Z",    (void*) anrTimerAccept},
    {"nativeAnrTimerDiscard",  "(JI)Z",    (void*) anrTimerDiscard},
    {"nativeAnrTimerRelease",  "(JI)Z",    (void*) anrTimerRelease},
    {"nativeAnrTimerDump",     "(J)[Ljava/lang/String;", (void*) anrTimerDump},
};

} // anonymous namespace

int register_android_server_utils_AnrTimer(JNIEnv* env)
{
    static const char *className = "com/android/server/utils/AnrTimer";
    jniRegisterNativeMethods(env, className, methods, NELEM(methods));

    nativeSupportEnabled = NATIVE_SUPPORT;

    // Do not perform any further initialization if native support is not enabled.
    if (!nativeSupportEnabled) return 0;

    jclass service = FindClassOrDie(env, className);
    gAnrArgs.clazz = MakeGlobalRefOrDie(env, service);
    gAnrArgs.func = env->GetMethodID(gAnrArgs.clazz, "expire", "(IIIJ)Z");
    env->GetJavaVM(&gAnrArgs.vm);

    return 0;
}

} // namespace android
