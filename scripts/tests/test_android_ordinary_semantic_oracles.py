#!/usr/bin/env python3
"""Negative and adversarial tests for all seven ordinary semantic oracles."""

from __future__ import annotations

import contextlib
import copy
import hashlib
import io
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from scripts.ci import android_ordinary_raw_evidence as raw_evidence
from scripts.ci import android_ordinary_semantic_oracles as oracles
from scripts.ci import check_dns_ipv6_killswitch_gates as gates
from scripts.ci import produce_android_ordinary_gate_results as producer
from scripts.tests import android_ordinary_semantic_fixtures as fixtures


class AndroidOrdinarySemanticOracleTest(unittest.TestCase):
    source_sha = "a" * 40

    def create_bundle(self, directory: Path) -> tuple[Path, Path, Path, dict]:
        artifact_root = directory / "artifacts"
        artifact_root.mkdir(mode=0o700)
        artifact_root.chmod(0o700)
        app_apk = directory / "app.apk"
        test_apk = directory / "test.apk"
        app_apk.write_bytes(b"semantic-app-apk")
        test_apk.write_bytes(b"semantic-test-apk")
        app_sha = hashlib.sha256(app_apk.read_bytes()).hexdigest()
        test_sha = hashlib.sha256(test_apk.read_bytes()).hexdigest()
        now = int(time.time() * 1000)
        actions = []
        for index, spec in enumerate(raw_evidence.ACTION_SPECS):
            started = now - 30_000 + index * 3_000
            finished = started + 1_000
            correlation = hashlib.sha256(
                f"semantic-correlation:{spec.action_id}".encode()
            ).hexdigest()
            payloads = fixtures.semantic_artifacts(
                spec.action_id,
                correlation_id=correlation,
                source_sha=self.source_sha,
                app_sha256=app_sha,
                test_sha256=test_sha,
                started_at=started,
                finished_at=finished,
            )
            artifacts = []
            for kind in raw_evidence.ARTIFACT_KINDS:
                suffix = "pcap" if kind == "packet-capture" else "json"
                name = f"{spec.action_id}.{kind}.{suffix}"
                payload = payloads[kind]
                path = artifact_root / name
                path.write_bytes(payload)
                path.chmod(0o600)
                artifacts.append(
                    {
                        "kind": kind,
                        "path": name,
                        "sha256": hashlib.sha256(payload).hexdigest(),
                        "sizeBytes": len(payload),
                        "vantage": raw_evidence.ARTIFACT_VANTAGES[kind],
                        "windowFinishedAtEpochMs": finished,
                        "windowStartedAtEpochMs": started,
                    }
                )
            actions.append(
                {
                    "actionId": spec.action_id,
                    "artifacts": artifacts,
                    "correlationId": correlation,
                    "gateIds": list(spec.gate_ids),
                    "windowFinishedAtEpochMs": finished,
                    "windowStartedAtEpochMs": started,
                }
            )
        manifest = {
            "actions": actions,
            "appApkSha256": app_sha,
            "artifactRoot": str(artifact_root),
            "createdAtEpochMs": now,
            "runId": hashlib.sha256(b"semantic-ordinary-run").hexdigest(),
            "sourceSha": self.source_sha,
            "testApkSha256": test_sha,
            "version": raw_evidence.BUNDLE_VERSION,
        }
        manifest_path = directory / "manifest.json"
        self.write_manifest(manifest_path, manifest)
        return manifest_path, app_apk, test_apk, manifest

    @staticmethod
    def write_manifest(path: Path, manifest: dict) -> None:
        path.write_bytes(raw_evidence.canonical_json_bytes(manifest))
        path.chmod(0o600)

    @staticmethod
    def action(manifest: dict, action_id: str) -> dict:
        return next(
            action for action in manifest["actions"] if action["actionId"] == action_id
        )

    @staticmethod
    def artifact(manifest: dict, action_id: str, kind: str) -> dict:
        action = AndroidOrdinarySemanticOracleTest.action(manifest, action_id)
        return next(
            artifact for artifact in action["artifacts"] if artifact["kind"] == kind
        )

    def replace_artifact(
        self,
        manifest_path: Path,
        manifest: dict,
        action_id: str,
        kind: str,
        payload: bytes,
    ) -> None:
        entry = self.artifact(manifest, action_id, kind)
        path = Path(manifest["artifactRoot"]) / entry["path"]
        path.write_bytes(payload)
        path.chmod(0o600)
        entry["sizeBytes"] = len(payload)
        entry["sha256"] = hashlib.sha256(payload).hexdigest()
        self.write_manifest(manifest_path, manifest)

    def mutate_json_artifact(
        self,
        manifest_path: Path,
        manifest: dict,
        action_id: str,
        kind: str,
        mutation,
    ) -> None:
        entry = self.artifact(manifest, action_id, kind)
        path = Path(manifest["artifactRoot"]) / entry["path"]
        value = json.loads(path.read_text(encoding="utf-8"))
        mutation(value)
        self.replace_artifact(
            manifest_path,
            manifest,
            action_id,
            kind,
            oracles.canonical_json_bytes(value),
        )

    def run_producer(
        self, directory: Path, manifest_path: Path, app_apk: Path, test_apk: Path
    ) -> tuple[int, dict]:
        output_parent = directory / "output"
        output_parent.mkdir(mode=0o700)
        output = output_parent / "results.json"
        with (
            mock.patch.object(
                producer, "current_head_sha", return_value=self.source_sha
            ),
            mock.patch.object(
                producer, "current_source_sha", return_value=self.source_sha
            ),
            contextlib.redirect_stdout(io.StringIO()),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            status = producer.main(
                [
                    "--output",
                    str(output),
                    "--raw-manifest",
                    str(manifest_path),
                    "--app-apk",
                    str(app_apk),
                    "--test-apk",
                    str(test_apk),
                ]
            )
        return status, json.loads(output.read_text(encoding="utf-8"))

    def assert_semantic_failure(self, mutation, expected_code: str) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest_path, app_apk, test_apk, manifest = self.create_bundle(directory)
            mutation(manifest_path, manifest)
            status, results = self.run_producer(
                directory, manifest_path, app_apk, test_apk
            )
            self.assertEqual(status, 1)
            self.assertTrue(
                all(
                    value["state"] == "FAIL" and expected_code in value["reason"]
                    for value in results["gateResults"].values()
                )
            )

    def test_all_seven_oracles_derive_proofs_but_public_producer_stays_fail_closed(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest_path, app_apk, test_apk, _ = self.create_bundle(directory)
            status, results = self.run_producer(
                directory, manifest_path, app_apk, test_apk
            )
            self.assertEqual(status, 1)
            self.assertTrue(producer.SOURCE_OWNED_VERIFIER_AVAILABLE)
            self.assertFalse(producer.SOURCE_OWNED_PHYSICAL_PRODUCER_AVAILABLE)
            self.assertEqual(
                set(results["gateResults"]), set(producer.ORDINARY_GATE_IDS)
            )
            self.assertTrue(
                all(
                    value["state"] == "FAIL"
                    and producer.PRODUCER_ATTESTATION_CODE in value["reason"]
                    for value in results["gateResults"].values()
                )
            )
            self.assertEqual(
                gates.validate_results_document(
                    gates.load_json(gates.POLICY_PATH),
                    results,
                    expected_source_sha=self.source_sha,
                    applies_to=producer.APPLIES_TO,
                ),
                results,
            )
            provenance = results["rawBundleProvenance"]
            self.assertFalse(provenance["productionReady"])
            self.assertTrue(provenance["semanticVerified"])
            self.assertEqual(provenance["verifier"], oracles.VERIFIER_VERSION)
            self.assertEqual(
                set(provenance["actionProofs"]),
                {spec.action_id for spec in raw_evidence.ACTION_SPECS},
            )

    def test_each_action_oracle_fails_closed_on_its_semantic_boundary(self) -> None:
        def ipv4_route(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "ipv4-only",
                "route-snapshot",
                lambda value: value["phases"][0]["commands"].update(
                    {"ip6RouteShow": "default dev tun0\n"}
                ),
            )

        def dual_route(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "dual-stack",
                "route-snapshot",
                lambda value: value["phases"][0]["commands"].update(
                    {"ip6RouteShow": ""}
                ),
            )

        def forced_probe(manifest_path, manifest):
            def mutate(value):
                value["probes"][0]["outcome"] = "connected"
                value["probes"][0]["error"] = None

            self.mutate_json_artifact(
                manifest_path, manifest, "forced-revoke", "action-receipt", mutate
            )

        def core_event(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "core-fault",
                "action-receipt",
                lambda value: value["event"].update({"coreExitCode": 0}),
            )

        def switch_route(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "wifi-lte-switch",
                "route-snapshot",
                lambda value: value["phases"][0]["commands"].update(
                    {"connectivity": "vpn_active=true\nlockdown_active=true\n"}
                ),
            )

        def sleep_event(manifest_path, manifest):
            def mutate(value):
                value["event"]["sleepAtEpochMs"] = value["event"]["wakeAtEpochMs"]

            self.mutate_json_artifact(
                manifest_path, manifest, "sleep-wake", "action-receipt", mutate
            )

        def always_on_settings(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "android-always-on-block",
                "route-snapshot",
                lambda value: value["phases"][0]["commands"].update(
                    {
                        "secureSettings": (
                            "always_on_vpn_app=com.poyka.ripdpi\n"
                            "always_on_vpn_lockdown=0\n"
                        )
                    }
                ),
            )

        cases = {
            "ipv4-only": (ipv4_route, "SEMANTIC_IPV4_ONLY_ROUTE_LEAK"),
            "dual-stack": (dual_route, "SEMANTIC_DUAL_STACK_ROUTE_INVALID"),
            "forced-revoke": (forced_probe, "SEMANTIC_PROBE_MISMATCH"),
            "core-fault": (core_event, "SEMANTIC_EVENT_MISMATCH"),
            "wifi-lte-switch": (switch_route, "SEMANTIC_KILLSWITCH_ROUTE_OPEN"),
            "sleep-wake": (sleep_event, "SEMANTIC_EVENT_MISMATCH"),
            "android-always-on-block": (
                always_on_settings,
                "SEMANTIC_ALWAYS_ON_INVALID",
            ),
        }
        for action_id, (mutation, code) in cases.items():
            with self.subTest(action=action_id):
                self.assert_semantic_failure(mutation, code)

    def test_ipv4_only_oracle_rejects_ipv6_address_dns_connect_and_aaaa_leaks(
        self,
    ) -> None:
        def route_address(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "ipv4-only",
                "route-snapshot",
                lambda value: value["phases"][0]["commands"].update(
                    {"ip6AddressShow": "inet6 fd00:1234::2/128 scope global\n"}
                ),
            )

        def route_dns(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "ipv4-only",
                "route-snapshot",
                lambda value: value["phases"][0]["commands"].update(
                    {"dnsServers": "nameserver 2001:db8::53\n"}
                ),
            )

        def connected_ipv6(manifest_path, manifest):
            def mutate(value):
                value["probes"][1]["outcome"] = "connected"
                value["probes"][1]["error"] = None

            self.mutate_json_artifact(
                manifest_path, manifest, "ipv4-only", "action-receipt", mutate
            )

        def aaaa_answer(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "ipv4-only",
                "action-receipt",
                lambda value: value["dnsObservation"].update(
                    {"answers": [fixtures.FIXTURE["controlIpv6"]]}
                ),
            )

        for name, mutation, code in (
            ("address", route_address, "SEMANTIC_IPV4_ONLY_ROUTE_LEAK"),
            ("dns", route_dns, "SEMANTIC_IPV4_ONLY_ROUTE_LEAK"),
            ("connect", connected_ipv6, "SEMANTIC_PROBE_MISMATCH"),
            ("aaaa", aaaa_answer, "SEMANTIC_IPV4_ONLY_DNS_LEAK"),
        ):
            with self.subTest(boundary=name):
                self.assert_semantic_failure(mutation, code)

    def test_ipv4_only_rejects_packet_parsed_ipv6_underlay_traffic(self) -> None:
        def mutation(manifest_path, manifest):
            action = self.action(manifest, "ipv4-only")
            started = action["windowStartedAtEpochMs"]
            correlation = action["correlationId"]
            records = [
                (
                    started + 100,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        fixtures.FIXTURE["markerAddress"],
                        42000,
                        fixtures.FIXTURE["markerPort"],
                        oracles._marker("ipv4-only", correlation, "action"),
                    ),
                ),
                (
                    started + 450,
                    fixtures._udp_ipv6(
                        "2001:db8::201",
                        "2001:db8::99",
                        43000,
                        443,
                        b"forbidden-ipv6-underlay",
                    ),
                ),
                (
                    started + 600,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        fixtures.FIXTURE["tunnelEndpoints"][0],
                        43000,
                        fixtures.FIXTURE["tunnelPort"],
                        b"tunnel-control:ipv4-only",
                    ),
                ),
                (
                    started + 900,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        fixtures.FIXTURE["markerAddress"],
                        42000,
                        fixtures.FIXTURE["markerPort"],
                        oracles._marker("ipv4-only", correlation, "outcome"),
                    ),
                ),
            ]
            self.replace_artifact(
                manifest_path,
                manifest,
                "ipv4-only",
                "packet-capture",
                fixtures._pcap(records),
            )

        self.assert_semantic_failure(mutation, "SEMANTIC_IPV4_ONLY_PACKET_LEAK")

    def test_dual_stack_requires_packet_parsed_tunnel_activity(self) -> None:
        def mutation(manifest_path, manifest):
            action = self.action(manifest, "dual-stack")
            started = action["windowStartedAtEpochMs"]
            correlation = action["correlationId"]
            records = [
                (
                    started + 100,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        fixtures.FIXTURE["markerAddress"],
                        42000,
                        fixtures.FIXTURE["markerPort"],
                        oracles._marker("dual-stack", correlation, "action"),
                    ),
                ),
                (
                    started + 900,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        fixtures.FIXTURE["markerAddress"],
                        42000,
                        fixtures.FIXTURE["markerPort"],
                        oracles._marker("dual-stack", correlation, "outcome"),
                    ),
                ),
            ]
            self.replace_artifact(
                manifest_path,
                manifest,
                "dual-stack",
                "packet-capture",
                fixtures._pcap(records),
            )

        self.assert_semantic_failure(mutation, "SEMANTIC_TUNNEL_CONTROL_MISSING")

    def test_caller_authored_verdict_and_counter_fields_are_rejected(self) -> None:
        for field, value in (("status", "PASS"), ("count", 0), ("verdict", "PASS")):
            with self.subTest(field=field):

                def mutate(manifest_path, manifest, field=field, value=value):
                    self.mutate_json_artifact(
                        manifest_path,
                        manifest,
                        "forced-revoke",
                        "action-receipt",
                        lambda receipt: receipt.update({field: value}),
                    )

                self.assert_semantic_failure(mutate, "CALLER_VERDICT_FORBIDDEN")

    def test_cross_action_receipt_copy_is_rejected_even_when_manifest_is_rehashed(
        self,
    ) -> None:
        def mutation(manifest_path, manifest):
            source = self.artifact(manifest, "core-fault", "action-receipt")
            payload = (Path(manifest["artifactRoot"]) / source["path"]).read_bytes()
            self.replace_artifact(
                manifest_path,
                manifest,
                "forced-revoke",
                "action-receipt",
                payload,
            )

        self.assert_semantic_failure(mutation, "SEMANTIC_BINDING_MISMATCH")

    def test_direct_target_packet_contradicts_blocked_receipt(self) -> None:
        def mutation(manifest_path, manifest):
            action = self.action(manifest, "forced-revoke")
            started = action["windowStartedAtEpochMs"]
            correlation = action["correlationId"]
            records = [
                (
                    started + 100,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        fixtures.FIXTURE["markerAddress"],
                        42000,
                        fixtures.FIXTURE["markerPort"],
                        oracles._marker("forced-revoke", correlation, "action"),
                    ),
                ),
                (
                    started + 500,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        fixtures.FIXTURE["controlIpv4"],
                        43000,
                        fixtures.FIXTURE["probePort"],
                        b"forbidden-direct-probe",
                    ),
                ),
                (
                    started + 900,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        fixtures.FIXTURE["markerAddress"],
                        42000,
                        fixtures.FIXTURE["markerPort"],
                        oracles._marker("forced-revoke", correlation, "outcome"),
                    ),
                ),
            ]
            self.replace_artifact(
                manifest_path,
                manifest,
                "forced-revoke",
                "packet-capture",
                fixtures._pcap(records),
            )

        self.assert_semantic_failure(mutation, "SEMANTIC_DIRECT_TRAFFIC_LEAK")

    def test_unrelated_underlay_packet_cannot_hide_behind_blocked_probes(self) -> None:
        def mutation(manifest_path, manifest):
            action = self.action(manifest, "android-always-on-block")
            started = action["windowStartedAtEpochMs"]
            correlation = action["correlationId"]
            records = [
                (
                    started + 100,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        fixtures.FIXTURE["markerAddress"],
                        42000,
                        fixtures.FIXTURE["markerPort"],
                        oracles._marker(
                            "android-always-on-block", correlation, "action"
                        ),
                    ),
                ),
                (
                    started + 500,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        "203.0.113.99",
                        43000,
                        9443,
                        b"unapproved-underlay-traffic",
                    ),
                ),
                (
                    started + 900,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        fixtures.FIXTURE["markerAddress"],
                        42000,
                        fixtures.FIXTURE["markerPort"],
                        oracles._marker(
                            "android-always-on-block", correlation, "outcome"
                        ),
                    ),
                ),
            ]
            self.replace_artifact(
                manifest_path,
                manifest,
                "android-always-on-block",
                "packet-capture",
                fixtures._pcap(records),
            )

        self.assert_semantic_failure(mutation, "SEMANTIC_UNEXPECTED_UNDERLAY_TRAFFIC")

    def test_stale_correlation_in_route_snapshot_is_rejected(self) -> None:
        def mutation(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "sleep-wake",
                "route-snapshot",
                lambda value: value.update({"correlationId": "f" * 64}),
            )

        self.assert_semantic_failure(mutation, "SEMANTIC_BINDING_MISMATCH")

    def test_duplicate_or_missing_markers_and_truncated_pcap_fail_closed(self) -> None:
        def duplicate(manifest_path, manifest):
            action = self.action(manifest, "core-fault")
            started = action["windowStartedAtEpochMs"]
            correlation = action["correlationId"]
            payload = fixtures.packet_capture(
                "core-fault", correlation_id=correlation, started_at=started
            )
            records = payload[24:]
            self.replace_artifact(
                manifest_path,
                manifest,
                "core-fault",
                "packet-capture",
                payload + records[: 16 + int.from_bytes(records[8:12], "little")],
            )

        def truncated(manifest_path, manifest):
            entry = self.artifact(manifest, "core-fault", "packet-capture")
            path = Path(manifest["artifactRoot"]) / entry["path"]
            self.replace_artifact(
                manifest_path,
                manifest,
                "core-fault",
                "packet-capture",
                path.read_bytes()[:-1],
            )

        self.assert_semantic_failure(duplicate, "SEMANTIC_MARKER_MISMATCH")
        self.assert_semantic_failure(truncated, "SEMANTIC_PCAP_TRUNCATED")

    def test_forged_pass_provenance_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest_path, app_apk, test_apk, _ = self.create_bundle(directory)
            status, results = self.run_producer(
                directory, manifest_path, app_apk, test_apk
            )
            self.assertEqual(status, 1)
            forged = copy.deepcopy(results)
            forged["gateResults"] = {
                gate_id: {"state": "PASS"} for gate_id in producer.ORDINARY_GATE_IDS
            }
            forged["rawBundleProvenance"]["productionReady"] = True
            with self.assertRaisesRegex(
                producer.EvidenceError, producer.PRODUCER_ATTESTATION_CODE
            ):
                producer.validate_pass_results(forged)


if __name__ == "__main__":
    unittest.main()
