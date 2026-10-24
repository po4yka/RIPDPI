"""Authenticated Android migration evidence rejects stale or untested exports."""

import importlib.util
import json
import hashlib
from pathlib import Path
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "ci/run-android-local-acceptance.py"
SPEC = importlib.util.spec_from_file_location("migration_runner", SOURCE)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class MigrationReceiptTests(unittest.TestCase):
    def identity(self):
        return {
            "migration_id": "11111111-1111-4111-8111-111111111111",
            "delivery_id": "22222222-2222-4222-8222-222222222222",
            "payload_sha256": "b" * 64,
            "receipt_key": "a" * 64,
        }

    def receipt(self):
        return {
            "schema_version": 1,
            "migration_id": "11111111-1111-4111-8111-111111111111",
            "delivery_id": "22222222-2222-4222-8222-222222222222",
            "payload_sha256": "b" * 64,
            "state": "path-tested",
            "tested_tag": "replacement",
            "received_at_ms": 1700000000000,
            "tested_at_ms": 1700000001000,
            "mac": "5f9fa66a746dc0544301b19267b463801afdcd067f8af699dd81f670010f2d55",
        }

    def test_known_v1_export_matches_v2_payload_identity(self):
        runner.validate_migration_receipt(self.receipt(), self.identity())

    def test_received_stale_forged_and_unordered_exports_fail(self):
        for key, value in (
            ("state", "received"),
            ("tested_tag", "old"),
            ("payload_sha256", "c" * 64),
            ("migration_id", "another"),
            ("delivery_id", "another"),
            ("mac", "0" * 64),
            ("tested_at_ms", 1699999999999),
            ("received_at_ms", True),
            ("schema_version", 2),
        ):
            with self.subTest(key=key):
                receipt = self.receipt()
                receipt[key] = value
                with self.assertRaises(ValueError):
                    runner.validate_migration_receipt(receipt, self.identity())

    def test_update_replaces_endpoint_and_identity_before_requesting_receipt(self):
        identity = self.identity()
        old = {"tcpPort": 1234, "uuid": "old-uuid", "publicKey": "old-key"}
        reserve = {"tcpPort": 2345, "uuid": "reserve-uuid", "publicKey": "reserve-key"}
        replacement = {
            "tcpPort": 3456,
            "uuid": "replacement-uuid",
            "publicKey": "replacement-key",
        }
        initial, updated = runner.migration_payloads(
            old, reserve, replacement, "10.0.2.2", 4567, "owned-token", identity
        )
        self.assertEqual(
            [o["tag"] for o in json.loads(initial)["outbounds"]],
            ["select", "old", "reserve"],
        )
        bundle = json.loads(updated)
        self.assertEqual(
            [o["tag"] for o in bundle["outbounds"]],
            ["select", "reserve", "replacement"],
        )
        self.assertEqual(bundle["outbounds"][2]["uuid"], "replacement-uuid")
        self.assertEqual(bundle["outbounds"][2]["server_port"], 3456)
        self.assertEqual(bundle["ripdpi"]["migration"]["target_tags"], ["replacement"])
        self.assertEqual(
            identity["payload_sha256"], hashlib.sha256(updated).hexdigest()
        )

    def test_wrong_device_receipt_key_fails(self):
        identity = self.identity()
        identity["receipt_key"] = "c" * 64
        with self.assertRaises(ValueError):
            runner.validate_migration_receipt(self.receipt(), identity)


if __name__ == "__main__":
    unittest.main()
