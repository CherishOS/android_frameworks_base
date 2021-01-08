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
import android.annotation.SuppressLint;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Encapsulates a request to update the schema of an {@link AppSearchSession} database.
 *
 * @see AppSearchSession#setSchema
 */
public final class SetSchemaRequest {
    private final Set<AppSearchSchema> mSchemas;
    private final Set<String> mSchemasNotVisibleToSystemUi;
    private final Map<String, Set<PackageIdentifier>> mSchemasVisibleToPackages;
    private final boolean mForceOverride;

    SetSchemaRequest(
            @NonNull Set<AppSearchSchema> schemas,
            @NonNull Set<String> schemasNotVisibleToSystemUi,
            @NonNull Map<String, Set<PackageIdentifier>> schemasVisibleToPackages,
            boolean forceOverride) {
        mSchemas = Preconditions.checkNotNull(schemas);
        mSchemasNotVisibleToSystemUi = Preconditions.checkNotNull(schemasNotVisibleToSystemUi);
        mSchemasVisibleToPackages = Preconditions.checkNotNull(schemasVisibleToPackages);
        mForceOverride = forceOverride;
    }

    /** Returns the schemas that are part of this request. */
    @NonNull
    public Set<AppSearchSchema> getSchemas() {
        return Collections.unmodifiableSet(mSchemas);
    }

    /**
     * Returns the set of schema types that have opted out of being visible on system UI surfaces.
     */
    @NonNull
    public Set<String> getSchemasNotVisibleToSystemUi() {
        return Collections.unmodifiableSet(mSchemasNotVisibleToSystemUi);
    }

    /**
     * Returns a mapping of schema types to the set of packages that have access to that schema
     * type. Each package is represented by a {@link PackageIdentifier}. name and byte[]
     * certificate.
     *
     * <p>This method is inefficient to call repeatedly.
     */
    @NonNull
    public Map<String, Set<PackageIdentifier>> getSchemasVisibleToPackages() {
        Map<String, Set<PackageIdentifier>> copy = new ArrayMap<>();
        for (String key : mSchemasVisibleToPackages.keySet()) {
            copy.put(key, new ArraySet<>(mSchemasVisibleToPackages.get(key)));
        }
        return copy;
    }

    /**
     * Returns a mapping of schema types to the set of packages that have access to that schema
     * type. Each package is represented by a {@link PackageIdentifier}. name and byte[]
     * certificate.
     *
     * <p>A more efficient version of {@link #getSchemasVisibleToPackages}, but it returns a
     * modifiable map. This is not meant to be unhidden and should only be used by internal classes.
     *
     * @hide
     */
    @NonNull
    public Map<String, Set<PackageIdentifier>> getSchemasVisibleToPackagesInternal() {
        return mSchemasVisibleToPackages;
    }

    /** Returns whether this request will force the schema to be overridden. */
    public boolean isForceOverride() {
        return mForceOverride;
    }

    /** Builder for {@link SetSchemaRequest} objects. */
    public static final class Builder {
        private final Set<AppSearchSchema> mSchemas = new ArraySet<>();
        private final Set<String> mSchemasNotVisibleToSystemUi = new ArraySet<>();
        private final Map<String, Set<PackageIdentifier>> mSchemasVisibleToPackages =
                new ArrayMap<>();
        private boolean mForceOverride = false;
        private boolean mBuilt = false;

        /**
         * Adds one or more types to the schema.
         *
         * <p>Any documents of these types will be visible on system UI surfaces by default.
         */
        @NonNull
        public Builder addSchema(@NonNull AppSearchSchema... schemas) {
            Preconditions.checkNotNull(schemas);
            return addSchema(Arrays.asList(schemas));
        }

        /**
         * Adds one or more types to the schema.
         *
         * <p>Any documents of these types will be visible on system UI surfaces by default.
         */
        @NonNull
        public Builder addSchema(@NonNull Collection<AppSearchSchema> schemas) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(schemas);
            mSchemas.addAll(schemas);
            return this;
        }

