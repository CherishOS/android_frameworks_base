/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settingslib.drawer;

import android.app.ActivityManager;
import static org.mockito.Mockito.verify;
import android.content.IContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Pair;

import com.android.settingslib.SuggestionParser;
import com.android.settingslib.TestConfig;
import com.android.settingslib.drawer.TileUtilsTest;
import static org.mockito.Mockito.atLeastOnce;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;


@RunWith(RobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class TileUtilsTest {

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private Resources mResources;
    @Mock
    private UserManager mUserManager;
    @Mock
    private IContentProvider mIContentProvider;
    @Mock
    private ContentResolver mContentResolver;

    private static final String URI_GET_SUMMARY = "content://authority/text/summary";
    private static final String URI_GET_ICON = "content://authority/icon/my_icon";

    @Before
    public void setUp() throws NameNotFoundException {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getResourcesForApplication(anyString())).thenReturn(mResources);
        mContentResolver = spy(RuntimeEnvironment.application.getContentResolver());
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
    }

    @Test
    public void getTilesForIntent_shouldParseCategory() {
        final String testCategory = "category1";
        Intent intent = new Intent();
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        info.add(newInfo(true, testCategory));
        Map<Pair<String, String>, Tile> cache = new ArrayMap<>();
        when(mPackageManager.queryIntentActivitiesAsUser(eq(intent), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForIntent(mContext, UserHandle.CURRENT, intent, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */,
                false /* checkCategory */);

        assertThat(outTiles.size()).isEqualTo(1);
        assertThat(outTiles.get(0).category).isEqualTo(testCategory);
    }

    @Test
    public void getTilesForIntent_shouldParseKeyHintForSystemApp() {
        String keyHint = "key";
        Intent intent = new Intent();
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, keyHint);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(eq(intent), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForIntent(mContext, UserHandle.CURRENT, intent, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */,
                false /* checkCategory */);

        assertThat(outTiles.size()).isEqualTo(1);
        assertThat(outTiles.get(0).key).isEqualTo(keyHint);
    }

    @Test
    public void getTilesForIntent_shouldSkipNonSystemApp() {
        final String testCategory = "category1";
        Intent intent = new Intent();
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        info.add(newInfo(false, testCategory));

        when(mPackageManager.queryIntentActivitiesAsUser(eq(intent), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForIntent(mContext, UserHandle.CURRENT, intent, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */,
                false /* checkCategory */);

        assertThat(outTiles.isEmpty()).isTrue();
    }

    @Test
    public void getTilesForIntent_shouldSkipFilteredApps() {
        final String testCategory = "category1";
        Intent intent = new Intent();
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY);
        addMetadataToInfo(resolveInfo, "com.android.settings.require_account", "com.google");
        addMetadataToInfo(resolveInfo, "com.android.settings.require_connection", "true");
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(eq(intent), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForIntent(mContext, UserHandle.CURRENT, intent, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */,
                false /* checkCategory */);

        assertThat(outTiles.size()).isEqualTo(1);
        SuggestionParser parser = new SuggestionParser(mContext, null);
        parser.filterSuggestions(outTiles, 0, false);
        assertThat(outTiles.size()).isEqualTo(0);
    }

    @Test
    public void getCategories_shouldHandleExtraIntentAction() {
        final String testCategory = "category1";
        final String testAction = "action1";
        Map<Pair<String, String>, Tile> cache = new ArrayMap<>();
        List<ResolveInfo> info = new ArrayList<>();
        info.add(newInfo(true, testCategory));
        Global.putInt(mContext.getContentResolver(), Global.DEVICE_PROVISIONED, 1);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        List<UserHandle> userHandleList = new ArrayList<>();
        userHandleList.add(UserHandle.CURRENT);
        when(mUserManager.getUserProfiles()).thenReturn(userHandleList);

        when(mPackageManager.queryIntentActivitiesAsUser(argThat(new ArgumentMatcher<Intent>() {
            public boolean matches(Object event) {
                return testAction.equals(((Intent) event).getAction());
            }
        }), anyInt(), anyInt())).thenReturn(info);

        List<DashboardCategory> categoryList = TileUtils.getCategories(
            mContext, cache, false /* categoryDefinedInManifest */, testAction,
            TileUtils.SETTING_PKG);
        assertThat(categoryList.get(0).tiles.get(0).category).isEqualTo(testCategory);
    }

    @Test
    public void getCategories_withPackageName() throws Exception {
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        Map<Pair<String, String>, Tile> cache = new ArrayMap<>();
        Global.putInt(mContext.getContentResolver(), Global.DEVICE_PROVISIONED, 1);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        List<UserHandle> userHandleList = new ArrayList<>();

        userHandleList.add(new UserHandle(ActivityManager.getCurrentUser()));
        when(mUserManager.getUserProfiles()).thenReturn(userHandleList);

        TileUtils.getCategories(
            mContext, cache, false /* categoryDefinedInManifest */, null /* action */,
            TileUtils.SETTING_PKG);
        verify(mPackageManager, atLeastOnce()).queryIntentActivitiesAsUser(
            intentCaptor.capture(), anyInt(), anyInt());

        assertThat(intentCaptor.getAllValues().get(0).getPackage())
            .isEqualTo(TileUtils.SETTING_PKG);
    }

    @Test
    public void getTilesForIntent_shouldReadMetadataTitleAsString() throws RemoteException {
        Intent intent = new Intent();
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, "my title", 0);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(eq(intent), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForIntent(mContext, UserHandle.CURRENT, intent, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */,
                false /* checkCategory */);

        assertThat(outTiles.size()).isEqualTo(1);
        assertThat(outTiles.get(0).title).isEqualTo("my title");
    }

    @Test
    public void getTilesForIntent_shouldReadMetadataTitleFromResource() throws RemoteException {
        Intent intent = new Intent();
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, null, 123);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(eq(intent), anyInt(), anyInt()))
                .thenReturn(info);

        when(mResources.getString(eq(123)))
                .thenReturn("my localized title");

        TileUtils.getTilesForIntent(mContext, UserHandle.CURRENT, intent, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */,
                false /* checkCategory */);

        assertThat(outTiles.size()).isEqualTo(1);
        assertThat(outTiles.get(0).title).isEqualTo("my localized title");
    }

    @Test
    public void getTilesForIntent_shouldNotProcessInvalidUriContentSystemApp()
            throws RemoteException {
        Intent intent = new Intent();
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, null, URI_GET_SUMMARY);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(eq(intent), anyInt(), anyInt()))
                .thenReturn(info);

        // Case 1: No provider associated with the uri specified.
        TileUtils.getTilesForIntent(mContext, UserHandle.CURRENT, intent, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */,
                false /* checkCategory */);

        assertThat(outTiles.size()).isEqualTo(1);
        assertThat(outTiles.get(0).icon.getResId()).isEqualTo(314159);
        assertThat(outTiles.get(0).summary).isEqualTo("static-summary");

        // Case 2: Empty bundle.
        Bundle bundle = new Bundle();
        when(mIContentProvider.call(anyString(),
                eq(TileUtils.getMethodFromUri(Uri.parse(URI_GET_SUMMARY))), eq(URI_GET_SUMMARY),
                any())).thenReturn(bundle);
        when(mContentResolver.acquireUnstableProvider(anyString()))
                .thenReturn(mIContentProvider);
        when(mContentResolver.acquireUnstableProvider(any(Uri.class)))
                .thenReturn(mIContentProvider);

        TileUtils.getTilesForIntent(mContext, UserHandle.CURRENT, intent, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */,
                false /* checkCategory */);

        assertThat(outTiles.size()).isEqualTo(1);
        assertThat(outTiles.get(0).icon.getResId()).isEqualTo(314159);
        assertThat(outTiles.get(0).summary).isEqualTo("static-summary");
    }

    @Test
    public void getTilesForIntent_shouldProcessUriContentForSystemApp() throws RemoteException {
        Intent intent = new Intent();
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(eq(intent), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForIntent(mContext, UserHandle.CURRENT, intent, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */,
                false /* checkCategory */);

        assertThat(outTiles.size()).isEqualTo(1);
    }

    public static ResolveInfo newInfo(boolean systemApp, String category) {
        return newInfo(systemApp, category, null);
    }

    private static ResolveInfo newInfo(boolean systemApp, String category, String keyHint) {
        return newInfo(systemApp, category, keyHint, null, null);
    }

    private static ResolveInfo newInfo(boolean systemApp, String category, String keyHint,
            String iconUri, String summaryUri) {
        return newInfo(systemApp, category, keyHint, iconUri, summaryUri, null, 0);
    }

    private static ResolveInfo newInfo(boolean systemApp, String category, String keyHint,
            String iconUri, String summaryUri, String title, int titleResId) {

        ResolveInfo info = new ResolveInfo();
        info.system = systemApp;
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = "abc";
        info.activityInfo.name = "123";
        info.activityInfo.metaData = new Bundle();
        info.activityInfo.metaData.putString("com.android.settings.category", category);
        info.activityInfo.metaData.putInt("com.android.settings.icon", 314159);
        info.activityInfo.metaData.putString("com.android.settings.summary", "static-summary");
        if (keyHint != null) {
            info.activityInfo.metaData.putString("com.android.settings.keyhint", keyHint);
        }
        if (iconUri != null) {
            info.activityInfo.metaData.putString("com.android.settings.icon_uri", iconUri);
        }
        if (summaryUri != null) {
            info.activityInfo.metaData.putString("com.android.settings.summary_uri", summaryUri);
        }
        if (title != null) {
            info.activityInfo.metaData.putString(TileUtils.META_DATA_PREFERENCE_TITLE, title);
        }
        if (titleResId != 0) {
            info.activityInfo.metaData.putInt(
                    TileUtils.META_DATA_PREFERENCE_TITLE_RES_ID, titleResId);
        }
        info.activityInfo.applicationInfo = new ApplicationInfo();
        if (systemApp) {
            info.activityInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        }
        return info;
    }

    private void addMetadataToInfo(ResolveInfo info, String key, String value) {
        if (!TextUtils.isEmpty(key)) {
            if (info.activityInfo == null) {
                info.activityInfo = new ActivityInfo();
            }
            if (info.activityInfo.metaData == null) {
                info.activityInfo.metaData = new Bundle();
            }
            info.activityInfo.metaData.putString(key, value);
        }
    }
}
