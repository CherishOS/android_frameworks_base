/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.server.blob;

import static android.provider.DeviceConfig.NAMESPACE_BLOBSTORE;
import static android.text.format.Formatter.FLAG_IEC_UNITS;
import static android.text.format.Formatter.formatFileSize;
import static android.util.TimeUtils.formatDuration;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Environment;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.util.DataUnit;
import android.util.Log;
import android.util.Slog;

import com.android.internal.util.IndentingPrintWriter;

import java.io.File;
import java.util.concurrent.TimeUnit;

class BlobStoreConfig {
    public static final String TAG = "BlobStore";
    public static final boolean LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    // Initial version.
    public static final int XML_VERSION_INIT = 1;
    // Added a string variant of lease description.
    public static final int XML_VERSION_ADD_STRING_DESC = 2;
    public static final int XML_VERSION_ADD_DESC_RES_NAME = 3;

    public static final int XML_VERSION_CURRENT = XML_VERSION_ADD_DESC_RES_NAME;

    private static final String ROOT_DIR_NAME = "blobstore";
    private static final String BLOBS_DIR_NAME = "blobs";
    private static final String SESSIONS_INDEX_FILE_NAME = "sessions_index.xml";
    private static final String BLOBS_INDEX_FILE_NAME = "blobs_index.xml";

    /**
     * Job Id for idle maintenance job ({@link BlobStoreIdleJobService}).
     */
    public static final int IDLE_JOB_ID = 0xB70B1D7; // 191934935L
    /**
     * Max time period (in millis) between each idle maintenance job run.
     */
    public static final long IDLE_JOB_PERIOD_MILLIS = TimeUnit.DAYS.toMillis(1);

    /**
     * Timeout in millis after which sessions with no updates will be deleted.
     */
    public static final long SESSION_EXPIRY_TIMEOUT_MILLIS = TimeUnit.DAYS.toMillis(7);

    public static class DeviceConfigProperties {
        /**
         * Denotes how low the limit for the amount of data, that an app will be allowed to acquire
         * a lease on, can be.
         */
        public static final String KEY_TOTAL_BYTES_PER_APP_LIMIT_FLOOR =
                "total_bytes_per_app_limit_floor";
        public static final long DEFAULT_TOTAL_BYTES_PER_APP_LIMIT_FLOOR =
                DataUnit.MEBIBYTES.toBytes(300); // 300 MiB
        public static long TOTAL_BYTES_PER_APP_LIMIT_FLOOR =
                DEFAULT_TOTAL_BYTES_PER_APP_LIMIT_FLOOR;

        /**
         * Denotes the maximum amount of data an app can acquire a lease on, in terms of fraction
         * of total disk space.
         */
        public static final String KEY_TOTAL_BYTES_PER_APP_LIMIT_FRACTION =
                "total_bytes_per_app_limit_fraction";
        public static final float DEFAULT_TOTAL_BYTES_PER_APP_LIMIT_FRACTION = 0.01f;
        public static float TOTAL_BYTES_PER_APP_LIMIT_FRACTION =
                DEFAULT_TOTAL_BYTES_PER_APP_LIMIT_FRACTION;

        static void refresh(Properties properties) {
            if (!NAMESPACE_BLOBSTORE.equals(properties.getNamespace())) {
                return;
            }
            properties.getKeyset().forEach(key -> {
                switch (key) {
                    case KEY_TOTAL_BYTES_PER_APP_LIMIT_FLOOR:
                        TOTAL_BYTES_PER_APP_LIMIT_FLOOR = properties.getLong(key,
                                DEFAULT_TOTAL_BYTES_PER_APP_LIMIT_FLOOR);
                        break;
                    case KEY_TOTAL_BYTES_PER_APP_LIMIT_FRACTION:
                        TOTAL_BYTES_PER_APP_LIMIT_FRACTION = properties.getFloat(key,
                                DEFAULT_TOTAL_BYTES_PER_APP_LIMIT_FRACTION);
                        break;
                    default:
                        Slog.wtf(TAG, "Unknown key in device config properties: " + key);
                }
            });
        }

