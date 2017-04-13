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

import static android.graphics.fonts.FontVariationAxis.InvalidFormatException;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.fonts.FontRequest;
import android.graphics.fonts.FontResult;
import android.graphics.fonts.FontVariationAxis;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ResultReceiver;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Object represent a font entry in the family returned from {@link #fetchFonts}.
     */
    public static class FontInfo {
        private final Uri mUri;
        private final int mTtcIndex;
        private final FontVariationAxis[] mAxes;
        private final int mWeight;
        private final boolean mItalic;
        private final int mResultCode;

        /**
         * Creates a Font with all the information needed about a provided font.
         * @param uri A URI associated to the font file.
         * @param ttcIndex If providing a TTC_INDEX file, the index to point to. Otherwise, 0.
         * @param axes If providing a variation font, the settings for it. May be null.
         * @param weight An integer that indicates the font weight.
         * @param italic A boolean that indicates the font is italic style or not.
         * @param resultCode A boolean that indicates the font contents is ready.
         */
        /** @hide */
        public FontInfo(@NonNull Uri uri, @IntRange(from = 0) int ttcIndex,
                @Nullable FontVariationAxis[] axes, @IntRange(from = 1, to = 1000) int weight,
                boolean italic, int resultCode) {
            mUri = Preconditions.checkNotNull(uri);
            mTtcIndex = ttcIndex;
            mAxes = axes;
            mWeight = weight;
            mItalic = italic;
            mResultCode = resultCode;
        }

        /**
         * Returns a URI associated to this record.
         */
        public @NonNull Uri getUri() {
            return mUri;
        }

        /**
         * Returns the index to be used to access this font when accessing a TTC file.
         */
        public @IntRange(from = 0) int getTtcIndex() {
            return mTtcIndex;
        }

        /**
         * Returns the list of axes associated to this font.
         */
        public @Nullable FontVariationAxis[] getAxes() {
            return mAxes;
        }

        /**
         * Returns the weight value for this font.
         */
        public @IntRange(from = 1, to = 1000) int getWeight() {
            return mWeight;
        }

        /**
         * Returns whether this font is italic.
         */
        public boolean isItalic() {
            return mItalic;
        }

        /**
         * Returns result code.
         *
         * {@link FontsContract.Columns#RESULT_CODE}
         */
        public int getResultCode() {
            return mResultCode;
        }
    }

    /**
     * Object returned from {@link #fetchFonts}.
     */
    public static class FontFamilyResult {
        /**
         * Constant represents that the font was successfully retrieved. Note that when this value
         * is set and {@link #getFonts} returns an empty array, it means there were no fonts
         * matching the given query.
         */
        public static final int STATUS_OK = 0;

        /**
         * Constant represents that the given certificate was not matched with the provider's
         * signature. {@link #getFonts} returns null if this status was set.
         */
        public static final int STATUS_WRONG_CERTIFICATES = 1;

        /**
         * Constant represents that the provider returns unexpected data. {@link #getFonts} returns
         * null if this status was set. For example, this value is set when the font provider
         * gives invalid format of variation settings.
         */
        public static final int STATUS_UNEXPECTED_DATA_PROVIDED = 2;

        /** @hide */
        @IntDef({STATUS_OK, STATUS_WRONG_CERTIFICATES, STATUS_UNEXPECTED_DATA_PROVIDED})
        @Retention(RetentionPolicy.SOURCE)
        @interface FontResultStatus {}

        private final @FontResultStatus int mStatusCode;
        private final FontInfo[] mFonts;

        /** @hide */
        public FontFamilyResult(@FontResultStatus int statusCode, @Nullable FontInfo[] fonts) {
            mStatusCode = statusCode;
            mFonts = fonts;
        }

        public @FontResultStatus int getStatusCode() {
            return mStatusCode;
        }

        public @NonNull FontInfo[] getFonts() {
            return mFonts;
        }
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
                ProviderInfo providerInfo;
                try {
                    providerInfo = getProvider(mPackageManager, request);
                    if (providerInfo == null) {
                        receiver.send(RESULT_CODE_PROVIDER_NOT_FOUND, null);
                        return;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    receiver.send(RESULT_CODE_PROVIDER_NOT_FOUND, null);
                    return;
                }
                FontInfo[] fonts;
                try {
                    fonts = getFontFromProvider(mContext, request, providerInfo.authority,
                            null /* cancellation signal */);
                } catch (InvalidFormatException e) {
                    receiver.send(RESULT_CODE_PROVIDER_NOT_FOUND, null);
                    return;
                }

                ArrayList<FontResult> result = new ArrayList<>();
                int resultCode = -1;
                for (FontInfo font : fonts) {
                    try {
                        resultCode = font.getResultCode();
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
                        ParcelFileDescriptor pfd = mContext.getContentResolver().openFileDescriptor(
                                font.getUri(), "r");
                        result.add(new FontResult(pfd, font.getTtcIndex(),
                                FontVariationAxis.toFontVariationSettings(font.getAxes()),
                                font.getWeight(), font.isItalic()));
                    } catch (FileNotFoundException e) {
                        Log.e(TAG, "FileNotFoundException raised when interacting with content "
                                + "provider " + providerInfo.authority, e);
                    }
                }
                if (!result.isEmpty()) {
                    Bundle bundle = new Bundle();
                    bundle.putParcelableArrayList(PARCEL_FONT_RESULTS, result);
                    receiver.send(Columns.RESULT_CODE_OK, bundle);
                    return;
                }
                receiver.send(Columns.RESULT_CODE_FONT_NOT_FOUND, null);
            });
            mHandler.removeCallbacks(mReplaceDispatcherThreadRunnable);
            mHandler.postDelayed(mReplaceDispatcherThreadRunnable, THREAD_RENEWAL_THRESHOLD_MS);
        }
    }

    /**
     * Interface used to receive asynchronously fetched typefaces.
     */
    public static class FontRequestCallback {
        /**
         * Constant returned by {@link #onTypefaceRequestFailed(int)} signaling that the given
         * provider was not found on the device.
         */
        public static final int FAIL_REASON_PROVIDER_NOT_FOUND = RESULT_CODE_PROVIDER_NOT_FOUND;
        /**
         * Constant returned by {@link #onTypefaceRequestFailed(int)} signaling that the given
         * provider must be authenticated and the given certificates do not match its signature.
         */
        public static final int FAIL_REASON_WRONG_CERTIFICATES = RESULT_CODE_WRONG_CERTIFICATES;
        /**
         * Constant returned by {@link #onTypefaceRequestFailed(int)} signaling that the font
         * returned by the provider was not loaded properly.
         */
        public static final int FAIL_REASON_FONT_LOAD_ERROR = -3;
        /**
         * Constant returned by {@link #onTypefaceRequestFailed(int)} signaling that the font
         * provider did not return any results for the given query.
         */
        public static final int FAIL_REASON_FONT_NOT_FOUND = Columns.RESULT_CODE_FONT_NOT_FOUND;
        /**
         * Constant returned by {@link #onTypefaceRequestFailed(int)} signaling that the font
         * provider found the queried font, but it is currently unavailable.
         */
        public static final int FAIL_REASON_FONT_UNAVAILABLE = Columns.RESULT_CODE_FONT_UNAVAILABLE;
        /**
         * Constant returned by {@link #onTypefaceRequestFailed(int)} signaling that the given
         * query was not supported by the provider.
         */
        public static final int FAIL_REASON_MALFORMED_QUERY = Columns.RESULT_CODE_MALFORMED_QUERY;

        /** @hide */
        @IntDef({ FAIL_REASON_PROVIDER_NOT_FOUND, FAIL_REASON_FONT_LOAD_ERROR,
                FAIL_REASON_FONT_NOT_FOUND, FAIL_REASON_FONT_UNAVAILABLE,
                FAIL_REASON_MALFORMED_QUERY })
        @Retention(RetentionPolicy.SOURCE)
        @interface FontRequestFailReason {}

        public FontRequestCallback() {}

        /**
         * Called then a Typeface request done via {@link Typeface#create(FontRequest,
         * FontRequestCallback)} is complete. Note that this method will not be called if
         * {@link #onTypefaceRequestFailed(int)} is called instead.
         * @param typeface  The Typeface object retrieved.
         */
        public void onTypefaceRetrieved(Typeface typeface) {}

        /**
         * Called when a Typeface request done via {@link Typeface#create(FontRequest,
         * FontRequestCallback)} fails.
         * @param reason One of {@link #FAIL_REASON_PROVIDER_NOT_FOUND},
         *               {@link #FAIL_REASON_FONT_NOT_FOUND},
         *               {@link #FAIL_REASON_FONT_LOAD_ERROR},
         *               {@link #FAIL_REASON_FONT_UNAVAILABLE} or
         *               {@link #FAIL_REASON_MALFORMED_QUERY}.
         */
        public void onTypefaceRequestFailed(@FontRequestFailReason int reason) {}
    }

    /**
     * Create a typeface object given a font request. The font will be asynchronously fetched,
     * therefore the result is delivered to the given callback. See {@link FontRequest}.
     * Only one of the methods in callback will be invoked, depending on whether the request
     * succeeds or fails. These calls will happen on the caller thread.
     * @param context A context to be used for fetching from font provider.
     * @param request A {@link FontRequest} object that identifies the provider and query for the
     *                request. May not be null.
     * @param callback A callback that will be triggered when results are obtained. May not be null.
     * @param handler A handler to be processed the font fetching.
     */
    public static void requestFont(@NonNull Context context, @NonNull FontRequest request,
            @NonNull FontRequestCallback callback, @NonNull Handler handler) {

        final Handler callerThreadHandler = new Handler();
        handler.post(() -> {
            // TODO: Cache the result.
            FontFamilyResult result;
            try {
                result = fetchFonts(context, null /* cancellation signal */, request);
            } catch (NameNotFoundException e) {
                callerThreadHandler.post(() -> callback.onTypefaceRequestFailed(
                        FontRequestCallback.FAIL_REASON_PROVIDER_NOT_FOUND));
                return;
            }

            if (result.getStatusCode() != FontFamilyResult.STATUS_OK) {
                switch (result.getStatusCode()) {
                    case FontFamilyResult.STATUS_WRONG_CERTIFICATES:
                        callerThreadHandler.post(() -> callback.onTypefaceRequestFailed(
                                FontRequestCallback.FAIL_REASON_WRONG_CERTIFICATES));
                        return;
                    case FontFamilyResult.STATUS_UNEXPECTED_DATA_PROVIDED:
                        callerThreadHandler.post(() -> callback.onTypefaceRequestFailed(
                                FontRequestCallback.FAIL_REASON_FONT_LOAD_ERROR));
                        return;
                    default:
                        // fetchFont returns unexpected status type. Fallback to load error.
                        callerThreadHandler.post(() -> callback.onTypefaceRequestFailed(
                                FontRequestCallback.FAIL_REASON_FONT_LOAD_ERROR));
                        return;
                }
            }

            final FontInfo[] fonts = result.getFonts();
            if (fonts == null || fonts.length == 0) {
                callerThreadHandler.post(() -> callback.onTypefaceRequestFailed(
                        FontRequestCallback.FAIL_REASON_FONT_NOT_FOUND));
                return;
            }
            for (final FontInfo font : fonts) {
                if (font.getResultCode() != Columns.RESULT_CODE_OK) {
                    // We proceed if all font entry is ready to use. Otherwise report the first
                    // error.
                    final int resultCode = font.getResultCode();
                    if (resultCode < 0) {
                        // Negative values are reserved for internal errors. Fallback to load error.
                        callerThreadHandler.post(() -> callback.onTypefaceRequestFailed(
                                FontRequestCallback.FAIL_REASON_FONT_LOAD_ERROR));
                    } else {
                        callerThreadHandler.post(() -> callback.onTypefaceRequestFailed(
                                resultCode));
                    }
                    return;
                }
            }

            final Typeface typeface = buildTypeface(context, null /* cancellation signal */, fonts);
            if (typeface == null) {
                // Something went wrong during reading font files. This happens if the given font
                // file is an unsupported font type.
                callerThreadHandler.post(() -> callback.onTypefaceRequestFailed(
                        FontRequestCallback.FAIL_REASON_FONT_LOAD_ERROR));
                return;
            }

            callerThreadHandler.post(() -> callback.onTypefaceRetrieved(typeface));
        });
    }

    /**
     * Fetch fonts given a font request.
     *
     * @param context A {@link Context} to be used for fetching fonts.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none. If
     *                           the operation is canceled, then {@link
     *                           android.os.OperationCanceledException} will be thrown when the
     *                           query is executed.
     * @param request A {@link FontRequest} object that identifies the provider and query for the
     *                request.
     *
     * @return {@link FontFamilyResult}
     *
     * @throws NameNotFoundException If requested package or authority was not found in system.
     */
    public static @NonNull FontFamilyResult fetchFonts(
            @NonNull Context context, @Nullable CancellationSignal cancellationSignal,
            @NonNull FontRequest request) throws NameNotFoundException {
        ProviderInfo providerInfo = getProvider(context.getPackageManager(), request);
        if (providerInfo == null) {
            return new FontFamilyResult(FontFamilyResult.STATUS_WRONG_CERTIFICATES, null);

        }
        try {
            FontInfo[] fonts = getFontFromProvider(
                    context, request, providerInfo.authority, cancellationSignal);
            return new FontFamilyResult(FontFamilyResult.STATUS_OK, fonts);
        } catch (InvalidFormatException e) {
            return new FontFamilyResult(FontFamilyResult.STATUS_UNEXPECTED_DATA_PROVIDED, null);
        }
    }

    /**
     * Build a Typeface from an array of {@link FontInfo}. Results that are marked as not ready
     * will be skipped.
     *
     * @param context A {@link Context} that will be used to fetch the font contents.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none. If
     *                           the operation is canceled, then {@link
     *                           android.os.OperationCanceledException} will be thrown.
     * @param fonts An array of {@link FontInfo} to be used to create a Typeface.
     * @param weight A weight value to be used for selecting a font from a font family.
     * @param italic {@code true} if this font is of italic style. This will be used for font
     *               selection from a font family.
     * @param fallbackFontName A fallback font name used if this method fails to create the
     *                         Typeface. By passing {@code null}, this method returns {@code null}
     *                         if typeface creation fails.
     * @return A Typeface object. May return {@code null} if that is the value passed to {@code
     *         fallBackFontName}.
     */
    public static Typeface buildTypeface(@NonNull Context context,
            @Nullable CancellationSignal cancellationSignal, @NonNull FontInfo[] fonts,
            int weight, boolean italic, @Nullable String fallbackFontName) {
        final Map<Uri, ByteBuffer> uriBuffer =
                prepareFontData(context, fonts, cancellationSignal);
        Typeface typeface = new Typeface.Builder(fonts, uriBuffer)
            .setWeight(weight)
            .setItalic(italic)
            .build();
        // TODO: Use Typeface fallback instead.
        if (typeface == null) {
            typeface = Typeface.create(fallbackFontName, Typeface.NORMAL);
        }
        return typeface;
    }

    /**
     * Build a Typeface from an array of {@link FontInfo}
     *
     * Results that are marked as not ready will be skipped.
     *
     * @param context A {@link Context} that will be used to fetch the font contents.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none. If
     *                           the operation is canceled, then {@link
     *                           android.os.OperationCanceledException} will be thrown.
     * @param fonts An array of {@link FontInfo} to be used to create a Typeface.
     * @return A Typeface object. Returns null if typeface creation fails.
     */
    public static Typeface buildTypeface(@NonNull Context context,
            @Nullable CancellationSignal cancellationSignal, @NonNull FontInfo[] fonts) {
        final Map<Uri, ByteBuffer> uriBuffer =
                prepareFontData(context, fonts, cancellationSignal);
        return new Typeface.Builder(fonts, uriBuffer).build();
    }

    /**
     * A helper function to create a mapping from {@link Uri} to {@link ByteBuffer}.
     *
     * Skip if the file contents is not ready to be read.
     *
     * @param context A {@link Context} to be used for resolving content URI in
     *                {@link FontInfo}.
     * @param fonts An array of {@link FontInfo}.
     * @return A map from {@link Uri} to {@link ByteBuffer}.
     */
    private static Map<Uri, ByteBuffer> prepareFontData(Context context, FontInfo[] fonts,
            CancellationSignal cancellationSignal) {
        final HashMap<Uri, ByteBuffer> out = new HashMap<>();
        final ContentResolver resolver = context.getContentResolver();

        for (FontInfo font : fonts) {
            if (font.getResultCode() != Columns.RESULT_CODE_OK) {
                continue;
            }

            final Uri uri = font.getUri();
            if (out.containsKey(uri)) {
                continue;
            }

            ByteBuffer buffer = null;
            try (final ParcelFileDescriptor pfd =
                    resolver.openFileDescriptor(uri, "r", cancellationSignal);
                    final FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                final FileChannel fileChannel = fis.getChannel();
                final long size = fileChannel.size();
                buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            } catch (IOException e) {
                // ignore
            }

            // TODO: try other approach?, e.g. read all contents instead of mmap.

            out.put(uri, buffer);
        }
        return Collections.unmodifiableMap(out);
    }

    /** @hide */
    @VisibleForTesting
    public static @Nullable ProviderInfo getProvider(
            PackageManager packageManager, FontRequest request) throws NameNotFoundException {
        String providerAuthority = request.getProviderAuthority();
        ProviderInfo info = packageManager.resolveContentProvider(providerAuthority, 0);
        if (info == null) {
            throw new NameNotFoundException("No package found for authority: " + providerAuthority);
        }

        if (!info.packageName.equals(request.getProviderPackage())) {
            throw new NameNotFoundException("Found content provider " + providerAuthority
                    + ", but package was not " + request.getProviderPackage());
        }
        // Trust system apps without signature checks
        if (info.applicationInfo.isSystemApp()) {
            return info;
        }

        List<byte[]> signatures;
        PackageInfo packageInfo = packageManager.getPackageInfo(info.packageName,
                PackageManager.GET_SIGNATURES);
        signatures = convertToByteArrayList(packageInfo.signatures);
        Collections.sort(signatures, sByteArrayComparator);

        List<List<byte[]>> requestCertificatesList = request.getCertificates();
        for (int i = 0; i < requestCertificatesList.size(); ++i) {
            // Make a copy so we can sort it without modifying the incoming data.
            List<byte[]> requestSignatures = new ArrayList<>(requestCertificatesList.get(i));
            Collections.sort(requestSignatures, sByteArrayComparator);
            if (equalsByteArrayList(signatures, requestSignatures)) {
                return info;
            }
        }
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

    private static boolean equalsByteArrayList(
            List<byte[]> signatures, List<byte[]> requestSignatures) {
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

    private static List<byte[]> convertToByteArrayList(Signature[] signatures) {
        List<byte[]> shas = new ArrayList<>();
        for (int i = 0; i < signatures.length; ++i) {
            shas.add(signatures[i].toByteArray());
        }
        return shas;
    }

    /** @hide */
    @VisibleForTesting
    public static @NonNull FontInfo[] getFontFromProvider(
            Context context, FontRequest request, String authority,
            CancellationSignal cancellationSignal) throws InvalidFormatException {
        ArrayList<FontInfo> result = new ArrayList<>();
        final Uri uri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .build();
        final Uri fileBaseUri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath("file")
                .build();
        try (Cursor cursor = context.getContentResolver().query(uri, new String[] { Columns._ID,
                        Columns.FILE_ID, Columns.TTC_INDEX, Columns.VARIATION_SETTINGS,
                        Columns.STYLE, Columns.WEIGHT, Columns.ITALIC, Columns.RESULT_CODE },
                "query = ?", new String[] { request.getQuery() }, null, cancellationSignal);) {
            // TODO: Should we restrict the amount of fonts that can be returned?
            // TODO: Write documentation explaining that all results should be from the same family.
            if (cursor != null && cursor.getCount() > 0) {
                final int resultCodeColumnIndex = cursor.getColumnIndex(Columns.RESULT_CODE);
                result = new ArrayList<>();
                final int idColumnIndex = cursor.getColumnIndexOrThrow(Columns._ID);
                final int fileIdColumnIndex = cursor.getColumnIndex(Columns.FILE_ID);
                final int ttcIndexColumnIndex = cursor.getColumnIndex(Columns.TTC_INDEX);
                final int vsColumnIndex = cursor.getColumnIndex(Columns.VARIATION_SETTINGS);
                final int weightColumnIndex = cursor.getColumnIndex(Columns.WEIGHT);
                final int italicColumnIndex = cursor.getColumnIndex(Columns.ITALIC);
                final int styleColumnIndex = cursor.getColumnIndex(Columns.STYLE);
                while (cursor.moveToNext()) {
                    int resultCode = resultCodeColumnIndex != -1
                            ? cursor.getInt(resultCodeColumnIndex) : Columns.RESULT_CODE_OK;
                    final int ttcIndex = ttcIndexColumnIndex != -1
                            ? cursor.getInt(ttcIndexColumnIndex) : 0;
                    final String variationSettings = vsColumnIndex != -1
                            ? cursor.getString(vsColumnIndex) : null;

                    Uri fileUri;
                    if (fileIdColumnIndex == -1) {
                        long id = cursor.getLong(idColumnIndex);
                        fileUri = ContentUris.withAppendedId(uri, id);
                    } else {
                        long id = cursor.getLong(fileIdColumnIndex);
                        fileUri = ContentUris.withAppendedId(fileBaseUri, id);
                    }
                    // TODO: Stop using STYLE column and enforce WEIGHT/ITALIC column.
                    int weight;
                    boolean italic;
                    if (weightColumnIndex != -1 && italicColumnIndex != -1) {
                        weight = cursor.getInt(weightColumnIndex);
                        italic = cursor.getInt(italicColumnIndex) == 1;
                    } else if (styleColumnIndex != -1) {
                        final int style = cursor.getInt(styleColumnIndex);
                        weight = (style & Typeface.BOLD) != 0 ?
                                Typeface.Builder.BOLD_WEIGHT : Typeface.Builder.NORMAL_WEIGHT;
                        italic = (style & Typeface.ITALIC) != 0;
                    } else {
                        weight = Typeface.Builder.NORMAL_WEIGHT;
                        italic = false;
                    }
                    FontVariationAxis[] axes =
                            FontVariationAxis.fromFontVariationSettings(variationSettings);
                    result.add(new FontInfo(fileUri, ttcIndex, axes, weight, italic, resultCode));
                }
            }
        }
        return result.toArray(new FontInfo[0]);
    }
}
