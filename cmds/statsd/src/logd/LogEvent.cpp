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

#define DEBUG false  // STOPSHIP if true
#include "logd/LogEvent.h"

#include "stats_log_util.h"
#include "statslog.h"

#include <android/binder_ibinder.h>
#include <android-base/stringprintf.h>
#include <private/android_filesystem_config.h>

namespace android {
namespace os {
namespace statsd {

// for TrainInfo experiment id serialization
const int FIELD_ID_EXPERIMENT_ID = 1;

using namespace android::util;
using android::base::StringPrintf;
using android::util::ProtoOutputStream;
using std::string;
using std::vector;

// stats_event.h socket types. Keep in sync.
/* ERRORS */
#define ERROR_NO_TIMESTAMP 0x1
#define ERROR_NO_ATOM_ID 0x2
#define ERROR_OVERFLOW 0x4
#define ERROR_ATTRIBUTION_CHAIN_TOO_LONG 0x8
#define ERROR_TOO_MANY_KEY_VALUE_PAIRS 0x10
#define ERROR_ANNOTATION_DOES_NOT_FOLLOW_FIELD 0x20
#define ERROR_INVALID_ANNOTATION_ID 0x40
#define ERROR_ANNOTATION_ID_TOO_LARGE 0x80
#define ERROR_TOO_MANY_ANNOTATIONS 0x100
#define ERROR_TOO_MANY_FIELDS 0x200
#define ERROR_INVALID_VALUE_TYPE 0x400
#define ERROR_STRING_NOT_NULL_TERMINATED 0x800

/* TYPE IDS */
#define INT32_TYPE 0x00
#define INT64_TYPE 0x01
#define STRING_TYPE 0x02
#define LIST_TYPE 0x03
#define FLOAT_TYPE 0x04
#define BOOL_TYPE 0x05
#define BYTE_ARRAY_TYPE 0x06
#define OBJECT_TYPE 0x07
#define KEY_VALUE_PAIRS_TYPE 0x08
#define ATTRIBUTION_CHAIN_TYPE 0x09
#define ERROR_TYPE 0x0F

// Msg is expected to begin at the start of the serialized atom -- it should not
// include the android_log_header_t or the StatsEventTag.
LogEvent::LogEvent(uint8_t* msg, uint32_t len, int32_t uid, int32_t pid)
    : mBuf(msg),
      mRemainingLen(len),
      mLogdTimestampNs(time(nullptr)),
      mLogUid(uid),
      mLogPid(pid)
{
#ifdef NEW_ENCODING_SCHEME
    initNew();
# else
    mContext = create_android_log_parser((char*)msg, len);
    init(mContext);
    if (mContext) android_log_destroy(&mContext); // set mContext to NULL
#endif
}

LogEvent::LogEvent(uint8_t* msg, uint32_t len, int32_t uid, int32_t pid, bool useNewSchema)
    : mBuf(msg),
      mRemainingLen(len),
      mLogdTimestampNs(time(nullptr)),
      mLogUid(uid),
      mLogPid(pid)
{
    if (useNewSchema) {
        initNew();
    } else {
        mContext = create_android_log_parser((char*)msg, len);
        init(mContext);
        if (mContext) android_log_destroy(&mContext);  // set mContext to NULL
    }
}

LogEvent::LogEvent(const LogEvent& event) {
    mTagId = event.mTagId;
    mLogUid = event.mLogUid;
    mLogPid = event.mLogPid;
    mElapsedTimestampNs = event.mElapsedTimestampNs;
    mLogdTimestampNs = event.mLogdTimestampNs;
    mValues = event.mValues;
}

LogEvent::LogEvent(int32_t tagId, int64_t wallClockTimestampNs, int64_t elapsedTimestampNs) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = tagId;
    mLogUid = 0;
    mContext = create_android_logger(1937006964); // the event tag shared by all stats logs
    if (mContext) {
        android_log_write_int64(mContext, elapsedTimestampNs);
        android_log_write_int32(mContext, tagId);
    }
}

LogEvent::LogEvent(int32_t tagId, int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                   int32_t uid,
                   const std::map<int32_t, int32_t>& int_map,
                   const std::map<int32_t, int64_t>& long_map,
                   const std::map<int32_t, std::string>& string_map,
                   const std::map<int32_t, float>& float_map) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = android::util::KEY_VALUE_PAIRS_ATOM;
    mLogUid = uid;

