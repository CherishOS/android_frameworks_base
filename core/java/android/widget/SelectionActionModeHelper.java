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

package android.widget;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.annotation.WorkerThread;
import android.content.Context;
import android.os.AsyncTask;
import android.os.LocaleList;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextUtils;
import android.util.Log;
import android.view.ActionMode;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextSelection;
import android.view.textclassifier.logging.SmartSelectionEventTracker;
import android.view.textclassifier.logging.SmartSelectionEventTracker.SelectionEvent;
import android.widget.Editor.SelectionModifierCursorController;

import com.android.internal.util.Preconditions;

import java.text.BreakIterator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Helper class for starting selection action mode
 * (synchronously without the TextClassifier, asynchronously with the TextClassifier).
 */
@UiThread
final class SelectionActionModeHelper {

    /**
     * Maximum time (in milliseconds) to wait for a result before timing out.
     */
    // TODO: Consider making this a ViewConfiguration.
    private static final int TIMEOUT_DURATION = 200;

    private final Editor mEditor;
    private final TextView mTextView;
    private final TextClassificationHelper mTextClassificationHelper;

    private TextClassification mTextClassification;
    private AsyncTask mTextClassificationAsyncTask;

    private final SelectionTracker mSelectionTracker;

    SelectionActionModeHelper(@NonNull Editor editor) {
        mEditor = Preconditions.checkNotNull(editor);
        mTextView = mEditor.getTextView();
        mTextClassificationHelper = new TextClassificationHelper(
                mTextView.getTextClassifier(), mTextView.getText(),
                0, 1, mTextView.getTextLocales());
        mSelectionTracker =
                new SelectionTracker(mTextView.getContext(), mTextView.isTextEditable());
    }

    public void startActionModeAsync(boolean adjustSelection) {
        mSelectionTracker.onOriginalSelection(
                mTextView.getText(),
                mTextView.getSelectionStart(),
                mTextView.getSelectionEnd(),
                mTextView.isTextEditable());
        cancelAsyncTask();
        if (skipTextClassification()) {
            startActionMode(null);
        } else {
            resetTextClassificationHelper();
            mTextClassificationAsyncTask = new TextClassificationAsyncTask(
                    mTextView,
                    TIMEOUT_DURATION,
                    adjustSelection
                            ? mTextClassificationHelper::suggestSelection
                            : mTextClassificationHelper::classifyText,
                    this::startActionMode)
                    .execute();
        }
    }

    public void invalidateActionModeAsync() {
        cancelAsyncTask();
        if (skipTextClassification()) {
            invalidateActionMode(null);
        } else {
            resetTextClassificationHelper();
            mTextClassificationAsyncTask = new TextClassificationAsyncTask(
                    mTextView,
                    TIMEOUT_DURATION,
                    mTextClassificationHelper::classifyText,
                    this::invalidateActionMode)
                    .execute();
        }
    }

    public void onSelectionAction(int menuItemId) {
        mSelectionTracker.onSelectionAction(
                mTextView.getSelectionStart(), mTextView.getSelectionEnd(),
                getActionType(menuItemId), mTextClassification);
    }

    public void onSelectionDrag() {
        mSelectionTracker.onSelectionAction(
                mTextView.getSelectionStart(), mTextView.getSelectionEnd(),
                SelectionEvent.ActionType.DRAG, mTextClassification);
    }

    public void onTypeOverSelection() {
        mSelectionTracker.onSelectionAction(
                mTextView.getSelectionStart(), mTextView.getSelectionEnd(),
                SelectionEvent.ActionType.OVERTYPE, mTextClassification);
    }

    public boolean resetSelection(int textIndex) {
        if (mSelectionTracker.resetSelection(textIndex, mEditor)) {
            invalidateActionModeAsync();
            return true;
        }
        return false;
    }

    @Nullable
    public TextClassification getTextClassification() {
        return mTextClassification;
    }

    public void onDestroyActionMode() {
        mSelectionTracker.onSelectionDestroyed();
        cancelAsyncTask();
    }

    private void cancelAsyncTask() {
        if (mTextClassificationAsyncTask != null) {
            mTextClassificationAsyncTask.cancel(true);
            mTextClassificationAsyncTask = null;
        }
        mTextClassification = null;
    }

    private boolean skipTextClassification() {
        // No need to make an async call for a no-op TextClassifier.
        final boolean noOpTextClassifier = mTextView.getTextClassifier() == TextClassifier.NO_OP;
        // Do not call the TextClassifier if there is no selection.
        final boolean noSelection = mTextView.getSelectionEnd() == mTextView.getSelectionStart();
        // Do not call the TextClassifier if this is a password field.
        final boolean password = mTextView.hasPasswordTransformationMethod()
                || TextView.isPasswordInputType(mTextView.getInputType());
        return noOpTextClassifier || noSelection || password;
    }

