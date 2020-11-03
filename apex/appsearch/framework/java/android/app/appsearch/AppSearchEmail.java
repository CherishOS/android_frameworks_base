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


import android.annotation.NonNull;
import android.annotation.Nullable;

import android.app.appsearch.AppSearchSchema.PropertyConfig;

/**
 * Encapsulates a {@link GenericDocument} that represent an email.
 *
 * <p>This class is a higher level implement of {@link GenericDocument}.
 *
 * @hide
 */

public class AppSearchEmail extends GenericDocument {
    /** The name of the schema type for {@link AppSearchEmail} documents.*/
    public static final String SCHEMA_TYPE = "builtin:Email";

    private static final String KEY_FROM = "from";
    private static final String KEY_TO = "to";
    private static final String KEY_CC = "cc";
    private static final String KEY_BCC = "bcc";
    private static final String KEY_SUBJECT = "subject";
    private static final String KEY_BODY = "body";

    public static final AppSearchSchema SCHEMA = new AppSearchSchema.Builder(SCHEMA_TYPE)
            .addProperty(new PropertyConfig.Builder(KEY_FROM)
                    .setDataType(PropertyConfig.DATA_TYPE_STRING)
                    .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).addProperty(new PropertyConfig.Builder(KEY_TO)
                    .setDataType(PropertyConfig.DATA_TYPE_STRING)
                    .setCardinality(PropertyConfig.CARDINALITY_REPEATED)
                    .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).addProperty(new PropertyConfig.Builder(KEY_CC)
                    .setDataType(PropertyConfig.DATA_TYPE_STRING)
                    .setCardinality(PropertyConfig.CARDINALITY_REPEATED)
                    .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).addProperty(new PropertyConfig.Builder(KEY_BCC)
                    .setDataType(PropertyConfig.DATA_TYPE_STRING)
                    .setCardinality(PropertyConfig.CARDINALITY_REPEATED)
                    .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).addProperty(new PropertyConfig.Builder(KEY_SUBJECT)
                    .setDataType(PropertyConfig.DATA_TYPE_STRING)
                    .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).addProperty(new PropertyConfig.Builder(KEY_BODY)
                    .setDataType(PropertyConfig.DATA_TYPE_STRING)
                    .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).build();

    /**
     * Creates a new {@link AppSearchEmail} from the contents of an existing
     * {@link GenericDocument}.
     *
     * @param document The {@link GenericDocument} containing the email content.
     */
    public AppSearchEmail(@NonNull GenericDocument document) {
        super(document);
    }

    /**
     * Gets the from address of {@link AppSearchEmail}.
     *
     * @return The subject of {@link AppSearchEmail} or {@code null} if it's not been set yet.
     */
    @Nullable
    public String getFrom() {
        return getPropertyString(KEY_FROM);
    }

    /**
     * Gets the destination addresses of {@link AppSearchEmail}.
     *
     * @return The destination addresses of {@link AppSearchEmail} or {@code null} if it's not
     *         been set yet.
     */
    @Nullable
    public String[] getTo() {
        return getPropertyStringArray(KEY_TO);
    }

    /**
     * Gets the CC list of {@link AppSearchEmail}.
     *
     * @return The CC list of {@link AppSearchEmail} or {@code null} if it's not been set yet.
     */
    @Nullable
    public String[] getCc() {
        return getPropertyStringArray(KEY_CC);
    }

    /**
     * Gets the BCC list of {@link AppSearchEmail}.
     *
     * @return The BCC list of {@link AppSearchEmail} or {@code null} if it's not been set yet.
     */
    @Nullable
    public String[] getBcc() {
        return getPropertyStringArray(KEY_BCC);
    }

    /**
     * Gets the subject of {@link AppSearchEmail}.
     *
     * @return The value subject of {@link AppSearchEmail} or {@code null} if it's not been set yet.
     */
    @Nullable
    public String getSubject() {
        return getPropertyString(KEY_SUBJECT);
    }

    /**
     * Gets the body of {@link AppSearchEmail}.
     *
     * @return The body of {@link AppSearchEmail} or {@code null} if it's not been set yet.
     */
    @Nullable
    public String getBody() {
        return getPropertyString(KEY_BODY);
    }

    /**
     * The builder class for {@link AppSearchEmail}.
     */
    public static class Builder extends GenericDocument.Builder<AppSearchEmail.Builder> {

        /**
         * Creates a new {@link AppSearchEmail.Builder}
         *
         * @param uri The Uri of the Email.
         */
        public Builder(@NonNull String uri) {
            super(uri, SCHEMA_TYPE);
        }

        /**
         * Sets the from address of {@link AppSearchEmail}
         */
        @NonNull
        public AppSearchEmail.Builder setFrom(@NonNull String from) {
            setPropertyString(KEY_FROM, from);
            return this;
        }

        /**
         * Sets the destination address of {@link AppSearchEmail}
         */
        @NonNull
        public AppSearchEmail.Builder setTo(@NonNull String... to) {
            setPropertyString(KEY_TO, to);
            return this;
        }

        /**
         * Sets the CC list of {@link AppSearchEmail}
         */
        @NonNull
        public AppSearchEmail.Builder setCc(@NonNull String... cc) {
            setPropertyString(KEY_CC, cc);
            return this;
        }

        /**
         * Sets the BCC list of {@link AppSearchEmail}
         */
        @NonNull
        public AppSearchEmail.Builder setBcc(@NonNull String... bcc) {
            setPropertyString(KEY_BCC, bcc);
            return this;
        }

        /**
         * Sets the subject of {@link AppSearchEmail}
         */
        @NonNull
        public AppSearchEmail.Builder setSubject(@NonNull String subject) {
            setPropertyString(KEY_SUBJECT, subject);
            return this;
        }

        /**
         * Sets the body of {@link AppSearchEmail}
         */
        @NonNull
        public AppSearchEmail.Builder setBody(@NonNull String body) {
            setPropertyString(KEY_BODY, body);
            return this;
        }

        /** Builds the {@link AppSearchEmail} object. */
        @NonNull
        @Override
        public AppSearchEmail build() {
            return new AppSearchEmail(super.build());
        }
    }
}
