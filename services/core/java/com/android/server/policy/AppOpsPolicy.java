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

package com.android.server.policy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.AppOpsManagerInternal;
import android.app.SyncNotedAppOp;
import android.app.role.RoleManager;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.location.LocationManagerInternal;
import android.net.Uri;
import android.os.IBinder;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.function.HeptFunction;
import com.android.internal.util.function.HexFunction;
import com.android.internal.util.function.OctFunction;
import com.android.internal.util.function.QuadFunction;
import com.android.internal.util.function.TriFunction;
import com.android.server.LocalServices;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class defines policy for special behaviors around app ops.
 */
public final class AppOpsPolicy implements AppOpsManagerInternal.CheckOpsDelegate {
    private static final String LOG_TAG = AppOpsPolicy.class.getName();

    private static final String ACTIVITY_RECOGNITION_TAGS =
            "android:activity_recognition_allow_listed_tags";
    private static final String ACTIVITY_RECOGNITION_TAGS_SEPARATOR = ";";

    @NonNull
    private final Object mLock = new Object();

    @NonNull
    private final Context mContext;

    @NonNull
    private final RoleManager mRoleManager;

    /**
     * The locking policy around the location tags is a bit special. Since we want to
     * avoid grabbing the lock on every op note we are taking the approach where the
     * read and write are being done via a thread-safe data structure such that the
     * lookup/insert are single thread-safe calls. When we update the cached state we
     * use a lock to ensure the update's lookup and store calls are done atomically,
     * so multiple writers would not interleave. The tradeoff is we make is that the
     * concurrent data structure would use boxing/unboxing of integers but this is
     * preferred to locking.
     */
    @GuardedBy("mLock - writes only - see above")
    @NonNull
    private final ConcurrentHashMap<Integer, ArrayMap<String, ArraySet<String>>> mLocationTags =
            new ConcurrentHashMap<>();

    @GuardedBy("mLock - writes only - see above")
    @NonNull
    private final ConcurrentHashMap<Integer, ArrayMap<String, ArraySet<String>>>
            mActivityRecognitionTags = new ConcurrentHashMap<>();

