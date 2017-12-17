/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "config/ConfigManager.h"
#include "storage/StorageManager.h"

#include "stats_util.h"

#include <android-base/file.h>
#include <dirent.h>
#include <stdio.h>
#include <vector>
#include "android-base/stringprintf.h"

namespace android {
namespace os {
namespace statsd {

using std::map;
using std::pair;
using std::set;
using std::string;
using std::vector;

#define STATS_SERVICE_DIR "/data/misc/stats-service"

using android::base::StringPrintf;
using std::unique_ptr;

ConfigManager::ConfigManager() {
}

ConfigManager::~ConfigManager() {
}

void ConfigManager::Startup() {
    map<ConfigKey, StatsdConfig> configsFromDisk;
    StorageManager::readConfigFromDisk(configsFromDisk);
    // TODO(b/70667694): Make the configs from disk be used. And remove the fake config,
    // and tests shouldn't call this Startup(), maybe call StartupForTest() so we don't read
    // configs from disk for tests.
    // for (const auto& pair : configsFromDisk) {
    //    UpdateConfig(pair.first, pair.second);
    //}
    // this should be called from StatsService when it receives a statsd_config
    UpdateConfig(ConfigKey(1000, "fake"), build_fake_config());
}

void ConfigManager::AddListener(const sp<ConfigListener>& listener) {
    mListeners.push_back(listener);
}

void ConfigManager::UpdateConfig(const ConfigKey& key, const StatsdConfig& config) {
    // Add to set
    mConfigs.insert(key);

    // Save to disk
    update_saved_configs(key, config);

    // Tell everyone
    for (auto& listener : mListeners) {
        listener->OnConfigUpdated(key, config);
    }
}

void ConfigManager::SetConfigReceiver(const ConfigKey& key, const string& pkg, const string& cls) {
    mConfigReceivers[key] = pair<string, string>(pkg, cls);
}

void ConfigManager::RemoveConfigReceiver(const ConfigKey& key) {
    mConfigReceivers.erase(key);
}

void ConfigManager::RemoveConfig(const ConfigKey& key) {
    auto it = mConfigs.find(key);
    if (it != mConfigs.end()) {
        // Remove from map
        mConfigs.erase(it);

        // Tell everyone
        for (auto& listener : mListeners) {
            listener->OnConfigRemoved(key);
        }
    }

    // Remove from disk. There can still be a lingering file on disk so we check
    // whether or not the config was on memory.
    remove_saved_configs(key);
}

void ConfigManager::remove_saved_configs(const ConfigKey& key) {
    string prefix = StringPrintf("%d-%s", key.GetUid(), key.GetName().c_str());
    StorageManager::deletePrefixedFiles(STATS_SERVICE_DIR, prefix.c_str());
}

void ConfigManager::RemoveConfigs(int uid) {
    vector<ConfigKey> removed;

    for (auto it = mConfigs.begin(); it != mConfigs.end();) {
        // Remove from map
        if (it->GetUid() == uid) {
            removed.push_back(*it);
            mConfigReceivers.erase(*it);
            it = mConfigs.erase(it);
        } else {
            it++;
        }
    }

    // Remove separately so if they do anything in the callback they can't mess up our iteration.
    for (auto& key : removed) {
        // Tell everyone
        for (auto& listener : mListeners) {
            listener->OnConfigRemoved(key);
        }
    }
}

void ConfigManager::RemoveAllConfigs() {
    vector<ConfigKey> removed;

    for (auto it = mConfigs.begin(); it != mConfigs.end();) {
        // Remove from map
        removed.push_back(*it);
        auto receiverIt = mConfigReceivers.find(*it);
        if (receiverIt != mConfigReceivers.end()) {
            mConfigReceivers.erase(*it);
        }
        it = mConfigs.erase(it);
    }

    // Remove separately so if they do anything in the callback they can't mess up our iteration.
    for (auto& key : removed) {
        // Tell everyone
        for (auto& listener : mListeners) {
            listener->OnConfigRemoved(key);
        }
    }
}

vector<ConfigKey> ConfigManager::GetAllConfigKeys() const {
    vector<ConfigKey> ret;
    for (auto it = mConfigs.cbegin(); it != mConfigs.cend(); ++it) {
        ret.push_back(*it);
    }
    return ret;
}

const pair<string, string> ConfigManager::GetConfigReceiver(const ConfigKey& key) const {
    auto it = mConfigReceivers.find(key);
    if (it == mConfigReceivers.end()) {
        return pair<string,string>();
    } else {
        return it->second;
    }
}

void ConfigManager::Dump(FILE* out) {
    fprintf(out, "CONFIGURATIONS (%d)\n", (int)mConfigs.size());
    fprintf(out, "     uid name\n");
    for (const auto& key : mConfigs) {
        fprintf(out, "  %6d %s\n", key.GetUid(), key.GetName().c_str());
        auto receiverIt = mConfigReceivers.find(key);
        if (receiverIt != mConfigReceivers.end()) {
            fprintf(out, "    -> received by %s, %s\n", receiverIt->second.first.c_str(),
                    receiverIt->second.second.c_str());
        }
    }
}

void ConfigManager::update_saved_configs(const ConfigKey& key, const StatsdConfig& config) {
    mkdir(STATS_SERVICE_DIR, S_IRWXU);

    // If there is a pre-existing config with same key we should first delete it.
    remove_saved_configs(key);

    // Then we save the latest config.
    string file_name = StringPrintf("%s/%d-%s-%ld", STATS_SERVICE_DIR, key.GetUid(),
                                    key.GetName().c_str(), time(nullptr));
    const int numBytes = config.ByteSize();
    vector<uint8_t> buffer(numBytes);
    config.SerializeToArray(&buffer[0], numBytes);
    StorageManager::writeFile(file_name.c_str(), &buffer[0], numBytes);
}

StatsdConfig build_fake_config() {
    // HACK: Hard code a test metric for counting screen on events...
    StatsdConfig config;
    config.set_name("CONFIG_12345");

    int WAKE_LOCK_TAG_ID = 1111;  // put a fake id here to make testing easier.
    int WAKE_LOCK_UID_KEY_ID = 1;
    int WAKE_LOCK_NAME_KEY = 3;
    int WAKE_LOCK_STATE_KEY = 4;
    int WAKE_LOCK_ACQUIRE_VALUE = 1;
    int WAKE_LOCK_RELEASE_VALUE = 0;

    int APP_USAGE_ID = 12345;
    int APP_USAGE_UID_KEY_ID = 1;
    int APP_USAGE_STATE_KEY = 2;
    int APP_USAGE_FOREGROUND = 1;
    int APP_USAGE_BACKGROUND = 0;

    int SCREEN_EVENT_TAG_ID = 29;
    int SCREEN_EVENT_STATE_KEY = 1;
    int SCREEN_EVENT_ON_VALUE = 2;
    int SCREEN_EVENT_OFF_VALUE = 1;

    int UID_PROCESS_STATE_TAG_ID = 27;
    int UID_PROCESS_STATE_UID_KEY = 1;

    int KERNEL_WAKELOCK_TAG_ID = 1004;
    int KERNEL_WAKELOCK_COUNT_KEY = 2;
    int KERNEL_WAKELOCK_NAME_KEY = 1;

    int DEVICE_TEMPERATURE_TAG_ID = 33;
    int DEVICE_TEMPERATURE_KEY = 1;

    // Count Screen ON events.
    CountMetric* metric = config.add_count_metric();
    metric->set_name("METRIC_1");
    metric->set_what("SCREEN_TURNED_ON");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);

