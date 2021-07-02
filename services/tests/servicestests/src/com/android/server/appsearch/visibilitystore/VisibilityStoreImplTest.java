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

package com.android.server.appsearch.visibilitystore;

import static android.Manifest.permission.READ_GLOBAL_APP_SEARCH_DATA;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.appsearch.PackageIdentifier;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.ArrayMap;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.OptimizeStrategy;
import com.android.server.appsearch.external.localstorage.UnlimitedLimitConfig;
import com.android.server.appsearch.external.localstorage.util.PrefixUtil;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityStore;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Map;

public class VisibilityStoreImplTest {
    /**
     * Always trigger optimize in this class. OptimizeStrategy will be tested in its own test class.
     */
    private static final OptimizeStrategy ALWAYS_OPTIMIZE = optimizeInfo -> true;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private final Map<UserHandle, PackageManager> mMockPackageManagers = new ArrayMap<>();
    private Context mContext;
    private VisibilityStoreImpl mVisibilityStore;
    private int mUid;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        mContext = new ContextWrapper(context) {
            @Override
            public Context createContextAsUser(UserHandle user, int flags) {
                return new ContextWrapper(super.createContextAsUser(user, flags)) {
                    @Override
                    public PackageManager getPackageManager() {
                        return getMockPackageManager(user);
                    }
                };
            }

            @Override
            public PackageManager getPackageManager() {
                return createContextAsUser(getUser(), /*flags=*/ 0).getPackageManager();
            }
        };

