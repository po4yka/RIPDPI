#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
artifact_dir="${1:-$repo_root/build/native-load-artifacts}"

mkdir -p "$artifact_dir"

export RIPDPI_RUN_LOAD="${RIPDPI_RUN_LOAD:-1}"
export RIPDPI_SOAK_PROFILE="${RIPDPI_SOAK_PROFILE:-smoke}"
export RIPDPI_SOAK_ARTIFACT_DIR="$artifact_dir"

echo "==> ripdpi-runtime load tests"
cargo test --manifest-path "$workspace_manifest" -p ripdpi-runtime --test network_load -- --ignored --nocapture --test-threads=1
