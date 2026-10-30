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
        for previous, expected in (("No operations.", "default"), ("ACTIVATE_VPN: ignore; time=never", "ignore"),
                                   ("Uid mode: ACTIVATE_VPN: deny", "deny")):
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
                        self.assertIn("--uid", command.call_args_list[-1].args[0])
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


class SingleUnderlayTest(unittest.TestCase):
    def test_mobile_data_is_restored_only_when_it_was_enabled(self):
        for previous, restored in (("1", True), ("0", False)):
            for failure in (False, True):
                with self.subTest(previous=previous, failure=failure):
                    with patch.object(RUNNER, "checked", side_effect=[previous, "", ""]) as command:
                        try:
                            with RUNNER.emulator_single_underlay(["adb"]):
                                if failure:
                                    raise RuntimeError("unit failure")
                        except RuntimeError:
                            self.assertTrue(failure)
                        commands = [call.args[0][1:] for call in command.call_args_list]
                        self.assertEqual(commands[1], ["shell", "svc", "data", "disable"])
                        self.assertEqual(commands[2:], [["shell", "svc", "data", "enable"]] if restored else [])

    def test_unknown_prior_state_refuses_before_mutation(self):
        with patch.object(RUNNER, "checked", return_value="null") as command:
            with self.assertRaisesRegex(ValueError, "mobile data state"):
                with RUNNER.emulator_single_underlay(["adb"]):
                    self.fail("unknown OS state cannot be mutated")
            self.assertEqual(command.call_count, 1)


class IdleGuestTest(unittest.TestCase):
    def test_waits_for_boot_settle_time_and_load_at_the_core_count(self):
        samples = ["4",
                   "1", "26.1 46.3", "1.88 0.41 0.13 17/1118 1634",  # quiet before the post-boot storm
                   "1", "320.0 600.0", "62.48 24.85 9.43 31/2374 4350",
                   "1", "540.5 1075.9", "3.90 9.0 8.0 2/2392 5848"]
        with patch.object(RUNNER, "checked", side_effect=samples) as command, \
                patch.object(RUNNER.time, "sleep") as sleep:
            self.assertEqual(RUNNER.await_idle_guest(["adb"]), 3.9)
        self.assertEqual(command.call_count, len(samples))
        self.assertEqual(sleep.call_count, 2)

    def test_a_guest_that_never_settles_fails_before_any_run(self):
        with patch.object(RUNNER, "checked", side_effect=["4"] + ["1", "900.0 1.0", "62.0 0 0 1/1 1"] * 3), \
                patch.object(RUNNER.time, "sleep"), patch.object(RUNNER.time, "monotonic", side_effect=[0, 0, 5, 11]):
            with self.assertRaisesRegex(ValueError, "stayed busy"):
                RUNNER.await_idle_guest(["adb"], timeout=10)