    private void startActionMode(@Nullable SelectionResult result) {
        final CharSequence text = mTextView.getText();
        if (result != null && text instanceof Spannable) {
            Selection.setSelection((Spannable) text, result.mStart, result.mEnd);
            mTextClassification = result.mClassification;
        } else {
            mTextClassification = null;
        }
        if (mEditor.startSelectionActionModeInternal()) {
            final SelectionModifierCursorController controller = mEditor.getSelectionController();
            if (controller != null) {
                controller.show();
            }
            if (result != null) {
                mSelectionTracker.onSmartSelection(result);
            }
        }
        mEditor.setRestartActionModeOnNextRefresh(false);
        mTextClassificationAsyncTask = null;
    }

    private void invalidateActionMode(@Nullable SelectionResult result) {
        mTextClassification = result != null ? result.mClassification : null;
        final ActionMode actionMode = mEditor.getTextActionMode();
        if (actionMode != null) {
            actionMode.invalidate();
        }
        mSelectionTracker.onSelectionUpdated(
                mTextView.getSelectionStart(), mTextView.getSelectionEnd(), mTextClassification);
        mTextClassificationAsyncTask = null;
    }

    private void resetTextClassificationHelper() {
        mTextClassificationHelper.reset(mTextView.getTextClassifier(), mTextView.getText(),
                mTextView.getSelectionStart(), mTextView.getSelectionEnd(),
                mTextView.getTextLocales());
    }

    /**
     * Tracks and logs smart selection changes.
     * It is important to trigger this object's methods at the appropriate event so that it tracks
     * smart selection events appropriately.
     */
    private static final class SelectionTracker {

        private final Context mContext;
        private SelectionMetricsLogger mLogger;

        private int mOriginalStart;
        private int mOriginalEnd;
        private int mSelectionStart;
        private int mSelectionEnd;
        private boolean mSelectionStarted;
        private boolean mAllowReset;

        SelectionTracker(Context context, boolean editable) {
            mContext = Preconditions.checkNotNull(context);
            mLogger = new SelectionMetricsLogger(context, editable);
        }

        /**
         * Called when the original selection happens, before smart selection is triggered.
         */
        public void onOriginalSelection(
                CharSequence text, int selectionStart, int selectionEnd, boolean editableText) {
            mOriginalStart = selectionStart;
            mOriginalEnd = selectionEnd;
            mSelectionStarted = true;
            mAllowReset = false;
            maybeInvalidateLogger(editableText);
            mLogger.logSelectionStarted(text, selectionStart);
        }

        /**
         * Called when selection action mode is started and the results come from a classifier.
         */
        public void onSmartSelection(SelectionResult result) {
            if (mSelectionStarted) {
                mSelectionStart = result.mStart;
                mSelectionEnd = result.mEnd;
                mAllowReset = mSelectionStart != mOriginalStart || mSelectionEnd != mOriginalEnd;
                mLogger.logSelectionModified(
                        result.mStart, result.mEnd, result.mClassification, result.mSelection);
            }
        }

        /**
         * Called when selection bounds change.
         */
        public void onSelectionUpdated(
                int selectionStart, int selectionEnd,
                @Nullable TextClassification classification) {
            if (mSelectionStarted) {
                mAllowReset = false;
                mLogger.logSelectionModified(selectionStart, selectionEnd, classification, null);
            }
        }

        /**
         * Called when the selection action mode is destroyed.
         */
        public void onSelectionDestroyed() {
            mAllowReset = false;
            mSelectionStarted = false;
            mLogger.logSelectionAction(
                    mSelectionStart, mSelectionEnd,
                    SelectionEvent.ActionType.ABANDON, null /* classification */);
        }

        /**
         * Called when an action is taken on a smart selection.
         */
        public void onSelectionAction(
                int selectionStart, int selectionEnd,
                @SelectionEvent.ActionType int action,
                @Nullable TextClassification classification) {
            if (mSelectionStarted) {
                mAllowReset = false;
                mLogger.logSelectionAction(selectionStart, selectionEnd, action, classification);
            }
        }

