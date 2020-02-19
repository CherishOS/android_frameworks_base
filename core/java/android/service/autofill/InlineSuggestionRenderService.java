/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.service.autofill;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Service;
import android.app.slice.Slice;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;

/**
 * A service that renders an inline presentation given the {@link InlinePresentation} containing
 * a {@link Slice} built using the {@link androidx.autofill.AutofillSliceBuilder}.
 *
 * {@hide}
 */
@SystemApi
@TestApi
public abstract class InlineSuggestionRenderService extends Service {

    private static final String TAG = "InlineSuggestionRenderService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     *
     * <p>To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_INLINE_SUGGESTION_RENDER_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.autofill.InlineSuggestionRenderService";

    private final Handler mHandler = new Handler(Looper.getMainLooper(), null, true);

    private void handleRenderSuggestion(IInlineSuggestionUiCallback callback,
            InlinePresentation presentation, int width, int height, IBinder hostInputToken) {
        if (hostInputToken == null) {
            try {
                callback.onError();
            } catch (RemoteException e) {
                Log.w(TAG, "RemoteException calling onError()");
            }
            return;
        }
        final SurfaceControlViewHost host = new SurfaceControlViewHost(this, this.getDisplay(),
                hostInputToken);
        final SurfaceControl surface = host.getSurfacePackage().getSurfaceControl();

        final View suggestionView = onRenderSuggestion(presentation, width, height);

        final InlineSuggestionRoot suggestionRoot = new InlineSuggestionRoot(this, callback);
        suggestionRoot.addView(suggestionView);
        suggestionRoot.setOnClickListener((v) -> {
            try {
                callback.onAutofill();
            } catch (RemoteException e) {
                Log.w(TAG, "RemoteException calling onAutofill()");
            }
        });

        WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION, 0, PixelFormat.TRANSPARENT);
        host.addView(suggestionRoot, lp);
        try {
            callback.onContent(surface);
        } catch (RemoteException e) {
            Log.w(TAG, "RemoteException calling onContent(" + surface + ")");
        }
    }

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return new IInlineSuggestionRenderService.Stub() {
                @Override
                public void renderSuggestion(@NonNull IInlineSuggestionUiCallback callback,
                        @NonNull InlinePresentation presentation, int width, int height,
                        @Nullable IBinder hostInputToken) {
                    mHandler.sendMessage(obtainMessage(
                            InlineSuggestionRenderService::handleRenderSuggestion,
                            InlineSuggestionRenderService.this, callback, presentation,
                            width, height, hostInputToken));
                }
            }.asBinder();
        }

        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    /**
     *  Returns the metadata about the renderer. Returns {@code null} if no metadata is provided.
     */
    @Nullable
    public Bundle onGetInlineSuggestionsRendererInfo() {
        return null;
    }

    /**
     * Renders the slice into a view.
     */
    @Nullable
    public View onRenderSuggestion(@NonNull InlinePresentation presentation,
            int width, int height) {
        Log.e(TAG, "service implementation (" + getClass() + " does not implement "
                + "onRenderSuggestion()");
        return null;
    }
}
