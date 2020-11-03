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

package com.android.server.appsearch.external.localstorage.converter;

import android.annotation.NonNull;

import android.app.appsearch.GenericDocument;
import com.android.internal.util.Preconditions;

import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.PropertyProto;
import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Translates a {@link GenericDocument} into a {@link DocumentProto}.
 * @hide
 */

public final class GenericDocumentToProtoConverter {
    private GenericDocumentToProtoConverter() {}

    /** Converts a {@link GenericDocument} into a {@link DocumentProto}. */
    @NonNull
    @SuppressWarnings("unchecked")
    public static DocumentProto convert(@NonNull GenericDocument document) {
        Preconditions.checkNotNull(document);
        DocumentProto.Builder mProtoBuilder = DocumentProto.newBuilder();
        mProtoBuilder.setUri(document.getUri())
                .setSchema(document.getSchemaType())
                .setNamespace(document.getNamespace())
                .setScore(document.getScore())
                .setTtlMs(document.getTtlMillis())
                .setCreationTimestampMs(document.getCreationTimestampMillis());
        ArrayList<String> keys = new ArrayList<>(document.getPropertyNames());
        Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            String name = keys.get(i);
            PropertyProto.Builder propertyProto = PropertyProto.newBuilder().setName(name);
            String[] stringValues = document.getPropertyStringArray(name);
            long[] longValues = document.getPropertyLongArray(name);
            double[] doubleValues = document.getPropertyDoubleArray(name);
            boolean[] booleanValues = document.getPropertyBooleanArray(name);
            byte[][] bytesValues = document.getPropertyBytesArray(name);
            GenericDocument[] documentValues = document.getPropertyDocumentArray(name);
            if (stringValues != null) {
                for (int j = 0; j < stringValues.length; j++) {
                    propertyProto.addStringValues(stringValues[j]);
                }
            } else if (longValues != null) {
                for (int j = 0; j < longValues.length; j++) {
                    propertyProto.addInt64Values(longValues[j]);
                }
            } else if (doubleValues != null) {
                for (int j = 0; j < doubleValues.length; j++) {
                    propertyProto.addDoubleValues(doubleValues[j]);
                }
            } else if (booleanValues != null) {
                for (int j = 0; j < booleanValues.length; j++) {
                    propertyProto.addBooleanValues(booleanValues[j]);
                }
            } else if (bytesValues != null) {
                for (int j = 0; j < bytesValues.length; j++) {
                    propertyProto.addBytesValues(ByteString.copyFrom(bytesValues[j]));
                }
            } else if (documentValues != null) {
                for (int j = 0; j < documentValues.length; j++) {
                    DocumentProto proto = convert(documentValues[j]);
                    propertyProto.addDocumentValues(proto);
                }
            } else {
                throw new IllegalStateException(
                        "Property \"" + name + "\" has unsupported value type");
            }
            mProtoBuilder.addProperties(propertyProto);
        }
        return mProtoBuilder.build();
    }

    /** Converts a {@link DocumentProto} into a {@link GenericDocument}. */
    @NonNull
    public static GenericDocument convert(@NonNull DocumentProto proto) {
        Preconditions.checkNotNull(proto);
        GenericDocument.Builder<?> documentBuilder =
                new GenericDocument.Builder<>(proto.getUri(), proto.getSchema())
                        .setNamespace(proto.getNamespace())
                        .setScore(proto.getScore())
                        .setTtlMillis(proto.getTtlMs())
                        .setCreationTimestampMillis(proto.getCreationTimestampMs());

        for (int i = 0; i < proto.getPropertiesCount(); i++) {
            PropertyProto property = proto.getProperties(i);
            String name = property.getName();
            if (property.getStringValuesCount() > 0) {
                String[] values = new String[property.getStringValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getStringValues(j);
                }
                documentBuilder.setPropertyString(name, values);
            } else if (property.getInt64ValuesCount() > 0) {
                long[] values = new long[property.getInt64ValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getInt64Values(j);
                }
                documentBuilder.setPropertyLong(name, values);
            } else if (property.getDoubleValuesCount() > 0) {
                double[] values = new double[property.getDoubleValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getDoubleValues(j);
                }
                documentBuilder.setPropertyDouble(name, values);
            } else if (property.getBooleanValuesCount() > 0) {
                boolean[] values = new boolean[property.getBooleanValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getBooleanValues(j);
                }
                documentBuilder.setPropertyBoolean(name, values);
            } else if (property.getBytesValuesCount() > 0) {
                byte[][] values = new byte[property.getBytesValuesCount()][];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getBytesValues(j).toByteArray();
                }
                documentBuilder.setPropertyBytes(name, values);
            } else if (property.getDocumentValuesCount() > 0) {
                GenericDocument[] values = new GenericDocument[property.getDocumentValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = convert(property.getDocumentValues(j));
                }
                documentBuilder.setPropertyDocument(name, values);
            } else {
                throw new IllegalStateException("Unknown type of value: " + name);
            }
        }
        return documentBuilder.build();
    }
}