        /**
         * Returns true if the current smart selection should be reset to normal selection based on
         * information that has been recorded about the original selection and the smart selection.
         * The expected UX here is to allow the user to select a word inside of the smart selection
         * on a single tap.
         */
        public boolean resetSelection(int textIndex, Editor editor) {
            final TextView textView = editor.getTextView();
            if (mSelectionStarted
                    && mAllowReset
                    && textIndex >= mSelectionStart && textIndex <= mSelectionEnd
                    && textView.getText() instanceof Spannable) {
                mAllowReset = false;
                boolean selected = editor.selectCurrentWord();
                if (selected) {
                    mLogger.logSelectionAction(
                            textView.getSelectionStart(), textView.getSelectionEnd(),
                            SelectionEvent.ActionType.RESET, null /* classification */);
                }
                return selected;
            }
            return false;
        }

        private void maybeInvalidateLogger(boolean editableText) {
            if (mLogger.isEditTextLogger() != editableText) {
                mLogger = new SelectionMetricsLogger(mContext, editableText);
            }
        }
    }

    // TODO: Write tests
    /**
     * Metrics logging helper.
     *
     * This logger logs selection by word indices. The initial (start) single word selection is
     * logged at [0, 1) -- end index is exclusive. Other word indices are logged relative to the
     * initial single word selection.
     * e.g. New York city, NY. Suppose the initial selection is "York" in
     * "New York city, NY", then "York" is at [0, 1), "New" is at [-1, 0], and "city" is at [1, 2).
     * "New York" is at [-1, 1).
     * Part selection of a word e.g. "or" is counted as selecting the
     * entire word i.e. equivalent to "York", and each special character is counted as a word, e.g.
     * "," is at [2, 3). Whitespaces are ignored.
     */
    private static final class SelectionMetricsLogger {

        private static final String LOG_TAG = "SelectionMetricsLogger";

        private final SmartSelectionEventTracker mDelegate;
        private final boolean mEditTextLogger;
        private final BreakIterator mWordIterator = BreakIterator.getWordInstance();
        private int mStartIndex;
        private int mEndIndex;
        private String mText;

        SelectionMetricsLogger(Context context, boolean editable) {
            final @SmartSelectionEventTracker.WidgetType int widgetType = editable
                    ? SmartSelectionEventTracker.WidgetType.EDITTEXT
                    : SmartSelectionEventTracker.WidgetType.TEXTVIEW;
            mDelegate = new SmartSelectionEventTracker(context, widgetType);
            mEditTextLogger = editable;
        }

        public void logSelectionStarted(CharSequence text, int index) {
            try {
                Preconditions.checkNotNull(text);
                Preconditions.checkArgumentInRange(index, 0, text.length(), "index");
                if (mText == null || !mText.contentEquals(text)) {
                    mText = text.toString();
                }
                mWordIterator.setText(mText);
                mStartIndex = index;
                mEndIndex = mWordIterator.following(index);
                mDelegate.logEvent(SelectionEvent.selectionStarted(0));
            } catch (Exception e) {
                // Avoid crashes due to logging.
                Log.d(LOG_TAG, e.getMessage());
            }
        }

        public void logSelectionModified(int start, int end,
                @Nullable TextClassification classification, @Nullable TextSelection selection) {
            try {
                Preconditions.checkArgumentInRange(start, 0, mText.length(), "start");
                Preconditions.checkArgumentInRange(end, start, mText.length(), "end");
                int[] wordIndices = getWordDelta(start, end);
                if (selection != null) {
                    mDelegate.logEvent(SelectionEvent.selectionModified(
                            wordIndices[0], wordIndices[1], selection));
                } else if (classification != null) {
                    mDelegate.logEvent(SelectionEvent.selectionModified(
                            wordIndices[0], wordIndices[1], classification));
                } else {
                    mDelegate.logEvent(SelectionEvent.selectionModified(
                            wordIndices[0], wordIndices[1]));
                }
            } catch (Exception e) {
                // Avoid crashes due to logging.
                Log.d(LOG_TAG, e.getMessage());
            }
        }

        public void logSelectionAction(
                int start, int end,
                @SelectionEvent.ActionType int action,
                @Nullable TextClassification classification) {
            try {
                Preconditions.checkArgumentInRange(start, 0, mText.length(), "start");
                Preconditions.checkArgumentInRange(end, start, mText.length(), "end");
                int[] wordIndices = getWordDelta(start, end);
                if (classification != null) {
                    mDelegate.logEvent(SelectionEvent.selectionAction(
                            wordIndices[0], wordIndices[1], action, classification));
                } else {
                    mDelegate.logEvent(SelectionEvent.selectionAction(
                            wordIndices[0], wordIndices[1], action));
                }
            } catch (Exception e) {
                // Avoid crashes due to logging.
                Log.d(LOG_TAG, e.getMessage());
            }
        }

