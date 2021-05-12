/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.wallet.ui;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.service.quickaccesswallet.GetWalletCardsError;
import android.service.quickaccesswallet.GetWalletCardsRequest;
import android.service.quickaccesswallet.GetWalletCardsResponse;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.service.quickaccesswallet.SelectWalletCardRequest;
import android.service.quickaccesswallet.WalletCard;
import android.service.quickaccesswallet.WalletServiceEvent;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Controller for the wallet card carousel screen. */
public class WalletScreenController implements
        WalletCardCarousel.OnSelectionListener,
        QuickAccessWalletClient.OnWalletCardsRetrievedCallback,
        QuickAccessWalletClient.WalletServiceEventListener,
        KeyguardStateController.Callback {

    private static final String TAG = "WalletScreenCtrl";
    private static final String PREFS_WALLET_VIEW_HEIGHT = "wallet_view_height";
    private static final int MAX_CARDS = 10;
    private static final long SELECTION_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private Context mContext;
    private final QuickAccessWalletClient mWalletClient;
    private final ActivityStarter mActivityStarter;
    private final Executor mExecutor;
    private final Handler mHandler;
    private final KeyguardStateController mKeyguardStateController;
    private final Runnable mSelectionRunnable = this::selectCard;
    private final SharedPreferences mPrefs;
    private final WalletView mWalletView;
    private final WalletCardCarousel mCardCarousel;
    private final FalsingManager mFalsingManager;

    @VisibleForTesting String mSelectedCardId;
    @VisibleForTesting boolean mIsDismissed;
    private boolean mHasRegisteredListener;

    public WalletScreenController(
            Context context,
            WalletView walletView,
            QuickAccessWalletClient walletClient,
            ActivityStarter activityStarter,
            Executor executor,
            Handler handler,
            UserTracker userTracker,
            FalsingManager falsingManager,
            KeyguardStateController keyguardStateController) {
        mContext = context;
        mWalletClient = walletClient;
        mActivityStarter = activityStarter;
        mExecutor = executor;
        mHandler = handler;
        mFalsingManager = falsingManager;
        mKeyguardStateController = keyguardStateController;
        mPrefs = userTracker.getUserContext().getSharedPreferences(TAG, Context.MODE_PRIVATE);
        mWalletView = walletView;
        mWalletView.setMinimumHeight(getExpectedMinHeight());
        mWalletView.setLayoutParams(
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mCardCarousel = mWalletView.getCardCarousel();
        if (mCardCarousel != null) {
            mCardCarousel.setSelectionListener(this);
        }
    }

    /**
     * Implements {@link QuickAccessWalletClient.OnWalletCardsRetrievedCallback}. Called when cards
     * are retrieved successfully from the service. This is called on {@link #mExecutor}.
     */
    @Override
    public void onWalletCardsRetrieved(@NonNull GetWalletCardsResponse response) {
        if (mIsDismissed) {
            return;
        }
        List<WalletCard> walletCards = response.getWalletCards();
        List<WalletCardViewInfo> data = new ArrayList<>(walletCards.size());
        for (WalletCard card : walletCards) {
            data.add(new QAWalletCardViewInfo(mContext, card));
        }

        // Get on main thread for UI updates.
        mHandler.post(() -> {
            if (mIsDismissed) {
                return;
            }
            if (data.isEmpty()) {
                showEmptyStateView();
            } else {
                mWalletView.showCardCarousel(
                        data, response.getSelectedIndex(), !mKeyguardStateController.isUnlocked());
            }
            removeMinHeightAndRecordHeightOnLayout();
        });
    }

    /**
     * Implements {@link QuickAccessWalletClient.OnWalletCardsRetrievedCallback}. Called when there
     * is an error during card retrieval. This will be run on the {@link #mExecutor}.
     */
    @Override
    public void onWalletCardRetrievalError(@NonNull GetWalletCardsError error) {
        mHandler.post(() -> {
            if (mIsDismissed) {
                return;
            }
            mWalletView.showErrorMessage(error.getMessage());
        });
    }

    /**
     * Implements {@link QuickAccessWalletClient.WalletServiceEventListener}. Called when the wallet
     * application propagates an event, such as an NFC tap, to the quick access wallet view.
     */
    @Override
    public void onWalletServiceEvent(WalletServiceEvent event) {
        if (mIsDismissed) {
            return;
        }
        switch (event.getEventType()) {
            case WalletServiceEvent.TYPE_NFC_PAYMENT_STARTED:
                break;
            case WalletServiceEvent.TYPE_WALLET_CARDS_UPDATED:
                queryWalletCards();
                break;
            default:
                Log.w(TAG, "onWalletServiceEvent: Unknown event type");
        }
    }

    @Override
    public void onKeyguardFadingAwayChanged() {
        queryWalletCards();
    }

    @Override
    public void onCardSelected(@NonNull WalletCardViewInfo card) {
        if (mIsDismissed) {
            return;
        }
        mSelectedCardId = card.getCardId();
        selectCard();
    }

    private void selectCard() {
        mHandler.removeCallbacks(mSelectionRunnable);
        String selectedCardId = mSelectedCardId;
        if (mIsDismissed || selectedCardId == null) {
            return;
        }
        mWalletClient.selectWalletCard(new SelectWalletCardRequest(selectedCardId));
        // Re-selecting the card keeps the connection bound so we continue to get service events
        // even if the user keeps it open for a long time.
        mHandler.postDelayed(mSelectionRunnable, SELECTION_DELAY_MILLIS);
    }



    @Override
    public void onCardClicked(@NonNull WalletCardViewInfo cardInfo) {
        if (!mKeyguardStateController.isUnlocked()
                && mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return;
        }
        if (!(cardInfo instanceof QAWalletCardViewInfo)
                || ((QAWalletCardViewInfo) cardInfo).mWalletCard == null
                || ((QAWalletCardViewInfo) cardInfo).mWalletCard.getPendingIntent() == null) {
            return;
        }
        mActivityStarter.startActivity(
                ((QAWalletCardViewInfo) cardInfo).mWalletCard.getPendingIntent().getIntent(), true);
    }

    @Override
    public void queryWalletCards() {
        if (mIsDismissed) {
            return;
        }
        int cardWidthPx = mCardCarousel.getCardWidthPx();
        int cardHeightPx = mCardCarousel.getCardHeightPx();
        if (cardWidthPx == 0 || cardHeightPx == 0) {
            return;
        }
        if (!mHasRegisteredListener) {
            // Listener is registered even when device is locked. Should only be registered once.
            mWalletClient.addWalletServiceEventListener(this);
            mHasRegisteredListener = true;
        }

        mWalletView.show();
        mWalletView.hideErrorMessage();
        int iconSizePx =
                mContext
                        .getResources()
                        .getDimensionPixelSize(R.dimen.wallet_screen_header_icon_size);
        GetWalletCardsRequest request =
                new GetWalletCardsRequest(cardWidthPx, cardHeightPx, iconSizePx, MAX_CARDS);
        mWalletClient.getWalletCards(mExecutor, request, this);
    }

    void onDismissed() {
        if (mIsDismissed) {
            return;
        }
        mIsDismissed = true;
        mSelectedCardId = null;
        mHandler.removeCallbacks(mSelectionRunnable);
        mFalsingManager.cleanup();
        mWalletClient.notifyWalletDismissed();
        mWalletClient.removeWalletServiceEventListener(this);
        mWalletView.animateDismissal();
        // clear refs to the Wallet Activity
        mContext = null;
    }

    private void showEmptyStateView() {
        Drawable logo = mWalletClient.getLogo();
        CharSequence logoContentDesc = mWalletClient.getServiceLabel();
        CharSequence label = mWalletClient.getShortcutLongLabel();
        Intent intent = mWalletClient.createWalletIntent();
        if (logo == null
                || TextUtils.isEmpty(logoContentDesc)
                || TextUtils.isEmpty(label)
                || intent == null) {
            Log.w(TAG, "QuickAccessWalletService manifest entry mis-configured");
            // Issue is not likely to be resolved until manifest entries are enabled.
            // Hide wallet feature until then.
            mWalletView.hide();
            mPrefs.edit().putInt(PREFS_WALLET_VIEW_HEIGHT, 0).apply();
        } else {
            logo.setTint(mContext.getColor(R.color.GM2_grey_900));
            mWalletView.showEmptyStateView(
                    logo,
                    logoContentDesc,
                    label,
                    v -> mActivityStarter.startActivity(intent, true));
        }
    }

    private int getExpectedMinHeight() {
        int expectedHeight = mPrefs.getInt(PREFS_WALLET_VIEW_HEIGHT, -1);
        if (expectedHeight == -1) {
            Resources res = mContext.getResources();
            expectedHeight = res.getDimensionPixelSize(R.dimen.min_wallet_empty_height);
        }
        return expectedHeight;
    }

    private void removeMinHeightAndRecordHeightOnLayout() {
        mWalletView.setMinimumHeight(0);
        mWalletView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                mWalletView.removeOnLayoutChangeListener(this);
                mPrefs.edit().putInt(PREFS_WALLET_VIEW_HEIGHT, bottom - top).apply();
            }
        });
    }

    @VisibleForTesting
    static class QAWalletCardViewInfo implements WalletCardViewInfo {

        private final WalletCard mWalletCard;
        private final Drawable mCardDrawable;
        private final Drawable mIconDrawable;

        /**
         * Constructor is called on background executor, so it is safe to load drawables
         * synchronously.
         */
        QAWalletCardViewInfo(Context context, WalletCard walletCard) {
            mWalletCard = walletCard;
            mCardDrawable = mWalletCard.getCardImage().loadDrawable(context);
            Icon icon = mWalletCard.getCardIcon();
            mIconDrawable = icon == null ? null : icon.loadDrawable(context);
        }

        @Override
        public String getCardId() {
            return mWalletCard.getCardId();
        }

        @Override
        public Drawable getCardDrawable() {
            return mCardDrawable;
        }

        @Override
        public CharSequence getContentDescription() {
            return mWalletCard.getContentDescription();
        }

        @Override
        public Drawable getIcon() {
            return mIconDrawable;
        }

        @Override
        public CharSequence getLabel() {
            CharSequence label = mWalletCard.getCardLabel();
            if (label == null) {
                return "";
            }
            return label;
        }

        @Override
        public PendingIntent getPendingIntent() {
            return mWalletCard.getPendingIntent();
        }
    }
}
