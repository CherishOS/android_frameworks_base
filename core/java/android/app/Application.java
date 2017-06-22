/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.app;

import android.annotation.CallSuper;
import android.app.backup.BackupAgent;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.ComponentCallbacks2;
import android.content.ContentProvider;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

import java.util.ArrayList;

/**
 * Base class for maintaining global application state. You can provide your own
 * implementation by creating a subclass and specifying the fully-qualified name
 * of this subclass as the <code>"android:name"</code> attribute in your
 * AndroidManifest.xml's <code>&lt;application&gt;</code> tag. The Application
 * class, or your subclass of the Application class, is instantiated before any
 * other class when the process for your application/package is created.
 *
 * <p class="note"><strong>Note: </strong>There is normally no need to subclass
 * Application.  In most situations, static singletons can provide the same
 * functionality in a more modular way.  If your singleton needs a global
 * context (for example to register broadcast receivers), include
 * {@link android.content.Context#getApplicationContext() Context.getApplicationContext()}
 * as a {@link android.content.Context} argument when invoking your singleton's
 * <code>getInstance()</code> method.
 * </p>
 */
public class Application extends ContextWrapper implements ComponentCallbacks2 {
    private ArrayList<ComponentCallbacks> mComponentCallbacks =
            new ArrayList<ComponentCallbacks>();
    private ArrayList<ActivityLifecycleCallbacks> mActivityLifecycleCallbacks =
            new ArrayList<ActivityLifecycleCallbacks>();
    private ArrayList<OnProvideAssistDataListener> mAssistCallbacks = null;

    /** @hide */
    public LoadedApk mLoadedApk;

    public interface ActivityLifecycleCallbacks {
        void onActivityCreated(Activity activity, Bundle savedInstanceState);
        void onActivityStarted(Activity activity);
        void onActivityResumed(Activity activity);
        void onActivityPaused(Activity activity);
        void onActivityStopped(Activity activity);
        void onActivitySaveInstanceState(Activity activity, Bundle outState);
        void onActivityDestroyed(Activity activity);
    }

    /**
     * Callback interface for use with {@link Application#registerOnProvideAssistDataListener}
     * and {@link Application#unregisterOnProvideAssistDataListener}.
     */
    public interface OnProvideAssistDataListener {
        /**
         * This is called when the user is requesting an assist, to build a full
         * {@link Intent#ACTION_ASSIST} Intent with all of the context of the current
         * application.  You can override this method to place into the bundle anything
         * you would like to appear in the {@link Intent#EXTRA_ASSIST_CONTEXT} part
         * of the assist Intent.
         */
        public void onProvideAssistData(Activity activity, Bundle data);
    }

    public Application() {
        super(null);
    }

    /**
     * Called when the application is starting, before any activity, service,
     * or receiver objects (excluding content providers) have been created.
     * Implementations should be as quick as possible (for example using
     * lazy initialization of state) since the time spent in this function
     * directly impacts the performance of starting the first activity,
     * service, or receiver in a process.
     * If you override this method, be sure to call super.onCreate().
     */
    @CallSuper
    public void onCreate() {
    }

    /**
     * This method is for use in emulated process environments.  It will
     * never be called on a production Android device, where processes are
     * removed by simply killing them; no user code (including this callback)
     * is executed when doing so.
     */
    @CallSuper
    public void onTerminate() {
    }

