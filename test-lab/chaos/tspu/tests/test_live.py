"""Tests for runner.live with a FakeAdapter."""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
TSPU_DIR = os.path.dirname(HERE)
if TSPU_DIR not in sys.path:
    sys.path.insert(0, TSPU_DIR)


from runner import live  # noqa: E402
from tests.test_packet_parser import _build_clienthello_with_sni  # noqa: E402


def _matrix() -> dict:
    return {
        "matrix_version": 1,
        "patterns": [
            {"id": "rst-after-sni-match", "config": {"sni_blocklist": ["blocked.example"]}},
            {
                "id": "quic-initial-drop",
                "config": {"alpn_blocklist": ["h3"], "sni_blocklist": ["blocked.example"]},
            },
            {"id": "sni-replace", "config": {"sni_blocklist": ["blocked.example"]}},
            {
                "id": "ip-blackhole-after-n-bytes",
                "config": {"threshold_bytes": 10_000, "target_dst_ports": [443]},
            },
            {"id": "mtu-clamp", "config": {"mtu_payload_bytes": 9999}},
        ],
    }


class LiveRunWithFakeAdapterTests(unittest.TestCase):
    def test_clienthello_with_blocked_sni_yields_blocked_cell(self):
        chlo = _build_clienthello_with_sni("blocked.example")
        adapter = live.FakeAdapter([("tcp", chlo, 49152, 443)])
        with tempfile.TemporaryDirectory() as out_dir:
            report = live.run_with_adapter(_matrix(), adapter, out_dir)
            self.assertEqual(report["mode"], "live")
            self.assertEqual(len(report["cells"]), 1)
            cell = report["cells"][0]
            self.assertEqual(cell["verdict"], "blocked")
            self.assertIn("rst-after-sni-match", cell["evidence"]["pattern_ids_matched"])
            self.assertIn("sni-replace", cell["evidence"]["pattern_ids_matched"])

    def test_clienthello_with_benign_sni_yields_bypassed(self):
        chlo = _build_clienthello_with_sni("ok.example")
        adapter = live.FakeAdapter([("tcp", chlo, 49152, 443)])
        with tempfile.TemporaryDirectory() as out_dir:
            report = live.run_with_adapter(_matrix(), adapter, out_dir)
            cell = report["cells"][0]
            self.assertEqual(cell["verdict"], "bypassed")
            self.assertEqual(cell["evidence"]["pattern_ids_matched"], [])

    def test_quic_initial_short_payload_does_not_match_sni(self):
        # Long-header Initial byte, but no SNI / ALPN known -> bypassed
        # because quic-initial-drop requires SNI/ALPN match.
        adapter = live.FakeAdapter([("udp", bytes([0xC0]) + bytes(20), 49152, 443)])
        with tempfile.TemporaryDirectory() as out_dir:
            report = live.run_with_adapter(_matrix(), adapter, out_dir)
            cell = report["cells"][0]
            self.assertEqual(cell["verdict"], "bypassed")

    def test_verdict_report_json_is_written(self):
        adapter = live.FakeAdapter([("tcp", b"\x17\x03\x01\x00\x00", 49152, 443)])
        with tempfile.TemporaryDirectory() as out_dir:
            live.run_with_adapter(_matrix(), adapter, out_dir)
            report_path = os.path.join(out_dir, "verdict-report.json")
            self.assertTrue(os.path.exists(report_path))
            with open(report_path) as fh:
                disk = json.load(fh)
            self.assertEqual(disk["mode"], "live")
            self.assertEqual(disk["report_schema_version"], 1)

    def test_totals_sum_matches_cell_count(self):
        packets = [
            ("tcp", _build_clienthello_with_sni("blocked.example"), 49152, 443),
            ("tcp", _build_clienthello_with_sni("ok.example"), 49152, 443),
            ("udp", bytes([0xC0]) + bytes(10), 49152, 443),
        ]
        adapter = live.FakeAdapter(packets)
        with tempfile.TemporaryDirectory() as out_dir:
            report = live.run_with_adapter(_matrix(), adapter, out_dir)
            self.assertEqual(sum(report["totals"].values()), len(report["cells"]))


class LiveRunNotAvailableTests(unittest.TestCase):
    def test_run_live_without_adapter_documents_failure(self):
        # On macOS the NfqueueAdapter import fails; run_live must return 2
        # with the documented message in stderr.
        import io

        old_stderr = sys.stderr
        sys.stderr = io.StringIO()
        try:
            rc = live.run_live(matrix_path=None, out_dir=None)
            msg = sys.stderr.getvalue()
        finally:
            sys.stderr = old_stderr
        self.assertEqual(rc, 2)
        # Either the adapter-not-available message or the args message,
        # depending on whether netfilterqueue happens to be installed.
        self.assertTrue(
            "live mode" in msg.lower() or "matrix" in msg.lower(),
            f"unexpected stderr: {msg!r}",
        )


if __name__ == "__main__":
    unittest.main()
