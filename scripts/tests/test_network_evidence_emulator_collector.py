#!/usr/bin/env python3
"""Unit tests for the emulator dual-vantage collector hook.

The capture backends need a live emulator and a host interface, so they are
exercised in CI. Everything that decides what the oracle is asked to prove --
packet accounting, semantic-rule binding, and the single-run rendezvous -- is
pure enough to test here, and it is the part that fails silently if wrong.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import struct
import tempfile
import threading
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COLLECTOR = ROOT / "test-lab/scripts/network-evidence-emulator-collector.py"
STARTUP_GATE_ID = "killswitch-tun-establish-native-ready"


def load_collector():
    spec = importlib.util.spec_from_file_location("emulator_collector", COLLECTOR)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


collector = load_collector()


def pcap(packet_count: int, *, big_endian: bool = False, truncate: int = 0) -> bytes:
    prefix = ">" if big_endian else "<"
    magic = b"\xa1\xb2\xc3\xd4" if big_endian else b"\xd4\xc3\xb2\xa1"
    payload = bytearray(magic)
    payload += struct.pack(prefix + "HHiIII", 2, 4, 0, 0, 192, 1)
    for index in range(packet_count):
        body = bytes([index % 251]) * 8
        payload += struct.pack(prefix + "IIII", 1700000000 + index, 0, len(body), len(body))
        payload += body
    if truncate:
        return bytes(payload[:-truncate])
    return bytes(payload)


class PcapAccountingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write(self, payload: bytes) -> Path:
        path = self.root / "capture.pcap"
        path.write_bytes(payload)
        return path

    def test_counts_records_in_both_byte_orders(self) -> None:
        for big_endian in (False, True):
            with self.subTest(big_endian=big_endian):
                path = self.write(pcap(7, big_endian=big_endian))
                self.assertEqual(collector.count_pcap_packets(path), 7)

    def test_empty_capture_counts_zero_packets(self) -> None:
        self.assertEqual(collector.count_pcap_packets(self.write(pcap(0))), 0)

    def test_capture_truncated_inside_a_body_is_rejected(self) -> None:
        path = self.write(pcap(3, truncate=4))
        with self.assertRaisesRegex(collector.CollectorError, "ends inside a record"):
            collector.count_pcap_packets(path)

    def test_capture_truncated_inside_a_record_header_is_rejected(self) -> None:
        path = self.write(pcap(3)[:-12])
        with self.assertRaisesRegex(collector.CollectorError, "ends inside a record"):
            collector.count_pcap_packets(path)

    def test_non_pcap_payload_is_rejected(self) -> None:
        path = self.write(b"\x00\x01\x02\x03" + bytes(64))
        with self.assertRaisesRegex(collector.CollectorError, "not a classic PCAP"):
            collector.count_pcap_packets(path)

    def test_missing_header_is_rejected(self) -> None:
        with self.assertRaisesRegex(collector.CollectorError, "missing a classic PCAP"):
            collector.count_pcap_packets(self.write(b"\xd4\xc3\xb2\xa1"))


class SemanticRuleBindingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def receipt(self, gate_id: str) -> Path:
        path = self.root / f"receipt-{gate_id}.json"
        path.write_text(
            json.dumps({"gateId": gate_id, "fixtureIdentitySha256": "c" * 64}),
            encoding="utf-8",
        )
        return path

    @staticmethod
    def plan(*window_ids: str) -> dict:
        return {"windows": [{"id": value} for value in window_ids]}

    def test_startup_window_binds_the_startup_rule(self) -> None:
        receipts = {STARTUP_GATE_ID: self.receipt(STARTUP_GATE_ID)}
        rules = collector.semantic_rules_for(self.plan(STARTUP_GATE_ID), receipts)
        self.assertEqual(rules[0]["rule"], "tun-establish-native-ready-v1")
        self.assertEqual(rules[0]["fixtureIdentitySha256"], "c" * 64)
        self.assertEqual(
            rules[0]["actionReceiptSha256"],
            hashlib.sha256(receipts[STARTUP_GATE_ID].read_bytes()).hexdigest(),
        )

    def test_every_dns_gate_binds_its_own_gate_specific_rule(self) -> None:
        for gate_id, expected in collector.DNS_RULES.items():
            with self.subTest(gate_id=gate_id):
                rules = collector.semantic_rules_for(
                    self.plan(gate_id), {gate_id: self.receipt(gate_id)}
                )
                self.assertEqual(rules[0]["rule"], expected)

    def test_a_policy_gate_without_a_receipt_fails_closed(self) -> None:
        with self.assertRaisesRegex(collector.CollectorError, "no action receipt"):
            collector.semantic_rules_for(self.plan("dns-virtual-vpn-resolver"), {})

    def test_non_policy_window_falls_back_to_the_generic_rule(self) -> None:
        rules = collector.semantic_rules_for(self.plan("fixture-smoke"), {})
        self.assertEqual(
            rules, [{"rule": "generic-marker-pair", "windowId": "fixture-smoke"}]
        )


class RendezvousTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.shared = Path(self.temp.name)
        self.plan = self.shared / "scenario-plan.json"
        self.plan.write_text(json.dumps({"windows": []}), encoding="utf-8")
        self.runs = self.shared / "oracle-runs"
        oracle = self.shared / "stub-oracle.py"
        oracle.write_text(
            "import pathlib, sys\n"
            f"runs = pathlib.Path({str(self.runs)!r})\n"
            "runs.write_text(str(int(runs.read_text() or 0) + 1) if runs.exists() else '1')\n"
            "args = sys.argv\n"
            "for flag in ('--client-output', '--observer-output'):\n"
            "    pathlib.Path(args[args.index(flag) + 1]).write_text('{}')\n",
            encoding="utf-8",
        )
        os.environ["RIPDPI_EVIDENCE_ORACLE"] = str(oracle)
        for role in collector.ROLES:
            (self.shared / f"{role}.pcap").write_bytes(pcap(1))
            (self.shared / f"{role}-metadata.json").write_text("{}", encoding="utf-8")

    def tearDown(self) -> None:
        os.environ.pop("RIPDPI_EVIDENCE_ORACLE", None)
        self.temp.cleanup()

    def test_both_vantages_share_exactly_one_oracle_run(self) -> None:
        results: dict[str, Path] = {}
        errors: list[BaseException] = []

        def worker(role: str) -> None:
            try:
                results[role] = collector.rendezvous(self.shared, role, "a" * 64, self.plan)
            except BaseException as error:  # noqa: BLE001 - surfaced by the assertion
                errors.append(error)

        threads = [
            threading.Thread(target=worker, args=(role,)) for role in collector.ROLES
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=60)

        self.assertEqual(errors, [])
        self.assertEqual(self.runs.read_text(), "1")
        self.assertEqual(set(results), set(collector.ROLES))
        for role, path in results.items():
            self.assertEqual(path.name, f"{role}-observation.json")
            self.assertTrue(path.exists())

    def test_winner_waits_for_a_peer_that_is_still_capturing(self) -> None:
        peer = collector.ROLES[1]
        (self.shared / f"{peer}-metadata.json").unlink()
        finished = threading.Event()
        outcome: list[Path] = []

        def win_the_lock() -> None:
            outcome.append(
                collector.rendezvous(self.shared, collector.ROLES[0], "a" * 64, self.plan)
            )
            finished.set()

        winner = threading.Thread(target=win_the_lock)
        winner.start()
        try:
            # The oracle must not run while one vantage is still capturing.
            self.assertFalse(finished.wait(timeout=1.0))
            self.assertFalse(self.runs.exists(), "oracle ran before the peer sealed")
            (self.shared / f"{peer}-metadata.json").write_text("{}", encoding="utf-8")
            self.assertTrue(finished.wait(timeout=30))
        finally:
            winner.join(timeout=30)
        self.assertEqual(self.runs.read_text(), "1")
        self.assertEqual(len(outcome), 1)
        self.assertTrue(outcome[0].is_file())

    def test_winner_fails_closed_when_the_oracle_skips_its_observation(self) -> None:
        silent = self.shared / "silent-oracle.py"
        silent.write_text("pass\n", encoding="utf-8")
        os.environ["RIPDPI_EVIDENCE_ORACLE"] = str(silent)
        with self.assertRaisesRegex(collector.CollectorError, "did not publish"):
            collector.rendezvous(self.shared, collector.ROLES[0], "a" * 64, self.plan)
        self.assertTrue((self.shared / "oracle.failed").exists())

    def test_loser_fails_closed_when_the_oracle_run_fails(self) -> None:
        (self.shared / "oracle.lock").touch()
        (self.shared / "oracle.failed").touch()
        with self.assertRaisesRegex(collector.CollectorError, "oracle failure"):
            collector.rendezvous(self.shared, collector.ROLES[1], "a" * 64, self.plan)

    def test_winner_fails_closed_without_a_source_bound_oracle(self) -> None:
        os.environ.pop("RIPDPI_EVIDENCE_ORACLE", None)
        with self.assertRaisesRegex(collector.CollectorError, "oracle path"):
            collector.rendezvous(self.shared, collector.ROLES[0], "a" * 64, self.plan)
        self.assertTrue((self.shared / "oracle.failed").exists())


class ObserverVantageScopeTest(unittest.TestCase):
    def tearDown(self) -> None:
        os.environ.pop("RIPDPI_EVIDENCE_OBSERVER_INTERFACE", None)

    def test_observer_watches_every_interface_by_default(self) -> None:
        """Loopback carries fixture traffic; a real leak egresses elsewhere."""
        os.environ.pop("RIPDPI_EVIDENCE_OBSERVER_INTERFACE", None)
        with tempfile.TemporaryDirectory() as temporary:
            backend = collector.ObserverCapture(Path(temporary), "a" * 64)
        self.assertEqual(backend.interface, "any")

    def test_narrowing_the_observer_is_an_explicit_opt_in(self) -> None:
        os.environ["RIPDPI_EVIDENCE_OBSERVER_INTERFACE"] = "eth0"
        with tempfile.TemporaryDirectory() as temporary:
            backend = collector.ObserverCapture(Path(temporary), "a" * 64)
        self.assertEqual(backend.interface, "eth0")


class CaptureMetadataTest(unittest.TestCase):
    def test_metadata_is_canonical_and_matches_the_oracle_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "metadata.json"
            document = {
                "captureFinishedAtEpoch": 2,
                "captureStartedAtEpoch": 1,
                "correlationId": "b" * 64,
                "packetCount": 3,
                "rawCaptureSha256": "d" * 64,
                "role": "client-underlay",
                "version": collector.CAPTURE_VERSION,
            }
            collector.write_private_json(path, document)
            raw = path.read_bytes()
            self.assertEqual(raw, collector.canonical_bytes(document))
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(json.loads(raw), document)


if __name__ == "__main__":
    unittest.main()
