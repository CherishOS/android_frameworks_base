/*
 * Copyright 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content.pm.verify.domain;

import android.content.pm.verify.domain.DomainVerificationInfo;
import android.content.pm.verify.domain.DomainVerificationUserSelection;
import java.util.List;

/**
 * @see DomainVerificationManager
 * @hide
 */
interface IDomainVerificationManager {

    List<String> getValidVerificationPackageNames();

    @nullable
    DomainVerificationInfo getDomainVerificationInfo(String packageName);

    @nullable
    DomainVerificationUserSelection getDomainVerificationUserSelection(String packageName,
            int userId);

    void setDomainVerificationStatus(String domainSetId, in List<String> domains, int state);

    void setDomainVerificationLinkHandlingAllowed(String packageName, boolean allowed, int userId);

    void setDomainVerificationUserSelection(String domainSetId, in List<String> domains,
            boolean enabled, int userId);
}