class EvidenceValidationTest(unittest.TestCase):
    profile = "awg-3.1-ranges-v1"

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.sha = "a" * 40
        self.nonce = "unit-test-invocation"
        protected = self.profile == "amneziavpn-5x"
        tcp_bytes = 4 << 20 if protected else 100
        self.evidence = {
            "version": VALIDATOR.VERSION, "nonce": self.nonce, "source_sha": self.sha, "profile": self.profile, "candidate_patch": None,
            "scope": "native_core", "device_class": "emulator", "abi": "arm64-v8a", "api": 32,
            "peer": {"version": "v3.1.20260828", "commit": VALIDATOR.PEER_COMMIT, "go": "go1.27.3"},
            "artifacts": {name: self.artifact(name, b"unit fixture, not native code")
                          for name in ("native_library", "test_apk", "peer_binary")}, "cycles": [],
        }
        apk = io.BytesIO()
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libripdpi-amneziawg.so", b"unit fixture, not native code")
        self.evidence["artifacts"]["test_apk"] = self.artifact("test_apk", apk.getvalue())
        for mtu in (1280, 1420):
            for cycle in range(4):
                observations = []
                for family in (4, 6):
                    for protocol in ("tcp", "udp", "udp", "dns") + (("udp_series",) if protected else ()):
                        size = tcp_bytes if protocol == "tcp" else 100
                        observation = {"protocol": protocol, "family": family, "sent_bytes": size,
                                       "received_bytes": 120 if protocol == "dns" else size,
                                       "sent_sha256": "b" * 64, "received_sha256": "b" * 64}
                        if protocol == "dns":
                            observation.update(answer_type=1 if family == 4 else 28, transaction_id=0x4321)
                        if protocol == "udp_series":
                            observation.update(datagrams=512)
                        observations.append(observation)
                value = {"nonce": self.nonce, "profile": self.profile, "mtu": mtu, "cycle": cycle, "peer_port": 40000 + mtu, "abi": "arm64-v8a", "api": 32,
                         "socket_protection": {"attempted": 1, "successful": 1}, "measurements": observations}
                identity = "INSTRUMENTATION_STATUS: class=" + VALIDATOR.METHOD.split("#")[0] + "\n" + "INSTRUMENTATION_STATUS: test=" + VALIDATOR.METHOD.split("#")[1] + "\n"
                log = (identity + "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: awgEvidence=" + json.dumps(value) +
                       "\nINSTRUMENTATION_STATUS_CODE: 2\n" + identity + "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")
                value["instrumentation"] = self.artifact(f"cycle-{mtu}-{cycle}.txt", log.encode())
                self.evidence["cycles"].append(value)
        self.evidence["wire_observations"] = []
        for mtu in (1280, 1420):
            padding,hp,trailers,cpa=VALIDATOR.WIRE.PROFILES[self.profile]
            prefix = padding[3]
            empty = prefix + 32 + (cpa[0] if cpa else 1 if trailers else 0)
            count = (8*tcp_bytes + mtu-1)//mtu
            observation = {
                "schema_version": 2, "wire_profile": self.profile, "handshakes": 4,
                "fresh_signature_packets": 4, "signature_order": "I1-I5,Jc,initiation",
                "signature_tags": ["b", "r", "rd", "rc", "t"],
                "padding": padding,
                "evidence": {
                    "lengths": [[{str(padding[0]+148+(7 if trailers else 0)): 4}, None, None, {str(empty): 4, str(prefix+32+mtu): count}],
                                [None, {str(padding[1]+92+(11 if trailers else 0)): 4}, None, {str(prefix+32+mtu): count}]],
                    "authenticated_keepalives": 4, "ip_lengths": {str(mtu): count},
                    "peer_counters": {"rx_bytes": 592+4*(empty-prefix)+count*(mtu+32), "tx_bytes": count*(mtu+32),
                                      "last_handshake_time_sec": 1, "last_handshake_time_nsec": 0},
                },
            }
            if hp: observation["headers_in_clear"] = 0
            content = json.dumps({"wire_observation": observation}).encode()
            self.evidence["wire_observations"].append({"mtu": mtu, **observation, "artifact": self.artifact(f"peer-{mtu}.json", content)})
        self.evidence["negative_control"] = None
        if protected:
            record = {"nonce": self.nonce, "mtu": 1280, "abi": "arm64-v8a", "api": 32, "window_ms": 12000,
                      "telemetry_polls": 400, "reached_running": False}
            identity = "INSTRUMENTATION_STATUS: class=" + VALIDATOR.NEGATIVE_METHOD.split("#")[0] + "\n" + "INSTRUMENTATION_STATUS: test=" + VALIDATOR.NEGATIVE_METHOD.split("#")[1] + "\n"
            log = (identity + "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: awgNegativeEvidence=" + json.dumps(record) +
                   "\nINSTRUMENTATION_STATUS_CODE: 2\n" + identity + "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")
            self.evidence["negative_control"] = {**record, "instrumentation": self.artifact("negative.txt", log.encode()),
                                                 "peer_exit": 1, "peer_error": "incomplete exchanges: 0/8"}

    def artifact(self, name, content):
        (self.root / name).write_bytes(content)
        return {"path": name, "sha256": hashlib.sha256(content).hexdigest()}

    def validate(self, data=None, nonce=None):
        path = self.root / "evidence.json"
        path.write_text(json.dumps(self.evidence if data is None else data))
        return VALIDATOR.validate(path, nonce or self.nonce, self.sha)

    def test_complete_validator_unit_fixture_is_accepted(self):
        self.validate()

    def test_missing_authenticated_evidence_fails_after_artifact_digest_update(self):
        changed = copy.deepcopy(self.evidence)
        value = changed["wire_observations"][0]
        value["evidence"]["peer_counters"]["rx_bytes"] = 0
        observation = {k: v for k, v in value.items() if k not in ("mtu", "artifact")}
        value["artifact"] = self.artifact("missing-auth.json", json.dumps({"wire_observation": observation}).encode())
        with self.assertRaisesRegex(ValueError, "authenticated byte accounting"):
            self.validate(changed)

    def test_nonce_source_scope_and_missing_matrix_are_rejected(self):
        with self.assertRaises(ValueError):
            self.validate(nonce="different-invocation")
        for key, value in (("source_sha", "c" * 40), ("scope", "android_tun"), ("cycles", self.evidence["cycles"][:-1])):
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.validate({**self.evidence, key: value})

    def test_initial_plus_only_two_reconnects_is_refused(self):
        changed = copy.deepcopy(self.evidence)
        changed["cycles"] = [cycle for cycle in changed["cycles"] if cycle["cycle"] != 3]
        with self.assertRaisesRegex(ValueError, "initial session plus three reconnects"):
            self.validate(changed)

    def test_replacing_peer_between_reconnects_is_refused(self):
        changed = copy.deepcopy(self.evidence)
        changed["cycles"][1]["peer_port"] += 1
        with self.assertRaisesRegex(ValueError, "retain the independent peer endpoint"):
            self.validate(changed)

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

    def test_candidate_patch_must_match_its_digest(self):
        self.evidence["candidate_patch"] = self.artifact("candidate.patch", b"diff --git a/x b/x\n")
        self.validate()
        (self.root / "candidate.patch").write_bytes(b"different patch")
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            self.validate()

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


