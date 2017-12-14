/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.AndroidException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import dalvik.system.CloseGuard;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;

/**
 * This class contains methods for managing IPsec sessions. Once configured, the kernel will apply
 * confidentiality (encryption) and integrity (authentication) to IP traffic.
 *
 * <p>Note that not all aspects of IPsec are permitted by this API. Applications may create
 * transport mode security associations and apply them to individual sockets. Applications looking
 * to create a VPN should use {@link VpnService}.
 *
 * @see <a href="https://tools.ietf.org/html/rfc4301">RFC 4301, Security Architecture for the
 *     Internet Protocol</a>
 */
@SystemService(Context.IPSEC_SERVICE)
public final class IpSecManager {
    private static final String TAG = "IpSecManager";

    /**
     * The Security Parameter Index (SPI) 0 indicates an unknown or invalid index.
     *
     * <p>No IPsec packet may contain an SPI of 0.
     *
     * @hide
     */
    @TestApi public static final int INVALID_SECURITY_PARAMETER_INDEX = 0;

    /** @hide */
    public interface Status {
        public static final int OK = 0;
        public static final int RESOURCE_UNAVAILABLE = 1;
        public static final int SPI_UNAVAILABLE = 2;
    }

    /** @hide */
    public static final int INVALID_RESOURCE_ID = 0;

    /**
     * Thrown to indicate that a requested SPI is in use.
     *
     * <p>The combination of remote {@code InetAddress} and SPI must be unique across all apps on
     * one device. If this error is encountered, a new SPI is required before a transform may be
     * created. This error can be avoided by calling {@link
     * IpSecManager#allocateSecurityParameterIndex}.
     */
    public static final class SpiUnavailableException extends AndroidException {
        private final int mSpi;

        /**
         * Construct an exception indicating that a transform with the given SPI is already in use
         * or otherwise unavailable.
         *
         * @param msg description indicating the colliding SPI
         * @param spi the SPI that could not be used due to a collision
         */
        SpiUnavailableException(String msg, int spi) {
            super(msg + " (spi: " + spi + ")");
            mSpi = spi;
        }

        /** Get the SPI that caused a collision. */
        public int getSpi() {
            return mSpi;
        }
    }

    /**
     * Thrown to indicate that an IPsec resource is unavailable.
     *
     * <p>This could apply to resources such as sockets, {@link SecurityParameterIndex}, {@link
     * IpSecTransform}, or other system resources. If this exception is thrown, users should release
     * allocated objects of the type requested.
     */
    public static final class ResourceUnavailableException extends AndroidException {

        ResourceUnavailableException(String msg) {
            super(msg);
        }
    }

    private final IIpSecService mService;

    /**
     * This class represents a reserved SPI.
     *
     * <p>Objects of this type are used to track reserved security parameter indices. They can be
     * obtained by calling {@link IpSecManager#allocateSecurityParameterIndex} and must be released
     * by calling {@link #close()} when they are no longer needed.
     */
    public static final class SecurityParameterIndex implements AutoCloseable {
        private final IIpSecService mService;
        private final InetAddress mRemoteAddress;
        private final CloseGuard mCloseGuard = CloseGuard.get();
        private int mSpi = INVALID_SECURITY_PARAMETER_INDEX;
        private int mResourceId;

        /** Get the underlying SPI held by this object. */
        public int getSpi() {
            return mSpi;
        }

