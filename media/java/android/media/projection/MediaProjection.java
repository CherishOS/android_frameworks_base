/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.projection;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.Log;
import android.view.ContentRecordingSession;
import android.view.Surface;

import java.util.Map;

/**
 * A token granting applications the ability to capture screen contents and/or
 * record system audio. The exact capabilities granted depend on the type of
 * MediaProjection.
 *
 * <p>
 * A screen capture session can be started through {@link
 * MediaProjectionManager#createScreenCaptureIntent}. This grants the ability to
 * capture screen contents, but not system audio.
 * </p>
 */
public final class MediaProjection {
    private static final String TAG = "MediaProjection";

    private final IMediaProjection mImpl;
    private final Context mContext;
    private final Map<Callback, CallbackRecord> mCallbacks;
    @Nullable private IMediaProjectionManager mProjectionService = null;

    /** @hide */
    public MediaProjection(Context context, IMediaProjection impl) {
        mCallbacks = new ArrayMap<Callback, CallbackRecord>();
        mContext = context;
        mImpl = impl;
        try {
            mImpl.start(new MediaProjectionCallback());
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to start media projection", e);
        }
    }

    /**
     * Register a listener to receive notifications about when the {@link MediaProjection} or
     * captured content changes state.
     * <p>
     * The callback should be registered before invoking
     * {@link #createVirtualDisplay(String, int, int, int, int, Surface, VirtualDisplay.Callback,
     * Handler)}
     * to ensure that any notifications on the callback are not missed.
     * </p>
     *
     * @param callback The callback to call.
     * @param handler  The handler on which the callback should be invoked, or
     *                 null if the callback should be invoked on the calling thread's looper.
     * @throws IllegalArgumentException If the given callback is null.
     * @see #unregisterCallback
     */
    public void registerCallback(Callback callback, Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("callback should not be null");
        }
        if (handler == null) {
            handler = new Handler();
        }
        mCallbacks.put(callback, new CallbackRecord(callback, handler));
    }

    /**
     * Unregister a {@link MediaProjection} listener.
     *
     * @param callback The callback to unregister.
     * @throws IllegalArgumentException If the given callback is null.
     * @see #registerCallback
     */
    public void unregisterCallback(Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback should not be null");
        }
        mCallbacks.remove(callback);
    }

    /**
     * @hide
     */
    public VirtualDisplay createVirtualDisplay(@NonNull String name,
            int width, int height, int dpi, boolean isSecure, @Nullable Surface surface,
            @Nullable VirtualDisplay.Callback callback, @Nullable Handler handler) {
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
        if (isSecure) {
            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
        }
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(name, width,
                height, dpi).setFlags(flags);
        if (surface != null) {
            builder.setSurface(surface);
        }
        VirtualDisplay virtualDisplay = createVirtualDisplay(builder, callback, handler);
        return virtualDisplay;
    }

    /**
     * Creates a {@link android.hardware.display.VirtualDisplay} to capture the
     * contents of the screen.
     *
     * @param name The name of the virtual display, must be non-empty.
     * @param width The width of the virtual display in pixels. Must be
     * greater than 0.
     * @param height The height of the virtual display in pixels. Must be
     * greater than 0.
     * @param dpi The density of the virtual display in dpi. Must be greater
     * than 0.
     * @param surface The surface to which the content of the virtual display
     * should be rendered, or null if there is none initially.
     * @param flags A combination of virtual display flags. See {@link DisplayManager} for the full
     * list of flags.
     * @param callback Callback to call when the virtual display's state
     * changes, or null if none.
     * @param handler The {@link android.os.Handler} on which the callback should be
     * invoked, or null if the callback should be invoked on the calling
     * thread's main {@link android.os.Looper}.
     *
     * @see android.hardware.display.VirtualDisplay
     */
    public VirtualDisplay createVirtualDisplay(@NonNull String name,
            int width, int height, int dpi, int flags, @Nullable Surface surface,
            @Nullable VirtualDisplay.Callback callback, @Nullable Handler handler) {
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(name, width,
                height, dpi).setFlags(flags);
        if (surface != null) {
            builder.setSurface(surface);
        }
        VirtualDisplay virtualDisplay = createVirtualDisplay(builder, callback, handler);
        return virtualDisplay;
    }

    /**
     * Creates a {@link android.hardware.display.VirtualDisplay} to capture the
     * contents of the screen.
     *
     * @param virtualDisplayConfig The arguments for the virtual display configuration. See
     * {@link VirtualDisplayConfig} for using it.
     * @param callback Callback to call when the virtual display's state changes, or null if none.
     * @param handler The {@link android.os.Handler} on which the callback should be invoked, or
     *                null if the callback should be invoked on the calling thread's main
     *                {@link android.os.Looper}.
     *
     * @see android.hardware.display.VirtualDisplay
     * @hide
     */
    @Nullable
    public VirtualDisplay createVirtualDisplay(
            @NonNull VirtualDisplayConfig.Builder virtualDisplayConfig,
            @Nullable VirtualDisplay.Callback callback, @Nullable Handler handler) {
        try {
            final IBinder launchCookie = mImpl.getLaunchCookie();
            Context windowContext = null;
            ContentRecordingSession session;
            if (launchCookie == null) {
                windowContext = mContext.createWindowContext(mContext.getDisplayNoVerify(),
                        TYPE_APPLICATION, null /* options */);
                session = ContentRecordingSession.createDisplaySession(
                        windowContext.getWindowContextToken());
            } else {
                session = ContentRecordingSession.createTaskSession(launchCookie);
            }
            // Pass in the current session details, so they are guaranteed to only be set in WMS
            // AFTER a VirtualDisplay is constructed (assuming there are no errors during set-up).
            virtualDisplayConfig.setContentRecordingSession(session);
            virtualDisplayConfig.setWindowManagerMirroringEnabled(true);
            final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            final VirtualDisplay virtualDisplay = dm.createVirtualDisplay(this,
                    virtualDisplayConfig.build(), callback, handler, windowContext);
            return virtualDisplay;
        } catch (RemoteException e) {
            // Can not capture if WMS is not accessible, so bail out.
            throw e.rethrowFromSystemServer();
        }
    }

    private IMediaProjectionManager getProjectionService() {
        if (mProjectionService == null) {
            mProjectionService = IMediaProjectionManager.Stub.asInterface(
                    ServiceManager.getService(Context.MEDIA_PROJECTION_SERVICE));
        }
        return mProjectionService;
    }

    /**
     * Stops projection.
     */
    public void stop() {
        try {
            mImpl.stop();
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to stop projection", e);
        }
    }

    /**
     * Get the underlying IMediaProjection.
     * @hide
     */
    public IMediaProjection getProjection() {
        return mImpl;
    }

    /**
     * Callbacks for the projection session.
     */
    public abstract static class Callback {
        /**
         * Called when the MediaProjection session is no longer valid.
         * <p>
         * Once a MediaProjection has been stopped, it's up to the application to release any
         * resources it may be holding (e.g. {@link android.hardware.display.VirtualDisplay}s).
         * </p>
         */
        public void onStop() { }

        /**
         * Invoked immediately after capture begins or when the size of the captured region changes,
         * providing the accurate sizing for the streamed capture.
         * <p>
         * The given width and height, in pixels, corresponds to the same width and height that
         * would be returned from {@link android.view.WindowMetrics#getBounds()} of the captured
         * region.
         * </p>
         * <p>
         * If the recorded content has a different aspect ratio from either the
         * {@link VirtualDisplay} or output {@link Surface}, the captured stream has letterboxing
         * (black bars) around the recorded content. The application can avoid the letterboxing
         * around the recorded content by updating the size of both the {@link VirtualDisplay} and
         * output {@link Surface}:
         * </p>
         *
         * <pre>
         * &#x40;Override
         * public String onCapturedContentResize(int width, int height) {
         *     // VirtualDisplay instance from MediaProjection#createVirtualDisplay
         *     virtualDisplay.resize(width, height, dpi);
         *
         *     // Create a new Surface with the updated size (depending on the application's use
         *     // case, this may be through different APIs - see Surface documentation for
         *     // options).
         *     int texName; // the OpenGL texture object name
         *     SurfaceTexture surfaceTexture = new SurfaceTexture(texName);
         *     surfaceTexture.setDefaultBufferSize(width, height);
         *     Surface surface = new Surface(surfaceTexture);
         *
         *     // Ensure the VirtualDisplay has the updated Surface to send the capture to.
         *     virtualDisplay.setSurface(surface);
         * }</pre>
         */
        public void onCapturedContentResize(int width, int height) { }

        /**
         * Invoked immediately after capture begins or when the visibility of the captured region
         * changes, providing the current visibility of the captured region.
         * <p>
         * Applications can take advantage of this callback by showing or hiding the captured
         * content from the output {@link Surface}, based on if the captured region is currently
         * visible to the user.
         * </p>
         * <p>
         * For example, if the user elected to capture a single app (from the activity shown from
         * {@link MediaProjectionManager#createScreenCaptureIntent()}), the following scenarios
         * trigger the callback:
         * <ul>
         *     <li>
         *         The captured region is visible ({@code isVisible} with value {@code true}),
         *         because the captured app is at least partially visible. This may happen if the
         *         user moves the covering app to show at least some portion of the captured app
         *         (e.g. the user has multiple apps visible in a multi-window mode such as split
         *         screen).
         *     </li>
         *     <li>
         *         The captured region is invisible ({@code isVisible} with value {@code false}) if
         *         it is entirely hidden. This may happen if another app entirely covers the
         *         captured app, or the user navigates away from the captured app.
         *     </li>
         * </ul>
         * </p>
         */
        public void onCapturedContentVisibilityChanged(boolean isVisible) { }
    }

    private final class MediaProjectionCallback extends IMediaProjectionCallback.Stub {
        @Override
        public void onStop() {
            for (CallbackRecord cbr : mCallbacks.values()) {
                cbr.onStop();
            }
        }

        @Override
        public void onCapturedContentResize(int width, int height) {
            for (CallbackRecord cbr : mCallbacks.values()) {
                cbr.onCapturedContentResize(width, height);
            }
        }

        @Override
        public void onCapturedContentVisibilityChanged(boolean isVisible) {
            for (CallbackRecord cbr : mCallbacks.values()) {
                cbr.onCapturedContentVisibilityChanged(isVisible);
            }
        }
    }

    private final static class CallbackRecord {
        private final Callback mCallback;
        private final Handler mHandler;

        public CallbackRecord(Callback callback, Handler handler) {
            mCallback = callback;
            mHandler = handler;
        }

        public void onStop() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onStop();
                }
            });
        }

        public void onCapturedContentResize(int width, int height) {
            mHandler.post(() -> mCallback.onCapturedContentResize(width, height));
        }

        public void onCapturedContentVisibilityChanged(boolean isVisible) {
            mHandler.post(() -> mCallback.onCapturedContentVisibilityChanged(isVisible));
        }
    }
}
