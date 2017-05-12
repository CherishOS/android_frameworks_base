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

package android.service.autofill;

import static android.view.autofill.Helper.sDebug;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IntentSender;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DebugUtils;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * Information used to indicate that an {@link AutofillService} is interested on saving the
 * user-inputed data for future use, through a
 * {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)}
 * call.
 *
 * <p>A {@link SaveInfo} is always associated with a {@link FillResponse}, and it contains at least
 * two pieces of information:
 *
 * <ol>
 *   <li>The type of user data that would be saved (like passoword or credit card info).
 *   <li>The minimum set of views (represented by their {@link AutofillId}) that need to be changed
 *       to trigger a save request.
 * </ol>
 *
 *  Typically, the {@link SaveInfo} contains the same {@code id}s as the {@link Dataset}:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .add(new Dataset.Builder(createPresentation())
 *          .setValue(id1, AutofillValue.forText("homer"))
 *          .setValue(id2, AutofillValue.forText("D'OH!"))
 *          .build())
 *      .setSaveInfo(new SaveInfo.Builder(SaveInfo.SAVE_INFO_TYPE_PASSWORD, new int[] {id1, id2})
 *                  .build())
 *      .build();
 * </pre>
 *
 * There might be cases where the {@link AutofillService} knows how to fill the
 * {@link android.app.Activity}, but the user has no data for it. In that case, the
 * {@link FillResponse} should contain just the {@link SaveInfo}, but no {@link Dataset}s:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .setSaveInfo(new SaveInfo.Builder(SaveInfo.SAVE_INFO_TYPE_PASSWORD, new int[] {id1, id2})
 *                  .build())
 *      .build();
 * </pre>
 *
 * <p>There might be cases where the user data in the {@link AutofillService} is enough
 * to populate some fields but not all, and the service would still be interested on saving the
 * other fields. In this scenario, the service could set the
 * {@link SaveInfo.Builder#setOptionalIds(AutofillId[])} as well:
 *
 * <pre class="prettyprint">
 *   new FillResponse.Builder()
 *       .add(new Dataset.Builder(createPresentation())
 *          .setValue(id1, AutofillValue.forText("742 Evergreen Terrace"))  // street
 *          .setValue(id2, AutofillValue.forText("Springfield"))            // city
 *          .build())
 *       .setSaveInfo(new SaveInfo.Builder(SaveInfo.SAVE_INFO_TYPE_ADDRESS, new int[] {id1, id2})
 *                   .setOptionalIds(new int[] {id3, id4}) // state and zipcode
 *                   .build())
 *       .build();
 * </pre>
 *
 * The
 * {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)}
 * is triggered after a call to {@link AutofillManager#commit()}, but only when all conditions
 * below are met:
 *
 * <ol>
 *   <li>The {@link SaveInfo} associated with the {@link FillResponse} is not {@code null}.
 *   <li>The {@link AutofillValue} of all required views (as set by the {@code requiredIds} passed
 *       to {@link SaveInfo.Builder} constructor are not empty.
 *   <li>The {@link AutofillValue} of at least one view (be it required or optional) has changed
 *       (i.e., it's not the same value passed in a {@link Dataset}).
 *   <li>The user explicitly tapped the affordance asking to save data for autofill.
 * </ol>
 */
public final class SaveInfo implements Parcelable {

    /**
     * Type used on when the service can save the contents of an activity, but cannot describe what
     * the content is for.
     */
    public static final int SAVE_DATA_TYPE_GENERIC = 0x0;

    /**
     * Type used when the {@link FillResponse} represents user credentials that have a password.
     */
    public static final int SAVE_DATA_TYPE_PASSWORD = 0x01;

    /**
     * Type used on when the {@link FillResponse} represents a physical address (such as street,
     * city, state, etc).
     */
    public static final int SAVE_DATA_TYPE_ADDRESS = 0x02;

    /**
     * Type used when the {@link FillResponse} represents a credit card.
     */
    public static final int SAVE_DATA_TYPE_CREDIT_CARD = 0x04;

    /**
     * Type used when the {@link FillResponse} represents just an username, without a password.
     */
    public static final int SAVE_DATA_TYPE_USERNAME = 0x08;

    /**
     * Type used when the {@link FillResponse} represents just an email address, without a password.
     */
    public static final int SAVE_DATA_TYPE_EMAIL_ADDRESS = 0x10;

    /**
     * Style for the negative button of the save UI to cancel the
     * save operation. In this case, the user tapping the negative
     * button signals that they would prefer to not save the filled
     * content.
     */
    public static final int NEGATIVE_BUTTON_STYLE_CANCEL = 0;

    /**
     * Style for the negative button of the save UI to reject the
     * save operation. This could be useful if the user needs to
     * opt-in your service and the save prompt is an advertisement
     * of the potential value you can add to the user. In this
     * case, the user tapping the negative button sends a strong
     * signal that the feature may not be useful and you may
     * consider some backoff strategy.
     */
    public static final int NEGATIVE_BUTTON_STYLE_REJECT = 1;

    /** @hide */
    @IntDef(
        value = {
                NEGATIVE_BUTTON_STYLE_CANCEL,
                NEGATIVE_BUTTON_STYLE_REJECT})
    @Retention(RetentionPolicy.SOURCE)
    @interface NegativeButtonStyle{}

    /** @hide */
    @IntDef(
       flag = true,
       value = {
               SAVE_DATA_TYPE_GENERIC,
               SAVE_DATA_TYPE_PASSWORD,
               SAVE_DATA_TYPE_ADDRESS,
               SAVE_DATA_TYPE_CREDIT_CARD,
               SAVE_DATA_TYPE_USERNAME,
               SAVE_DATA_TYPE_EMAIL_ADDRESS})
    @Retention(RetentionPolicy.SOURCE)
    @interface SaveDataType{}

    /**
     * Usually {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)}
     * is called once the activity finishes. If this flag is set it is called once all saved views
     * become invisible.
     */
    public static final int FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE = 0x1;

    /** @hide */
    @IntDef(
            flag = true,
            value = {FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE})
    @Retention(RetentionPolicy.SOURCE)
    @interface SaveInfoFlags{}

    private final @SaveDataType int mType;
    private final @NegativeButtonStyle int mNegativeButtonStyle;
    private final IntentSender mNegativeActionListener;
    private final AutofillId[] mRequiredIds;
    private final AutofillId[] mOptionalIds;
    private final CharSequence mDescription;
    private final int mFlags;

    private SaveInfo(Builder builder) {
        mType = builder.mType;
        mNegativeButtonStyle = builder.mNegativeButtonStyle;
        mNegativeActionListener = builder.mNegativeActionListener;
        mRequiredIds = builder.mRequiredIds;
        mOptionalIds = builder.mOptionalIds;
        mDescription = builder.mDescription;
        mFlags = builder.mFlags;
    }

    /** @hide */
    public @NegativeButtonStyle int getNegativeActionStyle() {
        return mNegativeButtonStyle;
    }

    /** @hide */
    public @Nullable IntentSender getNegativeActionListener() {
        return mNegativeActionListener;
    }

    /** @hide */
    public AutofillId[] getRequiredIds() {
        return mRequiredIds;
    }

    /** @hide */
    public @Nullable AutofillId[] getOptionalIds() {
        return mOptionalIds;
    }

    /** @hide */
    public @SaveDataType int getType() {
        return mType;
    }

    /** @hide */
    public @SaveInfoFlags int getFlags() {
        return mFlags;
    }

    /** @hide */
    public CharSequence getDescription() {
        return mDescription;
    }

    /**
     * A builder for {@link SaveInfo} objects.
     */
    public static final class Builder {

        private final @SaveDataType int mType;
        private @NegativeButtonStyle int mNegativeButtonStyle = NEGATIVE_BUTTON_STYLE_CANCEL;
        private IntentSender mNegativeActionListener;
        private final AutofillId[] mRequiredIds;
        private AutofillId[] mOptionalIds;
        private CharSequence mDescription;
        private boolean mDestroyed;
        private int mFlags;

        /**
         * Creates a new builder.
         *
         * @param type the type of information the associated {@link FillResponse} represents, can
         * be any combination of {@link SaveInfo#SAVE_DATA_TYPE_GENERIC},
         * {@link SaveInfo#SAVE_DATA_TYPE_PASSWORD},
         * {@link SaveInfo#SAVE_DATA_TYPE_ADDRESS}, {@link SaveInfo#SAVE_DATA_TYPE_CREDIT_CARD},
         * {@link SaveInfo#SAVE_DATA_TYPE_USERNAME}, or
         * {@link SaveInfo#SAVE_DATA_TYPE_EMAIL_ADDRESS}.
         * @param requiredIds ids of all required views that will trigger a save request.
         *
         * <p>See {@link SaveInfo} for more info.
         *
         * @throws IllegalArgumentException if {@code requiredIds} is {@code null} or empty.
         */
        public Builder(@SaveDataType int type, @NonNull AutofillId[] requiredIds) {
            Preconditions.checkArgument(requiredIds != null && requiredIds.length > 0,
                    "must have at least one required id: " + Arrays.toString(requiredIds));
            mType = type;
            mRequiredIds = requiredIds;
        }

        /**
         * Set flags changing the save behavior.
         *
         * @param flags {@link #FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE} or 0.
         * @return This builder.
         */
        public @NonNull Builder setFlags(@SaveInfoFlags int flags) {
            throwIfDestroyed();

            mFlags = Preconditions.checkFlagsArgument(flags, FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE);
            return this;
        }

        /**
         * Sets the ids of additional, optional views the service would be interested to save.
         *
         * <p>See {@link SaveInfo} for more info.
         *
         * @param ids The ids of the optional views.
         * @return This builder.
         */
        public @NonNull Builder setOptionalIds(@Nullable AutofillId[] ids) {
            throwIfDestroyed();
            if (ids != null && ids.length != 0) {
                mOptionalIds = ids;
            }
            return this;
        }

        /**
         * Sets an optional description to be shown in the UI when the user is asked to save.
         *
         * <p>Typically, it describes how the data will be stored by the service, so it can help
         * users to decide whether they can trust the service to save their data.
         *
         * @param description a succint description.
         * @return This Builder.
         */
        public @NonNull Builder setDescription(@Nullable CharSequence description) {
            throwIfDestroyed();
            mDescription = description;
            return this;
        }

        /**
         * Sets the style and listener for the negative save action.
         *
         * <p>This allows a fill-provider to customize the style and be
         * notified when the user selects the negative action in the save
         * UI. Note that selecting the negative action regardless of its style
         * and listener being customized would dismiss the save UI and if a
         * custom listener intent is provided then this intent will be
         * started. The default style is {@link #NEGATIVE_BUTTON_STYLE_CANCEL}</p>
         *
         * @param style The action style.
         * @param listener The action listener.
         * @return This builder.
         *
         * @see #NEGATIVE_BUTTON_STYLE_CANCEL
         * @see #NEGATIVE_BUTTON_STYLE_REJECT
         *
         * @throws IllegalArgumentException If the style is invalid
         */
        public @NonNull Builder setNegativeAction(@NegativeButtonStyle int style,
                @Nullable IntentSender listener) {
            throwIfDestroyed();
            if (style != NEGATIVE_BUTTON_STYLE_CANCEL
                    && style != NEGATIVE_BUTTON_STYLE_REJECT) {
                throw new IllegalArgumentException("Invalid style: " + style);
            }
            mNegativeButtonStyle = style;
            mNegativeActionListener = listener;
            return this;
        }

        /**
         * Builds a new {@link SaveInfo} instance.
         */
        public SaveInfo build() {
            throwIfDestroyed();
            mDestroyed = true;
            return new SaveInfo(this);
        }

        private void throwIfDestroyed() {
            if (mDestroyed) {
                throw new IllegalStateException("Already called #build()");
            }
        }

    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return new StringBuilder("SaveInfo: [type=")
                .append(DebugUtils.flagsToString(SaveInfo.class, "SAVE_DATA_TYPE_", mType))
                .append(", requiredIds=").append(Arrays.toString(mRequiredIds))
                .append(", optionalIds=").append(Arrays.toString(mOptionalIds))
                .append(", description=").append(mDescription)
                .append(DebugUtils.flagsToString(SaveInfo.class, "NEGATIVE_BUTTON_STYLE_",
                        mNegativeButtonStyle))
                .append(", mFlags=").append(mFlags)
                .append("]").toString();
    }

    /////////////////////////////////////
    // Parcelable "contract" methods. //
    /////////////////////////////////////

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mType);
        parcel.writeParcelableArray(mRequiredIds, flags);
        parcel.writeInt(mNegativeButtonStyle);
        parcel.writeParcelable(mNegativeActionListener, flags);
        parcel.writeParcelableArray(mOptionalIds, flags);
        parcel.writeCharSequence(mDescription);
        parcel.writeInt(mFlags);
    }

    public static final Parcelable.Creator<SaveInfo> CREATOR = new Parcelable.Creator<SaveInfo>() {
        @Override
        public SaveInfo createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final Builder builder = new Builder(parcel.readInt(),
                    parcel.readParcelableArray(null, AutofillId.class));
            builder.setNegativeAction(parcel.readInt(), parcel.readParcelable(null));
            builder.setOptionalIds(parcel.readParcelableArray(null, AutofillId.class));
            builder.setDescription(parcel.readCharSequence());
            builder.setFlags(parcel.readInt());
            return builder.build();
        }

        @Override
        public SaveInfo[] newArray(int size) {
            return new SaveInfo[size];
        }
    };
}