    @CallSuper
    public void onConfigurationChanged(Configuration newConfig) {
        Object[] callbacks = collectComponentCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ComponentCallbacks)callbacks[i]).onConfigurationChanged(newConfig);
            }
        }
    }

    @CallSuper
    public void onLowMemory() {
        Object[] callbacks = collectComponentCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ComponentCallbacks)callbacks[i]).onLowMemory();
            }
        }
    }

    @CallSuper
    public void onTrimMemory(int level) {
        Object[] callbacks = collectComponentCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                Object c = callbacks[i];
                if (c instanceof ComponentCallbacks2) {
                    ((ComponentCallbacks2)c).onTrimMemory(level);
                }
            }
        }
    }

    public void registerComponentCallbacks(ComponentCallbacks callback) {
        synchronized (mComponentCallbacks) {
            mComponentCallbacks.add(callback);
        }
    }

    public void unregisterComponentCallbacks(ComponentCallbacks callback) {
        synchronized (mComponentCallbacks) {
            mComponentCallbacks.remove(callback);
        }
    }

    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        synchronized (mActivityLifecycleCallbacks) {
            mActivityLifecycleCallbacks.add(callback);
        }
    }

    public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        synchronized (mActivityLifecycleCallbacks) {
            mActivityLifecycleCallbacks.remove(callback);
        }
    }

    public void registerOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
        synchronized (this) {
            if (mAssistCallbacks == null) {
                mAssistCallbacks = new ArrayList<OnProvideAssistDataListener>();
            }
            mAssistCallbacks.add(callback);
        }
    }

    public void unregisterOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
        synchronized (this) {
            if (mAssistCallbacks != null) {
                mAssistCallbacks.remove(callback);
            }
        }
    }

    // ------------------ Internal API ------------------

    /**
     * @hide
     */
    /* package */ final void attach(Context context) {
        attachBaseContext(context);
        mLoadedApk = ContextImpl.getImpl(context).mPackageInfo;
    }

    /* package */ void dispatchActivityCreated(Activity activity, Bundle savedInstanceState) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivityCreated(activity,
                        savedInstanceState);
            }
        }
    }

    /* package */ void dispatchActivityStarted(Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivityStarted(activity);
            }
        }
    }

    /* package */ void dispatchActivityResumed(Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivityResumed(activity);
            }
        }
    }

    /* package */ void dispatchActivityPaused(Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivityPaused(activity);
            }
        }
    }

    /* package */ void dispatchActivityStopped(Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivityStopped(activity);
            }
        }
    }

    /* package */ void dispatchActivitySaveInstanceState(Activity activity, Bundle outState) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivitySaveInstanceState(activity,
                        outState);
            }
        }
    }

    /* package */ void dispatchActivityDestroyed(Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivityDestroyed(activity);
            }
        }
    }

    private Object[] collectComponentCallbacks() {
        Object[] callbacks = null;
        synchronized (mComponentCallbacks) {
            if (mComponentCallbacks.size() > 0) {
                callbacks = mComponentCallbacks.toArray();
            }
        }
        return callbacks;
    }

    private Object[] collectActivityLifecycleCallbacks() {
        Object[] callbacks = null;
        synchronized (mActivityLifecycleCallbacks) {
            if (mActivityLifecycleCallbacks.size() > 0) {
                callbacks = mActivityLifecycleCallbacks.toArray();
            }
        }
        return callbacks;
    }

    /* package */ void dispatchOnProvideAssistData(Activity activity, Bundle data) {
        Object[] callbacks;
        synchronized (this) {
            if (mAssistCallbacks == null) {
                return;
            }
            callbacks = mAssistCallbacks.toArray();
        }
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((OnProvideAssistDataListener)callbacks[i]).onProvideAssistData(activity, data);
            }
        }
    }

    /**
     * Allows application to override the creation of activities. This can be used to
     * perform things such as dependency injection or class loader changes to these
     * classes. Return null to use the default creation flow.
     * @param cl The default classloader to use for instantiation.
     * @param className The class to be instantiated.
     * @param intent Intent creating the class.
     */
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent) {
        return null;
    }

    /**
     * Allows application to override the creation of receivers. This can be used to
     * perform things such as dependency injection or class loader changes to these
     * classes. Return null to use the default creation flow.
     * @param cl The default classloader to use for instantiation.
     * @param className The class to be instantiated.
     * @param intent Intent creating the class.
     */
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent) {
        return null;
    }

    /**
     * Allows application to override the creation of services. This can be used to
     * perform things such as dependency injection or class loader changes to these
     * classes. Return null to use the default creation flow.
     * @param cl The default classloader to use for instantiation.
     * @param className The class to be instantiated.
     * @param intent Intent creating the class.
     */
    public Service instantiateService(ClassLoader cl, String className, Intent intent) {
        return null;
    }

    /**
     * Allows application to override the creation of providers. This can be used to
     * perform things such as dependency injection or class loader changes to these
     * classes. Return null to use the default creation flow.
     * @param cl The default classloader to use for instantiation.
     * @param className The class to be instantiated.
     */
    public ContentProvider instantiateProvider(ClassLoader cl, String className) {
        return null;
    }

    /**
     * Allows application to override the creation of backup agents. This can be used to
     * perform things such as dependency injection or class loader changes to these
     * classes. Return null to use the default creation flow.
     * @param cl The default classloader to use for instantiation.
     * @param className The class to be instantiated.
     */
    public BackupAgent instantiateBackupAgent(ClassLoader cl, String className) {
        return null;
    }

    /**
     * Allows application to override the creation of instrumentation. This can be used to
     * perform things such as dependency injection or class loader changes to these
     * classes. Return null to use the default creation flow.
     * @param cl The default classloader to use for instantiation.
     * @param className The class to be instantiated.
     */
    public Instrumentation instantiateInstrumentation(ClassLoader cl, String className) {
        return null;
    }
}