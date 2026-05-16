"""Live-mode handler.

The dry-run replayer consumes JSON traces; the live mode consumes raw
packets from a kernel hook (nfqueue on Linux) and produces the same
verdict-report.json shape on shutdown.

The handler does not import netfilterqueue directly. Instead it depends
on a [`KernelAdapter`] protocol that exposes one method:

    def consume(self, handler) -> None: ...

`handler` receives `(transport, payload, src_port, dst_port)` per
packet and returns a [`Verdict`] which the adapter applies to the
kernel (accept / drop / mangle). In production we wire up
[`NfqueueAdapter`]; in tests we use [`FakeAdapter`] that drives a
canned packet list. Both produce the same per-cell verdict report so
the live-mode and dry-run goldens stay structurally identical.

Live mode currently runs the *full pattern matrix* per packet (cheap
in practice; 5 patterns x small classifiers). The first matched
pattern wins for verdict-recording purposes; the cell record carries
all matched pattern ids so triage retains the cross-pattern signal.

Shutdown semantics:

- A `--timeout-seconds` watchdog thread calls `adapter.shutdown()` after
  the configured budget elapses. The adapter is expected to break out
  of `consume()` when shut down.
- SIGTERM / SIGINT call the same `adapter.shutdown()` so the container
  exits cleanly when stopped by docker-compose / CI.
- `run_with_adapter` always writes verdict-report.json before returning,
  regardless of whether the adapter terminated naturally or via the
  watchdog/signal path. This is what makes the live mode usable from
  CI: the report is the contract artifact, not the process exit code.
"""

from __future__ import annotations

import importlib
import json
import os
import signal
import sys
import threading
from dataclasses import dataclass
from typing import Any, Callable, Protocol

from . import packet_parser, schema


LIVE_NOT_AVAILABLE_MESSAGE = (
    "live mode requires a KernelAdapter implementation. Build the v1.1 container "
    "(Dockerfile + docker-compose.tspu.yml) and run with NfqueueAdapter, or supply "
    "a FakeAdapter in process for testing."
)


@dataclass
class Verdict:
    """Kernel action for one packet seen by the live handler."""

    accept: bool
    pattern_ids_matched: tuple[str, ...]


class KernelAdapter(Protocol):
    """Two methods: consume packets and shut down."""

    def consume(
        self,
        handler: Callable[[str, bytes, int, int], Verdict],
    ) -> None: ...

    def shutdown(self) -> None: ...


def _load_pattern_module(pattern_id: str):
    module_name = pattern_id.replace("-", "_")
    return importlib.import_module(f"patterns.{module_name}")


