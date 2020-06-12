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

package com.android.systemui.media

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaDescription
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.UserHandle
import android.service.media.MediaBrowserService
import android.util.Log
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.Utils
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MediaResumeListener"

private const val MEDIA_PREFERENCES = "media_control_prefs"
private const val MEDIA_PREFERENCE_KEY = "browser_components_"

@Singleton
class MediaResumeListener @Inject constructor(
    private val context: Context,
    private val broadcastDispatcher: BroadcastDispatcher,
    @Background private val backgroundExecutor: Executor
) : MediaDataManager.Listener {

    private val useMediaResumption: Boolean = Utils.useMediaResumption(context)
    private val resumeComponents: ConcurrentLinkedQueue<ComponentName> = ConcurrentLinkedQueue()

    lateinit var addTrackToResumeCallback: (
        MediaDescription,
        Runnable,
        MediaSession.Token,
        String,
        PendingIntent,
        String
    ) -> Unit
    lateinit var resumeComponentFoundCallback: (String, Runnable?) -> Unit

    private var mediaBrowser: ResumeMediaBrowser? = null
    private var currentUserId: Int

    private val userChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_USER_UNLOCKED == intent.action) {
                loadMediaResumptionControls()
            } else if (Intent.ACTION_USER_SWITCHED == intent.action) {
                currentUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1)
                loadSavedComponents()
                loadMediaResumptionControls()
            }
        }
    }

    private val mediaBrowserCallback = object : ResumeMediaBrowser.Callback() {
        override fun addTrack(
            desc: MediaDescription,
            component: ComponentName,
            browser: ResumeMediaBrowser
        ) {
            val token = browser.token
            val appIntent = browser.appIntent
            val pm = context.getPackageManager()
            var appName: CharSequence = component.packageName
            val resumeAction = getResumeAction(component)
            try {
                appName = pm.getApplicationLabel(
                        pm.getApplicationInfo(component.packageName, 0))
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Error getting package information", e)
            }

            Log.d(TAG, "Adding resume controls $desc")
            addTrackToResumeCallback(desc, resumeAction, token, appName.toString(), appIntent,
                component.packageName)
        }
    }

    init {
        currentUserId = context.userId
        if (useMediaResumption) {
            val unlockFilter = IntentFilter()
            unlockFilter.addAction(Intent.ACTION_USER_UNLOCKED)
            unlockFilter.addAction(Intent.ACTION_USER_SWITCHED)
            broadcastDispatcher.registerReceiver(userChangeReceiver, unlockFilter, null,
                UserHandle.ALL)
            loadSavedComponents()
        }
    }

    private fun loadSavedComponents() {
        // Make sure list is empty (if we switched users)
        resumeComponents.clear()
        val prefs = context.getSharedPreferences(MEDIA_PREFERENCES, Context.MODE_PRIVATE)
        val listString = prefs.getString(MEDIA_PREFERENCE_KEY + currentUserId, null)
        val components = listString?.split(ResumeMediaBrowser.DELIMITER.toRegex())
            ?.dropLastWhile { it.isEmpty() }
        components?.forEach {
            val info = it.split("/")
            val packageName = info[0]
            val className = info[1]
            val component = ComponentName(packageName, className)
            resumeComponents.add(component)
        }
        Log.d(TAG, "loaded resume components ${resumeComponents.toArray().contentToString()}")
    }

    /**
     * Load controls for resuming media, if available
     */
    private fun loadMediaResumptionControls() {
        if (!useMediaResumption) {
            return
        }

        resumeComponents.forEach {
            val browser = ResumeMediaBrowser(context, mediaBrowserCallback, it)
            browser.findRecentMedia()
        }
    }

    override fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {
        if (useMediaResumption) {
            // If this had been started from a resume state, disconnect now that it's live
            mediaBrowser?.disconnect()
            // If we don't have a resume action, check if we haven't already
            if (data.resumeAction == null && !data.hasCheckedForResume) {
                // TODO also check for a media button receiver intended for restarting (b/154127084)
                Log.d(TAG, "Checking for service component for " + data.packageName)
                val pm = context.packageManager
                val serviceIntent = Intent(MediaBrowserService.SERVICE_INTERFACE)
                val resumeInfo = pm.queryIntentServices(serviceIntent, 0)

                val inf = resumeInfo?.filter {
                    it.serviceInfo.packageName == data.packageName
                }
                if (inf != null && inf.size > 0) {
                    backgroundExecutor.execute {
                        tryUpdateResumptionList(key, inf!!.get(0).componentInfo.componentName)
                    }
                } else {
                    // No service found
                    resumeComponentFoundCallback(key, null)
                }
            }
        }
    }

    /**
     * Verify that we can connect to the given component with a MediaBrowser, and if so, add that
     * component to the list of resumption components
     */
    private fun tryUpdateResumptionList(key: String, componentName: ComponentName) {
        Log.d(TAG, "Testing if we can connect to $componentName")
        mediaBrowser?.disconnect()
        mediaBrowser = ResumeMediaBrowser(context,
                object : ResumeMediaBrowser.Callback() {
                    override fun onConnected() {
                        Log.d(TAG, "yes we can resume with $componentName")
                        resumeComponentFoundCallback(key, getResumeAction(componentName))
                        updateResumptionList(componentName)
                        mediaBrowser?.disconnect()
                        mediaBrowser = null
                    }

                    override fun onError() {
                        Log.e(TAG, "Cannot resume with $componentName")
                        resumeComponentFoundCallback(key, null)
                        mediaBrowser?.disconnect()
                        mediaBrowser = null
                    }
                },
                componentName)
        mediaBrowser?.testConnection()
    }

    /**
     * Add the component to the saved list of media browser services, checking for duplicates and
     * removing older components that exceed the maximum limit
     * @param componentName
     */
    private fun updateResumptionList(componentName: ComponentName) {
        // Remove if exists
        resumeComponents.remove(componentName)
        // Insert at front of queue
        resumeComponents.add(componentName)
        // Remove old components if over the limit
        if (resumeComponents.size > ResumeMediaBrowser.MAX_RESUMPTION_CONTROLS) {
            resumeComponents.remove()
        }

        // Save changes
        val sb = StringBuilder()
        resumeComponents.forEach {
            sb.append(it.flattenToString())
            sb.append(ResumeMediaBrowser.DELIMITER)
        }
        val prefs = context.getSharedPreferences(MEDIA_PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().putString(MEDIA_PREFERENCE_KEY + currentUserId, sb.toString()).apply()
    }

    /**
     * Get a runnable which will resume media playback
     */
    private fun getResumeAction(componentName: ComponentName): Runnable {
        return Runnable {
            mediaBrowser?.disconnect()
            mediaBrowser = ResumeMediaBrowser(context,
                object : ResumeMediaBrowser.Callback() {
                    override fun onConnected() {
                        if (mediaBrowser?.token == null) {
                            Log.e(TAG, "Error after connect")
                            mediaBrowser?.disconnect()
                            mediaBrowser = null
                            return
                        }
                        Log.d(TAG, "Connected for restart $componentName")
                        val controller = MediaController(context, mediaBrowser!!.token)
                        val controls = controller.transportControls
                        controls.prepare()
                        controls.play()
                    }

                    override fun onError() {
                        Log.e(TAG, "Resume failed for $componentName")
                        mediaBrowser?.disconnect()
                        mediaBrowser = null
                    }
                },
                componentName)
            mediaBrowser?.restart()
        }
    }
}