#!/usr/bin/env bash
# Live per-exit-IP concurrent-TLS-session probe.
#
# Reproduces the RU home-ISP policing scenario that the proxy-runtime
# per-exit-IP concurrent-TLS cap defends against: ~12 simultaneous TLS
# sessions to a single foreign exit IP trips a silent-drop window. The cap
# (`ExitIpSessionLimiter` / `ExitIpSessionCaps`, default
# `DEFAULT_EXIT_IP_SESSION_CAP = 8`, in
# native/rust/crates/ripdpi-proxy-runtime/src/exit_ip_cap.rs) keeps the
# outbound profile under that ceiling; this probe opens COUNT concurrent TLS
# sessions to ENDPOINT, holds them open *simultaneously* (a barrier ensures
# every handshake is live at once), and verifies all COUNT establish within
# TIMEOUT and that COUNT stays at/under the cap.
#
# Default COUNT=5 mirrors the "5 simultaneous TLS connections in 60 s"
# reproducibility probe in the acceptance criteria (5 sits under the cap of 8).
#
# Endpoint defaults to the local-network-lab Caddy TLS server
# (test-lab/tls/caddy/Caddyfile, exposed at 127.0.0.1:8443). The lab cert is
# self-signed (test-lab/tls/certs/), so certificate verification is disabled
# for this lab-only probe. If the endpoint is unreachable the probe SKIPs
# (exit 0) rather than failing, matching the lab's graceful-skip convention.
#
# Usage:
#   test-lab/tls/probe-session-cap.sh [host:port]
# Env overrides:
#   RIPDPI_TLS_PROBE_COUNT   (default 5)   concurrent sessions to open
#   RIPDPI_TLS_PROBE_CAP     (default 8)   cap to assert COUNT stays under
#   RIPDPI_TLS_PROBE_TIMEOUT (default 60)  seconds to establish all sessions
#   RIPDPI_TLS_PROBE_OUT     (default "")  optional path to write the JSON verdict
set -euo pipefail

ENDPOINT="${1:-127.0.0.1:8443}"
COUNT="${RIPDPI_TLS_PROBE_COUNT:-5}"
CAP="${RIPDPI_TLS_PROBE_CAP:-8}"
TIMEOUT="${RIPDPI_TLS_PROBE_TIMEOUT:-60}"
OUT="${RIPDPI_TLS_PROBE_OUT:-}"

command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 2; }

python3 - "$ENDPOINT" "$COUNT" "$CAP" "$TIMEOUT" "$OUT" <<'PY'
import json
import socket
import ssl
import sys
import threading
import time

endpoint, count, cap, timeout, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
host, _, port_s = endpoint.partition(":")
port = int(port_s or "443")

# Reachability pre-check: skip (success) when the lab endpoint is not up, so the
# probe is a no-op in environments without the local-network lab running.
try:
    socket.create_connection((host, port), timeout=3).close()
except OSError as exc:
    print(json.dumps({"status": "skipped", "endpoint": endpoint, "reason": f"unreachable: {exc}"}))
    sys.exit(0)

# Lab cert is self-signed; this probe trusts the lab endpoint only (never used in
# production code paths).
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

barrier = threading.Barrier(count)
lock = threading.Lock()
established = []
errors = []
held = []

def worker(index):
    try:
        raw = socket.create_connection((host, port), timeout=timeout)
        tls = ctx.wrap_socket(raw, server_hostname=host)
        # Block until every worker has completed its handshake, so all `count`
        # sessions are provably open at the same instant (the scenario the cap
        # targets), not merely opened-then-closed serially.
        barrier.wait(timeout=timeout)
        with lock:
            established.append(index)
            held.append(tls)
    except Exception as exc:  # noqa: BLE001 - probe records any failure reason
        with lock:
            errors.append(str(exc))

threads = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(count)]
start = time.time()
for t in threads:
    t.start()
for t in threads:
    t.join(timeout=timeout + 5)
elapsed = time.time() - start

passed = len(established) == count and count <= cap and elapsed <= timeout
verdict = {
    "status": "passed" if passed else "failed",
    "endpoint": endpoint,
    "requested": count,
    "established": len(established),
    "cap": cap,
    "under_cap": count <= cap,
    "elapsed_s": round(elapsed, 2),
    "errors": errors[:5],
}

for tls in held:
    try:
        tls.close()
    except OSError:
        pass

text = json.dumps(verdict, indent=2)
print(text)
if out:
    with open(out, "w", encoding="utf-8") as fh:
        fh.write(text + "\n")

sys.exit(0 if passed else 1)
PY
