#!/usr/bin/env bash
set -euo pipefail

# Native architecture guardrails live here on purpose. Intentional adapter or
# ownership changes must update both the code and the checked-in CI contracts
# in the same change.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
source "$repo_root/scripts/ci/android-emulator-helpers.sh"

workspace_manifest="$repo_root/native/rust/Cargo.toml"
export RIPDPI_GOLDEN_ARTIFACT_DIR="${RIPDPI_GOLDEN_ARTIFACT_DIR:-$repo_root/native/rust/target/golden-diffs}"

echo "==> rustfmt"
cargo fmt --manifest-path "$workspace_manifest" --all --check

echo "==> clippy"
bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo clippy --locked --manifest-path "$workspace_manifest" --workspace --all-targets -- -D warnings

echo "==> REALITY BoringSSL hook vector guard"
python3 "$repo_root/scripts/ci/check_reality_boring_vector.py"

echo "==> cross-target check (Android ABIs)"
if ! android_sdk_root="$(resolve_android_sdk_root)"; then
  echo "  Android SDK not found — skipping Android cross-target checks"
else
  export ANDROID_HOME="$android_sdk_root"
  export ANDROID_SDK_ROOT="$android_sdk_root"

  # Disable sccache for cross-compilation: aws-lc-sys invokes the NDK C
  # compiler through cargo's cc crate, and sccache cannot wrap cross-
  # compiler toolchains like aarch64-linux-android-clang.

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
    echo "  Android NDK toolchain not found: $ndk_bin — skipping Android cross-target checks"
  else
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

    # Match the Gradle native build environment: Cargo dependencies that shell
    # out to CMake must not inherit Apple host SDK or architecture flags while
    # targeting Android, otherwise CMake can inject `-arch`/`-isysroot` into NDK
    # clang.
    unset SDKROOT
    unset MACOSX_DEPLOYMENT_TARGET IPHONEOS_DEPLOYMENT_TARGET TVOS_DEPLOYMENT_TARGET WATCHOS_DEPLOYMENT_TARGET XROS_DEPLOYMENT_TARGET
    unset ARCHFLAGS RC_ARCHS CMAKE_OSX_ARCHITECTURES CMAKE_OSX_SYSROOT
    unset CFLAGS CXXFLAGS CPPFLAGS LDFLAGS
    previous_boring_cpplib="${BORING_BSSL_RUST_CPPLIB-}"
    previous_boring_cpplib_was_set=0
    if [[ -v BORING_BSSL_RUST_CPPLIB ]]; then
      previous_boring_cpplib_was_set=1
    fi
    export BORING_BSSL_RUST_CPPLIB="${BORING_BSSL_RUST_CPPLIB:-c++_static}"
    compiler_wrapper_dir="$(create_android_compiler_wrapper_dir "$repo_root/native/rust/target/android-compiler-wrappers/native-checks")"

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

    if [[ "$previous_boring_cpplib_was_set" -eq 1 ]]; then
      export BORING_BSSL_RUST_CPPLIB="$previous_boring_cpplib"
    else
      unset BORING_BSSL_RUST_CPPLIB
    fi
  fi
fi

NEXTEST_PROFILE="${CI:+ci}"
NEXTEST_ARGS=(${NEXTEST_PROFILE:+--profile "$NEXTEST_PROFILE"})

echo "==> verify root workspace membership"
python3 - "$workspace_manifest" <<'PY'
import json
import subprocess
import sys

manifest = sys.argv[1]
result = subprocess.run(
    ["cargo", "metadata", "--manifest-path", manifest, "--format-version", "1", "--no-deps"],
    check=True,
    capture_output=True,
    text=True,
)
workspace_members = json.loads(result.stdout)["workspace_members"]
third_party_members = [member for member in workspace_members if "/third_party/" in member]
if third_party_members:
    print("error: native/rust root workspace must stay first-party only", file=sys.stderr)
    for member in third_party_members:
        print(member, file=sys.stderr)
    sys.exit(1)
PY

echo "==> native hotspot budgets"
python3 "$repo_root/scripts/ci/check_native_hotspot_budgets.py"

echo "==> native architecture checker tests"
python3 "$repo_root/scripts/ci/test_native_architecture_contracts.py"

echo "==> native architecture contracts"
python3 "$repo_root/scripts/ci/check_native_architecture_contracts.py"

echo "==> tests (workspace)"
bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked --manifest-path "$workspace_manifest" -p local-network-fixture "${NEXTEST_ARGS[@]}"
bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked --manifest-path "$workspace_manifest" -p ripdpi-tunnel-android "${NEXTEST_ARGS[@]}"
bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked --manifest-path "$workspace_manifest" -p ripdpi-android "${NEXTEST_ARGS[@]}"
# Exclude integration test binaries that have their own dedicated CI jobs
# (rust-network-e2e, rust-turmoil) and platform tests needing CAP_NET_ADMIN.
bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked --manifest-path "$workspace_manifest" --workspace \
  -E 'not binary(network_e2e) and not binary(tun_e2e) and not test(/^platform::linux::tests::bpf_/) and not test(/^platform::linux::tests::tcp_window_clamp/) and not test(/^runtime::tests::window_clamp/)' \
  "${NEXTEST_ARGS[@]}"

# ripdpi-socks5-core gates its SOCKS4 module behind the optional `socks4`
# feature (default = []), so the workspace run above never compiles or
# exercises socks4::* tests. Run the crate explicitly with the feature so the
# SOCKS4 reply round-trip / no-panic tests stay in the merge gate.
echo "==> tests (ripdpi-socks5-core, socks4 feature)"
bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked --manifest-path "$workspace_manifest" -p ripdpi-socks5-core --features socks4 "${NEXTEST_ARGS[@]}"

echo "==> tests (ignored / smoke)"
bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked --manifest-path "$workspace_manifest" -p ripdpi-tunnel-android -E 'test(startup_latency_smoke)' --run-ignored ignored-only --no-capture "${NEXTEST_ARGS[@]}"
