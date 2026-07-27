#!/usr/bin/env python3
"""Release gate: DNS-leak, IPv6-leak, and kill-switch no-ship gates.

Validates ``quality/release-gates/dns-ipv6-killswitch-gates.json`` -- the
machine-readable source of truth for the DNS/IPv6/kill-switch release gates --
and asserts that every acceptance criterion from the
``add-dns-ipv6-and-kill-switch-release-gates`` task is represented by a
concrete, no-ship-classified gate.

Run as a CI gate::

    python3 scripts/ci/check_dns_ipv6_killswitch_gates.py --policy-only
    python3 scripts/ci/check_dns_ipv6_killswitch_gates.py --policy-only --report

When a gate result file is supplied the script also enforces the no-ship
policy against actual probe results::

    python3 scripts/ci/check_dns_ipv6_killswitch_gates.py --results run.json

Release evidence combines ordinary results with a validated dual-vantage bundle::

    python3 scripts/ci/check_dns_ipv6_killswitch_gates.py \
      --results ordinary-results.json \
      --evidence-manifest evidence/manifest.json \
      --applies-to android-client-release \
      --expected-source-sha "$GITHUB_SHA"

Exit code is 0 when the policy artifact is well-formed (and, when supplied,
every no-ship gate result is PASS or a structured, scoped out-of-scope N/A);
non-zero otherwise.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

CI_DIR = Path(__file__).resolve().parent
if str(CI_DIR) not in sys.path:
    sys.path.insert(0, str(CI_DIR))

from release_gate_results import (  # noqa: E402
    VALID_RESULT_STATES,
    na_out_of_scope_violation,
    normalize_gate_result,
)
import network_evidence_manifest  # noqa: E402
import produce_android_ordinary_gate_results as android_ordinary_results  # noqa: E402


ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = ROOT / "quality/release-gates/dns-ipv6-killswitch-gates.json"

EXPECTED_VERSION = "dns_ipv6_killswitch_release_gates_v1"
EXPECTED_RESULTS_VERSION = "dns_ipv6_killswitch_results_v1"
DUAL_VANTAGE_EVIDENCE_SOURCE = "dual-vantage-network-manifest"
ORDINARY_EVIDENCE_SOURCE = "ordinary-results"
STARTUP_BARRIER_GATE_ID = "killswitch-tun-establish-native-ready"
STARTUP_BARRIER_APPLIES_TO = ["android-client-release"]

# Every acceptance criterion from docs/tasks/issues/
# add-dns-ipv6-and-kill-switch-release-gates.md, expressed as the gate ids that
# MUST exist in the policy artifact. If the spec grows, this set grows with it
# and the paired unit test fails until the policy artifact is updated.
REQUIRED_GATE_IDS = {
    # DNS leak gates.
    "dns-virtual-vpn-resolver",
    "dns-proxied-through-tunnelled-resolver",
    "dns-direct-ru-only-for-direct-domains",
    "dns-allowlisted-bootstrap-resolution",
    "dns-no-isp-fallback-on-encrypted-resolver-outage",
    "dns-network-switch-behavior",
    "dns-core-crash-behavior",
    "dns-android-private-dns-conflict",
    # Synthetic authoritative DNS gate.
    "synthetic-authoritative-dns-query-sources",
    # IPv4-only IPv6-leak gates.
    "ipv4only-no-ipv6-dns-address-route",
    "ipv4only-no-direct-ipv6",
    "ipv4only-blocked-ipv6-only-connect",
    "ipv4only-empty-or-blocked-aaaa",
    # Dual-stack IPv6 gates.
    "dualstack-default-route-through-tunnel",
    "dualstack-aaaa-through-tunnel",
    # Kill-switch gates.
    "killswitch-forced-disconnect",
    "killswitch-core-crash",
    "killswitch-wifi-lte-switch",
    "killswitch-sleep-wake",
    "killswitch-android-always-on-block",
    STARTUP_BARRIER_GATE_ID,
}
MANDATORY_ANDROID_DUAL_VANTAGE_GATE_IDS = {
    gate_id
    for gate_id in REQUIRED_GATE_IDS
    if gate_id.startswith(("dns-", "synthetic-"))
} | {STARTUP_BARRIER_GATE_ID}
MANDATORY_FLEET_ORDINARY_GATE_IDS = REQUIRED_GATE_IDS - {
    "killswitch-android-always-on-block",
    STARTUP_BARRIER_GATE_ID,
}

# Categories the policy must cover; mirrors the acceptance-criteria groupings.
REQUIRED_CATEGORIES = {
    "dns-leak",
    "synthetic-authoritative-dns",
    "ipv4-only-ipv6-leak",
    "dual-stack-ipv6",
    "kill-switch",
}

# Failure classifications that the no-ship policy must treat as no-ship.
REQUIRED_NOSHIP_CLASSIFICATIONS = {
    "dns-leak",
    "ipv6-leak",
    "kill-switch-failure",
}


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_policy(policy: dict) -> dict:
    """Validate the gate policy artifact. Raises ValueError on any defect."""
    if policy.get("version") != EXPECTED_VERSION:
        raise ValueError(
            f"unexpected policy version: {policy.get('version')!r} "
            f"(expected {EXPECTED_VERSION!r})"
        )

    gates = policy.get("gates")
    if not isinstance(gates, list) or not gates:
        raise ValueError("policy must declare a non-empty 'gates' list")

    seen_ids: set[str] = set()
    seen_categories: set[str] = set()
    for index, gate in enumerate(gates):
        gate_id = gate.get("id")
        if not gate_id or not isinstance(gate_id, str):
            raise ValueError(f"gate #{index} is missing a string 'id'")
        if gate_id in seen_ids:
            raise ValueError(f"duplicate gate id: {gate_id}")
        seen_ids.add(gate_id)

        category = gate.get("category")
        if category not in REQUIRED_CATEGORIES:
            raise ValueError(
                f"gate {gate_id} has unknown category {category!r}; "
                f"expected one of {sorted(REQUIRED_CATEGORIES)}"
            )
        seen_categories.add(category)

        if not gate.get("requirement"):
            raise ValueError(f"gate {gate_id} is missing a 'requirement'")

        if gate.get("noShip") is not True:
            raise ValueError(
                f"gate {gate_id} is not marked noShip=true; every DNS/IPv6/"
                "kill-switch gate is a no-ship blocker"
            )

        classification = gate.get("failureClassification")
        if classification not in REQUIRED_NOSHIP_CLASSIFICATIONS:
            raise ValueError(
                f"gate {gate_id} has failureClassification {classification!r}; "
                f"expected one of {sorted(REQUIRED_NOSHIP_CLASSIFICATIONS)}"
            )

        evidence_source = gate.get("evidenceSource")
        if gate_id == STARTUP_BARRIER_GATE_ID and (
            evidence_source != DUAL_VANTAGE_EVIDENCE_SOURCE
            or gate.get("appliesTo") != STARTUP_BARRIER_APPLIES_TO
        ):
            raise ValueError(
                "startup barrier gate must require dual-vantage network evidence "
                "and apply exactly to android-client-release"
            )
        if evidence_source not in (None, DUAL_VANTAGE_EVIDENCE_SOURCE):
            raise ValueError(
                f"gate {gate_id} has unknown evidenceSource {evidence_source!r}"
            )
        evidence_sources = gate.get("evidenceSources", {})
        if not isinstance(evidence_sources, dict) or any(
            scope not in policy.get("appliesTo", [])
            or source not in (DUAL_VANTAGE_EVIDENCE_SOURCE, "ordinary-results")
            for scope, source in evidence_sources.items()
        ):
            raise ValueError(f"gate {gate_id} has invalid evidenceSources mapping")

    missing_gates = REQUIRED_GATE_IDS - seen_ids
    if missing_gates:
        raise ValueError(
            "policy is missing required gates: " + ", ".join(sorted(missing_gates))
        )

    gate_index = {gate["id"]: gate for gate in gates}
    android_dual_vantage = dual_vantage_gate_ids(
        policy, applies_to="android-client-release"
    )
    if android_dual_vantage != MANDATORY_ANDROID_DUAL_VANTAGE_GATE_IDS:
        raise ValueError(
            "android dual-vantage gate mapping must match the mandatory set; "
            f"missing={sorted(MANDATORY_ANDROID_DUAL_VANTAGE_GATE_IDS - android_dual_vantage)}, "
            f"extra={sorted(android_dual_vantage - MANDATORY_ANDROID_DUAL_VANTAGE_GATE_IDS)}"
        )
    fleet_applicable = {
        gate_id
        for gate_id, gate in gate_index.items()
        if "fleet-profile-rollout" in gate.get("appliesTo", policy.get("appliesTo", []))
    }
    if fleet_applicable != MANDATORY_FLEET_ORDINARY_GATE_IDS:
        raise ValueError(
            "fleet applicable gate set must match the mandatory ordinary set"
        )
    for gate_id in MANDATORY_FLEET_ORDINARY_GATE_IDS:
        gate = gate_index[gate_id]
        effective_source = gate.get("evidenceSources", {}).get(
            "fleet-profile-rollout",
            gate.get("evidenceSource", ORDINARY_EVIDENCE_SOURCE),
        )
        if effective_source != ORDINARY_EVIDENCE_SOURCE:
            raise ValueError(f"fleet gate {gate_id} must use ordinary-results")

    missing_categories = REQUIRED_CATEGORIES - seen_categories
    if missing_categories:
        raise ValueError(
            "policy is missing required categories: "
            + ", ".join(sorted(missing_categories))
        )

    no_ship = policy.get("noShipPolicy")
    if not isinstance(no_ship, dict):
        raise ValueError("policy must declare a 'noShipPolicy' object")
    no_ship_classes = set(no_ship.get("failureClassifications", []))
    missing_classes = REQUIRED_NOSHIP_CLASSIFICATIONS - no_ship_classes
    if missing_classes:
        raise ValueError(
            "noShipPolicy.failureClassifications is missing: "
            + ", ".join(sorted(missing_classes))
        )

    return {
        "gateCount": len(gates),
        "gateIds": sorted(seen_ids),
        "categories": sorted(seen_categories),
        "noShipClassifications": sorted(no_ship_classes),
    }


def evaluate_results(
    policy: dict, results: dict, applies_to: str | None = None
) -> dict:
    """Evaluate actual gate results against the no-ship policy.

    ``results`` maps gate id -> result state or structured result object. Any
    FAIL/WARN/N/A on a no-ship gate, any unknown state, or any missing gate is a
    no-ship violation unless N/A is explicitly marked out-of-scope for the
    current appliesTo target with a reason.
    """
    gate_index = {gate["id"]: gate for gate in policy["gates"]}
    result_map = results.get("gateResults", results)
    if not isinstance(result_map, dict):
        raise ValueError("results must be an object of gateId -> state")

    violations: list[str] = []
    for gate_id, gate in gate_index.items():
        raw_result = result_map.get(gate_id)
        if raw_result is None:
            violations.append(f"{gate_id}: missing result (no-ship gate)")
            continue
        try:
            result = normalize_gate_result(gate_id, raw_result)
        except ValueError as exc:
            violations.append(str(exc))
            continue
        if result.state not in VALID_RESULT_STATES:
            violations.append(f"{gate_id}: invalid result state {result.state!r}")
            continue
        na_violation = na_out_of_scope_violation(gate_id, result, applies_to=applies_to)
        if na_violation:
            violations.append(na_violation)
            continue
        if result.state in ("FAIL", "WARN") and gate.get("noShip") is True:
            reason_suffix = f"; reason={result.reason}" if result.reason else ""
            violations.append(
                f"{gate_id}: {result.state} on no-ship gate "
                f"({gate.get('failureClassification')}){reason_suffix}"
            )

    unknown = sorted(set(result_map) - set(gate_index))
    for gate_id in unknown:
        violations.append(f"{gate_id}: result for unknown gate id")

    return {
        "violations": violations,
        "evaluated": len(gate_index),
    }


def validate_results_document(
    policy: dict,
    results: dict,
    *,
    expected_source_sha: str,
    applies_to: str,
) -> dict:
    """Validate provenance and scope before evaluating release evidence."""
    if not isinstance(results, dict):
        raise ValueError("results document must be a JSON object")
    if results.get("version") != EXPECTED_RESULTS_VERSION:
        raise ValueError(
            f"unexpected results version: {results.get('version')!r} "
            f"(expected {EXPECTED_RESULTS_VERSION!r})"
        )
    source_sha = results.get("sourceSha")
    if (
        not isinstance(source_sha, str)
        or re.fullmatch(r"[0-9a-f]{40}", source_sha) is None
    ):
        raise ValueError("results sourceSha must be a lowercase 40-character Git SHA-1")
    if source_sha != expected_source_sha:
        raise ValueError(
            f"results sourceSha {source_sha!r} does not match expected sourceSha {expected_source_sha!r}"
        )
    if applies_to not in policy.get("appliesTo", []):
        raise ValueError(f"policy does not apply to {applies_to!r}")
    if results.get("appliesTo") != applies_to:
        raise ValueError(
            f"results appliesTo {results.get('appliesTo')!r} does not match requested appliesTo {applies_to!r}"
        )
    if not isinstance(results.get("gateResults"), dict):
        raise ValueError("results document must contain a gateResults object")
    applicable_ids = applicable_gate_ids(policy, applies_to=applies_to)
    ordinary_gate_ids = applicable_ids - dual_vantage_gate_ids(
        policy, applies_to=applies_to
    )
    actual_gate_ids = set(results["gateResults"])
    if applies_to == "android-client-release" and actual_gate_ids not in (
        ordinary_gate_ids,
        applicable_ids,
    ):
        raise ValueError(
            "Android results must exactly cover all ordinary gates or all "
            "applicable gates; "
            f"missingOrdinary={sorted(ordinary_gate_ids - actual_gate_ids)}, "
            f"missingApplicable={sorted(applicable_ids - actual_gate_ids)}, "
            f"extra={sorted(actual_gate_ids - applicable_ids)}"
        )
    ordinary_values = {
        gate_id: normalize_gate_result(gate_id, results["gateResults"][gate_id])
        for gate_id in actual_gate_ids & ordinary_gate_ids
    }
    if applies_to == "android-client-release" and any(
        value.state == "PASS" for value in ordinary_values.values()
    ):
        android_ordinary_results.validate_pass_results(results)
    if applies_to == "android-client-release" and actual_gate_ids == ordinary_gate_ids:
        structured = all(
            isinstance(value, dict)
            and (
                value == {"state": "PASS"}
                or (
                    value.get("state") == "FAIL"
                    and set(value) == {"reason", "state"}
                    and isinstance(value.get("reason"), str)
                    and value["reason"].strip()
                )
            )
            for value in results["gateResults"].values()
        )
        if not structured:
            raise ValueError(
                "Android ordinary results must be structured verifier-owned PASS "
                "or reasoned FAIL objects"
            )
    return results


def dual_vantage_gate_ids(policy: dict, *, applies_to: str) -> set[str]:
    """Return gates that cannot be satisfied by self-attested results."""
    policy_scopes = policy.get("appliesTo", [])
    return {
        gate["id"]
        for gate in policy["gates"]
        if gate.get("evidenceSources", {}).get(applies_to, gate.get("evidenceSource"))
        == DUAL_VANTAGE_EVIDENCE_SOURCE
        and applies_to in gate.get("appliesTo", policy_scopes)
    }


def applicable_gate_ids(policy: dict, *, applies_to: str) -> set[str]:
    """Return policy gates that apply to the selected release target."""
    policy_scopes = policy.get("appliesTo", [])
    return {
        gate["id"]
        for gate in policy["gates"]
        if applies_to in gate.get("appliesTo", policy_scopes)
    }


def render_report(policy: dict, summary: dict) -> str:
    lines = ["# DNS / IPv6 / kill-switch release gates", ""]
    lines.append(f"- Policy version: `{policy.get('version')}`")
    lines.append(f"- Gates: {summary['gateCount']}")
    lines.append(f"- Categories: {', '.join(summary['categories'])}")
    lines.append(
        "- No-ship classifications: " + ", ".join(summary["noShipClassifications"])
    )
    lines.append("")
    lines.append("| Gate | Category | Failure class | No-ship |")
    lines.append("|------|----------|---------------|---------|")
    for gate in sorted(policy["gates"], key=lambda g: (g["category"], g["id"])):
        lines.append(
            f"| `{gate['id']}` | {gate['category']} | "
            f"{gate['failureClassification']} | "
            f"{'yes' if gate.get('noShip') else 'no'} |"
        )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--policy",
        default=str(POLICY_PATH),
        help="Path to the DNS/IPv6/kill-switch gate policy JSON.",
    )
    parser.add_argument(
        "--results",
        default=None,
        help="Gate-results JSON to enforce the no-ship policy against.",
    )
    parser.add_argument(
        "--evidence-manifest",
        default=None,
        help="Validated dual-vantage network evidence manifest to enforce.",
    )
    parser.add_argument(
        "--policy-only",
        action="store_true",
        help="Validate only the policy schema; never treat this as release evidence.",
    )
    parser.add_argument(
        "--applies-to",
        choices=("android-client-release", "fleet-profile-rollout"),
        default=None,
        help="Current release target for validating structured out-of-scope N/A results.",
    )
    parser.add_argument(
        "--expected-source-sha",
        default=None,
        help="Exact 40-character commit SHA that the results artifact must attest.",
    )
    parser.add_argument(
        "--max-evidence-age-seconds",
        type=int,
        default=2_592_000,
        help="Maximum age of dual-vantage release evidence (default: 30 days).",
    )
    parser.add_argument(
        "--expected-evidence-run-id",
        type=int,
        default=None,
        help="Deprecated GitHub Actions alias for --expected-execution-id.",
    )
    parser.add_argument(
        "--expected-evidence-run-attempt",
        type=int,
        default=None,
        help="Deprecated GitHub Actions alias for --expected-execution-attempt.",
    )
    parser.add_argument(
        "--expected-execution-kind",
        choices=("github-actions", "local"),
        default=None,
        help="Executor kind that the evidence manifest must attest.",
    )
    parser.add_argument(
        "--expected-execution-id",
        default=None,
        help="Executor-neutral run id that the evidence manifest must attest.",
    )
    parser.add_argument(
        "--expected-execution-attempt",
        type=int,
        default=None,
        help="Executor-neutral run attempt that the evidence manifest must attest.",
    )
    parser.add_argument(
        "--report",
        action="store_true",
        help="Print a markdown summary of the policy instead of a plain log.",
    )
    args = parser.parse_args(argv)

    expected_execution_kind = args.expected_execution_kind
    expected_execution_id = args.expected_execution_id
    expected_execution_attempt = args.expected_execution_attempt
    if args.expected_evidence_run_id is not None:
        legacy_id = str(args.expected_evidence_run_id)
        if expected_execution_id not in (None, legacy_id):
            parser.error("conflicting expected execution ids")
        if expected_execution_kind not in (None, "github-actions"):
            parser.error("legacy evidence run id requires github-actions")
        expected_execution_kind = "github-actions"
        expected_execution_id = legacy_id
    if args.expected_evidence_run_attempt is not None:
        if expected_execution_attempt not in (
            None,
            args.expected_evidence_run_attempt,
        ):
            parser.error("conflicting expected execution attempts")
        expected_execution_attempt = args.expected_evidence_run_attempt

    evidence_supplied = args.evidence_manifest is not None
    results_supplied = args.results is not None
    if not evidence_supplied and not results_supplied and not args.policy_only:
        print(
            "release evidence is required; pass --evidence-manifest FILE, --results FILE, "
            "or explicitly use --policy-only",
            file=sys.stderr,
        )
        return 1
    if args.policy_only and (evidence_supplied or results_supplied):
        print("--policy-only cannot be combined with release evidence", file=sys.stderr)
        return 1
    if (evidence_supplied or results_supplied) and args.applies_to is None:
        print(
            "--applies-to is required when evaluating release evidence", file=sys.stderr
        )
        return 1
    if (evidence_supplied or results_supplied) and args.expected_source_sha is None:
        print(
            "--expected-source-sha is required when evaluating release evidence",
            file=sys.stderr,
        )
        return 1
    if args.policy_only and args.applies_to is not None:
        print("--applies-to is only valid with --results", file=sys.stderr)
        return 1
    if args.policy_only and args.expected_source_sha is not None:
        print("--expected-source-sha is only valid with --results", file=sys.stderr)
        return 1

    policy_path = Path(args.policy)
    if not policy_path.is_file():
        print(
            f"DNS/IPv6/kill-switch gate policy not found: {policy_path}",
            file=sys.stderr,
        )
        return 1

    policy = load_json(policy_path)
    summary = validate_policy(policy)

    if args.report:
        print(render_report(policy, summary))
    else:
        print("DNS / IPv6 / kill-switch release gate check")
        print(f"Policy: {policy_path}")
        print(
            f"Gates: {summary['gateCount']} across {len(summary['categories'])} categories"
        )
        print("Categories: " + ", ".join(summary["categories"]))
        print("All gates are no-ship blockers.")

    evidence_gate_ids = (
        dual_vantage_gate_ids(policy, applies_to=args.applies_to)
        if args.applies_to is not None
        else set()
    )
    if evidence_gate_ids and not evidence_supplied:
        print(
            "dual-vantage gates require --evidence-manifest: "
            + ", ".join(sorted(evidence_gate_ids)),
            file=sys.stderr,
        )
        return 1
    if evidence_supplied and not results_supplied:
        print(
            "--results is required with --evidence-manifest to cover remaining gates",
            file=sys.stderr,
        )
        return 1

    merged_gate_results: dict[str, object] = {}
    if evidence_supplied:
        manifest_path = Path(args.evidence_manifest)
        if not manifest_path.is_file():
            print(
                f"network evidence manifest not found: {manifest_path}", file=sys.stderr
            )
            return 1
        manifest, manifest_raw = network_evidence_manifest.load_json_bytes(
            manifest_path
        )
        if manifest_raw != network_evidence_manifest.canonical_json_bytes(manifest):
            raise ValueError("network evidence manifest is not canonical JSON")
        evidence_summary = network_evidence_manifest.validate_manifest(
            manifest,
            artifact_root=manifest_path.parent,
            expected_source_sha=args.expected_source_sha,
            applies_to=args.applies_to,
            current_epoch=int(time.time()),
            max_age_seconds=args.max_evidence_age_seconds,
            expected_execution_kind=expected_execution_kind,
            expected_execution_id=expected_execution_id,
            expected_execution_attempt=expected_execution_attempt,
            require_pass=True,
        )
        if set(evidence_summary["gateResults"]) != evidence_gate_ids:
            raise ValueError(
                "network evidence gateResults do not match policy evidence gates"
            )
        merged_gate_results.update(evidence_summary["gateResults"])
    if results_supplied:
        results_path = Path(args.results)
        if not results_path.is_file():
            print(f"gate results file not found: {results_path}", file=sys.stderr)
            return 1
        ordinary_results = validate_results_document(
            policy,
            load_json(results_path),
            expected_source_sha=args.expected_source_sha,
            applies_to=args.applies_to,
        )
        ordinary_gate_results = ordinary_results["gateResults"]
        overlap = set(ordinary_gate_results) & evidence_gate_ids
        if overlap:
            raise ValueError(
                "self-attested results must not contain dual-vantage gates: "
                + ", ".join(sorted(overlap))
            )
        merged_gate_results.update(ordinary_gate_results)
    else:
        ordinary_results = None

    if merged_gate_results:
        merged_results = {
            "version": EXPECTED_RESULTS_VERSION,
            "sourceSha": args.expected_source_sha,
            "appliesTo": args.applies_to,
            "gateResults": merged_gate_results,
        }
        validated_results = validate_results_document(
            policy,
            merged_results,
            expected_source_sha=args.expected_source_sha,
            applies_to=args.applies_to,
        )
        expected_gate_ids = applicable_gate_ids(policy, applies_to=args.applies_to)
        actual_gate_ids = set(validated_results["gateResults"])
        if actual_gate_ids != expected_gate_ids:
            raise ValueError(
                "combined gate results do not exactly cover applicable policy gates; "
                f"missing={sorted(expected_gate_ids - actual_gate_ids)}, "
                f"extra={sorted(actual_gate_ids - expected_gate_ids)}"
            )
        evaluation_policy = {
            **policy,
            "gates": [
                gate for gate in policy["gates"] if gate["id"] in expected_gate_ids
            ],
        }
        evaluation = evaluate_results(
            evaluation_policy, validated_results, applies_to=args.applies_to
        )
        if evaluation["violations"]:
            print("NO-SHIP: release gate violations detected:", file=sys.stderr)
            for violation in evaluation["violations"]:
                print(f"  - {violation}", file=sys.stderr)
            return 1
        print(
            f"All {evaluation['evaluated']} gate results PASS or scoped out-of-scope N/A."
        )

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"DNS/IPv6/kill-switch gate check failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
