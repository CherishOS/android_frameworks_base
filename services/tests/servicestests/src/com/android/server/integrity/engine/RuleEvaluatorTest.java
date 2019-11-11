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

package com.android.server.integrity.engine;

import static com.android.server.integrity.model.IntegrityCheckResult.Effect.ALLOW;
import static com.android.server.integrity.model.IntegrityCheckResult.Effect.DENY;

import static org.junit.Assert.assertEquals;

import com.android.server.integrity.model.AppInstallMetadata;
import com.android.server.integrity.model.AtomicFormula;
import com.android.server.integrity.model.AtomicFormula.StringAtomicFormula;
import com.android.server.integrity.model.IntegrityCheckResult;
import com.android.server.integrity.model.OpenFormula;
import com.android.server.integrity.model.Rule;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(JUnit4.class)
public class RuleEvaluatorTest {

    private static final String PACKAGE_NAME_1 = "com.test.app";
    private static final String PACKAGE_NAME_2 = "com.test.app2";
    private static final String APP_CERTIFICATE = "test_cert";
    private static final AppInstallMetadata APP_INSTALL_METADATA =
            new AppInstallMetadata.Builder()
                    .setPackageName(PACKAGE_NAME_1)
                    .setAppCertificate(APP_CERTIFICATE)
                    .setVersionCode(2)
                    .build();

    @Test
    public void testEvaluateRules_noRules_allow() {
        List<Rule> rules = new ArrayList<>();

        IntegrityCheckResult result = RuleEvaluator.evaluateRules(rules, APP_INSTALL_METADATA);

        assertEquals(ALLOW, result.getEffect());
    }

    @Test
    public void testEvaluateRules_noMatchedRules_allow() {
        Rule rule1 =
                new Rule(
                        new StringAtomicFormula(AtomicFormula.PACKAGE_NAME, PACKAGE_NAME_2),
                        Rule.DENY);

        IntegrityCheckResult result =
                RuleEvaluator.evaluateRules(Collections.singletonList(rule1), APP_INSTALL_METADATA);

        assertEquals(ALLOW, result.getEffect());
    }

    @Test
    public void testEvaluateRules_oneMatch_deny() {
        Rule rule1 =
                new Rule(
                        new StringAtomicFormula(AtomicFormula.PACKAGE_NAME, PACKAGE_NAME_1),
                        Rule.DENY);
        Rule rule2 =
                new Rule(
                        new StringAtomicFormula(AtomicFormula.PACKAGE_NAME, PACKAGE_NAME_2),
                        Rule.DENY);

        IntegrityCheckResult result =
                RuleEvaluator.evaluateRules(Arrays.asList(rule1, rule2), APP_INSTALL_METADATA);

        assertEquals(DENY, result.getEffect());
        assertEquals(rule1, result.getRule());
    }

    @Test
    public void testEvaluateRules_multipleMatches_deny() {
        Rule rule1 =
                new Rule(
                        new StringAtomicFormula(AtomicFormula.PACKAGE_NAME, PACKAGE_NAME_1),
                        Rule.DENY);
        OpenFormula openFormula2 =
                new OpenFormula(
                        OpenFormula.AND,
                        Arrays.asList(
                                new StringAtomicFormula(AtomicFormula.PACKAGE_NAME, PACKAGE_NAME_1),
                                new StringAtomicFormula(
                                        AtomicFormula.APP_CERTIFICATE, APP_CERTIFICATE)));
        Rule rule2 = new Rule(openFormula2, Rule.DENY);

        IntegrityCheckResult result =
                RuleEvaluator.evaluateRules(Arrays.asList(rule1, rule2), APP_INSTALL_METADATA);

        assertEquals(DENY, result.getEffect());
        assertEquals(rule1, result.getRule());
    }

    @Test
    public void testEvaluateRules_ruleWithNot_deny() {
        OpenFormula openFormula =
                new OpenFormula(
                        OpenFormula.NOT,
                        Collections.singletonList(
                                new StringAtomicFormula(
                                        AtomicFormula.PACKAGE_NAME, PACKAGE_NAME_2)));
        Rule rule = new Rule(openFormula, Rule.DENY);

        IntegrityCheckResult result =
                RuleEvaluator.evaluateRules(Collections.singletonList(rule), APP_INSTALL_METADATA);

        assertEquals(DENY, result.getEffect());
        assertEquals(rule, result.getRule());
    }

