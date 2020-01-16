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

package com.android.server.integrity.parser;

import static com.android.server.integrity.model.ComponentBitSize.ATOMIC_FORMULA_START;
import static com.android.server.integrity.model.ComponentBitSize.BYTE_BITS;
import static com.android.server.integrity.model.ComponentBitSize.COMPOUND_FORMULA_END;
import static com.android.server.integrity.model.ComponentBitSize.COMPOUND_FORMULA_START;
import static com.android.server.integrity.model.ComponentBitSize.CONNECTOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.EFFECT_BITS;
import static com.android.server.integrity.model.ComponentBitSize.FORMAT_VERSION_BITS;
import static com.android.server.integrity.model.ComponentBitSize.IS_HASHED_BITS;
import static com.android.server.integrity.model.ComponentBitSize.KEY_BITS;
import static com.android.server.integrity.model.ComponentBitSize.OPERATOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.SEPARATOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.SIGNAL_BIT;
import static com.android.server.integrity.model.ComponentBitSize.VALUE_SIZE_BITS;
import static com.android.server.integrity.parser.BinaryFileOperations.getBooleanValue;
import static com.android.server.integrity.parser.BinaryFileOperations.getIntValue;
import static com.android.server.integrity.parser.BinaryFileOperations.getStringValue;

import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Formula;
import android.content.integrity.Rule;

import com.android.server.integrity.model.BitInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A helper class to parse rules into the {@link Rule} model from Binary representation. */
public class RuleBinaryParser implements RuleParser {

    @Override
    public List<Rule> parse(byte[] ruleBytes) throws RuleParseException {
        return parse(RandomAccessObject.ofBytes(ruleBytes), Collections.emptyList());
    }

    @Override
    public List<Rule> parse(RandomAccessObject randomAccessObject, List<RuleIndexRange> indexRanges)
            throws RuleParseException {
        try (RandomAccessInputStream randomAccessInputStream =
                new RandomAccessInputStream(randomAccessObject)) {
            return parseRules(randomAccessInputStream, indexRanges);
        } catch (Exception e) {
            throw new RuleParseException(e.getMessage(), e);
        }
    }

    private List<Rule> parseRules(
            RandomAccessInputStream randomAccessInputStream,
            List<RuleIndexRange> indexRanges)
            throws IOException {

        // Read the rule binary file format version.
        randomAccessInputStream.skip(FORMAT_VERSION_BITS / BYTE_BITS);

        return indexRanges.isEmpty()
                ? parseAllRules(randomAccessInputStream)
                : parseIndexedRules(randomAccessInputStream, indexRanges);
    }

    private List<Rule> parseAllRules(RandomAccessInputStream randomAccessInputStream)
            throws IOException {
        List<Rule> parsedRules = new ArrayList<>();

        BitInputStream inputStream =
                new BitInputStream(new BufferedInputStream(randomAccessInputStream));
        while (inputStream.hasNext()) {
            if (inputStream.getNext(SIGNAL_BIT) == 1) {
                parsedRules.add(parseRule(inputStream));
            }
        }

        return parsedRules;
    }

    private List<Rule> parseIndexedRules(
            RandomAccessInputStream randomAccessInputStream,
            List<RuleIndexRange> indexRanges)
            throws IOException {
        List<Rule> parsedRules = new ArrayList<>();

        for (RuleIndexRange range : indexRanges) {
            randomAccessInputStream.seek(range.getStartIndex());

            BitInputStream inputStream =
                    new BitInputStream(
                            new BufferedInputStream(
                                    new LimitInputStream(
                                            randomAccessInputStream,
                                            range.getEndIndex() - range.getStartIndex())));

            // Read the rules until we reach the end index. available() here is not reliable.
            while (inputStream.hasNext()) {
                if (inputStream.getNext(SIGNAL_BIT) == 1) {
                    parsedRules.add(parseRule(inputStream));
                }
            }
        }

        return parsedRules;
    }

    private Rule parseRule(BitInputStream bitInputStream) throws IOException {
        Formula formula = parseFormula(bitInputStream);
        int effect = bitInputStream.getNext(EFFECT_BITS);

        if (bitInputStream.getNext(SIGNAL_BIT) != 1) {
            throw new IllegalArgumentException("A rule must end with a '1' bit.");
        }

        return new Rule(formula, effect);
    }

    private Formula parseFormula(BitInputStream bitInputStream) throws IOException {
        int separator = bitInputStream.getNext(SEPARATOR_BITS);
        switch (separator) {
            case ATOMIC_FORMULA_START:
                return parseAtomicFormula(bitInputStream);
            case COMPOUND_FORMULA_START:
                return parseCompoundFormula(bitInputStream);
            case COMPOUND_FORMULA_END:
                return null;
            default:
                throw new IllegalArgumentException(
                        String.format("Unknown formula separator: %s", separator));
        }
    }

    private CompoundFormula parseCompoundFormula(BitInputStream bitInputStream) throws IOException {
        int connector = bitInputStream.getNext(CONNECTOR_BITS);
        List<Formula> formulas = new ArrayList<>();

        Formula parsedFormula = parseFormula(bitInputStream);
        while (parsedFormula != null) {
            formulas.add(parsedFormula);
            parsedFormula = parseFormula(bitInputStream);
        }

        return new CompoundFormula(connector, formulas);
    }

    private AtomicFormula parseAtomicFormula(BitInputStream bitInputStream) throws IOException {
        int key = bitInputStream.getNext(KEY_BITS);
        int operator = bitInputStream.getNext(OPERATOR_BITS);

        switch (key) {
            case AtomicFormula.PACKAGE_NAME:
            case AtomicFormula.APP_CERTIFICATE:
            case AtomicFormula.INSTALLER_NAME:
            case AtomicFormula.INSTALLER_CERTIFICATE:
                boolean isHashedValue = bitInputStream.getNext(IS_HASHED_BITS) == 1;
                int valueSize = bitInputStream.getNext(VALUE_SIZE_BITS);
                String stringValue = getStringValue(bitInputStream, valueSize, isHashedValue);
                return new AtomicFormula.StringAtomicFormula(key, stringValue, isHashedValue);
            case AtomicFormula.VERSION_CODE:
                int intValue = getIntValue(bitInputStream);
                return new AtomicFormula.IntAtomicFormula(key, operator, intValue);
            case AtomicFormula.PRE_INSTALLED:
                boolean booleanValue = getBooleanValue(bitInputStream);
                return new AtomicFormula.BooleanAtomicFormula(key, booleanValue);
            default:
                throw new IllegalArgumentException(String.format("Unknown key: %d", key));
        }
    }
}
