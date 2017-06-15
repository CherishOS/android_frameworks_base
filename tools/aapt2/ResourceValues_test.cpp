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

#include "ResourceValues.h"

#include "test/Test.h"

namespace aapt {

TEST(ResourceValuesTest, PluralEquals) {
  StringPool pool;

  Plural a;
  a.values[Plural::One] = util::make_unique<String>(pool.MakeRef("one"));
  a.values[Plural::Other] = util::make_unique<String>(pool.MakeRef("other"));

  Plural b;
  b.values[Plural::One] = util::make_unique<String>(pool.MakeRef("une"));
  b.values[Plural::Other] = util::make_unique<String>(pool.MakeRef("autre"));

  Plural c;
  c.values[Plural::One] = util::make_unique<String>(pool.MakeRef("one"));
  c.values[Plural::Other] = util::make_unique<String>(pool.MakeRef("other"));

  EXPECT_FALSE(a.Equals(&b));
  EXPECT_TRUE(a.Equals(&c));
}

TEST(ResourceValuesTest, PluralClone) {
  StringPool pool;

  Plural a;
  a.values[Plural::One] = util::make_unique<String>(pool.MakeRef("one"));
  a.values[Plural::Other] = util::make_unique<String>(pool.MakeRef("other"));

  std::unique_ptr<Plural> b(a.Clone(&pool));
  EXPECT_TRUE(a.Equals(b.get()));
}

TEST(ResourceValuesTest, ArrayEquals) {
  StringPool pool;

  Array a;
  a.items.push_back(util::make_unique<String>(pool.MakeRef("one")));
  a.items.push_back(util::make_unique<String>(pool.MakeRef("two")));

  Array b;
  b.items.push_back(util::make_unique<String>(pool.MakeRef("une")));
  b.items.push_back(util::make_unique<String>(pool.MakeRef("deux")));

  Array c;
  c.items.push_back(util::make_unique<String>(pool.MakeRef("uno")));

  Array d;
  d.items.push_back(util::make_unique<String>(pool.MakeRef("one")));
  d.items.push_back(util::make_unique<String>(pool.MakeRef("two")));

  EXPECT_FALSE(a.Equals(&b));
  EXPECT_FALSE(a.Equals(&c));
  EXPECT_FALSE(b.Equals(&c));
  EXPECT_TRUE(a.Equals(&d));
}

TEST(ResourceValuesTest, ArrayClone) {
  StringPool pool;

  Array a;
  a.items.push_back(util::make_unique<String>(pool.MakeRef("one")));
  a.items.push_back(util::make_unique<String>(pool.MakeRef("two")));

  std::unique_ptr<Array> b(a.Clone(&pool));
  EXPECT_TRUE(a.Equals(b.get()));
}

TEST(ResourceValuesTest, StyleEquals) {
  StringPool pool;

  std::unique_ptr<Style> a = test::StyleBuilder()
      .SetParent("android:style/Parent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bar", ResourceUtils::TryParseInt("2"))
      .Build();

  std::unique_ptr<Style> b = test::StyleBuilder()
      .SetParent("android:style/Parent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bar", ResourceUtils::TryParseInt("3"))
      .Build();

  std::unique_ptr<Style> c = test::StyleBuilder()
      .SetParent("android:style/NoParent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bar", ResourceUtils::TryParseInt("2"))
      .Build();

  std::unique_ptr<Style> d = test::StyleBuilder()
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bar", ResourceUtils::TryParseInt("2"))
      .Build();

  std::unique_ptr<Style> e = test::StyleBuilder()
      .SetParent("android:style/Parent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bat", ResourceUtils::TryParseInt("2"))
      .Build();

  std::unique_ptr<Style> f = test::StyleBuilder()
      .SetParent("android:style/Parent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .Build();

  std::unique_ptr<Style> g = test::StyleBuilder()
      .SetParent("android:style/Parent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bar", ResourceUtils::TryParseInt("2"))
      .Build();

  EXPECT_FALSE(a->Equals(b.get()));
  EXPECT_FALSE(a->Equals(c.get()));
  EXPECT_FALSE(a->Equals(d.get()));
  EXPECT_FALSE(a->Equals(e.get()));
  EXPECT_FALSE(a->Equals(f.get()));

  EXPECT_TRUE(a->Equals(g.get()));
}

TEST(ResourceValuesTest, StyleClone) {
  std::unique_ptr<Style> a = test::StyleBuilder()
      .SetParent("android:style/Parent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bar", ResourceUtils::TryParseInt("2"))
      .Build();

  std::unique_ptr<Style> b(a->Clone(nullptr));
  EXPECT_TRUE(a->Equals(b.get()));
}

TEST(ResourceValuesTest, StyleMerges) {
  StringPool pool_a;
  StringPool pool_b;

  std::unique_ptr<Style> a =
      test::StyleBuilder()
          .SetParent("android:style/Parent")
          .AddItem("android:attr/a", util::make_unique<String>(pool_a.MakeRef("FooA")))
          .AddItem("android:attr/b", util::make_unique<String>(pool_a.MakeRef("FooB")))
          .Build();

  std::unique_ptr<Style> b =
      test::StyleBuilder()
          .SetParent("android:style/OverlayParent")
          .AddItem("android:attr/c", util::make_unique<String>(pool_b.MakeRef("OverlayFooC")))
          .AddItem("android:attr/a", util::make_unique<String>(pool_b.MakeRef("OverlayFooA")))
          .Build();

  a->MergeWith(b.get(), &pool_a);

  StringPool pool;
  std::unique_ptr<Style> expected =
      test::StyleBuilder()
          .SetParent("android:style/OverlayParent")
          .AddItem("android:attr/a", util::make_unique<String>(pool.MakeRef("OverlayFooA")))
          .AddItem("android:attr/b", util::make_unique<String>(pool.MakeRef("FooB")))
          .AddItem("android:attr/c", util::make_unique<String>(pool.MakeRef("OverlayFooC")))
          .Build();

  EXPECT_TRUE(a->Equals(expected.get()));
}

// TYPE_NULL is encoded as TYPE_REFERENCE with a value of 0. This is represented in AAPT2
// by a default constructed Reference value.
TEST(ResourcesValuesTest, EmptyReferenceFlattens) {
  android::Res_value value = {};
  ASSERT_TRUE(Reference().Flatten(&value));

  EXPECT_EQ(android::Res_value::TYPE_REFERENCE, value.dataType);
  EXPECT_EQ(0x0u, value.data);
}

TEST(ResourcesValuesTest, AttributeMatches) {
  constexpr const uint32_t TYPE_DIMENSION = android::ResTable_map::TYPE_DIMENSION;
  constexpr const uint32_t TYPE_ENUM = android::ResTable_map::TYPE_ENUM;
  constexpr const uint32_t TYPE_FLAGS = android::ResTable_map::TYPE_FLAGS;
  constexpr const uint32_t TYPE_INTEGER = android::ResTable_map::TYPE_INTEGER;
  constexpr const uint8_t TYPE_INT_DEC = android::Res_value::TYPE_INT_DEC;

  Attribute attr1(false /*weak*/, TYPE_DIMENSION);
  EXPECT_FALSE(attr1.Matches(*ResourceUtils::TryParseColor("#7fff00")));
  EXPECT_TRUE(attr1.Matches(*ResourceUtils::TryParseFloat("23dp")));
  EXPECT_TRUE(attr1.Matches(*ResourceUtils::TryParseReference("@android:string/foo")));

  Attribute attr2(false /*weak*/, TYPE_INTEGER | TYPE_ENUM);
  attr2.min_int = 0;
  attr2.symbols.push_back(Attribute::Symbol{Reference(test::ParseNameOrDie("android:id/foo")),
                                            static_cast<uint32_t>(-1)});
  EXPECT_FALSE(attr2.Matches(*ResourceUtils::TryParseColor("#7fff00")));
  EXPECT_TRUE(attr2.Matches(BinaryPrimitive(TYPE_INT_DEC, static_cast<uint32_t>(-1))));
  EXPECT_TRUE(attr2.Matches(BinaryPrimitive(TYPE_INT_DEC, 1u)));
  EXPECT_FALSE(attr2.Matches(BinaryPrimitive(TYPE_INT_DEC, static_cast<uint32_t>(-2))));

  Attribute attr3(false /*weak*/, TYPE_INTEGER | TYPE_FLAGS);
  attr3.max_int = 100;
  attr3.symbols.push_back(
      Attribute::Symbol{Reference(test::ParseNameOrDie("android:id/foo")), 0x01u});
  attr3.symbols.push_back(
      Attribute::Symbol{Reference(test::ParseNameOrDie("android:id/bar")), 0x02u});
  attr3.symbols.push_back(
      Attribute::Symbol{Reference(test::ParseNameOrDie("android:id/baz")), 0x04u});
  attr3.symbols.push_back(
      Attribute::Symbol{Reference(test::ParseNameOrDie("android:id/bat")), 0x80u});
  EXPECT_FALSE(attr3.Matches(*ResourceUtils::TryParseColor("#7fff00")));
  EXPECT_TRUE(attr3.Matches(BinaryPrimitive(TYPE_INT_DEC, 0x01u | 0x02u)));
  EXPECT_TRUE(attr3.Matches(BinaryPrimitive(TYPE_INT_DEC, 0x01u | 0x02u | 0x80u)));

  // Not a flag, but a value less than max_int.
  EXPECT_TRUE(attr3.Matches(BinaryPrimitive(TYPE_INT_DEC, 0x08u)));

  // Not a flag and greater than max_int.
  EXPECT_FALSE(attr3.Matches(BinaryPrimitive(TYPE_INT_DEC, 127u)));

  Attribute attr4(false /*weak*/, TYPE_ENUM);
  attr4.symbols.push_back(
      Attribute::Symbol{Reference(test::ParseNameOrDie("android:id/foo")), 0x01u});
  EXPECT_TRUE(attr4.Matches(BinaryPrimitive(TYPE_INT_DEC, 0x01u)));
  EXPECT_FALSE(attr4.Matches(BinaryPrimitive(TYPE_INT_DEC, 0x02u)));
}

} // namespace aapt
