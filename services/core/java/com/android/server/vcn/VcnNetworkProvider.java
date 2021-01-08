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

package com.android.server.vcn;

import android.annotation.NonNull;
import android.content.Context;
import android.net.NetworkProvider;
import android.net.NetworkRequest;
import android.os.Looper;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import java.util.Objects;
import java.util.Set;

/**
 * VCN Network Provider routes NetworkRequests to listeners to bring up tunnels as needed.
 *
 * <p>The VcnNetworkProvider provides a caching layer to ensure that all listeners receive all
 * active NetworkRequest(s), including ones that were filed prior to listener registration.
 *
 * @hide
 */
public class VcnNetworkProvider extends NetworkProvider {
    private static final String TAG = VcnNetworkProvider.class.getSimpleName();

    private final Set<NetworkRequestListener> mListeners = new ArraySet<>();
    private final SparseArray<NetworkRequestEntry> mRequests = new SparseArray<>();

    public VcnNetworkProvider(Context context, Looper looper) {
        super(context, looper, VcnNetworkProvider.class.getSimpleName());
    }

    // Package-private
    void registerListener(@NonNull NetworkRequestListener listener) {
        mListeners.add(listener);

        // Send listener all cached requests
        for (int i = 0; i < mRequests.size(); i++) {
            notifyListenerForEvent(listener, mRequests.valueAt(i));
        }
    }

    // Package-private
    void unregisterListener(@NonNull NetworkRequestListener listener) {
        mListeners.remove(listener);
    }

    private void notifyListenerForEvent(
            @NonNull NetworkRequestListener listener, @NonNull NetworkRequestEntry entry) {
        listener.onNetworkRequested(entry.mRequest, entry.mScore, entry.mProviderId);
    }

    @Override
    public void onNetworkRequested(@NonNull NetworkRequest request, int score, int providerId) {
        Slog.v(
                TAG,
                String.format(
                        "Network requested: Request = %s, score = %d, providerId = %d",
                        request, score, providerId));

        final NetworkRequestEntry entry = new NetworkRequestEntry(request, score, providerId);
        mRequests.put(request.requestId, entry);

        // TODO(b/176939047): Intelligently route requests to prioritized VcnInstances (based on
        // Default Data Sub, or similar)
        for (NetworkRequestListener listener : mListeners) {
            notifyListenerForEvent(listener, entry);
        }
    }

    @Override
    public void onNetworkRequestWithdrawn(@NonNull NetworkRequest request) {
        mRequests.remove(request.requestId);
    }

    private static class NetworkRequestEntry {
        public final NetworkRequest mRequest;
        public final int mScore;
        public final int mProviderId;

        private NetworkRequestEntry(@NonNull NetworkRequest request, int score, int providerId) {
            mRequest = Objects.requireNonNull(request, "Missing request");
            mScore = score;
            mProviderId = providerId;
        }
    }

    // package-private
    interface NetworkRequestListener {
        void onNetworkRequested(@NonNull NetworkRequest request, int score, int providerId);
    }
}