        // Give ourselves global query permissions
        AppSearchImpl appSearchImpl = AppSearchImpl.create(
                mTemporaryFolder.newFolder(),
                new UnlimitedLimitConfig(),
                /*initStatsBuilder=*/ null,
                ALWAYS_OPTIMIZE);
        mVisibilityStore = VisibilityStoreImpl.create(appSearchImpl, mContext);
        mUid = mContext.getPackageManager().getPackageUid(mContext.getPackageName(), /*flags=*/ 0);
    }

    /**
     * Make sure that we don't conflict with any special characters that AppSearchImpl has reserved.
     */
    @Test
    public void testValidPackageName() {
        assertThat(VisibilityStore.PACKAGE_NAME)
                .doesNotContain(String.valueOf(PrefixUtil.PACKAGE_DELIMITER));
        assertThat(VisibilityStore.PACKAGE_NAME)
                .doesNotContain(String.valueOf(PrefixUtil.DATABASE_DELIMITER));
    }

    /**
     * Make sure that we don't conflict with any special characters that AppSearchImpl has reserved.
     */
    @Test
    public void testValidDatabaseName() {
        assertThat(VisibilityStore.DATABASE_NAME)
                .doesNotContain(String.valueOf(PrefixUtil.PACKAGE_DELIMITER));
        assertThat(VisibilityStore.DATABASE_NAME)
                .doesNotContain(String.valueOf(PrefixUtil.DATABASE_DELIMITER));
    }

    @Test
    public void testDoesCallerHaveSystemAccess() {
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager
                .checkPermission(READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_GRANTED);
        assertThat(mVisibilityStore.doesCallerHaveSystemAccess(mContext.getPackageName())).isTrue();

        when(mockPackageManager
                .checkPermission(READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_DENIED);
        assertThat(mVisibilityStore.doesCallerHaveSystemAccess(mContext.getPackageName()))
                .isFalse();
    }

    @Test
    public void testSetVisibility_displayedBySystem() throws Exception {
        // Make sure we have global query privileges
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager
                .checkPermission(READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_GRANTED);
        assertThat(mVisibilityStore.doesCallerHaveSystemAccess(mContext.getPackageName())).isTrue();

        mVisibilityStore.setVisibility(
                "package",
                "database",
                /*schemasNotDisplayedBySystem=*/ ImmutableSet.of(
                        "prefix/schema1", "prefix/schema2"),
                /*schemasVisibleToPackages=*/ Collections.emptyMap());
        assertThat(
                        mVisibilityStore.isSchemaSearchableByCaller(
                                "package",
                                "database",
                                "prefix/schema1",
                                mUid,
                                /*callerHasSystemAccess=*/ true))
                .isFalse();
        assertThat(
                        mVisibilityStore.isSchemaSearchableByCaller(
                                "package",
                                "database",
                                "prefix/schema2",
                                mUid,
                                /*callerHasSystemAccess=*/ true))
                .isFalse();

        // New .setVisibility() call completely overrides previous visibility settings.
        // So "schema2" isn't preserved.
        mVisibilityStore.setVisibility(
                "package",
                "database",
                /*schemasNotDisplayedBySystem=*/ ImmutableSet.of(
                        "prefix/schema1", "prefix/schema3"),
                /*schemasVisibleToPackages=*/ Collections.emptyMap());
        assertThat(
                        mVisibilityStore.isSchemaSearchableByCaller(
                                "package",
                                "database",
                                "prefix/schema1",
                                mUid,
                                /*callerHasSystemAccess=*/ true))
                .isFalse();
        assertThat(
                        mVisibilityStore.isSchemaSearchableByCaller(
                                "package",
                                "database",
                                "prefix/schema2",
                                mUid,
                                /*callerHasSystemAccess=*/ true))
                .isTrue();
        assertThat(
                        mVisibilityStore.isSchemaSearchableByCaller(
                                "package",
                                "database",
                                "prefix/schema3",
                                mUid,
                                /*callerHasSystemAccess=*/ true))
                .isFalse();

        // Everything defaults to visible again.
        mVisibilityStore.setVisibility(
                "package",
                "database",
                /*schemasNotDisplayedBySystem=*/ Collections.emptySet(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap());
        assertThat(
                        mVisibilityStore.isSchemaSearchableByCaller(
                                "package",
                                "database",
                                "prefix/schema1",
                                mUid,
                                /*callerHasSystemAccess=*/ true))
                .isTrue();
        assertThat(
                        mVisibilityStore.isSchemaSearchableByCaller(
                                "package",
                                "database",
                                "prefix/schema2",
                                mUid,
                                /*callerHasSystemAccess=*/ true))
                .isTrue();
        assertThat(
                        mVisibilityStore.isSchemaSearchableByCaller(
                                "package",
                                "database",
                                "prefix/schema3",
                                mUid,
                                /*callerHasSystemAccess=*/ true))
                .isTrue();
    }

    @Test
    public void testSetVisibility_visibleToPackages() throws Exception {
        // Values for a "foo" client
        String packageNameFoo = "packageFoo";
        byte[] sha256CertFoo = new byte[] {10};
        int uidFoo = 1;

        // Values for a "bar" client
        String packageNameBar = "packageBar";
        byte[] sha256CertBar = new byte[] {100};
        int uidBar = 2;

        // Can't be the same value as uidFoo nor uidBar
        int uidNotFooOrBar = 3;

        // Make sure none of them have global query privileges
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager
                .checkPermission(READ_GLOBAL_APP_SEARCH_DATA, packageNameFoo))
                .thenReturn(PERMISSION_DENIED);
        when(mockPackageManager
                .checkPermission(READ_GLOBAL_APP_SEARCH_DATA, packageNameBar))
                .thenReturn(PERMISSION_DENIED);

        // By default, a schema isn't package accessible.
        assertThat(mVisibilityStore.isSchemaSearchableByCaller(
                "package",
                "database",
                "prefix/schemaFoo",
                uidFoo,
                /*callerHasSystemAccess=*/ false))
                .isFalse();
        assertThat(mVisibilityStore.isSchemaSearchableByCaller(
                "package",
                "database",
                "prefix/schemaBar",
                uidBar,
                /*callerHasSystemAccess=*/ false))
                .isFalse();

        // Grant package access
        mVisibilityStore.setVisibility(
                "package",
                "database",
                /*schemasNotDisplayedBySystem=*/ Collections.emptySet(),
                /*schemasVisibleToPackages=*/ ImmutableMap.of(
                        "prefix/schemaFoo",
                        ImmutableList.of(new PackageIdentifier(packageNameFoo, sha256CertFoo)),
                        "prefix/schemaBar",
                        ImmutableList.of(new PackageIdentifier(packageNameBar, sha256CertBar))));

        // Should fail if PackageManager doesn't see that it has the proper certificate
        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenReturn(uidFoo);
        when(mockPackageManager.hasSigningCertificate(
                packageNameFoo, sha256CertFoo, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(false);
        assertThat(mVisibilityStore.isSchemaSearchableByCaller(
                "package",
                "database",
                "prefix/schemaFoo",
                uidFoo,
                /*callerHasSystemAccess=*/ false))
                .isFalse();

        // Should fail if PackageManager doesn't think the package belongs to the uid
        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenReturn(uidNotFooOrBar);
        when(mockPackageManager.hasSigningCertificate(
                packageNameFoo, sha256CertFoo, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);
        assertThat(mVisibilityStore.isSchemaSearchableByCaller(
                "package",
                "database",
                "prefix/schemaFoo",
                uidFoo,
                /*callerHasSystemAccess=*/ false))
                .isFalse();

        // But if uid and certificate match, then we should have access
        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenReturn(uidFoo);
        when(mockPackageManager.hasSigningCertificate(
                packageNameFoo, sha256CertFoo, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);
        assertThat(mVisibilityStore.isSchemaSearchableByCaller(
                "package",
                "database",
                "prefix/schemaFoo",
                uidFoo,
                /*callerHasSystemAccess=*/ false))
                .isTrue();

        when(mockPackageManager.getPackageUid(eq(packageNameBar), /*flags=*/ anyInt()))
                .thenReturn(uidBar);
        when(mockPackageManager.hasSigningCertificate(
                packageNameBar, sha256CertBar, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);
        assertThat(mVisibilityStore.isSchemaSearchableByCaller(
                "package",
                "database",
                "prefix/schemaBar",
                uidBar,
                /*callerHasSystemAccess=*/ false))
                .isTrue();

        // New .setVisibility() call completely overrides previous visibility settings. So
        // "schemaBar" settings aren't preserved.
        mVisibilityStore.setVisibility(
                "package",
                "database",
                /*schemasNotDisplayedBySystem=*/ Collections.emptySet(),
                /*schemasVisibleToPackages=*/ ImmutableMap.of(
                        "prefix/schemaFoo",
                        ImmutableList.of(new PackageIdentifier(packageNameFoo, sha256CertFoo))));

        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenReturn(uidFoo);
        when(mockPackageManager.hasSigningCertificate(
                packageNameFoo, sha256CertFoo, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);
        assertThat(mVisibilityStore.isSchemaSearchableByCaller(
                "package",
                "database",
                "prefix/schemaFoo",
                uidFoo,
                /*callerHasSystemAccess=*/ false))
                .isTrue();

        when(mockPackageManager.getPackageUid(eq(packageNameBar), /*flags=*/ anyInt()))
                .thenReturn(uidBar);
        when(mockPackageManager.hasSigningCertificate(
                packageNameBar, sha256CertBar, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);
        assertThat(mVisibilityStore.isSchemaSearchableByCaller(
                "package",
                "database",
                "prefix/schemaBar",
                uidBar,
                /*callerHasSystemAccess=*/ false))
                .isFalse();
    }

    @Test
    public void testIsSchemaSearchableByCaller_packageAccessibilityHandlesNameNotFoundException()
            throws Exception {
        // Values for a "foo" client
        String packageNameFoo = "packageFoo";
        byte[] sha256CertFoo = new byte[] {10};
        int uidFoo = 1;

        // Pretend we can't find the Foo package.
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenThrow(new PackageManager.NameNotFoundException());

        // Make sure "foo" doesn't have global query privileges
        when(mockPackageManager.checkPermission(READ_GLOBAL_APP_SEARCH_DATA, packageNameFoo))
                .thenReturn(PERMISSION_DENIED);

        // Grant package access
        mVisibilityStore.setVisibility(
                "package",
                "database",
                /*schemasNotDisplayedBySystem=*/ Collections.emptySet(),
                /*schemasVisibleToPackages=*/ ImmutableMap.of(
                        "prefix/schemaFoo",
                        ImmutableList.of(new PackageIdentifier(packageNameFoo, sha256CertFoo))));

        // If we can't verify the Foo package that has access, assume it doesn't have access.
        assertThat(mVisibilityStore.isSchemaSearchableByCaller(
                "package",
                "database",
                "prefix/schemaFoo",
                uidFoo,
                /*callerHasSystemAccess=*/ false))
                .isFalse();
    }

    @Test
    public void testEmptyPrefix() throws Exception {
        // Values for a "foo" client
        String packageNameFoo = "packageFoo";
        byte[] sha256CertFoo = new byte[] {10};
        int uidFoo = 1;

        // Set it up such that the test package has global query privileges, but "foo" doesn't.
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.checkPermission(
                READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_GRANTED);
        when(mockPackageManager.checkPermission(READ_GLOBAL_APP_SEARCH_DATA, packageNameFoo))
                .thenReturn(PERMISSION_DENIED);

        mVisibilityStore.setVisibility(
                /*packageName=*/ "",
                /*databaseName=*/ "",
                /*schemasNotDisplayedBySystem=*/ Collections.emptySet(),
                /*schemasVisibleToPackages=*/ ImmutableMap.of(
                        "schema",
                        ImmutableList.of(new PackageIdentifier(packageNameFoo, sha256CertFoo))));

        assertThat(
                        mVisibilityStore.isSchemaSearchableByCaller(
                                /*packageName=*/ "",
                                /*databaseName=*/ "",
                                "schema",
                                mUid,
                                /*callerHasSystemAccess=*/ true))
                .isTrue();

        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenReturn(uidFoo);
        when(mockPackageManager.hasSigningCertificate(
                packageNameFoo, sha256CertFoo, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);
        assertThat(
                        mVisibilityStore.isSchemaSearchableByCaller(
                                /*packageName=*/ "",
                                /*databaseName=*/ "",
                                "schema",
                                uidFoo,
                                /*callerHasSystemAccess=*/ false))
                .isTrue();
    }

    @NonNull
    private PackageManager getMockPackageManager(@NonNull UserHandle user) {
        PackageManager pm = mMockPackageManagers.get(user);
        if (pm == null) {
            pm = Mockito.mock(PackageManager.class);
            mMockPackageManagers.put(user, pm);
        }
        return pm;
    }
}
