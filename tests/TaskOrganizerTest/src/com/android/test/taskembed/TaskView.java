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

package com.android.test.taskembed;

import android.app.ActivityTaskManager;
import android.content.Context;
import android.window.TaskOrganizer;
import android.window.WindowContainerToken;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.window.ITaskOrganizer;

/**
 * Simple SurfaceView wrapper which registers a TaskOrganizer
 * after it's Surface is ready.
 */
class TaskView extends SurfaceView implements SurfaceHolder.Callback {
    final TaskOrganizer mTaskOrganizer;
    final int mWindowingMode;
    WindowContainerToken mWc;

    boolean mSurfaceCreated = false;
    boolean mNeedsReparent;

    TaskView(Context c, TaskOrganizer o, int windowingMode) {
        super(c);
        getHolder().addCallback(this);
        setZOrderOnTop(true);

        mTaskOrganizer = o;
        mWindowingMode = windowingMode;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurfaceCreated = true;
        if (mNeedsReparent) {
            mNeedsReparent = false;
            reparentLeash();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    void reparentTask(WindowContainerToken wc) {
        mWc = wc;
        if (mSurfaceCreated == false) {
            mNeedsReparent = true;
        } else {
            reparentLeash();
        }
    }

    void reparentLeash() {
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        SurfaceControl leash = null;
        try {
            leash = mWc.getLeash();
        } catch (Exception e) {
            // System server died.. oh well
        }

        t.reparent(leash, getSurfaceControl())
            .setPosition(leash, 0, 0)
            .show(leash)
            .apply();
    }
}
