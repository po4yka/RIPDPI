#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
install_task="${RIPDPI_INSTALL_TASK:-installGithubDebug}"
gradle_bin="${RIPDPI_GRADLE_BIN:-./gradlew}"
install_log="$(mktemp "${TMPDIR:-/tmp}/ripdpi-adb-install-debug.XXXXXX.log")"
status=0
trap 'rm -f "$install_log"' EXIT

set +e
(
  cd "$repo_root"
  "$gradle_bin" "$install_task"
) 2>&1 | tee "$install_log"
status="${PIPESTATUS[0]}"
set -e

if [[ "$status" -ne 0 && -s "$install_log" ]] && grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' "$install_log"; then
  cat >&2 <<'EOF'

RIPDPI debug install failed because the target already has com.poyka.ripdpi installed with a different signing key.
This helper will not uninstall the app or clear app data automatically.
Use a clean AVD, or explicitly uninstall com.poyka.ripdpi and com.poyka.ripdpi.test from the target before rerunning.
Only use E2E --skip-install when the installed app is known to match the current build and automation selectors.
EOF
fi

exit "$status"