        public boolean isEditTextLogger() {
            return mEditTextLogger;
        }

        private int[] getWordDelta(int start, int end) {
            int[] wordIndices = new int[2];

            if (start == mStartIndex) {
                wordIndices[0] = 0;
            } else if (start < mStartIndex) {
                wordIndices[0] = -countWordsForward(start);
            } else {  // start > mStartIndex
                if (mStartIndex < start && start < mEndIndex) {
                    // If the new selection did not move past the original word,
                    // assume it has not moved.
                    wordIndices[0] = 0;
                } else {
                    wordIndices[0] = countWordsBackward(start);
                }
            }

            if (end == mStartIndex) {
                wordIndices[1] = 0;
            } else if (end < mStartIndex) {
                wordIndices[1] = -countWordsForward(end);
            } else {  // end > mStartIndex
                wordIndices[1] = countWordsBackward(end);
            }

            return wordIndices;
        }

        private int countWordsBackward(int from) {
            Preconditions.checkArgument(from >= mStartIndex);
            int wordCount = 0;
            int offset = from;
            while (offset > mStartIndex) {
                int start = mWordIterator.preceding(offset);
                if (!isWhitespace(start, offset)) {
                    wordCount++;
                }
                offset = start;
            }
            return wordCount;
        }

        private int countWordsForward(int from) {
            Preconditions.checkArgument(from <= mStartIndex);
            int wordCount = 0;
            int offset = from;
            while (offset < mStartIndex) {
                int end = mWordIterator.following(offset);
                if (!isWhitespace(offset, end)) {
                    wordCount++;
                }
                offset = end;
            }
            return wordCount;
        }

        private boolean isWhitespace(int start, int end) {
            return mText.substring(start, end).trim().isEmpty();
        }
    }

    /**
     * AsyncTask for running a query on a background thread and returning the result on the
     * UiThread. The AsyncTask times out after a specified time, returning a null result if the
     * query has not yet returned.
     */
    private static final class TextClassificationAsyncTask
            extends AsyncTask<Void, Void, SelectionResult> {

        private final int mTimeOutDuration;
        private final Supplier<SelectionResult> mSelectionResultSupplier;
        private final Consumer<SelectionResult> mSelectionResultCallback;
        private final TextView mTextView;
        private final String mOriginalText;

        /**
         * @param textView the TextView
         * @param timeOut time in milliseconds to timeout the query if it has not completed
         * @param selectionResultSupplier fetches the selection results. Runs on a background thread
         * @param selectionResultCallback receives the selection results. Runs on the UiThread
         */
        TextClassificationAsyncTask(
                @NonNull TextView textView, int timeOut,
                @NonNull Supplier<SelectionResult> selectionResultSupplier,
                @NonNull Consumer<SelectionResult> selectionResultCallback) {
            super(textView != null ? textView.getHandler() : null);
            mTextView = Preconditions.checkNotNull(textView);
            mTimeOutDuration = timeOut;
            mSelectionResultSupplier = Preconditions.checkNotNull(selectionResultSupplier);
            mSelectionResultCallback = Preconditions.checkNotNull(selectionResultCallback);
            // Make a copy of the original text.
            mOriginalText = mTextView.getText().toString();
        }

        @Override
        @WorkerThread
        protected SelectionResult doInBackground(Void... params) {
            final Runnable onTimeOut = this::onTimeOut;
            mTextView.postDelayed(onTimeOut, mTimeOutDuration);
            final SelectionResult result = mSelectionResultSupplier.get();
            mTextView.removeCallbacks(onTimeOut);
            return result;
        }

        @Override
        @UiThread
        protected void onPostExecute(SelectionResult result) {
            result = TextUtils.equals(mOriginalText, mTextView.getText()) ? result : null;
            mSelectionResultCallback.accept(result);
        }

        private void onTimeOut() {
            if (getStatus() == Status.RUNNING) {
                onPostExecute(null);
            }
            cancel(true);
        }
    }

    /**
     * Helper class for querying the TextClassifier.
     * It trims text so that only text necessary to provide context of the selected text is
     * sent to the TextClassifier.
     */
    private static final class TextClassificationHelper {

        private static final int TRIM_DELTA = 120;  // characters

        private TextClassifier mTextClassifier;

        /** The original TextView text. **/
        private String mText;
        /** Start index relative to mText. */
        private int mSelectionStart;
        /** End index relative to mText. */
        private int mSelectionEnd;
        private LocaleList mLocales;

