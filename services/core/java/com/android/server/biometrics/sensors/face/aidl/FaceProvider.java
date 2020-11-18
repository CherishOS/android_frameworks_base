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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.SensorProps;
import android.hardware.face.Face;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.NativeHandle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserManager;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.ClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.face.FaceUtils;
import com.android.server.biometrics.sensors.face.ServiceProvider;
import com.android.server.biometrics.sensors.face.UsageStats;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider for a single instance of the {@link IFace} HAL.
 */
public class FaceProvider implements IBinder.DeathRecipient, ServiceProvider {
    private static final String TAG = "FaceProvider";
    private static final int ENROLL_TIMEOUT_SEC = 75;

    private boolean mTestHalEnabled;

    @NonNull private final Context mContext;
    @NonNull private final String mHalInstanceName;
    @NonNull private final SparseArray<Sensor> mSensors; // Map of sensors that this HAL supports
    @NonNull private final ClientMonitor.LazyDaemon<IFace> mLazyDaemon;
    @NonNull private final Handler mHandler;
    @NonNull private final LockoutResetDispatcher mLockoutResetDispatcher;
    @NonNull private final UsageStats mUsageStats;
    @NonNull private final IActivityTaskManager mActivityTaskManager;
    @NonNull private final BiometricTaskStackListener mTaskStackListener;

    @Nullable private IFace mDaemon;

