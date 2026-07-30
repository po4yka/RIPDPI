#!/usr/bin/env python3
"""Contract tests for the emulator evidence runner config and its allowlist.

The producer allowlist pins hook digests, so editing a hook without updating
`network-evidence-producers.json` would leave the lane refusing its own
producers at runtime. That drift is invisible until a release run, so it is
asserted here instead.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GENERATOR = ROOT / "test-lab/scripts/write-emulator-evidence-runner-config.py"
COLLECTOR = ROOT / "test-lab/scripts/network-evidence-emulator-collector.py"
WORKLOAD = ROOT / "test-lab/scripts/network-evidence-emulator-workload.py"
PRODUCERS = ROOT / "quality/release-gates/network-evidence-producers.json"
HEX64 = set("0123456789abcdef")

COLLECTOR_SPEC = importlib.util.spec_from_file_location("emulator_collector", COLLECTOR)
assert COLLECTOR_SPEC is not None and COLLECTOR_SPEC.loader is not None
collector_module = importlib.util.module_from_spec(COLLECTOR_SPEC)
COLLECTOR_SPEC.loader.exec_module(collector_module)
WORKLOAD_SPEC = importlib.util.spec_from_file_location("emulator_workload", WORKLOAD)
assert WORKLOAD_SPEC is not None and WORKLOAD_SPEC.loader is not None
workload_module = importlib.util.module_from_spec(WORKLOAD_SPEC)
WORKLOAD_SPEC.loader.exec_module(workload_module)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ProducerAllowlistTest(unittest.TestCase):
    def setUp(self) -> None:
        self.policy = json.loads(PRODUCERS.read_text(encoding="utf-8"))

    def test_allowlist_pins_the_current_hook_digests(self) -> None:
        collector = digest(COLLECTOR)
        workload = digest(WORKLOAD)
        self.assertIn(collector, self.policy["clientCollectorSha256"])
        self.assertIn(collector, self.policy["observerCollectorSha256"])
        self.assertIn(workload, self.policy["workloadSha256"])

    def test_every_allowlisted_digest_is_a_lowercase_sha256(self) -> None:
        for field in (
            "clientCollectorSha256",
            "observerCollectorSha256",
            "workloadSha256",
        ):
            for value in self.policy[field]:
                with self.subTest(field=field, value=value):
                    self.assertEqual(len(value), 64)
                    self.assertTrue(set(value) <= HEX64)


class CollectorArtifactOrderingTest(unittest.TestCase):
    def test_collector_and_workload_agree_on_transcript_rules(self) -> None:
        self.assertEqual(
            collector_module.SOURCE_OWNED_TRANSCRIPT_RULES,
            workload_module.SOURCE_OWNED_TRANSCRIPT_RULES,
        )

    def setUp(self) -> None:
        self.plan = {
            "windows": [
                {"id": "dns-virtual-vpn-resolver"},
                {"id": "dns-allowlisted-bootstrap-resolution"},
                {"id": "dns-no-isp-fallback-on-encrypted-resolver-outage"},
                {"id": "killswitch-tun-establish-native-ready"},
            ]
        }
        self.receipts = {
            gate_id: Path(f"/{gate_id}-receipt.json")
            for gate_id in reversed([window["id"] for window in self.plan["windows"]])
        }
        self.transcripts = {
            "dns-no-isp-fallback-on-encrypted-resolver-outage": Path("/outage.json"),
            "dns-virtual-vpn-resolver": Path("/virtual.json"),
        }

    def test_artifacts_follow_scenario_order_not_filename_order(self) -> None:
        receipts, transcripts = collector_module.ordered_action_artifacts(
            self.plan, self.receipts, self.transcripts
        )

        self.assertEqual(
            receipts,
            [self.receipts[window["id"]] for window in self.plan["windows"]],
        )
        self.assertEqual(
            transcripts,
            [
                self.transcripts["dns-virtual-vpn-resolver"],
                self.transcripts["dns-no-isp-fallback-on-encrypted-resolver-outage"],
            ],
        )

    def test_extra_or_missing_artifacts_fail_closed(self) -> None:
        for receipts, transcripts in (
            ({**self.receipts, "unexpected": Path("/extra.json")}, self.transcripts),
            (
                {
                    key: value
                    for key, value in self.receipts.items()
                    if key != "killswitch-tun-establish-native-ready"
                },
                self.transcripts,
            ),
            (self.receipts, {}),
            (self.receipts, {**self.transcripts, "unexpected": Path("/extra.json")}),
        ):
            with self.subTest(receipts=receipts, transcripts=transcripts):
                with self.assertRaises(collector_module.CollectorError):
                    collector_module.ordered_action_artifacts(
                        self.plan, receipts, transcripts
                    )


class RunnerConfigGeneratorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name) / "private"
        completed = subprocess.run(
            [sys.executable, str(GENERATOR), "--directory", str(self.directory)],
            capture_output=True,
            timeout=120,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr.decode())
        self.config_path = Path(completed.stdout.decode().strip())
        self.config = json.loads(self.config_path.read_text(encoding="utf-8"))

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_config_matches_the_runner_v1_field_contract(self) -> None:
        self.assertEqual(
            set(self.config),
            {
                "version",
                "clientHook",
                "observerHook",
                "workloadHook",
                "clientVantageId",
                "observerVantageId",
                "clientNetworkId",
                "observerNetworkId",
            },
        )
        self.assertEqual(self.config["version"], "ripdpi_network_evidence_runner_v1")

    def test_config_is_private_and_the_directory_is_not_traversable(self) -> None:
        self.assertEqual(self.config_path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.directory.stat().st_mode & 0o777, 0o700)

    def test_hooks_are_absolute_executable_regular_files(self) -> None:
        for field in ("clientHook", "observerHook", "workloadHook"):
            path = Path(self.config[field])
            with self.subTest(field=field):
                self.assertTrue(path.is_absolute())
                self.assertFalse(path.is_symlink())
                self.assertTrue(path.is_file())
                self.assertTrue(path.stat().st_mode & 0o111)

    def test_collector_copies_are_identical_content_on_distinct_inodes(self) -> None:
        client = Path(self.config["clientHook"])
        observer = Path(self.config["observerHook"])
        self.assertEqual(client.read_bytes(), observer.read_bytes())
        self.assertNotEqual(client.stat().st_ino, observer.stat().st_ino)

    def test_deployed_hooks_match_the_allowlisted_digests(self) -> None:
        policy = json.loads(PRODUCERS.read_text(encoding="utf-8"))
        self.assertIn(
            digest(Path(self.config["clientHook"])), policy["clientCollectorSha256"]
        )
        self.assertIn(
            digest(Path(self.config["observerHook"])), policy["observerCollectorSha256"]
        )
        self.assertIn(digest(Path(self.config["workloadHook"])), policy["workloadSha256"])

    def test_the_four_identifiers_are_distinct_random_hex(self) -> None:
        fields = (
            "clientVantageId",
            "observerVantageId",
            "clientNetworkId",
            "observerNetworkId",
        )
        values = [self.config[field] for field in fields]
        self.assertEqual(len(set(values)), len(fields))
        for value in values:
            self.assertEqual(len(value), 64)
            self.assertTrue(set(value) <= HEX64)

    def test_identifiers_are_not_reused_between_runs(self) -> None:
        with tempfile.TemporaryDirectory() as other:
            completed = subprocess.run(
                [
                    sys.executable,
                    str(GENERATOR),
                    "--directory",
                    str(Path(other) / "private"),
                ],
                capture_output=True,
                timeout=120,
                check=False,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr.decode())
            second = json.loads(
                Path(completed.stdout.decode().strip()).read_text(encoding="utf-8")
            )
        for field in ("clientVantageId", "observerVantageId", "clientNetworkId"):
            with self.subTest(field=field):
                self.assertNotEqual(self.config[field], second[field])


if __name__ == "__main__":
    unittest.main()
