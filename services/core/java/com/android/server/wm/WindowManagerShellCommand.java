/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static android.os.Build.IS_USER;
import static android.view.CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED;

import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_SOLID_COLOR;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_WALLPAPER;

import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.Display;
import android.view.IWindowManager;
import android.view.ViewDebug;

import com.android.internal.os.ByteTransferPipe;
import com.android.internal.protolog.ProtoLogImpl;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.LetterboxConfiguration.LetterboxBackgroundType;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ShellCommands for WindowManagerService.
 *
 * Use with {@code adb shell cmd window ...}.
 */
public class WindowManagerShellCommand extends ShellCommand {

    // IPC interface to activity manager -- don't need to do additional security checks.
    private final IWindowManager mInterface;

    // Internal service impl -- must perform security checks before touching.
    private final WindowManagerService mInternal;
    private final LetterboxConfiguration mLetterboxConfiguration;

    public WindowManagerShellCommand(WindowManagerService service) {
        mInterface = service;
        mInternal = service;
        mLetterboxConfiguration = service.mLetterboxConfiguration;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "size":
                    return runDisplaySize(pw);
                case "density":
                    return runDisplayDensity(pw);
                case "folded-area":
                    return runDisplayFoldedArea(pw);
                case "scaling":
                    return runDisplayScaling(pw);
                case "dismiss-keyguard":
                    return runDismissKeyguard(pw);
                case "tracing":
                    // XXX this should probably be changed to use openFileForSystem() to create
                    // the output trace file, so the shell gets the correct semantics for where
                    // trace files can be written.
                    return mInternal.mWindowTracing.onShellCommand(this);
                case "logging":
                    String[] args = peekRemainingArgs();
                    int result = ProtoLogImpl.getSingleInstance().onShellCommand(this);
                    if (result != 0) {
                        // Let the shell try and handle this
                        try (ParcelFileDescriptor pfd
                                     = ParcelFileDescriptor.dup(getOutFileDescriptor())){
                            pw.println("Not handled, calling status bar with args: "
                                    + Arrays.toString(args));
                            LocalServices.getService(StatusBarManagerInternal.class)
                                    .handleWindowManagerLoggingCommand(args, pfd);
                        } catch (IOException e) {
                            pw.println("Failed to handle logging command: " + e.getMessage());
                        }
                    }
                    return result;
                case "user-rotation":
                    return runDisplayUserRotation(pw);
                case "fixed-to-user-rotation":
                    return runFixedToUserRotation(pw);
                case "set-ignore-orientation-request":
                    return runSetIgnoreOrientationRequest(pw);
                case "get-ignore-orientation-request":
                    return runGetIgnoreOrientationRequest(pw);
                case "dump-visible-window-views":
                    return runDumpVisibleWindowViews(pw);
                case "set-letterbox-style":
                    return runSetLetterboxStyle(pw);
                case "get-letterbox-style":
                    return runGetLetterboxStyle(pw);
                case "reset-letterbox-style":
                    return runResetLetterboxStyle(pw);
                case "set-sandbox-display-apis":
                    return runSandboxDisplayApis(pw);
                case "reset":
                    return runReset(pw);
                case "disable-blur":
                    return runSetBlurDisabled(pw);
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private int getDisplayId(String opt) {
        int displayId = Display.DEFAULT_DISPLAY;
        String option = "-d".equals(opt) ? opt : getNextOption();
        if (option != null && "-d".equals(option)) {
            try {
                displayId = Integer.parseInt(getNextArgRequired());
            } catch (NumberFormatException e) {
                getErrPrintWriter().println("Error: bad number " + e);
            } catch (IllegalArgumentException e) {
                getErrPrintWriter().println("Error: " + e);
            }
        }
        return displayId;
    }

    private void printInitialDisplaySize(PrintWriter pw , int displayId) {
        final Point initialSize = new Point();
        final Point baseSize = new Point();

        try {
            mInterface.getInitialDisplaySize(displayId, initialSize);
            mInterface.getBaseDisplaySize(displayId, baseSize);
            pw.println("Physical size: " + initialSize.x + "x" + initialSize.y);
            if (!initialSize.equals(baseSize)) {
                pw.println("Override size: " + baseSize.x + "x" + baseSize.y);
            }
        } catch (RemoteException e) {
            // Can't call getInitialDisplaySize() on IWindowManager or
            // Can't call getBaseDisplaySize() on IWindowManager
            pw.println("Remote exception: " + e);
        }
    }

    private int runDisplaySize(PrintWriter pw) throws RemoteException {
        String size = getNextArg();
        int w, h;
        final int displayId = getDisplayId(size);
        if (size == null) {
            printInitialDisplaySize(pw, displayId);
            return 0;
        } else if ("-d".equals(size)) {
            printInitialDisplaySize(pw, displayId);
            return 0;
        } else if ("reset".equals(size)) {
            w = h = -1;
        } else {
            int div = size.indexOf('x');
            if (div <= 0 || div >= (size.length()-1)) {
                getErrPrintWriter().println("Error: bad size " + size);
                return -1;
            }
            String wstr = size.substring(0, div);
            String hstr = size.substring(div+1);
            try {
                w = parseDimension(wstr, displayId);
                h = parseDimension(hstr, displayId);
            } catch (NumberFormatException e) {
                getErrPrintWriter().println("Error: bad number " + e);
                return -1;
            }
        }

        if (w >= 0 && h >= 0) {
            mInterface.setForcedDisplaySize(displayId, w, h);
        } else {
            mInterface.clearForcedDisplaySize(displayId);
        }
        return 0;
    }

    private int runSetBlurDisabled(PrintWriter pw) throws RemoteException {
        String arg = getNextArg();
        if (arg == null) {
            pw.println("Blur supported on device: " + CROSS_WINDOW_BLUR_SUPPORTED);
            pw.println("Blur enabled: " + mInternal.mBlurController.mBlurEnabled);
            return 0;
        }

        final boolean disableBlur;
        switch (arg) {
            case "true":
            case "1":
                disableBlur = true;
                break;
            case "false":
            case "0":
                disableBlur = false;
                break;
            default:
                getErrPrintWriter().println("Error: expected true, 1, false, 0, but got " + arg);
                return -1;
        }

        Settings.Global.putInt(mInternal.mContext.getContentResolver(),
                Settings.Global.DISABLE_WINDOW_BLURS, disableBlur ? 1 : 0);

        return 0;
    }

    private void printInitialDisplayDensity(PrintWriter pw , int displayId) {
        try {
            final int initialDensity = mInterface.getInitialDisplayDensity(displayId);
            final int baseDensity = mInterface.getBaseDisplayDensity(displayId);
            pw.println("Physical density: " + initialDensity);
            if (initialDensity != baseDensity) {
                pw.println("Override density: " + baseDensity);
            }
        } catch (RemoteException e) {
            // Can't call getInitialDisplayDensity() on IWindowManager or
            // Can't call getBaseDisplayDensity() on IWindowManager
            pw.println("Remote exception: " + e);
        }
    }

    private int runDisplayDensity(PrintWriter pw) throws RemoteException {
        String densityStr = getNextArg();
        int density;
        final int displayId = getDisplayId(densityStr);

        if (densityStr == null) {
            printInitialDisplayDensity(pw, displayId);
            return 0;
        } else if ("-d".equals(densityStr)) {
            printInitialDisplayDensity(pw, displayId);
            return 0;
        } else if ("reset".equals(densityStr)) {
            density = -1;
        } else {
            try {
                density = Integer.parseInt(densityStr);
            } catch (NumberFormatException e) {
                getErrPrintWriter().println("Error: bad number " + e);
                return -1;
            }
            if (density < 72) {
                getErrPrintWriter().println("Error: density must be >= 72");
                return -1;
            }
        }

        if (density > 0) {
            mInterface.setForcedDisplayDensityForUser(displayId, density,
                    UserHandle.USER_CURRENT);
        } else {
            mInterface.clearForcedDisplayDensityForUser(displayId,
                    UserHandle.USER_CURRENT);
        }
        return 0;
    }

    private void printFoldedArea(PrintWriter pw) {
        final Rect foldedArea = mInternal.getFoldedArea();
        if (foldedArea.isEmpty()) {
            pw.println("Folded area: none");
        } else {
            pw.println("Folded area: " + foldedArea.left + "," + foldedArea.top + ","
                    + foldedArea.right + "," + foldedArea.bottom);
        }
    }

    private int runDisplayFoldedArea(PrintWriter pw) {
        final String areaStr = getNextArg();
        final Rect rect = new Rect();
        if (areaStr == null) {
            printFoldedArea(pw);
            return 0;
        } else if ("reset".equals(areaStr)) {
            rect.setEmpty();
        } else {
            final Pattern flattenedPattern = Pattern.compile(
                    "(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)");
            final Matcher matcher = flattenedPattern.matcher(areaStr);
            if (!matcher.matches()) {
                getErrPrintWriter().println("Error: area should be LEFT,TOP,RIGHT,BOTTOM");
                return -1;
            }
            rect.set(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)));
        }

