#!/usr/bin/env python3

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts/ci/check_android_network_action_receipt.py"
SPEC = importlib.util.spec_from_file_location(
    "android_network_action_receipt", MODULE_PATH
)
assert SPEC is not None and SPEC.loader is not None
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

SOURCE_SHA = "1" * 40
CORRELATION_ID = "2" * 64
CLIENT_SHA = "3" * 64
TEST_SHA = "4" * 64
FIXTURE_SHA = "5" * 64
TEST_READY_OVERRIDE = (
    ROOT / "scripts/tests/fixtures/android-network-evidence-test-ready-override.json"
)


def valid_receipt() -> dict[str, object]:
    facts = module.example_valid_facts(module.GATE_ID)
    return {
        "version": module.VERSION,
        "status": "PASS",
        "gateId": module.GATE_ID,
        "kind": module.KIND,
        "selector": module.SELECTOR,
        "semanticRule": module.load_action_registry()[module.GATE_ID].semantic_rule,
        "correlationId": CORRELATION_ID,
        "sourceSha": SOURCE_SHA,
        "clientArtifactSha256": CLIENT_SHA,
        "testArtifactSha256": TEST_SHA,
        "fixtureIdentitySha256": FIXTURE_SHA,
        "actionMarkerSha256": module.marker_sha256(CORRELATION_ID, "action"),
        "outcomeMarkerSha256": module.marker_sha256(CORRELATION_ID, "outcome"),
        "querySetSha256": module.query_set_sha256(module.GATE_ID, facts),
        "startedAtElapsedRealtimeMs": 100,
        "actionMarkerAtElapsedRealtimeMs": 110,
        "outcomeMarkerAtElapsedRealtimeMs": 300,
        "finishedAtElapsedRealtimeMs": 400,
        "appUid": 10101,
        "testUid": 10102,
        "actionMarkerPid": 201,
        "actionMarkerUid": 10101,
        "outcomeMarkerPid": 201,
        "outcomeMarkerUid": 10101,
        "dnsProbePids": [301],
        "dnsProbeUid": 10102,
        "facts": facts,
    }


class AndroidNetworkActionReceiptTest(unittest.TestCase):
    def validate(self, receipt: dict[str, object]) -> None:
        module.validate_receipt(
            receipt,
            source_sha=SOURCE_SHA,
            correlation_id=CORRELATION_ID,
            client_artifact_sha256=CLIENT_SHA,
            test_artifact_sha256=TEST_SHA,
            fixture_identity_sha256=FIXTURE_SHA,
            test_only_ready_override=TEST_READY_OVERRIDE,
        )

    def test_valid_receipt(self) -> None:
        self.validate(valid_receipt())

    def test_valid_receipt_accepts_one_retained_startup_datagram(self) -> None:
        receipt = valid_receipt()
        receipt["facts"]["postReadyDnsEventCount"] = 2
        self.validate(receipt)

    def test_post_ready_dns_event_count_is_bounded(self) -> None:
        for count in (0, 3):
            with self.subTest(count=count):
                receipt = valid_receipt()
                receipt["facts"]["postReadyDnsEventCount"] = count
                with self.assertRaisesRegex(module.ReceiptError, "postReadyDnsEventCount"):
                    self.validate(receipt)

    def test_unknown_or_missing_fields_fail(self) -> None:
        missing = valid_receipt()
        del missing["facts"]
        with self.assertRaisesRegex(module.ReceiptError, "fields mismatch"):
            self.validate(missing)
        unknown = valid_receipt()
        unknown["deviceSerial"] = "forbidden"
        with self.assertRaisesRegex(module.ReceiptError, "fields mismatch"):
            self.validate(unknown)

    def test_attribution_and_marker_tampering_fail(self) -> None:
        mutations = {
            "sourceSha": "6" * 40,
            "clientArtifactSha256": "6" * 64,
            "testArtifactSha256": "6" * 64,
            "fixtureIdentitySha256": "6" * 64,
            "actionMarkerSha256": "6" * 64,
            "outcomeMarkerSha256": "6" * 64,
            "querySetSha256": "malformed",
            "selector": "com.example.Wrong#test",
        }
        for field, replacement in mutations.items():
            with self.subTest(field=field):
                receipt = valid_receipt()
                receipt[field] = replacement
                with self.assertRaises(module.ReceiptError):
                    self.validate(receipt)

    def test_identity_observations_and_semantic_facts_fail_closed(self) -> None:
        mutations = {
            "appUid": 0,
            "testUid": 10101,
            "actionMarkerPid": 0,
            "actionMarkerUid": 10102,
            "outcomeMarkerPid": 0,
            "outcomeMarkerUid": 10102,
            "dnsProbePids": [],
            "dnsProbeUid": 10101,
        }
        for field, replacement in mutations.items():
            with self.subTest(field=field):
                receipt = valid_receipt()
                receipt[field] = replacement
                with self.assertRaises(module.ReceiptError):
                    self.validate(receipt)

    def test_elapsed_realtime_must_be_ordered(self) -> None:
        receipt = valid_receipt()
        receipt["outcomeMarkerAtElapsedRealtimeMs"] = receipt[
            "actionMarkerAtElapsedRealtimeMs"
        ]
        with self.assertRaisesRegex(module.ReceiptError, "ordering"):
            self.validate(receipt)

    def test_private_file_loader_rejects_mode_and_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            receipt_path = root / "receipt.json"
            receipt_path.write_text(json.dumps(valid_receipt()), encoding="utf-8")
            os.chmod(receipt_path, 0o644)
            with self.assertRaisesRegex(module.ReceiptError, "0600"):
                module.load_private_receipt(receipt_path)
            os.chmod(receipt_path, 0o600)
            value, raw = module.load_private_receipt(receipt_path)
            self.assertEqual(value, valid_receipt())
            self.assertEqual(
                hashlib.sha256(raw).hexdigest(),
                hashlib.sha256(receipt_path.read_bytes()).hexdigest(),
            )
            link = root / "receipt-link.json"
            link.symlink_to(receipt_path)
            with self.assertRaises(OSError):
                module.load_private_receipt(link)

    def test_boolean_is_not_accepted_as_integer(self) -> None:
        receipt = copy.deepcopy(valid_receipt())
        receipt["facts"]["closedWindowRunningCount"] = False
        with self.assertRaises(module.ReceiptError):
            self.validate(receipt)

    def test_private_file_loader_rejects_duplicate_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            receipt_path = Path(temporary) / "receipt.json"
            receipt_path.write_text(
                '{"status":"FAIL","status":"PASS"}', encoding="utf-8"
            )
            receipt_path.chmod(0o600)
            with self.assertRaisesRegex(
                module.ReceiptError, "duplicate JSON key: status"
            ):
                module.load_private_receipt(receipt_path)


if __name__ == "__main__":
    unittest.main()
