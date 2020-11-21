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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.appsearch.exceptions.AppSearchException;
import android.os.Bundle;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * Represents a document unit.
 *
 * <p>Documents are constructed via {@link GenericDocument.Builder}.
 *
 * @see AppSearchManager#putDocuments
 * @see AppSearchManager#getByUri
 * @see AppSearchManager#query
 */
public class GenericDocument {
    private static final String TAG = "GenericDocument";

    /** The default empty namespace. */
    public static final String DEFAULT_NAMESPACE = "";

    /**
     * The maximum number of elements in a repeatable field. Will reject the request if exceed this
     * limit.
     */
    private static final int MAX_REPEATED_PROPERTY_LENGTH = 100;

    /**
     * The maximum {@link String#length} of a {@link String} field. Will reject the request if
     * {@link String}s longer than this.
     */
    private static final int MAX_STRING_LENGTH = 20_000;

    /** The maximum number of indexed properties a document can have. */
    private static final int MAX_INDEXED_PROPERTIES = 16;

    /** The default score of document. */
    private static final int DEFAULT_SCORE = 0;

    /** The default time-to-live in millisecond of a document, which is infinity. */
    private static final long DEFAULT_TTL_MILLIS = 0L;

    private static final String PROPERTIES_FIELD = "properties";
    private static final String BYTE_ARRAY_FIELD = "byteArray";
    private static final String SCHEMA_TYPE_FIELD = "schemaType";
    private static final String URI_FIELD = "uri";
    private static final String SCORE_FIELD = "score";
    private static final String TTL_MILLIS_FIELD = "ttlMillis";
    private static final String CREATION_TIMESTAMP_MILLIS_FIELD = "creationTimestampMillis";
    private static final String NAMESPACE_FIELD = "namespace";

    /**
     * The maximum number of indexed properties a document can have.
     *
     * <p>Indexed properties are properties where the {@link
     * AppSearchSchema.PropertyConfig#getIndexingType()} constant is anything other than {@link
     * AppSearchSchema.PropertyConfig.IndexingType#INDEXING_TYPE_NONE}.
     */
    public static int getMaxIndexedProperties() {
        return MAX_INDEXED_PROPERTIES;
    }

    /** Contains {@link GenericDocument} basic information (uri, schemaType etc). */
    @NonNull final Bundle mBundle;

    /**
     * Contains all properties in {@link GenericDocument} to support getting properties via keys.
     */
    @NonNull private final Bundle mProperties;

    @NonNull private final String mUri;
    @NonNull private final String mSchemaType;
    private final long mCreationTimestampMillis;
    @Nullable private Integer mHashCode;

    /**
     * Rebuilds a {@link GenericDocument} by the a bundle.
     *
     * @param bundle Contains {@link GenericDocument} basic information (uri, schemaType etc) and a
     *     properties bundle contains all properties in {@link GenericDocument} to support getting
     *     properties via keys.
     * @hide
     */
    public GenericDocument(@NonNull Bundle bundle) {
        Preconditions.checkNotNull(bundle);
        mBundle = bundle;
        mProperties = Preconditions.checkNotNull(bundle.getParcelable(PROPERTIES_FIELD));
        mUri = Preconditions.checkNotNull(mBundle.getString(URI_FIELD));
        mSchemaType = Preconditions.checkNotNull(mBundle.getString(SCHEMA_TYPE_FIELD));
        mCreationTimestampMillis =
                mBundle.getLong(CREATION_TIMESTAMP_MILLIS_FIELD, System.currentTimeMillis());
    }

    /**
     * Creates a new {@link GenericDocument} from an existing instance.
     *
     * <p>This method should be only used by constructor of a subclass.
     */
    protected GenericDocument(@NonNull GenericDocument document) {
        this(document.mBundle);
    }

    /**
     * Returns the {@link Bundle} populated by this builder.
     *
     * @hide
     */
    @NonNull
    public Bundle getBundle() {
        return mBundle;
    }

