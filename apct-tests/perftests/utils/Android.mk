LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_STATIC_JAVA_LIBRARIES := \
    androidx.test.rules \
    androidx.annotation_annotation

# Build all java files in the java subdirectory
LOCAL_SRC_FILES := $(call all-subdir-java-files)

# The name of the jar file to create
LOCAL_MODULE := apct-perftests-utils

# Build a static jar file.
include $(BUILD_STATIC_JAVA_LIBRARY)