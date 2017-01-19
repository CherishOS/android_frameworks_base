LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests
#LOCAL_SDK_VERSION := current

LOCAL_SRC_FILES := $(call all-java-files-under, src) \

LOCAL_JAVA_LIBRARIES := android-support-v4 android.test.runner
LOCAL_STATIC_JAVA_LIBRARIES := \
    mockito-target \
    ub-uiautomator \
    legacy-android-test

LOCAL_PACKAGE_NAME := DocumentsUIAppPerfTests
LOCAL_INSTRUMENTATION_FOR := DocumentsUI

LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)

