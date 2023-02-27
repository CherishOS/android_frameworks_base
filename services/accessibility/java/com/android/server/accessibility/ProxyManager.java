/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.accessibility;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityTrace;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * Manages proxy connections.
 *
 * Currently this acts similarly to UiAutomationManager as a global manager, though ideally each
 * proxy connection will belong to a separate user state.
 *
 * TODO(241117292): Remove or cut down during simultaneous user refactoring.
 * TODO(262244375): Add unit tests.
 */
public class ProxyManager {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "ProxyManager";

    // Names used to populate ComponentName and ResolveInfo in connection.mA11yServiceInfo and in
    // the infos of connection.setInstalledAndEnabledServices
    static final String PROXY_COMPONENT_PACKAGE_NAME = "ProxyPackage";
    static final String PROXY_COMPONENT_CLASS_NAME = "ProxyClass";

    private final Object mLock;

    private final Context mContext;

    // Used to determine if we should notify AccessibilityManager clients of updates.
    // TODO(254545943): Separate this so each display id has its own state. Currently there is no
    // way to identify from AccessibilityManager which proxy state should be returned.
    private int mLastState = -1;

    private SparseArray<ProxyAccessibilityServiceConnection> mProxyA11yServiceConnections =
            new SparseArray<>();

    private AccessibilityWindowManager mA11yWindowManager;

    private AccessibilityInputFilter mA11yInputFilter;

    ProxyManager(Object lock, AccessibilityWindowManager awm, Context context) {
        mLock = lock;
        mA11yWindowManager = awm;
        mContext = context;
    }

    /**
     * Creates the service connection.
     */
    public void registerProxy(IAccessibilityServiceClient client, int displayId,
            Context context,
            int id, Handler mainHandler,
            AccessibilitySecurityPolicy securityPolicy,
            AbstractAccessibilityServiceConnection.SystemSupport systemSupport,
            AccessibilityTrace trace,
            WindowManagerInternal windowManagerInternal) throws RemoteException {
        if (DEBUG) {
            Slog.v(LOG_TAG, "Register proxy for display id: " + displayId);
        }

        // Set a default AccessibilityServiceInfo that is used before the proxy's info is
        // populated. A proxy has the touch exploration and window capabilities.
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.setCapabilities(AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION
                | AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
        final String componentClassDisplayName = PROXY_COMPONENT_CLASS_NAME + displayId;
        info.setComponentName(new ComponentName(PROXY_COMPONENT_PACKAGE_NAME,
                componentClassDisplayName));
        ProxyAccessibilityServiceConnection connection =
                new ProxyAccessibilityServiceConnection(context, info.getComponentName(), info,
                        id, mainHandler, mLock, securityPolicy, systemSupport, trace,
                        windowManagerInternal,
                        mA11yWindowManager, displayId);

        synchronized (mLock) {
            mProxyA11yServiceConnections.put(displayId, connection);
        }

        // If the client dies, make sure to remove the connection.
        IBinder.DeathRecipient deathRecipient =
                new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        client.asBinder().unlinkToDeath(this, 0);
                        clearConnection(displayId);
                    }
                };
        client.asBinder().linkToDeath(deathRecipient, 0);

        // Notify apps that the service state has changed.
        // A11yManager#A11yServicesStateChangeListener
        synchronized (mLock) {
            connection.mSystemSupport.onClientChangeLocked(true);
        }

