#!/usr/bin/env bash
set -euo pipefail

test_apks=()
while IFS= read -r path; do
  test_apks+=("$path")
done < <(find app/build/outputs/apk/androidTest -type f -iname '*github*full*release*.apk' | sort)
[[ "${#test_apks[@]}" -eq 1 ]] || {
  echo "Expected exactly one release AndroidTest APK, found ${#test_apks[@]}" >&2
  exit 1
}

lib_dirs=()
while IFS= read -r path; do
  lib_dirs+=("$path")
done < <(
  find app/build/intermediates/merged_native_libs \
    -ipath '*githubfullrelease*/out/lib' -type d | sort -u
)
[[ "${#lib_dirs[@]}" -eq 1 ]] || {
  echo "Expected exactly one GithubFullRelease native lib directory, found ${#lib_dirs[@]}" >&2
  exit 1
}
python3 scripts/ci/verify_native_elfs.py --lib-dir "${lib_dirs[0]}"
python3 scripts/ci/verify_jni_readiness_mapping.py
