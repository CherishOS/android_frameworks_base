// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <gtest/gtest.h>
#include "statsd_test_util.h"

namespace android {
namespace os {
namespace statsd {

AtomMatcher CreateWakelockStateChangedAtomMatcher(const string& name,
                                                  WakelockStateChanged::State state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_name(name);
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::WAKELOCK_STATE_CHANGED);
    auto field_value_matcher = simple_atom_matcher->add_field_value_matcher();
    field_value_matcher->set_field(4);  // State field.
    field_value_matcher->set_eq_int(state);
    return atom_matcher;
}

AtomMatcher CreateAcquireWakelockAtomMatcher() {
    return CreateWakelockStateChangedAtomMatcher("AcquireWakelock", WakelockStateChanged::ACQUIRE);
}

AtomMatcher CreateReleaseWakelockAtomMatcher() {
    return CreateWakelockStateChangedAtomMatcher("ReleaseWakelock", WakelockStateChanged::RELEASE);
}

AtomMatcher CreateScreenStateChangedAtomMatcher(
    const string& name, ScreenStateChanged::State state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_name(name);
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::SCREEN_STATE_CHANGED);
    auto field_value_matcher = simple_atom_matcher->add_field_value_matcher();
    field_value_matcher->set_field(1);  // State field.
    field_value_matcher->set_eq_int(state);
    return atom_matcher;
}

AtomMatcher CreateScreenTurnedOnAtomMatcher() {
    return CreateScreenStateChangedAtomMatcher("ScreenTurnedOn", ScreenStateChanged::STATE_ON);
}

AtomMatcher CreateScreenTurnedOffAtomMatcher() {
    return CreateScreenStateChangedAtomMatcher("ScreenTurnedOff", ScreenStateChanged::STATE_OFF);
}

AtomMatcher CreateSyncStateChangedAtomMatcher(
    const string& name, SyncStateChanged::State state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_name(name);
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::SYNC_STATE_CHANGED);
    auto field_value_matcher = simple_atom_matcher->add_field_value_matcher();
    field_value_matcher->set_field(3);  // State field.
    field_value_matcher->set_eq_int(state);
    return atom_matcher;
}

AtomMatcher CreateSyncStartAtomMatcher() {
    return CreateSyncStateChangedAtomMatcher("SyncStart", SyncStateChanged::ON);
}

AtomMatcher CreateSyncEndAtomMatcher() {
    return CreateSyncStateChangedAtomMatcher("SyncEnd", SyncStateChanged::OFF);
}

AtomMatcher CreateActivityForegroundStateChangedAtomMatcher(
    const string& name, ActivityForegroundStateChanged::Activity activity) {
    AtomMatcher atom_matcher;
    atom_matcher.set_name(name);
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED);
    auto field_value_matcher = simple_atom_matcher->add_field_value_matcher();
    field_value_matcher->set_field(4);  // Activity field.
    field_value_matcher->set_eq_int(activity);
    return atom_matcher;
}

AtomMatcher CreateMoveToBackgroundAtomMatcher() {
    return CreateActivityForegroundStateChangedAtomMatcher(
        "MoveToBackground", ActivityForegroundStateChanged::MOVE_TO_BACKGROUND);
}

AtomMatcher CreateMoveToForegroundAtomMatcher() {
    return CreateActivityForegroundStateChangedAtomMatcher(
        "MoveToForeground", ActivityForegroundStateChanged::MOVE_TO_FOREGROUND);
}

AtomMatcher CreateProcessLifeCycleStateChangedAtomMatcher(
    const string& name, ProcessLifeCycleStateChanged::Event event) {
    AtomMatcher atom_matcher;
    atom_matcher.set_name(name);
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    auto field_value_matcher = simple_atom_matcher->add_field_value_matcher();
    field_value_matcher->set_field(3);  // Process state field.
    field_value_matcher->set_eq_int(event);
    return atom_matcher;
}

AtomMatcher CreateProcessCrashAtomMatcher() {
    return CreateProcessLifeCycleStateChangedAtomMatcher(
        "ProcessCrashed", ProcessLifeCycleStateChanged::PROCESS_CRASHED);
}


