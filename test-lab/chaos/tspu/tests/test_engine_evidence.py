"""Fail-closed evidence tests; synthetic parser inputs are never release receipts."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from runner import engine_evidence as engine
from runner.chlo_builder import build_clienthello_with_sni


def packets(chunks):
    result = []
    for seq, payload in chunks:
        result.append({"_source": {"layers": {"ip": {"ip.src": "127.0.0.1", "ip.dst": "127.0.0.1"}, "tcp": {
            "tcp.dstport": "45678", "tcp.stream": "7", "tcp.srcport": "50000", "tcp.seq_raw": str(seq),
            "tcp.len": str(len(payload)), "tcp.payload": payload.hex(":")}}}})
    return result


class EngineEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.hello = build_clienthello_with_sni("fixture.test")
        self.split = packets([(100, self.hello[:3]), (103, self.hello[3:])])

    def test_complete_control_and_exact_split_classifier_coverage(self):
        control = engine.analyze_packets(packets([(100, self.hello)]), 45678, "fixture.test", False)
        candidate = engine.analyze_packets(self.split, 45678, "fixture.test", True)
        self.assertEqual(control["clientHelloSha256"], candidate["clientHelloSha256"])
        self.assertTrue(all(control["matched"].values()))
        self.assertFalse(any(candidate["matched"].values()))

    def test_missing_raw_bytes_and_zero_length_cannot_pass(self):
        for key in ["tcp.payload", "tcp.seq_raw"]:
            bad = copy.deepcopy(self.split)
            del bad[0]["_source"]["layers"]["tcp"][key]
            with self.assertRaises((ValueError, TypeError)):
                engine.analyze_packets(bad, 45678, "fixture.test", True)
        bad = copy.deepcopy(self.split)
        bad[0]["_source"]["layers"]["tcp"]["tcp.len"] = "0"
        with self.assertRaises(ValueError):
            engine.analyze_packets(bad, 45678, "fixture.test", True)

    def test_distinct_missing_or_nonloopback_flows_cannot_be_reassembled(self):
        for group, key, value in [("ip", "ip.dst", "127.0.0.2"), ("ip", "ip.src", "192.0.2.1"),
                                  ("tcp", "tcp.stream", "8"), ("tcp", "tcp.srcport", "50001")]:
            bad = copy.deepcopy(self.split)
            bad[1]["_source"]["layers"][group][key] = value
            with self.assertRaises(ValueError):
                engine.analyze_packets(bad, 45678, "fixture.test", True)
        for group, key in [("ip", "ip.dst"), ("ip", "ip.src"), ("tcp", "tcp.stream")]:
            bad = copy.deepcopy(self.split)
            del bad[0]["_source"]["layers"][group][key]
            with self.assertRaises(ValueError):
                engine.analyze_packets(bad, 45678, "fixture.test", True)

    def test_gap_overlap_wrong_sni_and_wrong_boundary_fail(self):
        cases = [packets([(100, self.hello[:3]), (104, self.hello[3:])]),
                 self.split + packets([(100, b"bad")]),
                 packets([(100, self.hello[:4]), (104, self.hello[4:])]),
                 packets([(100, self.hello)])]
        for case in cases:
            with self.subTest(case=case), self.assertRaises(ValueError):
                engine.analyze_packets(case, 45678, "fixture.test", True)
        with self.assertRaises(ValueError):
            engine.analyze_packets(self.split, 45678, "fabricated.test", True)

    def test_retransmission_and_wrapping_sequences_preserve_actual_bytes(self):
        candidate = packets([(2**32 - 2, self.hello[:3]), (1, self.hello[3:]), (2**32 - 2, self.hello[:3])])
        self.assertFalse(engine.analyze_packets(candidate, 45678, "fixture.test", True)["combinationMatched"])

    def test_malformed_untrusted_inputs_never_release_acceptance(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "report.json"
            for value in ["not json", "[]", "null", "{}", '{"mode":"dry-run"}', '{"mode":"nfqueue"}']:
                report.write_text(value)
                self.assertFalse(engine.validate_report(report, Path(temporary))["releaseAcceptance"])
            self.assertFalse(engine.validate_report(Path(temporary) / "missing", Path(temporary))["releaseAcceptance"])

    def complete_report(self, base):
        identity = {"treeSha256": "new", "files": {}, "head": "a" * 40}
        (base / "ripdpi").write_bytes(b"unit-test-only-binary")
        report = {"schemaVersion": 1, "mode": "engine-capture", "purpose": "engine-release",
                  "coverage": engine.COVERAGE, "platform": {"system": "Linux"}, "source": identity,
                  "binarySha256": engine.sha(base / "ripdpi"), "scenarios": {}}
        for role, selector in engine.SCENARIOS.items():
            directory = base / selector
            directory.mkdir()
            (directory / "fixture-manifest.json").write_text(json.dumps({"tlsEchoPort": 45678, "fixtureDomain": "fixture.test", "bindHost": "127.0.0.1"}))
            (directory / "fixture-events.json").write_text(json.dumps([{"service": "tls_echo", "detail": "handshake", "sni": "fixture.test", "target": "127.0.0.1:45678"}]))
            (directory / "cli-stderr.log").write_text("test-only")
            (directory / "cli-command.json").write_text(json.dumps(["--ip", "127.0.0.1", "--port", "12345", "--debug", "2"] + engine.COVERAGE["profiles"][role]))
            (directory / "test-output.txt").write_text(f"l7-engine tls-echo-ok\nl7-engine fixture-handshake-ok sni=fixture.test\ntest {selector} ... ok\ntest result: ok. 1 passed; 0 failed; 0 ignored; 2 filtered out\n")
            (directory / "capture.pcap").write_bytes(b"\xd4\xc3\xb2\xa1" + bytes(25))
            (directory / "capture.tshark.json").write_text(json.dumps(self.split if role == "candidate" else packets([(100, self.hello)])))
            report["scenarios"][role] = {"selector": selector, "profile": engine.COVERAGE["profiles"][role], "exitCode": 0,
                "command": ["cargo", "test", "--locked", "--manifest-path", "native/rust/Cargo.toml", "-p", "ripdpi-cli", "--test", "packet_smoke", selector, "--", "--ignored", "--exact", "--nocapture"],
                "hashes": {name: engine.sha(directory / name) for name in engine.ARTIFACTS}}
        path = base / "report.json"
        path.write_text(json.dumps(report))
        return path, report, identity

    def test_full_validator_checks_each_artifact_test_count_and_profile(self):
        # tshark is a unit-test oracle here, never a producer acceptance run.
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            path, report, identity = self.complete_report(base)
            def decode(command, **kwargs):
                directory = Path(command[2]).parent
                class Output:
                    stdout = (directory / "capture.tshark.json").read_bytes()
                return Output()
            with patch.object(engine, "source_identity", return_value=identity), patch.object(engine.subprocess, "run", side_effect=decode):
                self.assertTrue(engine.validate_report(path, base)["releaseAcceptance"])
                selector = engine.SCENARIOS["candidate"]
                directory = base / selector
                original = (directory / "test-output.txt").read_text()
                for invalid in [original.replace("1 passed", "0 passed"), original + "skipping scenario\n"]:
                    (directory / "test-output.txt").write_text(invalid)
                    report["scenarios"]["candidate"]["hashes"]["test-output.txt"] = engine.sha(directory / "test-output.txt")
                    path.write_text(json.dumps(report))
                    self.assertFalse(engine.validate_report(path, base)["releaseAcceptance"])
                (directory / "test-output.txt").write_text(original)
                report["scenarios"]["candidate"]["hashes"]["test-output.txt"] = engine.sha(directory / "test-output.txt")
                path.write_text(json.dumps(report))
                (directory / "cli-command.json").write_text(json.dumps(["--ip", "127.0.0.1", "--port", "12345", "--debug", "2", "-s", "4"]))
                report["scenarios"]["candidate"]["hashes"]["cli-command.json"] = engine.sha(directory / "cli-command.json")
                path.write_text(json.dumps(report))
                self.assertFalse(engine.validate_report(path, base)["releaseAcceptance"])
                (directory / "capture.pcap").unlink()
                self.assertFalse(engine.validate_report(path, base)["releaseAcceptance"])

    def test_boolean_schema_and_exit_status_are_not_integers(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            path, report, identity = self.complete_report(base)
            report["schemaVersion"] = True
            path.write_text(json.dumps(report))
            self.assertFalse(engine.validate_report(path, base)["releaseAcceptance"])
            report["schemaVersion"] = 1
            report["scenarios"]["control"]["exitCode"] = False
            path.write_text(json.dumps(report))
            with patch.object(engine, "source_identity", return_value=identity):
                self.assertIn("failed test", engine.validate_report(path, base)["errors"][0])

    def test_raw_capture_disagreement_blocked_candidate_and_missing_handshake_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            path, report, identity = self.complete_report(base)
            directory = base / engine.SCENARIOS["candidate"]
            def decode(command, **kwargs):
                actual_dir = Path(command[2]).parent
                class Output:
                    stdout = json.dumps(packets([(100, self.hello)]) if actual_dir == directory else packets([(100, self.hello)])).encode()
                return Output()
            with patch.object(engine, "source_identity", return_value=identity), patch.object(engine.subprocess, "run", side_effect=decode):
                self.assertFalse(engine.validate_report(path, base)["releaseAcceptance"])
                (directory / "fixture-events.json").write_text("[]")
                report["scenarios"]["candidate"]["hashes"]["fixture-events.json"] = engine.sha(directory / "fixture-events.json")
                path.write_text(json.dumps(report))
                self.assertIn("fixture TLS handshake", engine.validate_report(path, base)["errors"][0])

    def test_capture_hash_tamper_fails_before_decoder(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            path, report, identity = self.complete_report(base)
            directory = base / engine.SCENARIOS["control"]
            (directory / "capture.pcap").write_bytes(b"tampered")
            with patch.object(engine, "source_identity", return_value=identity), patch.object(engine.subprocess, "run") as decode:
                self.assertIn("artifact hash", engine.validate_report(path, base)["errors"][0])
                decode.assert_not_called()

    def test_tampered_unknown_or_missing_scenario_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            path, report, identity = self.complete_report(base)
            with patch.object(engine, "source_identity", return_value=identity):
                (base / "ripdpi").write_bytes(b"changed")
                self.assertIn("binary identity", engine.validate_report(path, base)["errors"][0])
                report["binarySha256"] = engine.sha(base / "ripdpi")
                report["scenarios"]["unknown"] = {}
                path.write_text(json.dumps(report))
                self.assertIn("scenario", engine.validate_report(path, base)["errors"][0])
                del report["scenarios"]["unknown"]
                del report["scenarios"]["candidate"]
                path.write_text(json.dumps(report))
                self.assertIn("scenario", engine.validate_report(path, base)["errors"][0])

    def test_unsupported_platform_never_starts_capture(self):
        with patch.object(engine.platform, "system", return_value="Darwin"):
            with self.assertRaisesRegex(ValueError, "requires Linux"):
                engine.capture(Path("."), Path("unused"))

    def test_changed_source_and_incomplete_unsupported_coverage_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "report.json"
            report = {"schemaVersion": 1, "mode": "engine-capture", "purpose": "engine-release",
                      "coverage": engine.COVERAGE, "platform": {"system": "Linux"},
                      "source": {"treeSha256": "old", "files": {}, "head": "a" * 40}}
            path.write_text(json.dumps(report))
            with patch.object(engine, "source_identity", return_value={"treeSha256": "new", "files": {}}):
                self.assertIn("source identity", engine.validate_report(path, Path(temporary))["errors"][0])
            for change in [{"patterns": ["quic-initial-drop"]}, {"combinations": []}, {"platform": "Darwin"}]:
                report["coverage"] = dict(engine.COVERAGE, **change)
                path.write_text(json.dumps(report))
                self.assertIn("coverage/platform", engine.validate_report(path, Path(temporary))["errors"][0])


if __name__ == "__main__":
    unittest.main()
