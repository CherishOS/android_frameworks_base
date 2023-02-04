/*
 * Copyright (C) 2021 The Android Open Source Project
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

#define LOG_TAG "InputController"

#include <android-base/unique_fd.h>
#include <android/input.h>
#include <android/keycodes.h>
#include <errno.h>
#include <fcntl.h>
#include <input/Input.h>
#include <linux/uinput.h>
#include <math.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <utils/Log.h>

#include <map>
#include <set>
#include <string>

/**
 * Log debug messages about native virtual input devices.
 * Enable this via "adb shell setprop log.tag.InputController DEBUG"
 */
static bool isDebug() {
    return __android_log_is_loggable(ANDROID_LOG_DEBUG, LOG_TAG, ANDROID_LOG_INFO);
}

namespace android {

enum class DeviceType {
    KEYBOARD,
    MOUSE,
    TOUCHSCREEN,
    DPAD,
};

enum class UinputAction {
    RELEASE = 0,
    PRESS = 1,
    MOVE = 2,
    CANCEL = 3,
};

static std::map<int, UinputAction> BUTTON_ACTION_MAPPING = {
        {AMOTION_EVENT_ACTION_BUTTON_PRESS, UinputAction::PRESS},
        {AMOTION_EVENT_ACTION_BUTTON_RELEASE, UinputAction::RELEASE},
};

static std::map<int, UinputAction> KEY_ACTION_MAPPING = {
        {AKEY_EVENT_ACTION_DOWN, UinputAction::PRESS},
        {AKEY_EVENT_ACTION_UP, UinputAction::RELEASE},
};

static std::map<int, UinputAction> TOUCH_ACTION_MAPPING = {
        {AMOTION_EVENT_ACTION_DOWN, UinputAction::PRESS},
        {AMOTION_EVENT_ACTION_UP, UinputAction::RELEASE},
        {AMOTION_EVENT_ACTION_MOVE, UinputAction::MOVE},
        {AMOTION_EVENT_ACTION_CANCEL, UinputAction::CANCEL},
};

// Button code mapping from https://source.android.com/devices/input/touch-devices
static std::map<int, int> BUTTON_CODE_MAPPING = {
        {AMOTION_EVENT_BUTTON_PRIMARY, BTN_LEFT},    {AMOTION_EVENT_BUTTON_SECONDARY, BTN_RIGHT},
        {AMOTION_EVENT_BUTTON_TERTIARY, BTN_MIDDLE}, {AMOTION_EVENT_BUTTON_BACK, BTN_BACK},
        {AMOTION_EVENT_BUTTON_FORWARD, BTN_FORWARD},
};

// Tool type mapping from https://source.android.com/devices/input/touch-devices
static std::map<int, int> TOOL_TYPE_MAPPING = {
        {AMOTION_EVENT_TOOL_TYPE_FINGER, MT_TOOL_FINGER},
        {AMOTION_EVENT_TOOL_TYPE_PALM, MT_TOOL_PALM},
};

// Dpad keycode mapping from https://source.android.com/devices/input/keyboard-devices
static std::map<int, int> DPAD_KEY_CODE_MAPPING = {
        {AKEYCODE_DPAD_DOWN, KEY_DOWN},     {AKEYCODE_DPAD_UP, KEY_UP},
        {AKEYCODE_DPAD_LEFT, KEY_LEFT},     {AKEYCODE_DPAD_RIGHT, KEY_RIGHT},
        {AKEYCODE_DPAD_CENTER, KEY_SELECT}, {AKEYCODE_BACK, KEY_BACK},
};

// Keycode mapping from https://source.android.com/devices/input/keyboard-devices
static std::map<int, int> KEY_CODE_MAPPING = {
        {AKEYCODE_0, KEY_0},
        {AKEYCODE_1, KEY_1},
        {AKEYCODE_2, KEY_2},
        {AKEYCODE_3, KEY_3},
        {AKEYCODE_4, KEY_4},
        {AKEYCODE_5, KEY_5},
        {AKEYCODE_6, KEY_6},
        {AKEYCODE_7, KEY_7},
        {AKEYCODE_8, KEY_8},
        {AKEYCODE_9, KEY_9},
        {AKEYCODE_A, KEY_A},
        {AKEYCODE_B, KEY_B},
        {AKEYCODE_C, KEY_C},
        {AKEYCODE_D, KEY_D},
        {AKEYCODE_E, KEY_E},
        {AKEYCODE_F, KEY_F},
        {AKEYCODE_G, KEY_G},
        {AKEYCODE_H, KEY_H},
        {AKEYCODE_I, KEY_I},
        {AKEYCODE_J, KEY_J},
        {AKEYCODE_K, KEY_K},
        {AKEYCODE_L, KEY_L},
        {AKEYCODE_M, KEY_M},
        {AKEYCODE_N, KEY_N},
        {AKEYCODE_O, KEY_O},
        {AKEYCODE_P, KEY_P},
        {AKEYCODE_Q, KEY_Q},
        {AKEYCODE_R, KEY_R},
        {AKEYCODE_S, KEY_S},
        {AKEYCODE_T, KEY_T},
        {AKEYCODE_U, KEY_U},
        {AKEYCODE_V, KEY_V},
        {AKEYCODE_W, KEY_W},
        {AKEYCODE_X, KEY_X},
        {AKEYCODE_Y, KEY_Y},
        {AKEYCODE_Z, KEY_Z},
        {AKEYCODE_GRAVE, KEY_GRAVE},
        {AKEYCODE_MINUS, KEY_MINUS},
        {AKEYCODE_EQUALS, KEY_EQUAL},
        {AKEYCODE_LEFT_BRACKET, KEY_LEFTBRACE},
        {AKEYCODE_RIGHT_BRACKET, KEY_RIGHTBRACE},
        {AKEYCODE_BACKSLASH, KEY_BACKSLASH},
        {AKEYCODE_SEMICOLON, KEY_SEMICOLON},
        {AKEYCODE_APOSTROPHE, KEY_APOSTROPHE},
        {AKEYCODE_COMMA, KEY_COMMA},
        {AKEYCODE_PERIOD, KEY_DOT},
        {AKEYCODE_SLASH, KEY_SLASH},
        {AKEYCODE_ALT_LEFT, KEY_LEFTALT},
        {AKEYCODE_ALT_RIGHT, KEY_RIGHTALT},
        {AKEYCODE_CTRL_LEFT, KEY_LEFTCTRL},
        {AKEYCODE_CTRL_RIGHT, KEY_RIGHTCTRL},
        {AKEYCODE_SHIFT_LEFT, KEY_LEFTSHIFT},
        {AKEYCODE_SHIFT_RIGHT, KEY_RIGHTSHIFT},
        {AKEYCODE_META_LEFT, KEY_LEFTMETA},
        {AKEYCODE_META_RIGHT, KEY_RIGHTMETA},
        {AKEYCODE_CAPS_LOCK, KEY_CAPSLOCK},
        {AKEYCODE_SCROLL_LOCK, KEY_SCROLLLOCK},
        {AKEYCODE_NUM_LOCK, KEY_NUMLOCK},
        {AKEYCODE_ENTER, KEY_ENTER},
        {AKEYCODE_TAB, KEY_TAB},
        {AKEYCODE_SPACE, KEY_SPACE},
        {AKEYCODE_DPAD_DOWN, KEY_DOWN},
        {AKEYCODE_DPAD_UP, KEY_UP},
        {AKEYCODE_DPAD_LEFT, KEY_LEFT},
        {AKEYCODE_DPAD_RIGHT, KEY_RIGHT},
        {AKEYCODE_MOVE_END, KEY_END},
        {AKEYCODE_MOVE_HOME, KEY_HOME},
        {AKEYCODE_PAGE_DOWN, KEY_PAGEDOWN},
        {AKEYCODE_PAGE_UP, KEY_PAGEUP},
        {AKEYCODE_DEL, KEY_BACKSPACE},
        {AKEYCODE_FORWARD_DEL, KEY_DELETE},
        {AKEYCODE_INSERT, KEY_INSERT},
        {AKEYCODE_ESCAPE, KEY_ESC},
        {AKEYCODE_BREAK, KEY_PAUSE},
        {AKEYCODE_F1, KEY_F1},
        {AKEYCODE_F2, KEY_F2},
        {AKEYCODE_F3, KEY_F3},
        {AKEYCODE_F4, KEY_F4},
        {AKEYCODE_F5, KEY_F5},
        {AKEYCODE_F6, KEY_F6},
        {AKEYCODE_F7, KEY_F7},
        {AKEYCODE_F8, KEY_F8},
        {AKEYCODE_F9, KEY_F9},
        {AKEYCODE_F10, KEY_F10},
        {AKEYCODE_F11, KEY_F11},
        {AKEYCODE_F12, KEY_F12},
        {AKEYCODE_BACK, KEY_BACK},
        {AKEYCODE_FORWARD, KEY_FORWARD},
        {AKEYCODE_NUMPAD_1, KEY_KP1},
        {AKEYCODE_NUMPAD_2, KEY_KP2},
        {AKEYCODE_NUMPAD_3, KEY_KP3},
        {AKEYCODE_NUMPAD_4, KEY_KP4},
        {AKEYCODE_NUMPAD_5, KEY_KP5},
        {AKEYCODE_NUMPAD_6, KEY_KP6},
        {AKEYCODE_NUMPAD_7, KEY_KP7},
        {AKEYCODE_NUMPAD_8, KEY_KP8},
        {AKEYCODE_NUMPAD_9, KEY_KP9},
        {AKEYCODE_NUMPAD_0, KEY_KP0},
        {AKEYCODE_NUMPAD_ADD, KEY_KPPLUS},
        {AKEYCODE_NUMPAD_SUBTRACT, KEY_KPMINUS},
        {AKEYCODE_NUMPAD_MULTIPLY, KEY_KPASTERISK},
        {AKEYCODE_NUMPAD_DIVIDE, KEY_KPSLASH},
        {AKEYCODE_NUMPAD_DOT, KEY_KPDOT},
        {AKEYCODE_NUMPAD_ENTER, KEY_KPENTER},
        {AKEYCODE_NUMPAD_EQUALS, KEY_KPEQUAL},
        {AKEYCODE_NUMPAD_COMMA, KEY_KPCOMMA},
};

/*
 * Map from the uinput touchscreen fd to the pointers present in the previous touch events that
 * hasn't been lifted.
 * We only allow pointer id to go up to MAX_POINTERS because the maximum slots of virtual
 * touchscreen is set up with MAX_POINTERS. Note that in other cases Android allows pointer id to go
 * up to MAX_POINTERS_ID.
 */
static std::map<int32_t, std::bitset<MAX_POINTERS>> unreleasedTouches;

/** Creates a new uinput device and assigns a file descriptor. */
static int openUinput(const char* readableName, jint vendorId, jint productId, const char* phys,
                      DeviceType deviceType, jint screenHeight, jint screenWidth) {
    android::base::unique_fd fd(TEMP_FAILURE_RETRY(::open("/dev/uinput", O_WRONLY | O_NONBLOCK)));
    if (fd < 0) {
        ALOGE("Error creating uinput device: %s", strerror(errno));
        return -errno;
    }

    ioctl(fd, UI_SET_PHYS, phys);

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    switch (deviceType) {
        case DeviceType::DPAD:
            for (const auto& [_, keyCode] : DPAD_KEY_CODE_MAPPING) {
                ioctl(fd, UI_SET_KEYBIT, keyCode);
            }
            break;
        case DeviceType::KEYBOARD:
            for (const auto& [_, keyCode] : KEY_CODE_MAPPING) {
                ioctl(fd, UI_SET_KEYBIT, keyCode);
            }
            break;
        case DeviceType::MOUSE:
            ioctl(fd, UI_SET_EVBIT, EV_REL);
            ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
            ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
            ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
            ioctl(fd, UI_SET_KEYBIT, BTN_BACK);
            ioctl(fd, UI_SET_KEYBIT, BTN_FORWARD);
            ioctl(fd, UI_SET_RELBIT, REL_X);
            ioctl(fd, UI_SET_RELBIT, REL_Y);
            ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
            ioctl(fd, UI_SET_RELBIT, REL_HWHEEL);
            break;
        case DeviceType::TOUCHSCREEN:
            ioctl(fd, UI_SET_EVBIT, EV_ABS);
            ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOOL_TYPE);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
            ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    }

