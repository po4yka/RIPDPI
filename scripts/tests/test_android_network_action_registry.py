#!/usr/bin/env python3

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "quality/release-gates/android-network-evidence-actions.json"
POLICY = ROOT / "quality/release-gates/dns-ipv6-killswitch-gates.json"
VALIDATOR = ROOT / "scripts/ci/check_android_network_action_receipt.py"
KOTLIN_RECEIPT = (
    ROOT
    / "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/NetworkEvidenceActionReceipt.kt"
)
KOTLIN_STARTUP_ACTION = (
    ROOT / "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/VpnStartupWindowE2ETest.kt"
)
SPEC = importlib.util.spec_from_file_location("network_action_receipt", VALIDATOR)
assert SPEC is not None and SPEC.loader is not None
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

SOURCE_SHA = "1" * 40
CORRELATION_ID = "2" * 64
CLIENT_SHA = "3" * 64
TEST_SHA = "4" * 64
FIXTURE_SHA = "5" * 64
TEST_READY_OVERRIDE = (
    ROOT / "scripts/tests/fixtures/android-network-evidence-test-ready-override.json"
)

DNS_DESCRIPTORS = {
    "dns-virtual-vpn-resolver": "virtual-vpn-resolver-v1",
    "dns-proxied-through-tunnelled-resolver": "proxied-domain-resolver-path-v1",
    "dns-direct-ru-only-for-direct-domains": "direct-ru-domain-partition-v1",
    "dns-allowlisted-bootstrap-resolution": "allowlisted-bootstrap-v1",
    "dns-no-isp-fallback-on-encrypted-resolver-outage": "encrypted-outage-fail-closed-v1",
    "dns-network-switch-behavior": "network-switch-dns-continuity-v1",
    "dns-core-crash-behavior": "core-crash-dns-fail-closed-v1",
    "dns-android-private-dns-conflict": "android-private-dns-conflict-v1",
    "synthetic-authoritative-dns-query-sources": "authoritative-query-sources-v1",
}

SOURCE_OWNED_DNS_ACTIONS = {
    "dns-virtual-vpn-resolver",
    "dns-proxied-through-tunnelled-resolver",
    "dns-no-isp-fallback-on-encrypted-resolver-outage",
}


def digest(label: str) -> str:
    return hashlib.sha256(label.encode("ascii")).hexdigest()


def valid_dns_receipt(gate_id: str) -> dict[str, object]:
    descriptor = module.load_action_registry()[gate_id]
    facts = module.example_valid_facts(gate_id)
    return {
        "version": module.VERSION,
        "status": "PASS",
        "gateId": gate_id,
        "kind": descriptor.kind,
        "selector": descriptor.selector,
        "semanticRule": descriptor.semantic_rule,
        "correlationId": CORRELATION_ID,
        "sourceSha": SOURCE_SHA,
        "clientArtifactSha256": CLIENT_SHA,
        "testArtifactSha256": TEST_SHA,
        "fixtureIdentitySha256": FIXTURE_SHA,
        "actionMarkerSha256": module.marker_sha256(
            CORRELATION_ID, "action", gate_id=gate_id, kind=descriptor.kind
        ),
        "outcomeMarkerSha256": module.marker_sha256(
            CORRELATION_ID, "outcome", gate_id=gate_id, kind=descriptor.kind
        ),
        "querySetSha256": module.query_set_sha256(gate_id, facts),
        "startedAtElapsedRealtimeMs": 100,
        "actionMarkerAtElapsedRealtimeMs": 110,
        "outcomeMarkerAtElapsedRealtimeMs": 300,
        "finishedAtElapsedRealtimeMs": 400,
        "appUid": 10101,
        "testUid": 10102,
        "actionMarkerPid": 201,
        "actionMarkerUid": 10101,
        "outcomeMarkerPid": 201,
        "outcomeMarkerUid": 10101,
        "dnsProbePids": [301],
        "dnsProbeUid": 10102,
        "facts": facts,
    }


