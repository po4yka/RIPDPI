#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
artifact_dir="${1:-$repo_root/build/linux-tun-soak-artifacts}"

mkdir -p "$artifact_dir"

export RIPDPI_RUN_SOAK="${RIPDPI_RUN_SOAK:-1}"
export RIPDPI_RUN_TUN_E2E="${RIPDPI_RUN_TUN_E2E:-1}"
export RIPDPI_SOAK_PROFILE="${RIPDPI_SOAK_PROFILE:-smoke}"
export RIPDPI_SOAK_ARTIFACT_DIR="$artifact_dir"

echo "==> ripdpi-tunnel-android linux tun soak"
cargo test --manifest-path "$workspace_manifest" -p ripdpi-tunnel-android --test linux_tun_soak -- --ignored --nocapture --test-threads=1
