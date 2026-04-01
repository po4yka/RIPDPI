#!/usr/bin/env bash
set -euo pipefail

# Cross-compilation checks for Android ABIs.
# Split from run-rust-native-checks.sh for parallel CI execution.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

echo "==> cross-target check (Android ABIs)"
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "  ANDROID_HOME not set — skipping Android cross-target checks"
  exit 1
fi

# Resolve NDK toolchain and set CC_<target>, AR_<target>, and
# CARGO_TARGET_<TARGET>_LINKER so cc-rs / ring / aws-lc-sys can find
# the correct NDK tools for each Android ABI.  Mirrors the approach in
# verify_native_bloat.py:cargo_environment().
ndk_version="$(grep '^ripdpi.nativeNdkVersion=' "$repo_root/gradle.properties" | cut -d= -f2-)"
min_sdk="$(grep '^ripdpi.minSdk=' "$repo_root/gradle.properties" | cut -d= -f2-)"
case "$(uname -s)" in
  Darwin) ndk_host="darwin-x86_64" ;;
  *)      ndk_host="linux-x86_64" ;;
esac
ndk_bin="$ANDROID_HOME/ndk/$ndk_version/toolchains/llvm/prebuilt/$ndk_host/bin"

declare -A CLANG_TARGETS=(
  [aarch64-linux-android]="aarch64-linux-android"
  [armv7-linux-androideabi]="armv7a-linux-androideabi"
  [i686-linux-android]="i686-linux-android"
  [x86_64-linux-android]="x86_64-linux-android"
)

for target in aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android; do
  clang_target="${CLANG_TARGETS[$target]}"
  target_env="${target//-/_}"
  target_upper="${target_env^^}"
  export "CC_${target_env}=$ndk_bin/${clang_target}${min_sdk}-clang"
  export "AR_${target_env}=$ndk_bin/llvm-ar"
  export "CARGO_TARGET_${target_upper}_LINKER=$ndk_bin/${clang_target}${min_sdk}-clang"
done

# Disable sccache for cross-compilation: aws-lc-sys invokes the NDK C
# compiler through cargo's cc crate, and sccache cannot wrap cross-
# compiler toolchains like aarch64-linux-android-clang.
#
# Exclude ripdpi-io-uring: the upstream io-uring crate (0.7.x) has
# broken cross-compilation for ARM/i686 targets (u16/u32 type mismatch
# in prebuilt sys.rs).  The crate is a Linux-only optional dependency
# and is validated by the host-native workspace tests instead.
for target in aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android; do
  echo "  -> $target"
  RUSTC_WRAPPER="" cargo check --manifest-path "$workspace_manifest" --workspace \
    --exclude ripdpi-io-uring --target "$target" --locked
done
