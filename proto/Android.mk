LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := framework-protos

LOCAL_PROTOC_OPTIMIZE_TYPE := nano
LOCAL_SRC_FILES:= $(call all-proto-files-under, src)
LOCAL_JARJAR_RULES := $(LOCAL_PATH)/jarjar-rules.txt

LOCAL_NO_STANDARD_LIBRARIES := true
LOCAL_JAVA_LIBRARIES := core-oj core-libart

LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk $(LOCAL_PATH)/jarjar-rules.txt

include $(BUILD_STATIC_JAVA_LIBRARY)

# Host-side version of framework-protos
# ============================================================
include $(CLEAR_VARS)

LOCAL_MODULE := host-framework-protos
LOCAL_MODULE_TAGS := optional

LOCAL_PROTOC_OPTIMIZE_TYPE := nano
LOCAL_SRC_FILES:= $(call all-proto-files-under, src)

LOCAL_NO_STANDARD_LIBRARIES := true
LOCAL_STATIC_JAVA_LIBRARIES := host-libprotobuf-java-nano

LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk $(LOCAL_PATH)/jarjar-rules.txt

include $(BUILD_HOST_JAVA_LIBRARY)