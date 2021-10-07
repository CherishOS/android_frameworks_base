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

package com.android.server.am;

import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.provider.DeviceConfig;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.appop.AppOpsService;
import com.android.server.testables.TestableDeviceConfig;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link CachedAppOptimizer}.
 *
 * Build/Install/Run:
 * atest FrameworksMockingServicesTests:CacheOomRankerTest
 */
@RunWith(MockitoJUnitRunner.class)
public class CacheOomRankerTest {

    @Mock
    private AppOpsService mAppOpsService;
    private Handler mHandler;
    private ActivityManagerService mAms;

    @Mock
    private PackageManagerInternal mPackageManagerInt;

    @Rule
    public final TestableDeviceConfig.TestableDeviceConfigRule
            mDeviceConfigRule = new TestableDeviceConfig.TestableDeviceConfigRule();
    @Rule
    public final ApplicationExitInfoTest.ServiceThreadRule
            mServiceThreadRule = new ApplicationExitInfoTest.ServiceThreadRule();

    private int mNextPid = 10000;
    private int mNextUid = 30000;
    private int mNextPackageUid = 40000;
    private int mNextPackageName = 1;

    private TestExecutor mExecutor = new TestExecutor();
    private CacheOomRanker mCacheOomRanker;

    @Before
    public void setUp() {
        HandlerThread handlerThread = new HandlerThread("");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        /* allowIo */
        ServiceThread thread = new ServiceThread("TestServiceThread",
                Process.THREAD_PRIORITY_DEFAULT,
                true /* allowIo */);
        thread.start();
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mAms = new ActivityManagerService(
                new TestInjector(context), mServiceThreadRule.getThread());
        mAms.mActivityTaskManager = new ActivityTaskManagerService(context);
        mAms.mActivityTaskManager.initialize(null, null, context.getMainLooper());
        mAms.mAtmInternal = spy(mAms.mActivityTaskManager.getAtmInternal());
        mAms.mPackageManagerInt = mPackageManagerInt;
        doReturn(new ComponentName("", "")).when(mPackageManagerInt).getSystemUiServiceComponent();
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInt);

