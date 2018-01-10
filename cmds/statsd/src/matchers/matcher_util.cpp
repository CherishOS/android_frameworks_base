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

#include "Log.h"

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "matchers/LogMatchingTracker.h"
#include "matchers/matcher_util.h"
#include "dimension.h"
#include "stats_util.h"
#include "field_util.h"

#include <log/event_tag_map.h>
#include <log/log_event_list.h>
#include <log/logprint.h>
#include <utils/Errors.h>

#include <sstream>
#include <unordered_map>

using std::ostringstream;
using std::set;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

bool combinationMatch(const vector<int>& children, const LogicalOperation& operation,
                      const vector<MatchingState>& matcherResults) {
    bool matched;
    switch (operation) {
        case LogicalOperation::AND: {
            matched = true;
            for (const int childIndex : children) {
                if (matcherResults[childIndex] != MatchingState::kMatched) {
                    matched = false;
                    break;
                }
            }
            break;
        }
        case LogicalOperation::OR: {
            matched = false;
            for (const int childIndex : children) {
                if (matcherResults[childIndex] == MatchingState::kMatched) {
                    matched = true;
                    break;
                }
            }
            break;
        }
        case LogicalOperation::NOT:
            matched = matcherResults[children[0]] == MatchingState::kNotMatched;
            break;
        case LogicalOperation::NAND:
            matched = false;
            for (const int childIndex : children) {
                if (matcherResults[childIndex] != MatchingState::kMatched) {
                    matched = true;
                    break;
                }
            }
            break;
        case LogicalOperation::NOR:
            matched = true;
            for (const int childIndex : children) {
                if (matcherResults[childIndex] == MatchingState::kMatched) {
                    matched = false;
                    break;
                }
            }
            break;
        case LogicalOperation::LOGICAL_OPERATION_UNSPECIFIED:
            matched = false;
            break;
    }
    return matched;
}

bool matchesNonRepeatedField(
       const UidMap& uidMap,
       const FieldValueMap& fieldMap,
       const FieldValueMatcher&matcher,
       const Field& field) {
    if (matcher.value_matcher_case() ==
            FieldValueMatcher::ValueMatcherCase::VALUE_MATCHER_NOT_SET) {
        return !fieldMap.empty() && fieldMap.begin()->first.field() == matcher.field();
    } else if (matcher.value_matcher_case() == FieldValueMatcher::ValueMatcherCase::kMatchesTuple) {
        bool allMatched = true;
        for (int i = 0; allMatched && i <  matcher.matches_tuple().field_value_matcher_size(); ++i) {
            const auto& childMatcher = matcher.matches_tuple().field_value_matcher(i);
            Field childField = field;
            appendLeaf(&childField, childMatcher.field());
            allMatched &= matchFieldSimple(uidMap, fieldMap, childMatcher, childField);
        }
        return allMatched;
    } else {
        auto ret = fieldMap.equal_range(field);
        int found = 0;
        for (auto it = ret.first; it != ret.second; ++it) {
            found++;
        }
        // Not found.
        if (found <= 0) {
            return false;
        }
        if (found > 1) {
            ALOGE("Found multiple values for optional field.");
            return false;
        }
        bool matched = false;
        switch (matcher.value_matcher_case()) {
            case FieldValueMatcher::ValueMatcherCase::kEqBool:
                 // Logd does not support bool, it is int instead.
                 matched = ((ret.first->second.value_int() > 0) == matcher.eq_bool());
                 break;
            case FieldValueMatcher::ValueMatcherCase::kEqString:
                 {
                    if (IsAttributionUidField(field)) {
                        const int uid = ret.first->second.value_int();
                        std::set<string> packageNames =
                            uidMap.getAppNamesFromUid(uid, true /* normalize*/);
                        matched = packageNames.find(matcher.eq_string()) != packageNames.end();
                    } else {
                        matched = (ret.first->second.value_str() == matcher.eq_string());
                    }
                 }
                 break;
            case FieldValueMatcher::ValueMatcherCase::kEqInt:
                 matched = (ret.first->second.value_int() == matcher.eq_int());
                 break;
            case FieldValueMatcher::ValueMatcherCase::kLtInt:
                 matched = (ret.first->second.value_int() < matcher.lt_int());
                 break;
            case FieldValueMatcher::ValueMatcherCase::kGtInt:
                 matched = (ret.first->second.value_int() > matcher.gt_int());
                 break;
            case FieldValueMatcher::ValueMatcherCase::kLtFloat:
                 matched = (ret.first->second.value_float() < matcher.lt_float());
                 break;
            case FieldValueMatcher::ValueMatcherCase::kGtFloat:
                 matched = (ret.first->second.value_float() > matcher.gt_float());
                 break;
            case FieldValueMatcher::ValueMatcherCase::kLteInt:
                 matched = (ret.first->second.value_int() <= matcher.lte_int());
                 break;
            case FieldValueMatcher::ValueMatcherCase::kGteInt:
                 matched = (ret.first->second.value_int() >= matcher.gte_int());
                 break;
            default:
                break;
        }
        return matched;
    }
}

