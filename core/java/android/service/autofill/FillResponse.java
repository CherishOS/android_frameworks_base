/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.service.autofill.FillRequest.INVALID_REQUEST_ID;
import static android.view.autofill.Helper.sDebug;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.Activity;
import android.content.IntentSender;
import android.content.pm.ParceledListSlice;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.autofill.AutofillId;
import android.widget.RemoteViews;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Response for a {@link
 * AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)}.
 *
 * <p>See the main {@link AutofillService} documentation for more details and examples.
 */
public final class FillResponse implements Parcelable {

    /**
     * Must be set in the last response to generate
     * {@link FillEventHistory.Event#TYPE_CONTEXT_COMMITTED} events.
     */
    public static final int FLAG_TRACK_CONTEXT_COMMITED = 0x1;

    /**
     * Used in conjunction to {@link FillResponse.Builder#disableAutofill(long)} to disable autofill
     * only for the activiy associated with the {@link FillResponse}, instead of the whole app.
     */
    public static final int FLAG_DISABLE_ACTIVITY_ONLY = 0x2;

    /** @hide */
    @IntDef(flag = true, value = {
            FLAG_TRACK_CONTEXT_COMMITED,
            FLAG_DISABLE_ACTIVITY_ONLY
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface FillResponseFlags {}

    private final @Nullable ParceledListSlice<Dataset> mDatasets;
    private final @Nullable SaveInfo mSaveInfo;
    private final @Nullable Bundle mClientState;
    private final @Nullable RemoteViews mPresentation;
    private final @Nullable IntentSender mAuthentication;
    private final @Nullable AutofillId[] mAuthenticationIds;
    private final @Nullable AutofillId[] mIgnoredIds;
    private final long mDisableDuration;
    private final @Nullable FieldsDetection mFieldsDetection;
    private final int mFlags;
    private int mRequestId;

    private FillResponse(@NonNull Builder builder) {
        mDatasets = (builder.mDatasets != null) ? new ParceledListSlice<>(builder.mDatasets) : null;
        mSaveInfo = builder.mSaveInfo;
        mClientState = builder.mClientState;
        mPresentation = builder.mPresentation;
        mAuthentication = builder.mAuthentication;
        mAuthenticationIds = builder.mAuthenticationIds;
        mIgnoredIds = builder.mIgnoredIds;
        mDisableDuration = builder.mDisableDuration;
        mFieldsDetection = builder.mFieldsDetection;
        mFlags = builder.mFlags;
        mRequestId = INVALID_REQUEST_ID;
    }

    /** @hide */
    public @Nullable Bundle getClientState() {
        return mClientState;
    }

    /** @hide */
    public @Nullable List<Dataset> getDatasets() {
        return (mDatasets != null) ? mDatasets.getList() : null;
    }

    /** @hide */
    public @Nullable SaveInfo getSaveInfo() {
        return mSaveInfo;
    }

    /** @hide */
    public @Nullable RemoteViews getPresentation() {
        return mPresentation;
    }

    /** @hide */
    public @Nullable IntentSender getAuthentication() {
        return mAuthentication;
    }

    /** @hide */
    public @Nullable AutofillId[] getAuthenticationIds() {
        return mAuthenticationIds;
    }

    /** @hide */
    public @Nullable AutofillId[] getIgnoredIds() {
        return mIgnoredIds;
    }

    /** @hide */
    public long getDisableDuration() {
        return mDisableDuration;
    }

    /** @hide */
    public @Nullable FieldsDetection getFieldsDetection() {
        return mFieldsDetection;
    }

    /** @hide */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Associates a {@link FillResponse} to a request.
     *
     * <p>Set inside of the {@link FillCallback} code, not the {@link AutofillService}.
     *
     * @param requestId The id of the request to associate the response to.
     *
     * @hide
     */
    public void setRequestId(int requestId) {
        mRequestId = requestId;
    }

    /** @hide */
    public int getRequestId() {
        return mRequestId;
    }

    /**
     * Builder for {@link FillResponse} objects. You must to provide at least
     * one dataset or set an authentication intent with a presentation view.
     */
    public static final class Builder {
        private ArrayList<Dataset> mDatasets;
        private SaveInfo mSaveInfo;
        private Bundle mClientState;
        private RemoteViews mPresentation;
        private IntentSender mAuthentication;
        private AutofillId[] mAuthenticationIds;
        private AutofillId[] mIgnoredIds;
        private long mDisableDuration;
        private FieldsDetection mFieldsDetection;
        private int mFlags;
        private boolean mDestroyed;

        /**
         * Triggers a custom UI before before autofilling the screen with any data set in this
         * response.
         *
         * <p><b>Note:</b> Although the name of this method suggests that it should be used just for
         * authentication flow, it can be used for other advanced flows; see {@link AutofillService}
         * for examples.
         *
         * <p>This is typically useful when a user interaction is required to unlock their
         * data vault if you encrypt the data set labels and data set data. It is recommended
         * to encrypt only the sensitive data and not the data set labels which would allow
         * auth on the data set level leading to a better user experience. Note that if you
         * use sensitive data as a label, for example an email address, then it should also
         * be encrypted. The provided {@link android.app.PendingIntent intent} must be an
         * {@link Activity} which implements your authentication flow. Also if you provide an auth
         * intent you also need to specify the presentation view to be shown in the fill UI
         * for the user to trigger your authentication flow.
         *
         * <p>When a user triggers autofill, the system launches the provided intent
         * whose extras will have the
         * {@link android.view.autofill.AutofillManager#EXTRA_ASSIST_STRUCTURE screen
         * content} and your {@link android.view.autofill.AutofillManager#EXTRA_CLIENT_STATE
         * client state}. Once you complete your authentication flow you should set the
         * {@link Activity} result to {@link android.app.Activity#RESULT_OK} and set the
         * {@link android.view.autofill.AutofillManager#EXTRA_AUTHENTICATION_RESULT} extra
         * with the fully populated {@link FillResponse response} (or {@code null} if the screen
         * cannot be autofilled).
         *
         * <p>For example, if you provided an empty {@link FillResponse response} because the
         * user's data was locked and marked that the response needs an authentication then
         * in the response returned if authentication succeeds you need to provide all
         * available data sets some of which may need to be further authenticated, for
         * example a credit card whose CVV needs to be entered.
         *
         * <p>If you provide an authentication intent you must also provide a presentation
         * which is used to visualize visualize the response for triggering the authentication
         * flow.
         *
         * <p><b>Note:</b> Do not make the provided pending intent
         * immutable by using {@link android.app.PendingIntent#FLAG_IMMUTABLE} as the
         * platform needs to fill in the authentication arguments.
         *
         * @param authentication Intent to an activity with your authentication flow.
         * @param presentation The presentation to visualize the response.
         * @param ids id of Views that when focused will display the authentication UI.
         *
         * @return This builder.
         * @throws IllegalArgumentException if {@code ids} is {@code null} or empty, or if
         * both {@code authentication} and {@code presentation} are {@code null}, or if
         * both {@code authentication} and {@code presentation} are non-{@code null}
         *
         * @see android.app.PendingIntent#getIntentSender()
         */
        public @NonNull Builder setAuthentication(@NonNull AutofillId[] ids,
                @Nullable IntentSender authentication, @Nullable RemoteViews presentation) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            if (ids == null || ids.length == 0) {
                throw new IllegalArgumentException("ids cannot be null or empry");
            }
            if (authentication == null ^ presentation == null) {
                throw new IllegalArgumentException("authentication and presentation"
                        + " must be both non-null or null");
            }
            mAuthentication = authentication;
            mPresentation = presentation;
            mAuthenticationIds = ids;
            return this;
        }

        /**
         * Specifies views that should not trigger new
         * {@link AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal,
         * FillCallback)} requests.
         *
         * <p>This is typically used when the service cannot autofill the view; for example, a
         * text field representing the result of a Captcha challenge.
         */
        public Builder setIgnoredIds(AutofillId...ids) {
            throwIfDestroyed();
            mIgnoredIds = ids;
            return this;
        }

        /**
         * Adds a new {@link Dataset} to this response.
         *
         * <p><b>Note: </b> on Android {@link android.os.Build.VERSION_CODES#O}, the total number of
         * datasets is limited by the Binder transaction size, so it's recommended to keep it
         * small (in the range of 10-20 at most) and use pagination by adding a fake
         * {@link Dataset.Builder#setAuthentication(IntentSender) authenticated dataset} at the end
         * with a presentation string like "Next 10" that would return a new {@link FillResponse}
         * with the next 10 datasets, and so on. This limitation was lifted on
         * Android {@link android.os.Build.VERSION_CODES#O_MR1}, although the Binder transaction
         * size can still be reached if each dataset itself is too big.
         *
         * @return This builder.
         */
        public @NonNull Builder addDataset(@Nullable Dataset dataset) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            if (dataset == null) {
                return this;
            }
            if (mDatasets == null) {
                mDatasets = new ArrayList<>();
            }
            if (!mDatasets.add(dataset)) {
                return this;
            }
            return this;
        }

        /**
         * Sets the {@link SaveInfo} associated with this response.
         *
         * @return This builder.
         */
        public @NonNull Builder setSaveInfo(@NonNull SaveInfo saveInfo) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            mSaveInfo = saveInfo;
            return this;
        }

