/*
 * Copyright 2017 The Android Open Source Project
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

package android.webkit;

import android.annotation.NonNull;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Simple TracingOutputStream implementation which writes the trace data from
 * {@link TracingController} to a new file.
 *
 */
public class TracingFileOutputStream implements TracingController.TracingOutputStream {

    private FileOutputStream mFileOutput;

    public TracingFileOutputStream(@NonNull String filename) throws FileNotFoundException {
        mFileOutput = new FileOutputStream(filename);
    }

    /**
     * Writes bytes chunk to the file.
     */
    public void write(byte[] chunk) {
        try {
            mFileOutput.write(chunk);
        } catch (IOException e) {
            onIOException(e);
        }
    }

    /**
     * Closes the file.
     */
    public void complete() {
        try {
            mFileOutput.close();
        } catch (IOException e) {
            onIOException(e);
        }
    }

    private void onIOException(IOException e) {
        throw new RuntimeException(e);
    }
}
