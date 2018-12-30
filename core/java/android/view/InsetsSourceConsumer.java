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
 * limitations under the License
 */

package android.view;

import android.annotation.Nullable;
import android.view.SurfaceControl.Transaction;
import android.view.InsetsState.InternalInsetType;

import com.android.internal.annotations.VisibleForTesting;

import java.util.function.Supplier;

/**
 * Controls the visibility and animations of a single window insets source.
 * @hide
 */
public class InsetsSourceConsumer {

    private final Supplier<Transaction> mTransactionSupplier;
    private final @InternalInsetType int mType;
    private final InsetsState mState;
    private final InsetsController mController;
    private @Nullable InsetsSourceControl mSourceControl;
    private boolean mVisible;

    public InsetsSourceConsumer(@InternalInsetType int type, InsetsState state,
            Supplier<Transaction> transactionSupplier, InsetsController controller) {
        mType = type;
        mState = state;
        mTransactionSupplier = transactionSupplier;
        mController = controller;
        mVisible = InsetsState.getDefaultVisibly(type);
    }

    public void setControl(@Nullable InsetsSourceControl control) {
        if (mSourceControl == control) {
            return;
        }
        mSourceControl = control;
        applyHiddenToControl();
        if (applyLocalVisibilityOverride()) {
            mController.notifyVisibilityChanged();
        }
    }

    @VisibleForTesting
    public InsetsSourceControl getControl() {
        return mSourceControl;
    }

    int getType() {
        return mType;
    }

    @VisibleForTesting
    public void show() {
        setVisible(true);
    }

    @VisibleForTesting
    public void hide() {
        setVisible(false);
    }

    boolean applyLocalVisibilityOverride() {

        // If we don't have control, we are not able to change the visibility.
        if (mSourceControl == null) {
            return false;
        }
        if (mState.getSource(mType).isVisible() == mVisible) {
            return false;
        }
        mState.getSource(mType).setVisible(mVisible);
        return true;
    }

    private void setVisible(boolean visible) {
        if (mVisible == visible) {
            return;
        }
        mVisible = visible;
        applyHiddenToControl();
        applyLocalVisibilityOverride();
        mController.notifyVisibilityChanged();
    }

    private void applyHiddenToControl() {
        if (mSourceControl == null) {
            return;
        }

        // TODO: Animation
        final Transaction t = mTransactionSupplier.get();
        if (mVisible) {
            t.show(mSourceControl.getLeash());
        } else {
            t.hide(mSourceControl.getLeash());
        }
        t.apply();
    }
}
