#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
if [[ -n "${RIPDPI_L7_ADVERSARIAL_ARTIFACT_DIR:-}" && -z "${RIPDPI_TSPU_ARTIFACT_DIR:-}" ]]; then
  export RIPDPI_TSPU_ARTIFACT_DIR="$RIPDPI_L7_ADVERSARIAL_ARTIFACT_DIR"
fi

exec "$repo_root/scripts/ci/run-tspu-dryrun.sh" "$@"
