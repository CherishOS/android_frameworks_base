/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "section_list.h"

/**
 * This is the mapping of section IDs to the commands that are run to get those commands.
 */
const Section* SECTION_LIST[] = {
    // Linux Services
    new FileSection(2002, "/d/wakeup_sources"),

    // System Services
    new DumpsysSection(3000, "fingerprint", "--proto", "--incident", NULL),
    NULL
};