    @Test
    public void testEvaluateRules_ruleWithIntegerOperators_deny() {
        Rule rule =
                new Rule(
                        new AtomicFormula.IntAtomicFormula(
                                AtomicFormula.VERSION_CODE, AtomicFormula.GT, 1),
                        Rule.DENY);

        IntegrityCheckResult result =
                RuleEvaluator.evaluateRules(Collections.singletonList(rule), APP_INSTALL_METADATA);

        assertEquals(DENY, result.getEffect());
        assertEquals(rule, result.getRule());
    }

    @Test
    public void testEvaluateRules_validForm_deny() {
        OpenFormula openFormula =
                new OpenFormula(
                        OpenFormula.AND,
                        Arrays.asList(
                                new StringAtomicFormula(AtomicFormula.PACKAGE_NAME, PACKAGE_NAME_1),
                                new StringAtomicFormula(
                                        AtomicFormula.APP_CERTIFICATE, APP_CERTIFICATE)));
        Rule rule = new Rule(openFormula, Rule.DENY);

        IntegrityCheckResult result =
                RuleEvaluator.evaluateRules(Collections.singletonList(rule), APP_INSTALL_METADATA);

        assertEquals(DENY, result.getEffect());
        assertEquals(rule, result.getRule());
    }

    @Test
    public void testEvaluateRules_ruleNotInDNF_ignoreAndAllow() {
        OpenFormula openFormula =
                new OpenFormula(
                        OpenFormula.OR,
                        Arrays.asList(
                                new StringAtomicFormula(AtomicFormula.PACKAGE_NAME, PACKAGE_NAME_1),
                                new StringAtomicFormula(
                                        AtomicFormula.APP_CERTIFICATE, APP_CERTIFICATE)));
        Rule rule = new Rule(openFormula, Rule.DENY);

        IntegrityCheckResult result =
                RuleEvaluator.evaluateRules(Collections.singletonList(rule), APP_INSTALL_METADATA);

        assertEquals(ALLOW, result.getEffect());
    }

    @Test
    public void testEvaluateRules_openFormulaWithNot_allow() {
        OpenFormula openSubFormula =
                new OpenFormula(
                        OpenFormula.AND,
                        Arrays.asList(
                                new StringAtomicFormula(AtomicFormula.PACKAGE_NAME, PACKAGE_NAME_2),
                                new StringAtomicFormula(
                                        AtomicFormula.APP_CERTIFICATE, APP_CERTIFICATE)));
        OpenFormula openFormula =
                new OpenFormula(OpenFormula.NOT, Collections.singletonList(openSubFormula));
        Rule rule = new Rule(openFormula, Rule.DENY);

        IntegrityCheckResult result =
                RuleEvaluator.evaluateRules(Collections.singletonList(rule), APP_INSTALL_METADATA);

        assertEquals(ALLOW, result.getEffect());
    }

    @Test
    public void testEvaluateRules_forceAllow() {
        Rule rule1 =
                new Rule(
                        new StringAtomicFormula(AtomicFormula.PACKAGE_NAME, PACKAGE_NAME_1),
                        Rule.FORCE_ALLOW);
        OpenFormula openFormula2 =
                new OpenFormula(
                        OpenFormula.AND,
                        Arrays.asList(
                                new StringAtomicFormula(AtomicFormula.PACKAGE_NAME, PACKAGE_NAME_1),
                                new StringAtomicFormula(
                                        AtomicFormula.APP_CERTIFICATE, APP_CERTIFICATE)));
        Rule rule2 = new Rule(openFormula2, Rule.DENY);

        IntegrityCheckResult result =
                RuleEvaluator.evaluateRules(Arrays.asList(rule1, rule2), APP_INSTALL_METADATA);

        assertEquals(ALLOW, result.getEffect());
        assertEquals(rule1, result.getRule());
    }
}
