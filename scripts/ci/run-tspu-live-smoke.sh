#!/usr/bin/env bash
# Drive the TSPU live-mode handler from CI on a Linux runner.
#
# Steps:
#
# 1. Load the CI nftables ruleset that funnels outbound TCP smoke traffic to
#    nfqueue 0.
# 2. Start a localhost TCP listener on the smoke port.
# 3. Start the live handler with elevated network privileges and a fixed --timeout-seconds.
# 4. Send a crafted TLS ClientHello with SNI=blocked.example.
# 5. Wait for the handler to time out.
# 6. Verify verdict-report.json contains >= 1 blocked cell.
#
# Requires:
#   - Linux host (nfqueue + nftables).
#   - root or sudo / NET_ADMIN.
#   - NetfilterQueue installed for the Python interpreter used below.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TSPU_DIR="$ROOT/test-lab/chaos/tspu"
NFT_FILE="$TSPU_DIR/runner/nft/tspu-ci-smoke.nft"
PYTHON_BIN="${TSPU_PYTHON:-${pythonLocation:+$pythonLocation/bin/python}}"
PYTHON_BIN="${PYTHON_BIN:-python3}"

if [[ "$(uname -s)" != "Linux" ]]; then
    echo "live smoke requires Linux (got $(uname -s)); skipping" >&2
    exit 0
fi

run_privileged() {
    if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
        "$@"
    else
        sudo "$@"
    fi
}

ARTIFACT_DIR="${RIPDPI_TSPU_LIVE_ARTIFACT_DIR:-$(mktemp -d -t tspu-live-XXXXXX)}"
mkdir -p "$ARTIFACT_DIR"
echo "tspu live: artifacts -> $ARTIFACT_DIR"

if [[ -n "${TSPU_LIVE_SMOKE_PORT:-}" ]]; then
    SMOKE_PORT="$TSPU_LIVE_SMOKE_PORT"
else
    SMOKE_PORT="$("$PYTHON_BIN" - <<'PY'
import socket
with socket.socket() as s:
    s.bind(("127.0.0.1", 0))
    print(s.getsockname()[1])
PY
)"
fi
echo "tspu live: smoke port -> $SMOKE_PORT"

# Ensure the nfqueue kernel module is loaded.
if command -v modprobe >/dev/null 2>&1; then
    run_privileged modprobe nfnetlink_queue || true
else
    echo "tspu live: modprobe unavailable; assuming nfqueue support is already present" >&2
fi

# Load nft rules; tear them down on exit.
NFT_RENDERED="$ARTIFACT_DIR/tspu-ci-smoke.nft"
sed -E "s/tcp dport [0-9]+/tcp dport $SMOKE_PORT/" "$NFT_FILE" > "$NFT_RENDERED"
run_privileged nft -f "$NFT_RENDERED"
cleanup() {
    run_privileged nft delete table inet tspu_ci_smoke >/dev/null 2>&1 || true
    if [[ -n "${LISTENER_PID:-}" ]]; then
        kill "$LISTENER_PID" >/dev/null 2>&1 || true
    fi
    if [[ -n "${HANDLER_PID:-}" ]]; then
        run_privileged kill "$HANDLER_PID" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

# 1. Loopback listener so the TCP connect completes and ClientHello
#    bytes are written into the OUTPUT path (which is where nft sees
#    them).
TSPU_LIVE_SMOKE_PORT="$SMOKE_PORT" "$PYTHON_BIN" - <<'PY' &
import os
import socket
import sys
port = int(os.environ["TSPU_LIVE_SMOKE_PORT"])
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", port))
s.listen(4)
sys.stdout.write(f"listening on 127.0.0.1:{port}\n")
sys.stdout.flush()
try:
    while True:
        conn, _ = s.accept()
        try:
            while True:
                data = conn.recv(4096)
                if not data:
                    break
        finally:
            try:
                conn.close()
            except OSError:
                pass
except KeyboardInterrupt:
    pass
PY
LISTENER_PID=$!
sleep 0.5
if ! kill -0 "$LISTENER_PID" >/dev/null 2>&1; then
    wait "$LISTENER_PID"
fi

# 2. Live handler with network privileges, time-limited so the script returns.
run_privileged env \
    PYTHONPATH="$TSPU_DIR" \
    "$PYTHON_BIN" -m runner.cli live \
        --matrix "$TSPU_DIR/matrix.json" \
        --out-dir "$ARTIFACT_DIR" \
        --timeout-seconds 8 \
    > "$ARTIFACT_DIR/handler.log" 2>&1 &
HANDLER_PID=$!
echo "tspu live: handler PID=$HANDLER_PID"

# Give the handler time to bind nfqueue.
sleep 2

# 3. Test traffic.
PYTHONPATH="$TSPU_DIR" TSPU_LIVE_SMOKE_PORT="$SMOKE_PORT" "$PYTHON_BIN" -m runner.ci_smoke_traffic blocked.example || true

# 4. Wait for the handler to finish (its watchdog will fire at 8s).
wait "$HANDLER_PID"

REPORT="$ARTIFACT_DIR/verdict-report.json"
if [[ ! -s "$REPORT" ]]; then
    echo "FAIL: verdict-report.json missing or empty at $REPORT" >&2
    echo "--- handler.log ---" >&2
    cat "$ARTIFACT_DIR/handler.log" >&2 || true
    exit 1
fi

"$PYTHON_BIN" - <<PY
import json, sys
with open("$REPORT") as fh:
    report = json.load(fh)
cells = report.get("cells", [])
totals = report.get("totals", {})
blocked = sum(1 for c in cells if c.get("verdict") == "blocked")
print(f"cells={len(cells)} blocked={blocked} totals={json.dumps(totals, sort_keys=True)}")
if blocked < 1:
    print("FAIL: expected at least 1 blocked cell", file=sys.stderr)
    sys.exit(1)
PY

echo "tspu live smoke OK"
