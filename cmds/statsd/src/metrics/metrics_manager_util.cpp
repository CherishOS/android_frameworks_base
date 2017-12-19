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

#define DEBUG true  // STOPSHIP if true
#include "Log.h"

#include "../condition/CombinationConditionTracker.h"
#include "../condition/SimpleConditionTracker.h"
#include "../external/StatsPullerManager.h"
#include "../matchers/CombinationLogMatchingTracker.h"
#include "../matchers/SimpleLogMatchingTracker.h"
#include "CountMetricProducer.h"
#include "DurationMetricProducer.h"
#include "EventMetricProducer.h"
#include "GaugeMetricProducer.h"
#include "ValueMetricProducer.h"
#include "stats_util.h"

using std::set;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

bool handleMetricWithLogTrackers(const string what, const int metricIndex,
                                 const bool usedForDimension,
                                 const vector<sp<LogMatchingTracker>>& allAtomMatchers,
                                 const unordered_map<string, int>& logTrackerMap,
                                 unordered_map<int, std::vector<int>>& trackerToMetricMap,
                                 int& logTrackerIndex) {
    auto logTrackerIt = logTrackerMap.find(what);
    if (logTrackerIt == logTrackerMap.end()) {
        ALOGW("cannot find the AtomMatcher \"%s\" in config", what.c_str());
        return false;
    }
    if (usedForDimension && allAtomMatchers[logTrackerIt->second]->getTagIds().size() > 1) {
        ALOGE("AtomMatcher \"%s\" has more than one tag ids. When a metric has dimension, "
              "the \"what\" can only about one atom type.",
              what.c_str());
        return false;
    }
    logTrackerIndex = logTrackerIt->second;
    auto& metric_list = trackerToMetricMap[logTrackerIndex];
    metric_list.push_back(metricIndex);
    return true;
}

bool handleMetricWithConditions(
        const string condition, const int metricIndex,
        const unordered_map<string, int>& conditionTrackerMap,
        const ::google::protobuf::RepeatedPtrField<::android::os::statsd::MetricConditionLink>&
                links,
        vector<sp<ConditionTracker>>& allConditionTrackers, int& conditionIndex,
        unordered_map<int, std::vector<int>>& conditionToMetricMap) {
    auto condition_it = conditionTrackerMap.find(condition);
    if (condition_it == conditionTrackerMap.end()) {
        ALOGW("cannot find Predicate \"%s\" in the config", condition.c_str());
        return false;
    }

    for (const auto& link : links) {
        auto it = conditionTrackerMap.find(link.condition());
        if (it == conditionTrackerMap.end()) {
            ALOGW("cannot find Predicate \"%s\" in the config", link.condition().c_str());
            return false;
        }
        allConditionTrackers[condition_it->second]->setSliced(true);
        allConditionTrackers[it->second]->setSliced(true);
        // TODO: We need to verify the link is valid.
    }
    conditionIndex = condition_it->second;

    // will create new vector if not exist before.
    auto& metricList = conditionToMetricMap[condition_it->second];
    metricList.push_back(metricIndex);
    return true;
}

bool initLogTrackers(const StatsdConfig& config, unordered_map<string, int>& logTrackerMap,
                     vector<sp<LogMatchingTracker>>& allAtomMatchers, set<int>& allTagIds) {
    vector<AtomMatcher> matcherConfigs;
    const int atomMatcherCount = config.atom_matcher_size();
    matcherConfigs.reserve(atomMatcherCount);
    allAtomMatchers.reserve(atomMatcherCount);

    for (int i = 0; i < atomMatcherCount; i++) {
        const AtomMatcher& logMatcher = config.atom_matcher(i);

        int index = allAtomMatchers.size();
        switch (logMatcher.contents_case()) {
            case AtomMatcher::ContentsCase::kSimpleAtomMatcher:
                allAtomMatchers.push_back(new SimpleLogMatchingTracker(
                        logMatcher.name(), index, logMatcher.simple_atom_matcher()));
                break;
            case AtomMatcher::ContentsCase::kCombination:
                allAtomMatchers.push_back(
                        new CombinationLogMatchingTracker(logMatcher.name(), index));
                break;
            default:
                ALOGE("Matcher \"%s\" malformed", logMatcher.name().c_str());
                return false;
                // continue;
        }
        if (logTrackerMap.find(logMatcher.name()) != logTrackerMap.end()) {
            ALOGE("Duplicate AtomMatcher found!");
            return false;
        }
        logTrackerMap[logMatcher.name()] = index;
        matcherConfigs.push_back(logMatcher);
    }

    vector<bool> stackTracker2(allAtomMatchers.size(), false);
    for (auto& matcher : allAtomMatchers) {
        if (!matcher->init(matcherConfigs, allAtomMatchers, logTrackerMap, stackTracker2)) {
            return false;
        }
        // Collect all the tag ids that are interesting. TagIds exist in leaf nodes only.
        const set<int>& tagIds = matcher->getTagIds();
        allTagIds.insert(tagIds.begin(), tagIds.end());
    }
    return true;
}

