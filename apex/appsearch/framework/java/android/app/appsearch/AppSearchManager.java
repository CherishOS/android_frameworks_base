/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Bundle;
import android.os.ParcelableException;
import android.os.RemoteException;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class provides access to the centralized AppSearch index maintained by the system.
 *
 * <p>Apps can index structured text documents with AppSearch, which can then be retrieved through
 * the query API.
 */
// TODO(b/148046169): This class header needs a detailed example/tutorial.
@SystemService(Context.APP_SEARCH_SERVICE)
public class AppSearchManager {
    /**
     * The default empty database name.
     * @hide
     */
    public static final String DEFAULT_DATABASE_NAME = "";

    private final IAppSearchManager mService;
    private final Context mContext;

    /** @hide */
    public AppSearchManager(@NonNull Context context, @NonNull IAppSearchManager service) {
        mContext = Objects.requireNonNull(context);
        mService = Objects.requireNonNull(service);
    }

    /** Contains information about how to create the search session. */
    public static final class SearchContext {
        final String mDatabaseName;

        SearchContext(@NonNull String databaseName) {
            mDatabaseName = Objects.requireNonNull(databaseName);
        }

        /**
         * Returns the name of the database to create or open.
         *
         * <p>Databases with different names are fully separate with distinct types, namespaces,
         * and data.
         */
        @NonNull
        public String getDatabaseName() {
            return mDatabaseName;
        }

        /** Builder for {@link SearchContext} objects. */
        public static final class Builder {
            private String mDatabaseName = DEFAULT_DATABASE_NAME;
            private boolean mBuilt = false;

            /**
             * Sets the name of the database associated with {@link AppSearchSession}.
             *
             * <p>{@link AppSearchSession} will create or open a database under the given name.
             *
             * <p>Databases with different names are fully separate with distinct types, namespaces,
             * and data.
             *
             * <p>Database name cannot contain {@code '/'}.
             *
             * <p>If not specified, defaults to the empty string.
             *
             * @param databaseName The name of the database.
             * @throws IllegalArgumentException if the databaseName contains {@code '/'}.
             */
            @NonNull
            public Builder setDatabaseName(@NonNull String databaseName) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                Objects.requireNonNull(databaseName);
                if (databaseName.contains("/")) {
                    throw new IllegalArgumentException("Database name cannot contain '/'");
                }
                mDatabaseName = databaseName;
                return this;
            }

