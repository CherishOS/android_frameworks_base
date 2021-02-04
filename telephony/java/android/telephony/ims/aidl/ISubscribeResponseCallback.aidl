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

package android.telephony.ims.aidl;

import android.net.Uri;
import android.telephony.ims.RcsContactTerminatedReason;

import java.util.List;
import java.util.Map;

/**
 * Interface used by the framework to receive the response of the subscribe
 * request through {@link RcsCapabilityExchangeImplBase#subscribeForCapabilities}
 * {@hide}
 */
oneway interface ISubscribeResponseCallback {
    void onCommandError(int code);
    void onNetworkResponse(int code, in String reason);
    void onNetworkRespHeader(int code, String reasonPhrase, int reasonHeaderCause, String reasonHeaderText);
    void onNotifyCapabilitiesUpdate(in List<String> pidfXmls);
    void onResourceTerminated(in List<RcsContactTerminatedReason> uriTerminatedReason);
    void onTerminated(in String reason, long retryAfterMilliseconds);
}
