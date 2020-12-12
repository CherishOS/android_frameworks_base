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

package android.hardware.hdmi;

import androidx.test.filters.SmallTest;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link HdmiPortInfo} */
@RunWith(JUnit4.class)
@SmallTest
public class HdmiPortInfoTest {

    @Test
    public void testEquals() {
        int portId = 1;
        int portType = 0;
        int address = 0x123456;
        boolean isCec = true;
        boolean isMhl = false;
        boolean isArcSupported = false;

        new EqualsTester()
                .addEqualityGroup(
                        new HdmiPortInfo(portId, portType, address, isCec, isMhl, isArcSupported),
                        new HdmiPortInfo(portId, portType, address, isCec, isMhl, isArcSupported))
                .addEqualityGroup(
                        new HdmiPortInfo(
                                portId + 1, portType, address, isCec, isMhl, isArcSupported))
                .addEqualityGroup(
                        new HdmiPortInfo(
                                portId, portType + 1, address, isCec, isMhl, isArcSupported))
                .addEqualityGroup(
                        new HdmiPortInfo(
                                portId, portType, address + 1, isCec, isMhl, isArcSupported))
                .addEqualityGroup(
                        new HdmiPortInfo(portId, portType, address, !isCec, isMhl, isArcSupported))
                .addEqualityGroup(
                        new HdmiPortInfo(portId, portType, address, isCec, !isMhl, isArcSupported))
                .addEqualityGroup(
                        new HdmiPortInfo(portId, portType, address, isCec, isMhl, !isArcSupported))
                .testEquals();
    }
}
