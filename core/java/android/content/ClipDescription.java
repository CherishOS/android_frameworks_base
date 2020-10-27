/**
 * Copyright (c) 2010, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.text.TextUtils;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Meta-data describing the contents of a {@link ClipData}.  Provides enough
 * information to know if you can handle the ClipData, but not the data
 * itself.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using the clipboard framework, read the
 * <a href="{@docRoot}guide/topics/clipboard/copy-paste.html">Copy and Paste</a>
 * developer guide.</p>
 * </div>
 */
public class ClipDescription implements Parcelable {
    /**
     * The MIME type for a clip holding plain text.
     */
    public static final String MIMETYPE_TEXT_PLAIN = "text/plain";

    /**
     * The MIME type for a clip holding HTML text.
     */
    public static final String MIMETYPE_TEXT_HTML = "text/html";

    /**
     * The MIME type for a clip holding one or more URIs.  This should be
     * used for URIs that are meaningful to a user (such as an http: URI).
     * It should <em>not</em> be used for a content: URI that references some
     * other piece of data; in that case the MIME type should be the type
     * of the referenced data.
     */
    public static final String MIMETYPE_TEXT_URILIST = "text/uri-list";

    /**
     * The MIME type for a clip holding an Intent.
     */
    public static final String MIMETYPE_TEXT_INTENT = "text/vnd.android.intent";

    /**
     * The MIME type for an activity. The ClipData must include intents with required extras
     * {@link #EXTRA_PENDING_INTENT} and {@link Intent#EXTRA_USER}, and an optional
     * {@link #EXTRA_ACTIVITY_OPTIONS}.
     * @hide
     */
    public static final String MIMETYPE_APPLICATION_ACTIVITY = "application/vnd.android.activity";

    /**
     * The MIME type for a shortcut. The ClipData must include intents with required extras
     * {@link #EXTRA_PENDING_INTENT} and {@link Intent#EXTRA_USER}, and an optional
     * {@link #EXTRA_ACTIVITY_OPTIONS}.
     * @hide
     */
    public static final String MIMETYPE_APPLICATION_SHORTCUT = "application/vnd.android.shortcut";

    /**
     * The MIME type for a task. The ClipData must include an intent with a required extra
     * {@link Intent#EXTRA_TASK_ID} of the task to launch.
     * @hide
     */
    public static final String MIMETYPE_APPLICATION_TASK = "application/vnd.android.task";

    /**
     * The MIME type for data whose type is otherwise unknown.
     * <p>
     * Per RFC 2046, the "application" media type is to be used for discrete
     * data which do not fit in any of the other categories, and the
     * "octet-stream" subtype is used to indicate that a body contains arbitrary
     * binary data.
     */
    public static final String MIMETYPE_UNKNOWN = "application/octet-stream";

    /**
     * The pending intent for the activity to launch.
     * <p>
     * Type: PendingIntent
     * </p>
     * @hide
     */
    public static final String EXTRA_PENDING_INTENT = "android.intent.extra.PENDING_INTENT";

    /**
     * The activity options bundle to use when launching an activity.
     * <p>
     * Type: Bundle
     * </p>
     * @hide
     */
    public static final String EXTRA_ACTIVITY_OPTIONS = "android.intent.extra.ACTIVITY_OPTIONS";


    final CharSequence mLabel;
    private final ArrayList<String> mMimeTypes;
    private PersistableBundle mExtras;
    private long mTimeStamp;

    /**
     * Create a new clip.
     *
     * @param label Label to show to the user describing this clip.
     * @param mimeTypes An array of MIME types this data is available as.
     */
    public ClipDescription(CharSequence label, String[] mimeTypes) {
        if (mimeTypes == null) {
            throw new NullPointerException("mimeTypes is null");
        }
        mLabel = label;
        mMimeTypes = new ArrayList<String>(Arrays.asList(mimeTypes));
    }

