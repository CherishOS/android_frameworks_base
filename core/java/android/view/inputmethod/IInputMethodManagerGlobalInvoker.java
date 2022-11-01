/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.view.inputmethod;

import android.Manifest;
import android.annotation.AnyThread;
import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.view.WindowManager;
import android.window.ImeOnBackInvokedDispatcher;

import com.android.internal.inputmethod.DirectBootAwareness;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.internal.view.IInputMethodManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A global wrapper to directly invoke {@link IInputMethodManager} IPCs.
 *
 * <p>All public static methods are guaranteed to be thread-safe.</p>
 *
 * <p>All public methods are guaranteed to do nothing when {@link IInputMethodManager} is
 * unavailable.</p>
 *
 * <p>If you want to use any of this method outside of {@code android.view.inputmethod}, create
 * a wrapper method in {@link InputMethodManagerGlobal} instead of making this class public.</p>
 */
final class IInputMethodManagerGlobalInvoker {
    @Nullable
    private static volatile IInputMethodManager sServiceCache = null;

    /**
     * @return {@code true} if {@link IInputMethodManager} is available.
     */
    @AnyThread
    static boolean isAvailable() {
        return getService() != null;
    }

    @AnyThread
    @Nullable
    static IInputMethodManager getService() {
        IInputMethodManager service = sServiceCache;
        if (service == null) {
            if (InputMethodManager.isInEditModeInternal()) {
                return null;
            }
            service = IInputMethodManager.Stub.asInterface(
                    ServiceManager.getService(Context.INPUT_METHOD_SERVICE));
            if (service == null) {
                return null;
            }
            sServiceCache = service;
        }
        return service;
    }

