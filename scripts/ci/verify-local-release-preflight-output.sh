#!/usr/bin/env bash
set -euo pipefail

[[ "$#" -eq 1 ]] || {
  echo "Usage: $0 <expected-host-abi>" >&2
  exit 2
}
expected_abi="$1"
[[ "$expected_abi" =~ ^[A-Za-z0-9_-]+$ ]] || {
  echo "Invalid local preflight host ABI: $expected_abi" >&2
  exit 2
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$repo_root"

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

lib_dir="app/build/intermediates/merged_native_libs/githubFullRelease/mergeGithubFullReleaseNativeLibs/out/lib"
[[ -d "$lib_dir" ]] || {
  echo "Missing GithubFullRelease native lib directory: $lib_dir" >&2
  exit 1
}
native_abis=()
shopt -s nullglob
for abi_dir in "$lib_dir"/*; do
  [[ -d "$abi_dir" ]] || continue
  owned_libraries=("$abi_dir"/libripdpi*.so)
  [[ "${#owned_libraries[@]}" -gt 0 ]] || continue
  native_abis+=("$(basename "$abi_dir")")
done
[[ "${#native_abis[@]}" -eq 1 && "${native_abis[0]}" == "$expected_abi" ]] || {
  echo "Repo-owned native ABI set must be exactly [$expected_abi], found [${native_abis[*]-}]" >&2
  exit 1
}
[[ -f "$lib_dir/$expected_abi/libripdpi.so" ]] || {
  echo "Missing libripdpi.so for expected host ABI: $expected_abi" >&2
  exit 1
}
python3 scripts/ci/verify_native_elfs.py --lib-dir "$lib_dir" --abis "$expected_abi"
python3 scripts/ci/verify_jni_readiness_mapping.py
