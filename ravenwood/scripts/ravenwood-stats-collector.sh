#!/bin/bash
# Copyright (C) 2024 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Script to collect the ravenwood "stats" CVS files and create a single file.

set -e

# Output file
out=/tmp/ravenwood-stats-all.csv

# Where the input files are.
path=$ANDROID_BUILD_TOP/out/host/linux-x86/testcases/ravenwood-stats-checker/x86_64/

m() {
    ${ANDROID_BUILD_TOP}/build/soong/soong_ui.bash --make-mode "$@"
}

# Building this will generate the files we need.
m ravenwood-stats-checker

# Start...

cd $path

dump() {
    local jar=$1
    local file=$2

    sed -e '1d' -e "s/^/$jar,/"  $file
}

collect() {
    echo 'Jar,PackageName,ClassName,SupportedMethods,TotalMethods'
    dump "framework-minus-apex"  hoststubgen_framework-minus-apex_stats.csv
    dump "service.core"  hoststubgen_services.core_stats.csv
}

collect >$out

echo "Full dump CVS created at $out"
