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

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;

import com.android.internal.infra.AndroidFuture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * This class provides access to the centralized AppSearch index maintained by the system.
 *
 * <p>Apps can index structured text documents with AppSearch, which can then be retrieved through
 * the query API.
 *
 * @hide
 */
// TODO(b/148046169): This class header needs a detailed example/tutorial.
@SystemService(Context.APP_SEARCH_SERVICE)
public class AppSearchManager {
    private final IAppSearchManager mService;

    /** @hide */
    public AppSearchManager(@NonNull IAppSearchManager service) {
        mService = service;
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
     * @param schemas The schema configs for the types used by the calling app.
     * @return the result of performing this operation.
     *
     * @hide
     */
    @NonNull
    public AppSearchResult<Void> setSchema(@NonNull AppSearchSchema... schemas) {
        return setSchema(Arrays.asList(schemas), /*forceOverride=*/false);
    }

    /**
     * Sets the schema being used by documents provided to the {@link #putDocuments} method.
     *
     * <p>This method is similar to {@link #setSchema(AppSearchSchema...)}, except for the
     * {@code forceOverride} parameter. If a backwards-incompatible schema is specified but the
     * {@code forceOverride} parameter is set to {@code true}, instead of returning an
     * {@link AppSearchResult} with the {@link AppSearchResult#RESULT_INVALID_SCHEMA} code, all
     * documents which are not compatible with the new schema will be deleted and the incompatible
     * schema will be applied.
     *
     * @param schemas The schema configs for the types used by the calling app.
     * @param forceOverride Whether to force the new schema to be applied even if there are
     *     incompatible changes versus the previously set schema. Documents which are incompatible
     *     with the new schema will be deleted.
     * @return the result of performing this operation.
     *
     * @hide
     */
    @NonNull
    public AppSearchResult<Void> setSchema(
            @NonNull List<AppSearchSchema> schemas, boolean forceOverride) {
        // TODO: This should use com.android.internal.infra.RemoteStream or another mechanism to
        //  avoid binder limits.
        List<Bundle> schemaBundles = new ArrayList<>(schemas.size());
        for (AppSearchSchema schema : schemas) {
            schemaBundles.add(schema.getBundle());
        }
        AndroidFuture<AppSearchResult> future = new AndroidFuture<>();
        try {
            mService.setSchema(schemaBundles, forceOverride, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
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
     * @param documents {@link GenericDocument}s that need to be indexed.
     * @return An {@link AppSearchBatchResult} mapping the document URIs to {@link Void} if they
     *     were successfully indexed, or a {@link Throwable} describing the failure if they could
     *     not be indexed.
     * @hide
     */
    public AppSearchBatchResult<String, Void> putDocuments(
            @NonNull List<GenericDocument> documents) {
        // TODO(b/146386470): Transmit these documents as a RemoteStream instead of sending them in
        // one big list.
        List<Bundle> documentBundles = new ArrayList<>(documents.size());
        for (GenericDocument document : documents) {
            documentBundles.add(document.getBundle());
        }
        AndroidFuture<AppSearchBatchResult> future = new AndroidFuture<>();
        try {
            mService.putDocuments(documentBundles, future);
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
     * @param uris URIs of the documents to look up.
     * @return An {@link AppSearchBatchResult} mapping the document URIs to
     *     {@link GenericDocument} values if they were successfully retrieved, a {@code null}
     *     failure if they were not found, or a {@link Throwable} failure describing the problem if
     *     an error occurred.
     */
    public AppSearchBatchResult<String, GenericDocument> getDocuments(@NonNull List<String> uris) {
        // TODO(b/146386470): Transmit the result documents as a RemoteStream instead of sending
        //     them in one big list.
        AndroidFuture<AppSearchBatchResult> future = new AndroidFuture<>();
        try {
            mService.getDocuments(uris, future);
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
     * @hide
     */
    @NonNull
    public AppSearchResult<List<SearchResult>> query(
            @NonNull String queryExpression, @NonNull SearchSpec searchSpec) {
        // TODO(b/146386470): Transmit the result documents as a RemoteStream instead of sending
        //     them in one big list.
        AndroidFuture<AppSearchResult> searchResultsFuture = new AndroidFuture<>();
        try {
            mService.query(queryExpression, searchSpec.getBundle(), searchResultsFuture);
        } catch (RemoteException e) {
            searchResultsFuture.completeExceptionally(e);
        }

        // Translate the list of Bundle into a list of SearchResult
        AppSearchResult<SearchResults> searchResultsResult = getFutureOrThrow(searchResultsFuture);
        if (!searchResultsResult.isSuccess()) {
            return AppSearchResult.newFailedResult(
                    searchResultsResult.getResultCode(), searchResultsResult.getErrorMessage());
        }
        SearchResults searchResults = searchResultsResult.getResultValue();
        return AppSearchResult.newSuccessfulResult(searchResults.mResults);
    }

    /**
     * Deletes {@link GenericDocument}s by URI.
     *
     * <p>You should not call this method directly; instead, use the {@code AppSearch#delete()} API
     * provided by JetPack.
     *
     * @param uris URIs of the documents to delete
     * @return An {@link AppSearchBatchResult} mapping each URI to a {@code null} success if
     *     deletion was successful, to a {@code null} failure if the document did not exist, or to a
     *     {@code throwable} failure if deletion failed for another reason.
     */
    public AppSearchBatchResult<String, Void> delete(@NonNull List<String> uris) {
        AndroidFuture<AppSearchBatchResult> future = new AndroidFuture<>();
        try {
            mService.delete(uris, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return getFutureOrThrow(future);
    }

    /**
     * Deletes {@link android.app.appsearch.AppSearch.Document}s by schema type.
     *
     * <p>You should not call this method directly; instead, use the
     * {@code AppSearch#deleteByType()} API provided by JetPack.
     *
     * @param schemaTypes Schema types whose documents to delete.
     * @return An {@link AppSearchBatchResult} mapping each schema type to a {@code null} success if
     *     deletion was successful, to a {@code null} failure if the type did not exist, or to a
     *     {@code throwable} failure if deletion failed for another reason.
     */
    public AppSearchBatchResult<String, Void> deleteByTypes(@NonNull List<String> schemaTypes) {
        AndroidFuture<AppSearchBatchResult> future = new AndroidFuture<>();
        try {
            mService.deleteByTypes(schemaTypes, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return getFutureOrThrow(future);
    }

    /** Deletes all documents owned by the calling app. */
    public AppSearchResult<Void> deleteAll() {
        AndroidFuture<AppSearchResult> future = new AndroidFuture<>();
        try {
            mService.deleteAll(future);
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
