/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm;

import android.content.Intent;
import android.content.pm.ResolveInfo;

import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.resolution.ComponentResolverApi;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Cross Profile intent resolution strategy used for and to clone profile.
 */
public class CloneProfileResolver extends CrossProfileResolver {

    public CloneProfileResolver(ComponentResolverApi componentResolver,
            UserManagerService userManagerService) {
        super(componentResolver, userManagerService);
    }

    /**
     * This is resolution strategy for Clone Profile.
     * In case of clone profile, the profile is supposed to be transparent to end user. To end user
     * clone and owner profile should be part of same user space. Hence, the resolution strategy
     * would resolve intent in both profile and return combined result without any filtering of the
     * results.
     *
     * @param computer ComputerEngine instance that would be needed by ComponentResolverApi
     * @param intent request
     * @param resolvedType the MIME data type of intent request
     * @param userId source/initiating user
     * @param targetUserId target user id
     * @param flags of intent request
     * @param pkgName the application package name this Intent is limited to
     * @param matchingFilters {@link CrossProfileIntentFilter}s configured for source user,
     *                                                        targeting the targetUserId
     * @param hasNonNegativePriorityResult if source have any non-negative(active and valid)
     *                                     resolveInfo in their profile.
     * @param pkgSettingFunction function to find PackageStateInternal for given package
     * @return list of {@link CrossProfileDomainInfo}
     */
    @Override
    public List<CrossProfileDomainInfo> resolveIntent(Computer computer, Intent intent,
            String resolvedType, int userId, int targetUserId, long flags,
            String pkgName, List<CrossProfileIntentFilter> matchingFilters,
            boolean hasNonNegativePriorityResult,
            Function<String, PackageStateInternal> pkgSettingFunction) {
        List<ResolveInfo> resolveInfos = mComponentResolver.queryActivities(computer,
                intent, resolvedType, flags, targetUserId);
        List<CrossProfileDomainInfo> crossProfileDomainInfos = new ArrayList<>();
        if (resolveInfos != null) {

            for (int index = 0; index < resolveInfos.size(); index++) {
                crossProfileDomainInfos.add(new CrossProfileDomainInfo(resolveInfos.get(index),
                        DomainVerificationManagerInternal.APPROVAL_LEVEL_NONE,
                        targetUserId));
            }
        }
        return filterIfNotSystemUser(crossProfileDomainInfos, userId);
    }

    /**
     * As clone and owner profile are going to be part of the same userspace, we need no filtering
     * out of any clone profile's result
     * @param intent request
     * @param crossProfileDomainInfos resolved in target user
     * @param flags for intent resolution
     * @param sourceUserId source user
     * @param targetUserId target user
     * @param highestApprovalLevel highest level of domain approval
     * @return list of CrossProfileDomainInfo
     */
    @Override
    public List<CrossProfileDomainInfo> filterResolveInfoWithDomainPreferredActivity(Intent intent,
            List<CrossProfileDomainInfo> crossProfileDomainInfos, long flags, int sourceUserId,
            int targetUserId, int highestApprovalLevel) {
        // no filtering for clone profile
        return crossProfileDomainInfos;
    }
}
