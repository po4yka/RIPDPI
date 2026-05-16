"""TSPU matrix-runner CLI.

Usage:

    python3 -m runner.cli dry-run \\
        --matrix matrix.json \\
        --fixtures fixtures \\
        --out-dir /tmp/tspu-dryrun

The CLI is launched from `test-lab/chaos/tspu/` so the `runner` and
`patterns` packages are importable from the working directory.

Subcommands:

- `dry-run` -- replay JSON traces, no kernel I/O. Runs on any host.
- `live`    -- placeholder for the nfqueue-based mode that follows this
                PR. Exits non-zero with a documented error message.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any

from . import live, replay


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="tspu-runner", description="TSPU matrix-runner")
    sub = parser.add_subparsers(dest="cmd", required=True)

    dry = sub.add_parser("dry-run", help="Replay JSON traces against pattern classifiers.")
    dry.add_argument("--matrix", required=True, help="Path to matrix.json.")
    dry.add_argument("--fixtures", required=True, help="Path to fixtures directory.")
    dry.add_argument("--out-dir", required=True, help="Where to write verdict-report.json and pcaps.")

    sub.add_parser("live", help="(stub) Run the nfqueue-attached classifier inside the container.")

    return parser.parse_args(argv)


def _load_matrix(path: str) -> dict[str, Any]:
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(list(argv) if argv is not None else sys.argv[1:])
    if args.cmd == "dry-run":
        matrix = _load_matrix(args.matrix)
        os.makedirs(args.out_dir, exist_ok=True)
        report = replay.replay_matrix(matrix, args.fixtures, args.out_dir)
        report_path = os.path.join(args.out_dir, "verdict-report.json")
        with open(report_path, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2, sort_keys=True)
            fh.write("\n")
        print(json.dumps(report["totals"], sort_keys=True))
        return 0
    if args.cmd == "live":
        return live.run_live()
    sys.stderr.write(f"unknown subcommand: {args.cmd}\n")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
