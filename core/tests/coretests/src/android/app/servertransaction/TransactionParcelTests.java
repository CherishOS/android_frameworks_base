/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import android.app.IApplicationThread;
import android.app.IInstrumentationWatcher;
import android.app.IUiAutomationConnection;
import android.app.ProfilerInfo;
import android.app.ResultInfo;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.ReferrerIntent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Test parcelling and unparcelling of transactions and transaction items. */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class TransactionParcelTests {

    private Parcel mParcel;

    @Before
    public void setUp() throws Exception {
        mParcel = Parcel.obtain();
    }

    @Test
    public void testConfigurationChange() {
        // Write to parcel
        ConfigurationChangeItem item = new ConfigurationChangeItem(config());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ConfigurationChangeItem result = ConfigurationChangeItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testActivityConfigChange() {
        // Write to parcel
        ActivityConfigurationChangeItem item = new ActivityConfigurationChangeItem(config());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ActivityConfigurationChangeItem result =
                ActivityConfigurationChangeItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testMoveToDisplay() {
        // Write to parcel
        MoveToDisplayItem item = new MoveToDisplayItem(4 /* targetDisplayId */, config());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        MoveToDisplayItem result = MoveToDisplayItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testNewIntent() {
        // Write to parcel
        NewIntentItem item = new NewIntentItem(referrerIntentList(), true /* pause */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        NewIntentItem result = NewIntentItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testActivityResult() {
        // Write to parcel
        ActivityResultItem item = new ActivityResultItem(resultInfoList());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ActivityResultItem result = ActivityResultItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testPipModeChange() {
        // Write to parcel
        PipModeChangeItem item = new PipModeChangeItem(true /* isInPipMode */, config());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        PipModeChangeItem result = PipModeChangeItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testMultiWindowModeChange() {
        // Write to parcel
        MultiWindowModeChangeItem item = new MultiWindowModeChangeItem(
                true /* isInMultiWindowMode */, config());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        MultiWindowModeChangeItem result =
                MultiWindowModeChangeItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testWindowVisibilityChange() {
        // Write to parcel
        WindowVisibilityItem item = new WindowVisibilityItem(true /* showWindow */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        WindowVisibilityItem result = WindowVisibilityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));

        // Check different value
        item = new WindowVisibilityItem(false);

        mParcel = Parcel.obtain();
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        result = WindowVisibilityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testDestroy() {
        DestroyActivityItem item = new DestroyActivityItem(true /* finished */,
                135 /* configChanges */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        DestroyActivityItem result = DestroyActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testLaunch() {
        // Write to parcel
        Intent intent = new Intent("action");
        int ident = 57;
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.flags = 42;
        activityInfo.maxAspectRatio = 2.4f;
        activityInfo.launchToken = "token";
        activityInfo.applicationInfo = new ApplicationInfo();
        activityInfo.packageName = "packageName";
        activityInfo.name = "name";
        Configuration overrideConfig = new Configuration();
        overrideConfig.assetsSeq = 5;
        CompatibilityInfo compat = CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;
        String referrer = "referrer";
        int procState = 4;
        Bundle bundle = new Bundle();
        bundle.putString("key", "value");
        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putInt("k", 4);

        LaunchActivityItem item = new LaunchActivityItem(intent, ident, activityInfo,
                config(), overrideConfig, compat, referrer, null /* voiceInteractor */,
                procState, bundle, persistableBundle, resultInfoList(), referrerIntentList(),
                true /* isForward */, null /* profilerInfo */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        LaunchActivityItem result = LaunchActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testPause() {
        // Write to parcel
        PauseActivityItem item = new PauseActivityItem(true /* finished */, true /* userLeaving */,
                135 /* configChanges */, true /* dontReport */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        PauseActivityItem result = PauseActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testResume() {
        // Write to parcel
        ResumeActivityItem item = new ResumeActivityItem(27 /* procState */, true /* isForward */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ResumeActivityItem result = ResumeActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testStop() {
        // Write to parcel
        StopActivityItem item = new StopActivityItem(true /* showWindow */, 14 /* configChanges */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        StopActivityItem result = StopActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testClientTransaction() {
        // Write to parcel
        WindowVisibilityItem callback1 = new WindowVisibilityItem(true);
        ActivityConfigurationChangeItem callback2 = new ActivityConfigurationChangeItem(config());

        StopActivityItem lifecycleRequest = new StopActivityItem(true /* showWindow */,
                78 /* configChanges */);

        IApplicationThread appThread = new StubAppThread();
        Binder activityToken = new Binder();

        ClientTransaction transaction = new ClientTransaction(appThread, activityToken);
        transaction.addCallback(callback1);
        transaction.addCallback(callback2);
        transaction.setLifecycleStateRequest(lifecycleRequest);

        writeAndPrepareForReading(transaction);

        // Read from parcel and assert
        ClientTransaction result = ClientTransaction.CREATOR.createFromParcel(mParcel);

        assertEquals(transaction.hashCode(), result.hashCode());
        assertTrue(transaction.equals(result));
    }

    @Test
    public void testClientTransactionCallbacksOnly() {
        // Write to parcel
        WindowVisibilityItem callback1 = new WindowVisibilityItem(true);
        ActivityConfigurationChangeItem callback2 = new ActivityConfigurationChangeItem(config());

        IApplicationThread appThread = new StubAppThread();
        Binder activityToken = new Binder();

        ClientTransaction transaction = new ClientTransaction(appThread, activityToken);
        transaction.addCallback(callback1);
        transaction.addCallback(callback2);

        writeAndPrepareForReading(transaction);

        // Read from parcel and assert
        ClientTransaction result = ClientTransaction.CREATOR.createFromParcel(mParcel);

        assertEquals(transaction.hashCode(), result.hashCode());
        assertTrue(transaction.equals(result));
    }

    @Test
    public void testClientTransactionLifecycleOnly() {
        // Write to parcel
        StopActivityItem lifecycleRequest = new StopActivityItem(true /* showWindow */,
                78 /* configChanges */);

        IApplicationThread appThread = new StubAppThread();
        Binder activityToken = new Binder();

        ClientTransaction transaction = new ClientTransaction(appThread, activityToken);
        transaction.setLifecycleStateRequest(lifecycleRequest);

        writeAndPrepareForReading(transaction);

        // Read from parcel and assert
        ClientTransaction result = ClientTransaction.CREATOR.createFromParcel(mParcel);

        assertEquals(transaction.hashCode(), result.hashCode());
        assertTrue(transaction.equals(result));
    }

    private static List<ResultInfo> resultInfoList() {
        String resultWho1 = "resultWho1";
        int requestCode1 = 7;
        int resultCode1 = 4;
        Intent data1 = new Intent("action1");
        ResultInfo resultInfo1 = new ResultInfo(resultWho1, requestCode1, resultCode1, data1);

        String resultWho2 = "resultWho2";
        int requestCode2 = 8;
        int resultCode2 = 6;
        Intent data2 = new Intent("action2");
        ResultInfo resultInfo2 = new ResultInfo(resultWho2, requestCode2, resultCode2, data2);

        List<ResultInfo> resultInfoList = new ArrayList<>();
        resultInfoList.add(resultInfo1);
        resultInfoList.add(resultInfo2);

        return resultInfoList;
    }

    private static List<ReferrerIntent> referrerIntentList() {
        Intent intent1 = new Intent("action1");
        ReferrerIntent referrerIntent1 = new ReferrerIntent(intent1, "referrer1");

        Intent intent2 = new Intent("action2");
        ReferrerIntent referrerIntent2 = new ReferrerIntent(intent2, "referrer2");

        List<ReferrerIntent> referrerIntents = new ArrayList<>();
        referrerIntents.add(referrerIntent1);
        referrerIntents.add(referrerIntent2);

        return referrerIntents;
    }

    private static Configuration config() {
        Configuration config = new Configuration();
        config.densityDpi = 10;
        config.fontScale = 0.3f;
        config.screenHeightDp = 15;
        config.orientation = ORIENTATION_LANDSCAPE;
        return config;
    }

    /** Write to {@link #mParcel} and reset its position to prepare for reading from the start. */
    private void writeAndPrepareForReading(Parcelable parcelable) {
        parcelable.writeToParcel(mParcel, 0 /* flags */);
        mParcel.setDataPosition(0);
    }

    /** Stub implementation of IApplicationThread that can be presented as {@link Binder}. */
    class StubAppThread extends android.app.IApplicationThread.Stub  {

        @Override
        public void scheduleTransaction(ClientTransaction transaction) throws RemoteException {
        }

        @Override
        public void scheduleReceiver(Intent intent, ActivityInfo activityInfo,
                CompatibilityInfo compatibilityInfo, int i, String s, Bundle bundle, boolean b,
                int i1, int i2) throws RemoteException {
        }

        @Override
        public void scheduleCreateService(IBinder iBinder, ServiceInfo serviceInfo,
                CompatibilityInfo compatibilityInfo, int i) throws RemoteException {
        }

        @Override
        public void scheduleStopService(IBinder iBinder) throws RemoteException {
        }

        @Override
        public void bindApplication(String s, ApplicationInfo applicationInfo,
                List<ProviderInfo> list, ComponentName componentName, ProfilerInfo profilerInfo,
                Bundle bundle, IInstrumentationWatcher iInstrumentationWatcher,
                IUiAutomationConnection iUiAutomationConnection, int i, boolean b, boolean b1,
                boolean b2, boolean b3, Configuration configuration,
                CompatibilityInfo compatibilityInfo, Map map, Bundle bundle1, String s1)
                throws RemoteException {
        }

        @Override
        public void scheduleExit() throws RemoteException {
        }

        @Override
        public void scheduleServiceArgs(IBinder iBinder, ParceledListSlice parceledListSlice)
                throws RemoteException {
        }

        @Override
        public void updateTimeZone() throws RemoteException {
        }

        @Override
        public void processInBackground() throws RemoteException {
        }

        @Override
        public void scheduleBindService(IBinder iBinder, Intent intent, boolean b, int i)
                throws RemoteException {
        }

        @Override
        public void scheduleUnbindService(IBinder iBinder, Intent intent) throws RemoteException {
        }

        @Override
        public void dumpService(ParcelFileDescriptor parcelFileDescriptor, IBinder iBinder,
                String[] strings) throws RemoteException {
        }

        @Override
        public void scheduleRegisteredReceiver(IIntentReceiver iIntentReceiver, Intent intent,
                int i, String s, Bundle bundle, boolean b, boolean b1, int i1, int i2)
                throws RemoteException {
        }

        @Override
        public void scheduleLowMemory() throws RemoteException {
        }

        @Override
        public void scheduleRelaunchActivity(IBinder iBinder, List<ResultInfo> list,
                List<ReferrerIntent> list1, int i, boolean b, Configuration configuration,
                Configuration configuration1, boolean b1) throws RemoteException {
        }

        @Override
        public void scheduleSleeping(IBinder iBinder, boolean b) throws RemoteException {
        }

        @Override
        public void profilerControl(boolean b, ProfilerInfo profilerInfo, int i)
                throws RemoteException {
        }

        @Override
        public void setSchedulingGroup(int i) throws RemoteException {
        }

        @Override
        public void scheduleCreateBackupAgent(ApplicationInfo applicationInfo,
                CompatibilityInfo compatibilityInfo, int i) throws RemoteException {
        }

        @Override
        public void scheduleDestroyBackupAgent(ApplicationInfo applicationInfo,
                CompatibilityInfo compatibilityInfo) throws RemoteException {
        }

        @Override
        public void scheduleOnNewActivityOptions(IBinder iBinder, Bundle bundle)
                throws RemoteException {
        }

        @Override
        public void scheduleSuicide() throws RemoteException {
        }

        @Override
        public void dispatchPackageBroadcast(int i, String[] strings) throws RemoteException {
        }

        @Override
        public void scheduleCrash(String s) throws RemoteException {
        }

        @Override
        public void dumpActivity(ParcelFileDescriptor parcelFileDescriptor, IBinder iBinder,
                String s, String[] strings) throws RemoteException {
        }

        @Override
        public void clearDnsCache() throws RemoteException {
        }

        @Override
        public void setHttpProxy(String s, String s1, String s2, Uri uri) throws RemoteException {
        }

        @Override
        public void setCoreSettings(Bundle bundle) throws RemoteException {
        }

        @Override
        public void updatePackageCompatibilityInfo(String s, CompatibilityInfo compatibilityInfo)
                throws RemoteException {
        }

        @Override
        public void scheduleTrimMemory(int i) throws RemoteException {
        }

        @Override
        public void dumpMemInfo(ParcelFileDescriptor parcelFileDescriptor,
                Debug.MemoryInfo memoryInfo, boolean b, boolean b1, boolean b2, boolean b3,
                boolean b4, String[] strings) throws RemoteException {
        }

        @Override
        public void dumpGfxInfo(ParcelFileDescriptor parcelFileDescriptor, String[] strings)
                throws RemoteException {
        }

        @Override
        public void dumpProvider(ParcelFileDescriptor parcelFileDescriptor, IBinder iBinder,
                String[] strings) throws RemoteException {
        }

        @Override
        public void dumpDbInfo(ParcelFileDescriptor parcelFileDescriptor, String[] strings)
                throws RemoteException {
        }

        @Override
        public void unstableProviderDied(IBinder iBinder) throws RemoteException {
        }

        @Override
        public void requestAssistContextExtras(IBinder iBinder, IBinder iBinder1, int i, int i1,
                int i2) throws RemoteException {
        }

        @Override
        public void scheduleTranslucentConversionComplete(IBinder iBinder, boolean b)
                throws RemoteException {
        }

        @Override
        public void setProcessState(int i) throws RemoteException {
        }

        @Override
        public void scheduleInstallProvider(ProviderInfo providerInfo) throws RemoteException {
        }

        @Override
        public void updateTimePrefs(int i) throws RemoteException {
        }

        @Override
        public void scheduleEnterAnimationComplete(IBinder iBinder) throws RemoteException {
        }

        @Override
        public void notifyCleartextNetwork(byte[] bytes) throws RemoteException {
        }

        @Override
        public void startBinderTracking() throws RemoteException {
        }

        @Override
        public void stopBinderTrackingAndDump(ParcelFileDescriptor parcelFileDescriptor)
                throws RemoteException {
        }

        @Override
        public void scheduleLocalVoiceInteractionStarted(IBinder iBinder,
                IVoiceInteractor iVoiceInteractor) throws RemoteException {
        }

        @Override
        public void handleTrustStorageUpdate() throws RemoteException {
        }

        @Override
        public void attachAgent(String s) throws RemoteException {
        }

        @Override
        public void scheduleApplicationInfoChanged(ApplicationInfo applicationInfo)
                throws RemoteException {
        }

        @Override
        public void setNetworkBlockSeq(long l) throws RemoteException {
        }

        @Override
        public void dumpHeap(boolean managed, boolean mallocInfo, boolean runGc, String path,
                ParcelFileDescriptor fd) {
        }

        @Override
        public final void runIsolatedEntryPoint(String entryPoint, String[] entryPointArgs) {
        }
    }
}
