"""Generate the test traffic for the live-mode CI smoke.

Connects to 127.0.0.1 on the configured smoke port and writes a crafted TLS ClientHello whose
SNI matches the smoke-matrix blocklist. The kernel routes the segment
through the nft rule into nfqueue, where the live handler classifies
it. The receiver does not need to do anything with the bytes; the
classification fires before the segment is delivered.

Invoked from `scripts/ci/run-tspu-live-smoke.sh`.
"""

from __future__ import annotations

import socket
import sys
import os

from runner.chlo_builder import build_clienthello_with_sni


def main() -> int:
    host = "127.0.0.1"
    port = int(os.environ.get("TSPU_LIVE_SMOKE_PORT", "18443"))
    sni = sys.argv[1] if len(sys.argv) > 1 else "blocked.example"
    chlo = build_clienthello_with_sni(sni)
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(2.0)
    try:
        s.connect((host, port))
        s.send(chlo)
    except OSError as exc:
        sys.stderr.write(f"ci_smoke_traffic: send returned {exc}; continuing\n")
    finally:
        try:
            s.close()
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