def _evaluate_patterns(packet: dict[str, Any], patterns: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Run every pattern's classifier against a single-packet trace."""
    trace = {"packets": [packet]}
    results = []
    for entry in patterns:
        module = _load_pattern_module(entry["id"])
        result = module.classify(trace, entry.get("config", {}))
        results.append({"pattern_id": entry["id"], "result": result, "config": entry.get("config", {})})
    return results


def run_with_adapter(
    matrix: dict[str, Any],
    adapter: KernelAdapter,
    out_dir: str,
    timeout_seconds: float | None = None,
    install_signal_handlers: bool = False,
) -> dict[str, Any]:
    """Drive `adapter` and emit a verdict-report.json compatible report.

    Cells are recorded per packet (the "desync_mode_id" field carries
    the live-mode source identifier rather than a fixture name). This
    keeps the report consumable by the same triage tooling.

    If `timeout_seconds` is set, a watchdog thread calls
    `adapter.shutdown()` after the budget elapses. If
    `install_signal_handlers` is true, SIGTERM and SIGINT do the same.
    Both are no-ops when `consume()` returns naturally before the
    watchdog fires.
    """
    os.makedirs(out_dir, exist_ok=True)
    patterns = matrix.get("patterns", [])
    cells: list[dict[str, Any]] = []

    def handler(transport: str, payload: bytes, src_port: int, dst_port: int) -> Verdict:
        packet = packet_parser.parse_outbound_packet(
            transport=transport, payload=payload, src_port=src_port, dst_port=dst_port
        )
        pattern_results = _evaluate_patterns(packet, patterns)
        matched = [r["pattern_id"] for r in pattern_results if r["result"].get("matched")]
        cell_verdict = schema.VERDICT_BLOCKED if matched else schema.VERDICT_BYPASSED
        cells.append(
            {
                "desync_mode_id": f"live::{transport}:{dst_port}",
                "pattern_id": matched[0] if matched else "(none)",
                "verdict": cell_verdict,
                "pattern_result": pattern_results[0]["result"] if pattern_results else {},
                "evidence": {
                    "transport": transport,
                    "src_port": src_port,
                    "dst_port": dst_port,
                    "payload_bytes_len": len(payload),
                    "pattern_ids_matched": matched,
                },
            }
        )
        return Verdict(accept=not matched, pattern_ids_matched=tuple(matched))

    watchdog: threading.Timer | None = None
    if timeout_seconds and timeout_seconds > 0:
        watchdog = threading.Timer(timeout_seconds, adapter.shutdown)
        watchdog.daemon = True
        watchdog.start()

    prior_signal_handlers: dict[int, Any] = {}
    if install_signal_handlers:
        for sig in (signal.SIGTERM, signal.SIGINT):
            try:
                prior_signal_handlers[sig] = signal.signal(sig, lambda _signum, _frame: adapter.shutdown())
            except ValueError:
                # Not in the main thread of the main interpreter; CI tests
                # call run_with_adapter from worker threads in some cases.
                pass

    try:
        adapter.consume(handler)
    finally:
        if watchdog is not None:
            watchdog.cancel()
        for sig, prior in prior_signal_handlers.items():
            try:
                signal.signal(sig, prior)
            except ValueError:
                pass

    totals = {v: 0 for v in schema.ALL_VERDICTS}
    for cell in cells:
        totals[cell["verdict"]] = totals.get(cell["verdict"], 0) + 1
    report = {
        "report_schema_version": schema.REPORT_SCHEMA_VERSION,
        "matrix_version": matrix.get("matrix_version"),
        "mode": "live",
        "cells": cells,
        "totals": totals,
    }
    report_path = os.path.join(out_dir, "verdict-report.json")
    with open(report_path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2, sort_keys=True)
        fh.write("\n")
    return report


class FakeAdapter:
    """In-process adapter that feeds a fixed packet list."""

    def __init__(self, packets: list[tuple[str, bytes, int, int]]):
        self._packets = list(packets)
        self._shutdown = threading.Event()

    def consume(self, handler: Callable[[str, bytes, int, int], Verdict]) -> None:
        for transport, payload, src_port, dst_port in self._packets:
            if self._shutdown.is_set():
                return
            handler(transport, payload, src_port, dst_port)

    def shutdown(self) -> None:
        self._shutdown.set()


def _try_load_nfqueue_adapter():
    """Lazily import the NfqueueAdapter so the host doesn't need
    netfilterqueue installed for dry-run."""
    try:
        from .nfqueue_adapter import NfqueueAdapter  # type: ignore

        return NfqueueAdapter
    except ImportError:
        return None


def run_live(
    matrix_path: str | None = None,
    out_dir: str | None = None,
    queue_num: int = 0,
    timeout_seconds: float | None = None,
) -> int:
    """Production entry point. Wires NfqueueAdapter if available.

    Exits non-zero with a documented message when the adapter is not
    installable (no netfilterqueue / not Linux).
    """
    NfqueueAdapter = _try_load_nfqueue_adapter()
    if NfqueueAdapter is None:
        sys.stderr.write(LIVE_NOT_AVAILABLE_MESSAGE + "\n")
        return 2
    if not matrix_path or not out_dir:
        sys.stderr.write("live mode requires --matrix and --out-dir\n")
        return 2
    with open(matrix_path, "r", encoding="utf-8") as fh:
        matrix = json.load(fh)
    adapter = NfqueueAdapter(queue_num=queue_num)
    run_with_adapter(
        matrix,
        adapter,
        out_dir,
        timeout_seconds=timeout_seconds,
        install_signal_handlers=True,
    )
    return 0
