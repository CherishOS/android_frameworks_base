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

import static android.media.MediaRoute2Info.PLAYBACK_VOLUME_FIXED;
import static android.media.MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2;
import android.media.MediaRouter2Manager;
import android.os.Bundle;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.TextUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
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
public class MediaRouterManagerTest {
    private static final String TAG = "MediaRouterManagerTest";

    // Must be the same as SampleMediaRoute2ProviderService
    public static final String ROUTE_ID1 = "route_id1";
    public static final String ROUTE_NAME1 = "Sample Route 1";
    public static final String ROUTE_ID2 = "route_id2";
    public static final String ROUTE_NAME2 = "Sample Route 2";

    public static final String ROUTE_ID_SPECIAL_CATEGORY = "route_special_category";
    public static final String ROUTE_NAME_SPECIAL_CATEGORY = "Special Category Route";

    public static final int VOLUME_MAX = 100;
    public static final String ROUTE_ID_FIXED_VOLUME = "route_fixed_volume";
    public static final String ROUTE_NAME_FIXED_VOLUME = "Fixed Volume Route";
    public static final String ROUTE_ID_VARIABLE_VOLUME = "route_variable_volume";
    public static final String ROUTE_NAME_VARIABLE_VOLUME = "Variable Volume Route";

    public static final String ACTION_REMOVE_ROUTE =
            "com.android.mediarouteprovider.action_remove_route";

    public static final String CATEGORY_SAMPLE =
            "com.android.mediarouteprovider.CATEGORY_SAMPLE";
    public static final String CATEGORY_SPECIAL =
            "com.android.mediarouteprovider.CATEGORY_SPECIAL";

    // system routes
    private static final String DEFAULT_ROUTE_ID = "DEFAULT_ROUTE";
    private static final String CATEGORY_LIVE_AUDIO = "android.media.intent.category.LIVE_AUDIO";
    private static final String CATEGORY_LIVE_VIDEO = "android.media.intent.category.LIVE_VIDEO";

    private static final int TIMEOUT_MS = 5000;

    private Context mContext;
    private MediaRouter2Manager mManager;
    private MediaRouter2 mRouter2;
    private Executor mExecutor;
    private String mPackageName;

    private final List<MediaRouter2Manager.Callback> mManagerCallbacks = new ArrayList<>();
    private final List<MediaRouter2.Callback> mRouterCallbacks = new ArrayList<>();
    private Map<String, MediaRoute2Info> mRoutes;

    private static final List<String> CATEGORIES_ALL = new ArrayList();
    private static final List<String> CATEGORIES_SPECIAL = new ArrayList();
    private static final List<String> CATEGORIES_LIVE_AUDIO = new ArrayList<>();

    static {
        CATEGORIES_ALL.add(CATEGORY_SAMPLE);
        CATEGORIES_ALL.add(CATEGORY_SPECIAL);
        CATEGORIES_ALL.add(CATEGORY_LIVE_AUDIO);

        CATEGORIES_SPECIAL.add(CATEGORY_SPECIAL);

        CATEGORIES_LIVE_AUDIO.add(CATEGORY_LIVE_AUDIO);
    }


    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mManager = MediaRouter2Manager.getInstance(mContext);
        mRouter2 = MediaRouter2.getInstance(mContext);
        //TODO: If we need to support thread pool executors, change this to thread pool executor.
        mExecutor = Executors.newSingleThreadExecutor();
        mPackageName = mContext.getPackageName();

