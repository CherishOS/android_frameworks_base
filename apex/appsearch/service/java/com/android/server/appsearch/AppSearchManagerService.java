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
package com.android.server.appsearch;

import static android.app.appsearch.AppSearchResult.throwableToFailedResult;
import static android.os.Process.INVALID_UID;
import static android.os.UserHandle.USER_NULL;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchMigrationHelper;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.IAppSearchBatchResultCallback;
import android.app.appsearch.IAppSearchManager;
import android.app.appsearch.IAppSearchResultCallback;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.SearchResultPage;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.StorageInfo;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.stats.CallStats;
import com.android.server.appsearch.stats.LoggerInstanceManager;
import com.android.server.appsearch.stats.PlatformLogger;

import com.google.android.icing.proto.PersistType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** TODO(b/142567528): add comments when implement this class */
public class AppSearchManagerService extends SystemService {
    private static final String TAG = "AppSearchManagerService";
    private final Context mContext;
    private PackageManagerInternal mPackageManagerInternal;
    private ImplInstanceManager mImplInstanceManager;
    private UserManager mUserManager;
    private LoggerInstanceManager mLoggerInstanceManager;

    // Never call shutdownNow(). It will cancel the futures it's returned. And since
    // Executor#execute won't return anything, we will hang forever waiting for the execution.
    // AppSearch multi-thread execution is guarded by Read & Write Lock in AppSearchImpl, all
    // mutate requests will need to gain write lock and query requests need to gain read lock.
    private static final Executor EXECUTOR = new ThreadPoolExecutor(/*corePoolSize=*/1,
            Runtime.getRuntime().availableProcessors(), /*keepAliveTime*/ 60L, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>());

    // Cache of unlocked user ids so we don't have to query UserManager service each time. The
    // "locked" suffix refers to the fact that access to the field should be locked; unrelated to
    // the unlocked status of user ids.
    @GuardedBy("mUnlockedUserIdsLocked")
    private final Set<Integer> mUnlockedUserIdsLocked = new ArraySet<>();

