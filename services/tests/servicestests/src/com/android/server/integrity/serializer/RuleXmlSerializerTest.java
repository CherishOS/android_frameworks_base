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

package com.android.server.integrity.serializer;

import static com.android.server.testutils.TestUtils.assertExpectException;

import static org.junit.Assert.assertEquals;

import android.content.integrity.AppInstallMetadata;
import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Formula;
import android.content.integrity.Rule;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RunWith(JUnit4.class)
public class RuleXmlSerializerTest {

    @Test
    public void testXmlString_serializeEmptyRule() throws Exception {
        Rule rule = null;
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        String expectedRules = "<RL />";

        String actualRules =
                xmlSerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, actualRules);
    }

    @Test
    public void testXmlString_serializeMultipleRules_oneEmpty() throws Exception {
        Rule rule1 = null;
        Rule rule2 =
                new Rule(
                        new AtomicFormula.StringAtomicFormula(
                                AtomicFormula.PACKAGE_NAME,
                                "com.app.test",
                                /* isHashedValue= */ false),
                        Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        Map<String, String> packageNameAttrs = new LinkedHashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        packageNameAttrs.put("H", "false");
        String expectedRules =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";

        String actualRules =
                xmlSerializer.serialize(
                        Arrays.asList(rule1, rule2), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, actualRules);
    }

    @Test
    public void testXmlStream_serializeValidOpenFormula() throws Exception {
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Collections.singletonList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                "com.app.test",
                                                /* isHashedValue= */ false))),
                        Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        OutputStream outputStream = new ByteArrayOutputStream();
        Map<String, String> packageNameAttrs = new LinkedHashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        packageNameAttrs.put("H", "false");
        String expectedRules =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.NOT)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";

        xmlSerializer.serialize(
                Collections.singletonList(rule),
                /* formatVersion= */ Optional.empty(),
                outputStream);

        String actualRules = outputStream.toString();
        assertEquals(expectedRules, actualRules);
    }

    @Test
    public void testXmlString_serializeValidOpenFormula_notConnector() throws Exception {
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Collections.singletonList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                "com.app.test",
                                                /* isHashedValue= */ false))),
                        Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        Map<String, String> packageNameAttrs = new LinkedHashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        packageNameAttrs.put("H", "false");
        String expectedRules =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.NOT)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";

        String actualRules =
                xmlSerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, actualRules);
    }

    @Test
    public void testXmlString_serializeValidOpenFormula_andConnector() throws Exception {
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.AND,
                                Arrays.asList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                "com.app.test",
                                                /* isHashedValue= */ false),
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.APP_CERTIFICATE,
                                                "test_cert",
                                                /* isHashedValue= */ false))),
                        Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        Map<String, String> packageNameAttrs = new LinkedHashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        packageNameAttrs.put("H", "false");
        Map<String, String> appCertificateAttrs = new LinkedHashMap<>();
        appCertificateAttrs.put("K", String.valueOf(AtomicFormula.APP_CERTIFICATE));
        appCertificateAttrs.put("V", "test_cert");
        appCertificateAttrs.put("H", "false");
        String expectedRules =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.AND)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", appCertificateAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";

        String actualRules =
                xmlSerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, actualRules);
    }

    @Test
    public void testXmlString_serializeValidOpenFormula_orConnector() throws Exception {
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.OR,
                                Arrays.asList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                "com.app.test",
                                                /* isHashedValue= */ false),
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.APP_CERTIFICATE,
                                                "test_cert",
                                                /* isHashedValue= */ false))),
                        Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        Map<String, String> packageNameAttrs = new LinkedHashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        packageNameAttrs.put("H", "false");
        Map<String, String> appCertificateAttrs = new LinkedHashMap<>();
        appCertificateAttrs.put("K", String.valueOf(AtomicFormula.APP_CERTIFICATE));
        appCertificateAttrs.put("V", "test_cert");
        appCertificateAttrs.put("H", "false");
        String expectedRules =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.OR)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", appCertificateAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";

        String actualRules =
                xmlSerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, actualRules);
    }

    @Test
    public void testXmlString_serializeValidAtomicFormula_stringValue() throws Exception {
        Rule rule =
                new Rule(
                        new AtomicFormula.StringAtomicFormula(
                                AtomicFormula.PACKAGE_NAME,
                                "com.app.test",
                                /* isHashedValue= */ false),
                        Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        Map<String, String> packageNameAttrs = new LinkedHashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        packageNameAttrs.put("H", "false");
        String expectedRules =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";

        String actualRules =
                xmlSerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, actualRules);
    }

    @Test
    public void testXmlString_serializeValidAtomicFormula_integerValue() throws Exception {
        Rule rule =
                new Rule(
                        new AtomicFormula.IntAtomicFormula(
                                AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 1),
                        Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        Map<String, String> versionCodeAttrs = new LinkedHashMap<>();
        versionCodeAttrs.put("K", String.valueOf(AtomicFormula.VERSION_CODE));
        versionCodeAttrs.put("O", String.valueOf(AtomicFormula.EQ));
        versionCodeAttrs.put("V", "1");
        String expectedRules =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", versionCodeAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";

        String actualRules =
                xmlSerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, actualRules);
    }

    @Test
    public void testXmlString_serializeValidAtomicFormula_booleanValue() throws Exception {
        Rule rule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        Map<String, String> preInstalledAttrs = new LinkedHashMap<>();
        preInstalledAttrs.put("K", String.valueOf(AtomicFormula.PRE_INSTALLED));
        preInstalledAttrs.put("V", "true");
        String expectedRules =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", preInstalledAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";

        String actualRules =
                xmlSerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, actualRules);
    }

    @Test
    public void testXmlString_serializeInvalidFormulaType() throws Exception {
        Formula invalidFormula = getInvalidFormula();
        Rule rule = new Rule(invalidFormula, Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();

        assertExpectException(
                RuleSerializeException.class,
                /* expectedExceptionMessageRegex */ "Invalid formula type",
                () ->
                        xmlSerializer.serialize(
                                Collections.singletonList(rule),
                                /* formatVersion= */ Optional.empty()));
    }

    private String generateTagWithAttribute(
            String tag, Map<String, String> attributeValues, boolean closed) {
        StringBuilder res = new StringBuilder("<");
        res.append(tag);
        for (String attribute : attributeValues.keySet()) {
            res.append(" ");
            res.append(attribute);
            res.append("=\"");
            res.append(attributeValues.get(attribute));
            res.append("\"");
        }
        res.append(closed ? " />" : ">");
        return res.toString();
    }

    private Formula getInvalidFormula() {
        return new Formula() {
            @Override
            public boolean isSatisfied(AppInstallMetadata appInstallMetadata) {
                return false;
            }

            @Override
            public int getTag() {
                return 0;
            }

            @Override
            public int hashCode() {
                return super.hashCode();
            }

            @Override
            public boolean equals(Object obj) {
                return super.equals(obj);
            }

            @NonNull
            @Override
            protected Object clone() throws CloneNotSupportedException {
                return super.clone();
            }

            @Override
            public String toString() {
                return super.toString();
            }

            @Override
            protected void finalize() throws Throwable {
                super.finalize();
            }
        };
    }
}
