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

package com.android.server.appsearch.external.localstorage.converter;

import android.annotation.NonNull;

import android.app.appsearch.SearchSpec;
import com.android.internal.util.Preconditions;

import com.google.android.icing.proto.ResultSpecProto;
import com.google.android.icing.proto.ScoringSpecProto;
import com.google.android.icing.proto.SearchSpecProto;
import com.google.android.icing.proto.TermMatchType;

/**
 * Translates a {@link SearchSpec} into icing search protos.
 * @hide
 */

public final class SearchSpecToProtoConverter {
    private SearchSpecToProtoConverter() {}

    /** Extracts {@link SearchSpecProto} information from a {@link SearchSpec}. */
    @NonNull
    public static SearchSpecProto toSearchSpecProto(@NonNull SearchSpec spec) {
        Preconditions.checkNotNull(spec);
        SearchSpecProto.Builder protoBuilder = SearchSpecProto.newBuilder()
                .addAllSchemaTypeFilters(spec.getSchemaTypes())
                .addAllNamespaceFilters(spec.getNamespaces());

        @SearchSpec.TermMatch int termMatchCode = spec.getTermMatch();
        TermMatchType.Code termMatchCodeProto = TermMatchType.Code.forNumber(termMatchCode);
        if (termMatchCodeProto == null || termMatchCodeProto.equals(TermMatchType.Code.UNKNOWN)) {
            throw new IllegalArgumentException("Invalid term match type: " + termMatchCode);
        }
        protoBuilder.setTermMatchType(termMatchCodeProto);

        return protoBuilder.build();
    }

    /** Extracts {@link ResultSpecProto} information from a {@link SearchSpec}. */
    @NonNull
    public static ResultSpecProto toResultSpecProto(@NonNull SearchSpec spec) {
        Preconditions.checkNotNull(spec);
        return ResultSpecProto.newBuilder()
                .setNumPerPage(spec.getResultCountPerPage())
                .setSnippetSpec(
                        ResultSpecProto.SnippetSpecProto.newBuilder()
                                .setNumToSnippet(spec.getSnippetCount())
                                .setNumMatchesPerProperty(spec.getSnippetCountPerProperty())
                                .setMaxWindowBytes(spec.getMaxSnippetSize()))
                .build();
    }

    /** Extracts {@link ScoringSpecProto} information from a {@link SearchSpec}. */
    @NonNull
    public static ScoringSpecProto toScoringSpecProto(@NonNull SearchSpec spec) {
        Preconditions.checkNotNull(spec);
        ScoringSpecProto.Builder protoBuilder = ScoringSpecProto.newBuilder();

        @SearchSpec.Order int orderCode = spec.getOrder();
        ScoringSpecProto.Order.Code orderCodeProto =
                ScoringSpecProto.Order.Code.forNumber(orderCode);
        if (orderCodeProto == null) {
            throw new IllegalArgumentException("Invalid result ranking order: " + orderCode);
        }
        protoBuilder.setOrderBy(orderCodeProto);

        @SearchSpec.RankingStrategy int rankingStrategyCode = spec.getRankingStrategy();
        ScoringSpecProto.RankingStrategy.Code rankingStrategyCodeProto =
                ScoringSpecProto.RankingStrategy.Code.forNumber(rankingStrategyCode);
        if (rankingStrategyCodeProto == null) {
            throw new IllegalArgumentException("Invalid result ranking strategy: "
                    + rankingStrategyCode);
        }
        protoBuilder.setRankBy(rankingStrategyCodeProto);

        return protoBuilder.build();
    }
}
