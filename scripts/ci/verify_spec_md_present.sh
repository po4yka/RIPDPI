#!/usr/bin/env bash
# Verify that each protocol crate ships a SPEC.md with at least an
# "Upstream" or "Standards" section. Offline-safe.
set -euo pipefail

CRATES=(
  ripdpi-vless
  ripdpi-xhttp
  ripdpi-hysteria2
  ripdpi-tuic
  ripdpi-shadowtls
  ripdpi-naiveproxy
  ripdpi-ws-tunnel
  ripdpi-masque
)

ROOT="$(git -c core.fsmonitor=false rev-parse --show-toplevel)"
CRATES_ROOT="${ROOT}/native/rust/crates"

fail=0
for c in "${CRATES[@]}"; do
  f="${CRATES_ROOT}/${c}/SPEC.md"
  if [ ! -f "$f" ]; then
    echo "  - ${c}: missing SPEC.md"
    fail=1
    continue
  fi
  if ! grep -qE '^## (Upstream|Standards)' "$f"; then
    echo "  - ${c}: SPEC.md missing 'Upstream' or 'Standards' section"
    fail=1
  fi
done

if [ "$fail" -eq 1 ]; then
  echo "SPEC.md presence check FAILED"
  exit 1
fi

echo "OK: all ${#CRATES[@]} protocol crates have SPEC.md with Upstream/Standards section"
