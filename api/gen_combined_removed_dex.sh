#!/bin/bash

metalava_path="$1"
tmp_dir="$2"
shift 2

# Convert each removed.txt to the "dex format" equivalent, and print all output.
for f in "$@"; do
    "$metalava_path" --no-banner "$f" --dex-api "${tmp_dir}/tmp"
    cat "${tmp_dir}/tmp"
done
