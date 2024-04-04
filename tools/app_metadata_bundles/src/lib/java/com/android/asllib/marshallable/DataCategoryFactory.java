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

import com.android.asllib.util.DataTypeConstants;
import com.android.asllib.util.MalformedXmlException;
import com.android.asllib.util.XmlUtils;

import org.w3c.dom.Element;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DataCategoryFactory implements AslMarshallableFactory<DataCategory> {
    @Override
    public DataCategory createFromHrElements(List<Element> elements) throws MalformedXmlException {
        String categoryName = null;
        Map<String, DataType> dataTypeMap = new LinkedHashMap<String, DataType>();
        for (Element ele : elements) {
            categoryName = XmlUtils.getStringAttr(ele, XmlUtils.HR_ATTR_DATA_CATEGORY, true);
            String dataTypeName = XmlUtils.getStringAttr(ele, XmlUtils.HR_ATTR_DATA_TYPE, true);
            if (!DataTypeConstants.getValidDataTypes().containsKey(categoryName)) {
                throw new MalformedXmlException(
                        String.format("Unrecognized data category %s", categoryName));
            }
            if (!DataTypeConstants.getValidDataTypes().get(categoryName).contains(dataTypeName)) {
                throw new MalformedXmlException(
                        String.format(
                                "Unrecognized data type name %s for category %s",
                                dataTypeName, categoryName));
            }
            dataTypeMap.put(
                    dataTypeName, new DataTypeFactory().createFromHrElements(XmlUtils.listOf(ele)));
        }

        return new DataCategory(categoryName, dataTypeMap);
    }
}
