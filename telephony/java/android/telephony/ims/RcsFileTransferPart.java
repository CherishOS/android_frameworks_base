/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.net.Uri;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A part of a composite {@link RcsMessage} that holds a file transfer. Please see Section 7
 * (File Transfer) - GSMA RCC.71 (RCS Universal Profile Service Definition Document)
 *
 * @hide - TODO(109759350) make this public
 */
public class RcsFileTransferPart {
    /**
     * The status to indicate that this {@link RcsFileTransferPart} is not set yet.
     */
    public static final int NOT_SET = 0;

    /**
     * The status to indicate that this {@link RcsFileTransferPart} is a draft and is not in the
     * process of sending yet.
     */
    public static final int DRAFT = 1;

    /**
     * The status to indicate that this {@link RcsFileTransferPart} is actively being sent right
     * now.
     */
    public static final int SENDING = 2;

    /**
     * The status to indicate that this {@link RcsFileTransferPart} was being sent, but the user has
     * paused the sending process.
     */
    public static final int SENDING_PAUSED = 3;

    /**
     * The status to indicate that this {@link RcsFileTransferPart} was attempted, but failed to
     * send.
     */
    public static final int SENDING_FAILED = 4;

    /**
     * The status to indicate that this {@link RcsFileTransferPart} is permanently cancelled to
     * send.
     */
    public static final int SENDING_CANCELLED = 5;

    /**
     * The status to indicate that this {@link RcsFileTransferPart} is actively being downloaded
     * right now.
     */
    public static final int DOWNLOADING = 6;

    /**
     * The status to indicate that this {@link RcsFileTransferPart} was being downloaded, but the
     * user paused the downloading process.
     */
    public static final int DOWNLOADING_PAUSED = 7;

    /**
     * The status to indicate that this {@link RcsFileTransferPart} was attempted, but failed to
     * download.
     */
    public static final int DOWNLOADING_FAILED = 8;

    /**
     * The status to indicate that this {@link RcsFileTransferPart} is permanently cancelled to
     * download.
     */
    public static final int DOWNLOADING_CANCELLED = 9;

    /**
     * The status to indicate that this {@link RcsFileTransferPart} was successfully sent or
     * received.
     */
    public static final int SUCCEEDED = 10;

    @IntDef({
            DRAFT, SENDING, SENDING_PAUSED, SENDING_FAILED, SENDING_CANCELLED, DOWNLOADING,
            DOWNLOADING_PAUSED, DOWNLOADING_FAILED, DOWNLOADING_CANCELLED, SUCCEEDED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RcsFileTransferStatus {
    }

    private int mId;

    /**
     * @hide
     */
    RcsFileTransferPart(int id) {
        mId = id;
    }

    /**
     * @hide
     */
    public void setId(int id) {
        mId = id;
    }

    /**
     * @hide
     */
    public int getId() {
        return mId;
    }

    /**
     * Sets the RCS file transfer session ID for this file transfer and persists into storage.
     *
     * @param sessionId The session ID to be used for this file transfer.
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setFileTransferSessionId(String sessionId) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setFileTransferSessionId(mId, sessionId));
    }

    /**
     * @return Returns the file transfer session ID.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public String getFileTransferSessionId() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getFileTransferSessionId(mId));
    }

    /**
     * Sets the content URI for this file transfer and persists into storage. The file transfer
     * should be reachable using this URI.
     *
     * @param contentUri The URI for this file transfer.
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setContentUri(Uri contentUri) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setFileTransferContentUri(mId, contentUri));
    }

    /**
     * @return Returns the URI for this file transfer
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @Nullable
    @WorkerThread
    public Uri getContentUri() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getFileTransferContentUri(mId));
    }

    /**
     * Sets the MIME type of this file transfer and persists into storage. Whether this type
     * actually matches any known or supported types is not checked.
     *
     * @param contentMimeType The type of this file transfer.
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setContentMimeType(String contentMimeType) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.setFileTransferContentType(mId, contentMimeType));
    }

    /**
     * @return Returns the content type of this file transfer
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    @Nullable
    public String getContentMimeType() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getFileTransferContentType(mId));
    }

    /**
     * Sets the content length (i.e. file size) for this file transfer and persists into storage.
     *
     * @param contentLength The content length of this file transfer
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setFileSize(long contentLength) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.setFileTransferFileSize(mId, contentLength));
    }

    /**
     * @return Returns the content length (i.e. file size) for this file transfer.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public long getFileSize() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getFileTransferFileSize(mId));
    }

    /**
     * Sets the transfer offset for this file transfer and persists into storage. The file transfer
     * offset is defined as how many bytes have been successfully transferred to the receiver of
     * this file transfer.
     *
     * @param transferOffset The transfer offset for this file transfer.
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setTransferOffset(long transferOffset) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.setFileTransferTransferOffset(mId, transferOffset));
    }

    /**
     * @return Returns the number of bytes that have successfully transferred.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public long getTransferOffset() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getFileTransferTransferOffset(mId));
    }

    /**
     * Sets the status for this file transfer and persists into storage.
     *
     * @param status The status of this file transfer.
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setFileTransferStatus(@RcsFileTransferStatus int status)
            throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setFileTransferStatus(mId, status));
    }

    /**
     * @return Returns the status of this file transfer.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public @RcsFileTransferStatus int getFileTransferStatus() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getFileTransferStatus(mId));
    }

    /**
     * @return Returns the width of this multi-media message part in pixels.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public int getWidth() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getFileTransferWidth(mId));
    }

    /**
     * Sets the width of this RCS multi-media message part and persists into storage.
     *
     * @param width The width value in pixels
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setWidth(int width) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setFileTransferWidth(mId, width));
    }

    /**
     * @return Returns the height of this multi-media message part in pixels.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public int getHeight() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getFileTransferHeight(mId));
    }

    /**
     * Sets the height of this RCS multi-media message part and persists into storage.
     *
     * @param height The height value in pixels
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setHeight(int height) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setFileTransferHeight(mId, height));
    }

    /**
     * @return Returns the length of this multi-media file (e.g. video or audio) in milliseconds.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public long getLength() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getFileTransferLength(mId));
    }

    /**
     * Sets the length of this multi-media file (e.g. video or audio) and persists into storage.
     *
     * @param length The length of the file in milliseconds.
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setLength(long length) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setFileTransferLength(mId, length));
    }

    /**
     * @return Returns the URI for the preview of this multi-media file (e.g. an image thumbnail for
     * a video)
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public Uri getPreviewUri() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getFileTransferPreviewUri(mId));
    }

    /**
     * Sets the URI for the preview of this multi-media file and persists into storage.
     *
     * @param previewUri The URI to access to the preview file.
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setPreviewUri(Uri previewUri) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setFileTransferPreviewUri(mId, previewUri));
    }

    /**
     * @return Returns the MIME type of this multi-media file's preview.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public String getPreviewMimeType() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getFileTransferPreviewType(mId));
    }

    /**
     * Sets the MIME type for this multi-media file's preview and persists into storage.
     *
     * @param previewMimeType The MIME type for the preview
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setPreviewMimeType(String previewMimeType) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.setFileTransferPreviewType(mId, previewMimeType));
    }
}