            /** Builds a {@link SearchContext} instance. */
            @NonNull
            public SearchContext build() {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                mBuilt = true;
                return new SearchContext(mDatabaseName);
            }
        }
    }

    /**
     * Creates a new {@link AppSearchSession}.
     *
     * <p>This process requires an AppSearch native indexing file system for each user. If it's not
     * created for this user, the initialization process will create one under user's directory.
     *
     * @param searchContext The {@link SearchContext} contains all information to create a new
     *                      {@link AppSearchSession}
     * @param executor      Executor on which to invoke the callback.
     * @param callback      The {@link AppSearchResult}&lt;{@link AppSearchSession}&gt; of
     *                      performing this operation. Or a {@link AppSearchResult} with failure
     *                      reason code and error information.
     */
    public void createSearchSession(
            @NonNull SearchContext searchContext,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<AppSearchSession>> callback) {
        Objects.requireNonNull(searchContext);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        AppSearchSession.createSearchSession(
                searchContext, mService, mContext.getUserId(), executor, callback);
    }

    /**
     * Creates a new {@link GlobalSearchSession}.
     *
     * <p>This process requires an AppSearch native indexing file system for each user. If it's not
     * created for this user, the initialization process will create one under user's directory.
     *
     * @param executor      Executor on which to invoke the callback.
     * @param callback      The {@link AppSearchResult}&lt;{@link GlobalSearchSession}&gt; of
     *                      performing this operation. Or a {@link AppSearchResult} with failure
     *                      reason code and error information.
     * @hide
     */
    @SystemApi
    public void createGlobalSearchSession(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<GlobalSearchSession>> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        GlobalSearchSession.createGlobalSearchSession(
                mService, mContext.getUserId(), executor, callback);
    }

    /**
     * Sets the schema being used by documents provided to the {@link #putDocuments} method.
     *
     * <p>The schema provided here is compared to the stored copy of the schema previously supplied
     * to {@link #setSchema}, if any, to determine how to treat existing documents. The following
     * types of schema modifications are always safe and are made without deleting any existing
     * documents:
     * <ul>
     *     <li>Addition of new types
     *     <li>Addition of new
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_OPTIONAL
     *             OPTIONAL} or
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_REPEATED
     *             REPEATED} properties to a type
     *     <li>Changing the cardinality of a data type to be less restrictive (e.g. changing an
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_OPTIONAL
     *             OPTIONAL} property into a
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_REPEATED
     *             REPEATED} property.
     * </ul>
     *
     * <p>The following types of schema changes are not backwards-compatible:
     * <ul>
     *     <li>Removal of an existing type
     *     <li>Removal of a property from a type
     *     <li>Changing the data type ({@code boolean}, {@code long}, etc.) of an existing property
     *     <li>For properties of {@code GenericDocument} type, changing the schema type of
     *         {@code GenericDocument}s of that property
     *     <li>Changing the cardinality of a data type to be more restrictive (e.g. changing an
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_OPTIONAL
     *             OPTIONAL} property into a
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_REQUIRED
     *             REQUIRED} property).
     *     <li>Adding a
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_REQUIRED
     *             REQUIRED} property.
     * </ul>
     * <p>Supplying a schema with such changes will result in this call returning an
     * {@link AppSearchResult} with a code of {@link AppSearchResult#RESULT_INVALID_SCHEMA} and an
     * error message describing the incompatibility. In this case the previously set schema will
     * remain active.
     *
     * <p>If you need to make non-backwards-compatible changes as described above, instead use the
     * {@link #setSchema(List, boolean)} method with the {@code forceOverride} parameter set to
     * {@code true}.
     *
     * <p>It is a no-op to set the same schema as has been previously set; this is handled
     * efficiently.
     *
     * @param request The schema update request.
     * @return the result of performing this operation.
     * @deprecated use {@link AppSearchSession#setSchema} instead.
     * @hide
     */
    @NonNull
    public AppSearchResult<Void> setSchema(@NonNull SetSchemaRequest request) {
        Preconditions.checkNotNull(request);
        // TODO: This should use com.android.internal.infra.RemoteStream or another mechanism to
        //  avoid binder limits.
        List<Bundle> schemaBundles = new ArrayList<>(request.getSchemas().size());
        for (AppSearchSchema schema : request.getSchemas()) {
            schemaBundles.add(schema.getBundle());
        }
        AndroidFuture<AppSearchResult> future = new AndroidFuture<>();
        try {
            mService.setSchema(
                    DEFAULT_DATABASE_NAME,
                    schemaBundles,
                    new ArrayList<>(request.getSchemasNotVisibleToSystemUi()),
                    /*schemasPackageAccessible=*/ Collections.emptyMap(),
                    request.isForceOverride(),
                    mContext.getUserId(),
                    new IAppSearchResultCallback.Stub() {
                        public void onResult(AppSearchResult result) {
                            future.complete(result);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return getFutureOrThrow(future);
    }

    /**
     * Index {@link GenericDocument}s into AppSearch.
     *
     * <p>You should not call this method directly; instead, use the
     * {@code AppSearch#putDocuments()} API provided by JetPack.
     *
     * <p>Each {@link GenericDocument}'s {@code schemaType} field must be set to the name of a
     * schema type previously registered via the {@link #setSchema} method.
     *
     * @param request {@link PutDocumentsRequest} containing documents to be indexed
     * @return The pending result of performing this operation. The keys of the returned
     * {@link AppSearchBatchResult} are the URIs of the input documents. The values are
     * {@code null} if they were successfully indexed, or a failed {@link AppSearchResult}
     * otherwise.
     * @throws RuntimeException If an error occurred during the execution.
     *
     * @deprecated use {@link AppSearchSession#putDocuments} instead.
     * @hide
     */
    public AppSearchBatchResult<String, Void> putDocuments(@NonNull PutDocumentsRequest request) {
        // TODO(b/146386470): Transmit these documents as a RemoteStream instead of sending them in
        // one big list.
        List<GenericDocument> documents = request.getDocuments();
        List<Bundle> documentBundles = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            documentBundles.add(documents.get(i).getBundle());
        }
        AndroidFuture<AppSearchBatchResult> future = new AndroidFuture<>();
        try {
            mService.putDocuments(DEFAULT_DATABASE_NAME, documentBundles, mContext.getUserId(),
                    new IAppSearchBatchResultCallback.Stub() {
                        public void onResult(AppSearchBatchResult result) {
                            future.complete(result);
                        }

                        public void onSystemError(ParcelableException exception) {
                            future.completeExceptionally(exception);
                        }
                    });
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return getFutureOrThrow(future);
    }

    /**
     * Retrieves {@link GenericDocument}s by URI.
     *
     * <p>You should not call this method directly; instead, use the
     * {@code AppSearch#getDocuments()} API provided by JetPack.
     *
     * @param request {@link GetByUriRequest} containing URIs to be retrieved.
     * @return The pending result of performing this operation. The keys of the returned
     * {@link AppSearchBatchResult} are the input URIs. The values are the returned
     * {@link GenericDocument}s on success, or a failed {@link AppSearchResult} otherwise.
     * URIs that are not found will return a failed {@link AppSearchResult} with a result code
     * of {@link AppSearchResult#RESULT_NOT_FOUND}.
     * @throws RuntimeException If an error occurred during the execution.
     *
     * @deprecated use {@link AppSearchSession#getByUri} instead.
     * @hide
     */
    public AppSearchBatchResult<String, GenericDocument> getByUri(
            @NonNull GetByUriRequest request) {
        // TODO(b/146386470): Transmit the result documents as a RemoteStream instead of sending
        //     them in one big list.
        List<String> uris = new ArrayList<>(request.getUris());
        AndroidFuture<AppSearchBatchResult> future = new AndroidFuture<>();
        try {
            mService.getDocuments(DEFAULT_DATABASE_NAME, request.getNamespace(), uris,
                    mContext.getUserId(),
                    new IAppSearchBatchResultCallback.Stub() {
                        public void onResult(AppSearchBatchResult result) {
                            future.complete(result);
                        }

                        public void onSystemError(ParcelableException exception) {
                            future.completeExceptionally(exception);
                        }
                    });
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }

        // Translate from document bundles to GenericDocument instances
        AppSearchBatchResult<String, Bundle> bundleResult = getFutureOrThrow(future);
        AppSearchBatchResult.Builder<String, GenericDocument> documentResultBuilder =
                new AppSearchBatchResult.Builder<>();

        // Translate successful results
        for (Map.Entry<String, Bundle> bundleEntry : bundleResult.getSuccesses().entrySet()) {
            GenericDocument document;
            try {
                document = new GenericDocument(bundleEntry.getValue());
            } catch (Throwable t) {
                // These documents went through validation, so how could this fail? We must have
                // done something wrong.
                documentResultBuilder.setFailure(
                        bundleEntry.getKey(),
                        AppSearchResult.RESULT_INTERNAL_ERROR,
                        t.getMessage());
                continue;
            }
            documentResultBuilder.setSuccess(bundleEntry.getKey(), document);
        }

        // Translate failed results
        for (Map.Entry<String, AppSearchResult<Bundle>> bundleEntry :
                bundleResult.getFailures().entrySet()) {
            documentResultBuilder.setFailure(
                    bundleEntry.getKey(),
                    bundleEntry.getValue().getResultCode(),
                    bundleEntry.getValue().getErrorMessage());
        }

        return documentResultBuilder.build();
    }

    /**
     * Searches a document based on a given query string.
     *
     * <p>You should not call this method directly; instead, use the {@code AppSearch#query()} API
     * provided by JetPack.
     *
     * <p>Currently we support following features in the raw query format:
     * <ul>
     *     <li>AND
     *     <p>AND joins (e.g. “match documents that have both the terms ‘dog’ and
     *     ‘cat’”).
     *     Example: hello world matches documents that have both ‘hello’ and ‘world’
     *     <li>OR
     *     <p>OR joins (e.g. “match documents that have either the term ‘dog’ or
     *     ‘cat’”).
     *     Example: dog OR puppy
     *     <li>Exclusion
     *     <p>Exclude a term (e.g. “match documents that do
     *     not have the term ‘dog’”).
     *     Example: -dog excludes the term ‘dog’
     *     <li>Grouping terms
     *     <p>Allow for conceptual grouping of subqueries to enable hierarchical structures (e.g.
     *     “match documents that have either ‘dog’ or ‘puppy’, and either ‘cat’ or ‘kitten’”).
     *     Example: (dog puppy) (cat kitten) two one group containing two terms.
     *     <li>Property restricts
     *     <p> Specifies which properties of a document to specifically match terms in (e.g.
     *     “match documents where the ‘subject’ property contains ‘important’”).
     *     Example: subject:important matches documents with the term ‘important’ in the
     *     ‘subject’ property
     *     <li>Schema type restricts
     *     <p>This is similar to property restricts, but allows for restricts on top-level document
     *     fields, such as schema_type. Clients should be able to limit their query to documents of
     *     a certain schema_type (e.g. “match documents that are of the ‘Email’ schema_type”).
     *     Example: { schema_type_filters: “Email”, “Video”,query: “dog” } will match documents
     *     that contain the query term ‘dog’ and are of either the ‘Email’ schema type or the
     *     ‘Video’ schema type.
     * </ul>
     *
     * @param queryExpression Query String to search.
     * @param searchSpec Spec for setting filters, raw query etc.
     * @throws RuntimeException If an error occurred during the execution.
     *
     * @deprecated use AppSearchSession#query instead.
     * @hide
     */
    @NonNull
    public AppSearchResult<List<SearchResult>> query(
            @NonNull String queryExpression, @NonNull SearchSpec searchSpec) {
        // TODO(b/146386470): Transmit the result documents as a RemoteStream instead of sending
        //     them in one big list.
        AndroidFuture<AppSearchResult> future = new AndroidFuture<>();
        try {
            mService.query(DEFAULT_DATABASE_NAME, queryExpression, searchSpec.getBundle(),
                    mContext.getUserId(),
                    new IAppSearchResultCallback.Stub() {
                        public void onResult(AppSearchResult result) {
                            future.complete(result);
                        }
                    });
            AppSearchResult<Bundle> bundleResult = getFutureOrThrow(future);
            if (!bundleResult.isSuccess()) {
                return AppSearchResult.newFailedResult(bundleResult.getResultCode(),
                        bundleResult.getErrorMessage());
            }
            SearchResultPage searchResultPage = new SearchResultPage(bundleResult.getResultValue());
            return AppSearchResult.newSuccessfulResult(searchResultPage.getResults());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (Throwable t) {
            return AppSearchResult.throwableToFailedResult(t);
        }
    }

    /**
     * Removes {@link GenericDocument}s by URI.
     *
     * <p>You should not call this method directly; instead, use the {@code AppSearch#delete()} API
     * provided by JetPack.
     *
     * @param request Request containing URIs to be removed.
     * @return The pending result of performing this operation. The keys of the returned
     * {@link AppSearchBatchResult} are the input URIs. The values are {@code null} on success,
     * or a failed {@link AppSearchResult} otherwise. URIs that are not found will return a
     * failed {@link AppSearchResult} with a result code of
     * {@link AppSearchResult#RESULT_NOT_FOUND}.
     * @throws RuntimeException If an error occurred during the execution.
     *
     * @deprecated use {@link AppSearchSession#removeByUri} instead.
     * @hide
     */
    public AppSearchBatchResult<String, Void> removeByUri(@NonNull RemoveByUriRequest request) {
        List<String> uris = new ArrayList<>(request.getUris());
        AndroidFuture<AppSearchBatchResult> future = new AndroidFuture<>();
        try {
            mService.removeByUri(DEFAULT_DATABASE_NAME, request.getNamespace(), uris,
                    mContext.getUserId(),
                    new IAppSearchBatchResultCallback.Stub() {
                        public void onResult(AppSearchBatchResult result) {
                            future.complete(result);
                        }

                        public void onSystemError(ParcelableException exception) {
                            future.completeExceptionally(exception);
                        }
                    });
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return getFutureOrThrow(future);
    }

    private static <T> T getFutureOrThrow(@NonNull AndroidFuture<T> future) {
        try {
            return future.get();
        } catch (Throwable e) {
            if (e instanceof ExecutionException) {
                e = e.getCause();
            }
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            if (e instanceof Error) {
                throw (Error) e;
            }
            throw new RuntimeException(e);
        }
    }
}
