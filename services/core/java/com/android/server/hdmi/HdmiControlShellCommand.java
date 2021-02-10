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

package com.android.server.hdmi;


import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.hdmi.IHdmiControlService;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class HdmiControlShellCommand extends ShellCommand {

    private static final String TAG = "HdmiShellCommand";

    private final IHdmiControlService.Stub mBinderService;


    HdmiControlShellCommand(IHdmiControlService.Stub binderService) {
        mBinderService = binderService;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        try {
            return handleShellCommand(cmd);
        } catch (Exception e) {
            getErrPrintWriter().println(
                    "Caught error for command '" + cmd + "': " + e.getMessage());
            Slog.e(TAG, "Error handling hdmi_control shell command: " + cmd, e);
            return 1;
        }
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();

        pw.println("HdmiControlManager (hdmi_control) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  onetouchplay, otp");
        pw.println("      Send the \"One Touch Play\" feature from a source to the TV");
        pw.println("  vendorcommand --device_type <originating device type>");
        pw.println("                --destination <destination device>");
        pw.println("                --args <vendor specific arguments>");
        pw.println("                [--id <true if vendor command should be sent with vendor id>]");
        pw.println("      Send a Vendor Command to the given target device");
        pw.println("  cec_setting get <setting name>");
        pw.println("      Get the current value of a CEC setting");
        pw.println("  cec_setting set <setting name> <value>");
        pw.println("      Set the value of a CEC setting");
    }

    private int handleShellCommand(String cmd) throws RemoteException {
        PrintWriter pw = getOutPrintWriter();

        switch (cmd) {
            case "otp":
            case "onetouchplay":
                return oneTouchPlay(pw);
            case "vendorcommand":
                return vendorCommand(pw);
            case "cec_setting":
                return cecSetting(pw);
        }

        getErrPrintWriter().println("Unhandled command: " + cmd);
        return 1;
    }

    private int oneTouchPlay(PrintWriter pw) throws RemoteException {
        final CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger cecResult = new AtomicInteger();
        pw.print("Sending One Touch Play...");
        mBinderService.oneTouchPlay(new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
                pw.println(" done (" + result + ")");
                latch.countDown();
                cecResult.set(result);
            }
        });

        try {
            if (!latch.await(HdmiConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                getErrPrintWriter().println("One Touch Play timed out.");
                return 1;
            }
        } catch (InterruptedException e) {
            getErrPrintWriter().println("Caught InterruptedException");
            Thread.currentThread().interrupt();
        }
        return cecResult.get() == HdmiControlManager.RESULT_SUCCESS ? 0 : 1;
    }

    private int vendorCommand(PrintWriter pw) throws RemoteException {
        if (6 > getRemainingArgsCount()) {
            throw new IllegalArgumentException("Expected 3 arguments.");
        }

        int deviceType = -1;
        int destination = -1;
        String parameters = "";
        boolean hasVendorId = false;

        String arg = getNextOption();
        while (arg != null) {
            switch (arg) {
                case "-t":
                case "--device_type":
                    deviceType = Integer.parseInt(getNextArgRequired());
                    break;
                case "-d":
                case "--destination":
                    destination = Integer.parseInt(getNextArgRequired());
                    break;
                case "-a":
                case "--args":
                    parameters = getNextArgRequired();
                    break;
                case "-i":
                case "--id":
                    hasVendorId = Boolean.parseBoolean(getNextArgRequired());
                    break;
                default:
                    throw new IllegalArgumentException("Unknown argument: " + arg);
            }
            arg = getNextArg();
        }

        String[] parts = parameters.split(":");
        byte[] params = new byte[parts.length];
        for (int i = 0; i < params.length; i++) {
            params[i] = (byte) Integer.parseInt(parts[i], 16);
        }

        pw.println("Sending <Vendor Command>");
        mBinderService.sendVendorCommand(deviceType, destination, params, hasVendorId);
        return 0;
    }

    private int cecSetting(PrintWriter pw) throws RemoteException {
        if (getRemainingArgsCount() < 1) {
            throw new IllegalArgumentException("Expected at least 1 argument (operation).");
        }
        String operation = getNextArgRequired();
        switch (operation) {
            case "get": {
                String setting = getNextArgRequired();
                try {
                    String value = mBinderService.getCecSettingStringValue(setting);
                    pw.println(setting + " = " + value);
                } catch (IllegalArgumentException e) {
                    int intValue = mBinderService.getCecSettingIntValue(setting);
                    pw.println(setting + " = " + intValue);
                }
                return 0;
            }
            case "set": {
                String setting = getNextArgRequired();
                String value = getNextArgRequired();
                try {
                    mBinderService.setCecSettingStringValue(setting, value);
                    pw.println(setting + " = " + value);
                } catch (IllegalArgumentException e) {
                    int intValue = Integer.parseInt(value);
                    mBinderService.setCecSettingIntValue(setting, intValue);
                    pw.println(setting + " = " + intValue);
                }
                return 0;
            }
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }
}
