/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <fstream>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "android-base/file.h"
#include "androidfw/ApkAssets.h"
#include "androidfw/Idmap.h"
#include "androidfw/LoadedArsc.h"

#include "idmap2/CommandLineOptions.h"
#include "idmap2/Idmap.h"

#include "TestHelpers.h"

namespace android::idmap2 {

TEST(CommandLineOptionsTests, Flag) {
  bool foo = true;
  bool bar = false;
  CommandLineOptions opts =
      CommandLineOptions("test").OptionalFlag("--foo", "", &foo).OptionalFlag("--bar", "", &bar);

  std::ostream fakeStdErr(nullptr);
  bool success = opts.Parse({"--foo", "--bar"}, fakeStdErr);
  ASSERT_TRUE(success);
  ASSERT_TRUE(foo);
  ASSERT_TRUE(bar);

  foo = bar = false;
  success = opts.Parse({"--foo"}, fakeStdErr);
  ASSERT_TRUE(success);
  ASSERT_TRUE(foo);
  ASSERT_FALSE(bar);
}

TEST(CommandLineOptionsTests, MandatoryOption) {
  std::string foo;
  std::string bar;
  CommandLineOptions opts = CommandLineOptions("test")
                                .MandatoryOption("--foo", "", &foo)
                                .MandatoryOption("--bar", "", &bar);
  std::ostream fakeStdErr(nullptr);
  bool success = opts.Parse({"--foo", "FOO", "--bar", "BAR"}, fakeStdErr);
  ASSERT_TRUE(success);
  ASSERT_EQ(foo, "FOO");
  ASSERT_EQ(bar, "BAR");

  success = opts.Parse({"--foo"}, fakeStdErr);
  ASSERT_FALSE(success);
}

TEST(CommandLineOptionsTests, MandatoryOptionMultipleArgsButExpectedOnce) {
  std::string foo;
  CommandLineOptions opts = CommandLineOptions("test").MandatoryOption("--foo", "", &foo);
  std::ostream fakeStdErr(nullptr);
  bool success = opts.Parse({"--foo", "FIRST", "--foo", "SECOND"}, fakeStdErr);
  ASSERT_TRUE(success);
  ASSERT_EQ(foo, "SECOND");
}

TEST(CommandLineOptionsTests, MandatoryOptionMultipleArgsAndExpectedOnceOrMore) {
  std::vector<std::string> args;
  CommandLineOptions opts = CommandLineOptions("test").MandatoryOption("--foo", "", &args);
  std::ostream fakeStdErr(nullptr);
  bool success = opts.Parse({"--foo", "FOO", "--foo", "BAR"}, fakeStdErr);
  ASSERT_TRUE(success);
  ASSERT_EQ(args.size(), 2U);
  ASSERT_EQ(args[0], "FOO");
  ASSERT_EQ(args[1], "BAR");
}

TEST(CommandLineOptionsTests, OptionalOption) {
  std::string foo;
  std::string bar;
  CommandLineOptions opts = CommandLineOptions("test")
                                .OptionalOption("--foo", "", &foo)
                                .OptionalOption("--bar", "", &bar);
  std::ostream fakeStdErr(nullptr);
  bool success = opts.Parse({"--foo", "FOO", "--bar", "BAR"}, fakeStdErr);
  ASSERT_TRUE(success);
  ASSERT_EQ(foo, "FOO");
  ASSERT_EQ(bar, "BAR");

  success = opts.Parse({"--foo", "BAZ"}, fakeStdErr);
  ASSERT_TRUE(success);
  ASSERT_EQ(foo, "BAZ");

  success = opts.Parse({"--foo"}, fakeStdErr);
  ASSERT_FALSE(success);

  success = opts.Parse({"--foo", "--bar", "BAR"}, fakeStdErr);
  ASSERT_FALSE(success);

  success = opts.Parse({"--foo", "FOO", "--bar"}, fakeStdErr);
  ASSERT_FALSE(success);
}

TEST(CommandLineOptionsTests, CornerCases) {
  std::string foo;
  std::string bar;
  bool baz = false;
  CommandLineOptions opts = CommandLineOptions("test")
                                .MandatoryOption("--foo", "", &foo)
                                .OptionalFlag("--baz", "", &baz)
                                .OptionalOption("--bar", "", &bar);
  std::ostream fakeStdErr(nullptr);
  bool success = opts.Parse({"--unexpected"}, fakeStdErr);
  ASSERT_FALSE(success);

  success = opts.Parse({"--bar", "BAR"}, fakeStdErr);
  ASSERT_FALSE(success);

  success = opts.Parse({"--baz", "--foo", "FOO"}, fakeStdErr);
  ASSERT_TRUE(success);
  ASSERT_TRUE(baz);
  ASSERT_EQ(foo, "FOO");
}

TEST(CommandLineOptionsTests, ConvertArgvToVector) {
  const char* argv[] = {
      "program-name",
      "--foo",
      "FOO",
      nullptr,
  };
  std::unique_ptr<std::vector<std::string>> v = CommandLineOptions::ConvertArgvToVector(3, argv);
  ASSERT_EQ(v->size(), 2UL);
  ASSERT_EQ((*v)[0], "--foo");
  ASSERT_EQ((*v)[1], "FOO");
}

TEST(CommandLineOptionsTests, ConvertArgvToVectorNoArgs) {
  const char* argv[] = {
      "program-name",
      nullptr,
  };
  std::unique_ptr<std::vector<std::string>> v = CommandLineOptions::ConvertArgvToVector(1, argv);
  ASSERT_EQ(v->size(), 0UL);
}

TEST(CommandLineOptionsTests, Usage) {
  std::string arg1;
  std::string arg2;
  std::string arg3;
  std::string arg4;
  bool arg5 = false;
  bool arg6 = false;
  std::vector<std::string> arg7;
  CommandLineOptions opts = CommandLineOptions("test")
                                .MandatoryOption("--aa", "description-aa", &arg1)
                                .OptionalFlag("--bb", "description-bb", &arg5)
                                .OptionalOption("--cc", "description-cc", &arg2)
                                .OptionalOption("--dd", "description-dd", &arg3)
                                .MandatoryOption("--ee", "description-ee", &arg4)
                                .OptionalFlag("--ff", "description-ff", &arg6)
                                .MandatoryOption("--gg", "description-gg", &arg7);
  std::stringstream stream;
  opts.Usage(stream);
  const std::string s = stream.str();
  ASSERT_NE(s.find("usage: test --aa arg [--bb] [--cc arg] [--dd arg] --ee arg [--ff] --gg arg "
                   "[--gg arg [..]]"),
            std::string::npos);
  ASSERT_NE(s.find("--aa arg    description-aa"), std::string::npos);
  ASSERT_NE(s.find("--ff        description-ff"), std::string::npos);
  ASSERT_NE(s.find("--gg arg    description-gg (can be provided multiple times)"),
            std::string::npos);
}

}  // namespace android::idmap2
