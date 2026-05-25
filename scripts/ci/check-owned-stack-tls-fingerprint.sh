#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

echo "==> owned-stack TLS JA3/JA4 fingerprint snapshot"
cargo test --locked --manifest-path "$workspace_manifest" \
  -p ripdpi-android-fetch-adapter \
  owned_stack_tls_fingerprint_snapshot_matches_fixture

echo "==> owned-stack TLS X25519MLKEM768 key-share guard"
cargo test --locked --manifest-path "$workspace_manifest" \
  -p ripdpi-android-fetch-adapter \
  owned_stack_tls_fingerprint_requires_x25519mlkem768_key_share
