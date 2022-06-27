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

package com.android.server.companion.datatransfer;

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.companion.CompanionDeviceManager.COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME;
import static android.content.ComponentName.createRelative;

import static com.android.server.companion.Utils.prepareForIpc;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.companion.AssociationInfo;
import android.companion.DeviceNotAssociatedException;
import android.companion.ISystemDataTransferCallback;
import android.companion.datatransfer.PermissionSyncRequest;
import android.companion.datatransfer.SystemDataTransferRequest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.permission.PermissionControllerManager;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.companion.AssociationStore;
import com.android.server.companion.CompanionDeviceManagerService;
import com.android.server.companion.PermissionsUtils;
import com.android.server.companion.proto.CompanionMessage;

import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This processor builds user consent intent for a given SystemDataTransferRequest and processes the
 * request when the system is ready (a secure channel is established between the handhold and the
 * companion device).
 */
public class SystemDataTransferProcessor {

    private static final String LOG_TAG = SystemDataTransferProcessor.class.getSimpleName();

    // Values from UI to SystemDataTransferProcessor via ResultReceiver
    private static final int RESULT_CODE_SYSTEM_DATA_TRANSFER_ALLOWED = 0;
    private static final int RESULT_CODE_SYSTEM_DATA_TRANSFER_DISALLOWED = 1;
    private static final String EXTRA_PERMISSION_SYNC_REQUEST = "permission_sync_request";
    private static final String EXTRA_COMPANION_DEVICE_NAME = "companion_device_name";
    private static final String EXTRA_SYSTEM_DATA_TRANSFER_RESULT_RECEIVER =
            "system_data_transfer_result_receiver";
    private static final ComponentName SYSTEM_DATA_TRANSFER_REQUEST_APPROVAL_ACTIVITY =
            createRelative(COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME,
                    ".CompanionDeviceDataTransferActivity");

    private final Context mContext;
    private final AssociationStore mAssociationStore;
    private final SystemDataTransferRequestStore mSystemDataTransferRequestStore;
    private final CompanionMessageProcessor mCompanionMessageProcessor;
    private final PermissionControllerManager mPermissionControllerManager;
    private final ExecutorService mExecutor;
    @GuardedBy("mTransports")
    private final SparseArray<Transport> mTransports = new SparseArray<>();