bool matchesRepeatedField(const UidMap& uidMap, const FieldValueMap& fieldMap,
                          const FieldValueMatcher&matcher, const Field& field) {
    if (matcher.position() == Position::FIRST) {
        Field first_field = field;
        setPositionForLeaf(&first_field, 0);
        return matchesNonRepeatedField(uidMap, fieldMap, matcher, first_field);
    } else {
        auto itLower = fieldMap.lower_bound(field);
        if (itLower == fieldMap.end()) {
            return false;
        }
        Field next_field = field;
        getNextField(&next_field);
        auto itUpper = fieldMap.lower_bound(next_field);
        switch (matcher.position()) {
             case Position::LAST:
                 {
                     itUpper--;
                     if (itUpper == fieldMap.end()) {
                        return false;
                     } else {
                         Field last_field = field;
                         int last_index = getPositionByReferenceField(field, itUpper->first);
                         if (last_index < 0) {
                            return false;
                         }
                         setPositionForLeaf(&last_field, last_index);
                         return matchesNonRepeatedField(uidMap, fieldMap, matcher, last_field);
                     }
                 }
                 break;
             case Position::ANY:
                 {
                    std::set<int> indexes;
                    for (auto it = itLower; it != itUpper; ++it) {
                        int index = getPositionByReferenceField(field, it->first);
                        if (index >= 0) {
                            indexes.insert(index);
                        }
                    }
                    bool matched = false;
                    for (const int index : indexes) {
                         Field any_field = field;
                         setPositionForLeaf(&any_field, index);
                         matched |= matchesNonRepeatedField(uidMap, fieldMap, matcher, any_field);
                    }
                    return matched;
                 }
             default:
                return false;
         }
    }

}

bool matchFieldSimple(const UidMap& uidMap, const FieldValueMap& fieldMap,
                      const FieldValueMatcher&matcher, const Field& field) {
    if (!matcher.has_position()) {
        return matchesNonRepeatedField(uidMap, fieldMap, matcher, field);
    } else {
        return matchesRepeatedField(uidMap, fieldMap, matcher, field);
    }
}

bool matchesSimple(const UidMap& uidMap, const SimpleAtomMatcher& simpleMatcher,
                   const LogEvent& event) {
    if (simpleMatcher.field_value_matcher_size() <= 0) {
        return event.GetTagId() == simpleMatcher.atom_id();
    }
    Field root_field;
    root_field.set_field(simpleMatcher.atom_id());
    FieldValueMatcher root_field_matcher;
    root_field_matcher.set_field(simpleMatcher.atom_id());
    for (int i = 0; i < simpleMatcher.field_value_matcher_size(); i++) {
        *root_field_matcher.mutable_matches_tuple()->add_field_value_matcher() =
            simpleMatcher.field_value_matcher(i);
    }
    return matchFieldSimple(uidMap, event.getFieldValueMap(), root_field_matcher, root_field);
}

vector<DimensionsValue> getDimensionKeys(const LogEvent& event, const FieldMatcher& matcher) {
    vector<DimensionsValue> values;
    findDimensionsValues(event.getFieldValueMap(), matcher, &values);
    return values;
}
}  // namespace statsd
}  // namespace os
}  // namespace android
