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

#include "BootParameters.h"

#include <gtest/gtest.h>

namespace android {

namespace {

TEST(BootParametersTest, TestParseValidParameters) {
  BootParameters boot_parameters = BootParameters();
  boot_parameters.loadParameters(R"(
    {
      "brightness":0.2,
      "volume":0.1,
      "params":{
        "key1":"value1",
        "key2":"value2"
      }
    }
  )");

  EXPECT_TRUE(boot_parameters.hasBrightness());
  EXPECT_TRUE(boot_parameters.hasVolume());
  EXPECT_FLOAT_EQ(0.2f, boot_parameters.getBrightness());
  EXPECT_FLOAT_EQ(0.1f, boot_parameters.getVolume());

  auto parameters = boot_parameters.getParameters();
  ASSERT_EQ(2u, parameters.size());
  ASSERT_STREQ(parameters[0].key, "key1");
  ASSERT_STREQ(parameters[0].value, "value1");
  ASSERT_STREQ(parameters[1].key, "key2");
  ASSERT_STREQ(parameters[1].value, "value2");
}

TEST(BootParametersTest, TestMalformedParametersAreSkipped) {
  BootParameters boot_parameters = BootParameters();
  boot_parameters.loadParameters(R"(
    {
      "brightness":0.5,
      "volume":0.5,
      "params": {
        "key1":1,
        "key2":"value2"
      }
    }
  )");

  EXPECT_TRUE(boot_parameters.hasBrightness());
  EXPECT_TRUE(boot_parameters.hasVolume());
  EXPECT_FLOAT_EQ(0.5f, boot_parameters.getBrightness());
  EXPECT_FLOAT_EQ(0.5f, boot_parameters.getVolume());

  auto parameters = boot_parameters.getParameters();
  ASSERT_EQ(1u, parameters.size());
  ASSERT_STREQ(parameters[0].key, "key2");
  ASSERT_STREQ(parameters[0].value, "value2");
}

TEST(BootParametersTest, TestMissingParametersHaveDefaults) {
  BootParameters boot_parameters = BootParameters();
  boot_parameters.loadParameters(R"(
    {
      "brightness":0.5
    }
  )");

  EXPECT_TRUE(boot_parameters.hasBrightness());
  EXPECT_FALSE(boot_parameters.hasVolume());
  EXPECT_FLOAT_EQ(0.5f, boot_parameters.getBrightness());
  EXPECT_FLOAT_EQ(-1.0f, boot_parameters.getVolume());

  auto parameters = boot_parameters.getParameters();
  ASSERT_EQ(0u, parameters.size());
}

}  // namespace

}  // namespace android
