#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
helper="$repo_root/test-lab/scripts/adb-wake-unlock.sh"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-adb-wake-unlock-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

fake_adb="$tmpdir/fake-adb"
fake_log="$tmpdir/fake-adb.log"
fake_state="$tmpdir/keyguard-state"
out="$tmpdir/out.txt"

cat > "$fake_adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >> "$FAKE_ADB_LOG"

if [[ "$*" == "get-state" ]]; then
  if [[ "${FAKE_ADB_NO_DEVICE:-false}" == "true" ]]; then
    echo "unknown"
  else
    echo "device"
  fi
  exit 0
fi

if [[ "$1" != "shell" ]]; then
  exit 0
fi
shift

case "$*" in
  "dumpsys window")
    if [[ "$(cat "$FAKE_KEYGUARD_STATE")" == "locked" ]]; then
      echo "mDreamingLockscreen=true"
    else
      echo "mDreamingLockscreen=false"
    fi
    ;;
  "wm dismiss-keyguard")
    if [[ "${FAKE_ADB_KEEP_LOCKED:-false}" != "true" ]]; then
      echo "unlocked" > "$FAKE_KEYGUARD_STATE"
    fi
    ;;
esac
EOF
chmod +x "$fake_adb"

echo "locked" > "$fake_state"
FAKE_ADB_LOG="$fake_log" FAKE_KEYGUARD_STATE="$fake_state" ADB="$fake_adb" RIPDPI_WAKE_UNLOCK_ATTEMPTS=2 RIPDPI_WAKE_UNLOCK_SETTLE_SECONDS=0 "$helper" > "$out" 2>&1
grep -F 'shell input keyevent 224' "$fake_log" >/dev/null
grep -F 'shell wm dismiss-keyguard' "$fake_log" >/dev/null
grep -F 'shell input keyevent 82' "$fake_log" >/dev/null
grep -F 'shell dumpsys window' "$fake_log" >/dev/null

>"$fake_log"
set +e
FAKE_ADB_NO_DEVICE=true FAKE_ADB_LOG="$fake_log" FAKE_KEYGUARD_STATE="$fake_state" ADB="$fake_adb" RIPDPI_WAKE_UNLOCK_ATTEMPTS=2 RIPDPI_WAKE_UNLOCK_SETTLE_SECONDS=0 "$helper" > "$out" 2>&1
status=$?
set -e
if [[ "$status" -ne 1 ]]; then
  echo "Expected missing adb target to fail with exit 1, got $status" >&2
  cat "$out" >&2
  exit 1
fi
grep -F 'No ready Android target selected for wake/unlock' "$out" >/dev/null

echo "locked" > "$fake_state"
>"$fake_log"
set +e
FAKE_ADB_KEEP_LOCKED=true FAKE_ADB_LOG="$fake_log" FAKE_KEYGUARD_STATE="$fake_state" ADB="$fake_adb" RIPDPI_WAKE_UNLOCK_ATTEMPTS=2 RIPDPI_WAKE_UNLOCK_SETTLE_SECONDS=0 "$helper" > "$out" 2>&1
status=$?
set -e
if [[ "$status" -ne 1 ]]; then
  echo "Expected persistent keyguard to fail with exit 1, got $status" >&2
  cat "$out" >&2
  exit 1
fi
grep -F 'still appears locked' "$out" >/dev/null

echo "adb-wake-unlock self-test passed."
