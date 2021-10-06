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

package com.android.server.locales;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.Manifest;
import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.LocaleList;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal.PackageConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/**
 * Unit tests for the {@link LocaleManagerService}.
 */
@RunWith(AndroidJUnit4.class)
public class LocaleManagerServiceTest {
    private static final String DEFAULT_PACKAGE_NAME = "com.android.myapp";
    private static final int DEFAULT_USER_ID = 0;
    private static final int DEFAULT_UID = Binder.getCallingUid() + 100;
    private static final int INVALID_UID = -1;
    private static final String DEFAULT_LOCALE_TAGS = "en-XC, ar-XB";
    private static final LocaleList DEFAULT_LOCALES =
            LocaleList.forLanguageTags(DEFAULT_LOCALE_TAGS);

    private LocaleManagerService mLocaleManagerService;

    @Mock
    private Context mMockContext;
    @Mock
    private PackageManagerInternal mMockPackageManager;
    @Mock
    private FakePackageConfigurationUpdater mFakePackageConfigurationUpdater;
    @Mock
    private ActivityTaskManagerInternal mMockActivityTaskManager;
    @Mock
    private ActivityManagerInternal mMockActivityManager;

    @Before
    public void setUp() {
        mMockContext = mock(Context.class);
        mMockActivityTaskManager = mock(ActivityTaskManagerInternal.class);
        mMockActivityManager = mock(ActivityManagerInternal.class);
        mMockPackageManager = mock(PackageManagerInternal.class);

        mFakePackageConfigurationUpdater = new FakePackageConfigurationUpdater();
        doReturn(mFakePackageConfigurationUpdater)
                .when(mMockActivityTaskManager)
                .createPackageConfigurationUpdater(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
        doReturn(mFakePackageConfigurationUpdater)
                .when(mMockActivityTaskManager).createPackageConfigurationUpdater();

        doReturn(DEFAULT_USER_ID).when(mMockActivityManager)
                .handleIncomingUser(anyInt(), anyInt(), eq(DEFAULT_USER_ID), anyBoolean(), anyInt(),
                        anyString(), anyString());

        mLocaleManagerService = new LocaleManagerService(mMockContext, mMockActivityTaskManager,
                mMockActivityManager, mMockPackageManager);
    }

    @Test(expected = SecurityException.class)
    public void testSetApplicationLocales_arbitraryAppWithoutPermissions_fails() throws Exception {
        doReturn(DEFAULT_UID)
                .when(mMockPackageManager).getPackageUid(anyString(), anyInt(), anyInt());
        setUpFailingPermissionCheckFor(Manifest.permission.CHANGE_CONFIGURATION);

        try {
            mLocaleManagerService.setApplicationLocales(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID,
                    LocaleList.getEmptyLocaleList());
            fail("Expected SecurityException");
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.CHANGE_CONFIGURATION),
                    anyString());
            assertNoLocalesStored(mFakePackageConfigurationUpdater.getStoredLocales());
        }
    }

    @Test(expected = NullPointerException.class)
    public void testSetApplicationLocales_nullPackageName_fails() throws Exception {
        try {
            mLocaleManagerService.setApplicationLocales(/* appPackageName = */ null,
                    DEFAULT_USER_ID, LocaleList.getEmptyLocaleList());
            fail("Expected NullPointerException");
        } finally {
            assertNoLocalesStored(mFakePackageConfigurationUpdater.getStoredLocales());
        }
    }

    @Test(expected = NullPointerException.class)
    public void testSetApplicationLocales_nullLocaleList_fails() throws Exception {
        setUpPassingPermissionCheckFor(Manifest.permission.CHANGE_CONFIGURATION);

        try {
            mLocaleManagerService.setApplicationLocales(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID,
                    /* locales = */ null);
            fail("Expected NullPointerException");
        } finally {
            assertNoLocalesStored(mFakePackageConfigurationUpdater.getStoredLocales());
        }
    }


    @Test
    public void testSetApplicationLocales_arbitraryAppWithPermission_succeeds() throws Exception {
        doReturn(DEFAULT_UID)
                .when(mMockPackageManager).getPackageUid(anyString(), anyInt(), anyInt());
        // if package is not owned by the caller, the calling app should have the following
        //   permission. We will mock this to succeed to imitate that.
        setUpPassingPermissionCheckFor(Manifest.permission.CHANGE_CONFIGURATION);

        mLocaleManagerService.setApplicationLocales(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID,
                DEFAULT_LOCALES);

        assertEquals(DEFAULT_LOCALES, mFakePackageConfigurationUpdater.getStoredLocales());

    }

    @Test
    public void testSetApplicationLocales_callerOwnsPackage_succeeds() throws Exception {
        doReturn(Binder.getCallingUid())
                .when(mMockPackageManager).getPackageUid(anyString(), anyInt(), anyInt());

        mLocaleManagerService.setApplicationLocales(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID,
                DEFAULT_LOCALES);

        assertEquals(DEFAULT_LOCALES, mFakePackageConfigurationUpdater.getStoredLocales());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetApplicationLocales_invalidPackageOrUserId_fails() throws Exception {
        doReturn(INVALID_UID)
                .when(mMockPackageManager).getPackageUid(anyString(), anyInt(), anyInt());
        try {
            mLocaleManagerService.setApplicationLocales(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID,
                    LocaleList.getEmptyLocaleList());
            fail("Expected IllegalArgumentException");
        } finally {
            assertNoLocalesStored(mFakePackageConfigurationUpdater.getStoredLocales());
        }
    }

    @Test(expected = SecurityException.class)
    public void testGetApplicationLocales_arbitraryAppWithoutPermission_fails() throws Exception {
        doReturn(DEFAULT_UID).when(mMockPackageManager)
                .getPackageUid(anyString(), anyInt(), anyInt());
        setUpFailingPermissionCheckFor(Manifest.permission.READ_APP_SPECIFIC_LOCALES);

        try {
            mLocaleManagerService.getApplicationLocales(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);
            fail("Expected SecurityException");
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.READ_APP_SPECIFIC_LOCALES),
                    anyString());
        }
    }

    @Test
    public void testGetApplicationLocales_appSpecificConfigAbsent_returnsEmptyList()
            throws Exception {
        // any valid app calling for its own package or having appropriate permission
        doReturn(DEFAULT_UID).when(mMockPackageManager)
                .getPackageUid(anyString(), anyInt(), anyInt());
        setUpPassingPermissionCheckFor(Manifest.permission.READ_APP_SPECIFIC_LOCALES);
        doReturn(null)
                .when(mMockActivityTaskManager).getApplicationConfig(anyString(), anyInt());

        LocaleList locales = mLocaleManagerService.getApplicationLocales(
                    DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);

        assertEquals(LocaleList.getEmptyLocaleList(), locales);
    }

    @Test
    public void testGetApplicationLocales_appSpecificLocalesAbsent_returnsEmptyList()
            throws Exception {
        doReturn(DEFAULT_UID).when(mMockPackageManager)
                .getPackageUid(anyString(), anyInt(), anyInt());
        setUpPassingPermissionCheckFor(Manifest.permission.READ_APP_SPECIFIC_LOCALES);
        doReturn(new PackageConfig(/* nightMode = */ 0, /* locales = */ null))
                .when(mMockActivityTaskManager).getApplicationConfig(any(), anyInt());

        LocaleList locales = mLocaleManagerService.getApplicationLocales(
                DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);

        assertEquals(LocaleList.getEmptyLocaleList(), locales);
    }

    @Test
    public void testGetApplicationLocales_callerOwnsAppAndConfigPresent_returnsLocales()
            throws Exception {
        doReturn(Binder.getCallingUid()).when(mMockPackageManager)
                .getPackageUid(anyString(), anyInt(), anyInt());
        doReturn(new PackageConfig(/* nightMode = */ 0, DEFAULT_LOCALES))
                .when(mMockActivityTaskManager).getApplicationConfig(anyString(), anyInt());

        LocaleList locales =
                mLocaleManagerService.getApplicationLocales(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);

        assertEquals(DEFAULT_LOCALES, locales);
    }

    @Test
    public void testGetApplicationLocales_arbitraryCallerWithPermissions_returnsLocales()
            throws Exception {
        doReturn(DEFAULT_UID).when(mMockPackageManager)
                .getPackageUid(anyString(), anyInt(), anyInt());
        setUpPassingPermissionCheckFor(Manifest.permission.READ_APP_SPECIFIC_LOCALES);
        doReturn(new PackageConfig(/* nightMode = */ 0, DEFAULT_LOCALES))
                .when(mMockActivityTaskManager).getApplicationConfig(anyString(), anyInt());

        LocaleList locales =
                mLocaleManagerService.getApplicationLocales(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);

        assertEquals(DEFAULT_LOCALES, locales);
    }

    private static void assertNoLocalesStored(LocaleList locales) {
        assertNull(locales);
    }

    private void setUpFailingPermissionCheckFor(String permission) {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(eq(permission), any());
    }

    private void setUpPassingPermissionCheckFor(String permission) {
        doNothing().when(mMockContext).enforceCallingPermission(eq(permission), any());
    }
}
