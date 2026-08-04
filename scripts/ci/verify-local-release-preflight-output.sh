#!/usr/bin/env bash
set -euo pipefail

for variant in fdroidFull fdroidSimple githubFull githubSimple playFull playSimple; do
  variant_dir="app/build/outputs/apk/$variant/release"
  apks=()
  while IFS= read -r path; do
    apks+=("$path")
  done < <(find "$variant_dir" -maxdepth 1 -type f -name '*.apk' | sort)
  [[ "${#apks[@]}" -eq 1 ]] || {
    echo "Expected exactly one $variant release APK, found ${#apks[@]}" >&2
    exit 1
  }
done

bundles=()
while IFS= read -r path; do
  bundles+=("$path")
done < <(find app/build/outputs/bundle/playFullRelease -maxdepth 1 -type f -name '*.aab' | sort)
[[ "${#bundles[@]}" -eq 1 ]] || {
  echo "Expected exactly one Play Full release bundle, found ${#bundles[@]}" >&2
  exit 1
}

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
