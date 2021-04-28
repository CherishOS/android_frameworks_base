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

package com.android.systemui.media;

import static android.provider.Settings.ACTION_MEDIA_CONTROLS_SETTINGS;

import android.app.PendingIntent;
import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemProperties;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.settingslib.Utils;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.GhostedViewLaunchAnimatorController;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.util.animation.TransitionLayout;

import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * A view controller used for Media Playback.
 */
public class MediaControlPanel {
    private static final String TAG = "MediaControlPanel";
    private static final float DISABLED_ALPHA = 0.38f;
    private static final String EXTRAS_MEDIA_SOURCE_LOGO = "media_source_logo";
    private static final String EXTRAS_MEDIA_SOURCE_PACKAGE_NAME = "package_name";
    private static final int MEDIA_RECOMMENDATION_MAX_NUM = 4;

    private final boolean mShowAppName = SystemProperties.getBoolean(
            "persist.sysui.qs_media_show_app_name", false);
    private final boolean mShowDeviceName = SystemProperties.getBoolean(
            "persist.sysui.qs_media_show_device_name", false);

    private static final Intent SETTINGS_INTENT = new Intent(ACTION_MEDIA_CONTROLS_SETTINGS);

    // Button IDs for QS controls
    static final int[] ACTION_IDS = {
            R.id.action0,
            R.id.action1,
            R.id.action2,
            R.id.action3,
            R.id.action4
    };

    private final SeekBarViewModel mSeekBarViewModel;
    private SeekBarObserver mSeekBarObserver;
    protected final Executor mBackgroundExecutor;
    private final ActivityStarter mActivityStarter;

    private Context mContext;
    private PlayerViewHolder mPlayerViewHolder;
    private RecommendationViewHolder mRecommendationViewHolder;
    private String mKey;
    private MediaViewController mMediaViewController;
    private MediaSession.Token mToken;
    private MediaController mController;
    private KeyguardDismissUtil mKeyguardDismissUtil;
    private Lazy<MediaDataManager> mMediaDataManagerLazy;
    private int mBackgroundColor;
    private int mAlbumArtSize;
    private int mAlbumArtRadius;
    // This will provide the corners for the album art.
    private final ViewOutlineProvider mViewOutlineProvider;
    private final MediaOutputDialogFactory mMediaOutputDialogFactory;