    public SystemDataTransferProcessor(CompanionDeviceManagerService service,
            AssociationStore associationStore,
            SystemDataTransferRequestStore systemDataTransferRequestStore,
            CompanionMessageProcessor companionMessageProcessor) {
        mContext = service.getContext();
        mAssociationStore = associationStore;
        mSystemDataTransferRequestStore = systemDataTransferRequestStore;
        mCompanionMessageProcessor = companionMessageProcessor;
        mCompanionMessageProcessor.setListener(this::onCompleteMessageReceived);
        mPermissionControllerManager = mContext.getSystemService(PermissionControllerManager.class);
        mExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * Resolve the requested association, throwing if the caller doesn't have
     * adequate permissions.
     */
    private @NonNull AssociationInfo resolveAssociation(String packageName, int userId,
            int associationId) {
        AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        association = PermissionsUtils.sanitizeWithCallerChecks(mContext, association);
        if (association == null) {
            throw new DeviceNotAssociatedException("Association "
                    + associationId + " is not associated with the app " + packageName
                    + " for user " + userId);
        }
        return association;
    }

    /**
     * Build a PendingIntent of permission sync user consent dialog
     */
    public PendingIntent buildPermissionTransferUserConsentIntent(String packageName,
            @UserIdInt int userId, int associationId) {
        final AssociationInfo association = resolveAssociation(packageName, userId, associationId);

        // Check if the request's data type has been requested before.
        List<SystemDataTransferRequest> storedRequests =
                mSystemDataTransferRequestStore.readRequestsByAssociationId(userId,
                        associationId);
        for (SystemDataTransferRequest storedRequest : storedRequests) {
            if (storedRequest instanceof PermissionSyncRequest) {
                Slog.e(LOG_TAG, "The request has been sent before, you can not send "
                        + "the same request type again.");
                return null;
            }
        }

        Slog.i(LOG_TAG, "Creating permission sync intent for userId [" + userId
                + "] associationId [" + associationId + "]");

        // Create an internal intent to launch the user consent dialog
        final Bundle extras = new Bundle();
        PermissionSyncRequest request = new PermissionSyncRequest(associationId);
        request.setUserId(userId);
        extras.putParcelable(EXTRA_PERMISSION_SYNC_REQUEST, request);
        extras.putCharSequence(EXTRA_COMPANION_DEVICE_NAME, association.getDisplayName());
        extras.putParcelable(EXTRA_SYSTEM_DATA_TRANSFER_RESULT_RECEIVER,
                prepareForIpc(mOnSystemDataTransferRequestConfirmationReceiver));

        final Intent intent = new Intent();
        intent.setComponent(SYSTEM_DATA_TRANSFER_REQUEST_APPROVAL_ACTIVITY);
        intent.putExtras(extras);

        // Create a PendingIntent
        final long token = Binder.clearCallingIdentity();
        try {
            return PendingIntent.getActivity(mContext, /*requestCode */ associationId, intent,
                    FLAG_ONE_SHOT | FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Start system data transfer. It should first try to establish a secure channel and then sync
     * system data.
     *
     * TODO: execute callback when the transfer finishes successfully or with errors.
     */
    public void startSystemDataTransfer(String packageName, int userId, int associationId,
            ISystemDataTransferCallback callback) {
        Slog.i(LOG_TAG, "Start system data transfer for package [" + packageName
                + "] userId [" + userId + "] associationId [" + associationId + "]");

        final AssociationInfo association = resolveAssociation(packageName, userId, associationId);

        // Check if the request has been consented by the user.
        List<SystemDataTransferRequest> storedRequests =
                mSystemDataTransferRequestStore.readRequestsByAssociationId(userId,
                        associationId);
        boolean hasConsented = false;
        for (SystemDataTransferRequest storedRequest : storedRequests) {
            if (storedRequest instanceof PermissionSyncRequest && storedRequest.isUserConsented()) {
                hasConsented = true;
                break;
            }
        }
        if (!hasConsented) {
            Slog.e(LOG_TAG, "User " + userId + " hasn't consented permission sync.");
            return;
        }

        // TODO: Establish a secure channel

        // Start permission sync
        final long callingIdentityToken = Binder.clearCallingIdentity();
        try {
            mPermissionControllerManager.getRuntimePermissionBackup(UserHandle.of(userId),
                    mExecutor,
                    backup -> mCompanionMessageProcessor.paginateAndDispatchMessagesToApp(backup,
                        CompanionMessage.PERMISSION_SYNC, packageName, userId, associationId));
        } finally {
            Binder.restoreCallingIdentity(callingIdentityToken);
        }
    }

    public void attachSystemDataTransport(String packageName, int userId, int associationId,
            ParcelFileDescriptor fd) {
        synchronized (mTransports) {
            // TODO: restore once testing has evolved
            // resolveAssociation(packageName, userId, associationId);

            if (mTransports.contains(associationId)) {
                detachSystemDataTransport(packageName, userId, associationId);
            }

            final Transport transport = new Transport(fd);
            transport.start();
            mTransports.put(associationId, transport);
        }
    }

    public void detachSystemDataTransport(String packageName, int userId, int associationId) {
        synchronized (mTransports) {
            // TODO: restore once testing has evolved
            // resolveAssociation(packageName, userId, associationId);

            final Transport transport = mTransports.get(associationId);
            if (transport != null) {
                mTransports.delete(associationId);
                transport.stop();
            }
        }
    }

    /**
     * Process a complete decrypted message reported by the companion app.
     */
    public void onCompleteMessageReceived(@NonNull CompanionMessageInfo completeMessage) {
        switch (completeMessage.getType()) {
            case CompanionMessage.PERMISSION_SYNC:
                processPermissionSyncMessage(completeMessage);
                break;
            default:
                Slog.e(LOG_TAG, "Unknown message type [" + completeMessage.getType()
                        + "]. Unable to process.");
        }
    }

    private void processPermissionSyncMessage(CompanionMessageInfo messageInfo) {
        Slog.i(LOG_TAG, "Applying permissions.");
        // Start applying permissions
        UserHandle user = mContext.getUser();
        final long callingIdentityToken = Binder.clearCallingIdentity();
        try {
            mPermissionControllerManager.stageAndApplyRuntimePermissionsBackup(
                    messageInfo.getData(), user);
        } finally {
            Slog.i(LOG_TAG, "Permissions applied.");
            Binder.restoreCallingIdentity(callingIdentityToken);
        }
    }

    private final ResultReceiver mOnSystemDataTransferRequestConfirmationReceiver =
            new ResultReceiver(Handler.getMain()) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle data) {
                    Slog.d(LOG_TAG, "onReceiveResult() code=" + resultCode + ", "
                            + "data=" + data);

                    if (resultCode == RESULT_CODE_SYSTEM_DATA_TRANSFER_ALLOWED
                            || resultCode == RESULT_CODE_SYSTEM_DATA_TRANSFER_DISALLOWED) {
                        final PermissionSyncRequest request =
                                data.getParcelable(EXTRA_PERMISSION_SYNC_REQUEST,
                                        PermissionSyncRequest.class);
                        if (request != null) {
                            request.setUserConsented(
                                    resultCode == RESULT_CODE_SYSTEM_DATA_TRANSFER_ALLOWED);
                            Slog.i(LOG_TAG, "Recording request: " + request);
                            mSystemDataTransferRequestStore.writeRequest(request.getUserId(),
                                    request);
                        }

                        return;
                    }

                    Slog.e(LOG_TAG, "Unknown result code:" + resultCode);
                }
            };

    private class Transport {
        private final InputStream mRemoteIn;
        private final OutputStream mRemoteOut;

        private volatile boolean mStopped;

        public Transport(ParcelFileDescriptor fd) {
            mRemoteIn = new ParcelFileDescriptor.AutoCloseInputStream(fd);
            mRemoteOut = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
        }

        public void start() {
            new Thread(() -> {
                try {
                    while (!mStopped) {
                        processNextCommand();
                    }
                } catch (IOException e) {
                    if (!mStopped) {
                        Slog.w(LOG_TAG, "Trouble during transport", e);
                        stop();
                    }
                }
            }).start();
        }

        public void stop() {
            mStopped = true;

            IoUtils.closeQuietly(mRemoteIn);
            IoUtils.closeQuietly(mRemoteOut);
        }

        private void processNextCommand() throws IOException {
            Slog.d(LOG_TAG, "Waiting for next command...");

            // Read message header
            final byte[] headerBytes = new byte[8];
            Streams.readFully(mRemoteIn, headerBytes);
            final ByteBuffer header = ByteBuffer.wrap(headerBytes);
            final int command = header.getInt();
            final int length = header.getInt();

            Slog.d(LOG_TAG, "Received command 0x" + Integer.toHexString(command)
                    + " length " + length);
            switch (command) {
                case 0x50490000: // PI(NG) version 0
                    // Repeat back the given payload, within reason
                    final int target = Math.min(length, 1_000_000);
                    final byte[] payload = new byte[target];
                    Streams.readFully(mRemoteIn, payload);
                    Streams.skipByReading(mRemoteIn, length - target);

                    // Respond with PO(NG) version 0
                    header.rewind();
                    header.putInt(0x504F0000);
                    header.putInt(target);
                    mRemoteOut.write(header.array());
                    mRemoteOut.write(payload);
                    break;

                default:
                    // Emit local warning, and skip message to
                    // handle next one
                    Slog.w(LOG_TAG, "Unknown command 0x" + Integer.toHexString(command));
                    mRemoteIn.skip(length);
            }
        }
    }
}
