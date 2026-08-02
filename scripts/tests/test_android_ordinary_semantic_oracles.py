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

    @contextlib.contextmanager
    def tightened(self, *withheld: str):
        """Run the block with ``withheld`` relaxations removed from the policy."""
        relaxations = producer.release_evidence_relaxations
        original = relaxations.POLICY_PATH
        policy = json.loads(original.read_text(encoding="utf-8"))
        block = policy.setdefault("relaxedEvidenceRequirements", {})
        declared = block.get("requirements", [])
        remaining = [item for item in declared if item not in withheld]
        self.assertEqual(
            len(remaining) + len(withheld),
            len(declared),
            "withheld relaxation is not declared by the shipped policy",
        )
        block["requirements"] = remaining
        with tempfile.TemporaryDirectory() as temporary:
            tightened_path = Path(temporary) / "tightened-policy.json"
            tightened_path.write_text(json.dumps(policy), encoding="utf-8")
            relaxations.POLICY_PATH = tightened_path
            try:
                yield
            finally:
                relaxations.POLICY_PATH = original

    @contextlib.contextmanager
    def relaxed(self, *requirements: str):
        """Run semantic-only assertions with explicit test-local relaxations."""
        relaxations = producer.release_evidence_relaxations
        original = relaxations.POLICY_PATH
        policy = json.loads(original.read_text(encoding="utf-8"))
        block = policy.setdefault("relaxedEvidenceRequirements", {})
        declared = block.setdefault("requirements", [])
        unknown = set(requirements) - relaxations.KNOWN_RELAXATIONS
        self.assertFalse(unknown, f"unknown test relaxation: {sorted(unknown)}")
        block["requirements"] = sorted(set(declared) | set(requirements))
        with tempfile.TemporaryDirectory() as temporary:
            relaxed_path = Path(temporary) / "relaxed-policy.json"
            relaxed_path.write_text(json.dumps(policy), encoding="utf-8")
            relaxations.POLICY_PATH = relaxed_path
            try:
                yield
            finally:
                relaxations.POLICY_PATH = original

    def assert_semantic_failure(self, mutation, expected_code: str) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest_path, app_apk, test_apk, manifest = self.create_bundle(directory)
            mutation(manifest_path, manifest)
            with self.relaxed("exact-sha-physical-run"):
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

    @staticmethod
    def shift_pcap_timestamps(payload: bytes, delta_ms: int) -> bytes:
        shifted = bytearray(payload)
        offset = 24
        while offset < len(shifted):
            seconds = int.from_bytes(shifted[offset : offset + 4], "little")
            micros = int.from_bytes(shifted[offset + 4 : offset + 8], "little")
            included = int.from_bytes(shifted[offset + 8 : offset + 12], "little")
            timestamp_ms = seconds * 1000 + micros // 1000 + delta_ms
            shifted[offset : offset + 4] = (timestamp_ms // 1000).to_bytes(
                4, "little"
            )
            shifted[offset + 4 : offset + 8] = (
                (timestamp_ms % 1000) * 1000
            ).to_bytes(4, "little")
            offset += 16 + included
        return bytes(shifted)

    def test_all_seven_oracles_derive_proofs_and_pass_without_physical_attestation(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest_path, app_apk, test_apk, _ = self.create_bundle(directory)
            with self.relaxed("exact-sha-physical-run"):
                status, results = self.run_producer(
                    directory, manifest_path, app_apk, test_apk
                )
            self.assertEqual(status, 0)
            self.assertTrue(producer.SOURCE_OWNED_VERIFIER_AVAILABLE)
            self.assertTrue(producer.SOURCE_OWNED_PHYSICAL_PRODUCER_AVAILABLE)
            self.assertEqual(
                set(results["gateResults"]), set(producer.ORDINARY_GATE_IDS)
            )
            self.assertTrue(
                all(
                    value == {"state": "PASS"}
                    for value in results["gateResults"].values()
                )
            )
            # No physical run happened, so no attestation is attached. Semantic
            # verification is the whole proof behind this PASS.
            self.assertNotIn("producerAttestation", results)
            with self.relaxed("exact-sha-physical-run"):
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
            self.assertTrue(provenance["productionReady"])
            self.assertTrue(provenance["semanticVerified"])
            self.assertEqual(provenance["verifier"], oracles.VERIFIER_VERSION)
            self.assertEqual(
                set(provenance["actionProofs"]),
                {spec.action_id for spec in raw_evidence.ACTION_SPECS},
            )

    def test_public_producer_stays_fail_closed_when_physical_run_is_required(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest_path, app_apk, test_apk, _ = self.create_bundle(directory)
            status, results = self.run_producer(
                directory, manifest_path, app_apk, test_apk
            )
            self.assertEqual(status, 1)
            self.assertTrue(
                all(
                    value["state"] == "FAIL"
                    and producer.PRODUCER_ATTESTATION_CODE in value["reason"]
                    for value in results["gateResults"].values()
                )
            )
            self.assertFalse(results["rawBundleProvenance"]["productionReady"])

    def test_one_bounded_capture_clock_offset_is_applied_to_all_actions(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest_path, app_apk, test_apk, manifest = self.create_bundle(directory)
            for spec in raw_evidence.ACTION_SPECS:
                entry = self.artifact(manifest, spec.action_id, "packet-capture")
                path = Path(manifest["artifactRoot"]) / entry["path"]
                self.replace_artifact(
                    manifest_path,
                    manifest,
                    spec.action_id,
                    "packet-capture",
                    self.shift_pcap_timestamps(path.read_bytes(), 1_775),
                )
            with self.relaxed("exact-sha-physical-run"):
                status, results = self.run_producer(
                    directory, manifest_path, app_apk, test_apk
                )
            self.assertEqual(status, 0)
            self.assertTrue(results["rawBundleProvenance"]["semanticVerified"])
            self.assertEqual(
                results["rawBundleProvenance"]["verifier"],
                "android_ordinary_semantic_oracles_v2",
            )

    def test_inconsistent_capture_clock_offsets_fail_closed(self) -> None:
        def mutation(manifest_path, manifest):
            entry = self.artifact(manifest, "core-fault", "packet-capture")
            path = Path(manifest["artifactRoot"]) / entry["path"]
            self.replace_artifact(
                manifest_path,
                manifest,
                "core-fault",
                "packet-capture",
                self.shift_pcap_timestamps(path.read_bytes(), 10_000),
            )

        self.assert_semantic_failure(mutation, "SEMANTIC_CLOCK_MISMATCH")

    def test_dual_stack_aaaa_compares_canonical_ip_values(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest_path, app_apk, test_apk, manifest = self.create_bundle(directory)
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "dual-stack",
                "action-receipt",
                lambda value: value["dnsObservation"].update(
                    {"answers": ["2001:0db8:0000:0000:0000:0000:0000:0010"]}
                ),
            )
            with self.relaxed("exact-sha-physical-run"):
                status, results = self.run_producer(
                    directory, manifest_path, app_apk, test_apk
                )
            self.assertEqual(status, 0)
            self.assertTrue(results["rawBundleProvenance"]["semanticVerified"])

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
            def mutation(value):
                commands = value["phases"][0]["commands"]
                commands.update(
                    {
                        "ipRouteShow": "",
                    }
                )

            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "wifi-lte-switch",
                "route-snapshot",
                mutation,
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

    def test_one_dual_stack_fixture_host_with_distinct_ports_is_accepted(self) -> None:
        same_host = copy.deepcopy(fixtures.FIXTURE)
        same_host.update(
            {
                "markerAddress": same_host["controlIpv4"],
                "tunnelEndpoints": [same_host["controlIpv4"]],
            }
        )
        with (
            mock.patch.dict(fixtures.FIXTURE, same_host, clear=True),
            tempfile.TemporaryDirectory() as temporary,
        ):
            directory = Path(temporary)
            manifest_path, app_apk, test_apk, _ = self.create_bundle(directory)
            with self.relaxed("exact-sha-physical-run"):
                status, results = self.run_producer(
                    directory, manifest_path, app_apk, test_apk
                )
            self.assertEqual(status, 0)
            self.assertTrue(results["rawBundleProvenance"]["semanticVerified"])

    def test_permission_revoke_requires_ignore_mode_and_fail_closed_routes(self) -> None:
        def wrong_appop_mode(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "forced-revoke",
                "action-receipt",
                lambda value: value["event"].update({"appOpMode": "allow"}),
            )

        def open_ipv4_route(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "forced-revoke",
                "route-snapshot",
                lambda value: value["phases"][0]["commands"].update({
                    "connectivity": "vpn_active=true\nlockdown_active=true\n",
                    "ipAddressShow": (
                        "7: tun0: <POINTOPOINT,UP> mtu 1500\n"
                        "    inet 10.0.0.2/32 scope global tun0\n"
                    ),
                    "ip6AddressShow": "7: tun0: <POINTOPOINT,UP> mtu 1500\n",
                    "ipRouteShow": "default dev tun0 scope link\n",
                }),
            )

        self.assert_semantic_failure(
            wrong_appop_mode, "SEMANTIC_PERMISSION_REVOKE_INVALID"
        )
        self.assert_semantic_failure(
            open_ipv4_route, "SEMANTIC_KILLSWITCH_ROUTE_OPEN"
        )

    def test_probe_fixture_binding_normalizes_equivalent_ipv6_text(self) -> None:
        expanded = "2001:0db8:0000:0000:0000:0000:0000:0010"
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest_path, app_apk, test_apk, manifest = self.create_bundle(directory)

            def mutate(value):
                value["fixture"]["controlIpv6"] = expanded
                value["probes"][1]["targetAddress"] = expanded

            self.mutate_json_artifact(
                manifest_path, manifest, "ipv4-only", "action-receipt", mutate
            )
            with self.relaxed("exact-sha-physical-run"):
                status, results = self.run_producer(
                    directory, manifest_path, app_apk, test_apk
                )

            self.assertEqual(status, 0)
            self.assertTrue(results["rawBundleProvenance"]["semanticVerified"])
            self.assertTrue(
                all(
                    value == {"state": "PASS"}
                    for value in results["gateResults"].values()
                )
            )

    def test_probe_fixture_binding_rejects_different_ipv6_address(self) -> None:
        def mutation(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "ipv4-only",
                "action-receipt",
                lambda value: value["probes"][1].update(
                    {"targetAddress": "2001:db8::11"}
                ),
            )

        self.assert_semantic_failure(mutation, "SEMANTIC_PROBE_MISMATCH")

    def test_same_host_probe_port_packet_remains_a_direct_leak(self) -> None:
        def mutation(manifest_path, manifest):
            action = self.action(manifest, "dual-stack")
            started = action["windowStartedAtEpochMs"]
            correlation = action["correlationId"]
            payload = fixtures.packet_capture(
                "dual-stack", correlation_id=correlation, started_at=started
            )
            leak = fixtures._udp_ipv4(
                "192.0.2.201",
                fixtures.FIXTURE["controlIpv4"],
                43100,
                fixtures.FIXTURE["probePort"],
                b"direct-control-leak",
            )
            leaked = payload + fixtures._pcap([(started + 650, leak)])[24:]
            self.replace_artifact(
                manifest_path, manifest, "dual-stack", "packet-capture", leaked
            )

        self.assert_semantic_failure(mutation, "SEMANTIC_DIRECT_TRAFFIC_LEAK")

    def test_fixture_local_post_tunnel_packet_is_not_an_underlay_leak(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest_path, app_apk, test_apk, manifest = self.create_bundle(directory)
            action = self.action(manifest, "dual-stack")
            started = action["windowStartedAtEpochMs"]
            correlation = action["correlationId"]
            payload = fixtures.packet_capture(
                "dual-stack", correlation_id=correlation, started_at=started
            )
            local_egress = fixtures._udp_ipv4(
                fixtures.FIXTURE["controlIpv4"],
                fixtures.FIXTURE["controlIpv4"],
                43100,
                fixtures.FIXTURE["probePort"],
                b"fixture-local-post-tunnel-egress",
            )
            captured = payload + fixtures._pcap([(started + 650, local_egress)])[24:]
            self.replace_artifact(
                manifest_path,
                manifest,
                "dual-stack",
                "packet-capture",
                captured,
            )
            with self.relaxed("exact-sha-physical-run"):
                status, results = self.run_producer(
                    directory, manifest_path, app_apk, test_apk
                )

            self.assertEqual(status, 0)
            self.assertTrue(results["rawBundleProvenance"]["semanticVerified"])

    def test_ipv4_only_oracle_rejects_ipv6_address_dns_connect_and_aaaa_leaks(
        self,
    ) -> None:
        def route_address(manifest_path, manifest):
            def mutation(value):
                commands = value["phases"][0]["commands"]
                address = "    inet6 fd00:1234::2/128 scope global\n"
                commands["ipAddressShow"] += address
                commands["ip6AddressShow"] += address

            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "ipv4-only",
                "route-snapshot",
                mutation,
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

        def excessive_dns_retries(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "ipv4-only",
                "action-receipt",
                lambda value: value["dnsObservation"].update({"attemptCount": 4}),
            )

        for name, mutation, code in (
            ("address", route_address, "SEMANTIC_IPV4_ONLY_ROUTE_LEAK"),
            ("dns", route_dns, "SEMANTIC_IPV4_ONLY_ROUTE_LEAK"),
            ("connect", connected_ipv6, "SEMANTIC_PROBE_MISMATCH"),
            ("aaaa", aaaa_answer, "SEMANTIC_IPV4_ONLY_DNS_LEAK"),
            ("dns-retries", excessive_dns_retries, "SEMANTIC_DNS_MISMATCH"),
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
                    started + 700,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        fixtures.FIXTURE["tunnelEndpoints"][0],
                        43000,
                        fixtures.FIXTURE["tunnelPort"],
                        b"approved-tunnel-traffic",
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

    def test_leaks_before_action_or_after_outcome_marker_are_still_in_scope(
        self,
    ) -> None:
        for leak_offset in (50, 950):
            with self.subTest(leak_offset=leak_offset):

                def mutation(manifest_path, manifest, offset=leak_offset):
                    action = self.action(manifest, "core-fault")
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
                                oracles._marker("core-fault", correlation, "action"),
                            ),
                        ),
                        (
                            started + offset,
                            fixtures._udp_ipv4(
                                "192.0.2.201",
                                fixtures.FIXTURE["controlIpv4"],
                                43000,
                                fixtures.FIXTURE["probePort"],
                                b"direct-leak",
                            ),
                        ),
                        (
                            started + 900,
                            fixtures._udp_ipv4(
                                "192.0.2.201",
                                fixtures.FIXTURE["markerAddress"],
                                42000,
                                fixtures.FIXTURE["markerPort"],
                                oracles._marker("core-fault", correlation, "outcome"),
                            ),
                        ),
                    ]
                    self.replace_artifact(
                        manifest_path,
                        manifest,
                        "core-fault",
                        "packet-capture",
                        fixtures._pcap(records),
                    )

                self.assert_semantic_failure(mutation, "SEMANTIC_DIRECT_TRAFFIC_LEAK")

    def test_receipt_routes_and_markers_must_follow_causal_action_order(self) -> None:
        def early_probe(manifest_path, manifest):
            action = self.action(manifest, "core-fault")
            started = action["windowStartedAtEpochMs"]

            def mutation(value):
                value["probes"][0]["startedAtEpochMs"] = started + 90
                value["probes"][0]["finishedAtEpochMs"] = started + 100

            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "core-fault",
                "action-receipt",
                mutation,
            )

        def early_route(manifest_path, manifest):
            action = self.action(manifest, "core-fault")
            started = action["windowStartedAtEpochMs"]
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "core-fault",
                "route-snapshot",
                lambda value: value["phases"][0].update(
                    {"capturedAtEpochMs": started + 110}
                ),
            )

        def early_outcome_marker(manifest_path, manifest):
            action = self.action(manifest, "core-fault")
            started = action["windowStartedAtEpochMs"]
            correlation = action["correlationId"]
            records = [
                (
                    started + offset,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        fixtures.FIXTURE["markerAddress"],
                        42000,
                        fixtures.FIXTURE["markerPort"],
                        oracles._marker("core-fault", correlation, phase),
                    ),
                )
                for offset, phase in ((100, "action"), (150, "outcome"))
            ]
            self.replace_artifact(
                manifest_path,
                manifest,
                "core-fault",
                "packet-capture",
                fixtures._pcap(records),
            )

        def tunnel_activity_precedes_event(manifest_path, manifest):
            action = self.action(manifest, "dual-stack")
            started = action["windowStartedAtEpochMs"]
            correlation = action["correlationId"]
            records = [
                (
                    started + offset,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        destination,
                        43000 if phase == "tunnel" else 42000,
                        (
                            fixtures.FIXTURE["tunnelPort"]
                            if phase == "tunnel"
                            else fixtures.FIXTURE["markerPort"]
                        ),
                        (
                            b"early-tunnel"
                            if phase == "tunnel"
                            else oracles._marker("dual-stack", correlation, phase)
                        ),
                    ),
                )
                for offset, destination, phase in (
                    (100, fixtures.FIXTURE["markerAddress"], "action"),
                    (110, fixtures.FIXTURE["tunnelEndpoints"][0], "tunnel"),
                    (900, fixtures.FIXTURE["markerAddress"], "outcome"),
                )
            ]
            self.replace_artifact(
                manifest_path,
                manifest,
                "dual-stack",
                "packet-capture",
                fixtures._pcap(records),
            )

        def sleep_starts_outside_window(manifest_path, manifest):
            action = self.action(manifest, "sleep-wake")
            started = action["windowStartedAtEpochMs"]
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "sleep-wake",
                "action-receipt",
                lambda value: value["event"].update({"sleepAtEpochMs": started - 1}),
            )

        self.assert_semantic_failure(early_probe, "SEMANTIC_CAUSAL_ORDER_INVALID")
        self.assert_semantic_failure(early_route, "SEMANTIC_CAUSAL_ORDER_INVALID")
        self.assert_semantic_failure(early_outcome_marker, "SEMANTIC_CLOCK_MISMATCH")
        self.assert_semantic_failure(
            tunnel_activity_precedes_event, "SEMANTIC_TUNNEL_CONTROL_MISSING"
        )
        self.assert_semantic_failure(
            sleep_starts_outside_window, "SEMANTIC_WINDOW_MISMATCH"
        )

    def test_combined_and_ipv6_specific_address_outputs_must_agree(self) -> None:
        def mutation(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "ipv4-only",
                "route-snapshot",
                lambda value: value["phases"][0]["commands"].update(
                    {
                        "ipAddressShow": (
                            value["phases"][0]["commands"]["ipAddressShow"]
                            + "    inet6 fd00:1234::99/128 scope global\n"
                        )
                    }
                ),
            )

        self.assert_semantic_failure(mutation, "SEMANTIC_ROUTE_MISMATCH")

    def test_active_tunnel_interface_must_be_up_with_an_ipv4_address(self) -> None:
        def mutation(manifest_path, manifest):
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "dual-stack",
                "route-snapshot",
                lambda value: value["phases"][0]["commands"].update(
                    {
                        "ipAddressShow": (
                            "7: tun0: <POINTOPOINT,DOWN> mtu 1500 state DOWN\n"
                            "    inet6 fd00:1234::2/128 scope global\n"
                        )
                    }
                ),
            )

        self.assert_semantic_failure(mutation, "SEMANTIC_ROUTE_MISMATCH")

    def test_vpn_interface_cannot_borrow_addresses_from_another_interface(self) -> None:
        def mutation(manifest_path, manifest):
            def rewrite(value):
                commands = value["phases"][0]["commands"]
                commands["ipAddressShow"] = (
                    "7: tun0: <POINTOPOINT,UP> mtu 1500\n"
                    "8: wlan0: <BROADCAST,UP> mtu 1500\n"
                    "    inet 192.0.2.44/24 scope global wlan0\n"
                    "    inet6 fd00:1234::2/128 scope global\n"
                )
                commands["ip6AddressShow"] = (
                    "7: tun0: <POINTOPOINT,UP> mtu 1500\n"
                    "8: wlan0: <BROADCAST,UP> mtu 1500\n"
                    "    inet6 fd00:1234::2/128 scope global\n"
                )

            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "dual-stack",
                "route-snapshot",
                rewrite,
            )

        self.assert_semantic_failure(mutation, "SEMANTIC_ROUTE_MISMATCH")

    def test_duplicate_vpn_interface_blocks_are_rejected(self) -> None:
        def mutation(manifest_path, manifest):
            def rewrite(value):
                commands = value["phases"][0]["commands"]
                commands["ipAddressShow"] += (
                    "8: wlan0: <BROADCAST,UP> mtu 1500\n"
                    "    inet 192.0.2.44/24 scope global wlan0\n"
                    "9: tun0: <POINTOPOINT,DOWN> mtu 1500 state DOWN\n"
                )

            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "dual-stack",
                "route-snapshot",
                rewrite,
            )

        self.assert_semantic_failure(mutation, "SEMANTIC_ROUTE_MISMATCH")

    def test_continuously_protected_actions_require_causal_tunnel_proof(self) -> None:
        def early_protected_probe(manifest_path, manifest):
            def mutation(value):
                probe = value["probes"][0]
                probe["startedAtEpochMs"] = value["windowStartedAtEpochMs"] + 100
                probe["finishedAtEpochMs"] = value["windowStartedAtEpochMs"] + 110

            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "wifi-lte-switch",
                "action-receipt",
                mutation,
            )

        def protected_snapshot_before_event(manifest_path, manifest):
            started = self.action(manifest, "sleep-wake")["windowStartedAtEpochMs"]
            self.mutate_json_artifact(
                manifest_path,
                manifest,
                "sleep-wake",
                "route-snapshot",
                lambda value: value["phases"][0].update(
                    {"capturedAtEpochMs": started + 100}
                ),
            )

        def missing_tunnel_activity(manifest_path, manifest):
            action = self.action(manifest, "wifi-lte-switch")
            started = action["windowStartedAtEpochMs"]
            correlation = action["correlationId"]
            records = [
                (
                    started + offset,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        destination,
                            42000,
                            fixtures.FIXTURE["markerPort"],
                            oracles._marker("wifi-lte-switch", correlation, phase),
                    ),
                )
                for offset, destination, phase in (
                    (100, fixtures.FIXTURE["markerAddress"], "action"),
                    (900, fixtures.FIXTURE["markerAddress"], "outcome"),
                )
            ]
            self.replace_artifact(
                manifest_path,
                manifest,
                "wifi-lte-switch",
                "packet-capture",
                fixtures._pcap(records),
            )

        self.assert_semantic_failure(
            early_protected_probe, "SEMANTIC_CAUSAL_ORDER_INVALID"
        )
        self.assert_semantic_failure(
            protected_snapshot_before_event, "SEMANTIC_CAUSAL_ORDER_INVALID"
        )
        self.assert_semantic_failure(
            missing_tunnel_activity,
            "SEMANTIC_TUNNEL_CONTROL_MISSING",
        )

    def test_tunnel_endpoint_and_port_must_match_the_same_direction(self) -> None:
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
                    started + 600,
                    fixtures._udp_ipv4(
                        "192.0.2.201",
                        fixtures.FIXTURE["tunnelEndpoints"][0],
                        fixtures.FIXTURE["tunnelPort"],
                        9443,
                        b"crossed-endpoint-port",
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

    def test_forged_pass_provenance_is_rejected_only_while_attestation_is_required(
        self,
    ) -> None:
        """Relaxing the physical run removes the anti-forgery binding.

        The attestation was what tied an all-PASS document to a real capture.
        Without it, a hand-authored document of the right shape is
        indistinguishable from a genuine one, so this test records the loss
        rather than pretending the protection survives.
        """
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest_path, app_apk, test_apk, _ = self.create_bundle(directory)
            with self.relaxed("exact-sha-physical-run"):
                status, results = self.run_producer(
                    directory, manifest_path, app_apk, test_apk
                )
            self.assertEqual(status, 0)

            forged = copy.deepcopy(results)
            forged["gateResults"] = {
                gate_id: {"state": "PASS"} for gate_id in producer.ORDINARY_GATE_IDS
            }
            forged["rawBundleProvenance"]["productionReady"] = True

            with self.relaxed("exact-sha-physical-run"):
                producer.validate_pass_results(forged)

            with self.assertRaisesRegex(
                producer.EvidenceError, producer.PRODUCER_ATTESTATION_CODE
            ):
                producer.validate_pass_results(forged)

    def test_semantic_provenance_is_still_required_for_pass(self) -> None:
        """The relaxation drops the physical run, not the semantic oracles."""
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest_path, app_apk, test_apk, _ = self.create_bundle(directory)
            _, results = self.run_producer(
                directory, manifest_path, app_apk, test_apk
            )
            for field, value in (
                ("semanticVerified", False),
                ("actionCount", 6),
                ("artifactCount", 20),
                ("verifier", "not_the_checked_in_verifier"),
            ):
                with self.subTest(field=field):
                    tampered = copy.deepcopy(results)
                    tampered["rawBundleProvenance"][field] = value
                    with self.assertRaises(producer.EvidenceError):
                        producer.validate_pass_results(tampered)


if __name__ == "__main__":
    unittest.main()
