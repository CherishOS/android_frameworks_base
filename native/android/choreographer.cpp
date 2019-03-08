/*
 * Copyright (C) 2015 The Android Open Source Project
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

#define LOG_TAG "Choreographer"
//#define LOG_NDEBUG 0

#include <cinttypes>
#include <queue>
#include <thread>

#include <android/choreographer.h>
#include <androidfw/DisplayEventDispatcher.h>
#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>
#include <utils/Looper.h>
#include <utils/Mutex.h>
#include <utils/Timers.h>

namespace android {

static inline const char* toString(bool value) {
    return value ? "true" : "false";
}

struct FrameCallback {
    AChoreographer_frameCallback callback;
    AChoreographer_frameCallback64 callback64;
    void* data;
    nsecs_t dueTime;

    inline bool operator<(const FrameCallback& rhs) const {
        // Note that this is intentionally flipped because we want callbacks due sooner to be at
        // the head of the queue
        return dueTime > rhs.dueTime;
    }
};


class Choreographer : public DisplayEventDispatcher, public MessageHandler {
public:
    void postFrameCallbackDelayed(AChoreographer_frameCallback cb,
                                  AChoreographer_frameCallback64 cb64, void* data, nsecs_t delay);

    enum {
        MSG_SCHEDULE_CALLBACKS = 0,
        MSG_SCHEDULE_VSYNC = 1
    };
    virtual void handleMessage(const Message& message) override;

    static Choreographer* getForThread();

protected:
    virtual ~Choreographer() = default;

private:
    explicit Choreographer(const sp<Looper>& looper);
    Choreographer(const Choreographer&) = delete;

    void dispatchVsync(nsecs_t timestamp, PhysicalDisplayId displayId, uint32_t count) override;
    void dispatchHotplug(nsecs_t timestamp, PhysicalDisplayId displayId, bool connected) override;
    void dispatchConfigChanged(nsecs_t timestamp, PhysicalDisplayId displayId,
                               int32_t configId) override;

    void scheduleCallbacks();

    // Protected by mLock
    std::priority_queue<FrameCallback> mCallbacks;

    mutable Mutex mLock;

    const sp<Looper> mLooper;
    const std::thread::id mThreadId;
};


static thread_local Choreographer* gChoreographer;
Choreographer* Choreographer::getForThread() {
    if (gChoreographer == nullptr) {
        sp<Looper> looper = Looper::getForThread();
        if (!looper.get()) {
            ALOGW("No looper prepared for thread");
            return nullptr;
        }
        gChoreographer = new Choreographer(looper);
        status_t result = gChoreographer->initialize();
        if (result != OK) {
            ALOGW("Failed to initialize");
            return nullptr;
        }
    }
    return gChoreographer;
}

Choreographer::Choreographer(const sp<Looper>& looper) :
    DisplayEventDispatcher(looper), mLooper(looper), mThreadId(std::this_thread::get_id()) {
}

void Choreographer::postFrameCallbackDelayed(
        AChoreographer_frameCallback cb, AChoreographer_frameCallback64 cb64, void* data, nsecs_t delay) {
    nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
    FrameCallback callback{cb, cb64, data, now + delay};
    {
        AutoMutex _l{mLock};
        mCallbacks.push(callback);
    }
    if (callback.dueTime <= now) {
        if (std::this_thread::get_id() != mThreadId) {
            Message m{MSG_SCHEDULE_VSYNC};
            mLooper->sendMessage(this, m);
        } else {
            scheduleVsync();
        }
    } else {
        Message m{MSG_SCHEDULE_CALLBACKS};
        mLooper->sendMessageDelayed(delay, this, m);
    }
}

void Choreographer::scheduleCallbacks() {
    AutoMutex _{mLock};
    nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
    if (mCallbacks.top().dueTime <= now) {
        ALOGV("choreographer %p ~ scheduling vsync", this);
        scheduleVsync();
        return;
    }
}

// TODO(b/74619554): The PhysicalDisplayId is ignored because SF only emits VSYNC events for the
// internal display and DisplayEventReceiver::requestNextVsync only allows requesting VSYNC for
// the internal display implicitly.
void Choreographer::dispatchVsync(nsecs_t timestamp, PhysicalDisplayId, uint32_t) {
    std::vector<FrameCallback> callbacks{};
    {
        AutoMutex _l{mLock};
        nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
        while (!mCallbacks.empty() && mCallbacks.top().dueTime < now) {
            callbacks.push_back(mCallbacks.top());
            mCallbacks.pop();
        }
    }
    for (const auto& cb : callbacks) {
        if (cb.callback64 != nullptr) {
            cb.callback64(timestamp, cb.data);
        } else if (cb.callback != nullptr) {
            cb.callback(timestamp, cb.data);
        }
    }
}

void Choreographer::dispatchHotplug(nsecs_t, PhysicalDisplayId displayId, bool connected) {
    ALOGV("choreographer %p ~ received hotplug event (displayId=%"
            ANDROID_PHYSICAL_DISPLAY_ID_FORMAT ", connected=%s), ignoring.",
            this, displayId, toString(connected));
}

void Choreographer::dispatchConfigChanged(nsecs_t, PhysicalDisplayId displayId,
                                          int32_t configId) {
    ALOGV("choreographer %p ~ received config changed event (displayId=%"
            ANDROID_PHYSICAL_DISPLAY_ID_FORMAT ", configId=%s), ignoring.",
            this, displayId, toString(configId));
}

void Choreographer::handleMessage(const Message& message) {
    switch (message.what) {
    case MSG_SCHEDULE_CALLBACKS:
        scheduleCallbacks();
        break;
    case MSG_SCHEDULE_VSYNC:
        scheduleVsync();
        break;
    }
}

}

/* Glue for the NDK interface */

using android::Choreographer;

static inline Choreographer* AChoreographer_to_Choreographer(AChoreographer* choreographer) {
    return reinterpret_cast<Choreographer*>(choreographer);
}

static inline AChoreographer* Choreographer_to_AChoreographer(Choreographer* choreographer) {
    return reinterpret_cast<AChoreographer*>(choreographer);
}

AChoreographer* AChoreographer_getInstance() {
    return Choreographer_to_AChoreographer(Choreographer::getForThread());
}

void AChoreographer_postFrameCallback(AChoreographer* choreographer,
        AChoreographer_frameCallback callback, void* data) {
    AChoreographer_to_Choreographer(choreographer)->postFrameCallbackDelayed(
            callback, nullptr, data, 0);
}
void AChoreographer_postFrameCallbackDelayed(AChoreographer* choreographer,
        AChoreographer_frameCallback callback, void* data, long delayMillis) {
    AChoreographer_to_Choreographer(choreographer)->postFrameCallbackDelayed(
            callback, nullptr, data, ms2ns(delayMillis));
}
void AChoreographer_postFrameCallback64(AChoreographer* choreographer,
        AChoreographer_frameCallback64 callback, void* data) {
    AChoreographer_to_Choreographer(choreographer)->postFrameCallbackDelayed(
            nullptr, callback, data, 0);
}
void AChoreographer_postFrameCallbackDelayed64(AChoreographer* choreographer,
        AChoreographer_frameCallback64 callback, void* data, uint32_t delayMillis) {
    AChoreographer_to_Choreographer(choreographer)->postFrameCallbackDelayed(
            nullptr, callback, data, ms2ns(delayMillis));
}
