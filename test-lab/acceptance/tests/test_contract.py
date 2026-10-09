"""Adversarial evidence checks; fixtures are not product acceptance evidence."""

import copy
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from contract import ContractError, validate_manifest, validate_result


def manifest():
    return {
        "schema_version": 1,
        "profiles": {"core": ["android-xray"]},
        "external_boundaries": [
            {"id": "cloudflare", "reason": "Needs provider service"}
        ],
        "scenarios": [
            {
                "id": "android-xray",
                "adapter": "android",
                "protocol": "vless",
                "tier": "android-tun",
                "platforms": ["darwin", "linux"],
                "timeout_seconds": 600,
                "checks": ["payload", "no-bypass"],
            }
        ],
    }


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "receipts.json").write_text('{"request":"unique"}')
        self.scenario = manifest()["scenarios"][0]
        self.result = {
            "schema_version": 1,
            "scenario_id": "android-xray",
            "run_id": "run-1",
            "source_sha": "a" * 40,
            "tier": "android-tun",
            "status": "passed",
            "checks": [
                {"id": "payload", "passed": True},
                {"id": "no-bypass", "passed": True},
            ],
            "artifacts": ["receipts.json"],
            "cleanup": {"passed": True},
        }

    def check(self):
        return validate_result(self.result, self.scenario, "run-1", "a" * 40, self.root)

    def test_valid_evidence_is_hashed(self):
        self.assertEqual(64, len(self.check()["artifact_hashes"]["receipts.json"]))

    def test_valid_manifest(self):
        validate_manifest(manifest())

    def test_manifest_rejects_duplicate_scenario(self):
        m = manifest()
        m["scenarios"].append(copy.deepcopy(m["scenarios"][0]))
        with self.assertRaises(ContractError):
            validate_manifest(m)

    def test_manifest_rejects_arbitrary_command(self):
        m = manifest()
        m["scenarios"][0]["adapter"] = "../../bin/sh"
        with self.assertRaises(ContractError):
            validate_manifest(m)

    def test_manifest_rejects_empty_profile(self):
        m = manifest()
        m["profiles"]["core"] = []
        with self.assertRaises(ContractError):
            validate_manifest(m)

    def test_manifest_rejects_unknown_scenario(self):
        m = manifest()
        m["profiles"]["core"] = ["missing"]
        with self.assertRaises(ContractError):
            validate_manifest(m)

    def test_manifest_rejects_weak_empty_checks(self):
        m = manifest()
        m["scenarios"][0]["checks"] = []
        with self.assertRaises(ContractError):
            validate_manifest(m)

    def test_manifest_rejects_boolean_timeout(self):
        m = manifest()
        m["scenarios"][0]["timeout_seconds"] = True
        with self.assertRaises(ContractError):
            validate_manifest(m)

    def test_result_rejects_stale_identity(self):
        for key, value in [
            ("run_id", "old"),
            ("source_sha", "b" * 40),
            ("scenario_id", "other"),
            ("tier", "native-contract"),
        ]:
            with self.subTest(key=key):
                original = self.result[key]
                self.result[key] = value
                with self.assertRaises(ContractError):
                    self.check()
                self.result[key] = original

    def test_result_rejects_missing_check(self):
        self.result["checks"].pop()
        with self.assertRaises(ContractError):
            self.check()

    def test_result_rejects_duplicate_check(self):
        self.result["checks"].append(copy.deepcopy(self.result["checks"][0]))
        with self.assertRaises(ContractError):
            self.check()

    def test_result_rejects_nonboolean_pass(self):
        self.result["checks"][0]["passed"] = "true"
        with self.assertRaises(ContractError):
            self.check()

    def test_result_rejects_failed_or_skipped_check(self):
        for value in [False, None]:
            self.result["checks"][0]["passed"] = value
            with self.assertRaises(ContractError):
                self.check()

    def test_result_rejects_failed_cleanup(self):
        self.result["cleanup"]["passed"] = False
        with self.assertRaises(ContractError):
            self.check()

    def test_result_rejects_skipped_status(self):
        self.result["status"] = "skipped"
        with self.assertRaises(ContractError):
            self.check()

    def test_result_rejects_missing_artifacts(self):
        self.result["artifacts"] = ["absent.json"]
        with self.assertRaises(ContractError):
            self.check()

    def test_result_rejects_escape_and_absolute_artifacts(self):
        for value in ["../receipts.json", str(self.root / "receipts.json")]:
            self.result["artifacts"] = [value]
            with self.assertRaises(ContractError):
                self.check()

    def test_result_rejects_symlink_artifacts(self):
        (self.root / "alias.json").symlink_to(self.root / "receipts.json")
        self.result["artifacts"] = ["alias.json"]
        with self.assertRaises(ContractError):
            self.check()

    def test_result_rejects_raw_pcap_and_private_keys(self):
        for name in ["raw.pcap", "secret.key", "raw.pcapng"]:
            (self.root / name).write_bytes(b"private")
            self.result["artifacts"] = [name]
            with self.assertRaises(ContractError):
                self.check()


if __name__ == "__main__":
    unittest.main()
