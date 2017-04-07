/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.autofill.ui;

import static com.android.server.autofill.ui.Helper.DEBUG;

import android.annotation.NonNull;
import android.app.Dialog;
import android.content.Context;
import android.content.IntentSender;
import android.os.Handler;
import android.service.autofill.SaveInfo;
import android.text.format.DateUtils;
import android.util.Slog;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.View;

import com.android.internal.R;
import com.android.server.UiThread;

/**
 * Autofill Save Prompt
 */
final class SaveUi {

    private static final String TAG = "SaveUi";

    public interface OnSaveListener {
        void onSave();
        void onCancel(IntentSender listener);
        void onDestroy();
    }

    private class OneTimeListener implements OnSaveListener {

        private final OnSaveListener mRealListener;
        private boolean mDone;

        OneTimeListener(OnSaveListener realListener) {
            mRealListener = realListener;
        }

        @Override
        public void onSave() {
            if (DEBUG) Slog.d(TAG, "onSave(): " + mDone);
            if (mDone) {
                return;
            }
            mDone = true;
            mRealListener.onSave();
        }

        @Override
        public void onCancel(IntentSender listener) {
            if (DEBUG) Slog.d(TAG, "onCancel(): " + mDone);
            if (mDone) {
                return;
            }
            mDone = true;
            mRealListener.onCancel(listener);
        }

        @Override
        public void onDestroy() {
            if (DEBUG) Slog.d(TAG, "onDestroy(): " + mDone);
            if (mDone) {
                return;
            }
            mDone = true;
            mRealListener.onDestroy();
        }
    }

    private final Handler mHandler = UiThread.getHandler();

    private final @NonNull Dialog mDialog;

    private final @NonNull OneTimeListener mListener;

    private boolean mDestroyed;

    SaveUi(@NonNull Context context, @NonNull CharSequence providerLabel, @NonNull SaveInfo info,
            @NonNull OnSaveListener listener) {
        mListener = new OneTimeListener(listener);

        final LayoutInflater inflater = LayoutInflater.from(context);
        final View view = inflater.inflate(R.layout.autofill_save, null);

        final TextView titleView = (TextView) view.findViewById(R.id.autofill_save_title);
        final String type;

        switch(info.getType()) {
            case SaveInfo.SAVE_DATA_TYPE_PASSWORD:
                type = context.getString(R.string.autofill_save_type_password);
                break;
            case SaveInfo.SAVE_DATA_TYPE_ADDRESS:
                type = context.getString(R.string.autofill_save_type_address);
                break;
            case SaveInfo.SAVE_DATA_TYPE_CREDIT_CARD:
                type = context.getString(R.string.autofill_save_type_credit_card);
                break;
            case SaveInfo.SAVE_DATA_TYPE_USERNAME:
                type = context.getString(R.string.autofill_save_type_username);
                break;
            case SaveInfo.SAVE_DATA_TYPE_EMAIL_ADDRESS:
                type = context.getString(R.string.autofill_save_type_email_address);
                break;
            default:
                type = null;
        }

        final String title = (type == null)
                ? context.getString(R.string.autofill_save_title, providerLabel)
                : context.getString(R.string.autofill_save_title_with_type, type, providerLabel);

        titleView.setText(title);
        final CharSequence subTitle = info.getDescription();
        if (subTitle != null) {
            final TextView subTitleView = (TextView) view.findViewById(R.id.autofill_save_subtitle);
            subTitleView.setText(subTitle);
            subTitleView.setVisibility(View.VISIBLE);
        }

        final TextView noButton = view.findViewById(R.id.autofill_save_no);
        if (info.getNegativeActionTitle() != null) {
            noButton.setText(info.getNegativeActionTitle());
            noButton.setOnClickListener((v) -> mListener.onCancel(
                    info.getNegativeActionListener()));
        } else {
            noButton.setOnClickListener((v) -> mListener.onCancel(null));
        }

        final View yesButton = view.findViewById(R.id.autofill_save_yes);
        yesButton.setOnClickListener((v) -> mListener.onSave());

        final View closeButton = view.findViewById(R.id.autofill_save_close);
        closeButton.setOnClickListener((v) -> mListener.onCancel(null));

        mDialog = new Dialog(context, R.style.Theme_Material_Panel);
        mDialog.setContentView(view);

        final Window window = mDialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER);
        window.setCloseOnTouchOutside(true);
        window.getAttributes().width = WindowManager.LayoutParams.MATCH_PARENT;

        mDialog.show();
    }

    void destroy() {
        throwIfDestroyed();
        mListener.onDestroy();
        mHandler.removeCallbacksAndMessages(mListener);
        mDialog.dismiss();
        mDestroyed = true;
    }

    private void throwIfDestroyed() {
        if (mDestroyed) {
            throw new IllegalStateException("cannot interact with a destroyed instance");
        }
    }
}
