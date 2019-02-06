/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.rollback;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.PackageRollbackInfo.RestoreInfo;
import android.util.IntArray;
import android.util.SparseLongArray;

import com.android.server.pm.Installer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

@RunWith(JUnit4.class)
public class AppDataRollbackHelperTest {

    @Test
    public void testSnapshotAppData() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = spy(new AppDataRollbackHelper(installer));

        // All users are unlocked so we should snapshot data for them.
        doReturn(true).when(helper).isUserCredentialLocked(eq(10));
        doReturn(true).when(helper).isUserCredentialLocked(eq(11));
        AppDataRollbackHelper.SnapshotAppDataResult result = helper.snapshotAppData("com.foo.bar",
                new int[]{10, 11});

        assertEquals(2, result.pendingBackups.size());
        assertEquals(10, result.pendingBackups.get(0));
        assertEquals(11, result.pendingBackups.get(1));

        assertEquals(0, result.ceSnapshotInodes.size());

        InOrder inOrder = Mockito.inOrder(installer);
        inOrder.verify(installer).snapshotAppData(
                eq("com.foo.bar"), eq(10), eq(Installer.FLAG_STORAGE_DE));
        inOrder.verify(installer).snapshotAppData(
                eq("com.foo.bar"), eq(11), eq(Installer.FLAG_STORAGE_DE));
        inOrder.verifyNoMoreInteractions();

        // One of the users is unlocked but the other isn't
        doReturn(false).when(helper).isUserCredentialLocked(eq(10));
        doReturn(true).when(helper).isUserCredentialLocked(eq(11));
        when(installer.snapshotAppData(anyString(), anyInt(), anyInt())).thenReturn(239L);

        result = helper.snapshotAppData("com.foo.bar", new int[]{10, 11});
        assertEquals(1, result.pendingBackups.size());
        assertEquals(11, result.pendingBackups.get(0));

        assertEquals(1, result.ceSnapshotInodes.size());
        assertEquals(239L, result.ceSnapshotInodes.get(10));

