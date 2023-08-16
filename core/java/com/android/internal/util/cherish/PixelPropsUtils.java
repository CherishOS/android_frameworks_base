/*
 * Copyright (C) 2022 The Pixel Experience Project
 *               2021-2022 crDroid Android Project
 * Copyright (C) 2022 Paranoid Android
 * Copyright (C) 2022 StatiXOS
 * Copyright (C) 2023 the RisingOS Android Project
 *           (C) 2023 ArrowOS
 *           (C) 2023 The LibreMobileOS Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.cherish;

import android.app.ActivityTaskManager;
import android.app.Application;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.ComponentName;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.R;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class PixelPropsUtils {

    private static final String TAG = PixelPropsUtils.class.getSimpleName();
    private static final String DEVICE = "org.pixelexperience.device";
    private static final boolean DEBUG = false;

    private static final String SAMSUNG = "com.samsung.";

    private static final Map<String, Object> propsToChangeGeneric;

    private static final Map<String, Object> propsToChangePixelFold =
            createGoogleSpoofProps("felix", "Pixel Fold",
                    "google/felix/felix:13/TQ3C.230805.001.B2/10453839:user/release-keys");

    private static final Map<String, Object> propsToChangePixel7Pro =
            createGoogleSpoofProps("cheetah", "Pixel 7 Pro",
                    "google/cheetah/cheetah:13/TQ3A.230805.001/10316531:user/release-keys");

    private static final Map<String, Object> propsToChangePixel5a =
            createGoogleSpoofProps("barbet", "Pixel 5a",
                    "google/barbet/barbet:13/TQ3A.230805.001.A2/10385117:user/release-keys");

    private static final Map<String, Object> propsToChangePixelXL =
            createGoogleSpoofProps("marlin", "Pixel XL",
                    "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");

    private static final Map<String, ArrayList<String>> propsToKeep;

    static {
        propsToKeep = new HashMap<>();
        propsToKeep.put("com.google.android.settings.intelligence", new ArrayList<>(Collections.singletonList("FINGERPRINT")));
        propsToChangeGeneric = new HashMap<>();
        propsToChangeGeneric.put("TYPE", "user");
        propsToChangeGeneric.put("TAGS", "release-keys");
    }

    private static final ArrayList<String> packagesToChangePixel7Pro = 
        new ArrayList<String> (
            Arrays.asList(
                "com.android.chrome",
                "com.breel.wallpapers20",
                "com.microsoft.android.smsorganizer",
                "com.nothing.smartcenter",
                "com.nhs.online.nhsonline",
                "com.amazon.avod.thirdpartyclient",
                "com.disney.disneyplus",
                "com.netflix.mediaclient",
                "in.startv.hotstar",
                "com.google.android.apps.emojiwallpaper",
                "com.google.android.wallpaper.effects",
                "com.google.pixel.livewallpaper",
                "com.google.android.apps.wallpaper.pixel",
                "com.google.android.apps.wallpaper",
                "com.google.android.apps.customization.pixel",
                "com.google.android.apps.privacy.wildlife",
                "com.google.android.apps.subscriptions.red"
        ));

    private static final ArrayList<String> packagesToChangePixelFold = 
        new ArrayList<String> (
            Arrays.asList(
        ));

    private static final ArrayList<String> extraPackagesToChange = 
        new ArrayList<String> (
            Arrays.asList(
        ));

    private static final ArrayList<String> customGoogleCameraPackages = 
        new ArrayList<String> (
            Arrays.asList(
                "com.google.android.MTCL83",
                "com.google.android.UltraCVM",
                "com.google.android.apps.cameralite"
        ));

    private static final ArrayList<String> packagesToKeep = 
        new ArrayList<String> (
            Arrays.asList(
                "com.google.android.as",
                "com.google.android.apps.motionsense.bridge",
                "com.google.android.euicc",
                "com.google.ar.core",
                "com.google.android.youtube",
                "com.google.android.apps.youtube.kids",
                "com.google.android.apps.youtube.music",
                "com.google.android.apps.wearables.maestro.companion",
                "com.google.android.apps.subscriptions.red",
                "com.google.android.apps.tachyon",
                "com.google.android.apps.tycho",
                "com.google.android.apps.restore",
                "com.google.oslo",
                "it.ingdirect.app"
        ));

    // Codenames for currently supported Pixels by Google
    private static final ArrayList<String> pixelCodenames = 
        new ArrayList<String> (
            Arrays.asList(
                "felix",
                "tangorpro",
                "lynx",
                "cheetah",
                "panther",
                "bluejay",
                "oriole",
                "raven",
                "barbet",
                "redfin",
                "bramble",
                "sunfish"
        ));

    private static final String sNetflixModel =
            Resources.getSystem().getString(R.string.config_netflixSpoofModel);

    private static final ComponentName GMS_ADD_ACCOUNT_ACTIVITY = ComponentName.unflattenFromString(
            "com.google.android.gms/.auth.uiflows.minutemaid.MinuteMaidActivity");

    private static volatile boolean sIsGms = false;
    private static volatile boolean sIsFinsky = false;

    private static String getBuildID(String fingerprint) {
        Pattern pattern = Pattern.compile("([A-Za-z0-9]+\\.\\d+\\.\\d+\\.\\w+)");
        Matcher matcher = pattern.matcher(fingerprint);

        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static Map<String, Object> createGoogleSpoofProps(String device, String model, String fingerprint) {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "google");
        props.put("MANUFACTURER", "Google");
        props.put("ID", getBuildID(fingerprint));
        props.put("DEVICE", device);
        props.put("PRODUCT", device);
        props.put("MODEL", model);
        props.put("FINGERPRINT", fingerprint);
        props.put("TYPE", "user");
        props.put("TAGS", "release-keys");
        return props;
    }

    private static boolean isGoogleCameraPackage(String packageName){
        return packageName.startsWith("com.google.android.GoogleCamera") ||
            customGoogleCameraPackages.contains(packageName);
    }

    private static boolean setPropsForGms(String packageName) {
        if (packageName.equals("com.android.vending")) {
            sIsFinsky = true;
        }
        if (packageName.equals("com.google.android.gms")
                || packageName.toLowerCase().contains("androidx.test")
                || packageName.equalsIgnoreCase("com.google.android.apps.restore")) {
            final String processName = Application.getProcessName();
            if (processName.toLowerCase().contains("unstable")
                    || processName.toLowerCase().contains("pixelmigrate")
                    || processName.toLowerCase().contains("instrumentation")) {
                sIsGms = true;
                final boolean was = isGmsAddAccountActivityOnTop();
                final TaskStackListener taskStackListener = new TaskStackListener() {
                    @Override
                    public void onTaskStackChanged() {
                        final boolean is = isGmsAddAccountActivityOnTop();
                        if (is ^ was) {
                            dlog("GmsAddAccountActivityOnTop is:" + is + " was:" + was + ", killing myself!");
                            // process will restart automatically later
                            Process.killProcess(Process.myPid());
                        }
                    }
                };
                try {
                    ActivityTaskManager.getService().registerTaskStackListener(taskStackListener);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to register task stack listener!", e);
                }
                if (was) return true;
                // Alter build parameters to pixel 2 for avoiding hardware attestation enforcement
                setPropValue("BRAND", "google");
                setPropValue("PRODUCT", "walleye");
                setPropValue("MODEL", "Pixel 2");
            	setPropValue("MANUFACTURER", "Google");
                setPropValue("DEVICE", "walleye");
                setPropValue("FINGERPRINT", "google/walleye/walleye:8.1.0/OPM1.171019.011/4448085:user/release-keys");
                setPropValue("ID", "OPM1.171019.011");
                setPropValue("TYPE", "user");
                setPropValue("TAGS", "release-keys");
                setVersionField("DEVICE_INITIAL_SDK_INT", Build.VERSION_CODES.O);
                setVersionFieldString("SECURITY_PATCH", "2017-12-05");
                return true;
            }
        }
        return false;
    }

    public static void setProps(Application app) {
        propsToChangeGeneric.forEach((k, v) -> setPropValue(k, v));

        final String packageName = app.getPackageName();
        final String processName = app.getProcessName();

        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        String procName = packageName;
        if (setPropsForGms(procName)) {
            return;
        }
        if (packagesToChangePixel7Pro.contains(processName)
            || packagesToChangePixelFold.contains(processName)
            || extraPackagesToChange.contains(processName)
            || packagesToKeep.contains(processName)) {
            procName = processName;
        // Allow process spoofing for GoogleCamera packages
        } else if (isGoogleCameraPackage(procName)) {
            return;
        }
        if (packagesToKeep.contains(procName)) {
            return;
        }
        Map<String, Object> propsToChange = new HashMap<>();
        boolean isPixelDevice = pixelCodenames.contains(SystemProperties.get(DEVICE));
        if (procName.equals("com.google.android.apps.photos")) {
            propsToChange = propsToChangePixelXL;
        } else if (isPixelDevice) {
            return;
        } else if (procName.startsWith("com.google.")
                || procName.startsWith(SAMSUNG)
                || packagesToChangePixel7Pro.contains(procName)
                || packagesToChangePixelFold.contains(procName)
                || extraPackagesToChange.contains(procName)) {

            if (packagesToChangePixel7Pro.contains(procName)) {
                propsToChange = propsToChangePixel7Pro;
            } else if (packagesToChangePixelFold.contains(procName)) {
                propsToChange = propsToChangePixelFold;
            } else {
                propsToChange = propsToChangePixel5a;
            }
        }
        if (propsToChange == null || propsToChange.isEmpty()) return;
        dlog("Defining props for: " + procName);
        for (Map.Entry<String, Object> prop : propsToChange.entrySet()) {
            String key = prop.getKey();
            Object value = prop.getValue();
            if (propsToKeep.containsKey(procName) && propsToKeep.get(procName).contains(key)) {
                dlog("Not defining " + key + " prop for: " + procName);
                continue;
            }
            dlog("Defining " + key + " prop for: " + procName);
            setPropValue(key, value);
        }
        // Set proper indexing fingerprint
        if (procName.equals("com.google.android.settings.intelligence")) {
            setPropValue("FINGERPRINT", Build.VERSION.INCREMENTAL);
            return;
        }
        if (!sNetflixModel.isEmpty() && procName.equals("com.netflix.mediaclient")) {
            dlog("Setting model to " + sNetflixModel + " for Netflix");
            setPropValue("MODEL", sNetflixModel);
            return;
        }
    }

    private static void setPropValue(String key, Object value) {
        try {
            dlog("Defining prop " + key + " to " + value.toString());
            Field field = Build.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static void setVersionField(String key, Object value) {
        try {
            dlog("Defining version field " + key + " to " + value.toString());
            Field field = Build.VERSION.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to set version field " + key, e);
        }
    }

    private static void setVersionFieldString(String key, String value) {
        try {
            Field field = Build.VERSION.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to spoof Build." + key, e);
        }
    }

    private static boolean isGmsAddAccountActivityOnTop() {
        try {
            final ActivityTaskManager.RootTaskInfo focusedTask =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            return focusedTask != null && focusedTask.topActivity != null
                    && focusedTask.topActivity.equals(GMS_ADD_ACCOUNT_ACTIVITY);
        } catch (Exception e) {
            Log.e(TAG, "Unable to get top activity!", e);
        }
        return false;
    }

    public static boolean shouldBypassTaskPermission(Context context) {
        // GMS doesn't have MANAGE_ACTIVITY_TASKS permission
        final int callingUid = Binder.getCallingUid();
        final int gmsUid;
        try {
            gmsUid = context.getPackageManager().getApplicationInfo("com.google.android.gms", 0).uid;
            dlog("shouldBypassTaskPermission: gmsUid:" + gmsUid + " callingUid:" + callingUid);
        } catch (Exception e) {
            Log.e(TAG, "shouldBypassTaskPermission: unable to get gms uid", e);
            return false;
        }
        return gmsUid == callingUid;
    }

    private static boolean isCallerSafetyNet() {
        return sIsGms && Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(elem -> elem.getClassName().contains("DroidGuard"));
    }

    public static void onEngineGetCertificateChain() {
        // Check stack for SafetyNet or Play Integrity
        if (isCallerSafetyNet() || sIsFinsky) {
            Log.i(TAG, "Blocked key attestation sIsGms=" + sIsGms + " sIsFinsky=" + sIsFinsky);
            throw new UnsupportedOperationException();
        }
    }

    public static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }
}