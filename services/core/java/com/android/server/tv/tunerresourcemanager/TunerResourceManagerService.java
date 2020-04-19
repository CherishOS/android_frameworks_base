/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.tv.tunerresourcemanager;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.tv.TvInputManager;
import android.media.tv.tunerresourcemanager.CasSessionRequest;
import android.media.tv.tunerresourcemanager.IResourcesReclaimListener;
import android.media.tv.tunerresourcemanager.ITunerResourceManager;
import android.media.tv.tunerresourcemanager.ResourceClientProfile;
import android.media.tv.tunerresourcemanager.TunerDemuxRequest;
import android.media.tv.tunerresourcemanager.TunerDescramblerRequest;
import android.media.tv.tunerresourcemanager.TunerFrontendInfo;
import android.media.tv.tunerresourcemanager.TunerFrontendRequest;
import android.media.tv.tunerresourcemanager.TunerLnbRequest;
import android.media.tv.tunerresourcemanager.TunerResourceManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * This class provides a system service that manages the TV tuner resources.
 *
 * @hide
 */
public class TunerResourceManagerService extends SystemService {
    private static final String TAG = "TunerResourceManagerService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final int INVALID_CLIENT_ID = -1;
    private static final int MAX_CLIENT_PRIORITY = 1000;

    // Map of the registered client profiles
    private Map<Integer, ClientProfile> mClientProfiles = new HashMap<>();
    private int mNextUnusedClientId = 0;

    // Map of the current available frontend resources
    private Map<Integer, FrontendResource> mFrontendResources = new HashMap<>();
    // Map of the current available lnb resources
    private Map<Integer, LnbResource> mLnbResources = new HashMap<>();

    @GuardedBy("mLock")
    private Map<Integer, ResourcesReclaimListenerRecord> mListeners = new HashMap<>();

    private TvInputManager mManager;
    private UseCasePriorityHints mPriorityCongfig = new UseCasePriorityHints();

    // An internal resource request count to help generate resource handle.
    private int mResourceRequestCount = 0;

    // Used to synchronize the access to the service.
    private final Object mLock = new Object();

    public TunerResourceManagerService(@Nullable Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        onStart(false /*isForTesting*/);
    }

    @VisibleForTesting
    protected void onStart(boolean isForTesting) {
        if (!isForTesting) {
            publishBinderService(Context.TV_TUNER_RESOURCE_MGR_SERVICE, new BinderService());
        }
        mManager = (TvInputManager) getContext().getSystemService(Context.TV_INPUT_SERVICE);
        mPriorityCongfig.parse();
    }

    private final class BinderService extends ITunerResourceManager.Stub {
        @Override
        public void registerClientProfile(@NonNull ResourceClientProfile profile,
                @NonNull IResourcesReclaimListener listener, @NonNull int[] clientId)
                throws RemoteException {
            enforceTrmAccessPermission("registerClientProfile");
            enforceTunerAccessPermission("registerClientProfile");
            if (profile == null) {
                throw new RemoteException("ResourceClientProfile can't be null");
            }

            if (clientId == null) {
                throw new RemoteException("clientId can't be null!");
            }

            if (listener == null) {
                throw new RemoteException("IResourcesReclaimListener can't be null!");
            }

            if (!mPriorityCongfig.isDefinedUseCase(profile.getUseCase())) {
                throw new RemoteException("Use undefined client use case:" + profile.getUseCase());
            }

            synchronized (mLock) {
                registerClientProfileInternal(profile, listener, clientId);
            }
        }

        @Override
        public void unregisterClientProfile(int clientId) throws RemoteException {
            enforceTrmAccessPermission("unregisterClientProfile");
            synchronized (mLock) {
                if (!checkClientExists(clientId)) {
                    Slog.e(TAG, "Unregistering non exists client:" + clientId);
                    return;
                }
                unregisterClientProfileInternal(clientId);
            }
        }

        @Override
        public boolean updateClientPriority(int clientId, int priority, int niceValue) {
            enforceTrmAccessPermission("updateClientPriority");
            synchronized (mLock) {
                return updateClientPriorityInternal(clientId, priority, niceValue);
            }
        }

        @Override
        public void setFrontendInfoList(@NonNull TunerFrontendInfo[] infos) throws RemoteException {
            enforceTrmAccessPermission("setFrontendInfoList");
            if (infos == null) {
                throw new RemoteException("TunerFrontendInfo can't be null");
            }
            synchronized (mLock) {
                setFrontendInfoListInternal(infos);
            }
        }

