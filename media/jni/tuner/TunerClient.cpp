/*
 * Copyright 2020 The Android Open Source Project
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

#define LOG_TAG "TunerClient"

#include <android/binder_manager.h>
#include <android-base/logging.h>
#include <utils/Log.h>

#include "TunerClient.h"

using ::android::hardware::tv::tuner::V1_0::FrontendId;
using ::android::hardware::tv::tuner::V1_0::FrontendType;

using ::aidl::android::media::tv::tunerresourcemanager::TunerFrontendInfo;

namespace android {

sp<ITuner> TunerClient::mTuner;
sp<::android::hardware::tv::tuner::V1_1::ITuner> TunerClient::mTuner_1_1;
shared_ptr<ITunerService> TunerClient::mTunerService;
int TunerClient::mTunerVersion;

/////////////// TunerClient ///////////////////////

TunerClient::TunerClient() {
    // Get HIDL Tuner in migration stage.
    getHidlTuner();
    updateTunerResources();
    // Connect with Tuner Service.
    ::ndk::SpAIBinder binder(AServiceManager_getService("media.tuner"));
    mTunerService = ITunerService::fromBinder(binder);
    // TODO: Remove after JNI migration is done.
    mTunerService = NULL;
    if (mTunerService == NULL) {
        ALOGE("Failed to get tuner service");
    }
}

TunerClient::~TunerClient() {
    mTuner = NULL;
    mTuner_1_1 = NULL;
    mTunerVersion = 0;
    mTunerService = NULL;
}

vector<FrontendId> TunerClient::getFrontendIds() {
    vector<FrontendId> ids;

    if (mTunerService != NULL) {
        vector<int32_t> v;
        int aidl_return;
        Status s = mTunerService->getFrontendIds(&v, &aidl_return);
        if (!s.isOk() || aidl_return != (int) Result::SUCCESS
                || v.size() == 0) {
            ids.clear();
            return ids;
        }
        for (int32_t id : v) {
            ids.push_back(static_cast<FrontendId>(id));
        }
        return ids;
    }

    if (mTuner != NULL) {
        Result res;
        mTuner->getFrontendIds([&](Result r, const hardware::hidl_vec<FrontendId>& frontendIds) {
            res = r;
            ids = frontendIds;
        });
        if (res != Result::SUCCESS || ids.size() == 0) {
            ALOGW("Frontend ids not available");
            ids.clear();
            return ids;
        }
        return ids;
    }

    return ids;
}


sp<FrontendClient> TunerClient::openFrontend(int frontendHandle) {
    if (mTunerService != NULL) {
        // TODO: handle error code
        shared_ptr<ITunerFrontend> tunerFrontend;
        mTunerService->openFrontend(frontendHandle, &tunerFrontend);
        return new FrontendClient(tunerFrontend, frontendHandle);
    }

    if (mTuner != NULL) {
        int id = getResourceIdFromHandle(frontendHandle, FRONTEND);
        sp<IFrontend> hidlFrontend = openHidlFrontendById(id);
        if (hidlFrontend != NULL) {
            sp<FrontendClient> frontendClient = new FrontendClient(NULL, id);
            frontendClient->setHidlFrontend(hidlFrontend);
            return frontendClient;
        }
    }

    return NULL;
}

shared_ptr<FrontendInfo> TunerClient::getFrontendInfo(int id) {
    if (mTunerService != NULL) {
        TunerServiceFrontendInfo aidlFrontendInfo;
        // TODO: handle error code
        mTunerService->getFrontendInfo(id, &aidlFrontendInfo);
        return make_shared<FrontendInfo>(FrontendInfoAidlToHidl(aidlFrontendInfo));
    }

    if (mTuner != NULL) {
        FrontendInfo hidlInfo;
        Result res = getHidlFrontendInfo(id, hidlInfo);
        if (res != Result::SUCCESS) {
            return NULL;
        }
        return make_shared<FrontendInfo>(hidlInfo);
    }

    return NULL;
}

shared_ptr<FrontendDtmbCapabilities> TunerClient::getFrontendDtmbCapabilities(int id) {
    // pending aidl interface

    if (mTuner_1_1 != NULL) {
        Result result;
        FrontendDtmbCapabilities dtmbCaps;
        mTuner_1_1->getFrontendDtmbCapabilities(id,
                [&](Result r, const FrontendDtmbCapabilities& caps) {
            dtmbCaps = caps;
            result = r;
        });
        if (result == Result::SUCCESS) {
            return make_shared<FrontendDtmbCapabilities>(dtmbCaps);
        }
    }

    return NULL;
}

sp<DemuxClient> TunerClient::openDemux(int /*demuxHandle*/) {
    if (mTunerService != NULL) {
        // TODO: handle error code
        /*shared_ptr<ITunerDemux> tunerDemux;
        mTunerService->openDemux(demuxHandle, &tunerDemux);
        return new DemuxClient(tunerDemux);*/
    }

    if (mTuner != NULL) {
        // TODO: pending aidl interface
        sp<DemuxClient> demuxClient = new DemuxClient();
        int demuxId;
        sp<IDemux> hidlDemux = openHidlDemux(demuxId);
        if (hidlDemux != NULL) {
            demuxClient->setHidlDemux(hidlDemux);
            demuxClient->setId(demuxId);
            return demuxClient;
        }
    }

    return NULL;
}

