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

package android.view.textclassifier;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.WorkerThread;
import android.os.LocaleList;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for providing text classification related features.
 *
 * <p>Unless otherwise stated, methods of this interface are blocking operations.
 * Avoid calling them on the UI thread.
 */
public interface TextClassifier {

    /** @hide */
    String DEFAULT_LOG_TAG = "TextClassifierImpl";

    /** @hide */
    String TYPE_UNKNOWN = "";  // TODO: Make this public API.
    String TYPE_OTHER = "other";
    String TYPE_EMAIL = "email";
    String TYPE_PHONE = "phone";
    String TYPE_ADDRESS = "address";
    String TYPE_URL = "url";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
            TYPE_UNKNOWN, TYPE_OTHER, TYPE_EMAIL, TYPE_PHONE, TYPE_ADDRESS, TYPE_URL
    })
    @interface EntityType {}

    /**
     * No-op TextClassifier.
     * This may be used to turn off TextClassifier features.
     */
    TextClassifier NO_OP = new TextClassifier() {};

    /**
     * Returns suggested text selection start and end indices, recognized entity types, and their
     * associated confidence scores. The entity types are ordered from highest to lowest scoring.
     *
     * @param text text providing context for the selected text (which is specified
     *      by the sub sequence starting at selectionStartIndex and ending at selectionEndIndex)
     * @param selectionStartIndex start index of the selected part of text
     * @param selectionEndIndex end index of the selected part of text
     * @param options optional input parameters
     *
     * @throws IllegalArgumentException if text is null; selectionStartIndex is negative;
     *      selectionEndIndex is greater than text.length() or not greater than selectionStartIndex
     */
    @WorkerThread
    @NonNull
    default TextSelection suggestSelection(
            @NonNull CharSequence text,
            @IntRange(from = 0) int selectionStartIndex,
            @IntRange(from = 0) int selectionEndIndex,
            @Nullable TextSelection.Options options) {
        return new TextSelection.Builder(selectionStartIndex, selectionEndIndex).build();
    }

    /**
     * @see #suggestSelection(CharSequence, int, int, TextSelection.Options)
     */
    // TODO: Consider deprecating (b/68846316)
    @WorkerThread
    @NonNull
    default TextSelection suggestSelection(
            @NonNull CharSequence text,
            @IntRange(from = 0) int selectionStartIndex,
            @IntRange(from = 0) int selectionEndIndex,
            @Nullable LocaleList defaultLocales) {
        return new TextSelection.Builder(selectionStartIndex, selectionEndIndex).build();
    }

    /**
     * Classifies the specified text and returns a {@link TextClassification} object that can be
     * used to generate a widget for handling the classified text.
     *
     * @param text text providing context for the text to classify (which is specified
     *      by the sub sequence starting at startIndex and ending at endIndex)
     * @param startIndex start index of the text to classify
     * @param endIndex end index of the text to classify
     * @param options optional input parameters
     *
     * @throws IllegalArgumentException if text is null; startIndex is negative;
     *      endIndex is greater than text.length() or not greater than startIndex
     */
    @WorkerThread
    @NonNull
    default TextClassification classifyText(
            @NonNull CharSequence text,
            @IntRange(from = 0) int startIndex,
            @IntRange(from = 0) int endIndex,
            @Nullable TextClassification.Options options) {
        return TextClassification.EMPTY;
    }

    /**
     * @see #classifyText(CharSequence, int, int, TextClassification.Options)
     */
    // TODO: Consider deprecating (b/68846316)
    @WorkerThread
    @NonNull
    default TextClassification classifyText(
            @NonNull CharSequence text,
            @IntRange(from = 0) int startIndex,
            @IntRange(from = 0) int endIndex,
            @Nullable LocaleList defaultLocales) {
        return TextClassification.EMPTY;
    }

    /**
     * Returns a {@link TextLinks} that may be applied to the text to annotate it with links
     * information.
     *
     * @param text the text to generate annotations for
     * @param options configuration for link generation. If null, defaults will be used.
     *
     * @throws IllegalArgumentException if text is null
     */
    @WorkerThread
    default TextLinks generateLinks(
            @NonNull CharSequence text, @Nullable TextLinks.Options options) {
        return new TextLinks.Builder(text.toString()).build();
    }

    /**
     * Logs a TextClassifier event.
     *
     * @param source the text classifier used to generate this event
     * @param event the text classifier related event
     * @hide
     */
    @WorkerThread
    default void logEvent(String source, String event) {}

    /**
     * Returns this TextClassifier's settings.
     * @hide
     */
    default TextClassifierConstants getSettings() {
        return TextClassifierConstants.DEFAULT;
    }
}
