/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.app;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionManager;
import android.app.prediction.AppPredictor;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.provider.DeviceConfig;
import android.provider.DocumentsContract;
import android.provider.Downloads;
import android.provider.OpenableColumns;
import android.service.chooser.ChooserTarget;
import android.service.chooser.ChooserTargetService;
import android.service.chooser.IChooserTargetResult;
import android.service.chooser.IChooserTargetService;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.HashedStringCache;
import android.util.Log;
import android.util.Size;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverListAdapter.ActivityInfoPresentationGetter;
import com.android.internal.app.ResolverListAdapter.ViewHolder;
import com.android.internal.app.chooser.ChooserTargetInfo;
import com.android.internal.app.chooser.DisplayResolveInfo;
import com.android.internal.app.chooser.MultiDisplayResolveInfo;
import com.android.internal.app.chooser.NotSelectableTargetInfo;
import com.android.internal.app.chooser.SelectableTargetInfo;
import com.android.internal.app.chooser.SelectableTargetInfo.SelectableTargetInfoCommunicator;
import com.android.internal.app.chooser.TargetInfo;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.content.PackageMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.widget.GridLayoutManager;
import com.android.internal.widget.RecyclerView;
import com.android.internal.widget.ResolverDrawerLayout;

import com.google.android.collect.Lists;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Chooser Activity handles intent resolution specifically for sharing intents -
 * for example, those generated by @see android.content.Intent#createChooser(Intent, CharSequence).
 *
 */
