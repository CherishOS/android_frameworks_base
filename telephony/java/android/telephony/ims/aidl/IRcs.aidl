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

package android.telephony.ims.aidl;

import android.net.Uri;
import android.telephony.ims.RcsEventQueryParameters;
import android.telephony.ims.RcsEventQueryResult;
import android.telephony.ims.RcsFileTransferCreationParameters;
import android.telephony.ims.RcsIncomingMessageCreationParameters;
import android.telephony.ims.RcsMessageCreationParameters;
import android.telephony.ims.RcsMessageSnippet;
import android.telephony.ims.RcsMessageQueryParameters;
import android.telephony.ims.RcsMessageQueryResult;
import android.telephony.ims.RcsParticipantQueryParameters;
import android.telephony.ims.RcsParticipantQueryResult;
import android.telephony.ims.RcsQueryContinuationToken;
import android.telephony.ims.RcsThreadQueryParameters;
import android.telephony.ims.RcsThreadQueryResult;

/**
 * RPC definition between RCS storage APIs and phone process.
 * {@hide}
 */
interface IRcs {
    /////////////////////////
    // RcsMessageStore APIs
    /////////////////////////
    RcsThreadQueryResult getRcsThreads(in RcsThreadQueryParameters queryParameters);

    RcsThreadQueryResult getRcsThreadsWithToken(
        in RcsQueryContinuationToken continuationToken);

    RcsParticipantQueryResult getParticipants(in RcsParticipantQueryParameters queryParameters);

    RcsParticipantQueryResult getParticipantsWithToken(
        in RcsQueryContinuationToken continuationToken);

    RcsMessageQueryResult getMessages(in RcsMessageQueryParameters queryParameters);

    RcsMessageQueryResult getMessagesWithToken(
        in RcsQueryContinuationToken continuationToken);

    RcsEventQueryResult getEvents(in RcsEventQueryParameters queryParameters);

    RcsEventQueryResult getEventsWithToken(
        in RcsQueryContinuationToken continuationToken);

    // returns true if the thread was successfully deleted
    boolean deleteThread(int threadId, int threadType);

    // Creates an Rcs1To1Thread and returns its row ID
    int createRcs1To1Thread(int participantId);

    // Creates an RcsGroupThread and returns its row ID
    int createGroupThread(in int[] participantIds, String groupName, in Uri groupIcon);

    /////////////////////////
    // RcsThread APIs
    /////////////////////////

    // Creates a new RcsIncomingMessage on the given thread and returns its row ID
    int addIncomingMessage(int rcsThreadId,
            in RcsIncomingMessageCreationParameters rcsIncomingMessageCreationParameters);

    // Creates a new RcsOutgoingMessage on the given thread and returns its row ID
    int addOutgoingMessage(int rcsThreadId,
            in RcsMessageCreationParameters rcsMessageCreationParameters);

    // TODO: modify RcsProvider URI's to allow deleting a message without specifying its thread
    void deleteMessage(int rcsMessageId, boolean isIncoming, int rcsThreadId, boolean isGroup);

    RcsMessageSnippet getMessageSnippet(int rcsThreadId);

    /////////////////////////
    // Rcs1To1Thread APIs
    /////////////////////////
    void set1To1ThreadFallbackThreadId(int rcsThreadId, long fallbackId);

    long get1To1ThreadFallbackThreadId(int rcsThreadId);

    int get1To1ThreadOtherParticipantId(int rcsThreadId);

    /////////////////////////
    // RcsGroupThread APIs
    /////////////////////////
    void setGroupThreadName(int rcsThreadId, String groupName);

    String getGroupThreadName(int rcsThreadId);

    void setGroupThreadIcon(int rcsThreadId, in Uri groupIcon);

    Uri getGroupThreadIcon(int rcsThreadId);

    void setGroupThreadOwner(int rcsThreadId, int participantId);

    int getGroupThreadOwner(int rcsThreadId);

    void setGroupThreadConferenceUri(int rcsThreadId, in Uri conferenceUri);

    Uri getGroupThreadConferenceUri(int rcsThreadId);

    void addParticipantToGroupThread(int rcsThreadId, int participantId);

    void removeParticipantFromGroupThread(int rcsThreadId, int participantId);

    /////////////////////////
    // RcsParticipant APIs
    /////////////////////////

    // Creates a new RcsParticipant and returns its rowId
    int createRcsParticipant(String canonicalAddress, String alias);

    String getRcsParticipantCanonicalAddress(int participantId);

    String getRcsParticipantAlias(int participantId);

    void setRcsParticipantAlias(int id, String alias);

