#!/usr/bin/env bash
set -euo pipefail

# Cross-compilation checks for Android ABIs.
# Split from run-rust-native-checks.sh for parallel CI execution.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
source "$repo_root/scripts/ci/android-emulator-helpers.sh"

echo "==> cross-target check (Android ABIs)"
if ! android_sdk_root="$(resolve_android_sdk_root)"; then
  echo "  Android SDK not found — cannot run Android cross-target checks" >&2
  exit 1
fi
export ANDROID_HOME="$android_sdk_root"
export ANDROID_SDK_ROOT="$android_sdk_root"

# Resolve NDK toolchain and set CC_<target>, CXX_<target>, AR_<target>,
# and CARGO_TARGET_<TARGET>_LINKER so cc-rs / ring / aws-lc-sys /
# boring-sys can find the correct NDK tools for each Android ABI.
# Mirrors the approach in verify_native_bloat.py:cargo_environment().
ndk_version="$(grep '^ripdpi.nativeNdkVersion=' "$repo_root/gradle.properties" | cut -d= -f2-)"
min_sdk="$(grep '^ripdpi.minSdk=' "$repo_root/gradle.properties" | cut -d= -f2-)"
ndk_dir="$ANDROID_HOME/ndk/$ndk_version"
case "$(uname -s)" in
  Darwin) ndk_host="darwin-x86_64" ;;
  *)      ndk_host="linux-x86_64" ;;
esac
ndk_bin="$ndk_dir/toolchains/llvm/prebuilt/$ndk_host/bin"
if [[ ! -d "$ndk_bin" ]]; then
  echo "  Android NDK toolchain not found: $ndk_bin" >&2
  exit 1
fi
export ANDROID_NDK_HOME="$ndk_dir"

android_cmake=""
if [[ -x "$ANDROID_HOME/cmake/3.22.1/bin/cmake" ]]; then
  android_cmake="$ANDROID_HOME/cmake/3.22.1/bin/cmake"
else
  android_cmake="$(
    find "$ANDROID_HOME/cmake" -path '*/bin/cmake' -type f 2>/dev/null | sort -V | tail -n1 || true
  )"
fi
if [[ -n "$android_cmake" && -x "$android_cmake" ]]; then
  export CMAKE="$android_cmake"
fi

# Match the Gradle native build environment: Cargo dependencies that shell out
# to CMake must not inherit Apple host SDK or architecture flags while targeting
# Android, otherwise CMake can inject `-arch`/`-isysroot` into NDK clang.
unset SDKROOT
unset MACOSX_DEPLOYMENT_TARGET IPHONEOS_DEPLOYMENT_TARGET TVOS_DEPLOYMENT_TARGET WATCHOS_DEPLOYMENT_TARGET XROS_DEPLOYMENT_TARGET
unset ARCHFLAGS RC_ARCHS CMAKE_OSX_ARCHITECTURES CMAKE_OSX_SYSROOT
unset CFLAGS CXXFLAGS CPPFLAGS LDFLAGS
export BORING_BSSL_RUST_CPPLIB="${BORING_BSSL_RUST_CPPLIB:-c++_static}"
compiler_wrapper_dir="$(create_android_compiler_wrapper_dir "$repo_root/native/rust/target/android-compiler-wrappers/cross-check")"

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
  cc_wrapper="$compiler_wrapper_dir/${clang_target}${min_sdk}-clang"
  cxx_wrapper="$compiler_wrapper_dir/${clang_target}${min_sdk}-clang++"
  write_android_compiler_filter_wrapper "$cc_wrapper" "$ndk_bin/${clang_target}${min_sdk}-clang"
  write_android_compiler_filter_wrapper "$cxx_wrapper" "$ndk_bin/${clang_target}${min_sdk}-clang++"
  export "CC_${target_env}=$cc_wrapper"
  export "CXX_${target_env}=$cxx_wrapper"
  export "AR_${target_env}=$ndk_bin/llvm-ar"
  export "CARGO_TARGET_${target_upper}_LINKER=$cc_wrapper"
  export "CARGO_TARGET_${target_upper}_AR=$ndk_bin/llvm-ar"
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
  clean_android_boring_sys_build_cache "${CARGO_TARGET_DIR:-$repo_root/native/rust/target}" "$target"
  RUSTC_WRAPPER="" cargo check --locked --manifest-path "$workspace_manifest" --workspace \
    --exclude ripdpi-io-uring --target "$target"
done