        if (mA11yInputFilter != null) {
            mA11yInputFilter.disableFeaturesForDisplayIfInstalled(displayId);
        }
        connection.initializeServiceInterface(client);
    }

    /**
     * Unregister the proxy based on display id.
     */
    public boolean unregisterProxy(int displayId) {
        return clearConnection(displayId);
    }

    private boolean clearConnection(int displayId) {
        boolean removed = false;
        synchronized (mLock) {
            if (mProxyA11yServiceConnections.contains(displayId)) {
                mProxyA11yServiceConnections.remove(displayId);
                removed = true;
                if (DEBUG) {
                    Slog.v(LOG_TAG, "Unregister proxy for display id " + displayId);
                }
            }
        }
        if (removed) {
            mA11yWindowManager.stopTrackingDisplayProxy(displayId);
            if (mA11yInputFilter != null) {
                final DisplayManager displayManager = (DisplayManager)
                        mContext.getSystemService(Context.DISPLAY_SERVICE);
                final Display proxyDisplay = displayManager.getDisplay(displayId);
                if (proxyDisplay != null) {
                    mA11yInputFilter.enableFeaturesForDisplayIfInstalled(proxyDisplay);
                }
            }
        }
        return removed;
    }

    /**
     * Checks if a display id is being proxy-ed.
     */
    public boolean isProxyed(int displayId) {
        synchronized (mLock) {
            final boolean tracked = mProxyA11yServiceConnections.contains(displayId);
            if (DEBUG) {
                Slog.v(LOG_TAG, "Tracking proxy display " + displayId + " : " + tracked);
            }
            return tracked;
        }
    }

    /**
     * Sends AccessibilityEvents to a proxy given the event's displayId.
     */
    public void sendAccessibilityEventLocked(AccessibilityEvent event) {
        final ProxyAccessibilityServiceConnection proxy =
                mProxyA11yServiceConnections.get(event.getDisplayId());
        if (proxy != null) {
            if (DEBUG) {
                Slog.v(LOG_TAG, "Send proxy event " + event + " for display id "
                        + event.getDisplayId());
            }
            proxy.notifyAccessibilityEvent(event);
        }
    }

    /**
     * Returns {@code true} if any proxy can retrieve windows.
     * TODO(b/250929565): Retrieve per connection/user state.
     */
    public boolean canRetrieveInteractiveWindowsLocked() {
        boolean observingWindows = false;
        for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
            final ProxyAccessibilityServiceConnection proxy =
                    mProxyA11yServiceConnections.valueAt(i);
            if (proxy.mRetrieveInteractiveWindows) {
                observingWindows = true;
                break;
            }
        }
        if (DEBUG) {
            Slog.v(LOG_TAG, "At least one proxy can retrieve windows: " + observingWindows);
        }
        return observingWindows;
    }

    /**
     * If there is at least one proxy, accessibility is enabled.
     */
    public int getStateLocked() {
        int clientState = 0;
        final boolean a11yEnabled = mProxyA11yServiceConnections.size() > 0;
        if (a11yEnabled) {
            clientState |= AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED;
        }
        for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
            final ProxyAccessibilityServiceConnection proxy =
                    mProxyA11yServiceConnections.valueAt(i);
            if (proxy.mRequestTouchExplorationMode) {
                clientState |= AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED;
            }
        }

        if (DEBUG) {
            Slog.v(LOG_TAG, "Accessibility is enabled for all proxies: "
                    + ((clientState & AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED) != 0));
            Slog.v(LOG_TAG, "Touch exploration is enabled for all proxies: "
                    + ((clientState & AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED)
                            != 0));
        }
        return clientState;
        // TODO(b/254545943): When A11yManager is separated, include support for other properties.
    }

    /**
     * Gets the last state.
     */
    public int getLastSentStateLocked() {
        return mLastState;
    }

    /**
     * Sets the last state.
     */
    public void setLastStateLocked(int proxyState) {
        mLastState = proxyState;
    }

    /**
     * Returns the relevant event types of every proxy.
     * TODO(254545943): When A11yManager is separated, return based on the A11yManager display.
     */
    public int getRelevantEventTypesLocked() {
        int relevantEventTypes = 0;
        for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
            ProxyAccessibilityServiceConnection proxy =
                    mProxyA11yServiceConnections.valueAt(i);
            relevantEventTypes |= proxy.getRelevantEventTypes();
        }
        if (DEBUG) {
            Slog.v(LOG_TAG, "Relevant event types for all proxies: "
                    + AccessibilityEvent.eventTypeToString(relevantEventTypes));
        }
        return relevantEventTypes;
    }

    /**
     * Gets the number of current proxy connections.
     * @return
     */
    public int getNumProxysLocked() {
        return mProxyA11yServiceConnections.size();
    }

    /**
     * Adds the service interfaces to a list.
     * @param interfaces
     */
    public void addServiceInterfacesLocked(List<IAccessibilityServiceClient> interfaces) {
        for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
            final ProxyAccessibilityServiceConnection proxy =
                    mProxyA11yServiceConnections.valueAt(i);
            final IBinder proxyBinder = proxy.mService;
            final IAccessibilityServiceClient proxyInterface = proxy.mServiceInterface;
            if ((proxyBinder != null) && (proxyInterface != null)) {
                interfaces.add(proxyInterface);
            }
        }
    }

    /**
     * Clears all proxy caches.
     */
    public void clearCacheLocked() {
        for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
            final ProxyAccessibilityServiceConnection proxy =
                    mProxyA11yServiceConnections.valueAt(i);
            proxy.notifyClearAccessibilityNodeInfoCache();
        }
    }

    void setAccessibilityInputFilter(AccessibilityInputFilter filter) {
        mA11yInputFilter = filter;
    }


    /**
     * Prints information belonging to each display that is controlled by an
     * AccessibilityDisplayProxy.
     */
    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            pw.println();
            pw.println("Proxy manager state:");
            pw.println("    Number of proxy connections: " + mProxyA11yServiceConnections.size());
            pw.println("    Registered proxy connections:");
            for (int i = 0; i < mProxyA11yServiceConnections.size(); i++) {
                final ProxyAccessibilityServiceConnection proxy =
                        mProxyA11yServiceConnections.valueAt(i);
                if (proxy != null) {
                    proxy.dump(fd, pw, args);
                }
            }
        }
    }
}