    private final class BiometricTaskStackListener extends TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(() -> {
                for (int i = 0; i < mSensors.size(); i++) {
                    final ClientMonitor<?> client = mSensors.get(i).getScheduler()
                            .getCurrentClient();
                    if (!(client instanceof AuthenticationClient)) {
                        Slog.e(getTag(), "Task stack changed for client: " + client);
                        continue;
                    }
                    if (Utils.isKeyguard(mContext, client.getOwnerString())) {
                        continue; // Keyguard is always allowed
                    }

                    try {
                        final List<ActivityManager.RunningTaskInfo> runningTasks =
                                mActivityTaskManager.getTasks(1);
                        if (!runningTasks.isEmpty()) {
                            final String topPackage =
                                    runningTasks.get(0).topActivity.getPackageName();
                            if (!topPackage.contentEquals(client.getOwnerString())
                                    && !client.isAlreadyDone()) {
                                Slog.e(getTag(), "Stopping background authentication, top: "
                                        + topPackage + " currentClient: " + client);
                                mSensors.get(i).getScheduler()
                                        .cancelAuthentication(client.getToken());
                            }
                        }
                    } catch (RemoteException e) {
                        Slog.e(getTag(), "Unable to get running tasks", e);
                    }
                }
            });
        }
    }

    public FaceProvider(@NonNull Context context, @NonNull SensorProps[] props,
            @NonNull String halInstanceName,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher) {
        mContext = context;
        mHalInstanceName = halInstanceName;
        mSensors = new SparseArray<>();
        mLazyDaemon = this::getHalInstance;
        mHandler = new Handler(Looper.getMainLooper());
        mUsageStats = new UsageStats(context);
        mLockoutResetDispatcher = lockoutResetDispatcher;
        mActivityTaskManager = ActivityTaskManager.getService();
        mTaskStackListener = new BiometricTaskStackListener();

        for (SensorProps prop : props) {
            final int sensorId = prop.commonProps.sensorId;

            final FaceSensorPropertiesInternal internalProp = new FaceSensorPropertiesInternal(
                    prop.commonProps.sensorId, prop.commonProps.sensorStrength,
                    prop.commonProps.maxEnrollmentsPerUser, false /* supportsFaceDetection */,
                    prop.halControlsPreview);
            final Sensor sensor = new Sensor(getTag() + "/" + sensorId, this, mContext, mHandler,
                    internalProp);

            mSensors.put(sensorId, sensor);
            Slog.d(getTag(), "Added: " + internalProp);
        }
    }

    private String getTag() {
        return "FaceProvider/" + mHalInstanceName;
    }

    @Nullable
    private synchronized IFace getHalInstance() {
        if (mTestHalEnabled) {
            return new TestHal();
        }

        if (mDaemon != null) {
            return mDaemon;
        }

        Slog.d(getTag(), "Daemon was null, reconnecting");

        mDaemon = IFace.Stub.asInterface(
                ServiceManager.waitForDeclaredService(IFace.DESCRIPTOR + "/" + mHalInstanceName));
        if (mDaemon == null) {
            Slog.e(getTag(), "Unable to get daemon");
            return null;
        }

        try {
            mDaemon.asBinder().linkToDeath(this, 0 /* flags */);
        } catch (RemoteException e) {
            Slog.e(getTag(), "Unable to linkToDeath", e);
        }

        for (int i = 0; i < mSensors.size(); i++) {
            final int sensorId = mSensors.keyAt(i);
            scheduleLoadAuthenticatorIds(sensorId);
            scheduleInternalCleanup(sensorId, ActivityManager.getCurrentUser());
        }

        return mDaemon;
    }

    private void scheduleForSensor(int sensorId, @NonNull ClientMonitor<?> client) {
        if (!mSensors.contains(sensorId)) {
            throw new IllegalStateException("Unable to schedule client: " + client
                    + " for sensor: " + sensorId);
        }
        mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
    }

    private void scheduleForSensor(int sensorId, @NonNull ClientMonitor<?> client,
            ClientMonitor.Callback callback) {
        if (!mSensors.contains(sensorId)) {
            throw new IllegalStateException("Unable to schedule client: " + client
                    + " for sensor: " + sensorId);
        }
        mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client, callback);
    }

    private void createNewSessionWithoutHandler(@NonNull IFace daemon, int sensorId,
            int userId) throws RemoteException {
        // Note that per IFingerprint createSession contract, this method will block until all
        // existing operations are canceled/finished. However, also note that this is fine, since
        // this method "withoutHandler" means it should only ever be invoked from the worker thread,
        // so callers will never be blocked.
        mSensors.get(sensorId).createNewSession(daemon, sensorId, userId);
    }


    private void scheduleLoadAuthenticatorIds(int sensorId) {
        for (UserInfo user : UserManager.get(mContext).getAliveUsers()) {
            scheduleLoadAuthenticatorIdsForUser(sensorId, user.id);
        }
    }

    private void scheduleLoadAuthenticatorIdsForUser(int sensorId, int userId) {
        mHandler.post(() -> {
            final IFace daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during loadAuthenticatorIds, sensorId: " + sensorId);
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final FaceGetAuthenticatorIdClient client = new FaceGetAuthenticatorIdClient(
                        mContext, mSensors.get(sensorId).getLazySession(), userId,
                        mContext.getOpPackageName(), sensorId,
                        mSensors.get(sensorId).getAuthenticatorIds());

                mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling loadAuthenticatorId"
                        + ", sensorId: " + sensorId
                        + ", userId: " + userId, e);
            }
        });
    }

    @Override
    public boolean containsSensor(int sensorId) {
        return mSensors.contains(sensorId);
    }

    @NonNull
    @Override
    public List<FaceSensorPropertiesInternal> getSensorProperties() {
        final List<FaceSensorPropertiesInternal> props = new ArrayList<>();
        for (int i = 0; i < mSensors.size(); ++i) {
            props.add(mSensors.valueAt(i).getSensorProperties());
        }
        return props;
    }

    @NonNull
    @Override
    public FaceSensorPropertiesInternal getSensorProperties(int sensorId) {
        return mSensors.get(sensorId).getSensorProperties();
    }

    @NonNull
    @Override
    public List<Face> getEnrolledFaces(int sensorId, int userId) {
        return FaceUtils.getInstance(sensorId).getBiometricsForUser(mContext, userId);
    }

    @Override
    public int getLockoutModeForUser(int sensorId, int userId) {
        return mSensors.get(sensorId).getLockoutCache().getLockoutModeForUser(userId);
    }

    @Override
    public long getAuthenticatorId(int sensorId, int userId) {
        return mSensors.get(sensorId).getAuthenticatorIds().getOrDefault(userId, 0L);
    }

    @Override
    public boolean isHardwareDetected(int sensorId) {
        return getHalInstance() != null;
    }

    @Override
    public void scheduleGenerateChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull IFaceServiceReceiver receiver, String opPackageName) {
        mHandler.post(() -> {
            final IFace daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during generateChallenge, sensorId: " + sensorId);
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final FaceGenerateChallengeClient client = new FaceGenerateChallengeClient(mContext,
                        mSensors.get(sensorId).getLazySession(), token,
                        new ClientMonitorCallbackConverter(receiver), opPackageName, sensorId);

                scheduleForSensor(sensorId, client);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling generateChallenge", e);
            }
        });
    }

    @Override
    public void scheduleRevokeChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull String opPackageName, long challenge) {
        mHandler.post(() -> {
            final IFace daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during revokeChallenge, sensorId: " + sensorId);
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final FaceRevokeChallengeClient client = new FaceRevokeChallengeClient(mContext,
                        mSensors.get(sensorId).getLazySession(), token, opPackageName, sensorId,
                        challenge);

                scheduleForSensor(sensorId, client);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling revokeChallenge", e);
            }
        });
    }

    @Override
    public void scheduleEnroll(int sensorId, @NonNull IBinder token,
            @NonNull byte[] hardwareAuthToken, int userId, @NonNull IFaceServiceReceiver receiver,
            @NonNull String opPackageName, @NonNull int[] disabledFeatures,
            @Nullable NativeHandle previewSurface) {
        mHandler.post(() -> {
            final IFace daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during enroll, sensorId: " + sensorId);
                // If this happens, we need to send HW_UNAVAILABLE after the scheduler gets to
                // this operation. We should not send the callback yet, since the scheduler may
                // be processing something else.
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final int maxTemplatesPerUser = mSensors.get(
                        sensorId).getSensorProperties().maxEnrollmentsPerUser;
                final FaceEnrollClient client = new FaceEnrollClient(mContext,
                        mSensors.get(sensorId).getLazySession(), token,
                        new ClientMonitorCallbackConverter(receiver), userId, hardwareAuthToken,
                        opPackageName, FaceUtils.getInstance(sensorId), disabledFeatures,
                        ENROLL_TIMEOUT_SEC, previewSurface, sensorId, maxTemplatesPerUser);
                scheduleForSensor(sensorId, client, new ClientMonitor.Callback() {
                    @Override
                    public void onClientFinished(@NonNull ClientMonitor<?> clientMonitor,
                            boolean success) {
                        if (success) {
                            scheduleLoadAuthenticatorIdsForUser(sensorId, userId);
                        }
                    }
                });
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling enroll", e);
            }
        });
    }

    @Override
    public void cancelEnrollment(int sensorId, @NonNull IBinder token) {
        mHandler.post(() -> mSensors.get(sensorId).getScheduler().cancelEnrollment(token));
    }

    @Override
    public void scheduleAuthenticate(int sensorId, @NonNull IBinder token, long operationId,
            int userId, int cookie, @NonNull ClientMonitorCallbackConverter callback,
            @NonNull String opPackageName, boolean restricted, int statsClient,
            boolean isKeyguard) {
        mHandler.post(() -> {
            final IFace daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during authenticate, sensorId: " + sensorId);
                // If this happens, we need to send HW_UNAVAILABLE after the scheduler gets to
                // this operation. We should not send the callback yet, since the scheduler may
                // be processing something else.
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final boolean isStrongBiometric = Utils.isStrongBiometric(sensorId);
                final FaceAuthenticationClient client = new FaceAuthenticationClient(
                        mContext, mSensors.get(sensorId).getLazySession(), token, callback, userId,
                        operationId, restricted, opPackageName, cookie,
                        false /* requireConfirmation */, sensorId, isStrongBiometric, statsClient,
                        mUsageStats, mSensors.get(sensorId).getLockoutCache());
                mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling authenticate", e);
            }
        });
    }

    @Override
    public void cancelAuthentication(int sensorId, @NonNull IBinder token) {
        mHandler.post(() -> mSensors.get(sensorId).getScheduler().cancelAuthentication(token));
    }

    @Override
    public void scheduleRemove(int sensorId, @NonNull IBinder token, int faceId, int userId,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        mHandler.post(() -> {
            final IFace daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during remove, sensorId: " + sensorId);
                // If this happens, we need to send HW_UNAVAILABLE after the scheduler gets to
                // this operation. We should not send the callback yet, since the scheduler may
                // be processing something else.
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final FaceRemovalClient client = new FaceRemovalClient(mContext,
                        mSensors.get(sensorId).getLazySession(), token,
                        new ClientMonitorCallbackConverter(receiver), faceId, userId,
                        opPackageName, FaceUtils.getInstance(sensorId), sensorId,
                        mSensors.get(sensorId).getAuthenticatorIds());

                mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling remove", e);
            }
        });
    }

    @Override
    public void scheduleResetLockout(int sensorId, int userId, @NonNull byte[] hardwareAuthToken) {
        mHandler.post(() -> {
            final IFace daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during resetLockout, sensorId: " + sensorId);
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final FaceResetLockoutClient client = new FaceResetLockoutClient(
                        mContext, mSensors.get(sensorId).getLazySession(), userId,
                        mContext.getOpPackageName(), sensorId, hardwareAuthToken,
                        mSensors.get(sensorId).getLockoutCache(), mLockoutResetDispatcher);

                scheduleForSensor(sensorId, client);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling resetLockout", e);
            }
        });
    }

    @Override
    public void scheduleSetFeature(int sensorId, @NonNull IBinder token, int userId, int feature,
            boolean enabled, @NonNull byte[] hardwareAuthToken,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        // TODO(b/171335732): implement this.
    }

    @Override
    public void scheduleGetFeature(int sensorId, @NonNull IBinder token, int userId, int feature,
            @NonNull ClientMonitorCallbackConverter callback, @NonNull String opPackageName) {
        // TODO(b/171335732): implement this.
    }

    @Override
    public void startPreparedClient(int sensorId, int cookie) {
        mHandler.post(() -> {
            mSensors.get(sensorId).getScheduler().startPreparedClient(cookie);
        });
    }

    @Override
    public void scheduleInternalCleanup(int sensorId, int userId) {
        mHandler.post(() -> {
            final IFace daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during internal cleanup, sensorId: " + sensorId);
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final List<Face> enrolledList = getEnrolledFaces(sensorId, userId);
                final FaceInternalCleanupClient client =
                        new FaceInternalCleanupClient(mContext,
                                mSensors.get(sensorId).getLazySession(), userId,
                                mContext.getOpPackageName(), sensorId, enrolledList,
                                FaceUtils.getInstance(sensorId),
                                mSensors.get(sensorId).getAuthenticatorIds());

                mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling internal cleanup", e);
            }
        });
    }

    @Override
    public void dumpProtoState(int sensorId, @NonNull ProtoOutputStream proto) {
        if (mSensors.contains(sensorId)) {
            mSensors.get(sensorId).dumpProtoState(sensorId, proto);
        }
    }

    @Override
    public void dumpProtoMetrics(int sensorId, @NonNull FileDescriptor fd) {

    }

    @Override
    public void dumpInternal(int sensorId, @NonNull PrintWriter pw) {
        PerformanceTracker performanceTracker =
                PerformanceTracker.getInstanceForSensorId(sensorId);

        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Face Manager");

            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(mContext).getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                final int c = FaceUtils.getInstance().getBiometricsForUser(mContext, userId).size();
                JSONObject set = new JSONObject();
                set.put("id", userId);
                set.put("count", c);
                set.put("accept", performanceTracker.getAcceptForUser(userId));
                set.put("reject", performanceTracker.getRejectForUser(userId));
                set.put("acquire", performanceTracker.getAcquireForUser(userId));
                set.put("lockout", performanceTracker.getTimedLockoutForUser(userId));
                set.put("permanentLockout", performanceTracker.getPermanentLockoutForUser(userId));
                // cryptoStats measures statistics about secure face transactions
                // (e.g. to unlock password storage, make secure purchases, etc.)
                set.put("acceptCrypto", performanceTracker.getAcceptCryptoForUser(userId));
                set.put("rejectCrypto", performanceTracker.getRejectCryptoForUser(userId));
                set.put("acquireCrypto", performanceTracker.getAcquireCryptoForUser(userId));
                sets.put(set);
            }

            dump.put("prints", sets);
        } catch (JSONException e) {
            Slog.e(TAG, "dump formatting failure", e);
        }
        pw.println(dump);
        pw.println("HAL deaths since last reboot: " + performanceTracker.getHALDeathCount());

        mSensors.get(sensorId).getScheduler().dump(pw);
        mUsageStats.print(pw);
    }

    @NonNull
    @Override
    public ITestSession createTestSession(int sensorId, @NonNull String opPackageName) {
        return mSensors.get(sensorId).createTestSession();
    }

    @Override
    public void binderDied() {
        Slog.e(getTag(), "HAL died");
        mHandler.post(() -> {
            mDaemon = null;
            for (int i = 0; i < mSensors.size(); i++) {
                final int sensorId = mSensors.keyAt(i);
                PerformanceTracker.getInstanceForSensorId(sensorId).incrementHALDeathCount();
            }
        });
    }

    void setTestHalEnabled(boolean enabled) {
        mTestHalEnabled = enabled;
    }
}
