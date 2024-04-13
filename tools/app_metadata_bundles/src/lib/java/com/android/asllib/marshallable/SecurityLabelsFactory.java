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

package com.android.asllib.marshallable;

import com.android.asllib.util.AslgenUtil;
import com.android.asllib.util.MalformedXmlException;
import com.android.asllib.util.XmlUtils;

import org.w3c.dom.Element;

import java.util.List;

public class SecurityLabelsFactory implements AslMarshallableFactory<SecurityLabels> {

    /** Creates a {@link SecurityLabels} from the human-readable DOM element. */
    @Override
    public SecurityLabels createFromHrElements(List<Element> elements)
            throws MalformedXmlException {
        Element ele = XmlUtils.getSingleElement(elements);
        if (ele == null) {
            AslgenUtil.logI("No SecurityLabels found in hr format.");
            return null;
        }
        Boolean isDataDeletable =
                XmlUtils.getBoolAttr(ele, XmlUtils.HR_ATTR_IS_DATA_DELETABLE, false);
        Boolean isDataEncrypted =
                XmlUtils.getBoolAttr(ele, XmlUtils.HR_ATTR_IS_DATA_ENCRYPTED, false);
        return new SecurityLabels(isDataDeletable, isDataEncrypted);
    }

    /** Creates an {@link AslMarshallableFactory} from on-device DOM elements */
    @Override
    public SecurityLabels createFromOdElements(List<Element> elements)
            throws MalformedXmlException {
        Element ele = XmlUtils.getSingleElement(elements);
        if (ele == null) {
            AslgenUtil.logI("No SecurityLabels found in od format.");
            return null;
        }
        Boolean isDataDeletable =
                XmlUtils.getOdBoolEle(ele, XmlUtils.OD_NAME_IS_DATA_DELETABLE, false);
        Boolean isDataEncrypted =
                XmlUtils.getOdBoolEle(ele, XmlUtils.OD_NAME_IS_DATA_ENCRYPTED, false);
        return new SecurityLabels(isDataDeletable, isDataEncrypted);
    }
}
