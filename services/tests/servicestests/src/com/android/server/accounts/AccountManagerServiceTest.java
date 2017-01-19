/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.server.accounts;

import static android.database.sqlite.SQLiteDatabase.deleteDatabase;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerInternal;
import android.accounts.AuthenticatorDescription;
import android.accounts.CantAddAccountActivity;
import android.accounts.IAccountManagerResponse;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.INotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.RegisteredServicesCacheListener;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.pm.RegisteredServicesCache.ServiceInfo;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.AndroidTestCase;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import com.android.frameworks.servicestests.R;
import com.android.server.LocalServices;

import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AccountManagerServiceTest extends AndroidTestCase {
    private static final String TAG = AccountManagerServiceTest.class.getSimpleName();

    @Mock private Context mMockContext;
    @Mock private AppOpsManager mMockAppOpsManager;
    @Mock private UserManager mMockUserManager;
    @Mock private PackageManager mMockPackageManager;
    @Mock private DevicePolicyManagerInternal mMockDevicePolicyManagerInternal;
    @Mock private DevicePolicyManager mMockDevicePolicyManager;
    @Mock private IAccountManagerResponse mMockAccountManagerResponse;
    @Mock private IBinder mMockBinder;

    @Captor private ArgumentCaptor<Intent> mIntentCaptor;
    @Captor private ArgumentCaptor<Bundle> mBundleCaptor;

    private static final int LATCH_TIMEOUT_MS = 500;
    private static final String PREN_DB = "pren.db";
    private static final String DE_DB = "de.db";
    private static final String CE_DB = "ce.db";
    private AccountManagerService mAms;
    private TestInjector mTestInjector;

    @Override
    protected void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mMockPackageManager.checkSignatures(anyInt(), anyInt()))
                    .thenReturn(PackageManager.SIGNATURE_MATCH);
        final UserInfo ui = new UserInfo(UserHandle.USER_SYSTEM, "user0", 0);
        when(mMockUserManager.getUserInfo(eq(ui.id))).thenReturn(ui);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mMockAppOpsManager);
        when(mMockContext.getSystemService(Context.USER_SERVICE)).thenReturn(mMockUserManager);
        when(mMockContext.getSystemServiceName(AppOpsManager.class)).thenReturn(
                Context.APP_OPS_SERVICE);
        when(mMockContext.checkCallingOrSelfPermission(anyString())).thenReturn(
                PackageManager.PERMISSION_GRANTED);
        Bundle bundle = new Bundle();
        when(mMockUserManager.getUserRestrictions(any(UserHandle.class))).thenReturn(bundle);
        when(mMockContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(
                mMockDevicePolicyManager);
        when(mMockAccountManagerResponse.asBinder()).thenReturn(mMockBinder);

        Context realTestContext = getContext();
        MyMockContext mockContext = new MyMockContext(realTestContext, mMockContext);
        setContext(mockContext);
        mTestInjector = new TestInjector(realTestContext, mockContext);
        mAms = new AccountManagerService(mTestInjector);
    }

    @Override
    protected void tearDown() throws Exception {
        // Let async logging tasks finish, otherwise they may crash due to db being removed
        CountDownLatch cdl = new CountDownLatch(1);
        mAms.mHandler.post(() -> {
            deleteDatabase(new File(mTestInjector.getCeDatabaseName(UserHandle.USER_SYSTEM)));
            deleteDatabase(new File(mTestInjector.getDeDatabaseName(UserHandle.USER_SYSTEM)));
            deleteDatabase(new File(mTestInjector.getPreNDatabaseName(UserHandle.USER_SYSTEM)));
            cdl.countDown();
        });
        cdl.await(1, TimeUnit.SECONDS);
        super.tearDown();
    }

    class AccountSorter implements Comparator<Account> {
        public int compare(Account object1, Account object2) {
            if (object1 == object2) return 0;
            if (object1 == null) return 1;
            if (object2 == null) return -1;
            int result = object1.type.compareTo(object2.type);
            if (result != 0) return result;
            return object1.name.compareTo(object2.name);
        }
    }

    @SmallTest
    public void testCheckAddAccount() throws Exception {
        unlockSystemUser();
        Account a11 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Account a21 = new Account("account2", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Account a31 = new Account("account3", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Account a12 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
        Account a22 = new Account("account2", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
        Account a32 = new Account("account3", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
        mAms.addAccountExplicitly(a11, "p11", null);
        mAms.addAccountExplicitly(a12, "p12", null);
        mAms.addAccountExplicitly(a21, "p21", null);
        mAms.addAccountExplicitly(a22, "p22", null);
        mAms.addAccountExplicitly(a31, "p31", null);
        mAms.addAccountExplicitly(a32, "p32", null);

        Account[] accounts = mAms.getAccounts(null, mContext.getOpPackageName());
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(6, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a21, accounts[1]);
        assertEquals(a31, accounts[2]);
        assertEquals(a12, accounts[3]);
        assertEquals(a22, accounts[4]);
        assertEquals(a32, accounts[5]);

        accounts = mAms.getAccounts(AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                mContext.getOpPackageName());
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(3, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a21, accounts[1]);
        assertEquals(a31, accounts[2]);

        mAms.removeAccountInternal(a21);

        accounts = mAms.getAccounts(AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                mContext.getOpPackageName());
        Arrays.sort(accounts, new AccountSorter());
        assertEquals(2, accounts.length);
        assertEquals(a11, accounts[0]);
        assertEquals(a31, accounts[1]);
    }

    @SmallTest
    public void testPasswords() throws Exception {
        unlockSystemUser();
        Account a11 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Account a12 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
        mAms.addAccountExplicitly(a11, "p11", null);
        mAms.addAccountExplicitly(a12, "p12", null);

        assertEquals("p11", mAms.getPassword(a11));
        assertEquals("p12", mAms.getPassword(a12));

        mAms.setPassword(a11, "p11b");

        assertEquals("p11b", mAms.getPassword(a11));
        assertEquals("p12", mAms.getPassword(a12));
    }

    @SmallTest
    public void testUserdata() throws Exception {
        unlockSystemUser();
        Account a11 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Bundle u11 = new Bundle();
        u11.putString("a", "a_a11");
        u11.putString("b", "b_a11");
        u11.putString("c", "c_a11");
        Account a12 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
        Bundle u12 = new Bundle();
        u12.putString("a", "a_a12");
        u12.putString("b", "b_a12");
        u12.putString("c", "c_a12");
        mAms.addAccountExplicitly(a11, "p11", u11);
        mAms.addAccountExplicitly(a12, "p12", u12);

        assertEquals("a_a11", mAms.getUserData(a11, "a"));
        assertEquals("b_a11", mAms.getUserData(a11, "b"));
        assertEquals("c_a11", mAms.getUserData(a11, "c"));
        assertEquals("a_a12", mAms.getUserData(a12, "a"));
        assertEquals("b_a12", mAms.getUserData(a12, "b"));
        assertEquals("c_a12", mAms.getUserData(a12, "c"));

        mAms.setUserData(a11, "b", "b_a11b");
        mAms.setUserData(a12, "c", null);

        assertEquals("a_a11", mAms.getUserData(a11, "a"));
        assertEquals("b_a11b", mAms.getUserData(a11, "b"));
        assertEquals("c_a11", mAms.getUserData(a11, "c"));
        assertEquals("a_a12", mAms.getUserData(a12, "a"));
        assertEquals("b_a12", mAms.getUserData(a12, "b"));
        assertNull(mAms.getUserData(a12, "c"));
    }

    @SmallTest
    public void testAuthtokens() throws Exception {
        unlockSystemUser();
        Account a11 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Account a12 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
        mAms.addAccountExplicitly(a11, "p11", null);
        mAms.addAccountExplicitly(a12, "p12", null);

        mAms.setAuthToken(a11, "att1", "a11_att1");
        mAms.setAuthToken(a11, "att2", "a11_att2");
        mAms.setAuthToken(a11, "att3", "a11_att3");
        mAms.setAuthToken(a12, "att1", "a12_att1");
        mAms.setAuthToken(a12, "att2", "a12_att2");
        mAms.setAuthToken(a12, "att3", "a12_att3");

        assertEquals("a11_att1", mAms.peekAuthToken(a11, "att1"));
        assertEquals("a11_att2", mAms.peekAuthToken(a11, "att2"));
        assertEquals("a11_att3", mAms.peekAuthToken(a11, "att3"));
        assertEquals("a12_att1", mAms.peekAuthToken(a12, "att1"));
        assertEquals("a12_att2", mAms.peekAuthToken(a12, "att2"));
        assertEquals("a12_att3", mAms.peekAuthToken(a12, "att3"));

        mAms.setAuthToken(a11, "att3", "a11_att3b");
        mAms.invalidateAuthToken(a12.type, "a12_att2");

        assertEquals("a11_att1", mAms.peekAuthToken(a11, "att1"));
        assertEquals("a11_att2", mAms.peekAuthToken(a11, "att2"));
        assertEquals("a11_att3b", mAms.peekAuthToken(a11, "att3"));
        assertEquals("a12_att1", mAms.peekAuthToken(a12, "att1"));
        assertNull(mAms.peekAuthToken(a12, "att2"));
        assertEquals("a12_att3", mAms.peekAuthToken(a12, "att3"));

        assertNull(mAms.peekAuthToken(a12, "att2"));
    }

    @SmallTest
    public void testRemovedAccountSync() throws Exception {
        unlockSystemUser();
        Account a1 = new Account("account1", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Account a2 = new Account("account2", AccountManagerServiceTestFixtures.ACCOUNT_TYPE_2);
        mAms.addAccountExplicitly(a1, "p1", null);
        mAms.addAccountExplicitly(a2, "p2", null);

        Context originalContext = ((MyMockContext)getContext()).mTestContext;
        // create a separate instance of AMS. It initially assumes that user0 is locked
        AccountManagerService ams2 = new AccountManagerService(mTestInjector);

        // Verify that account can be removed when user is locked
        ams2.removeAccountInternal(a1);
        Account[] accounts = ams2.getAccounts(UserHandle.USER_SYSTEM, mContext.getOpPackageName());
        assertEquals(1, accounts.length);
        assertEquals("Only a2 should be returned", a2, accounts[0]);

        // Verify that CE db file is unchanged and still has 2 accounts
        String ceDatabaseName = mTestInjector.getCeDatabaseName(UserHandle.USER_SYSTEM);
        int accountsNumber = readNumberOfAccountsFromDbFile(originalContext, ceDatabaseName);
        assertEquals("CE database should still have 2 accounts", 2, accountsNumber);

        // Unlock the user and verify that db has been updated
        ams2.onUserUnlocked(newIntentForUser(UserHandle.USER_SYSTEM));
        accounts = ams2.getAccounts(UserHandle.USER_SYSTEM, mContext.getOpPackageName());
        assertEquals(1, accounts.length);
        assertEquals("Only a2 should be returned", a2, accounts[0]);
        accountsNumber = readNumberOfAccountsFromDbFile(originalContext, ceDatabaseName);
        assertEquals("CE database should now have 1 account", 1, accountsNumber);
    }

    @SmallTest
    public void testPreNDatabaseMigration() throws Exception {
        String preNDatabaseName = mTestInjector.getPreNDatabaseName(UserHandle.USER_SYSTEM);
        Context originalContext = ((MyMockContext) getContext()).mTestContext;
        PreNTestDatabaseHelper.createV4Database(originalContext, preNDatabaseName);
        // Assert that database was created with 1 account
        int n = readNumberOfAccountsFromDbFile(originalContext, preNDatabaseName);
        assertEquals("pre-N database should have 1 account", 1, n);

        // Start testing
        unlockSystemUser();
        Account[] accounts = mAms.getAccounts(null, mContext.getOpPackageName());
        assertEquals("1 account should be migrated", 1, accounts.length);
        assertEquals(PreNTestDatabaseHelper.ACCOUNT_NAME, accounts[0].name);
        assertEquals(PreNTestDatabaseHelper.ACCOUNT_PASSWORD, mAms.getPassword(accounts[0]));
        assertEquals("Authtoken should be migrated",
                PreNTestDatabaseHelper.TOKEN_STRING,
                mAms.peekAuthToken(accounts[0], PreNTestDatabaseHelper.TOKEN_TYPE));

        assertFalse("pre-N database file should be removed but was found at " + preNDatabaseName,
                new File(preNDatabaseName).exists());

        // Verify that ce/de files are present
        String deDatabaseName = mTestInjector.getDeDatabaseName(UserHandle.USER_SYSTEM);
        String ceDatabaseName = mTestInjector.getCeDatabaseName(UserHandle.USER_SYSTEM);
        assertTrue("DE database file should be created at " + deDatabaseName,
                new File(deDatabaseName).exists());
        assertTrue("CE database file should be created at " + ceDatabaseName,
                new File(ceDatabaseName).exists());
    }

    @SmallTest
    public void testStartAddAccountSessionWithNullResponse() throws Exception {
        unlockSystemUser();
        try {
            mAms.startAddAccountSession(
                null, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1,
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null); // optionsIn
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
        } catch(Exception e){
            fail(String.format("Expect IllegalArgumentException, but got %s.", e));
        }
    }

    @SmallTest
    public void testStartAddAccountSessionWithNullAccountType() throws Exception {
        unlockSystemUser();
        try {
            mAms.startAddAccountSession(
                    mMockAccountManagerResponse, // response
                    null, // accountType
                    "authTokenType",
                    null, // requiredFeatures
                    true, // expectActivityLaunch
                    null); // optionsIn
            fail("IllegalArgumentException expected. But no exception was thrown.");
        } catch (IllegalArgumentException e) {
        } catch(Exception e){
            fail(String.format("Expect IllegalArgumentException, but got %s.", e));
        }
    }

    @SmallTest
    public void testStartAddAccountSessionUserCannotModifyAccountNoDPM() throws Exception {
        unlockSystemUser();
        Bundle bundle = new Bundle();
        bundle.putBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, true);
        when(mMockUserManager.getUserRestrictions(any(UserHandle.class))).thenReturn(bundle);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

        mAms.startAddAccountSession(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null); // optionsIn
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_USER_RESTRICTED), anyString());
        verify(mMockContext).startActivityAsUser(mIntentCaptor.capture(), eq(UserHandle.SYSTEM));

        // verify the intent for default CantAddAccountActivity is sent.
        Intent intent = mIntentCaptor.getValue();
        assertEquals(intent.getComponent().getClassName(), CantAddAccountActivity.class.getName());
        assertEquals(intent.getIntExtra(CantAddAccountActivity.EXTRA_ERROR_CODE, 0),
                AccountManager.ERROR_CODE_USER_RESTRICTED);
    }

    @SmallTest
    public void testStartAddAccountSessionUserCannotModifyAccountWithDPM() throws Exception {
        unlockSystemUser();
        Bundle bundle = new Bundle();
        bundle.putBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, true);
        when(mMockUserManager.getUserRestrictions(any(UserHandle.class))).thenReturn(bundle);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(
                DevicePolicyManagerInternal.class, mMockDevicePolicyManagerInternal);
        when(mMockDevicePolicyManagerInternal.createUserRestrictionSupportIntent(
                anyInt(), anyString())).thenReturn(new Intent());
        when(mMockDevicePolicyManagerInternal.createShowAdminSupportIntent(
                anyInt(), anyBoolean())).thenReturn(new Intent());

        mAms.startAddAccountSession(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null); // optionsIn

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_USER_RESTRICTED), anyString());
        verify(mMockContext).startActivityAsUser(any(Intent.class), eq(UserHandle.SYSTEM));
        verify(mMockDevicePolicyManagerInternal).createUserRestrictionSupportIntent(
                anyInt(), anyString());
    }

    @SmallTest
    public void testStartAddAccountSessionUserCannotModifyAccountForTypeNoDPM() throws Exception {
        unlockSystemUser();
        when(mMockDevicePolicyManager.getAccountTypesWithManagementDisabledAsUser(anyInt()))
                .thenReturn(new String[]{AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, "BBB"});
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

        mAms.startAddAccountSession(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null); // optionsIn

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE), anyString());
        verify(mMockContext).startActivityAsUser(mIntentCaptor.capture(), eq(UserHandle.SYSTEM));

        // verify the intent for default CantAddAccountActivity is sent.
        Intent intent = mIntentCaptor.getValue();
        assertEquals(intent.getComponent().getClassName(), CantAddAccountActivity.class.getName());
        assertEquals(intent.getIntExtra(CantAddAccountActivity.EXTRA_ERROR_CODE, 0),
                AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE);
    }

    @SmallTest
    public void testStartAddAccountSessionUserCannotModifyAccountForTypeWithDPM() throws Exception {
        unlockSystemUser();
        when(mMockContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(
                mMockDevicePolicyManager);
        when(mMockDevicePolicyManager.getAccountTypesWithManagementDisabledAsUser(anyInt()))
                .thenReturn(new String[]{AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, "BBB"});

        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(
                DevicePolicyManagerInternal.class, mMockDevicePolicyManagerInternal);
        when(mMockDevicePolicyManagerInternal.createUserRestrictionSupportIntent(
                anyInt(), anyString())).thenReturn(new Intent());
        when(mMockDevicePolicyManagerInternal.createShowAdminSupportIntent(
                anyInt(), anyBoolean())).thenReturn(new Intent());

        mAms.startAddAccountSession(
                mMockAccountManagerResponse, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                null); // optionsIn

        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE), anyString());
        verify(mMockContext).startActivityAsUser(any(Intent.class), eq(UserHandle.SYSTEM));
        verify(mMockDevicePolicyManagerInternal).createShowAdminSupportIntent(
                anyInt(), anyBoolean());
    }

    @SmallTest
    public void testStartAddAccountSessionUserSuccessWithoutPasswordForwarding() throws Exception {
        unlockSystemUser();
        when(mMockContext.checkCallingOrSelfPermission(anyString())).thenReturn(
                PackageManager.PERMISSION_DENIED);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS);
        mAms.startAddAccountSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                false, // expectActivityLaunch
                options); // optionsIn
        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Bundle sessionBundle = result.getBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE);
        assertNotNull(sessionBundle);
        // Assert that session bundle is encrypted and hence data not visible.
        assertNull(sessionBundle.getString(AccountManagerServiceTestFixtures.SESSION_DATA_NAME_1));
        // Assert password is not returned
        assertNull(result.getString(AccountManager.KEY_PASSWORD));
        assertNull(result.getString(AccountManager.KEY_AUTHTOKEN, null));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN,
                result.getString(AccountManager.KEY_ACCOUNT_STATUS_TOKEN));
    }

    @SmallTest
    public void testStartAddAccountSessionUserSuccessWithPasswordForwarding() throws Exception {
        unlockSystemUser();
        when(mMockContext.checkCallingOrSelfPermission(anyString())).thenReturn(
                PackageManager.PERMISSION_GRANTED);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS);
        mAms.startAddAccountSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                false, // expectActivityLaunch
                options); // optionsIn

        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Bundle sessionBundle = result.getBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE);
        assertNotNull(sessionBundle);
        // Assert that session bundle is encrypted and hence data not visible.
        assertNull(sessionBundle.getString(AccountManagerServiceTestFixtures.SESSION_DATA_NAME_1));
        // Assert password is returned
        assertEquals(result.getString(AccountManager.KEY_PASSWORD),
                AccountManagerServiceTestFixtures.ACCOUNT_PASSWORD);
        assertNull(result.getString(AccountManager.KEY_AUTHTOKEN));
        assertEquals(AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN,
                result.getString(AccountManager.KEY_ACCOUNT_STATUS_TOKEN));
    }

    @SmallTest
    public void testStartAddAccountSessionUserReturnWithInvalidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_NO_MATCH);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE);

        mAms.startAddAccountSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                options); // optionsIn
        waitForLatch(latch);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
        verify(mMockAccountManagerResponse).onError(
                eq(AccountManager.ERROR_CODE_REMOTE_EXCEPTION), anyString());
    }

    @SmallTest
    public void testStartAddAccountSessionUserReturnWithValidIntent() throws Exception {
        unlockSystemUser();
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        when(mMockPackageManager.resolveActivityAsUser(
                any(Intent.class), anyInt(), anyInt())).thenReturn(resolveInfo);
        when(mMockPackageManager.checkSignatures(
                anyInt(), anyInt())).thenReturn(PackageManager.SIGNATURE_MATCH);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE);

        mAms.startAddAccountSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                true, // expectActivityLaunch
                options); // optionsIn
        waitForLatch(latch);

        verify(mMockAccountManagerResponse).onResult(mBundleCaptor.capture());
        Bundle result = mBundleCaptor.getValue();
        Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
        assertNotNull(intent);
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_RESULT));
        assertNotNull(intent.getParcelableExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK));
    }

    @SmallTest
    public void testStartAddAccountSessionUserError() throws Exception {
        unlockSystemUser();
        Bundle options = createOptionsWithAccountName(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_ERROR);
        options.putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_INVALID_RESPONSE);
        options.putString(AccountManager.KEY_ERROR_MESSAGE,
                AccountManagerServiceTestFixtures.ERROR_MESSAGE);

        final CountDownLatch latch = new CountDownLatch(1);
        Response response = new Response(latch, mMockAccountManagerResponse);
        mAms.startAddAccountSession(
                response, // response
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1, // accountType
                "authTokenType",
                null, // requiredFeatures
                false, // expectActivityLaunch
                options); // optionsIn

        waitForLatch(latch);
        verify(mMockAccountManagerResponse).onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        verify(mMockAccountManagerResponse, never()).onResult(any(Bundle.class));
    }

    private void waitForLatch(CountDownLatch latch) {
        try {
            latch.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            fail("should not throw an InterruptedException");
        }
    }

    private Bundle createOptionsWithAccountName(final String accountName) {
        Bundle sessionBundle = new Bundle();
        sessionBundle.putString(
                AccountManagerServiceTestFixtures.SESSION_DATA_NAME_1,
                AccountManagerServiceTestFixtures.SESSION_DATA_VALUE_1);
        sessionBundle.putString(AccountManager.KEY_ACCOUNT_TYPE,
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        Bundle options = new Bundle();
        options.putString(AccountManagerServiceTestFixtures.KEY_ACCOUNT_NAME, accountName);
        options.putBundle(AccountManagerServiceTestFixtures.KEY_ACCOUNT_SESSION_BUNDLE,
                sessionBundle);
        options.putString(AccountManagerServiceTestFixtures.KEY_ACCOUNT_PASSWORD,
                AccountManagerServiceTestFixtures.ACCOUNT_PASSWORD);
        return options;
    }

    private int readNumberOfAccountsFromDbFile(Context context, String dbName) {
        SQLiteDatabase ceDb = context.openOrCreateDatabase(dbName, 0, null);
        try (Cursor cursor = ceDb.rawQuery("SELECT count(*) FROM accounts", null)) {
            assertTrue(cursor.moveToNext());
            return cursor.getInt(0);
        }
    }

    private void unlockSystemUser() {
        mAms.onUserUnlocked(newIntentForUser(UserHandle.USER_SYSTEM));
    }

    private static Intent newIntentForUser(int userId) {
        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        return intent;
    }

    static class MyMockContext extends MockContext {
        private Context mTestContext;
        private Context mMockContext;

        MyMockContext(Context testContext, Context mockContext) {
            this.mTestContext = testContext;
            this.mMockContext = mockContext;
        }

        @Override
        public int checkCallingOrSelfPermission(final String permission) {
            return mMockContext.checkCallingOrSelfPermission(permission);
        }

        @Override
        public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
                UserHandle user) {
            return mTestContext.bindServiceAsUser(service, conn, flags, user);
        }

        @Override
        public void unbindService(ServiceConnection conn) {
            mTestContext.unbindService(conn);
        }

        @Override
        public PackageManager getPackageManager() {
            return mMockContext.getPackageManager();
        }

        @Override
        public String getPackageName() {
            return mTestContext.getPackageName();
        }

        @Override
        public Object getSystemService(String name) {
            return mMockContext.getSystemService(name);
        }

        @Override
        public String getSystemServiceName(Class<?> serviceClass) {
            return mMockContext.getSystemServiceName(serviceClass);
        }

        @Override
        public void startActivityAsUser(Intent intent, UserHandle user) {
            mMockContext.startActivityAsUser(intent, user);
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            return mMockContext.registerReceiver(receiver, filter);
        }

        @Override
        public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                IntentFilter filter, String broadcastPermission, Handler scheduler) {
            return mMockContext.registerReceiverAsUser(
                    receiver, user, filter, broadcastPermission, scheduler);
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(String file, int mode,
                SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
            return mTestContext.openOrCreateDatabase(file, mode, factory,errorHandler);
        }

        @Override
        public void sendBroadcastAsUser(Intent intent, UserHandle user) {
            mMockContext.sendBroadcastAsUser(intent, user);
        }

        @Override
        public String getOpPackageName() {
            return mMockContext.getOpPackageName();
        }
    }

    static class TestAccountAuthenticatorCache extends AccountAuthenticatorCache {
        public TestAccountAuthenticatorCache(Context realContext) {
            super(realContext);
        }

        @Override
        protected File getUserSystemDirectory(int userId) {
            return new File(mContext.getCacheDir(), "authenticator");
        }
    }

    static class TestInjector extends AccountManagerService.Injector {
        private Context mRealContext;
        TestInjector(Context realContext, Context mockContext) {
            super(mockContext);
            mRealContext = realContext;
        }

        @Override
        Looper getMessageHandlerLooper() {
            return Looper.getMainLooper();
        }

        @Override
        void addLocalService(AccountManagerInternal service) {
        }

        @Override
        IAccountAuthenticatorCache getAccountAuthenticatorCache() {
            return new TestAccountAuthenticatorCache(mRealContext);
        }

        @Override
        protected String getCeDatabaseName(int userId) {
            return new File(mRealContext.getCacheDir(), CE_DB).getPath();
        }

        @Override
        protected String getDeDatabaseName(int userId) {
            return new File(mRealContext.getCacheDir(), DE_DB).getPath();
        }

        @Override
        String getPreNDatabaseName(int userId) {
            return new File(mRealContext.getCacheDir(), PREN_DB).getPath();
        }

        @Override
        INotificationManager getNotificationManager() {
            return mock(INotificationManager.class);
        }
    }

    class Response extends IAccountManagerResponse.Stub {
        private CountDownLatch mLatch;
        private IAccountManagerResponse mMockResponse;
        public Response(CountDownLatch latch, IAccountManagerResponse mockResponse) {
            mLatch = latch;
            mMockResponse = mockResponse;
        }

        @Override
        public void onResult(Bundle bundle) {
            try {
                mMockResponse.onResult(bundle);
            } catch (RemoteException e) {
            }
            mLatch.countDown();
        }

        @Override
        public void onError(int code, String message) {
            try {
                mMockResponse.onError(code, message);
            } catch (RemoteException e) {
            }
            mLatch.countDown();
        }
    }
}
