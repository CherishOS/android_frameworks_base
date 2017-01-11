/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "androidfw/AssetManager2.h"
#include "androidfw/AssetManager.h"

#include "android-base/logging.h"

#include "TestHelpers.h"
#include "data/basic/R.h"
#include "data/styles/R.h"

namespace basic = com::android::basic;
namespace app = com::android::app;

namespace android {

class AssetManager2Test : public ::testing::Test {
 public:
  void SetUp() override {
    basic_assets_ = ApkAssets::Load(GetTestDataPath() + "/basic/basic.apk");
    ASSERT_NE(nullptr, basic_assets_);

    basic_de_fr_assets_ = ApkAssets::Load(GetTestDataPath() + "/basic/basic_de_fr.apk");
    ASSERT_NE(nullptr, basic_de_fr_assets_);

    style_assets_ = ApkAssets::Load(GetTestDataPath() + "/styles/styles.apk");
    ASSERT_NE(nullptr, style_assets_);
  }

 protected:
  std::unique_ptr<ApkAssets> basic_assets_;
  std::unique_ptr<ApkAssets> basic_de_fr_assets_;
  std::unique_ptr<ApkAssets> style_assets_;
};

TEST_F(AssetManager2Test, FindsResourcesFromSingleApkAssets) {
  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));
  desired_config.language[0] = 'd';
  desired_config.language[1] = 'e';

  AssetManager2 assetmanager;
  assetmanager.SetConfiguration(desired_config);
  assetmanager.SetApkAssets({basic_assets_.get()});

  Res_value value;
  ResTable_config selected_config;
  uint32_t flags;

  ApkAssetsCookie cookie =
      assetmanager.GetResource(basic::R::string::test1, false /*may_be_bag*/,
                               0 /*density_override*/, &value, &selected_config, &flags);
  ASSERT_NE(kInvalidCookie, cookie);

  // Came from our ApkAssets.
  EXPECT_EQ(0, cookie);

  // It is the default config.
  EXPECT_EQ(0, selected_config.language[0]);
  EXPECT_EQ(0, selected_config.language[1]);

  // It is a string.
  EXPECT_EQ(Res_value::TYPE_STRING, value.dataType);
}

TEST_F(AssetManager2Test, FindsResourcesFromMultipleApkAssets) {
  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));
  desired_config.language[0] = 'd';
  desired_config.language[1] = 'e';

  AssetManager2 assetmanager;
  assetmanager.SetConfiguration(desired_config);
  assetmanager.SetApkAssets({basic_assets_.get(), basic_de_fr_assets_.get()});

  Res_value value;
  ResTable_config selected_config;
  uint32_t flags;

  ApkAssetsCookie cookie =
      assetmanager.GetResource(basic::R::string::test1, false /*may_be_bag*/,
                               0 /*density_override*/, &value, &selected_config, &flags);
  ASSERT_NE(kInvalidCookie, cookie);

  // Came from our de_fr ApkAssets.
  EXPECT_EQ(1, cookie);

  // The configuration is german.
  EXPECT_EQ('d', selected_config.language[0]);
  EXPECT_EQ('e', selected_config.language[1]);

  // It is a string.
  EXPECT_EQ(Res_value::TYPE_STRING, value.dataType);
}

TEST_F(AssetManager2Test, FindsBagResourcesFromSingleApkAssets) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_.get()});

  const ResolvedBag* bag = assetmanager.GetBag(basic::R::array::integerArray1);
  ASSERT_NE(nullptr, bag);
  ASSERT_EQ(3u, bag->entry_count);

  EXPECT_EQ(static_cast<uint8_t>(Res_value::TYPE_INT_DEC), bag->entries[0].value.dataType);
  EXPECT_EQ(1u, bag->entries[0].value.data);
  EXPECT_EQ(0, bag->entries[0].cookie);

  EXPECT_EQ(static_cast<uint8_t>(Res_value::TYPE_INT_DEC), bag->entries[1].value.dataType);
  EXPECT_EQ(2u, bag->entries[1].value.data);
  EXPECT_EQ(0, bag->entries[1].cookie);

  EXPECT_EQ(static_cast<uint8_t>(Res_value::TYPE_INT_DEC), bag->entries[2].value.dataType);
  EXPECT_EQ(3u, bag->entries[2].value.data);
  EXPECT_EQ(0, bag->entries[2].cookie);
}

TEST_F(AssetManager2Test, MergesStylesWithParentFromSingleApkAssets) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({style_assets_.get()});

  const ResolvedBag* bag_one = assetmanager.GetBag(app::R::style::StyleOne);
  ASSERT_NE(nullptr, bag_one);
  ASSERT_EQ(2u, bag_one->entry_count);

  EXPECT_EQ(app::R::attr::attr_one, bag_one->entries[0].key);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, bag_one->entries[0].value.dataType);
  EXPECT_EQ(1u, bag_one->entries[0].value.data);
  EXPECT_EQ(0, bag_one->entries[0].cookie);

  EXPECT_EQ(app::R::attr::attr_two, bag_one->entries[1].key);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, bag_one->entries[1].value.dataType);
  EXPECT_EQ(2u, bag_one->entries[1].value.data);
  EXPECT_EQ(0, bag_one->entries[1].cookie);

  const ResolvedBag* bag_two = assetmanager.GetBag(app::R::style::StyleTwo);
  ASSERT_NE(nullptr, bag_two);
  ASSERT_EQ(5u, bag_two->entry_count);

  // attr_one is inherited from StyleOne.
  EXPECT_EQ(app::R::attr::attr_one, bag_two->entries[0].key);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, bag_two->entries[0].value.dataType);
  EXPECT_EQ(1u, bag_two->entries[0].value.data);
  EXPECT_EQ(0, bag_two->entries[0].cookie);

  // attr_two should be overridden from StyleOne by StyleTwo.
  EXPECT_EQ(app::R::attr::attr_two, bag_two->entries[1].key);
  EXPECT_EQ(Res_value::TYPE_STRING, bag_two->entries[1].value.dataType);
  EXPECT_EQ(0, bag_two->entries[1].cookie);
  EXPECT_EQ(std::string("string"), GetStringFromPool(assetmanager.GetStringPoolForCookie(0),
                                                     bag_two->entries[1].value.data));

  // The rest are new attributes.

  EXPECT_EQ(app::R::attr::attr_three, bag_two->entries[2].key);
  EXPECT_EQ(Res_value::TYPE_ATTRIBUTE, bag_two->entries[2].value.dataType);
  EXPECT_EQ(app::R::attr::attr_indirect, bag_two->entries[2].value.data);
  EXPECT_EQ(0, bag_two->entries[2].cookie);

  EXPECT_EQ(app::R::attr::attr_five, bag_two->entries[3].key);
  EXPECT_EQ(Res_value::TYPE_REFERENCE, bag_two->entries[3].value.dataType);
  EXPECT_EQ(app::R::string::string_one, bag_two->entries[3].value.data);
  EXPECT_EQ(0, bag_two->entries[3].cookie);

  EXPECT_EQ(app::R::attr::attr_indirect, bag_two->entries[4].key);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, bag_two->entries[4].value.dataType);
  EXPECT_EQ(3u, bag_two->entries[4].value.data);
  EXPECT_EQ(0, bag_two->entries[4].cookie);
}

TEST_F(AssetManager2Test, FindsBagResourcesFromMultipleApkAssets) {}

TEST_F(AssetManager2Test, OpensFileFromSingleApkAssets) {}

TEST_F(AssetManager2Test, OpensFileFromMultipleApkAssets) {}

}  // namespace android
