/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.util.sensors;

import android.content.res.Resources;

public class FakeProximitySensor extends ProximitySensor {
    private boolean mAvailable;
    private boolean mRegistered;

    public FakeProximitySensor(Resources resources, AsyncSensorManager sensorManager) {
        super(resources, sensorManager);
        mAvailable = true;
    }

    public void setSensorAvailable(boolean available) {
        mAvailable = available;
    }

    public void setLastEvent(ProximityEvent event) {
        mLastEvent = event;
    }

    @Override
    public boolean isRegistered() {
        return mRegistered;
    }

    @Override
    public boolean getSensorAvailable() {
        return mAvailable;
    }

    @Override
    protected void registerInternal() {
        mRegistered = !mPaused;
    }

    @Override
    protected void unregisterInternal() {
        mRegistered = false;
    }
}
