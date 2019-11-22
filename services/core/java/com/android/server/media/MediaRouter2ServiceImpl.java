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

package com.android.server.media;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.IMediaRouter2Client;
import android.media.IMediaRouter2Manager;
import android.media.IMediaRouterClient;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRouter2;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.function.pooled.PooledLambda;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * TODO: Merge this to MediaRouterService once it's finished.
 */
class MediaRouter2ServiceImpl {
    private static final String TAG = "MR2ServiceImpl";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long ROUTE_SELECTION_REQUEST_TIMEOUT_MS = 5000L;

    private final Context mContext;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final SparseArray<UserRecord> mUserRecords = new SparseArray<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, ClientRecord> mAllClientRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, ManagerRecord> mAllManagerRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private int mCurrentUserId = -1;
    @GuardedBy("mLock")
    private int mSelectRouteRequestSequenceNumber = 0;

    MediaRouter2ServiceImpl(Context context) {
        mContext = context;
    }

    @NonNull
    public List<MediaRoute2Info> getSystemRoutes() {
        final int uid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(uid);

        Collection<MediaRoute2Info> systemRoutes;
        synchronized (mLock) {
            UserRecord userRecord = mUserRecords.get(userId);
            if (userRecord == null) {
                userRecord = new UserRecord(userId);
                mUserRecords.put(userId, userRecord);
                initializeUserLocked(userRecord);
            }
            systemRoutes = userRecord.mHandler.mSystemProvider.getProviderInfo().getRoutes();
        }
        return new ArrayList<>(systemRoutes);
    }

