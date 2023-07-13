/*
 * Copyright (C) 2022 The Pixel Experience Project
 *               2021-2022 crDroid Android Project
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

public class PixelPropsUtils {

    private static final String TAG = PixelPropsUtils.class.getSimpleName();
    private static final String DEVICE = "org.pixelexperience.device";
    private static final boolean DEBUG = false;

    private static final String SAMSUNG = "com.samsung.";

    private static final Map<String, Object> propsToChangeGeneric;
    private static final Map<String, Object> propsToChangePixel2;
    private static final Map<String, Object> propsToChangePixel6Pro;
    private static final Map<String, Object> propsToChangePixel7Pro;
    private static final Map<String, Object> propsToChangePixelFold;
    private static final Map<String, Object> propsToChangePixel5a;
    private static final Map<String, Object> propsToChangePixelXL;
    private static final Map<String, ArrayList<String>> propsToKeep;

    private static final String[] packagesToChangePixelXL = {
            "com.google.android.apps.photos",
    };

    // Packages to Spoof as Pixel 2
    private static final String[] packagesToChangePixel2 = {
            "com.snapchat.android",
    };

    private static final String[] packagesToChangePixel6Pro = {
    };

    private static final String[] packagesToChangePixel7Pro = {
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
            "com.google.android.apps.subscriptions.red",
    };

    private static final String[] packagesToChangePixelFold = {
            "com.android.vending",
            "com.google.android.apps.googleassistant",
            "com.google.android.apps.miphone.aiai.AiaiApplication",
            "com.google.android.apps.turbo",
            "com.google.android.as.oss",
            "com.google.android.as",
            "com.google.android.ext.services",
            "com.google.android.googlequicksearchbox",
            "com.google.android.inputmethod.latin",
            "com.google.android.setupwizard",
    };

    private static final String[] extraPackagesToChange = {
    };

    private static final String[] customGoogleCameraPackages = {
            "com.google.android.MTCL83",
            "com.google.android.UltraCVM",
            "com.google.android.apps.cameralite",
    };

    private static final String[] packagesToKeep = {
            "com.google.android.apps.miphone.aiai.AiaiApplication",
            "com.google.android.euicc",
            "com.google.ar.core",
            "com.google.android.youtube",
            "com.google.android.apps.youtube.kids",
            "com.google.android.apps.youtube.music",
            "com.google.android.apps.wearables.maestro.companion",
            "com.google.android.apps.subscriptions.red",
            "com.google.android.apps.tachyon",
            "com.google.android.apps.tycho",
            "it.ingdirect.app",
    };

    // Codenames for currently supported Pixels by Google
    private static final String[] pixelCodenames = {
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
            "sunfish",
    };

    private static final String sNetflixModel =
            Resources.getSystem().getString(R.string.config_netflixSpoofModel);

    private static final ComponentName GMS_ADD_ACCOUNT_ACTIVITY = ComponentName.unflattenFromString(
            "com.google.android.gms/.auth.uiflows.minutemaid.MinuteMaidActivity");

    private static volatile boolean sIsGms = false;
    private static volatile boolean sIsFinsky = false;

    static {
        propsToKeep = new HashMap<>();
        propsToKeep.put("com.google.android.settings.intelligence", new ArrayList<>(Collections.singletonList("FINGERPRINT")));
        propsToChangeGeneric = new HashMap<>();
        propsToChangeGeneric.put("TYPE", "user");
        propsToChangeGeneric.put("TAGS", "release-keys");
        propsToChangePixel6Pro = new HashMap<>();
        propsToChangePixel6Pro.put("BRAND", "google");
        propsToChangePixel6Pro.put("MANUFACTURER", "Google");
        propsToChangePixel6Pro.put("DEVICE", "raven");
        propsToChangePixel6Pro.put("PRODUCT", "raven");
        propsToChangePixel6Pro.put("HARDWARE", "raven");
        propsToChangePixel6Pro.put("MODEL", "Pixel 6 Pro");
        propsToChangePixel6Pro.put("ID", "TQ3A.230705.001.A1");
        propsToChangePixel6Pro.put("FINGERPRINT", "google/raven/raven:13/TQ3A.230705.001.A1/10217028:user/release-keys");
        propsToChangePixel7Pro = new HashMap<>();
        propsToChangePixel7Pro.put("BRAND", "google");
        propsToChangePixel7Pro.put("MANUFACTURER", "Google");
        propsToChangePixel7Pro.put("DEVICE", "cheetah");
        propsToChangePixel7Pro.put("PRODUCT", "cheetah");
        propsToChangePixel7Pro.put("HARDWARE", "cheetah");
        propsToChangePixel7Pro.put("MODEL", "Pixel 7 Pro");
        propsToChangePixel7Pro.put("ID", "TQ3A.230705.001.A1");
        propsToChangePixel7Pro.put("FINGERPRINT", "google/cheetah/cheetah:13/TQ3A.230705.001.A1/10217028:user/release-keys");
        propsToChangePixelFold = new HashMap<>();
        propsToChangePixelFold.put("BRAND", "google");
        propsToChangePixelFold.put("MANUFACTURER", "Google");
        propsToChangePixelFold.put("DEVICE", "felix");
        propsToChangePixelFold.put("PRODUCT", "felix");
        propsToChangePixelFold.put("HARDWARE", "felix");
        propsToChangePixelFold.put("MODEL", "Pixel Fold");
        propsToChangePixelFold.put("ID", "TQ3C.230705.001.C2");
        propsToChangePixelFold.put("FINGERPRINT", "google/felix/felix:13/TQ3C.230705.001.C2/10334521:user/release-keys");
        propsToChangePixel5a = new HashMap<>();
        propsToChangePixel5a.put("BRAND", "google");
        propsToChangePixel5a.put("MANUFACTURER", "Google");
        propsToChangePixel5a.put("DEVICE", "barbet");
        propsToChangePixel5a.put("PRODUCT", "barbet");
        propsToChangePixel5a.put("HARDWARE", "barbet");
        propsToChangePixel5a.put("MODEL", "Pixel 5a");
        propsToChangePixel5a.put("ID", "TQ3A.230705.001");
        propsToChangePixel5a.put("FINGERPRINT", "google/barbet/barbet:13/TQ3A.230705.001/10216780:user/release-keys");
        propsToChangePixel2 = new HashMap<>();
        propsToChangePixel2.put("BRAND", "google");
        propsToChangePixel2.put("MANUFACTURER", "Google");
        propsToChangePixel2.put("DEVICE", "walleye");
        propsToChangePixel2.put("PRODUCT", "walleye");
        propsToChangePixel2.put("MODEL", "Pixel 2");
        propsToChangePixel2.put("FINGERPRINT", "google/walleye/walleye:8.1.0/OPM1.171019.011/4448085:user/release-keys");
        propsToChangePixelXL = new HashMap<>();
        propsToChangePixelXL.put("BRAND", "google");
        propsToChangePixelXL.put("MANUFACTURER", "Google");
        propsToChangePixelXL.put("DEVICE", "marlin");
        propsToChangePixelXL.put("PRODUCT", "marlin");
        propsToChangePixelXL.put("MODEL", "Pixel XL");
        propsToChangePixelXL.put("FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
    }

    private static boolean isGoogleCameraPackage(String packageName){
        return packageName.startsWith("com.google.android.GoogleCamera") ||
            Arrays.asList(customGoogleCameraPackages).contains(packageName);
    }

    public static void setProps(String packageName) {
        propsToChangeGeneric.forEach((k, v) -> setPropValue(k, v));
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        if (Arrays.asList(packagesToKeep).contains(packageName)) {
            return;
        }
        if (isGoogleCameraPackage(packageName)) {
            return;
        }
        Map<String, Object> propsToChange = new HashMap<>();
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
                            if (DEBUG) Log.d(TAG, "GmsAddAccountActivityOnTop is:" + is + " was:" + was +
                                    ", killing myself!"); // process will restart automatically later
                            Process.killProcess(Process.myPid());
                        }
                    }
                };
                try {
                    ActivityTaskManager.getService().registerTaskStackListener(taskStackListener);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to register task stack listener!", e);
                }
                if (was) return;

                setPropValue("FINGERPRINT", "google/walleye/walleye:8.1.0/OPM1.171019.011/4448085:user/release-keys");
                setPropValue("MODEL", "walleye");
                setPropValue("PRODUCT", "walleye");
                setPropValue("DEVICE", "Pixel 2");
                setVersionField("DEVICE_INITIAL_SDK_INT", Build.VERSION_CODES.O);
                return;
            }
        }
        if (packageName.startsWith("com.google.")
                || packageName.startsWith(SAMSUNG)
                || Arrays.asList(packagesToChangePixelXL).contains(packageName)
                || Arrays.asList(packagesToChangePixel2).contains(packageName)
                || Arrays.asList(packagesToChangePixel6Pro).contains(packageName)
                || Arrays.asList(packagesToChangePixel7Pro).contains(packageName)
                || Arrays.asList(packagesToChangePixelFold).contains(packageName)
                || Arrays.asList(extraPackagesToChange).contains(packageName)) {

            boolean isPixelDevice = Arrays.asList(pixelCodenames).contains(SystemProperties.get(DEVICE));

            if (packageName.equals("com.android.vending")) {
                sIsFinsky = true;
            }
            if (Arrays.asList(packagesToChangePixelXL).contains(packageName)) {
                if (isPixelDevice) return;
                propsToChange.putAll(propsToChangePixelXL);
            } else if (Arrays.asList(packagesToChangePixel2).contains(packageName)) {
                if (isPixelDevice) return;
                propsToChange.putAll(propsToChangePixel2);
            } else if (Arrays.asList(packagesToChangePixel6Pro).contains(packageName)) {
                if (isPixelDevice) return;
                propsToChange.putAll(propsToChangePixel6Pro);
            } else if (Arrays.asList(packagesToChangePixel7Pro).contains(packageName)) {
                if (isPixelDevice) return;
                propsToChange.putAll(propsToChangePixel7Pro);
            } else if (Arrays.asList(packagesToChangePixelFold).contains(packageName)) {
                if (isPixelDevice) return;
                propsToChange.putAll(propsToChangePixelFold);
            } else {
                propsToChange.putAll(propsToChangePixel5a);
            }
        }
        if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
        for (Map.Entry<String, Object> prop : propsToChange.entrySet()) {
            String key = prop.getKey();
            Object value = prop.getValue();
            if (propsToKeep.containsKey(packageName) && propsToKeep.get(packageName).contains(key)) {
                if (DEBUG) Log.d(TAG, "Not defining " + key + " prop for: " + packageName);
                continue;
            }
            if (DEBUG) Log.d(TAG, "Defining " + key + " prop for: " + packageName);
            setPropValue(key, value);
        }
        // Set proper indexing fingerprint
        if (packageName.equals("com.google.android.settings.intelligence")) {
            setPropValue("FINGERPRINT", Build.VERSION.INCREMENTAL);
            return;
        }
        if (!sNetflixModel.isEmpty() && packageName.equals("com.netflix.mediaclient")) {
            if (DEBUG) Log.d(TAG, "Setting model to " + sNetflixModel + " for Netflix");
            setPropValue("MODEL", sNetflixModel);
            return;
        }
    }

    private static void setPropValue(String key, Object value) {
        try {
            if (DEBUG) Log.d(TAG, "Defining prop " + key + " to " + value.toString());
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
            if (DEBUG) Log.d(TAG, "Defining version field " + key + " to " + value.toString());
            Field field = Build.VERSION.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to set version field " + key, e);
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
            if (DEBUG) Log.d(TAG, "shouldBypassTaskPermission: gmsUid:" + gmsUid + " callingUid:" + callingUid);
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
}