        /**
         * Sets a {@link Bundle state} that will be passed to subsequent APIs that
         * manipulate this response. For example, they are passed to subsequent
         * calls to {@link AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal,
         * FillCallback)} and {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)}.
         * You can use this to store intermediate state that is persistent across multiple
         * fill requests and the subsequent save request.
         *
         * <p>If this method is called on multiple {@link FillResponse} objects for the same
         * screen, just the latest bundle is passed back to the service.
         *
         * <p>Once a {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)
         * save request} is made the client state is cleared.
         *
         * @param clientState The custom client state.
         * @return This builder.
         */
        public Builder setClientState(@Nullable Bundle clientState) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            mClientState = clientState;
            return this;
        }

        /**
         * TODO(b/67867469):
         *  - javadoc it
         *  - javadoc how to check results
         *  - unhide
         *  - unhide / remove testApi
         *  - throw exception (and document) if response has datasets or saveinfo
         *  - throw exception (and document) if id on fieldsDetection is ignored
         *
         * @hide
         */
        @TestApi
        public Builder setFieldsDetection(@NonNull FieldsDetection fieldsDetection) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            mFieldsDetection = Preconditions.checkNotNull(fieldsDetection);
            return this;
        }

        /**
         * Sets flags changing the response behavior.
         *
         * @param flags a combination of {@link #FLAG_TRACK_CONTEXT_COMMITED} and
         * {@link #FLAG_DISABLE_ACTIVITY_ONLY}, or {@code 0}.
         *
         * @return This builder.
         */
        public Builder setFlags(@FillResponseFlags int flags) {
            throwIfDestroyed();
            mFlags = Preconditions.checkFlagsArgument(flags,
                    FLAG_TRACK_CONTEXT_COMMITED | FLAG_DISABLE_ACTIVITY_ONLY);
            return this;
        }

        /**
         * Disables autofill for the app or activity.
         *
         * <p>This method is useful to optimize performance in cases where the service knows it
         * can not autofill an app&mdash;for example, when the service has a list of "blacklisted"
         * apps such as office suites.
         *
         * <p>By default, it disables autofill for all activities in the app, unless the response is
         * {@link #setFlags(int) flagged} with {@link #FLAG_DISABLE_ACTIVITY_ONLY}.
         *
         * <p>Autofill for the app or activity is automatically re-enabled after any of the
         * following conditions:
         *
         * <ol>
         *   <li>{@code duration} milliseconds have passed.
         *   <li>The autofill service for the user has changed.
         *   <li>The device has rebooted.
         * </ol>
         *
         * <p><b>Note:</b> Activities that are running when autofill is re-enabled remain
         * disabled for autofill until they finish and restart.
         *
         * @param duration duration to disable autofill, in milliseconds.
         *
         * @return this builder
         *
         * @throws IllegalArgumentException if {@code duration} is not a positive number.
         * @throws IllegalStateException if either {@link #addDataset(Dataset)},
         *       {@link #setAuthentication(AutofillId[], IntentSender, RemoteViews)},
         *       {@link #setSaveInfo(SaveInfo)}, or {@link #setClientState(Bundle)}
         *       was already called.
         */
        public Builder disableAutofill(long duration) {
            throwIfDestroyed();
            if (duration <= 0) {
                throw new IllegalArgumentException("duration must be greater than 0");
            }
            if (mAuthentication != null || mDatasets != null || mSaveInfo != null
                    || mFieldsDetection != null || mClientState != null) {
                throw new IllegalStateException("disableAutofill() must be the only method called");
            }

            mDisableDuration = duration;
            return this;
        }

        /**
         * Builds a new {@link FillResponse} instance.
         *
         * @throws IllegalStateException if any of the following conditions occur:
         * <ol>
         *   <li>{@link #build()} was already called.
         *   <li>No call was made to {@link #addDataset(Dataset)},
         *       {@link #setAuthentication(AutofillId[], IntentSender, RemoteViews)},
         *       {@link #setSaveInfo(SaveInfo)}, {@link #disableAutofill(long)},
         *       or {@link #setClientState(Bundle)}.
         * </ol>
         *
         * @return A built response.
         */
        public FillResponse build() {
            throwIfDestroyed();
            if (mAuthentication == null && mDatasets == null && mSaveInfo == null
                    && mDisableDuration == 0 && mFieldsDetection == null && mClientState == null) {
                throw new IllegalStateException("need to provide: at least one DataSet, or a "
                        + "SaveInfo, or an authentication with a presentation, "
                        + "or a FieldsDetection, or a client state, or disable autofill");
            }
            mDestroyed = true;
            return new FillResponse(this);
        }

        private void throwIfDestroyed() {
            if (mDestroyed) {
                throw new IllegalStateException("Already called #build()");
            }
        }

        private void throwIfDisableAutofillCalled() {
            if (mDisableDuration > 0) {
                throw new IllegalStateException("Already called #disableAutofill()");
            }
        }
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        // TODO: create a dump() method instead
        return new StringBuilder(
                "FillResponse : [mRequestId=" + mRequestId)
                .append(", datasets=").append(mDatasets == null ? "N/A" : mDatasets.getList())
                .append(", saveInfo=").append(mSaveInfo)
                .append(", clientState=").append(mClientState != null)
                .append(", hasPresentation=").append(mPresentation != null)
                .append(", hasAuthentication=").append(mAuthentication != null)
                .append(", authenticationIds=").append(Arrays.toString(mAuthenticationIds))
                .append(", ignoredIds=").append(Arrays.toString(mIgnoredIds))
                .append(", disableDuration=").append(mDisableDuration)
                .append(", flags=").append(mFlags)
                .append(", fieldDetection=").append(mFieldsDetection)
                .append("]")
                .toString();
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
        parcel.writeParcelable(mDatasets, flags);
        parcel.writeParcelable(mSaveInfo, flags);
        parcel.writeParcelable(mClientState, flags);
        parcel.writeParcelableArray(mAuthenticationIds, flags);
        parcel.writeParcelable(mAuthentication, flags);
        parcel.writeParcelable(mPresentation, flags);
        parcel.writeParcelableArray(mIgnoredIds, flags);
        parcel.writeLong(mDisableDuration);
        parcel.writeParcelable(mFieldsDetection, flags);
        parcel.writeInt(mFlags);
        parcel.writeInt(mRequestId);
    }

    public static final Parcelable.Creator<FillResponse> CREATOR =
            new Parcelable.Creator<FillResponse>() {
        @Override
        public FillResponse createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final Builder builder = new Builder();
            final ParceledListSlice<Dataset> datasetSlice = parcel.readParcelable(null);
            final List<Dataset> datasets = (datasetSlice != null) ? datasetSlice.getList() : null;
            final int datasetCount = (datasets != null) ? datasets.size() : 0;
            for (int i = 0; i < datasetCount; i++) {
                builder.addDataset(datasets.get(i));
            }
            builder.setSaveInfo(parcel.readParcelable(null));
            builder.setClientState(parcel.readParcelable(null));

            // Sets authentication state.
            final AutofillId[] authenticationIds = parcel.readParcelableArray(null,
                    AutofillId.class);
            final IntentSender authentication = parcel.readParcelable(null);
            final RemoteViews presentation = parcel.readParcelable(null);
            if (authenticationIds != null) {
                builder.setAuthentication(authenticationIds, authentication, presentation);
            }

            builder.setIgnoredIds(parcel.readParcelableArray(null, AutofillId.class));
            final long disableDuration = parcel.readLong();
            if (disableDuration > 0) {
                builder.disableAutofill(disableDuration);
            }
            final FieldsDetection fieldsDetection = parcel.readParcelable(null);
            if (fieldsDetection != null) {
                builder.setFieldsDetection(fieldsDetection);
            }
            builder.setFlags(parcel.readInt());

            final FillResponse response = builder.build();
            response.setRequestId(parcel.readInt());

            return response;
        }

        @Override
        public FillResponse[] newArray(int size) {
            return new FillResponse[size];
        }
    };
}