    public AppSearchManagerService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.APP_SEARCH_SERVICE, new Stub());
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mImplInstanceManager = ImplInstanceManager.getInstance(mContext);
        mUserManager = mContext.getSystemService(UserManager.class);
        mLoggerInstanceManager = LoggerInstanceManager.getInstance();
        registerReceivers();
    }

    private void registerReceivers() {
        mContext.registerReceiverAsUser(new UserActionReceiver(), UserHandle.ALL,
                new IntentFilter(Intent.ACTION_USER_REMOVED), /*broadcastPermission=*/ null,
                /*scheduler=*/ null);

        IntentFilter packageChangedFilter = new IntentFilter();
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        packageChangedFilter.addDataScheme("package");
        mContext.registerReceiverAsUser(new PackageChangedReceiver(), UserHandle.ALL,
                packageChangedFilter, /*broadcastPermission=*/ null,
                /*scheduler=*/ null);
    }

    private class UserActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_USER_REMOVED:
                    int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_NULL);
                    if (userId == USER_NULL) {
                        Log.e(TAG, "userId is missing in the intent: " + intent);
                        return;
                    }
                    handleUserRemoved(userId);
                    break;
                default:
                    Log.e(TAG, "Received unknown intent: " + intent);
            }
        }
    }

    /**
     * Handles user removed action.
     *
     * <p>Only need to clear the AppSearchImpl instance. The data of AppSearch is saved in the
     * "credential encrypted" system directory of each user. That directory will be auto-deleted
     * when a user is removed.
     *
     * @param userId The multi-user userId of the user that need to be removed.
     *
     * @see android.os.Environment#getDataSystemCeDirectory
     */
    private void handleUserRemoved(@UserIdInt int userId) {
        try {
            mImplInstanceManager.removeAppSearchImplForUser(userId);
            mLoggerInstanceManager.removePlatformLoggerForUser(userId);
            Log.i(TAG, "Removed AppSearchImpl instance for user: " + userId);
        } catch (Throwable t) {
            Log.e(TAG, "Unable to remove data for user: " + userId, t);
        }
    }

    private class PackageChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                case Intent.ACTION_PACKAGE_DATA_CLEARED:
                    String packageName = intent.getData().getSchemeSpecificPart();
                    if (packageName == null) {
                        Log.e(TAG, "Package name is missing in the intent: " + intent);
                        return;
                    }
                    int uid = intent.getIntExtra(Intent.EXTRA_UID, INVALID_UID);
                    if (uid == INVALID_UID) {
                        Log.e(TAG, "uid is missing in the intent: " + intent);
                        return;
                    }
                    handlePackageRemoved(packageName, uid);
                    break;
                default:
                    Log.e(TAG, "Received unknown intent: " + intent);
            }
        }
    }

    private void handlePackageRemoved(String packageName, int uid) {
        int userId = UserHandle.getUserId(uid);
        try {
            AppSearchImpl impl = mImplInstanceManager.getOrCreateAppSearchImpl(mContext, userId);
            //TODO(b/145759910) clear visibility setting for package.
            impl.clearPackageData(packageName);
        } catch (AppSearchException e) {
            Log.e(TAG, "Unable to remove data for package: " + packageName, e);
        }
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        synchronized (mUnlockedUserIdsLocked) {
            mUnlockedUserIdsLocked.add(user.getUserIdentifier());
        }
    }

    private class Stub extends IAppSearchManager.Stub {
        @Override
        public void setSchema(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull List<Bundle> schemaBundles,
                @NonNull List<String> schemasNotDisplayedBySystem,
                @NonNull Map<String, List<Bundle>> schemasPackageAccessibleBundles,
                boolean forceOverride,
                int schemaVersion,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Preconditions.checkNotNull(packageName);
            Preconditions.checkNotNull(databaseName);
            Preconditions.checkNotNull(schemaBundles);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    List<AppSearchSchema> schemas = new ArrayList<>(schemaBundles.size());
                    for (int i = 0; i < schemaBundles.size(); i++) {
                        schemas.add(new AppSearchSchema(schemaBundles.get(i)));
                    }
                    Map<String, List<PackageIdentifier>> schemasPackageAccessible =
                            new ArrayMap<>(schemasPackageAccessibleBundles.size());
                    for (Map.Entry<String, List<Bundle>> entry :
                            schemasPackageAccessibleBundles.entrySet()) {
                        List<PackageIdentifier> packageIdentifiers =
                                new ArrayList<>(entry.getValue().size());
                        for (int i = 0; i < entry.getValue().size(); i++) {
                            packageIdentifiers.add(
                                    new PackageIdentifier(entry.getValue().get(i)));
                        }
                        schemasPackageAccessible.put(entry.getKey(), packageIdentifiers);
                    }
                    AppSearchImpl impl = mImplInstanceManager.getAppSearchImpl(callingUserId);
                    SetSchemaResponse setSchemaResponse = impl.setSchema(
                            packageName,
                            databaseName,
                            schemas,
                            schemasNotDisplayedBySystem,
                            schemasPackageAccessible,
                            forceOverride,
                            schemaVersion);
                    invokeCallbackOnResult(callback,
                            AppSearchResult.newSuccessfulResult(setSchemaResponse.getBundle()));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void getSchema(
                @NonNull String packageName,
                @NonNull String databaseName,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Preconditions.checkNotNull(packageName);
            Preconditions.checkNotNull(databaseName);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    GetSchemaResponse response = impl.getSchema(packageName, databaseName);
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResult.newSuccessfulResult(response.getBundle()));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void getNamespaces(
                @NonNull String packageName,
                @NonNull String databaseName,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Preconditions.checkNotNull(packageName);
            Preconditions.checkNotNull(databaseName);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    List<String> namespaces = impl.getNamespaces(packageName, databaseName);
                    invokeCallbackOnResult(callback,
                            AppSearchResult.newSuccessfulResult(namespaces));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void putDocuments(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull List<Bundle> documentBundles,
                @UserIdInt int userId,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchBatchResultCallback callback) {
            Preconditions.checkNotNull(packageName);
            Preconditions.checkNotNull(databaseName);
            Preconditions.checkNotNull(documentBundles);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                PlatformLogger logger = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchBatchResult.Builder<String, Void> resultBuilder =
                            new AppSearchBatchResult.Builder<>();
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    logger = mLoggerInstanceManager.getPlatformLogger(callingUserId);
                    for (int i = 0; i < documentBundles.size(); i++) {
                        GenericDocument document = new GenericDocument(documentBundles.get(i));
                        try {
                            impl.putDocument(packageName, databaseName, document, logger);
                            resultBuilder.setSuccess(document.getUri(), /*result=*/ null);
                            ++operationSuccessCount;
                        } catch (Throwable t) {
                            resultBuilder.setResult(document.getUri(),
                                    throwableToFailedResult(t));
                            AppSearchResult<Void> result = throwableToFailedResult(t);
                            resultBuilder.setResult(document.getUri(), result);
                            // for failures, we would just log the one for last failure
                            statusCode = result.getResultCode();
                            ++operationFailureCount;
                        }
                    }
                    // Now that the batch has been written. Persist the newly written data.
                    impl.persistToDisk(PersistType.Code.LITE);
                    invokeCallbackOnResult(callback, resultBuilder.build());
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (logger != null) {
                        CallStats.Builder cBuilder = new CallStats.Builder(packageName,
                                databaseName)
                                .setCallType(CallStats.CALL_TYPE_PUT_DOCUMENTS)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(
                                        2 * (int) (totalLatencyStartTimeMillis
                                                - binderCallStartTimeMillis))
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount);
                        cBuilder.getGeneralStatsBuilder()
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(
                                        (int) (SystemClock.elapsedRealtime()
                                                - totalLatencyStartTimeMillis));
                        logger.logStats(cBuilder.build());
                    }
                }
            });
        }

        @Override
        public void getDocuments(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull String namespace,
                @NonNull List<String> uris,
                @NonNull Map<String, List<String>> typePropertyPaths,
                @UserIdInt int userId,
                @NonNull IAppSearchBatchResultCallback callback) {
            Preconditions.checkNotNull(packageName);
            Preconditions.checkNotNull(databaseName);
            Preconditions.checkNotNull(namespace);
            Preconditions.checkNotNull(uris);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchBatchResult.Builder<String, Bundle> resultBuilder =
                            new AppSearchBatchResult.Builder<>();
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    for (int i = 0; i < uris.size(); i++) {
                        String uri = uris.get(i);
                        try {
                            GenericDocument document =
                                    impl.getDocument(
                                            packageName,
                                            databaseName,
                                            namespace,
                                            uri,
                                            typePropertyPaths);
                            resultBuilder.setSuccess(uri, document.getBundle());
                        } catch (Throwable t) {
                            resultBuilder.setResult(uri, throwableToFailedResult(t));
                        }
                    }
                    invokeCallbackOnResult(callback, resultBuilder.build());
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void query(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull String queryExpression,
                @NonNull Bundle searchSpecBundle,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Preconditions.checkNotNull(packageName);
            Preconditions.checkNotNull(databaseName);
            Preconditions.checkNotNull(queryExpression);
            Preconditions.checkNotNull(searchSpecBundle);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    SearchResultPage searchResultPage =
                            impl.query(
                                    packageName,
                                    databaseName,
                                    queryExpression,
                                    new SearchSpec(searchSpecBundle));
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResult.newSuccessfulResult(searchResultPage.getBundle()));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void globalQuery(
                @NonNull String packageName,
                @NonNull String queryExpression,
                @NonNull Bundle searchSpecBundle,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Preconditions.checkNotNull(packageName);
            Preconditions.checkNotNull(queryExpression);
            Preconditions.checkNotNull(searchSpecBundle);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    SearchResultPage searchResultPage =
                            impl.globalQuery(
                                    queryExpression,
                                    new SearchSpec(searchSpecBundle),
                                    packageName,
                                    callingUid);
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResult.newSuccessfulResult(searchResultPage.getBundle()));
                }  catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void getNextPage(
                long nextPageToken,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            // TODO(b/162450968) check nextPageToken is being advanced by the same uid as originally
            // opened it
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    SearchResultPage searchResultPage = impl.getNextPage(nextPageToken);
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResult.newSuccessfulResult(searchResultPage.getBundle()));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void invalidateNextPageToken(long nextPageToken, @UserIdInt int userId) {
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    impl.invalidateNextPageToken(nextPageToken);
                } catch (Throwable t) {
                    Log.e(TAG, "Unable to invalidate the query page token", t);
                }
            });
        }

        @Override
        public void writeQueryResultsToFile(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull ParcelFileDescriptor fileDescriptor,
                @NonNull String queryExpression,
                @NonNull Bundle searchSpecBundle,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    // we don't need to append the file. The file is always brand new.
                    try (DataOutputStream outputStream = new DataOutputStream(
                            new FileOutputStream(fileDescriptor.getFileDescriptor()))) {
                        SearchResultPage searchResultPage = impl.query(
                                packageName,
                                databaseName,
                                queryExpression,
                                new SearchSpec(searchSpecBundle));
                        while (!searchResultPage.getResults().isEmpty()) {
                            for (int i = 0; i < searchResultPage.getResults().size(); i++) {
                                AppSearchMigrationHelper.writeBundleToOutputStream(
                                        outputStream, searchResultPage.getResults().get(i)
                                                .getGenericDocument().getBundle());
                            }
                            searchResultPage = impl.getNextPage(
                                    searchResultPage.getNextPageToken());
                        }
                    }
                    invokeCallbackOnResult(callback, AppSearchResult.newSuccessfulResult(null));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void putDocumentsFromFile(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull ParcelFileDescriptor fileDescriptor,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);

                    GenericDocument document;
                    ArrayList<Bundle> migrationFailureBundles = new ArrayList<>();
                    try (DataInputStream inputStream = new DataInputStream(
                            new FileInputStream(fileDescriptor.getFileDescriptor()))) {
                        while (true) {
                            try {
                                document = AppSearchMigrationHelper
                                        .readDocumentFromInputStream(inputStream);
                            } catch (EOFException e) {
                                // nothing wrong, we just finish the reading.
                                break;
                            }
                            try {
                                impl.putDocument(packageName, databaseName, document,
                                        /*logger=*/ null);
                            } catch (Throwable t) {
                                migrationFailureBundles.add(
                                        new SetSchemaResponse.MigrationFailure.Builder()
                                                .setNamespace(document.getNamespace())
                                                .setSchemaType(document.getSchemaType())
                                                .setUri(document.getUri())
                                                .setAppSearchResult(AppSearchResult
                                                        .throwableToFailedResult(t))
                                                .build().getBundle());
                            }
                        }
                    }
                    impl.persistToDisk(PersistType.Code.FULL);
                    invokeCallbackOnResult(callback,
                            AppSearchResult.newSuccessfulResult(migrationFailureBundles));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void reportUsage(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull String namespace,
                @NonNull String uri,
                long usageTimeMillis,
                boolean systemUsage,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(namespace);
            Objects.requireNonNull(uri);
            Objects.requireNonNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);

                    if (systemUsage) {
                        // TODO(b/183031844): Validate that the call comes from the system
                    }

                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    impl.reportUsage(
                            packageName, databaseName, namespace, uri,
                            usageTimeMillis, systemUsage);
                    invokeCallbackOnResult(
                            callback, AppSearchResult.newSuccessfulResult(/*result=*/ null));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void removeByUri(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull String namespace,
                @NonNull List<String> uris,
                @UserIdInt int userId,
                @NonNull IAppSearchBatchResultCallback callback) {
            Preconditions.checkNotNull(packageName);
            Preconditions.checkNotNull(databaseName);
            Preconditions.checkNotNull(uris);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchBatchResult.Builder<String, Void> resultBuilder =
                            new AppSearchBatchResult.Builder<>();
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    for (int i = 0; i < uris.size(); i++) {
                        String uri = uris.get(i);
                        try {
                            impl.remove(packageName, databaseName, namespace, uri);
                            resultBuilder.setSuccess(uri, /*result= */ null);
                        } catch (Throwable t) {
                            resultBuilder.setResult(uri, throwableToFailedResult(t));
                        }
                    }
                    // Now that the batch has been written. Persist the newly written data.
                    impl.persistToDisk(PersistType.Code.LITE);
                    invokeCallbackOnResult(callback, resultBuilder.build());
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void removeByQuery(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull String queryExpression,
                @NonNull Bundle searchSpecBundle,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Preconditions.checkNotNull(packageName);
            Preconditions.checkNotNull(databaseName);
            Preconditions.checkNotNull(queryExpression);
            Preconditions.checkNotNull(searchSpecBundle);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    impl.removeByQuery(
                            packageName,
                            databaseName,
                            queryExpression,
                            new SearchSpec(searchSpecBundle));
                    // Now that the batch has been written. Persist the newly written data.
                    impl.persistToDisk(PersistType.Code.LITE);
                    invokeCallbackOnResult(callback, AppSearchResult.newSuccessfulResult(null));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void getStorageInfo(
                @NonNull String packageName,
                @NonNull String databaseName,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Preconditions.checkNotNull(packageName);
            Preconditions.checkNotNull(databaseName);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    StorageInfo storageInfo = impl.getStorageInfoForDatabase(packageName,
                            databaseName);
                    Bundle storageInfoBundle = storageInfo.getBundle();
                    invokeCallbackOnResult(
                            callback, AppSearchResult.newSuccessfulResult(storageInfoBundle));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void persistToDisk(@UserIdInt int userId) {
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    impl.persistToDisk(PersistType.Code.FULL);
                } catch (Throwable t) {
                    Log.e(TAG, "Unable to persist the data to disk", t);
                }
            });
        }

        @Override
        public void initialize(@UserIdInt int userId, @NonNull IAppSearchResultCallback callback) {
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    mImplInstanceManager.getOrCreateAppSearchImpl(mContext, callingUserId);
                    mLoggerInstanceManager.getOrCreatePlatformLogger(getContext(), callingUserId);
                    invokeCallbackOnResult(callback, AppSearchResult.newSuccessfulResult(null));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        private void verifyUserUnlocked(int callingUserId) {
            synchronized (mUnlockedUserIdsLocked) {
                // First, check the local copy.
                if (mUnlockedUserIdsLocked.contains(callingUserId)) {
                    return;
                }
                // If the local copy says the user is locked, check with UM for the actual state,
                // since the user might just have been unlocked.
                if (!mUserManager.isUserUnlockingOrUnlocked(UserHandle.of(callingUserId))) {
                    throw new IllegalStateException(
                            "User " + callingUserId + " is locked or not running.");
                }
            }
        }

        private void verifyCallingPackage(int callingUid, @NonNull String callingPackage) {
            Preconditions.checkNotNull(callingPackage);
            if (mPackageManagerInternal.getPackageUid(
                            callingPackage, /*flags=*/ 0, UserHandle.getUserId(callingUid))
                    != callingUid) {
                throw new SecurityException(
                        "Specified calling package ["
                                + callingPackage
                                + "] does not match the calling uid "
                                + callingUid);
            }
        }

        /** Invokes the {@link IAppSearchResultCallback} with the result. */
        private void invokeCallbackOnResult(
                IAppSearchResultCallback callback, AppSearchResult<?> result) {
            try {
                callback.onResult(result);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to send result to the callback", e);
            }
        }

        /** Invokes the {@link IAppSearchBatchResultCallback} with the result. */
        private void invokeCallbackOnResult(
                IAppSearchBatchResultCallback callback, AppSearchBatchResult<?, ?> result) {
            try {
                callback.onResult(result);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to send result to the callback", e);
            }
        }

        /**
         * Invokes the {@link IAppSearchResultCallback} with an throwable.
         *
         * <p>The throwable is convert to a {@link AppSearchResult};
         */
        private void invokeCallbackOnError(IAppSearchResultCallback callback, Throwable throwable) {
            try {
                callback.onResult(throwableToFailedResult(throwable));
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to send result to the callback", e);
            }
        }

        /**
         * Invokes the {@link IAppSearchBatchResultCallback} with an unexpected internal throwable.
         *
         * <p>The throwable is converted to {@link ParcelableException}.
         */
        private void invokeCallbackOnError(
                IAppSearchBatchResultCallback callback, Throwable throwable) {
            try {
                //TODO(b/175067650) verify ParcelableException could propagate throwable correctly.
                callback.onSystemError(new ParcelableException(throwable));
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to send error to the callback", e);
            }
        }
    }

    // TODO(b/173553485) verifying that the caller has permission to access target user's data
    // TODO(b/173553485) Handle ACTION_USER_REMOVED broadcast
    // TODO(b/173553485) Implement SystemService.onUserStopping()
    private static int handleIncomingUser(@UserIdInt int userId, int callingUid) {
        int callingPid = Binder.getCallingPid();
        return ActivityManager.handleIncomingUser(
                callingPid,
                callingUid,
                userId,
                /*allowAll=*/ false,
                /*requireFull=*/ false,
                /*name=*/ null,
                /*callerPackage=*/ null);
    }
}
