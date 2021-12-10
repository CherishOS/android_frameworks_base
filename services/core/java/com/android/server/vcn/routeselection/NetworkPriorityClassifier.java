/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.vcn.routeselection;

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_TEST;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.vcn.VcnUnderlyingNetworkPriority.NETWORK_QUALITY_ANY;
import static android.net.vcn.VcnUnderlyingNetworkPriority.NETWORK_QUALITY_OK;

import static com.android.server.VcnManagementService.LOCAL_LOG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkCapabilities;
import android.net.TelephonyNetworkSpecifier;
import android.net.vcn.VcnCellUnderlyingNetworkPriority;
import android.net.vcn.VcnManager;
import android.net.vcn.VcnUnderlyingNetworkPriority;
import android.net.vcn.VcnWifiUnderlyingNetworkPriority;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.VcnContext;

import java.util.LinkedHashSet;
import java.util.Set;

/** @hide */
class NetworkPriorityClassifier {
    @NonNull private static final String TAG = NetworkPriorityClassifier.class.getSimpleName();
    /**
     * Minimum signal strength for a WiFi network to be eligible for switching to
     *
     * <p>A network that satisfies this is eligible to become the selected underlying network with
     * no additional conditions
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int WIFI_ENTRY_RSSI_THRESHOLD_DEFAULT = -70;
    /**
     * Minimum signal strength to continue using a WiFi network
     *
     * <p>A network that satisfies the conditions may ONLY continue to be used if it is already
     * selected as the underlying network. A WiFi network satisfying this condition, but NOT the
     * prospective-network RSSI threshold CANNOT be switched to.
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int WIFI_EXIT_RSSI_THRESHOLD_DEFAULT = -74;

    /** Priority for any other networks (including unvalidated, etc) */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int PRIORITY_ANY = Integer.MAX_VALUE;