    public void registerClient(@NonNull IMediaRouter2Client client,
            @NonNull String packageName) {
        Objects.requireNonNull(client, "client must not be null");

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserId(uid);
        final boolean trusted = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CONFIGURE_WIFI_DISPLAY)
                == PackageManager.PERMISSION_GRANTED;
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerClient2Locked(client, uid, pid, packageName, userId, trusted);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void unregisterClient(@NonNull IMediaRouter2Client client) {
        Objects.requireNonNull(client, "client must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                unregisterClient2Locked(client, false);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void registerManager(@NonNull IMediaRouter2Manager manager,
            @NonNull String packageName) {
        Objects.requireNonNull(manager, "manager must not be null");
        //TODO: should check permission
        final boolean trusted = true;

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerManagerLocked(manager, uid, pid, packageName, userId, trusted);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void unregisterManager(@NonNull IMediaRouter2Manager manager) {
        Objects.requireNonNull(manager, "manager must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                unregisterManagerLocked(manager, false);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void sendControlRequest(@NonNull IMediaRouter2Client client,
            @NonNull MediaRoute2Info route, @NonNull Intent request) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(request, "request must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                sendControlRequestLocked(client, route, request);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    //TODO: What would happen if a media app used MediaRouter and MediaRouter2 simultaneously?
    public void setControlCategories(@NonNull IMediaRouterClient client,
            @Nullable List<String> categories) {
        Objects.requireNonNull(client, "client must not be null");
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                ClientRecord clientRecord = mAllClientRecords.get(client.asBinder());
                setControlCategoriesLocked(clientRecord, categories);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setControlCategories2(@NonNull IMediaRouter2Client client,
            @Nullable List<String> categories) {
        Objects.requireNonNull(client, "client must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                ClientRecord clientRecord = mAllClientRecords.get(client.asBinder());
                setControlCategoriesLocked(clientRecord, categories);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestSelectRoute2(@NonNull IMediaRouter2Client client,
            @Nullable MediaRoute2Info route) {
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestSelectRoute2Locked(mAllClientRecords.get(client.asBinder()), route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestSetVolume2(IMediaRouter2Client client, MediaRoute2Info route, int volume) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestSetVolumeLocked(client, route, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestUpdateVolume2(IMediaRouter2Client client, MediaRoute2Info route, int delta) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestUpdateVolumeLocked(client, route, delta);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void selectClientRoute2(@NonNull IMediaRouter2Manager manager,
            String packageName, @Nullable MediaRoute2Info route) {
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                selectClientRoute2Locked(manager, packageName, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestSetVolume2Manager(IMediaRouter2Manager manager,
            MediaRoute2Info route, int volume) {
        Objects.requireNonNull(manager, "manager must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestSetVolumeLocked(manager, route, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestUpdateVolume2Manager(IMediaRouter2Manager manager,
            MediaRoute2Info route, int delta) {
        Objects.requireNonNull(manager, "manager must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestUpdateVolumeLocked(manager, route, delta);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }


    public void registerClient(@NonNull IMediaRouterClient client, @NonNull String packageName) {
        Objects.requireNonNull(client, "client must not be null");

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerClient1Locked(client, packageName, userId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void unregisterClient(@NonNull IMediaRouterClient client) {
        Objects.requireNonNull(client, "client must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                unregisterClient1Locked(client);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    //TODO: Review this is handling multi-user properly.
    void switchUser() {
        synchronized (mLock) {
            int userId = ActivityManager.getCurrentUser();
            if (mCurrentUserId != userId) {
                final int oldUserId = mCurrentUserId;
                mCurrentUserId = userId; // do this first

                UserRecord oldUser = mUserRecords.get(oldUserId);
                if (oldUser != null) {
                    oldUser.mHandler.sendMessage(
                            obtainMessage(UserHandler::stop, oldUser.mHandler));
                    disposeUserIfNeededLocked(oldUser); // since no longer current user
                }

                UserRecord newUser = mUserRecords.get(userId);
                if (newUser != null) {
                    newUser.mHandler.sendMessage(
                            obtainMessage(UserHandler::start, newUser.mHandler));
                }
            }
        }
    }

    void clientDied(Client2Record clientRecord) {
        synchronized (mLock) {
            unregisterClient2Locked(clientRecord.mClient, true);
        }
    }

    void managerDied(ManagerRecord managerRecord) {
        synchronized (mLock) {
            unregisterManagerLocked(managerRecord.mManager, true);
        }
    }

    private void registerClient2Locked(IMediaRouter2Client client,
            int uid, int pid, String packageName, int userId, boolean trusted) {
        final IBinder binder = client.asBinder();
        if (mAllClientRecords.get(binder) == null) {
            UserRecord userRecord = mUserRecords.get(userId);
            if (userRecord == null) {
                userRecord = new UserRecord(userId);
                mUserRecords.put(userId, userRecord);
                initializeUserLocked(userRecord);
            }
            Client2Record clientRecord = new Client2Record(userRecord, client, uid, pid,
                    packageName, trusted);
            try {
                binder.linkToDeath(clientRecord, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException("Media router client died prematurely.", ex);
            }

            userRecord.mClientRecords.add(clientRecord);
            mAllClientRecords.put(binder, clientRecord);

            userRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::notifyRoutesToClient, userRecord.mHandler, client));
        }
    }

    private void unregisterClient2Locked(IMediaRouter2Client client, boolean died) {
        Client2Record clientRecord = (Client2Record) mAllClientRecords.remove(client.asBinder());
        if (clientRecord != null) {
            UserRecord userRecord = clientRecord.mUserRecord;
            userRecord.mClientRecords.remove(clientRecord);
            //TODO: update discovery request
            clientRecord.dispose();
            disposeUserIfNeededLocked(userRecord); // since client removed from user
        }
    }

    private void requestSelectRoute2Locked(ClientRecord clientRecord, MediaRoute2Info route) {
        if (clientRecord != null) {
            MediaRoute2Info oldRoute = clientRecord.mSelectedRoute;
            clientRecord.mSelectingRoute = route;

            UserHandler handler = clientRecord.mUserRecord.mHandler;
            //TODO: Handle transfer instead of unselect and select
            if (oldRoute != null) {
                handler.sendMessage(obtainMessage(
                        UserHandler::unselectRoute, handler, clientRecord.mPackageName, oldRoute));
            }
            if (route != null) {
                final int seq = mSelectRouteRequestSequenceNumber;
                mSelectRouteRequestSequenceNumber++;

                handler.sendMessage(obtainMessage(
                        UserHandler::requestSelectRoute, handler, clientRecord.mPackageName,
                        route, seq));

                // Remove all previous timeout messages
                for (int previousSeq : clientRecord.mSelectRouteSequenceNumbers) {
                    clientRecord.mUserRecord.mHandler.removeMessages(previousSeq);
                }
                clientRecord.mSelectRouteSequenceNumbers.clear();

                // When the request is not handled in timeout, set the client's route to default.
                Message timeoutMsg = obtainMessage(UserHandler::handleRouteSelectionTimeout,
                        handler, clientRecord.mPackageName, route);
                timeoutMsg.what = seq; // Make the message cancelable.
                handler.sendMessageDelayed(timeoutMsg, ROUTE_SELECTION_REQUEST_TIMEOUT_MS);
                clientRecord.mSelectRouteSequenceNumbers.add(seq);
            }
        }
    }

    private void setControlCategoriesLocked(ClientRecord clientRecord, List<String> categories) {
        if (clientRecord != null) {
            clientRecord.mControlCategories = categories;

            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::updateClientUsage,
                            clientRecord.mUserRecord.mHandler, clientRecord));
        }
    }

    private void sendControlRequestLocked(IMediaRouter2Client client, MediaRoute2Info route,
            Intent request) {
        final IBinder binder = client.asBinder();
        ClientRecord clientRecord = mAllClientRecords.get(binder);

        if (clientRecord != null) {
            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::sendControlRequest,
                            clientRecord.mUserRecord.mHandler, route, request));
        }
    }

    private void requestSetVolumeLocked(IMediaRouter2Client client, MediaRoute2Info route,
            int volume) {
        final IBinder binder = client.asBinder();
        ClientRecord clientRecord = mAllClientRecords.get(binder);

        if (clientRecord != null) {
            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::requestSetVolume,
                            clientRecord.mUserRecord.mHandler, route, volume));
        }
    }

    private void requestUpdateVolumeLocked(IMediaRouter2Client client, MediaRoute2Info route,
            int delta) {
        final IBinder binder = client.asBinder();
        ClientRecord clientRecord = mAllClientRecords.get(binder);

        if (clientRecord != null) {
            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::requestUpdateVolume,
                            clientRecord.mUserRecord.mHandler, route, delta));
        }
    }

    private void registerManagerLocked(IMediaRouter2Manager manager,
            int uid, int pid, String packageName, int userId, boolean trusted) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);
        if (managerRecord == null) {
            boolean newUser = false;
            UserRecord userRecord = mUserRecords.get(userId);
            if (userRecord == null) {
                userRecord = new UserRecord(userId);
                newUser = true;
            }
            managerRecord = new ManagerRecord(userRecord, manager, uid, pid, packageName, trusted);
            try {
                binder.linkToDeath(managerRecord, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException("Media router manager died prematurely.", ex);
            }

            if (newUser) {
                mUserRecords.put(userId, userRecord);
                initializeUserLocked(userRecord);
            }

            userRecord.mManagerRecords.add(managerRecord);
            mAllManagerRecords.put(binder, managerRecord);

            userRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::notifyRoutesToManager,
                            userRecord.mHandler, manager));

            for (ClientRecord clientRecord : userRecord.mClientRecords) {
                // TODO: Do not use updateClientUsage since it updates all managers.
                // Instead, Notify only to the manager that is currently being registered.

                // TODO: UserRecord <-> ClientRecord, why do they reference each other?
                // How about removing mUserRecord from clientRecord?
                clientRecord.mUserRecord.mHandler.sendMessage(
                        obtainMessage(UserHandler::updateClientUsage,
                            clientRecord.mUserRecord.mHandler, clientRecord));
            }
        }
    }

    private void unregisterManagerLocked(IMediaRouter2Manager manager, boolean died) {
        ManagerRecord managerRecord = mAllManagerRecords.remove(manager.asBinder());
        if (managerRecord != null) {
            UserRecord userRecord = managerRecord.mUserRecord;
            userRecord.mManagerRecords.remove(managerRecord);
            managerRecord.dispose();
            disposeUserIfNeededLocked(userRecord); // since manager removed from user
        }
    }

    private void selectClientRoute2Locked(IMediaRouter2Manager manager,
            String packageName, MediaRoute2Info route) {
        ManagerRecord managerRecord = mAllManagerRecords.get(manager.asBinder());
        if (managerRecord != null) {
            ClientRecord clientRecord =
                    managerRecord.mUserRecord.findClientRecordLocked(packageName);
            if (clientRecord == null) {
                Slog.w(TAG, "Ignoring route selection for unknown client.");
            }
            if (clientRecord != null && managerRecord.mTrusted) {
                requestSelectRoute2Locked(clientRecord, route);
            }
        }
    }

    private void requestSetVolumeLocked(IMediaRouter2Manager manager, MediaRoute2Info route,
            int volume) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord != null) {
            managerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::requestSetVolume,
                            managerRecord.mUserRecord.mHandler, route, volume));
        }
    }

    private void requestUpdateVolumeLocked(IMediaRouter2Manager manager, MediaRoute2Info route,
            int delta) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord != null) {
            managerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::requestUpdateVolume,
                            managerRecord.mUserRecord.mHandler, route, delta));
        }
    }


    private void initializeUserLocked(UserRecord userRecord) {
        if (DEBUG) {
            Slog.d(TAG, userRecord + ": Initialized");
        }
        if (userRecord.mUserId == mCurrentUserId) {
            userRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::start, userRecord.mHandler));
        }
    }

    private void disposeUserIfNeededLocked(UserRecord userRecord) {
        // If there are no records left and the user is no longer current then go ahead
        // and purge the user record and all of its associated state.  If the user is current
        // then leave it alone since we might be connected to a route or want to query
        // the same route information again soon.
        if (userRecord.mUserId != mCurrentUserId
                && userRecord.mClientRecords.isEmpty()
                && userRecord.mManagerRecords.isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, userRecord + ": Disposed");
            }
            mUserRecords.remove(userRecord.mUserId);
            // Note: User already stopped (by switchUser) so no need to send stop message here.
        }
    }

    private void registerClient1Locked(IMediaRouterClient client, String packageName,
            int userId) {
        final IBinder binder = client.asBinder();
        if (mAllClientRecords.get(binder) == null) {
            boolean newUser = false;
            UserRecord userRecord = mUserRecords.get(userId);
            if (userRecord == null) {
                userRecord = new UserRecord(userId);
                newUser = true;
            }
            ClientRecord clientRecord = new Client1Record(userRecord, client, packageName);

            if (newUser) {
                mUserRecords.put(userId, userRecord);
                initializeUserLocked(userRecord);
            }

            userRecord.mClientRecords.add(clientRecord);
            mAllClientRecords.put(binder, clientRecord);
        }
    }

    private void unregisterClient1Locked(IMediaRouterClient client) {
        ClientRecord clientRecord = mAllClientRecords.remove(client.asBinder());
        if (clientRecord != null) {
            UserRecord userRecord = clientRecord.mUserRecord;
            userRecord.mClientRecords.remove(clientRecord);
            disposeUserIfNeededLocked(userRecord);
        }
    }

    final class UserRecord {
        public final int mUserId;
        //TODO: make records private for thread-safety
        final ArrayList<ClientRecord> mClientRecords = new ArrayList<>();
        final ArrayList<ManagerRecord> mManagerRecords = new ArrayList<>();
        final UserHandler mHandler;

        UserRecord(int userId) {
            mUserId = userId;
            mHandler = new UserHandler(MediaRouter2ServiceImpl.this, this);
        }

        ClientRecord findClientRecordLocked(String packageName) {
            for (ClientRecord clientRecord : mClientRecords) {
                if (TextUtils.equals(clientRecord.mPackageName, packageName)) {
                    return clientRecord;
                }
            }
            return null;
        }
    }

    class ClientRecord {
        public final UserRecord mUserRecord;
        public final String mPackageName;
        public final List<Integer> mSelectRouteSequenceNumbers;
        public List<String> mControlCategories;
        public MediaRoute2Info mSelectingRoute;
        public MediaRoute2Info mSelectedRoute;

        ClientRecord(UserRecord userRecord, String packageName) {
            mUserRecord = userRecord;
            mPackageName = packageName;
            mSelectRouteSequenceNumbers = new ArrayList<>();
            mControlCategories = Collections.emptyList();
        }
    }

    final class Client1Record extends ClientRecord {
        public final IMediaRouterClient mClient;

        Client1Record(UserRecord userRecord, IMediaRouterClient client,
                String packageName) {
            super(userRecord, packageName);
            mClient = client;
        }
    }

    final class Client2Record extends ClientRecord
            implements IBinder.DeathRecipient {
        public final IMediaRouter2Client mClient;
        public final int mUid;
        public final int mPid;
        public final boolean mTrusted;

        Client2Record(UserRecord userRecord, IMediaRouter2Client client,
                int uid, int pid, String packageName, boolean trusted) {
            super(userRecord, packageName);
            mClient = client;
            mUid = uid;
            mPid = pid;
            mTrusted = trusted;
        }

        public void dispose() {
            mClient.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            clientDied(this);
        }
    }

    final class ManagerRecord implements IBinder.DeathRecipient {
        public final UserRecord mUserRecord;
        public final IMediaRouter2Manager mManager;
        public final int mUid;
        public final int mPid;
        public final String mPackageName;
        public final boolean mTrusted;

        ManagerRecord(UserRecord userRecord, IMediaRouter2Manager manager,
                int uid, int pid, String packageName, boolean trusted) {
            mUserRecord = userRecord;
            mManager = manager;
            mUid = uid;
            mPid = pid;
            mPackageName = packageName;
            mTrusted = trusted;
        }

        public void dispose() {
            mManager.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            managerDied(this);
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + this);

            final String indent = prefix + "  ";
            pw.println(indent + "mTrusted=" + mTrusted);
        }

        @Override
        public String toString() {
            return "Manager " + mPackageName + " (pid " + mPid + ")";
        }
    }

    static final class UserHandler extends Handler implements
            MediaRoute2ProviderWatcher.Callback,
            MediaRoute2Provider.Callback {

        private final WeakReference<MediaRouter2ServiceImpl> mServiceRef;
        private final UserRecord mUserRecord;
        private final MediaRoute2ProviderWatcher mWatcher;

        //TODO: Make this thread-safe.
        private final SystemMediaRoute2Provider mSystemProvider;
        private final ArrayList<MediaRoute2Provider> mMediaProviders =
                new ArrayList<>();
        private final List<MediaRoute2ProviderInfo> mProviderInfos = new ArrayList<>();

        private boolean mRunning;
        private boolean mProviderInfosUpdateScheduled;

        UserHandler(MediaRouter2ServiceImpl service, UserRecord userRecord) {
            super(Looper.getMainLooper(), null, true);
            mServiceRef = new WeakReference<>(service);
            mUserRecord = userRecord;
            mSystemProvider = new SystemMediaRoute2Provider(service.mContext, this);
            mMediaProviders.add(mSystemProvider);
            mWatcher = new MediaRoute2ProviderWatcher(service.mContext, this,
                    this, mUserRecord.mUserId);
        }

        private void start() {
            if (!mRunning) {
                mRunning = true;
                mWatcher.start();
            }
        }

        private void stop() {
            if (mRunning) {
                mRunning = false;
                //TODO: may unselect routes
                mWatcher.stop(); // also stops all providers
            }
        }

        @Override
        public void onAddProvider(MediaRoute2ProviderProxy provider) {
            provider.setCallback(this);
            mMediaProviders.add(provider);
        }

        @Override
        public void onRemoveProvider(MediaRoute2ProviderProxy provider) {
            mMediaProviders.remove(provider);
        }

        @Override
        public void onProviderStateChanged(@NonNull MediaRoute2Provider provider) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::updateProvider, this, provider));
        }

        // TODO: When introducing MediaRoute2ProviderService#sendControlHints(),
        // Make this method to be called.
        public void onRouteSelectionRequestHandled(@NonNull MediaRoute2ProviderProxy provider,
                String clientPackageName, MediaRoute2Info route, Bundle controlHints, int seq) {
            sendMessage(PooledLambda.obtainMessage(
                    UserHandler::updateSelectedRoute, this, provider, clientPackageName, route,
                    controlHints, seq));
        }

        private void updateProvider(MediaRoute2Provider provider) {
            int providerIndex = getProviderInfoIndex(provider.getUniqueId());
            MediaRoute2ProviderInfo providerInfo = provider.getProviderInfo();
            MediaRoute2ProviderInfo prevInfo =
                    (providerIndex < 0) ? null : mProviderInfos.get(providerIndex);

            if (Objects.equals(prevInfo, providerInfo)) return;

            if (prevInfo == null) {
                mProviderInfos.add(providerInfo);
                Collection<MediaRoute2Info> addedRoutes = providerInfo.getRoutes();
                if (addedRoutes.size() > 0) {
                    sendMessage(PooledLambda.obtainMessage(UserHandler::notifyRoutesAddedToClients,
                            this, getClients(), new ArrayList<>(addedRoutes)));
                }
            } else if (providerInfo == null) {
                mProviderInfos.remove(prevInfo);
                Collection<MediaRoute2Info> removedRoutes = prevInfo.getRoutes();
                if (removedRoutes.size() > 0) {
                    sendMessage(PooledLambda.obtainMessage(
                            UserHandler::notifyRoutesRemovedToClients,
                            this, getClients(), new ArrayList<>(removedRoutes)));
                }
            } else {
                mProviderInfos.set(providerIndex, providerInfo);
                List<MediaRoute2Info> addedRoutes = new ArrayList<>();
                List<MediaRoute2Info> removedRoutes = new ArrayList<>();
                List<MediaRoute2Info> changedRoutes = new ArrayList<>();

                final Collection<MediaRoute2Info> currentRoutes = providerInfo.getRoutes();
                final Set<String> updatedRouteIds = new HashSet<>();

                for (MediaRoute2Info route : currentRoutes) {
                    if (!route.isValid()) {
                        Slog.w(TAG, "Ignoring invalid route : " + route);
                        continue;
                    }
                    MediaRoute2Info prevRoute = prevInfo.getRoute(route.getId());

                    if (prevRoute != null) {
                        if (!Objects.equals(prevRoute, route)) {
                            changedRoutes.add(route);
                        }
                        updatedRouteIds.add(route.getId());
                    } else {
                        addedRoutes.add(route);
                    }
                }

                for (MediaRoute2Info prevRoute : prevInfo.getRoutes()) {
                    if (!updatedRouteIds.contains(prevRoute.getId())) {
                        removedRoutes.add(prevRoute);
                    }
                }

                List<IMediaRouter2Client> clients = getClients();
                List<IMediaRouter2Manager> managers = getManagers();
                if (addedRoutes.size() > 0) {
                    notifyRoutesAddedToClients(clients, addedRoutes);
                    notifyRoutesAddedToManagers(managers, addedRoutes);
                }
                if (removedRoutes.size() > 0) {
                    notifyRoutesRemovedToClients(clients, removedRoutes);
                    notifyRoutesRemovedToManagers(managers, removedRoutes);
                }
                if (changedRoutes.size() > 0) {
                    notifyRoutesChangedToClients(clients, changedRoutes);
                    notifyRoutesChangedToManagers(managers, changedRoutes);
                }
            }
        }

        private int getProviderInfoIndex(String providerId) {
            for (int i = 0; i < mProviderInfos.size(); i++) {
                MediaRoute2ProviderInfo providerInfo = mProviderInfos.get(i);
                if (TextUtils.equals(providerInfo.getUniqueId(), providerId)) {
                    return i;
                }
            }
            return -1;
        }

        private void updateSelectedRoute(MediaRoute2ProviderProxy provider,
                String clientPackageName, MediaRoute2Info selectedRoute, Bundle controlHints,
                int seq) {
            if (selectedRoute == null
                    || !TextUtils.equals(clientPackageName, selectedRoute.getClientPackageName())) {
                Log.w(TAG, "Ignoring route selection which has non-matching clientPackageName.");
                return;
            }

            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return;
            }

            ClientRecord clientRecord;
            synchronized (service.mLock) {
                clientRecord = mUserRecord.findClientRecordLocked(clientPackageName);
            }
            if (!(clientRecord instanceof Client2Record)) {
                Log.w(TAG, "Ignoring route selection for unknown client.");
                unselectRoute(clientPackageName, selectedRoute);
                return;
            }

            if (clientRecord.mSelectingRoute == null || !TextUtils.equals(
                    clientRecord.mSelectingRoute.getUniqueId(), selectedRoute.getUniqueId())) {
                Log.w(TAG, "Ignoring invalid updateSelectedRoute call. selectingRoute="
                        + clientRecord.mSelectingRoute + " route=" + selectedRoute);
                unselectRoute(clientPackageName, selectedRoute);
                return;
            }
            clientRecord.mSelectingRoute = null;
            clientRecord.mSelectedRoute = selectedRoute;

            notifyRouteSelectedToClient(((Client2Record) clientRecord).mClient,
                    selectedRoute,
                    MediaRouter2.SELECT_REASON_USER_SELECTED,
                    controlHints);
            updateClientUsage(clientRecord);

            // Remove the fallback route selection message.
            removeMessages(seq);
        }

        private void handleRouteSelectionTimeout(String clientPackageName,
                MediaRoute2Info selectingRoute) {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return;
            }

            ClientRecord clientRecord;
            synchronized (service.mLock) {
                clientRecord = mUserRecord.findClientRecordLocked(clientPackageName);
            }
            if (!(clientRecord instanceof Client2Record)) {
                Log.w(TAG, "Ignoring fallback route selection for unknown client.");
                return;
            }

            if (clientRecord.mSelectingRoute == null || !TextUtils.equals(
                    clientRecord.mSelectingRoute.getUniqueId(), selectingRoute.getUniqueId())) {
                Log.w(TAG, "Ignoring invalid selectFallbackRoute call. "
                        + "Current selectingRoute=" + clientRecord.mSelectingRoute
                        + " , original selectingRoute=" + selectingRoute);
                return;
            }

            clientRecord.mSelectingRoute = null;
            // TODO: When the default route is introduced, make mSelectedRoute always non-null.
            MediaRoute2Info fallbackRoute = null;
            clientRecord.mSelectedRoute = fallbackRoute;

            notifyRouteSelectedToClient(((Client2Record) clientRecord).mClient,
                    fallbackRoute,
                    MediaRouter2.SELECT_REASON_FALLBACK,
                    Bundle.EMPTY /* controlHints */);
            updateClientUsage(clientRecord);
        }

        private void requestSelectRoute(String clientPackageName, MediaRoute2Info route, int seq) {
            if (route != null) {
                MediaRoute2Provider provider = findProvider(route.getProviderId());
                if (provider == null) {
                    Slog.w(TAG, "Ignoring to select route of unknown provider " + route);
                } else {
                    provider.requestSelectRoute(clientPackageName, route.getId(), seq);
                }
            }
        }

        private void unselectRoute(String clientPackageName, MediaRoute2Info route) {
            if (route != null) {
                MediaRoute2Provider provider = findProvider(route.getProviderId());
                if (provider == null) {
                    Slog.w(TAG, "Ignoring to unselect route of unknown provider " + route);
                } else {
                    provider.unselectRoute(clientPackageName, route.getId());
                }
            }
        }

        private void sendControlRequest(MediaRoute2Info route, Intent request) {
            final MediaRoute2Provider provider = findProvider(route.getProviderId());
            if (provider != null) {
                provider.sendControlRequest(route, request);
            }
        }

        private void requestSetVolume(MediaRoute2Info route, int volume) {
            final MediaRoute2Provider provider = findProvider(route.getProviderId());
            if (provider != null) {
                provider.requestSetVolume(route, volume);
            }
        }

        private void requestUpdateVolume(MediaRoute2Info route, int delta) {
            final MediaRoute2Provider provider = findProvider(route.getProviderId());
            if (provider != null) {
                provider.requestUpdateVolume(route, delta);
            }
        }

        private List<IMediaRouter2Client> getClients() {
            final List<IMediaRouter2Client> clients = new ArrayList<>();
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return clients;
            }
            synchronized (service.mLock) {
                for (ClientRecord clientRecord : mUserRecord.mClientRecords) {
                    if (clientRecord instanceof Client2Record) {
                        clients.add(((Client2Record) clientRecord).mClient);
                    }
                }
            }
            return clients;
        }

        private List<IMediaRouter2Manager> getManagers() {
            final List<IMediaRouter2Manager> managers = new ArrayList<>();
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return managers;
            }
            synchronized (service.mLock) {
                for (ManagerRecord managerRecord : mUserRecord.mManagerRecords) {
                    managers.add(managerRecord.mManager);
                }
            }
            return managers;
        }

        private void notifyRoutesToClient(IMediaRouter2Client client) {
            List<MediaRoute2Info> routes = new ArrayList<>();
            for (MediaRoute2ProviderInfo providerInfo : mProviderInfos) {
                routes.addAll(providerInfo.getRoutes());
            }
            if (routes.size() == 0) {
                return;
            }
            try {
                client.notifyRoutesAdded(routes);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify all routes. Client probably died.", ex);
            }
        }

        private void notifyRouteSelectedToClient(IMediaRouter2Client client,
                MediaRoute2Info route, int reason, Bundle controlHints) {
            try {
                client.notifyRouteSelected(route, reason, controlHints);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify routes selected. Client probably died.", ex);
            }
        }

        private void notifyRoutesAddedToClients(List<IMediaRouter2Client> clients,
                List<MediaRoute2Info> routes) {
            for (IMediaRouter2Client client : clients) {
                try {
                    client.notifyRoutesAdded(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes added. Client probably died.", ex);
                }
            }
        }

        private void notifyRoutesRemovedToClients(List<IMediaRouter2Client> clients,
                List<MediaRoute2Info> routes) {
            for (IMediaRouter2Client client : clients) {
                try {
                    client.notifyRoutesRemoved(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes removed. Client probably died.", ex);
                }
            }
        }

        private void notifyRoutesChangedToClients(List<IMediaRouter2Client> clients,
                List<MediaRoute2Info> routes) {
            for (IMediaRouter2Client client : clients) {
                try {
                    client.notifyRoutesChanged(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes changed. Client probably died.", ex);
                }
            }
        }

        private void notifyRoutesToManager(IMediaRouter2Manager manager) {
            List<MediaRoute2Info> routes = new ArrayList<>();
            for (MediaRoute2ProviderInfo providerInfo : mProviderInfos) {
                routes.addAll(providerInfo.getRoutes());
            }
            if (routes.size() == 0) {
                return;
            }
            try {
                manager.notifyRoutesAdded(routes);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify all routes. Manager probably died.", ex);
            }
        }

        private void notifyRoutesAddedToManagers(List<IMediaRouter2Manager> managers,
                List<MediaRoute2Info> routes) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRoutesAdded(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes added. Manager probably died.", ex);
                }
            }
        }

        private void notifyRoutesRemovedToManagers(List<IMediaRouter2Manager> managers,
                List<MediaRoute2Info> routes) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRoutesRemoved(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes removed. Manager probably died.", ex);
                }
            }
        }

        private void notifyRoutesChangedToManagers(List<IMediaRouter2Manager> managers,
                List<MediaRoute2Info> routes) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRoutesChanged(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes changed. Manager probably died.", ex);
                }
            }
        }

        private void updateClientUsage(ClientRecord clientRecord) {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return;
            }
            List<IMediaRouter2Manager> managers = new ArrayList<>();
            synchronized (service.mLock) {
                for (ManagerRecord managerRecord : mUserRecord.mManagerRecords) {
                    managers.add(managerRecord.mManager);
                }
            }
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRouteSelected(clientRecord.mPackageName,
                            clientRecord.mSelectedRoute);
                    manager.notifyControlCategoriesChanged(clientRecord.mPackageName,
                            clientRecord.mControlCategories);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to update client usage. Manager probably died.", ex);
                }
            }
        }

        private MediaRoute2Provider findProvider(String providerId) {
            for (MediaRoute2Provider provider : mMediaProviders) {
                if (TextUtils.equals(provider.getUniqueId(), providerId)) {
                    return provider;
                }
            }
            return null;
        }
    }
}