        /**
         * Release an SPI that was previously reserved.
         *
         * <p>Release an SPI for use by other users in the system. If a SecurityParameterIndex is
         * applied to an IpSecTransform, it will become unusable for future transforms but should
         * still be closed to ensure system resources are released.
         */
        @Override
        public void close() {
            try {
                mService.releaseSecurityParameterIndex(mResourceId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mCloseGuard.close();
        }

        /** Check that the SPI was closed properly. */
        @Override
        protected void finalize() throws Throwable {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            close();
        }

        private SecurityParameterIndex(
                @NonNull IIpSecService service, int direction, InetAddress remoteAddress, int spi)
                throws ResourceUnavailableException, SpiUnavailableException {
            mService = service;
            mRemoteAddress = remoteAddress;
            try {
                IpSecSpiResponse result =
                        mService.allocateSecurityParameterIndex(
                                direction, remoteAddress.getHostAddress(), spi, new Binder());

                if (result == null) {
                    throw new NullPointerException("Received null response from IpSecService");
                }

                int status = result.status;
                switch (status) {
                    case Status.OK:
                        break;
                    case Status.RESOURCE_UNAVAILABLE:
                        throw new ResourceUnavailableException(
                                "No more SPIs may be allocated by this requester.");
                    case Status.SPI_UNAVAILABLE:
                        throw new SpiUnavailableException("Requested SPI is unavailable", spi);
                    default:
                        throw new RuntimeException(
                                "Unknown status returned by IpSecService: " + status);
                }
                mSpi = result.spi;
                mResourceId = result.resourceId;

                if (mSpi == INVALID_SECURITY_PARAMETER_INDEX) {
                    throw new RuntimeException("Invalid SPI returned by IpSecService: " + status);
                }

                if (mResourceId == INVALID_RESOURCE_ID) {
                    throw new RuntimeException(
                            "Invalid Resource ID returned by IpSecService: " + status);
                }

            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mCloseGuard.open("open");
        }

        /** @hide */
        @VisibleForTesting
        public int getResourceId() {
            return mResourceId;
        }
    }

    /**
     * Reserve a random SPI for traffic bound to or from the specified remote address.
     *
     * <p>If successful, this SPI is guaranteed available until released by a call to {@link
     * SecurityParameterIndex#close()}.
     *
     * @param direction {@link IpSecTransform#DIRECTION_IN} or {@link IpSecTransform#DIRECTION_OUT}
     * @param remoteAddress address of the remote. SPIs must be unique for each remoteAddress
     * @return the reserved SecurityParameterIndex
     * @throws ResourceUnavailableException indicating that too many SPIs are currently allocated
     *     for this user
     * @throws SpiUnavailableException indicating that a particular SPI cannot be reserved
     */
    public SecurityParameterIndex allocateSecurityParameterIndex(
            int direction, InetAddress remoteAddress) throws ResourceUnavailableException {
        try {
            return new SecurityParameterIndex(
                    mService,
                    direction,
                    remoteAddress,
                    IpSecManager.INVALID_SECURITY_PARAMETER_INDEX);
        } catch (SpiUnavailableException unlikely) {
            throw new ResourceUnavailableException("No SPIs available");
        }
    }

    /**
     * Reserve the requested SPI for traffic bound to or from the specified remote address.
     *
     * <p>If successful, this SPI is guaranteed available until released by a call to {@link
     * SecurityParameterIndex#close()}.
     *
     * @param direction {@link IpSecTransform#DIRECTION_IN} or {@link IpSecTransform#DIRECTION_OUT}
     * @param remoteAddress address of the remote. SPIs must be unique for each remoteAddress
     * @param requestedSpi the requested SPI, or '0' to allocate a random SPI
     * @return the reserved SecurityParameterIndex
     * @throws ResourceUnavailableException indicating that too many SPIs are currently allocated
     *     for this user
     * @throws SpiUnavailableException indicating that the requested SPI could not be reserved
     */
    public SecurityParameterIndex allocateSecurityParameterIndex(
            int direction, InetAddress remoteAddress, int requestedSpi)
            throws SpiUnavailableException, ResourceUnavailableException {
        if (requestedSpi == IpSecManager.INVALID_SECURITY_PARAMETER_INDEX) {
            throw new IllegalArgumentException("Requested SPI must be a valid (non-zero) SPI");
        }
        return new SecurityParameterIndex(mService, direction, remoteAddress, requestedSpi);
    }

    /**
     * Apply an IPsec transform to a stream socket.
     *
     * <p>This applies transport mode encapsulation to the given socket. Once applied, I/O on the
     * socket will be encapsulated according to the parameters of the {@code IpSecTransform}. When
     * the transform is removed from the socket by calling {@link #removeTransportModeTransform},
     * unprotected traffic can resume on that socket.
     *
     * <p>For security reasons, the destination address of any traffic on the socket must match the
     * remote {@code InetAddress} of the {@code IpSecTransform}. Attempts to send traffic to any
     * other IP address will result in an IOException. In addition, reads and writes on the socket
     * will throw IOException if the user deactivates the transform (by calling {@link
     * IpSecTransform#close()}) without calling {@link #removeTransportModeTransform}.
     *
     * <h4>Rekey Procedure</h4>
     *
     * <p>When applying a new tranform to a socket, the previous transform will be removed. However,
     * inbound traffic on the old transform will continue to be decrypted until that transform is
     * deallocated by calling {@link IpSecTransform#close()}. This overlap allows rekey procedures
     * where both transforms are valid until both endpoints are using the new transform and all
     * in-flight packets have been received.
     *
     * @param socket a stream socket
     * @param transform a transport mode {@code IpSecTransform}
     * @throws IOException indicating that the transform could not be applied
     * @hide
     */
    public void applyTransportModeTransform(Socket socket, IpSecTransform transform)
            throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(socket)) {
            applyTransportModeTransform(pfd, transform);
        }
    }