    /** Gives networks a priority class, based on configured VcnGatewayConnectionConfig */
    public static int calculatePriorityClass(
            VcnContext vcnContext,
            UnderlyingNetworkRecord networkRecord,
            LinkedHashSet<VcnUnderlyingNetworkPriority> underlyingNetworkPriorities,
            ParcelUuid subscriptionGroup,
            TelephonySubscriptionSnapshot snapshot,
            UnderlyingNetworkRecord currentlySelected,
            PersistableBundle carrierConfig) {
        // mRouteSelectionNetworkRequest requires a network be both VALIDATED and NOT_SUSPENDED

        if (networkRecord.isBlocked) {
            logWtf("Network blocked for System Server: " + networkRecord.network);
            return PRIORITY_ANY;
        }

        if (snapshot == null) {
            logWtf("Got null snapshot");
            return PRIORITY_ANY;
        }

        int priorityIndex = 0;
        for (VcnUnderlyingNetworkPriority nwPriority : underlyingNetworkPriorities) {
            if (checkMatchesPriorityRule(
                    vcnContext,
                    nwPriority,
                    networkRecord,
                    subscriptionGroup,
                    snapshot,
                    currentlySelected,
                    carrierConfig)) {
                return priorityIndex;
            }
            priorityIndex++;
        }
        return PRIORITY_ANY;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static boolean checkMatchesPriorityRule(
            VcnContext vcnContext,
            VcnUnderlyingNetworkPriority networkPriority,
            UnderlyingNetworkRecord networkRecord,
            ParcelUuid subscriptionGroup,
            TelephonySubscriptionSnapshot snapshot,
            UnderlyingNetworkRecord currentlySelected,
            PersistableBundle carrierConfig) {
        // TODO: Check Network Quality reported by metric monitors/probers.

        final NetworkCapabilities caps = networkRecord.networkCapabilities;
        if (!networkPriority.allowMetered() && !caps.hasCapability(NET_CAPABILITY_NOT_METERED)) {
            return false;
        }

        if (vcnContext.isInTestMode() && caps.hasTransport(TRANSPORT_TEST)) {
            return true;
        }

        if (networkPriority instanceof VcnWifiUnderlyingNetworkPriority) {
            return checkMatchesWifiPriorityRule(
                    (VcnWifiUnderlyingNetworkPriority) networkPriority,
                    networkRecord,
                    currentlySelected,
                    carrierConfig);
        }

        if (networkPriority instanceof VcnCellUnderlyingNetworkPriority) {
            return checkMatchesCellPriorityRule(
                    vcnContext,
                    (VcnCellUnderlyingNetworkPriority) networkPriority,
                    networkRecord,
                    subscriptionGroup,
                    snapshot);
        }

        logWtf(
                "Got unknown VcnUnderlyingNetworkPriority class: "
                        + networkPriority.getClass().getSimpleName());
        return false;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static boolean checkMatchesWifiPriorityRule(
            VcnWifiUnderlyingNetworkPriority networkPriority,
            UnderlyingNetworkRecord networkRecord,
            UnderlyingNetworkRecord currentlySelected,
            PersistableBundle carrierConfig) {
        final NetworkCapabilities caps = networkRecord.networkCapabilities;

        if (!caps.hasTransport(TRANSPORT_WIFI)) {
            return false;
        }

        // TODO: Move the Network Quality check to the network metric monitor framework.
        if (networkPriority.getNetworkQuality()
                > getWifiQuality(networkRecord, currentlySelected, carrierConfig)) {
            return false;
        }

        if (networkPriority.getSsid() != null && networkPriority.getSsid() != caps.getSsid()) {
            return false;
        }

        return true;
    }

    private static int getWifiQuality(
            UnderlyingNetworkRecord networkRecord,
            UnderlyingNetworkRecord currentlySelected,
            PersistableBundle carrierConfig) {
        final NetworkCapabilities caps = networkRecord.networkCapabilities;
        final boolean isSelectedNetwork =
                currentlySelected != null
                        && networkRecord.network.equals(currentlySelected.network);

        if (isSelectedNetwork
                && caps.getSignalStrength() >= getWifiExitRssiThreshold(carrierConfig)) {
            return NETWORK_QUALITY_OK;
        }

        if (caps.getSignalStrength() >= getWifiEntryRssiThreshold(carrierConfig)) {
            return NETWORK_QUALITY_OK;
        }

        return NETWORK_QUALITY_ANY;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static boolean checkMatchesCellPriorityRule(
            VcnContext vcnContext,
            VcnCellUnderlyingNetworkPriority networkPriority,
            UnderlyingNetworkRecord networkRecord,
            ParcelUuid subscriptionGroup,
            TelephonySubscriptionSnapshot snapshot) {
        final NetworkCapabilities caps = networkRecord.networkCapabilities;

        if (!caps.hasTransport(TRANSPORT_CELLULAR)) {
            return false;
        }

        final TelephonyNetworkSpecifier telephonyNetworkSpecifier =
                ((TelephonyNetworkSpecifier) caps.getNetworkSpecifier());
        if (telephonyNetworkSpecifier == null) {
            logWtf("Got null NetworkSpecifier");
            return false;
        }

        final int subId = telephonyNetworkSpecifier.getSubscriptionId();
        final TelephonyManager subIdSpecificTelephonyMgr =
                vcnContext
                        .getContext()
                        .getSystemService(TelephonyManager.class)
                        .createForSubscriptionId(subId);

        if (!networkPriority.getAllowedOperatorPlmnIds().isEmpty()) {
            final String plmnId = subIdSpecificTelephonyMgr.getNetworkOperator();
            if (!networkPriority.getAllowedOperatorPlmnIds().contains(plmnId)) {
                return false;
            }
        }

        if (!networkPriority.getAllowedSpecificCarrierIds().isEmpty()) {
            final int carrierId = subIdSpecificTelephonyMgr.getSimSpecificCarrierId();
            if (!networkPriority.getAllowedSpecificCarrierIds().contains(carrierId)) {
                return false;
            }
        }

        if (!networkPriority.allowRoaming() && !caps.hasCapability(NET_CAPABILITY_NOT_ROAMING)) {
            return false;
        }

        if (networkPriority.requireOpportunistic()) {
            if (!isOpportunistic(snapshot, caps.getSubscriptionIds())) {
                return false;
            }

            // If this carrier is the active data provider, ensure that opportunistic is only
            // ever prioritized if it is also the active data subscription. This ensures that
            // if an opportunistic subscription is still in the process of being switched to,
            // or switched away from, the VCN does not attempt to continue using it against the
            // decision made at the telephony layer. Failure to do so may result in the modem
            // switching back and forth.
            //
            // Allow the following two cases:
            // 1. Active subId is NOT in the group that this VCN is supporting
            // 2. This opportunistic subscription is for the active subId
            if (snapshot.getAllSubIdsInGroup(subscriptionGroup)
                            .contains(SubscriptionManager.getActiveDataSubscriptionId())
                    && !caps.getSubscriptionIds()
                            .contains(SubscriptionManager.getActiveDataSubscriptionId())) {
                return false;
            }
        }

        return true;
    }

    static boolean isOpportunistic(
            @NonNull TelephonySubscriptionSnapshot snapshot, Set<Integer> subIds) {
        if (snapshot == null) {
            logWtf("Got null snapshot");
            return false;
        }
        for (int subId : subIds) {
            if (snapshot.isOpportunistic(subId)) {
                return true;
            }
        }
        return false;
    }

    static int getWifiEntryRssiThreshold(@Nullable PersistableBundle carrierConfig) {
        if (carrierConfig != null) {
            return carrierConfig.getInt(
                    VcnManager.VCN_NETWORK_SELECTION_WIFI_ENTRY_RSSI_THRESHOLD_KEY,
                    WIFI_ENTRY_RSSI_THRESHOLD_DEFAULT);
        }
        return WIFI_ENTRY_RSSI_THRESHOLD_DEFAULT;
    }

    static int getWifiExitRssiThreshold(@Nullable PersistableBundle carrierConfig) {
        if (carrierConfig != null) {
            return carrierConfig.getInt(
                    VcnManager.VCN_NETWORK_SELECTION_WIFI_EXIT_RSSI_THRESHOLD_KEY,
                    WIFI_EXIT_RSSI_THRESHOLD_DEFAULT);
        }
        return WIFI_EXIT_RSSI_THRESHOLD_DEFAULT;
    }

    private static void logWtf(String msg) {
        Slog.wtf(TAG, msg);
        LOCAL_LOG.log(TAG + " WTF: " + msg);
    }
}