    /** Returns the URI of the {@link GenericDocument}. */
    @NonNull
    public String getUri() {
        return mUri;
    }

    /** Returns the namespace of the {@link GenericDocument}. */
    @NonNull
    public String getNamespace() {
        return mBundle.getString(NAMESPACE_FIELD, DEFAULT_NAMESPACE);
    }

    /** Returns the schema type of the {@link GenericDocument}. */
    @NonNull
    public String getSchemaType() {
        return mSchemaType;
    }

    /**
     * Returns the creation timestamp of the {@link GenericDocument}, in milliseconds.
     *
     * <p>The value is in the {@link System#currentTimeMillis} time base.
     */
    public long getCreationTimestampMillis() {
        return mCreationTimestampMillis;
    }

    /**
     * Returns the TTL (Time To Live) of the {@link GenericDocument}, in milliseconds.
     *
     * <p>The TTL is measured against {@link #getCreationTimestampMillis}. At the timestamp of
     * {@code creationTimestampMillis + ttlMillis}, measured in the {@link System#currentTimeMillis}
     * time base, the document will be auto-deleted.
     *
     * <p>The default value is 0, which means the document is permanent and won't be auto-deleted
     * until the app is uninstalled.
     */
    public long getTtlMillis() {
        return mBundle.getLong(TTL_MILLIS_FIELD, DEFAULT_TTL_MILLIS);
    }

    /**
     * Returns the score of the {@link GenericDocument}.
     *
     * <p>The score is a query-independent measure of the document's quality, relative to other
     * {@link GenericDocument}s of the same type.
     *
     * <p>Results may be sorted by score using {@link SearchSpec.Builder#setRankingStrategy}.
     * Documents with higher scores are considered better than documents with lower scores.
     *
     * <p>Any nonnegative integer can be used a score.
     */
    public int getScore() {
        return mBundle.getInt(SCORE_FIELD, DEFAULT_SCORE);
    }

    /** Returns the names of all properties defined in this document. */
    @NonNull
    public Set<String> getPropertyNames() {
        return Collections.unmodifiableSet(mProperties.keySet());
    }

