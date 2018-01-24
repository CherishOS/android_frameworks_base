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

package android.app.slice;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A slice is a piece of app content and actions that can be surfaced outside of the app.
 *
 * <p>They are constructed using {@link Builder} in a tree structure
 * that provides the OS some information about how the content should be displayed.
 */
public final class Slice implements Parcelable {

    /**
     * @hide
     */
    @StringDef(prefix = { "HINT_" }, value = {
            HINT_TITLE,
            HINT_LIST,
            HINT_LIST_ITEM,
            HINT_LARGE,
            HINT_ACTIONS,
            HINT_SELECTED,
            HINT_NO_TINT,
            HINT_SHORTCUT,
            HINT_TOGGLE,
            HINT_HORIZONTAL,
            HINT_PARTIAL,
            HINT_SEE_MORE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SliceHint {}

    /**
     * The meta-data key that allows an activity to easily be linked directly to a slice.
     * <p>
     * An activity can be statically linked to a slice uri by including a meta-data item
     * for this key that contains a valid slice uri for the same application declaring
     * the activity.
     * @hide
     */
    public static final String SLICE_METADATA_KEY = "android.metadata.SLICE_URI";

    /**
     * Hint that this content is a title of other content in the slice. This can also indicate that
     * the content should be used in the shortcut representation of the slice (icon, label, action),
     * normally this should be indicated by adding the hint on the action containing that content.
     *
     * @see SliceItem#FORMAT_ACTION
     */
    public static final String HINT_TITLE       = "title";
    /**
     * Hint that all sub-items/sub-slices within this content should be considered
     * to have {@link #HINT_LIST_ITEM}.
     */
    public static final String HINT_LIST        = "list";
    /**
     * Hint that this item is part of a list and should be formatted as if is part
     * of a list.
     */
    public static final String HINT_LIST_ITEM   = "list_item";
    /**
     * Hint that this content is important and should be larger when displayed if
     * possible.
     */
    public static final String HINT_LARGE       = "large";
    /**
     * Hint that this slice contains a number of actions that can be grouped together
     * in a sort of controls area of the UI.
     */
    public static final String HINT_ACTIONS     = "actions";
    /**
     * Hint indicating that this item (and its sub-items) are the current selection.
     */
    public static final String HINT_SELECTED    = "selected";
    /**
     * Hint to indicate that this content should not be tinted.
     */
    public static final String HINT_NO_TINT     = "no_tint";
    /**
     * Hint to indicate that this content should only be displayed if the slice is presented
     * as a shortcut.
     */
    public static final String HINT_SHORTCUT = "shortcut";
    /**
     * Hint indicating this content should be shown instead of the normal content when the slice
     * is in small format.
     */
    public static final String HINT_SUMMARY = "summary";
    /**
     * Hint to indicate that this content has a toggle action associated with it. To indicate that
     * the toggle is on, use {@link #HINT_SELECTED}. When the toggle state changes, the intent
     * associated with it will be sent along with an extra {@link #EXTRA_TOGGLE_STATE} which can be
     * retrieved to see the new state of the toggle.
     * @hide
     */
    public static final String HINT_TOGGLE = "toggle";
    /**
     * Hint that list items within this slice or subslice would appear better
     * if organized horizontally.
     */
    public static final String HINT_HORIZONTAL = "horizontal";
    /**
     * Hint to indicate that this slice is incomplete and an update will be sent once
     * loading is complete. Slices which contain HINT_PARTIAL will not be cached by the
     * OS and should not be cached by apps.
     */
    public static final String HINT_PARTIAL     = "partial";
    /**
     * A hint representing that this item is the max value possible for the slice containing this.
     * Used to indicate the maximum integer value for a {@link #SUBTYPE_SLIDER}.
     */
    public static final String HINT_MAX = "max";
    /**
     * A hint representing that this item should be used to indicate that there's more
     * content associated with this slice.
     */
    public static final String HINT_SEE_MORE = "see_more";
    /**
     * A hint used when implementing app-specific slice permissions.
     * Tells the system that for this slice the return value of
     * {@link SliceProvider#onBindSlice(Uri, List)} may be different depending on
     * {@link SliceProvider#getBindingPackage} and should not be cached for multiple
     * apps.
     */
    public static final String HINT_CALLER_NEEDED = "caller_needed";
    /**
     * Key to retrieve an extra added to an intent when a control is changed.
     */
    public static final String EXTRA_TOGGLE_STATE = "android.app.slice.extra.TOGGLE_STATE";
    /**
     * Subtype to indicate that this is a message as part of a communication
     * sequence in this slice.
     */
    public static final String SUBTYPE_MESSAGE = "message";
    /**
     * Subtype to tag the source (i.e. sender) of a {@link #SUBTYPE_MESSAGE}.
     */
    public static final String SUBTYPE_SOURCE = "source";
    /**
     * Subtype to tag an item as representing a color.
     */
    public static final String SUBTYPE_COLOR = "color";
    /**
     * Subtype to tag an item represents a slider.
     */
    public static final String SUBTYPE_SLIDER = "slider";
    /**
     * Subtype to indicate that this content has a toggle action associated with it. To indicate
     * that the toggle is on, use {@link #HINT_SELECTED}. When the toggle state changes, the
     * intent associated with it will be sent along with an extra {@link #EXTRA_TOGGLE_STATE}
     * which can be retrieved to see the new state of the toggle.
     */
    public static final String SUBTYPE_TOGGLE = "toggle";
    /**
     * Subtype to tag an item representing priority.
     */
    public static final String SUBTYPE_PRIORITY = "priority";
    /**
     * Subtype to tag an item to use as a content description.
     */
    public static final String SUBTYPE_CONTENT_DESCRIPTION = "content_description";

    private final SliceItem[] mItems;
    private final @SliceHint String[] mHints;
    private SliceSpec mSpec;
    private Uri mUri;

    Slice(ArrayList<SliceItem> items, @SliceHint String[] hints, Uri uri, SliceSpec spec) {
        mHints = hints;
        mItems = items.toArray(new SliceItem[items.size()]);
        mUri = uri;
        mSpec = spec;
    }

    protected Slice(Parcel in) {
        mHints = in.readStringArray();
        int n = in.readInt();
        mItems = new SliceItem[n];
        for (int i = 0; i < n; i++) {
            mItems[i] = SliceItem.CREATOR.createFromParcel(in);
        }
        mUri = Uri.CREATOR.createFromParcel(in);
        mSpec = in.readTypedObject(SliceSpec.CREATOR);
    }

    /**
     * @return The spec for this slice
     */
    public @Nullable SliceSpec getSpec() {
        return mSpec;
    }

    /**
     * @return The Uri that this Slice represents.
     */
    public Uri getUri() {
        return mUri;
    }

    /**
     * @return All child {@link SliceItem}s that this Slice contains.
     */
    public List<SliceItem> getItems() {
        return Arrays.asList(mItems);
    }

    /**
     * @return All hints associated with this Slice.
     */
    public @SliceHint List<String> getHints() {
        return Arrays.asList(mHints);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStringArray(mHints);
        dest.writeInt(mItems.length);
        for (int i = 0; i < mItems.length; i++) {
            mItems[i].writeToParcel(dest, flags);
        }
        mUri.writeToParcel(dest, 0);
        dest.writeTypedObject(mSpec, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    public boolean hasHint(@SliceHint String hint) {
        return ArrayUtils.contains(mHints, hint);
    }

    /**
     * A Builder used to construct {@link Slice}s
     */
    public static class Builder {

        private final Uri mUri;
        private ArrayList<SliceItem> mItems = new ArrayList<>();
        private @SliceHint ArrayList<String> mHints = new ArrayList<>();
        private SliceSpec mSpec;

        /**
         * Create a builder which will construct a {@link Slice} for the Given Uri.
         * @param uri Uri to tag for this slice.
         */
        public Builder(@NonNull Uri uri) {
            mUri = uri;
        }

        /**
         * Create a builder for a {@link Slice} that is a sub-slice of the slice
         * being constructed by the provided builder.
         * @param parent The builder constructing the parent slice
         */
        public Builder(@NonNull Slice.Builder parent) {
            mUri = parent.mUri.buildUpon().appendPath("_gen")
                    .appendPath(String.valueOf(mItems.size())).build();
        }

        /**
         * Add hints to the Slice being constructed
         */
        public Builder addHints(@SliceHint String... hints) {
            mHints.addAll(Arrays.asList(hints));
            return this;
        }

        /**
         * Add hints to the Slice being constructed
         */
        public Builder addHints(@SliceHint List<String> hints) {
            return addHints(hints.toArray(new String[hints.size()]));
        }

        /**
         * Add the spec for this slice.
         */
        public Builder setSpec(SliceSpec spec) {
            mSpec = spec;
            return this;
        }

        /**
         * Add a sub-slice to the slice being constructed
         */
        public Builder addSubSlice(@NonNull Slice slice) {
            return addSubSlice(slice, null);
        }

        /**
         * Add a sub-slice to the slice being constructed
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Builder addSubSlice(@NonNull Slice slice, @Nullable String subType) {
            mItems.add(new SliceItem(slice, SliceItem.FORMAT_SLICE, subType,
                    slice.getHints().toArray(new String[slice.getHints().size()])));
            return this;
        }

        /**
         * Add an action to the slice being constructed
         */
        public Slice.Builder addAction(@NonNull PendingIntent action, @NonNull Slice s) {
            return addAction(action, s, null);
        }

        /**
         * Add an action to the slice being constructed
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Slice.Builder addAction(@NonNull PendingIntent action, @NonNull Slice s,
                @Nullable String subType) {
            List<String> hints = s.getHints();
            s.mSpec = null;
            mItems.add(new SliceItem(action, s, SliceItem.FORMAT_ACTION, subType, hints.toArray(
                    new String[hints.size()])));
            return this;
        }

        /**
         * Add text to the slice being constructed
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Builder addText(CharSequence text, @Nullable String subType,
                @SliceHint String... hints) {
            mItems.add(new SliceItem(text, SliceItem.FORMAT_TEXT, subType, hints));
            return this;
        }

        /**
         * Add text to the slice being constructed
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Builder addText(CharSequence text, @Nullable String subType,
                @SliceHint List<String> hints) {
            return addText(text, subType, hints.toArray(new String[hints.size()]));
        }

        /**
         * Add an image to the slice being constructed
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Builder addIcon(Icon icon, @Nullable String subType, @SliceHint String... hints) {
            mItems.add(new SliceItem(icon, SliceItem.FORMAT_IMAGE, subType, hints));
            return this;
        }

        /**
         * Add an image to the slice being constructed
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Builder addIcon(Icon icon, @Nullable String subType, @SliceHint List<String> hints) {
            return addIcon(icon, subType, hints.toArray(new String[hints.size()]));
        }

        /**
         * Add remote input to the slice being constructed
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Slice.Builder addRemoteInput(RemoteInput remoteInput, @Nullable String subType,
                @SliceHint List<String> hints) {
            return addRemoteInput(remoteInput, subType, hints.toArray(new String[hints.size()]));
        }

        /**
         * Add remote input to the slice being constructed
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Slice.Builder addRemoteInput(RemoteInput remoteInput, @Nullable String subType,
                @SliceHint String... hints) {
            mItems.add(new SliceItem(remoteInput, SliceItem.FORMAT_REMOTE_INPUT,
                    subType, hints));
            return this;
        }

        /**
         * Add a color to the slice being constructed
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Builder addInt(int value, @Nullable String subType, @SliceHint String... hints) {
            mItems.add(new SliceItem(value, SliceItem.FORMAT_INT, subType, hints));
            return this;
        }

        /**
         * Add a color to the slice being constructed
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Builder addInt(int value, @Nullable String subType,
                @SliceHint List<String> hints) {
            return addInt(value, subType, hints.toArray(new String[hints.size()]));
        }

        /**
         * Add a timestamp to the slice being constructed
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Slice.Builder addTimestamp(long time, @Nullable String subType,
                @SliceHint String... hints) {
            mItems.add(new SliceItem(time, SliceItem.FORMAT_TIMESTAMP, subType,
                    hints));
            return this;
        }

        /**
         * Add a timestamp to the slice being constructed
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Slice.Builder addTimestamp(long time, @Nullable String subType,
                @SliceHint List<String> hints) {
            return addTimestamp(time, subType, hints.toArray(new String[hints.size()]));
        }

        /**
         * Add a bundle to the slice being constructed.
         * <p>Expected to be used for support library extension, should not be used for general
         * development
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Slice.Builder addBundle(Bundle bundle, @Nullable String subType,
                @SliceHint String... hints) {
            mItems.add(new SliceItem(bundle, SliceItem.FORMAT_BUNDLE, subType,
                    hints));
            return this;
        }

        /**
         * Add a bundle to the slice being constructed.
         * <p>Expected to be used for support library extension, should not be used for general
         * development
         * @param subType Optional template-specific type information
         * @see {@link SliceItem#getSubType()}
         */
        public Slice.Builder addBundle(Bundle bundle, @Nullable String subType,
                @SliceHint List<String> hints) {
            return addBundle(bundle, subType, hints.toArray(new String[hints.size()]));
        }

        /**
         * Construct the slice.
         */
        public Slice build() {
            return new Slice(mItems, mHints.toArray(new String[mHints.size()]), mUri, mSpec);
        }
    }

    public static final Creator<Slice> CREATOR = new Creator<Slice>() {
        @Override
        public Slice createFromParcel(Parcel in) {
            return new Slice(in);
        }

        @Override
        public Slice[] newArray(int size) {
            return new Slice[size];
        }
    };

    /**
     * @hide
     * @return A string representation of this slice.
     */
    public String toString() {
        return toString("");
    }

    private String toString(String indent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mItems.length; i++) {
            sb.append(indent);
            if (Objects.equals(mItems[i].getFormat(), SliceItem.FORMAT_SLICE)) {
                sb.append("slice:\n");
                sb.append(mItems[i].getSlice().toString(indent + "   "));
            } else if (Objects.equals(mItems[i].getFormat(), SliceItem.FORMAT_TEXT)) {
                sb.append("text: ");
                sb.append(mItems[i].getText());
                sb.append("\n");
            } else {
                sb.append(mItems[i].getFormat());
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * @deprecated TO BE REMOVED.
     */
    @Deprecated
    public static @Nullable Slice bindSlice(ContentResolver resolver,
            @NonNull Uri uri, @NonNull List<SliceSpec> supportedSpecs) {
        Preconditions.checkNotNull(uri, "uri");
        IContentProvider provider = resolver.acquireProvider(uri);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
        try {
            Bundle extras = new Bundle();
            extras.putParcelable(SliceProvider.EXTRA_BIND_URI, uri);
            extras.putParcelableArrayList(SliceProvider.EXTRA_SUPPORTED_SPECS,
                    new ArrayList<>(supportedSpecs));
            final Bundle res = provider.call(resolver.getPackageName(), SliceProvider.METHOD_SLICE,
                    null, extras);
            Bundle.setDefusable(res, true);
            if (res == null) {
                return null;
            }
            return res.getParcelable(SliceProvider.EXTRA_SLICE);
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return null;
        } finally {
            resolver.releaseProvider(provider);
        }
    }

    /**
     * @deprecated TO BE REMOVED.
     */
    @Deprecated
    public static @Nullable Slice bindSlice(Context context, @NonNull Intent intent,
            @NonNull List<SliceSpec> supportedSpecs) {
        return context.getSystemService(SliceManager.class).bindSlice(intent, supportedSpecs);
    }
}
