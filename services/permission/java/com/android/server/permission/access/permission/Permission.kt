/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.permission.access.permission

import android.content.pm.PermissionInfo
import com.android.server.permission.access.util.hasBits

data class Permission(
    val permissionInfo: PermissionInfo,
    val isReconciled: Boolean,
    val type: Int,
    val appId: Int
) {
    inline val name: String
        get() = permissionInfo.name

    inline val packageName: String
        get() = permissionInfo.packageName

    inline val groupName: String?
        get() = permissionInfo.group

    inline val isDynamic: Boolean
        get() = type == TYPE_DYNAMIC

    inline val isNormal: Boolean
        get() = permissionInfo.protection == PermissionInfo.PROTECTION_NORMAL

    inline val isRuntime: Boolean
        get() = permissionInfo.protection == PermissionInfo.PROTECTION_DANGEROUS

    inline val isAppOp: Boolean
        get() = permissionInfo.protection == PermissionInfo.PROTECTION_FLAG_APPOP

    inline val isRemoved: Boolean
        get() = permissionInfo.flags.hasBits(PermissionInfo.FLAG_REMOVED)

    inline val isSoftRestricted: Boolean
        get() = permissionInfo.flags.hasBits(PermissionInfo.FLAG_SOFT_RESTRICTED)

    inline val isHardRestricted: Boolean
        get() = permissionInfo.flags.hasBits(PermissionInfo.FLAG_HARD_RESTRICTED)

    inline val isSignature: Boolean
        get() = permissionInfo.protection == PermissionInfo.PROTECTION_SIGNATURE

    inline val isInternal: Boolean
        get() = permissionInfo.protection == PermissionInfo.PROTECTION_INTERNAL

    inline val isDevelopment: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_DEVELOPMENT)

    inline val isInstaller: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_INSTALLER)

    inline val isOem: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_OEM)

    inline val isPre23: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_PRE23)

    inline val isPreInstalled: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_PREINSTALLED)

    inline val isPrivileged: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_PRIVILEGED)

    inline val isSetup: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_SETUP)

    inline val isVerifier: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_VERIFIER)

    inline val isVendorPrivileged: Boolean
        get() = permissionInfo.protectionFlags
            .hasBits(PROTECTION_FLAG_VENDOR_PRIVILEGED)

    inline val isSystemTextClassifier: Boolean
        get() = permissionInfo.protectionFlags
            .hasBits(PermissionInfo.PROTECTION_FLAG_SYSTEM_TEXT_CLASSIFIER)

    inline val isConfigurator: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_CONFIGURATOR)

    inline val isIncidentReportApprover: Boolean
        get() = permissionInfo.protectionFlags
            .hasBits(PermissionInfo.PROTECTION_FLAG_INCIDENT_REPORT_APPROVER)

    inline val isAppPredictor: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_APP_PREDICTOR)

    inline val isCompanion: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_COMPANION)

    inline val isRetailDemo: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_RETAIL_DEMO)

    inline val isRecents: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_RECENTS)

    inline val isRole: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_ROLE)

    inline val isKnownSigner: Boolean
        get() = permissionInfo.protectionFlags.hasBits(PermissionInfo.PROTECTION_FLAG_KNOWN_SIGNER)

    inline val hasGids: Boolean
        get() = throw NotImplementedError()

    inline val protectionLevel: Int
        @Suppress("DEPRECATION")
        get() = permissionInfo.protectionLevel

    inline val knownCerts: Set<String>
        get() = permissionInfo.knownCerts

    companion object {
        // The permission is defined in an application manifest.
        const val TYPE_MANIFEST = 0
        // The permission is defined in a system config.
        const val TYPE_CONFIG = 1
        // The permission is defined dynamically.
        const val TYPE_DYNAMIC = 2

        // TODO: PermissionInfo.PROTECTION_FLAG_VENDOR_PRIVILEGED is a testApi
        const val PROTECTION_FLAG_VENDOR_PRIVILEGED = 0x8000
    }
}