    /**
     * Retrieves a {@link String} value by key.
     *
     * @param key The key to look for.
     * @return The first {@link String} associated with the given key or {@code null} if there is no
     *     such key or the value is of a different type.
     */
    @Nullable
    public String getPropertyString(@NonNull String key) {
        Preconditions.checkNotNull(key);
        String[] propertyArray = getPropertyStringArray(key);
        if (propertyArray == null || propertyArray.length == 0) {
            return null;
        }
        warnIfSinglePropertyTooLong("String", key, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@code long} value by key.
     *
     * @param key The key to look for.
     * @return The first {@code long} associated with the given key or default value {@code 0} if
     *     there is no such key or the value is of a different type.
     */
    public long getPropertyLong(@NonNull String key) {
        Preconditions.checkNotNull(key);
        long[] propertyArray = getPropertyLongArray(key);
        if (propertyArray == null || propertyArray.length == 0) {
            return 0;
        }
        warnIfSinglePropertyTooLong("Long", key, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@code double} value by key.
     *
     * @param key The key to look for.
     * @return The first {@code double} associated with the given key or default value {@code 0.0}
     *     if there is no such key or the value is of a different type.
     */
    public double getPropertyDouble(@NonNull String key) {
        Preconditions.checkNotNull(key);
        double[] propertyArray = getPropertyDoubleArray(key);
        if (propertyArray == null || propertyArray.length == 0) {
            return 0.0;
        }
        warnIfSinglePropertyTooLong("Double", key, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@code boolean} value by key.
     *
     * @param key The key to look for.
     * @return The first {@code boolean} associated with the given key or default value {@code
     *     false} if there is no such key or the value is of a different type.
     */
    public boolean getPropertyBoolean(@NonNull String key) {
        Preconditions.checkNotNull(key);
        boolean[] propertyArray = getPropertyBooleanArray(key);
        if (propertyArray == null || propertyArray.length == 0) {
            return false;
        }
        warnIfSinglePropertyTooLong("Boolean", key, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@code byte[]} value by key.
     *
     * @param key The key to look for.
     * @return The first {@code byte[]} associated with the given key or {@code null} if there is no
     *     such key or the value is of a different type.
     */
    @Nullable
    public byte[] getPropertyBytes(@NonNull String key) {
        Preconditions.checkNotNull(key);
        byte[][] propertyArray = getPropertyBytesArray(key);
        if (propertyArray == null || propertyArray.length == 0) {
            return null;
        }
        warnIfSinglePropertyTooLong("ByteArray", key, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@link GenericDocument} value by key.
     *
     * @param key The key to look for.
     * @return The first {@link GenericDocument} associated with the given key or {@code null} if
     *     there is no such key or the value is of a different type.
     */
    @Nullable
    public GenericDocument getPropertyDocument(@NonNull String key) {
        Preconditions.checkNotNull(key);
        GenericDocument[] propertyArray = getPropertyDocumentArray(key);
        if (propertyArray == null || propertyArray.length == 0) {
            return null;
        }
        warnIfSinglePropertyTooLong("Document", key, propertyArray.length);
        return propertyArray[0];
    }

    /** Prints a warning to logcat if the given propertyLength is greater than 1. */
    private static void warnIfSinglePropertyTooLong(
            @NonNull String propertyType, @NonNull String key, int propertyLength) {
        if (propertyLength > 1) {
            Log.w(
                    TAG,
                    "The value for \""
                            + key
                            + "\" contains "
                            + propertyLength
                            + " elements. Only the first one will be returned from "
                            + "getProperty"
                            + propertyType
                            + "(). Try getProperty"
                            + propertyType
                            + "Array().");
        }
    }

    /**
     * Retrieves a repeated {@code String} property by key.
     *
     * @param key The key to look for.
     * @return The {@code String[]} associated with the given key, or {@code null} if no value is
     *     set or the value is of a different type.
     */
    @Nullable
    public String[] getPropertyStringArray(@NonNull String key) {
        Preconditions.checkNotNull(key);
        return getAndCastPropertyArray(key, String[].class);
    }

    /**
     * Retrieves a repeated {@link String} property by key.
     *
     * @param key The key to look for.
     * @return The {@code long[]} associated with the given key, or {@code null} if no value is set
     *     or the value is of a different type.
     */
    @Nullable
    public long[] getPropertyLongArray(@NonNull String key) {
        Preconditions.checkNotNull(key);
        return getAndCastPropertyArray(key, long[].class);
    }

    /**
     * Retrieves a repeated {@code double} property by key.
     *
     * @param key The key to look for.
     * @return The {@code double[]} associated with the given key, or {@code null} if no value is
     *     set or the value is of a different type.
     */
    @Nullable
    public double[] getPropertyDoubleArray(@NonNull String key) {
        Preconditions.checkNotNull(key);
        return getAndCastPropertyArray(key, double[].class);
    }

    /**
     * Retrieves a repeated {@code boolean} property by key.
     *
     * @param key The key to look for.
     * @return The {@code boolean[]} associated with the given key, or {@code null} if no value is
     *     set or the value is of a different type.
     */
    @Nullable
    public boolean[] getPropertyBooleanArray(@NonNull String key) {
        Preconditions.checkNotNull(key);
        return getAndCastPropertyArray(key, boolean[].class);
    }

    /**
     * Retrieves a {@code byte[][]} property by key.
     *
     * @param key The key to look for.
     * @return The {@code byte[][]} associated with the given key, or {@code null} if no value is
     *     set or the value is of a different type.
     */
    @SuppressLint("ArrayReturn")
    @Nullable
    @SuppressWarnings("unchecked")
    public byte[][] getPropertyBytesArray(@NonNull String key) {
        Preconditions.checkNotNull(key);
        ArrayList<Bundle> bundles = getAndCastPropertyArray(key, ArrayList.class);
        if (bundles == null || bundles.size() == 0) {
            return null;
        }
        byte[][] bytes = new byte[bundles.size()][];
        for (int i = 0; i < bundles.size(); i++) {
            Bundle bundle = bundles.get(i);
            if (bundle == null) {
                Log.e(TAG, "The inner bundle is null at " + i + ", for key: " + key);
                continue;
            }
            byte[] innerBytes = bundle.getByteArray(BYTE_ARRAY_FIELD);
            if (innerBytes == null) {
                Log.e(TAG, "The bundle at " + i + " contains a null byte[].");
                continue;
            }
            bytes[i] = innerBytes;
        }
        return bytes;
    }

    /**
     * Retrieves a repeated {@link GenericDocument} property by key.
     *
     * @param key The key to look for.
     * @return The {@link GenericDocument}[] associated with the given key, or {@code null} if no
     *     value is set or the value is of a different type.
     */
    @SuppressLint("ArrayReturn")
    @Nullable
    public GenericDocument[] getPropertyDocumentArray(@NonNull String key) {
        Preconditions.checkNotNull(key);
        Bundle[] bundles = getAndCastPropertyArray(key, Bundle[].class);
        if (bundles == null || bundles.length == 0) {
            return null;
        }
        GenericDocument[] documents = new GenericDocument[bundles.length];
        for (int i = 0; i < bundles.length; i++) {
            if (bundles[i] == null) {
                Log.e(TAG, "The inner bundle is null at " + i + ", for key: " + key);
                continue;
            }
            documents[i] = new GenericDocument(bundles[i]);
        }
        return documents;
    }

    /**
     * Gets a repeated property of the given key, and casts it to the given class type, which must
     * be an array class type.
     */
    @Nullable
    private <T> T getAndCastPropertyArray(@NonNull String key, @NonNull Class<T> tClass) {
        Object value = mProperties.get(key);
        if (value == null) {
            return null;
        }
        try {
            return tClass.cast(value);
        } catch (ClassCastException e) {
            Log.w(TAG, "Error casting to requested type for key \"" + key + "\"", e);
            return null;
        }
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GenericDocument)) {
            return false;
        }
        GenericDocument otherDocument = (GenericDocument) other;
        return bundleEquals(this.mBundle, otherDocument.mBundle);
    }

    /**
     * Deeply checks whether two bundles are equal.
     *
     * <p>Two bundles will be considered equal if they contain the same content.
     */
    @SuppressWarnings("unchecked")
    private static boolean bundleEquals(Bundle one, Bundle two) {
        if (one.size() != two.size()) {
            return false;
        }
        Set<String> keySetOne = one.keySet();
        Object valueOne;
        Object valueTwo;
        // Bundle inherit its equals() from Object.java, which only compare their memory address.
        // We should iterate all keys and check their presents and values in both bundle.
        for (String key : keySetOne) {
            valueOne = one.get(key);
            valueTwo = two.get(key);
            if (valueOne instanceof Bundle
                    && valueTwo instanceof Bundle
                    && !bundleEquals((Bundle) valueOne, (Bundle) valueTwo)) {
                return false;
            } else if (valueOne == null && (valueTwo != null || !two.containsKey(key))) {
                // If we call bundle.get(key) when the 'key' doesn't actually exist in the
                // bundle, we'll get back a null. So make sure that both values are null and
                // both keys exist in the bundle.
                return false;
            } else if (valueOne instanceof boolean[]) {
                if (!(valueTwo instanceof boolean[])
                        || !Arrays.equals((boolean[]) valueOne, (boolean[]) valueTwo)) {
                    return false;
                }
            } else if (valueOne instanceof long[]) {
                if (!(valueTwo instanceof long[])
                        || !Arrays.equals((long[]) valueOne, (long[]) valueTwo)) {
                    return false;
                }
            } else if (valueOne instanceof double[]) {
                if (!(valueTwo instanceof double[])
                        || !Arrays.equals((double[]) valueOne, (double[]) valueTwo)) {
                    return false;
                }
            } else if (valueOne instanceof Bundle[]) {
                if (!(valueTwo instanceof Bundle[])) {
                    return false;
                }
                Bundle[] bundlesOne = (Bundle[]) valueOne;
                Bundle[] bundlesTwo = (Bundle[]) valueTwo;
                if (bundlesOne.length != bundlesTwo.length) {
                    return false;
                }
                for (int i = 0; i < bundlesOne.length; i++) {
                    if (!bundleEquals(bundlesOne[i], bundlesTwo[i])) {
                        return false;
                    }
                }
            } else if (valueOne instanceof ArrayList) {
                if (!(valueTwo instanceof ArrayList)) {
                    return false;
                }
                ArrayList<Bundle> bundlesOne = (ArrayList<Bundle>) valueOne;
                ArrayList<Bundle> bundlesTwo = (ArrayList<Bundle>) valueTwo;
                if (bundlesOne.size() != bundlesTwo.size()) {
                    return false;
                }
                for (int i = 0; i < bundlesOne.size(); i++) {
                    if (!bundleEquals(bundlesOne.get(i), bundlesTwo.get(i))) {
                        return false;
                    }
                }
            } else if (valueOne instanceof Object[]) {
                if (!(valueTwo instanceof Object[])
                        || !Arrays.equals((Object[]) valueOne, (Object[]) valueTwo)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        if (mHashCode == null) {
            mHashCode = bundleHashCode(mBundle);
        }
        return mHashCode;
    }

    /**
     * Calculates the hash code for a bundle.
     *
     * <p>The hash code is only effected by the contents in the bundle. Bundles will get consistent
     * hash code if they have same contents.
     */
    @SuppressWarnings("unchecked")
    private static int bundleHashCode(Bundle bundle) {
        int[] hashCodes = new int[bundle.size()];
        int i = 0;
        // Bundle inherit its hashCode() from Object.java, which only relative to their memory
        // address. Bundle doesn't have an order, so we should iterate all keys and combine
        // their value's hashcode into an array. And use the hashcode of the array to be
        // the hashcode of the bundle.
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (value instanceof boolean[]) {
                hashCodes[i++] = Arrays.hashCode((boolean[]) value);
            } else if (value instanceof long[]) {
                hashCodes[i++] = Arrays.hashCode((long[]) value);
            } else if (value instanceof double[]) {
                hashCodes[i++] = Arrays.hashCode((double[]) value);
            } else if (value instanceof String[]) {
                hashCodes[i++] = Arrays.hashCode((Object[]) value);
            } else if (value instanceof Bundle) {
                hashCodes[i++] = bundleHashCode((Bundle) value);
            } else if (value instanceof Bundle[]) {
                Bundle[] bundles = (Bundle[]) value;
                int[] innerHashCodes = new int[bundles.length];
                for (int j = 0; j < innerHashCodes.length; j++) {
                    innerHashCodes[j] = bundleHashCode(bundles[j]);
                }
                hashCodes[i++] = Arrays.hashCode(innerHashCodes);
            } else if (value instanceof ArrayList) {
                ArrayList<Bundle> bundles = (ArrayList<Bundle>) value;
                int[] innerHashCodes = new int[bundles.size()];
                for (int j = 0; j < innerHashCodes.length; j++) {
                    innerHashCodes[j] = bundleHashCode(bundles.get(j));
                }
                hashCodes[i++] = Arrays.hashCode(innerHashCodes);
            } else {
                hashCodes[i++] = value.hashCode();
            }
        }
        return Arrays.hashCode(hashCodes);
    }

    @Override
    @NonNull
    public String toString() {
        return bundleToString(mBundle).toString();
    }

    @SuppressWarnings("unchecked")
    private static StringBuilder bundleToString(Bundle bundle) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            final Set<String> keySet = bundle.keySet();
            String[] keys = keySet.toArray(new String[0]);
            // Sort keys to make output deterministic. We need a custom comparator to handle
            // nulls (arbitrarily putting them first, similar to Comparator.nullsFirst, which is
            // only available since N).
            Arrays.sort(
                    keys,
                    (@Nullable String s1, @Nullable String s2) -> {
                        if (s1 == null) {
                            return s2 == null ? 0 : -1;
                        } else if (s2 == null) {
                            return 1;
                        } else {
                            return s1.compareTo(s2);
                        }
                    });
            for (String key : keys) {
                stringBuilder.append("{ key: '").append(key).append("' value: ");
                Object valueObject = bundle.get(key);
                if (valueObject == null) {
                    stringBuilder.append("<null>");
                } else if (valueObject instanceof Bundle) {
                    stringBuilder.append(bundleToString((Bundle) valueObject));
                } else if (valueObject.getClass().isArray()) {
                    stringBuilder.append("[ ");
                    for (int i = 0; i < Array.getLength(valueObject); i++) {
                        Object element = Array.get(valueObject, i);
                        stringBuilder.append("'");
                        if (element instanceof Bundle) {
                            stringBuilder.append(bundleToString((Bundle) element));
                        } else {
                            stringBuilder.append(Array.get(valueObject, i));
                        }
                        stringBuilder.append("' ");
                    }
                    stringBuilder.append("]");
                } else if (valueObject instanceof ArrayList) {
                    for (Bundle innerBundle : (ArrayList<Bundle>) valueObject) {
                        stringBuilder.append(bundleToString(innerBundle));
                    }
                } else {
                    stringBuilder.append(valueObject.toString());
                }
                stringBuilder.append(" } ");
            }
        } catch (RuntimeException e) {
            // Catch any exceptions here since corrupt Bundles can throw different types of
            // exceptions (e.g. b/38445840 & b/68937025).
            stringBuilder.append("<error>");
        }
        return stringBuilder;
    }

    /**
     * The builder class for {@link GenericDocument}.
     *
     * @param <BuilderType> Type of subclass who extends this.
     */
    // This builder is specifically designed to be extended by classes deriving from
    // GenericDocument.
    @SuppressLint("StaticFinalBuilder")
    public static class Builder<BuilderType extends Builder> {

        private final Bundle mProperties = new Bundle();
        private final Bundle mBundle = new Bundle();
        private final BuilderType mBuilderTypeInstance;
        private boolean mBuilt = false;

        /**
         * Create a new {@link GenericDocument.Builder}.
         *
         * @param uri The uri of {@link GenericDocument}.
         * @param schemaType The schema type of the {@link GenericDocument}. The passed-in {@code
         *     schemaType} must be defined using {@link AppSearchManager#setSchema} prior to
         *     inserting a document of this {@code schemaType} into the AppSearch index using {@link
         *     AppSearchManager#putDocuments}. Otherwise, the document will be rejected by {@link
         *     AppSearchManager#putDocuments}.
         */
        @SuppressWarnings("unchecked")
        public Builder(@NonNull String uri, @NonNull String schemaType) {
            Preconditions.checkNotNull(uri);
            Preconditions.checkNotNull(schemaType);
            mBuilderTypeInstance = (BuilderType) this;
            mBundle.putString(GenericDocument.URI_FIELD, uri);
            mBundle.putString(GenericDocument.SCHEMA_TYPE_FIELD, schemaType);
            mBundle.putString(GenericDocument.NAMESPACE_FIELD, DEFAULT_NAMESPACE);
            // Set current timestamp for creation timestamp by default.
            mBundle.putLong(
                    GenericDocument.CREATION_TIMESTAMP_MILLIS_FIELD, System.currentTimeMillis());
            mBundle.putLong(GenericDocument.TTL_MILLIS_FIELD, DEFAULT_TTL_MILLIS);
            mBundle.putInt(GenericDocument.SCORE_FIELD, DEFAULT_SCORE);
            mBundle.putBundle(PROPERTIES_FIELD, mProperties);
        }

        /**
         * Sets the app-defined namespace this Document resides in. No special values are reserved
         * or understood by the infrastructure.
         *
         * <p>URIs are unique within a namespace.
         *
         * <p>The number of namespaces per app should be kept small for efficiency reasons.
         */
        @NonNull
        public BuilderType setNamespace(@NonNull String namespace) {
            mBundle.putString(GenericDocument.NAMESPACE_FIELD, namespace);
            return mBuilderTypeInstance;
        }

        /**
         * Sets the score of the {@link GenericDocument}.
         *
         * <p>The score is a query-independent measure of the document's quality, relative to other
         * {@link GenericDocument}s of the same type.
         *
         * <p>Results may be sorted by score using {@link SearchSpec.Builder#setRankingStrategy}.
         * Documents with higher scores are considered better than documents with lower scores.
         *
         * <p>Any nonnegative integer can be used a score.
         *
         * @throws IllegalArgumentException If the provided value is negative.
         */
        @NonNull
        public BuilderType setScore(@IntRange(from = 0, to = Integer.MAX_VALUE) int score) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            if (score < 0) {
                throw new IllegalArgumentException("Document score cannot be negative.");
            }
            mBundle.putInt(GenericDocument.SCORE_FIELD, score);
            return mBuilderTypeInstance;
        }

        /**
         * Sets the creation timestamp of the {@link GenericDocument}, in milliseconds.
         *
         * <p>Should be set using a value obtained from the {@link System#currentTimeMillis} time
         * base.
         */
        @NonNull
        public BuilderType setCreationTimestampMillis(long creationTimestampMillis) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBundle.putLong(
                    GenericDocument.CREATION_TIMESTAMP_MILLIS_FIELD, creationTimestampMillis);
            return mBuilderTypeInstance;
        }

        /**
         * Sets the TTL (Time To Live) of the {@link GenericDocument}, in milliseconds.
         *
         * <p>The TTL is measured against {@link #getCreationTimestampMillis}. At the timestamp of
         * {@code creationTimestampMillis + ttlMillis}, measured in the {@link
         * System#currentTimeMillis} time base, the document will be auto-deleted.
         *
         * <p>The default value is 0, which means the document is permanent and won't be
         * auto-deleted until the app is uninstalled.
         *
         * @param ttlMillis A non-negative duration in milliseconds.
         * @throws IllegalArgumentException If the provided value is negative.
         */
        @NonNull
        public BuilderType setTtlMillis(long ttlMillis) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            if (ttlMillis < 0) {
                throw new IllegalArgumentException("Document ttlMillis cannot be negative.");
            }
            mBundle.putLong(GenericDocument.TTL_MILLIS_FIELD, ttlMillis);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code String} values for a property, replacing its previous values.
         *
         * @param key The key associated with the {@code values}.
         * @param values The {@code String} values of the property.
         */
        @NonNull
        public BuilderType setPropertyString(@NonNull String key, @NonNull String... values) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            Preconditions.checkNotNull(values);
            putInPropertyBundle(key, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code boolean} values for a property, replacing its previous
         * values.
         *
         * @param key The key associated with the {@code values}.
         * @param values The {@code boolean} values of the property.
         */
        @NonNull
        public BuilderType setPropertyBoolean(@NonNull String key, @NonNull boolean... values) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            Preconditions.checkNotNull(values);
            putInPropertyBundle(key, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code long} values for a property, replacing its previous values.
         *
         * @param key The key associated with the {@code values}.
         * @param values The {@code long} values of the property.
         */
        @NonNull
        public BuilderType setPropertyLong(@NonNull String key, @NonNull long... values) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            Preconditions.checkNotNull(values);
            putInPropertyBundle(key, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code double} values for a property, replacing its previous values.
         *
         * @param key The key associated with the {@code values}.
         * @param values The {@code double} values of the property.
         */
        @NonNull
        public BuilderType setPropertyDouble(@NonNull String key, @NonNull double... values) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            Preconditions.checkNotNull(values);
            putInPropertyBundle(key, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code byte[]} for a property, replacing its previous values.
         *
         * @param key The key associated with the {@code values}.
         * @param values The {@code byte[]} of the property.
         */
        @NonNull
        public BuilderType setPropertyBytes(@NonNull String key, @NonNull byte[]... values) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            Preconditions.checkNotNull(values);
            putInPropertyBundle(key, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@link GenericDocument} values for a property, replacing its
         * previous values.
         *
         * @param key The key associated with the {@code values}.
         * @param values The {@link GenericDocument} values of the property.
         */
        @NonNull
        public BuilderType setPropertyDocument(
                @NonNull String key, @NonNull GenericDocument... values) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            Preconditions.checkNotNull(values);
            putInPropertyBundle(key, values);
            return mBuilderTypeInstance;
        }