    @AnyThread
    private static void handleRemoteExceptionOrRethrow(@NonNull RemoteException e,
            @Nullable Consumer<RemoteException> exceptionHandler) {
        if (exceptionHandler != null) {
            exceptionHandler.accept(e);
        } else {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Invokes {@link IInputMethodManager#startProtoDump(byte[], int, String)}.
     *
     * @param protoDump client or service side information to be stored by the server
     * @param source where the information is coming from, refer to
     *               {@link com.android.internal.inputmethod.ImeTracing#IME_TRACING_FROM_CLIENT} and
     *               {@link com.android.internal.inputmethod.ImeTracing#IME_TRACING_FROM_IMS}
     * @param where where the information is coming from.
     * @param exceptionHandler an optional {@link RemoteException} handler.
     */
    @RequiresNoPermission
    @AnyThread
    static void startProtoDump(byte[] protoDump, int source, String where,
            @Nullable Consumer<RemoteException> exceptionHandler) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.startProtoDump(protoDump, source, where);
        } catch (RemoteException e) {
            handleRemoteExceptionOrRethrow(e, exceptionHandler);
        }
    }

    /**
     * Invokes {@link IInputMethodManager#startImeTrace()}.
     *
     * @param exceptionHandler an optional {@link RemoteException} handler.
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_UI_TRACING)
    @AnyThread
    static void startImeTrace(@Nullable Consumer<RemoteException> exceptionHandler) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.startImeTrace();
        } catch (RemoteException e) {
            handleRemoteExceptionOrRethrow(e, exceptionHandler);
        }
    }

    /**
     * Invokes {@link IInputMethodManager#stopImeTrace()}.
     *
     * @param exceptionHandler an optional {@link RemoteException} handler.
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_UI_TRACING)
    @AnyThread
    static void stopImeTrace(@Nullable Consumer<RemoteException> exceptionHandler) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.stopImeTrace();
        } catch (RemoteException e) {
            handleRemoteExceptionOrRethrow(e, exceptionHandler);
        }
    }

    /**
     * Invokes {@link IInputMethodManager#isImeTraceEnabled()}.
     *
     * @return The return value of {@link IInputMethodManager#isImeTraceEnabled()}.
     */
    @RequiresNoPermission
    @AnyThread
    static boolean isImeTraceEnabled() {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.isImeTraceEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Invokes {@link IInputMethodManager#removeImeSurface()}
     */
    @RequiresPermission(android.Manifest.permission.INTERNAL_SYSTEM_WINDOW)
    @AnyThread
    static void removeImeSurface(@Nullable Consumer<RemoteException> exceptionHandler) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.removeImeSurface();
        } catch (RemoteException e) {
            handleRemoteExceptionOrRethrow(e, exceptionHandler);
        }
    }

    @AnyThread
    static void addClient(@NonNull IInputMethodClient client,
            @NonNull IRemoteInputConnection fallbackInputConnection, int untrustedDisplayId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.addClient(client, fallbackInputConnection, untrustedDisplayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @NonNull
    static List<InputMethodInfo> getInputMethodList(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return new ArrayList<>();
        }
        try {
            return service.getInputMethodList(userId, directBootAwareness);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @NonNull
    static List<InputMethodInfo> getEnabledInputMethodList(@UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return new ArrayList<>();
        }
        try {
            return service.getEnabledInputMethodList(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @NonNull
    static List<InputMethodSubtype> getEnabledInputMethodSubtypeList(@Nullable String imiId,
            boolean allowsImplicitlyEnabledSubtypes, @UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return new ArrayList<>();
        }
        try {
            return service.getEnabledInputMethodSubtypeList(imiId,
                    allowsImplicitlyEnabledSubtypes, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @Nullable
    static InputMethodSubtype getLastInputMethodSubtype(@UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return null;
        }
        try {
            return service.getLastInputMethodSubtype(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static boolean showSoftInput(@NonNull IInputMethodClient client, @Nullable IBinder windowToken,
            int flags, int lastClickToolType, @Nullable ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.showSoftInput(
                    client, windowToken, flags, lastClickToolType, resultReceiver, reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static boolean hideSoftInput(@NonNull IInputMethodClient client, @Nullable IBinder windowToken,
            int flags, @Nullable ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.hideSoftInput(client, windowToken, flags, resultReceiver, reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @NonNull
    static InputBindResult startInputOrWindowGainedFocus(@StartInputReason int startInputReason,
            @NonNull IInputMethodClient client, @Nullable IBinder windowToken,
            @StartInputFlags int startInputFlags,
            @WindowManager.LayoutParams.SoftInputModeFlags int softInputMode,
            @WindowManager.LayoutParams.Flags int windowFlags, @Nullable EditorInfo editorInfo,
            @Nullable IRemoteInputConnection remoteInputConnection,
            @Nullable IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, @UserIdInt int userId,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return InputBindResult.NULL;
        }
        try {
            return service.startInputOrWindowGainedFocus(startInputReason, client, windowToken,
                    startInputFlags, softInputMode, windowFlags, editorInfo, remoteInputConnection,
                    remoteAccessibilityInputConnection, unverifiedTargetSdkVersion, userId,
                    imeDispatcher);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static void showInputMethodPickerFromClient(@NonNull IInputMethodClient client,
            int auxiliarySubtypeMode) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.showInputMethodPickerFromClient(client, auxiliarySubtypeMode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static void showInputMethodPickerFromSystem(@NonNull IInputMethodClient client,
            int auxiliarySubtypeMode, int displayId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.showInputMethodPickerFromSystem(client, auxiliarySubtypeMode, displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @RequiresPermission(Manifest.permission.TEST_INPUT_METHOD)
    static boolean isInputMethodPickerShownForTest() {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.isInputMethodPickerShownForTest();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @Nullable
    static InputMethodSubtype getCurrentInputMethodSubtype(@UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return null;
        }
        try {
            return service.getCurrentInputMethodSubtype(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static void setAdditionalInputMethodSubtypes(@NonNull String imeId,
            @NonNull InputMethodSubtype[] subtypes, @UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.setAdditionalInputMethodSubtypes(imeId, subtypes, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static void setExplicitlyEnabledInputMethodSubtypes(@NonNull String imeId,
            @NonNull int[] subtypeHashCodes, @UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.setExplicitlyEnabledInputMethodSubtypes(imeId, subtypeHashCodes, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static int getInputMethodWindowVisibleHeight(@NonNull IInputMethodClient client) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return 0;
        }
        try {
            return service.getInputMethodWindowVisibleHeight(client);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static void reportVirtualDisplayGeometryAsync(@NonNull IInputMethodClient client,
            int childDisplayId, @Nullable float[] matrixValues) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.reportVirtualDisplayGeometryAsync(client, childDisplayId, matrixValues);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static void reportPerceptibleAsync(@NonNull IBinder windowToken, boolean perceptible) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.reportPerceptibleAsync(windowToken, perceptible);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static void removeImeSurfaceFromWindowAsync(@NonNull IBinder windowToken) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.removeImeSurfaceFromWindowAsync(windowToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static void startStylusHandwriting(@NonNull IInputMethodClient client) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.startStylusHandwriting(client);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static boolean isStylusHandwritingAvailableAsUser(@UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.isStylusHandwritingAvailableAsUser(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static void addVirtualStylusIdForTestSession(IInputMethodClient client) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.addVirtualStylusIdForTestSession(client);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static void setStylusWindowIdleTimeoutForTest(
            IInputMethodClient client, @DurationMillisLong long timeout) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.setStylusWindowIdleTimeoutForTest(client, timeout);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