bool initConditions(const ConfigKey& key, const StatsdConfig& config,
                    const unordered_map<string, int>& logTrackerMap,
                    unordered_map<string, int>& conditionTrackerMap,
                    vector<sp<ConditionTracker>>& allConditionTrackers,
                    unordered_map<int, std::vector<int>>& trackerToConditionMap) {
    vector<Predicate> conditionConfigs;
    const int conditionTrackerCount = config.predicate_size();
    conditionConfigs.reserve(conditionTrackerCount);
    allConditionTrackers.reserve(conditionTrackerCount);

    for (int i = 0; i < conditionTrackerCount; i++) {
        const Predicate& condition = config.predicate(i);
        int index = allConditionTrackers.size();
        switch (condition.contents_case()) {
            case Predicate::ContentsCase::kSimplePredicate: {
                allConditionTrackers.push_back(new SimpleConditionTracker(
                        key, condition.name(), index, condition.simple_predicate(), logTrackerMap));
                break;
            }
            case Predicate::ContentsCase::kCombination: {
                allConditionTrackers.push_back(
                        new CombinationConditionTracker(condition.name(), index));
                break;
            }
            default:
                ALOGE("Predicate \"%s\" malformed", condition.name().c_str());
                return false;
        }
        if (conditionTrackerMap.find(condition.name()) != conditionTrackerMap.end()) {
            ALOGE("Duplicate Predicate found!");
            return false;
        }
        conditionTrackerMap[condition.name()] = index;
        conditionConfigs.push_back(condition);
    }

    vector<bool> stackTracker(allConditionTrackers.size(), false);
    for (size_t i = 0; i < allConditionTrackers.size(); i++) {
        auto& conditionTracker = allConditionTrackers[i];
        if (!conditionTracker->init(conditionConfigs, allConditionTrackers, conditionTrackerMap,
                                    stackTracker)) {
            return false;
        }
        for (const int trackerIndex : conditionTracker->getLogTrackerIndex()) {
            auto& conditionList = trackerToConditionMap[trackerIndex];
            conditionList.push_back(i);
        }
    }
    return true;
}