        private void putInPropertyBundle(@NonNull String key, @NonNull String[] values)
                throws IllegalArgumentException {
            validateRepeatedPropertyLength(key, values.length);
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    throw new IllegalArgumentException("The String at " + i + " is null.");
                } else if (values[i].length() > MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException(
                            "The String at "
                                    + i
                                    + " length is: "
                                    + values[i].length()
                                    + ", which exceeds length limit: "
                                    + MAX_STRING_LENGTH
                                    + ".");
                }
            }
            mProperties.putStringArray(key, values);
        }

        private void putInPropertyBundle(@NonNull String key, @NonNull boolean[] values) {
            validateRepeatedPropertyLength(key, values.length);
            mProperties.putBooleanArray(key, values);
        }

        private void putInPropertyBundle(@NonNull String key, @NonNull double[] values) {
            validateRepeatedPropertyLength(key, values.length);
            mProperties.putDoubleArray(key, values);
        }

        private void putInPropertyBundle(@NonNull String key, @NonNull long[] values) {
            validateRepeatedPropertyLength(key, values.length);
            mProperties.putLongArray(key, values);
        }

        /**
         * Converts and saves a byte[][] into {@link #mProperties}.
         *
         * <p>Bundle doesn't support for two dimension array byte[][], we are converting byte[][]
         * into ArrayList<Bundle>, and each elements will contain a one dimension byte[].
         */
        private void putInPropertyBundle(@NonNull String key, @NonNull byte[][] values) {
            validateRepeatedPropertyLength(key, values.length);
            ArrayList<Bundle> bundles = new ArrayList<>(values.length);
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    throw new IllegalArgumentException("The byte[] at " + i + " is null.");
                }
                Bundle bundle = new Bundle();
                bundle.putByteArray(BYTE_ARRAY_FIELD, values[i]);
                bundles.add(bundle);
            }
            mProperties.putParcelableArrayList(key, bundles);
        }

        private void putInPropertyBundle(@NonNull String key, @NonNull GenericDocument[] values) {
            validateRepeatedPropertyLength(key, values.length);
            Bundle[] documentBundles = new Bundle[values.length];
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    throw new IllegalArgumentException("The document at " + i + " is null.");
                }
                documentBundles[i] = values[i].mBundle;
            }
            mProperties.putParcelableArray(key, documentBundles);
        }

        private static void validateRepeatedPropertyLength(@NonNull String key, int length) {
            if (length == 0) {
                throw new IllegalArgumentException("The input array is empty.");
            } else if (length > MAX_REPEATED_PROPERTY_LENGTH) {
                throw new IllegalArgumentException(
                        "Repeated property \""
                                + key
                                + "\" has length "
                                + length
                                + ", which exceeds the limit of "
                                + MAX_REPEATED_PROPERTY_LENGTH);
            }
        }

        /** Builds the {@link GenericDocument} object. */
        @NonNull
        public GenericDocument build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBuilt = true;
            return new GenericDocument(mBundle);
        }
    }
}
