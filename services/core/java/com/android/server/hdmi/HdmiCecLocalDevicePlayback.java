/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.hdmi;

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemProperties;
import android.provider.Settings.Global;
import android.sysprop.HdmiProperties;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.LocalePicker;
import com.android.internal.app.LocalePicker.LocaleInfo;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Locale;

/**
 * Represent a logical device of type Playback residing in Android system.
 */
public class HdmiCecLocalDevicePlayback extends HdmiCecLocalDeviceSource {
    private static final String TAG = "HdmiCecLocalDevicePlayback";

    private static final boolean SET_MENU_LANGUAGE =
            HdmiProperties.set_menu_language_enabled().orElse(false);

    // Used to keep the device awake while it is the active source. For devices that
    // cannot wake up via CEC commands, this address the inconvenience of having to
    // turn them on. True by default, and can be disabled (i.e. device can go to sleep
    // in active device status) by explicitly setting the system property
    // persist.sys.hdmi.keep_awake to false.
    // Lazily initialized - should call getWakeLock() to get the instance.
    private ActiveWakeLock mWakeLock;

    // If true, turn off TV upon standby. False by default.
    private boolean mAutoTvOff;

    // Local active port number used for Routing Control.
    // Default 0 means HOME is the current active path. Temp solution only.
    // TODO(amyjojo): adding system constants for input ports to TIF mapping.
    private int mLocalActivePath = 0;

    // Determines what action should be taken upon receiving Routing Control messages.
    @VisibleForTesting
    protected HdmiProperties.playback_device_action_on_routing_control_values
            mPlaybackDeviceActionOnRoutingControl = HdmiProperties
                    .playback_device_action_on_routing_control()
                    .orElse(HdmiProperties.playback_device_action_on_routing_control_values.NONE);

    // Behaviour of the device when <Active Source> is lost in favor of another device.
    @VisibleForTesting
    protected HdmiProperties.power_state_change_on_active_source_lost_values
            mPowerStateChangeOnActiveSourceLost = HdmiProperties
                    .power_state_change_on_active_source_lost()
                    .orElse(HdmiProperties.power_state_change_on_active_source_lost_values.NONE);

    HdmiCecLocalDevicePlayback(HdmiControlService service) {
        super(service, HdmiDeviceInfo.DEVICE_PLAYBACK);

        mAutoTvOff = mService.readBooleanSetting(Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED, false);

        // The option is false by default. Update settings db as well to have the right
        // initial setting on UI.
        mService.writeBooleanSetting(Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED, mAutoTvOff);

        // Initialize settings database with System Property value. This will be the initial
        // setting on the UI. If no System Property is set, the option is set to to_tv by default.
        mService.writeStringSetting(Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                mService.readStringSetting(Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                        HdmiProperties.send_standby_on_sleep().orElse(
                                HdmiProperties.send_standby_on_sleep_values.TO_TV).name()
                                .toLowerCase()));
    }

