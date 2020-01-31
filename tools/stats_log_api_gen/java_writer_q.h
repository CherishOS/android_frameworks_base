/*
 * Copyright (C) 2019, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include "Collation.h"

#include <map>
#include <set>
#include <vector>

#include <stdio.h>
#include <string.h>

namespace android {
namespace stats_log_api_gen {

using namespace std;

void write_java_q_logging_constants(FILE* out, const string& indent);

int write_java_methods_q_schema(
        FILE* out,
        const map<vector<java_type_t>, set<string>>& signatures_to_modules,
        const AtomDecl &attributionDecl,
        const string& moduleName,
        const string& indent);

void write_java_helpers_for_q_schema_methods(
        FILE * out,
        const AtomDecl &attributionDecl,
        const int requiredHelpers,
        const string& indent);

int write_stats_log_java_q_for_module(FILE* out, const Atoms& atoms,
        const AtomDecl &attributionDecl, const string& moduleName, const string& javaClass,
        const string& javaPackage, const bool supportWorkSource);

#if defined(STATS_SCHEMA_LEGACY)
int write_stats_log_java_q(FILE* out, const Atoms& atoms, const AtomDecl &attributionDecl,
                           const bool supportWorkSource);
#endif
}  // namespace stats_log_api_gen
}  // namespace android
