/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.server.appsearch.external.localstorage;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.SearchResultPage;
import android.app.appsearch.SearchSpec;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.external.localstorage.stats.CallStats;
import com.android.server.appsearch.external.localstorage.stats.InitializeStats;
import com.android.server.appsearch.external.localstorage.stats.PutDocumentStats;
import com.android.server.appsearch.external.localstorage.stats.SearchStats;
import com.android.server.appsearch.proto.InitializeStatsProto;
import com.android.server.appsearch.proto.PutDocumentStatsProto;
import com.android.server.appsearch.proto.QueryStatsProto;
import com.android.server.appsearch.proto.ScoringSpecProto;
import com.android.server.appsearch.proto.TermMatchType;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.List;

public class AppSearchLoggerTest {
    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private AppSearchImpl mAppSearchImpl;
    private TestLogger mLogger;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();

        // Give ourselves global query permissions
        mAppSearchImpl =
                AppSearchImpl.create(
                        mTemporaryFolder.newFolder(),
                        context,
                        VisibilityStore.NO_OP_USER_ID,
                        /*globalQuerierPackage=*/ context.getPackageName(),
                        /*logger=*/ null);
        mLogger = new TestLogger();
    }

    // Test only not thread safe.
    public static class TestLogger implements AppSearchLogger {
        @Nullable CallStats mCallStats;
        @Nullable PutDocumentStats mPutDocumentStats;
        @Nullable InitializeStats mInitializeStats;
        @Nullable SearchStats mSearchStats;

        @Override
        public void logStats(@NonNull CallStats stats) {
            mCallStats = stats;
        }

        @Override
        public void logStats(@NonNull PutDocumentStats stats) {
            mPutDocumentStats = stats;
        }

        @Override
        public void logStats(@NonNull InitializeStats stats) {
            mInitializeStats = stats;
        }

        @Override
        public void logStats(@NonNull SearchStats stats) {
            mSearchStats = stats;
        }
    }

    @Test
    public void testAppSearchLoggerHelper_testCopyNativeStats_initialize() {
        int nativeLatencyMillis = 3;
        int nativeDocumentStoreRecoveryCause = InitializeStatsProto.RecoveryCause.DATA_LOSS_VALUE;
        int nativeIndexRestorationCause =
                InitializeStatsProto.RecoveryCause.INCONSISTENT_WITH_GROUND_TRUTH_VALUE;
        int nativeSchemaStoreRecoveryCause =
                InitializeStatsProto.RecoveryCause.SCHEMA_CHANGES_OUT_OF_SYNC_VALUE;
        int nativeDocumentStoreRecoveryLatencyMillis = 7;
        int nativeIndexRestorationLatencyMillis = 8;
        int nativeSchemaStoreRecoveryLatencyMillis = 9;
        int nativeDocumentStoreDataStatus =
                InitializeStatsProto.DocumentStoreDataStatus.NO_DATA_LOSS_VALUE;
        int nativeNumDocuments = 11;
        int nativeNumSchemaTypes = 12;
        InitializeStatsProto.Builder nativeInitBuilder =
                InitializeStatsProto.newBuilder()
                        .setLatencyMs(nativeLatencyMillis)
                        .setDocumentStoreRecoveryCause(
                                InitializeStatsProto.RecoveryCause.forNumber(
                                        nativeDocumentStoreRecoveryCause))
                        .setIndexRestorationCause(
                                InitializeStatsProto.RecoveryCause.forNumber(
                                        nativeIndexRestorationCause))
                        .setSchemaStoreRecoveryCause(
                                InitializeStatsProto.RecoveryCause.forNumber(
                                        nativeSchemaStoreRecoveryCause))
                        .setDocumentStoreRecoveryLatencyMs(nativeDocumentStoreRecoveryLatencyMillis)
                        .setIndexRestorationLatencyMs(nativeIndexRestorationLatencyMillis)
                        .setSchemaStoreRecoveryLatencyMs(nativeSchemaStoreRecoveryLatencyMillis)
                        .setDocumentStoreDataStatus(
                                InitializeStatsProto.DocumentStoreDataStatus.forNumber(
                                        nativeDocumentStoreDataStatus))
                        .setNumDocuments(nativeNumDocuments)
                        .setNumSchemaTypes(nativeNumSchemaTypes);
        InitializeStats.Builder initBuilder = new InitializeStats.Builder();

        AppSearchLoggerHelper.copyNativeStats(nativeInitBuilder.build(), initBuilder);

        InitializeStats iStats = initBuilder.build();
        assertThat(iStats.getNativeLatencyMillis()).isEqualTo(nativeLatencyMillis);
        assertThat(iStats.getDocumentStoreRecoveryCause())
                .isEqualTo(nativeDocumentStoreRecoveryCause);
        assertThat(iStats.getIndexRestorationCause()).isEqualTo(nativeIndexRestorationCause);
        assertThat(iStats.getSchemaStoreRecoveryCause()).isEqualTo(nativeSchemaStoreRecoveryCause);
        assertThat(iStats.getDocumentStoreRecoveryLatencyMillis())
                .isEqualTo(nativeDocumentStoreRecoveryLatencyMillis);
        assertThat(iStats.getIndexRestorationLatencyMillis())
                .isEqualTo(nativeIndexRestorationLatencyMillis);
        assertThat(iStats.getSchemaStoreRecoveryLatencyMillis())
                .isEqualTo(nativeSchemaStoreRecoveryLatencyMillis);
        assertThat(iStats.getDocumentStoreDataStatus()).isEqualTo(nativeDocumentStoreDataStatus);
        assertThat(iStats.getDocumentCount()).isEqualTo(nativeNumDocuments);
        assertThat(iStats.getSchemaTypeCount()).isEqualTo(nativeNumSchemaTypes);
    }

    @Test
    public void testAppSearchLoggerHelper_testCopyNativeStats_putDocument() {
        final int nativeLatencyMillis = 3;
        final int nativeDocumentStoreLatencyMillis = 4;
        final int nativeIndexLatencyMillis = 5;
        final int nativeIndexMergeLatencyMillis = 6;
        final int nativeDocumentSize = 7;
        final int nativeNumTokensIndexed = 8;
        final boolean nativeExceededMaxNumTokens = true;
        PutDocumentStatsProto nativePutDocumentStats =
                PutDocumentStatsProto.newBuilder()
                        .setLatencyMs(nativeLatencyMillis)
                        .setDocumentStoreLatencyMs(nativeDocumentStoreLatencyMillis)
                        .setIndexLatencyMs(nativeIndexLatencyMillis)
                        .setIndexMergeLatencyMs(nativeIndexMergeLatencyMillis)
                        .setDocumentSize(nativeDocumentSize)
                        .setTokenizationStats(
                                PutDocumentStatsProto.TokenizationStats.newBuilder()
                                        .setNumTokensIndexed(nativeNumTokensIndexed)
                                        .setExceededMaxTokenNum(nativeExceededMaxNumTokens)
                                        .build())
                        .build();
        PutDocumentStats.Builder pBuilder = new PutDocumentStats.Builder("packageName", "database");

        AppSearchLoggerHelper.copyNativeStats(nativePutDocumentStats, pBuilder);

        PutDocumentStats pStats = pBuilder.build();
        assertThat(pStats.getNativeLatencyMillis()).isEqualTo(nativeLatencyMillis);
        assertThat(pStats.getNativeDocumentStoreLatencyMillis())
                .isEqualTo(nativeDocumentStoreLatencyMillis);
        assertThat(pStats.getNativeIndexLatencyMillis()).isEqualTo(nativeIndexLatencyMillis);
        assertThat(pStats.getNativeIndexMergeLatencyMillis())
                .isEqualTo(nativeIndexMergeLatencyMillis);
        assertThat(pStats.getNativeDocumentSizeBytes()).isEqualTo(nativeDocumentSize);
        assertThat(pStats.getNativeNumTokensIndexed()).isEqualTo(nativeNumTokensIndexed);
        assertThat(pStats.getNativeExceededMaxNumTokens()).isEqualTo(nativeExceededMaxNumTokens);
    }

    @Test
    public void testAppSearchLoggerHelper_testCopyNativeStats_search() {
        int nativeLatencyMillis = 4;
        int nativeNumTerms = 5;
        // TODO(b/185804196) query length needs to be added in the native stats.
        // int nativeQueryLength = 6;
        int nativeNumNamespacesFiltered = 7;
        int nativeNumSchemaTypesFiltered = 8;
        int nativeRequestedPageSize = 9;
        int nativeNumResultsReturnedCurrentPage = 10;
        boolean nativeIsFirstPage = true;
        int nativeParseQueryLatencyMillis = 11;
        int nativeRankingStrategy = ScoringSpecProto.RankingStrategy.Code.CREATION_TIMESTAMP_VALUE;
        int nativeNumDocumentsScored = 13;
        int nativeScoringLatencyMillis = 14;
        int nativeRankingLatencyMillis = 15;
        int nativeNumResultsWithSnippets = 16;
        int nativeDocumentRetrievingLatencyMillis = 17;
        QueryStatsProto nativeQueryStats =
                QueryStatsProto.newBuilder()
                        .setLatencyMs(nativeLatencyMillis)
                        .setNumTerms(nativeNumTerms)
                        .setNumNamespacesFiltered(nativeNumNamespacesFiltered)
                        .setNumSchemaTypesFiltered(nativeNumSchemaTypesFiltered)
                        .setRequestedPageSize(nativeRequestedPageSize)
                        .setNumResultsReturnedCurrentPage(nativeNumResultsReturnedCurrentPage)
                        .setIsFirstPage(nativeIsFirstPage)
                        .setParseQueryLatencyMs(nativeParseQueryLatencyMillis)
                        .setRankingStrategy(
                                ScoringSpecProto.RankingStrategy.Code.forNumber(
                                        nativeRankingStrategy))
                        .setNumDocumentsScored(nativeNumDocumentsScored)
                        .setScoringLatencyMs(nativeScoringLatencyMillis)
                        .setRankingLatencyMs(nativeRankingLatencyMillis)
                        .setNumResultsWithSnippets(nativeNumResultsWithSnippets)
                        .setDocumentRetrievalLatencyMs(nativeDocumentRetrievingLatencyMillis)
                        .build();
        SearchStats.Builder qBuilder =
                new SearchStats.Builder(SearchStats.VISIBILITY_SCOPE_LOCAL, "packageName")
                        .setDatabase("database");

        AppSearchLoggerHelper.copyNativeStats(nativeQueryStats, qBuilder);

        SearchStats sStats = qBuilder.build();
        assertThat(sStats.getNativeLatencyMillis()).isEqualTo(nativeLatencyMillis);
        assertThat(sStats.getTermCount()).isEqualTo(nativeNumTerms);
        // assertThat(sStats.getNativeQueryLength()).isEqualTo(nativeQueryLength);
        assertThat(sStats.getFilteredNamespaceCount()).isEqualTo(nativeNumNamespacesFiltered);
        assertThat(sStats.getFilteredSchemaTypeCount()).isEqualTo(nativeNumSchemaTypesFiltered);
        assertThat(sStats.getRequestedPageSize()).isEqualTo(nativeRequestedPageSize);
        assertThat(sStats.getCurrentPageReturnedResultCount())
                .isEqualTo(nativeNumResultsReturnedCurrentPage);
        assertThat(sStats.isFirstPage()).isTrue();
        assertThat(sStats.getParseQueryLatencyMillis()).isEqualTo(nativeParseQueryLatencyMillis);
        assertThat(sStats.getRankingStrategy()).isEqualTo(nativeRankingStrategy);
        assertThat(sStats.getScoredDocumentCount()).isEqualTo(nativeNumDocumentsScored);
        assertThat(sStats.getScoringLatencyMillis()).isEqualTo(nativeScoringLatencyMillis);
        assertThat(sStats.getRankingLatencyMillis()).isEqualTo(nativeRankingLatencyMillis);
        assertThat(sStats.getResultWithSnippetsCount()).isEqualTo(nativeNumResultsWithSnippets);
        assertThat(sStats.getDocumentRetrievingLatencyMillis())
                .isEqualTo(nativeDocumentRetrievingLatencyMillis);
    }

    //
    // Testing actual logging
    //
    @Test
    public void testLoggingStats_initialize() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();

        AppSearchImpl appSearchImpl =
                AppSearchImpl.create(
                        mTemporaryFolder.newFolder(),
                        context,
                        VisibilityStore.NO_OP_USER_ID,
                        /*globalQuerierPackage=*/ context.getPackageName(),
                        mLogger);

        InitializeStats iStats = mLogger.mInitializeStats;
        assertThat(iStats).isNotNull();
        assertThat(iStats.getStatusCode()).isEqualTo(AppSearchResult.RESULT_OK);
        assertThat(iStats.getTotalLatencyMillis()).isGreaterThan(0);
        assertThat(iStats.hasDeSync()).isFalse();
        assertThat(iStats.getNativeLatencyMillis()).isGreaterThan(0);
        assertThat(iStats.getDocumentStoreDataStatus())
                .isEqualTo(InitializeStatsProto.DocumentStoreDataStatus.NO_DATA_LOSS_VALUE);
        assertThat(iStats.getDocumentCount()).isEqualTo(0);
        assertThat(iStats.getSchemaTypeCount()).isEqualTo(0);
    }

    @Test
    public void testLoggingStats_putDocument() throws Exception {
        // Insert schema
        final String testPackageName = "testPackage";
        final String testDatabase = "testDatabase";
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                testPackageName,
                testDatabase,
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);
        GenericDocument document = new GenericDocument.Builder<>("namespace", "id", "type").build();

        mAppSearchImpl.putDocument(testPackageName, testDatabase, document, mLogger);

        PutDocumentStats pStats = mLogger.mPutDocumentStats;
        assertThat(pStats).isNotNull();
        assertThat(pStats.getGeneralStats().getPackageName()).isEqualTo(testPackageName);
        assertThat(pStats.getGeneralStats().getDatabase()).isEqualTo(testDatabase);
        assertThat(pStats.getGeneralStats().getStatusCode()).isEqualTo(AppSearchResult.RESULT_OK);
        // The rest of native stats have been tested in testCopyNativeStats
        assertThat(pStats.getNativeDocumentSizeBytes()).isGreaterThan(0);
    }

    @Test
    public void testLoggingStats_search() throws Exception {
        // Insert schema
        final String testPackageName = "testPackage";
        final String testDatabase = "testDatabase";
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                testPackageName,
                testDatabase,
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);
        GenericDocument document = new GenericDocument.Builder<>("namespace", "id", "type").build();
        mAppSearchImpl.putDocument(testPackageName, testDatabase, document, mLogger);

        // No query filters specified. package2 should only get its own documents back.
        SearchSpec searchSpec =
                new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        SearchResultPage searchResultPage =
                mAppSearchImpl.query(
                        testPackageName,
                        testDatabase,
                        /*QueryExpression=*/ "",
                        searchSpec,
                        /*logger=*/ mLogger);

        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document);

        SearchStats sStats = mLogger.mSearchStats;

        assertThat(sStats).isNotNull();
        assertThat(sStats.getPackageName()).isEqualTo(testPackageName);
        assertThat(sStats.getDatabase()).isEqualTo(testDatabase);
        assertThat(sStats.getStatusCode()).isEqualTo(AppSearchResult.RESULT_OK);
        assertThat(sStats.getTotalLatencyMillis()).isGreaterThan(0);
        assertThat(sStats.getVisibilityScope()).isEqualTo(SearchStats.VISIBILITY_SCOPE_LOCAL);
        assertThat(sStats.getTermCount()).isEqualTo(0);
        // assertThat(sStats.getNativeQueryLength()).isEqualTo(0);
        assertThat(sStats.getFilteredNamespaceCount()).isEqualTo(1);
        assertThat(sStats.getFilteredSchemaTypeCount()).isEqualTo(1);
        assertThat(sStats.getCurrentPageReturnedResultCount()).isEqualTo(1);
        assertThat(sStats.isFirstPage()).isTrue();
        assertThat(sStats.getScoredDocumentCount()).isEqualTo(1);
    }
}
