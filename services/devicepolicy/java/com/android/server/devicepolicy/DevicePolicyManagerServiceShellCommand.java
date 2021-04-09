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
package com.android.server.devicepolicy;

import android.app.admin.DevicePolicyManager;
import android.os.ShellCommand;

import com.android.server.devicepolicy.Owners.OwnerDto;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

final class DevicePolicyManagerServiceShellCommand extends ShellCommand {

    private static final String CMD_IS_SAFE_OPERATION = "is-operation-safe";
    private static final String CMD_IS_SAFE_OPERATION_BY_REASON = "is-operation-safe-by-reason";
    private static final String CMD_SET_SAFE_OPERATION = "set-operation-safe";
    private static final String CMD_LIST_OWNERS = "list-owners";
    private static final String CMD_LIST_POLICY_EXEMPT_APPS = "list-policy-exempt-apps";

    private final DevicePolicyManagerService mService;

    DevicePolicyManagerServiceShellCommand(DevicePolicyManagerService service) {
        mService = Objects.requireNonNull(service);
    }

    @Override
    public void onHelp() {
        try (PrintWriter pw = getOutPrintWriter();) {
            pw.printf("DevicePolicyManager Service (device_policy) commands:\n\n");
            showHelp(pw);
        }
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        try (PrintWriter pw = getOutPrintWriter();) {
            switch (cmd) {
                case CMD_IS_SAFE_OPERATION:
                    return runIsSafeOperation(pw);
                case CMD_IS_SAFE_OPERATION_BY_REASON:
                    return runIsSafeOperationByReason(pw);
                case CMD_SET_SAFE_OPERATION:
                    return runSetSafeOperation(pw);
                case CMD_LIST_OWNERS:
                    return runListOwners(pw);
                case CMD_LIST_POLICY_EXEMPT_APPS:
                    return runListPolicyExemptApps(pw);
                default:
                    return onInvalidCommand(pw, cmd);
            }
        }
    }

    private int onInvalidCommand(PrintWriter pw, String cmd) {
        if (super.handleDefaultCommands(cmd) == 0) {
            return 0;
        }

        pw.println("Usage: ");
        showHelp(pw);
        return -1;
    }

    private void showHelp(PrintWriter pw) {
        pw.printf("  help\n");
        pw.printf("    Prints this help text.\n\n");
        pw.printf("  %s <OPERATION_ID>\n", CMD_IS_SAFE_OPERATION);
        pw.printf("    Checks if the give operation is safe \n\n");
        pw.printf("  %s <REASON_ID>\n", CMD_IS_SAFE_OPERATION_BY_REASON);
        pw.printf("    Checks if the operations are safe for the given reason\n\n");
        pw.printf("  %s <OPERATION_ID> <REASON_ID>\n", CMD_SET_SAFE_OPERATION);
        pw.printf("    Emulates the result of the next call to check if the given operation is safe"
                + " \n\n");
        pw.printf("  %s\n", CMD_LIST_OWNERS);
        pw.printf("    Lists the device / profile owners per user \n\n");
        pw.printf("  %s\n", CMD_LIST_POLICY_EXEMPT_APPS);
        pw.printf("    Lists the apps that are exempt from policies\n\n");
    }

    private int runIsSafeOperation(PrintWriter pw) {
        int operation = Integer.parseInt(getNextArgRequired());
        int reason = mService.getUnsafeOperationReason(operation);
        boolean safe = reason == DevicePolicyManager.OPERATION_SAFETY_REASON_NONE;
        pw.printf("Operation %s is %s. Reason: %s\n",
                DevicePolicyManager.operationToString(operation), safeToString(safe),
                DevicePolicyManager.operationSafetyReasonToString(reason));
        return 0;
    }

    private int runIsSafeOperationByReason(PrintWriter pw) {
        int reason = Integer.parseInt(getNextArgRequired());
        boolean safe = mService.isSafeOperation(reason);
        pw.printf("Operations affected by %s are %s\n",
                DevicePolicyManager.operationSafetyReasonToString(reason), safeToString(safe));
        return 0;
    }

    private static String safeToString(boolean safe) {
        return safe ? "SAFE" : "UNSAFE";
    }

    private int runSetSafeOperation(PrintWriter pw) {
        int operation = Integer.parseInt(getNextArgRequired());
        int reason = Integer.parseInt(getNextArgRequired());
        mService.setNextOperationSafety(operation, reason);
        pw.printf("Next call to check operation %s will return %s\n",
                DevicePolicyManager.operationToString(operation),
                DevicePolicyManager.operationSafetyReasonToString(reason));
        return 0;
    }

    private int printAndGetSize(PrintWriter pw, Collection<?> collection, String nameOnSingular) {
        if (collection.isEmpty()) {
            pw.printf("no %ss\n", nameOnSingular);
            return 0;
        }
        int size = collection.size();
        pw.printf("%d %s%s:\n", size, nameOnSingular, (size == 1 ? "" : "s"));
        return size;
    }

    private int runListOwners(PrintWriter pw) {
        List<OwnerDto> owners = mService.listAllOwners();
        int size = printAndGetSize(pw, owners, "owner");
        if (size == 0) return 0;

        for (int i = 0; i < size; i++) {
            OwnerDto owner = owners.get(i);
            pw.printf("User %2d: admin=%s", owner.userId, owner.admin.flattenToShortString());
            if (owner.isDeviceOwner) {
                pw.print(",DeviceOwner");
            }
            if (owner.isProfileOwner) {
                pw.print(",ProfileOwner");
            }
            if (owner.isAffiliated) {
                pw.print(",Affiliated");
            }
            pw.println();
        }

        return 0;
    }


    private int runListPolicyExemptApps(PrintWriter pw) {
        List<String> apps = mService.listPolicyExemptApps();
        int size = printAndGetSize(pw, apps, "policy exempt app");

        if (size == 0) return 0;

        for (int i = 0; i < size; i++) {
            String app = apps.get(i);
            pw.printf("  %d: %s\n", i, app);
        }
        return 0;
    }
}
