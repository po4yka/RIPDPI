#!/usr/bin/env bash
# End-to-end smoke: sibling ripdpi-vpn-deploy stack ↔ RIPDPI app on AVD.
#
# Wraps the half-dozen manual steps that, when run one-by-one, no one
# actually runs before merging. Refresh the sequence on every release:
#
#   1. Bring up the published-ports vpn-deploy molecule scenario.
#   2. Wait until host port 31443 accepts a TCP connection.
#   3. Verify a debug APK is present (assumes `just build` was run first;
#      bails with instructions rather than building, since native rust
#      adds ~6 min that pollutes the e2e signal).
#   4. Install the universal debug APK on the connected device/emulator.
#   5. Read the molecule test-secrets fixture, construct a VLESS REALITY
#      deep-link URI pointing at localhost:31443, hand it to the app via
#      `am start -a android.intent.action.VIEW`.
#   6. Wait briefly, scrape logcat for proxy import + handshake markers.
#   7. Report PASS/FAIL with the relevant logcat tail.
#
# Idempotent: each step skips if the side-effect is already in place.
#
# Env overrides:
#   RIPDPI_VPN_DEPLOY_DIR  path to sibling repo (default $HOME/GitHub/ripdpi-vpn-deploy)
#   RIPDPI_E2E_SKIP_LAB    if "1", skip starting the lab (assume already up)
#   RIPDPI_E2E_DEVICE      adb -s SERIAL override
#   RIPDPI_E2E_HOST        host the app dials (default localhost — for AVD use 10.0.2.2)
#   RIPDPI_E2E_PORT        VLESS port on host  (default 31443)
#   RIPDPI_E2E_APK         path to APK (default app/build/outputs/apk/github/debug/app-github-universal-debug.apk)
#
# Exit codes:
#   0   PASS (proxy import succeeded; no fatal markers in logcat)
#   1   FAIL (import or handshake failed; logcat tail printed)
#   2   misuse (missing tooling, missing APK, sibling repo not found)

set -uo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null \
              || (cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd))"
deploy_dir="${RIPDPI_VPN_DEPLOY_DIR:-${HOME}/GitHub/ripdpi-vpn-deploy}"
host="${RIPDPI_E2E_HOST:-localhost}"
port="${RIPDPI_E2E_PORT:-31443}"
pkg="com.poyka.ripdpi"
apk="${RIPDPI_E2E_APK:-${repo_root}/app/build/outputs/apk/github/debug/app-github-universal-debug.apk}"
adb_opt=()
[[ -n "${RIPDPI_E2E_DEVICE:-}" ]] && adb_opt=(-s "$RIPDPI_E2E_DEVICE")

log() { printf '[e2e] %s\n' "$*"; }
fail() { printf '[e2e] FAIL: %s\n' "$*" >&2; exit 1; }
die()  { printf '[e2e] %s\n' "$*" >&2; exit 2; }

# 0. Tooling.
for tool in adb python3 curl docker; do
  command -v "$tool" >/dev/null 2>&1 || die "missing tool: $tool"
done
[[ -d "$deploy_dir" ]] || die "sibling deploy repo not found: $deploy_dir (set RIPDPI_VPN_DEPLOY_DIR)"
[[ -f "$apk" ]] || die "debug APK not found at $apk; run 'just build' first"

devices_line="$(adb "${adb_opt[@]}" get-state 2>/dev/null || true)"
[[ "$devices_line" == "device" ]] || die "no device/emulator ready (adb get-state: '$devices_line')"

# 1. Bring up the lab unless told to skip.
if [[ "${RIPDPI_E2E_SKIP_LAB:-0}" == "1" ]]; then
  log "lab startup skipped (RIPDPI_E2E_SKIP_LAB=1)"
else
  log "starting vpn-deploy lab via test-lab/vpn-deploy/start.sh"
  bash "${repo_root}/test-lab/vpn-deploy/start.sh" || die "start.sh failed"
fi

# 2. Wait for the host VLESS port to accept TCP.
log "waiting for ${host}:${port} to accept TCP"
deadline=$((SECONDS + 60))
while ! (echo > "/dev/tcp/${host}/${port}") 2>/dev/null; do
  (( SECONDS >= deadline )) && fail "${host}:${port} not ready after 60s"
  sleep 1
done
log "  ${host}:${port} reachable"

# 3. Install the APK. -r replaces, -t allows debug-signed.
log "installing ${apk}"
adb "${adb_opt[@]}" install -r -t "$apk" >/dev/null \
  || fail "adb install failed (try: adb uninstall $pkg)"

# 4. Read molecule secrets, build the VLESS REALITY URI.
secrets="${deploy_dir}/ansible/molecule/full-stack/test-secrets.yaml"
[[ -f "$secrets" ]] || die "test-secrets fixture missing: $secrets"

uri="$(python3 - "$secrets" "$host" "$port" <<'PY'
import sys, urllib.parse as up, yaml
path, host, port = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path) as fh:
    d = yaml.safe_load(fh)
x = d["xray"]
client = x["clients"][0]
qs = up.urlencode({
    "security": "reality",
    "encryption": "none",
    "flow": "xtls-rprx-vision",
    "type": "tcp",
    "sni": x["server_names"][0],
    "fp": "chrome",
    "pbk": x["reality_public_key"],
    "sid": client["short_id"],
})
print(f"vless://{client['uuid']}@{host}:{port}?{qs}#ripdpi-e2e-{client['name']}")
PY
)" || die "failed to construct VLESS URI from $secrets"
uri_summary="$(python3 "${repo_root}/scripts/e2e_vpn_deploy_uri_summary.py" "$uri")" \
  || die "failed to construct redacted VLESS URI summary"
log "constructed URI: $uri_summary"

# 5. Hand the URI to the app. Clear logcat first so the scrape is scoped.
adb "${adb_opt[@]}" logcat -c
log "dispatching VIEW intent to $pkg"
adb "${adb_opt[@]}" shell am start -W \
  -a android.intent.action.VIEW \
  -c android.intent.category.BROWSABLE \
  -d "$uri" \
  "$pkg" >/dev/null \
  || fail "am start failed for VIEW $uri_summary"

# 6. Wait a few seconds, then scan logcat.
sleep 5
logcat_dump="$(adb "${adb_opt[@]}" logcat -d 2>/dev/null || true)"

if printf '%s\n' "$logcat_dump" \
     | grep -E "FATAL EXCEPTION|AndroidRuntime.*Process.*$pkg.*died" >/dev/null; then
  echo "[e2e] FAIL: app crashed during import" >&2
  echo "[e2e] ---- logcat tail ----" >&2
  printf '%s\n' "$logcat_dump" | tail -80 >&2
  exit 1
fi

# Success heuristics — at least one must hit. These match log lines emitted
# by the proxy-import flow (see com.poyka.ripdpi.proxyimport.*). Adjust if
# the tags rename; the test should not silently pass on a missing match.
if printf '%s\n' "$logcat_dump" \
     | grep -E "ProxyImport|VlessImport|imported proxy|reality handshake|added .* vless" >/dev/null; then
  log "PASS: proxy import markers present in logcat"
  exit 0
fi

echo "[e2e] FAIL: no proxy-import markers in logcat after 5s" >&2
echo "[e2e] ---- logcat tail ----" >&2
printf '%s\n' "$logcat_dump" | tail -80 >&2
exit 1