shared_ptr<DemuxCapabilities> TunerClient::getDemuxCaps() {
    // pending aidl interface

    if (mTuner != NULL) {
        Result res;
        DemuxCapabilities caps;
        mTuner->getDemuxCaps([&](Result r, const DemuxCapabilities& demuxCaps) {
            caps = demuxCaps;
            res = r;
        });
        if (res == Result::SUCCESS) {
            return make_shared<DemuxCapabilities>(caps);
        }
    }

    return NULL;
}

sp<DescramblerClient> TunerClient::openDescrambler(int /*descramblerHandle*/) {
    if (mTunerService != NULL) {
        // TODO: handle error code
        /*shared_ptr<ITunerDescrambler> tunerDescrambler;
        mTunerService->openDescrambler(demuxHandle, &tunerDescrambler);
        return new DescramblerClient(tunerDescrambler);*/
    }

    if (mTuner != NULL) {
        // TODO: pending aidl interface
        sp<DescramblerClient> descramblerClient = new DescramblerClient();
        sp<IDescrambler> hidlDescrambler = openHidlDescrambler();
        if (hidlDescrambler != NULL) {
            descramblerClient->setHidlDescrambler(hidlDescrambler);
            return descramblerClient;
        }
    }

    return NULL;}

sp<LnbClient> TunerClient::openLnb(int lnbHandle) {
    if (mTunerService != NULL) {
        // TODO: handle error code
        /*shared_ptr<ITunerLnb> tunerLnb;
        mTunerService->openLnb(demuxHandle, &tunerLnb);
        return new LnbClient(tunerLnb);*/
    }

    if (mTuner != NULL) {
        int id = getResourceIdFromHandle(lnbHandle, LNB);
        // TODO: pending aidl interface
        sp<LnbClient> lnbClient = new LnbClient();
        sp<ILnb> hidlLnb = openHidlLnbById(id);
        if (hidlLnb != NULL) {
            lnbClient->setHidlLnb(hidlLnb);
            lnbClient->setId(id);
            return lnbClient;
        }
    }

    return NULL;
}

sp<LnbClient> TunerClient::openLnbByName(string lnbName) {
    if (mTunerService != NULL) {
        // TODO: handle error code
        /*shared_ptr<ITunerLnb> tunerLnb;
        mTunerService->openLnbByName(lnbName, &tunerLnb);
        return new LnbClient(tunerLnb);*/
    }

    if (mTuner != NULL) {
        // TODO: pending aidl interface
        sp<LnbClient> lnbClient = new LnbClient();
        LnbId id;
        sp<ILnb> hidlLnb = openHidlLnbByName(lnbName, id);
        if (hidlLnb != NULL) {
            lnbClient->setHidlLnb(hidlLnb);
            lnbClient->setId(id);
            return lnbClient;
        }
    }

    return NULL;
}

/////////////// TunerClient Helper Methods ///////////////////////

void TunerClient::updateTunerResources() {
    if (mTuner == NULL) {
        return;
    }

    // Connect with Tuner Resource Manager.
    ::ndk::SpAIBinder binder(AServiceManager_getService("tv_tuner_resource_mgr"));
    mTunerResourceManager = ITunerResourceManager::fromBinder(binder);

    updateFrontendResources();
    updateLnbResources();
    // TODO: update Demux, Descrambler.
}

void TunerClient::updateFrontendResources() {
    vector<FrontendId> ids = getFrontendIds();
    if (ids.size() == 0) {
        return;
    }
    vector<TunerFrontendInfo> infos;
    for (int i = 0; i < ids.size(); i++) {
        shared_ptr<FrontendInfo> frontendInfo = getFrontendInfo((int)ids[i]);
        if (frontendInfo == NULL) {
            continue;
        }
        TunerFrontendInfo tunerFrontendInfo{
            .handle = getResourceHandleFromId((int)ids[i], FRONTEND),
            .frontendType = static_cast<int>(frontendInfo->type),
            .exclusiveGroupId = static_cast<int>(frontendInfo->exclusiveGroupId),
        };
        infos.push_back(tunerFrontendInfo);
    }
    mTunerResourceManager->setFrontendInfoList(infos);
}

void TunerClient::updateLnbResources() {
    vector<int> handles = getLnbHandles();
    if (handles.size() == 0) {
        return;
    }
    mTunerResourceManager->setLnbInfoList(handles);
}