    int version;
    if (ioctl(fd, UI_GET_VERSION, &version) == 0 && version >= 5) {
        uinput_setup setup;
        memset(&setup, 0, sizeof(setup));
        strlcpy(setup.name, readableName, UINPUT_MAX_NAME_SIZE);
        setup.id.version = 1;
        setup.id.bustype = BUS_VIRTUAL;
        setup.id.vendor = vendorId;
        setup.id.product = productId;
        if (deviceType == DeviceType::TOUCHSCREEN) {
            uinput_abs_setup xAbsSetup;
            xAbsSetup.code = ABS_MT_POSITION_X;
            xAbsSetup.absinfo.maximum = screenWidth - 1;
            xAbsSetup.absinfo.minimum = 0;
            if (ioctl(fd, UI_ABS_SETUP, &xAbsSetup) != 0) {
                ALOGE("Error creating touchscreen uinput x axis: %s", strerror(errno));
                return -errno;
            }
            uinput_abs_setup yAbsSetup;
            yAbsSetup.code = ABS_MT_POSITION_Y;
            yAbsSetup.absinfo.maximum = screenHeight - 1;
            yAbsSetup.absinfo.minimum = 0;
            if (ioctl(fd, UI_ABS_SETUP, &yAbsSetup) != 0) {
                ALOGE("Error creating touchscreen uinput y axis: %s", strerror(errno));
                return -errno;
            }
            uinput_abs_setup majorAbsSetup;
            majorAbsSetup.code = ABS_MT_TOUCH_MAJOR;
            majorAbsSetup.absinfo.maximum = screenWidth - 1;
            majorAbsSetup.absinfo.minimum = 0;
            if (ioctl(fd, UI_ABS_SETUP, &majorAbsSetup) != 0) {
                ALOGE("Error creating touchscreen uinput major axis: %s", strerror(errno));
                return -errno;
            }
            uinput_abs_setup pressureAbsSetup;
            pressureAbsSetup.code = ABS_MT_PRESSURE;
            pressureAbsSetup.absinfo.maximum = 255;
            pressureAbsSetup.absinfo.minimum = 0;
            if (ioctl(fd, UI_ABS_SETUP, &pressureAbsSetup) != 0) {
                ALOGE("Error creating touchscreen uinput pressure axis: %s", strerror(errno));
                return -errno;
            }
            uinput_abs_setup slotAbsSetup;
            slotAbsSetup.code = ABS_MT_SLOT;
            slotAbsSetup.absinfo.maximum = MAX_POINTERS;
            slotAbsSetup.absinfo.minimum = 0;
            if (ioctl(fd, UI_ABS_SETUP, &slotAbsSetup) != 0) {
                ALOGE("Error creating touchscreen uinput slots: %s", strerror(errno));
                return -errno;
            }
        }
        if (ioctl(fd, UI_DEV_SETUP, &setup) != 0) {
            ALOGE("Error creating uinput device: %s", strerror(errno));
            return -errno;
        }
    } else {
        // UI_DEV_SETUP was not introduced until version 5. Try setting up manually.
        ALOGI("Falling back to version %d manual setup", version);
        uinput_user_dev fallback;
        memset(&fallback, 0, sizeof(fallback));
        strlcpy(fallback.name, readableName, UINPUT_MAX_NAME_SIZE);
        fallback.id.version = 1;
        fallback.id.bustype = BUS_VIRTUAL;
        fallback.id.vendor = vendorId;
        fallback.id.product = productId;
        if (deviceType == DeviceType::TOUCHSCREEN) {
            fallback.absmin[ABS_MT_POSITION_X] = 0;
            fallback.absmax[ABS_MT_POSITION_X] = screenWidth - 1;
            fallback.absmin[ABS_MT_POSITION_Y] = 0;
            fallback.absmax[ABS_MT_POSITION_Y] = screenHeight - 1;
            fallback.absmin[ABS_MT_TOUCH_MAJOR] = 0;
            fallback.absmax[ABS_MT_TOUCH_MAJOR] = screenWidth - 1;
            fallback.absmin[ABS_MT_PRESSURE] = 0;
            fallback.absmax[ABS_MT_PRESSURE] = 255;
        }
        if (TEMP_FAILURE_RETRY(write(fd, &fallback, sizeof(fallback))) != sizeof(fallback)) {
            ALOGE("Error creating uinput device: %s", strerror(errno));
            return -errno;
        }
    }