        @Override
        public void updateCasInfo(int casSystemId, int maxSessionNum) {
            enforceTrmAccessPermission("updateCasInfo");
            if (DEBUG) {
                Slog.d(TAG,
                        "updateCasInfo(casSystemId=" + casSystemId
                                + ", maxSessionNum=" + maxSessionNum + ")");
            }
        }

        @Override
        public void setLnbInfoList(int[] lnbIds) throws RemoteException {
            enforceTrmAccessPermission("setLnbInfoList");
            if (lnbIds == null) {
                throw new RemoteException("Lnb id list can't be null");
            }
            synchronized (mLock) {
                setLnbInfoListInternal(lnbIds);
            }
        }

        @Override
        public boolean requestFrontend(@NonNull TunerFrontendRequest request,
                @NonNull int[] frontendHandle) throws RemoteException {
            enforceTunerAccessPermission("requestFrontend");
            enforceTrmAccessPermission("requestFrontend");
            if (frontendHandle == null) {
                throw new RemoteException("frontendHandle can't be null");
            }
            synchronized (mLock) {
                try {
                    return requestFrontendInternal(request, frontendHandle);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }

        @Override
        public void shareFrontend(int selfClientId, int targetClientId) {
            enforceTunerAccessPermission("shareFrontend");
            enforceTrmAccessPermission("shareFrontend");
            if (DEBUG) {
                Slog.d(TAG, "shareFrontend from " + selfClientId + " with " + targetClientId);
            }
        }

        @Override
        public boolean requestDemux(@NonNull TunerDemuxRequest request,
                    @NonNull int[] demuxHandle)  throws RemoteException {
            enforceTunerAccessPermission("requestDemux");
            enforceTrmAccessPermission("requestDemux");
            if (demuxHandle == null) {
                throw new RemoteException("demuxHandle can't be null");
            }
            synchronized (mLock) {
                return requestDemuxInternal(request, demuxHandle);
            }
        }

        @Override
        public boolean requestDescrambler(@NonNull TunerDescramblerRequest request,
                    @NonNull int[] descrambleHandle) throws RemoteException {
            enforceDescramblerAccessPermission("requestDescrambler");
            enforceTrmAccessPermission("requestDescrambler");
            if (descrambleHandle == null) {
                throw new RemoteException("descrambleHandle can't be null");
            }
            synchronized (mLock) {
                return requestDescramblerInternal(request, descrambleHandle);
            }
        }

        @Override
        public boolean requestCasSession(
                @NonNull CasSessionRequest request, @NonNull int[] sessionResourceHandle) {
            enforceTrmAccessPermission("requestCasSession");
            if (DEBUG) {
                Slog.d(TAG, "requestCasSession(request=" + request + ")");
            }

            return true;
        }

        @Override
        public boolean requestLnb(@NonNull TunerLnbRequest request, @NonNull int[] lnbHandle)
                throws RemoteException {
            enforceTunerAccessPermission("requestLnb");
            enforceTrmAccessPermission("requestLnb");
            if (lnbHandle == null) {
                throw new RemoteException("lnbHandle can't be null");
            }
            synchronized (mLock) {
                try {
                    return requestLnbInternal(request, lnbHandle);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }

        @Override
        public void releaseFrontend(int frontendHandle, int clientId) throws RemoteException {
            enforceTunerAccessPermission("releaseFrontend");
            enforceTrmAccessPermission("releaseFrontend");
            if (!validateResourceHandle(TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND,
                    frontendHandle)) {
                throw new RemoteException("frontendHandle can't be invalid");
            }
            int frontendId = getResourceIdFromHandle(frontendHandle);
            FrontendResource fe = getFrontendResource(frontendId);
            if (fe == null) {
                throw new RemoteException("Releasing frontend does not exist.");
            }
            if (fe.getOwnerClientId() != clientId) {
                throw new RemoteException("Client is not the current owner of the releasing fe.");
            }
            synchronized (mLock) {
                releaseFrontendInternal(fe);
            }
        }

        @Override
        public void releaseDemux(int demuxHandle, int clientId) {
            enforceTunerAccessPermission("releaseDemux");
            enforceTrmAccessPermission("releaseDemux");
            if (DEBUG) {
                Slog.d(TAG, "releaseDemux(demuxHandle=" + demuxHandle + ")");
            }
        }

        @Override
        public void releaseDescrambler(int descramblerHandle, int clientId) {
            enforceTunerAccessPermission("releaseDescrambler");
            enforceTrmAccessPermission("releaseDescrambler");
            if (DEBUG) {
                Slog.d(TAG, "releaseDescrambler(descramblerHandle=" + descramblerHandle + ")");
            }
        }

        @Override
        public void releaseCasSession(int sessionResourceId, int clientId) {
            enforceTrmAccessPermission("releaseCasSession");
            if (DEBUG) {
                Slog.d(TAG, "releaseCasSession(sessionResourceId=" + sessionResourceId + ")");
            }
        }

        @Override
        public void releaseLnb(int lnbHandle, int clientId) throws RemoteException {
            enforceTunerAccessPermission("releaseLnb");
            enforceTrmAccessPermission("releaseLnb");
            if (!validateResourceHandle(TunerResourceManager.TUNER_RESOURCE_TYPE_LNB, lnbHandle)) {
                throw new RemoteException("lnbHandle can't be invalid");
            }
            int lnbId = getResourceIdFromHandle(lnbHandle);
            LnbResource lnb = getLnbResource(lnbId);
            if (lnb == null) {
                throw new RemoteException("Releasing lnb does not exist.");
            }
            if (lnb.getOwnerClientId() != clientId) {
                throw new RemoteException("Client is not the current owner of the releasing lnb.");
            }
            synchronized (mLock) {
                releaseLnbInternal(lnb);
            }
        }

        @Override
        public boolean isHigherPriority(
                ResourceClientProfile challengerProfile, ResourceClientProfile holderProfile) {
            enforceTrmAccessPermission("isHigherPriority");
            if (DEBUG) {
                Slog.d(TAG,
                        "isHigherPriority(challengerProfile=" + challengerProfile
                                + ", holderProfile=" + challengerProfile + ")");
            }
            return true;
        }
    }

    @VisibleForTesting
    protected void registerClientProfileInternal(ResourceClientProfile profile,
            IResourcesReclaimListener listener, int[] clientId) {
        if (DEBUG) {
            Slog.d(TAG, "registerClientProfile(clientProfile=" + profile + ")");
        }

        clientId[0] = INVALID_CLIENT_ID;
        if (mManager == null) {
            Slog.e(TAG, "TvInputManager is null. Can't register client profile.");
            return;
        }
        // TODO tell if the client already exists
        clientId[0] = mNextUnusedClientId++;

        int pid = profile.getTvInputSessionId() == null
                ? Binder.getCallingPid() /*callingPid*/
                : mManager.getClientPid(profile.getTvInputSessionId()); /*tvAppId*/

        ClientProfile clientProfile = new ClientProfile.Builder(clientId[0])
                                              .tvInputSessionId(profile.getTvInputSessionId())
                                              .useCase(profile.getUseCase())
                                              .processId(pid)
                                              .build();
        clientProfile.setPriority(getClientPriority(profile.getUseCase(), pid));

        addClientProfile(clientId[0], clientProfile, listener);
    }

    @VisibleForTesting
    protected void unregisterClientProfileInternal(int clientId) {
        if (DEBUG) {
            Slog.d(TAG, "unregisterClientProfile(clientId=" + clientId + ")");
        }
        removeClientProfile(clientId);
    }

    @VisibleForTesting
    protected boolean updateClientPriorityInternal(int clientId, int priority, int niceValue) {
        if (DEBUG) {
            Slog.d(TAG,
                    "updateClientPriority(clientId=" + clientId + ", priority=" + priority
                            + ", niceValue=" + niceValue + ")");
        }

        ClientProfile profile = getClientProfile(clientId);
        if (profile == null) {
            Slog.e(TAG,
                    "Can not find client profile with id " + clientId
                            + " when trying to update the client priority.");
            return false;
        }

        profile.setPriority(priority);
        profile.setNiceValue(niceValue);

        return true;
    }

    @VisibleForTesting
    protected void setFrontendInfoListInternal(TunerFrontendInfo[] infos) {
        if (DEBUG) {
            Slog.d(TAG, "updateFrontendInfo:");
            for (int i = 0; i < infos.length; i++) {
                Slog.d(TAG, infos[i].toString());
            }
        }

        // A set to record the frontends pending on updating. Ids will be removed
        // from this set once its updating finished. Any frontend left in this set when all
        // the updates are done will be removed from mFrontendResources.
        Set<Integer> updatingFrontendIds = new HashSet<>(getFrontendResources().keySet());

        // Update frontendResources map and other mappings accordingly
        for (int i = 0; i < infos.length; i++) {
            if (getFrontendResource(infos[i].getId()) != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Frontend id=" + infos[i].getId() + "exists.");
                }
                updatingFrontendIds.remove(infos[i].getId());
            } else {
                // Add a new fe resource
                FrontendResource newFe = new FrontendResource.Builder(infos[i].getId())
                                                 .type(infos[i].getFrontendType())
                                                 .exclusiveGroupId(infos[i].getExclusiveGroupId())
                                                 .build();
                addFrontendResource(newFe);
            }
        }

        for (int removingId : updatingFrontendIds) {
            // update the exclusive group id member list
            removeFrontendResource(removingId);
        }
    }

    @VisibleForTesting
    protected void setLnbInfoListInternal(int[] lnbIds) {
        if (DEBUG) {
            for (int i = 0; i < lnbIds.length; i++) {
                Slog.d(TAG, "updateLnbInfo(lnbId=" + lnbIds[i] + ")");
            }
        }

        // A set to record the Lnbs pending on updating. Ids will be removed
        // from this set once its updating finished. Any lnb left in this set when all
        // the updates are done will be removed from mLnbResources.
        Set<Integer> updatingLnbIds = new HashSet<>(getLnbResources().keySet());

        // Update lnbResources map and other mappings accordingly
        for (int i = 0; i < lnbIds.length; i++) {
            if (getLnbResource(lnbIds[i]) != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Lnb id=" + lnbIds[i] + "exists.");
                }
                updatingLnbIds.remove(lnbIds[i]);
            } else {
                // Add a new lnb resource
                LnbResource newLnb = new LnbResource.Builder(lnbIds[i]).build();
                addLnbResource(newLnb);
            }
        }

        for (int removingId : updatingLnbIds) {
            removeLnbResource(removingId);
        }
    }

    @VisibleForTesting
    protected boolean requestFrontendInternal(TunerFrontendRequest request, int[] frontendHandle)
            throws RemoteException {
        if (DEBUG) {
            Slog.d(TAG, "requestFrontend(request=" + request + ")");
        }

        frontendHandle[0] = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        if (!checkClientExists(request.getClientId())) {
            Slog.e(TAG, "Request frontend from unregistered client:" + request.getClientId());
            return false;
        }
        ClientProfile requestClient = getClientProfile(request.getClientId());
        int grantingFrontendId = -1;
        int inUseLowestPriorityFrId = -1;
        // Priority max value is 1000
        int currentLowestPriority = MAX_CLIENT_PRIORITY + 1;
        for (FrontendResource fr : getFrontendResources().values()) {
            if (fr.getType() == request.getFrontendType()) {
                if (!fr.isInUse()) {
                    // Grant unused frontend with no exclusive group members first.
                    if (fr.getExclusiveGroupMemberFeIds().isEmpty()) {
                        grantingFrontendId = fr.getId();
                        break;
                    } else if (grantingFrontendId < 0) {
                        // Grant the unused frontend with lower id first if all the unused
                        // frontends have exclusive group members.
                        grantingFrontendId = fr.getId();
                    }
                } else if (grantingFrontendId < 0) {
                    // Record the frontend id with the lowest client priority among all the
                    // in use frontends when no available frontend has been found.
                    int priority = getOwnerClientPriority(fr);
                    if (currentLowestPriority > priority) {
                        inUseLowestPriorityFrId = fr.getId();
                        currentLowestPriority = priority;
                    }
                }
            }
        }

        // Grant frontend when there is unused resource.
        if (grantingFrontendId > -1) {
            frontendHandle[0] = generateResourceHandle(
                    TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND, grantingFrontendId);
            updateFrontendClientMappingOnNewGrant(grantingFrontendId, request.getClientId());
            return true;
        }

        // When all the resources are occupied, grant the lowest priority resource if the
        // request client has higher priority.
        if (inUseLowestPriorityFrId > -1 && (requestClient.getPriority() > currentLowestPriority)) {
            if (!reclaimResource(getFrontendResource(inUseLowestPriorityFrId).getOwnerClientId(),
                    TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND)) {
                return false;
            }
            frontendHandle[0] = generateResourceHandle(
                    TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND, inUseLowestPriorityFrId);
            updateFrontendClientMappingOnNewGrant(inUseLowestPriorityFrId, request.getClientId());
            return true;
        }

        return false;
    }

    @VisibleForTesting
    protected boolean requestLnbInternal(TunerLnbRequest request, int[] lnbHandle)
            throws RemoteException {
        if (DEBUG) {
            Slog.d(TAG, "requestLnb(request=" + request + ")");
        }

        lnbHandle[0] = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        if (!checkClientExists(request.getClientId())) {
            Slog.e(TAG, "Request lnb from unregistered client:" + request.getClientId());
            return false;
        }
        ClientProfile requestClient = getClientProfile(request.getClientId());
        int grantingLnbId = -1;
        int inUseLowestPriorityLnbId = -1;
        // Priority max value is 1000
        int currentLowestPriority = MAX_CLIENT_PRIORITY + 1;
        for (LnbResource lnb : getLnbResources().values()) {
            if (!lnb.isInUse()) {
                // Grant the unused lnb with lower id first
                grantingLnbId = lnb.getId();
                break;
            } else {
                // Record the lnb id with the lowest client priority among all the
                // in use lnb when no available lnb has been found.
                int priority = getOwnerClientPriority(lnb);
                if (currentLowestPriority > priority) {
                    inUseLowestPriorityLnbId = lnb.getId();
                    currentLowestPriority = priority;
                }
            }
        }

        // Grant Lnb when there is unused resource.
        if (grantingLnbId > -1) {
            lnbHandle[0] = generateResourceHandle(
                    TunerResourceManager.TUNER_RESOURCE_TYPE_LNB, grantingLnbId);
            updateLnbClientMappingOnNewGrant(grantingLnbId, request.getClientId());
            return true;
        }

        // When all the resources are occupied, grant the lowest priority resource if the
        // request client has higher priority.
        if (inUseLowestPriorityLnbId > -1
                && (requestClient.getPriority() > currentLowestPriority)) {
            if (!reclaimResource(getLnbResource(inUseLowestPriorityLnbId).getOwnerClientId(),
                    TunerResourceManager.TUNER_RESOURCE_TYPE_LNB)) {
                return false;
            }
            lnbHandle[0] = generateResourceHandle(
                    TunerResourceManager.TUNER_RESOURCE_TYPE_LNB, inUseLowestPriorityLnbId);
            updateLnbClientMappingOnNewGrant(inUseLowestPriorityLnbId, request.getClientId());
            return true;
        }

        return false;
    }

    @VisibleForTesting
    void releaseFrontendInternal(FrontendResource fe) {
        if (DEBUG) {
            Slog.d(TAG, "releaseFrontend(id=" + fe.getId() + ")");
        }
        updateFrontendClientMappingOnRelease(fe);
    }

    @VisibleForTesting
    void releaseLnbInternal(LnbResource lnb) {
        if (DEBUG) {
            Slog.d(TAG, "releaseLnb(lnbId=" + lnb.getId() + ")");
        }
        updateLnbClientMappingOnRelease(lnb);
    }

    @VisibleForTesting
    boolean requestDemuxInternal(TunerDemuxRequest request, int[] demuxHandle) {
        if (DEBUG) {
            Slog.d(TAG, "requestDemux(request=" + request + ")");
        }
        // There are enough Demux resources, so we don't manage Demux in R.
        demuxHandle[0] = generateResourceHandle(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX, 0);
        return true;
    }

    @VisibleForTesting
    boolean requestDescramblerInternal(TunerDescramblerRequest request, int[] descramblerHandle) {
        if (DEBUG) {
            Slog.d(TAG, "requestDescrambler(request=" + request + ")");
        }
        // There are enough Descrambler resources, so we don't manage Descrambler in R.
        descramblerHandle[0] =
                generateResourceHandle(TunerResourceManager.TUNER_RESOURCE_TYPE_DESCRAMBLER, 0);
        return true;
    }

    @VisibleForTesting
    protected class ResourcesReclaimListenerRecord implements IBinder.DeathRecipient {
        private final IResourcesReclaimListener mListener;
        private final int mClientId;

        public ResourcesReclaimListenerRecord(IResourcesReclaimListener listener, int clientId) {
            mListener = listener;
            mClientId = clientId;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                removeClientProfile(mClientId);
            }
        }

        public int getId() {
            return mClientId;
        }

        public IResourcesReclaimListener getListener() {
            return mListener;
        }
    }

