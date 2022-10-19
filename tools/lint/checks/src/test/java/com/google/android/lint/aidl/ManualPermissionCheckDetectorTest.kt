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

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("UnstableApiUsage")
class ManualPermissionCheckDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = ManualPermissionCheckDetector()
    override fun getIssues(): List<Issue> = listOf(
        ManualPermissionCheckDetector
            .ISSUE_USE_ENFORCE_PERMISSION_ANNOTATION
    )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk()

    fun testClass() {
        lint().files(
            java(
                """
                    import android.content.Context;
                    import android.test.ITest;
                    public class Foo extends ITest.Stub {
                        private Context mContext;
                        @Override
                        public void test() throws android.os.RemoteException {
                            mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                        }
                    }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:7: Warning: ITest permission check can be converted to @EnforcePermission annotation [UseEnforcePermissionAnnotation]
                        mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 7: Annotate with @EnforcePermission:
                @@ -5 +5
                +     @android.annotation.EnforcePermission("android.permission.READ_CONTACTS")
                @@ -7 +8
                -         mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                """
            )
    }

    fun testAnonClass() {
        lint().files(
            java(
                """
                    import android.content.Context;
                    import android.test.ITest;
                    public class Foo {
                        private Context mContext;
                        private ITest itest = new ITest.Stub() {
                            @Override
                            public void test() throws android.os.RemoteException {
                                mContext.enforceCallingOrSelfPermission(
                                    "android.permission.READ_CONTACTS", "foo");
                            }
                        };
                    }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:8: Warning: ITest permission check can be converted to @EnforcePermission annotation [UseEnforcePermissionAnnotation]
                            mContext.enforceCallingOrSelfPermission(
                            ^
                0 errors, 1 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 8: Annotate with @EnforcePermission:
                @@ -6 +6
                +         @android.annotation.EnforcePermission("android.permission.READ_CONTACTS")
                @@ -8 +9
                -             mContext.enforceCallingOrSelfPermission(
                -                 "android.permission.READ_CONTACTS", "foo");
                """
            )
    }

    fun testConstantEvaluation() {
        lint().files(
            java(
                """
                    import android.content.Context;
                    import android.test.ITest;

                    public class Foo extends ITest.Stub {
                        private Context mContext;
                        @Override
                        public void test() throws android.os.RemoteException {
                            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_CONTACTS, "foo");
                        }
                    }
                """
            ).indented(),
            *stubs,
            manifestStub
        )
            .run()
            .expect(
                """
                src/Foo.java:8: Warning: ITest permission check can be converted to @EnforcePermission annotation [UseEnforcePermissionAnnotation]
                        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_CONTACTS, "foo");
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 7: Annotate with @EnforcePermission:
                @@ -6 +6
                +     @android.annotation.EnforcePermission("android.permission.READ_CONTACTS")
                @@ -8 +9
                -         mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_CONTACTS, "foo");
                """
            )
    }

    fun testAllOf() {
        lint().files(
            java(
                """
                    import android.content.Context;
                    import android.test.ITest;
                    public class Foo {
                        private Context mContext;
                        private ITest itest = new ITest.Stub() {
                            @Override
                            public void test() throws android.os.RemoteException {
                                mContext.enforceCallingOrSelfPermission(
                                    "android.permission.READ_CONTACTS", "foo");
                                mContext.enforceCallingOrSelfPermission(
                                    "android.permission.WRITE_CONTACTS", "foo");
                            }
                        };
                    }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:10: Warning: ITest permission check can be converted to @EnforcePermission annotation [UseEnforcePermissionAnnotation]
                            mContext.enforceCallingOrSelfPermission(
                            ^
                0 errors, 1 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 10: Annotate with @EnforcePermission:
                @@ -6 +6
                +         @android.annotation.EnforcePermission(allOf={"android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"})
                @@ -8 +9
                -             mContext.enforceCallingOrSelfPermission(
                -                 "android.permission.READ_CONTACTS", "foo");
                -             mContext.enforceCallingOrSelfPermission(
                -                 "android.permission.WRITE_CONTACTS", "foo");
                """
            )
    }

    fun testPrecedingExpressions() {
        lint().files(
            java(
                """
                    import android.os.Binder;
                    import android.test.ITest;
                    public class Foo extends ITest.Stub {
                        private mContext Context;
                        @Override
                        public void test() throws android.os.RemoteException {
                            long uid = Binder.getCallingUid();
                            mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                        }
                    }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expectClean()
    }

    fun testPermissionHelper() {
        lint().skipTestModes(TestMode.PARENTHESIZED).files(
            java(
                """
                    import android.content.Context;
                    import android.test.ITest;

                    public class Foo extends ITest.Stub {
                        private Context mContext;

                        @android.content.pm.PermissionMethod
                        private void helper() {
                            mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                        }

                        @Override
                        public void test() throws android.os.RemoteException {
                            helper();
                        }
                    }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:14: Warning: ITest permission check can be converted to @EnforcePermission annotation [UseEnforcePermissionAnnotation]
                        helper();
                        ~~~~~~~~~
                0 errors, 1 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 14: Annotate with @EnforcePermission:
                @@ -12 +12
                +     @android.annotation.EnforcePermission("android.permission.READ_CONTACTS")
                @@ -14 +15
                -         helper();
                """
            )
    }

    fun testPermissionHelperAllOf() {
        lint().skipTestModes(TestMode.PARENTHESIZED).files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;

                public class Foo extends ITest.Stub {
                    private Context mContext;

                    @android.content.pm.PermissionMethod
                    private void helper() {
                        mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                        mContext.enforceCallingOrSelfPermission("android.permission.WRITE_CONTACTS", "foo");
                    }

                    @Override
                    public void test() throws android.os.RemoteException {
                        helper();
                        mContext.enforceCallingOrSelfPermission("FOO", "foo");
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:16: Warning: ITest permission check can be converted to @EnforcePermission annotation [UseEnforcePermissionAnnotation]
                        mContext.enforceCallingOrSelfPermission("FOO", "foo");
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 16: Annotate with @EnforcePermission:
                @@ -13 +13
                +     @android.annotation.EnforcePermission(allOf={"android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS", "FOO"})
                @@ -15 +16
                -         helper();
                -         mContext.enforceCallingOrSelfPermission("FOO", "foo");
                """
            )
    }


    fun testPermissionHelperNested() {
        lint().skipTestModes(TestMode.PARENTHESIZED).files(
            java(
                """
                import android.content.Context;
                import android.test.ITest;

                public class Foo extends ITest.Stub {
                    private Context mContext;

                    @android.content.pm.PermissionMethod
                    private void helperHelper() {
                        helper("android.permission.WRITE_CONTACTS");
                    }

                    @android.content.pm.PermissionMethod
                    private void helper(@android.content.pm.PermissionName String extraPermission) {
                        mContext.enforceCallingOrSelfPermission("android.permission.READ_CONTACTS", "foo");
                    }

                    @Override
                    public void test() throws android.os.RemoteException {
                        helperHelper();
                    }
                }
                """
            ).indented(),
            *stubs
        )
            .run()
            .expect(
                """
                src/Foo.java:19: Warning: ITest permission check can be converted to @EnforcePermission annotation [UseEnforcePermissionAnnotation]
                        helperHelper();
                        ~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
            .expectFixDiffs(
                """
                Fix for src/Foo.java line 19: Annotate with @EnforcePermission:
                @@ -17 +17
                +     @android.annotation.EnforcePermission(allOf={"android.permission.WRITE_CONTACTS", "android.permission.READ_CONTACTS"})
                @@ -19 +20
                -         helperHelper();
                """
            )
    }



    companion object {
        private val aidlStub: TestFile = java(
            """
               package android.test;
               public interface ITest extends android.os.IInterface {
                    public static abstract class Stub extends android.os.Binder implements android.test.ITest {}
                    public void test() throws android.os.RemoteException;
               }
            """
        ).indented()

        private val contextStub: TestFile = java(
            """
                package android.content;
                public class Context {
                    @android.content.pm.PermissionMethod
                    public void enforceCallingOrSelfPermission(@android.content.pm.PermissionName String permission, String message) {}
                }
            """
        ).indented()

        private val binderStub: TestFile = java(
            """
                package android.os;
                public class Binder {
                    public static int getCallingUid() {}
                }
            """
        ).indented()

        private val permissionMethodStub: TestFile = java(
            """
                package android.content.pm;

                import static java.lang.annotation.ElementType.METHOD;
                import static java.lang.annotation.RetentionPolicy.CLASS;

                import java.lang.annotation.Retention;
                import java.lang.annotation.Target;

                @Retention(CLASS)
                @Target({METHOD})
                public @interface PermissionMethod {}
            """
        ).indented()

        private val permissionNameStub: TestFile = java(
            """
                package android.content.pm;

                import static java.lang.annotation.ElementType.FIELD;
                import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
                import static java.lang.annotation.ElementType.METHOD;
                import static java.lang.annotation.ElementType.PARAMETER;
                import static java.lang.annotation.RetentionPolicy.CLASS;

                import java.lang.annotation.Retention;
                import java.lang.annotation.Target;

                @Retention(CLASS)
                @Target({PARAMETER, METHOD, LOCAL_VARIABLE, FIELD})
                public @interface PermissionName {}
            """
        ).indented()

        private val manifestStub: TestFile = java(
            """
                package android;

                public final class Manifest {
                    public static final class permission {
                        public static final String READ_CONTACTS="android.permission.READ_CONTACTS";
                    }
                }
            """.trimIndent()
        )

        val stubs = arrayOf(
            aidlStub,
            contextStub,
            binderStub,
            permissionMethodStub,
            permissionNameStub
        )
    }
}