    // Anomaly threshold for screen-on count.
    // TODO(b/70627390): Uncomment once the bug is fixed.
    /*Alert* alert = config.add_alert();
    alert->set_name("ALERT_1");
    alert->set_metric_name("METRIC_1");
    alert->set_number_of_buckets(6);
    alert->set_trigger_if_sum_gt(10);
    alert->set_refractory_period_secs(30);
    Alert::IncidentdDetails* details = alert->mutable_incidentd_details();
    details->add_section(12);
    details->add_section(13);*/

    // Count process state changes, slice by uid.
    metric = config.add_count_metric();
    metric->set_name("METRIC_2");
    metric->set_what("PROCESS_STATE_CHANGE");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    KeyMatcher* keyMatcher = metric->add_dimension();
    keyMatcher->set_key(UID_PROCESS_STATE_UID_KEY);

    // Anomaly threshold for background count.
    // TODO(b/70627390): Uncomment once the bug is fixed.
    /*
    alert = config.add_alert();
    alert->set_name("ALERT_2");
    alert->set_metric_name("METRIC_2");
    alert->set_number_of_buckets(4);
    alert->set_trigger_if_sum_gt(30);
    alert->set_refractory_period_secs(20);
    details = alert->mutable_incidentd_details();
    details->add_section(14);
    details->add_section(15);*/

