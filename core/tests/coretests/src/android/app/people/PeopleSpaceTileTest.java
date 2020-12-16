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

package android.app.people;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PeopleSpaceTileTest {

    private Context mContext;
    private final Drawable mDrawable = new ColorDrawable(Color.BLUE);
    private final Icon mIcon = PeopleSpaceTile.convertDrawableToIcon(mDrawable);

    @Mock
    private LauncherApps mLauncherApps;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
        MockitoAnnotations.initMocks(this);
        when(mLauncherApps.getShortcutIconDrawable(any(), eq(0))).thenReturn(mDrawable);
    }

    @Test
    public void testId() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertThat(tile.getId()).isEqualTo("123");

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setId("5")
                .build();
        assertThat(tile.getId()).isEqualTo("5");

        tile = new PeopleSpaceTile.Builder("12", null, null, null).build();
        assertThat(tile.getId()).isEqualTo("12");
    }

    @Test
    public void testUserName() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertThat(tile.getUserName()).isNull();

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setUserName("Name 1")
                .build();
        assertThat(tile.getUserName()).isEqualTo("Name 1");

        tile = new PeopleSpaceTile.Builder(null, "Name 2", null, null).build();
        assertThat(tile.getUserName()).isEqualTo("Name 2");
    }

    @Test
    public void testUserIcon() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).setUserIcon(
                mIcon).build();
        assertThat(tile.getUserIcon().toString()).isEqualTo(mIcon.toString());

        tile = new PeopleSpaceTile.Builder("12", null, mIcon,
                null).build();
        assertThat(tile.getUserIcon().toString()).isEqualTo(mIcon.toString());
    }

    @Test
    public void testContactUri() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).setContactUri(
                Uri.parse("test")).build();

        assertThat(tile.getContactUri()).isEqualTo(Uri.parse("test"));
    }

    @Test
    public void testUid() {
        PeopleSpaceTile tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setUid(42)
                .build();

        assertThat(tile.getUid()).isEqualTo(42);
    }

    @Test
    public void testPackageName() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        // Automatically added by creating a ShortcutInfo.
        assertThat(tile.getPackageName()).isEqualTo("com.android.frameworks.coretests");

        tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).setPackageName(
                "package.name").build();
        assertThat(tile.getPackageName()).isEqualTo("package.name");

        tile = new PeopleSpaceTile.Builder("12", null, null,
                new Intent().setPackage("intent.package")).build();
        assertThat(tile.getPackageName()).isEqualTo("intent.package");
    }

    @Test
    public void testLastInteractionTimestamp() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertThat(tile.getLastInteractionTimestamp()).isEqualTo(0L);

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setLastInteractionTimestamp(7L)
                .build();
        assertThat(tile.getLastInteractionTimestamp()).isEqualTo(7L);
    }

    @Test
    public void testImportantConversation() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertFalse(tile.isImportantConversation());

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setIsImportantConversation(true)
                .build();
        assertTrue(tile.isImportantConversation());
    }

    @Test
    public void testHiddenConversation() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertFalse(tile.isHiddenConversation());

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setIsHiddenConversation(true)
                .build();
        assertTrue(tile.isHiddenConversation());
    }

    @Test
    public void testNotificationKey() {
        PeopleSpaceTile tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setNotificationKey("test")
                .build();

        assertThat(tile.getNotificationKey()).isEqualTo("test");
    }

    @Test
    public void testNotificationContent() {
        PeopleSpaceTile tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setNotificationContent("test")
                .build();

        assertThat(tile.getNotificationContent()).isEqualTo("test");
    }

    @Test
    public void testNotificationDataUri() {
        PeopleSpaceTile tile =
                new PeopleSpaceTile.Builder(new ShortcutInfo.Builder(mContext, "123").build(),
                        mLauncherApps)
                .setNotificationDataUri(Uri.parse("test"))
                .build();

        assertThat(tile.getNotificationDataUri()).isEqualTo(Uri.parse("test"));
    }

    @Test
    public void testIntent() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertThat(tile.getIntent()).isNull();

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setIntent(new Intent())
                .build();
        assertThat(tile.getIntent().toString()).isEqualTo(new Intent().toString());

        tile = new PeopleSpaceTile.Builder("12", null, null, new Intent()).build();
        assertThat(tile.getIntent().toString()).isEqualTo(new Intent().toString());
    }

}
