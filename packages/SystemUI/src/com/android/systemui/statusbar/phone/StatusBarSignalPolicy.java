/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.os.Handler;
import android.telephony.SubscriptionInfo;
import android.util.ArraySet;
import android.util.Log;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkControllerImpl;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


public class StatusBarSignalPolicy implements NetworkControllerImpl.SignalCallback,
        SecurityController.SecurityControllerCallback, Tunable {
    private static final String TAG = "StatusBarSignalPolicy";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final String mSlotAirplane;
    private final String mSlotMobile;
    private final String mSlotWifi;
    private final String mSlotEthernet;
    private final String mSlotVpn;
    private final String mSlotNoCalling;

    private final Context mContext;
    private final StatusBarIconController mIconController;
    private final NetworkController mNetworkController;
    private final SecurityController mSecurityController;
    private final Handler mHandler = Handler.getMain();

    private boolean mHideAirplane;
    private boolean mHideMobile;
    private boolean mHideWifi;
    private boolean mHideEthernet;
    private boolean mActivityEnabled;
    private boolean mForceHideWifi;

    // Track as little state as possible, and only for padding purposes
    private boolean mIsAirplaneMode = false;
    private boolean mIsWifiEnabled = false;
    private boolean mWifiVisible = false;

    private ArrayList<MobileIconState> mMobileStates = new ArrayList<MobileIconState>();
    private ArrayList<NoCallingIconState> mNoCallingStates = new ArrayList<NoCallingIconState>();
    private WifiIconState mWifiIconState = new WifiIconState();

    public StatusBarSignalPolicy(Context context, StatusBarIconController iconController) {
        mContext = context;

        mSlotAirplane = mContext.getString(com.android.internal.R.string.status_bar_airplane);
        mSlotMobile   = mContext.getString(com.android.internal.R.string.status_bar_mobile);
        mSlotWifi     = mContext.getString(com.android.internal.R.string.status_bar_wifi);
        mSlotEthernet = mContext.getString(com.android.internal.R.string.status_bar_ethernet);
        mSlotVpn      = mContext.getString(com.android.internal.R.string.status_bar_vpn);
        mSlotNoCalling = mContext.getString(com.android.internal.R.string.status_bar_no_calling);
        mActivityEnabled = mContext.getResources().getBoolean(R.bool.config_showActivity);

        mIconController = iconController;
        mNetworkController = Dependency.get(NetworkController.class);
        mSecurityController = Dependency.get(SecurityController.class);

        Dependency.get(TunerService.class).addTunable(this, StatusBarIconController.ICON_HIDE_LIST);
        mNetworkController.addCallback(this);
        mSecurityController.addCallback(this);
    }

    public void destroy() {
        Dependency.get(TunerService.class).removeTunable(this);
        mNetworkController.removeCallback(this);
        mSecurityController.removeCallback(this);
    }

    private void updateVpn() {
        boolean vpnVisible = mSecurityController.isVpnEnabled();
        int vpnIconId = currentVpnIconId(mSecurityController.isVpnBranded());

        mIconController.setIcon(mSlotVpn, vpnIconId,
                mContext.getResources().getString(R.string.accessibility_vpn_on));
        mIconController.setIconVisibility(mSlotVpn, vpnVisible);
    }

    private int currentVpnIconId(boolean isBranded) {
        return isBranded ? R.drawable.stat_sys_branded_vpn : R.drawable.stat_sys_vpn_ic;
    }

    /**
     * From SecurityController
     */
    @Override
    public void onStateChanged() {
        mHandler.post(this::updateVpn);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!StatusBarIconController.ICON_HIDE_LIST.equals(key)) {
            return;
        }
        ArraySet<String> hideList = StatusBarIconController.getIconHideList(mContext, newValue);
        boolean hideAirplane = hideList.contains(mSlotAirplane);
        boolean hideMobile = hideList.contains(mSlotMobile);
        boolean hideWifi = hideList.contains(mSlotWifi);
        boolean hideEthernet = hideList.contains(mSlotEthernet);

        if (hideAirplane != mHideAirplane || hideMobile != mHideMobile
                || hideEthernet != mHideEthernet || hideWifi != mHideWifi) {
            mHideAirplane = hideAirplane;
            mHideMobile = hideMobile;
            mHideEthernet = hideEthernet;
            mHideWifi = hideWifi || mForceHideWifi;
            // Re-register to get new callbacks.
            mNetworkController.removeCallback(this);
            mNetworkController.addCallback(this);
        }
    }

    @Override
    public void setWifiIndicators(boolean enabled, IconState statusIcon, IconState qsIcon,
            boolean activityIn, boolean activityOut, String description, boolean isTransient,
            String statusLabel) {
        if (DEBUG) {
            Log.d(TAG, "setWifiIndicators: "
                    + "enabled = " + enabled + ","
                    + "statusIcon = " + (statusIcon == null ? "" : statusIcon.toString()) + ","
                    + "qsIcon = " + (qsIcon == null ? "" : qsIcon.toString()) + ","
                    + "activityIn = " + activityIn + ","
                    + "activityOut = " + activityOut + ","
                    + "description = " + description + ","
                    + "isTransient = " + isTransient + ","
                    + "statusLabel = " + statusLabel);
        }
        boolean visible = statusIcon.visible && !mHideWifi;
        boolean in = activityIn && mActivityEnabled && visible;
        boolean out = activityOut && mActivityEnabled && visible;
        mIsWifiEnabled = enabled;

        WifiIconState newState = mWifiIconState.copy();

        if (mWifiIconState.noDefaultNetwork && mWifiIconState.noNetworksAvailable
                && !mIsAirplaneMode) {
            newState.visible = true;
            newState.resId = R.drawable.ic_qs_no_internet_unavailable;
        } else if (mWifiIconState.noValidatedNetwork && !mWifiIconState.noNetworksAvailable
                && (!mIsAirplaneMode || (mIsAirplaneMode && mIsWifiEnabled))) {
            newState.visible = true;
            newState.resId = R.drawable.ic_qs_no_internet_available;
        } else {
            newState.visible = visible;
            newState.resId = statusIcon.icon;
            newState.activityIn = in;
            newState.activityOut = out;
            newState.contentDescription = statusIcon.contentDescription;
            MobileIconState first = getFirstMobileState();
            newState.signalSpacerVisible = first != null && first.typeId != 0;
        }
        newState.slot = mSlotWifi;
        newState.airplaneSpacerVisible = mIsAirplaneMode;
        updateWifiIconWithState(newState);
        mWifiIconState = newState;
    }

    private void updateShowWifiSignalSpacer(WifiIconState state) {
        MobileIconState first = getFirstMobileState();
        state.signalSpacerVisible = first != null && first.typeId != 0;
    }

    private void updateWifiIconWithState(WifiIconState state) {
        if (DEBUG) Log.d(TAG, "WifiIconState: " + state == null ? "" : state.toString());
        if (state.visible && state.resId > 0) {
            mIconController.setSignalIcon(mSlotWifi, state);
            mIconController.setIconVisibility(mSlotWifi, true);
        } else {
            mIconController.setIconVisibility(mSlotWifi, false);
        }
    }

    @Override
    public void setNoCallingStatus(boolean noCalling, int subId) {
        if (DEBUG) {
            Log.d(TAG, "setNoCallingStatus: "
                    + "noCalling = " + noCalling + ","
                    + "subId = " + subId);
        }
        NoCallingIconState state = getNoCallingState(subId);
        if (state == null) {
            return;
        }
        state.visible = noCalling;
        mIconController.setNoCallingIcons(
                mSlotNoCalling, NoCallingIconState.copyStates(mNoCallingStates));
    }

    @Override
    public void setMobileDataIndicators(IconState statusIcon, IconState qsIcon, int statusType,
            int qsType, boolean activityIn, boolean activityOut,
            CharSequence typeContentDescription,
            CharSequence typeContentDescriptionHtml, CharSequence description,
            boolean isWide, int subId, boolean roaming) {
        if (DEBUG) {
            Log.d(TAG, "setMobileDataIndicators: "
                    + "statusIcon = " + (statusIcon == null ? "" : statusIcon.toString()) + ","
                    + "qsIcon = " + (qsIcon == null ? "" : qsIcon.toString()) + ","
                    + "statusType = " + statusType + ","
                    + "qsType = " + qsType + ","
                    + "activityIn = " + activityIn + ","
                    + "activityOut = " + activityOut + ","
                    + "typeContentDescription = " + typeContentDescription + ","
                    + "typeContentDescriptionHtml = " + typeContentDescriptionHtml + ","
                    + "description = " + description + ","
                    + "isWide = " + isWide + ","
                    + "subId = " + subId + ","
                    + "roaming = " + roaming);
        }
        MobileIconState state = getState(subId);
        if (state == null) {
            return;
        }

        // Visibility of the data type indicator changed
        boolean typeChanged = statusType != state.typeId && (statusType == 0 || state.typeId == 0);

        state.visible = statusIcon.visible && !mHideMobile;
        state.strengthId = statusIcon.icon;
        state.typeId = statusType;
        state.contentDescription = statusIcon.contentDescription;
        state.typeContentDescription = typeContentDescription;
        state.roaming = roaming;
        state.activityIn = activityIn && mActivityEnabled;
        state.activityOut = activityOut && mActivityEnabled;

        if (DEBUG) {
            Log.d(TAG, "MobileIconStates: "
                    + (mMobileStates == null ? "" : mMobileStates.toString()));
        }
        // Always send a copy to maintain value type semantics
        mIconController.setMobileIcons(mSlotMobile, MobileIconState.copyStates(mMobileStates));

        if (typeChanged) {
            WifiIconState wifiCopy = mWifiIconState.copy();
            updateShowWifiSignalSpacer(wifiCopy);
            if (!Objects.equals(wifiCopy, mWifiIconState)) {
                updateWifiIconWithState(wifiCopy);
                mWifiIconState = wifiCopy;
            }
        }
    }

    private NoCallingIconState getNoCallingState(int subId) {
        for (NoCallingIconState state : mNoCallingStates) {
            if (state.subId == subId) {
                return state;
            }
        }
        Log.e(TAG, "Unexpected subscription " + subId);
        return null;
    }

    private MobileIconState getState(int subId) {
        for (MobileIconState state : mMobileStates) {
            if (state.subId == subId) {
                return state;
            }
        }
        Log.e(TAG, "Unexpected subscription " + subId);
        return null;
    }

    private MobileIconState getFirstMobileState() {
        if (mMobileStates.size() > 0) {
            return mMobileStates.get(0);
        }

        return null;
    }


    /**
     * It is expected that a call to setSubs will be immediately followed by setMobileDataIndicators
     * so we don't have to update the icon manager at this point, just remove the old ones
     * @param subs list of mobile subscriptions, displayed as mobile data indicators (max 8)
     */
    @Override
    public void setSubs(List<SubscriptionInfo> subs) {
        if (DEBUG) Log.d(TAG, "setSubs: " + (subs == null ? "" : subs.toString()));
        if (hasCorrectSubs(subs)) {
            return;
        }

        mIconController.removeAllIconsForSlot(mSlotMobile);
        mMobileStates.clear();
        List<NoCallingIconState> noCallingStates = new ArrayList<NoCallingIconState>();
        noCallingStates.addAll(mNoCallingStates);
        mNoCallingStates.clear();
        final int n = subs.size();
        for (int i = 0; i < n; i++) {
            mMobileStates.add(new MobileIconState(subs.get(i).getSubscriptionId()));
            boolean isNewSub = true;
            for (NoCallingIconState state : noCallingStates) {
                if (state.subId == subs.get(i).getSubscriptionId()) {
                    mNoCallingStates.add(state);
                    isNewSub = false;
                    break;
                }
            }
            if (isNewSub) {
                mNoCallingStates.add(new NoCallingIconState(subs.get(i).getSubscriptionId()));
            }
        }
    }

    private boolean hasCorrectSubs(List<SubscriptionInfo> subs) {
        final int N = subs.size();
        if (N != mMobileStates.size()) {
            return false;
        }
        for (int i = 0; i < N; i++) {
            if (mMobileStates.get(i).subId != subs.get(i).getSubscriptionId()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setNoSims(boolean show, boolean simDetected) {
        // Noop yay!
    }

    @Override
    public void setConnectivityStatus(boolean noDefaultNetwork, boolean noValidatedNetwork,
            boolean noNetworksAvailable) {
        if (DEBUG) {
            Log.d(TAG, "setConnectivityStatus: "
                    + "noDefaultNetwork = " + noDefaultNetwork + ","
                    + "noValidatedNetwork = " + noValidatedNetwork + ","
                    + "noNetworksAvailable = " + noNetworksAvailable);
        }
        WifiIconState newState = mWifiIconState.copy();
        newState.noDefaultNetwork = noDefaultNetwork;
        newState.noValidatedNetwork = noValidatedNetwork;
        newState.noNetworksAvailable = noNetworksAvailable;
        newState.slot = mSlotWifi;
        newState.airplaneSpacerVisible = mIsAirplaneMode;
        if (noDefaultNetwork && noNetworksAvailable && !mIsAirplaneMode) {
            newState.visible = true;
            newState.resId = R.drawable.ic_qs_no_internet_unavailable;
        } else if (noValidatedNetwork && !noNetworksAvailable
                && (!mIsAirplaneMode || (mIsAirplaneMode && mIsWifiEnabled))) {
            newState.visible = true;
            newState.resId = R.drawable.ic_qs_no_internet_available;
        } else {
            newState.visible = false;
            newState.resId = 0;
        }
        updateWifiIconWithState(newState);
        mWifiIconState = newState;
    }


    @Override
    public void setEthernetIndicators(IconState state) {
        boolean visible = state.visible && !mHideEthernet;
        int resId = state.icon;
        String description = state.contentDescription;

        if (resId > 0) {
            mIconController.setIcon(mSlotEthernet, resId, description);
            mIconController.setIconVisibility(mSlotEthernet, true);
        } else {
            mIconController.setIconVisibility(mSlotEthernet, false);
        }
    }

    @Override
    public void setIsAirplaneMode(IconState icon) {
        if (DEBUG) {
            Log.d(TAG, "setIsAirplaneMode: "
                    + "icon = " + (icon == null ? "" : icon.toString()));
        }
        mIsAirplaneMode = icon.visible && !mHideAirplane;
        int resId = icon.icon;
        String description = icon.contentDescription;

        if (mIsAirplaneMode && resId > 0) {
            mIconController.setIcon(mSlotAirplane, resId, description);
            mIconController.setIconVisibility(mSlotAirplane, true);
        } else {
            mIconController.setIconVisibility(mSlotAirplane, false);
        }
    }

    @Override
    public void setMobileDataEnabled(boolean enabled) {
        // Don't care.
    }

    /**
     * Stores the StatusBar state for no Calling & SMS.
     */
    public static class NoCallingIconState {
        public boolean visible;
        public int resId;
        public int subId;

        private NoCallingIconState(int subId) {
            this.subId = subId;
            this.resId = R.drawable.ic_qs_no_calling_sms;
        }

        @Override
        public boolean equals(Object o) {
            // Skipping reference equality bc this should be more of a value type
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            NoCallingIconState that = (NoCallingIconState) o;
            return visible == that.visible
                    && resId == that.resId
                    && subId == that.subId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(visible, resId, subId);
        }

        private void copyTo(NoCallingIconState other) {
            other.visible = visible;
            other.resId = resId;
            other.subId = subId;
        }

        private static List<NoCallingIconState> copyStates(List<NoCallingIconState> inStates) {
            ArrayList<NoCallingIconState> outStates = new ArrayList<>();
            for (NoCallingIconState state : inStates) {
                NoCallingIconState copy = new NoCallingIconState(state.subId);
                state.copyTo(copy);
                outStates.add(copy);
            }
            return outStates;
        }
    }

    private static abstract class SignalIconState {
        public boolean visible;
        public boolean activityOut;
        public boolean activityIn;
        public String slot;
        public String contentDescription;

        @Override
        public boolean equals(Object o) {
            // Skipping reference equality bc this should be more of a value type
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SignalIconState that = (SignalIconState) o;
            return visible == that.visible &&
                    activityOut == that.activityOut &&
                    activityIn == that.activityIn &&
                    Objects.equals(contentDescription, that.contentDescription) &&
                    Objects.equals(slot, that.slot);
        }

        @Override
        public int hashCode() {
            return Objects.hash(visible, activityOut, slot);
        }

        protected void copyTo(SignalIconState other) {
            other.visible = visible;
            other.activityIn = activityIn;
            other.activityOut = activityOut;
            other.slot = slot;
            other.contentDescription = contentDescription;
        }
    }

    public static class WifiIconState extends SignalIconState{
        public int resId;
        public boolean airplaneSpacerVisible;
        public boolean signalSpacerVisible;
        public boolean noDefaultNetwork;
        public boolean noValidatedNetwork;
        public boolean noNetworksAvailable;

        @Override
        public boolean equals(Object o) {
            // Skipping reference equality bc this should be more of a value type
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            if (!super.equals(o)) {
                return false;
            }
            WifiIconState that = (WifiIconState) o;
            return resId == that.resId
                    && airplaneSpacerVisible == that.airplaneSpacerVisible
                    && signalSpacerVisible == that.signalSpacerVisible
                    && noDefaultNetwork == that.noDefaultNetwork
                    && noValidatedNetwork == that.noValidatedNetwork
                    && noNetworksAvailable == that.noNetworksAvailable;
        }

        public void copyTo(WifiIconState other) {
            super.copyTo(other);
            other.resId = resId;
            other.airplaneSpacerVisible = airplaneSpacerVisible;
            other.signalSpacerVisible = signalSpacerVisible;
            other.noDefaultNetwork = noDefaultNetwork;
            other.noValidatedNetwork = noValidatedNetwork;
            other.noNetworksAvailable = noNetworksAvailable;
        }

        public WifiIconState copy() {
            WifiIconState newState = new WifiIconState();
            copyTo(newState);
            return newState;
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(),
                    resId, airplaneSpacerVisible, signalSpacerVisible, noDefaultNetwork,
                    noValidatedNetwork, noNetworksAvailable);
        }

        @Override public String toString() {
            return "WifiIconState(resId=" + resId + ", visible=" + visible + ")";
        }
    }

    /**
     * A little different. This one delegates to SignalDrawable instead of a specific resId
     */
    public static class MobileIconState extends SignalIconState {
        public int subId;
        public int strengthId;
        public int typeId;
        public boolean roaming;
        public boolean needsLeadingPadding;
        public CharSequence typeContentDescription;

        private MobileIconState(int subId) {
            super();
            this.subId = subId;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            if (!super.equals(o)) {
                return false;
            }
            MobileIconState that = (MobileIconState) o;
            return subId == that.subId &&
                    strengthId == that.strengthId &&
                    typeId == that.typeId &&
                    roaming == that.roaming &&
                    needsLeadingPadding == that.needsLeadingPadding &&
                    Objects.equals(typeContentDescription, that.typeContentDescription);
        }

        @Override
        public int hashCode() {

            return Objects
                    .hash(super.hashCode(), subId, strengthId, typeId, roaming, needsLeadingPadding,
                            typeContentDescription);
        }

        public MobileIconState copy() {
            MobileIconState copy = new MobileIconState(this.subId);
            copyTo(copy);
            return copy;
        }

        public void copyTo(MobileIconState other) {
            super.copyTo(other);
            other.subId = subId;
            other.strengthId = strengthId;
            other.typeId = typeId;
            other.roaming = roaming;
            other.needsLeadingPadding = needsLeadingPadding;
            other.typeContentDescription = typeContentDescription;
        }

        private static List<MobileIconState> copyStates(List<MobileIconState> inStates) {
            ArrayList<MobileIconState> outStates = new ArrayList<>();
            for (MobileIconState state : inStates) {
                MobileIconState copy = new MobileIconState(state.subId);
                state.copyTo(copy);
                outStates.add(copy);
            }

            return outStates;
        }

        @Override public String toString() {
            return "MobileIconState(subId=" + subId + ", strengthId=" + strengthId + ", roaming="
                    + roaming + ", typeId=" + typeId + ", visible=" + visible + ")";
        }
    }
}
