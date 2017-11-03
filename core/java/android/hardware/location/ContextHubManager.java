/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.hardware.location;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.Log;

import java.util.List;

/**
 * A class that exposes the Context hubs on a device to applications.
 *
 * Please note that this class is not expected to be used by unbundled applications. Also, calling
 * applications are expected to have LOCATION_HARDWARE permissions to use this class.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.CONTEXTHUB_SERVICE)
public final class ContextHubManager {
    private static final String TAG = "ContextHubManager";

    private final Looper mMainLooper;
    private final IContextHubService mService;
    private Callback mCallback;
    private Handler mCallbackHandler;

    /**
     * @deprecated Use {@code mCallback} instead.
     */
    @Deprecated
    private ICallback mLocalCallback;

    /**
     * An interface to receive asynchronous communication from the context hub.
     */
    public abstract static class Callback {
        protected Callback() {}

        /**
         * Callback function called on message receipt from context hub.
         *
         * @param hubHandle Handle (system-wide unique identifier) of the hub of the message.
         * @param nanoAppHandle Handle (unique identifier) for app instance that sent the message.
         * @param message The context hub message.
         *
         * @see ContextHubMessage
         */
        public abstract void onMessageReceipt(
                int hubHandle,
                int nanoAppHandle,
                ContextHubMessage message);
    }

    /**
     * @deprecated Use {@link Callback} instead.
     * @hide
     */
    @Deprecated
    public interface ICallback {
        /**
         * Callback function called on message receipt from context hub.
         *
         * @param hubHandle Handle (system-wide unique identifier) of the hub of the message.
         * @param nanoAppHandle Handle (unique identifier) for app instance that sent the message.
         * @param message The context hub message.
         *
         * @see ContextHubMessage
         */
        void onMessageReceipt(int hubHandle, int nanoAppHandle, ContextHubMessage message);
    }

    /**
     * Get a handle to all the context hubs in the system
     * @return array of context hub handles
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public int[] getContextHubHandles() {
        try {
            return mService.getContextHubHandles();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get more information about a specific hub.
     *
     * @param hubHandle Handle (system-wide unique identifier) of a context hub.
     * @return ContextHubInfo Information about the requested context hub.
     *
     * @see ContextHubInfo
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public ContextHubInfo getContextHubInfo(int hubHandle) {
        try {
            return mService.getContextHubInfo(hubHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Load a nano app on a specified context hub.
     *
     * Note that loading is asynchronous.  When we return from this method,
     * the nano app (probably) hasn't loaded yet.  Assuming a return of 0
     * from this method, then the final success/failure for the load, along
     * with the "handle" for the nanoapp, is all delivered in a byte
     * string via a call to Callback.onMessageReceipt.
     *
     * TODO(b/30784270): Provide a better success/failure and "handle" delivery.
     *
     * @param hubHandle handle of context hub to load the app on.
     * @param app the nanoApp to load on the hub
     *
     * @return 0 if the command for loading was sent to the context hub;
     *         -1 otherwise
     *
     * @see NanoApp
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public int loadNanoApp(int hubHandle, NanoApp app) {
        try {
            return mService.loadNanoApp(hubHandle, app);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unload a specified nanoApp
     *
     * Note that unloading is asynchronous.  When we return from this method,
     * the nano app (probably) hasn't unloaded yet.  Assuming a return of 0
     * from this method, then the final success/failure for the unload is
     * delivered in a byte string via a call to Callback.onMessageReceipt.
     *
     * TODO(b/30784270): Provide a better success/failure delivery.
     *
     * @param nanoAppHandle handle of the nanoApp to unload
     *
     * @return 0 if the command for unloading was sent to the context hub;
     *         -1 otherwise
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public int unloadNanoApp(int nanoAppHandle) {
        try {
            return mService.unloadNanoApp(nanoAppHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * get information about the nano app instance
     *
     * NOTE: The returned NanoAppInstanceInfo does _not_ contain correct
     * information for several fields, specifically:
     * - getName()
     * - getPublisher()
     * - getNeededExecMemBytes()
     * - getNeededReadMemBytes()
     * - getNeededWriteMemBytes()
     *
     * For example, say you call loadNanoApp() with a NanoApp that has
     * getName() returning "My Name".  Later, if you call getNanoAppInstanceInfo
     * for that nanoapp, the returned NanoAppInstanceInfo's getName()
     * method will claim "Preloaded app, unknown", even though you would
     * have expected "My Name".  For now, as the user, you'll need to
     * separately track the above fields if they are of interest to you.
     *
     * TODO(b/30943489): Have the returned NanoAppInstanceInfo contain the
     *     correct information.
     *
     * @param nanoAppHandle handle of the nanoAppInstance
     * @return NanoAppInstanceInfo Information about the nano app instance.
     *
     * @see NanoAppInstanceInfo
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppHandle) {
        try {
            return mService.getNanoAppInstanceInfo(nanoAppHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Find a specified nano app on the system
     *
     * @param hubHandle handle of hub to search for nano app
     * @param filter filter specifying the search criteria for app
     *
     * @see NanoAppFilter
     *
     * @return int[] Array of handles to any found nano apps
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public int[] findNanoAppOnHub(int hubHandle, NanoAppFilter filter) {
        try {
            return mService.findNanoAppOnHub(hubHandle, filter);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Send a message to a specific nano app instance on a context hub.
     *
     * Note that the return value of this method only speaks of success
     * up to the point of sending this to the Context Hub.  It is not
     * an assurance that the Context Hub successfully sent this message
     * on to the nanoapp.  If assurance is desired, a protocol should be
     * established between your code and the nanoapp, with the nanoapp
     * sending a confirmation message (which will be reported via
     * Callback.onMessageReceipt).
     *
     * @param hubHandle handle of the hub to send the message to
     * @param nanoAppHandle  handle of the nano app to send to
     * @param message Message to be sent
     *
     * @see ContextHubMessage
     *
     * @return int 0 on success, -1 otherwise
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public int sendMessage(int hubHandle, int nanoAppHandle, ContextHubMessage message) {
        try {
            return mService.sendMessage(hubHandle, nanoAppHandle, message);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of context hubs in the system.
     *
     * @return the list of context hub informations
     *
     * @see ContextHubInfo
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public List<ContextHubInfo> getContextHubs() {
        throw new UnsupportedOperationException("TODO: Implement this");
    }

    /*
     * Helper function to generate a stub for a non-query transaction callback.
     *
     * @param transaction the transaction to unblock when complete
     *
     * @return the callback
     *
     * @hide
     */
    private IContextHubTransactionCallback createTransactionCallback(
            ContextHubTransaction<Void> transaction) {
        return new IContextHubTransactionCallback.Stub() {
            @Override
            public void onQueryResponse(int result, List<NanoAppState> nanoappList) {
                Log.e(TAG, "Received a query callback on a non-query request");
                transaction.setResponse(new ContextHubTransaction.Response<Void>(
                        ContextHubTransaction.TRANSACTION_FAILED_SERVICE_INTERNAL_FAILURE, null));
            }

            @Override
            public void onTransactionComplete(int result) {
                transaction.setResponse(new ContextHubTransaction.Response<Void>(result, null));
            }
        };
    }

   /*
    * Helper function to generate a stub for a query transaction callback.
    *
    * @param transaction the transaction to unblock when complete
    *
    * @return the callback
    *
    * @hide
    */
    private IContextHubTransactionCallback createQueryCallback(
            ContextHubTransaction<List<NanoAppState>> transaction) {
        return new IContextHubTransactionCallback.Stub() {
            @Override
            public void onQueryResponse(int result, List<NanoAppState> nanoappList) {
                transaction.setResponse(new ContextHubTransaction.Response<List<NanoAppState>>(
                        result, nanoappList));
            }

            @Override
            public void onTransactionComplete(int result) {
                Log.e(TAG, "Received a non-query callback on a query request");
                transaction.setResponse(new ContextHubTransaction.Response<List<NanoAppState>>(
                        ContextHubTransaction.TRANSACTION_FAILED_SERVICE_INTERNAL_FAILURE, null));
            }
        };
    }

    /**
     * Loads a nanoapp at the specified Context Hub.
     *
     * After the nanoapp binary is successfully loaded at the specified hub, the nanoapp will be in
     * the enabled state.
     *
     * @param hubInfo the hub to load the nanoapp on
     * @param appBinary The app binary to load
     *
     * @return the ContextHubTransaction of the request
     *
     * @see NanoAppBinary
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public ContextHubTransaction<Void> loadNanoApp(
            ContextHubInfo hubInfo, NanoAppBinary appBinary) {
        throw new UnsupportedOperationException("TODO: Implement this");
    }

    /**
     * Unloads a nanoapp at the specified Context Hub.
     *
     * @param hubInfo the hub to unload the nanoapp from
     * @param nanoAppId the app to unload
     *
     * @return the ContextHubTransaction of the request
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public ContextHubTransaction<Void> unloadNanoApp(ContextHubInfo hubInfo, long nanoAppId) {
        throw new UnsupportedOperationException("TODO: Implement this");
    }

    /**
     * Enables a nanoapp at the specified Context Hub.
     *
     * @param hubInfo the hub to enable the nanoapp on
     * @param nanoAppId the app to enable
     *
     * @return the ContextHubTransaction of the request
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public ContextHubTransaction<Void> enableNanoApp(ContextHubInfo hubInfo, long nanoAppId) {
        throw new UnsupportedOperationException("TODO: Implement this");
    }

    /**
     * Disables a nanoapp at the specified Context Hub.
     *
     * @param hubInfo the hub to disable the nanoapp on
     * @param nanoAppId the app to disable
     *
     * @return the ContextHubTransaction of the request
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public ContextHubTransaction<Void> disableNanoApp(ContextHubInfo hubInfo, long nanoAppId) {
        throw new UnsupportedOperationException("TODO: Implement this");
    }

    /**
     * Requests a query for nanoapps loaded at the specified Context Hub.
     *
     * @param hubInfo the hub to query a list of nanoapps from
     *
     * @return the ContextHubTransaction of the request
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public ContextHubTransaction<List<NanoAppState>> queryNanoApps(ContextHubInfo hubInfo) {
        throw new UnsupportedOperationException("TODO: Implement this");
    }

    /**
     * Set a callback to receive messages from the context hub
     *
     * @param callback Callback object
     *
     * @see Callback
     *
     * @return int 0 on success, -1 otherwise
     */
    @SuppressLint("Doclava125")
    public int registerCallback(Callback callback) {
        return registerCallback(callback, null);
    }

    /**
     * @deprecated Use {@link #registerCallback(Callback)} instead.
     * @hide
     */
    @Deprecated
    public int registerCallback(ICallback callback) {
        if (mLocalCallback != null) {
            Log.w(TAG, "Max number of local callbacks reached!");
            return -1;
        }
        mLocalCallback = callback;
        return 0;
    }

    /**
     * Set a callback to receive messages from the context hub
     *
     * @param callback Callback object
     * @param handler Handler object
     *
     * @see Callback
     *
     * @return int 0 on success, -1 otherwise
     */
    @SuppressLint("Doclava125")
    public int registerCallback(Callback callback, Handler handler) {
        synchronized(this) {
            if (mCallback != null) {
                Log.w(TAG, "Max number of callbacks reached!");
                return -1;
            }
            mCallback = callback;
            mCallbackHandler = handler;
        }
        return 0;
    }

    /**
     * Creates and registers a client and its callback with the Context Hub Service.
     *
     * A client is registered with the Context Hub Service for a specified Context Hub. When the
     * registration succeeds, the client can send messages to nanoapps through the returned
     * {@link ContextHubClient} object, and receive notifications through the provided callback.
     *
     * @param callback the notification callback to register
     * @param hubInfo the hub to attach this client to
     * @param handler the handler to invoke the callback, if null uses the main thread's Looper
     *
     * @return the registered client object
     *
     * @see ContextHubClientCallback
     *
     * @hide
     */
    public ContextHubClient createClient(
            ContextHubClientCallback callback, ContextHubInfo hubInfo, @Nullable Handler handler) {
        throw new UnsupportedOperationException(
                "TODO: Implement this, and throw an exception on error");
    }

    /**
     * Unregister a callback for receive messages from the context hub.
     *
     * @see Callback
     *
     * @param callback method to deregister
     *
     * @return int 0 on success, -1 otherwise
     */
    @SuppressLint("Doclava125")
    public int unregisterCallback(Callback callback) {
      synchronized(this) {
          if (callback != mCallback) {
              Log.w(TAG, "Cannot recognize callback!");
              return -1;
          }

          mCallback = null;
          mCallbackHandler = null;
      }
      return 0;
    }

    /**
     * @deprecated Use {@link #unregisterCallback(Callback)} instead.
     * @hide
     */
    @Deprecated
    public synchronized int unregisterCallback(ICallback callback) {
        if (callback != mLocalCallback) {
            Log.w(TAG, "Cannot recognize local callback!");
            return -1;
        }
        mLocalCallback = null;
        return 0;
    }

    private final IContextHubCallback.Stub mClientCallback = new IContextHubCallback.Stub() {
        @Override
        public void onMessageReceipt(final int hubId, final int nanoAppId,
                final ContextHubMessage message) {
            if (mCallback != null) {
                synchronized(this) {
                    final Callback callback = mCallback;
                    Handler handler = mCallbackHandler == null ?
                            new Handler(mMainLooper) : mCallbackHandler;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onMessageReceipt(hubId, nanoAppId, message);
                        }
                    });
                }
            } else if (mLocalCallback != null) {
                // we always ensure that mCallback takes precedence, because mLocalCallback is only
                // for internal compatibility
                synchronized (this) {
                    mLocalCallback.onMessageReceipt(hubId, nanoAppId, message);
                }
            } else {
                Log.d(TAG, "Context hub manager client callback is NULL");
            }
        }
    };

    /** @throws ServiceNotFoundException
     * @hide */
    public ContextHubManager(Context context, Looper mainLooper) throws ServiceNotFoundException {
        mMainLooper = mainLooper;
        mService = IContextHubService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.CONTEXTHUB_SERVICE));
        try {
            mService.registerCallback(mClientCallback);
        } catch (RemoteException e) {
            Log.w(TAG, "Could not register callback:" + e);
        }
    }
}