    int pos[] = {1, 1, 1};

    mValues.push_back(FieldValue(Field(mTagId, pos, 0 /* depth */), Value(uid)));
    pos[0]++;
    for (const auto&itr : int_map) {
        pos[2] = 1;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.first)));
        pos[2] = 2;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.second)));
        mValues.back().mField.decorateLastPos(2);
        pos[1]++;
    }

    for (const auto&itr : long_map) {
        pos[2] = 1;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.first)));
        pos[2] = 3;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.second)));
        mValues.back().mField.decorateLastPos(2);
        pos[1]++;
    }

    for (const auto&itr : string_map) {
        pos[2] = 1;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.first)));
        pos[2] = 4;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.second)));
        mValues.back().mField.decorateLastPos(2);
        pos[1]++;
    }

    for (const auto&itr : float_map) {
        pos[2] = 1;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.first)));
        pos[2] = 5;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.second)));
        mValues.back().mField.decorateLastPos(2);
        pos[1]++;
    }
    if (!mValues.empty()) {
        mValues.back().mField.decorateLastPos(1);
        mValues.at(mValues.size() - 2).mField.decorateLastPos(1);
    }
}

LogEvent::LogEvent(const string& trainName, int64_t trainVersionCode, bool requiresStaging,
                   bool rollbackEnabled, bool requiresLowLatencyMonitor, int32_t state,
                   const std::vector<uint8_t>& experimentIds, int32_t userId) {
    mLogdTimestampNs = getWallClockNs();
    mElapsedTimestampNs = getElapsedRealtimeNs();
    mTagId = android::util::BINARY_PUSH_STATE_CHANGED;
    mLogUid = AIBinder_getCallingUid();
    mLogPid = AIBinder_getCallingPid();

    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(1)), Value(trainName)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(2)), Value(trainVersionCode)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(3)), Value((int)requiresStaging)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(4)), Value((int)rollbackEnabled)));
    mValues.push_back(
            FieldValue(Field(mTagId, getSimpleField(5)), Value((int)requiresLowLatencyMonitor)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(6)), Value(state)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(7)), Value(experimentIds)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(8)), Value(userId)));
}

LogEvent::LogEvent(int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                   const InstallTrainInfo& trainInfo) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = android::util::TRAIN_INFO;

    mValues.push_back(
            FieldValue(Field(mTagId, getSimpleField(1)), Value(trainInfo.trainVersionCode)));
    std::vector<uint8_t> experimentIdsProto;
    writeExperimentIdsToProto(trainInfo.experimentIds, &experimentIdsProto);
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(2)), Value(experimentIdsProto)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(3)), Value(trainInfo.trainName)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(4)), Value(trainInfo.status)));
}

LogEvent::LogEvent(int32_t tagId, int64_t timestampNs) : LogEvent(tagId, timestampNs, timestampNs) {
}

LogEvent::LogEvent(int32_t tagId, int64_t timestampNs, int32_t uid) {
    mLogdTimestampNs = timestampNs;
    mTagId = tagId;
    mLogUid = uid;
    mContext = create_android_logger(1937006964); // the event tag shared by all stats logs
    if (mContext) {
        android_log_write_int64(mContext, timestampNs);
        android_log_write_int32(mContext, tagId);
    }
}

void LogEvent::init() {
    if (mContext) {
        const char* buffer;
        size_t len = android_log_write_list_buffer(mContext, &buffer);
        // turns to reader mode
        android_log_context contextForRead = create_android_log_parser(buffer, len);
        if (contextForRead) {
            init(contextForRead);
            // destroy the context to save memory.
            // android_log_destroy will set mContext to NULL
            android_log_destroy(&contextForRead);
        }
        android_log_destroy(&mContext);
    }
}

LogEvent::~LogEvent() {
    if (mContext) {
        // This is for the case when LogEvent is created using the test interface
        // but init() isn't called.
        android_log_destroy(&mContext);
    }
}