    String getRcsParticipantContactId(int participantId);

    void setRcsParticipantContactId(int participantId, String contactId);

    /////////////////////////
    // RcsMessage APIs
    /////////////////////////
    void setMessageSubId(int messageId, boolean isIncoming, int subId);

    int getMessageSubId(int messageId, boolean isIncoming);

    void setMessageStatus(int messageId, boolean isIncoming, int status);

    int getMessageStatus(int messageId, boolean isIncoming);

    void setMessageOriginationTimestamp(int messageId, boolean isIncoming, long originationTimestamp);

    long getMessageOriginationTimestamp(int messageId, boolean isIncoming);

    void setGlobalMessageIdForMessage(int messageId, boolean isIncoming, String globalId);

    String getGlobalMessageIdForMessage(int messageId, boolean isIncoming);

    void setMessageArrivalTimestamp(int messageId, boolean isIncoming, long arrivalTimestamp);

    long getMessageArrivalTimestamp(int messageId, boolean isIncoming);

    void setMessageSeenTimestamp(int messageId, boolean isIncoming, long seenTimestamp);

    long getMessageSeenTimestamp(int messageId, boolean isIncoming);

    void setTextForMessage(int messageId, boolean isIncoming, String text);

    String getTextForMessage(int messageId, boolean isIncoming);

    void setLatitudeForMessage(int messageId, boolean isIncoming, double latitude);

    double getLatitudeForMessage(int messageId, boolean isIncoming);

    void setLongitudeForMessage(int messageId, boolean isIncoming, double longitude);

    double getLongitudeForMessage(int messageId, boolean isIncoming);

    // Returns the ID's of the file transfers attached to the given message
    int[] getFileTransfersAttachedToMessage(int messageId, boolean isIncoming);

    int getSenderParticipant(int messageId);

    /////////////////////////
    // RcsOutgoingMessageDelivery APIs
    /////////////////////////

    // Returns the participant ID's that this message is intended to be delivered to
    int[] getMessageRecipients(int messageId);

    long getOutgoingDeliveryDeliveredTimestamp(int messageId, int participantId);

    void setOutgoingDeliveryDeliveredTimestamp(int messageId, int participantId, long deliveredTimestamp);

    long getOutgoingDeliverySeenTimestamp(int messageId, int participantId);

    void setOutgoingDeliverySeenTimestamp(int messageId, int participantId, long seenTimestamp);

    int getOutgoingDeliveryStatus(int messageId, int participantId);

    void setOutgoingDeliveryStatus(int messageId, int participantId, int status);

    /////////////////////////
    // RcsFileTransferPart APIs
    /////////////////////////

    // Performs the initial write to storage and returns the row ID.
    int storeFileTransfer(int messageId, boolean isIncoming,
            in RcsFileTransferCreationParameters fileTransferCreationParameters);

    void deleteFileTransfer(int partId);

    void setFileTransferSessionId(int partId, String sessionId);

    String getFileTransferSessionId(int partId);

    void setFileTransferContentUri(int partId, in Uri contentUri);

    Uri getFileTransferContentUri(int partId);

    void setFileTransferContentType(int partId, String contentType);

    String getFileTransferContentType(int partId);

    void setFileTransferFileSize(int partId, long fileSize);

    long getFileTransferFileSize(int partId);

    void setFileTransferTransferOffset(int partId, long transferOffset);

    long getFileTransferTransferOffset(int partId);

    void setFileTransferStatus(int partId, int transferStatus);

    int getFileTransferStatus(int partId);

    void setFileTransferWidth(int partId, int width);

    int getFileTransferWidth(int partId);

    void setFileTransferHeight(int partId, int height);

    int getFileTransferHeight(int partId);

    void setFileTransferLength(int partId, long length);

    long getFileTransferLength(int partId);

    void setFileTransferPreviewUri(int partId, in Uri uri);

    Uri getFileTransferPreviewUri(int partId);

    void setFileTransferPreviewType(int partId, String type);

    String getFileTransferPreviewType(int partId);

    /////////////////////////
    // RcsEvent APIs
    /////////////////////////
    int createGroupThreadNameChangedEvent(long timestamp, int threadId, int originationParticipantId, String newName);

    int createGroupThreadIconChangedEvent(long timestamp, int threadId, int originationParticipantId, in Uri newIcon);

    int createGroupThreadParticipantJoinedEvent(long timestamp, int threadId, int originationParticipantId, int participantId);

    int createGroupThreadParticipantLeftEvent(long timestamp, int threadId, int originationParticipantId, int participantId);

    int createParticipantAliasChangedEvent(long timestamp, int participantId, String newAlias);
}