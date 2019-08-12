/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.providers.settings;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertArrayEquals;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.annotations.VisibleForTesting;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests for the SettingsHelperTest */
@RunWith(AndroidJUnit4.class)
public class SettingsBackupAgentTest extends BaseSettingsProviderTest {

    private static final String TEST_DISPLAY_DENSITY_FORCED = "123";
    private static final Map<String, String> TEST_VALUES = new HashMap<>();

    static {
        TEST_VALUES.put(Settings.Secure.DISPLAY_DENSITY_FORCED, TEST_DISPLAY_DENSITY_FORCED);
    }

    private TestFriendlySettingsBackupAgent mAgentUnderTest;
    private Context mContext;

    @Before
    public void setUp() {
        mContext = new ContextWithMockContentResolver(getContext());

        mAgentUnderTest = new TestFriendlySettingsBackupAgent();
        mAgentUnderTest.attach(mContext);
    }

    @Test
    public void testRoundTripDeviceSpecificSettings() throws IOException {
        TestSettingsHelper helper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = helper;

        byte[] settingsBackup = mAgentUnderTest.getDeviceSpecificConfiguration();

        assertEquals("Not all values backed up.", TEST_VALUES.keySet(), helper.mReadEntries);

        mAgentUnderTest.restoreDeviceSpecificConfig(settingsBackup);

        assertEquals("Not all values were restored.", TEST_VALUES, helper.mWrittenValues);
    }

    @Test
    public void testGeneratedHeaderMatchesCurrentDevice() throws IOException {
        mAgentUnderTest.mSettingsHelper = new TestSettingsHelper(mContext);

        byte[] header = generateUncorruptedHeader();

        AtomicInteger pos = new AtomicInteger(0);
        assertTrue(
                "Generated header is not correct for device.",
                mAgentUnderTest.isSourceAcceptable(header, pos));
    }

    @Test
    public void testTestHeaderGeneratorIsAccurate() throws IOException {
        byte[] classGeneratedHeader = generateUncorruptedHeader();
        byte[] testGeneratedHeader = generateCorruptedHeader(false, false, false);

        assertArrayEquals(
                "Difference in header generation", classGeneratedHeader, testGeneratedHeader);
    }

    @Test
    public void testNewerHeaderVersionFailsMatch() throws IOException {
        byte[] header = generateCorruptedHeader(true, false, false);

        AtomicInteger pos = new AtomicInteger(0);
        assertFalse(
                "Newer header does not fail match",
                mAgentUnderTest.isSourceAcceptable(header, pos));
    }

    @Test
    public void testWrongManufacturerFailsMatch() throws IOException {
        byte[] header = generateCorruptedHeader(false, true, false);

        AtomicInteger pos = new AtomicInteger(0);
        assertFalse(
                "Wrong manufacturer does not fail match",
                mAgentUnderTest.isSourceAcceptable(header, pos));
    }

    @Test
    public void testWrongProductFailsMatch() throws IOException {
        byte[] header = generateCorruptedHeader(false, false, true);

        AtomicInteger pos = new AtomicInteger(0);
        assertFalse(
                "Wrong product does not fail match",
                mAgentUnderTest.isSourceAcceptable(header, pos));
    }

    @Test
    public void checkAcceptTestFailingBlockRestore() {
        mAgentUnderTest.setForcedDeviceInfoRestoreAcceptability(false);
        byte[] data = new byte[0];

        assertFalse(
                "Blocking isSourceAcceptable did not stop restore",
                mAgentUnderTest.restoreDeviceSpecificConfig(data));
    }

    private byte[] generateUncorruptedHeader() throws IOException {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            mAgentUnderTest.writeHeader(os);
            return os.toByteArray();
        }
    }

    private byte[] generateCorruptedHeader(
            boolean corruptVersion, boolean corruptManufacturer, boolean corruptProduct)
            throws IOException {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            int version = SettingsBackupAgent.DEVICE_SPECIFIC_VERSION;
            if (corruptVersion) {
                version++;
            }
            os.write(SettingsBackupAgent.toByteArray(version));

            String manufacturer = Build.MANUFACTURER;
            if (corruptManufacturer) {
                manufacturer = manufacturer == null ? "X" : manufacturer + "X";
            }
            os.write(SettingsBackupAgent.toByteArray(manufacturer));

            String product = Build.PRODUCT;
            if (corruptProduct) {
                product = product == null ? "X" : product + "X";
            }
            os.write(SettingsBackupAgent.toByteArray(product));

            return os.toByteArray();
        }
    }

    private static class TestFriendlySettingsBackupAgent extends SettingsBackupAgent {
        private Boolean mForcedDeviceInfoRestoreAcceptability = null;

        void setForcedDeviceInfoRestoreAcceptability(boolean value) {
            mForcedDeviceInfoRestoreAcceptability = value;
        }

        @VisibleForTesting
        boolean isSourceAcceptable(byte[] data, AtomicInteger pos) {
            return mForcedDeviceInfoRestoreAcceptability == null
                    ? super.isSourceAcceptable(data, pos)
                    : mForcedDeviceInfoRestoreAcceptability;
        }
    }

    /** The TestSettingsHelper tracks which values have been backed up and/or restored. */
    private static class TestSettingsHelper extends SettingsHelper {
        private Set<String> mReadEntries;
        private Map<String, String> mWrittenValues;

        TestSettingsHelper(Context context) {
            super(context);
            mReadEntries = new HashSet<>();
            mWrittenValues = new HashMap<>();
        }

        @Override
        public String onBackupValue(String key, String value) {
            mReadEntries.add(key);
            String readValue = TEST_VALUES.get(key);
            assert readValue != null;
            return readValue;
        }

        @Override
        public void restoreValue(
                Context context,
                ContentResolver cr,
                ContentValues contentValues,
                Uri destination,
                String name,
                String value,
                int restoredFromSdkInt) {
            mWrittenValues.put(name, value);
        }
    }

    /**
     * ContextWrapper which allows us to return a MockContentResolver to code which uses it to
     * access settings. This allows us to override the ContentProvider for the Settings URIs to
     * return known values.
     */
    private static class ContextWithMockContentResolver extends ContextWrapper {
        private MockContentResolver mContentResolver;

        ContextWithMockContentResolver(Context targetContext) {
            super(targetContext);

            mContentResolver = new MockContentResolver();
            mContentResolver.addProvider(
                    Settings.AUTHORITY, new DeviceSpecificInfoMockContentProvider());
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }
    }

    /** ContentProvider which returns a set of known test values. */
    private static class DeviceSpecificInfoMockContentProvider extends MockContentProvider {
        private static final Object[][] RESULT_ROWS = {
            {Settings.Secure.DISPLAY_DENSITY_FORCED, TEST_DISPLAY_DENSITY_FORCED},
        };

        @Override
        public Cursor query(
                Uri uri,
                String[] projection,
                String selection,
                String[] selectionArgs,
                String sortOrder) {
            MatrixCursor result = new MatrixCursor(SettingsBackupAgent.PROJECTION);
            for (Object[] resultRow : RESULT_ROWS) {
                result.addRow(resultRow);
            }
            return result;
        }
    }
}