    if (ioctl(fd, UI_DEV_CREATE) != 0) {
        ALOGE("Error creating uinput device: %s", strerror(errno));
        return -errno;
    }

    return fd.release();
}

static int openUinputJni(JNIEnv* env, jstring name, jint vendorId, jint productId, jstring phys,
                         DeviceType deviceType, int screenHeight, int screenWidth) {
    ScopedUtfChars readableName(env, name);
    ScopedUtfChars readablePhys(env, phys);
    return openUinput(readableName.c_str(), vendorId, productId, readablePhys.c_str(), deviceType,
                      screenHeight, screenWidth);
}

static int nativeOpenUinputDpad(JNIEnv* env, jobject thiz, jstring name, jint vendorId,
                                jint productId, jstring phys) {
    return openUinputJni(env, name, vendorId, productId, phys, DeviceType::DPAD,
                         /* screenHeight */ 0, /* screenWidth */ 0);
}

static int nativeOpenUinputKeyboard(JNIEnv* env, jobject thiz, jstring name, jint vendorId,
                                    jint productId, jstring phys) {
    return openUinputJni(env, name, vendorId, productId, phys, DeviceType::KEYBOARD,
                         /* screenHeight */ 0, /* screenWidth */ 0);
}

static int nativeOpenUinputMouse(JNIEnv* env, jobject thiz, jstring name, jint vendorId,
                                 jint productId, jstring phys) {
    return openUinputJni(env, name, vendorId, productId, phys, DeviceType::MOUSE,
                         /* screenHeight */ 0, /* screenWidth */ 0);
}

