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

package com.android.systemui.qs;

import static com.android.systemui.classifier.Classifier.QS_SWIPE;
import static com.android.systemui.media.dagger.MediaModule.QS_PANEL;
import static com.android.systemui.qs.QSPanel.QS_SHOW_BRIGHTNESS;
import static com.android.systemui.qs.dagger.QSFragmentModule.QS_USING_MEDIA_PLAYER;

import android.annotation.NonNull;
import android.content.res.Configuration;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.media.MediaHierarchyManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.settings.brightness.BrightnessController;
import com.android.systemui.settings.brightness.BrightnessMirrorHandler;
import com.android.systemui.settings.brightness.BrightnessSliderController;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.Utils;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Controller for {@link QSPanel}.
 */
@QSScope
public class QSPanelController extends QSPanelControllerBase<QSPanel> {

    private final QSFgsManagerFooter mQSFgsManagerFooter;
    private final QSSecurityFooter mQsSecurityFooter;
    private final TunerService mTunerService;
    private final QSCustomizerController mQsCustomizerController;
    private final QSTileRevealController.Factory mQsTileRevealControllerFactory;
    private final FalsingManager mFalsingManager;
    private final BrightnessController mBrightnessController;
    private final BrightnessSliderController mBrightnessSliderController;
    private final BrightnessMirrorHandler mBrightnessMirrorHandler;
    private final FeatureFlags mFeatureFlags;

    private boolean mGridContentVisible = true;

    private final QSPanel.OnConfigurationChangedListener mOnConfigurationChangedListener =
            new QSPanel.OnConfigurationChangedListener() {
        @Override
        public void onConfigurationChange(Configuration newConfig) {
            updateMediaExpansion();
            mView.updateResources();
            mQsSecurityFooter.onConfigurationChanged();
            if (mView.isListening()) {
                refreshAllTiles();
            }
            mView.switchSecurityFooter(mShouldUseSplitNotificationShade);
        }
    };

