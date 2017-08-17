#########################################################################
# Build FrameworksServicesTests package
#########################################################################

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_STATIC_JAVA_LIBRARIES := \
    frameworks-base-testutils \
    services.accessibility \
    services.appwidget \
    services.backup \
    services.core \
    services.devicepolicy \
    services.net \
    services.usage \
    guava \
    android-support-test \
    mockito-target-minus-junit4 \
    platform-test-annotations \
    ShortcutManagerTestUtils \
    truth-prebuilt

LOCAL_AIDL_INCLUDES := $(LOCAL_PATH)/aidl

LOCAL_SRC_FILES += aidl/com/android/servicestests/aidl/INetworkStateObserver.aidl \
    aidl/com/android/servicestests/aidl/ICmdReceiverService.aidl

LOCAL_JAVA_LIBRARIES := android.test.mock legacy-android-test

LOCAL_PACKAGE_NAME := FrameworksServicesTests
LOCAL_COMPATIBILITY_SUITE := device-tests

LOCAL_CERTIFICATE := platform

# These are not normally accessible from apps so they must be explicitly included.
LOCAL_JNI_SHARED_LIBRARIES := \
    libbacktrace \
    libbase \
    libbinder \
    libc++ \
    libcutils \
    liblog \
    liblzma \
    libnativehelper \
    libnetdaidl \
    libui \
    libunwind \
    libutils

LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

LOCAL_JACK_FLAGS := --multi-dex native
LOCAL_DX_FLAGS := --multi-dex

LOCAL_STATIC_JAVA_LIBRARIES += ub-uiautomator

include $(BUILD_PACKAGE)

include $(call all-makefiles-under, $(LOCAL_PATH))