bool LogEvent::write(int32_t value) {
    if (mContext) {
        return android_log_write_int32(mContext, value) >= 0;
    }
    return false;
}

bool LogEvent::write(uint32_t value) {
    if (mContext) {
        return android_log_write_int32(mContext, value) >= 0;
    }
    return false;
}

bool LogEvent::write(int64_t value) {
    if (mContext) {
        return android_log_write_int64(mContext, value) >= 0;
    }
    return false;
}

bool LogEvent::write(uint64_t value) {
    if (mContext) {
        return android_log_write_int64(mContext, value) >= 0;
    }
    return false;
}

bool LogEvent::write(const string& value) {
    if (mContext) {
        return android_log_write_string8_len(mContext, value.c_str(), value.length()) >= 0;
    }
    return false;
}

bool LogEvent::write(float value) {
    if (mContext) {
        return android_log_write_float32(mContext, value) >= 0;
    }
    return false;
}

bool LogEvent::writeBytes(const string& value) {
    /* if (mContext) {
        return android_log_write_char_array(mContext, value.c_str(), value.length()) >= 0;
    }*/
    return false;
}

bool LogEvent::writeKeyValuePairs(int32_t uid,
                                  const std::map<int32_t, int32_t>& int_map,
                                  const std::map<int32_t, int64_t>& long_map,
                                  const std::map<int32_t, std::string>& string_map,
                                  const std::map<int32_t, float>& float_map) {
    if (mContext) {
         if (android_log_write_list_begin(mContext) < 0) {
            return false;
         }
         write(uid);
         for (const auto& itr : int_map) {
             if (android_log_write_list_begin(mContext) < 0) {
                return false;
             }
             write(itr.first);
             write(itr.second);
             if (android_log_write_list_end(mContext) < 0) {
                return false;
             }
         }

         for (const auto& itr : long_map) {
             if (android_log_write_list_begin(mContext) < 0) {
                return false;
             }
             write(itr.first);
             write(itr.second);
             if (android_log_write_list_end(mContext) < 0) {
                return false;
             }
         }

         for (const auto& itr : string_map) {
             if (android_log_write_list_begin(mContext) < 0) {
                return false;
             }
             write(itr.first);
             write(itr.second.c_str());
             if (android_log_write_list_end(mContext) < 0) {
                return false;
             }
         }

         for (const auto& itr : float_map) {
             if (android_log_write_list_begin(mContext) < 0) {
                return false;
             }
             write(itr.first);
             write(itr.second);
             if (android_log_write_list_end(mContext) < 0) {
                return false;
             }
         }

         if (android_log_write_list_end(mContext) < 0) {
            return false;
         }
         return true;
    }
    return false;
}

bool LogEvent::write(const std::vector<AttributionNodeInternal>& nodes) {
    if (mContext) {
         if (android_log_write_list_begin(mContext) < 0) {
            return false;
         }
         for (size_t i = 0; i < nodes.size(); ++i) {
             if (!write(nodes[i])) {
                return false;
             }
         }
         if (android_log_write_list_end(mContext) < 0) {
            return false;
         }
         return true;
    }
    return false;
}

bool LogEvent::write(const AttributionNodeInternal& node) {
    if (mContext) {
         if (android_log_write_list_begin(mContext) < 0) {
            return false;
         }
         if (android_log_write_int32(mContext, node.uid()) < 0) {
            return false;
         }
         if (android_log_write_string8(mContext, node.tag().c_str()) < 0) {
            return false;
         }
         if (android_log_write_list_end(mContext) < 0) {
            return false;
         }
         return true;
    }
    return false;
}

void LogEvent::parseInt32(int32_t* pos, int32_t depth, bool* last) {
    int32_t value = readNextValue<int32_t>();
    addToValues(pos, depth, value, last);
}

void LogEvent::parseInt64(int32_t* pos, int32_t depth, bool* last) {
    int64_t value = readNextValue<int64_t>();
    addToValues(pos, depth, value, last);
}

