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

package com.android.server.integrity;

import static android.content.integrity.AppIntegrityManager.EXTRA_STATUS;
import static android.content.integrity.AppIntegrityManager.STATUS_FAILURE;
import static android.content.integrity.AppIntegrityManager.STATUS_SUCCESS;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_ID;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_INSTALLER_PACKAGE;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_INSTALLER_UID;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.integrity.AppInstallMetadata;
import android.content.integrity.AtomicFormula;
import android.content.integrity.IntegrityFormula;
import android.content.integrity.Rule;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;

import androidx.test.InstrumentationRegistry;

import com.android.internal.R;
import com.android.server.integrity.engine.RuleEvaluationEngine;
import com.android.server.integrity.model.IntegrityCheckResult;
import com.android.server.testutils.TestUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unit test for {@link com.android.server.integrity.AppIntegrityManagerServiceImpl} */
@RunWith(JUnit4.class)
public class AppIntegrityManagerServiceImplTest {
    private static final String TEST_APP_PATH =
            "/data/local/tmp/AppIntegrityManagerServiceTestApp.apk";

    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String VERSION = "version";
    private static final String TEST_FRAMEWORK_PACKAGE = "com.android.frameworks.servicestests";

    private static final String PACKAGE_NAME = "com.test.app";

    private static final long VERSION_CODE = 100;
    private static final String INSTALLER = "com.long.random.test.installer.name";

    // These are obtained by running the test and checking logcat.
    private static final String APP_CERT =
            "301AA3CB081134501C45F1422ABC66C24224FD5DED5FDC8F17E697176FD866AA";
    private static final String INSTALLER_CERT =
            "301AA3CB081134501C45F1422ABC66C24224FD5DED5FDC8F17E697176FD866AA";
    // We use SHA256 for package names longer than 32 characters.
    private static final String INSTALLER_SHA256 =
            "30F41A7CBF96EE736A54DD6DF759B50ED3CC126ABCEF694E167C324F5976C227";

    private static final String PLAY_STORE_PKG = "com.android.vending";
    private static final String ADB_INSTALLER = "adb";
    private static final String PLAY_STORE_CERT = "play_store_cert";
    private static final String ADB_CERT = "";

    @org.junit.Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock
    PackageManagerInternal mPackageManagerInternal;
    @Mock
    Context mMockContext;
    @Mock
    Resources mMockResources;
    @Mock
    RuleEvaluationEngine mRuleEvaluationEngine;
    @Mock
    IntegrityFileManager mIntegrityFileManager;
    @Mock
    Handler mHandler;

    private PackageManager mSpyPackageManager;
    private File mTestApk;

    private final Context mRealContext = InstrumentationRegistry.getTargetContext();
    // under test
    private AppIntegrityManagerServiceImpl mService;