class AmneziaVpn5xEvidenceTest(EvidenceValidationTest):
    """The header-protected S1-S4 = 12 profile also needs bulk payloads, peer observations and a negative control."""

    profile = "amneziavpn-5x"

    def test_wrong_key_that_completes_an_exchange_is_rejected(self):
        for key, value in (("peer_exit", 0), ("peer_error", "incomplete exchanges: 1/8"), ("reached_running", True)):
            with self.subTest(key=key):
                changed = copy.deepcopy(self.evidence)
                changed["negative_control"][key] = value
                with self.assertRaises(ValueError):
                    self.validate(changed)

    def test_missing_negative_control_is_rejected(self):
        with self.assertRaises((ValueError, TypeError)):
            self.validate({**self.evidence, "negative_control": None})

    def test_small_tcp_payload_or_clear_headers_are_rejected(self):
        changed = copy.deepcopy(self.evidence)
        for value in changed["cycles"][0]["measurements"]:
            if value["protocol"] == "tcp":
                value["sent_bytes"] = value["received_bytes"] = 4096
        with self.assertRaises(ValueError):
            self.validate(changed)
        changed = copy.deepcopy(self.evidence)
        observation = {k: v for k, v in changed["wire_observations"][0].items() if k not in ("mtu", "artifact")}
        observation["headers_in_clear"] = 1
        changed["wire_observations"][0] = {"mtu": 1280, **observation,
                                           "artifact": self.artifact("clear.json", json.dumps({"wire_observation": observation}).encode())}
        with self.assertRaisesRegex(ValueError, "protected header exposed"):
            self.validate(changed)

    def test_udp_series_is_required(self):
        changed = copy.deepcopy(self.evidence)
        changed["cycles"][0]["measurements"] = [v for v in changed["cycles"][0]["measurements"] if v["protocol"] != "udp_series"]
        with self.assertRaises(ValueError):
            self.validate(changed)




class CpaAndroidEvidence(EvidenceValidationTest):
    profile="v3-cpa"

class TrailerAndroidEvidence(EvidenceValidationTest):
    profile="v3-trailers"

class CombinedAndroidEvidence(EvidenceValidationTest):
    profile="v3-cpa-trailers"

class AlignedAndroidEvidence(EvidenceValidationTest):
    profile="v3-aligned"

class FixedAndroidEvidence(EvidenceValidationTest):
    profile="v3-fixed-headers"


if __name__ == "__main__":
    unittest.main()
