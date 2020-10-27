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

#pragma once

#include <set>
#include <unordered_map>
#include <vector>

#include "anomaly/AlarmTracker.h"
#include "condition/ConditionTracker.h"
#include "external/StatsPullerManager.h"
#include "matchers/AtomMatchingTracker.h"
#include "metrics/MetricProducer.h"

namespace android {
namespace os {
namespace statsd {

// Helper functions for creating, validating, and updating config components from StatsdConfig.
// Should only be called from metrics_manager_util and config_update_utils.

// Create a AtomMatchingTracker.
// input:
// [logMatcher]: the input AtomMatcher from the StatsdConfig
// [index]: the index of the matcher
// output:
// new AtomMatchingTracker, or null if the tracker is unable to be created
sp<AtomMatchingTracker> createAtomMatchingTracker(const AtomMatcher& logMatcher, const int index,
                                                  const sp<UidMap>& uidMap);

// Create a ConditionTracker.
// input:
// [predicate]: the input Predicate from the StatsdConfig
// [index]: the index of the condition tracker
// [atomMatchingTrackerMap]: map of atom matcher id to its index in allAtomMatchingTrackers
// output:
// new ConditionTracker, or null if the tracker is unable to be created
sp<ConditionTracker> createConditionTracker(
        const ConfigKey& key, const Predicate& predicate, const int index,
        const unordered_map<int64_t, int>& atomMatchingTrackerMap);

// Get the hash of a metric, combining the activation if the metric has one.
bool getMetricProtoHash(const StatsdConfig& config, const google::protobuf::MessageLite& metric,
                        const int64_t id,
                        const std::unordered_map<int64_t, int>& metricToActivationMap,
                        uint64_t& metricHash);

// 1. Validates matcher existence
// 2. Enforces matchers with dimensions and those used for trigger_event are about one atom
// 3. Gets matcher index and updates tracker to metric map
bool handleMetricWithAtomMatchingTrackers(
        const int64_t matcherId, const int metricIndex, const bool enforceOneAtom,
        const std::vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const std::unordered_map<int64_t, int>& atomMatchingTrackerMap,
        std::unordered_map<int, std::vector<int>>& trackerToMetricMap, int& logTrackerIndex);

// 1. Validates condition existence, including those in links
// 2. Gets condition index and updates condition to metric map
bool handleMetricWithConditions(
        const int64_t condition, const int metricIndex,
        const std::unordered_map<int64_t, int>& conditionTrackerMap,
        const ::google::protobuf::RepeatedPtrField<::android::os::statsd::MetricConditionLink>&
                links,
        const std::vector<sp<ConditionTracker>>& allConditionTrackers, int& conditionIndex,
        std::unordered_map<int, std::vector<int>>& conditionToMetricMap);

// Validates a metricActivation and populates state.
// Fills the new event activation/deactivation maps, preserving the existing activations.
// Returns false if there are errors.
bool handleMetricActivationOnConfigUpdate(
        const StatsdConfig& config, const int64_t metricId, const int metricIndex,
        const std::unordered_map<int64_t, int>& metricToActivationMap,
        const std::unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
        const std::unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
        const std::unordered_map<int, shared_ptr<Activation>>& oldEventActivationMap,
        std::unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
        std::unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
        std::vector<int>& metricsWithActivation,
        std::unordered_map<int, shared_ptr<Activation>>& newEventActivationMap,
        std::unordered_map<int, std::vector<shared_ptr<Activation>>>& newEventDeactivationMap);

// Creates a CountMetricProducer and updates the vectors/maps used by MetricsManager with
// the appropriate indices. Returns an sp to the producer, or nullopt if there was an error.
optional<sp<MetricProducer>> createCountMetricProducerAndUpdateMetadata(
        const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseNs,
        const int64_t currentTimeNs, const CountMetric& metric, const int metricIndex,
        const std::vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const std::unordered_map<int64_t, int>& atomMatchingTrackerMap,
        std::vector<sp<ConditionTracker>>& allConditionTrackers,
        const std::unordered_map<int64_t, int>& conditionTrackerMap,
        const std::vector<ConditionState>& initialConditionCache, const sp<ConditionWizard>& wizard,
        const std::unordered_map<int64_t, int>& stateAtomIdMap,
        const std::unordered_map<int64_t, std::unordered_map<int, int64_t>>& allStateGroupMaps,
        const std::unordered_map<int64_t, int>& metricToActivationMap,
        std::unordered_map<int, std::vector<int>>& trackerToMetricMap,
        std::unordered_map<int, std::vector<int>>& conditionToMetricMap,
        std::unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
        std::unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
        std::vector<int>& metricsWithActivation);

// Creates a DurationMetricProducer and updates the vectors/maps used by MetricsManager with
// the appropriate indices. Returns an sp to the producer, or nullopt if there was an error.
optional<sp<MetricProducer>> createDurationMetricProducerAndUpdateMetadata(
        const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseNs,
        const int64_t currentTimeNs, const DurationMetric& metric, const int metricIndex,
        const std::vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const std::unordered_map<int64_t, int>& atomMatchingTrackerMap,
        std::vector<sp<ConditionTracker>>& allConditionTrackers,
        const std::unordered_map<int64_t, int>& conditionTrackerMap,
        const std::vector<ConditionState>& initialConditionCache, const sp<ConditionWizard>& wizard,
        const std::unordered_map<int64_t, int>& stateAtomIdMap,
        const std::unordered_map<int64_t, std::unordered_map<int, int64_t>>& allStateGroupMaps,
        const std::unordered_map<int64_t, int>& metricToActivationMap,
        std::unordered_map<int, std::vector<int>>& trackerToMetricMap,
        std::unordered_map<int, std::vector<int>>& conditionToMetricMap,
        std::unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
        std::unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
        std::vector<int>& metricsWithActivation);

// Creates an EventMetricProducer and updates the vectors/maps used by MetricsManager with
// the appropriate indices. Returns an sp to the producer, or nullopt if there was an error.
optional<sp<MetricProducer>> createEventMetricProducerAndUpdateMetadata(
        const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseNs,
        const EventMetric& metric, const int metricIndex,
        const std::vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const std::unordered_map<int64_t, int>& atomMatchingTrackerMap,
        std::vector<sp<ConditionTracker>>& allConditionTrackers,
        const std::unordered_map<int64_t, int>& conditionTrackerMap,
        const std::vector<ConditionState>& initialConditionCache, const sp<ConditionWizard>& wizard,
        const std::unordered_map<int64_t, int>& metricToActivationMap,
        std::unordered_map<int, std::vector<int>>& trackerToMetricMap,
        std::unordered_map<int, std::vector<int>>& conditionToMetricMap,
        std::unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
        std::unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
        std::vector<int>& metricsWithActivation);

// Creates a CountMetricProducer and updates the vectors/maps used by MetricsManager with
// the appropriate indices. Returns an sp to the producer, or nullopt if there was an error.
optional<sp<MetricProducer>> createValueMetricProducerAndUpdateMetadata(
        const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseNs,
        const int64_t currentTimeNs, const sp<StatsPullerManager>& pullerManager,
        const ValueMetric& metric, const int metricIndex,
        const std::vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const std::unordered_map<int64_t, int>& atomMatchingTrackerMap,
        std::vector<sp<ConditionTracker>>& allConditionTrackers,
        const std::unordered_map<int64_t, int>& conditionTrackerMap,
        const std::vector<ConditionState>& initialConditionCache, const sp<ConditionWizard>& wizard,
        const sp<EventMatcherWizard>& matcherWizard,
        const std::unordered_map<int64_t, int>& stateAtomIdMap,
        const std::unordered_map<int64_t, std::unordered_map<int, int64_t>>& allStateGroupMaps,
        const std::unordered_map<int64_t, int>& metricToActivationMap,
        std::unordered_map<int, std::vector<int>>& trackerToMetricMap,
        std::unordered_map<int, std::vector<int>>& conditionToMetricMap,
        std::unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
        std::unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
        std::vector<int>& metricsWithActivation);

// Creates a GaugeMetricProducer and updates the vectors/maps used by MetricsManager with
// the appropriate indices. Returns an sp to the producer, or nullopt if there was an error.
optional<sp<MetricProducer>> createGaugeMetricProducerAndUpdateMetadata(
        const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseNs,
        const int64_t currentTimeNs, const sp<StatsPullerManager>& pullerManager,
        const GaugeMetric& metric, const int metricIndex,
        const std::vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const std::unordered_map<int64_t, int>& atomMatchingTrackerMap,
        std::vector<sp<ConditionTracker>>& allConditionTrackers,
        const std::unordered_map<int64_t, int>& conditionTrackerMap,
        const std::vector<ConditionState>& initialConditionCache, const sp<ConditionWizard>& wizard,
        const sp<EventMatcherWizard>& matcherWizard,
        const std::unordered_map<int64_t, int>& metricToActivationMap,
        std::unordered_map<int, std::vector<int>>& trackerToMetricMap,
        std::unordered_map<int, std::vector<int>>& conditionToMetricMap,
        std::unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
        std::unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
        std::vector<int>& metricsWithActivation);

// Helper functions for MetricsManager to initialize from StatsdConfig.
// *Note*: only initStatsdConfig() should be called from outside.
// All other functions are intermediate
// steps, created to make unit tests easier. And most of the parameters in these
// functions are temporary objects in the initialization phase.

// Initialize the AtomMatchingTrackers.
// input:
// [key]: the config key that this config belongs to
// [config]: the input StatsdConfig
// output:
// [atomMatchingTrackerMap]: this map should contain matcher name to index mapping
// [allAtomMatchingTrackers]: should store the sp to all the AtomMatchingTracker
// [allTagIds]: contains the set of all interesting tag ids to this config.
bool initAtomMatchingTrackers(const StatsdConfig& config, const sp<UidMap>& uidMap,
                              std::unordered_map<int64_t, int>& atomMatchingTrackerMap,
                              std::vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
                              std::set<int>& allTagIds);

// Initialize ConditionTrackers
// input:
// [key]: the config key that this config belongs to
// [config]: the input config
// [atomMatchingTrackerMap]: AtomMatchingTracker name to index mapping from previous step.
// output:
// [conditionTrackerMap]: this map should contain condition name to index mapping
// [allConditionTrackers]: stores the sp to all the ConditionTrackers
// [trackerToConditionMap]: contain the mapping from index of
//                        log tracker to condition trackers that use the log tracker
// [initialConditionCache]: stores the initial conditions for each ConditionTracker
bool initConditions(const ConfigKey& key, const StatsdConfig& config,
                    const std::unordered_map<int64_t, int>& atomMatchingTrackerMap,
                    std::unordered_map<int64_t, int>& conditionTrackerMap,
                    std::vector<sp<ConditionTracker>>& allConditionTrackers,
                    std::unordered_map<int, std::vector<int>>& trackerToConditionMap,
                    std::vector<ConditionState>& initialConditionCache);

// Initialize State maps using State protos in the config. These maps will
// eventually be passed to MetricProducers to initialize their state info.
// input:
// [config]: the input config
// output:
// [stateAtomIdMap]: this map should contain the mapping from state ids to atom ids
// [allStateGroupMaps]: this map should contain the mapping from states ids and state
//                      values to state group ids for all states
// [stateProtoHashes]: contains a map of state id to the hash of the State proto from the config
bool initStates(const StatsdConfig& config, unordered_map<int64_t, int>& stateAtomIdMap,
                unordered_map<int64_t, unordered_map<int, int64_t>>& allStateGroupMaps,
                std::map<int64_t, uint64_t>& stateProtoHashes);

// Initialize MetricProducers.
// input:
// [key]: the config key that this config belongs to
// [config]: the input config
// [timeBaseSec]: start time base for all metrics
// [atomMatchingTrackerMap]: AtomMatchingTracker name to index mapping from previous step.
// [conditionTrackerMap]: condition name to index mapping
// [stateAtomIdMap]: contains the mapping from state ids to atom ids
// [allStateGroupMaps]: contains the mapping from atom ids and state values to
//                      state group ids for all states
// output:
// [allMetricProducers]: contains the list of sp to the MetricProducers created.
// [conditionToMetricMap]: contains the mapping from condition tracker index to
//                          the list of MetricProducer index
// [trackerToMetricMap]: contains the mapping from log tracker to MetricProducer index.
bool initMetrics(
        const ConfigKey& key, const StatsdConfig& config, const int64_t timeBaseTimeNs,
        const int64_t currentTimeNs, const sp<StatsPullerManager>& pullerManager,
        const std::unordered_map<int64_t, int>& atomMatchingTrackerMap,
        const std::unordered_map<int64_t, int>& conditionTrackerMap,
        const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const unordered_map<int64_t, int>& stateAtomIdMap,
        const unordered_map<int64_t, unordered_map<int, int64_t>>& allStateGroupMaps,
        vector<sp<ConditionTracker>>& allConditionTrackers,
        const std::vector<ConditionState>& initialConditionCache,
        std::vector<sp<MetricProducer>>& allMetricProducers,
        std::unordered_map<int, std::vector<int>>& conditionToMetricMap,
        std::unordered_map<int, std::vector<int>>& trackerToMetricMap,
        std::set<int64_t>& noReportMetricIds,
        std::unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
        std::unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
        std::vector<int>& metricsWithActivation);

// Initialize MetricsManager from StatsdConfig.
// Parameters are the members of MetricsManager. See MetricsManager for declaration.
bool initStatsdConfig(const ConfigKey& key, const StatsdConfig& config, const sp<UidMap>& uidMap,
                      const sp<StatsPullerManager>& pullerManager,
                      const sp<AlarmMonitor>& anomalyAlarmMonitor,
                      const sp<AlarmMonitor>& periodicAlarmMonitor, const int64_t timeBaseNs,
                      const int64_t currentTimeNs, std::set<int>& allTagIds,
                      std::vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
                      std::unordered_map<int64_t, int>& atomMatchingTrackerMap,
                      std::vector<sp<ConditionTracker>>& allConditionTrackers,
                      std::unordered_map<int64_t, int>& conditionTrackerMap,
                      std::vector<sp<MetricProducer>>& allMetricProducers,
                      std::unordered_map<int64_t, int>& metricProducerMap,
                      vector<sp<AnomalyTracker>>& allAnomalyTrackers,
                      vector<sp<AlarmTracker>>& allPeriodicAlarmTrackers,
                      std::unordered_map<int, std::vector<int>>& conditionToMetricMap,
                      std::unordered_map<int, std::vector<int>>& trackerToMetricMap,
                      std::unordered_map<int, std::vector<int>>& trackerToConditionMap,
                      std::unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
                      std::unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
                      std::unordered_map<int64_t, int>& alertTrackerMap,
                      std::vector<int>& metricsWithActivation,
                      std::map<int64_t, uint64_t>& stateProtoHashes,
                      std::set<int64_t>& noReportMetricIds);

}  // namespace statsd
}  // namespace os
}  // namespace android
