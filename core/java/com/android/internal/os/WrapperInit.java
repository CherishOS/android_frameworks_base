/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.os.Process;
import android.util.Slog;
import com.android.internal.os.Zygote.MethodAndArgsCaller;
import dalvik.system.VMRuntime;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;

import libcore.io.IoUtils;

/**
 * Startup class for the wrapper process.
 * @hide
 */
public class WrapperInit {
    private final static String TAG = "AndroidRuntime";

    /**
     * Class not instantiable.
     */
    private WrapperInit() {
    }

    /**
     * The main function called when starting a runtime application through a
     * wrapper process instead of by forking Zygote.
     *
     * The first argument specifies the file descriptor for a pipe that should receive
     * the pid of this process, or 0 if none.
     *
     * The second argument is the target SDK version for the app.
     *
     * The remaining arguments are passed to the runtime.
     *
     * @param args The command-line arguments.
     */
    public static void main(String[] args) {
        try {
            // Parse our mandatory arguments.
            int fdNum = Integer.parseInt(args[0], 10);
            int targetSdkVersion = Integer.parseInt(args[1], 10);

            // Tell the Zygote what our actual PID is (since it only knows about the
            // wrapper that it directly forked).
            if (fdNum != 0) {
                try {
                    FileDescriptor fd = new FileDescriptor();
                    fd.setInt$(fdNum);
                    DataOutputStream os = new DataOutputStream(new FileOutputStream(fd));
                    os.writeInt(Process.myPid());
                    os.close();
                    IoUtils.closeQuietly(fd);
                } catch (IOException ex) {
                    Slog.d(TAG, "Could not write pid of wrapped process to Zygote pipe.", ex);
                }
            }

            // Mimic system Zygote preloading.
            ZygoteInit.preload();

            // Launch the application.
            String[] runtimeArgs = new String[args.length - 2];
            System.arraycopy(args, 2, runtimeArgs, 0, runtimeArgs.length);
            WrapperInit.wrapperInit(targetSdkVersion, runtimeArgs);
        } catch (Zygote.MethodAndArgsCaller caller) {
            caller.run();
        }
    }

    /**
     * Executes a runtime application with a wrapper command.
     * This method never returns.
     *
     * @param invokeWith The wrapper command.
     * @param niceName The nice name for the application, or null if none.
     * @param targetSdkVersion The target SDK version for the app.
     * @param pipeFd The pipe to which the application's pid should be written, or null if none.
     * @param args Arguments for {@link RuntimeInit#main}.
     */
    public static void execApplication(String invokeWith, String niceName,
            int targetSdkVersion, String instructionSet, FileDescriptor pipeFd,
            String[] args) {
        StringBuilder command = new StringBuilder(invokeWith);

        final String appProcess;
        if (VMRuntime.is64BitInstructionSet(instructionSet)) {
            appProcess = "/system/bin/app_process64";
        } else {
            appProcess = "/system/bin/app_process32";
        }
        command.append(' ');
        command.append(appProcess);

        command.append(" /system/bin --application");
        if (niceName != null) {
            command.append(" '--nice-name=").append(niceName).append("'");
        }
        command.append(" com.android.internal.os.WrapperInit ");
        command.append(pipeFd != null ? pipeFd.getInt$() : 0);
        command.append(' ');
        command.append(targetSdkVersion);
        Zygote.appendQuotedShellArgs(command, args);
        Zygote.execShell(command.toString());
    }

    /**
     * The main function called when an application is started through a
     * wrapper process.
     *
     * When the wrapper starts, the runtime starts {@link RuntimeInit#main}
     * which calls {@link main} which then calls this method.
     * So we don't need to call commonInit() here.
     *
     * @param targetSdkVersion target SDK version
     * @param argv arg strings
     */
    private static void wrapperInit(int targetSdkVersion, String[] argv)
            throws Zygote.MethodAndArgsCaller {
        if (RuntimeInit.DEBUG) {
            Slog.d(RuntimeInit.TAG, "RuntimeInit: Starting application from wrapper");
        }

        // Check whether the first argument is a "-cp" in argv, and assume the next argument is the
        // classpath. If found, create a PathClassLoader and use it for applicationInit.
        ClassLoader classLoader = null;
        if (argv != null && argv.length > 2 && argv[0].equals("-cp")) {
            classLoader = ZygoteInit.createPathClassLoader(argv[1], targetSdkVersion);

            // Install this classloader as the context classloader, too.
            Thread.currentThread().setContextClassLoader(classLoader);

            // Remove the classpath from the arguments.
            String removedArgs[] = new String[argv.length - 2];
            System.arraycopy(argv, 2, removedArgs, 0, argv.length - 2);
            argv = removedArgs;
        }

        RuntimeInit.applicationInit(targetSdkVersion, argv, classLoader);
    }
}
