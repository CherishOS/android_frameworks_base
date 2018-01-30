/*
 * Copyright 2018 The Android Open Source Project
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

package android.media.update;

import android.media.AudioAttributes;
import android.media.MediaItem2;
import android.media.MediaPlayerInterface;
import android.media.MediaPlayerInterface.PlaybackListener;
import android.media.MediaSession2.Command;
import android.media.MediaSession2.CommandButton;
import android.media.MediaSession2.CommandGroup;
import android.media.MediaSession2.ControllerInfo;
import android.media.MediaSession2.PlaylistParams;
import android.media.SessionToken2;
import android.media.VolumeProvider;
import android.os.Bundle;
import android.os.ResultReceiver;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * @hide
 */
// TODO: @SystemApi
public interface MediaSession2Provider extends TransportControlProvider {
    void initialize();

    void close_impl();
    void setPlayer_impl(MediaPlayerInterface player);
    void setPlayer_impl(MediaPlayerInterface player, VolumeProvider volumeProvider);
    MediaPlayerInterface getPlayer_impl();
    SessionToken2 getToken_impl();
    List<ControllerInfo> getConnectedControllers_impl();
    void setCustomLayout_impl(ControllerInfo controller, List<CommandButton> layout);
    void setAudioFocusRequest_impl(int focusGain);

    void setAllowedCommands_impl(ControllerInfo controller, CommandGroup commands);
    void notifyMetadataChanged_impl();
    void sendCustomCommand_impl(ControllerInfo controller, Command command, Bundle args,
            ResultReceiver receiver);
    void sendCustomCommand_impl(Command command, Bundle args);
    void setPlaylist_impl(List<MediaItem2> playlist);
    List<MediaItem2> getPlaylist_impl();
    void setPlaylistParams_impl(PlaylistParams params);
    PlaylistParams getPlaylistParams_impl();

    void addPlaybackListener_impl(Executor executor, PlaybackListener listener);
    void removePlaybackListener_impl(PlaybackListener listener);

    interface ControllerInfoProvider {
        String getPackageName_impl();
        int getUid_impl();
        boolean isTrusted_impl();
        int hashCode_impl();
        boolean equals_impl(ControllerInfoProvider obj);
    }

    interface CommandProvider {
        int getCommandCode_impl();
        String getCustomCommand_impl();
        Bundle getExtra_impl();
        Bundle toBundle_impl();

        boolean equals_impl(Object ob);
        int hashCode_impl();
    }
}