    // Count process state changes, slice by uid, while SCREEN_IS_OFF
    metric = config.add_count_metric();
    metric->set_name("METRIC_3");
    metric->set_what("PROCESS_STATE_CHANGE");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    keyMatcher = metric->add_dimension();
    keyMatcher->set_key(UID_PROCESS_STATE_UID_KEY);
    metric->set_condition("SCREEN_IS_OFF");

    // Count wake lock, slice by uid, while SCREEN_IS_ON and app in background
    metric = config.add_count_metric();
    metric->set_name("METRIC_4");
    metric->set_what("APP_GET_WL");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    keyMatcher = metric->add_dimension();
    keyMatcher->set_key(WAKE_LOCK_UID_KEY_ID);
    metric->set_condition("APP_IS_BACKGROUND_AND_SCREEN_ON");
    MetricConditionLink* link = metric->add_links();
    link->set_condition("APP_IS_BACKGROUND");
    link->add_key_in_what()->set_key(WAKE_LOCK_UID_KEY_ID);
    link->add_key_in_condition()->set_key(APP_USAGE_UID_KEY_ID);

    // Duration of an app holding any wl, while screen on and app in background, slice by uid
    DurationMetric* durationMetric = config.add_duration_metric();
    durationMetric->set_name("METRIC_5");
    durationMetric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    durationMetric->set_aggregation_type(DurationMetric_AggregationType_SUM);
    keyMatcher = durationMetric->add_dimension();
    keyMatcher->set_key(WAKE_LOCK_UID_KEY_ID);
    durationMetric->set_what("WL_HELD_PER_APP_PER_NAME");
    durationMetric->set_condition("APP_IS_BACKGROUND_AND_SCREEN_ON");
    link = durationMetric->add_links();
    link->set_condition("APP_IS_BACKGROUND");
    link->add_key_in_what()->set_key(WAKE_LOCK_UID_KEY_ID);
    link->add_key_in_condition()->set_key(APP_USAGE_UID_KEY_ID);

    // max Duration of an app holding any wl, while screen on and app in background, slice by uid
    durationMetric = config.add_duration_metric();
    durationMetric->set_name("METRIC_6");
    durationMetric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    durationMetric->set_aggregation_type(DurationMetric_AggregationType_MAX_SPARSE);
    keyMatcher = durationMetric->add_dimension();
    keyMatcher->set_key(WAKE_LOCK_UID_KEY_ID);
    durationMetric->set_what("WL_HELD_PER_APP_PER_NAME");
    durationMetric->set_condition("APP_IS_BACKGROUND_AND_SCREEN_ON");
    link = durationMetric->add_links();
    link->set_condition("APP_IS_BACKGROUND");
    link->add_key_in_what()->set_key(WAKE_LOCK_UID_KEY_ID);
    link->add_key_in_condition()->set_key(APP_USAGE_UID_KEY_ID);

