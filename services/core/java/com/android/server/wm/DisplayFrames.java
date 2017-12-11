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

import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static com.android.server.wm.proto.DisplayFramesProto.STABLE_BOUNDS;

import android.annotation.NonNull;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayCutout;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Container class for all the display frames that affect how we do window layout on a display.
 * @hide
 */
public class DisplayFrames {
    public final int mDisplayId;

    /**
     * The current size of the screen; really; extends into the overscan area of the screen and
     * doesn't account for any system elements like the status bar.
     */
    public final Rect mOverscan = new Rect();

    /**
     * The current visible size of the screen; really; (ir)regardless of whether the status bar can
     * be hidden but not extending into the overscan area.
     */
    public final Rect mUnrestricted = new Rect();

    /** Like mOverscan*, but allowed to move into the overscan region where appropriate. */
    public final Rect mRestrictedOverscan = new Rect();

    /**
     * The current size of the screen; these may be different than (0,0)-(dw,dh) if the status bar
     * can't be hidden; in that case it effectively carves out that area of the display from all
     * other windows.
     */
    public final Rect mRestricted = new Rect();

    /**
     * During layout, the current screen borders accounting for any currently visible system UI
     * elements.
     */
    public final Rect mSystem = new Rect();

    /** For applications requesting stable content insets, these are them. */
    public final Rect mStable = new Rect();

    /**
     * For applications requesting stable content insets but have also set the fullscreen window
     * flag, these are the stable dimensions without the status bar.
     */
    public final Rect mStableFullscreen = new Rect();

    /**
     * During layout, the current screen borders with all outer decoration (status bar, input method
     * dock) accounted for.
     */
    public final Rect mCurrent = new Rect();

    /**
     * During layout, the frame in which content should be displayed to the user, accounting for all
     * screen decoration except for any space they deem as available for other content. This is
     * usually the same as mCurrent*, but may be larger if the screen decor has supplied content
     * insets.
     */
    public final Rect mContent = new Rect();

    /**
     * During layout, the frame in which voice content should be displayed to the user, accounting
     * for all screen decoration except for any space they deem as available for other content.
     */
    public final Rect mVoiceContent = new Rect();

    /** During layout, the current screen borders along which input method windows are placed. */
    public final Rect mDock = new Rect();

    /** Definition of the cutout */
    @NonNull public DisplayCutout mDisplayCutout = DisplayCutout.NO_CUTOUT;

    /**
     * During layout, the frame that is display-cutout safe, i.e. that does not intersect with it.
     */
    public final Rect mDisplayCutoutSafe = new Rect();

    private final Rect mDisplayInfoOverscan = new Rect();
    private final Rect mRotatedDisplayInfoOverscan = new Rect();
    public int mDisplayWidth;
    public int mDisplayHeight;

    public int mRotation;

    public DisplayFrames(int displayId, DisplayInfo info) {
        mDisplayId = displayId;
        onDisplayInfoUpdated(info);
    }

    public void onDisplayInfoUpdated(DisplayInfo info) {
        mDisplayWidth = info.logicalWidth;
        mDisplayHeight = info.logicalHeight;
        mRotation = info.rotation;
        mDisplayInfoOverscan.set(
                info.overscanLeft, info.overscanTop, info.overscanRight, info.overscanBottom);
    }

    public void onBeginLayout(boolean emulateDisplayCutout, int statusBarHeight) {
        switch (mRotation) {
            case ROTATION_90:
                mRotatedDisplayInfoOverscan.left = mDisplayInfoOverscan.top;
                mRotatedDisplayInfoOverscan.top = mDisplayInfoOverscan.right;
                mRotatedDisplayInfoOverscan.right = mDisplayInfoOverscan.bottom;
                mRotatedDisplayInfoOverscan.bottom = mDisplayInfoOverscan.left;
                break;
            case ROTATION_180:
                mRotatedDisplayInfoOverscan.left = mDisplayInfoOverscan.right;
                mRotatedDisplayInfoOverscan.top = mDisplayInfoOverscan.bottom;
                mRotatedDisplayInfoOverscan.right = mDisplayInfoOverscan.left;
                mRotatedDisplayInfoOverscan.bottom = mDisplayInfoOverscan.top;
                break;
            case ROTATION_270:
                mRotatedDisplayInfoOverscan.left = mDisplayInfoOverscan.bottom;
                mRotatedDisplayInfoOverscan.top = mDisplayInfoOverscan.left;
                mRotatedDisplayInfoOverscan.right = mDisplayInfoOverscan.top;
                mRotatedDisplayInfoOverscan.bottom = mDisplayInfoOverscan.right;
                break;
            default:
                mRotatedDisplayInfoOverscan.set(mDisplayInfoOverscan);
                break;
        }

        mRestrictedOverscan.set(0, 0, mDisplayWidth, mDisplayHeight);
        mOverscan.set(mRestrictedOverscan);
        mSystem.set(mRestrictedOverscan);
        mUnrestricted.set(mRotatedDisplayInfoOverscan);
        mUnrestricted.right = mDisplayWidth - mUnrestricted.right;
        mUnrestricted.bottom = mDisplayHeight - mUnrestricted.bottom;
        mRestricted.set(mUnrestricted);
        mDock.set(mUnrestricted);
        mContent.set(mUnrestricted);
        mVoiceContent.set(mUnrestricted);
        mStable.set(mUnrestricted);
        mStableFullscreen.set(mUnrestricted);
        mCurrent.set(mUnrestricted);
        mDisplayCutout = DisplayCutout.NO_CUTOUT;
        mDisplayCutoutSafe.set(Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE);
        if (emulateDisplayCutout) {
            setEmulatedDisplayCutout((int) (statusBarHeight * 0.8));
        }
    }