    @Before
    public void setup() throws Exception {
        mTestApk = new File(TEST_APP_PATH);

        mService =
                new AppIntegrityManagerServiceImpl(
                        mMockContext,
                        mPackageManagerInternal,
                        mRuleEvaluationEngine,
                        mIntegrityFileManager,
                        mHandler,
                        /* checkIntegrityForRuleProviders= */ true);

        mSpyPackageManager = spy(mRealContext.getPackageManager());
        // setup mocks to prevent NPE
        when(mMockContext.getPackageManager()).thenReturn(mSpyPackageManager);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getStringArray(anyInt())).thenReturn(new String[]{});
        when(mIntegrityFileManager.initialized()).thenReturn(true);
    }

    @Test
    public void updateRuleSet_notAuthorized() throws Exception {
        makeUsSystemApp();
        Rule rule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);
        TestUtils.assertExpectException(
                SecurityException.class,
                "Only system packages specified in config_integrityRuleProviderPackages are"
                        + " allowed to call this method.",
                () ->
                        mService.updateRuleSet(
                                VERSION,
                                new ParceledListSlice<>(Arrays.asList(rule)),
                                /* statusReceiver= */ null));
    }

    @Test
    public void updateRuleSet_notSystemApp() throws Exception {
        whitelistUsAsRuleProvider();
        Rule rule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);
        TestUtils.assertExpectException(
                SecurityException.class,
                "Only system packages specified in config_integrityRuleProviderPackages are"
                        + " allowed to call this method.",
                () ->
                        mService.updateRuleSet(
                                VERSION,
                                new ParceledListSlice<>(Arrays.asList(rule)),
                                /* statusReceiver= */ null));
    }

    @Test
    public void updateRuleSet_authorized() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        Rule rule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);

        // no SecurityException
        mService.updateRuleSet(
                VERSION, new ParceledListSlice<>(Arrays.asList(rule)), mock(IntentSender.class));
    }

    @Test
    public void updateRuleSet_correctMethodCall() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        IntentSender mockReceiver = mock(IntentSender.class);
        List<Rule> rules =
                Arrays.asList(
                        new Rule(IntegrityFormula.PACKAGE_NAME.equalTo(PACKAGE_NAME), Rule.DENY));

        mService.updateRuleSet(VERSION, new ParceledListSlice<>(rules), mockReceiver);
        runJobInHandler();

        verify(mIntegrityFileManager).writeRules(VERSION, TEST_FRAMEWORK_PACKAGE, rules);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mockReceiver).sendIntent(any(), anyInt(), intentCaptor.capture(), any(), any());
        assertEquals(STATUS_SUCCESS, intentCaptor.getValue().getIntExtra(EXTRA_STATUS, -1));
    }

    @Test
    public void updateRuleSet_fail() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        doThrow(new IOException()).when(mIntegrityFileManager).writeRules(any(), any(), any());
        IntentSender mockReceiver = mock(IntentSender.class);
        List<Rule> rules =
                Arrays.asList(
                        new Rule(IntegrityFormula.PACKAGE_NAME.equalTo(PACKAGE_NAME), Rule.DENY));

        mService.updateRuleSet(VERSION, new ParceledListSlice<>(rules), mockReceiver);
        runJobInHandler();

        verify(mIntegrityFileManager).writeRules(VERSION, TEST_FRAMEWORK_PACKAGE, rules);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mockReceiver).sendIntent(any(), anyInt(), intentCaptor.capture(), any(), any());
        assertEquals(STATUS_FAILURE, intentCaptor.getValue().getIntExtra(EXTRA_STATUS, -1));
    }

    @Test
    public void broadcastReceiverRegistration() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        ArgumentCaptor<IntentFilter> intentFilterCaptor =
                ArgumentCaptor.forClass(IntentFilter.class);

        verify(mMockContext).registerReceiver(any(), intentFilterCaptor.capture(), any(), any());
        assertEquals(1, intentFilterCaptor.getValue().countActions());
        assertEquals(
                Intent.ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION,
                intentFilterCaptor.getValue().getAction(0));
        assertEquals(1, intentFilterCaptor.getValue().countDataTypes());
        assertEquals(PACKAGE_MIME_TYPE, intentFilterCaptor.getValue().getDataType(0));
    }

    @Test
    public void handleBroadcast_correctArgs() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        Intent intent = makeVerificationIntent();
        when(mRuleEvaluationEngine.evaluate(any(), any())).thenReturn(IntegrityCheckResult.allow());

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        ArgumentCaptor<AppInstallMetadata> metadataCaptor =
                ArgumentCaptor.forClass(AppInstallMetadata.class);
        Map<String, String> allowedInstallers = new HashMap<>();
        ArgumentCaptor<Map<String, String>> allowedInstallersCaptor =
                ArgumentCaptor.forClass(allowedInstallers.getClass());
        verify(mRuleEvaluationEngine)
                .evaluate(metadataCaptor.capture(), allowedInstallersCaptor.capture());
        AppInstallMetadata appInstallMetadata = metadataCaptor.getValue();
        allowedInstallers = allowedInstallersCaptor.getValue();
        assertEquals(PACKAGE_NAME, appInstallMetadata.getPackageName());
        assertEquals(APP_CERT, appInstallMetadata.getAppCertificate());
        assertEquals(INSTALLER_SHA256, appInstallMetadata.getInstallerName());
        assertEquals(INSTALLER_CERT, appInstallMetadata.getInstallerCertificate());
        assertEquals(VERSION_CODE, appInstallMetadata.getVersionCode());
        assertFalse(appInstallMetadata.isPreInstalled());
        // These are hardcoded in the test apk android manifest
        assertEquals(2, allowedInstallers.size());
        assertEquals(PLAY_STORE_CERT, allowedInstallers.get(PLAY_STORE_PKG));
        assertEquals(ADB_CERT, allowedInstallers.get(ADB_INSTALLER));
    }

    @Test
    public void handleBroadcast_allow() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        Intent intent = makeVerificationIntent();
        when(mRuleEvaluationEngine.evaluate(any(), any())).thenReturn(IntegrityCheckResult.allow());

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        verify(mPackageManagerInternal)
                .setIntegrityVerificationResult(
                        1, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
    }

    @Test
    public void handleBroadcast_reject() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        when(mRuleEvaluationEngine.evaluate(any(), any()))
                .thenReturn(
                        IntegrityCheckResult.deny(
                                Arrays.asList(
                                        new Rule(
                                                new AtomicFormula.BooleanAtomicFormula(
                                                        AtomicFormula.PRE_INSTALLED, false),
                                                Rule.DENY))));
        Intent intent = makeVerificationIntent();

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        verify(mPackageManagerInternal)
                .setIntegrityVerificationResult(
                        1, PackageManagerInternal.INTEGRITY_VERIFICATION_REJECT);
    }

    @Test
    public void handleBroadcast_notInitialized() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        when(mIntegrityFileManager.initialized()).thenReturn(false);
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        Intent intent = makeVerificationIntent();
        when(mRuleEvaluationEngine.evaluate(any(), any())).thenReturn(IntegrityCheckResult.allow());

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        // The evaluation will still run since we still evaluate manifest based rules.
        verify(mPackageManagerInternal)
                .setIntegrityVerificationResult(
                        1, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
    }

    @Test
    public void verifierAsInstaller_skipIntegrityVerification() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        mService =
                new AppIntegrityManagerServiceImpl(
                        mMockContext,
                        mPackageManagerInternal,
                        mRuleEvaluationEngine,
                        mIntegrityFileManager,
                        mHandler,
                        /* checkIntegrityForRuleProviders= */ false);
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext, atLeastOnce())
                .registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        Intent intent = makeVerificationIntent(TEST_FRAMEWORK_PACKAGE);
        when(mRuleEvaluationEngine.evaluate(any(), any()))
                .thenReturn(IntegrityCheckResult.deny(/* rule= */ null));

        broadcastReceiverCaptor.getValue().onReceive(mMockContext, intent);
        runJobInHandler();

        verify(mPackageManagerInternal)
                .setIntegrityVerificationResult(
                        1, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
    }

    @Test
    public void getCurrentRules() throws Exception {
        whitelistUsAsRuleProvider();
        makeUsSystemApp();
        Rule rule = new Rule(IntegrityFormula.PACKAGE_NAME.equalTo("package"), Rule.DENY);
        when(mIntegrityFileManager.readRules(any())).thenReturn(Arrays.asList(rule));

        assertThat(mService.getCurrentRules().getList()).containsExactly(rule);
    }

    private void whitelistUsAsRuleProvider() {
        Resources mockResources = mock(Resources.class);
        when(mockResources.getStringArray(R.array.config_integrityRuleProviderPackages))
                .thenReturn(new String[]{TEST_FRAMEWORK_PACKAGE});
        when(mMockContext.getResources()).thenReturn(mockResources);
    }

    private void runJobInHandler() {
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        // sendMessageAtTime is the first non-final method in the call chain when "post" is invoked.
        verify(mHandler).sendMessageAtTime(messageCaptor.capture(), anyLong());
        messageCaptor.getValue().getCallback().run();
    }

    private void makeUsSystemApp() throws Exception {
        PackageInfo packageInfo =
                mRealContext.getPackageManager().getPackageInfo(TEST_FRAMEWORK_PACKAGE, 0);
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        doReturn(packageInfo)
                .when(mSpyPackageManager)
                .getPackageInfo(eq(TEST_FRAMEWORK_PACKAGE), anyInt());
    }

    private Intent makeVerificationIntent() throws Exception {
        PackageInfo packageInfo =
                mRealContext
                        .getPackageManager()
                        .getPackageInfo(TEST_FRAMEWORK_PACKAGE, PackageManager.GET_SIGNATURES);
        doReturn(packageInfo).when(mSpyPackageManager).getPackageInfo(eq(INSTALLER), anyInt());
        doReturn(1).when(mSpyPackageManager).getPackageUid(eq(INSTALLER), anyInt());
        return makeVerificationIntent(INSTALLER);
    }

    private Intent makeVerificationIntent(String installer) throws Exception {
        Intent intent = new Intent();
        intent.setDataAndType(Uri.fromFile(mTestApk), PACKAGE_MIME_TYPE);
        intent.setAction(Intent.ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION);
        intent.putExtra(EXTRA_VERIFICATION_ID, 1);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, PACKAGE_NAME);
        intent.putExtra(EXTRA_VERIFICATION_INSTALLER_PACKAGE, installer);
        intent.putExtra(
                EXTRA_VERIFICATION_INSTALLER_UID,
                mMockContext.getPackageManager().getPackageUid(installer, /* flags= */ 0));
        intent.putExtra(Intent.EXTRA_LONG_VERSION_CODE, VERSION_CODE);
        return intent;
    }
}
