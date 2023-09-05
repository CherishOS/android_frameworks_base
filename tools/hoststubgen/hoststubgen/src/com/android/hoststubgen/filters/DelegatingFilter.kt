/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.filters

/**
 * Base class for an [OutputFilter] that uses another filter as a fallback.
 */
abstract class DelegatingFilter(
        // fallback shouldn't be used by subclasses, so make it private.
        // They should instead be calling into `super` or `outermostFilter`.
        private val fallback: OutputFilter
) : OutputFilter() {
    init {
        fallback.outermostFilter = this
    }

    override var outermostFilter: OutputFilter = this
        get() = field
        set(value) {
            field = value
            // Propagate the inner filters.
            fallback.outermostFilter = value
        }

    override fun getPolicyForClass(className: String): FilterPolicyWithReason {
        return fallback.getPolicyForClass(className)
    }

    override fun getPolicyForField(
            className: String,
            fieldName: String
    ): FilterPolicyWithReason {
        return fallback.getPolicyForField(className, fieldName)
    }

    override fun getPolicyForMethod(
            className: String,
            methodName: String,
            descriptor: String
    ): FilterPolicyWithReason {
        return fallback.getPolicyForMethod(className, methodName, descriptor)
    }

    override fun getRenameTo(
            className: String,
            methodName: String,
            descriptor: String
    ): String? {
        return fallback.getRenameTo(className, methodName, descriptor)
    }

    override fun getNativeSubstitutionClass(className: String): String? {
        return fallback.getNativeSubstitutionClass(className)
    }

    override fun getClassLoadHook(className: String): String? {
        return fallback.getClassLoadHook(className)
    }
}
