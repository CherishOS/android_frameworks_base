/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.provider.DeviceConfig.OnPropertiesChangedListener;
import static android.provider.DeviceConfig.OnPropertyChangedListener;

import static com.google.common.truth.Truth.assertThat;

import android.app.ActivityThread;
import android.content.ContentResolver;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Tests that ensure appropriate settings are backed up. */
@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class DeviceConfigTest {
    // TODO(b/109919982): Migrate tests to CTS
    private static final String sNamespace = "namespace1";
    private static final String sKey = "key1";
    private static final String sValue = "value1";
    private static final long WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS = 2000; // 2 sec

    @After
    public void cleanUp() {
        deleteViaContentProvider(sNamespace, sKey);
    }

    @Test
    public void getProperty_empty() {
        String result = DeviceConfig.getProperty(sNamespace, sKey);
        assertThat(result).isNull();
    }

    @Test
    public void getProperty_nullNamespace() {
        try {
            DeviceConfig.getProperty(null, sKey);
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getProperty_nullName() {
        try {
            DeviceConfig.getProperty(sNamespace, null);
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getString_empty() {
        final String default_value = "default_value";
        final String result = DeviceConfig.getString(sNamespace, sKey, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getString_nullDefault() {
        final String result = DeviceConfig.getString(sNamespace, sKey, null);
        assertThat(result).isNull();
    }

    @Test
    public void getString_nonEmpty() {
        final String value = "new_value";
        final String default_value = "default";
        DeviceConfig.setProperty(sNamespace, sKey, value, false);

        final String result = DeviceConfig.getString(sNamespace, sKey, default_value);
        assertThat(result).isEqualTo(value);
    }

    @Test
    public void getString_nullNamespace() {
        try {
            DeviceConfig.getString(null, sKey, "default_value");
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getString_nullName() {
        try {
            DeviceConfig.getString(sNamespace, null, "default_value");
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getBoolean_empty() {
        final boolean default_value = true;
        final boolean result = DeviceConfig.getBoolean(sNamespace, sKey, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getBoolean_valid() {
        final boolean value = true;
        final boolean default_value = false;
        DeviceConfig.setProperty(sNamespace, sKey, String.valueOf(value), false);

        final boolean result = DeviceConfig.getBoolean(sNamespace, sKey, default_value);
        assertThat(result).isEqualTo(value);
    }

    @Test
    public void getBoolean_invalid() {
        final boolean default_value = true;
        DeviceConfig.setProperty(sNamespace, sKey, "not_a_boolean", false);

        final boolean result = DeviceConfig.getBoolean(sNamespace, sKey, default_value);
        // Anything non-null other than case insensitive "true" parses to false.
        assertThat(result).isFalse();
    }

    @Test
    public void getBoolean_nullNamespace() {
        try {
            DeviceConfig.getBoolean(null, sKey, false);
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getBoolean_nullName() {
        try {
            DeviceConfig.getBoolean(sNamespace, null, false);
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getInt_empty() {
        final int default_value = 999;
        final int result = DeviceConfig.getInt(sNamespace, sKey, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getInt_valid() {
        final int value = 123;
        final int default_value = 999;
        DeviceConfig.setProperty(sNamespace, sKey, String.valueOf(value), false);

        final int result = DeviceConfig.getInt(sNamespace, sKey, default_value);
        assertThat(result).isEqualTo(value);
    }

    @Test
    public void getInt_invalid() {
        final int default_value = 999;
        DeviceConfig.setProperty(sNamespace, sKey, "not_an_int", false);

        final int result = DeviceConfig.getInt(sNamespace, sKey, default_value);
        // Failure to parse results in using the default value
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getInt_nullNamespace() {
        try {
            DeviceConfig.getInt(null, sKey, 0);
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getInt_nullName() {
        try {
            DeviceConfig.getInt(sNamespace, null, 0);
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getLong_empty() {
        final long default_value = 123456;
        final long result = DeviceConfig.getLong(sNamespace, sKey, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getLong_valid() {
        final long value = 456789;
        final long default_value = 123456;
        DeviceConfig.setProperty(sNamespace, sKey, String.valueOf(value), false);

        final long result = DeviceConfig.getLong(sNamespace, sKey, default_value);
        assertThat(result).isEqualTo(value);
    }

    @Test
    public void getLong_invalid() {
        final long default_value = 123456;
        DeviceConfig.setProperty(sNamespace, sKey, "not_a_long", false);

        final long result = DeviceConfig.getLong(sNamespace, sKey, default_value);
        // Failure to parse results in using the default value
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getLong_nullNamespace() {
        try {
            DeviceConfig.getLong(null, sKey, 0);
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getLong_nullName() {
        try {
            DeviceConfig.getLong(sNamespace, null, 0);
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getFloat_empty() {
        final float default_value = 123.456f;
        final float result = DeviceConfig.getFloat(sNamespace, sKey, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getFloat_valid() {
        final float value = 456.789f;
        final float default_value = 123.456f;
        DeviceConfig.setProperty(sNamespace, sKey, String.valueOf(value), false);

        final float result = DeviceConfig.getFloat(sNamespace, sKey, default_value);
        assertThat(result).isEqualTo(value);
    }

    @Test
    public void getFloat_invalid() {
        final float default_value = 123.456f;
        DeviceConfig.setProperty(sNamespace, sKey, "not_a_float", false);

        final float result = DeviceConfig.getFloat(sNamespace, sKey, default_value);
        // Failure to parse results in using the default value
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getFloat_nullNamespace() {
        try {
            DeviceConfig.getFloat(null, sKey, 0);
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getFloat_nullName() {
        try {
            DeviceConfig.getFloat(sNamespace, null, 0);
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void setProperty_nullNamespace() {
        try {
            DeviceConfig.setProperty(null, sKey, sValue, false);
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void setProperty_nullName() {
        try {
            DeviceConfig.setProperty(sNamespace, null, sValue, false);
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void setAndGetProperty_sameNamespace() {
        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        String result = DeviceConfig.getProperty(sNamespace, sKey);
        assertThat(result).isEqualTo(sValue);
    }

    @Test
    public void setAndGetProperty_differentNamespace() {
        String newNamespace = "namespace2";
        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        String result = DeviceConfig.getProperty(newNamespace, sKey);
        assertThat(result).isNull();
    }

    @Test
    public void setAndGetProperty_multipleNamespaces() {
        String newNamespace = "namespace2";
        String newValue = "value2";
        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        DeviceConfig.setProperty(newNamespace, sKey, newValue, false);
        String result = DeviceConfig.getProperty(sNamespace, sKey);
        assertThat(result).isEqualTo(sValue);
        result = DeviceConfig.getProperty(newNamespace, sKey);
        assertThat(result).isEqualTo(newValue);

        // clean up
        deleteViaContentProvider(newNamespace, sKey);
    }

    @Test
    public void setAndGetProperty_overrideValue() {
        String newValue = "value2";
        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        DeviceConfig.setProperty(sNamespace, sKey, newValue, false);
        String result = DeviceConfig.getProperty(sNamespace, sKey);
        assertThat(result).isEqualTo(newValue);
    }

    @Test
    public void testOnPropertiesChangedListener() throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        OnPropertiesChangedListener changeListener = (properties) -> {
            assertThat(properties.getNamespace()).isEqualTo(sNamespace);
            assertThat(properties.getKeyset()).contains(sKey);
            assertThat(properties.getString(sKey, "default_value")).isEqualTo(sValue);
            countDownLatch.countDown();
        };

        try {
            DeviceConfig.addOnPropertiesChangedListener(sNamespace,
                    ActivityThread.currentApplication().getMainExecutor(), changeListener);
            DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
            assertThat(countDownLatch.await(
                    WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
        } catch (InterruptedException e) {
            Assert.fail(e.getMessage());
        } finally {
            DeviceConfig.removeOnPropertiesChangedListener(changeListener);
        }
    }

    @Test
    public void testOnPropertyChangedListener() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);

        OnPropertyChangedListener changeListener = (namespace, name, value) -> {
            assertThat(namespace).isEqualTo(sNamespace);
            assertThat(name).isEqualTo(sKey);
            assertThat(value).isEqualTo(sValue);
            countDownLatch.countDown();
        };

        try {
            DeviceConfig.addOnPropertyChangedListener(sNamespace,
                    ActivityThread.currentApplication().getMainExecutor(), changeListener);
            DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
            assertThat(countDownLatch.await(
                    WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
        } catch (InterruptedException e) {
            Assert.fail(e.getMessage());
        } finally {
            DeviceConfig.removeOnPropertyChangedListener(changeListener);
        }

    }

    private static boolean deleteViaContentProvider(String namespace, String key) {
        ContentResolver resolver = InstrumentationRegistry.getContext().getContentResolver();
        String compositeName = namespace + "/" + key;
        Bundle result = resolver.call(
                DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_DELETE_CONFIG, compositeName, null);
        assertThat(result).isNotNull();
        return compositeName.equals(result.getString(Settings.NameValueTable.VALUE));
    }

}
