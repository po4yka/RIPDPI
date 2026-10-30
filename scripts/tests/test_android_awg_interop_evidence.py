"""Offline runner/validator regression cases, not Android runtime evidence."""

import copy
import hashlib
import importlib.util
import json
import io
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

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


class LocalNetworkAuthorizationTest(unittest.TestCase):
    def test_grant_restores_prior_state_after_success_or_failure(self):
        permission = "android.permission.ACCESS_LOCAL_NETWORK"
        for previous in (True, False):
            for failure in (True, False):
                with self.subTest(previous=previous, failure=failure):
                    report = permission + ": granted=" + str(previous).lower()
                    with patch.object(RUNNER, "checked", side_effect=["37", report, "", ""]) as command:
                        try:
                            with RUNNER.emulator_local_network_authorization(["adb"]):
                                if failure:
                                    raise RuntimeError("unit failure")
                        except RuntimeError:
                            self.assertTrue(failure)
                        mutations = [c.args[0][2:] for c in command.call_args_list[2:]]
                        self.assertEqual(mutations, [] if previous else [
                            ["pm", "grant", RUNNER.PACKAGE, permission],
                            ["pm", "revoke", RUNNER.PACKAGE, permission]])

    def test_older_api_does_not_change_permissions(self):
        with patch.object(RUNNER, "checked", return_value="35") as command:
            with RUNNER.emulator_local_network_authorization(["adb"]):
                pass
            self.assertEqual(command.call_count, 1)

    def test_unknown_or_multiple_user_grants_refuse_before_mutation(self):
        permission = "android.permission.ACCESS_LOCAL_NETWORK"
        for report in ("missing", (permission + ": granted=true\n") * 2):
            with patch.object(RUNNER, "checked", side_effect=["37", report]) as command:
                with self.assertRaisesRegex(ValueError, "prior emulator local network"):
                    with RUNNER.emulator_local_network_authorization(["adb"]):
                        self.fail("ambiguous permission state cannot be changed")
                self.assertEqual(command.call_count, 2)

    def test_failed_grant_still_attempts_restore(self):
        permission = "android.permission.ACCESS_LOCAL_NETWORK"
        with patch.object(RUNNER, "checked", side_effect=["37", permission + ": granted=false", OSError("grant failure"), ""]) as command:
            with self.assertRaises(OSError):
                with RUNNER.emulator_local_network_authorization(["adb"]):
                    self.fail("failed grant cannot start the native test")
            self.assertEqual(command.call_args.args[0], ["adb", "shell", "pm", "revoke", RUNNER.PACKAGE, permission])


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
                   "1", "540.5 1075.9", "3.90 9.0 8.0 2/2392 5848", "RUNNING_UNLOCKED"]
        with patch.object(RUNNER, "checked", side_effect=samples) as command, \
                patch.object(RUNNER.time, "sleep") as sleep:
            self.assertEqual(RUNNER.await_idle_guest(["adb"]), 3.9)
        self.assertEqual(command.call_count, len(samples))
        self.assertEqual(sleep.call_count, 2)

    def test_locked_user_fails_before_instrumentation_without_changing_credentials(self):
        samples = ["4", "1", "540.5 1075.9", "3.90 9.0 8.0 2/2392 5848", "RUNNING_LOCKED"]
        with patch.object(RUNNER, "checked", side_effect=samples) as command:
            with self.assertRaisesRegex(ValueError, "clean credential storage"):
                RUNNER.await_idle_guest(["adb"])
        self.assertEqual(command.call_count, len(samples))
        self.assertEqual(command.call_args.args[0], ["adb", "shell", "am", "get-started-user-state", "0"])

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
            "scenario": "payload", "version": VALIDATOR.VERSION, "nonce": self.nonce, "source_sha": self.sha, "profile": self.profile, "candidate_patch": None,
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