sp<ITuner> TunerClient::getHidlTuner() {
    if (mTuner == NULL) {
        mTunerVersion = 0;
        mTuner_1_1 = ::android::hardware::tv::tuner::V1_1::ITuner::getService();

        if (mTuner_1_1 == NULL) {
            ALOGW("Failed to get tuner 1.1 service.");
            mTuner = ITuner::getService();
            if (mTuner == NULL) {
                ALOGW("Failed to get tuner 1.0 service.");
            } else {
                mTunerVersion = 1 << 16;
            }
        } else {
            mTuner = static_cast<sp<ITuner>>(mTuner_1_1);
            mTunerVersion = ((1 << 16) | 1);
         }
     }
     return mTuner;
}

sp<IFrontend> TunerClient::openHidlFrontendById(int id) {
    sp<IFrontend> fe;
    Result res;
    mTuner->openFrontendById(id, [&](Result r, const sp<IFrontend>& frontend) {
        fe = frontend;
        res = r;
    });
    if (res != Result::SUCCESS || fe == nullptr) {
        ALOGE("Failed to open frontend");
        return NULL;
    }
    return fe;
}

Result TunerClient::getHidlFrontendInfo(int id, FrontendInfo& feInfo) {
    Result res;
    mTuner->getFrontendInfo(id, [&](Result r, const FrontendInfo& info) {
        feInfo = info;
        res = r;
    });
    return res;
}

sp<IDemux> TunerClient::openHidlDemux(int& demuxId) {
    sp<IDemux> demux;
    Result res;

    mTuner->openDemux([&](Result result, uint32_t id, const sp<IDemux>& demuxSp) {
        demux = demuxSp;
        demuxId = id;
        res = result;
    });
    if (res != Result::SUCCESS || demux == nullptr) {
        ALOGE("Failed to open demux");
        return NULL;
    }
    return demux;
}

sp<ILnb> TunerClient::openHidlLnbById(int id) {
    sp<ILnb> lnb;
    Result res;

    mTuner->openLnbById(id, [&](Result r, const sp<ILnb>& lnbSp) {
        res = r;
        lnb = lnbSp;
    });
    if (res != Result::SUCCESS || lnb == nullptr) {
        ALOGE("Failed to open lnb by id");
        return NULL;
    }
    return lnb;
}

sp<ILnb> TunerClient::openHidlLnbByName(string name, LnbId& lnbId) {
    sp<ILnb> lnb;
    Result res;

    mTuner->openLnbByName(name, [&](Result r, LnbId id, const sp<ILnb>& lnbSp) {
        res = r;
        lnb = lnbSp;
        lnbId = id;
    });
    if (res != Result::SUCCESS || lnb == nullptr) {
        ALOGE("Failed to open lnb by name");
        return NULL;
    }
    return lnb;
}

sp<IDescrambler> TunerClient::openHidlDescrambler() {
    sp<IDescrambler> descrambler;
    Result res;

    mTuner->openDescrambler([&](Result r, const sp<IDescrambler>& descramblerSp) {
        res = r;
        descrambler = descramblerSp;
    });

    if (res != Result::SUCCESS || descrambler == NULL) {
        return NULL;
    }

    return descrambler;
}

vector<int> TunerClient::getLnbHandles() {
    vector<int> lnbHandles;

    if (mTunerService != NULL) {
        // TODO: pending hidl interface
    }

    if (mTuner != NULL) {
        Result res;
        vector<LnbId> lnbIds;
        mTuner->getLnbIds([&](Result r, const hardware::hidl_vec<LnbId>& ids) {
            lnbIds = ids;
            res = r;
        });
        if (res != Result::SUCCESS || lnbIds.size() == 0) {
            ALOGW("Lnb isn't available");
        } else {
            for (int i = 0; i < lnbIds.size(); i++) {
                lnbHandles.push_back(getResourceHandleFromId((int)lnbIds[i], LNB));
            }
        }
    }

    return lnbHandles;
}

FrontendInfo TunerClient::FrontendInfoAidlToHidl(TunerServiceFrontendInfo aidlFrontendInfo) {
    FrontendInfo hidlFrontendInfo {
        .type = static_cast<FrontendType>(aidlFrontendInfo.type),
        .minFrequency = static_cast<uint32_t>(aidlFrontendInfo.minFrequency),
        .maxFrequency = static_cast<uint32_t>(aidlFrontendInfo.maxFrequency),
        .minSymbolRate = static_cast<uint32_t>(aidlFrontendInfo.minSymbolRate),
        .maxSymbolRate = static_cast<uint32_t>(aidlFrontendInfo.maxSymbolRate),
        .acquireRange = static_cast<uint32_t>(aidlFrontendInfo.acquireRange),
        .exclusiveGroupId = static_cast<uint32_t>(aidlFrontendInfo.exclusiveGroupId),
    };
    // TODO: handle Frontend caps

    return hidlFrontendInfo;
}

int TunerClient::getResourceIdFromHandle(int handle, int /*resourceType*/) {
    return (handle & 0x00ff0000) >> 16;
}

int TunerClient::getResourceHandleFromId(int id, int resourceType) {
    // TODO: build up randomly generated id to handle mapping
    return (resourceType & 0x000000ff) << 24
            | (id << 16)
            | (mResourceRequestCount++ & 0xffff);
}
}  // namespace android
