/**
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

package com.android.server.broadcastradio.hal2;

import android.annotation.NonNull;
import android.hardware.radio.RadioManager;
import android.hardware.broadcastradio.V2_0.IBroadcastRadio;
import android.hidl.manager.V1_0.IServiceManager;
import android.os.RemoteException;
import android.util.Slog;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BroadcastRadioService {
    private static final String TAG = "BcRadio2Srv";

    private final Map<Integer, RadioModule> mModules = new HashMap<>();

    private static @NonNull List<String> listByInterface(@NonNull String fqName) {
        try {
            IServiceManager manager = IServiceManager.getService();
            if (manager == null) {
                Slog.e(TAG, "Failed to get HIDL Service Manager");
                return Collections.emptyList();
            }

            List<String> list = manager.listByInterface(fqName);
            if (list == null) {
                Slog.e(TAG, "Didn't get interface list from HIDL Service Manager");
                return Collections.emptyList();
            }
            return list;
        } catch (RemoteException ex) {
            Slog.e(TAG, "Failed fetching interface list", ex);
            return Collections.emptyList();
        }
    }

    public @NonNull Collection<RadioManager.ModuleProperties> loadModules(int idx) {
        Slog.v(TAG, "loadModules(" + idx + ")");

        for (String serviceName : listByInterface(IBroadcastRadio.kInterfaceName)) {
            Slog.v(TAG, "checking service: " + serviceName);

            RadioModule module = RadioModule.tryLoadingModule(idx, serviceName);
            if (module != null) {
                Slog.i(TAG, "loaded broadcast radio module " + idx + ": " +
                        serviceName + " (HAL 2.0)");
                mModules.put(idx++, module);
            }
        }

        return mModules.values().stream().map(module -> module.mProperties).
                collect(Collectors.toList());
    }

    public boolean hasModule(int id) {
        return mModules.containsKey(id);
    }
}