    /**
     * Create a copy of a ClipDescription.
     */
    public ClipDescription(ClipDescription o) {
        mLabel = o.mLabel;
        mMimeTypes = new ArrayList<String>(o.mMimeTypes);
        mTimeStamp = o.mTimeStamp;
    }

    /**
     * Helper to compare two MIME types, where one may be a pattern.
     * @param concreteType A fully-specified MIME type.
     * @param desiredType A desired MIME type that may be a pattern such as *&#47;*.
     * @return Returns true if the two MIME types match.
     */
    public static boolean compareMimeTypes(String concreteType, String desiredType) {
        final int typeLength = desiredType.length();
        if (typeLength == 3 && desiredType.equals("*/*")) {
            return true;
        }

        final int slashpos = desiredType.indexOf('/');
        if (slashpos > 0) {
            if (typeLength == slashpos+2 && desiredType.charAt(slashpos+1) == '*') {
                if (desiredType.regionMatches(0, concreteType, 0, slashpos+1)) {
                    return true;
                }
            } else if (desiredType.equals(concreteType)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Used for setting the timestamp at which the associated {@link ClipData} is copied to
     * global clipboard.
     *
     * @param timeStamp at which the associated {@link ClipData} is copied to clipboard in
     *                  {@link System#currentTimeMillis()} time base.
     * @hide
     */
    public void setTimestamp(long timeStamp) {
        mTimeStamp = timeStamp;
    }

    /**
     * Return the timestamp at which the associated {@link ClipData} is copied to global clipboard
     * in the {@link System#currentTimeMillis()} time base.
     *
     * @return timestamp at which the associated {@link ClipData} is copied to global clipboard
     *         or {@code 0} if it is not copied to clipboard.
     */
    public long getTimestamp() {
        return mTimeStamp;
    }

    /**
     * Return the label for this clip.
     */
    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * Check whether the clip description contains the given MIME type.
     *
     * @param mimeType The desired MIME type.  May be a pattern.
     * @return Returns true if one of the MIME types in the clip description
     * matches the desired MIME type, else false.
     */
    public boolean hasMimeType(String mimeType) {
        final int size = mMimeTypes.size();
        for (int i=0; i<size; i++) {
            if (compareMimeTypes(mMimeTypes.get(i), mimeType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether the clip description contains any of the given MIME types.
     *
     * @param targetMimeTypes The target MIME types. May use patterns.
     * @return Returns true if at least one of the MIME types in the clip description matches at
     * least one of the target MIME types, else false.
     *
     * @hide
     */
    public boolean hasMimeType(@NonNull String[] targetMimeTypes) {
        for (String targetMimeType : targetMimeTypes) {
            if (hasMimeType(targetMimeType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filter the clip description MIME types by the given MIME type.  Returns
     * all MIME types in the clip that match the given MIME type.
     *
     * @param mimeType The desired MIME type.  May be a pattern.
     * @return Returns an array of all matching MIME types.  If there are no
     * matching MIME types, null is returned.
     */
    public String[] filterMimeTypes(String mimeType) {
        ArrayList<String> array = null;
        final int size = mMimeTypes.size();
        for (int i=0; i<size; i++) {
            if (compareMimeTypes(mMimeTypes.get(i), mimeType)) {
                if (array == null) {
                    array = new ArrayList<String>();
                }
                array.add(mMimeTypes.get(i));
            }
        }
        if (array == null) {
            return null;
        }
        String[] rawArray = new String[array.size()];
        array.toArray(rawArray);
        return rawArray;
    }

    /**
     * Return the number of MIME types the clip is available in.
     */
    public int getMimeTypeCount() {
        return mMimeTypes.size();
    }

    /**
     * Return one of the possible clip MIME types.
     */
    public String getMimeType(int index) {
        return mMimeTypes.get(index);
    }

    /**
     * Add MIME types to the clip description.
     */
    void addMimeTypes(String[] mimeTypes) {
        for (int i=0; i!=mimeTypes.length; i++) {
            final String mimeType = mimeTypes[i];
            if (!mMimeTypes.contains(mimeType)) {
                mMimeTypes.add(mimeType);
            }
        }
    }

    /**
     * Retrieve extended data from the clip description.
     *
     * @return the bundle containing extended data previously set with
     * {@link #setExtras(PersistableBundle)}, or null if no extras have been set.
     *
     * @see #setExtras(PersistableBundle)
     */
    public PersistableBundle getExtras() {
        return mExtras;
    }

    /**
     * Add extended data to the clip description.
     *
     * @see #getExtras()
     */
    public void setExtras(PersistableBundle extras) {
        mExtras = new PersistableBundle(extras);
    }

    /** @hide */
    public void validate() {
        if (mMimeTypes == null) {
            throw new NullPointerException("null mime types");
        }
        final int size = mMimeTypes.size();
        if (size <= 0) {
            throw new IllegalArgumentException("must have at least 1 mime type");
        }
        for (int i=0; i<size; i++) {
            if (mMimeTypes.get(i) == null) {
                throw new NullPointerException("mime type at " + i + " is null");
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder(128);

        b.append("ClipDescription { ");
        toShortString(b, true);
        b.append(" }");

        return b.toString();
    }

    /**
     * Appends this description to the given builder.
     * @param redactContent If true, redacts common forms of PII; otherwise appends full details.
     * @hide
     */
    public boolean toShortString(StringBuilder b, boolean redactContent) {
        boolean first = !toShortStringTypesOnly(b);
        if (mLabel != null) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            if (redactContent) {
                b.append("hasLabel(").append(mLabel.length()).append(')');
            } else {
                b.append('"').append(mLabel).append('"');
            }
        }
        if (mExtras != null) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            if (redactContent) {
                if (mExtras.isParcelled()) {
                    // We don't want this toString function to trigger un-parcelling.
                    b.append("hasExtras");
                } else {
                    b.append("hasExtras(").append(mExtras.size()).append(')');
                }
            } else {
                b.append(mExtras.toString());
            }
        }
        if (mTimeStamp > 0) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append('<');
            b.append(TimeUtils.logTimeOfDay(mTimeStamp));
            b.append('>');
        }
        return !first;
    }

    /** @hide */
    public boolean toShortStringTypesOnly(StringBuilder b) {
        boolean first = true;
        final int size = mMimeTypes.size();
        for (int i=0; i<size; i++) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append(mMimeTypes.get(i));
        }
        return !first;
    }

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);

        final int size = mMimeTypes.size();
        for (int i = 0; i < size; i++) {
            proto.write(ClipDescriptionProto.MIME_TYPES, mMimeTypes.get(i));
        }

        if (mLabel != null) {
            proto.write(ClipDescriptionProto.LABEL, mLabel.toString());
        }
        if (mExtras != null) {
            mExtras.dumpDebug(proto, ClipDescriptionProto.EXTRAS);
        }
        if (mTimeStamp > 0) {
            proto.write(ClipDescriptionProto.TIMESTAMP_MS, mTimeStamp);
        }

        proto.end(token);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        TextUtils.writeToParcel(mLabel, dest, flags);
        dest.writeStringList(mMimeTypes);
        dest.writePersistableBundle(mExtras);
        dest.writeLong(mTimeStamp);
    }

    ClipDescription(Parcel in) {
        mLabel = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mMimeTypes = in.createStringArrayList();
        mExtras = in.readPersistableBundle();
        mTimeStamp = in.readLong();
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ClipDescription> CREATOR =
        new Parcelable.Creator<ClipDescription>() {

            public ClipDescription createFromParcel(Parcel source) {
                return new ClipDescription(source);
            }

            public ClipDescription[] newArray(int size) {
                return new ClipDescription[size];
            }
        };
}
