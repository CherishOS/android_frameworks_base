/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.view.contentcapture;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.autofill.AutofillId;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** @hide */
@SystemApi
public final class ContentCaptureEvent implements Parcelable {

    /** @hide */
    public static final int TYPE_SESSION_FINISHED = -2;
    /** @hide */
    public static final int TYPE_SESSION_STARTED = -1;

    /**
     * Called when a node has been added to the screen and is visible to the user.
     *
     * <p>The metadata of the node is available through {@link #getViewNode()}.
     */
    public static final int TYPE_VIEW_APPEARED = 1;

    /**
     * Called when a node has been removed from the screen and is not visible to the user anymore.
     *
     * <p>The id of the node is available through {@link #getId()}.
     */
    public static final int TYPE_VIEW_DISAPPEARED = 2;

    /**
     * Called when the text of a node has been changed.
     *
     * <p>The id of the node is available through {@link #getId()}, and the new text is
     * available through {@link #getText()}.
     */
    public static final int TYPE_VIEW_TEXT_CHANGED = 3;

    // TODO(b/111276913): add event to indicate when FLAG_SECURE was changed?

    /** @hide */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_VIEW_APPEARED,
            TYPE_VIEW_DISAPPEARED,
            TYPE_VIEW_TEXT_CHANGED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventType{}

    private final @NonNull String mSessionId;
    private final int mType;
    private final long mEventTime;
    private final int mFlags;
    private @Nullable AutofillId mId;
    private @Nullable ViewNode mNode;
    private @Nullable CharSequence mText;
    private @Nullable String mParentSessionId;
    private @Nullable ContentCaptureContext mClientContext;

    /** @hide */
    public ContentCaptureEvent(@NonNull String sessionId, int type, long eventTime, int flags) {
        mSessionId = sessionId;
        mType = type;
        mEventTime = eventTime;
        mFlags = flags;
    }

    /** @hide */
    public ContentCaptureEvent(@NonNull String sessionId, int type, int flags) {
        this(sessionId, type, System.currentTimeMillis(), flags);
    }

    /** @hide */
    public ContentCaptureEvent(@NonNull String sessionId, int type) {
        this(sessionId, type, /* flags= */ 0);
    }

    /** @hide */
    public ContentCaptureEvent setAutofillId(@NonNull AutofillId id) {
        mId = Preconditions.checkNotNull(id);
        return this;
    }

    /**
     * Used by {@link #TYPE_SESSION_STARTED} and {@link #TYPE_SESSION_FINISHED}.
     *
     * @hide
     */
    public ContentCaptureEvent setParentSessionId(@NonNull String parentSessionId) {
        mParentSessionId = parentSessionId;
        return this;
    }

    /**
     * Used by {@link #TYPE_SESSION_STARTED} and {@link #TYPE_SESSION_FINISHED}.
     *
     * @hide
     */
    public ContentCaptureEvent setClientContext(@NonNull ContentCaptureContext clientContext) {
        mClientContext = clientContext;
        return this;
    }

    /** @hide */
    @NonNull
    public String getSessionId() {
        return mSessionId;
    }

    /**
     * Used by {@link #TYPE_SESSION_STARTED} and {@link #TYPE_SESSION_FINISHED}.
     *
     * @hide
     */
    @Nullable
    public String getParentSessionId() {
        return mParentSessionId;
    }

    /**
     * Used by {@link #TYPE_SESSION_STARTED}.
     *
     * @hide
     */
    @Nullable
    public ContentCaptureContext getClientContext() {
        return mClientContext;
    }

    /** @hide */
    @NonNull
    public ContentCaptureEvent setViewNode(@NonNull ViewNode node) {
        mNode = Preconditions.checkNotNull(node);
        return this;
    }

    /** @hide */
    @NonNull
    public ContentCaptureEvent setText(@Nullable CharSequence text) {
        mText = text;
        return this;
    }

    /**
     * Gets the type of the event.
     *
     * @return one of {@link #TYPE_VIEW_APPEARED}, {@link #TYPE_VIEW_DISAPPEARED},
     * or {@link #TYPE_VIEW_TEXT_CHANGED}.
     */
    public @EventType int getType() {
        return mType;
    }

    /**
     * Gets when the event was generated, in millis since epoch.
     */
    public long getEventTime() {
        return mEventTime;
    }

    /**
     * Gets optional flags associated with the event.
     *
     * @return either {@code 0} or
     * {@link android.view.contentcapture.ContentCaptureSession#FLAG_USER_INPUT}.
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Gets the whole metadata of the node associated with the event.
     *
     * <p>Only set on {@link #TYPE_VIEW_APPEARED} events.
     */
    @Nullable
    public ViewNode getViewNode() {
        return mNode;
    }

