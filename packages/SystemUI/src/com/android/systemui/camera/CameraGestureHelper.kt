/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.camera

import android.app.ActivityManager
import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.AsyncTask
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import android.view.WindowManager
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.camera.CameraIntents.Companion.isSecureCameraIntent
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.PanelViewController
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject

/**
 * Helps with handling camera-related gestures (for example, double-tap the power button to launch
 * the camera).
 */
class CameraGestureHelper @Inject constructor(
    private val context: Context,
    private val centralSurfaces: CentralSurfaces,
    private val keyguardStateController: KeyguardStateController,
    private val packageManager: PackageManager,
    private val activityManager: ActivityManager,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val cameraIntents: CameraIntentsWrapper,
    private val contentResolver: ContentResolver,
) {
    /**
     * Whether the camera application can be launched for the camera launch gesture.
     */
    fun canCameraGestureBeLaunched(statusBarState: Int): Boolean {
        if (!centralSurfaces.isCameraAllowedByAdmin) {
            return false
        }

        val resolveInfo: ResolveInfo = packageManager.resolveActivityAsUser(
            getStartCameraIntent(),
            PackageManager.MATCH_DEFAULT_ONLY,
            KeyguardUpdateMonitor.getCurrentUser()
        )
        val resolvedPackage = resolveInfo.activityInfo?.packageName
        return (resolvedPackage != null &&
                (statusBarState != StatusBarState.SHADE ||
                !isForegroundApp(resolvedPackage)))
    }

    /**
     * Launches the camera.
     *
     * @param source The source of the camera launch, to be passed to the camera app via [Intent]
     */
    fun launchCamera(source: Int) {
        val intent: Intent = getStartCameraIntent()
        intent.putExtra(EXTRA_CAMERA_LAUNCH_SOURCE, source)
        val wouldLaunchResolverActivity = activityIntentHelper.wouldLaunchResolverActivity(
            intent, KeyguardUpdateMonitor.getCurrentUser()
        )
        if (isSecureCameraIntent(intent) && !wouldLaunchResolverActivity) {
            AsyncTask.execute {
                // Normally an activity will set its requested rotation animation on its window.
                // However when launching an activity causes the orientation to change this is too
                // late. In these cases, the default animation is used. This doesn't look good for
                // the camera (as it rotates the camera contents out of sync with physical reality).
                // Therefore, we ask the WindowManager to force the cross-fade animation if an
                // orientation change happens to occur during the launch.
                val activityOptions = ActivityOptions.makeBasic()
                activityOptions.setDisallowEnterPictureInPictureWhileLaunching(true)
                activityOptions.rotationAnimationHint =
                    WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
                try {
                    ActivityTaskManager.getService().startActivityAsUser(
                        null,
                        context.basePackageName,
                        context.attributionTag,
                        intent,
                        intent.resolveTypeIfNeeded(contentResolver),
                        null,
                        null,
                        0,
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                        null,
                        activityOptions.toBundle(),
                        UserHandle.CURRENT.identifier,
                    )
                } catch (e: RemoteException) {
                    Log.w(
                        PanelViewController.TAG,
                        "Unable to start camera activity",
                        e
                    )
                }
            }
        } else {
            // We need to delay starting the activity because ResolverActivity finishes itself if
            // launched from behind the lock-screen.
            activityStarter.startActivity(intent, false /* dismissShade */)
        }
    }

    /**
     * Returns an [Intent] that can be used to start the camera app such that it occludes the
     * lock-screen, if needed.
     */
    private fun getStartCameraIntent(): Intent {
        val isLockScreenDismissible = keyguardStateController.canDismissLockScreen()
        val isSecure = keyguardStateController.isMethodSecure
        return if (isSecure && !isLockScreenDismissible) {
            cameraIntents.getSecureCameraIntent()
        } else {
            cameraIntents.getInsecureCameraIntent()
        }
    }

    /**
     * Returns `true` if the application with the given package name is running in the foreground;
     * `false` otherwise
     */
    private fun isForegroundApp(packageName: String): Boolean {
        val tasks: List<RunningTaskInfo> = activityManager.getRunningTasks(1)
        return tasks.isNotEmpty() && packageName == tasks[0].topActivity.packageName
    }

    companion object {
        private const val EXTRA_CAMERA_LAUNCH_SOURCE = "com.android.systemui.camera_launch_source"
    }
}
