#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FLOW_DIR="$ROOT_DIR/maestro"

resolve_maestro() {
  if [[ -n "${MAESTRO_BIN:-}" ]]; then
    if [[ -x "$MAESTRO_BIN" ]]; then
      printf '%s\n' "$MAESTRO_BIN"
      return 0
    fi
    echo "MAESTRO_BIN is set but is not executable: $MAESTRO_BIN" >&2
    return 1
  fi

  if command -v maestro >/dev/null 2>&1; then
    command -v maestro
    return 0
  fi

  local default_bin="$HOME/.maestro/bin/maestro"
  if [[ -x "$default_bin" ]]; then
    printf '%s\n' "$default_bin"
    return 0
  fi

  return 1
}

if ! MAESTRO_BIN_RESOLVED="$(resolve_maestro)"; then
  echo "maestro CLI is required. Add it to PATH, set MAESTRO_BIN, or install it at ~/.maestro/bin/maestro." >&2
  exit 1
fi

adb wait-for-device

"$MAESTRO_BIN_RESOLVED" test "$FLOW_DIR/01-cold-launch-home.yaml"
"$MAESTRO_BIN_RESOLVED" test "$FLOW_DIR/02-settings-navigation.yaml"
"$MAESTRO_BIN_RESOLVED" test "$FLOW_DIR/03-advanced-settings-edit-save.yaml"
"$MAESTRO_BIN_RESOLVED" test "$FLOW_DIR/04-start-stop-configured-mode.yaml"