    /**
     * Apply an IPsec transform to a datagram socket.
     *
     * <p>This applies transport mode encapsulation to the given socket. Once applied, I/O on the
     * socket will be encapsulated according to the parameters of the {@code IpSecTransform}. When
     * the transform is removed from the socket by calling {@link #removeTransportModeTransform},
     * unprotected traffic can resume on that socket.
     *
     * <p>For security reasons, the destination address of any traffic on the socket must match the
     * remote {@code InetAddress} of the {@code IpSecTransform}. Attempts to send traffic to any
     * other IP address will result in an IOException. In addition, reads and writes on the socket
     * will throw IOException if the user deactivates the transform (by calling {@link
     * IpSecTransform#close()}) without calling {@link #removeTransportModeTransform}.
     *
     * <h4>Rekey Procedure</h4>
     *
     * <p>When applying a new tranform to a socket, the previous transform will be removed. However,
     * inbound traffic on the old transform will continue to be decrypted until that transform is
     * deallocated by calling {@link IpSecTransform#close()}. This overlap allows rekey procedures
     * where both transforms are valid until both endpoints are using the new transform and all
     * in-flight packets have been received.
     *
     * @param socket a datagram socket
     * @param transform a transport mode {@code IpSecTransform}
     * @throws IOException indicating that the transform could not be applied
     * @hide
     */
    public void applyTransportModeTransform(DatagramSocket socket, IpSecTransform transform)
            throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromDatagramSocket(socket)) {
            applyTransportModeTransform(pfd, transform);
        }
    }

    /**
     * Apply an IPsec transform to a socket.
     *
     * <p>This applies transport mode encapsulation to the given socket. Once applied, I/O on the
     * socket will be encapsulated according to the parameters of the {@code IpSecTransform}. When
     * the transform is removed from the socket by calling {@link #removeTransportModeTransform},
     * unprotected traffic can resume on that socket.
     *
     * <p>For security reasons, the destination address of any traffic on the socket must match the
     * remote {@code InetAddress} of the {@code IpSecTransform}. Attempts to send traffic to any
     * other IP address will result in an IOException. In addition, reads and writes on the socket
     * will throw IOException if the user deactivates the transform (by calling {@link
     * IpSecTransform#close()}) without calling {@link #removeTransportModeTransform}.
     *
     * <h4>Rekey Procedure</h4>
     *
     * <p>When applying a new tranform to a socket, the previous transform will be removed. However,
     * inbound traffic on the old transform will continue to be decrypted until that transform is
     * deallocated by calling {@link IpSecTransform#close()}. This overlap allows rekey procedures
     * where both transforms are valid until both endpoints are using the new transform and all
     * in-flight packets have been received.
     *
     * @param socket a socket file descriptor
     * @param transform a transport mode {@code IpSecTransform}
     * @throws IOException indicating that the transform could not be applied
     */
    public void applyTransportModeTransform(FileDescriptor socket, IpSecTransform transform)
            throws IOException {
        // We dup() the FileDescriptor here because if we don't, then the ParcelFileDescriptor()
        // constructor takes control and closes the user's FD when we exit the method
        // This is behaviorally the same as the other versions, but the PFD constructor does not
        // dup() automatically, whereas PFD.fromSocket() and PDF.fromDatagramSocket() do dup().
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(socket)) {
            applyTransportModeTransform(pfd, transform);
        }
    }

    /* Call down to activate a transform */
    private void applyTransportModeTransform(ParcelFileDescriptor pfd, IpSecTransform transform) {
        try {
            mService.applyTransportModeTransform(pfd, transform.getResourceId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Apply an active Tunnel Mode IPsec Transform to a network, which will tunnel all traffic to
     * and from that network's interface with IPsec (applies an outer IP header and IPsec Header to
     * all traffic, and expects an additional IP header and IPsec Header on all inbound traffic).
     * Applications should probably not use this API directly. Instead, they should use {@link
     * VpnService} to provide VPN capability in a more generic fashion.
     *
     * <p>TODO: Update javadoc for tunnel mode APIs at the same time the APIs are re-worked.
     *
     * @param net a {@link Network} that will be tunneled via IP Sec.
     * @param transform an {@link IpSecTransform}, which must be an active Tunnel Mode transform.
     * @hide
     */
    public void applyTunnelModeTransform(Network net, IpSecTransform transform) {}

    /**
     * Remove an IPsec transform from a stream socket.
     *
     * <p>Once removed, traffic on the socket will not be encrypted. This operation will succeed
     * regardless of the state of the transform. Removing a transform from a socket allows the
     * socket to be reused for communication in the clear.
     *
     * <p>If an {@code IpSecTransform} object applied to this socket was deallocated by calling
     * {@link IpSecTransform#close()}, then communication on the socket will fail until this method
     * is called.
     *
     * @param socket a socket that previously had a transform applied to it
     * @param transform the IPsec Transform that was previously applied to the given socket
     * @throws IOException indicating that the transform could not be removed from the socket
     * @hide
     */
    public void removeTransportModeTransform(Socket socket, IpSecTransform transform)
            throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(socket)) {
            removeTransportModeTransform(pfd, transform);
        }
    }

    /**
     * Remove an IPsec transform from a datagram socket.
     *
     * <p>Once removed, traffic on the socket will not be encrypted. This operation will succeed
     * regardless of the state of the transform. Removing a transform from a socket allows the
     * socket to be reused for communication in the clear.
     *
     * <p>If an {@code IpSecTransform} object applied to this socket was deallocated by calling
     * {@link IpSecTransform#close()}, then communication on the socket will fail until this method
     * is called.
     *
     * @param socket a socket that previously had a transform applied to it
     * @param transform the IPsec Transform that was previously applied to the given socket
     * @throws IOException indicating that the transform could not be removed from the socket
     * @hide
     */
    public void removeTransportModeTransform(DatagramSocket socket, IpSecTransform transform)
            throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromDatagramSocket(socket)) {
            removeTransportModeTransform(pfd, transform);
        }
    }

    /**
     * Remove an IPsec transform from a socket.
     *
     * <p>Once removed, traffic on the socket will not be encrypted. This operation will succeed
     * regardless of the state of the transform. Removing a transform from a socket allows the
     * socket to be reused for communication in the clear.
     *
     * <p>If an {@code IpSecTransform} object applied to this socket was deallocated by calling
     * {@link IpSecTransform#close()}, then communication on the socket will fail until this method
     * is called.
     *
     * @param socket a socket that previously had a transform applied to it
     * @param transform the IPsec Transform that was previously applied to the given socket
     * @throws IOException indicating that the transform could not be removed from the socket
     */
    public void removeTransportModeTransform(FileDescriptor socket, IpSecTransform transform)
            throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(socket)) {
            removeTransportModeTransform(pfd, transform);
        }
    }

    /* Call down to remove a transform */
    private void removeTransportModeTransform(ParcelFileDescriptor pfd, IpSecTransform transform) {
        try {
            mService.removeTransportModeTransform(pfd, transform.getResourceId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove a Tunnel Mode IPsec Transform from a {@link Network}. This must be used as part of
     * cleanup if a tunneled Network experiences a change in default route. The Network will drop
     * all traffic that cannot be routed to the Tunnel's outbound interface. If that interface is
     * lost, all traffic will drop.
     *
     * <p>TODO: Update javadoc for tunnel mode APIs at the same time the APIs are re-worked.
     *
     * @param net a network that currently has transform applied to it.
     * @param transform a Tunnel Mode IPsec Transform that has been previously applied to the given
     *     network
     * @hide
     */
    public void removeTunnelModeTransform(Network net, IpSecTransform transform) {}

    /**
     * This class provides access to a UDP encapsulation Socket.
     *
     * <p>{@code UdpEncapsulationSocket} wraps a system-provided datagram socket intended for IKEv2
     * signalling and UDP encapsulated IPsec traffic. Instances can be obtained by calling {@link
     * IpSecManager#openUdpEncapsulationSocket}. The provided socket cannot be re-bound by the
     * caller. The caller should not close the {@code FileDescriptor} returned by {@link
     * #getSocket}, but should use {@link #close} instead.
     *
     * <p>Allowing the user to close or unbind a UDP encapsulation socket could impact the traffic
     * of the next user who binds to that port. To prevent this scenario, these sockets are held
     * open by the system so that they may only be closed by calling {@link #close} or when the user
     * process exits.
     */
    public static final class UdpEncapsulationSocket implements AutoCloseable {
        private final ParcelFileDescriptor mPfd;
        private final IIpSecService mService;
        private final int mResourceId;
        private final int mPort;
        private final CloseGuard mCloseGuard = CloseGuard.get();

        private UdpEncapsulationSocket(@NonNull IIpSecService service, int port)
                throws ResourceUnavailableException, IOException {
            mService = service;
            try {
                IpSecUdpEncapResponse result =
                        mService.openUdpEncapsulationSocket(port, new Binder());
                switch (result.status) {
                    case Status.OK:
                        break;
                    case Status.RESOURCE_UNAVAILABLE:
                        throw new ResourceUnavailableException(
                                "No more Sockets may be allocated by this requester.");
                    default:
                        throw new RuntimeException(
                                "Unknown status returned by IpSecService: " + result.status);
                }
                mResourceId = result.resourceId;
                mPort = result.port;
                mPfd = result.fileDescriptor;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mCloseGuard.open("constructor");
        }

        /** Get the wrapped socket. */
        public FileDescriptor getSocket() {
            if (mPfd == null) {
                return null;
            }
            return mPfd.getFileDescriptor();
        }

        /** Get the bound port of the wrapped socket. */
        public int getPort() {
            return mPort;
        }

        /**
         * Close this socket.
         *
         * <p>This closes the wrapped socket. Open encapsulation sockets count against a user's
         * resource limits, and forgetting to close them eventually will result in {@link
         * ResourceUnavailableException} being thrown.
         */
        @Override
        public void close() throws IOException {
            try {
                mService.closeUdpEncapsulationSocket(mResourceId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }

            try {
                mPfd.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close UDP Encapsulation Socket with Port= " + mPort);
                throw e;
            }
            mCloseGuard.close();
        }

        /** Check that the socket was closed properly. */
        @Override
        protected void finalize() throws Throwable {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            close();
        }

        /** @hide */
        @VisibleForTesting
        public int getResourceId() {
            return mResourceId;
        }
    };

    /**
     * Open a socket for UDP encapsulation and bind to the given port.
     *
     * <p>See {@link UdpEncapsulationSocket} for the proper way to close the returned socket.
     *
     * @param port a local UDP port
     * @return a socket that is bound to the given port
     * @throws IOException indicating that the socket could not be opened or bound
     * @throws ResourceUnavailableException indicating that too many encapsulation sockets are open
     */
    // Returning a socket in this fashion that has been created and bound by the system
    // is the only safe way to ensure that a socket is both accessible to the user and
    // safely usable for Encapsulation without allowing a user to possibly unbind from/close
    // the port, which could potentially impact the traffic of the next user who binds to that
    // socket.
    public UdpEncapsulationSocket openUdpEncapsulationSocket(int port)
            throws IOException, ResourceUnavailableException {
        /*
         * Most range checking is done in the service, but this version of the constructor expects
         * a valid port number, and zero cannot be checked after being passed to the service.
         */
        if (port == 0) {
            throw new IllegalArgumentException("Specified port must be a valid port number!");
        }
        return new UdpEncapsulationSocket(mService, port);
    }

    /**
     * Open a socket for UDP encapsulation.
     *
     * <p>See {@link UdpEncapsulationSocket} for the proper way to close the returned socket.
     *
     * <p>The local port of the returned socket can be obtained by calling {@link
     * UdpEncapsulationSocket#getPort()}.
     *
     * @return a socket that is bound to a local port
     * @throws IOException indicating that the socket could not be opened or bound
     * @throws ResourceUnavailableException indicating that too many encapsulation sockets are open
     */
    // Returning a socket in this fashion that has been created and bound by the system
    // is the only safe way to ensure that a socket is both accessible to the user and
    // safely usable for Encapsulation without allowing a user to possibly unbind from/close
    // the port, which could potentially impact the traffic of the next user who binds to that
    // socket.
    public UdpEncapsulationSocket openUdpEncapsulationSocket()
            throws IOException, ResourceUnavailableException {
        return new UdpEncapsulationSocket(mService, 0);
    }

    /**
     * Construct an instance of IpSecManager within an application context.
     *
     * @param context the application context for this manager
     * @hide
     */
    public IpSecManager(IIpSecService service) {
        mService = checkNotNull(service, "missing service");
    }
}
