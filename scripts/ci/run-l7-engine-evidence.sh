#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
artifact_root="${RIPDPI_L7_ENGINE_ARTIFACT_DIR:-$repo_root/build/l7-engine}"
export PYTHONPATH="$repo_root/test-lab/chaos/tspu${PYTHONPATH:+:$PYTHONPATH}"
python3 -m runner.engine_evidence capture --repo-root "$repo_root" --out "$artifact_root"
