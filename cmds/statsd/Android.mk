# Copyright (C) 2017 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH:= $(call my-dir)

statsd_common_src := \
    ../../core/java/android/os/IStatsCallbacks.aidl \
    ../../core/java/android/os/IStatsCompanionService.aidl \
    ../../core/java/android/os/IStatsManager.aidl \
    src/stats_log.proto \
    src/statsd_config.proto \
    src/stats_events_copy.proto \
    src/anomaly/AnomalyMonitor.cpp \
    src/condition/CombinationConditionTracker.cpp \
    src/condition/condition_util.cpp \
    src/condition/SimpleConditionTracker.cpp \
    src/condition/ConditionWizard.cpp \
    src/config/ConfigKey.cpp \
    src/config/ConfigListener.cpp \
    src/config/ConfigManager.cpp \
    src/external/StatsCompanionServicePuller.cpp \
    src/external/ResourcePowerManagerPuller.cpp \
    src/external/StatsPullerManager.cpp \
    src/logd/LogEvent.cpp \
    src/logd/LogListener.cpp \
    src/logd/LogReader.cpp \
    src/matchers/CombinationLogMatchingTracker.cpp \
    src/matchers/matcher_util.cpp \
    src/matchers/SimpleLogMatchingTracker.cpp \
    src/metrics/CountAnomalyTracker.cpp \
    src/metrics/MetricProducer.cpp \
    src/metrics/EventMetricProducer.cpp \
    src/metrics/CountMetricProducer.cpp \
    src/metrics/DurationMetricProducer.cpp \
    src/metrics/duration_helper/OringDurationTracker.cpp \
    src/metrics/duration_helper/MaxDurationTracker.cpp \
    src/metrics/ValueMetricProducer.cpp \
    src/metrics/MetricsManager.cpp \
    src/metrics/metrics_manager_util.cpp \
    src/packages/UidMap.cpp \
    src/storage/DropboxReader.cpp \
    src/storage/DropboxWriter.cpp \
    src/StatsLogProcessor.cpp \
    src/StatsService.cpp \
    src/stats_util.cpp

statsd_common_c_includes := \
    $(LOCAL_PATH)/src \
    $(LOCAL_PATH)/../../libs/services/include

statsd_common_aidl_includes := \
    $(LOCAL_PATH)/../../core/java

statsd_common_shared_libraries := \
    libbase \
    libbinder \
    libcutils \
    libincident \
    liblog \
    libselinux \
    libutils \
    libservices \
    libandroidfw \
    libprotoutil \
    libstatslog \
    libhardware \
    libhardware_legacy \
    libhidlbase \
    libhidltransport \
    libhwbinder \
    android.hardware.power@1.0 \
    android.hardware.power@1.1

# =========
# statsd
# =========

include $(CLEAR_VARS)

LOCAL_MODULE := statsd

LOCAL_SRC_FILES := \
    $(statsd_common_src) \
    src/main.cpp

LOCAL_CFLAGS += \
    -Wall \
    -Werror \
    -Wno-missing-field-initializers \
    -Wno-unused-variable \
    -Wno-unused-function \
    -Wno-unused-parameter

ifeq (debug,)
    LOCAL_CFLAGS += \
            -g -O0
else
    # optimize for size (protobuf glop can get big)
    LOCAL_CFLAGS += \
            -Os
endif
LOCAL_PROTOC_OPTIMIZE_TYPE := lite-static

LOCAL_AIDL_INCLUDES := $(statsd_common_aidl_includes)
LOCAL_C_INCLUDES += $(statsd_common_c_includes)

LOCAL_SHARED_LIBRARIES := $(statsd_common_shared_libraries) \
    libgtest_prod

LOCAL_MODULE_CLASS := EXECUTABLES

#LOCAL_INIT_RC := statsd.rc

include $(BUILD_EXECUTABLE)


# ==============
# statsd_test
# ==============

include $(CLEAR_VARS)

LOCAL_MODULE := statsd_test
LOCAL_COMPATIBILITY_SUITE := device-tests
LOCAL_MODULE_TAGS := tests

LOCAL_AIDL_INCLUDES := $(statsd_common_aidl_includes)
LOCAL_C_INCLUDES += $(statsd_common_c_includes)

LOCAL_CFLAGS += \
    -Wall \
    -Werror \
    -Wno-missing-field-initializers \
    -Wno-unused-variable \
    -Wno-unused-function \
    -Wno-unused-parameter

LOCAL_SRC_FILES := \
    $(statsd_common_src) \
    tests/AnomalyMonitor_test.cpp \
    tests/ConditionTracker_test.cpp \
    tests/ConfigManager_test.cpp \
    tests/indexed_priority_queue_test.cpp \
    tests/LogEntryMatcher_test.cpp \
    tests/LogReader_test.cpp \
    tests/MetricsManager_test.cpp \
    tests/UidMap_test.cpp \
    tests/OringDurationTracker_test.cpp \
    tests/MaxDurationTracker_test.cpp


LOCAL_STATIC_LIBRARIES := \
    libgmock

LOCAL_SHARED_LIBRARIES := $(statsd_common_shared_libraries)

LOCAL_PROTOC_OPTIMIZE_TYPE := lite

statsd_common_src:=
statsd_common_aidl_includes:=
statsd_common_c_includes:=

include $(BUILD_NATIVE_TEST)

