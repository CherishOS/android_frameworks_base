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

#include "src/logd/LogEvent.h"
#include <gtest/gtest.h>
#include <log/log_event_list.h>
#include "frameworks/base/cmds/statsd/src/atoms.pb.h"
#include "frameworks/base/core/proto/android/stats/launcher/launcher.pb.h"

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

using std::string;
using util::ProtoOutputStream;

TEST(LogEventTest, TestLogParsing) {
    LogEvent event1(1, 2000);

    std::vector<AttributionNodeInternal> nodes;

    AttributionNodeInternal node1;
    node1.set_uid(1000);
    node1.set_tag("tag1");
    nodes.push_back(node1);

    AttributionNodeInternal node2;
    node2.set_uid(2000);
    node2.set_tag("tag2");
    nodes.push_back(node2);

    event1.write(nodes);
    event1.write("hello");
    event1.write((int32_t)10);
    event1.write((int64_t)20);
    event1.write((float)1.1);
    event1.init();

    const auto& items = event1.getValues();
    EXPECT_EQ((size_t)8, items.size());
    EXPECT_EQ(1, event1.GetTagId());

    const FieldValue& item0 = event1.getValues()[0];
    EXPECT_EQ(0x2010101, item0.mField.getField());
    EXPECT_EQ(Type::INT, item0.mValue.getType());
    EXPECT_EQ(1000, item0.mValue.int_value);

    const FieldValue& item1 = event1.getValues()[1];
    EXPECT_EQ(0x2010182, item1.mField.getField());
    EXPECT_EQ(Type::STRING, item1.mValue.getType());
    EXPECT_EQ("tag1", item1.mValue.str_value);

    const FieldValue& item2 = event1.getValues()[2];
    EXPECT_EQ(0x2018201, item2.mField.getField());
    EXPECT_EQ(Type::INT, item2.mValue.getType());
    EXPECT_EQ(2000, item2.mValue.int_value);

    const FieldValue& item3 = event1.getValues()[3];
    EXPECT_EQ(0x2018282, item3.mField.getField());
    EXPECT_EQ(Type::STRING, item3.mValue.getType());
    EXPECT_EQ("tag2", item3.mValue.str_value);

    const FieldValue& item4 = event1.getValues()[4];
    EXPECT_EQ(0x20000, item4.mField.getField());
    EXPECT_EQ(Type::STRING, item4.mValue.getType());
    EXPECT_EQ("hello", item4.mValue.str_value);

    const FieldValue& item5 = event1.getValues()[5];
    EXPECT_EQ(0x30000, item5.mField.getField());
    EXPECT_EQ(Type::INT, item5.mValue.getType());
    EXPECT_EQ(10, item5.mValue.int_value);

    const FieldValue& item6 = event1.getValues()[6];
    EXPECT_EQ(0x40000, item6.mField.getField());
    EXPECT_EQ(Type::LONG, item6.mValue.getType());
    EXPECT_EQ((int64_t)20, item6.mValue.long_value);

    const FieldValue& item7 = event1.getValues()[7];
    EXPECT_EQ(0x50000, item7.mField.getField());
    EXPECT_EQ(Type::FLOAT, item7.mValue.getType());
    EXPECT_EQ((float)1.1, item7.mValue.float_value);
}

TEST(LogEventTest, TestLogParsing2) {
    LogEvent event1(1, 2000);

    std::vector<AttributionNodeInternal> nodes;

    event1.write("hello");

    // repeated msg can be in the middle
    AttributionNodeInternal node1;
    node1.set_uid(1000);
    node1.set_tag("tag1");
    nodes.push_back(node1);

    AttributionNodeInternal node2;
    node2.set_uid(2000);
    node2.set_tag("tag2");
    nodes.push_back(node2);
    event1.write(nodes);

    event1.write((int32_t)10);
    event1.write((int64_t)20);
    event1.write((float)1.1);
    event1.init();

    const auto& items = event1.getValues();
    EXPECT_EQ((size_t)8, items.size());
    EXPECT_EQ(1, event1.GetTagId());

    const FieldValue& item = event1.getValues()[0];
    EXPECT_EQ(0x00010000, item.mField.getField());
    EXPECT_EQ(Type::STRING, item.mValue.getType());
    EXPECT_EQ("hello", item.mValue.str_value);

    const FieldValue& item0 = event1.getValues()[1];
    EXPECT_EQ(0x2020101, item0.mField.getField());
    EXPECT_EQ(Type::INT, item0.mValue.getType());
    EXPECT_EQ(1000, item0.mValue.int_value);

    const FieldValue& item1 = event1.getValues()[2];
    EXPECT_EQ(0x2020182, item1.mField.getField());
    EXPECT_EQ(Type::STRING, item1.mValue.getType());
    EXPECT_EQ("tag1", item1.mValue.str_value);

    const FieldValue& item2 = event1.getValues()[3];
    EXPECT_EQ(0x2028201, item2.mField.getField());
    EXPECT_EQ(Type::INT, item2.mValue.getType());
    EXPECT_EQ(2000, item2.mValue.int_value);

    const FieldValue& item3 = event1.getValues()[4];
    EXPECT_EQ(0x2028282, item3.mField.getField());
    EXPECT_EQ(Type::STRING, item3.mValue.getType());
    EXPECT_EQ("tag2", item3.mValue.str_value);

    const FieldValue& item5 = event1.getValues()[5];
    EXPECT_EQ(0x30000, item5.mField.getField());
    EXPECT_EQ(Type::INT, item5.mValue.getType());
    EXPECT_EQ(10, item5.mValue.int_value);

    const FieldValue& item6 = event1.getValues()[6];
    EXPECT_EQ(0x40000, item6.mField.getField());
    EXPECT_EQ(Type::LONG, item6.mValue.getType());
    EXPECT_EQ((int64_t)20, item6.mValue.long_value);

    const FieldValue& item7 = event1.getValues()[7];
    EXPECT_EQ(0x50000, item7.mField.getField());
    EXPECT_EQ(Type::FLOAT, item7.mValue.getType());
    EXPECT_EQ((float)1.1, item7.mValue.float_value);
}


