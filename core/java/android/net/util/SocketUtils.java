/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.net.util;

import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_BINDTODEVICE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.net.MacAddress;
import android.net.NetworkUtils;
import android.system.ErrnoException;
import android.system.NetlinkSocketAddress;
import android.system.Os;
import android.system.PacketSocketAddress;
import android.system.StructTimeval;

import libcore.io.IoBridge;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * Collection of utilities to interact with raw sockets.
 * @hide
 */
@SystemApi
@TestApi
public final class SocketUtils {
    /**
     * Create a raw datagram socket that is bound to an interface.
     *
     * <p>Data sent through the socket will go directly to the underlying network, ignoring VPNs.
     */
    public static void bindSocketToInterface(@NonNull FileDescriptor socket, @NonNull String iface)
            throws ErrnoException {
        // SO_BINDTODEVICE actually takes a string. This works because the first member
        // of struct ifreq is a NULL-terminated interface name.
        // TODO: add a setsockoptString()
        Os.setsockoptIfreq(socket, SOL_SOCKET, SO_BINDTODEVICE, iface);
        NetworkUtils.protectFromVpn(socket);
    }

    /**
     * Make a socket address to communicate with netlink.
     */
    @NonNull
    public static SocketAddress makeNetlinkSocketAddress(int portId, int groupsMask) {
        return new NetlinkSocketAddress(portId, groupsMask);
    }

    /**
     * Make socket address that packet sockets can bind to.
     */
    @NonNull
    public static SocketAddress makePacketSocketAddress(int protocol, int ifIndex) {
        return new PacketSocketAddress((short) protocol, ifIndex);
    }

    /**
     * Make a socket address that packet socket can send packets to.
     */
    @NonNull
    public static SocketAddress makePacketSocketAddress(int ifIndex, @NonNull byte[] hwAddr) {
        return new PacketSocketAddress(ifIndex, hwAddr);
    }

    /**
     * Set an option on a socket that takes a time value argument.
     */
    public static void setSocketTimeValueOption(
            @NonNull FileDescriptor fd, int level, int option, long millis) throws ErrnoException {
        Os.setsockoptTimeval(fd, level, option, StructTimeval.fromMillis(millis));
    }

    /**
     * @see IoBridge#closeAndSignalBlockedThreads(FileDescriptor)
     */
    public static void closeSocket(@Nullable FileDescriptor fd) throws IOException {
        IoBridge.closeAndSignalBlockedThreads(fd);
    }

    /**
     * Attaches a socket filter that accepts DHCP packets to the given socket.
     */
    public static void attachDhcpFilter(@NonNull FileDescriptor fd) throws SocketException {
        NetworkUtils.attachDhcpFilter(fd);
    }

    /**
     * Attaches a socket filter that accepts ICMPv6 router advertisements to the given socket.
     * @param fd the socket's {@link FileDescriptor}.
     * @param packetType the hardware address type, one of ARPHRD_*.
     */
    public static void attachRaFilter(@NonNull FileDescriptor fd, int packetType)
            throws SocketException {
        NetworkUtils.attachRaFilter(fd, packetType);
    }

    /**
     * Attaches a socket filter that accepts L2-L4 signaling traffic required for IP connectivity.
     *
     * This includes: all ARP, ICMPv6 RS/RA/NS/NA messages, and DHCPv4 exchanges.
     *
     * @param fd the socket's {@link FileDescriptor}.
     * @param packetType the hardware address type, one of ARPHRD_*.
     */
    public static void attachControlPacketFilter(@NonNull FileDescriptor fd, int packetType)
            throws SocketException {
        NetworkUtils.attachControlPacketFilter(fd, packetType);
    }

    /**
     * Add an entry into the ARP cache.
     */
    public static void addArpEntry(@NonNull Inet4Address ipv4Addr, @NonNull MacAddress ethAddr,
            @NonNull String ifname, @NonNull FileDescriptor fd) throws IOException {
        NetworkUtils.addArpEntry(ipv4Addr, ethAddr, ifname, fd);
    }

    private SocketUtils() {}
}
