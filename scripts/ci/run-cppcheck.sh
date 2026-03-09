#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
report_dir="${1:-$repo_root/build/reports/cppcheck}"

mkdir -p "$report_dir"

cppcheck \
  --enable=warning,portability \
  --language=c \
  --std=c99 \
  --error-exitcode=1 \
  --inline-suppr \
  --quiet \
  --xml \
  --xml-version=2 \
  --suppress=missingIncludeSystem \
  -DANDROID \
  -D__ANDROID__ \
  -DANDROID_APP \
  -D_XOPEN_SOURCE=500 \
  -I "$repo_root/core/engine/src/main/cpp" \
  -I "$repo_root/core/engine/src/main/cpp/byedpi" \
  "$repo_root/core/engine/src/main/cpp" \
  2> "$report_dir/cppcheck.xml"

echo "cppcheck report written to $report_dir/cppcheck.xml"
