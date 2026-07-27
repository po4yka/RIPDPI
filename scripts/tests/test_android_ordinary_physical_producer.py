#!/usr/bin/env python3
"""Adversarial contracts for the source-owned ordinary physical producer."""

from __future__ import annotations

import copy
import hashlib
import json
import struct
import unittest
from pathlib import Path
from unittest import mock

from scripts.ci import android_ordinary_dns_fixture as dns_fixture
from scripts.ci import android_ordinary_physical_attestation as attestation
from scripts.ci import android_ordinary_raw_evidence as raw_evidence
from scripts.ci import android_ordinary_semantic_oracles as oracles
from scripts.ci import produce_android_ordinary_gate_results as gate_producer
from scripts.ci import produce_android_ordinary_physical_evidence as physical_producer
from scripts.tests import android_ordinary_semantic_fixtures as fixtures


def dns_query(name: str, query_type: int = 28) -> bytes:
    labels = b"".join(
        bytes((len(label),)) + label.encode() for label in name.split(".")
    )
    return (
        struct.pack("!HHHHHH", 7, 0x0100, 1, 0, 0, 0)
        + labels
        + b"\0"
        + struct.pack("!HH", query_type, 1)
    )


def der(tag: int, payload: bytes) -> bytes:
    if len(payload) < 0x80:
        length = bytes((len(payload),))
    else:
        encoded = len(payload).to_bytes((len(payload).bit_length() + 7) // 8, "big")
        length = bytes((0x80 | len(encoded),)) + encoded
    return bytes((tag,)) + length + payload


class AndroidOrdinaryPhysicalProducerTest(unittest.TestCase):
    source_sha = "a" * 40
    app_sha = hashlib.sha256(b"app").hexdigest()
    test_sha = hashlib.sha256(b"test").hexdigest()
    run_id = hashlib.sha256(b"physical-run").hexdigest()

    def test_runner_does_not_require_coordinator_network_for_fixture_preflight(
        self,
    ) -> None:
        runner = Path(
            "scripts/ci/run-android-ordinary-physical-evidence.sh"
        ).read_text()
        self.assertNotRegex(runner, r"(?m)^nc -[46] ")
        self.assertRegex(
            runner,
            r'ssh_remote\[@\].*socket\.create_connection\(\(\\"::1\\"',
        )

    def test_runner_suspends_and_restores_the_existing_always_on_vpn(self) -> None:
        runner = Path(
            "scripts/ci/run-android-ordinary-physical-evidence.sh"
        ).read_text()
        saved = runner.index('always_on_before=""')
        suspended = runner.rindex("settings delete secure always_on_vpn_app")
        instrumented = runner.index("shell am instrument")
        restored = runner.index(
            'settings put secure always_on_vpn_app "$always_on_before"'
        )
        self.assertLess(saved, suspended)
        self.assertLess(suspended, instrumented)
        self.assertLess(restored, suspended)
        self.assertIn('am force-stop "$always_on_before"', runner)

    def test_dns_fixture_returns_empty_ipv4_only_and_exact_dual_stack_aaaa(
        self,
    ) -> None:
        ipv6 = "2001:db8::44"
        empty = dns_fixture.build_response(
            dns_query(dns_fixture.IPV4_ONLY_NAME), dual_stack_ipv6=ipv6
        )
        dual = dns_fixture.build_response(
            dns_query(dns_fixture.DUAL_STACK_NAME), dual_stack_ipv6=ipv6
        )
        self.assertEqual(struct.unpack("!H", empty[6:8])[0], 0)
        self.assertEqual(struct.unpack("!H", dual[6:8])[0], 1)
        self.assertEqual(dual[-16:], __import__("ipaddress").IPv6Address(ipv6).packed)

    def test_dns_fixture_refuses_unknown_and_malformed_questions(self) -> None:
        nxdomain = dns_fixture.build_response(
            dns_query("caller.example"), dual_stack_ipv6="2001:db8::44"
        )
        self.assertEqual(struct.unpack("!H", nxdomain[2:4])[0] & 0xF, 3)
        with self.assertRaisesRegex(ValueError, "truncated"):
            dns_fixture.build_response(b"short", dual_stack_ipv6="2001:db8::44")

    def test_key_description_requires_hardware_levels_and_exact_challenge(self) -> None:
        challenge = b"c" * 32
        description = der(
            0x30,
            der(0x02, b"\x04")
            + der(0x0A, b"\x02")
            + der(0x02, b"\x64")
            + der(0x0A, b"\x01")
            + der(0x04, challenge)
            + der(0x04, b""),
        )
        self.assertEqual(attestation._key_description(description), (2, 1, challenge))
        with self.assertRaisesRegex(ValueError, "schema differs"):
            attestation._key_description(
                description.replace(b"\x0a\x01\x02", b"\x02\x01\x02", 1)
            )

    def observations(self) -> dict:
        actions = []
        for index, spec in enumerate(raw_evidence.ACTION_SPECS):
            started = 1_800_000_000_000 + index * 2_000
            finished = started + 1_000
            correlation = hashlib.sha256(
                f"{self.run_id}:{spec.action_id}".encode()
            ).hexdigest()
            receipt = json.loads(
                fixtures.action_receipt(
                    spec.action_id,
                    correlation_id=correlation,
                    source_sha=self.source_sha,
                    app_sha256=self.app_sha,
                    test_sha256=self.test_sha,
                    started_at=started,
                    finished_at=finished,
                )
            )
            route = json.loads(
                fixtures.route_snapshot(
                    spec.action_id,
                    correlation_id=correlation,
                    source_sha=self.source_sha,
                    started_at=started,
                    finished_at=finished,
                )
            )
            actions.append(
                {
                    "actionId": spec.action_id,
                    "correlationId": correlation,
                    "dnsObservation": receipt["dnsObservation"],
                    "event": receipt["event"],
                    "fixture": receipt["fixture"],
                    "probes": receipt["probes"],
                    "routePhases": route["phases"],
                    "windowFinishedAtEpochMs": finished,
                    "windowStartedAtEpochMs": started,
                }
            )
        hardware = {
            "certificateChainDerBase64": ["leaf", "root"],
            "challengeSha256": attestation.expected_challenge(
                self.source_sha, self.app_sha, self.test_sha, self.run_id
            ).hex(),
            "requestedStrongBox": True,
            "version": attestation.HARDWARE_VERSION,
        }
        return {
            "actions": actions,
            "apiLevel": 37,
            "appApkSha256": self.app_sha,
            "deviceCodename": "husky",
            "deviceManufacturer": "Google",
            "hardwareAttestation": hardware,
            "kernelRelease": "6.1.0",
            "runId": self.run_id,
            "sourceSha": self.source_sha,
            "testApkSha256": self.test_sha,
            "version": physical_producer.OBSERVATION_VERSION,
        }

    def pass_inputs(self) -> tuple[dict, dict, dict]:
        observations = self.observations()
        documents = physical_producer.expected_raw_documents(observations)
        capture_sha = hashlib.sha256(b"physical-capture").hexdigest()
        action_proofs = {}
        gate_results = {}
        for spec in raw_evidence.ACTION_SPECS:
            receipt, route = documents[spec.action_id]
            action_proofs[spec.action_id] = {
                "artifacts": {
                    "action-receipt": hashlib.sha256(
                        oracles.canonical_json_bytes(receipt)
                    ).hexdigest(),
                    "packet-capture": capture_sha,
                    "route-snapshot": hashlib.sha256(
                        oracles.canonical_json_bytes(route)
                    ).hexdigest(),
                },
                "factsSha256": "f" * 64,
                "gateIds": list(spec.gate_ids),
            }
            gate_results.update(
                {gate_id: {"state": "PASS"} for gate_id in spec.gate_ids}
            )
        provenance = {
            "actionCount": 7,
            "actionProofs": action_proofs,
            "appApkSha256": self.app_sha,
            "artifactCount": 21,
            "manifestSha256": "b" * 64,
            "semanticVerifier": oracles.VERIFIER_VERSION,
            "testApkSha256": self.test_sha,
        }
        document = {
            "appApkSha256": self.app_sha,
            "captureSha256": capture_sha,
            "device": {
                "apiLevel": 37,
                "codename": "husky",
                "kernelRelease": "6.1.0",
                "manufacturer": "Google",
                "serialSha256": "d" * 64,
            },
            "hardwareAttestation": observations["hardwareAttestation"],
            "instrumentationTranscriptSha256": "e" * 64,
            "manifestSha256": "b" * 64,
            "observations": observations,
            "producerSha256": hashlib.sha256(
                Path(physical_producer.__file__).read_bytes()
            ).hexdigest(),
            "runId": self.run_id,
            "sourceSha": self.source_sha,
            "testApkSha256": self.test_sha,
            "version": attestation.ATTESTATION_VERSION,
        }
        return (
            provenance,
            {"actionProofs": action_proofs, "gateResults": gate_results},
            document,
        )

    def test_attested_semantics_are_the_only_pass_path(self) -> None:
        provenance, evaluation, document = self.pass_inputs()
        with mock.patch.object(
            gate_producer.android_ordinary_physical_attestation,
            "validate_physical_attestation",
            return_value={
                "producerSha256": document["producerSha256"],
                "runId": self.run_id,
            },
        ):
            results = gate_producer.semantic_verification_results(
                self.source_sha, provenance, evaluation, document
            )
        self.assertTrue(gate_producer.SOURCE_OWNED_PHYSICAL_PRODUCER_AVAILABLE)
        self.assertTrue(results["rawBundleProvenance"]["productionReady"])
        self.assertTrue(
            all(value == {"state": "PASS"} for value in results["gateResults"].values())
        )

    def test_copied_or_mutated_attestation_cannot_author_pass(self) -> None:
        provenance, evaluation, document = self.pass_inputs()
        results = gate_producer.semantic_verification_results(
            self.source_sha, provenance, evaluation
        )
        results["gateResults"] = {
            gate_id: {"state": "PASS"} for gate_id in gate_producer.ORDINARY_GATE_IDS
        }
        results["rawBundleProvenance"]["productionReady"] = True
        results["producerAttestation"] = document
        mutations = (
            lambda value: value["rawBundleProvenance"]["actionProofs"]["dual-stack"][
                "artifacts"
            ].update({"packet-capture": "0" * 64}),
            lambda value: value["producerAttestation"].update(
                {"producerSha256": "0" * 64}
            ),
            lambda value: value["producerAttestation"]["observations"]["actions"][
                0
            ].update({"correlationId": "0" * 64}),
        )
        for mutation in mutations:
            forged = copy.deepcopy(results)
            mutation(forged)
            with (
                self.subTest(mutation=mutation),
                mock.patch.object(
                    gate_producer.android_ordinary_physical_attestation,
                    "validate_physical_attestation",
                    side_effect=lambda attested, **_: {
                        "producerSha256": attested["producerSha256"],
                        "runId": attested["runId"],
                    },
                ),
                self.assertRaises(gate_producer.EvidenceError),
            ):
                gate_producer.validate_pass_results(forged)


if __name__ == "__main__":
    unittest.main()
