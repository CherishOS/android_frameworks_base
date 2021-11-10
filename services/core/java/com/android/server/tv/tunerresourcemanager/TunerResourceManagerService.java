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
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Context;
import android.media.IResourceManagerService;
import android.media.tv.TvInputManager;
import android.media.tv.tunerresourcemanager.CasSessionRequest;
import android.media.tv.tunerresourcemanager.IResourcesReclaimListener;
import android.media.tv.tunerresourcemanager.ITunerResourceManager;
import android.media.tv.tunerresourcemanager.ResourceClientProfile;
import android.media.tv.tunerresourcemanager.TunerCiCamRequest;
import android.media.tv.tunerresourcemanager.TunerDemuxRequest;
import android.media.tv.tunerresourcemanager.TunerDescramblerRequest;
import android.media.tv.tunerresourcemanager.TunerFrontendInfo;
import android.media.tv.tunerresourcemanager.TunerFrontendRequest;
import android.media.tv.tunerresourcemanager.TunerLnbRequest;
import android.media.tv.tunerresourcemanager.TunerResourceManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class provides a system service that manages the TV tuner resources.
 *
 * @hide
 */
public class TunerResourceManagerService extends SystemService implements IBinder.DeathRecipient {
    private static final String TAG = "TunerResourceManagerService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final int INVALID_CLIENT_ID = -1;
    private static final int MAX_CLIENT_PRIORITY = 1000;

    // Map of the registered client profiles
    private Map<Integer, ClientProfile> mClientProfiles = new HashMap<>();
    private int mNextUnusedClientId = 0;

    // Map of the current available frontend resources
    private Map<Integer, FrontendResource> mFrontendResources = new HashMap<>();
    // Backup Map of the current available frontend resources
    private Map<Integer, FrontendResource> mFrontendResourcesBackup = new HashMap<>();
    // Map of the current available lnb resources
    private Map<Integer, LnbResource> mLnbResources = new HashMap<>();
    // Map of the current available Cas resources
    private Map<Integer, CasResource> mCasResources = new HashMap<>();
    // Map of the current available CiCam resources
    private Map<Integer, CiCamResource> mCiCamResources = new HashMap<>();

    @GuardedBy("mLock")
    private Map<Integer, ResourcesReclaimListenerRecord> mListeners = new HashMap<>();

