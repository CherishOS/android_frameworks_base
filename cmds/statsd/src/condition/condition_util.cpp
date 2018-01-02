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

#include "condition_util.h"

#include <log/event_tag_map.h>
#include <log/log_event_list.h>
#include <log/logprint.h>
#include <utils/Errors.h>
#include <unordered_map>
#include "../matchers/matcher_util.h"
#include "ConditionTracker.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "stats_util.h"
#include "dimension.h"

namespace android {
namespace os {
namespace statsd {

using std::set;
using std::string;
using std::unordered_map;
using std::vector;


ConditionState evaluateCombinationCondition(const std::vector<int>& children,
                                            const LogicalOperation& operation,
                                            const std::vector<ConditionState>& conditionCache) {
    ConditionState newCondition;

    bool hasUnknown = false;
    bool hasFalse = false;
    bool hasTrue = false;

    for (auto childIndex : children) {
        ConditionState childState = conditionCache[childIndex];
        if (childState == ConditionState::kUnknown) {
            hasUnknown = true;
            break;
        }
        if (childState == ConditionState::kFalse) {
            hasFalse = true;
        }
        if (childState == ConditionState::kTrue) {
            hasTrue = true;
        }
    }

    // If any child condition is in unknown state, the condition is unknown too.
    if (hasUnknown) {
        return ConditionState::kUnknown;
    }

    switch (operation) {
        case LogicalOperation::AND: {
            newCondition = hasFalse ? ConditionState::kFalse : ConditionState::kTrue;
            break;
        }
        case LogicalOperation::OR: {
            newCondition = hasTrue ? ConditionState::kTrue : ConditionState::kFalse;
            break;
        }
        case LogicalOperation::NOT:
            newCondition = (conditionCache[children[0]] == ConditionState::kFalse)
                                   ? ConditionState::kTrue
                                   : ConditionState::kFalse;
            break;
        case LogicalOperation::NAND:
            newCondition = hasFalse ? ConditionState::kTrue : ConditionState::kFalse;
            break;
        case LogicalOperation::NOR:
            newCondition = hasTrue ? ConditionState::kFalse : ConditionState::kTrue;
            break;
        case LogicalOperation::LOGICAL_OPERATION_UNSPECIFIED:
            newCondition = ConditionState::kFalse;
            break;
    }
    return newCondition;
}

ConditionState operator|(ConditionState l, ConditionState r) {
    return l >= r ? l : r;
}

void OrConditionState(const std::vector<ConditionState>& ref, vector<ConditionState> * ored) {
    if (ref.size() != ored->size()) {
        return;
    }
    for (size_t i = 0; i < ored->size(); ++i) {
        ored->at(i) = ored->at(i) | ref.at(i);
    }
}

void OrBooleanVector(const std::vector<bool>& ref, vector<bool> * ored) {
    if (ref.size() != ored->size()) {
        return;
    }
    for (size_t i = 0; i < ored->size(); ++i) {
        ored->at(i) = ored->at(i) | ref.at(i);
    }
}

void getFieldsFromFieldMatcher(const FieldMatcher& matcher, const Field& parentField,
                       std::vector<Field> *allFields) {
    Field newParent = parentField;
    Field* leaf = getSingleLeaf(&newParent);
    leaf->set_field(matcher.field());
    if (matcher.child_size() == 0) {
        allFields->push_back(newParent);
        return;
    }
    for (int i = 0; i < matcher.child_size(); ++i) {
        leaf->add_child();
        getFieldsFromFieldMatcher(matcher.child(i), newParent, allFields);
    }
}

void getFieldsFromFieldMatcher(const FieldMatcher& matcher, std::vector<Field> *allFields) {
    Field parentField;
    getFieldsFromFieldMatcher(matcher, parentField, allFields);
}

void flattenValueLeaves(const DimensionsValue& value,
                        std::vector<DimensionsValue> *allLaves) {
    switch (value.value_case()) {
        case DimensionsValue::ValueCase::kValueStr:
        case DimensionsValue::ValueCase::kValueInt:
        case DimensionsValue::ValueCase::kValueLong:
        case DimensionsValue::ValueCase::kValueBool:
        case DimensionsValue::ValueCase::kValueFloat:
        case DimensionsValue::ValueCase::VALUE_NOT_SET:
            allLaves->push_back(value);
            break;
        case DimensionsValue::ValueCase::kValueTuple:
            for (int i = 0; i < value.value_tuple().dimensions_value_size(); ++i) {
                flattenValueLeaves(value.value_tuple().dimensions_value(i), allLaves);
            }
            break;
    }
}

std::vector<HashableDimensionKey> getDimensionKeysForCondition(
    const LogEvent& event, const MetricConditionLink& link) {
    std::vector<Field> whatFields;
    getFieldsFromFieldMatcher(link.dimensions_in_what(), &whatFields);
    std::vector<Field> conditionFields;
    getFieldsFromFieldMatcher(link.dimensions_in_condition(), &conditionFields);

    std::vector<HashableDimensionKey> hashableDimensionKeys;

    // TODO(yanglu): here we could simplify the logic to get the leaf value node in what and
    // directly construct the full condition value tree.
    std::vector<DimensionsValue> whatValues = getDimensionKeys(event, link.dimensions_in_what());

    for (size_t i = 0; i < whatValues.size(); ++i) {
        std::vector<DimensionsValue> whatLeaves;
        flattenValueLeaves(whatValues[i], &whatLeaves);
        if (whatLeaves.size() != whatFields.size() ||
            whatLeaves.size() != conditionFields.size()) {
            ALOGE("Dimensions between what and condition not equal.");
            return hashableDimensionKeys;
        }
        FieldValueMap conditionValueMap;
        for (size_t j = 0; j < whatLeaves.size(); ++j) {
            if (!setFieldInLeafValueProto(conditionFields[j], &whatLeaves[j])) {
                ALOGE("Not able to reset the field for condition leaf value.");
                return hashableDimensionKeys;
            }
            conditionValueMap.insert(std::make_pair(conditionFields[j], whatLeaves[j]));
        }
        std::vector<DimensionsValue> conditionValues;
        findDimensionsValues(conditionValueMap, link.dimensions_in_condition(), &conditionValues);
        if (conditionValues.size() != 1) {
            ALOGE("Not able to find unambiguous field value in condition atom.");
            continue;
        }
        hashableDimensionKeys.push_back(HashableDimensionKey(conditionValues[0]));
    }

    return hashableDimensionKeys;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