        /**
         * Sets visibility on system UI surfaces for the given {@code schemaType}.
         *
         * @param schemaType The schema type to set visibility on.
         * @param visible Whether the {@code schemaType} will be visible or not.
         */
        // Merged list available from getSchemasNotVisibleToSystemUi
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setSchemaTypeVisibilityForSystemUi(
                @NonNull String schemaType, boolean visible) {
            Preconditions.checkNotNull(schemaType);
            Preconditions.checkState(!mBuilt, "Builder has already been used");

            if (visible) {
                mSchemasNotVisibleToSystemUi.remove(schemaType);
            } else {
                mSchemasNotVisibleToSystemUi.add(schemaType);
            }
            return this;
        }

        /**
         * Sets visibility for a package for the given {@code schemaType}.
         *
         * @param schemaType The schema type to set visibility on.
         * @param visible Whether the {@code schemaType} will be visible or not.
         * @param packageIdentifier Represents the package that will be granted visibility.
         */
        // Merged list available from getSchemasVisibleToPackages
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setSchemaTypeVisibilityForPackage(
                @NonNull String schemaType,
                boolean visible,
                @NonNull PackageIdentifier packageIdentifier) {
            Preconditions.checkNotNull(schemaType);
            Preconditions.checkNotNull(packageIdentifier);
            Preconditions.checkState(!mBuilt, "Builder has already been used");

            Set<PackageIdentifier> packageIdentifiers = mSchemasVisibleToPackages.get(schemaType);
            if (visible) {
                if (packageIdentifiers == null) {
                    packageIdentifiers = new ArraySet<>();
                }
                packageIdentifiers.add(packageIdentifier);
                mSchemasVisibleToPackages.put(schemaType, packageIdentifiers);
            } else {
                if (packageIdentifiers == null) {
                    // Return early since there was nothing set to begin with.
                    return this;
                }
                packageIdentifiers.remove(packageIdentifier);
                if (packageIdentifiers.isEmpty()) {
                    // Remove the entire key so that we don't have empty sets as values.
                    mSchemasVisibleToPackages.remove(schemaType);
                }
            }

            return this;
        }

        /**
         * Configures the {@link SetSchemaRequest} to delete any existing documents that don't
         * follow the new schema.
         *
         * <p>By default, this is {@code false} and schema incompatibility causes the {@link
         * AppSearchSession#setSchema} call to fail.
         *
         * @see AppSearchSession#setSchema
         */
        @NonNull
        public Builder setForceOverride(boolean forceOverride) {
            mForceOverride = forceOverride;
            return this;
        }

        /**
         * Builds a new {@link SetSchemaRequest}.
         *
         * @throws IllegalArgumentException If schema types were referenced, but the corresponding
         *     {@link AppSearchSchema} was never added.
         */
        @NonNull
        public SetSchemaRequest build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBuilt = true;

            // Verify that any schema types with visibility settings refer to a real schema.
            // Create a copy because we're going to remove from the set for verification purposes.
            Set<String> referencedSchemas = new ArraySet<>(mSchemasNotVisibleToSystemUi);
            referencedSchemas.addAll(mSchemasVisibleToPackages.keySet());

            for (AppSearchSchema schema : mSchemas) {
                referencedSchemas.remove(schema.getSchemaType());
            }
            if (!referencedSchemas.isEmpty()) {
                // We still have schema types that weren't seen in our mSchemas set. This means
                // there wasn't a corresponding AppSearchSchema.
                throw new IllegalArgumentException(
                        "Schema types " + referencedSchemas + " referenced, but were not added.");
            }

            return new SetSchemaRequest(
                    mSchemas,
                    mSchemasNotVisibleToSystemUi,
                    mSchemasVisibleToPackages,
                    mForceOverride);
        }
    }
}
