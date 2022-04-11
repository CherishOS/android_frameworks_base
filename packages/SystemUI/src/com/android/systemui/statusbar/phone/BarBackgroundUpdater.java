/*
 * Copyright (C) 2014 ParanoidAndroid Project
 * Copyright (C) 2021 Arif JeNong
 * Copyright (C) 2021 The Android Open Source Project
 * Copyright (C) 2021 Dynamic System Bars Project
 * Copyright (C) 2022 Nusantara Project
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


package com.android.systemui.statusbar.phone;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Surface;
import android.view.WindowManager;

import com.android.systemui.nad.DynamicUtils;
import com.android.systemui.nad.ResourceUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class BarBackgroundUpdater {
    public static boolean abu;
    public static boolean accent;
    public static boolean linkedColor;
    public static boolean mStatusFilterEnabled;
    public static boolean mNavigationEnabled;
    public static boolean mStatusEnabled;
    public static boolean reverse;
    public static boolean PAUSED;

    public static int mNavigationBarIconOverrideColor;
    public static int mNavigationBarOverrideColor;
    public static int mPreviousNavigationBarIconOverrideColor;
    public static int mPreviousNavigationBarOverrideColor;
    public static int mPreviousStatusBarIconOverrideColor;
    public static int mPreviousStatusBarOverrideColor;
    public static int mStatusBarIconOverrideColor;
    public static int mStatusBarOverrideColor;
    public static int navigationBarOverrideColor;
    public static int navigationBarIconOverrideColor;
    public static int statusBarOverrideColor;
    public static int statusBarIconOverrideColor;
    public static int mTransparency;
    public static int parseColorLight;
    public static int parseColorDark;
    public static long sMinDelay = 50;
    public static int mDynamicColor;

    public static final ArrayList<UpdateListener> mListeners = new ArrayList<>();
    public static Handler mHandler;
    public static Context mContext;
    public static SettingsObserver mObserver;

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (BarBackgroundUpdater.class) {
                if (intent.getAction().equals("android.intent.action.SCREEN_OFF")) {
                    pause();
                } else if (intent.getAction().equals("android.intent.action.SCREEN_ON")) {
                    resume();
                }
            }
        }
    };

    private static final Thread THREAD = new Thread(() -> {
            while (true) {
                float f2 = 0.3f;
                int f3 = -10;
                float f = 0.7f;

                if (PAUSED) {

                    // we have been told to do nothing; wait for notify to continue
                    synchronized (BarBackgroundUpdater.class) {
                        try {
                            BarBackgroundUpdater.class.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    continue;
                }

                boolean isAnyDsbEnabled = mStatusEnabled || mNavigationEnabled;

                if (isAnyDsbEnabled) {
                    final Context context = mContext;
                    int isSleep = 1000;

                    if (context == null) {

                        // we haven't been initiated yet; retry in a bit
                        try {
                            Thread.sleep(isSleep);
                        } catch (InterruptedException e) {
                            return;
                        }

                        continue;
                    }

                    final WindowManager wm =
                            (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

                    final int rotation = wm.getDefaultDisplay().getRotation();
                    final boolean isLandscape = rotation == Surface.ROTATION_90 ||
                            rotation == Surface.ROTATION_270;

                    final Resources r = context.getResources();
                    final int statusBarHeight = r.getDimensionPixelSize(
                            ResourceUtils.getAndroidDimenResId("status_bar_height"));
                    final int navigationBarHeight = r.getDimensionPixelSize(
                            ResourceUtils.getAndroidDimenResId("navigation_bar_height" + (isLandscape ?
                                    "_landscape" : "")));

                    parseColorLight = Color.parseColor("#FFFFFFFF");
                    parseColorDark = accent ? r.getColor(ResourceUtils.getAndroidColorResId("accent_device_default_light")) : Color.parseColor(abu ? "#ff646464" : "#FF000000");

                    final int colors = DynamicUtils.getTargetColorStatusBar(statusBarHeight);
                    final int colorsn = linkedColor ? colors : DynamicUtils.getTargetColorNavi(navigationBarHeight);

                    if (navigationBarHeight <= 0 && mNavigationEnabled) {
                        // the navigation bar height is not positive - no dynamic navigation bar
                        Settings.System.putInt(context.getContentResolver(),
                                "DYNAMIC_NAVIGATION_BAR_STATE", 0);

                        // configuration has changed - abort and retry in a bit
                        try {
                            Thread.sleep(isSleep);
                        } catch (InterruptedException e) {
                            return;
                        }
                        continue;
                    }

                    boolean statuscolors = (colors != 0);

                    int dsbColor = mDynamicColor = statusBarOverrideColor = mStatusFilterEnabled ? filter(colors, (float) f3) : colors;
                    int iconColor = statusBarIconOverrideColor = (cekbriknes(dsbColor) <= f || !statuscolors) ? parseColorLight : parseColorDark;

                    // Dynamic status bar
                    boolean statusEnable = mStatusEnabled;

                    updateStatusBarColor(statusEnable ? dsbColor : 0);
                    updateStatusBarIconColor(statusEnable ? iconColor : 0);

                    // Dynamic navigation bar
                    if (mNavigationEnabled) {
                        int colornav = navigationBarOverrideColor = colorsn;
                        int colorstat = statusBarOverrideColor;
                        updateNavigationBarColor(colornav);
                        float cekbriknesStatus = cekbriknes(colorstat);
                        float cekbriknesnavigationBar = cekbriknes(colornav);
                        boolean navigationcolors = (colorsn != 0);
                        int red = Color.red(colorstat);
                        int green = Color.green(colorstat);
                        int blue = Color.blue(colorstat);
                        int colorResult = Color.argb(0xFF, red, green, blue);
                        int parseColor = !reverse || !mStatusEnabled || cekbriknesStatus > f && cekbriknesnavigationBar > f || cekbriknesStatus < f2 && cekbriknesnavigationBar < f2 || cekbriknesStatus == cekbriknesnavigationBar ? (cekbriknesnavigationBar <= f || !navigationcolors) ? parseColorLight : parseColorDark : colorResult;
                        updateNavigationBarIconColor(parseColor);
                    } else {
                        // dynamic navigation bar is disabled
                        updateNavigationBarColor(0);
                        updateNavigationBarIconColor(0);
                    }
                } else {
                    // we are disabled completely - shush
                    updateStatusBarColor(0);
                    updateStatusBarIconColor(0);
                    updateNavigationBarColor(0);
                    updateNavigationBarIconColor(0);
                }

                // do a quick cleanup of the listener list
                synchronized (BarBackgroundUpdater.class) {
                    final ArrayList<UpdateListener> removables = new ArrayList<UpdateListener>();

                    for (final UpdateListener listener : mListeners) {
                        if (listener.shouldGc()) {
                            removables.add(listener);
                        }
                    }

                    for (final UpdateListener removable : removables) {
                        mListeners.remove(removable);
                    }
                }
                final long now = System.currentTimeMillis();
                final long delta = now - now;
                final long delay = Math.max(sMinDelay, delta * 2);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    return;
                }
            }
    });

    static {
        THREAD.setPriority(4);
        THREAD.start();
    }

    public static float cekbriknes(int tintColor) {
        return (0.299f * Color.red(tintColor) +
                0.587f * Color.green(tintColor) +
                0.114f * Color.blue(tintColor)) / 255;
    }

    public static synchronized void setPauseState(boolean pause) {
        PAUSED = pause;
        if (!pause) {
            BarBackgroundUpdater.class.notifyAll();
        }
    }

    public static void pause() {
        setPauseState(true);
    }

    public static void resume() {
        setPauseState(false);
    }

    public synchronized static void init(Context context) {
        Context ctx = mContext;
        if (ctx != null) {
            ctx.unregisterReceiver(RECEIVER);
            if (mObserver != null) {
                context.getContentResolver().unregisterContentObserver(mObserver);
            }
        }
        mHandler = new Handler();
        mContext = context;
        ContentResolver resolver = mContext.getContentResolver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.SCREEN_ON");
        intentFilter.addAction("android.intent.action.SCREEN_OFF");
        mContext.registerReceiver(RECEIVER, intentFilter);
        if (mObserver == null) {
            mObserver = new SettingsObserver(new Handler());
        }
        resolver.registerContentObserver(Settings.System.getUriFor("DYNAMIC_STATUS_BAR_STATE"), false, mObserver);
        resolver.registerContentObserver(Settings.System.getUriFor("DYNAMIC_SYSTEM_BARS_GRADIENT_STATE"), false, mObserver);
        resolver.registerContentObserver(Settings.System.getUriFor("DYNAMIC_NAVIGATION_BARS_GRADIENT_STATE"), false, mObserver);
        resolver.registerContentObserver(Settings.System.getUriFor("DYNAMIC_NAVIGATION_BAR_STATE"), false, mObserver);
        resolver.registerContentObserver(Settings.System.getUriFor("DYNAMIC_STATUS_BAR_FILTER_STATE"), false, mObserver);
        resolver.registerContentObserver(Settings.System.getUriFor("EXPERIMENTAL_DSB_FREQUENCY"), false, mObserver);
        resolver.registerContentObserver(Settings.System.getUriFor("UI_COLOR"), false, mObserver);
        resolver.registerContentObserver(Settings.System.getUriFor("ABU_ABU"), false, mObserver);
        resolver.registerContentObserver(Settings.System.getUriFor("ACCENT_COLOR"), false, mObserver);
        resolver.registerContentObserver(Settings.System.getUriFor("LINKED_COLOR"), false, mObserver);
        accent = Settings.System.getIntForUser(resolver, "ACCENT_COLOR", 0, UserHandle.USER_CURRENT) == 1;
        linkedColor = Settings.System.getIntForUser(resolver, "LINKED_COLOR", 0, UserHandle.USER_CURRENT) == 1;
        abu = Settings.System.getIntForUser(resolver, "ABU_ABU", 0, UserHandle.USER_CURRENT) == 1;
        reverse = Settings.System.getIntForUser(resolver, "UI_COLOR", 0, UserHandle.USER_CURRENT) == 1;
        mStatusEnabled = Settings.System.getIntForUser(resolver, "DYNAMIC_STATUS_BAR_STATE", 0, UserHandle.USER_CURRENT) == 1;
        mNavigationEnabled = Settings.System.getIntForUser(resolver, "DYNAMIC_NAVIGATION_BAR_STATE", 0, UserHandle.USER_CURRENT) == 1;
        mStatusFilterEnabled = Settings.System.getIntForUser(resolver, "DYNAMIC_STATUS_BAR_FILTER_STATE", 0, UserHandle.USER_CURRENT) == 1;
        mTransparency = Settings.System.getIntForUser(resolver, "EXPERIMENTAL_DSB_FREQUENCY", 255, UserHandle.USER_CURRENT);
        resume();
    }

    public synchronized static void addListener(UpdateListener... updateListenerArr) {
        for (UpdateListener updateListener : updateListenerArr) {
            if (updateListener != null) {
                updateListener.onUpdateStatusBarColor(mPreviousStatusBarOverrideColor, mStatusBarOverrideColor);
                updateListener.onUpdateStatusBarIconColor(mPreviousStatusBarIconOverrideColor, mStatusBarIconOverrideColor);
                updateListener.onUpdateNavigationBarColor(mPreviousNavigationBarOverrideColor, mNavigationBarOverrideColor);
                updateListener.onUpdateNavigationBarIconColor(mPreviousNavigationBarIconOverrideColor, mNavigationBarIconOverrideColor);
                boolean update = true;
                for (UpdateListener updateListener2 : mListeners) {
                    if (updateListener2 == updateListener) {
                        update = false;
                    }
                }
                if (update) {
                    mListeners.add(updateListener);
                }
            }
        }
    }

    public static int filter(final int original, final float diff) {
        final int red = (int) (Color.red(original) + diff);
        final int green = (int) (Color.green(original) + diff);
        final int blue = (int) (Color.blue(original) + diff);

        return Color.argb(
                Color.alpha(original),
                red > 0 ? (red < 255 ? red : 255) : 0,
                green > 0 ? (green < 255 ? green : 255) : 0,
                blue > 0 ? (blue < 255 ? blue : 255) : 0);
    }

    private static final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            ContentResolver resolver = mContext.getContentResolver();
            accent = Settings.System.getIntForUser(resolver, "ACCENT_COLOR", 0, UserHandle.USER_CURRENT) == 1;
            linkedColor = Settings.System.getIntForUser(resolver, "LINKED_COLOR", 0, UserHandle.USER_CURRENT) == 1;
            abu = Settings.System.getIntForUser(resolver, "ABU_ABU", 0, UserHandle.USER_CURRENT) == 1;
            reverse = Settings.System.getIntForUser(resolver, "UI_COLOR", 0, UserHandle.USER_CURRENT) == 1;
            mStatusEnabled = Settings.System.getIntForUser(resolver, "DYNAMIC_STATUS_BAR_STATE", 0, UserHandle.USER_CURRENT) == 1;
            mNavigationEnabled = Settings.System.getIntForUser(resolver, "DYNAMIC_NAVIGATION_BAR_STATE", 0, UserHandle.USER_CURRENT) == 1;
            mStatusFilterEnabled = Settings.System.getIntForUser(resolver, "DYNAMIC_STATUS_BAR_FILTER_STATE", 0, UserHandle.USER_CURRENT) == 1;
            mTransparency = Settings.System.getIntForUser(resolver, "EXPERIMENTAL_DSB_FREQUENCY", 255, UserHandle.USER_CURRENT);
        }
    }

    public static class UpdateListener {
        private final WeakReference<Object> mRef;

        public void onUpdateNavigationBarColor(int previousColor, int newColor) {
        }

        public void onUpdateNavigationBarIconColor(int previousColor, int newColor) {
        }

        public void onUpdateStatusBarColor(int previousColor, int newColor) {
        }

        public void onUpdateStatusBarIconColor(int previousColor, int newColor) {
        }

        public UpdateListener(Object obj) {
            mRef = new WeakReference<Object>(obj);
        }

        public final boolean shouldGc() {
            return mRef.get() == null;
        }
    }

    public synchronized static void updateStatusBarColor(int newColor) {
        if (mStatusBarOverrideColor != newColor) {
            mPreviousStatusBarOverrideColor = mStatusBarOverrideColor;
            mStatusBarOverrideColor = newColor;
            for (UpdateListener onUpdateStatusBarColor : mListeners) {
                onUpdateStatusBarColor.onUpdateStatusBarColor(mPreviousStatusBarOverrideColor, mStatusBarOverrideColor);
            }
        }
    }

    public synchronized static void updateNavigationBarColor(int newColor) {
        if (mNavigationBarOverrideColor != newColor) {
            mPreviousNavigationBarOverrideColor = mNavigationBarOverrideColor;
            mNavigationBarOverrideColor = newColor;
            for (UpdateListener onUpdateNavigationBarColor : mListeners) {
                onUpdateNavigationBarColor.onUpdateNavigationBarColor(mPreviousNavigationBarOverrideColor, mNavigationBarOverrideColor);
            }
        }
    }

    public synchronized static void updateStatusBarIconColor(int newColor) {
        if (mStatusBarIconOverrideColor != newColor) {
            mPreviousStatusBarIconOverrideColor = mStatusBarIconOverrideColor;
            mStatusBarIconOverrideColor = newColor;
            for (UpdateListener onUpdateStatusBarIconColor : mListeners) {
                onUpdateStatusBarIconColor.onUpdateStatusBarIconColor(mPreviousStatusBarIconOverrideColor, mStatusBarIconOverrideColor);
            }
        }
    }

    public synchronized static void updateNavigationBarIconColor(int newColor) {
        if (mNavigationBarIconOverrideColor != newColor) {
            mPreviousNavigationBarIconOverrideColor = mNavigationBarIconOverrideColor;
            mNavigationBarIconOverrideColor = newColor;
            for (UpdateListener onUpdateNavigationBarIconColor : mListeners) {
                onUpdateNavigationBarIconColor.onUpdateNavigationBarIconColor(mPreviousNavigationBarIconOverrideColor, mNavigationBarIconOverrideColor);
            }
        }
    }
}
