#!/usr/bin/env bash
# Validate content-bound provenance, the actual gomobile API and ELF ABI/alignment.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
exec python3 "$repo_root/scripts/native/libxray_artifacts.py" \
    "${RIPDPI_XRAY_AAR_DIR:-$repo_root/native/xray/artifacts}" "$@"
