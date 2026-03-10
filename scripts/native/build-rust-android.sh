#!/usr/bin/env bash
set -euo pipefail

sdk_dir=""
ndk_version=""
min_sdk=""
abis=""
build_dir=""
output_dir=""

while (($# > 0)); do
  case "$1" in
    --sdk-dir)
      sdk_dir="$2"
      shift 2
      ;;
    --ndk-version)
      ndk_version="$2"
      shift 2
      ;;
    --min-sdk)
      min_sdk="$2"
      shift 2
      ;;
    --abis)
      abis="$2"
      shift 2
      ;;
    --build-dir)
      build_dir="$2"
      shift 2
      ;;
    --output-dir)
      output_dir="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$sdk_dir" || -z "$ndk_version" || -z "$min_sdk" || -z "$abis" || -z "$build_dir" || -z "$output_dir" ]]; then
  echo "Missing required arguments." >&2
  exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
ndk_dir="$sdk_dir/ndk/$ndk_version"
toolchains_dir="$ndk_dir/toolchains/llvm/prebuilt"

if [[ -d "$toolchains_dir/linux-x86_64" ]]; then
  host_tag="linux-x86_64"
elif [[ -d "$toolchains_dir/darwin-arm64" ]]; then
  host_tag="darwin-arm64"
elif [[ -d "$toolchains_dir/darwin-x86_64" ]]; then
  host_tag="darwin-x86_64"
else
  echo "Unsupported NDK host toolchain layout in $toolchains_dir" >&2
  exit 1
fi

bin_dir="$toolchains_dir/$host_tag/bin"
byedpi_manifest="$repo_root/native/rust/third_party/byedpi/Cargo.toml"
hev_manifest="$repo_root/native/rust/third_party/hev-socks5-tunnel/Cargo.toml"

declare -A abi_to_target=(
  ["armeabi-v7a"]="armv7-linux-androideabi"
  ["arm64-v8a"]="aarch64-linux-android"
  ["x86"]="i686-linux-android"
  ["x86_64"]="x86_64-linux-android"
)
declare -A abi_to_clang_target=(
  ["armeabi-v7a"]="armv7a-linux-androideabi"
  ["arm64-v8a"]="aarch64-linux-android"
  ["x86"]="i686-linux-android"
  ["x86_64"]="x86_64-linux-android"
)

IFS=',' read -r -a abi_list <<<"$abis"

for abi in "${abi_list[@]}"; do
  target="${abi_to_target[$abi]:-}"
  clang_target="${abi_to_clang_target[$abi]:-}"
  if [[ -z "$target" || -z "$clang_target" ]]; then
    echo "Unsupported ABI: $abi" >&2
    exit 1
  fi

  rustup target add "$target" >/dev/null

  linker="$bin_dir/${clang_target}${min_sdk}-clang"
  ar="$bin_dir/llvm-ar"
  if [[ ! -x "$linker" ]]; then
    echo "Android linker not found: $linker" >&2
    exit 1
  fi

  target_env="$(echo "$target" | tr '[:lower:]-' '[:upper:]_')"
  abi_build_dir="$build_dir/$abi"
  byedpi_target_dir="$abi_build_dir/byedpi-target"
  hev_target_dir="$abi_build_dir/hev-target"
  abi_output_dir="$output_dir/$abi"

  mkdir -p "$abi_output_dir"

  env \
    "CC_${target_env}=$linker" \
    "AR_${target_env}=$ar" \
    "CARGO_TARGET_${target_env}_LINKER=$linker" \
    "CARGO_TARGET_${target_env}_AR=$ar" \
    "CARGO_TARGET_DIR=$byedpi_target_dir" \
    "RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384" \
    cargo build --manifest-path "$byedpi_manifest" -p ciadpi-jni --target "$target" --release

  env \
    "CC_${target_env}=$linker" \
    "AR_${target_env}=$ar" \
    "CARGO_TARGET_${target_env}_LINKER=$linker" \
    "CARGO_TARGET_${target_env}_AR=$ar" \
    "CARGO_TARGET_DIR=$hev_target_dir" \
    "RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384" \
    cargo build --manifest-path "$hev_manifest" -p hs5t-jni --target "$target" --release

  install -m 0644 "$byedpi_target_dir/$target/release/libciadpi_jni.so" "$abi_output_dir/libripdpi.so"
  install -m 0644 "$hev_target_dir/$target/release/libhs5t_jni.so" "$abi_output_dir/libhev-socks5-tunnel.so"
done
