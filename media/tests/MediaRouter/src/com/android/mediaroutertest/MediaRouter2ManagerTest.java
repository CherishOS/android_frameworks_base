/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.mediaroutertest;

import static android.media.MediaRoute2Info.FEATURE_LIVE_AUDIO;
import static android.media.MediaRoute2Info.PLAYBACK_VOLUME_FIXED;
import static android.media.MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE;

import static com.android.mediaroutertest.SampleMediaRoute2ProviderService.FEATURE_SAMPLE;
import static com.android.mediaroutertest.SampleMediaRoute2ProviderService.FEATURE_SPECIAL;
import static com.android.mediaroutertest.SampleMediaRoute2ProviderService.ROUTE_ID1;
import static com.android.mediaroutertest.SampleMediaRoute2ProviderService.ROUTE_ID2;
import static com.android.mediaroutertest.SampleMediaRoute2ProviderService.ROUTE_ID5_TO_TRANSFER_TO;
import static com.android.mediaroutertest.SampleMediaRoute2ProviderService.ROUTE_ID_FIXED_VOLUME;
import static com.android.mediaroutertest.SampleMediaRoute2ProviderService.ROUTE_ID_SPECIAL_FEATURE;
import static com.android.mediaroutertest.SampleMediaRoute2ProviderService.ROUTE_ID_VARIABLE_VOLUME;
import static com.android.mediaroutertest.SampleMediaRoute2ProviderService.ROUTE_NAME2;
import static com.android.mediaroutertest.SampleMediaRoute2ProviderService.VOLUME_MAX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2;
import android.media.MediaRouter2.RouteCallback;
import android.media.MediaRouter2.RoutingControllerCallback;
import android.media.MediaRouter2Manager;
import android.media.MediaRouter2Utils;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.TextUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MediaRouter2ManagerTest {
    private static final String TAG = "MediaRouter2ManagerTest";
    private static final int TIMEOUT_MS = 5000;

    private Context mContext;
    private MediaRouter2Manager mManager;
    private MediaRouter2 mRouter2;
    private Executor mExecutor;
    private String mPackageName;

    private final List<MediaRouter2Manager.Callback> mManagerCallbacks = new ArrayList<>();
    private final List<RouteCallback> mRouteCallbacks = new ArrayList<>();
    private final List<RoutingControllerCallback> mControllerCallbacks = new ArrayList<>();

    public static final List<String> FEATURES_ALL = new ArrayList();
    public static final List<String> FEATURES_SPECIAL = new ArrayList();
    private static final List<String> FEATURES_LIVE_AUDIO = new ArrayList<>();

    static {
        FEATURES_ALL.add(FEATURE_SAMPLE);
        FEATURES_ALL.add(FEATURE_SPECIAL);
        FEATURES_ALL.add(FEATURE_LIVE_AUDIO);

        FEATURES_SPECIAL.add(FEATURE_SPECIAL);

        FEATURES_LIVE_AUDIO.add(FEATURE_LIVE_AUDIO);
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mManager = MediaRouter2Manager.getInstance(mContext);
        mRouter2 = MediaRouter2.getInstance(mContext);
        //TODO: If we need to support thread pool executors, change this to thread pool executor.
        mExecutor = Executors.newSingleThreadExecutor();
        mPackageName = mContext.getPackageName();
    }

    @After
    public void tearDown() {
        // order matters (callbacks should be cleared at the last)
        releaseAllSessions();
        // unregister callbacks
        clearCallbacks();
    }

    @Test
    public void testOnRoutesRemovedAndAdded() throws Exception {
        RouteCallback routeCallback = new RouteCallback();
        mRouteCallbacks.add(routeCallback);
        mRouter2.registerRouteCallback(mExecutor, routeCallback,
                new RouteDiscoveryPreference.Builder(FEATURES_ALL, true).build());

        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);

        CountDownLatch removedLatch = new CountDownLatch(1);
        CountDownLatch addedLatch = new CountDownLatch(1);

        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onRoutesRemoved(List<MediaRoute2Info> routes) {
                assertTrue(routes.size() > 0);
                for (MediaRoute2Info route : routes) {
                    if (route.getOriginalId().equals(ROUTE_ID2)
                            && route.getName().equals(ROUTE_NAME2)) {
                        removedLatch.countDown();
                    }
                }
            }
            @Override
            public void onRoutesAdded(List<MediaRoute2Info> routes) {
                assertTrue(routes.size() > 0);
                if (removedLatch.getCount() > 0) {
                    return;
                }
                for (MediaRoute2Info route : routes) {
                    if (route.getOriginalId().equals(ROUTE_ID2)
                            && route.getName().equals(ROUTE_NAME2)) {
                        addedLatch.countDown();
                    }
                }
            }
        });

        MediaRoute2Info routeToRemove = routes.get(ROUTE_ID2);

        try {
            SampleMediaRoute2ProviderService sInstance =
                    SampleMediaRoute2ProviderService.getInstance();
            assertNotNull(sInstance);
            sInstance.removeRoute(ROUTE_ID2);
            assertTrue(removedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            sInstance.addRoute(routeToRemove);
            assertTrue(addedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            mRouter2.unregisterRouteCallback(routeCallback);
        }
    }

    /**
     * Tests if we get proper routes for application that has special route feature.
     */
    @Test
    public void testRouteFeatures() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_SPECIAL);

        int routeCount = 0;
        for (MediaRoute2Info route : routes.values()) {
            if (!route.isSystemRoute()) {
                routeCount++;
            }
        }

        assertEquals(1, routeCount);
        assertNotNull(routes.get(ROUTE_ID_SPECIAL_FEATURE));
    }

    /**
     * Tests if MR2.SessionCallback.onSessionCreated is called
     * when a route is selected from MR2Manager.
     */
    @Test
    public void testRouterOnSessionCreated() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);

        CountDownLatch latch = new CountDownLatch(1);

        addManagerCallback(new MediaRouter2Manager.Callback());
        //TODO: remove this when it's not necessary.
        addRouterCallback(new MediaRouter2.RouteCallback());
        addSessionCallback(new RoutingControllerCallback() {
            @Override
            public void onControllerCreated(MediaRouter2.RoutingController controller) {
                if (createRouteMap(controller.getSelectedRoutes()).containsKey(ROUTE_ID1)) {
                    latch.countDown();
                }
            }
        });

        MediaRoute2Info routeToSelect = routes.get(ROUTE_ID1);
        assertNotNull(routeToSelect);

        mManager.selectRoute(mPackageName, routeToSelect);
        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(2, mManager.getActiveSessions().size());
    }

    @Test
    public void testGetRoutingControllers() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        addRouterCallback(new RouteCallback());
        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onSessionCreated(MediaRouter2Manager.RoutingController controller) {
                if (TextUtils.equals(mPackageName, controller.getClientPackageName())
                        && createRouteMap(controller.getSelectedRoutes()).containsKey(ROUTE_ID1)) {
                    latch.countDown();
                }
            }
        });

        assertEquals(1, mManager.getRoutingSessions(mPackageName).size());

        mManager.selectRoute(mPackageName, routes.get(ROUTE_ID1));
        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        List<RoutingSessionInfo> sessions = mManager.getRoutingSessions(mPackageName);

        assertEquals(2, sessions.size());

        MediaRouter2Manager.RoutingController routingController =
                mManager.getControllerForSession(sessions.get(1));
        awaitOnRouteChangedManager(
                () -> routingController.release(),
                ROUTE_ID1,
                route -> TextUtils.equals(route.getClientPackageName(), null));
        assertEquals(1, mManager.getRoutingSessions(mPackageName).size());
    }

    /**
     * Tests select, transfer, release of routes of a provider
     */
    @Test
    public void testSelectAndTransferAndRelease() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        addRouterCallback(new RouteCallback());

        CountDownLatch onSessionCreatedLatch = new CountDownLatch(1);

        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onSessionCreated(MediaRouter2Manager.RoutingController controller) {
                assertNotNull(controller);
                onSessionCreatedLatch.countDown();
            }
        });
        awaitOnRouteChangedManager(
                () -> mManager.selectRoute(mPackageName, routes.get(ROUTE_ID1)),
                ROUTE_ID1,
                route -> TextUtils.equals(route.getClientPackageName(), mPackageName));
        assertTrue(onSessionCreatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        List<RoutingSessionInfo> sessions = mManager.getRoutingSessions(mPackageName);

        assertEquals(2, sessions.size());
        MediaRouter2Manager.RoutingController routingController =
                mManager.getControllerForSession(sessions.get(1));

        awaitOnRouteChangedManager(
                () -> mManager.selectRoute(mPackageName, routes.get(ROUTE_ID5_TO_TRANSFER_TO)),
                ROUTE_ID5_TO_TRANSFER_TO,
                route -> TextUtils.equals(route.getClientPackageName(), mPackageName));

        awaitOnRouteChangedManager(
                () -> routingController.release(),
                ROUTE_ID5_TO_TRANSFER_TO,
                route -> TextUtils.equals(route.getClientPackageName(), null));
    }

    @Test
    public void testSetSystemRouteVolume() throws Exception {
        // ensure client
        addManagerCallback(new MediaRouter2Manager.Callback());
        String selectedSystemRouteId =
                MediaRouter2Utils.getOriginalId(
                mManager.getActiveSessions().get(0).getSelectedRoutes().get(0));
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(Collections.emptyList());
        MediaRoute2Info volRoute = routes.get(selectedSystemRouteId);
        assertNotNull(volRoute);

        int originalVolume = volRoute.getVolume();
        int targetVolume = originalVolume == volRoute.getVolumeMax()
                ? originalVolume - 1 : originalVolume + 1;

        awaitOnRouteChangedManager(
                () -> mManager.setRouteVolume(volRoute, targetVolume),
                selectedSystemRouteId,
                (route -> route.getVolume() == targetVolume));

        awaitOnRouteChangedManager(
                () -> mManager.setRouteVolume(volRoute, originalVolume),
                selectedSystemRouteId,
                (route -> route.getVolume() == originalVolume));
    }

    @Test
    public void testSetRouteVolume() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        MediaRoute2Info volRoute = routes.get(ROUTE_ID_VARIABLE_VOLUME);

        int originalVolume = volRoute.getVolume();
        int targetVolume = originalVolume == volRoute.getVolumeMax()
                ? originalVolume - 1 : originalVolume + 1;

        awaitOnRouteChangedManager(
                () -> mManager.setRouteVolume(volRoute, targetVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == targetVolume));

        awaitOnRouteChangedManager(
                () -> mManager.setRouteVolume(volRoute, originalVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume));
    }

    @Test
    public void testSetSessionVolume() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        addRouterCallback(new RouteCallback());

        CountDownLatch onSessionCreatedLatch = new CountDownLatch(1);
        CountDownLatch volumeChangedLatch = new CountDownLatch(2);

        // create a controller
        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onSessionCreated(MediaRouter2Manager.RoutingController controller) {
                assertNotNull(controller);
                onSessionCreatedLatch.countDown();
            }
        });

        mManager.selectRoute(mPackageName, routes.get(ROUTE_ID1));
        assertTrue(onSessionCreatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        List<RoutingSessionInfo> sessions = mManager.getRoutingSessions(mPackageName);
        assertEquals(2, sessions.size());

        // test setSessionVolume
        RoutingSessionInfo sessionInfo = sessions.get(1);
        int currentVolume = sessionInfo.getVolume();
        int targetVolume = (currentVolume == 0) ? 1 : (currentVolume - 1);

        RoutingControllerCallback routingControllerCallback = new RoutingControllerCallback() {
            @Override
            public void onControllerUpdated(MediaRouter2.RoutingController controller) {
                if (!TextUtils.equals(sessionInfo.getId(), controller.getId())) {
                    return;
                }
                if (controller.getVolume() == targetVolume) {
                    volumeChangedLatch.countDown();
                }
            }
        };
        mRouter2.registerControllerCallback(mExecutor, routingControllerCallback);

        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onSessionsUpdated() {
                List<RoutingSessionInfo> sessions = mManager.getRoutingSessions(mPackageName);
                if (sessions.size() != 2) {
                    return;
                }

                if (sessions.get(1).getVolume() == targetVolume) {
                    volumeChangedLatch.countDown();
                }
            }
        });

        mManager.setSessionVolume(sessionInfo, targetVolume);

        try {
            assertTrue(volumeChangedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            mRouter2.unregisterControllerCallback(routingControllerCallback);
        }
    }

    @Test
    public void testVolumeHandling() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);

        MediaRoute2Info fixedVolumeRoute = routes.get(ROUTE_ID_FIXED_VOLUME);
        MediaRoute2Info variableVolumeRoute = routes.get(ROUTE_ID_VARIABLE_VOLUME);

        assertEquals(PLAYBACK_VOLUME_FIXED, fixedVolumeRoute.getVolumeHandling());
        assertEquals(PLAYBACK_VOLUME_VARIABLE, variableVolumeRoute.getVolumeHandling());
        assertEquals(VOLUME_MAX, variableVolumeRoute.getVolumeMax());
    }

    Map<String, MediaRoute2Info> waitAndGetRoutesWithManager(List<String> routeFeatures)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(2);

        // A dummy callback is required to send route feature info.
        RouteCallback routeCallback = new RouteCallback();
        MediaRouter2Manager.Callback managerCallback = new MediaRouter2Manager.Callback() {
            @Override
            public void onRoutesAdded(List<MediaRoute2Info> routes) {
                for (int i = 0; i < routes.size(); i++) {
                    if (!routes.get(i).isSystemRoute()) {
                        latch.countDown();
                        break;
                    }
                }
            }

            @Override
            public void onControlCategoriesChanged(String packageName,
                    List<String> preferredFeatures) {
                if (TextUtils.equals(mPackageName, packageName)
                        && preferredFeatures.equals(preferredFeatures)) {
                    latch.countDown();
                }
            }
        };
        mManager.registerCallback(mExecutor, managerCallback);
        mRouter2.registerRouteCallback(mExecutor, routeCallback,
                new RouteDiscoveryPreference.Builder(routeFeatures, true).build());
        try {
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return createRouteMap(mManager.getAvailableRoutes(mPackageName));
        } finally {
            mRouter2.unregisterRouteCallback(routeCallback);
            mManager.unregisterCallback(managerCallback);
        }
    }

    void awaitOnRouteChangedManager(Runnable task, String routeId,
            Predicate<MediaRoute2Info> predicate) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        MediaRouter2Manager.Callback callback = new MediaRouter2Manager.Callback() {
            @Override
            public void onRoutesChanged(List<MediaRoute2Info> changed) {
                MediaRoute2Info route = createRouteMap(changed).get(routeId);
                if (route != null && predicate.test(route)) {
                    latch.countDown();
                }
            }
        };
        mManager.registerCallback(mExecutor, callback);
        try {
            task.run();
            assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            mManager.unregisterCallback(callback);
        }
    }

    // Helper for getting routes easily
    static Map<String, MediaRoute2Info> createRouteMap(List<MediaRoute2Info> routes) {
        Map<String, MediaRoute2Info> routeMap = new HashMap<>();
        for (MediaRoute2Info route : routes) {
            routeMap.put(route.getOriginalId(), route);
        }
        return routeMap;
    }

    private void addManagerCallback(MediaRouter2Manager.Callback callback) {
        mManagerCallbacks.add(callback);
        mManager.registerCallback(mExecutor, callback);
    }

    private void addRouterCallback(RouteCallback routeCallback) {
        mRouteCallbacks.add(routeCallback);
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryPreference.EMPTY);
    }

    private void addSessionCallback(RoutingControllerCallback controllerCallback) {
        mControllerCallbacks.add(controllerCallback);
        mRouter2.registerControllerCallback(mExecutor, controllerCallback);
    }

    private void clearCallbacks() {
        for (MediaRouter2Manager.Callback callback : mManagerCallbacks) {
            mManager.unregisterCallback(callback);
        }
        mManagerCallbacks.clear();

        for (RouteCallback routeCallback : mRouteCallbacks) {
            mRouter2.unregisterRouteCallback(routeCallback);
        }
        mRouteCallbacks.clear();

        for (RoutingControllerCallback controllerCallback : mControllerCallbacks) {
            mRouter2.unregisterControllerCallback(controllerCallback);
        }
        mControllerCallbacks.clear();
    }

    private void releaseAllSessions() {
        // ensure ManagerRecord in MediaRouter2ServiceImpl
        addManagerCallback(new MediaRouter2Manager.Callback());

        for (RoutingSessionInfo session : mManager.getActiveSessions()) {
            mManager.getControllerForSession(session).release();
        }
    }
}
