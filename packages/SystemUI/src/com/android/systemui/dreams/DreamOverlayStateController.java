/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.dreams;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.complication.Complication;
import com.android.systemui.statusbar.policy.CallbackController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * {@link DreamOverlayStateController} is the source of truth for Dream overlay configurations and
 * state. Clients can register as listeners for changes to the overlay composition and can query for
 * the complications on-demand.
 */
@SysUISingleton
public class DreamOverlayStateController implements
        CallbackController<DreamOverlayStateController.Callback> {
    /**
     * Callback for dream overlay events.
     */
    public interface Callback {
        /**
         * Called when the composition of complications changes.
         */
        default void onComplicationsChanged() {
        }
    }

    private final Executor mExecutor;
    private final ArrayList<Callback> mCallbacks = new ArrayList<>();

    private final Collection<Complication> mComplications = new HashSet();

    @VisibleForTesting
    @Inject
    public DreamOverlayStateController(@Main Executor executor) {
        mExecutor = executor;
    }

    /**
     * Adds a complication to be included on the dream overlay.
     */
    public void addComplication(Complication complication) {
        mExecutor.execute(() -> {
            if (mComplications.add(complication)) {
                mCallbacks.stream().forEach(callback -> callback.onComplicationsChanged());
            }
        });
    }

    /**
     * Removes a complication from inclusion on the dream overlay.
     */
    public void removeComplication(Complication complication) {
        mExecutor.execute(() -> {
            if (mComplications.remove(complication)) {
                mCallbacks.stream().forEach(callback -> callback.onComplicationsChanged());
            }
        });
    }

    /**
     * Returns collection of present {@link Complication}.
     */
    public Collection<Complication> getComplications() {
        return Collections.unmodifiableCollection(mComplications);
    }

    @Override
    public void addCallback(@NonNull Callback callback) {
        mExecutor.execute(() -> {
            Objects.requireNonNull(callback, "Callback must not be null. b/128895449");
            if (mCallbacks.contains(callback)) {
                return;
            }

            mCallbacks.add(callback);

            if (mComplications.isEmpty()) {
                return;
            }

            callback.onComplicationsChanged();
        });
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        mExecutor.execute(() -> {
            Objects.requireNonNull(callback, "Callback must not be null. b/128895449");
            mCallbacks.remove(callback);
        });
    }
}
