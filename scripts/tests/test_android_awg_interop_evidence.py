"""Offline runner/validator regression cases, not Android runtime evidence."""

import copy
import hashlib
import importlib.util
import json
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("awg_evidence", Path(__file__).with_name("validate-android-awg-interop.py"))
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)
RUNNER_SPEC = importlib.util.spec_from_file_location("awg_runner", Path(__file__).with_name("run-android-awg-interop.py"))
RUNNER = importlib.util.module_from_spec(RUNNER_SPEC)
RUNNER_SPEC.loader.exec_module(RUNNER)


class AuthorizationRestorationTest(unittest.TestCase):
    def test_previous_mode_is_restored_after_success_or_failure(self):
        for previous, expected in (("No operations.", "default"), ("ACTIVATE_VPN: ignore; time=never", "ignore")):
            for failure in (False, True):
                with self.subTest(previous=previous, failure=failure):
                    with patch.object(RUNNER, "checked", side_effect=[previous, "", ""]) as command:
                        try:
                            with RUNNER.emulator_vpn_authorization(["adb", "-s", "emulator-unit"]):
                                if failure:
                                    raise RuntimeError("unit failure")
                        except RuntimeError:
                            self.assertTrue(failure)
                        self.assertEqual(command.call_args_list[-1].args[0][-1], expected)
                        self.assertEqual(command.call_count, 3)

    def test_grant_failure_also_restores_previous_mode(self):
        with patch.object(RUNNER, "checked", side_effect=["No operations.", OSError("unit denial"), ""]) as command:
            with self.assertRaises(OSError):
                with RUNNER.emulator_vpn_authorization(["adb"]):
                    self.fail("a failed OS grant cannot start the native test")
            self.assertEqual(command.call_args_list[-1].args[0][-1], "default")

    def test_unknown_prior_mode_refuses_before_mutation(self):
        with patch.object(RUNNER, "checked", return_value="unrecognized output") as command:
            with self.assertRaisesRegex(ValueError, "prior emulator VPN authorization"):
                with RUNNER.emulator_vpn_authorization(["adb"]):
                    self.fail("unknown OS state cannot be mutated")
            self.assertEqual(command.call_count, 1)


class EvidenceValidationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.sha = "a" * 40
        self.nonce = "unit-test-invocation"
        self.evidence = {
            "version": VALIDATOR.VERSION, "nonce": self.nonce, "source_sha": self.sha,
            "scope": "native_core", "device_class": "emulator", "abi": "arm64-v8a", "api": 32,
            "peer": {"version": "v0.2.12", "commit": VALIDATOR.PEER_COMMIT, "go": "go1.27.1"},
            "artifacts": {name: self.artifact(name, b"unit fixture, not native code")
                          for name in ("native_library", "test_apk", "peer_binary")}, "cycles": [],
        }
        apk = io.BytesIO()
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libripdpi-amneziawg.so", b"unit fixture, not native code")
        self.evidence["artifacts"]["test_apk"] = self.artifact("test_apk", apk.getvalue())
        for mtu in (1280, 1420):
            for cycle in (1, 2, 3):
                observations = []
                for family in (4, 6):
                    for protocol in ("tcp", "udp", "udp", "dns"):
                        observation = {"protocol": protocol, "family": family, "sent_bytes": 100,
                                       "received_bytes": 120 if protocol == "dns" else 100,
                                       "sent_sha256": "b" * 64, "received_sha256": "b" * 64}
                        if protocol == "dns":
                            observation.update(answer_type=1 if family == 4 else 28, transaction_id=0x4321)
                        observations.append(observation)
                value = {"nonce": self.nonce, "mtu": mtu, "cycle": cycle, "abi": "arm64-v8a", "api": 32,
                         "socket_protection": {"attempted": 1, "successful": 1}, "measurements": observations}
                identity = "INSTRUMENTATION_STATUS: class=" + VALIDATOR.METHOD.split("#")[0] + "\n" + "INSTRUMENTATION_STATUS: test=" + VALIDATOR.METHOD.split("#")[1] + "\n"
                log = (identity + "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: awgEvidence=" + json.dumps(value) +
                       "\nINSTRUMENTATION_STATUS_CODE: 2\n" + identity + "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")
                value["instrumentation"] = self.artifact(f"cycle-{mtu}-{cycle}.txt", log.encode())
                self.evidence["cycles"].append(value)

    def artifact(self, name, content):
        (self.root / name).write_bytes(content)
        return {"path": name, "sha256": hashlib.sha256(content).hexdigest()}

    def validate(self, data=None, nonce=None):
        path = self.root / "evidence.json"
        path.write_text(json.dumps(self.evidence if data is None else data))
        return VALIDATOR.validate(path, nonce or self.nonce, self.sha)

    def test_complete_validator_unit_fixture_is_accepted(self):
        self.validate()

    def test_nonce_source_scope_and_missing_matrix_are_rejected(self):
        with self.assertRaises(ValueError):
            self.validate(nonce="different-invocation")
        for key, value in (("source_sha", "c" * 40), ("scope", "android_tun"), ("cycles", self.evidence["cycles"][:-1])):
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.validate({**self.evidence, key: value})

    def test_tampered_measurements_or_artifacts_are_rejected(self):
        changed = copy.deepcopy(self.evidence)
        changed["cycles"][0]["measurements"][0]["received_bytes"] = 0
        with self.assertRaises(ValueError):
            self.validate(changed)
        (self.root / "native_library").write_bytes(b"tampered")
        with self.assertRaises(ValueError):
            self.validate()

    def test_missing_rejected_or_untyped_android_protection_is_refused(self):
        for protection in ({}, {"attempted": 1, "successful": 0}, {"attempted": True, "successful": True}):
            with self.subTest(protection=protection):
                changed = copy.deepcopy(self.evidence)
                changed["cycles"][0]["socket_protection"] = protection
                with self.assertRaisesRegex(ValueError, "socket protection absent"):
                    self.validate(changed)

    def test_artifact_escape_is_rejected(self):
        changed = copy.deepcopy(self.evidence)
        changed["artifacts"]["native_library"]["path"] = "../foreign-library"
        with self.assertRaises(ValueError):
            self.validate(changed)

    def test_apk_with_wrong_packaged_library_is_rejected(self):
        apk = io.BytesIO()
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libripdpi-amneziawg.so", b"different unit fixture")
        self.evidence["artifacts"]["test_apk"] = self.artifact("test_apk", apk.getvalue())
        with self.assertRaisesRegex(ValueError, "different native library"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
