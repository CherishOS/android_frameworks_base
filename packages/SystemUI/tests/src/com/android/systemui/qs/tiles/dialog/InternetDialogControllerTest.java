package com.android.systemui.qs.tiles.dialog;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.settingslib.Utils;
import com.android.settingslib.wifi.WifiUtils;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.AccessPointController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.wifitrackerlib.WifiEntry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class InternetDialogControllerTest extends SysuiTestCase {

    private static final int SUB_ID = 1;
    private static final String CONNECTED_TITLE = "Connected Wi-Fi Title";
    private static final String CONNECTED_SUMMARY = "Connected Wi-Fi Summary";

    @Mock
    private WifiManager mWifiManager;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private SubscriptionManager mSubscriptionManager;
    @Mock
    private Handler mHandler;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private GlobalSettings mGlobalSettings;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private NetworkController.AccessPointController mAccessPointController;
    @Mock
    private WifiEntry mConnectedEntry;
    @Mock
    private WifiInfo mWifiInfo;
    @Mock
    private ServiceState mServiceState;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private WifiUtils.InternetIconInjector mWifiIconInjector;

    private MockInternetDialogController mInternetDialogController;
    private FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(SUB_ID);
        when(mWifiManager.getConnectionInfo()).thenReturn(mWifiInfo);
        when(mConnectedEntry.isDefaultNetwork()).thenReturn(true);

        mInternetDialogController = new MockInternetDialogController(mContext,
                mock(UiEventLogger.class), mock(ActivityStarter.class), mAccessPointController,
                mSubscriptionManager, mTelephonyManager, mWifiManager,
                mock(ConnectivityManager.class), mHandler, mExecutor, mBroadcastDispatcher,
                mKeyguardUpdateMonitor, mGlobalSettings);
        mSubscriptionManager.addOnSubscriptionsChangedListener(mExecutor,
                mInternetDialogController.mOnSubscriptionsChangedListener);
        mInternetDialogController.onStart(
                mock(InternetDialogController.InternetDialogCallback.class));
        mInternetDialogController.mActivityStarter = mActivityStarter;
        mInternetDialogController.mConnectedEntry = mConnectedEntry;
        mInternetDialogController.mWifiIconInjector = mWifiIconInjector;
    }

    @Test
    public void getDialogTitleText_withAirplaneModeOn_returnAirplaneMode() {
        mInternetDialogController.setAirplaneModeEnabled(true);

        assertTrue(TextUtils.equals(mInternetDialogController.getDialogTitleText(),
                getResourcesString("airplane_mode")));
    }

    @Test
    public void getDialogTitleText_withAirplaneModeOff_returnInternet() {
        mInternetDialogController.setAirplaneModeEnabled(false);

        assertTrue(TextUtils.equals(mInternetDialogController.getDialogTitleText(),
                getResourcesString("quick_settings_internet_label")));
    }

    @Test
    public void getSubtitleText_withAirplaneModeOn_returnNull() {
        mInternetDialogController.setAirplaneModeEnabled(true);

        assertThat(mInternetDialogController.getSubtitleText(false)).isNull();
    }

    @Test
    public void getSubtitleText_withWifiOff_returnWifiIsOff() {
        mInternetDialogController.setAirplaneModeEnabled(false);
        when(mWifiManager.isWifiEnabled()).thenReturn(false);

        assertTrue(TextUtils.equals(mInternetDialogController.getSubtitleText(false),
                getResourcesString("wifi_is_off")));
    }

    @Test
    public void getSubtitleText_withNoWifiEntry_returnSearchWifi() {
        mInternetDialogController.setAirplaneModeEnabled(false);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        List<ScanResult> wifiScanResults = mock(ArrayList.class);
        doReturn(0).when(wifiScanResults).size();
        when(mWifiManager.getScanResults()).thenReturn(wifiScanResults);

        assertTrue(TextUtils.equals(mInternetDialogController.getSubtitleText(true),
                getResourcesString("wifi_empty_list_wifi_on")));
    }

    @Test
    public void getSubtitleText_withWifiEntry_returnTapToConnect() {
        mInternetDialogController.setAirplaneModeEnabled(false);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        List<ScanResult> wifiScanResults = mock(ArrayList.class);
        doReturn(1).when(wifiScanResults).size();
        when(mWifiManager.getScanResults()).thenReturn(wifiScanResults);

        assertTrue(TextUtils.equals(mInternetDialogController.getSubtitleText(false),
                getResourcesString("tap_a_network_to_connect")));
    }

    @Test
    public void getSubtitleText_withNoService_returnNoNetworksAvailable() {
        mInternetDialogController.setAirplaneModeEnabled(false);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        List<ScanResult> wifiScanResults = new ArrayList<>();
        doReturn(wifiScanResults).when(mWifiManager).getScanResults();
        when(mWifiManager.getScanResults()).thenReturn(wifiScanResults);
        when(mSubscriptionManager.getActiveSubscriptionIdList())
                .thenReturn(new int[] {SUB_ID});

        doReturn(ServiceState.STATE_OUT_OF_SERVICE).when(mServiceState).getState();
        doReturn(mServiceState).when(mTelephonyManager).getServiceState();
        doReturn(TelephonyManager.DATA_DISCONNECTED).when(mTelephonyManager).getDataState();

        assertTrue(TextUtils.equals(mInternetDialogController.getSubtitleText(false),
                getResourcesString("all_network_unavailable")));
    }

    @Test
    public void getSubtitleText_withMobileDataDisabled_returnNoOtherAvailable() {
        mInternetDialogController.setAirplaneModeEnabled(false);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        List<ScanResult> wifiScanResults = new ArrayList<>();
        doReturn(wifiScanResults).when(mWifiManager).getScanResults();
        when(mWifiManager.getScanResults()).thenReturn(wifiScanResults);
        when(mSubscriptionManager.getActiveSubscriptionIdList())
                .thenReturn(new int[] {SUB_ID});

        doReturn(ServiceState.STATE_IN_SERVICE).when(mServiceState).getState();
        doReturn(mServiceState).when(mTelephonyManager).getServiceState();

        when(mTelephonyManager.isDataEnabled()).thenReturn(false);

        assertTrue(TextUtils.equals(mInternetDialogController.getSubtitleText(false),
                getResourcesString("non_carrier_network_unavailable")));
    }

    @Test
    public void getDefaultWifiEntry_connectedEntryIsNull_returnNull() {
        mInternetDialogController.mConnectedEntry = null;

        assertThat(mInternetDialogController.getDefaultWifiEntry()).isNull();
    }

    @Test
    public void getDefaultWifiEntry_connectedEntryIsNotDefault_returnNull() {
        when(mConnectedEntry.isDefaultNetwork()).thenReturn(false);

        assertThat(mInternetDialogController.getDefaultWifiEntry()).isNull();
    }

    @Test
    public void getDefaultWifiEntry_connectedEntryIsDefault_returnConnectedEntry() {
        // The default conditions have been set in setUp().
        //   - The connected Wi-Fi entry with the default network condition.

        assertThat(mInternetDialogController.getDefaultWifiEntry()).isEqualTo(mConnectedEntry);
    }

    @Test
    public void getDefaultWifiTitle_withNoDefaultEntry_returnEmpty() {
        mInternetDialogController.mConnectedEntry = null;

        assertThat(mInternetDialogController.getDefaultWifiTitle()).isEmpty();
    }

    @Test
    public void getDefaultWifiTitle_withDefaultEntry_returnTitle() {
        when(mConnectedEntry.getTitle()).thenReturn(CONNECTED_TITLE);

        assertThat(mInternetDialogController.getDefaultWifiTitle()).isEqualTo(CONNECTED_TITLE);
    }

    @Test
    public void getDefaultWifiSummary_withNoDefaultEntry_returnEmpty() {
        mInternetDialogController.mConnectedEntry = null;

        assertThat(mInternetDialogController.getDefaultWifiSummary()).isEmpty();
    }

    @Test
    public void getDefaultWifiSummary_withDefaultEntry_returnSummary() {
        when(mConnectedEntry.getSummary(false)).thenReturn(CONNECTED_SUMMARY);

        assertThat(mInternetDialogController.getDefaultWifiSummary()).isEqualTo(CONNECTED_SUMMARY);
    }

    @Test
    public void getWifiDetailsSettingsIntent_withNoConnectedEntry_returnNull() {
        mInternetDialogController.mConnectedEntry = null;

        assertThat(mInternetDialogController.getWifiDetailsSettingsIntent()).isNull();
    }

    @Test
    public void getWifiDetailsSettingsIntent_withNoConnectedEntryKey_returnNull() {
        when(mConnectedEntry.getKey()).thenReturn(null);

        assertThat(mInternetDialogController.getWifiDetailsSettingsIntent()).isNull();
    }

    @Test
    public void getWifiDetailsSettingsIntent_withConnectedEntryKey_returnIntent() {
        when(mConnectedEntry.getKey()).thenReturn("test_key");

        assertThat(mInternetDialogController.getWifiDetailsSettingsIntent()).isNotNull();
    }

    @Test
    public void getWifiConnectedDrawable_withConnectedEntry_returnIntentIconWithColorAccent() {
        final Drawable drawable = mock(Drawable.class);
        when(mWifiIconInjector.getIcon(anyBoolean(), anyInt())).thenReturn(drawable);

        mInternetDialogController.getConnectedWifiDrawable(mConnectedEntry);

        verify(mWifiIconInjector).getIcon(eq(false), anyInt());
        verify(drawable).setTint(mContext.getColor(R.color.connected_network_primary_color));
    }

    @Test
    public void launchWifiNetworkDetailsSetting_withNoConnectedEntry_doNothing() {
        mInternetDialogController.mConnectedEntry = null;

        mInternetDialogController.launchWifiNetworkDetailsSetting();

        verify(mActivityStarter, never())
                .postStartActivityDismissingKeyguard(any(Intent.class), anyInt());
    }

    @Test
    public void launchWifiNetworkDetailsSetting_withConnectedEntryKey_startActivity() {
        when(mConnectedEntry.getKey()).thenReturn("test_key");

        mInternetDialogController.launchWifiNetworkDetailsSetting();

        verify(mActivityStarter).postStartActivityDismissingKeyguard(any(Intent.class), anyInt());
    }

    private String getResourcesString(String name) {
        return mContext.getResources().getString(getResourcesId(name));
    }

    private int getResourcesId(String name) {
        return mContext.getResources().getIdentifier(name, "string",
                mContext.getPackageName());
    }

    private class MockInternetDialogController extends InternetDialogController {

        private GlobalSettings mGlobalSettings;
        private boolean mIsAirplaneModeOn;

        MockInternetDialogController(Context context, UiEventLogger uiEventLogger,
                ActivityStarter starter, AccessPointController accessPointController,
                SubscriptionManager subscriptionManager, TelephonyManager telephonyManager,
                @Nullable WifiManager wifiManager, ConnectivityManager connectivityManager,
                @Main Handler handler, @Main Executor mainExecutor,
                BroadcastDispatcher broadcastDispatcher,
                KeyguardUpdateMonitor keyguardUpdateMonitor, GlobalSettings globalSettings) {
            super(context, uiEventLogger, starter, accessPointController, subscriptionManager,
                    telephonyManager, wifiManager, connectivityManager, handler, mainExecutor,
                    broadcastDispatcher, keyguardUpdateMonitor, globalSettings);
            mGlobalSettings = globalSettings;
        }

        @Override
        boolean isAirplaneModeEnabled() {
            return mIsAirplaneModeOn;
        }

        public void setAirplaneModeEnabled(boolean enabled) {
            mIsAirplaneModeOn = enabled;
        }
    }
}