    @Override
    @ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        if (reason == mService.INITIATED_BY_ENABLE_CEC) {
            mService.setAndBroadcastActiveSource(mService.getPhysicalAddress(),
                    getDeviceInfo().getDeviceType(), Constants.ADDR_BROADCAST);
        }
        mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, mService.getPhysicalAddress(), mDeviceType));
        mService.sendCecCommand(HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                mAddress, mService.getVendorId()));
        // Actively send out an OSD name to the TV to update the TV panel in case the TV
        // does not query the OSD name on time. This is not a required behavior by the spec.
        // It is used for some TVs that need the OSD name update but don't query it themselves.
        buildAndSendSetOsdName(Constants.ADDR_TV);
        if (mService.audioSystem() == null) {
            // If current device is not a functional audio system device,
            // send message to potential audio system device in the system to get the system
            // audio mode status. If no response, set to false.
            mService.sendCecCommand(HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(
                    mAddress, Constants.ADDR_AUDIO_SYSTEM), new SendMessageCallback() {
                        @Override
                        public void onSendCompleted(int error) {
                            if (error != SendMessageResult.SUCCESS) {
                                HdmiLogger.debug(
                                        "AVR did not respond to <Give System Audio Mode Status>");
                                mService.setSystemAudioActivated(false);
                            }
                        }
                    });
        }
        startQueuedActions();
    }

    @Override
    @ServiceThreadOnly
    protected int getPreferredAddress() {
        assertRunOnServiceThread();
        return SystemProperties.getInt(Constants.PROPERTY_PREFERRED_ADDRESS_PLAYBACK,
                Constants.ADDR_UNREGISTERED);
    }

    @Override
    @ServiceThreadOnly
    protected void setPreferredAddress(int addr) {
        assertRunOnServiceThread();
        mService.writeStringSystemProperty(Constants.PROPERTY_PREFERRED_ADDRESS_PLAYBACK,
                String.valueOf(addr));
    }

    @ServiceThreadOnly
    void queryDisplayStatus(IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        List<DevicePowerStatusAction> actions = getActions(DevicePowerStatusAction.class);
        if (!actions.isEmpty()) {
            Slog.i(TAG, "queryDisplayStatus already in progress");
            actions.get(0).addCallback(callback);
            return;
        }
        DevicePowerStatusAction action = DevicePowerStatusAction.create(this, Constants.ADDR_TV,
                callback);
        if (action == null) {
            Slog.w(TAG, "Cannot initiate queryDisplayStatus");
            invokeCallback(callback, HdmiControlManager.RESULT_EXCEPTION);
            return;
        }
        addAndStartAction(action);
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();
        mCecMessageCache.flushAll();
        // We'll not clear mIsActiveSource on the hotplug event to pass CETC 11.2.2-2 ~ 3.
        if (!connected) {
            getWakeLock().release();
        }
    }

    @Override
    @ServiceThreadOnly
    protected void onStandby(boolean initiatedByCec, int standbyAction) {
        assertRunOnServiceThread();
        if (!mService.isControlEnabled()) {
            return;
        }
        if (mIsActiveSource) {
            mService.sendCecCommand(HdmiCecMessageBuilder.buildInactiveSource(
                    mAddress, mService.getPhysicalAddress()));
        }
        boolean wasActiveSource = mIsActiveSource;
        // Invalidate the internal active source record when goes to standby
        // This set will also update mIsActiveSource
        mService.setActiveSource(Constants.ADDR_INVALID, Constants.INVALID_PHYSICAL_ADDRESS,
                "HdmiCecLocalDevicePlayback#onStandby()");
        if (initiatedByCec || !mAutoTvOff || !wasActiveSource) {
            return;
        }
        switch (standbyAction) {
            case HdmiControlService.STANDBY_SCREEN_OFF:
                // Get latest setting value
                @HdmiControlManager.StandbyBehavior
                String sendStandbyOnSleep = mService.readStringSetting(
                        Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                        HdmiProperties.send_standby_on_sleep().orElse(
                                HdmiProperties.send_standby_on_sleep_values.TO_TV).name()
                                .toLowerCase());
                switch (sendStandbyOnSleep) {
                    case HdmiControlManager.SEND_STANDBY_ON_SLEEP_TO_TV:
                        mService.sendCecCommand(
                                HdmiCecMessageBuilder.buildStandby(mAddress, Constants.ADDR_TV));
                        break;
                    case HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST:
                        mService.sendCecCommand(
                                HdmiCecMessageBuilder.buildStandby(mAddress,
                                        Constants.ADDR_BROADCAST));
                        break;
                    case HdmiControlManager.SEND_STANDBY_ON_SLEEP_NONE:
                        break;
                }
                break;
            case HdmiControlService.STANDBY_SHUTDOWN:
                // ACTION_SHUTDOWN is taken as a signal to power off all the devices.
                mService.sendCecCommand(
                        HdmiCecMessageBuilder.buildStandby(mAddress, Constants.ADDR_BROADCAST));
                break;
        }
    }

    @Override
    @ServiceThreadOnly
    void setAutoDeviceOff(boolean enabled) {
        assertRunOnServiceThread();
        mAutoTvOff = enabled;
    }

    @Override
    @ServiceThreadOnly
    @VisibleForTesting
    void setIsActiveSource(boolean on) {
        assertRunOnServiceThread();
        super.setIsActiveSource(on);
        if (on) {
            getWakeLock().acquire();
        } else {
            getWakeLock().release();
        }
    }

    @ServiceThreadOnly
    private ActiveWakeLock getWakeLock() {
        assertRunOnServiceThread();
        if (mWakeLock == null) {
            if (SystemProperties.getBoolean(Constants.PROPERTY_KEEP_AWAKE, true)) {
                mWakeLock = new SystemWakeLock();
            } else {
                // Create a stub lock object that doesn't do anything about wake lock,
                // hence allows the device to go to sleep even if it's the active source.
                mWakeLock = new ActiveWakeLock() {
                    @Override
                    public void acquire() { }
                    @Override
                    public void release() { }
                    @Override
                    public boolean isHeld() { return false; }
                };
                HdmiLogger.debug("No wakelock is used to keep the display on.");
            }
        }
        return mWakeLock;
    }

    @Override
    protected boolean canGoToStandby() {
        return !getWakeLock().isHeld();
    }

    @Override
    @ServiceThreadOnly
    protected void onActiveSourceLost() {
        assertRunOnServiceThread();
        switch (mPowerStateChangeOnActiveSourceLost) {
            case STANDBY_NOW:
                mService.standby();
                return;
            case NONE:
                return;
        }
    }

    @ServiceThreadOnly
    protected boolean handleUserControlPressed(HdmiCecMessage message) {
        assertRunOnServiceThread();
        wakeUpIfActiveSource();
        return super.handleUserControlPressed(message);
    }

    @Override
    protected void wakeUpIfActiveSource() {
        if (!mIsActiveSource) {
            return;
        }
        // Wake up the device if the power is in standby mode, or its screen is off -
        // which can happen if the device is holding a partial lock.
        if (mService.isPowerStandbyOrTransient() || !mService.getPowerManager().isScreenOn()) {
            mService.wakeUp();
        }
    }

    @ServiceThreadOnly
    protected boolean handleSetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!SET_MENU_LANGUAGE) {
            return false;
        }

        try {
            String iso3Language = new String(message.getParams(), 0, 3, "US-ASCII");
            Locale currentLocale = mService.getContext().getResources().getConfiguration().locale;
            if (currentLocale.getISO3Language().equals(iso3Language)) {
                // Do not switch language if the new language is the same as the current one.
                // This helps avoid accidental country variant switching from en_US to en_AU
                // due to the limitation of CEC. See the warning below.
                return true;
            }

            // Don't use Locale.getAvailableLocales() since it returns a locale
            // which is not available on Settings.
            final List<LocaleInfo> localeInfos = LocalePicker.getAllAssetLocales(
                    mService.getContext(), false);
            for (LocaleInfo localeInfo : localeInfos) {
                if (localeInfo.getLocale().getISO3Language().equals(iso3Language)) {
                    // WARNING: CEC adopts ISO/FDIS-2 for language code, while Android requires
                    // additional country variant to pinpoint the locale. This keeps the right
                    // locale from being chosen. 'eng' in the CEC command, for instance,
                    // will always be mapped to en-AU among other variants like en-US, en-GB,
                    // an en-IN, which may not be the expected one.
                    LocalePicker.updateLocale(localeInfo.getLocale());
                    return true;
                }
            }
            Slog.w(TAG, "Can't handle <Set Menu Language> of " + iso3Language);
            return false;
        } catch (UnsupportedEncodingException e) {
            Slog.w(TAG, "Can't handle <Set Menu Language>", e);
            return false;
        }
    }

    @Override
    protected boolean handleSetSystemAudioMode(HdmiCecMessage message) {
        // System Audio Mode only turns on/off when Audio System broadcasts on/off message.
        // For device with type 4 and 5, it can set system audio mode on/off
        // when there is another audio system device connected into the system first.
        if (message.getDestination() != Constants.ADDR_BROADCAST
                || message.getSource() != Constants.ADDR_AUDIO_SYSTEM
                || mService.audioSystem() != null) {
            return true;
        }
        boolean setSystemAudioModeOn = HdmiUtils.parseCommandParamSystemAudioStatus(message);
        if (mService.isSystemAudioActivated() != setSystemAudioModeOn) {
            mService.setSystemAudioActivated(setSystemAudioModeOn);
        }
        return true;
    }

    @Override
    protected boolean handleSystemAudioModeStatus(HdmiCecMessage message) {
        // Only directly addressed System Audio Mode Status message can change internal
        // system audio mode status.
        if (message.getDestination() == mAddress
                && message.getSource() == Constants.ADDR_AUDIO_SYSTEM) {
            boolean setSystemAudioModeOn = HdmiUtils.parseCommandParamSystemAudioStatus(message);
            if (mService.isSystemAudioActivated() != setSystemAudioModeOn) {
                mService.setSystemAudioActivated(setSystemAudioModeOn);
            }
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRoutingChange(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams(), 2);
        handleRoutingChangeAndInformation(physicalAddress, message);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRoutingInformation(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        handleRoutingChangeAndInformation(physicalAddress, message);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected void handleRoutingChangeAndInformation(int physicalAddress, HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (physicalAddress != mService.getPhysicalAddress()) {
            setActiveSource(physicalAddress,
                    "HdmiCecLocalDevicePlayback#handleRoutingChangeAndInformation()");
            return;
        }
        switch (mPlaybackDeviceActionOnRoutingControl) {
            case WAKE_UP_AND_SEND_ACTIVE_SOURCE:
                setAndBroadcastActiveSource(message, physicalAddress);
                break;
            case WAKE_UP_ONLY:
                mService.wakeUp();
                break;
            case NONE:
                break;
        }
    }

    @Override
    protected int findKeyReceiverAddress() {
        return Constants.ADDR_TV;
    }

    @Override
    protected int findAudioReceiverAddress() {
        if (mService.isSystemAudioActivated()) {
            return Constants.ADDR_AUDIO_SYSTEM;
        }
        return Constants.ADDR_TV;
    }

    @Override
    @ServiceThreadOnly
    protected void disableDevice(boolean initiatedByCec, PendingActionClearedCallback callback) {
        super.disableDevice(initiatedByCec, callback);

        assertRunOnServiceThread();
        checkIfPendingActionsCleared();
    }

    private void routeToPort(int portId) {
        // TODO(AMYJOJO): route to specific input of the port
        mLocalActivePath = portId;
    }

    @VisibleForTesting
    protected int getLocalActivePath() {
        return mLocalActivePath;
    }

    @Override
    protected void dump(final IndentingPrintWriter pw) {
        super.dump(pw);
        pw.println("mIsActiveSource: " + mIsActiveSource);
        pw.println("mAutoTvOff:" + mAutoTvOff);
    }

    // Wrapper interface over PowerManager.WakeLock
    private interface ActiveWakeLock {
        void acquire();
        void release();
        boolean isHeld();
    }

    private class SystemWakeLock implements ActiveWakeLock {
        private final WakeLock mWakeLock;
        public SystemWakeLock() {
            mWakeLock = mService.getPowerManager().newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            mWakeLock.setReferenceCounted(false);
        }

        @Override
        public void acquire() {
            mWakeLock.acquire();
            HdmiLogger.debug("active source: %b. Wake lock acquired", mIsActiveSource);
        }

        @Override
        public void release() {
            mWakeLock.release();
            HdmiLogger.debug("Wake lock released");
        }

        @Override
        public boolean isHeld() {
            return mWakeLock.isHeld();
        }
    }
}