TEST(LogEventTest, TestBinaryFieldAtom) {
    Atom launcherAtom;
    auto launcher_event = launcherAtom.mutable_launcher_event();
    launcher_event->set_action(stats::launcher::LauncherAction::LONGPRESS);
    launcher_event->set_src_state(stats::launcher::LauncherState::OVERVIEW);
    launcher_event->set_dst_state(stats::launcher::LauncherState::ALLAPPS);

    auto extension = launcher_event->mutable_extension();

    auto src_target = extension->add_src_target();
    src_target->set_type(stats::launcher::LauncherTarget_Type_ITEM_TYPE);
    src_target->set_item(stats::launcher::LauncherTarget_Item_FOLDER_ICON);

    auto dst_target = extension->add_dst_target();
    dst_target->set_type(stats::launcher::LauncherTarget_Type_ITEM_TYPE);
    dst_target->set_item(stats::launcher::LauncherTarget_Item_WIDGET);

    string extension_str;
    extension->SerializeToString(&extension_str);

    LogEvent event1(Atom::kLauncherEventFieldNumber, 1000);

    event1.write((int32_t)stats::launcher::LauncherAction::LONGPRESS);
    event1.write((int32_t)stats::launcher::LauncherState::OVERVIEW);
    event1.write((int64_t)stats::launcher::LauncherState::ALLAPPS);
    event1.write(extension_str);
    event1.init();

    ProtoOutputStream proto;
    event1.ToProto(proto);

    std::vector<uint8_t> outData;
    outData.resize(proto.size());
    size_t pos = 0;
    auto iter = proto.data();
    while (iter.readBuffer() != NULL) {
        size_t toRead = iter.currentToRead();
        std::memcpy(&(outData[pos]), iter.readBuffer(), toRead);
        pos += toRead;
        iter.rp()->move(toRead);
    }

    std::string result_str(outData.begin(), outData.end());
    std::string orig_str;
    launcherAtom.SerializeToString(&orig_str);

    EXPECT_EQ(orig_str, result_str);
}

TEST(LogEventTest, TestBinaryFieldAtom_empty) {
    Atom launcherAtom;
    auto launcher_event = launcherAtom.mutable_launcher_event();
    launcher_event->set_action(stats::launcher::LauncherAction::LONGPRESS);
    launcher_event->set_src_state(stats::launcher::LauncherState::OVERVIEW);
    launcher_event->set_dst_state(stats::launcher::LauncherState::ALLAPPS);

    // empty string.
    string extension_str;

    LogEvent event1(Atom::kLauncherEventFieldNumber, 1000);

    event1.write((int32_t)stats::launcher::LauncherAction::LONGPRESS);
    event1.write((int32_t)stats::launcher::LauncherState::OVERVIEW);
    event1.write((int64_t)stats::launcher::LauncherState::ALLAPPS);
    event1.write(extension_str);
    event1.init();

    ProtoOutputStream proto;
    event1.ToProto(proto);

    std::vector<uint8_t> outData;
    outData.resize(proto.size());
    size_t pos = 0;
    auto iter = proto.data();
    while (iter.readBuffer() != NULL) {
        size_t toRead = iter.currentToRead();
        std::memcpy(&(outData[pos]), iter.readBuffer(), toRead);
        pos += toRead;
        iter.rp()->move(toRead);
    }

    std::string result_str(outData.begin(), outData.end());
    std::string orig_str;
    launcherAtom.SerializeToString(&orig_str);

    EXPECT_EQ(orig_str, result_str);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif