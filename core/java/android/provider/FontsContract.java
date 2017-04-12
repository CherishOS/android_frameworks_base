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
package android.provider;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.fonts.FontRequest;
import android.graphics.fonts.FontResult;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ResultReceiver;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Utility class to deal with Font ContentProviders.
 */
public class FontsContract {
    private static final String TAG = "FontsContract";

    /**
     * Defines the constants used in a response from a Font Provider. The cursor returned from the
     * query should have the ID column populated with the content uri ID for the resulting font.
     * This should point to a real file or shared memory, as the client will mmap the given file
     * descriptor. Pipes, sockets and other non-mmap-able file descriptors will fail to load in the
     * client application.
     */
    public static final class Columns implements BaseColumns {
        /**
         * Constant used to request data from a font provider. The cursor returned from the query
         * may populate this column with a long for the font file ID. The client will request a file
         * descriptor to "file/FILE_ID" with this ID immediately under the top-level content URI. If
         * not present, the client will request a file descriptor to the top-level URI with the
         * given base font ID. Note that several results may return the same file ID, e.g. for TTC
         * files with different indices.
         */
        public static final String FILE_ID = "file_id";
        /**
         * Constant used to request data from a font provider. The cursor returned from the query
         * should have this column populated with an int for the ttc index for the resulting font.
         */
        public static final String TTC_INDEX = "font_ttc_index";
        /**
         * Constant used to request data from a font provider. The cursor returned from the query
         * may populate this column with the font variation settings String information for the
         * font.
         */
        public static final String VARIATION_SETTINGS = "font_variation_settings";
        /**
         * DO NOT USE THIS COLUMN.
         * This column is kept for preventing demo apps.
         * TODO: Remove once nobody uses this column.
         * @hide
         * @removed
         */
        public static final String STYLE = "font_style";
        /**
         * Constant used to request data from a font provider. The cursor returned from the query
         * should have this column populated with the int weight for the resulting font. This value
         * should be between 100 and 900. The most common values are 400 for regular weight and 700
         * for bold weight.
         */
        public static final String WEIGHT = "font_weight";
        /**
         * Constant used to request data from a font provider. The cursor returned from the query
         * should have this column populated with the int italic for the resulting font. This should
         * be 0 for regular style and 1 for italic.
         */
        public static final String ITALIC = "font_italic";
        /**
         * Constant used to request data from a font provider. The cursor returned from the query
         * should have this column populated to indicate the result status of the
         * query. This will be checked before any other data in the cursor. Possible values are
         * {@link #RESULT_CODE_OK}, {@link #RESULT_CODE_FONT_NOT_FOUND},
         * {@link #RESULT_CODE_MALFORMED_QUERY} and {@link #RESULT_CODE_FONT_UNAVAILABLE}. If not
         * present, {@link #RESULT_CODE_OK} will be assumed.
         */
        public static final String RESULT_CODE = "result_code";

        /**
         * Constant used to represent a result was retrieved successfully. The given fonts will be
         * attempted to retrieve immediately via
         * {@link android.content.ContentProvider#openFile(Uri, String)}. See {@link #RESULT_CODE}.
         */
        public static final int RESULT_CODE_OK = 0;
        /**
         * Constant used to represent a result was not found. See {@link #RESULT_CODE}.
         */
        public static final int RESULT_CODE_FONT_NOT_FOUND = 1;
        /**
         * Constant used to represent a result was found, but cannot be provided at this moment. Use
         * this to indicate, for example, that a font needs to be fetched from the network. See
         * {@link #RESULT_CODE}.
         */
        public static final int RESULT_CODE_FONT_UNAVAILABLE = 2;
        /**
         * Constant used to represent that the query was not in a supported format by the provider.
         * See {@link #RESULT_CODE}.
         */
        public static final int RESULT_CODE_MALFORMED_QUERY = 3;
    }

    /**
     * Constant used to identify the List of {@link ParcelFileDescriptor} item in the Bundle
     * returned to the ResultReceiver in getFont.
     * @hide
     */
    public static final String PARCEL_FONT_RESULTS = "font_results";

    // Error codes internal to the system, which can not come from a provider. To keep the number
    // space open for new provider codes, these should all be negative numbers.
    /** @hide */
    public static final int RESULT_CODE_PROVIDER_NOT_FOUND = -1;
    /** @hide */
    public static final int RESULT_CODE_WRONG_CERTIFICATES = -2;
    // Note -3 is used by Typeface to indicate the font failed to load.

    private static final int THREAD_RENEWAL_THRESHOLD_MS = 10000;

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private Handler mHandler;
    @GuardedBy("mLock")
    private HandlerThread mThread;

    /** @hide */
    public FontsContract(Context context) {
        mContext = context.getApplicationContext();
        mPackageManager = mContext.getPackageManager();
    }

    /** @hide */
    @VisibleForTesting
    public FontsContract(Context context, PackageManager packageManager) {
        mContext = context;
        mPackageManager = packageManager;
    }

    // We use a background thread to post the content resolving work for all requests on. This
    // thread should be quit/stopped after all requests are done.
    private final Runnable mReplaceDispatcherThreadRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                if (mThread != null) {
                    mThread.quitSafely();
                    mThread = null;
                    mHandler = null;
                }
            }
        }
    };

    /** @hide */
    public void getFont(FontRequest request, ResultReceiver receiver) {
        synchronized (mLock) {
            if (mHandler == null) {
                mThread = new HandlerThread("fonts", Process.THREAD_PRIORITY_BACKGROUND);
                mThread.start();
                mHandler = new Handler(mThread.getLooper());
            }
            mHandler.post(() -> {
                ProviderInfo providerInfo = getProvider(request, receiver);
                if (providerInfo == null) {
                    return;
                }
                getFontFromProvider(request, receiver, providerInfo.authority);
            });
            mHandler.removeCallbacks(mReplaceDispatcherThreadRunnable);
            mHandler.postDelayed(mReplaceDispatcherThreadRunnable, THREAD_RENEWAL_THRESHOLD_MS);
        }
    }

    /** @hide */
    @VisibleForTesting
    public ProviderInfo getProvider(FontRequest request, ResultReceiver receiver) {
        String providerAuthority = request.getProviderAuthority();
        ProviderInfo info = mPackageManager.resolveContentProvider(providerAuthority, 0);
        if (info == null) {
            Log.e(TAG, "Can't find content provider " + providerAuthority);
            receiver.send(RESULT_CODE_PROVIDER_NOT_FOUND, null);
            return null;
        }

        if (!info.packageName.equals(request.getProviderPackage())) {
            Log.e(TAG, "Found content provider " + providerAuthority + ", but package was not "
                    + request.getProviderPackage());
            receiver.send(RESULT_CODE_PROVIDER_NOT_FOUND, null);
            return null;
        }
        // Trust system apps without signature checks
        if (info.applicationInfo.isSystemApp()) {
            return info;
        }

        List<byte[]> signatures;
        try {
            PackageInfo packageInfo = mPackageManager.getPackageInfo(info.packageName,
                    PackageManager.GET_SIGNATURES);
            signatures = convertToByteArrayList(packageInfo.signatures);
            Collections.sort(signatures, sByteArrayComparator);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Can't find content provider " + providerAuthority, e);
            receiver.send(RESULT_CODE_PROVIDER_NOT_FOUND, null);
            return null;
        }
        List<List<byte[]>> requestCertificatesList = request.getCertificates();
        for (int i = 0; i < requestCertificatesList.size(); ++i) {
            // Make a copy so we can sort it without modifying the incoming data.
            List<byte[]> requestSignatures = new ArrayList<>(requestCertificatesList.get(i));
            Collections.sort(requestSignatures, sByteArrayComparator);
            if (equalsByteArrayList(signatures, requestSignatures)) {
                return info;
            }
        }
        Log.e(TAG, "Certificates don't match for given provider " + providerAuthority);
        receiver.send(RESULT_CODE_WRONG_CERTIFICATES, null);
        return null;
    }

    private static final Comparator<byte[]> sByteArrayComparator = (l, r) -> {
        if (l.length != r.length) {
            return l.length - r.length;
        }
        for (int i = 0; i < l.length; ++i) {
            if (l[i] != r[i]) {
                return l[i] - r[i];
            }
        }
        return 0;
    };

    private boolean equalsByteArrayList(List<byte[]> signatures, List<byte[]> requestSignatures) {
        if (signatures.size() != requestSignatures.size()) {
            return false;
        }
        for (int i = 0; i < signatures.size(); ++i) {
            if (!Arrays.equals(signatures.get(i), requestSignatures.get(i))) {
                return false;
            }
        }
        return true;
    }

    private List<byte[]> convertToByteArrayList(Signature[] signatures) {
        List<byte[]> shas = new ArrayList<>();
        for (int i = 0; i < signatures.length; ++i) {
            shas.add(signatures[i].toByteArray());
        }
        return shas;
    }

    /** @hide */
    @VisibleForTesting
    public void getFontFromProvider(FontRequest request, ResultReceiver receiver,
            String authority) {
        ArrayList<FontResult> result = null;
        final Uri uri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .build();
        final Uri fileBaseUri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath("file")
                .build();
        try (Cursor cursor = mContext.getContentResolver().query(uri, new String[] { Columns._ID,
                        Columns.FILE_ID, Columns.TTC_INDEX, Columns.VARIATION_SETTINGS,
                        Columns.STYLE, Columns.WEIGHT, Columns.ITALIC, Columns.RESULT_CODE },
                "query = ?", new String[] { request.getQuery() }, null);) {
            // TODO: Should we restrict the amount of fonts that can be returned?
            // TODO: Write documentation explaining that all results should be from the same family.
            if (cursor != null && cursor.getCount() > 0) {
                final int resultCodeColumnIndex = cursor.getColumnIndex(Columns.RESULT_CODE);
                int resultCode = -1;
                result = new ArrayList<>();
                final int idColumnIndex = cursor.getColumnIndexOrThrow(Columns._ID);
                final int fileIdColumnIndex = cursor.getColumnIndex(Columns.FILE_ID);
                final int ttcIndexColumnIndex = cursor.getColumnIndex(Columns.TTC_INDEX);
                final int vsColumnIndex = cursor.getColumnIndex(Columns.VARIATION_SETTINGS);
                final int weightColumnIndex = cursor.getColumnIndex(Columns.WEIGHT);
                final int italicColumnIndex = cursor.getColumnIndex(Columns.ITALIC);
                final int styleColumnIndex = cursor.getColumnIndex(Columns.STYLE);
                while (cursor.moveToNext()) {
                    resultCode = resultCodeColumnIndex != -1
                            ? cursor.getInt(resultCodeColumnIndex) : Columns.RESULT_CODE_OK;
                    if (resultCode != Columns.RESULT_CODE_OK) {
                        if (resultCode < 0) {
                            // Negative values are reserved for the internal errors.
                            resultCode = Columns.RESULT_CODE_FONT_NOT_FOUND;
                        }
                        for (int i = 0; i < result.size(); ++i) {
                            try {
                                result.get(i).getFileDescriptor().close();
                            } catch (IOException e) {
                                // Ignore, as we are closing fds for cleanup.
                            }
                        }
                        receiver.send(resultCode, null);
                        return;
                    }
                    Uri fileUri;
                    if (fileIdColumnIndex == -1) {
                        long id = cursor.getLong(idColumnIndex);
                        fileUri = ContentUris.withAppendedId(uri, id);
                    } else {
                        long id = cursor.getLong(fileIdColumnIndex);
                        fileUri = ContentUris.withAppendedId(fileBaseUri, id);
                    }
                    try {
                        ParcelFileDescriptor pfd =
                                mContext.getContentResolver().openFileDescriptor(fileUri, "r");
                        final int ttcIndex = ttcIndexColumnIndex != -1
                                ? cursor.getInt(ttcIndexColumnIndex) : 0;
                        final String variationSettings = vsColumnIndex != -1
                                ? cursor.getString(vsColumnIndex) : null;
                        // TODO: Stop using STYLE column and enforce WEIGHT/ITALIC column.
                        int weight;
                        boolean italic;
                        if (weightColumnIndex != -1 && italicColumnIndex != -1) {
                            weight = cursor.getInt(weightColumnIndex);
                            italic = cursor.getInt(italicColumnIndex) == 1;
                        } else if (styleColumnIndex != -1) {
                            final int style = cursor.getInt(styleColumnIndex);
                            weight = (style & Typeface.BOLD) != 0 ? 700 : 400;
                            italic = (style & Typeface.ITALIC) != 0;
                        } else {
                            weight = 400;
                            italic = false;
                        }
                        result.add(
                                new FontResult(pfd, ttcIndex, variationSettings, weight, italic));
                    } catch (FileNotFoundException e) {
                        Log.e(TAG, "FileNotFoundException raised when interacting with content "
                                + "provider " + authority, e);
                    }
                }
            }
        }
        if (result != null && !result.isEmpty()) {
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(PARCEL_FONT_RESULTS, result);
            receiver.send(Columns.RESULT_CODE_OK, bundle);
            return;
        }
        receiver.send(Columns.RESULT_CODE_FONT_NOT_FOUND, null);
    }
}