class FeatureEvidenceValidationTest(unittest.TestCase):
    def setUp(self):
        self.base=EvidenceValidationTest('test_complete_validator_unit_fixture_is_accepted')
        self.base.profile='v3-cpa-trailers';self.base.setUp();self.addCleanup(self.base.doCleanups)

    def configure(self,scenario,observation):
        data=copy.deepcopy(self.base.evidence);data['scenario']=scenario
        data['cycles']=[c for c in data['cycles'] if c['cycle']==0]
        names=[]
        if scenario in VALIDATOR.WIRE.COOKIE_SCENARIOS:names=['invalid-mac1','admission']+(['exhausted-noise'] if scenario=='cookies-disabled' else ['bad-mac2','valid-mac2'])+['challenge-budget','recovery']
        if scenario=='negative-transport':names=['corrupt','wrong-hp','foreign-source','replay','ambiguous','malformed-ip']
        for cycle in data['cycles']:
            cycle.pop('instrumentation');cycle.update(scenario=scenario,measurements=[],feature=dict(scenario=scenario,completed=True,elapsed_ms=10000,phases=names))
            if scenario=='timer-reject':cycle['feature']['expired_probe']=dict(no_reply=True,wait_ms=1000)
            identity='INSTRUMENTATION_STATUS: class='+VALIDATOR.FEATURE_METHOD.split('#')[0]+'\nINSTRUMENTATION_STATUS: test='+VALIDATOR.FEATURE_METHOD.split('#')[1]+'\n'
            log=identity+'INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: awgEvidence='+json.dumps(cycle)+'\nINSTRUMENTATION_STATUS_CODE: 2\n'+identity+'INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_CODE: -1\n'
            cycle['instrumentation']=self.base.artifact(f'feature-{cycle["mtu"]}.txt',log.encode())
        data['wire_observations']=[]
        for mtu in (1280,1420):
            recorded=copy.deepcopy(observation)
            recorded.pop('expired_probe',None) # client-owned probe is bound separately to its log.
            artifact=self.base.artifact(f'feature-peer-{mtu}.json',json.dumps(dict(wire_observation=recorded)).encode())
            data['wire_observations'].append(dict(mtu=mtu,**recorded,artifact=artifact))
        return data

    def test_feature_peer_and_client_bindings_and_missing_controls(self):
        from scripts.tests.test_awg_wire_validation import small_cpa_observation,timer_observation,cookie_observation,negative_transport_observation,recount_timer
        small=small_cpa_observation();small.update(scenario='small-datagrams',handshakes=1,fresh_signature_packets=1)
        small['evidence'].update(lengths=[[{'200':1},None,None,{'72':1,'117':2}],[None,{'130':1},None,{'117':2}]],authenticated_keepalives=1,ip_lengths={'45':2})
        small['evidence']['peer_counters'].update(rx_bytes=382,tx_bytes=364)
        observations=[small,*[timer_observation(s) for s in ('timer-persistent','timer-idle','timer-retry-loss','timer-rekey')],cookie_observation(False),cookie_observation(True),negative_transport_observation()]
        exhausted=timer_observation('timer-retry-loss');exhausted.update(scenario='timer-retry-exhaust',elapsed_ns=7_500_000_000)
        exhausted['evidence']['events']=[dict(at_ns=int((t+offset)*1e9),direction=d,type=k,length=l,dropped=True) for t in (0,1.2,2.4) for offset,d,k,l in ((0,'in',1,200),(.01,'out',2,130))]
        # Only the responses are deliberately dropped.
        for event in exhausted['evidence']['events']:
            if event['type']==1:event.pop('dropped')
        recount_timer(exhausted);exhausted['evidence']['peer_counters'].update(last_handshake_time_sec=0,tx_bytes=390);observations.append(exhausted)
        rejected=timer_observation('timer-rekey');rejected.update(scenario='timer-reject',elapsed_ns=8_900_000_000,expired_probe=dict(no_reply=True,wait_ms=1000))
        events=rejected['evidence']['events'];initial=events[:7];recovery=events[7:]
        for event in recovery:event['at_ns']+=5_700_000_000
        drops=[]
        for t in (4.6,5.8,7.0):drops.extend([dict(at_ns=int(t*1e9),direction='in',type=1,length=200),dict(at_ns=int((t+.01)*1e9),direction='out',type=2,length=130,dropped=True)])
        events[:]=initial+drops+recovery;recount_timer(rejected);observations.append(rejected)
        for observation in observations:
            scenario=observation['scenario'];good=self.configure(scenario,observation)
            self.base.validate(good)
            for mutate in (lambda d:d['cycles'].pop(),lambda d:d.update(scenario='payload'),lambda d:d['cycles'][0].update(scenario='payload'),lambda d:d['cycles'][0]['feature'].update(completed=False),lambda d:d['cycles'][0]['feature'].update(elapsed_ms=0)):
                bad=copy.deepcopy(good);mutate(bad)
                with self.subTest(scenario=scenario),self.assertRaises(ValueError):self.base.validate(bad)
            changed=copy.deepcopy(good)
            value=changed['wire_observations'][0];value['evidence']['authenticated_keepalives']+=1
            recorded={k:v for k,v in value.items() if k not in ('mtu','artifact')}
            value['artifact']=self.base.artifact('feature-mutated-peer.json',json.dumps(dict(wire_observation=recorded)).encode())
            with self.assertRaises(ValueError):self.base.validate(changed)

    def test_runner_preserves_scenario_in_the_leased_phase(self):
        from unittest.mock import patch
        import os
        argv=['run-android-awg-interop.py','--output-dir',str(self.base.root/'unused'),'--nonce','unit-scenario-run','--profile','v3-cpa-trailers','--scenario','timer-idle','--api','35']
        with patch.object(RUNNER.sys,'argv',argv),patch.dict(os.environ,{'BUILD_GATE_HELD':'1'}),patch.object(RUNNER,'build'),patch.object(RUNNER,'checked',return_value='') as command:
            self.assertEqual(RUNNER.main(),0)
        values=command.call_args.args[0]
        self.assertEqual(values[values.index('--scenario')+1],'timer-idle')
        self.assertEqual(values[values.index('--profile',values.index('--device-phase'))+1],'v3-cpa-trailers')




