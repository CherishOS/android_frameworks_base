/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.inputmethodservice;

import android.annotation.AnyThread;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionInspector;
import android.view.inputmethod.InputConnectionInspector.MissingMethodFlags;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.SurroundingText;

import com.android.internal.inputmethod.CancellationGroup;
import com.android.internal.inputmethod.Completable;
import com.android.internal.inputmethod.IInputContextInvoker;
import com.android.internal.inputmethod.ImeTracing;
import com.android.internal.inputmethod.InputConnectionProtoDumper;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethod;

import java.lang.ref.WeakReference;

/**
 * Takes care of remote method invocations of {@link InputConnection} in the IME side.
 *
 * <p>This class works as a proxy to forward API calls on {@link InputConnection} to
 * {@link com.android.internal.inputmethod.RemoteInputConnectionImpl} running on the IME client
 * (editor app) process then waits replies as needed.</p>
 *
 * <p>See also {@link IInputContext} for the actual {@link android.os.Binder} IPC protocols under
 * the hood.</p>
 */
final class RemoteInputConnection implements InputConnection {
    private static final String TAG = "RemoteInputConnection";

    private static final int MAX_WAIT_TIME_MILLIS = 2000;

    @NonNull
    private final IInputContextInvoker mInvoker;

    @NonNull
    private final WeakReference<InputMethodServiceInternal> mInputMethodService;

    @MissingMethodFlags
    private final int mMissingMethods;

    /**
     * Signaled when the system decided to take away IME focus from the target app.
     *
     * <p>This is expected to be signaled immediately when the IME process receives
     * {@link IInputMethod#unbindInput()}.</p>
     */
    @NonNull
    private final CancellationGroup mCancellationGroup;

    RemoteInputConnection(
            @NonNull WeakReference<InputMethodServiceInternal> inputMethodService,
            IInputContext inputContext, @MissingMethodFlags int missingMethods,
            @NonNull CancellationGroup cancellationGroup) {
        mInputMethodService = inputMethodService;
        mInvoker = IInputContextInvoker.create(inputContext);
        mMissingMethods = missingMethods;
        mCancellationGroup = cancellationGroup;
    }

