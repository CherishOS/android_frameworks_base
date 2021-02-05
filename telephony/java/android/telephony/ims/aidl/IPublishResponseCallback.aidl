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

import java.util.List;

/**
 * Interface used by the framework to receive the response of the publish
 * request through {@link RcsCapabilityExchangeImplBase#publishCapabilities}
 * {@hide}
 */
oneway interface IPublishResponseCallback {
    void onCommandError(int code);
    void onNetworkResponse(int code, String reason);
    void onNetworkRespHeader(int code, String reasonPhrase, int reasonHeaderCause, String reasonHeaderText);
}
