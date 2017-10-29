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

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DRAG;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.content.ClipData;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.util.Slog;
import android.view.Display;
import android.view.IWindow;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import com.android.server.wm.WindowManagerService.H;

/**
 * Managing drag and drop operations initiated by View#startDragAndDrop.
 */
class DragDropController {
    private static final float DRAG_SHADOW_ALPHA_TRANSPARENT = .7071f;
    private static final long DRAG_TIMEOUT_MS = 5000;
    DragState mDragState;

    boolean dragDropActiveLocked() {
        return mDragState != null;
    }

    IBinder prepareDrag(WindowManagerService service, SurfaceSession session, int callerPid,
            int callerUid, IWindow window, int flags, int width, int height, Surface outSurface) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "prepare drag surface: w=" + width + " h=" + height
                    + " flags=" + Integer.toHexString(flags) + " win=" + window
                    + " asbinder=" + window.asBinder());
        }

        IBinder token = null;

        synchronized (service.mWindowMap) {
            if (dragDropActiveLocked()) {
                Slog.w(TAG_WM, "Drag already in progress");
                return null;
            }

            // TODO(multi-display): support other displays
            final DisplayContent displayContent =
                    service.getDefaultDisplayContentLocked();
            final Display display = displayContent.getDisplay();

            final SurfaceControl surface = new SurfaceControl.Builder(session)
                    .setName("drag surface")
                    .setSize(width, height)
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .build();
            surface.setLayerStack(display.getLayerStack());
            float alpha = 1;
            if ((flags & View.DRAG_FLAG_OPAQUE) == 0) {
                alpha = DRAG_SHADOW_ALPHA_TRANSPARENT;
            }
            surface.setAlpha(alpha);

            if (SHOW_TRANSACTIONS) Slog.i(TAG_WM, "  DRAG " + surface + ": CREATE");
            outSurface.copyFrom(surface);
            final IBinder winBinder = window.asBinder();
            token = new Binder();
            mDragState = new DragState(service, token, surface, flags, winBinder);
            mDragState.mPid = callerPid;
            mDragState.mUid = callerUid;
            mDragState.mOriginalAlpha = alpha;
            token = mDragState.mToken = new Binder();

            // 5 second timeout for this window to actually begin the drag
            service.mH.removeMessages(H.DRAG_START_TIMEOUT, winBinder);
            Message msg = service.mH.obtainMessage(H.DRAG_START_TIMEOUT, winBinder);
            service.mH.sendMessageDelayed(msg, DRAG_TIMEOUT_MS);
        }

        return token;
    }

    boolean performDrag(WindowManagerService service, IWindow window, IBinder dragToken,
            int touchSource, float touchX, float touchY, float thumbCenterX, float thumbCenterY,
            ClipData data) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "perform drag: win=" + window + " data=" + data);
        }

        synchronized (service.mWindowMap) {
            if (mDragState == null) {
                Slog.w(TAG_WM, "No drag prepared");
                throw new IllegalStateException("performDrag() without prepareDrag()");
            }

            if (dragToken != mDragState.mToken) {
                Slog.w(TAG_WM, "Performing mismatched drag");
                throw new IllegalStateException("performDrag() does not match prepareDrag()");
            }

            final WindowState callingWin = service.windowForClientLocked(null, window, false);
            if (callingWin == null) {
                Slog.w(TAG_WM, "Bad requesting window " + window);
                return false;  // !!! TODO: throw here?
            }

            // !!! TODO: if input is not still focused on the initiating window, fail
            // the drag initiation (e.g. an alarm window popped up just as the application
            // called performDrag()

            service.mH.removeMessages(H.DRAG_START_TIMEOUT, window.asBinder());

            // !!! TODO: extract the current touch (x, y) in screen coordinates.  That
            // will let us eliminate the (touchX,touchY) parameters from the API.

            // !!! FIXME: put all this heavy stuff onto the mH looper, as well as
            // the actual drag event dispatch stuff in the dragstate

            final DisplayContent displayContent = callingWin.getDisplayContent();
            if (displayContent == null) {
                return false;
            }
            Display display = displayContent.getDisplay();
            mDragState.register(display);
            if (!service.mInputManager.transferTouchFocus(callingWin.mInputChannel,
                    mDragState.getInputChannel())) {
                Slog.e(TAG_WM, "Unable to transfer touch focus");
                mDragState.unregister();
                mDragState.reset();
                mDragState = null;
                return false;
            }

            mDragState.mDisplayContent = displayContent;
            mDragState.mData = data;
            mDragState.broadcastDragStartedLw(touchX, touchY);
            mDragState.overridePointerIconLw(touchSource);

            // remember the thumb offsets for later
            mDragState.mThumbOffsetX = thumbCenterX;
            mDragState.mThumbOffsetY = thumbCenterY;

            // Make the surface visible at the proper location
            final SurfaceControl surfaceControl = mDragState.mSurfaceControl;
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(
                    TAG_WM, ">>> OPEN TRANSACTION performDrag");
            service.openSurfaceTransaction();
            try {
                surfaceControl.setPosition(touchX - thumbCenterX,
                        touchY - thumbCenterY);
                surfaceControl.setLayer(mDragState.getDragLayerLw());
                surfaceControl.setLayerStack(display.getLayerStack());
                surfaceControl.show();
            } finally {
                service.closeSurfaceTransaction();
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(
                        TAG_WM, "<<< CLOSE TRANSACTION performDrag");
            }

            mDragState.notifyLocationLw(touchX, touchY);
        }

        return true;    // success!
    }

    void reportDropResult(WindowManagerService service, IWindow window, boolean consumed) {
        IBinder token = window.asBinder();
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "Drop result=" + consumed + " reported by " + token);
        }

        synchronized (service.mWindowMap) {
            if (mDragState == null) {
                // Most likely the drop recipient ANRed and we ended the drag
                // out from under it.  Log the issue and move on.
                Slog.w(TAG_WM, "Drop result given but no drag in progress");
                return;
            }

            if (mDragState.mToken != token) {
                // We're in a drag, but the wrong window has responded.
                Slog.w(TAG_WM, "Invalid drop-result claim by " + window);
                throw new IllegalStateException("reportDropResult() by non-recipient");
            }

            // The right window has responded, even if it's no longer around,
            // so be sure to halt the timeout even if the later WindowState
            // lookup fails.
            service.mH.removeMessages(H.DRAG_END_TIMEOUT, window.asBinder());
            WindowState callingWin = service.windowForClientLocked(null, window, false);
            if (callingWin == null) {
                Slog.w(TAG_WM, "Bad result-reporting window " + window);
                return;  // !!! TODO: throw here?
            }

            mDragState.mDragResult = consumed;
            mDragState.endDragLw();
        }
    }

    void cancelDragAndDrop(WindowManagerService service, IBinder dragToken) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "cancelDragAndDrop");
        }

        synchronized (service.mWindowMap) {
            if (mDragState == null) {
                Slog.w(TAG_WM, "cancelDragAndDrop() without prepareDrag()");
                throw new IllegalStateException("cancelDragAndDrop() without prepareDrag()");
            }

            if (mDragState.mToken != dragToken) {
                Slog.w(TAG_WM,
                        "cancelDragAndDrop() does not match prepareDrag()");
                throw new IllegalStateException(
                        "cancelDragAndDrop() does not match prepareDrag()");
            }

            mDragState.mDragResult = false;
            mDragState.cancelDragLw();
        }
    }

    void dragRecipientEntered(IWindow window) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "Drag into new candidate view @ " + window.asBinder());
        }
    }

    void dragRecipientExited(IWindow window) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "Drag from old candidate view @ " + window.asBinder());
        }
    }

    void handleMessage(WindowManagerService service, Message msg) {
        switch (msg.what) {
            case H.DRAG_START_TIMEOUT: {
                IBinder win = (IBinder) msg.obj;
                if (DEBUG_DRAG) {
                    Slog.w(TAG_WM, "Timeout starting drag by win " + win);
                }
                synchronized (service.mWindowMap) {
                    // !!! TODO: ANR the app that has failed to start the drag in time
                    if (mDragState != null) {
                        mDragState.unregister();
                        mDragState.reset();
                        mDragState = null;
                    }
                }
                break;
            }

            case H.DRAG_END_TIMEOUT: {
                IBinder win = (IBinder) msg.obj;
                if (DEBUG_DRAG) {
                    Slog.w(TAG_WM, "Timeout ending drag to win " + win);
                }
                synchronized (service.mWindowMap) {
                    // !!! TODO: ANR the drag-receiving app
                    if (mDragState != null) {
                        mDragState.mDragResult = false;
                        mDragState.endDragLw();
                    }
                }
                break;
            }

            case H.TEAR_DOWN_DRAG_AND_DROP_INPUT: {
                if (DEBUG_DRAG)
                    Slog.d(TAG_WM, "Drag ending; tearing down input channel");
                DragState.InputInterceptor interceptor = (DragState.InputInterceptor) msg.obj;
                if (interceptor != null) {
                    synchronized (service.mWindowMap) {
                        interceptor.tearDown();
                    }
                }
                break;
            }
        }
    }
}
