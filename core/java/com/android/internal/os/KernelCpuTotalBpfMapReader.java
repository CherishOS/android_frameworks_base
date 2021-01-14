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

package com.android.internal.os;

/**
 * Reads total CPU time bpf map.
 */
public final class KernelCpuTotalBpfMapReader {
    private KernelCpuTotalBpfMapReader() {
    }

    /** Returns whether total CPU time is measured. */
    public static boolean isSupported() {
        // TODO(b/174245730): Implement this check.
        return true;
    }

    /** Reads total CPU time from bpf map. */
    public static native boolean read(Callback callback);

    /** Callback accepting values read from bpf map. */
    public interface Callback {
        /**
         * Accepts values read from bpf map: cluster index, frequency in kilohertz and time in
         * milliseconds that the cpu cluster spent at the frequency (excluding sleep).
         */
        void accept(int cluster, int freqKhz, long timeMs);
    }
}
