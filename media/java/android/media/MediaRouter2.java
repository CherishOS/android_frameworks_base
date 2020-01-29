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

package android.media;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Media Router 2 allows applications to control the routing of media channels
 * and streams from the current device to remote speakers and devices.
 */
// TODO: Add method names at the beginning of log messages. (e.g. updateControllerOnHandler)
//       Not only MediaRouter2, but also to service / manager / provider.
// TODO: ensure thread-safe and document it
public class MediaRouter2 {
    private static final String TAG = "MR2";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final Object sRouterLock = new Object();

    @GuardedBy("sRouterLock")
    private static MediaRouter2 sInstance;

    private final Context mContext;
    private final IMediaRouterService mMediaRouterService;

    private final CopyOnWriteArrayList<RouteCallbackRecord> mRouteCallbackRecords =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<ControllerCallbackRecord> mControllerCallbackRecords =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<ControllerCreationRequest> mControllerCreationRequests =
            new CopyOnWriteArrayList<>();

    private final String mPackageName;
    @GuardedBy("sRouterLock")
    final Map<String, MediaRoute2Info> mRoutes = new HashMap<>();

    final RoutingController mSystemController;

    @GuardedBy("sRouterLock")
    private RouteDiscoveryPreference mDiscoveryPreference = RouteDiscoveryPreference.EMPTY;

    // TODO: Make MediaRouter2 is always connected to the MediaRouterService.
    @GuardedBy("sRouterLock")
    Client2 mClient;

    @GuardedBy("sRouterLock")
    private Map<String, RoutingController> mRoutingControllers = new ArrayMap<>();

    private AtomicInteger mControllerCreationRequestCnt = new AtomicInteger(1);

    final Handler mHandler;
    @GuardedBy("sRouterLock")
    private boolean mShouldUpdateRoutes;
    private volatile List<MediaRoute2Info> mFilteredRoutes = Collections.emptyList();
    private volatile OnGetControllerHintsListener mOnGetControllerHintsListener;

    /**
     * Gets an instance of the media router associated with the context.
     */
    @NonNull
    public static MediaRouter2 getInstance(@NonNull Context context) {
        Objects.requireNonNull(context, "context must not be null");
        synchronized (sRouterLock) {
            if (sInstance == null) {
                sInstance = new MediaRouter2(context.getApplicationContext());
            }
            return sInstance;
        }
    }

    private MediaRouter2(Context appContext) {
        mContext = appContext;
        mMediaRouterService = IMediaRouterService.Stub.asInterface(
                ServiceManager.getService(Context.MEDIA_ROUTER_SERVICE));
        mPackageName = mContext.getPackageName();
        mHandler = new Handler(Looper.getMainLooper());

        List<MediaRoute2Info> currentSystemRoutes = null;
        RoutingSessionInfo currentSystemSessionInfo = null;
        try {
            currentSystemRoutes = mMediaRouterService.getSystemRoutes();
            currentSystemSessionInfo = mMediaRouterService.getSystemSessionInfo();
        } catch (RemoteException ex) {
            Log.e(TAG, "Unable to get current system's routes / session info", ex);
        }

        if (currentSystemRoutes == null || currentSystemRoutes.isEmpty()) {
            throw new RuntimeException("Null or empty currentSystemRoutes. Something is wrong.");
        }

        if (currentSystemSessionInfo == null) {
            throw new RuntimeException("Null currentSystemSessionInfo. Something is wrong.");
        }

        for (MediaRoute2Info route : currentSystemRoutes) {
            mRoutes.put(route.getId(), route);
        }
        mSystemController = new SystemRoutingController(currentSystemSessionInfo);
    }

