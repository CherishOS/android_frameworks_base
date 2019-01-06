/*
 * Copyright 2016 The Android Open Source Project
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

package android.os;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.opengl.EGL14;
import android.provider.Settings;
import android.util.Log;

import dalvik.system.VMRuntime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** @hide */
public class GraphicsEnvironment {

    private static final GraphicsEnvironment sInstance = new GraphicsEnvironment();

    /**
     * Returns the shared {@link GraphicsEnvironment} instance.
     */
    public static GraphicsEnvironment getInstance() {
        return sInstance;
    }

    private static final boolean DEBUG = false;
    private static final String TAG = "GraphicsEnvironment";
    private static final String PROPERTY_GFX_DRIVER = "ro.gfx.driver.0";
    private static final String GUP_WHITELIST_FILENAME = "whitelist.txt";
    private static final String ANGLE_RULES_FILE = "a4a_rules.json";
    private static final String ANGLE_TEMP_RULES = "debug.angle.rules";
    private static final String ACTION_ANGLE_FOR_ANDROID = "android.app.action.ANGLE_FOR_ANDROID";

    private ClassLoader mClassLoader;
    private String mLayerPath;
    private String mDebugLayerPath;

    /**
     * Set up GraphicsEnvironment
     */
    public void setup(Context context, Bundle coreSettings) {
        setupGpuLayers(context, coreSettings);
        setupAngle(context, coreSettings, context.getPackageName());
        chooseDriver(context, coreSettings);
    }

