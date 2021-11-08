/**
 * Copyright (C) 2016 Samsung Electronics Co., Ltd. All rights reserved.
 *
 * Mobile Communications Business,
 * IT & Mobile Communications, Samsung Electronics Co., Ltd.
 *
 * This software and its documentation are confidential and proprietary
 * information of Samsung Electronics Co., Ltd.  No part of the software and
 * documents may be copied, reproduced, transmitted, translated, or reduced to
 * any electronic medium or machine-readable form without the prior written
 * consent of Samsung Electronics.
 *
 * Samsung Electronics makes no representations with respect to the contents,
 * and assumes no responsibility for any errors that might appear in the
 * software and documents. This publication and the contents hereof are subject
 * to change without notice.
 */

package com.android.server;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.MergedConfiguration;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static java.lang.Math.abs;

/**
 * Stores datails for appications with DSS(Dynamic Surface Scaling)
 * applied and whether they are running.
 */
public class DssController {
    static final String TAG = "DssController";

    private static DssController sDssController = null;

    public static DssController getService() {
        if (sDssController == null) {
            sDssController = new DssController();
        }
        return sDssController;
    }

    private final float DEFAULT_SCALE = 1.0f;
    private final float FLOAT_EPSILON = 0.001f;

    private class DssAppDate{
        public float mScale = DEFAULT_SCALE;
        DssAppDate(){
            mScale = 1.0f;
        }

        void addPackage(float scalingFactor){
            mScale = scalingFactor;
        }
    }

    private class RunningPackage {
        public ArrayList<Integer> mPids;
        public ArrayList<String> mFixedSizeSurfaces;
        public float mDssScale;

        public RunningPackage(int firstPid, float dssScale) {
            mPids = new ArrayList<>();
            mPids.add(firstPid);
            mFixedSizeSurfaces = new ArrayList<>();
            mDssScale = dssScale;
        }
    }

    private final HashMap<String, DssAppDate> mDssList = new HashMap<String, DssAppDate>();
    private final HashMap<String, RunningPackage> mRunningPackages = new HashMap<>();

    public DssController() {
    }

    static public class Tools {
        public static void applyDssToConfiguration(Configuration config, float dssScale) {
            if (config.densityDpi != Configuration.DENSITY_DPI_UNDEFINED) {
                config.densityDpi = scaleDpiValue(config.densityDpi, dssScale);
            }
            if (config.windowConfiguration.getAppBounds() != null) {
                applyScaleToCompatFrame(config.windowConfiguration.getAppBounds(), dssScale);
            }
        }

        public static void applyDssToMergedConfiguration(MergedConfiguration config,
                                                         float dssScale) {
            int globalDpi = config.getGlobalConfiguration().densityDpi;
            int overrideDpi = config.getOverrideConfiguration().densityDpi;
            Configuration newOverride = new Configuration(config.getOverrideConfiguration());
            if (overrideDpi != Configuration.DENSITY_DPI_UNDEFINED) {
                // If the override config defines a DPI, scale that
                newOverride.densityDpi = scaleDpiValue(overrideDpi, dssScale);
            } else if (globalDpi != Configuration.DENSITY_DPI_UNDEFINED) {
                // Otherwise, if applicable, scale the global config's DPI and add it
                // to the override config
                newOverride.densityDpi = scaleDpiValue(globalDpi, dssScale);
            }

            Rect globalBounds = config.getGlobalConfiguration().windowConfiguration.getAppBounds();
            Rect overrideBounds = config.getOverrideConfiguration().windowConfiguration.
                    getAppBounds();

            if (overrideBounds != null) {
                // If the override config defines bounds, scale those
                Rect newBounds = new Rect(overrideBounds);
                applyScaleToCompatFrame(newBounds, dssScale);
                newOverride.windowConfiguration.setAppBounds(newBounds);
            } else if (globalBounds != null) {
                // Otherwise, if applicable, scale the global config's bounds and add it
                // to the override config
                Rect newBounds = new Rect(globalBounds);
                applyScaleToCompatFrame(newBounds, dssScale);
                newOverride.windowConfiguration.setAppBounds(newBounds);
            }

            config.setOverrideConfiguration(newOverride);
        }

        public static int scaleDpiValue(int dpi, float scale) {
            return (int) (dpi * scale + .5f);
        }

        public static void applyScaleToCompatFrame(Rect rect, float dssScale) {
            // Also the scaled frame that we report to the app needs to be
            // adjusted to be in its coordinate space.
            int offsetX = -rect.left;
            int offsetY = -rect.top;
            rect.offset(offsetX, offsetY);
            rect.scale(dssScale);
            rect.offset(-(int)(offsetX * dssScale + .5f), -(int)(offsetY * dssScale + .5f));
        }
    }

    /**
     * Add package to DSS list if the scalingFactor is not 1.0. Remove
     * package from DSS list if the scalingFactor is 1.0.
     */
    public synchronized void setDssForPackage(String packageName, float scalingFactor) {
        if (abs(scalingFactor - 1.0f) < FLOAT_EPSILON) { // scalingFactor == 1.0f
            removePackage(packageName);
        } else {
            addPackageData(packageName,scalingFactor);
        }
    }