    /**
     * Gets the {@link AutofillId} of the node associated with the event.
     *
     * <p>Only set on {@link #TYPE_VIEW_DISAPPEARED} and {@link #TYPE_VIEW_TEXT_CHANGED} events.
     */
    @Nullable
    public AutofillId getId() {
        return mId;
    }

    /**
     * Gets the current text of the node associated with the event.
     *
     * <p>Only set on {@link #TYPE_VIEW_TEXT_CHANGED} events.
     */
    @Nullable
    public CharSequence getText() {
        return mText;
    }

    /** @hide */
    public void dump(@NonNull PrintWriter pw) {
        pw.print("type="); pw.print(getTypeAsString(mType));
        pw.print(", time="); pw.print(mEventTime);
        if (mFlags > 0) {
            pw.print(", flags="); pw.print(mFlags);
        }
        if (mId != null) {
            pw.print(", id="); pw.print(mId);
        }
        if (mNode != null) {
            pw.print(", mNode.id="); pw.print(mNode.getAutofillId());
        }
        if (mSessionId != null) {
            pw.print(", sessionId="); pw.print(mSessionId);
        }
        if (mParentSessionId != null) {
            pw.print(", parentSessionId="); pw.print(mParentSessionId);
        }
        if (mText != null) {
            // Cannot print content because could have PII
            pw.print(", text="); pw.print(mText.length()); pw.print("_chars");
        }
    }

    @Override
    public String toString() {
        final StringBuilder string = new StringBuilder("ContentCaptureEvent[type=")
                .append(getTypeAsString(mType));
        if (mFlags > 0) {
            string.append(", flags=").append(mFlags);
        }
        if (mId != null) {
            string.append(", id=").append(mId);
        }
        if (mNode != null) {
            final String className = mNode.getClassName();
            if (mNode != null) {
                string.append(", class=").append(className);
            }
            string.append(", id=").append(mNode.getAutofillId());
        }
        return string.append(']').toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mSessionId);
        parcel.writeInt(mType);
        parcel.writeLong(mEventTime);
        parcel.writeInt(mFlags);
        parcel.writeParcelable(mId, flags);
        ViewNode.writeToParcel(parcel, mNode, flags);
        parcel.writeCharSequence(mText);
        if (mType == TYPE_SESSION_STARTED || mType == TYPE_SESSION_FINISHED) {
            parcel.writeString(mParentSessionId);
        }
        if (mType == TYPE_SESSION_STARTED) {
            parcel.writeParcelable(mClientContext, flags);
        }
    }

    public static final Parcelable.Creator<ContentCaptureEvent> CREATOR =
            new Parcelable.Creator<ContentCaptureEvent>() {

        @Override
        public ContentCaptureEvent createFromParcel(Parcel parcel) {
            final String sessionId = parcel.readString();
            final int type = parcel.readInt();
            final long eventTime  = parcel.readLong();
            final int flags = parcel.readInt();
            final ContentCaptureEvent event =
                    new ContentCaptureEvent(sessionId, type, eventTime, flags);
            final AutofillId id = parcel.readParcelable(null);
            if (id != null) {
                event.setAutofillId(id);
            }
            final ViewNode node = ViewNode.readFromParcel(parcel);
            if (node != null) {
                event.setViewNode(node);
            }
            event.setText(parcel.readCharSequence());
            if (type == TYPE_SESSION_STARTED || type == TYPE_SESSION_FINISHED) {
                event.setParentSessionId(parcel.readString());
            }
            if (type == TYPE_SESSION_STARTED) {
                event.setClientContext(parcel.readParcelable(null));
            }
            return event;
        }

        @Override
        public ContentCaptureEvent[] newArray(int size) {
            return new ContentCaptureEvent[size];
        }
    };

    /** @hide */
    public static String getTypeAsString(@EventType int type) {
        switch (type) {
            case TYPE_SESSION_STARTED:
                return "SESSION_STARTED";
            case TYPE_SESSION_FINISHED:
                return "SESSION_FINISHED";
            case TYPE_VIEW_APPEARED:
                return "VIEW_APPEARED";
            case TYPE_VIEW_DISAPPEARED:
                return "VIEW_DISAPPEARED";
            case TYPE_VIEW_TEXT_CHANGED:
                return "VIEW_TEXT_CHANGED";
            default:
                return "UKNOWN_TYPE: " + type;
        }
    }
}