public class ChooserActivity extends ResolverActivity implements
        ChooserListAdapter.ChooserListCommunicator,
        SelectableTargetInfoCommunicator {
    private static final String TAG = "ChooserActivity";

    @UnsupportedAppUsage
    public ChooserActivity() {
    }
    /**
     * Boolean extra to change the following behavior: Normally, ChooserActivity finishes itself
     * in onStop when launched in a new task. If this extra is set to true, we do not finish
     * ourselves when onStop gets called.
     */
    public static final String EXTRA_PRIVATE_RETAIN_IN_ON_STOP
            = "com.android.internal.app.ChooserActivity.EXTRA_PRIVATE_RETAIN_IN_ON_STOP";

    private static final String PREF_NUM_SHEET_EXPANSIONS = "pref_num_sheet_expansions";

    private static final boolean DEBUG = false;

    private static final boolean USE_PREDICTION_MANAGER_FOR_SHARE_ACTIVITIES = true;
    // TODO(b/123088566) Share these in a better way.
    private static final String APP_PREDICTION_SHARE_UI_SURFACE = "share";
    public static final String LAUNCH_LOCATON_DIRECT_SHARE = "direct_share";
    private static final int APP_PREDICTION_SHARE_TARGET_QUERY_PACKAGE_LIMIT = 20;
    public static final String APP_PREDICTION_INTENT_FILTER_KEY = "intent_filter";

    @VisibleForTesting
    public static final int LIST_VIEW_UPDATE_INTERVAL_IN_MILLIS = 250;

    private boolean mIsAppPredictorComponentAvailable;
    private AppPredictor mAppPredictor;
    private AppPredictor.Callback mAppPredictorCallback;
    private Map<ChooserTarget, AppTarget> mDirectShareAppTargetCache;

    public static final int TARGET_TYPE_DEFAULT = 0;
    public static final int TARGET_TYPE_CHOOSER_TARGET = 1;
    public static final int TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER = 2;
    public static final int TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE = 3;

    private static final boolean USE_CHOOSER_TARGET_SERVICE_FOR_DIRECT_TARGETS = true;

    @IntDef(flag = false, prefix = { "TARGET_TYPE_" }, value = {
            TARGET_TYPE_DEFAULT,
            TARGET_TYPE_CHOOSER_TARGET,
            TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER,
            TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShareTargetType {}

    /**
     * The transition time between placeholders for direct share to a message
     * indicating that non are available.
     */
    private static final int NO_DIRECT_SHARE_ANIM_IN_MILLIS = 200;

    private static final float DIRECT_SHARE_EXPANSION_RATE = 0.78f;

    // TODO(b/121287224): Re-evaluate this limit
    private static final int SHARE_TARGET_QUERY_PACKAGE_LIMIT = 20;

    private static final int QUERY_TARGET_SERVICE_LIMIT = 5;

    private static final int DEFAULT_SALT_EXPIRATION_DAYS = 7;
    private int mMaxHashSaltDays = DeviceConfig.getInt(DeviceConfig.NAMESPACE_SYSTEMUI,
            SystemUiDeviceConfigFlags.HASH_SALT_MAX_DAYS,
            DEFAULT_SALT_EXPIRATION_DAYS);

    private Bundle mReplacementExtras;
    private IntentSender mChosenComponentSender;
    private IntentSender mRefinementIntentSender;
    private RefinementResultReceiver mRefinementResultReceiver;
    private ChooserTarget[] mCallerChooserTargets;
    private ComponentName[] mFilteredComponentNames;

    private Intent mReferrerFillInIntent;

    private long mChooserShownTime;
    protected boolean mIsSuccessfullySelected;

    private long mQueriedTargetServicesTimeMs;
    private long mQueriedSharingShortcutsTimeMs;

    private int mChooserRowServiceSpacing;

    private int mCurrAvailableWidth = 0;

    private static final String TARGET_DETAILS_FRAGMENT_TAG = "targetDetailsFragment";
    // TODO: Update to handle landscape instead of using static value
    private static final int MAX_RANKED_TARGETS = 4;

    private final List<ChooserTargetServiceConnection> mServiceConnections = new ArrayList<>();
    private final Set<ComponentName> mServicesRequested = new HashSet<>();

    private static final int MAX_LOG_RANK_POSITION = 12;

    private static final int MAX_EXTRA_INITIAL_INTENTS = 2;
    private static final int MAX_EXTRA_CHOOSER_TARGETS = 2;

    private SharedPreferences mPinnedSharedPrefs;
    private static final String PINNED_SHARED_PREFS_NAME = "chooser_pin_settings";

    @Retention(SOURCE)
    @IntDef({CONTENT_PREVIEW_FILE, CONTENT_PREVIEW_IMAGE, CONTENT_PREVIEW_TEXT})
    private @interface ContentPreviewType {
    }

    // Starting at 1 since 0 is considered "undefined" for some of the database transformations
    // of tron logs.
    private static final int CONTENT_PREVIEW_IMAGE = 1;
    private static final int CONTENT_PREVIEW_FILE = 2;
    private static final int CONTENT_PREVIEW_TEXT = 3;
    protected MetricsLogger mMetricsLogger;

    private ContentPreviewCoordinator mPreviewCoord;

    @VisibleForTesting
    protected ChooserMultiProfilePagerAdapter mChooserMultiProfilePagerAdapter;

    private class ContentPreviewCoordinator {
        private static final int IMAGE_FADE_IN_MILLIS = 150;
        private static final int IMAGE_LOAD_TIMEOUT = 1;
        private static final int IMAGE_LOAD_INTO_VIEW = 2;

        private final int mImageLoadTimeoutMillis =
                getResources().getInteger(R.integer.config_shortAnimTime);

        private final View mParentView;
        private boolean mHideParentOnFail;
        private boolean mAtLeastOneLoaded = false;

        class LoadUriTask {
            public final Uri mUri;
            public final int mImageResourceId;
            public final int mExtraCount;
            public final Bitmap mBmp;

            LoadUriTask(int imageResourceId, Uri uri, int extraCount, Bitmap bmp) {
                this.mImageResourceId = imageResourceId;
                this.mUri = uri;
                this.mExtraCount = extraCount;
                this.mBmp = bmp;
            }
        }

        // If at least one image loads within the timeout period, allow other
        // loads to continue. Otherwise terminate and optionally hide
        // the parent area
        private final Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case IMAGE_LOAD_TIMEOUT:
                        maybeHideContentPreview();
                        break;

                    case IMAGE_LOAD_INTO_VIEW:
                        if (isFinishing()) break;

                        LoadUriTask task = (LoadUriTask) msg.obj;
                        RoundedRectImageView imageView = mParentView.findViewById(
                                task.mImageResourceId);
                        if (task.mBmp == null) {
                            imageView.setVisibility(View.GONE);
                            maybeHideContentPreview();
                            return;
                        }

                        mAtLeastOneLoaded = true;
                        imageView.setVisibility(View.VISIBLE);
                        imageView.setAlpha(0.0f);
                        imageView.setImageBitmap(task.mBmp);

                        ValueAnimator fadeAnim = ObjectAnimator.ofFloat(imageView, "alpha", 0.0f,
                                1.0f);
                        fadeAnim.setInterpolator(new DecelerateInterpolator(1.0f));
                        fadeAnim.setDuration(IMAGE_FADE_IN_MILLIS);
                        fadeAnim.start();

                        if (task.mExtraCount > 0) {
                            imageView.setExtraImageCount(task.mExtraCount);
                        }
                }
            }
        };

        ContentPreviewCoordinator(View parentView, boolean hideParentOnFail) {
            super();

            this.mParentView = parentView;
            this.mHideParentOnFail = hideParentOnFail;
        }

        private void loadUriIntoView(final int imageResourceId, final Uri uri,
                final int extraImages) {
            mHandler.sendEmptyMessageDelayed(IMAGE_LOAD_TIMEOUT, mImageLoadTimeoutMillis);

            AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
                int size = getResources().getDimensionPixelSize(
                        R.dimen.chooser_preview_image_max_dimen);
                final Bitmap bmp = loadThumbnail(uri, new Size(size, size));
                final Message msg = Message.obtain();
                msg.what = IMAGE_LOAD_INTO_VIEW;
                msg.obj = new LoadUriTask(imageResourceId, uri, extraImages, bmp);
                mHandler.sendMessage(msg);
            });
        }

        private void cancelLoads() {
            mHandler.removeMessages(IMAGE_LOAD_INTO_VIEW);
            mHandler.removeMessages(IMAGE_LOAD_TIMEOUT);
        }

        private void maybeHideContentPreview() {
            if (!mAtLeastOneLoaded && mHideParentOnFail) {
                Log.i(TAG, "Hiding image preview area. Timed out waiting for preview to load"
                        + " within " + mImageLoadTimeoutMillis + "ms.");
                collapseParentView();
                hideContentPreview();
                mHideParentOnFail = false;
            }
        }

        private void collapseParentView() {
            // This will effectively hide the content preview row by forcing the height
            // to zero. It is faster than forcing a relayout of the listview
            final View v = mParentView;
            int widthSpec = MeasureSpec.makeMeasureSpec(v.getWidth(), MeasureSpec.EXACTLY);
            int heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY);
            v.measure(widthSpec, heightSpec);
            v.getLayoutParams().height = 0;
            v.layout(v.getLeft(), v.getTop(), v.getRight(), v.getTop());
            v.invalidate();
        }
    }

    private final ChooserHandler mChooserHandler = new ChooserHandler();

    private class ChooserHandler extends Handler {
        private static final int CHOOSER_TARGET_SERVICE_RESULT = 1;
        private static final int CHOOSER_TARGET_SERVICE_WATCHDOG_MIN_TIMEOUT = 2;
        private static final int CHOOSER_TARGET_SERVICE_WATCHDOG_MAX_TIMEOUT = 3;
        private static final int SHORTCUT_MANAGER_SHARE_TARGET_RESULT = 4;
        private static final int SHORTCUT_MANAGER_SHARE_TARGET_RESULT_COMPLETED = 5;
        private static final int LIST_VIEW_UPDATE_MESSAGE = 6;

        private static final int WATCHDOG_TIMEOUT_MAX_MILLIS = 10000;
        private static final int WATCHDOG_TIMEOUT_MIN_MILLIS = 3000;

        private boolean mMinTimeoutPassed = false;

        private void removeAllMessages() {
            removeMessages(LIST_VIEW_UPDATE_MESSAGE);
            removeMessages(CHOOSER_TARGET_SERVICE_WATCHDOG_MIN_TIMEOUT);
            removeMessages(CHOOSER_TARGET_SERVICE_WATCHDOG_MAX_TIMEOUT);
            removeMessages(CHOOSER_TARGET_SERVICE_RESULT);
            removeMessages(SHORTCUT_MANAGER_SHARE_TARGET_RESULT);
            removeMessages(SHORTCUT_MANAGER_SHARE_TARGET_RESULT_COMPLETED);
        }

        private void restartServiceRequestTimer() {
            mMinTimeoutPassed = false;
            removeMessages(CHOOSER_TARGET_SERVICE_WATCHDOG_MIN_TIMEOUT);
            removeMessages(CHOOSER_TARGET_SERVICE_WATCHDOG_MAX_TIMEOUT);

            if (DEBUG) {
                Log.d(TAG, "queryTargets setting watchdog timer for "
                        + WATCHDOG_TIMEOUT_MIN_MILLIS + "-"
                        + WATCHDOG_TIMEOUT_MAX_MILLIS + "ms");
            }

            sendEmptyMessageDelayed(CHOOSER_TARGET_SERVICE_WATCHDOG_MIN_TIMEOUT,
                    WATCHDOG_TIMEOUT_MIN_MILLIS);
            sendEmptyMessageDelayed(CHOOSER_TARGET_SERVICE_WATCHDOG_MAX_TIMEOUT,
                    WATCHDOG_TIMEOUT_MAX_MILLIS);
        }

        private void maybeStopServiceRequestTimer() {
            // Set a minimum timeout threshold, to ensure both apis, sharing shortcuts
            // and older-style direct share services, have had time to load, otherwise
            // just checking mServiceConnections could force us to end prematurely
            if (mMinTimeoutPassed && mServiceConnections.isEmpty()) {
                logDirectShareTargetReceived(
                        MetricsEvent.ACTION_DIRECT_SHARE_TARGETS_LOADED_CHOOSER_SERVICE);
                sendVoiceChoicesIfNeeded();
                mChooserMultiProfilePagerAdapter.getActiveListAdapter()
                        .completeServiceTargetLoading();
            }
        }

        @Override
        public void handleMessage(Message msg) {
            if (mChooserMultiProfilePagerAdapter.getActiveListAdapter() == null || isDestroyed()) {
                return;
            }

            switch (msg.what) {
                case CHOOSER_TARGET_SERVICE_RESULT:
                    if (DEBUG) Log.d(TAG, "CHOOSER_TARGET_SERVICE_RESULT");
                    final ServiceResultInfo sri = (ServiceResultInfo) msg.obj;
                    if (!mServiceConnections.contains(sri.connection)) {
                        Log.w(TAG, "ChooserTargetServiceConnection " + sri.connection
                                + " returned after being removed from active connections."
                                + " Have you considered returning results faster?");
                        break;
                    }
                    if (sri.resultTargets != null) {
                        // TODO(arangelov): Instead of using getCurrentListAdapter(), pass the
                        // profileId as part of the message.
                        mChooserMultiProfilePagerAdapter.getActiveListAdapter().addServiceResults(
                                sri.originalTarget, sri.resultTargets, TARGET_TYPE_CHOOSER_TARGET);
                    }
                    unbindService(sri.connection);
                    sri.connection.destroy();
                    mServiceConnections.remove(sri.connection);
                    maybeStopServiceRequestTimer();
                    break;

                case CHOOSER_TARGET_SERVICE_WATCHDOG_MIN_TIMEOUT:
                    mMinTimeoutPassed = true;
                    maybeStopServiceRequestTimer();
                    break;

                case CHOOSER_TARGET_SERVICE_WATCHDOG_MAX_TIMEOUT:
                    unbindRemainingServices();
                    maybeStopServiceRequestTimer();
                    break;

                case LIST_VIEW_UPDATE_MESSAGE:
                    if (DEBUG) {
                        Log.d(TAG, "LIST_VIEW_UPDATE_MESSAGE; ");
                    }

                    mChooserMultiProfilePagerAdapter.getActiveListAdapter().refreshListView();
                    break;

                case SHORTCUT_MANAGER_SHARE_TARGET_RESULT:
                    if (DEBUG) Log.d(TAG, "SHORTCUT_MANAGER_SHARE_TARGET_RESULT");
                    final ServiceResultInfo resultInfo = (ServiceResultInfo) msg.obj;
                    if (resultInfo.resultTargets != null) {
                        mChooserMultiProfilePagerAdapter.getActiveListAdapter().addServiceResults(
                                resultInfo.originalTarget, resultInfo.resultTargets, msg.arg1);
                    }
                    break;

                case SHORTCUT_MANAGER_SHARE_TARGET_RESULT_COMPLETED:
                    logDirectShareTargetReceived(
                            MetricsEvent.ACTION_DIRECT_SHARE_TARGETS_LOADED_SHORTCUT_MANAGER);
                    sendVoiceChoicesIfNeeded();
                    break;

                default:
                    super.handleMessage(msg);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        final long intentReceivedTime = System.currentTimeMillis();
        // This is the only place this value is being set. Effectively final.
        //TODO(arangelov) - should there be a mIsAppPredictorComponentAvailable flag for work tab?
        mIsAppPredictorComponentAvailable = isAppPredictionServiceAvailable();

        mIsSuccessfullySelected = false;
        Intent intent = getIntent();
        Parcelable targetParcelable = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        if (!(targetParcelable instanceof Intent)) {
            Log.w("ChooserActivity", "Target is not an intent: " + targetParcelable);
            finish();
            super.onCreate(null);
            return;
        }
        Intent target = (Intent) targetParcelable;
        if (target != null) {
            modifyTargetIntent(target);
        }
        Parcelable[] targetsParcelable
                = intent.getParcelableArrayExtra(Intent.EXTRA_ALTERNATE_INTENTS);
        if (targetsParcelable != null) {
            final boolean offset = target == null;
            Intent[] additionalTargets =
                    new Intent[offset ? targetsParcelable.length - 1 : targetsParcelable.length];
            for (int i = 0; i < targetsParcelable.length; i++) {
                if (!(targetsParcelable[i] instanceof Intent)) {
                    Log.w(TAG, "EXTRA_ALTERNATE_INTENTS array entry #" + i + " is not an Intent: "
                            + targetsParcelable[i]);
                    finish();
                    super.onCreate(null);
                    return;
                }
                final Intent additionalTarget = (Intent) targetsParcelable[i];
                if (i == 0 && target == null) {
                    target = additionalTarget;
                    modifyTargetIntent(target);
                } else {
                    additionalTargets[offset ? i - 1 : i] = additionalTarget;
                    modifyTargetIntent(additionalTarget);
                }
            }
            setAdditionalTargets(additionalTargets);
        }

        mReplacementExtras = intent.getBundleExtra(Intent.EXTRA_REPLACEMENT_EXTRAS);

        // Do not allow the title to be changed when sharing content
        CharSequence title = null;
        if (target != null) {
            if (!isSendAction(target)) {
                title = intent.getCharSequenceExtra(Intent.EXTRA_TITLE);
            } else {
                Log.w(TAG, "Ignoring intent's EXTRA_TITLE, deprecated in P. You may wish to set a"
                        + " preview title by using EXTRA_TITLE property of the wrapped"
                        + " EXTRA_INTENT.");
            }
        }

        int defaultTitleRes = 0;
        if (title == null) {
            defaultTitleRes = com.android.internal.R.string.chooseActivity;
        }

        Parcelable[] pa = intent.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS);
        Intent[] initialIntents = null;
        if (pa != null) {
            int count = Math.min(pa.length, MAX_EXTRA_INITIAL_INTENTS);
            initialIntents = new Intent[count];
            for (int i = 0; i < count; i++) {
                if (!(pa[i] instanceof Intent)) {
                    Log.w(TAG, "Initial intent #" + i + " not an Intent: " + pa[i]);
                    finish();
                    super.onCreate(null);
                    return;
                }
                final Intent in = (Intent) pa[i];
                modifyTargetIntent(in);
                initialIntents[i] = in;
            }
        }

        mReferrerFillInIntent = new Intent().putExtra(Intent.EXTRA_REFERRER, getReferrer());

        mChosenComponentSender = intent.getParcelableExtra(
                Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER);
        mRefinementIntentSender = intent.getParcelableExtra(
                Intent.EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER);
        setSafeForwardingMode(true);

        mPinnedSharedPrefs = getPinnedSharedPrefs(this);

        pa = intent.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS);
        if (pa != null) {
            ComponentName[] names = new ComponentName[pa.length];
            for (int i = 0; i < pa.length; i++) {
                if (!(pa[i] instanceof ComponentName)) {
                    Log.w(TAG, "Filtered component #" + i + " not a ComponentName: " + pa[i]);
                    names = null;
                    break;
                }
                names[i] = (ComponentName) pa[i];
            }
            mFilteredComponentNames = names;
        }

        pa = intent.getParcelableArrayExtra(Intent.EXTRA_CHOOSER_TARGETS);
        if (pa != null) {
            int count = Math.min(pa.length, MAX_EXTRA_CHOOSER_TARGETS);
            ChooserTarget[] targets = new ChooserTarget[count];
            for (int i = 0; i < count; i++) {
                if (!(pa[i] instanceof ChooserTarget)) {
                    Log.w(TAG, "Chooser target #" + i + " not a ChooserTarget: " + pa[i]);
                    targets = null;
                    break;
                }
                targets[i] = (ChooserTarget) pa[i];
            }
            mCallerChooserTargets = targets;
        }

        setRetainInOnStop(intent.getBooleanExtra(EXTRA_PRIVATE_RETAIN_IN_ON_STOP, false));
        super.onCreate(savedInstanceState, target, title, defaultTitleRes, initialIntents,
                null, false);

        mChooserShownTime = System.currentTimeMillis();
        final long systemCost = mChooserShownTime - intentReceivedTime;

        getMetricsLogger().write(new LogMaker(MetricsEvent.ACTION_ACTIVITY_CHOOSER_SHOWN)
                .setSubtype(isWorkProfile() ? MetricsEvent.MANAGED_PROFILE :
                        MetricsEvent.PARENT_PROFILE)
                .addTaggedData(MetricsEvent.FIELD_SHARESHEET_MIMETYPE, target.getType())
                .addTaggedData(MetricsEvent.FIELD_TIME_TO_APP_TARGETS, systemCost));

        AppPredictor appPredictor = getAppPredictorForDirectShareIfEnabled();
        if (appPredictor != null) {
            mDirectShareAppTargetCache = new HashMap<>();
            mAppPredictorCallback = resultList -> {
                //TODO(arangelov) Take care of edge case when callback called after swiping tabs
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (mChooserMultiProfilePagerAdapter.getActiveListAdapter().getCount() == 0) {
                    return;
                }
                if (resultList.isEmpty()) {
                    // APS may be disabled, so try querying targets ourselves.
                    //TODO(arangelov) queryDirectShareTargets indirectly uses mIntents.
                    // Investigate implications for work tab.
                    queryDirectShareTargets(
                            mChooserMultiProfilePagerAdapter.getActiveListAdapter(), true);
                    return;
                }
                final List<DisplayResolveInfo> driList =
                        getDisplayResolveInfos(
                                mChooserMultiProfilePagerAdapter.getActiveListAdapter());
                final List<ShortcutManager.ShareShortcutInfo> shareShortcutInfos =
                        new ArrayList<>();
                for (AppTarget appTarget : resultList) {
                    if (appTarget.getShortcutInfo() == null) {
                        continue;
                    }
                    shareShortcutInfos.add(new ShortcutManager.ShareShortcutInfo(
                            appTarget.getShortcutInfo(),
                            new ComponentName(
                                appTarget.getPackageName(), appTarget.getClassName())));
                }
                sendShareShortcutInfoList(shareShortcutInfos, driList, resultList);
            };
            appPredictor
                .registerPredictionUpdates(this.getMainExecutor(), mAppPredictorCallback);
        }

        mChooserRowServiceSpacing = getResources()
                                        .getDimensionPixelSize(R.dimen.chooser_service_spacing);

        if (mResolverDrawerLayout != null) {
            mResolverDrawerLayout.addOnLayoutChangeListener(this::handleLayoutChange);

            // expand/shrink direct share 4 -> 8 viewgroup
            if (isSendAction(target)) {
                mResolverDrawerLayout.setOnScrollChangeListener(this::handleScroll);
            }

            final View chooserHeader = mResolverDrawerLayout.findViewById(R.id.chooser_header);
            final float defaultElevation = chooserHeader.getElevation();
            final float chooserHeaderScrollElevation =
                    getResources().getDimensionPixelSize(R.dimen.chooser_header_scroll_elevation);

            mChooserMultiProfilePagerAdapter.getCurrentAdapterView().addOnScrollListener(
                    new RecyclerView.OnScrollListener() {
                        public void onScrollStateChanged(RecyclerView view, int scrollState) {
                        }

                        public void onScrolled(RecyclerView view, int dx, int dy) {
                            if (view.getChildCount() > 0) {
                                View child = view.getLayoutManager().findViewByPosition(0);
                                if (child == null || child.getTop() < 0) {
                                    chooserHeader.setElevation(chooserHeaderScrollElevation);
                                    return;
                                }
                            }

                            chooserHeader.setElevation(defaultElevation);
                        }
            });

            mResolverDrawerLayout.setOnCollapsedChangedListener(
                    new ResolverDrawerLayout.OnCollapsedChangedListener() {

                        // Only consider one expansion per activity creation
                        private boolean mWrittenOnce = false;

                        @Override
                        public void onCollapsedChanged(boolean isCollapsed) {
                            if (!isCollapsed && !mWrittenOnce) {
                                incrementNumSheetExpansions();
                                mWrittenOnce = true;
                            }
                        }
                    });
        }

        if (DEBUG) {
            Log.d(TAG, "System Time Cost is " + systemCost);
        }
    }

    static SharedPreferences getPinnedSharedPrefs(Context context) {
        // The code below is because in the android:ui process, no one can hear you scream.
        // The package info in the context isn't initialized in the way it is for normal apps,
        // so the standard, name-based context.getSharedPreferences doesn't work. Instead, we
        // build the path manually below using the same policy that appears in ContextImpl.
        // This fails silently under the hood if there's a problem, so if we find ourselves in
        // the case where we don't have access to credential encrypted storage we just won't
        // have our pinned target info.
        final File prefsFile = new File(new File(
                Environment.getDataUserCePackageDirectory(StorageManager.UUID_PRIVATE_INTERNAL,
                        context.getUserId(), context.getPackageName()),
                "shared_prefs"),
                PINNED_SHARED_PREFS_NAME + ".xml");
        return context.getSharedPreferences(prefsFile, MODE_PRIVATE);
    }

    @Override
    protected AbstractMultiProfilePagerAdapter createMultiProfilePagerAdapter(
            Intent[] initialIntents,
            List<ResolveInfo> rList,
            boolean filterLastUsed) {
        if (hasWorkProfile() && ENABLE_TABBED_VIEW) {
            mChooserMultiProfilePagerAdapter = createChooserMultiProfilePagerAdapterForTwoProfiles(
                    initialIntents, rList, filterLastUsed);
        } else {
            mChooserMultiProfilePagerAdapter = createChooserMultiProfilePagerAdapterForOneProfile(
                    initialIntents, rList, filterLastUsed);
        }
        return mChooserMultiProfilePagerAdapter;
    }

    private ChooserMultiProfilePagerAdapter createChooserMultiProfilePagerAdapterForOneProfile(
            Intent[] initialIntents,
            List<ResolveInfo> rList,
            boolean filterLastUsed) {
        ChooserGridAdapter adapter = createChooserGridAdapter(
                /* context */ this,
                /* payloadIntents */ mIntents,
                initialIntents,
                rList,
                filterLastUsed,
                mUseLayoutForBrowsables,
                /* userHandle */ UserHandle.of(UserHandle.myUserId()));
        return new ChooserMultiProfilePagerAdapter(
                /* context */ this,
                adapter);
    }

    private ChooserMultiProfilePagerAdapter createChooserMultiProfilePagerAdapterForTwoProfiles(
            Intent[] initialIntents,
            List<ResolveInfo> rList,
            boolean filterLastUsed) {
        ChooserGridAdapter personalAdapter = createChooserGridAdapter(
                /* context */ this,
                /* payloadIntents */ mIntents,
                initialIntents,
                rList,
                filterLastUsed,
                mUseLayoutForBrowsables,
                /* userHandle */ getPersonalProfileUserHandle());
        ChooserGridAdapter workAdapter = createChooserGridAdapter(
                /* context */ this,
                /* payloadIntents */ mIntents,
                initialIntents,
                rList,
                filterLastUsed,
                mUseLayoutForBrowsables,
                /* userHandle */ getWorkProfileUserHandle());
        return new ChooserMultiProfilePagerAdapter(
                /* context */ this,
                personalAdapter,
                workAdapter,
                /* defaultProfile */ getCurrentProfile());
    }

    @Override
    protected boolean postRebuildList(boolean rebuildCompleted) {
        updateContentPreview();
        return postRebuildListInternal(rebuildCompleted);
    }

    /**
     * Returns true if app prediction service is defined and the component exists on device.
     */
    @VisibleForTesting
    public boolean isAppPredictionServiceAvailable() {
        if (getPackageManager().getAppPredictionServicePackageName() == null) {
            // Default AppPredictionService is not defined.
            return false;
        }

        final String appPredictionServiceName =
                getString(R.string.config_defaultAppPredictionService);
        if (appPredictionServiceName == null) {
            return false;
        }
        final ComponentName appPredictionComponentName =
                ComponentName.unflattenFromString(appPredictionServiceName);
        if (appPredictionComponentName == null) {
            return false;
        }

        // Check if the app prediction component actually exists on the device.
        Intent intent = new Intent();
        intent.setComponent(appPredictionComponentName);
        if (getPackageManager().resolveService(intent, PackageManager.MATCH_ALL) == null) {
            Log.e(TAG, "App prediction service is defined, but does not exist: "
                    + appPredictionServiceName);
            return false;
        }
        return true;
    }

    /**
     * Check if the profile currently used is a work profile.
     * @return true if it is work profile, false if it is parent profile (or no work profile is
     * set up)
     */
    protected boolean isWorkProfile() {
        return getSystemService(UserManager.class)
                .getUserInfo(UserHandle.myUserId()).isManagedProfile();
    }

    @Override
    protected PackageMonitor createPackageMonitor() {
        return new PackageMonitor() {
            @Override
            public void onSomePackagesChanged() {
                handlePackagesChanged();
            }
        };
    }

    /**
     * Update UI to reflect changes in data.
     */
    public void handlePackagesChanged() {
        // TODO(arangelov): Dispatch this to all adapters when we have the helper methods
        // in a follow-up CL
        mChooserMultiProfilePagerAdapter.getActiveListAdapter().handlePackagesChanged();
        updateProfileViewButton();
    }

    private void onCopyButtonClicked(View v) {
        Intent targetIntent = getTargetIntent();
        if (targetIntent == null) {
            finish();
        } else {
            final String action = targetIntent.getAction();

            ClipData clipData = null;
            if (Intent.ACTION_SEND.equals(action)) {
                String extraText = targetIntent.getStringExtra(Intent.EXTRA_TEXT);
                Uri extraStream = targetIntent.getParcelableExtra(Intent.EXTRA_STREAM);

                if (extraText != null) {
                    clipData = ClipData.newPlainText(null, extraText);
                } else if (extraStream != null) {
                    clipData = ClipData.newUri(getContentResolver(), null, extraStream);
                } else {
                    Log.w(TAG, "No data available to copy to clipboard");
                    return;
                }
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                final ArrayList<Uri> streams = targetIntent.getParcelableArrayListExtra(
                        Intent.EXTRA_STREAM);
                clipData = ClipData.newUri(getContentResolver(), null, streams.get(0));
                for (int i = 1; i < streams.size(); i++) {
                    clipData.addItem(getContentResolver(), new ClipData.Item(streams.get(i)));
                }
            } else {
                // expected to only be visible with ACTION_SEND or ACTION_SEND_MULTIPLE
                // so warn about unexpected action
                Log.w(TAG, "Action (" + action + ") not supported for copying to clipboard");
                return;
            }

            ClipboardManager clipboardManager = (ClipboardManager) getSystemService(
                    Context.CLIPBOARD_SERVICE);
            clipboardManager.setPrimaryClip(clipData);
            Toast.makeText(getApplicationContext(), R.string.copied, Toast.LENGTH_SHORT).show();

            // Log share completion via copy
            LogMaker targetLogMaker = new LogMaker(
                    MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_SYSTEM_TARGET).setSubtype(1);
            getMetricsLogger().write(targetLogMaker);

            finish();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        adjustPreviewWidth(newConfig.orientation, null);
    }

    private boolean shouldDisplayLandscape(int orientation) {
        // Sharesheet fixes the # of items per row and therefore can not correctly lay out
        // when in the restricted size of multi-window mode. In the future, would be nice
        // to use minimum dp size requirements instead
        return orientation == Configuration.ORIENTATION_LANDSCAPE && !isInMultiWindowMode();
    }

    private void adjustPreviewWidth(int orientation, View parent) {
        int width = -1;
        if (shouldDisplayLandscape(orientation)) {
            width = getResources().getDimensionPixelSize(R.dimen.chooser_preview_width);
        }

        parent = parent == null ? getWindow().getDecorView() : parent;

        updateLayoutWidth(R.id.content_preview_text_layout, width, parent);
        updateLayoutWidth(R.id.content_preview_title_layout, width, parent);
        updateLayoutWidth(R.id.content_preview_file_layout, width, parent);
    }

    private void updateLayoutWidth(int layoutResourceId, int width, View parent) {
        View view = parent.findViewById(layoutResourceId);
        if (view != null && view.getLayoutParams() != null) {
            LayoutParams params = view.getLayoutParams();
            params.width = width;
            view.setLayoutParams(params);
        }
    }

    private ViewGroup createContentPreviewView(ViewGroup parent) {
        Intent targetIntent = getTargetIntent();
        int previewType = findPreferredContentPreview(targetIntent, getContentResolver());
        return displayContentPreview(previewType, targetIntent, getLayoutInflater(), parent);
    }

    private ViewGroup displayContentPreview(@ContentPreviewType int previewType,
            Intent targetIntent, LayoutInflater layoutInflater, ViewGroup parent) {
        ViewGroup layout = null;

        switch (previewType) {
            case CONTENT_PREVIEW_TEXT:
                layout = displayTextContentPreview(targetIntent, layoutInflater, parent);
                break;
            case CONTENT_PREVIEW_IMAGE:
                layout = displayImageContentPreview(targetIntent, layoutInflater, parent);
                break;
            case CONTENT_PREVIEW_FILE:
                layout = displayFileContentPreview(targetIntent, layoutInflater, parent);
                break;
            default:
                Log.e(TAG, "Unexpected content preview type: " + previewType);
        }

        if (layout != null) {
            adjustPreviewWidth(getResources().getConfiguration().orientation, layout);
        }

        return layout;
    }

    private ViewGroup displayTextContentPreview(Intent targetIntent, LayoutInflater layoutInflater,
            ViewGroup parent) {
        ViewGroup contentPreviewLayout = (ViewGroup) layoutInflater.inflate(
                R.layout.chooser_grid_preview_text, parent, false);

        contentPreviewLayout.findViewById(R.id.copy_button).setOnClickListener(
                this::onCopyButtonClicked);

        CharSequence sharingText = targetIntent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (sharingText == null) {
            contentPreviewLayout.findViewById(R.id.content_preview_text_layout).setVisibility(
                    View.GONE);
        } else {
            TextView textView = contentPreviewLayout.findViewById(R.id.content_preview_text);
            textView.setText(sharingText);
        }

        String previewTitle = targetIntent.getStringExtra(Intent.EXTRA_TITLE);
        if (TextUtils.isEmpty(previewTitle)) {
            contentPreviewLayout.findViewById(R.id.content_preview_title_layout).setVisibility(
                    View.GONE);
        } else {
            TextView previewTitleView = contentPreviewLayout.findViewById(
                    R.id.content_preview_title);
            previewTitleView.setText(previewTitle);

            ClipData previewData = targetIntent.getClipData();
            Uri previewThumbnail = null;
            if (previewData != null) {
                if (previewData.getItemCount() > 0) {
                    ClipData.Item previewDataItem = previewData.getItemAt(0);
                    previewThumbnail = previewDataItem.getUri();
                }
            }

            ImageView previewThumbnailView = contentPreviewLayout.findViewById(
                    R.id.content_preview_thumbnail);
            if (previewThumbnail == null) {
                previewThumbnailView.setVisibility(View.GONE);
            } else {
                mPreviewCoord = new ContentPreviewCoordinator(contentPreviewLayout, false);
                mPreviewCoord.loadUriIntoView(R.id.content_preview_thumbnail, previewThumbnail, 0);
            }
        }

        return contentPreviewLayout;
    }

    private ViewGroup displayImageContentPreview(Intent targetIntent, LayoutInflater layoutInflater,
            ViewGroup parent) {
        ViewGroup contentPreviewLayout = (ViewGroup) layoutInflater.inflate(
                R.layout.chooser_grid_preview_image, parent, false);
        mPreviewCoord = new ContentPreviewCoordinator(contentPreviewLayout, true);

        String action = targetIntent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = targetIntent.getParcelableExtra(Intent.EXTRA_STREAM);
            mPreviewCoord.loadUriIntoView(R.id.content_preview_image_1_large, uri, 0);
        } else {
            ContentResolver resolver = getContentResolver();

            List<Uri> uris = targetIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            List<Uri> imageUris = new ArrayList<>();
            for (Uri uri : uris) {
                if (isImageType(resolver.getType(uri))) {
                    imageUris.add(uri);
                }
            }

            if (imageUris.size() == 0) {
                Log.i(TAG, "Attempted to display image preview area with zero"
                        + " available images detected in EXTRA_STREAM list");
                contentPreviewLayout.setVisibility(View.GONE);
                return contentPreviewLayout;
            }

            mPreviewCoord.loadUriIntoView(R.id.content_preview_image_1_large, imageUris.get(0), 0);

            if (imageUris.size() == 2) {
                mPreviewCoord.loadUriIntoView(R.id.content_preview_image_2_large,
                        imageUris.get(1), 0);
            } else if (imageUris.size() > 2) {
                mPreviewCoord.loadUriIntoView(R.id.content_preview_image_2_small,
                        imageUris.get(1), 0);
                mPreviewCoord.loadUriIntoView(R.id.content_preview_image_3_small,
                        imageUris.get(2), imageUris.size() - 3);
            }
        }

        return contentPreviewLayout;
    }

    private static class FileInfo {
        public final String name;
        public final boolean hasThumbnail;

        FileInfo(String name, boolean hasThumbnail) {
            this.name = name;
            this.hasThumbnail = hasThumbnail;
        }
    }

    /**
     * Wrapping the ContentResolver call to expose for easier mocking,
     * and to avoid mocking Android core classes.
     */
    @VisibleForTesting
    public Cursor queryResolver(ContentResolver resolver, Uri uri) {
        return resolver.query(uri, null, null, null, null);
    }

    private FileInfo extractFileInfo(Uri uri, ContentResolver resolver) {
        String fileName = null;
        boolean hasThumbnail = false;

        try (Cursor cursor = queryResolver(resolver, uri)) {
            if (cursor != null && cursor.getCount() > 0) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int titleIndex = cursor.getColumnIndex(Downloads.Impl.COLUMN_TITLE);
                int flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS);

                cursor.moveToFirst();
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex);
                } else if (titleIndex != -1) {
                    fileName = cursor.getString(titleIndex);
                }

                if (flagsIndex != -1) {
                    hasThumbnail = (cursor.getInt(flagsIndex)
                            & DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL) != 0;
                }
            }
        } catch (SecurityException | NullPointerException e) {
            logContentPreviewWarning(uri);
        }

        if (TextUtils.isEmpty(fileName)) {
            fileName = uri.getPath();
            int index = fileName.lastIndexOf('/');
            if (index != -1) {
                fileName = fileName.substring(index + 1);
            }
        }

        return new FileInfo(fileName, hasThumbnail);
    }

    private void logContentPreviewWarning(Uri uri) {
        // The ContentResolver already logs the exception. Log something more informative.
        Log.w(TAG, "Could not load (" + uri.toString() + ") thumbnail/name for preview. If "
                + "desired, consider using Intent#createChooser to launch the ChooserActivity, "
                + "and set your Intent's clipData and flags in accordance with that method's "
                + "documentation");
    }

    private ViewGroup displayFileContentPreview(Intent targetIntent, LayoutInflater layoutInflater,
            ViewGroup parent) {

        ViewGroup contentPreviewLayout = (ViewGroup) layoutInflater.inflate(
                R.layout.chooser_grid_preview_file, parent, false);

        // TODO(b/120417119): Disable file copy until after moving to sysui,
        // due to permissions issues
        contentPreviewLayout.findViewById(R.id.file_copy_button).setVisibility(View.GONE);

        String action = targetIntent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = targetIntent.getParcelableExtra(Intent.EXTRA_STREAM);
            loadFileUriIntoView(uri, contentPreviewLayout);
        } else {
            List<Uri> uris = targetIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            int uriCount = uris.size();

            if (uriCount == 0) {
                contentPreviewLayout.setVisibility(View.GONE);
                Log.i(TAG,
                        "Appears to be no uris available in EXTRA_STREAM, removing "
                                + "preview area");
                return contentPreviewLayout;
            } else if (uriCount == 1) {
                loadFileUriIntoView(uris.get(0), contentPreviewLayout);
            } else {
                FileInfo fileInfo = extractFileInfo(uris.get(0), getContentResolver());
                int remUriCount = uriCount - 1;
                String fileName = getResources().getQuantityString(R.plurals.file_count,
                        remUriCount, fileInfo.name, remUriCount);

                TextView fileNameView = contentPreviewLayout.findViewById(
                        R.id.content_preview_filename);
                fileNameView.setText(fileName);

                View thumbnailView = contentPreviewLayout.findViewById(
                        R.id.content_preview_file_thumbnail);
                thumbnailView.setVisibility(View.GONE);

                ImageView fileIconView = contentPreviewLayout.findViewById(
                        R.id.content_preview_file_icon);
                fileIconView.setVisibility(View.VISIBLE);
                fileIconView.setImageResource(R.drawable.ic_file_copy);
            }
        }

        return contentPreviewLayout;
    }

    private void loadFileUriIntoView(final Uri uri, final View parent) {
        FileInfo fileInfo = extractFileInfo(uri, getContentResolver());

        TextView fileNameView = parent.findViewById(R.id.content_preview_filename);
        fileNameView.setText(fileInfo.name);

        if (fileInfo.hasThumbnail) {
            mPreviewCoord = new ContentPreviewCoordinator(parent, false);
            mPreviewCoord.loadUriIntoView(R.id.content_preview_file_thumbnail, uri, 0);
        } else {
            View thumbnailView = parent.findViewById(R.id.content_preview_file_thumbnail);
            thumbnailView.setVisibility(View.GONE);

            ImageView fileIconView = parent.findViewById(R.id.content_preview_file_icon);
            fileIconView.setVisibility(View.VISIBLE);
            fileIconView.setImageResource(R.drawable.chooser_file_generic);
        }
    }

    @VisibleForTesting
    protected boolean isImageType(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    @ContentPreviewType
    private int findPreferredContentPreview(Uri uri, ContentResolver resolver) {
        if (uri == null) {
            return CONTENT_PREVIEW_TEXT;
        }

        String mimeType = resolver.getType(uri);
        return isImageType(mimeType) ? CONTENT_PREVIEW_IMAGE : CONTENT_PREVIEW_FILE;
    }

    /**
     * In {@link android.content.Intent#getType}, the app may specify a very general
     * mime-type that broadly covers all data being shared, such as {@literal *}/*
     * when sending an image and text. We therefore should inspect each item for the
     * the preferred type, in order of IMAGE, FILE, TEXT.
     */
    @ContentPreviewType
    private int findPreferredContentPreview(Intent targetIntent, ContentResolver resolver) {
        String action = targetIntent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = targetIntent.getParcelableExtra(Intent.EXTRA_STREAM);
            return findPreferredContentPreview(uri, resolver);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<Uri> uris = targetIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris == null || uris.isEmpty()) {
                return CONTENT_PREVIEW_TEXT;
            }

            for (Uri uri : uris) {
                // Defaulting to file preview when there are mixed image/file types is
                // preferable, as it shows the user the correct number of items being shared
                if (findPreferredContentPreview(uri, resolver) == CONTENT_PREVIEW_FILE) {
                    return CONTENT_PREVIEW_FILE;
                }
            }

            return CONTENT_PREVIEW_IMAGE;
        }

        return CONTENT_PREVIEW_TEXT;
    }

    private int getNumSheetExpansions() {
        return getPreferences(Context.MODE_PRIVATE).getInt(PREF_NUM_SHEET_EXPANSIONS, 0);
    }

    private void incrementNumSheetExpansions() {
        getPreferences(Context.MODE_PRIVATE).edit().putInt(PREF_NUM_SHEET_EXPANSIONS,
                getNumSheetExpansions() + 1).apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRefinementResultReceiver != null) {
            mRefinementResultReceiver.destroy();
            mRefinementResultReceiver = null;
        }
        unbindRemainingServices();
        mChooserHandler.removeAllMessages();

        if (mPreviewCoord != null) mPreviewCoord.cancelLoads();

        if (mAppPredictor != null) {
            mAppPredictor.unregisterPredictionUpdates(mAppPredictorCallback);
            mAppPredictor.destroy();
        }
    }

    @Override // ResolverListCommunicator
    public Intent getReplacementIntent(ActivityInfo aInfo, Intent defIntent) {
        Intent result = defIntent;
        if (mReplacementExtras != null) {
            final Bundle replExtras = mReplacementExtras.getBundle(aInfo.packageName);
            if (replExtras != null) {
                result = new Intent(defIntent);
                result.putExtras(replExtras);
            }
        }
        if (aInfo.name.equals(IntentForwarderActivity.FORWARD_INTENT_TO_PARENT)
                || aInfo.name.equals(IntentForwarderActivity.FORWARD_INTENT_TO_MANAGED_PROFILE)) {
            result = Intent.createChooser(result,
                    getIntent().getCharSequenceExtra(Intent.EXTRA_TITLE));

            // Don't auto-launch single intents if the intent is being forwarded. This is done
            // because automatically launching a resolving application as a response to the user
            // action of switching accounts is pretty unexpected.
            result.putExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, false);
        }
        return result;
    }

    @Override
    public void onActivityStarted(TargetInfo cti) {
        if (mChosenComponentSender != null) {
            final ComponentName target = cti.getResolvedComponentName();
            if (target != null) {
                final Intent fillIn = new Intent().putExtra(Intent.EXTRA_CHOSEN_COMPONENT, target);
                try {
                    mChosenComponentSender.sendIntent(this, Activity.RESULT_OK, fillIn, null, null);
                } catch (IntentSender.SendIntentException e) {
                    Slog.e(TAG, "Unable to launch supplied IntentSender to report "
                            + "the chosen component: " + e);
                }
            }
        }
    }

    @Override
    public void onPrepareAdapterView(ResolverListAdapter adapter) {
        mChooserMultiProfilePagerAdapter.getCurrentAdapterView().setVisibility(View.VISIBLE);
        if (mCallerChooserTargets != null && mCallerChooserTargets.length > 0) {
            mChooserMultiProfilePagerAdapter.getActiveListAdapter().addServiceResults(
                    /* origTarget */ null,
                    Lists.newArrayList(mCallerChooserTargets),
                    TARGET_TYPE_DEFAULT);
        }
    }

    @Override
    public int getLayoutResource() {
        return R.layout.chooser_grid;
    }

    @Override // ResolverListCommunicator
    public boolean shouldGetActivityMetadata() {
        return true;
    }

    @Override
    public boolean shouldAutoLaunchSingleChoice(TargetInfo target) {
        // Note that this is only safe because the Intent handled by the ChooserActivity is
        // guaranteed to contain no extras unknown to the local ClassLoader. That is why this
        // method can not be replaced in the ResolverActivity whole hog.
        if (!super.shouldAutoLaunchSingleChoice(target)) {
            return false;
        }

        return getIntent().getBooleanExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, true);
    }

    void showTargetDetails(TargetInfo ti) {
        if (ti == null) {
            return;
        }
        ComponentName name = ti.getResolveInfo().activityInfo.getComponentName();
        boolean pinned = mPinnedSharedPrefs.getBoolean(name.flattenToString(), false);

        ResolverTargetActionsDialogFragment f;

        // For multiple targets, include info on all targets
        if (ti instanceof MultiDisplayResolveInfo) {
            MultiDisplayResolveInfo mti = (MultiDisplayResolveInfo) ti;
            List<CharSequence> labels = new ArrayList<>();

            for (TargetInfo innerInfo : mti.getTargets()) {
                labels.add(innerInfo.getResolveInfo().loadLabel(getPackageManager()));
            }
            f = new ResolverTargetActionsDialogFragment(mti.getDisplayLabel(), name,
                    mti.getTargets(), labels);
        } else {
            f = new ResolverTargetActionsDialogFragment(
                    ti.getResolveInfo().loadLabel(getPackageManager()), name, pinned);
        }

        f.show(getFragmentManager(), TARGET_DETAILS_FRAGMENT_TAG);
    }

    private void modifyTargetIntent(Intent in) {
        if (isSendAction(in)) {
            in.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
    }

    @Override
    protected boolean onTargetSelected(TargetInfo target, boolean alwaysCheck) {
        if (mRefinementIntentSender != null) {
            final Intent fillIn = new Intent();
            final List<Intent> sourceIntents = target.getAllSourceIntents();
            if (!sourceIntents.isEmpty()) {
                fillIn.putExtra(Intent.EXTRA_INTENT, sourceIntents.get(0));
                if (sourceIntents.size() > 1) {
                    final Intent[] alts = new Intent[sourceIntents.size() - 1];
                    for (int i = 1, N = sourceIntents.size(); i < N; i++) {
                        alts[i - 1] = sourceIntents.get(i);
                    }
                    fillIn.putExtra(Intent.EXTRA_ALTERNATE_INTENTS, alts);
                }
                if (mRefinementResultReceiver != null) {
                    mRefinementResultReceiver.destroy();
                }
                mRefinementResultReceiver = new RefinementResultReceiver(this, target, null);
                fillIn.putExtra(Intent.EXTRA_RESULT_RECEIVER,
                        mRefinementResultReceiver);
                try {
                    mRefinementIntentSender.sendIntent(this, 0, fillIn, null, null);
                    return false;
                } catch (SendIntentException e) {
                    Log.e(TAG, "Refinement IntentSender failed to send", e);
                }
            }
        }
        updateModelAndChooserCounts(target);
        return super.onTargetSelected(target, alwaysCheck);
    }

    @Override
    public void startSelected(int which, boolean always, boolean filtered) {
        ChooserListAdapter currentListAdapter =
                mChooserMultiProfilePagerAdapter.getActiveListAdapter();
        TargetInfo targetInfo = currentListAdapter
                .targetInfoForPosition(which, filtered);
        if (targetInfo != null && targetInfo instanceof NotSelectableTargetInfo) {
            return;
        }

        final long selectionCost = System.currentTimeMillis() - mChooserShownTime;

        if (targetInfo instanceof MultiDisplayResolveInfo) {
            MultiDisplayResolveInfo mti = (MultiDisplayResolveInfo) targetInfo;
            if (!mti.hasSelected()) {
                // Stacked apps get a disambiguation first
                CharSequence[] labels = new CharSequence[mti.getTargets().size()];
                int i = 0;
                for (TargetInfo ti : mti.getTargets()) {
                    labels[i++] = ti.getResolveInfo().loadLabel(getPackageManager());
                }
                ChooserStackedAppDialogFragment f = new ChooserStackedAppDialogFragment(
                        targetInfo.getDisplayLabel(),
                        ((MultiDisplayResolveInfo) targetInfo), labels, which);

                f.show(getFragmentManager(), TARGET_DETAILS_FRAGMENT_TAG);
                return;
            }
        }

        super.startSelected(which, always, filtered);


        if (currentListAdapter.getCount() > 0) {
            // Log the index of which type of target the user picked.
            // Lower values mean the ranking was better.
            int cat = 0;
            int value = which;
            int directTargetAlsoRanked = -1;
            int numCallerProvided = 0;
            HashedStringCache.HashResult directTargetHashed = null;
            switch (currentListAdapter.getPositionTargetType(which)) {
                case ChooserListAdapter.TARGET_SERVICE:
                    cat = MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_SERVICE_TARGET;
                    // Log the package name + target name to answer the question if most users
                    // share to mostly the same person or to a bunch of different people.
                    ChooserTarget target = currentListAdapter.getChooserTargetForValue(value);
                    directTargetHashed = HashedStringCache.getInstance().hashString(
                            this,
                            TAG,
                            target.getComponentName().getPackageName()
                                    + target.getTitle().toString(),
                            mMaxHashSaltDays);
                    directTargetAlsoRanked = getRankedPosition((SelectableTargetInfo) targetInfo);

                    if (mCallerChooserTargets != null) {
                        numCallerProvided = mCallerChooserTargets.length;
                    }
                    break;
                case ChooserListAdapter.TARGET_CALLER:
                case ChooserListAdapter.TARGET_STANDARD:
                    cat = MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_APP_TARGET;
                    value -= currentListAdapter.getSelectableServiceTargetCount();
                    numCallerProvided = currentListAdapter.getCallerTargetCount();
                    break;
                case ChooserListAdapter.TARGET_STANDARD_AZ:
                    // A-Z targets are unranked standard targets; we use -1 to mark that they
                    // are from the alphabetical pool.
                    value = -1;
                    cat = MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_STANDARD_TARGET;
                    break;
            }

            if (cat != 0) {
                LogMaker targetLogMaker = new LogMaker(cat).setSubtype(value);
                if (directTargetHashed != null) {
                    targetLogMaker.addTaggedData(
                            MetricsEvent.FIELD_HASHED_TARGET_NAME, directTargetHashed.hashedString);
                    targetLogMaker.addTaggedData(
                                    MetricsEvent.FIELD_HASHED_TARGET_SALT_GEN,
                                    directTargetHashed.saltGeneration);
                    targetLogMaker.addTaggedData(MetricsEvent.FIELD_RANKED_POSITION,
                                    directTargetAlsoRanked);
                }
                targetLogMaker.addTaggedData(MetricsEvent.FIELD_IS_CATEGORY_USED,
                        numCallerProvided);
                getMetricsLogger().write(targetLogMaker);
            }

            if (mIsSuccessfullySelected) {
                if (DEBUG) {
                    Log.d(TAG, "User Selection Time Cost is " + selectionCost);
                    Log.d(TAG, "position of selected app/service/caller is " +
                            Integer.toString(value));
                }
                MetricsLogger.histogram(null, "user_selection_cost_for_smart_sharing",
                        (int) selectionCost);
                MetricsLogger.histogram(null, "app_position_for_smart_sharing", value);
            }
        }
    }

    private int getRankedPosition(SelectableTargetInfo targetInfo) {
        String targetPackageName =
                targetInfo.getChooserTarget().getComponentName().getPackageName();
        ChooserListAdapter currentListAdapter =
                mChooserMultiProfilePagerAdapter.getActiveListAdapter();
        int maxRankedResults = Math.min(currentListAdapter.mDisplayList.size(),
                MAX_LOG_RANK_POSITION);

        for (int i = 0; i < maxRankedResults; i++) {
            if (currentListAdapter.mDisplayList.get(i)
                    .getResolveInfo().activityInfo.packageName.equals(targetPackageName)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected boolean shouldAddFooterView() {
        // To accommodate for window insets
        return true;
    }

    @Override
    protected void applyFooterView(int height) {
        int count = mChooserMultiProfilePagerAdapter.getItemCount();

        for (int i = 0; i < count; i++) {
            mChooserMultiProfilePagerAdapter.getAdapterForIndex(i).setFooterHeight(height);
        }
    }

    void queryTargetServices(ChooserListAdapter adapter) {
        mQueriedTargetServicesTimeMs = System.currentTimeMillis();

        final PackageManager pm = getPackageManager();
        ShortcutManager sm = (ShortcutManager) getSystemService(ShortcutManager.class);
        int targetsToQuery = 0;

        for (int i = 0, N = adapter.getDisplayResolveInfoCount(); i < N; i++) {
            final DisplayResolveInfo dri = adapter.getDisplayResolveInfo(i);
            if (adapter.getScore(dri) == 0) {
                // A score of 0 means the app hasn't been used in some time;
                // don't query it as it's not likely to be relevant.
                continue;
            }
            final ActivityInfo ai = dri.getResolveInfo().activityInfo;
            if (ChooserFlags.USE_SHORTCUT_MANAGER_FOR_DIRECT_TARGETS
                    && sm.hasShareTargets(ai.packageName)) {
                // Share targets will be queried from ShortcutManager
                continue;
            }
            final Bundle md = ai.metaData;
            final String serviceName = md != null ? convertServiceName(ai.packageName,
                    md.getString(ChooserTargetService.META_DATA_NAME)) : null;
            if (serviceName != null) {
                final ComponentName serviceComponent = new ComponentName(
                        ai.packageName, serviceName);

                if (mServicesRequested.contains(serviceComponent)) {
                    continue;
                }
                mServicesRequested.add(serviceComponent);

                final Intent serviceIntent = new Intent(ChooserTargetService.SERVICE_INTERFACE)
                        .setComponent(serviceComponent);

                if (DEBUG) {
                    Log.d(TAG, "queryTargets found target with service " + serviceComponent);
                }

                try {
                    final String perm = pm.getServiceInfo(serviceComponent, 0).permission;
                    if (!ChooserTargetService.BIND_PERMISSION.equals(perm)) {
                        Log.w(TAG, "ChooserTargetService " + serviceComponent + " does not require"
                                + " permission " + ChooserTargetService.BIND_PERMISSION
                                + " - this service will not be queried for ChooserTargets."
                                + " add android:permission=\""
                                + ChooserTargetService.BIND_PERMISSION + "\""
                                + " to the <service> tag for " + serviceComponent
                                + " in the manifest.");
                        continue;
                    }
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Could not look up service " + serviceComponent
                            + "; component name not found");
                    continue;
                }

                final ChooserTargetServiceConnection conn =
                        new ChooserTargetServiceConnection(this, dri);

                // Explicitly specify Process.myUserHandle instead of calling bindService
                // to avoid the warning from calling from the system process without an explicit
                // user handle
                if (bindServiceAsUser(serviceIntent, conn, BIND_AUTO_CREATE | BIND_NOT_FOREGROUND,
                        Process.myUserHandle())) {
                    if (DEBUG) {
                        Log.d(TAG, "Binding service connection for target " + dri
                                + " intent " + serviceIntent);
                    }
                    mServiceConnections.add(conn);
                    targetsToQuery++;
                }
            }
            if (targetsToQuery >= QUERY_TARGET_SERVICE_LIMIT) {
                if (DEBUG) {
                    Log.d(TAG, "queryTargets hit query target limit "
                            + QUERY_TARGET_SERVICE_LIMIT);
                }
                break;
            }
        }

        mChooserHandler.restartServiceRequestTimer();
    }

    private IntentFilter getTargetIntentFilter() {
        try {
            final Intent intent = getTargetIntent();
            String dataString = intent.getDataString();
            if (TextUtils.isEmpty(dataString)) {
                dataString = intent.getType();
            }
            return new IntentFilter(intent.getAction(), dataString);
        } catch (Exception e) {
            Log.e(TAG, "failed to get target intent filter " + e);
            return null;
        }
    }

    private List<DisplayResolveInfo> getDisplayResolveInfos(ChooserListAdapter adapter) {
        // Need to keep the original DisplayResolveInfos to be able to reconstruct ServiceResultInfo
        // and use the old code path. This Ugliness should go away when Sharesheet is refactored.
        List<DisplayResolveInfo> driList = new ArrayList<>();
        int targetsToQuery = 0;
        for (int i = 0, n = adapter.getDisplayResolveInfoCount(); i < n; i++) {
            final DisplayResolveInfo dri = adapter.getDisplayResolveInfo(i);
            if (adapter.getScore(dri) == 0) {
                // A score of 0 means the app hasn't been used in some time;
                // don't query it as it's not likely to be relevant.
                continue;
            }
            driList.add(dri);
            targetsToQuery++;
            // TODO(b/121287224): Do we need this here? (similar to queryTargetServices)
            if (targetsToQuery >= SHARE_TARGET_QUERY_PACKAGE_LIMIT) {
                if (DEBUG) {
                    Log.d(TAG, "queryTargets hit query target limit "
                            + SHARE_TARGET_QUERY_PACKAGE_LIMIT);
                }
                break;
            }
        }
        return driList;
    }

    private void queryDirectShareTargets(
                ChooserListAdapter adapter, boolean skipAppPredictionService) {
        mQueriedSharingShortcutsTimeMs = System.currentTimeMillis();
        if (!skipAppPredictionService) {
            AppPredictor appPredictor = getAppPredictorForDirectShareIfEnabled();
            if (appPredictor != null) {
                appPredictor.requestPredictionUpdate();
                return;
            }
        }
        // Default to just querying ShortcutManager if AppPredictor not present.
        //TODO(arangelov) we're using mIntents here, investicate possible implications on work tab
        final IntentFilter filter = getTargetIntentFilter();
        if (filter == null) {
            return;
        }
        final List<DisplayResolveInfo> driList = getDisplayResolveInfos(adapter);

        AsyncTask.execute(() -> {
            //TODO(arangelov) use the selected probile tab's ShortcutManager
            ShortcutManager sm = (ShortcutManager) getSystemService(Context.SHORTCUT_SERVICE);
            List<ShortcutManager.ShareShortcutInfo> resultList = sm.getShareTargets(filter);
            sendShareShortcutInfoList(resultList, driList, null);
        });
    }

    private void sendShareShortcutInfoList(
                List<ShortcutManager.ShareShortcutInfo> resultList,
                List<DisplayResolveInfo> driList,
                @Nullable List<AppTarget> appTargets) {
        if (appTargets != null && appTargets.size() != resultList.size()) {
            throw new RuntimeException("resultList and appTargets must have the same size."
                    + " resultList.size()=" + resultList.size()
                    + " appTargets.size()=" + appTargets.size());
        }

        for (int i = resultList.size() - 1; i >= 0; i--) {
            final String packageName = resultList.get(i).getTargetComponent().getPackageName();
            if (!isPackageEnabled(packageName)) {
                resultList.remove(i);
                if (appTargets != null) {
                    appTargets.remove(i);
                }
            }
        }

        // If |appTargets| is not null, results are from AppPredictionService and already sorted.
        final int shortcutType = (appTargets == null ? TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER :
                TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE);

        // Match ShareShortcutInfos with DisplayResolveInfos to be able to use the old code path
        // for direct share targets. After ShareSheet is refactored we should use the
        // ShareShortcutInfos directly.
        boolean resultMessageSent = false;
        for (int i = 0; i < driList.size(); i++) {
            List<ShortcutManager.ShareShortcutInfo> matchingShortcuts = new ArrayList<>();
            for (int j = 0; j < resultList.size(); j++) {
                if (driList.get(i).getResolvedComponentName().equals(
                            resultList.get(j).getTargetComponent())) {
                    matchingShortcuts.add(resultList.get(j));
                }
            }
            if (matchingShortcuts.isEmpty()) {
                continue;
            }
            List<ChooserTarget> chooserTargets = convertToChooserTarget(
                    matchingShortcuts, resultList, appTargets, shortcutType);

            final Message msg = Message.obtain();
            msg.what = ChooserHandler.SHORTCUT_MANAGER_SHARE_TARGET_RESULT;
            msg.obj = new ServiceResultInfo(driList.get(i), chooserTargets, null);
            msg.arg1 = shortcutType;
            mChooserHandler.sendMessage(msg);
            resultMessageSent = true;
        }

        if (resultMessageSent) {
            sendShortcutManagerShareTargetResultCompleted();
        }
    }

    private void sendShortcutManagerShareTargetResultCompleted() {
        final Message msg = Message.obtain();
        msg.what = ChooserHandler.SHORTCUT_MANAGER_SHARE_TARGET_RESULT_COMPLETED;
        mChooserHandler.sendMessage(msg);
    }

    private boolean isPackageEnabled(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        ApplicationInfo appInfo;
        try {
            appInfo = getPackageManager().getApplicationInfo(packageName, 0);
        } catch (NameNotFoundException e) {
            return false;
        }

        if (appInfo != null && appInfo.enabled
                && (appInfo.flags & ApplicationInfo.FLAG_SUSPENDED) == 0) {
            return true;
        }
        return false;
    }

    /**
     * Converts a list of ShareShortcutInfos to ChooserTargets.
     * @param matchingShortcuts List of shortcuts, all from the same package, that match the current
     *                         share intent filter.
     * @param allShortcuts List of all the shortcuts from all the packages on the device that are
     *                    returned for the current sharing action.
     * @param allAppTargets List of AppTargets. Null if the results are not from prediction service.
     * @param shortcutType One of the values TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER or
     *                    TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE
     * @return A list of ChooserTargets sorted by score in descending order.
     */
    @VisibleForTesting
    @NonNull
    public List<ChooserTarget> convertToChooserTarget(
            @NonNull List<ShortcutManager.ShareShortcutInfo> matchingShortcuts,
            @NonNull List<ShortcutManager.ShareShortcutInfo> allShortcuts,
            @Nullable List<AppTarget> allAppTargets, @ShareTargetType int shortcutType) {
        // A set of distinct scores for the matched shortcuts. We use index of a rank in the sorted
        // list instead of the actual rank value when converting a rank to a score.
        List<Integer> scoreList = new ArrayList<>();
        if (shortcutType == TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER) {
            for (int i = 0; i < matchingShortcuts.size(); i++) {
                int shortcutRank = matchingShortcuts.get(i).getShortcutInfo().getRank();
                if (!scoreList.contains(shortcutRank)) {
                    scoreList.add(shortcutRank);
                }
            }
            Collections.sort(scoreList);
        }

        List<ChooserTarget> chooserTargetList = new ArrayList<>(matchingShortcuts.size());
        for (int i = 0; i < matchingShortcuts.size(); i++) {
            ShortcutInfo shortcutInfo = matchingShortcuts.get(i).getShortcutInfo();
            int indexInAllShortcuts = allShortcuts.indexOf(matchingShortcuts.get(i));

            float score;
            if (shortcutType == TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE) {
                // Incoming results are ordered. Create a score based on index in the original list.
                score = Math.max(1.0f - (0.01f * indexInAllShortcuts), 0.0f);
            } else {
                // Create a score based on the rank of the shortcut.
                int rankIndex = scoreList.indexOf(shortcutInfo.getRank());
                score = Math.max(1.0f - (0.01f * rankIndex), 0.0f);
            }

            Bundle extras = new Bundle();
            extras.putString(Intent.EXTRA_SHORTCUT_ID, shortcutInfo.getId());
            ChooserTarget chooserTarget = new ChooserTarget(shortcutInfo.getShortLabel(),
                    null, // Icon will be loaded later if this target is selected to be shown.
                    score, matchingShortcuts.get(i).getTargetComponent().clone(), extras);

            chooserTargetList.add(chooserTarget);
            if (mDirectShareAppTargetCache != null && allAppTargets != null) {
                mDirectShareAppTargetCache.put(chooserTarget,
                        allAppTargets.get(indexInAllShortcuts));
            }
        }
        // Sort ChooserTargets by score in descending order
        Comparator<ChooserTarget> byScore =
                (ChooserTarget a, ChooserTarget b) -> -Float.compare(a.getScore(), b.getScore());
        Collections.sort(chooserTargetList, byScore);
        return chooserTargetList;
    }

    private String convertServiceName(String packageName, String serviceName) {
        if (TextUtils.isEmpty(serviceName)) {
            return null;
        }

        final String fullName;
        if (serviceName.startsWith(".")) {
            // Relative to the app package. Prepend the app package name.
            fullName = packageName + serviceName;
        } else if (serviceName.indexOf('.') >= 0) {
            // Fully qualified package name.
            fullName = serviceName;
        } else {
            fullName = null;
        }
        return fullName;
    }

    void unbindRemainingServices() {
        if (DEBUG) {
            Log.d(TAG, "unbindRemainingServices, " + mServiceConnections.size() + " left");
        }
        for (int i = 0, N = mServiceConnections.size(); i < N; i++) {
            final ChooserTargetServiceConnection conn = mServiceConnections.get(i);
            if (DEBUG) Log.d(TAG, "unbinding " + conn);
            unbindService(conn);
            conn.destroy();
        }
        mServicesRequested.clear();
        mServiceConnections.clear();
    }

    private void logDirectShareTargetReceived(int logCategory) {
        final long queryTime =
                logCategory == MetricsEvent.ACTION_DIRECT_SHARE_TARGETS_LOADED_SHORTCUT_MANAGER
                        ? mQueriedSharingShortcutsTimeMs : mQueriedTargetServicesTimeMs;
        final int apiLatency = (int) (System.currentTimeMillis() - queryTime);
        getMetricsLogger().write(new LogMaker(logCategory).setSubtype(apiLatency));
    }

    void updateModelAndChooserCounts(TargetInfo info) {
        if (info != null) {
            sendClickToAppPredictor(info);
            final ResolveInfo ri = info.getResolveInfo();
            Intent targetIntent = getTargetIntent();
            if (ri != null && ri.activityInfo != null && targetIntent != null) {
                ChooserListAdapter currentListAdapter =
                        mChooserMultiProfilePagerAdapter.getActiveListAdapter();
                if (currentListAdapter != null) {
                    currentListAdapter.updateModel(info.getResolvedComponentName());
                    currentListAdapter.updateChooserCounts(ri.activityInfo.packageName, getUserId(),
                            targetIntent.getAction());
                }
                if (DEBUG) {
                    Log.d(TAG, "ResolveInfo Package is " + ri.activityInfo.packageName);
                    Log.d(TAG, "Action to be updated is " + targetIntent.getAction());
                }
            } else if (DEBUG) {
                Log.d(TAG, "Can not log Chooser Counts of null ResovleInfo");
            }
        }
        mIsSuccessfullySelected = true;
    }

    private void sendClickToAppPredictor(TargetInfo targetInfo) {
        AppPredictor directShareAppPredictor = getAppPredictorForDirectShareIfEnabled();
        if (directShareAppPredictor == null) {
            return;
        }
        if (!(targetInfo instanceof ChooserTargetInfo)) {
            return;
        }
        ChooserTarget chooserTarget = ((ChooserTargetInfo) targetInfo).getChooserTarget();
        AppTarget appTarget = null;
        if (mDirectShareAppTargetCache != null) {
            appTarget = mDirectShareAppTargetCache.get(chooserTarget);
        }
        // This is a direct share click that was provided by the APS
        if (appTarget != null) {
            directShareAppPredictor.notifyAppTargetEvent(
                    new AppTargetEvent.Builder(appTarget, AppTargetEvent.ACTION_LAUNCH)
                        .setLaunchLocation(LAUNCH_LOCATON_DIRECT_SHARE)
                        .build());
        }
    }

    @Nullable
    private AppPredictor getAppPredictor() {
        if (!mIsAppPredictorComponentAvailable) {
            return null;
        }
        if (mAppPredictor == null) {
            final IntentFilter filter = getTargetIntentFilter();
            Bundle extras = new Bundle();
            extras.putParcelable(APP_PREDICTION_INTENT_FILTER_KEY, filter);
            AppPredictionContext appPredictionContext = new AppPredictionContext.Builder(this)
                .setUiSurface(APP_PREDICTION_SHARE_UI_SURFACE)
                .setPredictedTargetCount(APP_PREDICTION_SHARE_TARGET_QUERY_PACKAGE_LIMIT)
                .setExtras(extras)
                .build();
            AppPredictionManager appPredictionManager
                    = getSystemService(AppPredictionManager.class);
            mAppPredictor = appPredictionManager.createAppPredictionSession(appPredictionContext);
        }
        return mAppPredictor;
    }

    /**
     * This will return an app predictor if it is enabled for direct share sorting
     * and if one exists. Otherwise, it returns null.
     */
    @Nullable
    private AppPredictor getAppPredictorForDirectShareIfEnabled() {
        return ChooserFlags.USE_PREDICTION_MANAGER_FOR_DIRECT_TARGETS
                && !ActivityManager.isLowRamDeviceStatic() ? getAppPredictor() : null;
    }

    /**
     * This will return an app predictor if it is enabled for share activity sorting
     * and if one exists. Otherwise, it returns null.
     */
    @Nullable
    private AppPredictor getAppPredictorForShareActivitesIfEnabled() {
        return USE_PREDICTION_MANAGER_FOR_SHARE_ACTIVITIES ? getAppPredictor() : null;
    }

    void onRefinementResult(TargetInfo selectedTarget, Intent matchingIntent) {
        if (mRefinementResultReceiver != null) {
            mRefinementResultReceiver.destroy();
            mRefinementResultReceiver = null;
        }
        if (selectedTarget == null) {
            Log.e(TAG, "Refinement result intent did not match any known targets; canceling");
        } else if (!checkTargetSourceIntent(selectedTarget, matchingIntent)) {
            Log.e(TAG, "onRefinementResult: Selected target " + selectedTarget
                    + " cannot match refined source intent " + matchingIntent);
        } else {
            TargetInfo clonedTarget = selectedTarget.cloneFilledIn(matchingIntent, 0);
            if (super.onTargetSelected(clonedTarget, false)) {
                updateModelAndChooserCounts(clonedTarget);
                finish();
                return;
            }
        }
        onRefinementCanceled();
    }

    void onRefinementCanceled() {
        if (mRefinementResultReceiver != null) {
            mRefinementResultReceiver.destroy();
            mRefinementResultReceiver = null;
        }
        finish();
    }

    boolean checkTargetSourceIntent(TargetInfo target, Intent matchingIntent) {
        final List<Intent> targetIntents = target.getAllSourceIntents();
        for (int i = 0, N = targetIntents.size(); i < N; i++) {
            final Intent targetIntent = targetIntents.get(i);
            if (targetIntent.filterEquals(matchingIntent)) {
                return true;
            }
        }
        return false;
    }

    void filterServiceTargets(String packageName, List<ChooserTarget> targets) {
        if (targets == null) {
            return;
        }

        final PackageManager pm = getPackageManager();
        for (int i = targets.size() - 1; i >= 0; i--) {
            final ChooserTarget target = targets.get(i);
            final ComponentName targetName = target.getComponentName();
            if (packageName != null && packageName.equals(targetName.getPackageName())) {
                // Anything from the original target's package is fine.
                continue;
            }

            boolean remove;
            try {
                final ActivityInfo ai = pm.getActivityInfo(targetName, 0);
                remove = !ai.exported || ai.permission != null;
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Target " + target + " returned by " + packageName
                        + " component not found");
                remove = true;
            }

            if (remove) {
                targets.remove(i);
            }
        }
    }

    /**
     * Sort intents alphabetically based on display label.
     */
    static class AzInfoComparator implements Comparator<DisplayResolveInfo> {
        Collator mCollator;
        AzInfoComparator(Context context) {
            mCollator = Collator.getInstance(context.getResources().getConfiguration().locale);
        }

        @Override
        public int compare(
                DisplayResolveInfo lhsp, DisplayResolveInfo rhsp) {
            return mCollator.compare(lhsp.getDisplayLabel(), rhsp.getDisplayLabel());
        }
    }

    protected MetricsLogger getMetricsLogger() {
        if (mMetricsLogger == null) {
            mMetricsLogger = new MetricsLogger();
        }
        return mMetricsLogger;
    }

    public class ChooserListController extends ResolverListController {
        public ChooserListController(Context context,
                PackageManager pm,
                Intent targetIntent,
                String referrerPackageName,
                int launchedFromUid,
                UserHandle userId,
                AbstractResolverComparator resolverComparator) {
            super(context, pm, targetIntent, referrerPackageName, launchedFromUid, userId,
                    resolverComparator);
        }

        @Override
        boolean isComponentFiltered(ComponentName name) {
            if (mFilteredComponentNames == null) {
                return false;
            }
            for (ComponentName filteredComponentName : mFilteredComponentNames) {
                if (name.equals(filteredComponentName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isComponentPinned(ComponentName name) {
            return mPinnedSharedPrefs.getBoolean(name.flattenToString(), false);
        }
    }

    @VisibleForTesting
    public ChooserGridAdapter createChooserGridAdapter(Context context,
            List<Intent> payloadIntents, Intent[] initialIntents, List<ResolveInfo> rList,
            boolean filterLastUsed, boolean useLayoutForBrowsables, UserHandle userHandle) {
        return new ChooserGridAdapter(
                new ChooserListAdapter(context, payloadIntents, initialIntents, rList,
                        filterLastUsed, createListController(userHandle), useLayoutForBrowsables,
                        this, this));
    }

    @VisibleForTesting
    protected ResolverListController createListController(UserHandle userHandle) {
        AppPredictor appPredictor = getAppPredictorForShareActivitesIfEnabled();
        AbstractResolverComparator resolverComparator;
        if (appPredictor != null) {
            resolverComparator = new AppPredictionServiceResolverComparator(this, getTargetIntent(),
                    getReferrerPackageName(), appPredictor, getUser());
        } else {
            resolverComparator =
                    new ResolverRankerServiceResolverComparator(this, getTargetIntent(),
                        getReferrerPackageName(), null);
        }

        return new ChooserListController(
                this,
                mPm,
                getTargetIntent(),
                getReferrerPackageName(),
                mLaunchedFromUid,
                userHandle,
                resolverComparator);
    }

    @VisibleForTesting
    protected Bitmap loadThumbnail(Uri uri, Size size) {
        if (uri == null || size == null) {
            return null;
        }

        try {
            return getContentResolver().loadThumbnail(uri, size, null);
        } catch (IOException | NullPointerException | SecurityException ex) {
            logContentPreviewWarning(uri);
        }
        return null;
    }

    static final class PlaceHolderTargetInfo extends NotSelectableTargetInfo {
        public Drawable getDisplayIcon(Context context) {
            AnimatedVectorDrawable avd = (AnimatedVectorDrawable)
                    context.getDrawable(R.drawable.chooser_direct_share_icon_placeholder);
            avd.start(); // Start animation after generation
            return avd;
        }
    }

    static final class EmptyTargetInfo extends NotSelectableTargetInfo {
        public Drawable getDisplayIcon(Context context) {
            return null;
        }
    }

    private void handleScroll(View view, int x, int y, int oldx, int oldy) {
        if (mChooserMultiProfilePagerAdapter.getCurrentRootAdapter() != null) {
            mChooserMultiProfilePagerAdapter.getCurrentRootAdapter().handleScroll(view, y, oldy);
        }
    }

    /*
     * Need to dynamically adjust how many icons can fit per row before we add them,
     * which also means setting the correct offset to initially show the content
     * preview area + 2 rows of targets
     */
    private void handleLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        if (mChooserMultiProfilePagerAdapter == null) {
            return;
        }
        RecyclerView recyclerView = mChooserMultiProfilePagerAdapter.getCurrentAdapterView();
        ChooserGridAdapter gridAdapter = mChooserMultiProfilePagerAdapter.getCurrentRootAdapter();
        if (gridAdapter == null || recyclerView == null) {
            return;
        }

        final int availableWidth = right - left - v.getPaddingLeft() - v.getPaddingRight();
        if (gridAdapter.consumeLayoutRequest()
                || gridAdapter.calculateChooserTargetWidth(availableWidth)
                || recyclerView.getAdapter() == null
                || availableWidth != mCurrAvailableWidth) {
            mCurrAvailableWidth = availableWidth;
            recyclerView.setAdapter(gridAdapter);
            ((GridLayoutManager) recyclerView.getLayoutManager())
                    .setSpanCount(gridAdapter.getMaxTargetsPerRow());

            getMainThreadHandler().post(() -> {
                if (mResolverDrawerLayout == null || gridAdapter == null) {
                    return;
                }

                final int bottomInset = mSystemWindowInsets != null
                                            ? mSystemWindowInsets.bottom : 0;
                int offset = bottomInset;
                int rowsToShow = gridAdapter.getProfileRowCount()
                        + gridAdapter.getServiceTargetRowCount()
                        + gridAdapter.getCallerAndRankedTargetRowCount();

                // then this is most likely not a SEND_* action, so check
                // the app target count
                if (rowsToShow == 0) {
                    rowsToShow = gridAdapter.getRowCount();
                }

                // still zero? then use a default height and leave, which
                // can happen when there are no targets to show
                if (rowsToShow == 0 && !shouldShowContentPreview()) {
                    offset += getResources().getDimensionPixelSize(
                            R.dimen.chooser_max_collapsed_height);
                    mResolverDrawerLayout.setCollapsibleHeightReserved(offset);
                    return;
                }

                if (shouldShowContentPreview()) {
                    offset += findViewById(R.id.content_preview_container).getHeight();
                }

                int directShareHeight = 0;
                rowsToShow = Math.min(4, rowsToShow);
                for (int i = 0, childCount = recyclerView.getChildCount();
                        i < childCount && rowsToShow > 0; i++) {
                    View child = recyclerView.getChildAt(i);
                    if (((GridLayoutManager.LayoutParams)
                            child.getLayoutParams()).getSpanIndex() != 0) {
                        continue;
                    }
                    int height = child.getHeight();
                    offset += height;

                    if (gridAdapter.getTargetType(
                            recyclerView.getChildAdapterPosition(child))
                            == ChooserListAdapter.TARGET_SERVICE) {
                        directShareHeight = height;
                    }
                    rowsToShow--;
                }

                boolean isExpandable = getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_PORTRAIT && !isInMultiWindowMode();
                if (directShareHeight != 0 && isSendAction(getTargetIntent())
                        && isExpandable) {
                    // make sure to leave room for direct share 4->8 expansion
                    int requiredExpansionHeight =
                            (int) (directShareHeight / DIRECT_SHARE_EXPANSION_RATE);
                    int topInset = mSystemWindowInsets != null ? mSystemWindowInsets.top : 0;
                    int minHeight = bottom - top - mResolverDrawerLayout.getAlwaysShowHeight()
                                        - requiredExpansionHeight - topInset - bottomInset;

                    offset = Math.min(offset, minHeight);
                }

                mResolverDrawerLayout.setCollapsibleHeightReserved(Math.min(offset, bottom - top));
            });
        }
    }

    static class BaseChooserTargetComparator implements Comparator<ChooserTarget> {
        @Override
        public int compare(ChooserTarget lhs, ChooserTarget rhs) {
            // Descending order
            return (int) Math.signum(rhs.getScore() - lhs.getScore());
        }
    }

    @Override // ResolverListCommunicator
    public void onHandlePackagesChanged() {
        mServicesRequested.clear();
        mChooserMultiProfilePagerAdapter.getActiveListAdapter().notifyDataSetChanged();
        super.onHandlePackagesChanged();
    }

    @Override // SelectableTargetInfoCommunicator
    public ActivityInfoPresentationGetter makePresentationGetter(ActivityInfo info) {
        return mChooserMultiProfilePagerAdapter.getActiveListAdapter().makePresentationGetter(info);
    }

    @Override // SelectableTargetInfoCommunicator
    public Intent getReferrerFillInIntent() {
        return mReferrerFillInIntent;
    }

    @Override // ChooserListCommunicator
    public int getMaxRankedTargets() {
        return mChooserMultiProfilePagerAdapter.getCurrentRootAdapter() == null
                ? ChooserGridAdapter.MAX_TARGETS_PER_ROW_PORTRAIT
                : mChooserMultiProfilePagerAdapter.getCurrentRootAdapter().getMaxTargetsPerRow();
    }

    @Override // ChooserListCommunicator
    public void sendListViewUpdateMessage() {
        mChooserHandler.sendEmptyMessageDelayed(ChooserHandler.LIST_VIEW_UPDATE_MESSAGE,
                LIST_VIEW_UPDATE_INTERVAL_IN_MILLIS);
    }

    @Override
    public void onListRebuilt(ResolverListAdapter listAdapter) {
        ChooserListAdapter chooserListAdapter = (ChooserListAdapter) listAdapter;
        if (chooserListAdapter.mDisplayList == null
                || chooserListAdapter.mDisplayList.isEmpty()) {
            chooserListAdapter.notifyDataSetChanged();
        } else {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... voids) {
                    chooserListAdapter.updateAlphabeticalList();
                    return null;
                }
                @Override
                protected void onPostExecute(Void aVoid) {
                    chooserListAdapter.notifyDataSetChanged();
                }
            }.execute();
        }

        // don't support direct share on low ram devices
        if (ActivityManager.isLowRamDeviceStatic()) {
            return;
        }

        if (ChooserFlags.USE_SHORTCUT_MANAGER_FOR_DIRECT_TARGETS
                || ChooserFlags.USE_PREDICTION_MANAGER_FOR_DIRECT_TARGETS) {
            if (DEBUG) {
                Log.d(TAG, "querying direct share targets from ShortcutManager");
            }

            queryDirectShareTargets(chooserListAdapter, false);
        }
        if (USE_CHOOSER_TARGET_SERVICE_FOR_DIRECT_TARGETS) {
            if (DEBUG) {
                Log.d(TAG, "List built querying services");
            }

            queryTargetServices(chooserListAdapter);
        }
    }

    @Override // ChooserListCommunicator
    public boolean isSendAction(Intent targetIntent) {
        if (targetIntent == null) {
            return false;
        }

        String action = targetIntent.getAction();
        if (action == null) {
            return false;
        }

        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return true;
        }

        return false;
    }

    private boolean shouldShowContentPreview() {
        return mMultiProfilePagerAdapter.getActiveListAdapter().getCount() > 0
                && isSendAction(getTargetIntent());
    }

    private void updateContentPreview() {
        if (shouldShowContentPreview()) {
            showContentPreview();
        } else {
            hideContentPreview();
        }
    }

    private void showContentPreview() {
        ViewGroup contentPreviewContainer = findViewById(R.id.content_preview_container);
        contentPreviewContainer.setVisibility(View.VISIBLE);
        ViewGroup contentPreviewView = createContentPreviewView(contentPreviewContainer);
        contentPreviewContainer.addView(contentPreviewView);
        logActionShareWithPreview();
    }

    private void hideContentPreview() {
        ViewGroup contentPreviewContainer = findViewById(R.id.content_preview_container);
        contentPreviewContainer.removeAllViews();
        contentPreviewContainer.setVisibility(View.GONE);
    }

    private void logActionShareWithPreview() {
        Intent targetIntent = getTargetIntent();
        int previewType = findPreferredContentPreview(targetIntent, getContentResolver());
        getMetricsLogger().write(new LogMaker(MetricsEvent.ACTION_SHARE_WITH_PREVIEW)
                .setSubtype(previewType));
    }

    /**
     * Used to bind types of individual item including
     * {@link ChooserGridAdapter#VIEW_TYPE_NORMAL},
     * {@link ChooserGridAdapter#VIEW_TYPE_PROFILE},
     * and {@link ChooserGridAdapter#VIEW_TYPE_AZ_LABEL}.
     */
    final class ItemViewHolder extends RecyclerView.ViewHolder {
        ResolverListAdapter.ViewHolder mWrappedViewHolder;
        int mListPosition = ChooserListAdapter.NO_POSITION;

        ItemViewHolder(View itemView, boolean isClickable) {
            super(itemView);
            mWrappedViewHolder = new ResolverListAdapter.ViewHolder(itemView);
            if (isClickable) {
                itemView.setOnClickListener(v -> startSelected(mListPosition,
                        false/* always */, true/* filterd */));
                itemView.setOnLongClickListener(v -> {
                    showTargetDetails(
                            mChooserMultiProfilePagerAdapter.getActiveListAdapter()
                                    .targetInfoForPosition(mListPosition, /* filtered */ true));
                    return true;
                });
            }
        }
    }

    /**
     * Add a footer to the list, to support scrolling behavior below the navbar.
     */
    final class FooterViewHolder extends RecyclerView.ViewHolder {
        FooterViewHolder(View itemView) {
            super(itemView);
        }

        public void setHeight(int height) {
            itemView.setLayoutParams(
                    new RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, height));
        }
    }

    /**
     * Intentionally override the {@link ResolverActivity} implementation as we only need that
     * implementation for the intent resolver case.
     */
    @Override
    public void onButtonClick(View v) {}

    /**
     * Intentionally override the {@link ResolverActivity} implementation as we only need that
     * implementation for the intent resolver case.
     */
    @Override
    protected void resetButtonBar() {}

    /**
     * Adapter for all types of items and targets in ShareSheet.
     * Note that ranked sections like Direct Share - while appearing grid-like - are handled on the
     * row level by this adapter but not on the item level. Individual targets within the row are
     * handled by {@link ChooserListAdapter}
     */
    final class ChooserGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private ChooserListAdapter mChooserListAdapter;
        private final LayoutInflater mLayoutInflater;

        private DirectShareViewHolder mDirectShareViewHolder;
        private int mChooserTargetWidth = 0;
        private boolean mShowAzLabelIfPoss;

        private boolean mLayoutRequested = false;

        private FooterViewHolder mFooterViewHolder;

        private static final int VIEW_TYPE_DIRECT_SHARE = 0;
        private static final int VIEW_TYPE_NORMAL = 1;
        private static final int VIEW_TYPE_PROFILE = 2;
        private static final int VIEW_TYPE_AZ_LABEL = 3;
        private static final int VIEW_TYPE_CALLER_AND_RANK = 4;
        private static final int VIEW_TYPE_FOOTER = 5;

        private static final int MAX_TARGETS_PER_ROW_PORTRAIT = 4;
        private static final int MAX_TARGETS_PER_ROW_LANDSCAPE = 8;

        private static final int NUM_EXPANSIONS_TO_HIDE_AZ_LABEL = 20;

        ChooserGridAdapter(ChooserListAdapter wrappedAdapter) {
            super();
            mChooserListAdapter = wrappedAdapter;
            mLayoutInflater = LayoutInflater.from(ChooserActivity.this);

            mFooterViewHolder = new FooterViewHolder(
                    new Space(ChooserActivity.this.getApplicationContext()));

            mShowAzLabelIfPoss = getNumSheetExpansions() < NUM_EXPANSIONS_TO_HIDE_AZ_LABEL;

            wrappedAdapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    super.onChanged();
                    notifyDataSetChanged();
                }

                @Override
                public void onInvalidated() {
                    super.onInvalidated();
                    notifyDataSetChanged();
                }
            });
        }

        public void setFooterHeight(int height) {
            mFooterViewHolder.setHeight(height);
        }

        /**
         * Calculate the chooser target width to maximize space per item
         *
         * @param width The new row width to use for recalculation
         * @return true if the view width has changed
         */
        public boolean calculateChooserTargetWidth(int width) {
            if (width == 0) {
                return false;
            }

            int newWidth = width / getMaxTargetsPerRow();
            if (newWidth != mChooserTargetWidth) {
                mChooserTargetWidth = newWidth;
                return true;
            }

            return false;
        }

        int getMaxTargetsPerRow() {
            int maxTargets = MAX_TARGETS_PER_ROW_PORTRAIT;
            if (shouldDisplayLandscape(getResources().getConfiguration().orientation)) {
                maxTargets = MAX_TARGETS_PER_ROW_LANDSCAPE;
            }
            return maxTargets;
        }

        public boolean consumeLayoutRequest() {
            boolean oldValue = mLayoutRequested;
            mLayoutRequested = false;
            return oldValue;
        }

        public int getRowCount() {
            return (int) (
                    getProfileRowCount()
                            + getServiceTargetRowCount()
                            + getCallerAndRankedTargetRowCount()
                            + getAzLabelRowCount()
                            + Math.ceil(
                            (float) mChooserListAdapter.getAlphaTargetCount()
                                    / getMaxTargetsPerRow())
            );
        }

        public int getProfileRowCount() {
            return mChooserListAdapter.getOtherProfile() == null ? 0 : 1;
        }

        public int getFooterRowCount() {
            return 1;
        }

        public int getCallerAndRankedTargetRowCount() {
            return (int) Math.ceil(
                    ((float) mChooserListAdapter.getCallerTargetCount()
                            + mChooserListAdapter.getRankedTargetCount()) / getMaxTargetsPerRow());
        }

        // There can be at most one row in the listview, that is internally
        // a ViewGroup with 2 rows
        public int getServiceTargetRowCount() {
            if (isSendAction(getTargetIntent())
                    && !ActivityManager.isLowRamDeviceStatic()) {
                return 1;
            }
            return 0;
        }

        public int getAzLabelRowCount() {
            // Only show a label if the a-z list is showing
            return (mShowAzLabelIfPoss && mChooserListAdapter.getAlphaTargetCount() > 0) ? 1 : 0;
        }

        @Override
        public int getItemCount() {
            return (int) (
                    getProfileRowCount()
                            + getServiceTargetRowCount()
                            + getCallerAndRankedTargetRowCount()
                            + getAzLabelRowCount()
                            + mChooserListAdapter.getAlphaTargetCount()
                            + getFooterRowCount()
            );
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            switch (viewType) {
                case VIEW_TYPE_PROFILE:
                    return new ItemViewHolder(createProfileView(parent), false);
                case VIEW_TYPE_AZ_LABEL:
                    return new ItemViewHolder(createAzLabelView(parent), false);
                case VIEW_TYPE_NORMAL:
                    return new ItemViewHolder(mChooserListAdapter.createView(parent), true);
                case VIEW_TYPE_DIRECT_SHARE:
                case VIEW_TYPE_CALLER_AND_RANK:
                    return createItemGroupViewHolder(viewType, parent);
                case VIEW_TYPE_FOOTER:
                    return mFooterViewHolder;
                default:
                    // Since we catch all possible viewTypes above, no chance this is being called.
                    return null;
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int viewType = getItemViewType(position);
            switch (viewType) {
                case VIEW_TYPE_DIRECT_SHARE:
                case VIEW_TYPE_CALLER_AND_RANK:
                    bindItemGroupViewHolder(position, (ItemGroupViewHolder) holder);
                    break;
                case VIEW_TYPE_NORMAL:
                    bindItemViewHolder(position, (ItemViewHolder) holder);
                    break;
                default:
            }
        }

        @Override
        public int getItemViewType(int position) {
            int count;

            int countSum = (count = getProfileRowCount());
            if (count > 0 && position < countSum) return VIEW_TYPE_PROFILE;

            countSum += (count = getServiceTargetRowCount());
            if (count > 0 && position < countSum) return VIEW_TYPE_DIRECT_SHARE;

            countSum += (count = getCallerAndRankedTargetRowCount());
            if (count > 0 && position < countSum) return VIEW_TYPE_CALLER_AND_RANK;

            countSum += (count = getAzLabelRowCount());
            if (count > 0 && position < countSum) return VIEW_TYPE_AZ_LABEL;

            if (position == getItemCount() - 1) return VIEW_TYPE_FOOTER;

            return VIEW_TYPE_NORMAL;
        }

        public int getTargetType(int position) {
            return mChooserListAdapter.getPositionTargetType(getListPosition(position));
        }

        private View createProfileView(ViewGroup parent) {
            View profileRow = mLayoutInflater.inflate(R.layout.chooser_profile_row, parent, false);
            profileRow.setBackground(
                    getResources().getDrawable(R.drawable.chooser_row_layer_list, null));
            mProfileView = profileRow.findViewById(R.id.profile_button);
            mProfileView.setOnClickListener(ChooserActivity.this::onProfileClick);
            updateProfileViewButton();
            return profileRow;
        }

        private View createAzLabelView(ViewGroup parent) {
            return mLayoutInflater.inflate(R.layout.chooser_az_label_row, parent, false);
        }

        private ItemGroupViewHolder loadViewsIntoGroup(ItemGroupViewHolder holder) {
            final int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            final int exactSpec = MeasureSpec.makeMeasureSpec(mChooserTargetWidth,
                    MeasureSpec.EXACTLY);
            int columnCount = holder.getColumnCount();

            final boolean isDirectShare = holder instanceof DirectShareViewHolder;

            for (int i = 0; i < columnCount; i++) {
                final View v = mChooserListAdapter.createView(holder.getRowByIndex(i));
                final int column = i;
                v.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startSelected(holder.getItemIndex(column), false, true);
                    }
                });
                v.setOnLongClickListener(new OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        showTargetDetails(
                                mChooserListAdapter.targetInfoForPosition(
                                        holder.getItemIndex(column), true));
                        return true;
                    }
                });
                holder.addView(i, v);

                // Force Direct Share to be 2 lines and auto-wrap to second line via hoz scroll =
                // false. TextView#setHorizontallyScrolling must be reset after #setLines. Must be
                // done before measuring.
                if (isDirectShare) {
                    final ViewHolder vh = (ViewHolder) v.getTag();
                    vh.text.setLines(2);
                    vh.text.setHorizontallyScrolling(false);
                    vh.text2.setVisibility(View.GONE);
                }

                // Force height to be a given so we don't have visual disruption during scaling.
                v.measure(exactSpec, spec);
                setViewBounds(v, v.getMeasuredWidth(), v.getMeasuredHeight());
            }

            final ViewGroup viewGroup = holder.getViewGroup();

            // Pre-measure and fix height so we can scale later.
            holder.measure();
            setViewBounds(viewGroup, LayoutParams.MATCH_PARENT, holder.getMeasuredRowHeight());

            if (isDirectShare) {
                DirectShareViewHolder dsvh = (DirectShareViewHolder) holder;
                setViewBounds(dsvh.getRow(0), LayoutParams.MATCH_PARENT, dsvh.getMinRowHeight());
                setViewBounds(dsvh.getRow(1), LayoutParams.MATCH_PARENT, dsvh.getMinRowHeight());
            }

            viewGroup.setTag(holder);

            return holder;
        }

        private void setViewBounds(View view, int widthPx, int heightPx) {
            LayoutParams lp = view.getLayoutParams();
            if (lp == null) {
                lp = new LayoutParams(widthPx, heightPx);
                view.setLayoutParams(lp);
            } else {
                lp.height = heightPx;
                lp.width = widthPx;
            }
        }

        ItemGroupViewHolder createItemGroupViewHolder(int viewType, ViewGroup parent) {
            if (viewType == VIEW_TYPE_DIRECT_SHARE) {
                ViewGroup parentGroup = (ViewGroup) mLayoutInflater.inflate(
                        R.layout.chooser_row_direct_share, parent, false);
                ViewGroup row1 = (ViewGroup) mLayoutInflater.inflate(R.layout.chooser_row,
                        parentGroup, false);
                ViewGroup row2 = (ViewGroup) mLayoutInflater.inflate(R.layout.chooser_row,
                        parentGroup, false);
                parentGroup.addView(row1);
                parentGroup.addView(row2);

                mDirectShareViewHolder = new DirectShareViewHolder(parentGroup,
                        Lists.newArrayList(row1, row2), getMaxTargetsPerRow());
                loadViewsIntoGroup(mDirectShareViewHolder);

                return mDirectShareViewHolder;
            } else {
                ViewGroup row = (ViewGroup) mLayoutInflater.inflate(R.layout.chooser_row, parent,
                        false);
                ItemGroupViewHolder holder = new SingleRowViewHolder(row, getMaxTargetsPerRow());
                loadViewsIntoGroup(holder);

                return holder;
            }
        }

        /**
         * Need to merge CALLER + ranked STANDARD into a single row and prevent a separator from
         * showing on top of the AZ list if the AZ label is visible. All other types are placed into
         * their own row as determined by their target type, and dividers are added in the list to
         * separate each type.
         */
        int getRowType(int rowPosition) {
            // Merge caller and ranked standard into a single row
            int positionType = mChooserListAdapter.getPositionTargetType(rowPosition);
            if (positionType == ChooserListAdapter.TARGET_CALLER) {
                return ChooserListAdapter.TARGET_STANDARD;
            }

            // If an the A-Z label is shown, prevent a separator from appearing by making the A-Z
            // row type the same as the suggestion row type
            if (getAzLabelRowCount() > 0 && positionType == ChooserListAdapter.TARGET_STANDARD_AZ) {
                return ChooserListAdapter.TARGET_STANDARD;
            }

            return positionType;
        }

        void bindItemViewHolder(int position, ItemViewHolder holder) {
            View v = holder.itemView;
            int listPosition = getListPosition(position);
            holder.mListPosition = listPosition;
            mChooserListAdapter.bindView(listPosition, v);
        }

        void bindItemGroupViewHolder(int position, ItemGroupViewHolder holder) {
            final ViewGroup viewGroup = (ViewGroup) holder.itemView;
            int start = getListPosition(position);
            int startType = getRowType(start);
            if (viewGroup.getForeground() == null) {
                viewGroup.setForeground(
                        getResources().getDrawable(R.drawable.chooser_row_layer_list, null));
            }

            int columnCount = holder.getColumnCount();
            int end = start + columnCount - 1;
            while (getRowType(end) != startType && end >= start) {
                end--;
            }

            if (end == start && mChooserListAdapter.getItem(start) instanceof EmptyTargetInfo) {
                final TextView textView = viewGroup.findViewById(R.id.chooser_row_text_option);

                if (textView.getVisibility() != View.VISIBLE) {
                    textView.setAlpha(0.0f);
                    textView.setVisibility(View.VISIBLE);
                    textView.setText(R.string.chooser_no_direct_share_targets);

                    ValueAnimator fadeAnim = ObjectAnimator.ofFloat(textView, "alpha", 0.0f, 1.0f);
                    fadeAnim.setInterpolator(new DecelerateInterpolator(1.0f));

                    float translationInPx = getResources().getDimensionPixelSize(
                            R.dimen.chooser_row_text_option_translate);
                    textView.setTranslationY(translationInPx);
                    ValueAnimator translateAnim = ObjectAnimator.ofFloat(textView, "translationY",
                            0.0f);
                    translateAnim.setInterpolator(new DecelerateInterpolator(1.0f));

                    AnimatorSet animSet = new AnimatorSet();
                    animSet.setDuration(NO_DIRECT_SHARE_ANIM_IN_MILLIS);
                    animSet.setStartDelay(NO_DIRECT_SHARE_ANIM_IN_MILLIS);
                    animSet.playTogether(fadeAnim, translateAnim);
                    animSet.start();
                }
            }

            for (int i = 0; i < columnCount; i++) {
                final View v = holder.getView(i);

                if (start + i <= end) {
                    holder.setViewVisibility(i, View.VISIBLE);
                    holder.setItemIndex(i, start + i);
                    mChooserListAdapter.bindView(holder.getItemIndex(i), v);
                } else {
                    holder.setViewVisibility(i, View.INVISIBLE);
                }
            }
        }

        int getListPosition(int position) {
            position -= getProfileRowCount();

            final int serviceCount = mChooserListAdapter.getServiceTargetCount();
            final int serviceRows = (int) Math.ceil((float) serviceCount
                    / ChooserListAdapter.MAX_SERVICE_TARGETS);
            if (position < serviceRows) {
                return position * getMaxTargetsPerRow();
            }

            position -= serviceRows;

            final int callerAndRankedCount = mChooserListAdapter.getCallerTargetCount()
                                                 + mChooserListAdapter.getRankedTargetCount();
            final int callerAndRankedRows = getCallerAndRankedTargetRowCount();
            if (position < callerAndRankedRows) {
                return serviceCount + position * getMaxTargetsPerRow();
            }

            position -= getAzLabelRowCount() + callerAndRankedRows;

            return callerAndRankedCount + serviceCount + position;
        }

        public void handleScroll(View v, int y, int oldy) {
            // Only expand direct share area if there is a minimum number of shortcuts,
            // which will help reduce the amount of visible shuffling due to older-style
            // direct share targets.
            int orientation = getResources().getConfiguration().orientation;
            boolean canExpandDirectShare =
                    mChooserListAdapter.getNumShortcutResults() > getMaxTargetsPerRow()
                    && orientation == Configuration.ORIENTATION_PORTRAIT
                    && !isInMultiWindowMode();

            if (mDirectShareViewHolder != null && canExpandDirectShare) {
                mDirectShareViewHolder.handleScroll(
                        mChooserMultiProfilePagerAdapter.getCurrentAdapterView(), y, oldy,
                        getMaxTargetsPerRow());
            }
        }

        public ChooserListAdapter getListAdapter() {
            return mChooserListAdapter;
        }

        boolean shouldCellSpan(int position) {
            return getItemViewType(position) == VIEW_TYPE_NORMAL;
        }
    }

    /**
     * Used to bind types for group of items including:
     * {@link ChooserGridAdapter#VIEW_TYPE_DIRECT_SHARE},
     * and {@link ChooserGridAdapter#VIEW_TYPE_CALLER_AND_RANK}.
     */
    abstract class ItemGroupViewHolder extends RecyclerView.ViewHolder {
        protected int mMeasuredRowHeight;
        private int[] mItemIndices;
        protected final View[] mCells;
        private final int mColumnCount;

        ItemGroupViewHolder(int cellCount, View itemView) {
            super(itemView);
            this.mCells = new View[cellCount];
            this.mItemIndices = new int[cellCount];
            this.mColumnCount = cellCount;
        }

        abstract ViewGroup addView(int index, View v);

        abstract ViewGroup getViewGroup();

        abstract ViewGroup getRowByIndex(int index);

        abstract ViewGroup getRow(int rowNumber);

        abstract void setViewVisibility(int i, int visibility);

        public int getColumnCount() {
            return mColumnCount;
        }

        public void measure() {
            final int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            getViewGroup().measure(spec, spec);
            mMeasuredRowHeight = getViewGroup().getMeasuredHeight();
        }

        public int getMeasuredRowHeight() {
            return mMeasuredRowHeight;
        }

        public void setItemIndex(int itemIndex, int listIndex) {
            mItemIndices[itemIndex] = listIndex;
        }

        public int getItemIndex(int itemIndex) {
            return mItemIndices[itemIndex];
        }

        public View getView(int index) {
            return mCells[index];
        }
    }

    class SingleRowViewHolder extends ItemGroupViewHolder {
        private final ViewGroup mRow;

        SingleRowViewHolder(ViewGroup row, int cellCount) {
            super(cellCount, row);

            this.mRow = row;
        }

        public ViewGroup getViewGroup() {
            return mRow;
        }

        public ViewGroup getRowByIndex(int index) {
            return mRow;
        }

        public ViewGroup getRow(int rowNumber) {
            if (rowNumber == 0) return mRow;
            return null;
        }

        public ViewGroup addView(int index, View v) {
            mRow.addView(v);
            mCells[index] = v;

            return mRow;
        }

        public void setViewVisibility(int i, int visibility) {
            getView(i).setVisibility(visibility);
        }
    }

    class DirectShareViewHolder extends ItemGroupViewHolder {
        private final ViewGroup mParent;
        private final List<ViewGroup> mRows;
        private int mCellCountPerRow;

        private boolean mHideDirectShareExpansion = false;
        private int mDirectShareMinHeight = 0;
        private int mDirectShareCurrHeight = 0;
        private int mDirectShareMaxHeight = 0;

        private final boolean[] mCellVisibility;

        DirectShareViewHolder(ViewGroup parent, List<ViewGroup> rows, int cellCountPerRow) {
            super(rows.size() * cellCountPerRow, parent);

            this.mParent = parent;
            this.mRows = rows;
            this.mCellCountPerRow = cellCountPerRow;
            this.mCellVisibility = new boolean[rows.size() * cellCountPerRow];
        }

        public ViewGroup addView(int index, View v) {
            ViewGroup row = getRowByIndex(index);
            row.addView(v);
            mCells[index] = v;

            return row;
        }

        public ViewGroup getViewGroup() {
            return mParent;
        }

        public ViewGroup getRowByIndex(int index) {
            return mRows.get(index / mCellCountPerRow);
        }

        public ViewGroup getRow(int rowNumber) {
            return mRows.get(rowNumber);
        }

        public void measure() {
            final int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            getRow(0).measure(spec, spec);
            getRow(1).measure(spec, spec);

            mDirectShareMinHeight = getRow(0).getMeasuredHeight();
            mDirectShareCurrHeight = mDirectShareCurrHeight > 0
                    ? mDirectShareCurrHeight : mDirectShareMinHeight;
            mDirectShareMaxHeight = 2 * mDirectShareMinHeight;
        }

        public int getMeasuredRowHeight() {
            return mDirectShareCurrHeight;
        }

        public int getMinRowHeight() {
            return mDirectShareMinHeight;
        }

        public void setViewVisibility(int i, int visibility) {
            final View v = getView(i);
            if (visibility == View.VISIBLE) {
                mCellVisibility[i] = true;
                v.setVisibility(visibility);
                v.setAlpha(1.0f);
            } else if (visibility == View.INVISIBLE && mCellVisibility[i]) {
                mCellVisibility[i] = false;

                ValueAnimator fadeAnim = ObjectAnimator.ofFloat(v, "alpha", 1.0f, 0f);
                fadeAnim.setDuration(NO_DIRECT_SHARE_ANIM_IN_MILLIS);
                fadeAnim.setInterpolator(new AccelerateInterpolator(1.0f));
                fadeAnim.addListener(new AnimatorListenerAdapter() {
                    public void onAnimationEnd(Animator animation) {
                        v.setVisibility(View.INVISIBLE);
                    }
                });
                fadeAnim.start();
            }
        }

        public void handleScroll(RecyclerView view, int y, int oldy, int maxTargetsPerRow) {
            // only exit early if fully collapsed, otherwise onListRebuilt() with shifting
            // targets can lock us into an expanded mode
            boolean notExpanded = mDirectShareCurrHeight == mDirectShareMinHeight;
            if (notExpanded) {
                if (mHideDirectShareExpansion) {
                    return;
                }

                // only expand if we have more than maxTargetsPerRow, and delay that decision
                // until they start to scroll
                if (mChooserMultiProfilePagerAdapter.getActiveListAdapter()
                        .getSelectableServiceTargetCount() <= maxTargetsPerRow) {
                    mHideDirectShareExpansion = true;
                    return;
                }
            }

            int yDiff = (int) ((oldy - y) * DIRECT_SHARE_EXPANSION_RATE);

            int prevHeight = mDirectShareCurrHeight;
            int newHeight = Math.min(prevHeight + yDiff, mDirectShareMaxHeight);
            newHeight = Math.max(newHeight, mDirectShareMinHeight);
            yDiff = newHeight - prevHeight;

            if (view == null || view.getChildCount() == 0 || yDiff == 0) {
                return;
            }

            // locate the item to expand, and offset the rows below that one
            boolean foundExpansion = false;
            for (int i = 0; i < view.getChildCount(); i++) {
                View child = view.getChildAt(i);

                if (foundExpansion) {
                    child.offsetTopAndBottom(yDiff);
                } else {
                    if (child.getTag() != null && child.getTag() instanceof DirectShareViewHolder) {
                        int widthSpec = MeasureSpec.makeMeasureSpec(child.getWidth(),
                                MeasureSpec.EXACTLY);
                        int heightSpec = MeasureSpec.makeMeasureSpec(newHeight,
                                MeasureSpec.EXACTLY);
                        child.measure(widthSpec, heightSpec);
                        child.getLayoutParams().height = child.getMeasuredHeight();
                        child.layout(child.getLeft(), child.getTop(), child.getRight(),
                                child.getTop() + child.getMeasuredHeight());

                        foundExpansion = true;
                    }
                }
            }

            if (foundExpansion) {
                mDirectShareCurrHeight = newHeight;
            }
        }
    }

    static class ChooserTargetServiceConnection implements ServiceConnection {
        private DisplayResolveInfo mOriginalTarget;
        private ComponentName mConnectedComponent;
        private ChooserActivity mChooserActivity;
        private final Object mLock = new Object();

        private final IChooserTargetResult mChooserTargetResult = new IChooserTargetResult.Stub() {
            @Override
            public void sendResult(List<ChooserTarget> targets) throws RemoteException {
                synchronized (mLock) {
                    if (mChooserActivity == null) {
                        Log.e(TAG, "destroyed ChooserTargetServiceConnection received result from "
                                + mConnectedComponent + "; ignoring...");
                        return;
                    }
                    mChooserActivity.filterServiceTargets(
                            mOriginalTarget.getResolveInfo().activityInfo.packageName, targets);
                    final Message msg = Message.obtain();
                    msg.what = ChooserHandler.CHOOSER_TARGET_SERVICE_RESULT;
                    msg.obj = new ServiceResultInfo(mOriginalTarget, targets,
                            ChooserTargetServiceConnection.this);
                    mChooserActivity.mChooserHandler.sendMessage(msg);
                }
            }
        };

        public ChooserTargetServiceConnection(ChooserActivity chooserActivity,
                DisplayResolveInfo dri) {
            mChooserActivity = chooserActivity;
            mOriginalTarget = dri;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Log.d(TAG, "onServiceConnected: " + name);
            synchronized (mLock) {
                if (mChooserActivity == null) {
                    Log.e(TAG, "destroyed ChooserTargetServiceConnection got onServiceConnected");
                    return;
                }

                final IChooserTargetService icts = IChooserTargetService.Stub.asInterface(service);
                try {
                    icts.getChooserTargets(mOriginalTarget.getResolvedComponentName(),
                            mOriginalTarget.getResolveInfo().filter, mChooserTargetResult);
                } catch (RemoteException e) {
                    Log.e(TAG, "Querying ChooserTargetService " + name + " failed.", e);
                    mChooserActivity.unbindService(this);
                    mChooserActivity.mServiceConnections.remove(this);
                    destroy();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Log.d(TAG, "onServiceDisconnected: " + name);
            synchronized (mLock) {
                if (mChooserActivity == null) {
                    Log.e(TAG,
                            "destroyed ChooserTargetServiceConnection got onServiceDisconnected");
                    return;
                }

                mChooserActivity.unbindService(this);
                mChooserActivity.mServiceConnections.remove(this);
                if (mChooserActivity.mServiceConnections.isEmpty()) {
                    mChooserActivity.sendVoiceChoicesIfNeeded();
                }
                mConnectedComponent = null;
                destroy();
            }
        }

        public void destroy() {
            synchronized (mLock) {
                mChooserActivity = null;
                mOriginalTarget = null;
            }
        }

        @Override
        public String toString() {
            return "ChooserTargetServiceConnection{service="
                    + mConnectedComponent + ", activity="
                    + (mOriginalTarget != null
                    ? mOriginalTarget.getResolveInfo().activityInfo.toString()
                    : "<connection destroyed>") + "}";
        }
    }

    static class ServiceResultInfo {
        public final DisplayResolveInfo originalTarget;
        public final List<ChooserTarget> resultTargets;
        public final ChooserTargetServiceConnection connection;

        public ServiceResultInfo(DisplayResolveInfo ot, List<ChooserTarget> rt,
                ChooserTargetServiceConnection c) {
            originalTarget = ot;
            resultTargets = rt;
            connection = c;
        }
    }

    static class RefinementResultReceiver extends ResultReceiver {
        private ChooserActivity mChooserActivity;
        private TargetInfo mSelectedTarget;

        public RefinementResultReceiver(ChooserActivity host, TargetInfo target,
                Handler handler) {
            super(handler);
            mChooserActivity = host;
            mSelectedTarget = target;
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (mChooserActivity == null) {
                Log.e(TAG, "Destroyed RefinementResultReceiver received a result");
                return;
            }
            if (resultData == null) {
                Log.e(TAG, "RefinementResultReceiver received null resultData");
                return;
            }

            switch (resultCode) {
                case RESULT_CANCELED:
                    mChooserActivity.onRefinementCanceled();
                    break;
                case RESULT_OK:
                    Parcelable intentParcelable = resultData.getParcelable(Intent.EXTRA_INTENT);
                    if (intentParcelable instanceof Intent) {
                        mChooserActivity.onRefinementResult(mSelectedTarget,
                                (Intent) intentParcelable);
                    } else {
                        Log.e(TAG, "RefinementResultReceiver received RESULT_OK but no Intent"
                                + " in resultData with key Intent.EXTRA_INTENT");
                    }
                    break;
                default:
                    Log.w(TAG, "Unknown result code " + resultCode
                            + " sent to RefinementResultReceiver");
                    break;
            }
        }

        public void destroy() {
            mChooserActivity = null;
            mSelectedTarget = null;
        }
    }

    /**
     * Used internally to round image corners while obeying view padding.
     */
    public static class RoundedRectImageView extends ImageView {
        private int mRadius = 0;
        private Path mPath = new Path();
        private Paint mOverlayPaint = new Paint(0);
        private Paint mRoundRectPaint = new Paint(0);
        private Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String mExtraImageCount = null;

        public RoundedRectImageView(Context context) {
            super(context);
        }

        public RoundedRectImageView(Context context, AttributeSet attrs) {
            this(context, attrs, 0);
        }

        public RoundedRectImageView(Context context, AttributeSet attrs, int defStyleAttr) {
            this(context, attrs, defStyleAttr, 0);
        }

        public RoundedRectImageView(Context context, AttributeSet attrs, int defStyleAttr,
                int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
            mRadius = context.getResources().getDimensionPixelSize(R.dimen.chooser_corner_radius);

            mOverlayPaint.setColor(0x99000000);
            mOverlayPaint.setStyle(Paint.Style.FILL);

            mRoundRectPaint.setColor(context.getResources().getColor(R.color.chooser_row_divider));
            mRoundRectPaint.setStyle(Paint.Style.STROKE);
            mRoundRectPaint.setStrokeWidth(context.getResources()
                    .getDimensionPixelSize(R.dimen.chooser_preview_image_border));

            mTextPaint.setColor(Color.WHITE);
            mTextPaint.setTextSize(context.getResources()
                    .getDimensionPixelSize(R.dimen.chooser_preview_image_font_size));
            mTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        private void updatePath(int width, int height) {
            mPath.reset();

            int imageWidth = width - getPaddingRight() - getPaddingLeft();
            int imageHeight = height - getPaddingBottom() - getPaddingTop();
            mPath.addRoundRect(getPaddingLeft(), getPaddingTop(), imageWidth, imageHeight, mRadius,
                    mRadius, Path.Direction.CW);
        }

        /**
          * Sets the corner radius on all corners
          *
          * param radius 0 for no radius, &gt; 0 for a visible corner radius
          */
        public void setRadius(int radius) {
            mRadius = radius;
            updatePath(getWidth(), getHeight());
        }

        /**
          * Display an overlay with extra image count on 3rd image
          */
        public void setExtraImageCount(int count) {
            if (count > 0) {
                this.mExtraImageCount = "+" + count;
            } else {
                this.mExtraImageCount = null;
            }
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            updatePath(width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mRadius != 0) {
                canvas.clipPath(mPath);
            }

            super.onDraw(canvas);

            int x = getPaddingLeft();
            int y = getPaddingRight();
            int width = getWidth() - getPaddingRight() - getPaddingLeft();
            int height = getHeight() - getPaddingBottom() - getPaddingTop();
            if (mExtraImageCount != null) {
                canvas.drawRect(x, y, width, height, mOverlayPaint);

                int xPos = canvas.getWidth() / 2;
                int yPos = (int) ((canvas.getHeight() / 2.0f)
                        - ((mTextPaint.descent() + mTextPaint.ascent()) / 2.0f));

                canvas.drawText(mExtraImageCount, xPos, yPos, mTextPaint);
            }

            canvas.drawRoundRect(x, y, width, height, mRadius, mRadius, mRoundRectPaint);
        }
    }
}
