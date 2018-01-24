/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server;

import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;

import static com.android.server.ForceAppStandbyTracker.TARGET_OP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.AppOpsManager.OpEntry;
import android.app.AppOpsManager.PackageOps;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;
import android.util.ArraySet;
import android.util.Pair;

import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.ForceAppStandbyTracker.Listener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ForceAppStandbyTrackerTest {

    private class ForceAppStandbyTrackerTestable extends ForceAppStandbyTracker {
        ForceAppStandbyTrackerTestable() {
            super(mMockContext, Looper.getMainLooper());
        }

        @Override
        AppOpsManager injectAppOpsManager() {
            return mMockAppOpsManager;
        }

        @Override
        IAppOpsService injectIAppOpsService() {
            return mMockIAppOpsService;
        }

        @Override
        IActivityManager injectIActivityManager() {
            return mMockIActivityManager;
        }

        @Override
        PowerManagerInternal injectPowerManagerInternal() {
            return mMockPowerManagerInternal;
        }

        @Override
        boolean isSmallBatteryDevice() { return mIsSmallBatteryDevice; };
    }

    private static final int UID_1 = Process.FIRST_APPLICATION_UID + 1;
    private static final int UID_2 = Process.FIRST_APPLICATION_UID + 2;
    private static final int UID_3 = Process.FIRST_APPLICATION_UID + 3;
    private static final int UID_10_1 = UserHandle.getUid(10, UID_1);
    private static final int UID_10_2 = UserHandle.getUid(10, UID_2);
    private static final int UID_10_3 = UserHandle.getUid(10, UID_3);
    private static final String PACKAGE_1 = "package1";
    private static final String PACKAGE_2 = "package2";
    private static final String PACKAGE_3 = "package3";
    private static final String PACKAGE_SYSTEM = "android";

    private Handler mMainHandler;

    @Mock
    private Context mMockContext;

    @Mock
    private IActivityManager mMockIActivityManager;

    @Mock
    private AppOpsManager mMockAppOpsManager;

    @Mock
    private IAppOpsService mMockIAppOpsService;

    @Mock
    private PowerManagerInternal mMockPowerManagerInternal;

    private IUidObserver mIUidObserver;
    private IAppOpsCallback.Stub mAppOpsCallback;
    private Consumer<PowerSaveState> mPowerSaveObserver;
    private BroadcastReceiver mReceiver;

    private MockContentResolver mMockContentResolver;
    private FakeSettingsProvider mFakeSettingsProvider;

    private boolean mPowerSaveMode;
    private boolean mIsSmallBatteryDevice;

    private final ArraySet<Pair<Integer, String>> mRestrictedPackages = new ArraySet();

    @Before
    public void setUp() {
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    private void waitUntilMainHandlerDrain() throws Exception {
        final CountDownLatch l = new CountDownLatch(1);
        mMainHandler.post(() -> {
            l.countDown();
        });
        assertTrue(l.await(5, TimeUnit.SECONDS));
    }

    private PowerSaveState getPowerSaveState() {
        return new PowerSaveState.Builder().setBatterySaverEnabled(mPowerSaveMode).build();
    }

    private ForceAppStandbyTrackerTestable newInstance() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mMockIAppOpsService.checkOperation(eq(TARGET_OP), anyInt(), anyString()))
                .thenAnswer(inv -> {
                    return mRestrictedPackages.indexOf(
                            Pair.create(inv.getArgument(1), inv.getArgument(2))) >= 0 ?
                            AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED;
                });

        final ForceAppStandbyTrackerTestable instance = new ForceAppStandbyTrackerTestable();

        return instance;
    }

    private void callStart(ForceAppStandbyTrackerTestable instance) throws RemoteException {

        // Set up functions that start() calls.
        when(mMockPowerManagerInternal.getLowPowerState(eq(ServiceType.FORCE_ALL_APPS_STANDBY)))
                .thenAnswer(inv -> getPowerSaveState());
        when(mMockAppOpsManager.getPackagesForOps(
                any(int[].class)
                )).thenAnswer(inv -> new ArrayList<AppOpsManager.PackageOps>());

        mMockContentResolver = new MockContentResolver();
        mFakeSettingsProvider = new FakeSettingsProvider();
        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
        mMockContentResolver.addProvider(Settings.AUTHORITY, mFakeSettingsProvider);

        // Call start.
        instance.start();

        // Capture the listeners.
        ArgumentCaptor<IUidObserver> uidObserverArgumentCaptor =
                ArgumentCaptor.forClass(IUidObserver.class);
        ArgumentCaptor<IAppOpsCallback.Stub> appOpsCallbackCaptor =
                ArgumentCaptor.forClass(IAppOpsCallback.Stub.class);
        ArgumentCaptor<Consumer<PowerSaveState>> powerSaveObserverCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);

        verify(mMockIActivityManager).registerUidObserver(
                uidObserverArgumentCaptor.capture(),
                eq(ActivityManager.UID_OBSERVER_GONE | ActivityManager.UID_OBSERVER_IDLE
                        | ActivityManager.UID_OBSERVER_ACTIVE),
                eq(ActivityManager.PROCESS_STATE_UNKNOWN),
                isNull());
        verify(mMockIAppOpsService).startWatchingMode(
                eq(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND),
                isNull(),
                appOpsCallbackCaptor.capture());
        verify(mMockPowerManagerInternal).registerLowPowerModeObserver(
                eq(ServiceType.FORCE_ALL_APPS_STANDBY),
                powerSaveObserverCaptor.capture());

        verify(mMockContext).registerReceiver(
                receiverCaptor.capture(), any(IntentFilter.class));

        mIUidObserver = uidObserverArgumentCaptor.getValue();
        mAppOpsCallback = appOpsCallbackCaptor.getValue();
        mPowerSaveObserver = powerSaveObserverCaptor.getValue();
        mReceiver = receiverCaptor.getValue();

        assertNotNull(mIUidObserver);
        assertNotNull(mAppOpsCallback);
        assertNotNull(mPowerSaveObserver);
        assertNotNull(mReceiver);
        assertNotNull(instance.mFlagsObserver);
    }

    private void setAppOps(int uid, String packageName, boolean restrict) throws RemoteException {
        final Pair p = Pair.create(uid, packageName);
        if (restrict) {
            mRestrictedPackages.add(p);
        } else {
            mRestrictedPackages.remove(p);
        }
        if (mAppOpsCallback != null) {
            mAppOpsCallback.opChanged(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, uid, packageName);
        }
    }

    private static final int NONE = 0;
    private static final int ALARMS_ONLY = 1 << 0;
    private static final int JOBS_ONLY = 1 << 1;
    private static final int JOBS_AND_ALARMS = ALARMS_ONLY | JOBS_ONLY;

    private void areRestricted(ForceAppStandbyTrackerTestable instance, int uid, String packageName,
            int restrictionTypes, boolean exemptFromBatterySaver) {
        assertEquals(((restrictionTypes & JOBS_ONLY) != 0),
                instance.areJobsRestricted(uid, packageName, exemptFromBatterySaver));
        assertEquals(((restrictionTypes & ALARMS_ONLY) != 0),
                instance.areAlarmsRestricted(uid, packageName));
    }

    private void areRestricted(ForceAppStandbyTrackerTestable instance, int uid, String packageName,
            int restrictionTypes) {
        areRestricted(instance, uid, packageName, restrictionTypes,
                /*exemptFromBatterySaver=*/ false);
    }

    @Test
    public void testAll() throws Exception {
        final ForceAppStandbyTrackerTestable instance = newInstance();
        callStart(instance);

        assertFalse(instance.isForceAllAppsStandbyEnabled());
        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, NONE);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        assertTrue(instance.isForceAllAppsStandbyEnabled());

        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        // Toggle the foreground state.
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        assertFalse(instance.isInForeground(UID_1));
        assertFalse(instance.isInForeground(UID_2));
        assertTrue(instance.isInForeground(Process.SYSTEM_UID));

        mIUidObserver.onUidActive(UID_1);
        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);
        assertTrue(instance.isInForeground(UID_1));
        assertFalse(instance.isInForeground(UID_2));

        mIUidObserver.onUidGone(UID_1, /*disable=*/ false);
        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);
        assertFalse(instance.isInForeground(UID_1));
        assertFalse(instance.isInForeground(UID_2));

        mIUidObserver.onUidActive(UID_1);
        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        mIUidObserver.onUidIdle(UID_1, /*disable=*/ false);
        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);
        assertFalse(instance.isInForeground(UID_1));
        assertFalse(instance.isInForeground(UID_2));

        // Toggle the app ops.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_2, PACKAGE_2));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_2, PACKAGE_2));

        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_10_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, NONE);
        areRestricted(instance, UID_10_2, PACKAGE_2, NONE);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        setAppOps(UID_1, PACKAGE_1, true);
        setAppOps(UID_10_2, PACKAGE_2, true);
        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_2, PACKAGE_2));
        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_2, PACKAGE_2));

        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, NONE);
        areRestricted(instance, UID_10_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        // Toggle power saver, should still be the same.
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, NONE);
        areRestricted(instance, UID_10_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        // Clear the app ops and update the whitelist.
        setAppOps(UID_1, PACKAGE_1, false);
        setAppOps(UID_10_2, PACKAGE_2, false);

        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        areRestricted(instance, UID_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_1, PACKAGE_1, JOBS_AND_ALARMS);
        areRestricted(instance, UID_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_2, PACKAGE_2, JOBS_AND_ALARMS);
        areRestricted(instance, UID_3, PACKAGE_3, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_3, PACKAGE_3, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_1}, new int[] {UID_2});

        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_10_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, ALARMS_ONLY);
        areRestricted(instance, UID_10_2, PACKAGE_2, ALARMS_ONLY);
        areRestricted(instance, UID_3, PACKAGE_3, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_3, PACKAGE_3, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        // Again, make sure toggling the global state doesn't change it.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        areRestricted(instance, UID_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_10_1, PACKAGE_1, NONE);
        areRestricted(instance, UID_2, PACKAGE_2, ALARMS_ONLY);
        areRestricted(instance, UID_10_2, PACKAGE_2, ALARMS_ONLY);
        areRestricted(instance, UID_3, PACKAGE_3, JOBS_AND_ALARMS);
        areRestricted(instance, UID_10_3, PACKAGE_3, JOBS_AND_ALARMS);
        areRestricted(instance, Process.SYSTEM_UID, PACKAGE_SYSTEM, NONE);

        assertTrue(instance.isUidPowerSaveWhitelisted(UID_1));
        assertTrue(instance.isUidPowerSaveWhitelisted(UID_10_1));
        assertFalse(instance.isUidPowerSaveWhitelisted(UID_2));
        assertFalse(instance.isUidPowerSaveWhitelisted(UID_10_2));

        assertFalse(instance.isUidTempPowerSaveWhitelisted(UID_1));
        assertFalse(instance.isUidTempPowerSaveWhitelisted(UID_10_1));
        assertTrue(instance.isUidTempPowerSaveWhitelisted(UID_2));
        assertTrue(instance.isUidTempPowerSaveWhitelisted(UID_10_2));
    }

    public void loadPersistedAppOps() throws Exception {
        final ForceAppStandbyTrackerTestable instance = newInstance();

        final List<PackageOps> ops = new ArrayList<>();

        //--------------------------------------------------
        List<OpEntry> entries = new ArrayList<>();
        entries.add(new AppOpsManager.OpEntry(
                AppOpsManager.OP_ACCESS_NOTIFICATIONS,
                AppOpsManager.MODE_IGNORED, 0, 0, 0, 0, null));
        entries.add(new AppOpsManager.OpEntry(
                ForceAppStandbyTracker.TARGET_OP,
                AppOpsManager.MODE_IGNORED, 0, 0, 0, 0, null));

        ops.add(new PackageOps(PACKAGE_1, UID_1, entries));

        //--------------------------------------------------
        entries = new ArrayList<>();
        entries.add(new AppOpsManager.OpEntry(
                ForceAppStandbyTracker.TARGET_OP,
                AppOpsManager.MODE_IGNORED, 0, 0, 0, 0, null));

        ops.add(new PackageOps(PACKAGE_2, UID_2, entries));

        //--------------------------------------------------
        entries = new ArrayList<>();
        entries.add(new AppOpsManager.OpEntry(
                ForceAppStandbyTracker.TARGET_OP,
                AppOpsManager.MODE_ALLOWED, 0, 0, 0, 0, null));

        ops.add(new PackageOps(PACKAGE_1, UID_10_1, entries));

        //--------------------------------------------------
        entries = new ArrayList<>();
        entries.add(new AppOpsManager.OpEntry(
                ForceAppStandbyTracker.TARGET_OP,
                AppOpsManager.MODE_IGNORED, 0, 0, 0, 0, null));
        entries.add(new AppOpsManager.OpEntry(
                AppOpsManager.OP_ACCESS_NOTIFICATIONS,
                AppOpsManager.MODE_IGNORED, 0, 0, 0, 0, null));

        ops.add(new PackageOps(PACKAGE_3, UID_10_3, entries));

        callStart(instance);

        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_1, PACKAGE_1));
        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_2, PACKAGE_2));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_3, PACKAGE_3));

        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_1, PACKAGE_1));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_2, PACKAGE_2));
        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_3, PACKAGE_3));
    }

    private void assertNoCallbacks(Listener l) throws Exception {
        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);
    }

    @Test
    public void testPowerSaveListener() throws Exception {
        final ForceAppStandbyTrackerTestable instance = newInstance();
        callStart(instance);

        ForceAppStandbyTracker.Listener l = mock(ForceAppStandbyTracker.Listener.class);
        instance.addListener(l);

        // Power save on.
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Power save off.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(1)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Updating to the same state should not fire listener
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        assertNoCallbacks(l);
    }

    @Test
    public void testAllListeners() throws Exception {
        final ForceAppStandbyTrackerTestable instance = newInstance();
        callStart(instance);

        ForceAppStandbyTracker.Listener l = mock(ForceAppStandbyTracker.Listener.class);
        instance.addListener(l);

        // -------------------------------------------------------------------------
        // Test with apppops.

        setAppOps(UID_10_2, PACKAGE_2, true);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(1)).updateJobsForUidPackage(eq(UID_10_2), eq(PACKAGE_2));

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        setAppOps(UID_10_2, PACKAGE_2, false);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(1)).updateJobsForUidPackage(eq(UID_10_2), eq(PACKAGE_2));

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(1)).unblockAlarmsForUidPackage(eq(UID_10_2), eq(PACKAGE_2));
        reset(l);

        setAppOps(UID_10_2, PACKAGE_2, false);

        verify(l, times(0)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());

        // Unrestrict while battery saver is on. Shouldn't fire.
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        // Note toggling appops while BS is on will suppress unblockAlarmsForUidPackage().
        setAppOps(UID_10_2, PACKAGE_2, true);

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(1)).updateJobsForUidPackage(eq(UID_10_2), eq(PACKAGE_2));

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Battery saver off.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(1)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // -------------------------------------------------------------------------
        // Tests with system/user/temp whitelist.

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_1, UID_2}, new int[] {});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_2}, new int[] {});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(1)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Update temp whitelist.
        instance.setPowerSaveWhitelistAppIds(new int[] {UID_2}, new int[] {UID_1, UID_3});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_2}, new int[] {UID_3});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Do the same thing with battery saver on. (Currently same callbacks are called.)
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_1, UID_2}, new int[] {});

        waitUntilMainHandlerDrain();
        // Called once for updating all whitelist and once for updating temp whitelist
        verify(l, times(2)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_2}, new int[] {});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(1)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Update temp whitelist.
        instance.setPowerSaveWhitelistAppIds(new int[] {UID_2}, new int[] {UID_1, UID_3});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        instance.setPowerSaveWhitelistAppIds(new int[] {UID_2}, new int[] {UID_3});

        waitUntilMainHandlerDrain();
        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(anyInt());
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);


        // -------------------------------------------------------------------------
        // Tests with proc state changes.

        // With battery save.
        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());

        mIUidObserver.onUidActive(UID_10_1);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1));
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(1)).unblockAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidGone(UID_10_1, true);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1));
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidActive(UID_10_1);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1));
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(1)).unblockAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidIdle(UID_10_1, true);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1));
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        // Without battery save.
        mPowerSaveMode = false;
        mPowerSaveObserver.accept(getPowerSaveState());

        verify(l, times(1)).updateAllJobs();
        verify(l, times(0)).updateJobsForUid(eq(UID_10_1));
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(1)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidActive(UID_10_1);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1));
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(1)).unblockAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidGone(UID_10_1, true);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1));
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidActive(UID_10_1);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1));
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(1)).unblockAlarmsForUid(eq(UID_10_1));
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);

        mIUidObserver.onUidIdle(UID_10_1, true);

        waitUntilMainHandlerDrain();
        verify(l, times(0)).updateAllJobs();
        verify(l, times(1)).updateJobsForUid(eq(UID_10_1));
        verify(l, times(0)).updateJobsForUidPackage(anyInt(), anyString());

        verify(l, times(0)).unblockAllUnrestrictedAlarms();
        verify(l, times(0)).unblockAlarmsForUid(anyInt());
        verify(l, times(0)).unblockAlarmsForUidPackage(anyInt(), anyString());
        reset(l);
    }

    @Test
    public void testUserRemoved() throws Exception {
        final ForceAppStandbyTrackerTestable instance = newInstance();
        callStart(instance);

        mIUidObserver.onUidActive(UID_1);
        mIUidObserver.onUidActive(UID_10_1);

        setAppOps(UID_2, PACKAGE_2, true);
        setAppOps(UID_10_2, PACKAGE_2, true);

        assertTrue(instance.isInForeground(UID_1));
        assertTrue(instance.isInForeground(UID_10_1));

        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_2, PACKAGE_2));
        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_2, PACKAGE_2));

        final Intent intent = new Intent(Intent.ACTION_USER_REMOVED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, 10);
        mReceiver.onReceive(mMockContext, intent);

        waitUntilMainHandlerDrain();

        assertTrue(instance.isInForeground(UID_1));
        assertFalse(instance.isInForeground(UID_10_1));

        assertFalse(instance.isRunAnyInBackgroundAppOpsAllowed(UID_2, PACKAGE_2));
        assertTrue(instance.isRunAnyInBackgroundAppOpsAllowed(UID_10_2, PACKAGE_2));
    }

    @Test
    public void testSmallBatteryAndPluggedIn() throws Exception {
        // This is a small battery device
        mIsSmallBatteryDevice = true;

        final ForceAppStandbyTrackerTestable instance = newInstance();
        callStart(instance);
        assertFalse(instance.isForceAllAppsStandbyEnabled());

        // Setting/experiment for all app standby for small battery is enabled
        Global.putInt(mMockContentResolver, Global.FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED, 1);
        instance.mFlagsObserver.onChange(true,
                Global.getUriFor(Global.FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED));
        assertTrue(instance.isForceAllAppsStandbyEnabled());

        // When battery is plugged in, force app standby is disabled
        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB);
        mReceiver.onReceive(mMockContext, intent);
        assertFalse(instance.isForceAllAppsStandbyEnabled());

        // When battery stops plugged in, force app standby is enabled
        mReceiver.onReceive(mMockContext, new Intent(Intent.ACTION_BATTERY_CHANGED));
        assertTrue(instance.isForceAllAppsStandbyEnabled());
    }

    @Test
    public void testNotSmallBatteryAndPluggedIn() throws Exception {
        // Not a small battery device, so plugged in status should not affect forced app standby
        mIsSmallBatteryDevice = false;

        final ForceAppStandbyTrackerTestable instance = newInstance();
        callStart(instance);
        assertFalse(instance.isForceAllAppsStandbyEnabled());

        mPowerSaveMode = true;
        mPowerSaveObserver.accept(getPowerSaveState());
        assertTrue(instance.isForceAllAppsStandbyEnabled());

        // When battery is plugged in, force app standby is unaffected
        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB);
        mReceiver.onReceive(mMockContext, intent);
        assertTrue(instance.isForceAllAppsStandbyEnabled());

        // When battery stops plugged in, force app standby is unaffected
        mReceiver.onReceive(mMockContext, new Intent(Intent.ACTION_BATTERY_CHANGED));
        assertTrue(instance.isForceAllAppsStandbyEnabled());
    }

    static int[] array(int... appIds) {
        Arrays.sort(appIds);
        return appIds;
    }

    private final Random mRandom = new Random();

    int[] makeRandomArray() {
        final ArrayList<Integer> list = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (mRandom.nextDouble() < 0.5) {
                list.add(i);
            }
        }
        return Arrays.stream(list.toArray(new Integer[list.size()]))
                .mapToInt(Integer::intValue).toArray();
    }

    static boolean isAnyAppIdUnwhitelistedSlow(int[] prevArray, int[] newArray) {
        Arrays.sort(newArray); // Just in case...
        for (int p : prevArray) {
            if (Arrays.binarySearch(newArray, p) < 0) {
                return true;
            }
        }
        return false;
    }

    private void checkAnyAppIdUnwhitelisted(int[] prevArray, int[] newArray, boolean expected) {
        assertEquals("Input: " + Arrays.toString(prevArray) + " " + Arrays.toString(newArray),
                expected, ForceAppStandbyTracker.isAnyAppIdUnwhitelisted(prevArray, newArray));

        // Also test isAnyAppIdUnwhitelistedSlow.
        assertEquals("Input: " + Arrays.toString(prevArray) + " " + Arrays.toString(newArray),
                expected, isAnyAppIdUnwhitelistedSlow(prevArray, newArray));
    }

    @Test
    public void isAnyAppIdUnwhitelisted() {
        checkAnyAppIdUnwhitelisted(array(), array(), false);

        checkAnyAppIdUnwhitelisted(array(1), array(), true);
        checkAnyAppIdUnwhitelisted(array(1), array(1), false);
        checkAnyAppIdUnwhitelisted(array(1), array(0, 1), false);
        checkAnyAppIdUnwhitelisted(array(1), array(0, 1, 2), false);
        checkAnyAppIdUnwhitelisted(array(1), array(0, 1, 2), false);

        checkAnyAppIdUnwhitelisted(array(1, 2, 10), array(), true);
        checkAnyAppIdUnwhitelisted(array(1, 2, 10), array(1, 2), true);
        checkAnyAppIdUnwhitelisted(array(1, 2, 10), array(1, 2, 10), false);
        checkAnyAppIdUnwhitelisted(array(1, 2, 10), array(2, 10), true);
        checkAnyAppIdUnwhitelisted(array(1, 2, 10), array(0, 1, 2, 4, 3, 10), false);
        checkAnyAppIdUnwhitelisted(array(1, 2, 10), array(0, 0, 1, 2, 10), false);

        // Random test
        int trueCount = 0;
        final int count = 10000;
        for (int i = 0; i < count; i++) {
            final int[] array1 = makeRandomArray();
            final int[] array2 = makeRandomArray();

            final boolean expected = isAnyAppIdUnwhitelistedSlow(array1, array2);
            final boolean actual = ForceAppStandbyTracker.isAnyAppIdUnwhitelisted(array1, array2);

            assertEquals("Input: " + Arrays.toString(array1) + " " + Arrays.toString(array2),
                    expected, actual);
            if (expected) {
                trueCount++;
            }
        }

        // Make sure makeRandomArray() didn't generate all same arrays by accident.
        assertTrue(trueCount > 0);
        assertTrue(trueCount < count);
    }
}
