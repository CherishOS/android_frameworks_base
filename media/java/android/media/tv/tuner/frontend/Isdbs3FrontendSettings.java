/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.frontend;

/**
 * Frontend settings for ISDBS-3.
 * @hide
 */
public class Isdbs3FrontendSettings extends FrontendSettings {
    public int streamId;
    public int streamIdType;
    public int modulation;
    public int coderate;
    public int symbolRate;
    public int rolloff;

    Isdbs3FrontendSettings(int frequency) {
        super(frequency);
    }

    @Override
    public int getType() {
        return FrontendSettings.TYPE_ISDBS3;
    }
}