    private void addResourcesReclaimListener(int clientId, IResourcesReclaimListener listener) {
        if (listener == null) {
            if (DEBUG) {
                Slog.w(TAG, "Listener is null when client " + clientId + " registered!");
            }
            return;
        }

        ResourcesReclaimListenerRecord record =
                new ResourcesReclaimListenerRecord(listener, clientId);

        try {
            listener.asBinder().linkToDeath(record, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Listener already died.");
            return;
        }

        mListeners.put(clientId, record);
    }

    @VisibleForTesting
    protected boolean reclaimResource(int reclaimingClientId,
            @TunerResourceManager.TunerResourceType int resourceType) {
        if (DEBUG) {
            Slog.d(TAG, "Reclaiming resources because higher priority client request resource type "
                    + resourceType);
        }
        try {
            mListeners.get(reclaimingClientId).getListener().onReclaimResources();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to reclaim resources on client " + reclaimingClientId, e);
            return false;
        }
        ClientProfile profile = getClientProfile(reclaimingClientId);
        reclaimingResourcesFromClient(profile);
        return true;
    }

    @VisibleForTesting
    protected int getClientPriority(int useCase, int pid) {
        if (DEBUG) {
            Slog.d(TAG, "getClientPriority useCase=" + useCase
                    + ", pid=" + pid + ")");
        }

        if (isForeground(pid)) {
            return mPriorityCongfig.getForegroundPriority(useCase);
        }
        return mPriorityCongfig.getBackgroundPriority(useCase);
    }

    @VisibleForTesting
    protected boolean isForeground(int pid) {
        // TODO: how to get fg/bg information from pid
        return true;
    }

    private void updateFrontendClientMappingOnNewGrant(int grantingId, int ownerClientId) {
        FrontendResource grantingFrontend = getFrontendResource(grantingId);
        ClientProfile ownerProfile = getClientProfile(ownerClientId);
        grantingFrontend.setOwner(ownerClientId);
        ownerProfile.useFrontend(grantingId);
        for (int exclusiveGroupMember : grantingFrontend.getExclusiveGroupMemberFeIds()) {
            getFrontendResource(exclusiveGroupMember).setOwner(ownerClientId);
            ownerProfile.useFrontend(exclusiveGroupMember);
        }
    }

    private void updateFrontendClientMappingOnRelease(@NonNull FrontendResource releasingFrontend) {
        ClientProfile ownerProfile = getClientProfile(releasingFrontend.getOwnerClientId());
        releasingFrontend.removeOwner();
        ownerProfile.releaseFrontend(releasingFrontend.getId());
        for (int exclusiveGroupMember : releasingFrontend.getExclusiveGroupMemberFeIds()) {
            getFrontendResource(exclusiveGroupMember).removeOwner();
            ownerProfile.releaseFrontend(exclusiveGroupMember);
        }
    }

    private void updateLnbClientMappingOnNewGrant(int grantingId, int ownerClientId) {
        LnbResource grantingLnb = getLnbResource(grantingId);
        ClientProfile ownerProfile = getClientProfile(ownerClientId);
        grantingLnb.setOwner(ownerClientId);
        ownerProfile.useLnb(grantingId);
    }

    private void updateLnbClientMappingOnRelease(@NonNull LnbResource releasingLnb) {
        ClientProfile ownerProfile = getClientProfile(releasingLnb.getOwnerClientId());
        releasingLnb.removeOwner();
        ownerProfile.releaseLnb(releasingLnb.getId());
    }

    /**
     * Get the owner client's priority from the resource id.
     *
     * @param resource a in use tuner resource.
     * @return the priority of the owner client of the resource.
     */
    private int getOwnerClientPriority(TunerResourceBasic resource) {
        return getClientProfile(resource.getOwnerClientId()).getPriority();
    }

    @VisibleForTesting
    @Nullable
    protected FrontendResource getFrontendResource(int frontendId) {
        return mFrontendResources.get(frontendId);
    }

    @VisibleForTesting
    protected Map<Integer, FrontendResource> getFrontendResources() {
        return mFrontendResources;
    }

    private void addFrontendResource(FrontendResource newFe) {
        // Update the exclusive group member list in all the existing Frontend resource
        for (FrontendResource fe : getFrontendResources().values()) {
            if (fe.getExclusiveGroupId() == newFe.getExclusiveGroupId()) {
                newFe.addExclusiveGroupMemberFeId(fe.getId());
                newFe.addExclusiveGroupMemberFeIds(fe.getExclusiveGroupMemberFeIds());
                for (int excGroupmemberFeId : fe.getExclusiveGroupMemberFeIds()) {
                    getFrontendResource(excGroupmemberFeId)
                            .addExclusiveGroupMemberFeId(newFe.getId());
                }
                fe.addExclusiveGroupMemberFeId(newFe.getId());
                break;
            }
        }
        // Update resource list and available id list
        mFrontendResources.put(newFe.getId(), newFe);
    }

    private void removeFrontendResource(int removingId) {
        FrontendResource fe = getFrontendResource(removingId);
        if (fe.isInUse()) {
            releaseFrontendInternal(fe);
        }
        for (int excGroupmemberFeId : fe.getExclusiveGroupMemberFeIds()) {
            getFrontendResource(excGroupmemberFeId)
                    .removeExclusiveGroupMemberFeId(fe.getId());
        }
        mFrontendResources.remove(removingId);
    }

    @VisibleForTesting
    @Nullable
    protected LnbResource getLnbResource(int lnbId) {
        return mLnbResources.get(lnbId);
    }

    @VisibleForTesting
    protected Map<Integer, LnbResource> getLnbResources() {
        return mLnbResources;
    }

    private void addLnbResource(LnbResource newLnb) {
        // Update resource list and available id list
        mLnbResources.put(newLnb.getId(), newLnb);
    }

    private void removeLnbResource(int removingId) {
        LnbResource lnb = getLnbResource(removingId);
        if (lnb.isInUse()) {
            releaseLnbInternal(lnb);
        }
        mLnbResources.remove(removingId);
    }

    @VisibleForTesting
    @Nullable
    protected ClientProfile getClientProfile(int clientId) {
        return mClientProfiles.get(clientId);
    }

    private void addClientProfile(int clientId, ClientProfile profile,
            IResourcesReclaimListener listener) {
        mClientProfiles.put(clientId, profile);
        addResourcesReclaimListener(clientId, listener);
    }

    private void removeClientProfile(int clientId) {
        for (int id : getClientProfile(clientId).getInUseFrontendIds()) {
            getFrontendResource(id).removeOwner();
            for (int groupMemberId : getFrontendResource(id).getExclusiveGroupMemberFeIds()) {
                getFrontendResource(groupMemberId).removeOwner();
            }
        }
        mClientProfiles.remove(clientId);
        mListeners.remove(clientId);
    }

    private void reclaimingResourcesFromClient(ClientProfile profile) {
        for (Integer feId : profile.getInUseFrontendIds()) {
            getFrontendResource(feId).removeOwner();
        }
        for (Integer lnbId : profile.getInUseLnbIds()) {
            getLnbResource(lnbId).removeOwner();
        }
        profile.reclaimAllResources();
    }

    @VisibleForTesting
    protected boolean checkClientExists(int clientId) {
        return mClientProfiles.keySet().contains(clientId);
    }

    private int generateResourceHandle(
            @TunerResourceManager.TunerResourceType int resourceType, int resourceId) {
        return (resourceType & 0x000000ff) << 24
                | (resourceId << 16)
                | (mResourceRequestCount++ & 0xffff);
    }

    @VisibleForTesting
    protected int getResourceIdFromHandle(int resourceHandle) {
        if (resourceHandle == TunerResourceManager.INVALID_RESOURCE_HANDLE) {
            return resourceHandle;
        }
        return (resourceHandle & 0x00ff0000) >> 16;
    }

    private boolean validateResourceHandle(int resourceType, int resourceHandle) {
        if (resourceHandle == TunerResourceManager.INVALID_RESOURCE_HANDLE
                || ((resourceHandle & 0xff000000) >> 24) != resourceType) {
            return false;
        }
        return true;
    }

    private void enforceTrmAccessPermission(String apiName) {
        getContext().enforceCallingPermission("android.permission.TUNER_RESOURCE_ACCESS",
                TAG + ": " + apiName);
    }

    private void enforceTunerAccessPermission(String apiName) {
        getContext().enforceCallingPermission("android.permission.ACCESS_TV_TUNER",
                TAG + ": " + apiName);
    }

    private void enforceDescramblerAccessPermission(String apiName) {
        getContext().enforceCallingPermission("android.permission.ACCESS_TV_DESCRAMBLER",
                TAG + ": " + apiName);
    }
}