        static void dump(IndentingPrintWriter fout, Context context) {
            final String dumpFormat = "%s: [cur: %s, def: %s]";
            fout.println(String.format(dumpFormat, KEY_TOTAL_BYTES_PER_APP_LIMIT_FLOOR,
                    formatFileSize(context, TOTAL_BYTES_PER_APP_LIMIT_FLOOR, FLAG_IEC_UNITS),
                    formatFileSize(context, DEFAULT_TOTAL_BYTES_PER_APP_LIMIT_FLOOR,
                            FLAG_IEC_UNITS)));
            fout.println(String.format(dumpFormat, KEY_TOTAL_BYTES_PER_APP_LIMIT_FRACTION,
                    TOTAL_BYTES_PER_APP_LIMIT_FRACTION,
                    DEFAULT_TOTAL_BYTES_PER_APP_LIMIT_FRACTION));
        }
    }

    public static void initialize(Context context) {
        DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_BLOBSTORE,
                context.getMainExecutor(),
                properties -> DeviceConfigProperties.refresh(properties));
        DeviceConfigProperties.refresh(DeviceConfig.getProperties(NAMESPACE_BLOBSTORE));
    }

    /**
     * Returns the maximum amount of data that an app can acquire a lease on.
     */
    public static long getAppDataBytesLimit() {
        final long totalBytesLimit = (long) (Environment.getDataSystemDirectory().getTotalSpace()
                * DeviceConfigProperties.TOTAL_BYTES_PER_APP_LIMIT_FRACTION);
        return Math.max(DeviceConfigProperties.TOTAL_BYTES_PER_APP_LIMIT_FLOOR, totalBytesLimit);
    }

    @Nullable
    public static File prepareBlobFile(long sessionId) {
        final File blobsDir = prepareBlobsDir();
        return blobsDir == null ? null : getBlobFile(blobsDir, sessionId);
    }

    @NonNull
    public static File getBlobFile(long sessionId) {
        return getBlobFile(getBlobsDir(), sessionId);
    }

    @NonNull
    private static File getBlobFile(File blobsDir, long sessionId) {
        return new File(blobsDir, String.valueOf(sessionId));
    }

    @Nullable
    public static File prepareBlobsDir() {
        final File blobsDir = getBlobsDir(prepareBlobStoreRootDir());
        if (!blobsDir.exists() && !blobsDir.mkdir()) {
            Slog.e(TAG, "Failed to mkdir(): " + blobsDir);
            return null;
        }
        return blobsDir;
    }

    @NonNull
    public static File getBlobsDir() {
        return getBlobsDir(getBlobStoreRootDir());
    }

    @NonNull
    private static File getBlobsDir(File blobsRootDir) {
        return new File(blobsRootDir, BLOBS_DIR_NAME);
    }

    @Nullable
    public static File prepareSessionIndexFile() {
        final File blobStoreRootDir = prepareBlobStoreRootDir();
        if (blobStoreRootDir == null) {
            return null;
        }
        return new File(blobStoreRootDir, SESSIONS_INDEX_FILE_NAME);
    }

    @Nullable
    public static File prepareBlobsIndexFile() {
        final File blobsStoreRootDir = prepareBlobStoreRootDir();
        if (blobsStoreRootDir == null) {
            return null;
        }
        return new File(blobsStoreRootDir, BLOBS_INDEX_FILE_NAME);
    }

    @Nullable
    public static File prepareBlobStoreRootDir() {
        final File blobStoreRootDir = getBlobStoreRootDir();
        if (!blobStoreRootDir.exists() && !blobStoreRootDir.mkdir()) {
            Slog.e(TAG, "Failed to mkdir(): " + blobStoreRootDir);
            return null;
        }
        return blobStoreRootDir;
    }

    @NonNull
    public static File getBlobStoreRootDir() {
        return new File(Environment.getDataSystemDirectory(), ROOT_DIR_NAME);
    }

    public static void dump(IndentingPrintWriter fout, Context context) {
        fout.println("XML current version: " + XML_VERSION_CURRENT);

        fout.println("Idle job ID: " + IDLE_JOB_ID);
        fout.println("Idle job period: " + formatDuration(IDLE_JOB_PERIOD_MILLIS));

        fout.println("Session expiry timeout: " + formatDuration(SESSION_EXPIRY_TIMEOUT_MILLIS));

        fout.println("Total bytes per app limit: " + formatFileSize(context,
                getAppDataBytesLimit(), FLAG_IEC_UNITS));

        fout.println("Device config properties:");
        fout.increaseIndent();
        DeviceConfigProperties.dump(fout, context);
        fout.decreaseIndent();
    }
}