class MatrixCompletenessTest(unittest.TestCase):
    def test_a_partial_matrix_cannot_claim_completion(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"matrix.json"
            data=dict(version="android_awg_native_matrix_v1",nonce="matrix-unit",source_sha="a"*40,expected_cases=23,cases=[])
            path.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError,"incomplete Android feature matrix"):
                VALIDATOR.validate_matrix(path,"matrix-unit","a"*40)
            data['cases']=[dict(profile="awg-3.1-ranges-v1",scenario="payload")]*23
            path.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError,"incomplete Android feature matrix"):
                VALIDATOR.validate_matrix(path,"matrix-unit","a"*40)




class ScenarioCliReportingTest(unittest.TestCase):
    def test_valid_matrix_does_not_require_single_case_fields(self):
        argv=['validate-android-awg-interop.py','--matrix','--evidence','matrix.json','--nonce','matrix-unit','--source-sha','a'*40]
        with patch.object(VALIDATOR.sys,'argv',argv),patch.object(VALIDATOR,'validate_matrix',return_value={'version':'android_awg_native_matrix_v1'}),patch('sys.stdout',new_callable=io.StringIO) as output:
            self.assertEqual(VALIDATOR.main(),0)
        self.assertIn('complete profile/feature matrix',output.getvalue())

    def test_feature_cli_does_not_claim_dual_stack_payload(self):
        argv=['validate-android-awg-interop.py','--evidence','feature.json','--nonce','feature-unit','--source-sha','a'*40]
        with patch.object(VALIDATOR.sys,'argv',argv),patch.object(VALIDATOR,'validate',return_value={'scenario':'timer-idle','candidate_patch':None}),patch('sys.stdout',new_callable=io.StringIO) as output:
            self.assertEqual(VALIDATOR.main(),0)
        self.assertIn('feature scenario timer-idle',output.getvalue())
        self.assertNotIn('dual-stack',output.getvalue())




class InstrumentationFailureEvidenceTest(unittest.TestCase):
    def test_timeout_retains_partial_output_and_stops_only_test_host(self):
        import subprocess
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"instrumentation.txt"
            failure=subprocess.TimeoutExpired(['adb'],240,output=b'INSTRUMENTATION_STATUS: test=owned-test',stderr=b'owned failure')
            result=subprocess.CompletedProcess(['adb'],0,stdout='1234\n',stderr='')
            logs=subprocess.CompletedProcess(['adb'],0,stdout='owned pid stack',stderr='')
            with patch.object(RUNNER,'checked',side_effect=failure),patch.object(RUNNER.subprocess,'run',side_effect=[result,logs,result,result,result]) as command:
                with self.assertRaises(subprocess.TimeoutExpired):RUNNER.instrumentation(['adb'],['adb','-s','emulator-unit'],path,240)
            self.assertIn('owned-test',path.read_text())
            self.assertIn('owned failure',path.read_text())
            self.assertEqual(path.with_name('instrumentation-logcat-1234.txt').read_text(),'owned pid stack')
            self.assertEqual(command.call_args.args[0],['adb','-s','emulator-unit','shell','am','force-stop',RUNNER.PACKAGE])




class DiagnosticWriteFailureTest(unittest.TestCase):
    def test_diagnostic_write_failure_still_stops_owned_instrumentation(self):
        import subprocess
        failure=subprocess.TimeoutExpired(['adb'],240,output=b'partial output')
        with patch.object(RUNNER,'checked',side_effect=failure),patch.object(Path,'write_text',side_effect=OSError('unit disk failure')),patch.object(RUNNER.subprocess,'run') as command:
            with self.assertRaises(subprocess.TimeoutExpired) as observed:
                RUNNER.instrumentation(['adb'],['adb','-s','emulator-unit'],Path('unwritable'),240)
        self.assertIs(observed.exception,failure)
        self.assertEqual(command.call_args.args[0],['adb','-s','emulator-unit','shell','am','force-stop',RUNNER.PACKAGE])




class DeadInstrumentationDiagnosticsTest(unittest.TestCase):
    def test_dead_process_diagnostics_are_scoped_to_test_package_uid(self):
        import subprocess
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"instrumentation.txt"
            failure=subprocess.TimeoutExpired(['adb'],240,output=b'partial')
            result=lambda text:subprocess.CompletedProcess(['adb'],0,stdout=text,stderr='')
            with patch.object(RUNNER,'checked',side_effect=failure),patch.object(RUNNER.subprocess,'run',side_effect=[result(''),result('package:'+RUNNER.PACKAGE+' uid:12345'),result('owned crash'),result(''),result(''),result('')]) as command:
                with self.assertRaises(subprocess.TimeoutExpired):RUNNER.instrumentation(['adb'],['adb'],path,240)
            self.assertEqual(path.with_name('instrumentation-logcat-uid-12345.txt').read_text(),'owned crash')
            self.assertIn('--uid=12345',command.call_args_list[2].args[0])


if __name__ == "__main__":
    unittest.main()