    private View.OnTouchListener mTileLayoutTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mFalsingManager.isFalseTouch(QS_SWIPE);
            }
            return false;
        }
    };

    @Inject
    QSPanelController(QSPanel view, QSFgsManagerFooter qsFgsManagerFooter,
            QSSecurityFooter qsSecurityFooter, TunerService tunerService,
            QSTileHost qstileHost, QSCustomizerController qsCustomizerController,
            @Named(QS_USING_MEDIA_PLAYER) boolean usingMediaPlayer,
            @Named(QS_PANEL) MediaHost mediaHost,
            QSTileRevealController.Factory qsTileRevealControllerFactory,
            DumpManager dumpManager, MetricsLogger metricsLogger, UiEventLogger uiEventLogger,
            QSLogger qsLogger, BrightnessController.Factory brightnessControllerFactory,
            BrightnessSliderController.Factory brightnessSliderFactory,
            FalsingManager falsingManager, FeatureFlags featureFlags) {
        super(view, qstileHost, qsCustomizerController, usingMediaPlayer, mediaHost,
                metricsLogger, uiEventLogger, qsLogger, dumpManager);
        mQSFgsManagerFooter = qsFgsManagerFooter;
        mQsSecurityFooter = qsSecurityFooter;
        mTunerService = tunerService;
        mQsCustomizerController = qsCustomizerController;
        mQsTileRevealControllerFactory = qsTileRevealControllerFactory;
        mFalsingManager = falsingManager;

        mBrightnessSliderController = brightnessSliderFactory.create(getContext(), mView);
        mView.setBrightnessView(mBrightnessSliderController.getRootView());

        mBrightnessController = brightnessControllerFactory.create(mBrightnessSliderController);
        mBrightnessMirrorHandler = new BrightnessMirrorHandler(mBrightnessController);
        mFeatureFlags = featureFlags;
        view.setUseNewFooter(featureFlags.isEnabled(Flags.NEW_FOOTER));
    }

    @Override
    public void onInit() {
        super.onInit();
        updateMediaExpansion();
        mMediaHost.setShowsOnlyActiveMedia(false);
        mMediaHost.init(MediaHierarchyManager.LOCATION_QS);
        mQsCustomizerController.init();
        mBrightnessSliderController.init();
        mQSFgsManagerFooter.init();
    }

    private void updateMediaExpansion() {
        mMediaHost.setExpansion(Utils.shouldUseSplitNotificationShade(getResources()) ? 0 : 1);
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();

        updateMediaDisappearParameters();

        mTunerService.addTunable(mView, QS_SHOW_BRIGHTNESS);
        mView.updateResources();
        if (mView.isListening()) {
            refreshAllTiles();
        }
        mView.addOnConfigurationChangedListener(mOnConfigurationChangedListener);
        if (!mFeatureFlags.isEnabled(Flags.NEW_FOOTER)) {
            mView.setSecurityFooter(mQsSecurityFooter.getView(), mShouldUseSplitNotificationShade);
            mView.setFgsManagerFooter(mQSFgsManagerFooter.getView());
        }
        switchTileLayout(true);
        mBrightnessMirrorHandler.onQsPanelAttached();

        ((PagedTileLayout) mView.getOrCreateTileLayout())
                .setOnTouchListener(mTileLayoutTouchListener);
    }

    @Override
    protected QSTileRevealController createTileRevealController() {
        return mQsTileRevealControllerFactory.create(
                this, (PagedTileLayout) mView.getOrCreateTileLayout());
    }

    @Override
    protected void onViewDetached() {
        mTunerService.removeTunable(mView);
        mView.removeOnConfigurationChangedListener(mOnConfigurationChangedListener);
        mBrightnessMirrorHandler.onQsPanelDettached();
        super.onViewDetached();
    }

    /**
     * Set the header container of quick settings.
     */
    public void setHeaderContainer(@NonNull ViewGroup headerContainer) {
        mView.setHeaderContainer(headerContainer);
    }

    /** */
    public void setVisibility(int visibility) {
        mView.setVisibility(visibility);
    }

    /** */
    public void setListening(boolean listening, boolean expanded) {
        // TODO(218268829): checking for split shade is workaround but when proper fix lands
        //  "|| mShouldUseSplitNotificationShade" should be removed
        setListening(listening && (expanded || mShouldUseSplitNotificationShade));
        if (mView.isListening()) {
            refreshAllTiles();
        }

        if (!mFeatureFlags.isEnabled(Flags.NEW_FOOTER)) {
            mQSFgsManagerFooter.setListening(listening);
            mQsSecurityFooter.setListening(listening);
        }

        // Set the listening as soon as the QS fragment starts listening regardless of the
        //expansion, so it will update the current brightness before the slider is visible.
        if (listening) {
            mBrightnessController.registerCallbacks();
        } else {
            mBrightnessController.unregisterCallbacks();
        }
    }

    public void setBrightnessMirror(BrightnessMirrorController brightnessMirrorController) {
        mBrightnessMirrorHandler.setController(brightnessMirrorController);
    }

    /** Get the QSTileHost this panel uses. */
    public QSTileHost getHost() {
        return mHost;
    }

    /** Show the device monitoring dialog. */
    public void showDeviceMonitoringDialog() {
        mQsSecurityFooter.showDeviceMonitoringDialog();
    }

    /** Update appearance of QSPanel. */
    public void updateResources() {
        mView.updateResources();
    }

    /** Update state of all tiles. */
    public void refreshAllTiles() {
        mBrightnessController.checkRestrictionAndSetEnabled();
        super.refreshAllTiles();
        mQSFgsManagerFooter.refreshState();
        mQsSecurityFooter.refreshState();
    }

    /** Start customizing the Quick Settings. */
    public void showEdit(View view) {
        view.post(() -> {
            if (!mQsCustomizerController.isCustomizing()) {
                int[] loc = view.getLocationOnScreen();
                int x = loc[0] + view.getWidth() / 2;
                int y = loc[1] + view.getHeight() / 2;
                mQsCustomizerController.show(x, y, false);
            }
        });
    }

    /** */
    public void setGridContentVisibility(boolean visible) {
        int newVis = visible ? View.VISIBLE : View.INVISIBLE;
        setVisibility(newVis);
        if (mGridContentVisible != visible) {
            mMetricsLogger.visibility(MetricsEvent.QS_PANEL, newVis);
        }
        mGridContentVisible = visible;
    }

    public boolean isLayoutRtl() {
        return mView.isLayoutRtl();
    }

    /** */
    public void setPageListener(PagedTileLayout.PageListener listener) {
        mView.setPageListener(listener);
    }

    public boolean isShown() {
        return mView.isShown();
    }

    /** */
    public void setContentMargins(int startMargin, int endMargin) {
        mView.setContentMargins(startMargin, endMargin, mMediaHost.getHostView());
    }

    /** */
    public void setFooterPageIndicator(PageIndicator pageIndicator) {
        mView.setFooterPageIndicator(pageIndicator);
    }

    /** */
    public boolean isExpanded() {
        return mView.isExpanded();
    }

    void setPageMargin(int pageMargin) {
        mView.setPageMargin(pageMargin);
    }
}

