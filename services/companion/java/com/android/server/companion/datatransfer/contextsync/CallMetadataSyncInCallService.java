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

package com.android.server.companion.datatransfer.contextsync;

import android.annotation.Nullable;
import android.companion.AssociationInfo;
import android.telecom.Call;
import android.telecom.InCallService;
import android.telecom.TelecomManager;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.companion.CompanionDeviceConfig;
import com.android.server.companion.CompanionDeviceManagerServiceInternal;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * In-call service to sync call metadata across a user's devices. Note that mute and silence are
 * global states and apply to all current calls.
 */
public class CallMetadataSyncInCallService extends InCallService {

    private static final String TAG = "CallMetadataIcs";
    private static final long NOT_VALID = -1L;

    private CompanionDeviceManagerServiceInternal mCdmsi;

    @VisibleForTesting
    final Map<Call, CrossDeviceCall> mCurrentCalls = new HashMap<>();
    @VisibleForTesting int mNumberOfActiveSyncAssociations;
    final Call.Callback mTelecomCallback = new Call.Callback() {
        @Override
        public void onDetailsChanged(Call call, Call.Details details) {
            if (mNumberOfActiveSyncAssociations > 0) {
                final CrossDeviceCall crossDeviceCall = mCurrentCalls.get(call);
                if (crossDeviceCall != null) {
                    crossDeviceCall.updateCallDetails(details);
                    sync(getUserId());
                } else {
                    Slog.w(TAG, "Could not update details for nonexistent call");
                }
            }
        }
    };
    final CrossDeviceSyncControllerCallback
            mCrossDeviceSyncControllerCallback = new CrossDeviceSyncControllerCallback() {
                @Override
                void processContextSyncMessage(int associationId,
                        CallMetadataSyncData callMetadataSyncData) {
                    final Iterator<CallMetadataSyncData.Call> iterator =
                            callMetadataSyncData.getRequests().iterator();
                    while (iterator.hasNext()) {
                        final CallMetadataSyncData.Call call = iterator.next();
                        if (call.getId() != 0) {
                            // The call is already assigned an id; treat as control invocations.
                            for (int control : call.getControls()) {
                                processCallControlAction(call.getId(), control);
                            }
                        }
                        iterator.remove();
                    }
                }

                private void processCallControlAction(long crossDeviceCallId,
                        int callControlAction) {
                    final CrossDeviceCall crossDeviceCall = getCallForId(crossDeviceCallId,
                            mCurrentCalls.values());
                    switch (callControlAction) {
                        case android.companion.Telecom.Call.ACCEPT:
                            if (crossDeviceCall != null) {
                                crossDeviceCall.doAccept();
                            }
                            break;
                        case android.companion.Telecom.Call.REJECT:
                            if (crossDeviceCall != null) {
                                crossDeviceCall.doReject();
                            }
                            break;
                        case android.companion.Telecom.Call.SILENCE:
                            doSilence();
                            break;
                        case android.companion.Telecom.Call.MUTE:
                            doMute();
                            break;
                        case android.companion.Telecom.Call.UNMUTE:
                            doUnmute();
                            break;
                        case android.companion.Telecom.Call.END:
                            if (crossDeviceCall != null) {
                                crossDeviceCall.doEnd();
                            }
                            break;
                        case android.companion.Telecom.Call.PUT_ON_HOLD:
                            if (crossDeviceCall != null) {
                                crossDeviceCall.doPutOnHold();
                            }
                            break;
                        case android.companion.Telecom.Call.TAKE_OFF_HOLD:
                            if (crossDeviceCall != null) {
                                crossDeviceCall.doTakeOffHold();
                            }
                            break;
                        default:
                    }
                }

                @Override
                void requestCrossDeviceSync(AssociationInfo associationInfo) {
                    if (associationInfo.getUserId() == getUserId()) {
                        sync(associationInfo);
                    }
                }

                @Override
                void updateNumberOfActiveSyncAssociations(int userId, boolean added) {
                    if (userId == getUserId()) {
                        final boolean wasActivelySyncing = mNumberOfActiveSyncAssociations > 0;
                        if (added) {
                            mNumberOfActiveSyncAssociations++;
                        } else {
                            mNumberOfActiveSyncAssociations--;
                        }
                        if (!wasActivelySyncing && mNumberOfActiveSyncAssociations > 0) {
                            initializeCalls();
                        } else if (wasActivelySyncing && mNumberOfActiveSyncAssociations <= 0) {
                            mCurrentCalls.clear();
                        }
                    }
                }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (CompanionDeviceConfig.isEnabled(CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
            mCdmsi = LocalServices.getService(CompanionDeviceManagerServiceInternal.class);
            mCdmsi.registerCallMetadataSyncCallback(mCrossDeviceSyncControllerCallback);
        }
    }

    private void initializeCalls() {
        if (CompanionDeviceConfig.isEnabled(CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)
                && mNumberOfActiveSyncAssociations > 0) {
            mCurrentCalls.putAll(getCalls().stream().collect(Collectors.toMap(call -> call,
                    call -> new CrossDeviceCall(getPackageManager(), call, getCallAudioState()))));
            mCurrentCalls.keySet().forEach(call -> call.registerCallback(mTelecomCallback,
                    getMainThreadHandler()));
            sync(getUserId());
        }
    }

    @Nullable
    @VisibleForTesting
    CrossDeviceCall getCallForId(long crossDeviceCallId, Collection<CrossDeviceCall> calls) {
        if (crossDeviceCallId == NOT_VALID) {
            return null;
        }
        for (CrossDeviceCall crossDeviceCall : calls) {
            if (crossDeviceCall.getId() == crossDeviceCallId) {
                return crossDeviceCall;
            }
        }
        return null;
    }

    @Override
    public void onCallAdded(Call call) {
        if (CompanionDeviceConfig.isEnabled(CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)
                && mNumberOfActiveSyncAssociations > 0) {
            mCurrentCalls.put(call,
                    new CrossDeviceCall(getPackageManager(), call, getCallAudioState()));
            call.registerCallback(mTelecomCallback);
            sync(getUserId());
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        if (CompanionDeviceConfig.isEnabled(CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)
                && mNumberOfActiveSyncAssociations > 0) {
            mCurrentCalls.remove(call);
            call.unregisterCallback(mTelecomCallback);
            sync(getUserId());
        }
    }

    @Override
    public void onMuteStateChanged(boolean isMuted) {
        if (CompanionDeviceConfig.isEnabled(CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)
                && mNumberOfActiveSyncAssociations > 0) {
            mCurrentCalls.values().forEach(call -> call.updateMuted(isMuted));
            sync(getUserId());
        }
    }

    @Override
    public void onSilenceRinger() {
        if (CompanionDeviceConfig.isEnabled(CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)
                && mNumberOfActiveSyncAssociations > 0) {
            mCurrentCalls.values().forEach(call -> call.updateSilencedIfRinging());
            sync(getUserId());
        }
    }

    private void doMute() {
        setMuted(/* shouldMute= */ true);
    }

    private void doUnmute() {
        setMuted(/* shouldMute= */ false);
    }

    private void doSilence() {
        final TelecomManager telecomManager = getSystemService(TelecomManager.class);
        if (telecomManager != null) {
            telecomManager.silenceRinger();
        }
    }

    private void sync(int userId) {
        mCdmsi.crossDeviceSync(userId, mCurrentCalls.values());
    }

    private void sync(AssociationInfo associationInfo) {
        mCdmsi.crossDeviceSync(associationInfo, mCurrentCalls.values());
    }
}