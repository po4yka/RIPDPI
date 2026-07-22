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


def valid_receipt() -> dict[str, object]:
    return {
        "version": module.VERSION,
        "status": "PASS",
        "gateId": module.GATE_ID,
        "kind": module.KIND,
        "selector": module.SELECTOR,
        "correlationId": CORRELATION_ID,
        "sourceSha": SOURCE_SHA,
        "clientArtifactSha256": CLIENT_SHA,
        "testArtifactSha256": TEST_SHA,
        "fixtureIdentitySha256": FIXTURE_SHA,
        "actionMarkerSha256": module.marker_sha256(CORRELATION_ID, "action"),
        "outcomeMarkerSha256": module.marker_sha256(CORRELATION_ID, "outcome"),
        "startedAtElapsedRealtimeMs": 100,
        "actionMarkerAtElapsedRealtimeMs": 110,
        "outcomeMarkerAtElapsedRealtimeMs": 300,
        "finishedAtElapsedRealtimeMs": 400,
        "appAndTestUidsDistinct": True,
        "actionMarkerRanAsTargetApp": True,
        "outcomeMarkerRanAsTargetApp": True,
        "dnsProbeRanAsAndroidTest": True,
        "actionMarkerPidObserved": True,
        "outcomeMarkerPidObserved": True,
        "dnsProbePidObserved": True,
        "tunFdObserved": True,
        "closedWindowRunningCount": 0,
        "preReadyDnsEventCount": 0,
        "startupWindowAssertionElapsedMs": 300,
        "dnsRcode": 0,
        "dnsAnswersExact": True,
        "postReadyDnsEventCount": 1,
        "txPackets": 2,
        "rxPackets": 1,
        "finalStatus": "Halted",
        "gateClean": True,
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
        )

    def test_valid_receipt(self) -> None:
        self.validate(valid_receipt())

    def test_unknown_or_missing_fields_fail(self) -> None:
        missing = valid_receipt()
        del missing["gateClean"]
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
            "appAndTestUidsDistinct": False,
            "actionMarkerRanAsTargetApp": False,
            "outcomeMarkerRanAsTargetApp": False,
            "dnsProbeRanAsAndroidTest": False,
            "actionMarkerPidObserved": False,
            "outcomeMarkerPidObserved": False,
            "dnsProbePidObserved": False,
            "tunFdObserved": False,
            "closedWindowRunningCount": 1,
            "preReadyDnsEventCount": 1,
            "startupWindowAssertionElapsedMs": 4_000,
            "dnsRcode": 2,
            "dnsAnswersExact": False,
            "postReadyDnsEventCount": 0,
            "txPackets": 0,
            "rxPackets": 0,
            "finalStatus": "Running",
            "gateClean": False,
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
        receipt["closedWindowRunningCount"] = False
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
