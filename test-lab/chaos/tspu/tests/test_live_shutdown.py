"""Tests for live-mode shutdown semantics (watchdog timeout + signals)."""

from __future__ import annotations

import os
import sys
import tempfile
import threading
import time
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
TSPU_DIR = os.path.dirname(HERE)
if TSPU_DIR not in sys.path:
    sys.path.insert(0, TSPU_DIR)


from runner import live  # noqa: E402


class StallingAdapter:
    """Test adapter that blocks in consume() until shutdown() is called."""

    def __init__(self):
        self._unblock = threading.Event()
        self.shutdown_called = False

    def consume(self, handler):
        self._unblock.wait(timeout=5.0)

    def shutdown(self):
        self.shutdown_called = True
        self._unblock.set()


class FakeAdapterShutdownTests(unittest.TestCase):
    def test_fake_adapter_shutdown_stops_consume_early(self):
        clienthello = b"\x16\x03\x01\x00\x00"  # any TCP payload triggers a cell record
        adapter = live.FakeAdapter([
            ("tcp", clienthello, 49152, 443),
            ("tcp", clienthello, 49153, 443),
            ("tcp", clienthello, 49154, 443),
        ])
        # Pre-shut the adapter; consume should write zero cells.
        adapter.shutdown()
        with tempfile.TemporaryDirectory() as out_dir:
            report = live.run_with_adapter({"matrix_version": 1, "patterns": []}, adapter, out_dir)
        self.assertEqual(len(report["cells"]), 0)


class WatchdogTests(unittest.TestCase):
    def test_watchdog_fires_shutdown_after_timeout(self):
        adapter = StallingAdapter()
        started = time.monotonic()
        with tempfile.TemporaryDirectory() as out_dir:
            report = live.run_with_adapter(
                {"matrix_version": 1, "patterns": []},
                adapter,
                out_dir,
                timeout_seconds=0.3,
            )
        elapsed = time.monotonic() - started
        self.assertTrue(adapter.shutdown_called, "watchdog did not call adapter.shutdown()")
        self.assertLess(elapsed, 2.0, "consume() did not return promptly after shutdown")
        self.assertEqual(report["mode"], "live")
        self.assertEqual(report["cells"], [])

    def test_no_watchdog_when_timeout_none(self):
        clienthello = b"\x16\x03\x01\x00\x00"
        adapter = live.FakeAdapter([("tcp", clienthello, 49152, 443)])
        with tempfile.TemporaryDirectory() as out_dir:
            report = live.run_with_adapter(
                {"matrix_version": 1, "patterns": []},
                adapter,
                out_dir,
                timeout_seconds=None,
            )
        # The adapter exhausted its packet list naturally; one cell emitted.
        self.assertEqual(len(report["cells"]), 1)


class CliTimeoutFlagTests(unittest.TestCase):
    def test_cli_accepts_timeout_seconds(self):
        # The CLI should accept --timeout-seconds without erroring even
        # though run_live exits 2 on macOS where netfilterqueue is absent.
        from runner import cli

        import io
        old_err = sys.stderr
        sys.stderr = io.StringIO()
        try:
            rc = cli.main(["live", "--timeout-seconds", "1.5"])
        finally:
            sys.stderr = old_err
        # rc=2 because of missing kernel adapter / missing matrix; the
        # important assertion is that argparse did not reject the flag.
        self.assertEqual(rc, 2)


if __name__ == "__main__":
    unittest.main()