class AndroidNetworkActionRegistryTest(unittest.TestCase):
    def test_registry_exactly_covers_policy_dual_vantage_gates(self) -> None:
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
        expected = {
            gate["id"]
            for gate in policy["gates"]
            if gate.get("evidenceSources", {}).get("android-client-release")
            == "dual-vantage-network-manifest"
        }
        expected.add("killswitch-tun-establish-native-ready")
        registry = module.load_action_registry()
        self.assertEqual(set(registry), expected)
        self.assertEqual(
            {gate_id: registry[gate_id].semantic_rule for gate_id in DNS_DESCRIPTORS},
            DNS_DESCRIPTORS,
        )
        self.assertEqual(
            len({descriptor.selector for descriptor in registry.values()}),
            len(registry),
        )
        self.assertEqual(
            len({descriptor.receipt_file for descriptor in registry.values()}),
            len(registry),
        )
        for gate_id, descriptor in registry.items():
            self.assertFalse(descriptor.production_ready, gate_id)
            if gate_id in DNS_DESCRIPTORS:
                self.assertEqual(descriptor.kind, "dns")

    def test_blocking_reasons_describe_current_technical_and_evidence_gaps(
        self,
    ) -> None:
        document = json.loads(REGISTRY.read_text(encoding="utf-8"))
        for action in document["actions"]:
            reasons = action["blockingReasons"]
            combined = " ".join(reasons).lower()
            with self.subTest(gate_id=action["gateId"]):
                self.assertNotIn("not authorized", combined)
                self.assertNotIn("no authorized", combined)
                self.assertNotIn("not approved", combined)
                self.assertIn(
                    "physical dual-vantage Pixel evidence has not yet been executed against the current source",
                    reasons,
                )
                if (
                    action["kind"] == "dns"
                    and action["gateId"] not in SOURCE_OWNED_DNS_ACTIONS
                ):
                    self.assertIn(
                        "production instrumentation selector is not implemented",
                        reasons,
                    )

    def test_wave_one_dns_actions_have_source_owned_selectors_and_transcript_facts(
        self,
    ) -> None:
        source = "\n".join(
            path.read_text(encoding="utf-8")
            for path in (
                KOTLIN_RECEIPT,
                ROOT
                / "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/DnsNetworkEvidenceE2ETest.kt",
            )
        )
        registry = module.load_action_registry()
        for gate_id in SOURCE_OWNED_DNS_ACTIONS:
            descriptor = registry[gate_id]
            method = descriptor.selector.split("#", 1)[1]
            with self.subTest(gate_id=gate_id):
                self.assertIn(f"fun {method}(", source)
                self.assertIn(
                    "fixtureTranscriptSha256", module.example_valid_facts(gate_id)
                )

    def test_registry_selectors_are_bound_to_exact_instrumentation_methods(
        self,
    ) -> None:
        source = "\n".join(
            path.read_text(encoding="utf-8")
            for path in (KOTLIN_RECEIPT, KOTLIN_STARTUP_ACTION)
        )
        for gate_id, descriptor in module.load_action_registry().items():
            class_name, method_name = descriptor.selector.split("#", 1)
            with self.subTest(gate_id=gate_id):
                if descriptor.production_ready:
                    self.assertIn(class_name, source)
                    self.assertIn(f"fun {method_name}(", source)

    def test_production_registry_receipts_cannot_validate_as_pass(self) -> None:
        for gate_id in module.load_action_registry():
            with self.subTest(gate_id=gate_id):
                with self.assertRaisesRegex(
                    module.ReceiptError, "not production ready"
                ):
                    module.validate_receipt(
                        valid_dns_receipt(gate_id),
                        gate_id=gate_id,
                        source_sha=SOURCE_SHA,
                        correlation_id=CORRELATION_ID,
                        client_artifact_sha256=CLIENT_SHA,
                        test_artifact_sha256=TEST_SHA,
                        fixture_identity_sha256=FIXTURE_SHA,
                    )

    def test_every_dns_descriptor_accepts_only_its_exact_facts(self) -> None:
        for gate_id in DNS_DESCRIPTORS:
            with self.subTest(gate_id=gate_id):
                receipt = valid_dns_receipt(gate_id)
                validated = module.validate_receipt(
                    receipt,
                    gate_id=gate_id,
                    source_sha=SOURCE_SHA,
                    correlation_id=CORRELATION_ID,
                    client_artifact_sha256=CLIENT_SHA,
                    test_artifact_sha256=TEST_SHA,
                    fixture_identity_sha256=FIXTURE_SHA,
                    test_only_ready_override=TEST_READY_OVERRIDE,
                )
                self.assertEqual(validated["gateId"], gate_id)

                unknown = copy.deepcopy(receipt)
                unknown["facts"]["gateClean"] = True
                with self.assertRaises(module.ReceiptError):
                    module.validate_receipt(
                        unknown,
                        gate_id=gate_id,
                        source_sha=SOURCE_SHA,
                        correlation_id=CORRELATION_ID,
                        client_artifact_sha256=CLIENT_SHA,
                        test_artifact_sha256=TEST_SHA,
                        fixture_identity_sha256=FIXTURE_SHA,
                        test_only_ready_override=TEST_READY_OVERRIDE,
                    )

                marker_only = copy.deepcopy(receipt)
                marker_only["facts"] = {}
                with self.assertRaises(module.ReceiptError):
                    module.validate_receipt(
                        marker_only,
                        gate_id=gate_id,
                        source_sha=SOURCE_SHA,
                        correlation_id=CORRELATION_ID,
                        client_artifact_sha256=CLIENT_SHA,
                        test_artifact_sha256=TEST_SHA,
                        fixture_identity_sha256=FIXTURE_SHA,
                        test_only_ready_override=TEST_READY_OVERRIDE,
                    )

    def test_swapped_gate_rule_selector_and_query_digest_fail(self) -> None:
        gate_id = "dns-virtual-vpn-resolver"
        other_gate = "dns-proxied-through-tunnelled-resolver"
        base = valid_dns_receipt(gate_id)
        mutations = {
            "gateId": other_gate,
            "semanticRule": DNS_DESCRIPTORS[other_gate],
            "selector": module.load_action_registry()[other_gate].selector,
            "querySetSha256": "malformed",
        }
        for field, replacement in mutations.items():
            with self.subTest(field=field):
                receipt = copy.deepcopy(base)
                receipt[field] = replacement
                with self.assertRaises(module.ReceiptError):
                    module.validate_receipt(
                        receipt,
                        gate_id=gate_id,
                        source_sha=SOURCE_SHA,
                        correlation_id=CORRELATION_ID,
                        client_artifact_sha256=CLIENT_SHA,
                        test_artifact_sha256=TEST_SHA,
                        fixture_identity_sha256=FIXTURE_SHA,
                        test_only_ready_override=TEST_READY_OVERRIDE,
                    )


if __name__ == "__main__":
    unittest.main()
