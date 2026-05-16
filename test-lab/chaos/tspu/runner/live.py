"""Live-mode entry point (skeleton).

The full nfqueue-attached classifier lands in the follow-up PR. This
module exists so the dispatching surface in `runner.cli` has a real
import target, and so the live-mode contract can be tested with a
deliberate "not implemented" exit before the real handler arrives.

Implementation plan for the follow-up PR:

1. Install nftables rules from `runner/nft/` that funnel matched
   packets into nfqueue 0.
2. For each pattern in `matrix.json`, instantiate its classifier and
   register it with the netfilterqueue python bindings.
3. For each incoming packet, build the trace dict on the fly from the
   raw bytes (parsing TLS ClientHello / QUIC long-header headers here,
   not in the pattern modules) and call `pattern.classify(...)`.
4. Emit a cell-verdict report with the same JSON shape the dry-run
   replayer produces, so downstream tooling stays unchanged.

For v1.1 the entry point exits non-zero with a documented message; the
test suite exercises this path so the contract does not regress.
"""

from __future__ import annotations

import sys


LIVE_NOT_IMPLEMENTED_MESSAGE = (
    "live mode requires the nfqueue-attached classifier inside the v1.1 container "
    "(see docs/architecture/spike-tspu-adversarial-emulator.md). Not implemented in v1.1."
)


def run_live() -> int:
    sys.stderr.write(LIVE_NOT_IMPLEMENTED_MESSAGE + "\n")
    return 2