    // Duration of an app holding any wl, while screen on and app in background
    durationMetric = config.add_duration_metric();
    durationMetric->set_name("METRIC_7");
    durationMetric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    durationMetric->set_aggregation_type(DurationMetric_AggregationType_MAX_SPARSE);
    durationMetric->set_what("WL_HELD_PER_APP_PER_NAME");
    durationMetric->set_condition("APP_IS_BACKGROUND_AND_SCREEN_ON");
    link = durationMetric->add_links();
    link->set_condition("APP_IS_BACKGROUND");
    link->add_key_in_what()->set_key(WAKE_LOCK_UID_KEY_ID);
    link->add_key_in_condition()->set_key(APP_USAGE_UID_KEY_ID);

    // Duration of screen on time.
    durationMetric = config.add_duration_metric();
    durationMetric->set_name("METRIC_8");
    durationMetric->mutable_bucket()->set_bucket_size_millis(10 * 1000L);
    durationMetric->set_aggregation_type(DurationMetric_AggregationType_SUM);
    durationMetric->set_what("SCREEN_IS_ON");

    // Anomaly threshold for background count.
    // TODO(b/70627390): Uncomment once the bug is fixed.
    /*
    alert = config.add_alert();
    alert->set_name("ALERT_8");
    alert->set_metric_name("METRIC_8");
    alert->set_number_of_buckets(4);
    alert->set_trigger_if_sum_gt(2000000000); // 2 seconds
    alert->set_refractory_period_secs(120);
    details = alert->mutable_incidentd_details();
    details->add_section(-1);*/

    // Value metric to count KERNEL_WAKELOCK when screen turned on
    ValueMetric* valueMetric = config.add_value_metric();
    valueMetric->set_name("METRIC_6");
    valueMetric->set_what("KERNEL_WAKELOCK");
    valueMetric->set_value_field(KERNEL_WAKELOCK_COUNT_KEY);
    valueMetric->set_condition("SCREEN_IS_ON");
    keyMatcher = valueMetric->add_dimension();
    keyMatcher->set_key(KERNEL_WAKELOCK_NAME_KEY);
    // This is for testing easier. We should never set bucket size this small.
    valueMetric->mutable_bucket()->set_bucket_size_millis(60 * 1000L);

    // Add an EventMetric to log process state change events.
    EventMetric* eventMetric = config.add_event_metric();
    eventMetric->set_name("METRIC_9");
    eventMetric->set_what("SCREEN_TURNED_ON");

    // Add an GaugeMetric.
    GaugeMetric* gaugeMetric = config.add_gauge_metric();
    gaugeMetric->set_name("METRIC_10");
    gaugeMetric->set_what("DEVICE_TEMPERATURE");
    gaugeMetric->set_gauge_field(DEVICE_TEMPERATURE_KEY);
    gaugeMetric->mutable_bucket()->set_bucket_size_millis(60 * 1000L);

    // Event matchers............
    AtomMatcher* temperatureAtomMatcher = config.add_atom_matcher();
    temperatureAtomMatcher->set_name("DEVICE_TEMPERATURE");
    temperatureAtomMatcher->mutable_simple_atom_matcher()->set_tag(
        DEVICE_TEMPERATURE_TAG_ID);

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("SCREEN_TURNED_ON");
    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_tag(SCREEN_EVENT_TAG_ID);
    KeyValueMatcher* keyValueMatcher = simpleAtomMatcher->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(SCREEN_EVENT_STATE_KEY);
    keyValueMatcher->set_eq_int(SCREEN_EVENT_ON_VALUE);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("SCREEN_TURNED_OFF");
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_tag(SCREEN_EVENT_TAG_ID);
    keyValueMatcher = simpleAtomMatcher->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(SCREEN_EVENT_STATE_KEY);
    keyValueMatcher->set_eq_int(SCREEN_EVENT_OFF_VALUE);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("PROCESS_STATE_CHANGE");
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_tag(UID_PROCESS_STATE_TAG_ID);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("APP_GOES_BACKGROUND");
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_tag(APP_USAGE_ID);
    keyValueMatcher = simpleAtomMatcher->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(APP_USAGE_STATE_KEY);
    keyValueMatcher->set_eq_int(APP_USAGE_BACKGROUND);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("APP_GOES_FOREGROUND");
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_tag(APP_USAGE_ID);
    keyValueMatcher = simpleAtomMatcher->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(APP_USAGE_STATE_KEY);
    keyValueMatcher->set_eq_int(APP_USAGE_FOREGROUND);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("APP_GET_WL");
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_tag(WAKE_LOCK_TAG_ID);
    keyValueMatcher = simpleAtomMatcher->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(WAKE_LOCK_STATE_KEY);
    keyValueMatcher->set_eq_int(WAKE_LOCK_ACQUIRE_VALUE);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("APP_RELEASE_WL");
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_tag(WAKE_LOCK_TAG_ID);
    keyValueMatcher = simpleAtomMatcher->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(WAKE_LOCK_STATE_KEY);
    keyValueMatcher->set_eq_int(WAKE_LOCK_RELEASE_VALUE);

