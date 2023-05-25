/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.sdksandbox.SdkSandboxManager.ACTION_START_SANDBOXED_ACTIVITY;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_DEFAULT;
import static android.content.pm.ApplicationInfo.FLAG_SUSPENDED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.wm.ActivityInterceptorCallback.MAINLINE_SDK_SANDBOX_ORDER_ID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.testing.DexmakerShareClassLoaderRule;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;

import com.android.internal.app.BlockedAppActivity;
import com.android.internal.app.HarmfulAppWarningActivity;
import com.android.internal.app.SuspendedAppActivity;
import com.android.internal.app.UnlaunchableAppActivity;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link ActivityStartInterceptorTest}.
 *
 * Build/Install/Run:
 * atest WmTests:ActivityStartInterceptorTest
 */
@SmallTest
@Presubmit
public class ActivityStartInterceptorTest {
    private static final int TEST_USER_ID = 1;
    private static final int TEST_REAL_CALLING_UID = 2;
    private static final int TEST_REAL_CALLING_PID = 3;
    private static final String TEST_CALLING_PACKAGE = "com.test.caller";
    private static final int TEST_START_FLAGS = 4;
    private static final Intent ADMIN_SUPPORT_INTENT =
            new Intent("com.test.ADMIN_SUPPORT");
    private static final Intent CONFIRM_CREDENTIALS_INTENT =
            new Intent("com.test.CONFIRM_CREDENTIALS");
    private static final UserInfo PARENT_USER_INFO = new UserInfo(0 /* userId */, "parent",
            0 /* flags */);
    private static final String TEST_PACKAGE_NAME = "com.test.package";

    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    @Mock
    private Context mContext;
    @Mock
    private ActivityManagerService mAm;
    @Mock
    private ActivityTaskManagerService mService;
    @Mock
    private RootWindowContainer mRootWindowContainer;
    @Mock
    private ActivityTaskSupervisor mSupervisor;
    @Mock
    private DevicePolicyManagerInternal mDevicePolicyManager;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    private UserManager mUserManager;
    @Mock
    private KeyguardManager mKeyguardManager;
    @Mock
    private IPackageManager mPackageManager;
    @Mock
    private ActivityManagerInternal mAmInternal;
    @Mock
    private LockTaskController mLockTaskController;

    private ActivityStartInterceptor mInterceptor;
    private ActivityInfo mAInfo = new ActivityInfo();

    private SparseArray<ActivityInterceptorCallback> mActivityInterceptorCallbacks =
            new SparseArray<>();

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mService.mAmInternal = mAmInternal;
        mInterceptor = new ActivityStartInterceptor(
                mService, mSupervisor, mRootWindowContainer, mContext);
        mInterceptor.setStates(TEST_USER_ID, TEST_REAL_CALLING_PID, TEST_REAL_CALLING_UID,
                TEST_START_FLAGS, TEST_CALLING_PACKAGE, null);

