/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net;

import android.annotation.NonNull;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import java.io.FileDescriptor;
import java.net.Socket;
import java.util.concurrent.Executor;

/** @hide */
final class TcpSocketKeepalive extends SocketKeepalive {

    private final Socket mSocket;

    TcpSocketKeepalive(@NonNull IConnectivityManager service,
            @NonNull Network network,
            @NonNull Socket socket,
            @NonNull Executor executor,
            @NonNull Callback callback) {
        super(service, network, executor, callback);
        mSocket = socket;
    }

    /**
     * Starts keepalives. {@code mSocket} must be a connected TCP socket.
     *
     * - The application must not write to or read from the socket after calling this method, until
     *   onDataReceived, onStopped, or onError are called. If it does, the keepalive will fail
     *   with {@link #ERROR_SOCKET_NOT_IDLE}, or {@code #ERROR_INVALID_SOCKET} if the socket
     *   experienced an error (as in poll(2) returned POLLERR); if this happens, the data received
     *   from the socket may be invalid, and the socket can't be recovered.
     * - If the socket has data in the send or receive buffer, then this call will fail with
     *   {@link #ERROR_SOCKET_NOT_IDLE} and can be retried after the data has been processed.
     *   An app could ensure this by using an application-layer protocol where it can receive
     *   acknowledgement that it will go into keepalive mode. It could then go into keepalive
     *   mode after having read the acknowledgement, draining the socket.
     */
    @Override
    void startImpl(int intervalSec) {
        try {
            final FileDescriptor fd = mSocket.getFileDescriptor$();
            mService.startTcpKeepalive(mNetwork, fd, intervalSec, mMessenger, new Binder());
        } catch (RemoteException e) {
            Log.e(TAG, "Error starting packet keepalive: ", e);
            stopLooper();
        }
    }

    @Override
    void stopImpl() {
        try {
            if (mSlot != null) {
                mService.stopKeepalive(mNetwork, mSlot);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error stopping packet keepalive: ", e);
            stopLooper();
        }
    }
}