    private TvInputManager mTvInputManager;
    private ActivityManager mActivityManager;
    private IResourceManagerService mMediaResourceManager;
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
        mTvInputManager = (TvInputManager) getContext().getSystemService(Context.TV_INPUT_SERVICE);
        mActivityManager =
                (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        mPriorityCongfig.parse();

        if (mMediaResourceManager == null) {
            IBinder mediaResourceManagerBinder = getBinderService("media.resource_manager");
            if (mediaResourceManagerBinder == null) {
                Slog.w(TAG, "Resource Manager Service not available.");
                return;
            }
            try {
                mediaResourceManagerBinder.linkToDeath(this, /*flags*/ 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "Could not link to death of native resource manager service.");
                return;
            }
            mMediaResourceManager = IResourceManagerService.Stub.asInterface(
                    mediaResourceManagerBinder);
        }
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

            if (!mPriorityCongfig.isDefinedUseCase(profile.useCase)) {
                throw new RemoteException("Use undefined client use case:" + profile.useCase);
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
        public boolean hasUnusedFrontend(int frontendType) {
            enforceTrmAccessPermission("hasUnusedFrontend");
            synchronized (mLock) {
                return hasUnusedFrontendInternal(frontendType);
            }
        }

        @Override
        public boolean isLowestPriority(int clientId, int frontendType)
                throws RemoteException {
            enforceTrmAccessPermission("isLowestPriority");
            synchronized (mLock) {
                if (!checkClientExists(clientId)) {
                    throw new RemoteException("isLowestPriority called from unregistered client: "
                            + clientId);
                }
                return isLowestPriorityInternal(clientId, frontendType);
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
            synchronized (mLock) {
                updateCasInfoInternal(casSystemId, maxSessionNum);
            }
        }

        @Override
        public void setLnbInfoList(int[] lnbHandles) throws RemoteException {
            enforceTrmAccessPermission("setLnbInfoList");
            if (lnbHandles == null) {
                throw new RemoteException("Lnb handle list can't be null");
            }
            synchronized (mLock) {
                setLnbInfoListInternal(lnbHandles);
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
                if (!checkClientExists(request.clientId)) {
                    throw new RemoteException("Request frontend from unregistered client: "
                            + request.clientId);
                }
                // If the request client is holding or sharing a frontend, throw an exception.
                if (!getClientProfile(request.clientId).getInUseFrontendHandles().isEmpty()) {
                    throw new RemoteException("Release frontend before requesting another one. "
                            + "Client id: " + request.clientId);
                }
                return requestFrontendInternal(request, frontendHandle);
            }
        }

        @Override
        public void shareFrontend(int selfClientId, int targetClientId) throws RemoteException {
            enforceTunerAccessPermission("shareFrontend");
            enforceTrmAccessPermission("shareFrontend");
            synchronized (mLock) {
                if (!checkClientExists(selfClientId)) {
                    throw new RemoteException("Share frontend request from an unregistered client:"
                            + selfClientId);
                }
                if (!checkClientExists(targetClientId)) {
                    throw new RemoteException("Request to share frontend with an unregistered "
                            + "client:" + targetClientId);
                }
                if (getClientProfile(targetClientId).getInUseFrontendHandles().isEmpty()) {
                    throw new RemoteException("Request to share frontend with a client that has no "
                            + "frontend resources. Target client id:" + targetClientId);
                }
                shareFrontendInternal(selfClientId, targetClientId);
            }
        }

        @Override
        public boolean requestDemux(@NonNull TunerDemuxRequest request,
                    @NonNull int[] demuxHandle) throws RemoteException {
            enforceTunerAccessPermission("requestDemux");
            enforceTrmAccessPermission("requestDemux");
            if (demuxHandle == null) {
                throw new RemoteException("demuxHandle can't be null");
            }
            synchronized (mLock) {
                if (!checkClientExists(request.clientId)) {
                    throw new RemoteException("Request demux from unregistered client:"
                            + request.clientId);
                }
                return requestDemuxInternal(request, demuxHandle);
            }
        }

        @Override
        public boolean requestDescrambler(@NonNull TunerDescramblerRequest request,
                    @NonNull int[] descramblerHandle) throws RemoteException {
            enforceDescramblerAccessPermission("requestDescrambler");
            enforceTrmAccessPermission("requestDescrambler");
            if (descramblerHandle == null) {
                throw new RemoteException("descramblerHandle can't be null");
            }
            synchronized (mLock) {
                if (!checkClientExists(request.clientId)) {
                    throw new RemoteException("Request descrambler from unregistered client:"
                            + request.clientId);
                }
                return requestDescramblerInternal(request, descramblerHandle);
            }
        }

        @Override
        public boolean requestCasSession(@NonNull CasSessionRequest request,
                @NonNull int[] casSessionHandle) throws RemoteException {
            enforceTrmAccessPermission("requestCasSession");
            if (casSessionHandle == null) {
                throw new RemoteException("casSessionHandle can't be null");
            }
            synchronized (mLock) {
                if (!checkClientExists(request.clientId)) {
                    throw new RemoteException("Request cas from unregistered client:"
                            + request.clientId);
                }
                return requestCasSessionInternal(request, casSessionHandle);
            }
        }

        @Override
        public boolean requestCiCam(@NonNull TunerCiCamRequest request,
                @NonNull int[] ciCamHandle) throws RemoteException {
            enforceTrmAccessPermission("requestCiCam");
            if (ciCamHandle == null) {
                throw new RemoteException("ciCamHandle can't be null");
            }
            synchronized (mLock) {
                if (!checkClientExists(request.clientId)) {
                    throw new RemoteException("Request ciCam from unregistered client:"
                            + request.clientId);
                }
                return requestCiCamInternal(request, ciCamHandle);
            }
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
                if (!checkClientExists(request.clientId)) {
                    throw new RemoteException("Request lnb from unregistered client:"
                            + request.clientId);
                }
                return requestLnbInternal(request, lnbHandle);
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
            synchronized (mLock) {
                if (!checkClientExists(clientId)) {
                    throw new RemoteException("Release frontend from unregistered client:"
                            + clientId);
                }
                FrontendResource fe = getFrontendResource(frontendHandle);
                if (fe == null) {
                    throw new RemoteException("Releasing frontend does not exist.");
                }
                if (fe.getOwnerClientId() != clientId) {
                    throw new RemoteException(
                            "Client is not the current owner of the releasing fe.");
                }
                releaseFrontendInternal(fe, clientId);
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
        public void releaseCasSession(int casSessionHandle, int clientId) throws RemoteException {
            enforceTrmAccessPermission("releaseCasSession");
            if (!validateResourceHandle(
                    TunerResourceManager.TUNER_RESOURCE_TYPE_CAS_SESSION, casSessionHandle)) {
                throw new RemoteException("casSessionHandle can't be invalid");
            }
            synchronized (mLock) {
                if (!checkClientExists(clientId)) {
                    throw new RemoteException("Release cas from unregistered client:" + clientId);
                }
                int casSystemId = getClientProfile(clientId).getInUseCasSystemId();
                CasResource cas = getCasResource(casSystemId);
                if (cas == null) {
                    throw new RemoteException("Releasing cas does not exist.");
                }
                if (!cas.getOwnerClientIds().contains(clientId)) {
                    throw new RemoteException(
                            "Client is not the current owner of the releasing cas.");
                }
                releaseCasSessionInternal(cas, clientId);
            }
        }

        @Override
        public void releaseCiCam(int ciCamHandle, int clientId) throws RemoteException {
            enforceTrmAccessPermission("releaseCiCam");
            if (!validateResourceHandle(
                    TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND_CICAM, ciCamHandle)) {
                throw new RemoteException("ciCamHandle can't be invalid");
            }
            synchronized (mLock) {
                if (!checkClientExists(clientId)) {
                    throw new RemoteException("Release ciCam from unregistered client:" + clientId);
                }
                int ciCamId = getClientProfile(clientId).getInUseCiCamId();
                if (ciCamId != getResourceIdFromHandle(ciCamHandle)) {
                    throw new RemoteException("The client " + clientId + " is not the owner of "
                            + "the releasing ciCam.");
                }
                CiCamResource ciCam = getCiCamResource(ciCamId);
                if (ciCam == null) {
                    throw new RemoteException("Releasing ciCam does not exist.");
                }
                if (!ciCam.getOwnerClientIds().contains(clientId)) {
                    throw new RemoteException(
                            "Client is not the current owner of the releasing ciCam.");
                }
                releaseCiCamInternal(ciCam, clientId);
            }
        }

        @Override
        public void releaseLnb(int lnbHandle, int clientId) throws RemoteException {
            enforceTunerAccessPermission("releaseLnb");
            enforceTrmAccessPermission("releaseLnb");
            if (!validateResourceHandle(TunerResourceManager.TUNER_RESOURCE_TYPE_LNB, lnbHandle)) {
                throw new RemoteException("lnbHandle can't be invalid");
            }
            synchronized (mLock) {
                if (!checkClientExists(clientId)) {
                    throw new RemoteException("Release lnb from unregistered client:" + clientId);
                }
                LnbResource lnb = getLnbResource(lnbHandle);
                if (lnb == null) {
                    throw new RemoteException("Releasing lnb does not exist.");
                }
                if (lnb.getOwnerClientId() != clientId) {
                    throw new RemoteException("Client is not the current owner "
                            + "of the releasing lnb.");
                }
                releaseLnbInternal(lnb);
            }
        }

        @Override
        public boolean isHigherPriority(
                ResourceClientProfile challengerProfile, ResourceClientProfile holderProfile)
                throws RemoteException {
            enforceTrmAccessPermission("isHigherPriority");
            if (challengerProfile == null || holderProfile == null) {
                throw new RemoteException("Client profiles can't be null.");
            }
            synchronized (mLock) {
                return isHigherPriorityInternal(challengerProfile, holderProfile);
            }
        }

        @Override
        public void storeResourceMap(int resourceType) {
            enforceTrmAccessPermission("storeResourceMap");
            synchronized (mLock) {
                storeResourceMapInternal(resourceType);
            }
        }

        @Override
        public void clearResourceMap(int resourceType) {
            enforceTrmAccessPermission("clearResourceMap");
            synchronized (mLock) {
                clearResourceMapInternal(resourceType);
            }
        }

        @Override
        public void restoreResourceMap(int resourceType) {
            enforceTrmAccessPermission("restoreResourceMap");
            synchronized (mLock) {
                restoreResourceMapInternal(resourceType);
            }
        }

        @Override
        protected void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
            final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

            synchronized (mLock) {
                if (mClientProfiles != null) {
                    pw.println("ClientProfiles:");
                    pw.increaseIndent();
                    for (Map.Entry<Integer, ClientProfile> entry : mClientProfiles.entrySet()) {
                        pw.println(entry.getKey() + " : " + entry.getValue());
                    }
                    pw.decreaseIndent();
                }

                if (mFrontendResources != null) {
                    pw.println("FrontendResources:");
                    pw.increaseIndent();
                    for (Map.Entry<Integer, FrontendResource> entry
                            : mFrontendResources.entrySet()) {
                        pw.println(entry.getKey() + " : " + entry.getValue());
                    }
                    pw.decreaseIndent();
                }

                if (mFrontendResourcesBackup != null) {
                    pw.println("FrontendResourcesBackUp:");
                    pw.increaseIndent();
                    for (Map.Entry<Integer, FrontendResource> entry
                            : mFrontendResourcesBackup.entrySet()) {
                        pw.println(entry.getKey() + " : " + entry.getValue());
                    }
                    pw.decreaseIndent();
                }

                if (mLnbResources != null) {
                    pw.println("LnbResources:");
                    pw.increaseIndent();
                    for (Map.Entry<Integer, LnbResource> entry : mLnbResources.entrySet()) {
                        pw.println(entry.getKey() + " : " + entry.getValue());
                    }
                    pw.decreaseIndent();
                }

                if (mCasResources != null) {
                    pw.println("CasResources:");
                    pw.increaseIndent();
                    for (Map.Entry<Integer, CasResource> entry : mCasResources.entrySet()) {
                        pw.println(entry.getKey() + " : " + entry.getValue());
                    }
                    pw.decreaseIndent();
                }

                if (mCiCamResources != null) {
                    pw.println("CiCamResources:");
                    pw.increaseIndent();
                    for (Map.Entry<Integer, CiCamResource> entry : mCiCamResources.entrySet()) {
                        pw.println(entry.getKey() + " : " + entry.getValue());
                    }
                    pw.decreaseIndent();
                }

                if (mListeners != null) {
                    pw.println("Listners:");
                    pw.increaseIndent();
                    for (Map.Entry<Integer, ResourcesReclaimListenerRecord> entry
                            : mListeners.entrySet()) {
                        pw.println(entry.getKey() + " : " + entry.getValue());
                    }
                    pw.decreaseIndent();
                }
            }
        }

    }

    /**
     * Handle the death of the native resource manager service
     */
    @Override
    public void binderDied() {
        if (DEBUG) {
            Slog.w(TAG, "Native media resource manager service has died");
        }
        synchronized (mLock) {
            mMediaResourceManager = null;
        }
    }

    @VisibleForTesting
    protected void registerClientProfileInternal(ResourceClientProfile profile,
            IResourcesReclaimListener listener, int[] clientId) {
        if (DEBUG) {
            Slog.d(TAG, "registerClientProfile(clientProfile=" + profile + ")");
        }

        clientId[0] = INVALID_CLIENT_ID;
        if (mTvInputManager == null) {
            Slog.e(TAG, "TvInputManager is null. Can't register client profile.");
            return;
        }
        // TODO tell if the client already exists
        clientId[0] = mNextUnusedClientId++;

        int pid = profile.tvInputSessionId == null
                ? Binder.getCallingPid() /*callingPid*/
                : mTvInputManager.getClientPid(profile.tvInputSessionId); /*tvAppId*/

        // Update Media Resource Manager with the tvAppId
        if (profile.tvInputSessionId != null && mMediaResourceManager != null) {
            try {
                mMediaResourceManager.overridePid(Binder.getCallingPid(), pid);
            } catch (RemoteException e) {
                Slog.e(TAG, "Could not overridePid in resourceManagerSercice,"
                        + " remote exception: " + e);
            }
        }

        ClientProfile clientProfile = new ClientProfile.Builder(clientId[0])
                                              .tvInputSessionId(profile.tvInputSessionId)
                                              .useCase(profile.useCase)
                                              .processId(pid)
                                              .build();
        clientProfile.setPriority(
                getClientPriority(profile.useCase, checkIsForeground(pid)));

        addClientProfile(clientId[0], clientProfile, listener);
    }

    @VisibleForTesting
    protected void unregisterClientProfileInternal(int clientId) {
        if (DEBUG) {
            Slog.d(TAG, "unregisterClientProfile(clientId=" + clientId + ")");
        }
        removeClientProfile(clientId);
        // Remove the Media Resource Manager callingPid to tvAppId mapping
        if (mMediaResourceManager != null) {
            try {
                mMediaResourceManager.overridePid(Binder.getCallingPid(), -1);
            } catch (RemoteException e) {
                Slog.e(TAG, "Could not overridePid in resourceManagerSercice when unregister,"
                        + " remote exception: " + e);
            }
        }
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

        profile.overwritePriority(priority);
        profile.setNiceValue(niceValue);

        return true;
    }


    protected boolean hasUnusedFrontendInternal(int frontendType) {
        for (FrontendResource fr : getFrontendResources().values()) {
            if (fr.getType() == frontendType && !fr.isInUse()) {
                return true;
            }
        }
        return false;
    }

    protected boolean isLowestPriorityInternal(int clientId, int frontendType)
            throws RemoteException {
        // Update the client priority
        ClientProfile requestClient = getClientProfile(clientId);
        if (requestClient == null) {
            return true;
        }
        clientPriorityUpdateOnRequest(requestClient);
        int clientPriority = requestClient.getPriority();

        // Check if there is another holder with lower priority
        for (FrontendResource fr : getFrontendResources().values()) {
            if (fr.getType() == frontendType && fr.isInUse()) {
                int priority = updateAndGetOwnerClientPriority(fr.getOwnerClientId());
                // Returns false only when the clientPriority is strictly greater
                // because false means that there is another reclaimable resource
                if (clientPriority > priority) {
                    return false;
                }
            }
        }
        return true;
    }

    protected void storeResourceMapInternal(int resourceType) {
        switch (resourceType) {
            case TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND:
                if (mFrontendResources != null && mFrontendResources.size() > 0) {
                    mFrontendResourcesBackup.putAll(mFrontendResources);
                    mFrontendResources.clear();
                }
                break;
                // TODO: implement for other resource type when needed
            default:
                break;
        }
    }

    protected void clearResourceMapInternal(int resourceType) {
        switch (resourceType) {
            case TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND:
                if (mFrontendResources != null) {
                    mFrontendResources.clear();
                }
                break;
                // TODO: implement for other resource type when needed
            default:
                break;
        }
    }

    protected void restoreResourceMapInternal(int resourceType) {
        switch (resourceType) {
            case TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND:
                if (mFrontendResourcesBackup != null
                        && mFrontendResourcesBackup.size() > 0) {
                    mFrontendResources.clear();
                    mFrontendResources.putAll(mFrontendResourcesBackup);
                    mFrontendResourcesBackup.clear();
                }
                break;
                // TODO: implement for other resource type when needed
            default:
                break;
        }
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
        Set<Integer> updatingFrontendHandles = new HashSet<>(getFrontendResources().keySet());

        // Update frontendResources map and other mappings accordingly
        for (int i = 0; i < infos.length; i++) {
            if (getFrontendResource(infos[i].handle) != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Frontend handle=" + infos[i].handle + "exists.");
                }
                updatingFrontendHandles.remove(infos[i].handle);
            } else {
                // Add a new fe resource
                FrontendResource newFe = new FrontendResource.Builder(infos[i].handle)
                                                 .type(infos[i].type)
                                                 .exclusiveGroupId(infos[i].exclusiveGroupId)
                                                 .build();
                addFrontendResource(newFe);
            }
        }

        for (int removingHandle : updatingFrontendHandles) {
            // update the exclusive group id member list
            removeFrontendResource(removingHandle);
        }
    }

    @VisibleForTesting
    protected void setLnbInfoListInternal(int[] lnbHandles) {
        if (DEBUG) {
            for (int i = 0; i < lnbHandles.length; i++) {
                Slog.d(TAG, "updateLnbInfo(lnbHanle=" + lnbHandles[i] + ")");
            }
        }

        // A set to record the Lnbs pending on updating. Handles will be removed
        // from this set once its updating finished. Any lnb left in this set when all
        // the updates are done will be removed from mLnbResources.
        Set<Integer> updatingLnbHandles = new HashSet<>(getLnbResources().keySet());

        // Update lnbResources map and other mappings accordingly
        for (int i = 0; i < lnbHandles.length; i++) {
            if (getLnbResource(lnbHandles[i]) != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Lnb handle=" + lnbHandles[i] + "exists.");
                }
                updatingLnbHandles.remove(lnbHandles[i]);
            } else {
                // Add a new lnb resource
                LnbResource newLnb = new LnbResource.Builder(lnbHandles[i]).build();
                addLnbResource(newLnb);
            }
        }

        for (int removingHandle : updatingLnbHandles) {
            removeLnbResource(removingHandle);
        }
    }

    @VisibleForTesting
    protected void updateCasInfoInternal(int casSystemId, int maxSessionNum) {
        if (DEBUG) {
            Slog.d(TAG,
                    "updateCasInfo(casSystemId=" + casSystemId
                            + ", maxSessionNum=" + maxSessionNum + ")");
        }
        // If maxSessionNum is 0, removing the Cas Resource.
        if (maxSessionNum == 0) {
            removeCasResource(casSystemId);
            removeCiCamResource(casSystemId);
            return;
        }
        // If the Cas exists, updates the Cas Resource accordingly.
        CasResource cas = getCasResource(casSystemId);
        CiCamResource ciCam = getCiCamResource(casSystemId);
        if (cas != null) {
            if (cas.getUsedSessionNum() > maxSessionNum) {
                // Sort and release the short number of Cas resources.
                int releasingCasResourceNum = cas.getUsedSessionNum() - maxSessionNum;
                // TODO: handle CiCam session update.
            }
            cas.updateMaxSessionNum(maxSessionNum);
            if (ciCam != null) {
                ciCam.updateMaxSessionNum(maxSessionNum);
            }
            return;
        }
        // Add the new Cas Resource.
        cas = new CasResource.Builder(casSystemId)
                             .maxSessionNum(maxSessionNum)
                             .build();
        ciCam = new CiCamResource.Builder(casSystemId)
                             .maxSessionNum(maxSessionNum)
                             .build();
        addCasResource(cas);
        addCiCamResource(ciCam);
    }

    @VisibleForTesting
    protected boolean requestFrontendInternal(TunerFrontendRequest request, int[] frontendHandle) {
        if (DEBUG) {
            Slog.d(TAG, "requestFrontend(request=" + request + ")");
        }

        frontendHandle[0] = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        ClientProfile requestClient = getClientProfile(request.clientId);
        // TODO: check if this is really needed
        if (requestClient == null) {
            return false;
        }
        clientPriorityUpdateOnRequest(requestClient);
        int grantingFrontendHandle = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        int inUseLowestPriorityFrHandle = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        // Priority max value is 1000
        int currentLowestPriority = MAX_CLIENT_PRIORITY + 1;
        for (FrontendResource fr : getFrontendResources().values()) {
            if (fr.getType() == request.frontendType) {
                if (!fr.isInUse()) {
                    // Grant unused frontend with no exclusive group members first.
                    if (fr.getExclusiveGroupMemberFeHandles().isEmpty()) {
                        grantingFrontendHandle = fr.getHandle();
                        break;
                    } else if (grantingFrontendHandle
                            == TunerResourceManager.INVALID_RESOURCE_HANDLE) {
                        // Grant the unused frontend with lower id first if all the unused
                        // frontends have exclusive group members.
                        grantingFrontendHandle = fr.getHandle();
                    }
                } else if (grantingFrontendHandle == TunerResourceManager.INVALID_RESOURCE_HANDLE) {
                    // Record the frontend id with the lowest client priority among all the
                    // in use frontends when no available frontend has been found.
                    int priority = getFrontendHighestClientPriority(fr.getOwnerClientId());
                    if (currentLowestPriority > priority) {
                        inUseLowestPriorityFrHandle = fr.getHandle();
                        currentLowestPriority = priority;
                    }
                }
            }
        }

        // Grant frontend when there is unused resource.
        if (grantingFrontendHandle != TunerResourceManager.INVALID_RESOURCE_HANDLE) {
            frontendHandle[0] = grantingFrontendHandle;
            updateFrontendClientMappingOnNewGrant(grantingFrontendHandle, request.clientId);
            return true;
        }

        // When all the resources are occupied, grant the lowest priority resource if the
        // request client has higher priority.
        if (inUseLowestPriorityFrHandle != TunerResourceManager.INVALID_RESOURCE_HANDLE
                && (requestClient.getPriority() > currentLowestPriority)) {
            if (!reclaimResource(
                    getFrontendResource(inUseLowestPriorityFrHandle).getOwnerClientId(),
                    TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND)) {
                return false;
            }
            frontendHandle[0] = inUseLowestPriorityFrHandle;
            updateFrontendClientMappingOnNewGrant(
                    inUseLowestPriorityFrHandle, request.clientId);
            return true;
        }

        return false;
    }

    @VisibleForTesting
    protected void shareFrontendInternal(int selfClientId, int targetClientId) {
        if (DEBUG) {
            Slog.d(TAG, "shareFrontend from " + selfClientId + " with " + targetClientId);
        }
        for (int feId : getClientProfile(targetClientId).getInUseFrontendHandles()) {
            getClientProfile(selfClientId).useFrontend(feId);
        }
        getClientProfile(targetClientId).shareFrontend(selfClientId);
    }

    @VisibleForTesting
    protected boolean requestLnbInternal(TunerLnbRequest request, int[] lnbHandle) {
        if (DEBUG) {
            Slog.d(TAG, "requestLnb(request=" + request + ")");
        }

        lnbHandle[0] = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        ClientProfile requestClient = getClientProfile(request.clientId);
        clientPriorityUpdateOnRequest(requestClient);
        int grantingLnbHandle = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        int inUseLowestPriorityLnbHandle = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        // Priority max value is 1000
        int currentLowestPriority = MAX_CLIENT_PRIORITY + 1;
        for (LnbResource lnb : getLnbResources().values()) {
            if (!lnb.isInUse()) {
                // Grant the unused lnb with lower handle first
                grantingLnbHandle = lnb.getHandle();
                break;
            } else {
                // Record the lnb id with the lowest client priority among all the
                // in use lnb when no available lnb has been found.
                int priority = updateAndGetOwnerClientPriority(lnb.getOwnerClientId());
                if (currentLowestPriority > priority) {
                    inUseLowestPriorityLnbHandle = lnb.getHandle();
                    currentLowestPriority = priority;
                }
            }
        }

        // Grant Lnb when there is unused resource.
        if (grantingLnbHandle > -1) {
            lnbHandle[0] = grantingLnbHandle;
            updateLnbClientMappingOnNewGrant(grantingLnbHandle, request.clientId);
            return true;
        }

        // When all the resources are occupied, grant the lowest priority resource if the
        // request client has higher priority.
        if (inUseLowestPriorityLnbHandle > TunerResourceManager.INVALID_RESOURCE_HANDLE
                && (requestClient.getPriority() > currentLowestPriority)) {
            if (!reclaimResource(getLnbResource(inUseLowestPriorityLnbHandle).getOwnerClientId(),
                    TunerResourceManager.TUNER_RESOURCE_TYPE_LNB)) {
                return false;
            }
            lnbHandle[0] = inUseLowestPriorityLnbHandle;
            updateLnbClientMappingOnNewGrant(inUseLowestPriorityLnbHandle, request.clientId);
            return true;
        }

        return false;
    }

    @VisibleForTesting
    protected boolean requestCasSessionInternal(CasSessionRequest request, int[] casSessionHandle) {
        if (DEBUG) {
            Slog.d(TAG, "requestCasSession(request=" + request + ")");
        }
        CasResource cas = getCasResource(request.casSystemId);
        // Unregistered Cas System is treated as having unlimited sessions.
        if (cas == null) {
            cas = new CasResource.Builder(request.casSystemId)
                                 .maxSessionNum(Integer.MAX_VALUE)
                                 .build();
            addCasResource(cas);
        }
        casSessionHandle[0] = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        ClientProfile requestClient = getClientProfile(request.clientId);
        clientPriorityUpdateOnRequest(requestClient);
        int lowestPriorityOwnerId = -1;
        // Priority max value is 1000
        int currentLowestPriority = MAX_CLIENT_PRIORITY + 1;
        if (!cas.isFullyUsed()) {
            casSessionHandle[0] = generateResourceHandle(
                    TunerResourceManager.TUNER_RESOURCE_TYPE_CAS_SESSION, cas.getSystemId());
            updateCasClientMappingOnNewGrant(request.casSystemId, request.clientId);
            return true;
        }
        for (int ownerId : cas.getOwnerClientIds()) {
            // Record the client id with lowest priority that is using the current Cas system.
            int priority = updateAndGetOwnerClientPriority(ownerId);
            if (currentLowestPriority > priority) {
                lowestPriorityOwnerId = ownerId;
                currentLowestPriority = priority;
            }
        }

        // When all the Cas sessions are occupied, reclaim the lowest priority client if the
        // request client has higher priority.
        if (lowestPriorityOwnerId > -1 && (requestClient.getPriority() > currentLowestPriority)) {
            if (!reclaimResource(lowestPriorityOwnerId,
                    TunerResourceManager.TUNER_RESOURCE_TYPE_CAS_SESSION)) {
                return false;
            }
            casSessionHandle[0] = generateResourceHandle(
                    TunerResourceManager.TUNER_RESOURCE_TYPE_CAS_SESSION, cas.getSystemId());
            updateCasClientMappingOnNewGrant(request.casSystemId, request.clientId);
            return true;
        }
        return false;
    }

    @VisibleForTesting
    protected boolean requestCiCamInternal(TunerCiCamRequest request, int[] ciCamHandle) {
        if (DEBUG) {
            Slog.d(TAG, "requestCiCamInternal(TunerCiCamRequest=" + request + ")");
        }
        CiCamResource ciCam = getCiCamResource(request.ciCamId);
        // Unregistered Cas System is treated as having unlimited sessions.
        if (ciCam == null) {
            ciCam = new CiCamResource.Builder(request.ciCamId)
                                     .maxSessionNum(Integer.MAX_VALUE)
                                     .build();
            addCiCamResource(ciCam);
        }
        ciCamHandle[0] = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        ClientProfile requestClient = getClientProfile(request.clientId);
        clientPriorityUpdateOnRequest(requestClient);
        int lowestPriorityOwnerId = -1;
        // Priority max value is 1000
        int currentLowestPriority = MAX_CLIENT_PRIORITY + 1;
        if (!ciCam.isFullyUsed()) {
            ciCamHandle[0] = generateResourceHandle(
                    TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND_CICAM, ciCam.getCiCamId());
            updateCiCamClientMappingOnNewGrant(request.ciCamId, request.clientId);
            return true;
        }
        for (int ownerId : ciCam.getOwnerClientIds()) {
            // Record the client id with lowest priority that is using the current Cas system.
            int priority = updateAndGetOwnerClientPriority(ownerId);
            if (currentLowestPriority > priority) {
                lowestPriorityOwnerId = ownerId;
                currentLowestPriority = priority;
            }
        }

        // When all the CiCam sessions are occupied, reclaim the lowest priority client if the
        // request client has higher priority.
        if (lowestPriorityOwnerId > -1 && (requestClient.getPriority() > currentLowestPriority)) {
            if (!reclaimResource(lowestPriorityOwnerId,
                    TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND_CICAM)) {
                return false;
            }
            ciCamHandle[0] = generateResourceHandle(
                    TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND_CICAM, ciCam.getCiCamId());
            updateCiCamClientMappingOnNewGrant(request.ciCamId, request.clientId);
            return true;
        }
        return false;
    }

    @VisibleForTesting
    protected boolean isHigherPriorityInternal(ResourceClientProfile challengerProfile,
            ResourceClientProfile holderProfile) {
        if (DEBUG) {
            Slog.d(TAG,
                    "isHigherPriority(challengerProfile=" + challengerProfile
                            + ", holderProfile=" + challengerProfile + ")");
        }
        if (mTvInputManager == null) {
            Slog.e(TAG, "TvInputManager is null. Can't compare the priority.");
            // Allow the client to acquire the hardware interface
            // when the TRM is not able to compare the priority.
            return true;
        }

        int challengerPid = challengerProfile.tvInputSessionId == null
                ? Binder.getCallingPid() /*callingPid*/
                : mTvInputManager.getClientPid(challengerProfile.tvInputSessionId); /*tvAppId*/
        int holderPid = holderProfile.tvInputSessionId == null
                ? Binder.getCallingPid() /*callingPid*/
                : mTvInputManager.getClientPid(holderProfile.tvInputSessionId); /*tvAppId*/

        int challengerPriority = getClientPriority(
                challengerProfile.useCase, checkIsForeground(challengerPid));
        int holderPriority = getClientPriority(holderProfile.useCase, checkIsForeground(holderPid));
        return challengerPriority > holderPriority;
    }

    @VisibleForTesting
    protected void releaseFrontendInternal(FrontendResource fe, int clientId) {
        if (DEBUG) {
            Slog.d(TAG, "releaseFrontend(id=" + fe.getHandle() + ", clientId=" + clientId + " )");
        }
        if (clientId == fe.getOwnerClientId()) {
            ClientProfile ownerClient = getClientProfile(fe.getOwnerClientId());
            if (ownerClient != null) {
                for (int shareOwnerId : ownerClient.getShareFeClientIds()) {
                    clearFrontendAndClientMapping(getClientProfile(shareOwnerId));
                }
            }
        }
        clearFrontendAndClientMapping(getClientProfile(clientId));
    }

    @VisibleForTesting
    protected void releaseLnbInternal(LnbResource lnb) {
        if (DEBUG) {
            Slog.d(TAG, "releaseLnb(lnbHandle=" + lnb.getHandle() + ")");
        }
        updateLnbClientMappingOnRelease(lnb);
    }

    @VisibleForTesting
    protected void releaseCasSessionInternal(CasResource cas, int ownerClientId) {
        if (DEBUG) {
            Slog.d(TAG, "releaseCasSession(sessionResourceId=" + cas.getSystemId() + ")");
        }
        updateCasClientMappingOnRelease(cas, ownerClientId);
    }

    @VisibleForTesting
    protected void releaseCiCamInternal(CiCamResource ciCam, int ownerClientId) {
        if (DEBUG) {
            Slog.d(TAG, "releaseCiCamInternal(ciCamId=" + ciCam.getCiCamId() + ")");
        }
        updateCiCamClientMappingOnRelease(ciCam, ownerClientId);
    }

    @VisibleForTesting
    protected boolean requestDemuxInternal(TunerDemuxRequest request, int[] demuxHandle) {
        if (DEBUG) {
            Slog.d(TAG, "requestDemux(request=" + request + ")");
        }
        // There are enough Demux resources, so we don't manage Demux in R.
        demuxHandle[0] = generateResourceHandle(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX, 0);
        return true;
    }

    @VisibleForTesting
    // This mothod is to sync up the request/holder client's foreground/background status and update
    // the client priority accordingly whenever a new resource request comes in.
    protected void clientPriorityUpdateOnRequest(ClientProfile profile) {
        if (profile.isPriorityOverwritten()) {
            // To avoid overriding the priority set through updateClientPriority API.
            return;
        }
        int pid = profile.getProcessId();
        boolean currentIsForeground = checkIsForeground(pid);
        profile.setPriority(
                getClientPriority(profile.getUseCase(), currentIsForeground));
    }

    @VisibleForTesting
    protected boolean requestDescramblerInternal(
            TunerDescramblerRequest request, int[] descramblerHandle) {
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
                if (checkClientExists(mClientId)) {
                    removeClientProfile(mClientId);
                }
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

        // Reclaim all the resources of the share owners of the frontend that is used by the current
        // resource reclaimed client.
        ClientProfile profile = getClientProfile(reclaimingClientId);
        // TODO: check if this check is really needed.
        if (profile == null) {
            return true;
        }
        Set<Integer> shareFeClientIds = profile.getShareFeClientIds();
        for (int clientId : shareFeClientIds) {
            try {
                mListeners.get(clientId).getListener().onReclaimResources();
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to reclaim resources on client " + clientId, e);
                return false;
            }
            clearAllResourcesAndClientMapping(getClientProfile(clientId));
        }

        if (DEBUG) {
            Slog.d(TAG, "Reclaiming resources because higher priority client request resource type "
                    + resourceType + ", clientId:" + reclaimingClientId);
        }
        try {
            mListeners.get(reclaimingClientId).getListener().onReclaimResources();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to reclaim resources on client " + reclaimingClientId, e);
            return false;
        }
        clearAllResourcesAndClientMapping(profile);
        return true;
    }

    @VisibleForTesting
    protected int getClientPriority(int useCase, boolean isForeground) {
        if (DEBUG) {
            Slog.d(TAG, "getClientPriority useCase=" + useCase
                    + ", isForeground=" + isForeground + ")");
        }

        if (isForeground) {
            return mPriorityCongfig.getForegroundPriority(useCase);
        }
        return mPriorityCongfig.getBackgroundPriority(useCase);
    }

    @VisibleForTesting
    protected boolean checkIsForeground(int pid) {
        if (mActivityManager == null) {
            return false;
        }
        List<RunningAppProcessInfo> appProcesses = mActivityManager.getRunningAppProcesses();
        if (appProcesses == null) {
            return false;
        }
        for (RunningAppProcessInfo appProcess : appProcesses) {
            if (appProcess.pid == pid
                    && appProcess.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return true;
            }
        }
        return false;
    }

    private void updateFrontendClientMappingOnNewGrant(int grantingHandle, int ownerClientId) {
        FrontendResource grantingFrontend = getFrontendResource(grantingHandle);
        ClientProfile ownerProfile = getClientProfile(ownerClientId);
        grantingFrontend.setOwner(ownerClientId);
        ownerProfile.useFrontend(grantingHandle);
        for (int exclusiveGroupMember : grantingFrontend.getExclusiveGroupMemberFeHandles()) {
            getFrontendResource(exclusiveGroupMember).setOwner(ownerClientId);
            ownerProfile.useFrontend(exclusiveGroupMember);
        }
    }

    private void updateLnbClientMappingOnNewGrant(int grantingHandle, int ownerClientId) {
        LnbResource grantingLnb = getLnbResource(grantingHandle);
        ClientProfile ownerProfile = getClientProfile(ownerClientId);
        grantingLnb.setOwner(ownerClientId);
        ownerProfile.useLnb(grantingHandle);
    }

    private void updateLnbClientMappingOnRelease(@NonNull LnbResource releasingLnb) {
        ClientProfile ownerProfile = getClientProfile(releasingLnb.getOwnerClientId());
        releasingLnb.removeOwner();
        ownerProfile.releaseLnb(releasingLnb.getHandle());
    }

    private void updateCasClientMappingOnNewGrant(int grantingId, int ownerClientId) {
        CasResource grantingCas = getCasResource(grantingId);
        ClientProfile ownerProfile = getClientProfile(ownerClientId);
        grantingCas.setOwner(ownerClientId);
        ownerProfile.useCas(grantingId);
    }

    private void updateCiCamClientMappingOnNewGrant(int grantingId, int ownerClientId) {
        CiCamResource grantingCiCam = getCiCamResource(grantingId);
        ClientProfile ownerProfile = getClientProfile(ownerClientId);
        grantingCiCam.setOwner(ownerClientId);
        ownerProfile.useCiCam(grantingId);
    }

    private void updateCasClientMappingOnRelease(
            @NonNull CasResource releasingCas, int ownerClientId) {
        ClientProfile ownerProfile = getClientProfile(ownerClientId);
        releasingCas.removeOwner(ownerClientId);
        ownerProfile.releaseCas();
    }

    private void updateCiCamClientMappingOnRelease(
            @NonNull CiCamResource releasingCiCam, int ownerClientId) {
        ClientProfile ownerProfile = getClientProfile(ownerClientId);
        releasingCiCam.removeOwner(ownerClientId);
        ownerProfile.releaseCiCam();
    }

    /**
     * Update and get the owner client's priority.
     *
     * @param clientId the owner client id.
     * @return the priority of the owner client.
     */
    private int updateAndGetOwnerClientPriority(int clientId) {
        ClientProfile profile = getClientProfile(clientId);
        clientPriorityUpdateOnRequest(profile);
        return profile.getPriority();
    }

    /**
     * Update the owner and sharee clients' priority and get the highest priority
     * for frontend resource
     *
     * @param clientId the owner client id.
     * @return the highest priority among all the clients holding the same frontend resource.
     */
    private int getFrontendHighestClientPriority(int clientId) {
        // Check if the owner profile exists
        ClientProfile ownerClient = getClientProfile(clientId);
        if (ownerClient == null) {
            return 0;
        }

        // Update and get the priority of the owner client
        int highestPriority = updateAndGetOwnerClientPriority(clientId);

        // Update and get all the client IDs of frontend resource holders
        for (int shareeId : ownerClient.getShareFeClientIds()) {
            int priority = updateAndGetOwnerClientPriority(shareeId);
            if (priority > highestPriority) {
                highestPriority = priority;
            }
        }
        return highestPriority;
    }

    @VisibleForTesting
    @Nullable
    protected FrontendResource getFrontendResource(int frontendHandle) {
        return mFrontendResources.get(frontendHandle);
    }

    @VisibleForTesting
    protected Map<Integer, FrontendResource> getFrontendResources() {
        return mFrontendResources;
    }

    private void addFrontendResource(FrontendResource newFe) {
        // Update the exclusive group member list in all the existing Frontend resource
        for (FrontendResource fe : getFrontendResources().values()) {
            if (fe.getExclusiveGroupId() == newFe.getExclusiveGroupId()) {
                newFe.addExclusiveGroupMemberFeHandle(fe.getHandle());
                newFe.addExclusiveGroupMemberFeHandles(fe.getExclusiveGroupMemberFeHandles());
                for (int excGroupmemberFeHandle : fe.getExclusiveGroupMemberFeHandles()) {
                    getFrontendResource(excGroupmemberFeHandle)
                            .addExclusiveGroupMemberFeHandle(newFe.getHandle());
                }
                fe.addExclusiveGroupMemberFeHandle(newFe.getHandle());
                break;
            }
        }
        // Update resource list and available id list
        mFrontendResources.put(newFe.getHandle(), newFe);
    }

    private void removeFrontendResource(int removingHandle) {
        FrontendResource fe = getFrontendResource(removingHandle);
        if (fe == null) {
            return;
        }
        if (fe.isInUse()) {
            ClientProfile ownerClient = getClientProfile(fe.getOwnerClientId());
            for (int shareOwnerId : ownerClient.getShareFeClientIds()) {
                clearFrontendAndClientMapping(getClientProfile(shareOwnerId));
            }
            clearFrontendAndClientMapping(ownerClient);
        }
        for (int excGroupmemberFeHandle : fe.getExclusiveGroupMemberFeHandles()) {
            getFrontendResource(excGroupmemberFeHandle)
                    .removeExclusiveGroupMemberFeId(fe.getHandle());
        }
        mFrontendResources.remove(removingHandle);
    }

    @VisibleForTesting
    @Nullable
    protected LnbResource getLnbResource(int lnbHandle) {
        return mLnbResources.get(lnbHandle);
    }

    @VisibleForTesting
    protected Map<Integer, LnbResource> getLnbResources() {
        return mLnbResources;
    }

    private void addLnbResource(LnbResource newLnb) {
        // Update resource list and available id list
        mLnbResources.put(newLnb.getHandle(), newLnb);
    }

    private void removeLnbResource(int removingHandle) {
        LnbResource lnb = getLnbResource(removingHandle);
        if (lnb == null) {
            return;
        }
        if (lnb.isInUse()) {
            releaseLnbInternal(lnb);
        }
        mLnbResources.remove(removingHandle);
    }

    @VisibleForTesting
    @Nullable
    protected CasResource getCasResource(int systemId) {
        return mCasResources.get(systemId);
    }

    @VisibleForTesting
    @Nullable
    protected CiCamResource getCiCamResource(int ciCamId) {
        return mCiCamResources.get(ciCamId);
    }

    @VisibleForTesting
    protected Map<Integer, CasResource> getCasResources() {
        return mCasResources;
    }

    @VisibleForTesting
    protected Map<Integer, CiCamResource> getCiCamResources() {
        return mCiCamResources;
    }

    private void addCasResource(CasResource newCas) {
        // Update resource list and available id list
        mCasResources.put(newCas.getSystemId(), newCas);
    }

    private void addCiCamResource(CiCamResource newCiCam) {
        // Update resource list and available id list
        mCiCamResources.put(newCiCam.getCiCamId(), newCiCam);
    }

    private void removeCasResource(int removingId) {
        CasResource cas = getCasResource(removingId);
        if (cas == null) {
            return;
        }
        for (int ownerId : cas.getOwnerClientIds()) {
            getClientProfile(ownerId).releaseCas();
        }
        mCasResources.remove(removingId);
    }

    private void removeCiCamResource(int removingId) {
        CiCamResource ciCam = getCiCamResource(removingId);
        if (ciCam == null) {
            return;
        }
        for (int ownerId : ciCam.getOwnerClientIds()) {
            getClientProfile(ownerId).releaseCiCam();
        }
        mCiCamResources.remove(removingId);
    }

    private void releaseLowerPriorityClientCasResources(int releasingCasResourceNum) {
        // TODO: Sort with a treemap

        // select the first num client to release
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
        for (int shareOwnerId : getClientProfile(clientId).getShareFeClientIds()) {
            clearFrontendAndClientMapping(getClientProfile(shareOwnerId));
        }
        clearAllResourcesAndClientMapping(getClientProfile(clientId));
        mClientProfiles.remove(clientId);
        mListeners.remove(clientId);
    }

    private void clearFrontendAndClientMapping(ClientProfile profile) {
        // TODO: check if this check is really needed
        if (profile == null) {
            return;
        }
        for (Integer feId : profile.getInUseFrontendHandles()) {
            FrontendResource fe = getFrontendResource(feId);
            int ownerClientId = fe.getOwnerClientId();
            if (ownerClientId == profile.getId()) {
                fe.removeOwner();
                continue;
            }
            ClientProfile ownerClientProfile = getClientProfile(ownerClientId);
            if (ownerClientProfile != null) {
                ownerClientProfile.stopSharingFrontend(profile.getId());
            }

        }
        profile.releaseFrontend();
    }

    private void clearAllResourcesAndClientMapping(ClientProfile profile) {
        // TODO: check if this check is really needed. Maybe needed for reclaimResource path.
        if (profile == null) {
            return;
        }
        // Clear Lnb
        for (Integer lnbHandle : profile.getInUseLnbHandles()) {
            getLnbResource(lnbHandle).removeOwner();
        }
        // Clear Cas
        if (profile.getInUseCasSystemId() != ClientProfile.INVALID_RESOURCE_ID) {
            getCasResource(profile.getInUseCasSystemId()).removeOwner(profile.getId());
        }
        // Clear CiCam
        if (profile.getInUseCiCamId() != ClientProfile.INVALID_RESOURCE_ID) {
            getCiCamResource(profile.getInUseCiCamId()).removeOwner(profile.getId());
        }
        // Clear Frontend
        clearFrontendAndClientMapping(profile);
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
        getContext().enforceCallingOrSelfPermission("android.permission.TUNER_RESOURCE_ACCESS",
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
