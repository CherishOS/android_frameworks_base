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

package com.android.systemui.qs;

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.android.settingslib.media.MediaDevice;
import com.android.systemui.R;
import com.android.systemui.media.MediaControlPanel;
import com.android.systemui.statusbar.NotificationMediaManager;

import java.util.concurrent.Executor;

/**
 * Single media player for carousel in QSPanel
 */
public class QSMediaPlayer extends MediaControlPanel {

    private static final String TAG = "QSMediaPlayer";

    // Button IDs for QS controls
    static final int[] QS_ACTION_IDS = {
            R.id.action0,
            R.id.action1,
            R.id.action2,
            R.id.action3,
            R.id.action4
    };

    /**
     * Initialize quick shade version of player
     * @param context
     * @param parent
     * @param manager
     * @param backgroundExecutor
     */
    public QSMediaPlayer(Context context, ViewGroup parent, NotificationMediaManager manager,
            Executor backgroundExecutor) {
        super(context, parent, manager, R.layout.qs_media_panel, QS_ACTION_IDS, backgroundExecutor);
    }

    /**
     * Update media panel view for the given media session
     * @param token token for this media session
     * @param icon app notification icon
     * @param iconColor foreground color (for text, icons)
     * @param bgColor background color
     * @param actionsContainer a LinearLayout containing the media action buttons
     * @param notif reference to original notification
     * @param device current playback device
     */
    public void setMediaSession(MediaSession.Token token, Icon icon, int iconColor,
            int bgColor, View actionsContainer, Notification notif, MediaDevice device) {

        String appName = Notification.Builder.recoverBuilder(getContext(), notif)
                .loadHeaderAppName();
        super.setMediaSession(token, icon, iconColor, bgColor, notif.contentIntent,
                appName, device);

        // Media controls
        LinearLayout parentActionsLayout = (LinearLayout) actionsContainer;
        int i = 0;
        for (; i < parentActionsLayout.getChildCount() && i < QS_ACTION_IDS.length; i++) {
            ImageButton thisBtn = mMediaNotifView.findViewById(QS_ACTION_IDS[i]);
            ImageButton thatBtn = parentActionsLayout.findViewById(NOTIF_ACTION_IDS[i]);
            if (thatBtn == null || thatBtn.getDrawable() == null
                    || thatBtn.getVisibility() != View.VISIBLE) {
                thisBtn.setVisibility(View.GONE);
                continue;
            }

            Drawable thatIcon = thatBtn.getDrawable();
            thisBtn.setImageDrawable(thatIcon.mutate());
            thisBtn.setVisibility(View.VISIBLE);
            thisBtn.setOnClickListener(v -> {
                Log.d(TAG, "clicking on other button");
                thatBtn.performClick();
            });
        }

        // Hide any unused buttons
        for (; i < QS_ACTION_IDS.length; i++) {
            ImageButton thisBtn = mMediaNotifView.findViewById(QS_ACTION_IDS[i]);
            thisBtn.setVisibility(View.GONE);
        }
    }
}