Predicate CreateScreenIsOnPredicate() {
    Predicate predicate;
    predicate.set_name("ScreenIsOn");
    predicate.mutable_simple_predicate()->set_start("ScreenTurnedOn");
    predicate.mutable_simple_predicate()->set_stop("ScreenTurnedOff");
    return predicate;
}

Predicate CreateScreenIsOffPredicate() {
    Predicate predicate;
    predicate.set_name("ScreenIsOff");
    predicate.mutable_simple_predicate()->set_start("ScreenTurnedOff");
    predicate.mutable_simple_predicate()->set_stop("ScreenTurnedOn");
    return predicate;
}

Predicate CreateHoldingWakelockPredicate() {
    Predicate predicate;
    predicate.set_name("HoldingWakelock");
    predicate.mutable_simple_predicate()->set_start("AcquireWakelock");
    predicate.mutable_simple_predicate()->set_stop("ReleaseWakelock");
    return predicate;
}

Predicate CreateIsSyncingPredicate() {
    Predicate predicate;
    predicate.set_name("IsSyncing");
    predicate.mutable_simple_predicate()->set_start("SyncStart");
    predicate.mutable_simple_predicate()->set_stop("SyncEnd");
    return predicate;
}

Predicate CreateIsInBackgroundPredicate() {
    Predicate predicate;
    predicate.set_name("IsInBackground");
    predicate.mutable_simple_predicate()->set_start("MoveToBackground");
    predicate.mutable_simple_predicate()->set_stop("MoveToForeground");
    return predicate;
}

void addPredicateToPredicateCombination(const Predicate& predicate,
                                        Predicate* combinationPredicate) {
    combinationPredicate->mutable_combination()->add_predicate(predicate.name());
}

FieldMatcher CreateAttributionUidDimensions(const int atomId,
                                            const std::vector<Position>& positions) {
    FieldMatcher dimensions;
    dimensions.set_field(atomId);
    for (const auto position : positions) {
        auto child = dimensions.add_child();
        child->set_field(1);
        child->set_position(position);
        child->add_child()->set_field(1);
    }
    return dimensions;
}

FieldMatcher CreateAttributionUidAndTagDimensions(const int atomId,
                                                 const std::vector<Position>& positions) {
    FieldMatcher dimensions;
    dimensions.set_field(atomId);
    for (const auto position : positions) {
        auto child = dimensions.add_child();
        child->set_field(1);
        child->set_position(position);
        child->add_child()->set_field(1);
        child->add_child()->set_field(2);
    }
    return dimensions;
}

FieldMatcher CreateDimensions(const int atomId, const std::vector<int>& fields) {
    FieldMatcher dimensions;
    dimensions.set_field(atomId);
    for (const int field : fields) {
        dimensions.add_child()->set_field(field);
    }
    return dimensions;
}

std::unique_ptr<LogEvent> CreateScreenStateChangedEvent(
    const ScreenStateChanged::State state, uint64_t timestampNs) {
    auto event = std::make_unique<LogEvent>(android::util::SCREEN_STATE_CHANGED, timestampNs);
    EXPECT_TRUE(event->write(state));
    event->init();
    return event;
}

std::unique_ptr<LogEvent> CreateWakelockStateChangedEvent(
    const std::vector<AttributionNode>& attributions, const string& wakelockName,
    const WakelockStateChanged::State state, uint64_t timestampNs) {
    auto event = std::make_unique<LogEvent>(android::util::WAKELOCK_STATE_CHANGED, timestampNs);
    event->write(attributions);
    event->write(WakelockStateChanged::PARTIAL);
    event->write(wakelockName);
    event->write(state);
    event->init();
    return event;
}

std::unique_ptr<LogEvent> CreateAcquireWakelockEvent(
    const std::vector<AttributionNode>& attributions,
    const string& wakelockName, uint64_t timestampNs) {
    return CreateWakelockStateChangedEvent(
        attributions, wakelockName, WakelockStateChanged::ACQUIRE, timestampNs);
}