        // Mock ActivityManagerInternal
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mAmInternal);

        // Mock DevicePolicyManagerInternal
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(DevicePolicyManagerInternal.class,
                mDevicePolicyManager);
        when(mDevicePolicyManager.createShowAdminSupportIntent(TEST_USER_ID, true))
                .thenReturn(ADMIN_SUPPORT_INTENT);
        when(mService.getPackageManagerInternalLocked()).thenReturn(mPackageManagerInternal);

        // Mock UserManager
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mUserManager.getProfileParent(TEST_USER_ID)).thenReturn(PARENT_USER_INFO);

        // Mock KeyguardManager
        when(mContext.getSystemService(Context.KEYGUARD_SERVICE)).thenReturn(mKeyguardManager);
        when(mKeyguardManager.createConfirmDeviceCredentialIntent(
                nullable(CharSequence.class), nullable(CharSequence.class), eq(TEST_USER_ID),
                eq(true))).thenReturn(CONFIRM_CREDENTIALS_INTENT);

        // Mock PackageManager
        when(mService.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getHarmfulAppWarning(TEST_PACKAGE_NAME, TEST_USER_ID))
                .thenReturn(null);

        // Mock LockTaskController
        mAInfo.lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_DEFAULT;
        when(mService.getLockTaskController()).thenReturn(mLockTaskController);
        when(mLockTaskController.isActivityAllowed(
                TEST_USER_ID, TEST_PACKAGE_NAME, LOCK_TASK_LAUNCH_MODE_DEFAULT))
                .thenReturn(true);

        // Mock the activity start callbacks
        when(mService.getActivityInterceptorCallbacks()).thenReturn(mActivityInterceptorCallbacks);

        // Initialise activity info
        mAInfo.applicationInfo = new ApplicationInfo();
        mAInfo.packageName = mAInfo.applicationInfo.packageName = TEST_PACKAGE_NAME;
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
    }

    @Test
    public void testSuspendedByAdminPackage() {
        // GIVEN the package we're about to launch is currently suspended
        mAInfo.applicationInfo.flags = FLAG_SUSPENDED;

        when(mPackageManagerInternal.getSuspendingPackage(TEST_PACKAGE_NAME, TEST_USER_ID))
                .thenReturn(PLATFORM_PACKAGE_NAME);

        // THEN calling intercept returns true
        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, null, 0, 0, null));

        // THEN the returned intent is the admin support intent
        assertEquals(ADMIN_SUPPORT_INTENT, mInterceptor.mIntent);
    }

    @Test
    public void testSuspendedPackage() {
        final String suspendingPackage = "com.test.suspending.package";
        final SuspendDialogInfo dialogInfo = suspendPackage(suspendingPackage);
        // THEN calling intercept returns true
        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, null, 0, 0, null));

        // Check intent parameters
        assertEquals(dialogInfo,
                mInterceptor.mIntent.getParcelableExtra(SuspendedAppActivity.EXTRA_DIALOG_INFO));
        assertEquals(suspendingPackage,
                mInterceptor.mIntent.getStringExtra(SuspendedAppActivity.EXTRA_SUSPENDING_PACKAGE));
        assertEquals(TEST_PACKAGE_NAME,
                mInterceptor.mIntent.getStringExtra(SuspendedAppActivity.EXTRA_SUSPENDED_PACKAGE));
        assertEquals(TEST_USER_ID, mInterceptor.mIntent.getIntExtra(Intent.EXTRA_USER_ID, -1000));
    }

    private SuspendDialogInfo suspendPackage(String suspendingPackage) {
        mAInfo.applicationInfo.flags = FLAG_SUSPENDED;
        final SuspendDialogInfo dialogInfo = new SuspendDialogInfo.Builder()
                .setMessage("Test Message")
                .setIcon(0x11110001)
                .build();
        when(mPackageManagerInternal.getSuspendingPackage(TEST_PACKAGE_NAME, TEST_USER_ID))
                .thenReturn(suspendingPackage);
        when(mPackageManagerInternal.getSuspendedDialogInfo(TEST_PACKAGE_NAME, suspendingPackage,
                TEST_USER_ID)).thenReturn(dialogInfo);
        return dialogInfo;
    }

    @Test
    public void testInterceptLockTaskModeViolationPackage() {
        when(mLockTaskController.isActivityAllowed(
                TEST_USER_ID, TEST_PACKAGE_NAME, LOCK_TASK_LAUNCH_MODE_DEFAULT))
                .thenReturn(false);

        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, null, 0, 0, null));

        assertTrue(BlockedAppActivity.createIntent(TEST_USER_ID, TEST_PACKAGE_NAME)
                .filterEquals(mInterceptor.mIntent));
    }

    @Test
    public void testInterceptQuietProfile_keepProfilesRunningEnabled() {
        // GIVEN that the user the activity is starting as is currently in quiet mode and
        // profiles are kept running when in quiet mode.
        when(mUserManager.isQuietModeEnabled(eq(UserHandle.of(TEST_USER_ID)))).thenReturn(true);
        when(mDevicePolicyManager.isKeepProfilesRunningEnabled()).thenReturn(true);

        // THEN calling intercept returns false because package also has to be suspended.
        assertFalse(mInterceptor.intercept(null, null, mAInfo, null, null,  null, 0, 0, null));
    }

    @Test
    public void testInterceptQuietProfile_keepProfilesRunningDisabled() {
        // GIVEN that the user the activity is starting as is currently in quiet mode and
        // profiles are stopped when in quiet mode (pre-U behavior, no profile app suspension).
        when(mUserManager.isQuietModeEnabled(eq(UserHandle.of(TEST_USER_ID)))).thenReturn(true);
        when(mDevicePolicyManager.isKeepProfilesRunningEnabled()).thenReturn(false);

        // THEN calling intercept returns true
        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null,  null, 0, 0, null));

        // THEN the returned intent is the quiet mode intent
        assertTrue(UnlaunchableAppActivity.createInQuietModeDialogIntent(TEST_USER_ID)
                .filterEquals(mInterceptor.mIntent));
    }

    @Test
    public void testInterceptQuietProfileWhenPackageSuspended_keepProfilesRunningEnabled() {
        // GIVEN that the user the activity is starting as is currently in quiet mode,
        // the package is suspended and profiles are kept running while in quiet mode.
        suspendPackage("com.test.suspending.package");
        when(mUserManager.isQuietModeEnabled(eq(UserHandle.of(TEST_USER_ID)))).thenReturn(true);
        when(mDevicePolicyManager.isKeepProfilesRunningEnabled()).thenReturn(true);

        // THEN calling intercept returns true
        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, null, 0, 0, null));

        // THEN the returned intent is the quiet mode intent
        assertTrue(UnlaunchableAppActivity.createInQuietModeDialogIntent(TEST_USER_ID)
                .filterEquals(mInterceptor.mIntent));
    }

    @Test
    public void testInterceptQuietProfileWhenPackageSuspended_keepProfilesRunningDisabled() {
        // GIVEN that the user the activity is starting as is currently in quiet mode,
        // the package is suspended and profiles are stopped while in quiet mode.
        suspendPackage("com.test.suspending.package");
        when(mUserManager.isQuietModeEnabled(eq(UserHandle.of(TEST_USER_ID)))).thenReturn(true);
        when(mDevicePolicyManager.isKeepProfilesRunningEnabled()).thenReturn(false);

        // THEN calling intercept returns true
        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, null, 0, 0, null));

        // THEN the returned intent is the quiet mode intent
        assertTrue(UnlaunchableAppActivity.createInQuietModeDialogIntent(TEST_USER_ID)
                .filterEquals(mInterceptor.mIntent));
    }

    @Test
    public void testLockedManagedProfile() {
        // GIVEN that the user the activity is starting as is currently locked
        when(mAmInternal.shouldConfirmCredentials(TEST_USER_ID)).thenReturn(true);

        // THEN calling intercept returns true
        mInterceptor.intercept(null, null, mAInfo, null, null, null, 0, 0, null);

        // THEN the returned intent is the confirm credentials intent
        assertTrue(CONFIRM_CREDENTIALS_INTENT.filterEquals(mInterceptor.mIntent));
    }

    @Test
    public void testLockedManagedProfileShowWhenLocked() {
        Intent originalIntent = new Intent();
        // GIVEN that the user is locked but its storage is unlocked and the activity has
        // showWhenLocked flag
        when(mAmInternal.shouldConfirmCredentials(TEST_USER_ID)).thenReturn(true);
        when(mUserManager.isUserUnlocked(eq(TEST_USER_ID))).thenReturn(true);
        mAInfo.flags |= ActivityInfo.FLAG_SHOW_WHEN_LOCKED;

        // THEN calling intercept returns true
        mInterceptor.intercept(originalIntent, null, mAInfo, null, null, null, 0, 0, null);

        // THEN the returned intent is original intent
        assertSame(originalIntent, mInterceptor.mIntent);
    }

    @Test
    public void testLockedManagedProfileShowWhenLockedEncryptedStorage() {
        // GIVEN that the user storage is locked, activity has showWhenLocked flag but no
        // directBootAware flag
        when(mAmInternal.shouldConfirmCredentials(TEST_USER_ID)).thenReturn(true);
        when(mUserManager.isUserUnlocked(eq(TEST_USER_ID))).thenReturn(false);
        mAInfo.flags |= ActivityInfo.FLAG_SHOW_WHEN_LOCKED;
        mAInfo.directBootAware = false;

        // THEN calling intercept returns true
        mInterceptor.intercept(null, null, mAInfo, null, null, null, 0, 0, null);

        // THEN the returned intent is the confirm credentials intent
        assertTrue(CONFIRM_CREDENTIALS_INTENT.filterEquals(mInterceptor.mIntent));
    }

    @Test
    public void testLockedManagedProfileShowWhenLockedEncryptedStorageDirectBootAware() {
        Intent originalIntent = new Intent();
        // GIVEN that the user storage is locked, activity has showWhenLocked flag and
        // directBootAware flag
        when(mAmInternal.shouldConfirmCredentials(TEST_USER_ID)).thenReturn(true);
        when(mUserManager.isUserUnlocked(eq(TEST_USER_ID))).thenReturn(false);
        mAInfo.flags |= ActivityInfo.FLAG_SHOW_WHEN_LOCKED;
        mAInfo.directBootAware = true;

        // THEN calling intercept returns true
        mInterceptor.intercept(originalIntent, null, mAInfo, null, null, null, 0, 0, null);

        // THEN the returned intent is original intent
        assertSame(originalIntent, mInterceptor.mIntent);
    }

    @Test
    public void testHarmfulAppWarning() throws RemoteException {
        // GIVEN the package we're about to launch has a harmful app warning set
        when(mPackageManager.getHarmfulAppWarning(TEST_PACKAGE_NAME, TEST_USER_ID))
                .thenReturn("This app is bad");

        // THEN calling intercept returns true
        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, null, 0, 0, null));

        // THEN the returned intent is the harmful app warning intent
        assertEquals(HarmfulAppWarningActivity.class.getName(),
                mInterceptor.mIntent.getComponent().getClassName());
    }

    @Test
    public void testNoInterception() {
        // GIVEN that none of the interception conditions are met

        // THEN calling intercept returns false
        assertFalse(mInterceptor.intercept(null, null, mAInfo, null, null, null, 0, 0, null));
    }

    public void addMockInterceptorCallback(
            @Nullable Intent intent, @Nullable ActivityOptions activityOptions) {
        addMockInterceptorCallback(intent, activityOptions, false);
    }

    public void addMockInterceptorCallback(
            @Nullable Intent intent, @Nullable ActivityOptions activityOptions,
            boolean skipResolving) {
        int size = mActivityInterceptorCallbacks.size();
        mActivityInterceptorCallbacks.put(size, new ActivityInterceptorCallback() {
            @Override
            public ActivityInterceptResult onInterceptActivityLaunch(@NonNull
                    ActivityInterceptorInfo info) {
                if (intent == null && activityOptions == null) {
                    return null;
                }
                return new ActivityInterceptResult(
                        intent != null ? intent : info.getIntent(),
                        activityOptions != null ? activityOptions : info.getCheckedOptions(),
                        skipResolving);
            }
        });
    }

    @Test
    public void testInterceptionCallback_singleCallback() {
        addMockInterceptorCallback(
                new Intent("android.test.foo"),
                ActivityOptions.makeBasic().setLaunchDisplayId(3));

        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, null, 0, 0, null));
        assertEquals("android.test.foo", mInterceptor.mIntent.getAction());
        assertEquals(3, mInterceptor.mActivityOptions.getLaunchDisplayId());
    }

    @Test
    public void testInterceptionCallback_singleCallbackReturnsNull() {
        addMockInterceptorCallback(null, null);

        assertFalse(mInterceptor.intercept(null, null, mAInfo, null, null, null, 0, 0, null));
    }

    @Test
    public void testInterceptionCallback_fallbackToSecondCallback() {
        addMockInterceptorCallback(null, null);
        addMockInterceptorCallback(new Intent("android.test.second"), null);

        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, null, 0, 0, null));
        assertEquals("android.test.second", mInterceptor.mIntent.getAction());
    }

    @Test
    public void testInterceptionCallback_skipResolving() {
        addMockInterceptorCallback(
                new Intent("android.test.foo"),
                ActivityOptions.makeBasic().setLaunchDisplayId(3), true);
        ActivityInfo aInfo = mAInfo;
        assertTrue(mInterceptor.intercept(null, null, aInfo, null, null, null, 0, 0, null));
        assertEquals("android.test.foo", mInterceptor.mIntent.getAction());
        assertEquals(3, mInterceptor.mActivityOptions.getLaunchDisplayId());
        assertEquals(aInfo, mInterceptor.mAInfo); // mAInfo should not be resolved
    }

    @Test
    public void testInterceptionCallback_NoSkipResolving() throws InterruptedException {
        addMockInterceptorCallback(
                new Intent("android.test.foo"),
                ActivityOptions.makeBasic().setLaunchDisplayId(3));
        ActivityInfo aInfo = mAInfo;
        assertTrue(mInterceptor.intercept(null, null, aInfo, null, null, null, 0, 0, null));
        assertEquals("android.test.foo", mInterceptor.mIntent.getAction());
        assertEquals(3, mInterceptor.mActivityOptions.getLaunchDisplayId());
        assertNotEquals(aInfo, mInterceptor.mAInfo); // mAInfo should be resolved after intercept
    }

    @Test
    public void testActivityLaunchedCallback_singleCallback() {
        addMockInterceptorCallback(null, null);

        assertEquals(1, mActivityInterceptorCallbacks.size());
        final ActivityInterceptorCallback callback = mActivityInterceptorCallbacks.valueAt(0);
        spyOn(callback);
        mInterceptor.onActivityLaunched(null, mock(ActivityRecord.class));

        verify(callback, times(1)).onActivityLaunched(any(), any(), any());
    }

    @Test
    public void testSandboxServiceInterceptionHappensToIntentWithSandboxActivityAction() {
        ActivityInterceptorCallback spyCallback = Mockito.spy(info -> null);
        mActivityInterceptorCallbacks.put(MAINLINE_SDK_SANDBOX_ORDER_ID, spyCallback);

        PackageManager packageManagerMock = mock(PackageManager.class);
        String sandboxPackageNameMock = "com.sandbox.mock";
        when(mContext.getPackageManager()).thenReturn(packageManagerMock);
        when(packageManagerMock.getSdkSandboxPackageName()).thenReturn(sandboxPackageNameMock);

        Intent intent = new Intent().setAction(ACTION_START_SANDBOXED_ACTIVITY);
        mInterceptor.intercept(intent, null, mAInfo, null, null, null, 0, 0, null);

        verify(spyCallback, times(1)).onInterceptActivityLaunch(
                any(ActivityInterceptorCallback.ActivityInterceptorInfo.class));
    }

    @Test
    public void testSandboxServiceInterceptionHappensToIntentWithSandboxPackage() {
        ActivityInterceptorCallback spyCallback = Mockito.spy(info -> null);
        mActivityInterceptorCallbacks.put(MAINLINE_SDK_SANDBOX_ORDER_ID, spyCallback);

        PackageManager packageManagerMock = mock(PackageManager.class);
        String sandboxPackageNameMock = "com.sandbox.mock";
        when(mContext.getPackageManager()).thenReturn(packageManagerMock);
        when(packageManagerMock.getSdkSandboxPackageName()).thenReturn(sandboxPackageNameMock);

        Intent intent = new Intent().setPackage(sandboxPackageNameMock);
        mInterceptor.intercept(intent, null, mAInfo, null, null, null, 0, 0, null);

        verify(spyCallback, times(1)).onInterceptActivityLaunch(
                any(ActivityInterceptorCallback.ActivityInterceptorInfo.class));
    }

    @Test
    public void testSandboxServiceInterceptionHappensToIntentWithComponentNameWithSandboxPackage() {
        ActivityInterceptorCallback spyCallback = Mockito.spy(info -> null);
        mActivityInterceptorCallbacks.put(MAINLINE_SDK_SANDBOX_ORDER_ID, spyCallback);

        PackageManager packageManagerMock = mock(PackageManager.class);
        String sandboxPackageNameMock = "com.sandbox.mock";
        when(mContext.getPackageManager()).thenReturn(packageManagerMock);
        when(packageManagerMock.getSdkSandboxPackageName()).thenReturn(sandboxPackageNameMock);

        Intent intent = new Intent().setComponent(new ComponentName(sandboxPackageNameMock, ""));
        mInterceptor.intercept(intent, null, mAInfo, null, null, null, 0, 0, null);

        verify(spyCallback, times(1)).onInterceptActivityLaunch(
                any(ActivityInterceptorCallback.ActivityInterceptorInfo.class));
    }

    @Test
    public void testSandboxServiceInterceptionNotCalledWhenIntentNotRelatedToSandbox() {
        ActivityInterceptorCallback spyCallback = Mockito.spy(info -> null);
        mActivityInterceptorCallbacks.put(MAINLINE_SDK_SANDBOX_ORDER_ID, spyCallback);

        PackageManager packageManagerMock = mock(PackageManager.class);
        String sandboxPackageNameMock = "com.sandbox.mock";
        when(mContext.getPackageManager()).thenReturn(packageManagerMock);
        when(packageManagerMock.getSdkSandboxPackageName()).thenReturn(sandboxPackageNameMock);

        // Intent: null
        mInterceptor.intercept(null, null, mAInfo, null, null, null, 0, 0, null);

        // Action: null, Package: null, ComponentName: null
        Intent intent = new Intent();
        mInterceptor.intercept(intent, null, mAInfo, null, null, null, 0, 0, null);

        // Wrong Action
        intent = new Intent().setAction(Intent.ACTION_VIEW);
        mInterceptor.intercept(intent, null, mAInfo, null, null, null, 0, 0, null);

        // Wrong Package
        intent = new Intent().setPackage("Random");
        mInterceptor.intercept(intent, null, mAInfo, null, null, null, 0, 0, null);

        // Wrong ComponentName's package
        intent = new Intent().setComponent(new ComponentName("Random", ""));
        mInterceptor.intercept(intent, null, mAInfo, null, null, null, 0, 0, null);

        verify(spyCallback, never()).onInterceptActivityLaunch(
                any(ActivityInterceptorCallback.ActivityInterceptorInfo.class));
    }
}
