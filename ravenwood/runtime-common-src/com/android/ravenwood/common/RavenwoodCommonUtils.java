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
package com.android.ravenwood.common;

import com.android.ravenwood.common.divergence.RavenwoodDivergence;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.PrintStream;
import java.util.Arrays;

public class RavenwoodCommonUtils {
    private static final String TAG = "RavenwoodCommonUtils";

    private RavenwoodCommonUtils() {
    }

    private static final Object sLock = new Object();

    /** Name of `libravenwood_runtime` */
    private static final String RAVENWOOD_NATIVE_RUNTIME_NAME = "ravenwood_runtime";

    /** Directory name of `out/host/linux-x86/testcases/ravenwood-runtime` */
    private static final String RAVENWOOD_RUNTIME_DIR_NAME = "ravenwood-runtime";

    private static boolean sEnableExtraRuntimeCheck =
            "1".equals(System.getenv("RAVENWOOD_ENABLE_EXTRA_RUNTIME_CHECK"));

    private static final boolean IS_ON_RAVENWOOD = RavenwoodDivergence.isOnRavenwood();

    private static final String RAVEWOOD_RUNTIME_PATH = getRavenwoodRuntimePathInternal();

    public static final String RAVENWOOD_SYSPROP = "ro.is_on_ravenwood";

    // @GuardedBy("sLock")
    private static boolean sIntegrityChecked = false;

    /**
     * @return if we're running on Ravenwood.
     */
    public static boolean isOnRavenwood() {
        return IS_ON_RAVENWOOD;
    }

    /**
     * Throws if the runtime is not Ravenwood.
     */
    public static void ensureOnRavenwood() {
        if (!isOnRavenwood()) {
            throw new RavenwoodRuntimeException("This is only supposed to be used on Ravenwood");
        }
    }

    /**
     * @return if the various extra runtime check should be enabled.
     */
    public static boolean shouldEnableExtraRuntimeCheck() {
        return sEnableExtraRuntimeCheck;
    }

    /**
     * Load the main runtime JNI library.
     */
    public static void loadRavenwoodNativeRuntime() {
        ensureOnRavenwood();
        loadJniLibrary(RAVENWOOD_NATIVE_RUNTIME_NAME);
    }

    /**
     * Internal implementation of
     * {@link android.platform.test.ravenwood.RavenwoodUtils#loadJniLibrary(String)}
     */
    public static void loadJniLibrary(String libname) {
        if (RavenwoodCommonUtils.isOnRavenwood()) {
            loadJniLibraryInternal(libname);
        } else {
            System.loadLibrary(libname);
        }
    }

    /**
     * Function equivalent to ART's System.loadLibrary. See RavenwoodUtils for why we need it.
     */
    private static void loadJniLibraryInternal(String libname) {
        var path = System.getProperty("java.library.path");
        var filename = "lib" + libname + ".so";

        System.out.println("Looking for library " + libname + ".so in java.library.path:" + path);

        try {
            if (path == null) {
                throw new UnsatisfiedLinkError("Cannot load library " + libname + "."
                        + " Property java.library.path not set!");
            }
            for (var dir : path.split(":")) {
                var file = new File(dir + "/" + filename);
                if (file.exists()) {
                    System.load(file.getAbsolutePath());
                    return;
                }
            }
            throw new UnsatisfiedLinkError("Library " + libname + " not found in "
                    + "java.library.path: " + path);
        } catch (Throwable e) {
            dumpFiles(System.out);
            throw e;
        }
    }

    private static void dumpFiles(PrintStream out) {
        try {
            var path = System.getProperty("java.library.path");
            out.println("# java.library.path=" + path);

            for (var dir : path.split(":")) {
                listFiles(out, new File(dir), "");

                var gparent = new File((new File(dir)).getAbsolutePath() + "../../..")
                        .getCanonicalFile();
                if (gparent.getName().contains("testcases")) {
                    // Special case: if we found this directory, dump its contents too.
                    listFiles(out, gparent, "");
                }
            }

            var gparent = new File("../..").getCanonicalFile();
            out.println("# ../..=" + gparent);
            listFiles(out, gparent, "");
        } catch (Throwable th) {
            out.println("Error: " + th.toString());
            th.printStackTrace(out);
        }
    }

    private static void listFiles(PrintStream out, File dir, String prefix) {
        if (!dir.isDirectory()) {
            out.println(prefix + dir.getAbsolutePath() + " is not a directory!");
            return;
        }
        out.println(prefix + ":" + dir.getAbsolutePath() + "/");
        // First, list the files.
        for (var file : Arrays.stream(dir.listFiles()).sorted().toList()) {
            out.println(prefix + "  " + file.getName() + "" + (file.isDirectory() ? "/" : ""));
        }

        // Then recurse.
        if (dir.getAbsolutePath().startsWith("/usr") || dir.getAbsolutePath().startsWith("/lib")) {
            // There would be too many files, so don't recurse.
            return;
        }
        for (var file : Arrays.stream(dir.listFiles()).sorted().toList()) {
            if (file.isDirectory()) {
                listFiles(out, file, prefix + "  ");
            }
        }
    }

    /**
     * @return the full directory path that contains the "ravenwood-runtime" files.
     *
     * This method throws if called on the device side.
     */
    public static String getRavenwoodRuntimePath() {
        ensureOnRavenwood();
        return RAVEWOOD_RUNTIME_PATH;
    }

    private static String getRavenwoodRuntimePathInternal() {
        if (!isOnRavenwood()) {
            return null;
        }
        var path = System.getProperty("java.library.path");

        System.out.println("Looking for " + RAVENWOOD_RUNTIME_DIR_NAME + " directory"
                + " in java.library.path:" + path);

        try {
            if (path == null) {
                throw new IllegalStateException("java.library.path shouldn't be null");
            }
            for (var dir : path.split(":")) {

                // For each path, see if the path contains RAVENWOOD_RUNTIME_DIR_NAME.
                var d = new File(dir);
                for (;;) {
                    if (d.getParent() == null) {
                        break; // Root dir, stop.
                    }
                    if (RAVENWOOD_RUNTIME_DIR_NAME.equals(d.getName())) {
                        var ret = d.getAbsolutePath() + "/";
                        System.out.println("Found: " + ret);
                        return ret;
                    }
                    d = d.getParentFile();
                }
            }
            throw new IllegalStateException(RAVENWOOD_RUNTIME_DIR_NAME + " not found");
        } catch (Throwable e) {
            dumpFiles(System.out);
            throw e;
        }
    }

    /** Close an {@link AutoCloseable}. */
    public static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    /** Close a {@link FileDescriptor}. */
    public static void closeQuietly(FileDescriptor fd) {
        var is = new FileInputStream(fd);
        RavenwoodCommonUtils.closeQuietly(is);
    }
}
