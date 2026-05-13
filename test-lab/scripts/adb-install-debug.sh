#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
install_task="${RIPDPI_INSTALL_TASK:-installGithubDebug}"

(
  cd "$repo_root"
  ./gradlew "$install_task"
)
