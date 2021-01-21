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

package com.android.server.graphics.fonts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontFileUtil;
import android.graphics.fonts.SystemFonts;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.text.FontConfig;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.NioUtils;
import java.nio.channels.FileChannel;
import java.util.Map;

/** A service for managing system fonts. */
// TODO(b/173619554): Add API to update fonts.
public final class FontManagerService {

    private static final String TAG = "FontManagerService";

    // TODO: make this a DeviceConfig flag.
    private static final boolean ENABLE_FONT_UPDATES = false;
    private static final String FONT_FILES_DIR = "/data/fonts/files";

    /** Class to manage FontManagerService's lifecycle. */
    public static final class Lifecycle extends SystemService {
        private final FontManagerService mService;

        public Lifecycle(@NonNull Context context) {
            super(context);
            mService = new FontManagerService();
        }

        @Override
        public void onStart() {
            LocalServices.addService(FontManagerInternal.class,
                    new FontManagerInternal() {
                        @Override
                        @Nullable
                        public SharedMemory getSerializedSystemFontMap() {
                            if (!Typeface.ENABLE_LAZY_TYPEFACE_INITIALIZATION) {
                                return null;
                            }
                            return mService.getCurrentFontSettings().getSerializedSystemFontMap();
                        }
                    });
        }
    }

    private static class OtfFontFileParser implements UpdatableFontDir.FontFileParser {
        @Override
        public long getVersion(File file) throws IOException {
            ByteBuffer buffer = mmap(file);
            try {
                return FontFileUtil.getRevision(buffer, 0);
            } finally {
                NioUtils.freeDirectBuffer(buffer);
            }
        }

        private static ByteBuffer mmap(File file) throws IOException {
            try (FileInputStream in = new FileInputStream(file)) {
                FileChannel fileChannel = in.getChannel();
                return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
            }
        }
    }

    @Nullable
    private final UpdatableFontDir mUpdatableFontDir;

    @GuardedBy("FontManagerService.this")
    @Nullable SystemFontSettings mCurrentFontSettings = null;

    private FontManagerService() {
        mUpdatableFontDir = ENABLE_FONT_UPDATES
                ? new UpdatableFontDir(new File(FONT_FILES_DIR), new OtfFontFileParser()) : null;
    }

    @NonNull private SystemFontSettings getCurrentFontSettings() {
        synchronized (FontManagerService.this) {
            if (mCurrentFontSettings == null) {
                mCurrentFontSettings = SystemFontSettings.create(mUpdatableFontDir);
            }
            return mCurrentFontSettings;
        }
    }

    private boolean installFontFile(String name, FileDescriptor fd) {
        if (mUpdatableFontDir == null) return false;
        synchronized (FontManagerService.this) {
            try {
                mUpdatableFontDir.installFontFile(name, fd);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to install font file: " + name, e);
                return false;
            }
            // Create updated font map in the next getSerializedSystemFontMap() call.
            mCurrentFontSettings = null;
            return true;
        }
    }

    private static class SystemFontSettings {
        private final @NonNull SharedMemory mSerializedSystemFontMap;
        private final @NonNull FontConfig mSystemFontConfig;
        private final @NonNull Map<String, FontFamily[]> mSystemFallbackMap;
        private final @NonNull Map<String, Typeface> mSystemTypefaceMap;

        SystemFontSettings(
                @NonNull SharedMemory serializedSystemFontMap,
                @NonNull FontConfig systemFontConfig,
                @NonNull Map<String, FontFamily[]> systemFallbackMap,
                @NonNull Map<String, Typeface> systemTypefaceMap) {
            mSerializedSystemFontMap = serializedSystemFontMap;
            mSystemFontConfig = systemFontConfig;
            mSystemFallbackMap = systemFallbackMap;
            mSystemTypefaceMap = systemTypefaceMap;
        }

        public @NonNull SharedMemory getSerializedSystemFontMap() {
            return mSerializedSystemFontMap;
        }

        public @NonNull FontConfig getSystemFontConfig() {
            return mSystemFontConfig;
        }

        public @NonNull Map<String, FontFamily[]> getSystemFallbackMap() {
            return mSystemFallbackMap;
        }

        public @NonNull Map<String, Typeface> getSystemTypefaceMap() {
            return mSystemTypefaceMap;
        }

        public static @Nullable SystemFontSettings create(
                @Nullable UpdatableFontDir updatableFontDir) {
            if (updatableFontDir != null) {
                final FontConfig fontConfig = SystemFonts.getSystemFontConfig(
                        updatableFontDir.getFontFileMap());
                final Map<String, FontFamily[]> fallback =
                        SystemFonts.buildSystemFallback(fontConfig);
                final Map<String, Typeface> typefaceMap =
                        SystemFonts.buildSystemTypefaces(fontConfig, fallback);

                try {
                    final SharedMemory shm = Typeface.serializeFontMap(typefaceMap);
                    return new SystemFontSettings(shm, fontConfig, fallback, typefaceMap);
                } catch (IOException | ErrnoException e) {
                    Slog.w(TAG, "Failed to serialize updatable font map. "
                            + "Retrying with system image fonts.", e);
                }
            }

            final FontConfig fontConfig = SystemFonts.getSystemPreinstalledFontConfig();
            final Map<String, FontFamily[]> fallback = SystemFonts.buildSystemFallback(fontConfig);
            final Map<String, Typeface> typefaceMap =
                    SystemFonts.buildSystemTypefaces(fontConfig, fallback);
            try {
                final SharedMemory shm = Typeface.serializeFontMap(typefaceMap);
                return new SystemFontSettings(shm, fontConfig, fallback, typefaceMap);
            } catch (IOException | ErrnoException e) {
                Slog.e(TAG, "Failed to serialize SystemServer system font map", e);
            }
            return null;
        }
    };
}