        /** Trimmed text starting from mTrimStart in mText. */
        private CharSequence mTrimmedText;
        /** Index indicating the start of mTrimmedText in mText. */
        private int mTrimStart;
        /** Start index relative to mTrimmedText */
        private int mRelativeStart;
        /** End index relative to mTrimmedText */
        private int mRelativeEnd;

        /** Information about the last classified text to avoid re-running a query. */
        private CharSequence mLastClassificationText;
        private int mLastClassificationSelectionStart;
        private int mLastClassificationSelectionEnd;
        private LocaleList mLastClassificationLocales;
        private SelectionResult mLastClassificationResult;

        TextClassificationHelper(TextClassifier textClassifier,
                CharSequence text, int selectionStart, int selectionEnd, LocaleList locales) {
            reset(textClassifier, text, selectionStart, selectionEnd, locales);
        }

        @UiThread
        public void reset(TextClassifier textClassifier,
                CharSequence text, int selectionStart, int selectionEnd, LocaleList locales) {
            mTextClassifier = Preconditions.checkNotNull(textClassifier);
            mText = Preconditions.checkNotNull(text).toString();
            mLastClassificationText = null; // invalidate.
            Preconditions.checkArgument(selectionEnd > selectionStart);
            mSelectionStart = selectionStart;
            mSelectionEnd = selectionEnd;
            mLocales = locales;
        }

        @WorkerThread
        public SelectionResult classifyText() {
            return performClassification(null);
        }

        @WorkerThread
        public SelectionResult suggestSelection() {
            trimText();
            final TextSelection selection = mTextClassifier.suggestSelection(
                    mTrimmedText, mRelativeStart, mRelativeEnd, mLocales);
            mSelectionStart = Math.max(0, selection.getSelectionStartIndex() + mTrimStart);
            mSelectionEnd = Math.min(mText.length(), selection.getSelectionEndIndex() + mTrimStart);
            return performClassification(selection);
        }

        private SelectionResult performClassification(@Nullable TextSelection selection) {
            if (!Objects.equals(mText, mLastClassificationText)
                    || mSelectionStart != mLastClassificationSelectionStart
                    || mSelectionEnd != mLastClassificationSelectionEnd
                    || !Objects.equals(mLocales, mLastClassificationLocales)) {

                mLastClassificationText = mText;
                mLastClassificationSelectionStart = mSelectionStart;
                mLastClassificationSelectionEnd = mSelectionEnd;
                mLastClassificationLocales = mLocales;

                trimText();
                mLastClassificationResult = new SelectionResult(
                        mSelectionStart,
                        mSelectionEnd,
                        mTextClassifier.classifyText(
                                mTrimmedText, mRelativeStart, mRelativeEnd, mLocales),
                        selection);

            }
            return mLastClassificationResult;
        }

        private void trimText() {
            mTrimStart = Math.max(0, mSelectionStart - TRIM_DELTA);
            final int referenceEnd = Math.min(mText.length(), mSelectionEnd + TRIM_DELTA);
            mTrimmedText = mText.subSequence(mTrimStart, referenceEnd);
            mRelativeStart = mSelectionStart - mTrimStart;
            mRelativeEnd = mSelectionEnd - mTrimStart;
        }
    }

    /**
     * Selection result.
     */
    private static final class SelectionResult {
        private final int mStart;
        private final int mEnd;
        private final TextClassification mClassification;
        @Nullable private final TextSelection mSelection;

        SelectionResult(int start, int end,
                TextClassification classification, @Nullable TextSelection selection) {
            mStart = start;
            mEnd = end;
            mClassification = Preconditions.checkNotNull(classification);
            mSelection = selection;
        }
    }



    @SelectionEvent.ActionType
    private static int getActionType(int menuItemId) {
        switch (menuItemId) {
            case TextView.ID_SELECT_ALL:
                return SelectionEvent.ActionType.SELECT_ALL;
            case TextView.ID_CUT:
                return SelectionEvent.ActionType.CUT;
            case TextView.ID_COPY:
                return SelectionEvent.ActionType.COPY;
            case TextView.ID_PASTE:  // fall through
            case TextView.ID_PASTE_AS_PLAIN_TEXT:
                return SelectionEvent.ActionType.PASTE;
            case TextView.ID_SHARE:
                return SelectionEvent.ActionType.SHARE;
            case TextView.ID_ASSIST:
                return SelectionEvent.ActionType.SMART_SHARE;
            default:
                return SelectionEvent.ActionType.OTHER;
        }
    }
}
