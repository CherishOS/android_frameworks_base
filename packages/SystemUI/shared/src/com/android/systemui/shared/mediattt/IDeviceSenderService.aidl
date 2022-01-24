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

package com.android.systemui.shared.mediattt;

import android.media.MediaRoute2Info;
import com.android.systemui.shared.mediattt.DeviceInfo;
import com.android.systemui.shared.mediattt.IUndoTransferCallback;

/**
 * An interface that can be invoked to trigger media transfer events on System UI.
 *
 * This interface is for the *sender* device, which is the device currently playing media. This
 * sender device can transfer the media to a different device, called the receiver.
 *
 * System UI will implement this interface and other services will invoke it.
 */
interface IDeviceSenderService {
    /**
     * Invoke to notify System UI that this device (the sender) is close to a receiver device, so
     * the user can potentially *start* a cast to the receiver device if the user moves their device
     * a bit closer.
     *
     * Important notes:
     *   - When this callback triggers, the device is close enough to inform the user that
     *     transferring is an option, but the device is *not* close enough to actually initiate a
     *     transfer yet.
     *   - This callback is for *starting* a cast. It should be used when this device is currently
     *     playing media locally and the media should be transferred to be played on the receiver
     *     device instead.
     */
    oneway void closeToReceiverToStartCast(
        in MediaRoute2Info mediaInfo, in DeviceInfo otherDeviceInfo);

    /**
     * Invoke to notify System UI that this device (the sender) is close to a receiver device, so
     * the user can potentially *end* a cast on the receiver device if the user moves this device a
     * bit closer.
     *
     * Important notes:
     *   - When this callback triggers, the device is close enough to inform the user that
     *     transferring is an option, but the device is *not* close enough to actually initiate a
     *     transfer yet.
     *   - This callback is for *ending* a cast. It should be used when media is currently being
     *     played on the receiver device and the media should be transferred to play locally
     *     instead.
     */
    oneway void closeToReceiverToEndCast(
        in MediaRoute2Info mediaInfo, in DeviceInfo otherDeviceInfo);

    /**
     * Invoke to notify System UI that a media transfer from this device (the sender) to a receiver
     * device has been started.
     *
     * Important notes:
     *   - This callback is for *starting* a cast. It should be used when this device is currently
     *     playing media locally and the media has started being transferred to the receiver device
     *     instead.
     */
    oneway void transferToReceiverTriggered(
        in MediaRoute2Info mediaInfo, in DeviceInfo otherDeviceInfo);

    /**
     * Invoke to notify System UI that a media transfer from the receiver and back to this device
     * (the sender) has been started.
     *
     * Important notes:
     *   - This callback is for *ending* a cast. It should be used when media is currently being
     *     played on the receiver device and the media has started being transferred to play locally
     *     instead.
     */
    oneway void transferToThisDeviceTriggered(
        in MediaRoute2Info mediaInfo, in DeviceInfo otherDeviceInfo);

    /**
     * Invoke to notify System UI that a media transfer from this device (the sender) to a receiver
     * device has finished successfully.
     *
     * Important notes:
     *   - This callback is for *starting* a cast. It should be used when this device had previously
     *     been playing media locally and the media has successfully been transferred to the
     *     receiver device instead.
     *
     * @param undoCallback will be invoked if the user chooses to undo this transfer.
     */
    oneway void transferToReceiverSucceeded(
        in MediaRoute2Info mediaInfo,
        in DeviceInfo otherDeviceInfo,
        in IUndoTransferCallback undoCallback);

    /**
     * Invoke to notify System UI that a media transfer from the receiver and back to this device
     * (the sender) has finished successfully.
     *
     * Important notes:
     *   - This callback is for *ending* a cast. It should be used when media was previously being
     *     played on the receiver device and has been successfully transferred to play locally on
     *     this device instead.
     *
     * @param undoCallback will be invoked if the user chooses to undo this transfer.
     */
    oneway void transferToThisDeviceSucceeded(
        in MediaRoute2Info mediaInfo,
        in DeviceInfo otherDeviceInfo,
        in IUndoTransferCallback undoCallback);

    /**
     * Invoke to notify System UI that the attempted transfer has failed.
     *
     * This callback will be used for both the transfer that should've *started* playing the media
     * on the receiver and the transfer that should've *ended* the playing on the receiver.
     */
    oneway void transferFailed(in MediaRoute2Info mediaInfo, in DeviceInfo otherDeviceInfo);

    /**
     * Invoke to notify System UI that this device is no longer close to the receiver device.
     */
    oneway void noLongerCloseToReceiver(
        in MediaRoute2Info mediaInfo, in DeviceInfo otherDeviceInfo);
}