static int nativeOpenUinputTouchscreen(JNIEnv* env, jobject thiz, jstring name, jint vendorId,
                                       jint productId, jstring phys, jint height, jint width) {
    return openUinputJni(env, name, vendorId, productId, phys, DeviceType::TOUCHSCREEN, height,
                         width);
}

static bool nativeCloseUinput(JNIEnv* env, jobject thiz, jint fd) {
    ioctl(fd, UI_DEV_DESTROY);
    if (auto touchesOnFd = unreleasedTouches.find(fd); touchesOnFd != unreleasedTouches.end()) {
        const size_t remainingPointers = touchesOnFd->second.size();
        unreleasedTouches.erase(touchesOnFd);
        ALOGW_IF(remainingPointers > 0, "Closing touchscreen %d, erased %zu unreleased pointers.",
                 fd, remainingPointers);
    }
    return close(fd);
}

static bool writeInputEvent(int fd, uint16_t type, uint16_t code, int32_t value) {
    struct input_event ev = {.type = type, .code = code, .value = value};
    return TEMP_FAILURE_RETRY(write(fd, &ev, sizeof(struct input_event))) == sizeof(ev);
}

static bool writeKeyEvent(jint fd, jint androidKeyCode, jint action,
                          const std::map<int, int>& keyCodeMapping) {
    auto keyCodeIterator = keyCodeMapping.find(androidKeyCode);
    if (keyCodeIterator == keyCodeMapping.end()) {
        ALOGE("Unsupported native keycode for androidKeyCode %d", androidKeyCode);
        return false;
    }
    auto actionIterator = KEY_ACTION_MAPPING.find(action);
    if (actionIterator == KEY_ACTION_MAPPING.end()) {
        return false;
    }
    if (!writeInputEvent(fd, EV_KEY, static_cast<uint16_t>(keyCodeIterator->second),
                         static_cast<int32_t>(actionIterator->second))) {
        return false;
    }
    if (!writeInputEvent(fd, EV_SYN, SYN_REPORT, 0)) {
        return false;
    }
    return true;
}

