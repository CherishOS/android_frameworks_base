/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.asllib;

import com.android.asllib.util.AslgenUtil;
import com.android.asllib.util.MalformedXmlException;

import org.w3c.dom.Element;

import java.util.List;

public class SafetyLabelsFactory implements AslMarshallableFactory<SafetyLabels> {

    /** Creates a {@link SafetyLabels} from the human-readable DOM element. */
    @Override
    public SafetyLabels createFromHrElements(List<Element> elements) throws MalformedXmlException {
        Element safetyLabelsEle = XmlUtils.getSingleElement(elements);
        if (safetyLabelsEle == null) {
            AslgenUtil.logI("No SafetyLabels found in hr format.");
            return null;
        }
        long version = XmlUtils.tryGetVersion(safetyLabelsEle);

        DataLabels dataLabels =
                new DataLabelsFactory()
                        .createFromHrElements(
                                XmlUtils.listOf(
                                        XmlUtils.getSingleChildElement(
                                                safetyLabelsEle, XmlUtils.HR_TAG_DATA_LABELS)));
        return new SafetyLabels(version, dataLabels);
    }
}