    public int getInputMethodWindowVisibleHeight() {
        return mDock.bottom - mCurrent.bottom;
    }

    private void setEmulatedDisplayCutout(int height) {
        final boolean swappedDimensions = mRotation == ROTATION_90 || mRotation == ROTATION_270;

        final int screenWidth = swappedDimensions ? mDisplayHeight : mDisplayWidth;
        final int screenHeight = swappedDimensions ? mDisplayWidth : mDisplayHeight;

        final int widthTop = (int) (screenWidth * 0.3);
        final int widthBottom = widthTop - height;

        switch (mRotation) {
            case ROTATION_90:
                mDisplayCutout = DisplayCutout.fromBoundingPolygon(Arrays.asList(
                        new Point(0, (screenWidth - widthTop) / 2),
                        new Point(height, (screenWidth - widthBottom) / 2),
                        new Point(height, (screenWidth + widthBottom) / 2),
                        new Point(0, (screenWidth + widthTop) / 2)
                )).calculateRelativeTo(mUnrestricted);
                mDisplayCutoutSafe.left = height;
                break;
            case ROTATION_180:
                mDisplayCutout = DisplayCutout.fromBoundingPolygon(Arrays.asList(
                        new Point((screenWidth - widthTop) / 2, screenHeight),
                        new Point((screenWidth - widthBottom) / 2, screenHeight - height),
                        new Point((screenWidth + widthBottom) / 2, screenHeight - height),
                        new Point((screenWidth + widthTop) / 2, screenHeight)
                )).calculateRelativeTo(mUnrestricted);
                mDisplayCutoutSafe.bottom = screenHeight - height;
                break;
            case ROTATION_270:
                mDisplayCutout = DisplayCutout.fromBoundingPolygon(Arrays.asList(
                        new Point(screenHeight, (screenWidth - widthTop) / 2),
                        new Point(screenHeight - height, (screenWidth - widthBottom) / 2),
                        new Point(screenHeight - height, (screenWidth + widthBottom) / 2),
                        new Point(screenHeight, (screenWidth + widthTop) / 2)
                )).calculateRelativeTo(mUnrestricted);
                mDisplayCutoutSafe.right = screenHeight - height;
                break;
            default:
                mDisplayCutout = DisplayCutout.fromBoundingPolygon(Arrays.asList(
                        new Point((screenWidth - widthTop) / 2, 0),
                        new Point((screenWidth - widthBottom) / 2, height),
                        new Point((screenWidth + widthBottom) / 2, height),
                        new Point((screenWidth + widthTop) / 2, 0)
                )).calculateRelativeTo(mUnrestricted);
                mDisplayCutoutSafe.top = height;
                break;
        }
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        mStable.writeToProto(proto, STABLE_BOUNDS);
        proto.end(token);
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "DisplayFrames w=" + mDisplayWidth + " h=" + mDisplayHeight
                + " r=" + mRotation);
        final String myPrefix = prefix + "  ";
        dumpFrame(mStable, "mStable", myPrefix, pw);
        dumpFrame(mStableFullscreen, "mStableFullscreen", myPrefix, pw);
        dumpFrame(mDock, "mDock", myPrefix, pw);
        dumpFrame(mCurrent, "mCurrent", myPrefix, pw);
        dumpFrame(mSystem, "mSystem", myPrefix, pw);
        dumpFrame(mContent, "mContent", myPrefix, pw);
        dumpFrame(mVoiceContent, "mVoiceContent", myPrefix, pw);
        dumpFrame(mOverscan, "mOverscan", myPrefix, pw);
        dumpFrame(mRestrictedOverscan, "mRestrictedOverscan", myPrefix, pw);
        dumpFrame(mRestricted, "mRestricted", myPrefix, pw);
        dumpFrame(mUnrestricted, "mUnrestricted", myPrefix, pw);
        dumpFrame(mDisplayInfoOverscan, "mDisplayInfoOverscan", myPrefix, pw);
        dumpFrame(mRotatedDisplayInfoOverscan, "mRotatedDisplayInfoOverscan", myPrefix, pw);
        pw.println(myPrefix + "mDisplayCutout=" + mDisplayCutout);
    }

    private void dumpFrame(Rect frame, String name, String prefix, PrintWriter pw) {
        pw.print(prefix + name + "="); frame.printShortString(pw); pw.println();
    }
}