bool initMetrics(const ConfigKey& key, const StatsdConfig& config, const long timeBaseSec,
                 const unordered_map<string, int>& logTrackerMap,
                 const unordered_map<string, int>& conditionTrackerMap,
                 const vector<sp<LogMatchingTracker>>& allAtomMatchers,
                 vector<sp<ConditionTracker>>& allConditionTrackers,
                 vector<sp<MetricProducer>>& allMetricProducers,
                 unordered_map<int, std::vector<int>>& conditionToMetricMap,
                 unordered_map<int, std::vector<int>>& trackerToMetricMap,
                 unordered_map<string, int>& metricMap) {
    sp<ConditionWizard> wizard = new ConditionWizard(allConditionTrackers);
    const int allMetricsCount = config.count_metric_size() + config.duration_metric_size() +
                                config.event_metric_size() + config.value_metric_size();
    allMetricProducers.reserve(allMetricsCount);
    StatsPullerManager statsPullerManager;
    // Align all buckets to same instant in MIN_BUCKET_SIZE_SEC, so that avoid alarm
    // clock will not grow very aggressive. New metrics will be delayed up to
    // MIN_BUCKET_SIZE_SEC before starting.
    long currentTimeSec = time(nullptr);
    uint64_t startTimeNs = (currentTimeSec - kMinBucketSizeSec -
                            (currentTimeSec - timeBaseSec) % kMinBucketSizeSec) *
                           NS_PER_SEC;

    // Build MetricProducers for each metric defined in config.
    // build CountMetricProducer
    for (int i = 0; i < config.count_metric_size(); i++) {
        const CountMetric& metric = config.count_metric(i);
        if (!metric.has_what()) {
            ALOGW("cannot find \"what\" in CountMetric \"%s\"", metric.name().c_str());
            return false;
        }

        int metricIndex = allMetricProducers.size();
        metricMap.insert({metric.name(), metricIndex});
        int trackerIndex;
        if (!handleMetricWithLogTrackers(metric.what(), metricIndex, metric.dimension_size() > 0,
                                         allAtomMatchers, logTrackerMap, trackerToMetricMap,
                                         trackerIndex)) {
            return false;
        }

        int conditionIndex = -1;
        if (metric.has_condition()) {
            bool good = handleMetricWithConditions(
                    metric.condition(), metricIndex, conditionTrackerMap, metric.links(),
                    allConditionTrackers, conditionIndex, conditionToMetricMap);
            if (!good) {
                return false;
            }
        } else {
            if (metric.links_size() > 0) {
                ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
                return false;
            }
        }

        sp<MetricProducer> countProducer =
                new CountMetricProducer(key, metric, conditionIndex, wizard, startTimeNs);
        allMetricProducers.push_back(countProducer);
    }

    // build DurationMetricProducer
    for (int i = 0; i < config.duration_metric_size(); i++) {
        int metricIndex = allMetricProducers.size();
        const DurationMetric& metric = config.duration_metric(i);
        metricMap.insert({metric.name(), metricIndex});

        auto what_it = conditionTrackerMap.find(metric.what());
        if (what_it == conditionTrackerMap.end()) {
            ALOGE("DurationMetric's \"what\" is invalid");
            return false;
        }

        const Predicate& durationWhat = config.predicate(what_it->second);

        if (durationWhat.contents_case() != Predicate::ContentsCase::kSimplePredicate) {
            ALOGE("DurationMetric's \"what\" must be a simple condition");
            return false;
        }

        const auto& simplePredicate = durationWhat.simple_predicate();

        bool nesting = simplePredicate.count_nesting();

        int trackerIndices[3] = {-1, -1, -1};
        if (!simplePredicate.has_start() ||
            !handleMetricWithLogTrackers(simplePredicate.start(), metricIndex,
                                         metric.dimension_size() > 0, allAtomMatchers,
                                         logTrackerMap, trackerToMetricMap, trackerIndices[0])) {
            ALOGE("Duration metrics must specify a valid the start event matcher");
            return false;
        }

        if (simplePredicate.has_stop() &&
            !handleMetricWithLogTrackers(simplePredicate.stop(), metricIndex,
                                         metric.dimension_size() > 0, allAtomMatchers,
                                         logTrackerMap, trackerToMetricMap, trackerIndices[1])) {
            return false;
        }

        if (simplePredicate.has_stop_all() &&
            !handleMetricWithLogTrackers(simplePredicate.stop_all(), metricIndex,
                                         metric.dimension_size() > 0, allAtomMatchers,
                                         logTrackerMap, trackerToMetricMap, trackerIndices[2])) {
            return false;
        }

        vector<KeyMatcher> internalDimension;
        internalDimension.insert(internalDimension.begin(), simplePredicate.dimension().begin(),
                                 simplePredicate.dimension().end());

        int conditionIndex = -1;

        if (metric.has_condition()) {
            bool good = handleMetricWithConditions(
                    metric.condition(), metricIndex, conditionTrackerMap, metric.links(),
                    allConditionTrackers, conditionIndex, conditionToMetricMap);
            if (!good) {
                return false;
            }
        } else {
            if (metric.links_size() > 0) {
                ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
                return false;
            }
        }

        sp<MetricProducer> durationMetric = new DurationMetricProducer(
                key, metric, conditionIndex, trackerIndices[0], trackerIndices[1],
                trackerIndices[2], nesting, wizard, internalDimension, startTimeNs);

        allMetricProducers.push_back(durationMetric);
    }

    // build EventMetricProducer
    for (int i = 0; i < config.event_metric_size(); i++) {
        int metricIndex = allMetricProducers.size();
        const EventMetric& metric = config.event_metric(i);
        metricMap.insert({metric.name(), metricIndex});
        if (!metric.has_name() || !metric.has_what()) {
            ALOGW("cannot find the metric name or what in config");
            return false;
        }
        int trackerIndex;
        if (!handleMetricWithLogTrackers(metric.what(), metricIndex, false, allAtomMatchers,
                                         logTrackerMap, trackerToMetricMap, trackerIndex)) {
            return false;
        }

        int conditionIndex = -1;
        if (metric.has_condition()) {
            bool good = handleMetricWithConditions(
                    metric.condition(), metricIndex, conditionTrackerMap, metric.links(),
                    allConditionTrackers, conditionIndex, conditionToMetricMap);
            if (!good) {
                return false;
            }
        } else {
            if (metric.links_size() > 0) {
                ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
                return false;
            }
        }

        sp<MetricProducer> eventMetric =
                new EventMetricProducer(key, metric, conditionIndex, wizard, startTimeNs);

        allMetricProducers.push_back(eventMetric);
    }

    // build ValueMetricProducer
    for (int i = 0; i < config.value_metric_size(); i++) {
        const ValueMetric& metric = config.value_metric(i);
        if (!metric.has_what()) {
            ALOGW("cannot find \"what\" in ValueMetric \"%s\"", metric.name().c_str());
            return false;
        }

        int metricIndex = allMetricProducers.size();
        metricMap.insert({metric.name(), metricIndex});
        int trackerIndex;
        if (!handleMetricWithLogTrackers(metric.what(), metricIndex, metric.dimension_size() > 0,
                                         allAtomMatchers, logTrackerMap, trackerToMetricMap,
                                         trackerIndex)) {
            return false;
        }

        sp<LogMatchingTracker> atomMatcher = allAtomMatchers.at(trackerIndex);
        // If it is pulled atom, it should be simple matcher with one tagId.
        if (atomMatcher->getTagIds().size() != 1) {
            return false;
        }
        int atomTagId = *(atomMatcher->getTagIds().begin());
        int pullTagId = statsPullerManager.PullerForMatcherExists(atomTagId) ? atomTagId : -1;

        int conditionIndex = -1;
        if (metric.has_condition()) {
            bool good = handleMetricWithConditions(
                    metric.condition(), metricIndex, conditionTrackerMap, metric.links(),
                    allConditionTrackers, conditionIndex, conditionToMetricMap);
            if (!good) {
                return false;
            }
        } else {
            if (metric.links_size() > 0) {
                ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
                return false;
            }
        }

        sp<MetricProducer> valueProducer = new ValueMetricProducer(key, metric, conditionIndex,
                                                                   wizard, pullTagId, startTimeNs);
        allMetricProducers.push_back(valueProducer);
    }

    // Gauge metrics.
    for (int i = 0; i < config.gauge_metric_size(); i++) {
        const GaugeMetric& metric = config.gauge_metric(i);
        if (!metric.has_what()) {
            ALOGW("cannot find \"what\" in GaugeMetric \"%s\"", metric.name().c_str());
            return false;
        }

        if ((!metric.gauge_fields().has_include_all() ||
             (metric.gauge_fields().include_all() == false)) &&
            metric.gauge_fields().field_num_size() == 0) {
            ALOGW("Incorrect field filter setting in GaugeMetric %s", metric.name().c_str());
            return false;
        }
        if ((metric.gauge_fields().has_include_all() &&
             metric.gauge_fields().include_all() == true) &&
            metric.gauge_fields().field_num_size() > 0) {
            ALOGW("Incorrect field filter setting in GaugeMetric %s", metric.name().c_str());
            return false;
        }

        int metricIndex = allMetricProducers.size();
        metricMap.insert({metric.name(), metricIndex});
        int trackerIndex;
        if (!handleMetricWithLogTrackers(metric.what(), metricIndex, metric.dimension_size() > 0,
                                         allAtomMatchers, logTrackerMap, trackerToMetricMap,
                                         trackerIndex)) {
            return false;
        }

        sp<LogMatchingTracker> atomMatcher = allAtomMatchers.at(trackerIndex);
        // If it is pulled atom, it should be simple matcher with one tagId.
        if (atomMatcher->getTagIds().size() != 1) {
            return false;
        }
        int atomTagId = *(atomMatcher->getTagIds().begin());
        int pullTagId = statsPullerManager.PullerForMatcherExists(atomTagId) ? atomTagId : -1;

        int conditionIndex = -1;
        if (metric.has_condition()) {
            bool good = handleMetricWithConditions(
                    metric.condition(), metricIndex, conditionTrackerMap, metric.links(),
                    allConditionTrackers, conditionIndex, conditionToMetricMap);
            if (!good) {
                return false;
            }
        } else {
            if (metric.links_size() > 0) {
                ALOGW("metrics has a MetricConditionLink but doesn't have a condition");
                return false;
            }
        }

        sp<MetricProducer> gaugeProducer = new GaugeMetricProducer(
                key, metric, conditionIndex, wizard, pullTagId, atomTagId, startTimeNs);
        allMetricProducers.push_back(gaugeProducer);
    }
    return true;
}