    /**
     * Initialize a new control panel
     *
     * @param backgroundExecutor background executor, used for processing artwork
     * @param activityStarter    activity starter
     */
    @Inject
    public MediaControlPanel(Context context, @Background Executor backgroundExecutor,
            ActivityStarter activityStarter, MediaViewController mediaViewController,
            SeekBarViewModel seekBarViewModel, Lazy<MediaDataManager> lazyMediaDataManager,
            KeyguardDismissUtil keyguardDismissUtil, MediaOutputDialogFactory
            mediaOutputDialogFactory) {
        mContext = context;
        mBackgroundExecutor = backgroundExecutor;
        mActivityStarter = activityStarter;
        mSeekBarViewModel = seekBarViewModel;
        mMediaViewController = mediaViewController;
        mMediaDataManagerLazy = lazyMediaDataManager;
        mKeyguardDismissUtil = keyguardDismissUtil;
        mMediaOutputDialogFactory = mediaOutputDialogFactory;
        loadDimens();

        mViewOutlineProvider = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, mAlbumArtSize, mAlbumArtSize, mAlbumArtRadius);
            }
        };
    }

    public void onDestroy() {
        if (mSeekBarObserver != null) {
            mSeekBarViewModel.getProgress().removeObserver(mSeekBarObserver);
        }
        mSeekBarViewModel.onDestroy();
        mMediaViewController.onDestroy();
    }

    private void loadDimens() {
        mAlbumArtRadius = mContext.getResources().getDimensionPixelSize(
                Utils.getThemeAttr(mContext, android.R.attr.dialogCornerRadius));
        mAlbumArtSize = mContext.getResources().getDimensionPixelSize(R.dimen.qs_media_album_size);
    }

    /**
     * Get the player view holder used to display media controls.
     *
     * @return the player view holder
     */
    @Nullable
    public PlayerViewHolder getPlayerViewHolder() {
        return mPlayerViewHolder;
    }

    /**
     * Get the recommendation view holder used to display Smartspace media recs.
     * @return the recommendation view holder
     */
    @Nullable
    public RecommendationViewHolder getRecommendationViewHolder() {
        return mRecommendationViewHolder;
    }

    /**
     * Get the view controller used to display media controls
     *
     * @return the media view controller
     */
    @NonNull
    public MediaViewController getMediaViewController() {
        return mMediaViewController;
    }

    /**
     * Sets the listening state of the player.
     * <p>
     * Should be set to true when the QS panel is open. Otherwise, false. This is a signal to avoid
     * unnecessary work when the QS panel is closed.
     *
     * @param listening True when player should be active. Otherwise, false.
     */
    public void setListening(boolean listening) {
        mSeekBarViewModel.setListening(listening);
    }

    /**
     * Get the context
     *
     * @return context
     */
    public Context getContext() {
        return mContext;
    }

    /** Attaches the player to the player view holder. */
    public void attachPlayer(PlayerViewHolder vh) {
        mPlayerViewHolder = vh;
        TransitionLayout player = vh.getPlayer();

        ImageView albumView = vh.getAlbumView();
        albumView.setOutlineProvider(mViewOutlineProvider);
        albumView.setClipToOutline(true);

        mSeekBarObserver = new SeekBarObserver(vh);
        mSeekBarViewModel.getProgress().observeForever(mSeekBarObserver);
        mSeekBarViewModel.attachTouchHandlers(vh.getSeekBar());
        mMediaViewController.attach(player, MediaViewController.TYPE.PLAYER);

        mPlayerViewHolder.getPlayer().setOnLongClickListener(v -> {
            if (!mMediaViewController.isGutsVisible()) {
                mMediaViewController.openGuts();
                return true;
            } else {
                return false;
            }
        });
        mPlayerViewHolder.getCancel().setOnClickListener(v -> {
            closeGuts();
        });
        mPlayerViewHolder.getSettings().setOnClickListener(v -> {
            mActivityStarter.startActivity(SETTINGS_INTENT, true /* dismissShade */);
        });
    }

    /** Attaches the recommendations to the recommendation view holder. */
    public void attachRecommendation(RecommendationViewHolder vh) {
        mRecommendationViewHolder = vh;
        TransitionLayout recommendations = vh.getRecommendations();

        mMediaViewController.attach(recommendations, MediaViewController.TYPE.RECOMMENDATION);

        mRecommendationViewHolder.getRecommendations().setOnLongClickListener(v -> {
            if (!mMediaViewController.isGutsVisible()) {
                mMediaViewController.openGuts();
                return true;
            } else {
                return false;
            }
        });
        mRecommendationViewHolder.getCancel().setOnClickListener(v -> {
            closeGuts();
        });
        mRecommendationViewHolder.getSettings().setOnClickListener(v -> {
            mActivityStarter.startActivity(SETTINGS_INTENT, true /* dismissShade */);
        });
    }

    /** Bind this player view based on the data given. */
    public void bindPlayer(@NonNull MediaData data, String key) {
        if (mPlayerViewHolder == null) {
            return;
        }
        mKey = key;
        MediaSession.Token token = data.getToken();
        mBackgroundColor = data.getBackgroundColor();
        if (mToken == null || !mToken.equals(token)) {
            mToken = token;
        }

        if (mToken != null) {
            mController = new MediaController(mContext, mToken);
        } else {
            mController = null;
        }

        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();

        mPlayerViewHolder.getPlayer().setBackgroundTintList(
                ColorStateList.valueOf(mBackgroundColor));

        // Click action
        PendingIntent clickIntent = data.getClickIntent();
        if (clickIntent != null) {
            mPlayerViewHolder.getPlayer().setOnClickListener(v -> {
                if (mMediaViewController.isGutsVisible()) return;
                mActivityStarter.postStartActivityDismissingKeyguard(clickIntent,
                        buildLaunchAnimatorController(mPlayerViewHolder.getPlayer()));
            });
        }

        ImageView albumView = mPlayerViewHolder.getAlbumView();
        boolean hasArtwork = data.getArtwork() != null;
        if (hasArtwork) {
            Drawable artwork = scaleDrawable(data.getArtwork());
            albumView.setImageDrawable(artwork);
        }
        setVisibleAndAlpha(collapsedSet, R.id.album_art, hasArtwork);
        setVisibleAndAlpha(expandedSet, R.id.album_art, hasArtwork);

        // App icon
        ImageView appIcon = mPlayerViewHolder.getAppIcon();
        if (data.getAppIcon() != null) {
            appIcon.setImageIcon(data.getAppIcon());
        } else {
            appIcon.setImageResource(R.drawable.ic_music_note);
        }

        // Song name

        TextView titleText = mPlayerViewHolder.getTitleText();
        titleText.setText(data.getSong());

        // App title
        TextView appName = mPlayerViewHolder.getAppName();
        appName.setText(data.getApp());
        appName.setVisibility(mShowAppName ? View.VISIBLE : View.GONE);
        setVisibleAndAlpha(collapsedSet, R.id.app_name, mShowAppName);
        setVisibleAndAlpha(expandedSet, R.id.app_name, mShowAppName);

        // Artist name
        TextView artistText = mPlayerViewHolder.getArtistText();
        artistText.setText(data.getArtist());

        // Transfer chip
        mPlayerViewHolder.getSeamless().setVisibility(View.VISIBLE);
        setVisibleAndAlpha(collapsedSet, R.id.media_seamless, true /*visible */);
        setVisibleAndAlpha(expandedSet, R.id.media_seamless, true /*visible */);
        mPlayerViewHolder.getSeamless().setOnClickListener(v -> {
            mMediaOutputDialogFactory.create(data.getPackageName(), true);
        });
        TextView mDeviceName = mPlayerViewHolder.getSeamlessText();
        mDeviceName.setVisibility(mShowDeviceName ? View.VISIBLE : View.GONE);
        setVisibleAndAlpha(collapsedSet, R.id.media_seamless_text, mShowDeviceName);
        setVisibleAndAlpha(expandedSet, R.id.media_seamless_text, mShowDeviceName);

        ImageView iconView = mPlayerViewHolder.getSeamlessIcon();
        TextView deviceName = mPlayerViewHolder.getSeamlessText();

        final MediaDeviceData device = data.getDevice();
        final int seamlessId = mPlayerViewHolder.getSeamless().getId();
        final int seamlessFallbackId = mPlayerViewHolder.getSeamlessFallback().getId();
        final boolean showFallback = device != null && !device.getEnabled();
        final int seamlessFallbackVisibility = showFallback ? View.VISIBLE : View.GONE;
        mPlayerViewHolder.getSeamlessFallback().setVisibility(seamlessFallbackVisibility);
        expandedSet.setVisibility(seamlessFallbackId, seamlessFallbackVisibility);
        collapsedSet.setVisibility(seamlessFallbackId, seamlessFallbackVisibility);
        final int seamlessVisibility = showFallback ? View.GONE : View.VISIBLE;
        mPlayerViewHolder.getSeamless().setVisibility(seamlessVisibility);
        expandedSet.setVisibility(seamlessId, seamlessVisibility);
        collapsedSet.setVisibility(seamlessId, seamlessVisibility);
        final float seamlessAlpha = data.getResumption() ? DISABLED_ALPHA : 1.0f;
        expandedSet.setAlpha(seamlessId, seamlessAlpha);
        collapsedSet.setAlpha(seamlessId, seamlessAlpha);
        // Disable clicking on output switcher for resumption controls.
        mPlayerViewHolder.getSeamless().setEnabled(!data.getResumption());
        if (showFallback) {
            iconView.setImageDrawable(null);
            deviceName.setText(null);
        } else if (device != null) {
            Drawable icon = device.getIcon();
            iconView.setVisibility(View.VISIBLE);
            if (icon instanceof AdaptiveIcon) {
                AdaptiveIcon aIcon = (AdaptiveIcon) icon;
                aIcon.setBackgroundColor(mBackgroundColor);
                iconView.setImageDrawable(aIcon);
            } else {
                iconView.setImageDrawable(icon);
            }
            deviceName.setText(device.getName());
        } else {
            // Reset to default
            Log.w(TAG, "device is null. Not binding output chip.");
            iconView.setVisibility(View.GONE);
            deviceName.setText(com.android.internal.R.string.ext_media_seamless_action);
        }

        List<Integer> actionsWhenCollapsed = data.getActionsToShowInCompact();
        // Media controls
        int i = 0;
        List<MediaAction> actionIcons = data.getActions();
        for (; i < actionIcons.size() && i < ACTION_IDS.length; i++) {
            int actionId = ACTION_IDS[i];
            final ImageButton button = mPlayerViewHolder.getAction(actionId);
            MediaAction mediaAction = actionIcons.get(i);
            button.setImageIcon(mediaAction.getIcon());
            button.setContentDescription(mediaAction.getContentDescription());
            Runnable action = mediaAction.getAction();

            if (action == null) {
                button.setEnabled(false);
            } else {
                button.setEnabled(true);
                button.setOnClickListener(v -> {
                    action.run();
                });
            }
            boolean visibleInCompat = actionsWhenCollapsed.contains(i);
            setVisibleAndAlpha(collapsedSet, actionId, visibleInCompat);
            setVisibleAndAlpha(expandedSet, actionId, true /*visible */);
        }

        // Hide any unused buttons
        for (; i < ACTION_IDS.length; i++) {
            setVisibleAndAlpha(expandedSet, ACTION_IDS[i], false /*visible */);
            setVisibleAndAlpha(collapsedSet, ACTION_IDS[i], false /*visible */);
        }

        // Seek Bar
        final MediaController controller = getController();
        mBackgroundExecutor.execute(() -> mSeekBarViewModel.updateController(controller));

        // Guts label
        boolean isDismissible = data.isClearable();
        mPlayerViewHolder.getSettingsText().setText(isDismissible
                ? R.string.controls_media_close_session
                : R.string.controls_media_active_session);

        // Dismiss
        mPlayerViewHolder.getDismissLabel().setAlpha(isDismissible ? 1 : DISABLED_ALPHA);
        mPlayerViewHolder.getDismiss().setEnabled(isDismissible);
        mPlayerViewHolder.getDismiss().setOnClickListener(v -> {
            if (mKey != null) {
                closeGuts();
                mKeyguardDismissUtil.executeWhenUnlocked(() -> {
                    mMediaDataManagerLazy.get().dismissMediaData(mKey,
                            MediaViewController.GUTS_ANIMATION_DURATION + 100);
                    return true;
                }, /* requiresShadeOpen */ true);
            } else {
                Log.w(TAG, "Dismiss media with null notification. Token uid="
                        + data.getToken().getUid());
            }
        });

        // TODO: We don't need to refresh this state constantly, only if the state actually changed
        // to something which might impact the measurement
        mMediaViewController.refreshState();
    }

    @Nullable
    private ActivityLaunchAnimator.Controller buildLaunchAnimatorController(
            TransitionLayout player) {
        // TODO(b/174236650): Make sure that the carousel indicator also fades out.
        // TODO(b/174236650): Instrument the animation to measure jank.
        return new GhostedViewLaunchAnimatorController(player) {
            @Override
            protected float getCurrentTopCornerRadius() {
                return ((IlluminationDrawable) player.getBackground()).getCornerRadius();
            }

            @Override
            protected float getCurrentBottomCornerRadius() {
                // TODO(b/184121838): Make IlluminationDrawable support top and bottom radius.
                return getCurrentTopCornerRadius();
            }

            @Override
            protected void setBackgroundCornerRadius(Drawable background, float topCornerRadius,
                    float bottomCornerRadius) {
                // TODO(b/184121838): Make IlluminationDrawable support top and bottom radius.
                float radius = Math.min(topCornerRadius, bottomCornerRadius);
                ((IlluminationDrawable) background).setCornerRadiusOverride(radius);
            }

            @Override
            public void onLaunchAnimationEnd(boolean isExpandingFullyAbove) {
                super.onLaunchAnimationEnd(isExpandingFullyAbove);
                ((IlluminationDrawable) player.getBackground()).setCornerRadiusOverride(null);
            }
        };
    }

    /** Bind this recommendation view based on the data given. */
    public void bindRecommendation(@NonNull SmartspaceTarget target, @NonNull int backgroundColor) {
        if (mRecommendationViewHolder == null) {
            return;
        }

        mRecommendationViewHolder.getRecommendations()
                .setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
        mBackgroundColor = backgroundColor;

        List<SmartspaceAction> mediaRecommendationList = target.getIconGrid();
        if (mediaRecommendationList == null || mediaRecommendationList.isEmpty()) {
            Log.w(TAG, "Empty media recommendations");
            return;
        }

        List<ImageView> mediaCoverItems = mRecommendationViewHolder.getMediaCoverItems();
        List<ImageView> mediaLogoItems = mRecommendationViewHolder.getMediaLogoItems();
        List<Integer> mediaCoverItemsResIds = mRecommendationViewHolder.getMediaCoverItemsResIds();
        List<Integer> mediaLogoItemsResIds = mRecommendationViewHolder.getMediaLogoItemsResIds();
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();
        int mediaRecommendationNum = Math.min(mediaRecommendationList.size(),
                MEDIA_RECOMMENDATION_MAX_NUM);
        for (int i = 0; i < mediaRecommendationNum; i++) {
            SmartspaceAction recommendation = mediaRecommendationList.get(i);
            if (recommendation.getIcon() == null) {
                Log.w(TAG, "No media cover is provided. Skipping this item...");
                continue;
            }

            // Get media source app's logo.
            Bundle extras = recommendation.getExtras();
            Drawable icon = null;
            if (extras != null && extras.getString(EXTRAS_MEDIA_SOURCE_PACKAGE_NAME) != null) {
                // Get the logo from app's package name when applicable.
                String packageName = extras.getString(EXTRAS_MEDIA_SOURCE_PACKAGE_NAME);
                try {
                    icon = mContext.getPackageManager().getApplicationIcon(
                            packageName);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "No media source icon can be fetched via package name", e);
                }
            } else {
                Log.w(TAG, "No media source icon is provided. Skipping this item...");
                continue;
            }

            // Set up media source app's logo.
            ImageView mediaSourceLogoImageView = mediaLogoItems.get(i);
            mediaSourceLogoImageView.setImageDrawable(icon);

            // Set up media item cover.
            ImageView mediaCoverImageView = mediaCoverItems.get(i);
            mediaCoverImageView.setImageIcon(recommendation.getIcon());

            // Set up the click listener if applicable.
            setSmartspaceRecItemOnClickListener(
                    mediaCoverImageView,
                    recommendation,
                    target.getSmartspaceTargetId(),
                    view -> mMediaDataManagerLazy
                            .get()
                            .dismissSmartspaceRecommendation(0L /* delay */));

            setVisibleAndAlpha(expandedSet, mediaCoverItemsResIds.get(i), true);
            setVisibleAndAlpha(expandedSet, mediaLogoItemsResIds.get(i), true);
            setVisibleAndAlpha(collapsedSet, mediaCoverItemsResIds.get(i), true);
            setVisibleAndAlpha(collapsedSet, mediaLogoItemsResIds.get(i), true);
        }

        // Set up long press to show guts setting panel.
        mRecommendationViewHolder.getDismiss().setOnClickListener(v -> {
            closeGuts();
            mKeyguardDismissUtil.executeWhenUnlocked(() -> {
                mMediaDataManagerLazy.get().dismissSmartspaceRecommendation(
                        MediaViewController.GUTS_ANIMATION_DURATION + 100L);
                return true;
            }, true /* requiresShadeOpen */);
        });

        mController = null;
        mMediaViewController.refreshState();
    }

    /**
     * Close the guts for this player.
     *
     * @param immediate {@code true} if it should be closed without animation
     */
    public void closeGuts(boolean immediate) {
        mMediaViewController.closeGuts(immediate);
    }

    private void closeGuts() {
        closeGuts(false);
    }

    @UiThread
    private Drawable scaleDrawable(Icon icon) {
        if (icon == null) {
            return null;
        }
        // Let's scale down the View, such that the content always nicely fills the view.
        // ThumbnailUtils actually scales it down such that it may not be filled for odd aspect
        // ratios
        Drawable drawable = icon.loadDrawable(mContext);
        float aspectRatio = drawable.getIntrinsicHeight() / (float) drawable.getIntrinsicWidth();
        Rect bounds;
        if (aspectRatio > 1.0f) {
            bounds = new Rect(0, 0, mAlbumArtSize, (int) (mAlbumArtSize * aspectRatio));
        } else {
            bounds = new Rect(0, 0, (int) (mAlbumArtSize / aspectRatio), mAlbumArtSize);
        }
        if (bounds.width() > mAlbumArtSize || bounds.height() > mAlbumArtSize) {
            float offsetX = (bounds.width() - mAlbumArtSize) / 2.0f;
            float offsetY = (bounds.height() - mAlbumArtSize) / 2.0f;
            bounds.offset((int) -offsetX, (int) -offsetY);
        }
        drawable.setBounds(bounds);
        return drawable;
    }

    /**
     * Get the current media controller
     *
     * @return the controller
     */
    public MediaController getController() {
        return mController;
    }

    /**
     * Check whether the media controlled by this player is currently playing
     *
     * @return whether it is playing, or false if no controller information
     */
    public boolean isPlaying() {
        return isPlaying(mController);
    }

    /**
     * Check whether the given controller is currently playing
     *
     * @param controller media controller to check
     * @return whether it is playing, or false if no controller information
     */
    protected boolean isPlaying(MediaController controller) {
        if (controller == null) {
            return false;
        }

        PlaybackState state = controller.getPlaybackState();
        if (state == null) {
            return false;
        }

        return (state.getState() == PlaybackState.STATE_PLAYING);
    }

    private void setVisibleAndAlpha(ConstraintSet set, int actionId, boolean visible) {
        set.setVisibility(actionId, visible ? ConstraintSet.VISIBLE : ConstraintSet.GONE);
        set.setAlpha(actionId, visible ? 1.0f : 0.0f);
    }

    private void setSmartspaceRecItemOnClickListener(
            @NonNull View view,
            @NonNull SmartspaceAction action,
            @NonNull String targetId,
            @Nullable View.OnClickListener callback) {
        if (view == null || action == null || action.getIntent() == null) {
            Log.e(TAG, "No tap action can be set up");
            return;
        }

        view.setOnClickListener(v -> {
            // When media recommendation card is shown, there could be only one card.
            SysUiStatsLog.write(SysUiStatsLog.SMARTSPACE_CARD_REPORTED,
                    760, // SMARTSPACE_CARD_CLICK
                    targetId.hashCode(),
                    SysUiStatsLog
                            .SMART_SPACE_CARD_REPORTED__CARD_TYPE__HEADPHONE_MEDIA_RECOMMENDATIONS,
                    getSurfaceForSmartspaceLogging(mMediaViewController.getCurrentEndLocation()),
                    /* rank */ 1,
                    /* cardinality */ 1);

            mActivityStarter.postStartActivityDismissingKeyguard(
                    action.getIntent(),
                    0 /* delay */,
                    buildLaunchAnimatorController(mRecommendationViewHolder.getRecommendations()));
            if (callback != null) {
                callback.onClick(v);
            }
        });
    }

    private int getSurfaceForSmartspaceLogging(int currentEndLocation) {
        if (currentEndLocation == MediaHierarchyManager.LOCATION_QQS
                || currentEndLocation == MediaHierarchyManager.LOCATION_QS) {
            return SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE;
        } else if (currentEndLocation == MediaHierarchyManager.LOCATION_LOCKSCREEN) {
            return SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__LOCKSCREEN;
        }
        return SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__DEFAULT_SURFACE;
    }
}