static bool nativeWriteDpadKeyEvent(JNIEnv* env, jobject thiz, jint fd, jint androidKeyCode,
                                    jint action) {
    return writeKeyEvent(fd, androidKeyCode, action, DPAD_KEY_CODE_MAPPING);
}

static bool nativeWriteKeyEvent(JNIEnv* env, jobject thiz, jint fd, jint androidKeyCode,
                                jint action) {
    return writeKeyEvent(fd, androidKeyCode, action, KEY_CODE_MAPPING);
}

static bool nativeWriteButtonEvent(JNIEnv* env, jobject thiz, jint fd, jint buttonCode,
                                   jint action) {
    auto buttonCodeIterator = BUTTON_CODE_MAPPING.find(buttonCode);
    if (buttonCodeIterator == BUTTON_CODE_MAPPING.end()) {
        return false;
    }
    auto actionIterator = BUTTON_ACTION_MAPPING.find(action);
    if (actionIterator == BUTTON_ACTION_MAPPING.end()) {
        return false;
    }
    if (!writeInputEvent(fd, EV_KEY, static_cast<uint16_t>(buttonCodeIterator->second),
                         static_cast<int32_t>(actionIterator->second))) {
        return false;
    }
    if (!writeInputEvent(fd, EV_SYN, SYN_REPORT, 0)) {
        return false;
    }
    return true;
}