    public synchronized void addPackageData(String packageName, float scalingFactor) {
        DssAppDate dssAppData = new DssAppDate();
        dssAppData.addPackage(scalingFactor);
        mDssList.put(packageName, dssAppData);
    }

    public synchronized void removePackage(String packageName) {
        if (mDssList.containsKey(packageName)) {
            mDssList.remove(packageName);
        }
    }

    public synchronized void showAllDSSInfo() {
        for (Map.Entry<String, DssAppDate> elem : mDssList.entrySet()) {
            if (mRunningPackages.containsKey(elem.getKey())) {
                String pkgName = elem.getKey();
                RunningPackage pkg = mRunningPackages.get(pkgName);

                String pids = String.valueOf(pkg.mPids.get(0));
                for (int i = 1; i < pkg.mPids.size(); ++i) {
                    pids += ", ";
                    pids += String.valueOf(pkg.mPids.get(i));
                }

                String surfaces = "";
                for (int i = 0; i < pkg.mFixedSizeSurfaces.size(); ++i) {
                    if (i != 0) surfaces += ", ";
                    surfaces += pkg.mFixedSizeSurfaces.get(i);
                }
                Slog.i(TAG, "\tRunning Package - " + /*"+String.format("%1$-32s", pkgName).
                substring(0, 32)+",\t*/ "Scale: " + pkg.mDssScale + ",\tPIDs: {" + pids +
                "}" + (pkg.mFixedSizeSurfaces.isEmpty() ? "" : ",\tFixed Size Surfaces: "
                + "{" + surfaces + "}"));
            }
        }
    }

    private String pidToPkg(int pid) {
        String packageName = null;
        for (Map.Entry<String, RunningPackage> pkg : mRunningPackages.entrySet())
        {
            if (pkg.getValue().mPids.contains(pid)) {
                packageName = pkg.getKey();
                break;
            }
        }
        return packageName;
    }

    public synchronized boolean isScaledApp(int pid) {
        return isScaledApp(pidToPkg(pid));
    }

    public synchronized boolean isScaledApp(String packageName) {
        if (!isScaleAllowed() || !mRunningPackages.containsKey(packageName)) {
            return false;
        } else {
            return mRunningPackages.get(packageName).mDssScale != DEFAULT_SCALE;
        }
    }

    public synchronized float getScalingFactor(int pid) {
        return getScalingFactor(pidToPkg(pid));
    }

    public synchronized float getScalingFactor(String packageName) {
        if (!isScaleAllowed()) {
            return DEFAULT_SCALE;
        }
        return mRunningPackages.containsKey(packageName) ?
            mRunningPackages.get(packageName).mDssScale : DEFAULT_SCALE;
    }

    public synchronized float onApplicationStarted(String packageName, int pid,
                                                   boolean forceDisable) {
        float dssScale = DEFAULT_SCALE;
        if (mRunningPackages.containsKey(packageName)) {
            // It's already running, so just add the pid
            RunningPackage pkg = mRunningPackages.get(packageName);
            pkg.mPids.add(pid);
            dssScale = pkg.mDssScale;
        } else {
            // It's not running yet, so get the info we need to create a new entry
            if (mDssList != null && mDssList.containsKey(packageName)) {
                dssScale = forceDisable ? 1.0f : mDssList.get(packageName).mScale;
            }
            mRunningPackages.put(packageName, new RunningPackage(pid, dssScale));
        }
        if (!isScaleAllowed()) {
            return DEFAULT_SCALE;
        }
        return dssScale;
    }

    public synchronized void onApplicationStopped(String packageName, int pid) {
        RunningPackage pkg = mRunningPackages.get(packageName);
        if (pkg == null) {
            return;
        }
        if (pkg.mPids.size() <= 1) {
            if (pkg.mPids.size() == 1 && pkg.mPids.get(0) != pid) {
                Slog.wtf(TAG, "Stopped pid does not match the started " +
                        "pid recorded by DssController!");
            }
            // All processes have now stopped, so remove the package
            mRunningPackages.remove(packageName);
        } else {
            // There is still at least one process, so just remove this pid
            // Be careful to remove by Integer object, not by int index!
            pkg.mPids.remove((Integer)pid);
        }
    }

    /** aux functions */
    public Configuration createScaledConfiguration(Configuration config, String packageName) {
        if (isScaledApp(packageName)) {
            float dssScale = getScalingFactor(packageName);
            config = new Configuration(config);
            Tools.applyDssToConfiguration(config, dssScale);
        }
        return config;
    }

    public void scaleExistingConfiguration(Configuration config, String packageName) {
        if (isScaledApp(packageName)) {
            float dssScale = getScalingFactor(packageName);
            Tools.applyDssToConfiguration(config, dssScale);
        }
    }

    public void scaleExistingMergedConfiguration(MergedConfiguration config, String packageName) {
        if (isScaledApp(packageName)) {
            float dssScale = getScalingFactor(packageName);
            Tools.applyDssToMergedConfiguration(config, dssScale);
        }
    }

    private static boolean isScaleAllowed() {
        return true;
    }
}