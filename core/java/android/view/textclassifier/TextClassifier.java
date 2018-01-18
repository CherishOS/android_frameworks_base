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

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.WorkerThread;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Interface for providing text classification related features.
 *
 * <p>Unless otherwise stated, methods of this interface are blocking operations.
 * Avoid calling them on the UI thread.
 */
public interface TextClassifier {

    /** @hide */
    String DEFAULT_LOG_TAG = "androidtc";

    String TYPE_UNKNOWN = "";
    String TYPE_OTHER = "other";
    String TYPE_EMAIL = "email";
    String TYPE_PHONE = "phone";
    String TYPE_ADDRESS = "address";
    String TYPE_URL = "url";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = { "TYPE_" }, value = {
            TYPE_UNKNOWN,
            TYPE_OTHER,
            TYPE_EMAIL,
            TYPE_PHONE,
            TYPE_ADDRESS,
            TYPE_URL,
    })
    @interface EntityType {}

    /** Designates that the TextClassifier should identify all entity types it can. **/
    int ENTITY_PRESET_ALL = 0;
    /** Designates that the TextClassifier should identify no entities. **/
    int ENTITY_PRESET_NONE = 1;
    /** Designates that the TextClassifier should identify a base set of entities determined by the
     * TextClassifier. **/
    int ENTITY_PRESET_BASE = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "ENTITY_CONFIG_" },
            value = {ENTITY_PRESET_ALL, ENTITY_PRESET_NONE, ENTITY_PRESET_BASE})
    @interface EntityPreset {}

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
     *
     * @see #suggestSelection(CharSequence, int, int)
     */
    @WorkerThread
    @NonNull
    default TextSelection suggestSelection(
            @NonNull CharSequence text,
            @IntRange(from = 0) int selectionStartIndex,
            @IntRange(from = 0) int selectionEndIndex,
            @Nullable TextSelection.Options options) {
        Utils.validateInput(text, selectionStartIndex, selectionEndIndex);
        return new TextSelection.Builder(selectionStartIndex, selectionEndIndex).build();
    }

    /**
     * Returns suggested text selection start and end indices, recognized entity types, and their
     * associated confidence scores. The entity types are ordered from highest to lowest scoring.
     *
     * <p><b>NOTE:</b> Do not implement. The default implementation of this method calls
     * {@link #suggestSelection(CharSequence, int, int, TextSelection.Options)}. If that method
     * calls this method, a stack overflow error will happen.
     *
     * @param text text providing context for the selected text (which is specified
     *      by the sub sequence starting at selectionStartIndex and ending at selectionEndIndex)
     * @param selectionStartIndex start index of the selected part of text
     * @param selectionEndIndex end index of the selected part of text
     *
     * @throws IllegalArgumentException if text is null; selectionStartIndex is negative;
     *      selectionEndIndex is greater than text.length() or not greater than selectionStartIndex
     *
     * @see #suggestSelection(CharSequence, int, int, TextSelection.Options)
     */
    @WorkerThread
    @NonNull
    default TextSelection suggestSelection(
            @NonNull CharSequence text,
            @IntRange(from = 0) int selectionStartIndex,
            @IntRange(from = 0) int selectionEndIndex) {
        return suggestSelection(text, selectionStartIndex, selectionEndIndex,
                (TextSelection.Options) null);
    }

    /**
     * See {@link #suggestSelection(CharSequence, int, int)} or
     * {@link #suggestSelection(CharSequence, int, int, TextSelection.Options)}.
     *
     * <p><b>NOTE:</b> Do not implement. The default implementation of this method calls
     * {@link #suggestSelection(CharSequence, int, int, TextSelection.Options)}. If that method
     * calls this method, a stack overflow error will happen.
     */
    @WorkerThread
    @NonNull
    default TextSelection suggestSelection(
            @NonNull CharSequence text,
            @IntRange(from = 0) int selectionStartIndex,
            @IntRange(from = 0) int selectionEndIndex,
            @Nullable LocaleList defaultLocales) {
        final TextSelection.Options options = (defaultLocales != null)
                ? new TextSelection.Options().setDefaultLocales(defaultLocales)
                : null;
        return suggestSelection(text, selectionStartIndex, selectionEndIndex, options);
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
     *
     * @see #classifyText(CharSequence, int, int)
     */
    @WorkerThread
    @NonNull
    default TextClassification classifyText(
            @NonNull CharSequence text,
            @IntRange(from = 0) int startIndex,
            @IntRange(from = 0) int endIndex,
            @Nullable TextClassification.Options options) {
        Utils.validateInput(text, startIndex, endIndex);
        return TextClassification.EMPTY;
    }

    /**
     * Classifies the specified text and returns a {@link TextClassification} object that can be
     * used to generate a widget for handling the classified text.
     *
     * <p><b>NOTE:</b> Do not implement. The default implementation of this method calls
     * {@link #classifyText(CharSequence, int, int, TextClassification.Options)}. If that method
     * calls this method, a stack overflow error will happen.
     *
     * @param text text providing context for the text to classify (which is specified
     *      by the sub sequence starting at startIndex and ending at endIndex)
     * @param startIndex start index of the text to classify
     * @param endIndex end index of the text to classify
     *
     * @throws IllegalArgumentException if text is null; startIndex is negative;
     *      endIndex is greater than text.length() or not greater than startIndex
     *
     * @see #classifyText(CharSequence, int, int, TextClassification.Options)
     */
    @WorkerThread
    @NonNull
    default TextClassification classifyText(
            @NonNull CharSequence text,
            @IntRange(from = 0) int startIndex,
            @IntRange(from = 0) int endIndex) {
        return classifyText(text, startIndex, endIndex, (TextClassification.Options) null);
    }

    /**
     * See {@link #classifyText(CharSequence, int, int, TextClassification.Options)} or
     * {@link #classifyText(CharSequence, int, int)}.
     *
     * <p><b>NOTE:</b> Do not implement. The default implementation of this method calls
     * {@link #classifyText(CharSequence, int, int, TextClassification.Options)}. If that method
     * calls this method, a stack overflow error will happen.
     */
    @WorkerThread
    @NonNull
    default TextClassification classifyText(
            @NonNull CharSequence text,
            @IntRange(from = 0) int startIndex,
            @IntRange(from = 0) int endIndex,
            @Nullable LocaleList defaultLocales) {
        final TextClassification.Options options = (defaultLocales != null)
                ? new TextClassification.Options().setDefaultLocales(defaultLocales)
                : null;
        return classifyText(text, startIndex, endIndex, options);
    }

    /**
     * Returns a {@link TextLinks} that may be applied to the text to annotate it with links
     * information.
     *
     * If no options are supplied, default values will be used, determined by the TextClassifier.
     *
     * @param text the text to generate annotations for
     * @param options configuration for link generation
     *
     * @throws IllegalArgumentException if text is null
     *
     * @see #generateLinks(CharSequence)
     */
    @WorkerThread
    default TextLinks generateLinks(
            @NonNull CharSequence text, @Nullable TextLinks.Options options) {
        Utils.validateInput(text);
        return new TextLinks.Builder(text.toString()).build();
    }

    /**
     * Returns a {@link TextLinks} that may be applied to the text to annotate it with links
     * information.
     *
     * <p><b>NOTE:</b> Do not implement. The default implementation of this method calls
     * {@link #generateLinks(CharSequence, TextLinks.Options)}. If that method calls this method,
     * a stack overflow error will happen.
     *
     * @param text the text to generate annotations for
     *
     * @throws IllegalArgumentException if text is null
     *
     * @see #generateLinks(CharSequence, TextLinks.Options)
     */
    @WorkerThread
    default TextLinks generateLinks(@NonNull CharSequence text) {
        return generateLinks(text, null);
    }

    /**
     * Returns a {@link Collection} of the entity types in the specified preset.
     *
     * @see #ENTITY_PRESET_ALL
     * @see #ENTITY_PRESET_NONE
     */
    default Collection<String> getEntitiesForPreset(@EntityPreset int entityPreset) {
        return Collections.EMPTY_LIST;
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

    /**
     * Configuration object for specifying what entities to identify.
     *
     * Configs are initially based on a predefined preset, and can be modified from there.
     */
    final class EntityConfig implements Parcelable {
        private final @TextClassifier.EntityPreset int mEntityPreset;
        private final Collection<String> mExcludedEntityTypes;
        private final Collection<String> mIncludedEntityTypes;

        public EntityConfig(@TextClassifier.EntityPreset int mEntityPreset) {
            this.mEntityPreset = mEntityPreset;
            mExcludedEntityTypes = new ArraySet<>();
            mIncludedEntityTypes = new ArraySet<>();
        }

        /**
         * Specifies an entity to include in addition to any specified by the enity preset.
         *
         * Note that if an entity has been excluded, the exclusion will take precedence.
         */
        public EntityConfig includeEntities(String... entities) {
            for (String entity : entities) {
                mIncludedEntityTypes.add(entity);
            }
            return this;
        }

        /**
         * Specifies an entity to be excluded.
         */
        public EntityConfig excludeEntities(String... entities) {
            for (String entity : entities) {
                mExcludedEntityTypes.add(entity);
            }
            return this;
        }

        /**
         * Returns an unmodifiable list of the final set of entities to find.
         */
        public List<String> getEntities(TextClassifier textClassifier) {
            ArrayList<String> entities = new ArrayList<>();
            for (String entity : textClassifier.getEntitiesForPreset(mEntityPreset)) {
                if (!mExcludedEntityTypes.contains(entity)) {
                    entities.add(entity);
                }
            }
            for (String entity : mIncludedEntityTypes) {
                if (!mExcludedEntityTypes.contains(entity) && !entities.contains(entity)) {
                    entities.add(entity);
                }
            }
            return Collections.unmodifiableList(entities);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mEntityPreset);
            dest.writeStringList(new ArrayList<>(mExcludedEntityTypes));
            dest.writeStringList(new ArrayList<>(mIncludedEntityTypes));
        }

        public static final Parcelable.Creator<EntityConfig> CREATOR =
                new Parcelable.Creator<EntityConfig>() {
                    @Override
                    public EntityConfig createFromParcel(Parcel in) {
                        return new EntityConfig(in);
                    }

                    @Override
                    public EntityConfig[] newArray(int size) {
                        return new EntityConfig[size];
                    }
                };

        private EntityConfig(Parcel in) {
            mEntityPreset = in.readInt();
            mExcludedEntityTypes = new ArraySet<>(in.createStringArrayList());
            mIncludedEntityTypes = new ArraySet<>(in.createStringArrayList());
        }
    }

    /**
     * Utility functions for TextClassifier methods.
     *
     * <ul>
     *  <li>Provides validation of input parameters to TextClassifier methods
     * </ul>
     *
     * Intended to be used only in this package.
     * @hide
     */
    final class Utils {

        /**
         * @throws IllegalArgumentException if text is null; startIndex is negative;
         *      endIndex is greater than text.length() or is not greater than startIndex;
         *      options is null
         */
        static void validateInput(
                @NonNull CharSequence text, int startIndex, int endIndex) {
            Preconditions.checkArgument(text != null);
            Preconditions.checkArgument(startIndex >= 0);
            Preconditions.checkArgument(endIndex <= text.length());
            Preconditions.checkArgument(endIndex > startIndex);
        }

        /**
         * @throws IllegalArgumentException if text is null or options is null
         */
        static void validateInput(@NonNull CharSequence text) {
            Preconditions.checkArgument(text != null);
        }
    }
}