    /**
     * Returns whether any route in {@code routeList} has a same unique ID with given route.
     *
     * @hide
     */
    public static boolean checkRouteListContainsRouteId(@NonNull List<MediaRoute2Info> routeList,
            @NonNull String routeId) {
        for (MediaRoute2Info info : routeList) {
            if (TextUtils.equals(routeId, info.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Registers a callback to discover routes and to receive events when they change.
     * <p>
     * If the specified callback is already registered, its registration will be updated for the
     * given {@link Executor executor} and {@link RouteDiscoveryPreference discovery preference}.
     * </p>
     */
    public void registerRouteCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull RouteCallback routeCallback,
            @NonNull RouteDiscoveryPreference preference) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(routeCallback, "callback must not be null");
        Objects.requireNonNull(preference, "preference must not be null");

        RouteCallbackRecord record = new RouteCallbackRecord(executor, routeCallback, preference);

        mRouteCallbackRecords.remove(record);
        // It can fail to add the callback record if another registration with the same callback
        // is happening but it's okay because either this or the other registration should be done.
        mRouteCallbackRecords.addIfAbsent(record);

        synchronized (sRouterLock) {
            if (mClient == null) {
                Client2 client = new Client2();
                try {
                    mMediaRouterService.registerClient2(client, mPackageName);
                    updateDiscoveryRequestLocked();
                    mMediaRouterService.setDiscoveryRequest2(client, mDiscoveryPreference);
                    mClient = client;
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to register media router.", ex);
                }
            }
        }
    }

    /**
     * Unregisters the given callback. The callback will no longer receive events.
     * If the callback has not been added or been removed already, it is ignored.
     *
     * @param routeCallback the callback to unregister
     * @see #registerRouteCallback
     */
    public void unregisterRouteCallback(@NonNull RouteCallback routeCallback) {
        Objects.requireNonNull(routeCallback, "callback must not be null");

        if (!mRouteCallbackRecords.remove(
                new RouteCallbackRecord(null, routeCallback, null))) {
            Log.w(TAG, "Ignoring unknown callback");
            return;
        }

        synchronized (sRouterLock) {
            if (mRouteCallbackRecords.size() == 0 && mClient != null) {
                try {
                    mMediaRouterService.unregisterClient2(mClient);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to unregister media router.", ex);
                }
                //TODO: Clean up mRoutes. (onHandler?)
                mClient = null;
            }
        }
    }

    private void updateDiscoveryRequestLocked() {
        mDiscoveryPreference = new RouteDiscoveryPreference.Builder(
                mRouteCallbackRecords.stream().map(record -> record.mPreference).collect(
                        Collectors.toList())).build();
    }

    /**
     * Gets the unmodifiable list of {@link MediaRoute2Info routes} currently
     * known to the media router.
     * Please note that the list can be changed before callbacks are invoked.
     *
     * @return the list of routes that contains at least one of the route features in discovery
     * preferences registered by the application
     */
    @NonNull
    public List<MediaRoute2Info> getRoutes() {
        synchronized (sRouterLock) {
            if (mShouldUpdateRoutes) {
                mShouldUpdateRoutes = false;

                List<MediaRoute2Info> filteredRoutes = new ArrayList<>();
                for (MediaRoute2Info route : mRoutes.values()) {
                    if (route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
                        filteredRoutes.add(route);
                    }
                }
                mFilteredRoutes = Collections.unmodifiableList(filteredRoutes);
            }
        }
        return mFilteredRoutes;
    }

    /**
     * Registers a callback to get updates on creations and changes of
     * {@link RoutingController routing controllers}.
     * If you register the same callback twice or more, it will be ignored.
     *
     * @param executor the executor to execute the callback on
     * @param callback the callback to register
     * @see #unregisterControllerCallback
     */
    public void registerControllerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull RoutingControllerCallback callback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        ControllerCallbackRecord record = new ControllerCallbackRecord(executor, callback);
        if (!mControllerCallbackRecords.addIfAbsent(record)) {
            Log.w(TAG, "Ignoring the same controller callback");
            return;
        }
    }

    /**
     * Unregisters the given callback. The callback will no longer receive events.
     * If the callback has not been added or been removed already, it is ignored.
     *
     * @param callback the callback to unregister
     * @see #registerControllerCallback
     */
    public void unregisterControllerCallback(@NonNull RoutingControllerCallback callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mControllerCallbackRecords.remove(new ControllerCallbackRecord(null, callback))) {
            Log.w(TAG, "Ignoring unknown controller callback");
            return;
        }
    }

    /**
     * Sets an {@link OnGetControllerHintsListener} to send hints when creating a
     * {@link RoutingController}. To send the hints, listener should be set <em>BEFORE</em> calling
     * {@link #requestCreateController(MediaRoute2Info)}.
     *
     * @param listener A listener to send optional app-specific hints when creating a controller.
     *                 {@code null} for unset.
     */
    public void setOnGetControllerHintsListener(@Nullable OnGetControllerHintsListener listener) {
        mOnGetControllerHintsListener = listener;
    }

