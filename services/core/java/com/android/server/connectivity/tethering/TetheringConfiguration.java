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

package com.android.server.connectivity.tethering;

import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_MOBILE_DUN;
import static android.net.ConnectivityManager.TYPE_MOBILE_HIPRI;

import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.StringJoiner;


/**
 * A utility class to encapsulate the various tethering configuration elements.
 *
 * This configuration data includes elements describing upstream properties
 * (preferred and required types of upstream connectivity as well as default
 * DNS servers to use if none are available) and downstream properties (such
 * as regular expressions use to match suitable downstream interfaces and the
 * DHCPv4 ranges to use).
 *
 * @hide
 */
public class TetheringConfiguration {
    private static final String TAG = TetheringConfiguration.class.getSimpleName();

    private static final int DUN_NOT_REQUIRED = 0;
    private static final int DUN_REQUIRED = 1;
    private static final int DUN_UNSPECIFIED = 2;

    // USB is  192.168.42.1 and 255.255.255.0
    // Wifi is 192.168.43.1 and 255.255.255.0
    // BT is limited to max default of 5 connections. 192.168.44.1 to 192.168.48.1
    // with 255.255.255.0
    // P2P is 192.168.49.1 and 255.255.255.0
    private static final String[] DHCP_DEFAULT_RANGE = {
        "192.168.42.2", "192.168.42.254", "192.168.43.2", "192.168.43.254",
        "192.168.44.2", "192.168.44.254", "192.168.45.2", "192.168.45.254",
        "192.168.46.2", "192.168.46.254", "192.168.47.2", "192.168.47.254",
        "192.168.48.2", "192.168.48.254", "192.168.49.2", "192.168.49.254",
    };

    private final String[] DEFAULT_IPV4_DNS = {"8.8.4.4", "8.8.8.8"};

    public final String[] tetherableUsbRegexs;
    public final String[] tetherableWifiRegexs;
    public final String[] tetherableBluetoothRegexs;
    public final boolean isDunRequired;
    public final Collection<Integer> preferredUpstreamIfaceTypes;
    public final String[] dhcpRanges;
    public final String[] defaultIPv4DNS;

    public TetheringConfiguration(Context ctx) {
        tetherableUsbRegexs = ctx.getResources().getStringArray(
                com.android.internal.R.array.config_tether_usb_regexs);
        tetherableWifiRegexs = ctx.getResources().getStringArray(
                com.android.internal.R.array.config_tether_wifi_regexs);
        tetherableBluetoothRegexs = ctx.getResources().getStringArray(
                com.android.internal.R.array.config_tether_bluetooth_regexs);

        isDunRequired = checkDunRequired(ctx);
        preferredUpstreamIfaceTypes = getUpstreamIfaceTypes(ctx, isDunRequired);

        dhcpRanges = getDhcpRanges(ctx);
        defaultIPv4DNS = copy(DEFAULT_IPV4_DNS);
    }

    public boolean isUsb(String iface) {
        return matchesDownstreamRegexs(iface, tetherableUsbRegexs);
    }

    public boolean isWifi(String iface) {
        return matchesDownstreamRegexs(iface, tetherableWifiRegexs);
    }

    public boolean isBluetooth(String iface) {
        return matchesDownstreamRegexs(iface, tetherableBluetoothRegexs);
    }

    public void dump(PrintWriter pw) {
        dumpStringArray(pw, "tetherableUsbRegexs", tetherableUsbRegexs);
        dumpStringArray(pw, "tetherableWifiRegexs", tetherableWifiRegexs);
        dumpStringArray(pw, "tetherableBluetoothRegexs", tetherableBluetoothRegexs);

        pw.print("isDunRequired: ");
        pw.println(isDunRequired);

        String[] upstreamTypes = null;
        if (preferredUpstreamIfaceTypes != null) {
            upstreamTypes = new String[preferredUpstreamIfaceTypes.size()];
            int i = 0;
            for (Integer netType : preferredUpstreamIfaceTypes) {
                upstreamTypes[i] = ConnectivityManager.getNetworkTypeName(netType);
                i++;
            }
        }
        dumpStringArray(pw, "preferredUpstreamIfaceTypes", upstreamTypes);

        dumpStringArray(pw, "dhcpRanges", dhcpRanges);
        dumpStringArray(pw, "defaultIPv4DNS", defaultIPv4DNS);
    }

    private static void dumpStringArray(PrintWriter pw, String label, String[] values) {
        pw.print(label);
        pw.print(": ");

        if (values != null) {
            final StringJoiner sj = new StringJoiner(", ", "[", "]");
            for (String value : values) { sj.add(value); }
            pw.print(sj.toString());
        } else {
            pw.print("null");
        }

        pw.println();
    }

    private static boolean checkDunRequired(Context ctx) {
        final TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
        final int secureSetting =
                (tm != null) ? tm.getTetherApnRequired() : DUN_UNSPECIFIED;
        return (secureSetting == DUN_REQUIRED);
    }

    private static Collection<Integer> getUpstreamIfaceTypes(Context ctx, boolean requiresDun) {
        final int ifaceTypes[] = ctx.getResources().getIntArray(
                com.android.internal.R.array.config_tether_upstream_types);
        final ArrayList<Integer> upstreamIfaceTypes = new ArrayList<>(ifaceTypes.length);
        for (int i : ifaceTypes) {
            switch (i) {
                case TYPE_MOBILE:
                case TYPE_MOBILE_HIPRI:
                    if (requiresDun) continue;
                    break;
                case TYPE_MOBILE_DUN:
                    if (!requiresDun) continue;
                    break;
            }
            upstreamIfaceTypes.add(i);
        }

        // Fix up upstream interface types for DUN or mobile. NOTE: independent
        // of the value of |requiresDun|, cell data of one form or another is
        // *always* an upstream, regardless of the upstream interface types
        // specified by configuration resources.
        if (requiresDun) {
            if (!upstreamIfaceTypes.contains(TYPE_MOBILE_DUN)) {
                upstreamIfaceTypes.add(TYPE_MOBILE_DUN);
            }
        } else {
            if (!upstreamIfaceTypes.contains(TYPE_MOBILE)) {
                upstreamIfaceTypes.add(TYPE_MOBILE);
            }
            if (!upstreamIfaceTypes.contains(TYPE_MOBILE_HIPRI)) {
                upstreamIfaceTypes.add(TYPE_MOBILE_HIPRI);
            }
        }

        return upstreamIfaceTypes;
    }

    private static boolean matchesDownstreamRegexs(String iface, String[] regexs) {
        for (String regex : regexs) {
            if (iface.matches(regex)) return true;
        }
        return false;
    }

    private static String[] getDhcpRanges(Context ctx) {
        final String[] fromResource = ctx.getResources().getStringArray(
                com.android.internal.R.array.config_tether_dhcp_range);
        if ((fromResource.length > 0) && (fromResource.length % 2 == 0)) {
            return fromResource;
        }
        return copy(DHCP_DEFAULT_RANGE);
    }

    private static String[] copy(String[] strarray) {
        return Arrays.copyOf(strarray, strarray.length);
    }
}
