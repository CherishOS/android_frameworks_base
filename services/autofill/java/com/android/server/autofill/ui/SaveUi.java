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

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.autofill.CustomDescription;
import android.service.autofill.SaveInfo;
import android.service.autofill.ValueFinder;
import android.text.Html;
import android.util.ArraySet;
import android.util.Slog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.view.autofill.IAutoFillManagerClient;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.server.UiThread;

import java.io.PrintWriter;

/**
 * Autofill Save Prompt
 */
final class SaveUi {

    private static final String TAG = "AutofillSaveUi";

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
            if (sDebug) Slog.d(TAG, "OneTimeListener.onSave(): " + mDone);
            if (mDone) {
                return;
            }
            mDone = true;
            mRealListener.onSave();
        }

        @Override
        public void onCancel(IntentSender listener) {
            if (sDebug) Slog.d(TAG, "OneTimeListener.onCancel(): " + mDone);
            if (mDone) {
                return;
            }
            mDone = true;
            mRealListener.onCancel(listener);
        }

        @Override
        public void onDestroy() {
            if (sDebug) Slog.d(TAG, "OneTimeListener.onDestroy(): " + mDone);
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

    private final @NonNull OverlayControl mOverlayControl;

    private final CharSequence mTitle;
    private final CharSequence mSubTitle;
    private final PendingUi mPendingUi;

    private boolean mDestroyed;

    SaveUi(@NonNull Context context, @NonNull PendingUi pendingUi,
           @NonNull CharSequence providerLabel, @NonNull SaveInfo info,
           @NonNull ValueFinder valueFinder, @NonNull OverlayControl overlayControl,
           @NonNull OnSaveListener listener) {
        mPendingUi= pendingUi;
        mListener = new OneTimeListener(listener);
        mOverlayControl = overlayControl;

        final LayoutInflater inflater = LayoutInflater.from(context);
        final View view = inflater.inflate(R.layout.autofill_save, null);

        final TextView titleView = view.findViewById(R.id.autofill_save_title);

        final ArraySet<String> types = new ArraySet<>(3);
        final int type = info.getType();

        if ((type & SaveInfo.SAVE_DATA_TYPE_PASSWORD) != 0) {
            types.add(context.getString(R.string.autofill_save_type_password));
        }
        if ((type & SaveInfo.SAVE_DATA_TYPE_ADDRESS) != 0) {
            types.add(context.getString(R.string.autofill_save_type_address));
        }
        if ((type & SaveInfo.SAVE_DATA_TYPE_CREDIT_CARD) != 0) {
            types.add(context.getString(R.string.autofill_save_type_credit_card));
        }
        if ((type & SaveInfo.SAVE_DATA_TYPE_USERNAME) != 0) {
            types.add(context.getString(R.string.autofill_save_type_username));
        }
        if ((type & SaveInfo.SAVE_DATA_TYPE_EMAIL_ADDRESS) != 0) {
            types.add(context.getString(R.string.autofill_save_type_email_address));
        }

        switch (types.size()) {
            case 1:
                mTitle = Html.fromHtml(context.getString(R.string.autofill_save_title_with_type,
                        types.valueAt(0), providerLabel), 0);
                break;
            case 2:
                mTitle = Html.fromHtml(context.getString(R.string.autofill_save_title_with_2types,
                        types.valueAt(0), types.valueAt(1), providerLabel), 0);
                break;
            case 3:
                mTitle = Html.fromHtml(context.getString(R.string.autofill_save_title_with_3types,
                        types.valueAt(0), types.valueAt(1), types.valueAt(2), providerLabel), 0);
                break;
            default:
                // Use generic if more than 3 or invalid type (size 0).
                mTitle = Html.fromHtml(
                        context.getString(R.string.autofill_save_title, providerLabel), 0);
        }

        titleView.setText(mTitle);

        ScrollView subtitleContainer = null;
        final CustomDescription customDescription = info.getCustomDescription();
        if (customDescription != null) {
            mSubTitle = null;
            if (sDebug) Slog.d(TAG, "Using custom description");

            final RemoteViews presentation = customDescription.getPresentation(valueFinder);
            if (presentation != null) {
                final RemoteViews.OnClickHandler handler = new RemoteViews.OnClickHandler() {
                    @Override
                    public boolean onClickHandler(View view, PendingIntent pendingIntent,
                            Intent intent) {
                        // We need to hide the Save UI before launching the pending intent, and
                        // restore back it once the activity is finished, and that's achieved by
                        // adding a custom extra in the activity intent.
                        if (pendingIntent != null) {
                            if (intent == null) {
                                Slog.w(TAG,
                                        "remote view on custom description does not have intent");
                                return false;
                            }
                            if (!pendingIntent.isActivity()) {
                                Slog.w(TAG, "ignoring custom description pending intent that's not "
                                        + "for an activity: " + pendingIntent);
                                return false;
                            }
                            if (sVerbose) {
                                Slog.v(TAG,
                                        "Intercepting custom description intent: " + intent);
                            }
                            final IBinder token = mPendingUi.getToken();
                            intent.putExtra(AutofillManager.EXTRA_RESTORE_SESSION_TOKEN, token);
                            try {
                                pendingUi.client.startIntentSender(pendingIntent.getIntentSender(),
                                        intent);
                                mPendingUi.setState(PendingUi.STATE_PENDING);
                                if (sDebug) {
                                    Slog.d(TAG, "hiding UI until restored with token " + token);
                                }
                                hide();
                            } catch (RemoteException e) {
                                Slog.w(TAG, "error triggering pending intent: " + intent);
                                return false;
                            }
                        }
                        return true;
                    }
                };

                try {
                    final View customSubtitleView = presentation.apply(context, null, handler);
                    subtitleContainer = view.findViewById(R.id.autofill_save_custom_subtitle);
                    subtitleContainer.addView(customSubtitleView);
                    subtitleContainer.setVisibility(View.VISIBLE);
                } catch (Exception e) {
                    Slog.e(TAG, "Could not inflate custom description. ", e);
                }
            } else {
                Slog.w(TAG, "could not create remote presentation for custom title");
            }
        } else {
            mSubTitle = info.getDescription();
            if (mSubTitle != null) {
                subtitleContainer = view.findViewById(R.id.autofill_save_custom_subtitle);
                final TextView subtitleView = new TextView(context);
                subtitleView.setText(mSubTitle);
                subtitleContainer.addView(subtitleView,
                        new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
                subtitleContainer.setVisibility(View.VISIBLE);
            }
            if (sDebug) Slog.d(TAG, "on constructor: title=" + mTitle + ", subTitle=" + mSubTitle);
        }

        final TextView noButton = view.findViewById(R.id.autofill_save_no);
        if (info.getNegativeActionStyle() == SaveInfo.NEGATIVE_BUTTON_STYLE_REJECT) {
            noButton.setText(R.string.save_password_notnow);
        } else {
            noButton.setText(R.string.autofill_save_no);
        }
        final View.OnClickListener cancelListener =
                (v) -> mListener.onCancel(info.getNegativeActionListener());
        noButton.setOnClickListener(cancelListener);

        final View yesButton = view.findViewById(R.id.autofill_save_yes);
        yesButton.setOnClickListener((v) -> mListener.onSave());

        mDialog = new Dialog(context, R.style.Theme_DeviceDefault_Light_Panel);
        mDialog.setContentView(view);

        // Dialog can be dismissed when touched outside.
        mDialog.setOnDismissListener((d) -> mListener.onCancel(info.getNegativeActionListener()));

        final Window window = mDialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        window.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER);
        window.setCloseOnTouchOutside(true);
        final WindowManager.LayoutParams params = window.getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.accessibilityTitle = context.getString(R.string.autofill_save_accessibility_title);
        params.windowAnimations = R.style.AutofillSaveAnimation;

        show();
    }

    /**
     * Update the pending UI, if any.
     *
     * @param operation how to update it.
     * @param token token associated with the pending UI - if it doesn't match the pending token,
     * the operation will be ignored.
     */
    void onPendingUi(int operation, @NonNull IBinder token) {
        if (!mPendingUi.matches(token)) {
            Slog.w(TAG, "restore(" + operation + "): got token " + token + " instead of "
                    + mPendingUi.getToken());
            return;
        }
        switch (operation) {
            case AutofillManager.PENDING_UI_OPERATION_RESTORE:
                if (sDebug) Slog.d(TAG, "Restoring save dialog for " + token);
                show();
                break;
            case AutofillManager.PENDING_UI_OPERATION_CANCEL:
                if (sDebug) Slog.d(TAG, "Cancelling pending save dialog for " + token);
                hide();
                break;
            default:
                Slog.w(TAG, "restore(): invalid operation " + operation);
        }
        mPendingUi.setState(PendingUi.STATE_FINISHED);
    }

    private void show() {
        Slog.i(TAG, "Showing save dialog: " + mTitle);
        mDialog.show();
        mOverlayControl.hideOverlays();
   }

    PendingUi hide() {
        if (sVerbose) Slog.v(TAG, "Hiding save dialog.");
        try {
            mDialog.hide();
        } finally {
            mOverlayControl.showOverlays();
        }
        return mPendingUi;
    }

    void destroy() {
        try {
            if (sDebug) Slog.d(TAG, "destroy()");
            throwIfDestroyed();
            mListener.onDestroy();
            mHandler.removeCallbacksAndMessages(mListener);
            mDialog.dismiss();
            mDestroyed = true;
        } finally {
            mOverlayControl.showOverlays();
        }
    }

    private void throwIfDestroyed() {
        if (mDestroyed) {
            throw new IllegalStateException("cannot interact with a destroyed instance");
        }
    }

    @Override
    public String toString() {
        return mTitle == null ? "NO TITLE" : mTitle.toString();
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("title: "); pw.println(mTitle);
        pw.print(prefix); pw.print("subtitle: "); pw.println(mSubTitle);
        pw.print(prefix); pw.print("pendingUi: "); pw.println(mPendingUi);

        final View view = mDialog.getWindow().getDecorView();
        final int[] loc = view.getLocationOnScreen();
        pw.print(prefix); pw.print("coordinates: ");
            pw.print('('); pw.print(loc[0]); pw.print(','); pw.print(loc[1]);pw.print(')');
            pw.print('(');
                pw.print(loc[0] + view.getWidth()); pw.print(',');
                pw.print(loc[1] + view.getHeight());pw.println(')');
        pw.print(prefix); pw.print("destroyed: "); pw.println(mDestroyed);
    }
}
