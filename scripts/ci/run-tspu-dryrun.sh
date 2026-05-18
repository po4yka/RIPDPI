#!/usr/bin/env bash
# Drive the TSPU adversarial emulator dry-run from CI.
#
# Runs the unittest suite first (cheap; gates the rest), then executes
# one matrix replay against the checked-in fixtures and verifies the
# output artifacts exist. Output goes to $RIPDPI_TSPU_ARTIFACT_DIR (or a
# tempdir when unset). The script exits non-zero on any failure.
#
# Live mode is documented under test-lab/chaos/tspu/README.md and is
# not exercised by this script.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TSPU_DIR="$ROOT/test-lab/chaos/tspu"

if [[ ! -d "$TSPU_DIR" ]]; then
    echo "tspu directory missing at $TSPU_DIR" >&2
    exit 2
fi

ARTIFACT_DIR="${RIPDPI_TSPU_ARTIFACT_DIR:-$(mktemp -d -t tspu-dryrun-XXXXXX)}"
mkdir -p "$ARTIFACT_DIR"
ARTIFACT_DIR="$(cd "$ARTIFACT_DIR" && pwd)"
echo "tspu artifacts -> $ARTIFACT_DIR"

echo "--- unittest discover"
python3 -m unittest discover -s "$TSPU_DIR/tests" -t "$TSPU_DIR" -v

echo "--- matrix dry-run"
(
    cd "$TSPU_DIR"
    python3 -m runner.cli dry-run \
        --matrix matrix.json \
        --fixtures fixtures \
        --out-dir "$ARTIFACT_DIR"
)

REPORT="$ARTIFACT_DIR/verdict-report.json"
if [[ ! -s "$REPORT" ]]; then
    echo "verdict-report.json missing or empty at $REPORT" >&2
    exit 3
fi

PCAP_COUNT="$(find "$ARTIFACT_DIR" -name '*.pcap' -type f | wc -l | tr -d ' ')"
if [[ "$PCAP_COUNT" -lt 1 ]]; then
    echo "no pcap artifacts produced under $ARTIFACT_DIR" >&2
    exit 4
fi

echo "--- summary"
python3 - <<PY
import json, sys
with open("$REPORT") as fh:
    report = json.load(fh)
cells = report.get("cells", [])
totals = report.get("totals", {})
print(f"cells={len(cells)} totals={json.dumps(totals, sort_keys=True)}")
# Sanity: every totals key is a known verdict.
for verdict in totals:
    assert verdict in ("bypassed", "blocked", "degraded", "inconclusive"), verdict
PY

echo "tspu dry-run OK"
