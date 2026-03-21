#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FLOW_DIR="$ROOT_DIR/maestro"

if ! command -v maestro >/dev/null 2>&1; then
  echo "maestro CLI is required on PATH" >&2
  exit 1
fi

adb wait-for-device

maestro test "$FLOW_DIR/01-cold-launch-home.yaml"
maestro test "$FLOW_DIR/02-settings-navigation.yaml"
maestro test "$FLOW_DIR/03-advanced-settings-edit-save.yaml"
maestro test "$FLOW_DIR/04-start-stop-configured-mode.yaml"
