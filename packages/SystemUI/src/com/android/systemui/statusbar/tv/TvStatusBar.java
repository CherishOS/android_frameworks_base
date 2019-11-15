/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.tv;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.CommandQueue;

import javax.inject.Inject;
import javax.inject.Singleton;


/**
 * Status bar implementation for "large screen" products that mostly present no on-screen nav.
 * Serves as a collection of UI components, rather than showing its own UI.
 * The following is the list of elements that constitute the TV-specific status bar:
 * <ul>
 * <li> {@link AudioRecordingDisclosureBar} - shown whenever applications are conducting audio
 * recording, discloses the responsible applications </li>
 * </ul>
 */
@Singleton
public class TvStatusBar extends SystemUI implements CommandQueue.Callbacks {

    private final CommandQueue mCommandQueue;

    @Inject
    public TvStatusBar(Context context, CommandQueue commandQueue) {
        super(context);
        mCommandQueue = commandQueue;
    }

    @Override
    public void start() {
        final IStatusBarService barService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mCommandQueue.addCallback(this);
        try {
            barService.registerStatusBar(mCommandQueue);
        } catch (RemoteException ex) {
            // If the system process isn't there we're doomed anyway.
        }

        new AudioRecordingDisclosureBar(mContext).start();
    }
}