    public AppOpsPolicy(@NonNull Context context) {
        mContext = context;
        mRoleManager = mContext.getSystemService(RoleManager.class);

        final LocationManagerInternal locationManagerInternal = LocalServices.getService(
                LocationManagerInternal.class);
        locationManagerInternal.setOnProviderLocationTagsChangeListener((providerTagInfo) -> {
            synchronized (mLock) {
                updateAllowListedTagsForPackageLocked(providerTagInfo.getUid(),
                        providerTagInfo.getPackageName(), providerTagInfo.getTags(),
                        mLocationTags);
            }
        });

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addDataScheme("package");

        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final Uri uri = intent.getData();
                if (uri == null) {
                    return;
                }
                final String packageName = uri.getSchemeSpecificPart();
                if (TextUtils.isEmpty(packageName)) {
                    return;
                }
                final List<String> activityRecognizers = mRoleManager.getRoleHolders(
                        RoleManager.ROLE_SYSTEM_ACTIVITY_RECOGNIZER);
                if (activityRecognizers.contains(packageName)) {
                    updateActivityRecognizerTags(packageName);
                }
            }
        }, UserHandle.SYSTEM, intentFilter, null, null);

        mRoleManager.addOnRoleHoldersChangedListenerAsUser(context.getMainExecutor(),
                (String roleName, UserHandle user) -> {
            if (RoleManager.ROLE_SYSTEM_ACTIVITY_RECOGNIZER.equals(roleName)) {
                initializeActivityRecognizersTags();
            }
        }, UserHandle.SYSTEM);

        initializeActivityRecognizersTags();
    }

    @Override
    public int checkOperation(int code, int uid, String packageName, boolean raw,
            QuadFunction<Integer, Integer, String, Boolean, Integer> superImpl) {
        return superImpl.apply(code, uid, packageName, raw);
    }

    @Override
    public int checkAudioOperation(int code, int usage, int uid, String packageName,
            QuadFunction<Integer, Integer, Integer, String, Integer> superImpl) {
        return superImpl.apply(code, usage, uid, packageName);
    }

    @Override
    public SyncNotedAppOp noteOperation(int code, int uid, @Nullable String packageName,
            @Nullable String attributionTag, boolean shouldCollectAsyncNotedOp, @Nullable
            String message, boolean shouldCollectMessage, @NonNull HeptFunction<Integer, Integer,
                    String, String, Boolean, String, Boolean, SyncNotedAppOp> superImpl) {
        return superImpl.apply(resolveDatasourceOp(code, uid, packageName, attributionTag), uid,
                packageName, attributionTag, shouldCollectAsyncNotedOp,
                message, shouldCollectMessage);
    }

    @Override
    public SyncNotedAppOp noteProxyOperation(int code, @NonNull AttributionSource attributionSource,
            boolean shouldCollectAsyncNotedOp, @Nullable String message,
            boolean shouldCollectMessage, boolean skipProxyOperation, @NonNull HexFunction<Integer,
                    AttributionSource, Boolean, String, Boolean, Boolean,
            SyncNotedAppOp> superImpl) {
        return superImpl.apply(resolveDatasourceOp(code, attributionSource.getUid(),
                attributionSource.getPackageName(), attributionSource.getAttributionTag()),
                attributionSource, shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                skipProxyOperation);
    }

    @Override
    public SyncNotedAppOp startProxyOperation(IBinder token, int code,
            @NonNull AttributionSource attributionSource, boolean startIfModeDefault,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation, @NonNull OctFunction<IBinder, Integer, AttributionSource,
                    Boolean, Boolean, String, Boolean, Boolean, SyncNotedAppOp> superImpl) {
        return superImpl.apply(token, resolveDatasourceOp(code, attributionSource.getUid(),
                attributionSource.getPackageName(), attributionSource.getAttributionTag()),
                attributionSource, startIfModeDefault, shouldCollectAsyncNotedOp, message,
                shouldCollectMessage, skipProxyOperation);
    }

    @Override
    public void finishProxyOperation(IBinder clientId, int code,
            @NonNull AttributionSource attributionSource,
            @NonNull TriFunction<IBinder, Integer, AttributionSource, Void> superImpl) {
        superImpl.apply(clientId, resolveDatasourceOp(code, attributionSource.getUid(),
                attributionSource.getPackageName(), attributionSource.getAttributionTag()),
                attributionSource);
    }

    private int resolveDatasourceOp(int code, int uid, @NonNull String packageName,
            @Nullable String attributionTag) {
        if (attributionTag == null) {
            return code;
        }
        int resolvedCode = resolveLocationOp(code);
        if (resolvedCode != code) {
            if (isDatasourceAttributionTag(uid, packageName, attributionTag,
                    mLocationTags)) {
                return resolvedCode;
            }
        } else {
            resolvedCode = resolveArOp(code);
            if (resolvedCode != code) {
                if (isDatasourceAttributionTag(uid, packageName, attributionTag,
                        mActivityRecognitionTags)) {
                    return resolvedCode;
                }
            }
        }
        return code;
    }

    private void initializeActivityRecognizersTags() {
        final List<String> activityRecognizers = mRoleManager.getRoleHolders(
                RoleManager.ROLE_SYSTEM_ACTIVITY_RECOGNIZER);
        final int recognizerCount = activityRecognizers.size();
        if (recognizerCount > 0) {
            for (int i = 0; i < recognizerCount; i++) {
                final String activityRecognizer = activityRecognizers.get(i);
                updateActivityRecognizerTags(activityRecognizer);
            }
        } else {
            clearActivityRecognitionTags();
        }
    }

    private void clearActivityRecognitionTags() {
        synchronized (mLock) {
            mActivityRecognitionTags.clear();
        }
    }

    private void updateActivityRecognizerTags(@NonNull String activityRecognizer) {
        final int flags = PackageManager.GET_SERVICES
                | PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

        final Intent intent = new Intent(Intent.ACTION_ACTIVITY_RECOGNIZER);
        intent.setPackage(activityRecognizer);
        final ResolveInfo resolvedService = mContext.getPackageManager()
                .resolveServiceAsUser(intent, flags, UserHandle.USER_SYSTEM);
        if (resolvedService == null || resolvedService.serviceInfo == null) {
            Log.w(LOG_TAG, "Service recognizer doesn't handle "
                    + Intent.ACTION_ACTIVITY_RECOGNIZER +  ", ignoring!");
            return;
        }
        final String tagsList = resolvedService.serviceInfo.metaData.getString(
                ACTIVITY_RECOGNITION_TAGS);
        if (tagsList != null) {
            final String[] tags = tagsList.split(ACTIVITY_RECOGNITION_TAGS_SEPARATOR);
            synchronized (mLock) {
                updateAllowListedTagsForPackageLocked(
                        resolvedService.serviceInfo.applicationInfo.uid,
                        resolvedService.serviceInfo.packageName, new ArraySet<>(tags),
                        mActivityRecognitionTags);
            }
        }
    }

    private static void updateAllowListedTagsForPackageLocked(int uid, String packageName,
            Set<String> allowListedTags, ConcurrentHashMap<Integer, ArrayMap<String,
            ArraySet<String>>> datastore) {
        // We make a copy of the per UID state to limit our mutation to one
        // operation in the underlying concurrent data structure.
        ArrayMap<String, ArraySet<String>> uidTags = datastore.get(uid);
        if (uidTags != null) {
            uidTags = new ArrayMap<>(uidTags);
        }

        ArraySet<String> packageTags = (uidTags != null) ? uidTags.get(packageName) : null;
        if (packageTags != null) {
            packageTags = new ArraySet<>(packageTags);
        }

        if (allowListedTags != null && !allowListedTags.isEmpty()) {
            if (packageTags != null) {
                packageTags.clear();
                packageTags.addAll(allowListedTags);
            } else {
                packageTags = new ArraySet<>(allowListedTags);
            }
            if (uidTags == null) {
                uidTags = new ArrayMap<>();
            }
            uidTags.put(packageName, packageTags);
            datastore.put(uid, uidTags);
        } else if (uidTags != null) {
            uidTags.remove(packageName);
            if (!uidTags.isEmpty()) {
                datastore.put(uid, uidTags);
            } else {
                datastore.remove(uid);
            }
        }
    }

    private static boolean isDatasourceAttributionTag(int uid, @NonNull String packageName,
            @NonNull String attributionTag, @NonNull Map<Integer, ArrayMap<String,
            ArraySet<String>>> mappedOps) {
        // Only a single lookup from the underlying concurrent data structure
        final ArrayMap<String, ArraySet<String>> uidTags = mappedOps.get(uid);
        if (uidTags != null) {
            final ArraySet<String> packageTags = uidTags.get(packageName);
            if (packageTags != null && packageTags.contains(attributionTag)) {
                return true;
            }
        }
        return false;
    }

    private static int resolveLocationOp(int code) {
        switch (code) {
            case AppOpsManager.OP_FINE_LOCATION:
                return AppOpsManager.OP_FINE_LOCATION_SOURCE;
            case AppOpsManager.OP_COARSE_LOCATION:
                return AppOpsManager.OP_COARSE_LOCATION_SOURCE;
        }
        return code;
    }

    private static int resolveArOp(int code) {
        if (code == AppOpsManager.OP_ACTIVITY_RECOGNITION) {
            return AppOpsManager.OP_ACTIVITY_RECOGNITION_SOURCE;
        }
        return code;
    }
}