void LogEvent::parseString(int32_t* pos, int32_t depth, bool* last) {
    int32_t numBytes = readNextValue<int32_t>();
    if ((uint32_t)numBytes > mRemainingLen) {
        mValid = false;
        return;
    }

    string value = string((char*)mBuf, numBytes);
    mBuf += numBytes;
    mRemainingLen -= numBytes;
    addToValues(pos, depth, value, last);
}

void LogEvent::parseFloat(int32_t* pos, int32_t depth, bool* last) {
    float value = readNextValue<float>();
    addToValues(pos, depth, value, last);
}

void LogEvent::parseBool(int32_t* pos, int32_t depth, bool* last) {
    // cast to int32_t because FieldValue does not support bools
    int32_t value = (int32_t)readNextValue<uint8_t>();
    addToValues(pos, depth, value, last);
}

void LogEvent::parseByteArray(int32_t* pos, int32_t depth, bool* last) {
    int32_t numBytes = readNextValue<int32_t>();
    if ((uint32_t)numBytes > mRemainingLen) {
        mValid = false;
        return;
    }

    vector<uint8_t> value(mBuf, mBuf + numBytes);
    mBuf += numBytes;
    mRemainingLen -= numBytes;
    addToValues(pos, depth, value, last);
}

void LogEvent::parseKeyValuePairs(int32_t* pos, int32_t depth, bool* last) {
    int32_t numPairs = readNextValue<uint8_t>();

    for (pos[1] = 1; pos[1] <= numPairs; pos[1]++) {
        last[1] = (pos[1] == numPairs);

        // parse key
        pos[2] = 1;
        parseInt32(pos, 2, last);

        // parse value
        last[2] = true;
        uint8_t typeId = getTypeId(readNextValue<uint8_t>());
        switch (typeId) {
            case INT32_TYPE:
                pos[2] = 2; // pos[2] determined by index of type in KeyValuePair in atoms.proto
                parseInt32(pos, 2, last);
                break;
            case INT64_TYPE:
                pos[2] = 3;
                parseInt64(pos, 2, last);
                break;
            case STRING_TYPE:
                pos[2] = 4;
                parseString(pos, 2, last);
                break;
            case FLOAT_TYPE:
                pos[2] = 5;
                parseFloat(pos, 2, last);
                break;
            default:
                mValid = false;
        }
    }

    pos[1] = pos[2] = 1;
    last[1] = last[2] = false;
}

void LogEvent::parseAttributionChain(int32_t* pos, int32_t depth, bool* last) {
    int32_t numNodes = readNextValue<uint8_t>();
    for (pos[1] = 1; pos[1] <= numNodes; pos[1]++) {
        last[1] = (pos[1] == numNodes);

        // parse uid
        pos[2] = 1;
        parseInt32(pos, 2, last);

        // parse tag
        pos[2] = 2;
        last[2] = true;
        parseString(pos, 2, last);
    }

    pos[1] = pos[2] = 1;
    last[1] = last[2] = false;
}


// This parsing logic is tied to the encoding scheme used in StatsEvent.java and
// stats_event.c
void LogEvent::initNew() {
    int32_t pos[] = {1, 1, 1};
    bool last[] = {false, false, false};

    // Beginning of buffer is OBJECT_TYPE | NUM_FIELDS | TIMESTAMP | ATOM_ID
    uint8_t typeInfo = readNextValue<uint8_t>();
    if (getTypeId(typeInfo) != OBJECT_TYPE) mValid = false;

    uint8_t numElements = readNextValue<uint8_t>();
    if (numElements < 2 || numElements > 127) mValid = false;

    typeInfo = readNextValue<uint8_t>();
    if (getTypeId(typeInfo) != INT64_TYPE) mValid = false;
    mElapsedTimestampNs = readNextValue<int64_t>();
    numElements--;

    typeInfo = readNextValue<uint8_t>();
    if (getTypeId(typeInfo) != INT32_TYPE) mValid = false;
    mTagId = readNextValue<int32_t>();
    numElements--;


    for (pos[0] = 1; pos[0] <= numElements && mValid; pos[0]++) {
        typeInfo = readNextValue<uint8_t>();
        uint8_t typeId = getTypeId(typeInfo);

        last[0] = (pos[0] == numElements);

        // TODO(b/144373276): handle errors passed to the socket
        // TODO(b/144373257): parse annotations
        switch(typeId) {
            case BOOL_TYPE:
                parseBool(pos, 0, last);
                break;
            case INT32_TYPE:
                parseInt32(pos, 0, last);
                break;
            case INT64_TYPE:
                parseInt64(pos, 0, last);
                break;
            case FLOAT_TYPE:
                parseFloat(pos, 0, last);
                break;
            case BYTE_ARRAY_TYPE:
                parseByteArray(pos, 0, last);
                break;
            case STRING_TYPE:
                parseString(pos, 0, last);
                break;
            case KEY_VALUE_PAIRS_TYPE:
                parseKeyValuePairs(pos, 0, last);
                break;
            case ATTRIBUTION_CHAIN_TYPE:
                parseAttributionChain(pos, 0, last);
                break;
            default:
                mValid = false;
        }
    }

    if (mRemainingLen != 0) mValid = false;
    mBuf = nullptr;
}