        mCacheOomRanker = new CacheOomRanker(mAms);
        mCacheOomRanker.init(mExecutor);
    }

    @Test
    public void init_listensForConfigChanges() throws InterruptedException {
        mExecutor.init();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_USE_OOM_RE_RANKING,
                Boolean.TRUE.toString(), true);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.useOomReranking()).isTrue();
        mExecutor.init();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_USE_OOM_RE_RANKING, Boolean.FALSE.toString(), false);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.useOomReranking()).isFalse();

        mExecutor.init();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_NUMBER_TO_RE_RANK,
                Integer.toString(CacheOomRanker.DEFAULT_OOM_RE_RANKING_NUMBER_TO_RE_RANK + 2),
                false);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.getNumberToReRank())
                .isEqualTo(CacheOomRanker.DEFAULT_OOM_RE_RANKING_NUMBER_TO_RE_RANK + 2);

        mExecutor.init();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_LRU_WEIGHT,
                Float.toString(CacheOomRanker.DEFAULT_OOM_RE_RANKING_LRU_WEIGHT + 0.1f),
                false);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.mLruWeight)
                .isEqualTo(CacheOomRanker.DEFAULT_OOM_RE_RANKING_LRU_WEIGHT + 0.1f);

        mExecutor.init();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_RSS_WEIGHT,
                Float.toString(CacheOomRanker.DEFAULT_OOM_RE_RANKING_RSS_WEIGHT - 0.1f),
                false);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.mRssWeight)
                .isEqualTo(CacheOomRanker.DEFAULT_OOM_RE_RANKING_RSS_WEIGHT - 0.1f);

        mExecutor.init();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_USES_WEIGHT,
                Float.toString(CacheOomRanker.DEFAULT_OOM_RE_RANKING_USES_WEIGHT + 0.2f),
                false);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.mUsesWeight)
                .isEqualTo(CacheOomRanker.DEFAULT_OOM_RE_RANKING_USES_WEIGHT + 0.2f);
    }

    @Test
    public void reRankLruCachedApps_lruImpactsOrdering() throws InterruptedException {
        setConfig(/* numberToReRank= */ 5,
                /* usesWeight= */ 0.0f,
                /* pssWeight= */ 0.0f,
                /* lruWeight= */1.0f);

        ProcessList list = new ProcessList();
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord lastUsed40MinutesAgo = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(40).toMillis(), 10 * 1024L, 1000);
        processList.add(lastUsed40MinutesAgo);
        ProcessRecord lastUsed42MinutesAgo = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(42).toMillis(), 20 * 1024L, 2000);
        processList.add(lastUsed42MinutesAgo);
        ProcessRecord lastUsed60MinutesAgo = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(60).toMillis(), 1024L, 10000);
        processList.add(lastUsed60MinutesAgo);
        ProcessRecord lastUsed15MinutesAgo = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(15).toMillis(), 100 * 1024L, 10);
        processList.add(lastUsed15MinutesAgo);
        ProcessRecord lastUsed17MinutesAgo = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(17).toMillis(), 1024L, 20);
        processList.add(lastUsed17MinutesAgo);
        // Only re-ranking 5 entries so this should stay in most recent position.
        ProcessRecord lastUsed30MinutesAgo = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(30).toMillis(), 1024L, 20);
        processList.add(lastUsed30MinutesAgo);

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());

        // First 5 ordered by least recently used first, then last processes position unchanged.
        assertThat(processList).containsExactly(lastUsed60MinutesAgo, lastUsed42MinutesAgo,
                lastUsed40MinutesAgo, lastUsed17MinutesAgo, lastUsed15MinutesAgo,
                lastUsed30MinutesAgo);
    }

    @Test
    public void reRankLruCachedApps_rssImpactsOrdering() throws InterruptedException {
        setConfig(/* numberToReRank= */ 6,
                /* usesWeight= */ 0.0f,
                /* pssWeight= */ 1.0f,
                /* lruWeight= */ 0.0f);

        ProcessList list = new ProcessList();
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord rss10k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(40).toMillis(), 10 * 1024L, 1000);
        processList.add(rss10k);
        ProcessRecord rss20k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(42).toMillis(), 20 * 1024L, 2000);
        processList.add(rss20k);
        ProcessRecord rss1k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(60).toMillis(), 1024L, 10000);
        processList.add(rss1k);
        ProcessRecord rss100k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(15).toMillis(), 100 * 1024L, 10);
        processList.add(rss100k);
        ProcessRecord rss2k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(17).toMillis(), 2 * 1024L, 20);
        processList.add(rss2k);
        ProcessRecord rss15k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(30).toMillis(), 15 * 1024L, 20);
        processList.add(rss15k);
        // Only re-ranking 6 entries so this should stay in most recent position.
        ProcessRecord rss16k = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(30).toMillis(), 16 * 1024L, 20);
        processList.add(rss16k);

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());

        // First 6 ordered by largest pss, then last processes position unchanged.
        assertThat(processList).containsExactly(rss100k, rss20k, rss15k, rss10k, rss2k, rss1k,
                rss16k);
    }

    @Test
    public void reRankLruCachedApps_usesImpactsOrdering() throws InterruptedException {
        setConfig(/* numberToReRank= */ 4,
                /* usesWeight= */ 1.0f,
                /* pssWeight= */ 0.0f,
                /* lruWeight= */ 0.0f);

        ProcessList list = new ProcessList();
        list.setLruProcessServiceStartLSP(1);
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord used1000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(40).toMillis(), 10 * 1024L, 1000);
        processList.add(used1000);
        ProcessRecord used2000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(42).toMillis(), 20 * 1024L, 2000);
        processList.add(used2000);
        ProcessRecord used10 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(15).toMillis(), 100 * 1024L, 10);
        processList.add(used10);
        ProcessRecord used20 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(17).toMillis(), 2 * 1024L, 20);
        processList.add(used20);
        // Only re-ranking 6 entries so last two should stay in most recent position.
        ProcessRecord used500 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(30).toMillis(), 15 * 1024L, 500);
        processList.add(used500);
        ProcessRecord used200 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(30).toMillis(), 16 * 1024L, 200);
        processList.add(used200);

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());

        // First 4 ordered by uses, then last processes position unchanged.
        assertThat(processList).containsExactly(used10, used20, used1000, used2000, used500,
                used200);
    }

    @Test
    public void reRankLruCachedApps_notEnoughProcesses() throws InterruptedException {
        setConfig(/* numberToReRank= */ 4,
                /* usesWeight= */ 0.5f,
                /* pssWeight= */ 0.2f,
                /* lruWeight= */ 0.3f);

        ProcessList list = new ProcessList();
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord unknownAdj1 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(40).toMillis(), 10 * 1024L, 1000);
        processList.add(unknownAdj1);
        ProcessRecord unknownAdj2 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(42).toMillis(), 20 * 1024L, 2000);
        processList.add(unknownAdj2);
        ProcessRecord unknownAdj3 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(15).toMillis(), 100 * 1024L, 10);
        processList.add(unknownAdj3);
        ProcessRecord foregroundAdj = nextProcessRecord(ProcessList.FOREGROUND_APP_ADJ,
                Duration.ofMinutes(17).toMillis(), 2 * 1024L, 20);
        processList.add(foregroundAdj);
        ProcessRecord serviceAdj = nextProcessRecord(ProcessList.SERVICE_ADJ,
                Duration.ofMinutes(30).toMillis(), 15 * 1024L, 500);
        processList.add(serviceAdj);
        ProcessRecord systemAdj = nextProcessRecord(ProcessList.SYSTEM_ADJ,
                Duration.ofMinutes(30).toMillis(), 16 * 1024L, 200);
        processList.add(systemAdj);

        // 6 Processes but only 3 in eligible for cache so no re-ranking.
        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());

        // All positions unchanged.
        assertThat(processList).containsExactly(unknownAdj1, unknownAdj2, unknownAdj3,
                foregroundAdj, serviceAdj, systemAdj);
    }

    @Test
    public void reRankLruCachedApps_notEnoughNonServiceProcesses() throws InterruptedException {
        setConfig(/* numberToReRank= */ 4,
                /* usesWeight= */ 1.0f,
                /* pssWeight= */ 0.0f,
                /* lruWeight= */ 0.0f);

        ProcessList list = new ProcessList();
        list.setLruProcessServiceStartLSP(4);
        ArrayList<ProcessRecord> processList = list.getLruProcessesLSP();
        ProcessRecord used1000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(40).toMillis(), 10 * 1024L, 1000);
        processList.add(used1000);
        ProcessRecord used2000 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(42).toMillis(), 20 * 1024L, 2000);
        processList.add(used2000);
        ProcessRecord used10 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(15).toMillis(), 100 * 1024L, 10);
        processList.add(used10);
        ProcessRecord used20 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(17).toMillis(), 2 * 1024L, 20);
        processList.add(used20);
        ProcessRecord used500 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(30).toMillis(), 15 * 1024L, 500);
        processList.add(used500);
        ProcessRecord used200 = nextProcessRecord(ProcessList.UNKNOWN_ADJ,
                Duration.ofMinutes(30).toMillis(), 16 * 1024L, 200);
        processList.add(used200);

        mCacheOomRanker.reRankLruCachedAppsLSP(processList, list.getLruProcessServiceStartLOSP());

        // All positions unchanged.
        assertThat(processList).containsExactly(used1000, used2000, used10, used20, used500,
                used200);
    }

    private void setConfig(int numberToReRank, float useWeight, float pssWeight, float lruWeight)
            throws InterruptedException {
        mExecutor.init(4);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_NUMBER_TO_RE_RANK,
                Integer.toString(numberToReRank),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_LRU_WEIGHT,
                Float.toString(lruWeight),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_RSS_WEIGHT,
                Float.toString(pssWeight),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CacheOomRanker.KEY_OOM_RE_RANKING_USES_WEIGHT,
                Float.toString(useWeight),
                false);
        mExecutor.waitForLatch();
        assertThat(mCacheOomRanker.getNumberToReRank()).isEqualTo(numberToReRank);
        assertThat(mCacheOomRanker.mRssWeight).isEqualTo(pssWeight);
        assertThat(mCacheOomRanker.mUsesWeight).isEqualTo(useWeight);
        assertThat(mCacheOomRanker.mLruWeight).isEqualTo(lruWeight);
    }

    private ProcessRecord nextProcessRecord(int setAdj, long lastActivityTime, long lastRss,
            int returnedToCacheCount) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = "a.package.name" + mNextPackageName++;
        ProcessRecord app = new ProcessRecord(mAms, ai, ai.packageName + ":process", mNextUid++);
        app.setPid(mNextPid++);
        app.info.uid = mNextPackageUid++;
        // Exact value does not mater, it can be any state for which compaction is allowed.
        app.mState.setSetProcState(PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
        app.mState.setSetAdj(setAdj);
        app.setLastActivityTime(lastActivityTime);
        app.mProfile.setLastRss(lastRss);
        app.mState.setCached(false);
        for (int i = 0; i < returnedToCacheCount; ++i) {
            app.mState.setCached(false);
            app.mState.setCached(true);
        }
        return app;
    }

    private class TestExecutor implements Executor {
        private CountDownLatch mLatch;

        private void init(int count) {
            mLatch = new CountDownLatch(count);
        }

        private void init() {
            init(1);
        }

        private void waitForLatch() throws InterruptedException {
            mLatch.await(5, TimeUnit.SECONDS);
        }

        @Override
        public void execute(Runnable command) {
            command.run();
            mLatch.countDown();
        }
    }

    private class TestInjector extends ActivityManagerService.Injector {
        private TestInjector(Context context) {
            super(context);
        }

        @Override
        public AppOpsService getAppOpsService(File file, Handler handler) {
            return mAppOpsService;
        }

        @Override
        public Handler getUiHandler(ActivityManagerService service) {
            return mHandler;
        }
    }
}