    // pulled events
    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("KERNEL_WAKELOCK");
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_tag(KERNEL_WAKELOCK_TAG_ID);

    // Predicates.............
    Predicate* predicate = config.add_predicate();
    predicate->set_name("SCREEN_IS_ON");
    SimplePredicate* simplePredicate = predicate->mutable_simple_predicate();
    simplePredicate->set_start("SCREEN_TURNED_ON");
    simplePredicate->set_stop("SCREEN_TURNED_OFF");
    simplePredicate->set_count_nesting(false);

    predicate = config.add_predicate();
    predicate->set_name("SCREEN_IS_OFF");
    simplePredicate = predicate->mutable_simple_predicate();
    simplePredicate->set_start("SCREEN_TURNED_OFF");
    simplePredicate->set_stop("SCREEN_TURNED_ON");
    simplePredicate->set_count_nesting(false);

    predicate = config.add_predicate();
    predicate->set_name("APP_IS_BACKGROUND");
    simplePredicate = predicate->mutable_simple_predicate();
    simplePredicate->set_start("APP_GOES_BACKGROUND");
    simplePredicate->set_stop("APP_GOES_FOREGROUND");
    KeyMatcher* predicate_dimension1 = simplePredicate->add_dimension();
    predicate_dimension1->set_key(APP_USAGE_UID_KEY_ID);
    simplePredicate->set_count_nesting(false);

    predicate = config.add_predicate();
    predicate->set_name("APP_IS_BACKGROUND_AND_SCREEN_ON");
    Predicate_Combination* combination_predicate = predicate->mutable_combination();
    combination_predicate->set_operation(LogicalOperation::AND);
    combination_predicate->add_predicate("APP_IS_BACKGROUND");
    combination_predicate->add_predicate("SCREEN_IS_ON");

    predicate = config.add_predicate();
    predicate->set_name("WL_HELD_PER_APP_PER_NAME");
    simplePredicate = predicate->mutable_simple_predicate();
    simplePredicate->set_start("APP_GET_WL");
    simplePredicate->set_stop("APP_RELEASE_WL");
    KeyMatcher* predicate_dimension = simplePredicate->add_dimension();
    predicate_dimension->set_key(WAKE_LOCK_UID_KEY_ID);
    predicate_dimension = simplePredicate->add_dimension();
    predicate_dimension->set_key(WAKE_LOCK_NAME_KEY);
    simplePredicate->set_count_nesting(true);

    predicate = config.add_predicate();
    predicate->set_name("WL_HELD_PER_APP");
    simplePredicate = predicate->mutable_simple_predicate();
    simplePredicate->set_start("APP_GET_WL");
    simplePredicate->set_stop("APP_RELEASE_WL");
    simplePredicate->set_initial_value(SimplePredicate_InitialValue_FALSE);
    predicate_dimension = simplePredicate->add_dimension();
    predicate_dimension->set_key(WAKE_LOCK_UID_KEY_ID);
    simplePredicate->set_count_nesting(true);

    return config;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
