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

source "$ROOT/test-lab/scripts/python.sh"

PYTHON_BIN="$(ripdpi_resolve_python "TSPU dry-run")"

if [[ ! -d "$TSPU_DIR" ]]; then
    echo "tspu directory missing at $TSPU_DIR" >&2
    exit 2
fi

ARTIFACT_DIR="${RIPDPI_TSPU_ARTIFACT_DIR:-$(mktemp -d -t tspu-dryrun-XXXXXX)}"
mkdir -p "$ARTIFACT_DIR"
ARTIFACT_DIR="$(cd "$ARTIFACT_DIR" && pwd)"
echo "tspu artifacts -> $ARTIFACT_DIR"

echo "--- unittest discover"
"$PYTHON_BIN" -m unittest discover -s "$TSPU_DIR/tests" -t "$TSPU_DIR" -v

echo "--- matrix dry-run"
(
    cd "$TSPU_DIR"
    "$PYTHON_BIN" -m runner.cli dry-run \
        --matrix matrix.json \
        --fixtures fixtures \
        --out-dir "$ARTIFACT_DIR"
)

REPORT="$ARTIFACT_DIR/verdict-report.json"
if [[ ! -s "$REPORT" ]]; then
    echo "verdict-report.json missing or empty at $REPORT" >&2
    exit 3
fi

echo "--- classifier self-test receipt"
(
    cd "$TSPU_DIR"
    "$PYTHON_BIN" -m runner.expectations \
        --report "$REPORT" \
        --repo-root "$ROOT" \
        --receipt "$ARTIFACT_DIR/classifier-self-test.json"
)

echo "tspu classifier self-test OK (not engine release acceptance)"
