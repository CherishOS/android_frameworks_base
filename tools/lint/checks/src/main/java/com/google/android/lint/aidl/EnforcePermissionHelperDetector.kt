/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.google.android.lint.aidl

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiElement
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UDeclarationsExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod

class EnforcePermissionHelperDetector : Detector(), SourceCodeScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement?>> =
            listOf(UMethod::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = AidlStubHandler(context)

    private inner class AidlStubHandler(val context: JavaContext) : UElementHandler() {
        override fun visitMethod(node: UMethod) {
            if (!node.hasAnnotation(ANNOTATION_ENFORCE_PERMISSION)) return

            val targetExpression = "super.${node.name}$HELPER_SUFFIX()"

            val body = node.uastBody as? UBlockExpression
            if (body == null) {
                context.report(
                        ISSUE_ENFORCE_PERMISSION_HELPER,
                        context.getLocation(node),
                        "Method must start with $targetExpression",
                )
                return
            }

            val firstExpression = body.expressions.firstOrNull()
            if (firstExpression == null) {
                context.report(
                    ISSUE_ENFORCE_PERMISSION_HELPER,
                    context.getLocation(node),
                    "Method must start with $targetExpression",
                )
                return
            }

            val firstExpressionSource = firstExpression.asSourceString()
                    .filterNot(Char::isWhitespace)

            if (firstExpressionSource != targetExpression) {
                val locationTarget = getLocationTarget(firstExpression)
                val expressionLocation = context.getLocation(locationTarget)
                val indent = " ".repeat(expressionLocation.start?.column ?: 0)

                val fix = fix()
                    .replace()
                    .range(expressionLocation)
                    .beginning()
                    .with("$targetExpression;\n\n$indent")
                    .reformat(true)
                    .autoFix()
                    .build()

                context.report(
                    ISSUE_ENFORCE_PERMISSION_HELPER,
                    context.getLocation(node),
                    "Method must start with $targetExpression",
                    fix
                )
            }
        }
    }

    companion object {
        private const val HELPER_SUFFIX = "_enforcePermission"

        private const val EXPLANATION = """
            When @EnforcePermission is applied, the AIDL compiler generates a Stub method to do the
            permission check called yourMethodName$HELPER_SUFFIX.

            You must call this method as the first expression in your implementation.
            """

        val ISSUE_ENFORCE_PERMISSION_HELPER: Issue = Issue.create(
                id = "MissingEnforcePermissionHelper",
                briefDescription = """Missing permission-enforcing method call in AIDL method 
                    |annotated with @EnforcePermission""".trimMargin(),
                explanation = EXPLANATION,
                category = Category.SECURITY,
                priority = 6,
                severity = Severity.ERROR,
                implementation = Implementation(
                        EnforcePermissionHelperDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                )
        )

        /**
         * handles an edge case with UDeclarationsExpression, where sourcePsi is null,
         * resulting in an incorrect Location if used directly
         */
        private fun getLocationTarget(firstExpression: UExpression): PsiElement? {
            if (firstExpression.sourcePsi != null) return firstExpression.sourcePsi
            if (firstExpression is UDeclarationsExpression) {
                return firstExpression.declarations.firstOrNull()?.sourcePsi
            }
            return null
        }
    }
}