bool initAlerts(const StatsdConfig& config, const unordered_map<string, int>& metricProducerMap,
                vector<sp<MetricProducer>>& allMetricProducers,
                vector<sp<AnomalyTracker>>& allAnomalyTrackers) {
    for (int i = 0; i < config.alert_size(); i++) {
        const Alert& alert = config.alert(i);
        const auto& itr = metricProducerMap.find(alert.metric_name());
        if (itr == metricProducerMap.end()) {
            ALOGW("alert \"%s\" has unknown metric name: \"%s\"", alert.name().c_str(),
                  alert.metric_name().c_str());
            return false;
        }
        if (alert.trigger_if_sum_gt() < 0 || alert.number_of_buckets() <= 0) {
            ALOGW("invalid alert: threshold=%lld num_buckets= %d",
                  alert.trigger_if_sum_gt(), alert.number_of_buckets());
            return false;
        }
        const int metricIndex = itr->second;
        sp<MetricProducer> metric = allMetricProducers[metricIndex];
        sp<AnomalyTracker> anomalyTracker = metric->createAnomalyTracker(alert);
        if (anomalyTracker != nullptr) {
            metric->addAnomalyTracker(anomalyTracker);
            allAnomalyTrackers.push_back(anomalyTracker);
        }
    }
    return true;
}

