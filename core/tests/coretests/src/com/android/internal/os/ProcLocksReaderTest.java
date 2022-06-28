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

package com.android.internal.os;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.FileUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ProcLocksReaderTest implements
        ProcLocksReader.ProcLocksReaderCallback {
    private File mProcDirectory;
    private ArrayList<Integer> mPids = new ArrayList<>();

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getContext();
        mProcDirectory = context.getDir("proc", Context.MODE_PRIVATE);
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContents(mProcDirectory);
    }

    @Test
    public void testRunSimpleLocks() throws Exception {
        String simpleLocks =
                "1: POSIX  ADVISORY  READ  18403 fd:09:9070 1073741826 1073742335\n" +
                "2: POSIX  ADVISORY  WRITE 18292 fd:09:34062 0 EOF\n";
        runHandleBlockingFileLocks(simpleLocks);
        assertTrue(mPids.isEmpty());
    }

    @Test
    public void testRunBlockingLocks() throws Exception {
        String blockedLocks =
                "1: POSIX  ADVISORY  READ  18403 fd:09:9070 1073741826 1073742335\n" +
                "2: POSIX  ADVISORY  WRITE 18292 fd:09:34062 0 EOF\n" +
                "2: -> POSIX  ADVISORY  WRITE 18291 fd:09:34062 0 EOF\n" +
                "2: -> POSIX  ADVISORY  WRITE 18293 fd:09:34062 0 EOF\n" +
                "3: POSIX  ADVISORY  READ  3888 fd:09:13992 128 128\n" +
                "4: POSIX  ADVISORY  READ  3888 fd:09:14230 1073741826 1073742335\n";
        runHandleBlockingFileLocks(blockedLocks);
        assertTrue(mPids.remove(0).equals(18292));
        assertTrue(mPids.isEmpty());
    }

    @Test
    public void testRunMultipleBlockingLocks() throws Exception {
        String blockedLocks =
                "1: POSIX  ADVISORY  READ  18403 fd:09:9070 1073741826 1073742335\n" +
                "2: POSIX  ADVISORY  WRITE 18292 fd:09:34062 0 EOF\n" +
                "2: -> POSIX  ADVISORY  WRITE 18291 fd:09:34062 0 EOF\n" +
                "2: -> POSIX  ADVISORY  WRITE 18293 fd:09:34062 0 EOF\n" +
                "3: POSIX  ADVISORY  READ  3888 fd:09:13992 128 128\n" +
                "4: FLOCK  ADVISORY  WRITE 3840 fe:01:5111809 0 EOF\n" +
                "4: -> FLOCK  ADVISORY  WRITE 3841 fe:01:5111809 0 EOF\n" +
                "5: POSIX  ADVISORY  READ  3888 fd:09:14230 1073741826 1073742335\n";
        runHandleBlockingFileLocks(blockedLocks);
        assertTrue(mPids.remove(0).equals(18292));
        assertTrue(mPids.remove(0).equals(3840));
        assertTrue(mPids.isEmpty());
    }

    private void runHandleBlockingFileLocks(String fileContents) throws Exception {
        File tempFile = File.createTempFile("locks", null, mProcDirectory);
        Files.write(tempFile.toPath(), fileContents.getBytes());
        mPids.clear();
        new ProcLocksReader(tempFile.toString()).handleBlockingFileLocks(this);
        Files.delete(tempFile.toPath());
    }

    /**
     * Call the callback function of handleBlockingFileLocks().
     *
     * @param pid Each process that hold file locks blocking other processes.
     */
    @Override
    public void onBlockingFileLock(int pid) {
        mPids.add(pid);
    }
}