    /**
     * See {@link InputConnection#getTextAfterCursor(int, int)}.
     */
    @Nullable
    @AnyThread
    public CharSequence getTextAfterCursor(@IntRange(from = 0) int length, int flags) {
        if (length < 0 || mCancellationGroup.isCanceled()) {
            return null;
        }

        final Completable.CharSequence value = mInvoker.getTextAfterCursor(length, flags);
        final CharSequence result = Completable.getResultOrNull(
                value, TAG, "getTextAfterCursor()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);

        final InputMethodServiceInternal inputMethodService = mInputMethodService.get();
        if (inputMethodService != null && ImeTracing.getInstance().isEnabled()) {
            final byte[] icProto = InputConnectionProtoDumper.buildGetTextAfterCursorProto(length,
                    flags, result);
            inputMethodService.triggerServiceDump(TAG + "#getTextAfterCursor", icProto);
        }

        return result;
    }

    /**
     * See {@link InputConnection#getTextBeforeCursor(int, int)}.
     */
    @Nullable
    @AnyThread
    public CharSequence getTextBeforeCursor(@IntRange(from = 0) int length, int flags) {
        if (length < 0 || mCancellationGroup.isCanceled()) {
            return null;
        }

        final Completable.CharSequence value = mInvoker.getTextBeforeCursor(length, flags);
        final CharSequence result = Completable.getResultOrNull(
                value, TAG, "getTextBeforeCursor()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);

        final InputMethodServiceInternal inputMethodService = mInputMethodService.get();
        if (inputMethodService != null && ImeTracing.getInstance().isEnabled()) {
            final byte[] icProto = InputConnectionProtoDumper.buildGetTextBeforeCursorProto(length,
                    flags, result);
            inputMethodService.triggerServiceDump(TAG + "#getTextBeforeCursor", icProto);
        }

        return result;
    }

    @AnyThread
    public CharSequence getSelectedText(int flags) {
        if (mCancellationGroup.isCanceled()) {
            return null;
        }

        if (isMethodMissing(MissingMethodFlags.GET_SELECTED_TEXT)) {
            // This method is not implemented.
            return null;
        }
        final Completable.CharSequence value = mInvoker.getSelectedText(flags);
        final CharSequence result = Completable.getResultOrNull(
                value, TAG, "getSelectedText()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);

        final InputMethodServiceInternal inputMethodService = mInputMethodService.get();
        if (inputMethodService != null && ImeTracing.getInstance().isEnabled()) {
            final byte[] icProto = InputConnectionProtoDumper.buildGetSelectedTextProto(flags,
                    result);
            inputMethodService.triggerServiceDump(TAG + "#getSelectedText", icProto);
        }

        return result;
    }

    /**
     * Get {@link SurroundingText} around the current cursor, with <var>beforeLength</var>
     * characters of text before the cursor, <var>afterLength</var> characters of text after the
     * cursor, and all of the selected text.
     * @param beforeLength The expected length of the text before the cursor
     * @param afterLength The expected length of the text after the cursor
     * @param flags Supplies additional options controlling how the text is returned. May be either
     *              0 or {@link #GET_TEXT_WITH_STYLES}.
     * @return the surrounding text around the cursor position; the length of the returned text
     * might be less than requested.  It could also be {@code null} when the editor or system could
     * not support this protocol.
     */
    @AnyThread
    public SurroundingText getSurroundingText(
            @IntRange(from = 0) int beforeLength, @IntRange(from = 0) int afterLength, int flags) {
        if (beforeLength < 0 || afterLength < 0 || mCancellationGroup.isCanceled()) {
            return null;
        }

        if (isMethodMissing(MissingMethodFlags.GET_SURROUNDING_TEXT)) {
            // This method is not implemented.
            return null;
        }
        final Completable.SurroundingText value = mInvoker.getSurroundingText(beforeLength,
                afterLength, flags);
        final SurroundingText result = Completable.getResultOrNull(
                value, TAG, "getSurroundingText()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);

        final InputMethodServiceInternal inputMethodService = mInputMethodService.get();
        if (inputMethodService != null && ImeTracing.getInstance().isEnabled()) {
            final byte[] icProto = InputConnectionProtoDumper.buildGetSurroundingTextProto(
                    beforeLength, afterLength, flags, result);
            inputMethodService.triggerServiceDump(TAG + "#getSurroundingText", icProto);
        }

        return result;
    }

    @AnyThread
    public int getCursorCapsMode(int reqModes) {
        if (mCancellationGroup.isCanceled()) {
            return 0;
        }

        final Completable.Int value = mInvoker.getCursorCapsMode(reqModes);
        final int result = Completable.getResultOrZero(
                value, TAG, "getCursorCapsMode()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);

        final InputMethodServiceInternal inputMethodService = mInputMethodService.get();
        if (inputMethodService != null && ImeTracing.getInstance().isEnabled()) {
            final byte[] icProto = InputConnectionProtoDumper.buildGetCursorCapsModeProto(
                    reqModes, result);
            inputMethodService.triggerServiceDump(TAG + "#getCursorCapsMode", icProto);
        }

        return result;
    }

    @AnyThread
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        if (mCancellationGroup.isCanceled()) {
            return null;
        }

        final Completable.ExtractedText value = mInvoker.getExtractedText(request, flags);
        final ExtractedText result = Completable.getResultOrNull(
                value, TAG, "getExtractedText()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);

        final InputMethodServiceInternal inputMethodService = mInputMethodService.get();
        if (inputMethodService != null && ImeTracing.getInstance().isEnabled()) {
            final byte[] icProto = InputConnectionProtoDumper.buildGetExtractedTextProto(
                    request, flags, result);
            inputMethodService.triggerServiceDump(TAG + "#getExtractedText", icProto);
        }

        return result;
    }

    @AnyThread
    public boolean commitText(CharSequence text, int newCursorPosition) {
        final boolean handled = mInvoker.commitText(text, newCursorPosition);
        if (handled) {
            notifyUserActionIfNecessary();
        }
        return handled;
    }

    @AnyThread
    private void notifyUserActionIfNecessary() {
        final InputMethodServiceInternal inputMethodService = mInputMethodService.get();
        if (inputMethodService == null) {
            // This basically should not happen, because it's the the caller of this method.
            return;
        }
        inputMethodService.notifyUserActionIfNecessary();
    }

    @AnyThread
    public boolean commitCompletion(CompletionInfo text) {
        return mInvoker.commitCompletion(text);
    }

    @AnyThread
    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        if (isMethodMissing(MissingMethodFlags.COMMIT_CORRECTION)) {
            // This method is not implemented.
            return false;
        }
        return mInvoker.commitCorrection(correctionInfo);
    }

    @AnyThread
    public boolean setSelection(int start, int end) {
        return mInvoker.setSelection(start, end);
    }

    @AnyThread
    public boolean performEditorAction(int actionCode) {
        return mInvoker.performEditorAction(actionCode);
    }

    @AnyThread
    public boolean performContextMenuAction(int id) {
        return mInvoker.performContextMenuAction(id);
    }

    @AnyThread
    public boolean setComposingRegion(int start, int end) {
        if (isMethodMissing(MissingMethodFlags.SET_COMPOSING_REGION)) {
            // This method is not implemented.
            return false;
        }
        return mInvoker.setComposingRegion(start, end);
    }

    @AnyThread
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        final boolean handled = mInvoker.setComposingText(text, newCursorPosition);
        if (handled) {
            notifyUserActionIfNecessary();
        }
        return handled;
    }

    @AnyThread
    public boolean finishComposingText() {
        return mInvoker.finishComposingText();
    }

    @AnyThread
    public boolean beginBatchEdit() {
        return mInvoker.beginBatchEdit();
    }

    @AnyThread
    public boolean endBatchEdit() {
        return mInvoker.endBatchEdit();
    }

    @AnyThread
    public boolean sendKeyEvent(KeyEvent event) {
        final boolean handled = mInvoker.sendKeyEvent(event);
        if (handled) {
            notifyUserActionIfNecessary();
        }
        return handled;
    }

    @AnyThread
    public boolean clearMetaKeyStates(int states) {
        return mInvoker.clearMetaKeyStates(states);
    }

    @AnyThread
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        return mInvoker.deleteSurroundingText(beforeLength, afterLength);
    }

    @AnyThread
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        if (isMethodMissing(MissingMethodFlags.DELETE_SURROUNDING_TEXT_IN_CODE_POINTS)) {
            // This method is not implemented.
            return false;
        }
        return mInvoker.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
    }

    @AnyThread
    public boolean reportFullscreenMode(boolean enabled) {
        // Nothing should happen when called from input method.
        return false;
    }

    @AnyThread
    public boolean performSpellCheck() {
        return mInvoker.performSpellCheck();
    }

    @AnyThread
    public boolean performPrivateCommand(String action, Bundle data) {
        return mInvoker.performPrivateCommand(action, data);
    }

    @AnyThread
    public boolean requestCursorUpdates(int cursorUpdateMode) {
        if (mCancellationGroup.isCanceled()) {
            return false;
        }

        if (isMethodMissing(MissingMethodFlags.REQUEST_CURSOR_UPDATES)) {
            // This method is not implemented.
            return false;
        }
        final Completable.Boolean value = mInvoker.requestCursorUpdates(cursorUpdateMode);
        return Completable.getResultOrFalse(value, TAG, "requestCursorUpdates()",
                mCancellationGroup, MAX_WAIT_TIME_MILLIS);
    }

    @AnyThread
    public Handler getHandler() {
        // Nothing should happen when called from input method.
        return null;
    }

    @AnyThread
    public void closeConnection() {
        // Nothing should happen when called from input method.
    }

    @AnyThread
    public boolean commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts) {
        if (mCancellationGroup.isCanceled()) {
            return false;
        }

        if (isMethodMissing(MissingMethodFlags.COMMIT_CONTENT)) {
            // This method is not implemented.
            return false;
        }

        if ((flags & InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
            final InputMethodServiceInternal inputMethodService = mInputMethodService.get();
            if (inputMethodService == null) {
                // This basically should not happen, because it's the caller of this method.
                return false;
            }
            inputMethodService.exposeContent(inputContentInfo, this);
        }

        final Completable.Boolean value = mInvoker.commitContent(inputContentInfo, flags, opts);
        return Completable.getResultOrFalse(
                value, TAG, "commitContent()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);
    }

    /**
     * See {@link InputConnection#setImeConsumesInput(boolean)}.
     */
    @AnyThread
    public boolean setImeConsumesInput(boolean imeConsumesInput) {
        return mInvoker.setImeConsumesInput(imeConsumesInput);
    }

    @AnyThread
    private boolean isMethodMissing(@MissingMethodFlags final int methodFlag) {
        return (mMissingMethods & methodFlag) == methodFlag;
    }

    @AnyThread
    @Override
    public String toString() {
        return "RemoteInputConnection{idHash=#"
                + Integer.toHexString(System.identityHashCode(this))
                + " mMissingMethods="
                + InputConnectionInspector.getMissingMethodFlagsAsString(mMissingMethods) + "}";
    }
}
