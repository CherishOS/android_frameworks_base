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

import static com.android.server.integrity.serializer.RuleIndexingDetails.APP_CERTIFICATE_INDEXED;
import static com.android.server.integrity.serializer.RuleIndexingDetails.NOT_INDEXED;
import static com.android.server.integrity.serializer.RuleIndexingDetails.PACKAGE_NAME_INDEXED;

import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Formula;
import android.content.integrity.Rule;
import android.util.Xml;

import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** A helper class to serialize rules from the {@link Rule} model to Xml representation. */
public class RuleXmlSerializer implements RuleSerializer {

    public static final String TAG = "RuleXmlSerializer";
    private static final String NAMESPACE = "";

    private static final String RULE_LIST_TAG = "RL";
    private static final String RULE_TAG = "R";
    private static final String COMPOUND_FORMULA_TAG = "OF";
    private static final String ATOMIC_FORMULA_TAG = "AF";
    private static final String EFFECT_ATTRIBUTE = "E";
    private static final String KEY_ATTRIBUTE = "K";
    private static final String OPERATOR_ATTRIBUTE = "O";
    private static final String VALUE_ATTRIBUTE = "V";
    private static final String CONNECTOR_ATTRIBUTE = "C";
    private static final String IS_HASHED_VALUE_ATTRIBUTE = "H";

    @Override
    public void serialize(
            List<Rule> rules,
            Optional<Integer> formatVersion,
            OutputStream outputStream,
            OutputStream indexingOutputStream)
            throws RuleSerializeException {
        try {
            XmlSerializer xmlSerializer = Xml.newSerializer();
            xmlSerializer.setOutput(outputStream, StandardCharsets.UTF_8.name());
            serializeRules(rules, xmlSerializer);

            // TODO(b/145493956): Implement the indexing logic.
        } catch (Exception e) {
            throw new RuleSerializeException(e.getMessage(), e);
        }
    }

    @Override
    public byte[] serialize(List<Rule> rules, Optional<Integer> formatVersion)
            throws RuleSerializeException {
        try {
            XmlSerializer xmlSerializer = Xml.newSerializer();
            StringWriter writer = new StringWriter();
            xmlSerializer.setOutput(writer);
            serializeRules(rules, xmlSerializer);
            return writer.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuleSerializeException(e.getMessage(), e);
        }
    }

    private void serializeRules(List<Rule> rules, XmlSerializer xmlSerializer)
            throws RuleSerializeException {
        try {
            // Determine the indexing groups and the order of the rules within each indexed group.
            Map<Integer, Map<String, List<Rule>>> indexedRules =
                    RuleIndexingDetailsIdentifier.splitRulesIntoIndexBuckets(rules);

            // Write the XML formatted rules in order.
            xmlSerializer.startTag(NAMESPACE, RULE_LIST_TAG);

            serializeRuleList(indexedRules.get(PACKAGE_NAME_INDEXED), xmlSerializer);
            serializeRuleList(indexedRules.get(APP_CERTIFICATE_INDEXED), xmlSerializer);
            serializeRuleList(indexedRules.get(NOT_INDEXED), xmlSerializer);

            xmlSerializer.endTag(NAMESPACE, RULE_LIST_TAG);
            xmlSerializer.endDocument();
        } catch (Exception e) {
            throw new RuleSerializeException(e.getMessage(), e);
        }
    }

    private void serializeRuleList(Map<String, List<Rule>> rulesMap, XmlSerializer xmlSerializer)
            throws IOException {
        List<String> sortedKeyList =
                rulesMap.keySet().stream().sorted().collect(Collectors.toList());
        for (String key : sortedKeyList) {
            for (Rule rule : rulesMap.get(key)) {
                serializeRule(rule, xmlSerializer);
            }
        }
    }