static bool handleTouchUp(int fd, int pointerId) {
    if (!writeInputEvent(fd, EV_ABS, ABS_MT_TRACKING_ID, static_cast<int32_t>(-1))) {
        return false;
    }
    auto touchesOnFd = unreleasedTouches.find(fd);
    if (touchesOnFd == unreleasedTouches.end()) {
        ALOGE("PointerId %d action UP received with no prior events on touchscreen %d.", pointerId,
              fd);
        return false;
    }
    ALOGD_IF(isDebug(), "Unreleased touches found for touchscreen %d in the map", fd);

    // When a pointer is no longer in touch, remove the pointer id from the corresponding
    // entry in the unreleased touches map.
    if (pointerId < 0 || pointerId >= MAX_POINTERS) {
        ALOGE("Virtual touch event has invalid pointer id %d; value must be between 0 and %zu",
              pointerId, MAX_POINTERS - 1);
        return false;
    }
    if (!touchesOnFd->second.test(pointerId)) {
        ALOGE("PointerId %d action UP received with no prior action DOWN on touchscreen %d.",
              pointerId, fd);
        return false;
    }
    touchesOnFd->second.reset(pointerId);
    ALOGD_IF(isDebug(), "Pointer %d erased from the touchscreen %d", pointerId, fd);

    // Only sends the BTN UP event when there's no pointers on the touchscreen.
    if (touchesOnFd->second.none()) {
        unreleasedTouches.erase(touchesOnFd);
        if (!writeInputEvent(fd, EV_KEY, BTN_TOUCH, static_cast<int32_t>(UinputAction::RELEASE))) {
            return false;
        }
        ALOGD_IF(isDebug(), "No pointers on touchscreen %d, BTN UP event sent.", fd);
    }
    return true;
}

static bool handleTouchDown(int fd, int pointerId) {
    // When a new pointer is down on the touchscreen, add the pointer id in the corresponding
    // entry in the unreleased touches map.
    auto touchesOnFd = unreleasedTouches.find(fd);
    if (touchesOnFd == unreleasedTouches.end()) {
        // Only sends the BTN Down event when the first pointer on the touchscreen is down.
        if (!writeInputEvent(fd, EV_KEY, BTN_TOUCH, static_cast<int32_t>(UinputAction::PRESS))) {
            return false;
        }
        touchesOnFd = unreleasedTouches.insert({fd, {}}).first;
        ALOGD_IF(isDebug(), "New touchscreen with fd %d added in the unreleased touches map.", fd);
    }
    if (touchesOnFd->second.test(pointerId)) {
        ALOGE("Repetitive action DOWN event received on a pointer %d that is already down.",
              pointerId);
        return false;
    }
    touchesOnFd->second.set(pointerId);
    ALOGD_IF(isDebug(), "Added pointer %d under touchscreen %d in the map", pointerId, fd);
    if (!writeInputEvent(fd, EV_ABS, ABS_MT_TRACKING_ID, static_cast<int32_t>(pointerId))) {
        return false;
    }
    return true;
}