    /**
     * Requests the media route provider service to create a {@link RoutingController}
     * with the given route.
     *
     * @param route the route you want to create a controller with.
     *
     * @see RoutingControllerCallback#onControllerCreated
     * @see RoutingControllerCallback#onControllerCreationFailed
     */
    public void requestCreateController(@NonNull MediaRoute2Info route) {
        Objects.requireNonNull(route, "route must not be null");
        // TODO: Check the given route exists

        final int requestId;
        requestId = mControllerCreationRequestCnt.getAndIncrement();

        ControllerCreationRequest request = new ControllerCreationRequest(requestId, route);
        mControllerCreationRequests.add(request);

        OnGetControllerHintsListener listener = mOnGetControllerHintsListener;
        Bundle controllerHints = null;
        if (listener != null) {
            controllerHints = listener.onGetControllerHints(route);
            if (controllerHints != null) {
                controllerHints = new Bundle(controllerHints);
            }
        }

        Client2 client;
        synchronized (sRouterLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.requestCreateSession(client, route, requestId, controllerHints);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to request to create controller.", ex);
                mHandler.sendMessage(obtainMessage(MediaRouter2::createControllerOnHandler,
                        MediaRouter2.this, null, requestId));
            }
        }
    }

    /**
     * Gets a {@link RoutingController} which can control the routes provided by system.
     * e.g. Phone speaker, wired headset, Bluetooth, etc.
     * <p>
     * Note: The system controller can't be released. Calling {@link RoutingController#release()}
     * will be ignored.
     * <p>
     * This method will always return the same instance.
     */
    @NonNull
    public RoutingController getSystemController() {
        return mSystemController;
    }

    /**
     * Gets the list of currently non-released {@link RoutingController routing controllers}.
     * <p>
     * Note: The list returned here will never be empty. The first element in the list is
     * always the {@link #getSystemController() system controller}.
     */
    @NonNull
    public List<RoutingController> getControllers() {
        List<RoutingController> result = new ArrayList<>();
        result.add(0, mSystemController);

        Collection<RoutingController> controllers;
        synchronized (sRouterLock) {
            controllers = mRoutingControllers.values();
            if (controllers != null) {
                result.addAll(controllers);
            }
        }
        return result;
    }

    /**
     * Sends a media control request to be performed asynchronously by the route's destination.
     *
     * @param route the route that will receive the control request
     * @param request the media control request
     * @hide
     */
    //TODO: Discuss what to use for request (e.g., Intent? Request class?)
    //TODO: Provide a way to obtain the result
    public void sendControlRequest(@NonNull MediaRoute2Info route, @NonNull Intent request) {
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Client2 client;
        synchronized (sRouterLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.sendControlRequest(client, route, request);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to send control request.", ex);
            }
        }
    }

    /**
     * Requests a volume change for the route asynchronously.
     * <p>
     * It may have no effect if the route is currently not selected.
     * </p>
     *
     * @param volume The new volume value between 0 and {@link MediaRoute2Info#getVolumeMax}.
     * @hide
     */
    public void requestSetVolume(@NonNull MediaRoute2Info route, int volume) {
        Objects.requireNonNull(route, "route must not be null");

        Client2 client;
        synchronized (sRouterLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.requestSetVolume2(client, route, volume);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to send control request.", ex);
            }
        }
    }

    /**
     * Requests an incremental volume update  for the route asynchronously.
     * <p>
     * It may have no effect if the route is currently not selected.
     * </p>
     *
     * @param delta The delta to add to the current volume.
     * @hide
     */
    public void requestUpdateVolume(@NonNull MediaRoute2Info route, int delta) {
        Objects.requireNonNull(route, "route must not be null");

        Client2 client;
        synchronized (sRouterLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.requestUpdateVolume2(client, route, delta);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to send control request.", ex);
            }
        }
    }

    void addRoutesOnHandler(List<MediaRoute2Info> routes) {
        // TODO: When onRoutesAdded is first called,
        //  1) clear mRoutes before adding the routes
        //  2) Call onRouteSelected(system_route, reason_fallback) if previously selected route
        //     does not exist anymore. => We may need 'boolean MediaRoute2Info#isSystemRoute()'.
        List<MediaRoute2Info> addedRoutes = new ArrayList<>();
        synchronized (sRouterLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.put(route.getId(), route);
                if (route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
                    addedRoutes.add(route);
                }
            }
            mShouldUpdateRoutes = true;
        }
        if (addedRoutes.size() > 0) {
            notifyRoutesAdded(addedRoutes);
        }
    }

    void removeRoutesOnHandler(List<MediaRoute2Info> routes) {
        List<MediaRoute2Info> removedRoutes = new ArrayList<>();
        synchronized (sRouterLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.remove(route.getId());
                if (route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
                    removedRoutes.add(route);
                }
            }
            mShouldUpdateRoutes = true;
        }
        if (removedRoutes.size() > 0) {
            notifyRoutesRemoved(removedRoutes);
        }
    }

    void changeRoutesOnHandler(List<MediaRoute2Info> routes) {
        List<MediaRoute2Info> changedRoutes = new ArrayList<>();
        synchronized (sRouterLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.put(route.getId(), route);
                if (route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
                    changedRoutes.add(route);
                }
            }
        }
        if (changedRoutes.size() > 0) {
            notifyRoutesChanged(changedRoutes);
        }
    }

    /**
     * Creates a controller and calls the {@link RoutingControllerCallback#onControllerCreated}.
     * If the controller creation has failed, then it calls
     * {@link RoutingControllerCallback#onControllerCreationFailed}.
     * <p>
     * Pass {@code null} to sessionInfo for the failure case.
     */
    void createControllerOnHandler(@Nullable RoutingSessionInfo sessionInfo, int requestId) {
        ControllerCreationRequest matchingRequest = null;
        for (ControllerCreationRequest request : mControllerCreationRequests) {
            if (request.mRequestId == requestId) {
                matchingRequest = request;
                break;
            }
        }

        if (matchingRequest != null) {
            mControllerCreationRequests.remove(matchingRequest);

            MediaRoute2Info requestedRoute = matchingRequest.mRoute;

            if (sessionInfo == null) {
                // TODO: We may need to distinguish between failure and rejection.
                //       One way can be introducing 'reason'.
                notifyControllerCreationFailed(requestedRoute);
                return;
            } else if (!sessionInfo.getSelectedRoutes().contains(requestedRoute.getId())) {
                Log.w(TAG, "The session does not contain the requested route. "
                        + "(requestedRouteId=" + requestedRoute.getId()
                        + ", actualRoutes=" + sessionInfo.getSelectedRoutes()
                        + ")");
                notifyControllerCreationFailed(requestedRoute);
                return;
            } else if (!TextUtils.equals(requestedRoute.getProviderId(),
                    sessionInfo.getProviderId())) {
                Log.w(TAG, "The session's provider ID does not match the requested route's. "
                        + "(requested route's providerId=" + requestedRoute.getProviderId()
                        + ", actual providerId=" + sessionInfo.getProviderId()
                        + ")");
                notifyControllerCreationFailed(requestedRoute);
                return;
            }
        }

        if (sessionInfo != null) {
            RoutingController controller = new RoutingController(sessionInfo);
            synchronized (sRouterLock) {
                mRoutingControllers.put(controller.getId(), controller);
            }
            notifyControllerCreated(controller);
        }
    }

    void updateControllerOnHandler(RoutingSessionInfo sessionInfo) {
        if (sessionInfo == null) {
            Log.w(TAG, "updateControllerOnHandler: Ignoring null sessionInfo.");
            return;
        }

        if (sessionInfo.isSystemSession()) {
            // The session info is sent from SystemMediaRoute2Provider.
            RoutingController systemController = getSystemController();
            systemController.setRoutingSessionInfo(sessionInfo);
            notifyControllerUpdated(systemController);
            return;
        }

        RoutingController matchingController;
        synchronized (sRouterLock) {
            matchingController = mRoutingControllers.get(sessionInfo.getId());
        }

        if (matchingController == null) {
            Log.w(TAG, "updateControllerOnHandler: Matching controller not found. uniqueSessionId="
                    + sessionInfo.getId());
            return;
        }

        RoutingSessionInfo oldInfo = matchingController.getRoutingSessionInfo();
        if (!TextUtils.equals(oldInfo.getProviderId(), sessionInfo.getProviderId())) {
            Log.w(TAG, "updateControllerOnHandler: Provider IDs are not matched. old="
                    + oldInfo.getProviderId() + ", new=" + sessionInfo.getProviderId());
            return;
        }

        matchingController.setRoutingSessionInfo(sessionInfo);
        notifyControllerUpdated(matchingController);
    }

    void releaseControllerOnHandler(RoutingSessionInfo sessionInfo) {
        if (sessionInfo == null) {
            Log.w(TAG, "releaseControllerOnHandler: Ignoring null sessionInfo.");
            return;
        }

        final String uniqueSessionId = sessionInfo.getId();
        RoutingController matchingController;
        synchronized (sRouterLock) {
            matchingController = mRoutingControllers.get(uniqueSessionId);
        }

        if (matchingController == null) {
            if (DEBUG) {
                Log.d(TAG, "releaseControllerOnHandler: Matching controller not found. "
                        + "uniqueSessionId=" + sessionInfo.getId());
            }
            return;
        }

        RoutingSessionInfo oldInfo = matchingController.getRoutingSessionInfo();
        if (!TextUtils.equals(oldInfo.getProviderId(), sessionInfo.getProviderId())) {
            Log.w(TAG, "releaseControllerOnHandler: Provider IDs are not matched. old="
                    + oldInfo.getProviderId() + ", new=" + sessionInfo.getProviderId());
            return;
        }

        boolean removed;
        synchronized (sRouterLock) {
            removed = mRoutingControllers.remove(uniqueSessionId, matchingController);
        }

        if (removed) {
            matchingController.release();
            notifyControllerReleased(matchingController);
        }
    }

    private List<MediaRoute2Info> filterRoutes(List<MediaRoute2Info> routes,
            RouteDiscoveryPreference discoveryRequest) {
        return routes.stream()
                .filter(
                        route -> route.hasAnyFeatures(discoveryRequest.getPreferredFeatures()))
                .collect(Collectors.toList());
    }

    private void notifyRoutesAdded(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record: mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes = filterRoutes(routes, record.mPreference);
            if (!filteredRoutes.isEmpty()) {
                record.mExecutor.execute(
                        () -> record.mRouteCallback.onRoutesAdded(filteredRoutes));
            }
        }
    }

    private void notifyRoutesRemoved(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record: mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes = filterRoutes(routes, record.mPreference);
            if (!filteredRoutes.isEmpty()) {
                record.mExecutor.execute(
                        () -> record.mRouteCallback.onRoutesRemoved(filteredRoutes));
            }
        }
    }

    private void notifyRoutesChanged(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record: mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes = filterRoutes(routes, record.mPreference);
            if (!filteredRoutes.isEmpty()) {
                record.mExecutor.execute(
                        () -> record.mRouteCallback.onRoutesChanged(filteredRoutes));
            }
        }
    }

    private void notifyControllerCreated(RoutingController controller) {
        for (ControllerCallbackRecord record: mControllerCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mControllerCallback.onControllerCreated(controller));
        }
    }

    private void notifyControllerCreationFailed(MediaRoute2Info route) {
        for (ControllerCallbackRecord record: mControllerCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mControllerCallback.onControllerCreationFailed(route));
        }
    }

    private void notifyControllerUpdated(RoutingController controller) {
        for (ControllerCallbackRecord record: mControllerCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mControllerCallback.onControllerUpdated(controller));
        }
    }

    private void notifyControllerReleased(RoutingController controller) {
        for (ControllerCallbackRecord record: mControllerCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mControllerCallback.onControllerReleased(controller));
        }
    }

    /**
     * Callback for receiving events about media route discovery.
     */
    public static class RouteCallback {
        /**
         * Called when routes are added. Whenever you registers a callback, this will
         * be invoked with known routes.
         *
         * @param routes the list of routes that have been added. It's never empty.
         */
        public void onRoutesAdded(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when routes are removed.
         *
         * @param routes the list of routes that have been removed. It's never empty.
         */
        public void onRoutesRemoved(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when routes are changed. For example, it is called when the route's name
         * or volume have been changed.
         *
         * @param routes the list of routes that have been changed. It's never empty.
         */
        public void onRoutesChanged(@NonNull List<MediaRoute2Info> routes) {}
    }

    /**
     * Callback for receiving a result of {@link RoutingController} creation and updates.
     */
    public static class RoutingControllerCallback {
        /**
         * Called when the {@link RoutingController} is created.
         * A {@link RoutingController} can be created by calling
         * {@link #requestCreateController(MediaRoute2Info)}, or by the system.
         *
         * @param controller the controller to control routes
         */
        public void onControllerCreated(@NonNull RoutingController controller) {}

        /**
         * Called when the controller creation request failed.
         *
         * @param requestedRoute the route info which was used for the creation request
         */
        public void onControllerCreationFailed(@NonNull MediaRoute2Info requestedRoute) {}

        /**
         * Called when the controller is updated.
         *
         * @param controller the updated controller. Can be the system controller.
         * @see #getSystemController()
         */
        public void onControllerUpdated(@NonNull RoutingController controller) {}

        /**
         * Called when a routing controller is released. It can be released in two cases:
         * <ul>
         *     <li>When {@link RoutingController#release()} is called.</li>
         *     <li>When the remote session in the provider is destroyed.</li>
         * </ul>
         * {@link RoutingController#isReleased()} will always return {@code true}
         * for the {@code controller} here.
         *
         * @see RoutingController#release()
         * @see RoutingController#isReleased()
         */
        // TODO: Add tests for checking whether this method is called.
        // TODO: When service process dies, this should be called.
        public void onControllerReleased(@NonNull RoutingController controller) {}
    }

    /**
     * A listener interface to send an optional app-specific hints when creating the
     * {@link RoutingController}.
     */
    public interface OnGetControllerHintsListener {
        /**
         * Called when the {@link MediaRouter2} is about to request
         * the media route provider service to create a controller with the given route.
         * The {@link Bundle} returned here will be sent to media route provider service as a hint.
         * <p>
         * To send hints when creating the controller, set the listener before calling
         * {@link #requestCreateController(MediaRoute2Info)}. The method will be called
         * on the same thread which calls {@link #requestCreateController(MediaRoute2Info)}.
         *
         * @param route The route to create controller with
         * @return An optional bundle of app-specific arguments to send to the provider,
         *         or null if none. The contents of this bundle may affect the result of
         *         controller creation.
         * @see MediaRoute2ProviderService#onCreateSession(String, String, long, Bundle)
         */
        @Nullable
        Bundle onGetControllerHints(@NonNull MediaRoute2Info route);
    }

    /**
     * A class to control media routing session in media route provider.
     * For example, selecting/deselcting/transferring routes to session can be done through this
     * class. Instances are created by {@link #requestCreateController(MediaRoute2Info)}.
     */
    public class RoutingController {
        private final Object mControllerLock = new Object();

        @GuardedBy("mControllerLock")
        private RoutingSessionInfo mSessionInfo;

        @GuardedBy("mControllerLock")
        private volatile boolean mIsReleased;

        RoutingController(@NonNull RoutingSessionInfo sessionInfo) {
            mSessionInfo = sessionInfo;
        }

        /**
         * @return the ID of the controller
         */
        @NonNull
        public String getId() {
            synchronized (mControllerLock) {
                return mSessionInfo.getId();
            }
        }

        /**
         * @return the control hints used to control routing session if available.
         */
        @Nullable
        public Bundle getControlHints() {
            synchronized (mControllerLock) {
                return mSessionInfo.getControlHints();
            }
        }

        /**
         * @return the unmodifiable list of currently selected routes
         */
        @NonNull
        public List<MediaRoute2Info> getSelectedRoutes() {
            synchronized (mControllerLock) {
                return getRoutesWithIdsLocked(mSessionInfo.getSelectedRoutes());
            }
        }

        /**
         * @return the unmodifiable list of selectable routes for the session.
         */
        @NonNull
        public List<MediaRoute2Info> getSelectableRoutes() {
            synchronized (mControllerLock) {
                return getRoutesWithIdsLocked(mSessionInfo.getSelectableRoutes());
            }
        }

        /**
         * @return the unmodifiable list of deselectable routes for the session.
         */
        @NonNull
        public List<MediaRoute2Info> getDeselectableRoutes() {
            synchronized (mControllerLock) {
                return getRoutesWithIdsLocked(mSessionInfo.getDeselectableRoutes());
            }
        }

        /**
         * @return the unmodifiable list of transferrable routes for the session.
         */
        @NonNull
        public List<MediaRoute2Info> getTransferrableRoutes() {
            synchronized (mControllerLock) {
                return getRoutesWithIdsLocked(mSessionInfo.getTransferrableRoutes());
            }
        }

        /**
         * Returns true if this controller is released, false otherwise.
         * If it is released, then all other getters from this instance may return invalid values.
         * Also, any operations to this instance will be ignored once released.
         *
         * @see #release
         */
        public boolean isReleased() {
            synchronized (mControllerLock) {
                return mIsReleased;
            }
        }

        /**
         * Selects a route for the remote session. The given route must satisfy all of the
         * following conditions:
         * <ul>
         * <li>ID should not be included in {@link #getSelectedRoutes()}</li>
         * <li>ID should be included in {@link #getSelectableRoutes()}</li>
         * </ul>
         * If the route doesn't meet any of above conditions, it will be ignored.
         *
         * @see #getSelectedRoutes()
         * @see #getSelectableRoutes()
         * @see RoutingControllerCallback#onControllerUpdated
         */
        public void selectRoute(@NonNull MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");
            synchronized (mControllerLock) {
                if (mIsReleased) {
                    Log.w(TAG, "selectRoute() called on released controller. Ignoring.");
                    return;
                }
            }

            List<MediaRoute2Info> selectedRoutes = getSelectedRoutes();
            if (checkRouteListContainsRouteId(selectedRoutes, route.getId())) {
                Log.w(TAG, "Ignoring selecting a route that is already selected. route=" + route);
                return;
            }

            List<MediaRoute2Info> selectableRoutes = getSelectableRoutes();
            if (!checkRouteListContainsRouteId(selectableRoutes, route.getId())) {
                Log.w(TAG, "Ignoring selecting a non-selectable route=" + route);
                return;
            }

            Client2 client;
            synchronized (sRouterLock) {
                client = mClient;
            }
            if (client != null) {
                try {
                    mMediaRouterService.selectRoute(client, getId(), route);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to select route for session.", ex);
                }
            }
        }

        /**
         * Deselects a route from the remote session. The given route must satisfy all of the
         * following conditions:
         * <ul>
         * <li>ID should be included in {@link #getSelectedRoutes()}</li>
         * <li>ID should be included in {@link #getDeselectableRoutes()}</li>
         * </ul>
         * If the route doesn't meet any of above conditions, it will be ignored.
         *
         * @see #getSelectedRoutes()
         * @see #getDeselectableRoutes()
         * @see RoutingControllerCallback#onControllerUpdated
         */
        public void deselectRoute(@NonNull MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");
            synchronized (mControllerLock) {
                if (mIsReleased) {
                    Log.w(TAG, "deselectRoute() called on released controller. Ignoring.");
                    return;
                }
            }

            List<MediaRoute2Info> selectedRoutes = getSelectedRoutes();
            if (!checkRouteListContainsRouteId(selectedRoutes, route.getId())) {
                Log.w(TAG, "Ignoring deselecting a route that is not selected. route=" + route);
                return;
            }

            List<MediaRoute2Info> deselectableRoutes = getDeselectableRoutes();
            if (!checkRouteListContainsRouteId(deselectableRoutes, route.getId())) {
                Log.w(TAG, "Ignoring deselecting a non-deselectable route=" + route);
                return;
            }

            Client2 client;
            synchronized (sRouterLock) {
                client = mClient;
            }
            if (client != null) {
                try {
                    mMediaRouterService.deselectRoute(client, getId(), route);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to remove route from session.", ex);
                }
            }
        }

        /**
         * Transfers to a given route for the remote session. The given route must satisfy
         * all of the following conditions:
         * <ul>
         * <li>ID should not be included in {@link #getSelectedRoutes()}</li>
         * <li>ID should be included in {@link #getTransferrableRoutes()}</li>
         * </ul>
         * If the route doesn't meet any of above conditions, it will be ignored.
         *
         * @see #getSelectedRoutes()
         * @see #getTransferrableRoutes()
         * @see RoutingControllerCallback#onControllerUpdated
         */
        public void transferToRoute(@NonNull MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");
            synchronized (mControllerLock) {
                if (mIsReleased) {
                    Log.w(TAG, "transferToRoute() called on released controller. Ignoring.");
                    return;
                }
            }

            List<MediaRoute2Info> selectedRoutes = getSelectedRoutes();
            if (checkRouteListContainsRouteId(selectedRoutes, route.getId())) {
                Log.w(TAG, "Ignoring transferring to a route that is already added. route="
                        + route);
                return;
            }

            List<MediaRoute2Info> transferrableRoutes = getTransferrableRoutes();
            if (!checkRouteListContainsRouteId(transferrableRoutes, route.getId())) {
                Log.w(TAG, "Ignoring transferring to a non-transferrable route=" + route);
                return;
            }

            Client2 client;
            synchronized (sRouterLock) {
                client = mClient;
            }
            if (client != null) {
                try {
                    mMediaRouterService.transferToRoute(client, getId(), route);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to transfer to route for session.", ex);
                }
            }
        }

        /**
         * Release this controller and corresponding session.
         * Any operations on this controller after calling this method will be ignored.
         * The devices that are playing media will stop playing it.
         */
        // TODO: Add tests using {@link MediaRouter2Manager#getActiveSessions()}.
        public void release() {
            synchronized (mControllerLock) {
                if (mIsReleased) {
                    Log.w(TAG, "release() called on released controller. Ignoring.");
                    return;
                }
                mIsReleased = true;
            }

            Client2 client;
            boolean removed;
            synchronized (sRouterLock) {
                removed = mRoutingControllers.remove(getId(), this);
                client = mClient;
            }

            if (removed) {
                mHandler.post(() -> notifyControllerReleased(RoutingController.this));
            }

            if (client != null) {
                try {
                    mMediaRouterService.releaseSession(client, getId());
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to notify of controller release", ex);
                }
            }
        }

        @Override
        public String toString() {
            // To prevent logging spam, we only print the ID of each route.
            List<String> selectedRoutes = getSelectedRoutes().stream()
                    .map(MediaRoute2Info::getId).collect(Collectors.toList());
            List<String> selectableRoutes = getSelectableRoutes().stream()
                    .map(MediaRoute2Info::getId).collect(Collectors.toList());
            List<String> deselectableRoutes = getDeselectableRoutes().stream()
                    .map(MediaRoute2Info::getId).collect(Collectors.toList());
            List<String> transferrableRoutes = getTransferrableRoutes().stream()
                    .map(MediaRoute2Info::getId).collect(Collectors.toList());

            StringBuilder result = new StringBuilder()
                    .append("RoutingController{ ")
                    .append("id=").append(getId())
                    .append(", selectedRoutes={")
                    .append(selectedRoutes)
                    .append("}")
                    .append(", selectableRoutes={")
                    .append(selectableRoutes)
                    .append("}")
                    .append(", deselectableRoutes={")
                    .append(deselectableRoutes)
                    .append("}")
                    .append(", transferrableRoutes={")
                    .append(transferrableRoutes)
                    .append("}")
                    .append(" }");
            return result.toString();
        }

        /**
         * TODO: Change this to package private. (Hidden for debugging purposes)
         * @hide
         */
        @NonNull
        public RoutingSessionInfo getRoutingSessionInfo() {
            synchronized (mControllerLock) {
                return mSessionInfo;
            }
        }

        void setRoutingSessionInfo(@NonNull RoutingSessionInfo info) {
            synchronized (mControllerLock) {
                mSessionInfo = info;
            }
        }

        // TODO: This method uses two locks (mLock outside, sLock inside).
        //       Check if there is any possiblity of deadlock.
        private List<MediaRoute2Info> getRoutesWithIdsLocked(List<String> routeIds) {
            List<MediaRoute2Info> routes = new ArrayList<>();
            synchronized (sRouterLock) {
                // TODO: Maybe able to change using Collection.stream()?
                for (String routeId : routeIds) {
                    MediaRoute2Info route = mRoutes.get(routeId);
                    if (route != null) {
                        routes.add(route);
                    }
                }
            }
            return Collections.unmodifiableList(routes);
        }
    }

    class SystemRoutingController extends RoutingController {
        SystemRoutingController(@NonNull RoutingSessionInfo sessionInfo) {
            super(sessionInfo);
        }

        @Override
        public void release() {
            // Do nothing. SystemRoutingController will never be released
        }

        @Override
        public boolean isReleased() {
            // SystemRoutingController will never be released
            return false;
        }
    }

    final class RouteCallbackRecord {
        public final Executor mExecutor;
        public final RouteCallback mRouteCallback;
        public final RouteDiscoveryPreference mPreference;

        RouteCallbackRecord(@Nullable Executor executor, @NonNull RouteCallback routeCallback,
                @Nullable RouteDiscoveryPreference preference) {
            mRouteCallback = routeCallback;
            mExecutor = executor;
            mPreference = preference;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RouteCallbackRecord)) {
                return false;
            }
            return mRouteCallback == ((RouteCallbackRecord) obj).mRouteCallback;
        }

        @Override
        public int hashCode() {
            return mRouteCallback.hashCode();
        }
    }

    final class ControllerCallbackRecord {
        public final Executor mExecutor;
        public final RoutingControllerCallback mControllerCallback;

        ControllerCallbackRecord(@NonNull Executor executor,
                @NonNull RoutingControllerCallback controllerCallback) {
            mControllerCallback = controllerCallback;
            mExecutor = executor;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ControllerCallbackRecord)) {
                return false;
            }
            return mControllerCallback
                    == ((ControllerCallbackRecord) obj).mControllerCallback;
        }

        @Override
        public int hashCode() {
            return mControllerCallback.hashCode();
        }
    }

    final class ControllerCreationRequest {
        public final MediaRoute2Info mRoute;
        public final int mRequestId;

        ControllerCreationRequest(int requestId, @NonNull MediaRoute2Info route) {
            mRoute = route;
            mRequestId = requestId;
        }
    }

    class Client2 extends IMediaRouter2Client.Stub {
        @Override
        public void notifyRestoreRoute() throws RemoteException {}

        @Override
        public void notifyRoutesAdded(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::addRoutesOnHandler,
                    MediaRouter2.this, routes));
        }

        @Override
        public void notifyRoutesRemoved(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::removeRoutesOnHandler,
                    MediaRouter2.this, routes));
        }

        @Override
        public void notifyRoutesChanged(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::changeRoutesOnHandler,
                    MediaRouter2.this, routes));
        }

        @Override
        public void notifySessionCreated(@Nullable RoutingSessionInfo sessionInfo, int requestId) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::createControllerOnHandler,
                    MediaRouter2.this, sessionInfo, requestId));
        }

        @Override
        public void notifySessionInfoChanged(@Nullable RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::updateControllerOnHandler,
                    MediaRouter2.this, sessionInfo));
        }

        @Override
        public void notifySessionReleased(RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::releaseControllerOnHandler,
                    MediaRouter2.this, sessionInfo));
        }
    }
}