    /**
     * Check whether application is debuggable
     */
    private static boolean isDebuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) > 0;
    }

    /**
     * Store the layer paths available to the loader.
     */
    public void setLayerPaths(ClassLoader classLoader,
                              String layerPath,
                              String debugLayerPath) {
        // We have to store these in the class because they are set up before we
        // have access to the Context to properly set up GraphicsEnvironment
        mClassLoader = classLoader;
        mLayerPath = layerPath;
        mDebugLayerPath = debugLayerPath;
    }

    /**
     * Return the debug layer app's on-disk and in-APK lib directories
     */
    private static String getDebugLayerAppPaths(Context context, String app) {
        ApplicationInfo appInfo;
        try {
            appInfo = context.getPackageManager().getApplicationInfo(
                    app, PackageManager.MATCH_ALL);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Debug layer app '" + app + "' not installed");

            return null;
        }

        String abi = chooseAbi(appInfo);

        StringBuilder sb = new StringBuilder();
        sb.append(appInfo.nativeLibraryDir)
            .append(File.pathSeparator);
        sb.append(appInfo.sourceDir)
            .append("!/lib/")
            .append(abi);
        String paths = sb.toString();

        if (DEBUG) Log.v(TAG, "Debug layer app libs: " + paths);

        return paths;
    }

    /**
     * Set up layer search paths for all apps
     * If debuggable, check for additional debug settings
     */
    private void setupGpuLayers(Context context, Bundle coreSettings) {

        String layerPaths = "";

        // Only enable additional debug functionality if the following conditions are met:
        // 1. App is debuggable or device is rooted
        // 2. ENABLE_GPU_DEBUG_LAYERS is true
        // 3. Package name is equal to GPU_DEBUG_APP

        if (isDebuggable(context) || (getCanLoadSystemLibraries() == 1)) {

            int enable = coreSettings.getInt(Settings.Global.ENABLE_GPU_DEBUG_LAYERS, 0);

            if (enable != 0) {

                String gpuDebugApp = coreSettings.getString(Settings.Global.GPU_DEBUG_APP);

                String packageName = context.getPackageName();

                if ((gpuDebugApp != null && packageName != null)
                        && (!gpuDebugApp.isEmpty() && !packageName.isEmpty())
                        && gpuDebugApp.equals(packageName)) {
                    Log.i(TAG, "GPU debug layers enabled for " + packageName);

                    // Prepend the debug layer path as a searchable path.
                    // This will ensure debug layers added will take precedence over
                    // the layers specified by the app.
                    layerPaths = mDebugLayerPath + ":";


                    // If there is a debug layer app specified, add its path.
                    String gpuDebugLayerApp =
                            coreSettings.getString(Settings.Global.GPU_DEBUG_LAYER_APP);

                    if (gpuDebugLayerApp != null && !gpuDebugLayerApp.isEmpty()) {
                        Log.i(TAG, "GPU debug layer app: " + gpuDebugLayerApp);
                        String paths = getDebugLayerAppPaths(context, gpuDebugLayerApp);
                        if (paths != null) {
                            // Append the path so files placed in the app's base directory will
                            // override the external path
                            layerPaths += paths + ":";
                        }
                    }

                    String layers = coreSettings.getString(Settings.Global.GPU_DEBUG_LAYERS);

                    Log.i(TAG, "Vulkan debug layer list: " + layers);
                    if (layers != null && !layers.isEmpty()) {
                        setDebugLayers(layers);
                    }

                    String layersGLES =
                            coreSettings.getString(Settings.Global.GPU_DEBUG_LAYERS_GLES);

                    Log.i(TAG, "GLES debug layer list: " + layersGLES);
                    if (layersGLES != null && !layersGLES.isEmpty()) {
                        setDebugLayersGLES(layersGLES);
                    }
                }
            }
        }

        // Include the app's lib directory in all cases
        layerPaths += mLayerPath;

        setLayerPaths(mClassLoader, layerPaths);
    }

    enum OpenGlDriverChoice {
        DEFAULT,
        NATIVE,
        ANGLE
    }

    private static final Map<OpenGlDriverChoice, String> sDriverMap = buildMap();
    private static Map<OpenGlDriverChoice, String> buildMap() {
        Map<OpenGlDriverChoice, String> map = new HashMap<>();
        map.put(OpenGlDriverChoice.DEFAULT, "default");
        map.put(OpenGlDriverChoice.ANGLE, "angle");
        map.put(OpenGlDriverChoice.NATIVE, "native");

        return map;
    }


    private static List<String> getGlobalSettingsString(Bundle bundle, String globalSetting) {
        List<String> valueList = null;
        String settingsValue = bundle.getString(globalSetting);

        if (settingsValue != null) {
            valueList = new ArrayList<>(Arrays.asList(settingsValue.split(",")));
        } else {
            valueList = new ArrayList<>();
        }

        return valueList;
    }

    private static int getGlobalSettingsPkgIndex(String pkgName,
                                                 List<String> globalSettingsDriverPkgs) {
        for (int pkgIndex = 0; pkgIndex < globalSettingsDriverPkgs.size(); pkgIndex++) {
            if (globalSettingsDriverPkgs.get(pkgIndex).equals(pkgName)) {
                return pkgIndex;
            }
        }

        return -1;
    }

    private static String getDriverForPkg(Bundle bundle, String packageName) {
        String allUseAngle =
                bundle.getString(Settings.Global.GLOBAL_SETTINGS_ANGLE_GL_DRIVER_ALL_ANGLE);
        if ((allUseAngle != null) && allUseAngle.equals("1")) {
            return sDriverMap.get(OpenGlDriverChoice.ANGLE);
        }

        List<String> globalSettingsDriverPkgs =
                getGlobalSettingsString(bundle,
                        Settings.Global.GLOBAL_SETTINGS_ANGLE_GL_DRIVER_SELECTION_PKGS);
        List<String> globalSettingsDriverValues =
                getGlobalSettingsString(bundle,
                        Settings.Global.GLOBAL_SETTINGS_ANGLE_GL_DRIVER_SELECTION_VALUES);

        // Make sure we have a good package name
        if ((packageName == null) || (packageName.isEmpty())) {
            return sDriverMap.get(OpenGlDriverChoice.DEFAULT);
        }
        // Make sure we have good settings to use
        if (globalSettingsDriverPkgs.size() != globalSettingsDriverValues.size()) {
            Log.w(TAG,
                    "Global.Settings values are invalid: "
                        + "globalSettingsDriverPkgs.size = "
                            + globalSettingsDriverPkgs.size() + ", "
                        + "globalSettingsDriverValues.size = "
                            + globalSettingsDriverValues.size());
            return sDriverMap.get(OpenGlDriverChoice.DEFAULT);
        }

        int pkgIndex = getGlobalSettingsPkgIndex(packageName, globalSettingsDriverPkgs);

        if (pkgIndex < 0) {
            return sDriverMap.get(OpenGlDriverChoice.DEFAULT);
        }

        return globalSettingsDriverValues.get(pkgIndex);
    }

    /**
     * Get the ANGLE package name.
     */
    private String getAnglePackageName(Context context) {
        Intent intent = new Intent(ACTION_ANGLE_FOR_ANDROID);

        List<ResolveInfo> resolveInfos = context.getPackageManager()
                .queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY);
        if (resolveInfos.size() != 1) {
            Log.e(TAG, "Invalid number of ANGLE packages. Required: 1, Found: "
                    + resolveInfos.size());
            for (ResolveInfo resolveInfo : resolveInfos) {
                Log.e(TAG, "Found ANGLE package: " + resolveInfo.activityInfo.packageName);
            }
            return "";
        }

        // Must be exactly 1 ANGLE PKG found to get here.
        return resolveInfos.get(0).activityInfo.packageName;
    }

    /**
     * Attempt to setup ANGLE with a temporary rules file.
     * True: Temporary rules file was loaded.
     * False: Temporary rules file was *not* loaded.
     */
    private boolean setupAngleWithTempRulesFile(Context context,
                                                String packageName,
                                                String paths,
                                                String devOptIn) {
        // Check for temporary rules if debuggable or root
        if (!isDebuggable(context) && !(getCanLoadSystemLibraries() == 1)) {
            Log.v(TAG, "Skipping loading temporary rules file");
            return false;
        }

        String angleTempRules = SystemProperties.get(ANGLE_TEMP_RULES);

        if ((angleTempRules == null) || angleTempRules.isEmpty()) {
            Log.v(TAG, "System property '" + ANGLE_TEMP_RULES + "' is not set or is empty");
            return false;
        }

        Log.i(TAG, "Detected system property " + ANGLE_TEMP_RULES + ": " + angleTempRules);

        File tempRulesFile = new File(angleTempRules);
        if (tempRulesFile.exists()) {
            Log.i(TAG, angleTempRules + " exists, loading file.");
            try {
                FileInputStream stream = new FileInputStream(angleTempRules);

                try {
                    FileDescriptor rulesFd = stream.getFD();
                    long rulesOffset = 0;
                    long rulesLength = stream.getChannel().size();
                    Log.i(TAG, "Loaded temporary ANGLE rules from " + angleTempRules);

                    setAngleInfo(paths, packageName, devOptIn, rulesFd, rulesOffset, rulesLength);

                    stream.close();

                    // We successfully setup ANGLE, so return with good status
                    return true;
                } catch (IOException e) {
                    Log.w(TAG, "Hit IOException thrown by FileInputStream: " + e);
                }
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Temp ANGLE rules file not found: " + e);
            } catch (SecurityException e) {
                Log.w(TAG, "Temp ANGLE rules file not accessible: " + e);
            }
        }

        return false;
    }

    /**
     * Attempt to setup ANGLE with a (temporary) default rules file: b/121153494
     * True: Rules file was loaded.
     * False: Rules file was *not* loaded.
     */
    private boolean setupAngleRulesDebug(String packageName, String paths, String devOptIn) {
        // b/121153494
        // Skip APK rules file checking.
        if (!DEBUG) {
            Log.v(TAG, "Skipping loading the rules file.");
            // Fill in some default values for now, so the loader can get an answer when it asks.
            // Most importantly, we need to indicate which app we are init'ing and what the
            // developer options for it are so we can turn on ANGLE if needed.
            setAngleInfo(paths, packageName, devOptIn, null, 0, 0);
            return true;
        }

        return false;
    }

    /**
     * Attempt to setup ANGLE with a rules file loaded from the ANGLE APK.
     * True: APK rules file was loaded.
     * False: APK rules file was *not* loaded.
     */
    private boolean setupAngleRulesApk(String anglePkgName,
            ApplicationInfo angleInfo,
            Context context,
            String packageName,
            String paths,
            String devOptIn) {
        // Pass the rules file to loader for ANGLE decisions
        try {
            AssetManager angleAssets =
                    context.getPackageManager().getResourcesForApplication(angleInfo).getAssets();

            try {
                AssetFileDescriptor assetsFd = angleAssets.openFd(ANGLE_RULES_FILE);

                setAngleInfo(paths, packageName, devOptIn, assetsFd.getFileDescriptor(),
                        assetsFd.getStartOffset(), assetsFd.getLength());

                assetsFd.close();

                return true;
            } catch (IOException e) {
                Log.w(TAG, "Failed to get AssetFileDescriptor for " + ANGLE_RULES_FILE
                        + " from '" + anglePkgName + "': " + e);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to get AssetManager for '" + anglePkgName + "': " + e);
        }

        return false;
    }

    /**
     * Pass ANGLE details down to trigger enable logic
     */
    public void setupAngle(Context context, Bundle bundle, String packageName) {
        String devOptIn = getDriverForPkg(bundle, packageName);

        if (DEBUG) {
            Log.v(TAG, "ANGLE Developer option for '" + packageName + "' "
                    + "set to: '" + devOptIn + "'");
        }

        String anglePkgName = getAnglePackageName(context);
        if (anglePkgName.isEmpty()) {
            Log.e(TAG, "Failed to find ANGLE package.");
            return;
        }

        ApplicationInfo angleInfo;
        try {
            angleInfo = context.getPackageManager().getApplicationInfo(anglePkgName,
                PackageManager.MATCH_SYSTEM_ONLY);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "ANGLE package '" + anglePkgName + "' not installed");
            return;
        }

        String abi = chooseAbi(angleInfo);

        // Build a path that includes installed native libs and APK
        String paths = angleInfo.nativeLibraryDir
                + File.pathSeparator
                + angleInfo.sourceDir
                + "!/lib/"
                + abi;

        if (DEBUG) Log.v(TAG, "ANGLE package libs: " + paths);

        if (setupAngleWithTempRulesFile(context, packageName, paths, devOptIn)) {
            // We setup ANGLE with a temp rules file, so we're done here.
            return;
        }

        // b/121153494
        if (setupAngleRulesDebug(packageName, paths, devOptIn)) {
            // We setup ANGLE with defaults, so we're done here.
            return;
        }

        if (setupAngleRulesApk(anglePkgName, angleInfo, context, packageName, paths, devOptIn)) {
            // We setup ANGLE with rules from the APK, so we're done here.
            return;
        }
    }

    /**
     * Choose whether the current process should use the builtin or an updated driver.
     */
    private static void chooseDriver(Context context, Bundle coreSettings) {
        String driverPackageName = SystemProperties.get(PROPERTY_GFX_DRIVER);
        if (driverPackageName == null || driverPackageName.isEmpty()) {
            return;
        }

        // To minimize risk of driver updates crippling the device beyond user repair, never use an
        // updated driver for privileged or non-updated system apps. Presumably pre-installed apps
        // were tested thoroughly with the pre-installed driver.
        ApplicationInfo ai = context.getApplicationInfo();
        if (ai.isPrivilegedApp() || (ai.isSystemApp() && !ai.isUpdatedSystemApp())) {
            if (DEBUG) Log.v(TAG, "ignoring driver package for privileged/non-updated system app");
            return;
        }

        String applicationPackageName = context.getPackageName();
        String devOptInApplicationName = coreSettings.getString(
                Settings.Global.GUP_DEV_OPT_IN_APPS);
        boolean devOptIn = applicationPackageName.equals(devOptInApplicationName);
        boolean whitelisted = onWhitelist(context, driverPackageName, ai.packageName);
        if (!devOptIn && !whitelisted) {
            if (DEBUG) {
                Log.w(TAG, applicationPackageName + " is not on the whitelist.");
            }
            return;
        }

        ApplicationInfo driverInfo;
        try {
            driverInfo = context.getPackageManager().getApplicationInfo(driverPackageName,
                    PackageManager.MATCH_SYSTEM_ONLY);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "driver package '" + driverPackageName + "' not installed");
            return;
        }

        // O drivers are restricted to the sphal linker namespace, so don't try to use
        // packages unless they declare they're compatible with that restriction.
        if (driverInfo.targetSdkVersion < Build.VERSION_CODES.O) {
            if (DEBUG) {
                Log.w(TAG, "updated driver package is not known to be compatible with O");
            }
            return;
        }

        String abi = chooseAbi(driverInfo);
        if (abi == null) {
            if (DEBUG) {
                // This is the normal case for the pre-installed empty driver package, don't spam
                if (driverInfo.isUpdatedSystemApp()) {
                    Log.w(TAG, "updated driver package has no compatible native libraries");
                }
            }
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(driverInfo.nativeLibraryDir)
          .append(File.pathSeparator);
        sb.append(driverInfo.sourceDir)
          .append("!/lib/")
          .append(abi);
        String paths = sb.toString();

        if (DEBUG) Log.v(TAG, "gfx driver package libs: " + paths);
        setDriverPath(paths);
    }

    /**
     * Start a background thread to initialize EGL.
     *
     * Initializing EGL involves loading and initializing the graphics driver. Some drivers take
     * several 10s of milliseconds to do this, so doing it on-demand when an app tries to render
     * its first frame adds directly to user-visible app launch latency. By starting it earlier
     * on a separate thread, it can usually be finished well before the UI is ready to be drawn.
     *
     * Should only be called after chooseDriver().
     */
    public static void earlyInitEGL() {
        Thread eglInitThread = new Thread(
                () -> {
                    EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                },
                "EGL Init");
        eglInitThread.start();
    }

    private static String chooseAbi(ApplicationInfo ai) {
        String isa = VMRuntime.getCurrentInstructionSet();
        if (ai.primaryCpuAbi != null &&
                isa.equals(VMRuntime.getInstructionSet(ai.primaryCpuAbi))) {
            return ai.primaryCpuAbi;
        }
        if (ai.secondaryCpuAbi != null &&
                isa.equals(VMRuntime.getInstructionSet(ai.secondaryCpuAbi))) {
            return ai.secondaryCpuAbi;
        }
        return null;
    }

    private static boolean onWhitelist(Context context, String driverPackageName,
            String applicationPackageName) {
        try {
            Context driverContext = context.createPackageContext(driverPackageName,
                                                                 Context.CONTEXT_RESTRICTED);
            AssetManager assets = driverContext.getAssets();
            InputStream stream = assets.open(GUP_WHITELIST_FILENAME);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            for (String packageName; (packageName = reader.readLine()) != null; ) {
                if (packageName.equals(applicationPackageName)) {
                    return true;
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) {
                Log.w(TAG, "driver package '" + driverPackageName + "' not installed");
            }
        } catch (IOException e) {
            if (DEBUG) {
                Log.w(TAG, "Failed to load whitelist driver package, abort.");
            }
        }
        return false;
    }

    private static native int getCanLoadSystemLibraries();
    private static native void setLayerPaths(ClassLoader classLoader, String layerPaths);
    private static native void setDebugLayers(String layers);
    private static native void setDebugLayersGLES(String layers);
    private static native void setDriverPath(String path);
    private static native void setAngleInfo(String path, String appPackage,
                                            String devOptIn, FileDescriptor rulesFd,
                                            long rulesOffset, long rulesLength);
}