static bool nativeWriteTouchEvent(JNIEnv* env, jobject thiz, jint fd, jint pointerId, jint toolType,
                                  jint action, jfloat locationX, jfloat locationY, jfloat pressure,
                                  jfloat majorAxisSize) {
    if (!writeInputEvent(fd, EV_ABS, ABS_MT_SLOT, pointerId)) {
        return false;
    }
    auto toolTypeIterator = TOOL_TYPE_MAPPING.find(toolType);
    if (toolTypeIterator == TOOL_TYPE_MAPPING.end()) {
        return false;
    }
    if (toolType != -1) {
        if (!writeInputEvent(fd, EV_ABS, ABS_MT_TOOL_TYPE,
                             static_cast<int32_t>(toolTypeIterator->second))) {
            return false;
        }
    }
    auto actionIterator = TOUCH_ACTION_MAPPING.find(action);
    if (actionIterator == TOUCH_ACTION_MAPPING.end()) {
        return false;
    }
    UinputAction uinputAction = actionIterator->second;
    if (uinputAction == UinputAction::PRESS && !handleTouchDown(fd, pointerId)) {
        return false;
    } else if (uinputAction == UinputAction::RELEASE && !handleTouchUp(fd, pointerId)) {
        return false;
    }
    if (!writeInputEvent(fd, EV_ABS, ABS_MT_POSITION_X, locationX)) {
        return false;
    }
    if (!writeInputEvent(fd, EV_ABS, ABS_MT_POSITION_Y, locationY)) {
        return false;
    }
    if (!isnan(pressure)) {
        if (!writeInputEvent(fd, EV_ABS, ABS_MT_PRESSURE, pressure)) {
            return false;
        }
    }
    if (!isnan(majorAxisSize)) {
        if (!writeInputEvent(fd, EV_ABS, ABS_MT_TOUCH_MAJOR, majorAxisSize)) {
            return false;
        }
    }
    return writeInputEvent(fd, EV_SYN, SYN_REPORT, 0);
}

static bool nativeWriteRelativeEvent(JNIEnv* env, jobject thiz, jint fd, jfloat relativeX,
                                     jfloat relativeY) {
    return writeInputEvent(fd, EV_REL, REL_X, relativeX) &&
            writeInputEvent(fd, EV_REL, REL_Y, relativeY) &&
            writeInputEvent(fd, EV_SYN, SYN_REPORT, 0);
}

static bool nativeWriteScrollEvent(JNIEnv* env, jobject thiz, jint fd, jfloat xAxisMovement,
                                   jfloat yAxisMovement) {
    return writeInputEvent(fd, EV_REL, REL_HWHEEL, xAxisMovement) &&
            writeInputEvent(fd, EV_REL, REL_WHEEL, yAxisMovement) &&
            writeInputEvent(fd, EV_SYN, SYN_REPORT, 0);
}

static JNINativeMethod methods[] = {
        {"nativeOpenUinputDpad", "(Ljava/lang/String;IILjava/lang/String;)I",
         (void*)nativeOpenUinputDpad},
        {"nativeOpenUinputKeyboard", "(Ljava/lang/String;IILjava/lang/String;)I",
         (void*)nativeOpenUinputKeyboard},
        {"nativeOpenUinputMouse", "(Ljava/lang/String;IILjava/lang/String;)I",
         (void*)nativeOpenUinputMouse},
        {"nativeOpenUinputTouchscreen", "(Ljava/lang/String;IILjava/lang/String;II)I",
         (void*)nativeOpenUinputTouchscreen},
        {"nativeCloseUinput", "(I)Z", (void*)nativeCloseUinput},
        {"nativeWriteDpadKeyEvent", "(III)Z", (void*)nativeWriteDpadKeyEvent},
        {"nativeWriteKeyEvent", "(III)Z", (void*)nativeWriteKeyEvent},
        {"nativeWriteButtonEvent", "(III)Z", (void*)nativeWriteButtonEvent},
        {"nativeWriteTouchEvent", "(IIIIFFFF)Z", (void*)nativeWriteTouchEvent},
        {"nativeWriteRelativeEvent", "(IFF)Z", (void*)nativeWriteRelativeEvent},
        {"nativeWriteScrollEvent", "(IFF)Z", (void*)nativeWriteScrollEvent},
};

int register_android_server_companion_virtual_InputController(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/companion/virtual/InputController",
                                    methods, NELEM(methods));
}

} // namespace android