uint8_t LogEvent::getTypeId(uint8_t typeInfo) {
    return typeInfo & 0x0F; // type id in lower 4 bytes
}

uint8_t LogEvent::getNumAnnotations(uint8_t typeInfo) {
    return (typeInfo >> 4) & 0x0F;
}

/**
 * The elements of each log event are stored as a vector of android_log_list_elements.
 * The goal is to do as little preprocessing as possible, because we read a tiny fraction
 * of the elements that are written to the log.
 *
 * The idea here is to read through the log items once, we get as much information we need for
 * matching as possible. Because this log will be matched against lots of matchers.
 */
void LogEvent::init(android_log_context context) {
    android_log_list_element elem;
    int i = 0;
    int depth = -1;
    int pos[] = {1, 1, 1};
    bool isKeyValuePairAtom = false;
    do {
        elem = android_log_read_next(context);
        switch ((int)elem.type) {
            case EVENT_TYPE_INT:
                // elem at [0] is EVENT_TYPE_LIST, [1] is the timestamp, [2] is tag id.
                if (i == 2) {
                    mTagId = elem.data.int32;
                    isKeyValuePairAtom = (mTagId == android::util::KEY_VALUE_PAIRS_ATOM);
                } else {
                    if (depth < 0 || depth > 2) {
                        return;
                    }

                    mValues.push_back(
                            FieldValue(Field(mTagId, pos, depth), Value((int32_t)elem.data.int32)));

                    pos[depth]++;
                }
                break;
            case EVENT_TYPE_FLOAT: {
                if (depth < 0 || depth > 2) {
                    ALOGE("Depth > 2. Not supported!");
                    return;
                }

                // Handles the oneof field in KeyValuePair atom.
                if (isKeyValuePairAtom && depth == 2) {
                    pos[depth] = 5;
                }

                mValues.push_back(FieldValue(Field(mTagId, pos, depth), Value(elem.data.float32)));

                pos[depth]++;

            } break;
            case EVENT_TYPE_STRING: {
                if (depth < 0 || depth > 2) {
                    ALOGE("Depth > 2. Not supported!");
                    return;
                }

                // Handles the oneof field in KeyValuePair atom.
                if (isKeyValuePairAtom && depth == 2) {
                    pos[depth] = 4;
                }
                mValues.push_back(FieldValue(Field(mTagId, pos, depth),
                                             Value(string(elem.data.string, elem.len))));

                pos[depth]++;

            } break;
            case EVENT_TYPE_LONG: {
                if (i == 1) {
                    mElapsedTimestampNs = elem.data.int64;
                } else {
                    if (depth < 0 || depth > 2) {
                        ALOGE("Depth > 2. Not supported!");
                        return;
                    }
                    // Handles the oneof field in KeyValuePair atom.
                    if (isKeyValuePairAtom && depth == 2) {
                        pos[depth] = 3;
                    }
                    mValues.push_back(
                            FieldValue(Field(mTagId, pos, depth), Value((int64_t)elem.data.int64)));

                    pos[depth]++;
                }
            } break;
            case EVENT_TYPE_LIST:
                depth++;
                if (depth > 2) {
                    ALOGE("Depth > 2. Not supported!");
                    return;
                }
                pos[depth] = 1;

                break;
            case EVENT_TYPE_LIST_STOP: {
                int prevDepth = depth;
                depth--;
                if (depth >= 0 && depth < 2) {
                    // Now go back to decorate the previous items that are last at prevDepth.
                    // So that we can later easily match them with Position=Last matchers.
                    pos[prevDepth]--;
                    int path = getEncodedField(pos, prevDepth, false);
                    for (auto it = mValues.rbegin(); it != mValues.rend(); ++it) {
                        if (it->mField.getDepth() >= prevDepth &&
                            it->mField.getPath(prevDepth) == path) {
                            it->mField.decorateLastPos(prevDepth);
                        } else {
                            // Safe to break, because the items are in DFS order.
                            break;
                        }
                    }
                    pos[depth]++;
                }
                break;
            }
            case EVENT_TYPE_UNKNOWN:
                break;
            default:
                break;
        }
        i++;
    } while ((elem.type != EVENT_TYPE_UNKNOWN) && !elem.complete);
    if (isKeyValuePairAtom && mValues.size() > 0) {
        mValues[0] = FieldValue(Field(android::util::KEY_VALUE_PAIRS_ATOM, getSimpleField(1)),
                                Value((int32_t)mLogUid));
    }
}