        // ensure media router 2 client
        addRouterCallback(new MediaRouter2.Callback());
        mRoutes = waitAndGetRoutesWithManager(CATEGORIES_ALL);
    }

    @After
    public void tearDown() {
        // unregister callbacks
        clearCallbacks();
    }

    //TODO: Move to a separate file
    @Test
    public void testMediaRoute2Info() {
        MediaRoute2Info routeInfo1 = new MediaRoute2Info.Builder("id", "name")
                .build();
        MediaRoute2Info routeInfo2 = new MediaRoute2Info.Builder(routeInfo1).build();

        MediaRoute2Info routeInfo3 = new MediaRoute2Info.Builder(routeInfo1)
                .setClientPackageName(mPackageName).build();

        assertEquals(routeInfo1, routeInfo2);
        assertNotEquals(routeInfo1, routeInfo3);
    }

    /**
     * Tests if routes are added correctly when a new callback is registered.
     */
    @Test
    public void testOnRoutesAdded() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onRoutesAdded(List<MediaRoute2Info> routes) {
                assertTrue(routes.size() > 0);
                for (MediaRoute2Info route : routes) {
                    if (route.getId().equals(ROUTE_ID1) && route.getName().equals(ROUTE_NAME1)) {
                        latch.countDown();
                    }
                }
            }
        });

        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testOnRoutesRemoved() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onRoutesRemoved(List<MediaRoute2Info> routes) {
                assertTrue(routes.size() > 0);
                for (MediaRoute2Info route : routes) {
                    if (route.getId().equals(ROUTE_ID2) && route.getName().equals(ROUTE_NAME2)) {
                        latch.countDown();
                    }
                }
            }
        });

        //TODO: Figure out a more proper way to test.
        // (Control requests shouldn't be used in this way.)
        mRouter2.sendControlRequest(mRoutes.get(ROUTE_ID2), new Intent(ACTION_REMOVE_ROUTE));
        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    /**
     * Tests if we get proper routes for application that has special control category.
     */
    @Test
    public void testControlCategory() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(CATEGORIES_SPECIAL);

        assertEquals(1, routes.size());
        assertNotNull(routes.get(ROUTE_ID_SPECIAL_CATEGORY));
    }

    /**
     * Tests if we get proper routes for application that has special control category.
     */
    @Test
    public void testGetRoutes() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(CATEGORIES_SPECIAL);

        assertEquals(1, routes.size());
        assertNotNull(routes.get(ROUTE_ID_SPECIAL_CATEGORY));
    }

    /**
     * Tests if MR2.Callback.onRouteSelected is called when a route is selected from MR2Manager.
     */
    @Test
    public void testRouterOnRouteSelected() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        addRouterCallback(new MediaRouter2.Callback() {
            @Override
            public void onRouteSelected(MediaRoute2Info route, int reason, Bundle controlHints) {
                if (route != null && TextUtils.equals(route.getId(), ROUTE_ID1)) {
                    latch.countDown();
                }
            }
        });

        MediaRoute2Info routeToSelect = mRoutes.get(ROUTE_ID1);
        assertNotNull(routeToSelect);

        mManager.selectRoute(mPackageName, routeToSelect);

        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    /**
     * Tests if MR2Manager.Callback.onRouteSelected is called
     * when a route is selected by MR2Manager.
     */
    @Test
    public void testManagerOnRouteSelected() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onRouteSelected(String packageName, MediaRoute2Info route) {
                if (TextUtils.equals(mPackageName, packageName)
                        && route != null && TextUtils.equals(route.getId(), ROUTE_ID1)) {
                    latch.countDown();
                }
            }
        });

        MediaRoute2Info routeToSelect = mRoutes.get(ROUTE_ID1);
        assertNotNull(routeToSelect);

        mManager.selectRoute(mPackageName, routeToSelect);

        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    /**
     * Tests selecting and unselecting routes of a single provider.
     */
    @Test
    public void testSingleProviderSelect() throws Exception {
        awaitOnRouteChangedManager(
                () -> mManager.selectRoute(mPackageName, mRoutes.get(ROUTE_ID1)),
                ROUTE_ID1,
                route -> TextUtils.equals(route.getClientPackageName(), mPackageName));

        awaitOnRouteChangedManager(
                () -> mManager.selectRoute(mPackageName, mRoutes.get(ROUTE_ID2)),
                ROUTE_ID2,
                route -> TextUtils.equals(route.getClientPackageName(), mPackageName));

        awaitOnRouteChangedManager(
                () -> mManager.unselectRoute(mPackageName),
                ROUTE_ID2,
                route -> TextUtils.equals(route.getClientPackageName(), null));
    }

    @Test
    public void testControlVolumeWithRouter() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(CATEGORIES_ALL);

        MediaRoute2Info volRoute = routes.get(ROUTE_ID_VARIABLE_VOLUME);
        int originalVolume = volRoute.getVolume();
        int deltaVolume = (originalVolume == volRoute.getVolumeMax() ? -1 : 1);

        awaitOnRouteChanged(
                () -> mRouter2.requestUpdateVolume(volRoute, deltaVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume + deltaVolume));

        awaitOnRouteChanged(
                () -> mRouter2.requestSetVolume(volRoute, originalVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume));
    }

    @Test
    public void testControlVolumeWithManager() throws Exception {
        MediaRoute2Info volRoute = mRoutes.get(ROUTE_ID_VARIABLE_VOLUME);
        int originalVolume = volRoute.getVolume();
        int deltaVolume = (originalVolume == volRoute.getVolumeMax() ? -1 : 1);

        awaitOnRouteChangedManager(
                () -> mManager.requestUpdateVolume(volRoute, deltaVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume + deltaVolume));

        awaitOnRouteChangedManager(
                () -> mManager.requestSetVolume(volRoute, originalVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume));
    }

    @Test
    public void testVolumeHandling() throws Exception {
        MediaRoute2Info fixedVolumeRoute = mRoutes.get(ROUTE_ID_FIXED_VOLUME);
        MediaRoute2Info variableVolumeRoute = mRoutes.get(ROUTE_ID_VARIABLE_VOLUME);

        assertEquals(PLAYBACK_VOLUME_FIXED, fixedVolumeRoute.getVolumeHandling());
        assertEquals(PLAYBACK_VOLUME_VARIABLE, variableVolumeRoute.getVolumeHandling());
        assertEquals(VOLUME_MAX, variableVolumeRoute.getVolumeMax());
    }

    @Test
    public void testDefaultRoute() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(CATEGORIES_LIVE_AUDIO);

        assertNotNull(routes.get(DEFAULT_ROUTE_ID));
    }

    Map<String, MediaRoute2Info> waitAndGetRoutes(List<String> controlCategories) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        MediaRouter2.Callback callback = new MediaRouter2.Callback() {
            @Override
            public void onRoutesAdded(List<MediaRoute2Info> added) {
                if (added.size() > 0) latch.countDown();
            }
        };
        mRouter2.setControlCategories(controlCategories);
        mRouter2.registerCallback(mExecutor, callback);
        try {
            assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            return createRouteMap(mRouter2.getRoutes());
        } finally {
            mRouter2.unregisterCallback(callback);
        }
    }

    Map<String, MediaRoute2Info> waitAndGetRoutesWithManager(List<String> controlCategories)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(2);

        // A dummy callback is required to send control category info.
        MediaRouter2.Callback routerCallback = new MediaRouter2.Callback();
        MediaRouter2Manager.Callback managerCallback = new MediaRouter2Manager.Callback() {
            @Override
            public void onRoutesAdded(List<MediaRoute2Info> routes) {
                if (routes.size() > 0) {
                    latch.countDown();
                }
            }

            @Override
            public void onControlCategoriesChanged(String packageName) {
                if (TextUtils.equals(mPackageName, packageName)) {
                    latch.countDown();
                }
            }
        };
        mManager.registerCallback(mExecutor, managerCallback);
        mRouter2.setControlCategories(controlCategories);
        mRouter2.registerCallback(mExecutor, routerCallback);
        try {
            assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            return createRouteMap(mManager.getAvailableRoutes(mPackageName));
        } finally {
            mRouter2.unregisterCallback(routerCallback);
            mManager.unregisterCallback(managerCallback);
        }
    }

    void awaitOnRouteChanged(Runnable task, String routeId,
            Predicate<MediaRoute2Info> predicate) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        MediaRouter2.Callback callback = new MediaRouter2.Callback() {
            @Override
            public void onRoutesChanged(List<MediaRoute2Info> changed) {
                MediaRoute2Info route = createRouteMap(changed).get(routeId);
                if (route != null && predicate.test(route)) {
                    latch.countDown();
                }
            }
        };
        mRouter2.registerCallback(mExecutor, callback);
        try {
            task.run();
            assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            mRouter2.unregisterCallback(callback);
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
            // intentionally not using route.getUniqueId() for convenience.
            routeMap.put(route.getId(), route);
        }
        return routeMap;
    }

    private void addManagerCallback(MediaRouter2Manager.Callback callback) {
        mManagerCallbacks.add(callback);
        mManager.registerCallback(mExecutor, callback);
    }

    private void addRouterCallback(MediaRouter2.Callback callback) {
        mRouterCallbacks.add(callback);
        mRouter2.registerCallback(mExecutor, callback);
    }

    private void clearCallbacks() {
        for (MediaRouter2Manager.Callback callback : mManagerCallbacks) {
            mManager.unregisterCallback(callback);
        }
        mManagerCallbacks.clear();

        for (MediaRouter2.Callback callback : mRouterCallbacks) {
            mRouter2.unregisterCallback(callback);
        }
        mRouterCallbacks.clear();
    }
}