    private void serializeRule(Rule rule, XmlSerializer xmlSerializer) throws IOException {
        if (rule == null) {
            return;
        }
        xmlSerializer.startTag(NAMESPACE, RULE_TAG);
        serializeAttributeValue(EFFECT_ATTRIBUTE, String.valueOf(rule.getEffect()), xmlSerializer);
        serializeFormula(rule.getFormula(), xmlSerializer);
        xmlSerializer.endTag(NAMESPACE, RULE_TAG);
    }

    private void serializeFormula(Formula formula, XmlSerializer xmlSerializer) throws IOException {
        if (formula instanceof AtomicFormula) {
            serializeAtomicFormula((AtomicFormula) formula, xmlSerializer);
        } else if (formula instanceof CompoundFormula) {
            serializeCompoundFormula((CompoundFormula) formula, xmlSerializer);
        } else {
            throw new IllegalArgumentException(
                    String.format("Invalid formula type: %s", formula.getClass()));
        }
    }

    private void serializeCompoundFormula(
            CompoundFormula compoundFormula, XmlSerializer xmlSerializer) throws IOException {
        if (compoundFormula == null) {
            return;
        }
        xmlSerializer.startTag(NAMESPACE, COMPOUND_FORMULA_TAG);
        serializeAttributeValue(
                CONNECTOR_ATTRIBUTE, String.valueOf(compoundFormula.getConnector()), xmlSerializer);
        for (Formula formula : compoundFormula.getFormulas()) {
            serializeFormula(formula, xmlSerializer);
        }
        xmlSerializer.endTag(NAMESPACE, COMPOUND_FORMULA_TAG);
    }

    private void serializeAtomicFormula(AtomicFormula atomicFormula, XmlSerializer xmlSerializer)
            throws IOException {
        if (atomicFormula == null) {
            return;
        }
        xmlSerializer.startTag(NAMESPACE, ATOMIC_FORMULA_TAG);
        serializeAttributeValue(
                KEY_ATTRIBUTE, String.valueOf(atomicFormula.getKey()), xmlSerializer);
        if (atomicFormula.getTag() == AtomicFormula.STRING_ATOMIC_FORMULA_TAG) {
            serializeAttributeValue(
                    VALUE_ATTRIBUTE,
                    ((AtomicFormula.StringAtomicFormula) atomicFormula).getValue(),
                    xmlSerializer);
            serializeAttributeValue(
                    IS_HASHED_VALUE_ATTRIBUTE,
                    String.valueOf(
                            ((AtomicFormula.StringAtomicFormula) atomicFormula).getIsHashedValue()),
                    xmlSerializer);
        } else if (atomicFormula.getTag() == AtomicFormula.INT_ATOMIC_FORMULA_TAG) {
            serializeAttributeValue(
                    OPERATOR_ATTRIBUTE,
                    String.valueOf(((AtomicFormula.IntAtomicFormula) atomicFormula).getOperator()),
                    xmlSerializer);
            serializeAttributeValue(
                    VALUE_ATTRIBUTE,
                    String.valueOf(((AtomicFormula.IntAtomicFormula) atomicFormula).getValue()),
                    xmlSerializer);
        } else if (atomicFormula.getTag() == AtomicFormula.BOOLEAN_ATOMIC_FORMULA_TAG) {
            serializeAttributeValue(
                    VALUE_ATTRIBUTE,
                    String.valueOf(((AtomicFormula.BooleanAtomicFormula) atomicFormula).getValue()),
                    xmlSerializer);
        } else {
            throw new IllegalArgumentException(
                    String.format("Invalid atomic formula type: %s", atomicFormula.getClass()));
        }
        xmlSerializer.endTag(NAMESPACE, ATOMIC_FORMULA_TAG);
    }

    private void serializeAttributeValue(
            String attribute, String value, XmlSerializer xmlSerializer) throws IOException {
        if (value == null) {
            return;
        }
        xmlSerializer.attribute(NAMESPACE, attribute, value);
    }
}