int64_t LogEvent::GetLong(size_t key, status_t* err) const {
    // TODO(b/110561208): encapsulate the magical operations in Field struct as static functions
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == LONG) {
                return value.mValue.long_value;
            } else if (value.mValue.getType() == INT) {
                return value.mValue.int_value;
            } else {
                *err = BAD_TYPE;
                return 0;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return 0;
}

int LogEvent::GetInt(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == INT) {
                return value.mValue.int_value;
            } else {
                *err = BAD_TYPE;
                return 0;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return 0;
}

const char* LogEvent::GetString(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == STRING) {
                return value.mValue.str_value.c_str();
            } else {
                *err = BAD_TYPE;
                return 0;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return NULL;
}

bool LogEvent::GetBool(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == INT) {
                return value.mValue.int_value != 0;
            } else if (value.mValue.getType() == LONG) {
                return value.mValue.long_value != 0;
            } else {
                *err = BAD_TYPE;
                return false;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return false;
}

float LogEvent::GetFloat(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == FLOAT) {
                return value.mValue.float_value;
            } else {
                *err = BAD_TYPE;
                return 0.0;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return 0.0;
}

std::vector<uint8_t> LogEvent::GetStorage(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
      if (value.mField.getField() == field) {
        if (value.mValue.getType() == STORAGE) {
          return value.mValue.storage_value;
        } else {
          *err = BAD_TYPE;
          return vector<uint8_t>();
        }
      }
      if ((size_t)value.mField.getPosAtDepth(0) > key) {
        break;
      }
    }

    *err = BAD_INDEX;
    return vector<uint8_t>();
}

string LogEvent::ToString() const {
    string result;
    result += StringPrintf("{ uid(%d) %lld %lld (%d)", mLogUid, (long long)mLogdTimestampNs,
                           (long long)mElapsedTimestampNs, mTagId);
    for (const auto& value : mValues) {
        result +=
                StringPrintf("%#x", value.mField.getField()) + "->" + value.mValue.toString() + " ";
    }
    result += " }";
    return result;
}

void LogEvent::ToProto(ProtoOutputStream& protoOutput) const {
    writeFieldValueTreeToStream(mTagId, getValues(), &protoOutput);
}

void writeExperimentIdsToProto(const std::vector<int64_t>& experimentIds,
                               std::vector<uint8_t>* protoOut) {
    ProtoOutputStream proto;
    for (const auto& expId : experimentIds) {
        proto.write(FIELD_TYPE_INT64 | FIELD_COUNT_REPEATED | FIELD_ID_EXPERIMENT_ID,
                    (long long)expId);
    }

    protoOut->resize(proto.size());
    size_t pos = 0;
    sp<ProtoReader> reader = proto.data();
    while (reader->readBuffer() != NULL) {
        size_t toRead = reader->currentToRead();
        std::memcpy(protoOut->data() + pos, reader->readBuffer(), toRead);
        pos += toRead;
        reader->move(toRead);
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