std::unique_ptr<LogEvent> CreateReleaseWakelockEvent(
    const std::vector<AttributionNode>& attributions,
    const string& wakelockName, uint64_t timestampNs) {
    return CreateWakelockStateChangedEvent(
        attributions, wakelockName, WakelockStateChanged::RELEASE, timestampNs);
}

std::unique_ptr<LogEvent> CreateActivityForegroundStateChangedEvent(
    const int uid, const ActivityForegroundStateChanged::Activity activity, uint64_t timestampNs) {
    auto event = std::make_unique<LogEvent>(
        android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, timestampNs);
    event->write(uid);
    event->write("pkg_name");
    event->write("class_name");
    event->write(activity);
    event->init();
    return event;
}

std::unique_ptr<LogEvent> CreateMoveToBackgroundEvent(const int uid, uint64_t timestampNs) {
    return CreateActivityForegroundStateChangedEvent(
        uid, ActivityForegroundStateChanged::MOVE_TO_BACKGROUND, timestampNs);
}

std::unique_ptr<LogEvent> CreateMoveToForegroundEvent(const int uid, uint64_t timestampNs) {
    return CreateActivityForegroundStateChangedEvent(
        uid, ActivityForegroundStateChanged::MOVE_TO_FOREGROUND, timestampNs);
}

std::unique_ptr<LogEvent> CreateSyncStateChangedEvent(
    const int uid, const string& name, const SyncStateChanged::State state, uint64_t timestampNs) {
    auto event = std::make_unique<LogEvent>(android::util::SYNC_STATE_CHANGED, timestampNs);
    event->write(uid);
    event->write(name);
    event->write(state);
    event->init();
    return event;
}

std::unique_ptr<LogEvent> CreateSyncStartEvent(
    const int uid, const string& name, uint64_t timestampNs){
    return CreateSyncStateChangedEvent(uid, name, SyncStateChanged::ON, timestampNs);
}

std::unique_ptr<LogEvent> CreateSyncEndEvent(
    const int uid, const string& name, uint64_t timestampNs) {
    return CreateSyncStateChangedEvent(uid, name, SyncStateChanged::OFF, timestampNs);
}

std::unique_ptr<LogEvent> CreateProcessLifeCycleStateChangedEvent(
    const int uid, const ProcessLifeCycleStateChanged::Event event, uint64_t timestampNs) {
    auto logEvent = std::make_unique<LogEvent>(
        android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, timestampNs);
    logEvent->write(uid);
    logEvent->write("");
    logEvent->write(event);
    logEvent->init();
    return logEvent;
}

std::unique_ptr<LogEvent> CreateAppCrashEvent(const int uid, uint64_t timestampNs) {
    return CreateProcessLifeCycleStateChangedEvent(
        uid, ProcessLifeCycleStateChanged::PROCESS_CRASHED, timestampNs);
}

sp<StatsLogProcessor> CreateStatsLogProcessor(const long timeBaseSec, const StatsdConfig& config,
                                              const ConfigKey& key) {
    sp<UidMap> uidMap = new UidMap();
    sp<AnomalyMonitor> anomalyMonitor = new AnomalyMonitor(10); // 10 seconds
    sp<StatsLogProcessor> processor = new StatsLogProcessor(
        uidMap, anomalyMonitor, timeBaseSec, [](const ConfigKey&){});
    processor->OnConfigUpdated(key, config);
    return processor;
}

AttributionNode CreateAttribution(const int& uid, const string& tag) {
    AttributionNode attribution;
    attribution.set_uid(uid);
    attribution.set_tag(tag);
    return attribution;
}

void sortLogEventsByTimestamp(std::vector<std::unique_ptr<LogEvent>> *events) {
  std::sort(events->begin(), events->end(),
            [](const std::unique_ptr<LogEvent>& a, const std::unique_ptr<LogEvent>& b) {
              return a->GetTimestampNs() < b->GetTimestampNs();
            });
}

}  // namespace statsd
}  // namespace os
}  // namespace android