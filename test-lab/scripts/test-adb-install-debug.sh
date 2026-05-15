#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
helper="$repo_root/test-lab/scripts/adb-install-debug.sh"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-adb-install-debug-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

fake_gradle="$tmpdir/fake-gradle"
out="$tmpdir/out.txt"

cat > "$fake_gradle" <<'EOF'
#!/usr/bin/env bash
case "${FAKE_GRADLE_MODE:-success}" in
  success)
    echo "fake gradle success: $*"
    exit 0
    ;;
  incompatible)
    echo "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match newer version" >&2
    exit 1
    ;;
  other_failure)
    echo "some other install failure" >&2
    exit 3
    ;;
  *)
    echo "unknown fake mode: ${FAKE_GRADLE_MODE:-}" >&2
    exit 9
    ;;
esac
EOF
chmod +x "$fake_gradle"

RIPDPI_GRADLE_BIN="$fake_gradle" RIPDPI_INSTALL_TASK=installGithubDebug "$helper" >"$out" 2>&1
grep -F 'fake gradle success: installGithubDebug' "$out" >/dev/null

set +e
FAKE_GRADLE_MODE=incompatible RIPDPI_GRADLE_BIN="$fake_gradle" "$helper" >"$out" 2>&1
status=$?
set -e
if [[ "$status" -ne 1 ]]; then
  echo "Expected incompatible install failure to exit 1, got $status" >&2
  cat "$out" >&2
  exit 1
fi
grep -F 'different signing key' "$out" >/dev/null
grep -F 'will not uninstall the app or clear app data automatically' "$out" >/dev/null
grep -F 'Only use E2E --skip-install when the installed app is known to match' "$out" >/dev/null

set +e
FAKE_GRADLE_MODE=other_failure RIPDPI_GRADLE_BIN="$fake_gradle" "$helper" >"$out" 2>&1
status=$?
set -e
if [[ "$status" -ne 3 ]]; then
  echo "Expected generic install failure to preserve exit 3, got $status" >&2
  cat "$out" >&2
  exit 1
fi
if grep -Fq 'different signing key' "$out"; then
  echo "Generic install failure should not print signing-key guidance" >&2
  cat "$out" >&2
  exit 1
fi

echo "adb-install-debug self-test passed."