bool initStatsdConfig(const ConfigKey& key, const StatsdConfig& config, const long timeBaseSec, set<int>& allTagIds,
                      vector<sp<LogMatchingTracker>>& allAtomMatchers,
                      vector<sp<ConditionTracker>>& allConditionTrackers,
                      vector<sp<MetricProducer>>& allMetricProducers,
                      vector<sp<AnomalyTracker>>& allAnomalyTrackers,
                      unordered_map<int, std::vector<int>>& conditionToMetricMap,
                      unordered_map<int, std::vector<int>>& trackerToMetricMap,
                      unordered_map<int, std::vector<int>>& trackerToConditionMap) {
    unordered_map<string, int> logTrackerMap;
    unordered_map<string, int> conditionTrackerMap;
    unordered_map<string, int> metricProducerMap;

    if (!initLogTrackers(config, logTrackerMap, allAtomMatchers, allTagIds)) {
        ALOGE("initLogMatchingTrackers failed");
        return false;
    }
    ALOGD("initLogMatchingTrackers succeed...");

    if (!initConditions(key, config, logTrackerMap, conditionTrackerMap, allConditionTrackers,
                        trackerToConditionMap)) {
        ALOGE("initConditionTrackers failed");
        return false;
    }

    if (!initMetrics(key, config, timeBaseSec, logTrackerMap, conditionTrackerMap, allAtomMatchers,
                     allConditionTrackers, allMetricProducers, conditionToMetricMap,
                     trackerToMetricMap, metricProducerMap)) {
        ALOGE("initMetricProducers failed");
        return false;
    }
    if (!initAlerts(config, metricProducerMap, allMetricProducers, allAnomalyTrackers)) {
        ALOGE("initAlerts failed");
        return false;
    }
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
