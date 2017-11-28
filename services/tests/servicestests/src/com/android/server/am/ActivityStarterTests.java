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
 * limitations under the License
 */

package com.android.server.am;

import static android.app.ActivityManager.START_ABORTED;
import static android.app.ActivityManager.START_CLASS_NOT_FOUND;
import static android.app.ActivityManager.START_FORWARD_AND_REQUEST_CONFLICT;
import static android.app.ActivityManager.START_NOT_VOICE_COMPATIBLE;
import static android.app.ActivityManager.START_SUCCESS;
import static android.app.ActivityManager.START_SWITCHES_CANCELED;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.service.voice.IVoiceInteractionSession;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.runner.RunWith;
import org.junit.Test;

import static com.android.server.am.ActivityManagerService.ANIMATE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import static android.app.ActivityManager.START_PERMISSION_DENIED;
import static android.app.ActivityManager.START_INTENT_NOT_RESOLVED;

import com.android.internal.os.BatteryStatsImpl;

/**
 * Tests for the {@link ActivityStack} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.am.ActivityStarterTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityStarterTests extends ActivityTestsBase {
    private ActivityManagerService mService;
    private ActivityStarter mStarter;
    private IPackageManager mPackageManager;

    private static final int PRECONDITION_NO_CALLER_APP = 1;
    private static final int PRECONDITION_NO_INTENT_COMPONENT = 1 << 1;
    private static final int PRECONDITION_NO_ACTIVITY_INFO = 1 << 2;
    private static final int PRECONDITION_SOURCE_PRESENT = 1 << 3;
    private static final int PRECONDITION_REQUEST_CODE = 1 << 4;
    private static final int PRECONDITION_SOURCE_VOICE_SESSION = 1 << 5;
    private static final int PRECONDITION_NO_VOICE_SESSION_SUPPORT = 1 << 6;
    private static final int PRECONDITION_DIFFERENT_UID = 1 << 7;
    private static final int PRECONDITION_ACTIVITY_SUPPORTS_INTENT_EXCEPTION = 1 << 8;
    private static final int PRECONDITION_CANNOT_START_ANY_ACTIVITY = 1 << 9;
    private static final int PRECONDITION_DISALLOW_APP_SWITCHING = 1 << 10;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mService = createActivityManagerService();
        mStarter = new ActivityStarter(mService);
    }

    @Test
    public void testUpdateLaunchBounds() throws Exception {
        // When in a non-resizeable stack, the task bounds should be updated.
        final TaskRecord task = new TaskBuilder(mService.mStackSupervisor)
                .setStack(mService.mStackSupervisor.getDefaultDisplay().createStack(
                        WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */))
                .build();
        final Rect bounds = new Rect(10, 10, 100, 100);

        mStarter.updateBounds(task, bounds);
        assertEquals(task.getOverrideBounds(), bounds);
        assertEquals(new Rect(), task.getStack().getOverrideBounds());

        // When in a resizeable stack, the stack bounds should be updated as well.
        final TaskRecord task2 = new TaskBuilder(mService.mStackSupervisor)
                .setStack(mService.mStackSupervisor.getDefaultDisplay().createStack(
                        WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */))
                .build();
        assertTrue(task2.getStack() instanceof PinnedActivityStack);
        mStarter.updateBounds(task2, bounds);

        verify(mService, times(1)).resizeStack(eq(task2.getStack().mStackId),
                eq(bounds), anyBoolean(), anyBoolean(), anyBoolean(), anyInt());

        // In the case of no animation, the stack and task bounds should be set immediately.
        if (!ANIMATE) {
            assertEquals(task2.getStack().getOverrideBounds(), bounds);
            assertEquals(task2.getOverrideBounds(), bounds);
        } else {
            assertEquals(task2.getOverrideBounds(), new Rect());
        }
    }

    @Test
    public void testStartActivityPreconditions() throws Exception {
        verifyStartActivityPreconditions(PRECONDITION_NO_CALLER_APP, START_PERMISSION_DENIED);
        verifyStartActivityPreconditions(PRECONDITION_NO_INTENT_COMPONENT,
                START_INTENT_NOT_RESOLVED);
        verifyStartActivityPreconditions(PRECONDITION_NO_ACTIVITY_INFO, START_CLASS_NOT_FOUND);
        verifyStartActivityPreconditions(PRECONDITION_SOURCE_PRESENT | PRECONDITION_REQUEST_CODE,
                Intent.FLAG_ACTIVITY_FORWARD_RESULT, START_FORWARD_AND_REQUEST_CONFLICT);
        verifyStartActivityPreconditions(
                PRECONDITION_SOURCE_PRESENT | PRECONDITION_NO_VOICE_SESSION_SUPPORT
                        | PRECONDITION_SOURCE_VOICE_SESSION | PRECONDITION_DIFFERENT_UID,
                START_NOT_VOICE_COMPATIBLE);
        verifyStartActivityPreconditions(
                PRECONDITION_SOURCE_PRESENT | PRECONDITION_NO_VOICE_SESSION_SUPPORT
                        | PRECONDITION_SOURCE_VOICE_SESSION | PRECONDITION_DIFFERENT_UID
                        | PRECONDITION_ACTIVITY_SUPPORTS_INTENT_EXCEPTION,
                START_NOT_VOICE_COMPATIBLE);
        verifyStartActivityPreconditions(PRECONDITION_CANNOT_START_ANY_ACTIVITY, START_ABORTED);
        verifyStartActivityPreconditions(PRECONDITION_DISALLOW_APP_SWITCHING,
                START_SWITCHES_CANCELED);
    }

    private static boolean containsConditions(int preconditions, int mask) {
        return (preconditions & mask) == mask;
    }

    private void verifyStartActivityPreconditions(int preconditions, int expectedResult) {
        verifyStartActivityPreconditions(preconditions, 0 /*launchFlags*/, expectedResult);
    }

    /**
     * Excercises how the {@link ActivityStarter} reacts to various preconditions. The caller
     * provides a bitmask of all the set conditions (such as {@link #PRECONDITION_NO_CALLER_APP})
     * and the launch flags specified in the intent. The method constructs a call to
     * {@link ActivityStarter#startActivityLocked} based on these preconditions and ensures the
     * result matches the expected. It is important to note that the method also checks side effects
     * of the start, such as ensuring {@link ActivityOptions#abort()} is called in the relevant
     * scenarios.
     * @param preconditions A bitmask representing the preconditions for the launch
     * @param launchFlags The launch flags to be provided by the launch {@link Intent}.
     * @param expectedResult The expected result from the launch.
     */
    private void verifyStartActivityPreconditions(int preconditions, int launchFlags,
            int expectedResult) {
        final ActivityManagerService service = createActivityManagerService();
        final IPackageManager packageManager = mock(IPackageManager.class);
        final ActivityStarter starter = new ActivityStarter(service);

        final IApplicationThread caller = mock(IApplicationThread.class);

        // If no caller app, return {@code null} {@link ProcessRecord}.
        final ProcessRecord record = containsConditions(preconditions, PRECONDITION_NO_CALLER_APP)
                ? null : new ProcessRecord(mock(BatteryStatsImpl.class),
                mock(ApplicationInfo.class), null, 0);

        doReturn(record).when(service).getRecordForAppLocked(anyObject());

        final Intent intent = new Intent();
        intent.setFlags(launchFlags);

        final ActivityInfo aInfo = containsConditions(preconditions, PRECONDITION_NO_ACTIVITY_INFO)
                ?  null : new ActivityInfo();

        if (aInfo != null) {
            aInfo.applicationInfo = new ApplicationInfo();
            aInfo.applicationInfo.packageName = ActivityBuilder.DEFAULT_PACKAGE;
        }

        IVoiceInteractionSession voiceSession =
                containsConditions(preconditions, PRECONDITION_SOURCE_VOICE_SESSION)
                ? mock(IVoiceInteractionSession.class) : null;

        // Create source token
        final ActivityBuilder builder = new ActivityBuilder(service).setTask(
                new TaskBuilder(service.mStackSupervisor).setVoiceSession(voiceSession).build());

        // Offset uid by one from {@link ActivityInfo} to simulate different uids.
        if (containsConditions(preconditions, PRECONDITION_DIFFERENT_UID)) {
            builder.setUid(aInfo.applicationInfo.uid + 1);
        }

        final ActivityRecord source = builder.build();

        if (!containsConditions(preconditions, PRECONDITION_NO_INTENT_COMPONENT)) {
            intent.setComponent(source.realActivity);
        }

        if (containsConditions(preconditions, PRECONDITION_DISALLOW_APP_SWITCHING)) {
            doReturn(false).when(service).checkAppSwitchAllowedLocked(anyInt(), anyInt(), anyInt(),
                    anyInt(), any());
        }

        if (containsConditions(preconditions,PRECONDITION_CANNOT_START_ANY_ACTIVITY)) {
            doReturn(false).when(service.mStackSupervisor).checkStartAnyActivityPermission(
                    any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), anyBoolean(),
                    any(), any(), any(), any());
        }

        try {
            if (containsConditions(preconditions,
                    PRECONDITION_ACTIVITY_SUPPORTS_INTENT_EXCEPTION)) {
                doAnswer((inv) -> {
                    throw new RemoteException();
                }).when(packageManager).activitySupportsIntent(eq(source.realActivity), eq(intent),
                        any());
            } else {
                doReturn(!containsConditions(preconditions, PRECONDITION_NO_VOICE_SESSION_SUPPORT))
                        .when(packageManager).activitySupportsIntent(eq(source.realActivity),
                        eq(intent), any());
            }
        } catch (RemoteException e) {
        }

        final IBinder resultTo = containsConditions(preconditions, PRECONDITION_SOURCE_PRESENT)
                || containsConditions(preconditions, PRECONDITION_SOURCE_VOICE_SESSION)
                ? source.appToken : null;

        final int requestCode = containsConditions(preconditions, PRECONDITION_REQUEST_CODE)
                ? 1 : 0;

        final int result = starter.startActivityLocked(caller, intent,
                null /*ephemeralIntent*/, null /*resolvedType*/, aInfo, null /*rInfo*/,
                null /*voiceSession*/, null /*voiceInteractor*/, resultTo,
                null /*resultWho*/, requestCode, 0 /*callingPid*/, 0 /*callingUid*/,
                null /*callingPackage*/, 0 /*realCallingPid*/, 0 /*realCallingUid*/,
                0 /*startFlags*/, null /*options*/, false /*ignoreTargetSecurity*/,
                false /*componentSpecified*/, null /*outActivity*/,
                null /*inTask*/, "testLaunchActivityPermissionDenied");

        // In some cases the expected result internally is different than the published result. We
        // must use ActivityStarter#getExternalResult to translate.
        assertEquals(ActivityStarter.getExternalResult(expectedResult), result);

        // Ensure that {@link ActivityOptions} are aborted with unsuccessful result.
        if (expectedResult != START_SUCCESS) {
            final ActivityOptions options = spy(ActivityOptions.makeBasic());
            final int optionResult = starter.startActivityLocked(caller, intent,
                    null /*ephemeralIntent*/, null /*resolvedType*/, aInfo, null /*rInfo*/,
                    null /*voiceSession*/, null /*voiceInteractor*/, resultTo,
                    null /*resultWho*/, requestCode, 0 /*callingPid*/, 0 /*callingUid*/,
                    null /*callingPackage*/, 0 /*realCallingPid*/, 0 /*realCallingUid*/,
                    0 /*startFlags*/, options /*options*/, false /*ignoreTargetSecurity*/,
                    false /*componentSpecified*/, null /*outActivity*/,
                    null /*inTask*/, "testLaunchActivityPermissionDenied");
            verify(options, times(1)).abort();
        }
    }

// TODO(b/69270257): Add test to verify task layout is passed additional data such as activity and
// source.
//    @Test
//    public void testCreateTaskLayout() {
//    }
}
