/**
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
package android.ext.services.notification;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.RemoteAction;
import android.app.RemoteInput;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemProperties;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public class SmartActionsHelper {
    private static final ArrayList<Notification.Action> EMPTY_ACTION_LIST = new ArrayList<>();
    private static final ArrayList<CharSequence> EMPTY_REPLY_LIST = new ArrayList<>();

    // If a notification has any of these flags set, it's inelgibile for actions being added.
    private static final int FLAG_MASK_INELGIBILE_FOR_ACTIONS =
            Notification.FLAG_ONGOING_EVENT
                    | Notification.FLAG_FOREGROUND_SERVICE
                    | Notification.FLAG_GROUP_SUMMARY
                    | Notification.FLAG_NO_CLEAR;
    private static final int MAX_ACTION_EXTRACTION_TEXT_LENGTH = 400;
    private static final int MAX_ACTIONS_PER_LINK = 1;
    private static final int MAX_SMART_ACTIONS = Notification.MAX_ACTION_BUTTONS;
    // Allow us to test out smart reply with dumb suggestions, it is disabled by default.
    // TODO: Removed this once we have the model.
    private static final String SYS_PROP_SMART_REPLIES_EXPERIMENT =
            "persist.sys.smart_replies_experiment";

    SmartActionsHelper() {}

    /**
     * Adds action adjustments based on the notification contents.
     *
     * TODO: Once we have a API in {@link TextClassificationManager} to predict smart actions
     * from notification text / message, we can replace most of the code here by consuming that API.
     */
    @NonNull
    ArrayList<Notification.Action> suggestActions(
            @Nullable Context context, @NonNull StatusBarNotification sbn) {
        if (!isEligibleForActionAdjustment(sbn)) {
            return EMPTY_ACTION_LIST;
        }
        if (context == null) {
            return EMPTY_ACTION_LIST;
        }
        TextClassificationManager tcm = context.getSystemService(TextClassificationManager.class);
        if (tcm == null) {
            return EMPTY_ACTION_LIST;
        }
        Notification.Action[] actions = sbn.getNotification().actions;
        int numOfExistingActions = actions == null ? 0: actions.length;
        int maxSmartActions = MAX_SMART_ACTIONS - numOfExistingActions;
        return suggestActionsFromText(
                tcm,
                getMostSalientActionText(sbn.getNotification()), maxSmartActions);
    }

    ArrayList<CharSequence> suggestReplies(
            @Nullable Context context, @NonNull StatusBarNotification sbn) {
        if (!isEligibleForReplyAdjustment(sbn)) {
            return EMPTY_REPLY_LIST;
        }
        if (context == null) {
            return EMPTY_REPLY_LIST;
        }
        // TODO: replaced this with our model when it is ready.
        return new ArrayList<>(Arrays.asList("Yes, please", "No, thanks"));
    }

    /**
     * Returns whether a notification is eligible for action adjustments.
     *
     * <p>We exclude system notifications, those that get refreshed frequently, or ones that relate
     * to fundamental phone functionality where any error would result in a very negative user
     * experience.
     */
    private boolean isEligibleForActionAdjustment(@NonNull StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        String pkg = sbn.getPackageName();
        if (!Process.myUserHandle().equals(sbn.getUser())) {
            return false;
        }
        if (notification.actions != null
                && notification.actions.length >= Notification.MAX_ACTION_BUTTONS) {
            return false;
        }
        if (0 != (notification.flags & FLAG_MASK_INELGIBILE_FOR_ACTIONS)) {
            return false;
        }
        if (TextUtils.isEmpty(pkg) || pkg.equals("android")) {
            return false;
        }
        // For now, we are only interested in messages.
        return Notification.CATEGORY_MESSAGE.equals(notification.category)
                || Notification.MessagingStyle.class.equals(notification.getNotificationStyle())
                || hasInlineReply(notification);
    }

    private boolean isEligibleForReplyAdjustment(@NonNull StatusBarNotification sbn) {
        if (!SystemProperties.getBoolean(SYS_PROP_SMART_REPLIES_EXPERIMENT, false)) {
            return false;
        }
        Notification notification = sbn.getNotification();
        if (notification.actions == null) {
            return false;
        }
        return hasInlineReply(sbn.getNotification());
    }

    private boolean hasInlineReply(Notification notification) {
        Notification.Action[] actions = notification.actions;
        if (actions == null) {
            return false;
        }
        for (Notification.Action action : actions) {
            RemoteInput[] remoteInputs = action.getRemoteInputs();
            if (remoteInputs == null) {
                continue;
            }
            for (RemoteInput remoteInput : remoteInputs) {
                if (remoteInput.getAllowFreeFormInput()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns the text most salient for action extraction in a notification. */
    @Nullable
    private CharSequence getMostSalientActionText(@NonNull Notification notification) {
        /* If it's messaging style, use the most recent message. */
        Parcelable[] messages = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length != 0) {
            Bundle lastMessage = (Bundle) messages[messages.length - 1];
            CharSequence lastMessageText =
                    lastMessage.getCharSequence(Notification.MessagingStyle.Message.KEY_TEXT);
            if (!TextUtils.isEmpty(lastMessageText)) {
                return lastMessageText;
            }
        }

        // Fall back to using the normal text.
        return notification.extras.getCharSequence(Notification.EXTRA_TEXT);
    }

    /** Returns a list of actions to act on entities in a given piece of text. */
    @NonNull
    private ArrayList<Notification.Action> suggestActionsFromText(
            @NonNull TextClassificationManager tcm, @Nullable CharSequence text,
            int maxSmartActions) {
        if (TextUtils.isEmpty(text)) {
            return EMPTY_ACTION_LIST;
        }
        TextClassifier textClassifier = tcm.getTextClassifier();

        // We want to process only text visible to the user to avoid confusing suggestions, so we
        // truncate the text to a reasonable length. This is particularly important for e.g.
        // email apps that sometimes include the text for the entire thread.
        text = text.subSequence(0, Math.min(text.length(), MAX_ACTION_EXTRACTION_TEXT_LENGTH));

        // Extract all entities.
        TextLinks.Request textLinksRequest = new TextLinks.Request.Builder(text)
                .setEntityConfig(
                        TextClassifier.EntityConfig.createWithHints(
                                Collections.singletonList(
                                        TextClassifier.HINT_TEXT_IS_NOT_EDITABLE)))
                .build();
        TextLinks links = textClassifier.generateLinks(textLinksRequest);
        ArrayMap<String, Integer> entityTypeFrequency = getEntityTypeFrequency(links);

        ArrayList<Notification.Action> actions = new ArrayList<>();
        for (TextLinks.TextLink link : links.getLinks()) {
            // Ignore any entity type for which we have too many entities. This is to handle the
            // case where a notification contains e.g. a list of phone numbers. In such cases, the
            // user likely wants to act on the whole list rather than an individual entity.
            if (link.getEntityCount() == 0
                    || entityTypeFrequency.get(link.getEntity(0)) != 1) {
                continue;
            }

            // Generate the actions, and add the most prominent ones to the action bar.
            TextClassification classification =
                    textClassifier.classifyText(
                            new TextClassification.Request.Builder(
                                    text, link.getStart(), link.getEnd()).build());
            int numOfActions = Math.min(
                    MAX_ACTIONS_PER_LINK, classification.getActions().size());
            for (int i = 0; i < numOfActions; ++i) {
                RemoteAction action = classification.getActions().get(i);
                actions.add(
                        new Notification.Action.Builder(
                                action.getIcon(),
                                action.getTitle(),
                                action.getActionIntent())
                                .build());
                // We have enough smart actions.
                if (actions.size() >= maxSmartActions) {
                    return actions;
                }
            }
        }
        return actions;
    }

    /**
     * Given the links extracted from a piece of text, returns the frequency of each entity
     * type.
     */
    @NonNull
    private ArrayMap<String, Integer> getEntityTypeFrequency(@NonNull TextLinks links) {
        ArrayMap<String, Integer> entityTypeCount = new ArrayMap<>();
        for (TextLinks.TextLink link : links.getLinks()) {
            if (link.getEntityCount() == 0) {
                continue;
            }
            String entityType = link.getEntity(0);
            if (entityTypeCount.containsKey(entityType)) {
                entityTypeCount.put(entityType, entityTypeCount.get(entityType) + 1);
            } else {
                entityTypeCount.put(entityType, 1);
            }
        }
        return entityTypeCount;
    }
}
