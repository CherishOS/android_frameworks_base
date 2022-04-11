/*
 * Copyright (C) Arif JeNong
 * Copyright (C) 2021 The Android Open Source Project
 * Copyright (C) Dynamic System Bars Project
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


// This sources implements DSB. Simple as that.

package com.android.systemui.nad;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.SurfaceControl;
import android.view.WindowManager;

public class DynamicUtils {

    private static Context mContext;
    static String TAG = "DynamicUtils";


    public static void init(Context ctx) {
        mContext = ctx;
    }

    private static int getARGB(int[] bitmap, int pixelSpacing) {
        int R = 0;
        int G = 0;
        int B = 0;
        int n = 0;
        for (int i = 0; i < bitmap.length; i += pixelSpacing) {
            int color = bitmap[i];
            R += Color.red(color);
            G += Color.green(color);
            B += Color.blue(color);
            n++;
        }
        R /= n;
        G /= n;
        B /= n;
        int alpha = 0xFF;
        R = (R >> 16) & alpha;
        G = (B >> 8) & alpha;
        B = (B >> 0) & alpha;
        return Color.argb(alpha, R, G, B);
    }

    private static int getColorBrightness(int color, int brightness) {
        int setBrightness = 0;
        float[] hsv = new float[3];
        float[] hsvNext = new float[3];
        Color.colorToHSV(color, hsv);
        float f = hsv[0];
        Color.colorToHSV(brightness, hsvNext);
        if (((int) Math.abs(f - hsvNext[0])) <= 20) {
            Color.colorToHSV(color, hsv);
            f = hsv[1];
            Color.colorToHSV(brightness, hsvNext);
            if (((int) Math.abs(f - hsvNext[1])) <= 20) {
                Color.colorToHSV(color, hsv);
                float hsv2 = hsv[2];
                Color.colorToHSV(brightness, hsvNext);
                if (((int) Math.abs(hsv2 - hsvNext[2])) <= 20) {
                    setBrightness = 1;
                }
            }
        }
        return setBrightness;
    }

    public static int getTargetColorStatusBar(int h) {
        return getTargetColor(h, false);
    }

    public static int getTargetColorNavi(int h) {
        return getTargetColor(h, true);
    }

    public static int getNotchHeightTop() {
        DisplayCutout displayCutout = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE))
            .getDefaultDisplay()
            .getCutout();

        if(displayCutout == null || displayCutout.getBoundingRects() == null){
            return 0;
        }

        return displayCutout.getSafeInsetTop();
    }

    public static int getNotchHeightBottom() {
        DisplayCutout displayCutout = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE))
            .getDefaultDisplay()
            .getCutout();

        if(displayCutout == null || displayCutout.getBoundingRects() == null){
            return 0;
        }

        return displayCutout.getSafeInsetBottom();
    }

    public static int getNotchHeightRight() {
        DisplayCutout displayCutout = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE))
            .getDefaultDisplay()
            .getCutout();

        if(displayCutout == null || displayCutout.getBoundingRects() == null){
            return 0;
        }

        return displayCutout.getSafeInsetRight();
    }

    public static int getNotchHeightLeft() {
        DisplayCutout displayCutout = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE))
            .getDefaultDisplay()
            .getCutout();

        if(displayCutout == null || displayCutout.getBoundingRects() == null){
            return 0;
        }

        return displayCutout.getSafeInsetLeft();
    }

    public static int getTargetColor(int heightTarget, boolean navigationBar) {
        Bitmap bitmap = takeScreenshotSurface();
        if (bitmap == null || Build.VERSION.SDK_INT != 33) {
            return Color.BLACK;
        }
        Context ctx = mContext;
        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(displayMetrics);
        int color;
        int[] bmp = new int[5];
        int n = 0;
        int Height = bitmap.getHeight()
            - 5
            - getNotchHeightBottom();
        int density = (int) displayMetrics.density;
        int OffsetY = navigationBar ?
            (Height - getNavigationBarSize()) :
            (heightTarget
            + 2
            + getNotchHeightTop());
        if (navigationBar) {
            if (OffsetY <= 0) {
                OffsetY = Height - heightTarget;
            }
        }
        int OffsetX = (1 / density) + getNotchHeightLeft();
        do {
            bmp[n] = bitmap.getPixel(OffsetX, OffsetY);
            n++;
        } while (n < 5);
        int[] pixelSpacing = new int[5];
        int n2 = 0;
        do {
            pixelSpacing[n2] = 0;
            n2++;
        } while (n2 < 5);
        int n3 = 0;
        do {
            int color2 = bmp[n3];
            int n4 = 0;
            int colorPixel2 = 0;
            do {
                colorPixel2 <<= 1;
                if (color2 == bmp[n4]) {
                    colorPixel2 |= 1;
                }
                n4++;
            } while (n4 < 5);
            pixelSpacing[n3] = colorPixel2;
            n3++;
        } while (n3 < 5);
        int pixelSpaceColor = pixelSpacing[0];
        if (pixelSpaceColor >= 29 || pixelSpaceColor == 23 || pixelSpaceColor == 27) {
            color = bmp[0];
        } else if (pixelSpacing[1] == 15 || (pixelSpacing[0] == 17 && pixelSpacing[1] == 14)) {
            color = bmp[1];
        } else {
            int n5 = 0;
            do {
                pixelSpacing[n5] = 0;
                n5++;
            } while (n5 < 5);
            int n6 = 0;
            do {
                int color3 = bmp[n6];
                int n7 = 0;
                int colorPixel = 0;
                do {
                    colorPixel <<= 1;
                    if (getColorBrightness(color3, bmp[n7]) != 0) {
                        colorPixel |= 1;
                    }
                    n7++;
                } while (n7 < 5);
                pixelSpacing[n6] = colorPixel;
                n6++;
            } while (n6 < 5);
            int pixelSpace = pixelSpacing[0];
            if (pixelSpace >= 29 || pixelSpace == 23 || pixelSpace == 27) {
                color = getARGB(bmp, pixelSpacing[0]);
            } else if (pixelSpacing[1] != 15 && (pixelSpacing[0] != 17 || pixelSpacing[1] != 14)) {
                return 0;
            } else {
                color = getARGB(bmp, pixelSpacing[1]);
            }
        }
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = 0.8f;
        return color;
    }

    protected static int getNavigationBarSize() {
        Point appUsableSize = getAppScreenSize();
        Point realScreenSize = getScreenSize();
        // navigation bar on the right (Landscape)
        if (appUsableSize.x < realScreenSize.x) {
            return realScreenSize.x - appUsableSize.x;
        }
        // navigation bar at the bottom (Landscape or Portrait)
        if (appUsableSize.y < realScreenSize.y) {
            return realScreenSize.y - appUsableSize.y;
        }
        // navigation bar is not present, no need Colors for navigation bar
        return 0;
    }

    protected static Point getAppScreenSize() {
        Context ctx = mContext;
        final WindowManager wm = ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE));
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        return size;
    }

    protected static Point getScreenSize() {
        Context ctx = mContext;
        final WindowManager wm = ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE));
        Display display = wm.getDefaultDisplay();
        Point realSize = new Point();
        display.getRealSize(realSize);
        return realSize;
    }

    public static Bitmap takeScreenshotSurface() {
        // Get Colors from the screenshot
        final IBinder displayToken = SurfaceControl.getInternalDisplayToken();
        Point point = getScreenSize();
        Rect crop = new Rect(0, 0, point.x, point.y);

        final SurfaceControl.DisplayCaptureArgs captureArgs =
            new SurfaceControl.DisplayCaptureArgs.Builder(displayToken)
            .setSize(crop.width(), crop.height())
            .build();
        final SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer =
            SurfaceControl.captureDisplay(captureArgs);
        final Bitmap screenShot = screenshotBuffer == null ? null : screenshotBuffer.asBitmap();
        if (screenShot == null) {
            Log.e(TAG, "Failed to get colors apply Black!!! for dynamic system bars");
            return null;
        }
        screenShot.prepareToDraw();

        return screenShot.copy(Bitmap.Config.ARGB_8888, true);
    }

}

