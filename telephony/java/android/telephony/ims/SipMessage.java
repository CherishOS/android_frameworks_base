/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telephony.ims;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a partially encoded SIP message. See RFC 3261 for more information on how SIP
 * messages are structured and used.
 * <p>
 * The SIP message is represented in a partially encoded form in order to allow for easier
 * verification and should not be used as a generic SIP message container.
 * @hide
 */
@SystemApi
public final class SipMessage implements Parcelable {
    // Should not be set to true for production!
    private static final boolean IS_DEBUGGING = Build.IS_ENG;

    private static final String[] SIP_REQUEST_METHODS = new String[] {"INVITE", "ACK", "OPTIONS",
            "BYE", "CANCEL", "REGISTER"};

    private final String mStartLine;
    private final String mHeaderSection;
    private final byte[] mContent;

    /**
     * Represents a partially encoded SIP message.
     *
     * @param startLine The start line of the message, containing either the request-line or
     *                  status-line.
     * @param headerSection A String containing the full unencoded SIP message header.
     * @param content UTF-8 encoded SIP message body.
     */
    public SipMessage(@NonNull String startLine, @NonNull String headerSection,
            @NonNull byte[] content) {
        if (startLine == null || headerSection == null || content == null) {
            throw new IllegalArgumentException("One or more null parameters entered");
        }
        mStartLine = startLine;
        mHeaderSection = headerSection;
        mContent = content;
    }

    /**
     * Private constructor used only for unparcelling.
     */
    private SipMessage(Parcel source) {
        mStartLine = source.readString();
        mHeaderSection = source.readString();
        mContent = new byte[source.readInt()];
        source.readByteArray(mContent);
    }
    /**
     * @return The start line of the SIP message, which contains either the request-line or
     * status-line.
     */
    public @NonNull String getStartLine() {
        return mStartLine;
    }

    /**
     * @return The full, unencoded header section of the SIP message.
     */
    public @NonNull String getHeaderSection() {
        return mHeaderSection;
    }

    /**
     * @return only the UTF-8 encoded SIP message body.
     */
    public @NonNull byte[] getContent() {
        return mContent;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mStartLine);
        dest.writeString(mHeaderSection);
        dest.writeInt(mContent.length);
        dest.writeByteArray(mContent);
    }

    public static final @NonNull Creator<SipMessage> CREATOR = new Creator<SipMessage>() {
        @Override
        public SipMessage createFromParcel(Parcel source) {
            return new SipMessage(source);
        }

        @Override
        public SipMessage[] newArray(int size) {
            return new SipMessage[size];
        }
    };

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append("StartLine: [");
        if (IS_DEBUGGING) {
            b.append(mStartLine);
        } else {
            b.append(sanitizeStartLineRequest(mStartLine));
        }
        b.append("], [");
        b.append("Header: [");
        if (IS_DEBUGGING) {
            b.append(mHeaderSection);
        } else {
            // only identify transaction id/call ID when it is available.
            b.append("***");
        }
        b.append("], ");
        b.append("Content: [NOT SHOWN]");
        return b.toString();
    }

    /**
     * Start lines containing requests are formatted: METHOD SP Request-URI SP SIP-Version CRLF.
     * Detect if this is a REQUEST and redact Request-URI portion here, as it contains PII.
     */
    private String sanitizeStartLineRequest(String startLine) {
        String[] splitLine = startLine.split(" ");
        if (splitLine == null || splitLine.length == 0)  {
            return "(INVALID STARTLINE)";
        }
        for (String method : SIP_REQUEST_METHODS) {
            if (splitLine[0].contains(method)) {
                return splitLine[0] + " <Request-URI> " + splitLine[2];
            }
        }
        return startLine;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SipMessage that = (SipMessage) o;
        return mStartLine.equals(that.mStartLine)
                && mHeaderSection.equals(that.mHeaderSection)
                && Arrays.equals(mContent, that.mContent);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mStartLine, mHeaderSection);
        result = 31 * result + Arrays.hashCode(mContent);
        return result;
    }
}
