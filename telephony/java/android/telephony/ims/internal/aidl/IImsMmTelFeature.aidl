/*
 * Copyright (c) 2017 The Android Open Source Project
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

package android.telephony.ims.internal.aidl;

import android.os.Message;
import android.telephony.ims.internal.aidl.IImsMmTelListener;
import android.telephony.ims.internal.aidl.IImsCapabilityCallback;
import android.telephony.ims.internal.aidl.IImsCallSessionListener;
import android.telephony.ims.internal.feature.CapabilityChangeRequest;

import com.android.ims.ImsCallProfile;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsUt;

/**
 * See MmTelFeature for more information.
 * {@hide}
 */
interface IImsMmTelFeature {
    void setListener(IImsMmTelListener l);
    int getFeatureState();
    ImsCallProfile createCallProfile(int callSessionType, int callType);
    IImsCallSession createCallSession(in ImsCallProfile profile, IImsCallSessionListener listener);
    IImsUt getUtInterface();
    IImsEcbm getEcbmInterface();
    void setUiTtyMode(int uiTtyMode, in Message onCompleteMessage);
    IImsMultiEndpoint getMultiEndpointInterface();
    int queryCapabilityStatus();
    oneway void addCapabilityCallback(IImsCapabilityCallback c);
    oneway void removeCapabilityCallback(IImsCapabilityCallback c);
    oneway void changeCapabilitiesConfiguration(in CapabilityChangeRequest request,
            IImsCapabilityCallback c);
    oneway void queryCapabilityConfiguration(int capability, int radioTech,
            IImsCapabilityCallback c);
}
