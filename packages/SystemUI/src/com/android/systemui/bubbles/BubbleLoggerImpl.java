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

package com.android.systemui.bubbles;

import android.os.UserHandle;

import com.android.internal.logging.UiEventLoggerImpl;
import com.android.systemui.shared.system.SysUiStatsLog;

/**
 * Implementation of UiEventLogger for logging bubble UI events.
 *
 * See UiEventReported atom in atoms.proto for more context.
 */
public class BubbleLoggerImpl extends UiEventLoggerImpl implements BubbleLogger {

    /**
     * @param b Bubble involved in this UI event
     * @param e UI event
     */
    public void log(Bubble b, UiEventEnum e) {
        super.log(e, b.getUser().getIdentifier(), b.getPackageName());
    }

    /**
     * @param b Bubble removed from overflow
     * @param r Reason that bubble was removed
     */
    public void logOverflowRemove(Bubble b, @BubbleController.DismissReason int r) {
        if (r == BubbleController.DISMISS_NOTIF_CANCEL) {
            log(b, BubbleLogger.Event.BUBBLE_OVERFLOW_REMOVE_CANCEL);
        } else if (r == BubbleController.DISMISS_GROUP_CANCELLED) {
            log(b, BubbleLogger.Event.BUBBLE_OVERFLOW_REMOVE_GROUP_CANCEL);
        } else if (r == BubbleController.DISMISS_NO_LONGER_BUBBLE) {
            log(b, BubbleLogger.Event.BUBBLE_OVERFLOW_REMOVE_NO_LONGER_BUBBLE);
        } else if (r == BubbleController.DISMISS_BLOCKED) {
            log(b, BubbleLogger.Event.BUBBLE_OVERFLOW_REMOVE_BLOCKED);
        }
    }

    /**
     * @param b Bubble added to overflow
     * @param r Reason that bubble was added to overflow
     */
    public void logOverflowAdd(Bubble b, @BubbleController.DismissReason int r) {
        if (r == BubbleController.DISMISS_AGED) {
            log(b, Event.BUBBLE_OVERFLOW_ADD_AGED);
        } else if (r == BubbleController.DISMISS_USER_GESTURE) {
            log(b, Event.BUBBLE_OVERFLOW_ADD_USER_GESTURE);
        }
    }

    void logStackUiChanged(String packageName, int action, int bubbleCount, float normalX,
            float normalY) {
        SysUiStatsLog.write(SysUiStatsLog.BUBBLE_UI_CHANGED,
                packageName,
                null /* notification channel */,
                0 /* notification ID */,
                0 /* bubble position */,
                bubbleCount,
                action,
                normalX,
                normalY,
                false /* unread bubble */,
                false /* on-going bubble */,
                false /* isAppForeground (unused) */);
    }

    void logShowOverflow(String packageName, int currentUserId) {
        super.log(BubbleLogger.Event.BUBBLE_OVERFLOW_SELECTED, currentUserId,
                packageName);
    }

    void logBubbleUiChanged(Bubble bubble, String packageName, int action, int bubbleCount,
            float normalX, float normalY, int index) {
        SysUiStatsLog.write(SysUiStatsLog.BUBBLE_UI_CHANGED,
                packageName,
                bubble.getChannelId() /* notification channel */,
                bubble.getNotificationId() /* notification ID */,
                index,
                bubbleCount,
                action,
                normalX,
                normalY,
                bubble.showInShade() /* isUnread */,
                false /* isOngoing (unused) */,
                false /* isAppForeground (unused) */);
    }
}