        inOrder = Mockito.inOrder(installer);
        inOrder.verify(installer).snapshotAppData(
                eq("com.foo.bar"), eq(10),
                eq(Installer.FLAG_STORAGE_CE | Installer.FLAG_STORAGE_DE));
        inOrder.verify(installer).snapshotAppData(
                eq("com.foo.bar"), eq(11), eq(Installer.FLAG_STORAGE_DE));
        inOrder.verifyNoMoreInteractions();
    }

    private static RollbackData createInProgressRollbackData(String packageName) {
        RollbackData data = new RollbackData(1, new File("/does/not/exist"), -1, true);
        data.packages.add(new PackageRollbackInfo(
                new VersionedPackage(packageName, 1), new VersionedPackage(packageName, 1),
                new IntArray(), new ArrayList<>(), false, new IntArray(), new SparseLongArray()));
        data.inProgress = true;

        return data;
    }

    @Test
    public void testRestoreAppDataSnapshot_noRollbackData() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = spy(new AppDataRollbackHelper(installer));

        assertFalse(helper.restoreAppData("com.foo", null, 0, 0, 0, "seinfo"));
        verifyZeroInteractions(installer);
    }

    @Test
    public void testRestoreAppDataSnapshot_noRollbackInProgress() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = spy(new AppDataRollbackHelper(installer));

        RollbackData rd = createInProgressRollbackData("com.foo");
        // Override the in progress flag.
        rd.inProgress = false;
        assertFalse(helper.restoreAppData("com.foo", rd, 0, 0, 0, "seinfo"));
        verifyZeroInteractions(installer);
    }

    @Test
    public void testRestoreAppDataSnapshot_pendingBackupForUser() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = spy(new AppDataRollbackHelper(installer));

        RollbackData rd = createInProgressRollbackData("com.foo");
        IntArray pendingBackups = rd.packages.get(0).getPendingBackups();
        pendingBackups.add(10);
        pendingBackups.add(11);

        assertTrue(helper.restoreAppData("com.foo", rd, 10 /* userId */, 1, 2, "seinfo"));

        // Should only require FLAG_STORAGE_DE here because we have a pending backup that we
        // didn't manage to execute.
        InOrder inOrder = Mockito.inOrder(installer);
        inOrder.verify(installer).restoreAppDataSnapshot(
                eq("com.foo"), eq(1), eq(2L), eq("seinfo"), eq(10), eq(Installer.FLAG_STORAGE_DE));
        inOrder.verifyNoMoreInteractions();

        assertEquals(1, pendingBackups.size());
        assertEquals(11, pendingBackups.get(0));
    }

    @Test
    public void testRestoreAppDataSnapshot_availableBackupForLockedUser() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = spy(new AppDataRollbackHelper(installer));
        doReturn(true).when(helper).isUserCredentialLocked(eq(10));

        RollbackData rd = createInProgressRollbackData("com.foo");

        assertTrue(helper.restoreAppData("com.foo", rd, 10 /* userId */, 1, 2, "seinfo"));

        InOrder inOrder = Mockito.inOrder(installer);
        inOrder.verify(installer).restoreAppDataSnapshot(
                eq("com.foo"), eq(1), eq(2L), eq("seinfo"), eq(10), eq(Installer.FLAG_STORAGE_DE));
        inOrder.verifyNoMoreInteractions();

        ArrayList<RestoreInfo> pendingRestores = rd.packages.get(0).getPendingRestores();
        assertEquals(1, pendingRestores.size());
        assertEquals(10, pendingRestores.get(0).userId);
        assertEquals(1, pendingRestores.get(0).appId);
        assertEquals("seinfo", pendingRestores.get(0).seInfo);
    }

    @Test
    public void testRestoreAppDataSnapshot_availableBackupForUnockedUser() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = spy(new AppDataRollbackHelper(installer));
        doReturn(false).when(helper).isUserCredentialLocked(eq(10));

        RollbackData rd = createInProgressRollbackData("com.foo");
        assertFalse(helper.restoreAppData("com.foo", rd, 10 /* userId */, 1, 2, "seinfo"));

        InOrder inOrder = Mockito.inOrder(installer);
        inOrder.verify(installer).restoreAppDataSnapshot(
                eq("com.foo"), eq(1), eq(2L), eq("seinfo"), eq(10),
                eq(Installer.FLAG_STORAGE_DE | Installer.FLAG_STORAGE_CE));
        inOrder.verifyNoMoreInteractions();

        ArrayList<RestoreInfo> pendingRestores = rd.packages.get(0).getPendingRestores();
        assertEquals(0, pendingRestores.size());
    }

    @Test
    public void destroyAppData() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = new AppDataRollbackHelper(installer);
        SparseLongArray ceSnapshotInodes = new SparseLongArray();
        ceSnapshotInodes.put(11, 239L);

        helper.destroyAppDataSnapshot("com.foo.bar", 10, 0L);
        helper.destroyAppDataSnapshot("com.foo.bar", 11, 239L);

        InOrder inOrder = Mockito.inOrder(installer);
        inOrder.verify(installer).destroyAppDataSnapshot(
                eq("com.foo.bar"), eq(10), eq(0L),
                eq(Installer.FLAG_STORAGE_DE));
        inOrder.verify(installer).destroyAppDataSnapshot(
                eq("com.foo.bar"), eq(11), eq(239L),
                eq(Installer.FLAG_STORAGE_DE | Installer.FLAG_STORAGE_CE));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void commitPendingBackupAndRestoreForUser_updatesRollbackData() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = new AppDataRollbackHelper(installer);

        ArrayList<RollbackData> changedRollbackData = new ArrayList<>();
        changedRollbackData.add(createInProgressRollbackData("com.foo.bar"));

        when(installer.snapshotAppData(anyString(), anyInt(), anyInt())).thenReturn(239L);

        ArrayList<String> pendingBackups = new ArrayList<>();
        pendingBackups.add("com.foo.bar");

        helper.commitPendingBackupAndRestoreForUser(11, pendingBackups,
                new HashMap<>() /* pendingRestores */, changedRollbackData);

        assertEquals(1, changedRollbackData.size());
        assertEquals(1, changedRollbackData.get(0).packages.size());
        PackageRollbackInfo info = changedRollbackData.get(0).packages.get(0);

        assertEquals(1, info.getCeSnapshotInodes().size());
        assertEquals(239L, info.getCeSnapshotInodes().get(11));

        InOrder inOrder = Mockito.inOrder(installer);
        inOrder.verify(installer).snapshotAppData("com.foo.bar", 11 /* userId */,
                Installer.FLAG_STORAGE_CE);
        inOrder.verifyNoMoreInteractions();
    }
}
