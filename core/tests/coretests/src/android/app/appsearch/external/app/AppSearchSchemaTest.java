/*
 * Copyright 2020 The Android Open Source Project
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

package android.app.appsearch;


import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.app.appsearch.AppSearchSchema.PropertyConfig;
import android.app.appsearch.exceptions.IllegalSchemaException;

import org.junit.Test;

public class AppSearchSchemaTest {
    @Test
    public void testInvalidEnums() {
        PropertyConfig.Builder builder = new PropertyConfig.Builder("test");
        expectThrows(IllegalArgumentException.class, () -> builder.setDataType(99));
        expectThrows(IllegalArgumentException.class, () -> builder.setCardinality(99));
    }

    @Test
    public void testMissingFields() {
        PropertyConfig.Builder builder = new PropertyConfig.Builder("test");
        IllegalSchemaException e = expectThrows(IllegalSchemaException.class, builder::build);
        assertThat(e).hasMessageThat().contains("Missing field: dataType");

        builder.setDataType(PropertyConfig.DATA_TYPE_DOCUMENT);
        e = expectThrows(IllegalSchemaException.class, builder::build);
        assertThat(e).hasMessageThat().contains("Missing field: schemaType");

        builder.setSchemaType("TestType");
        e = expectThrows(IllegalSchemaException.class, builder::build);
        assertThat(e).hasMessageThat().contains("Missing field: cardinality");

        builder.setCardinality(PropertyConfig.CARDINALITY_REPEATED);
        builder.build();
    }

    @Test
    public void testDuplicateProperties() {
        AppSearchSchema.Builder builder = new AppSearchSchema.Builder("Email")
                .addProperty(new PropertyConfig.Builder("subject")
                        .setDataType(PropertyConfig.DATA_TYPE_STRING)
                        .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                        .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                        .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                        .build()
                );
        IllegalSchemaException e = expectThrows(IllegalSchemaException.class,
                () -> builder.addProperty(new PropertyConfig.Builder("subject")
                        .setDataType(PropertyConfig.DATA_TYPE_STRING)
                        .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                        .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                        .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                        .build()));
        assertThat(e).hasMessageThat().contains("Property defined more than once: subject");
    }
}