        mInternal.setOverrideFoldedArea(rect);
        return 0;
    }

    private int runDisplayScaling(PrintWriter pw) throws RemoteException {
        String scalingStr = getNextArgRequired();
        if ("auto".equals(scalingStr)) {
            mInterface.setForcedDisplayScalingMode(getDisplayId(scalingStr),
                    DisplayContent.FORCE_SCALING_MODE_AUTO);
        } else if ("off".equals(scalingStr)) {
            mInterface.setForcedDisplayScalingMode(getDisplayId(scalingStr),
                    DisplayContent.FORCE_SCALING_MODE_DISABLED);
        } else {
            getErrPrintWriter().println("Error: scaling must be 'auto' or 'off'");
            return -1;
        }
        return 0;
    }

    /**
     * Override display size and metrics to reflect the DisplayArea of the calling activity.
     */
    private int runSandboxDisplayApis(PrintWriter pw) throws RemoteException {
        int displayId = Display.DEFAULT_DISPLAY;
        String arg = getNextArgRequired();
        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
            arg = getNextArgRequired();
        }

        final boolean sandboxDisplayApis;
        switch (arg) {
            case "true":
            case "1":
                sandboxDisplayApis = true;
                break;
            case "false":
            case "0":
                sandboxDisplayApis = false;
                break;
            default:
                getErrPrintWriter().println("Error: expecting true, 1, false, 0, but we "
                        + "get " + arg);
                return -1;
        }

        mInternal.setSandboxDisplayApis(displayId, sandboxDisplayApis);
        return 0;
    }

    private int runDismissKeyguard(PrintWriter pw) throws RemoteException {
        mInterface.dismissKeyguard(null /* callback */, null /* message */);
        return 0;
    }

    private int parseDimension(String s, int displayId) throws NumberFormatException {
        if (s.endsWith("px")) {
            return Integer.parseInt(s.substring(0, s.length() - 2));
        }
        if (s.endsWith("dp")) {
            int density;
            try {
                density = mInterface.getBaseDisplayDensity(displayId);
            } catch (RemoteException e) {
                density = DisplayMetrics.DENSITY_DEFAULT;
            }
            return Integer.parseInt(s.substring(0, s.length() - 2)) * density /
                    DisplayMetrics.DENSITY_DEFAULT;
        }
        return Integer.parseInt(s);
    }

    private int runDisplayUserRotation(PrintWriter pw) {
        int displayId = Display.DEFAULT_DISPLAY;
        String arg = getNextArg();
        if (arg == null) {
            return printDisplayUserRotation(pw, displayId);
        }

        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
            arg = getNextArg();
        }

        final String lockMode = arg;
        if (lockMode == null) {
            return printDisplayUserRotation(pw, displayId);
        }

        if ("free".equals(lockMode)) {
            mInternal.thawDisplayRotation(displayId);
            return 0;
        }

        if (!"lock".equals(lockMode)) {
            getErrPrintWriter().println("Error: argument needs to be either -d, free or lock.");
            return -1;
        }

        arg = getNextArg();
        try {
            final int rotation =
                    arg != null ? Integer.parseInt(arg) : -1 /* lock to current rotation */;
            mInternal.freezeDisplayRotation(displayId, rotation);
            return 0;
        } catch (IllegalArgumentException e) {
            getErrPrintWriter().println("Error: " + e.getMessage());
            return -1;
        }
    }

    private int printDisplayUserRotation(PrintWriter pw, int displayId) {
        final int displayUserRotation = mInternal.getDisplayUserRotation(displayId);
        if (displayUserRotation < 0) {
            getErrPrintWriter().println("Error: check logcat for more details.");
            return -1;
        }
        if (!mInternal.isDisplayRotationFrozen(displayId)) {
            pw.println("free");
            return 0;
        }
        pw.print("lock ");
        pw.println(displayUserRotation);
        return 0;
    }

    private int runFixedToUserRotation(PrintWriter pw) throws RemoteException {
        int displayId = Display.DEFAULT_DISPLAY;
        String arg = getNextArg();
        if (arg == null) {
            printFixedToUserRotation(pw, displayId);
            return 0;
        }

        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
            arg = getNextArg();
        }

        if (arg == null) {
            return printFixedToUserRotation(pw, displayId);
        }

        final int fixedToUserRotation;
        switch (arg) {
            case "enabled":
                fixedToUserRotation = IWindowManager.FIXED_TO_USER_ROTATION_ENABLED;
                break;
            case "disabled":
                fixedToUserRotation = IWindowManager.FIXED_TO_USER_ROTATION_DISABLED;
                break;
            case "default":
                fixedToUserRotation = IWindowManager.FIXED_TO_USER_ROTATION_DEFAULT;
                break;
            default:
                getErrPrintWriter().println("Error: expecting enabled, disabled or default, but we "
                        + "get " + arg);
                return -1;
        }

        mInterface.setFixedToUserRotation(displayId, fixedToUserRotation);
        return 0;
    }

    private int printFixedToUserRotation(PrintWriter pw, int displayId) {
        int fixedToUserRotationMode = mInternal.getFixedToUserRotation(displayId);
        switch (fixedToUserRotationMode) {
            case IWindowManager.FIXED_TO_USER_ROTATION_DEFAULT:
                pw.println("default");
                return 0;
            case IWindowManager.FIXED_TO_USER_ROTATION_DISABLED:
                pw.println("disabled");
                return 0;
            case IWindowManager.FIXED_TO_USER_ROTATION_ENABLED:
                pw.println("enabled");
                return 0;
            default:
                getErrPrintWriter().println("Error: check logcat for more details.");
                return -1;
        }
    }

    private int runSetIgnoreOrientationRequest(PrintWriter pw) throws RemoteException {
        int displayId = Display.DEFAULT_DISPLAY;
        String arg = getNextArgRequired();
        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
            arg = getNextArgRequired();
        }

        final boolean ignoreOrientationRequest;
        switch (arg) {
            case "true":
            case "1":
                ignoreOrientationRequest = true;
                break;
            case "false":
            case "0":
                ignoreOrientationRequest = false;
                break;
            default:
                getErrPrintWriter().println("Error: expecting true, 1, false, 0, but we "
                        + "get " + arg);
                return -1;
        }

        mInterface.setIgnoreOrientationRequest(displayId, ignoreOrientationRequest);
        return 0;
    }

    private int runGetIgnoreOrientationRequest(PrintWriter pw) throws RemoteException {
        int displayId = Display.DEFAULT_DISPLAY;
        String arg = getNextArg();
        if ("-d".equals(arg)) {
            displayId = Integer.parseInt(getNextArgRequired());
        }

        final boolean ignoreOrientationRequest = mInternal.getIgnoreOrientationRequest(displayId);
        pw.println("ignoreOrientationRequest " + ignoreOrientationRequest
                + " for displayId=" + displayId);
        return 0;
    }

    private int runDumpVisibleWindowViews(PrintWriter pw) {
        if (!mInternal.checkCallingPermission(android.Manifest.permission.DUMP,
                "runDumpVisibleWindowViews()")) {
            throw new SecurityException("Requires DUMP permission");
        }

        try (ZipOutputStream out = new ZipOutputStream(getRawOutputStream())) {
            ArrayList<Pair<String, ByteTransferPipe>> requestList = new ArrayList<>();
            synchronized (mInternal.mGlobalLock) {
                // Request dump from all windows parallelly before writing to disk.
                mInternal.mRoot.forAllWindows(w -> {
                    if (w.isVisible()) {
                        ByteTransferPipe pipe = null;
                        try {
                            pipe = new ByteTransferPipe();
                            w.mClient.executeCommand(ViewDebug.REMOTE_COMMAND_DUMP_ENCODED, null,
                                    pipe.getWriteFd());
                            requestList.add(Pair.create(w.getName(), pipe));
                        } catch (IOException | RemoteException e) {
                            // Skip this window
                            if (pipe != null) {
                                pipe.kill();
                            }
                        }
                    }
                }, false /* traverseTopToBottom */);
            }
            for (Pair<String, ByteTransferPipe> entry : requestList) {
                byte[] data;
                try {
                    data = entry.second.get();
                } catch (IOException e) {
                    // Ignore this window
                    continue;
                }
                out.putNextEntry(new ZipEntry(entry.first));
                out.write(data);
            }
        } catch (IOException e) {
            pw.println("Error fetching dump " + e.getMessage());
        }
        return 0;
    }

    private int runSetFixedOrientationLetterboxAspectRatio(PrintWriter pw) throws RemoteException {
        final float aspectRatio;
        try {
            String arg = getNextArgRequired();
            aspectRatio = Float.parseFloat(arg);
        } catch (NumberFormatException  e) {
            getErrPrintWriter().println("Error: bad aspect ratio format " + e);
            return -1;
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: 'reset' or aspect ratio should be provided as an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(aspectRatio);
        }
        return 0;
    }

    private int runSetLetterboxActivityCornersRadius(PrintWriter pw) throws RemoteException {
        final int cornersRadius;
        try {
            String arg = getNextArgRequired();
            cornersRadius = Integer.parseInt(arg);
        } catch (NumberFormatException  e) {
            getErrPrintWriter().println("Error: bad corners radius format " + e);
            return -1;
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: 'reset' or corners radius should be provided as an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mLetterboxConfiguration.setLetterboxActivityCornersRadius(cornersRadius);
        }
        return 0;
    }

    private int runSetLetterboxBackgroundType(PrintWriter pw) throws RemoteException {
        @LetterboxBackgroundType final int backgroundType;
        try {
            String arg = getNextArgRequired();
            switch (arg) {
                case "solid_color":
                    backgroundType = LETTERBOX_BACKGROUND_SOLID_COLOR;
                    break;
                case "app_color_background":
                    backgroundType = LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND;
                    break;
                case "app_color_background_floating":
                    backgroundType = LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING;
                    break;
                case "wallpaper":
                    backgroundType = LETTERBOX_BACKGROUND_WALLPAPER;
                    break;
                default:
                    getErrPrintWriter().println(
                            "Error: 'reset', 'solid_color', 'app_color_background' or "
                            + "'wallpaper' should be provided as an argument");
                    return -1;
            }
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: 'reset', 'solid_color', 'app_color_background' or "
                        + "'wallpaper' should be provided as an argument" + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mLetterboxConfiguration.setLetterboxBackgroundType(backgroundType);
        }
        return 0;
    }

    private int runSetLetterboxBackgroundColor(PrintWriter pw) throws RemoteException {
        final Color color;
        try {
            String arg = getNextArgRequired();
            color = Color.valueOf(Color.parseColor(arg));
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: 'reset' or color in #RRGGBB format should be provided as "
                            + "an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mLetterboxConfiguration.setLetterboxBackgroundColor(color);
        }
        return 0;
    }

    private int runSetLetterboxBackgroundWallpaperBlurRadius(PrintWriter pw)
            throws RemoteException {
        final int radius;
        try {
            String arg = getNextArgRequired();
            radius = Integer.parseInt(arg);
        } catch (NumberFormatException  e) {
            getErrPrintWriter().println("Error: blur radius format " + e);
            return -1;
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: 'reset' or blur radius should be provided as an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mLetterboxConfiguration.setLetterboxBackgroundWallpaperBlurRadius(radius);
        }
        return 0;
    }

    private int runSetLetterboxBackgroundWallpaperDarkScrimAlpha(PrintWriter pw)
            throws RemoteException {
        final float alpha;
        try {
            String arg = getNextArgRequired();
            alpha = Float.parseFloat(arg);
        } catch (NumberFormatException  e) {
            getErrPrintWriter().println("Error: bad alpha format " + e);
            return -1;
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: 'reset' or alpha should be provided as an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mLetterboxConfiguration.setLetterboxBackgroundWallpaperDarkScrimAlpha(alpha);
        }
        return 0;
    }

    private int runSeLetterboxHorizontalPositionMultiplier(PrintWriter pw) throws RemoteException {
        final float multiplier;
        try {
            String arg = getNextArgRequired();
            multiplier = Float.parseFloat(arg);
        } catch (NumberFormatException  e) {
            getErrPrintWriter().println("Error: bad multiplier format " + e);
            return -1;
        } catch (IllegalArgumentException  e) {
            getErrPrintWriter().println(
                    "Error: 'reset' or multiplier should be provided as an argument " + e);
            return -1;
        }
        synchronized (mInternal.mGlobalLock) {
            mLetterboxConfiguration.setLetterboxHorizontalPositionMultiplier(multiplier);
        }
        return 0;
    }

    private int runSetLetterboxStyle(PrintWriter pw) throws RemoteException {
        if (peekNextArg() == null) {
            getErrPrintWriter().println("Error: No arguments provided.");
        }
        while (peekNextArg() != null) {
            String arg = getNextArg();
            switch (arg) {
                case "--aspectRatio":
                    runSetFixedOrientationLetterboxAspectRatio(pw);
                    break;
                case "--cornerRadius":
                    runSetLetterboxActivityCornersRadius(pw);
                    break;
                case "--backgroundType":
                    runSetLetterboxBackgroundType(pw);
                    break;
                case "--backgroundColor":
                    runSetLetterboxBackgroundColor(pw);
                    break;
                case "--wallpaperBlurRadius":
                    runSetLetterboxBackgroundWallpaperBlurRadius(pw);
                    break;
                case "--wallpaperDarkScrimAlpha":
                    runSetLetterboxBackgroundWallpaperDarkScrimAlpha(pw);
                    break;
                case "--horizontalPositionMultiplier":
                    runSeLetterboxHorizontalPositionMultiplier(pw);
                    break;
                default:
                    getErrPrintWriter().println(
                            "Error: Unrecognized letterbox style option: " + arg);
                    return -1;
            }
        }
        return 0;
    }

    private int runResetLetterboxStyle(PrintWriter pw) throws RemoteException {
        if (peekNextArg() == null) {
            resetLetterboxStyle();
        }
        synchronized (mInternal.mGlobalLock) {
            while (peekNextArg() != null) {
                String arg = getNextArg();
                switch (arg) {
                    case "aspectRatio":
                        mLetterboxConfiguration.resetFixedOrientationLetterboxAspectRatio();
                        break;
                    case "cornerRadius":
                        mLetterboxConfiguration.resetLetterboxActivityCornersRadius();
                        break;
                    case "backgroundType":
                        mLetterboxConfiguration.resetLetterboxBackgroundType();
                        break;
                    case "backgroundColor":
                        mLetterboxConfiguration.resetLetterboxBackgroundColor();
                        break;
                    case "wallpaperBlurRadius":
                        mLetterboxConfiguration.resetLetterboxBackgroundWallpaperBlurRadius();
                        break;
                    case "wallpaperDarkScrimAlpha":
                        mLetterboxConfiguration.resetLetterboxBackgroundWallpaperDarkScrimAlpha();
                        break;
                    case "horizontalPositionMultiplier":
                        mLetterboxConfiguration.resetLetterboxHorizontalPositionMultiplier();
                        break;
                    default:
                        getErrPrintWriter().println(
                                "Error: Unrecognized letterbox style option: " + arg);
                        return -1;
                }
            }
        }
        return 0;
    }

    private void resetLetterboxStyle() {
        synchronized (mInternal.mGlobalLock) {
            mLetterboxConfiguration.resetFixedOrientationLetterboxAspectRatio();
            mLetterboxConfiguration.resetLetterboxActivityCornersRadius();
            mLetterboxConfiguration.resetLetterboxBackgroundType();
            mLetterboxConfiguration.resetLetterboxBackgroundColor();
            mLetterboxConfiguration.resetLetterboxBackgroundWallpaperBlurRadius();
            mLetterboxConfiguration.resetLetterboxBackgroundWallpaperDarkScrimAlpha();
            mLetterboxConfiguration.resetLetterboxHorizontalPositionMultiplier();
        }
    }

    private int runGetLetterboxStyle(PrintWriter pw) throws RemoteException {
        synchronized (mInternal.mGlobalLock) {
            pw.println("Corner radius: "
                    + mLetterboxConfiguration.getLetterboxActivityCornersRadius());
            pw.println("Horizontal position multiplier: "
                    + mLetterboxConfiguration.getLetterboxHorizontalPositionMultiplier());
            pw.println("Aspect ratio: "
                    + mLetterboxConfiguration.getFixedOrientationLetterboxAspectRatio());

            pw.println("Background type: "
                    + LetterboxConfiguration.letterboxBackgroundTypeToString(
                            mLetterboxConfiguration.getLetterboxBackgroundType()));
            pw.println("    Background color: " + Integer.toHexString(
                    mLetterboxConfiguration.getLetterboxBackgroundColor().toArgb()));
            pw.println("    Wallpaper blur radius: "
                    + mLetterboxConfiguration.getLetterboxBackgroundWallpaperBlurRadius());
            pw.println("    Wallpaper dark scrim alpha: "
                    + mLetterboxConfiguration.getLetterboxBackgroundWallpaperDarkScrimAlpha());
        }
        return 0;
    }

    private int runReset(PrintWriter pw) throws RemoteException {
        int displayId = getDisplayId(getNextArg());

        // size
        mInterface.clearForcedDisplaySize(displayId);

        // density
        mInterface.clearForcedDisplayDensityForUser(displayId, UserHandle.USER_CURRENT);

        // folded-area
        mInternal.setOverrideFoldedArea(new Rect());

        // scaling
        mInterface.setForcedDisplayScalingMode(displayId, DisplayContent.FORCE_SCALING_MODE_AUTO);

        // user-rotation
        mInternal.thawDisplayRotation(displayId);

        // fixed-to-user-rotation
        mInterface.setFixedToUserRotation(displayId, IWindowManager.FIXED_TO_USER_ROTATION_DEFAULT);

        // set-ignore-orientation-request
        mInterface.setIgnoreOrientationRequest(displayId, false /* ignoreOrientationRequest */);

        // set-letterbox-style
        resetLetterboxStyle();

        // set-sandbox-display-apis
        mInternal.setSandboxDisplayApis(displayId, /* sandboxDisplayApis= */ true);

        pw.println("Reset all settings for displayId=" + displayId);
        return 0;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Window manager (window) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  size [reset|WxH|WdpxHdp] [-d DISPLAY_ID]");
        pw.println("    Return or override display size.");
        pw.println("    width and height in pixels unless suffixed with 'dp'.");
        pw.println("  density [reset|DENSITY] [-d DISPLAY_ID]");
        pw.println("    Return or override display density.");
        pw.println("  folded-area [reset|LEFT,TOP,RIGHT,BOTTOM]");
        pw.println("    Return or override folded area.");
        pw.println("  scaling [off|auto] [-d DISPLAY_ID]");
        pw.println("    Set display scaling mode.");
        pw.println("  dismiss-keyguard");
        pw.println("    Dismiss the keyguard, prompting user for auth if necessary.");
        pw.println("  disable-blur [true|1|false|0]");
        pw.println("  user-rotation [-d DISPLAY_ID] [free|lock] [rotation]");
        pw.println("    Print or set user rotation mode and user rotation.");
        pw.println("  dump-visible-window-views");
        pw.println("    Dumps the encoded view hierarchies of visible windows");
        pw.println("  fixed-to-user-rotation [-d DISPLAY_ID] [enabled|disabled|default]");
        pw.println("    Print or set rotating display for app requested orientation.");
        pw.println("  set-ignore-orientation-request [-d DISPLAY_ID] [true|1|false|0]");
        pw.println("  get-ignore-orientation-request [-d DISPLAY_ID] ");
        pw.println("    If app requested orientation should be ignored.");
        pw.println("  set-sandbox-display-apis [true|1|false|0]");
        pw.println("    Sets override of Display APIs getRealSize / getRealMetrics to reflect ");
        pw.println("    DisplayArea of the activity, or the window bounds if in letterbox or");
        pw.println("    Size Compat Mode.");

        printLetterboxHelp(pw);

        pw.println("  reset [-d DISPLAY_ID]");
        pw.println("    Reset all override settings.");
        if (!IS_USER) {
            pw.println("  tracing (start | stop)");
            pw.println("    Start or stop window tracing.");
            pw.println("  logging (start | stop | enable | disable | enable-text | disable-text)");
            pw.println("    Logging settings.");
        }
    }

    private void printLetterboxHelp(PrintWriter pw) {
        pw.println("  set-letterbox-style");
        pw.println("    Sets letterbox style using the following options:");
        pw.println("      --aspectRatio aspectRatio");
        pw.println("        Aspect ratio of letterbox for fixed orientation. If aspectRatio <= "
                + LetterboxConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO);
        pw.println("        both it and R.dimen.config_fixedOrientationLetterboxAspectRatio will");
        pw.println("        be ignored and framework implementation will determine aspect ratio.");
        pw.println("      --cornerRadius radius");
        pw.println("        Corners radius for activities in the letterbox mode. If radius < 0,");
        pw.println("        both it and R.integer.config_letterboxActivityCornersRadius will be");
        pw.println("        ignored and corners of the activity won't be rounded.");
        pw.println("      --backgroundType [reset|solid_color|app_color_background");
        pw.println("          |app_color_background_floating|wallpaper]");
        pw.println("        Type of background used in the letterbox mode.");
        pw.println("      --backgroundColor color");
        pw.println("        Color of letterbox which is be used when letterbox background type");
        pw.println("        is 'solid-color'. Use (set)get-letterbox-style to check and control");
        pw.println("        letterbox background type. See Color#parseColor for allowed color");
        pw.println("        formats (#RRGGBB and some colors by name, e.g. magenta or olive).");
        pw.println("      --wallpaperBlurRadius radius");
        pw.println("        Blur radius for 'wallpaper' letterbox background. If radius <= 0");
        pw.println("        both it and R.dimen.config_letterboxBackgroundWallpaperBlurRadius");
        pw.println("        are ignored and 0 is used.");
        pw.println("      --wallpaperDarkScrimAlpha alpha");
        pw.println("        Alpha of a black translucent scrim shown over 'wallpaper'");
        pw.println("        letterbox background. If alpha < 0 or >= 1 both it and");
        pw.println("        R.dimen.config_letterboxBackgroundWallaperDarkScrimAlpha are ignored");
        pw.println("        and 0.0 (transparent) is used instead.");
        pw.println("      --horizontalPositionMultiplier multiplier");
        pw.println("        Horizontal position of app window center. If multiplier < 0 or > 1,");
        pw.println("        both it and R.dimen.config_letterboxHorizontalPositionMultiplier");
        pw.println("        are ignored and central position (0.5) is used.");
        pw.println("  reset-letterbox-style [aspectRatio|cornerRadius|backgroundType");
        pw.println("      |backgroundColor|wallpaperBlurRadius|wallpaperDarkScrimAlpha");
        pw.println("      |horizontalPositionMultiplier]");
        pw.println("    Resets overrides to default values for specified properties separated");
        pw.println("    by space, e.g. 'reset-letterbox-style aspectRatio cornerRadius'.");
        pw.println("    If no arguments provided, all values will be reset.");
        pw.println("  get-letterbox-style");
        pw.println("    Prints letterbox style configuration.");
    }
}
