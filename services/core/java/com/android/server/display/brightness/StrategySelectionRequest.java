/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display.brightness;

import android.hardware.display.DisplayManagerInternal;

import java.util.Objects;

/**
 * A wrapper class to encapsulate the request to select a strategy from
 * DisplayBrightnessStrategySelector
 */
public final class StrategySelectionRequest {
    // The request to change the associated display's state and brightness
    private DisplayManagerInternal.DisplayPowerRequest mDisplayPowerRequest;

    // The display state to which the screen is switching to
    private int mTargetDisplayState;

    public StrategySelectionRequest(DisplayManagerInternal.DisplayPowerRequest displayPowerRequest,
            int targetDisplayState) {
        mDisplayPowerRequest = displayPowerRequest;
        mTargetDisplayState = targetDisplayState;
    }

    public DisplayManagerInternal.DisplayPowerRequest getDisplayPowerRequest() {
        return mDisplayPowerRequest;
    }

    public int getTargetDisplayState() {
        return mTargetDisplayState;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof StrategySelectionRequest)) {
            return false;
        }
        StrategySelectionRequest other = (StrategySelectionRequest) obj;
        return Objects.equals(other.getDisplayPowerRequest(), getDisplayPowerRequest())
                && other.getTargetDisplayState() == getTargetDisplayState();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDisplayPowerRequest, mTargetDisplayState);
    }
}
