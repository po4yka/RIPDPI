#!/usr/bin/env python3
"""Release gate: fleet release-gating and cadence policy.

Validates ``quality/release-gates/fleet-release-cadence-policy.json`` -- the
machine-readable source of truth for the daily/weekly/release cadences, the
staging/production/client-release gate sets, and the no-ship / warn-only
failure policy from the ``add-fleet-release-gating-and-cadence-policy`` task.

Run as a CI gate::

    python3 scripts/ci/check_fleet_release_gates.py                  # validate
    python3 scripts/ci/check_fleet_release_gates.py --report          # markdown

When a results file is supplied the script evaluates an actual gate run and
emits a short sanitized report, blocking on no-ship FAILs and demoting
warn-only FAILs to WARN::

    python3 scripts/ci/check_fleet_release_gates.py \\
        --gate-set production --results run.json

Exit code is 0 when the policy artifact is well-formed (and, when a results
file is supplied, no no-ship gate failed or used unscoped N/A); non-zero
otherwise.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

CI_DIR = Path(__file__).resolve().parent
if str(CI_DIR) not in sys.path:
    sys.path.insert(0, str(CI_DIR))

from release_gate_results import VALID_RESULT_STATES, na_out_of_scope_violation, normalize_gate_result


ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = ROOT / "quality/release-gates/fleet-release-cadence-policy.json"

EXPECTED_VERSION = "fleet_release_cadence_policy_v2"

# Acceptance criteria -> required owner-operated relay deployment controls.
REQUIRED_DEPLOYMENT_CONTROLS = {
    "certificate-rotation-drill": "Certificate Rotation",
    "per-device-credential-revocation": "Per-Device Credential Revocation",
    "disposable-relay-rebuild": "Disposable Rebuild",
    "firewall-drift-check": "Firewall Drift",
    "incident-playbook-ready": "Incident Playbooks",
}

# Acceptance criteria -> required cadence checks.
REQUIRED_CADENCE_CHECKS = {
    "daily": {
        "node-service-health",
        "external-tcp-443",
        "reality-non-ru-connect",
        "https-64kb-payload",
        "cert-expiry",
        "firewall-drift-check",
        "backup-age",
    },
    "weekly": {
        "ru-fixed-tests",
        "ru-mobile-tests",
        "dns-leak",
        "active-probe-simulation",
        "revoked-credential",
        "per-device-credential-revocation",
        "incident-playbook-ready",
        "delivery-token-expiry-revocation",
    },
    "release": {
        "full-predeploy-suite",
        "staging-deploy",
        "non-ru-smoke",
        "ru-fixed-smoke",
        "ru-mobile-smoke",
        "client-regression",
        "old-profile-revocation",
        "certificate-rotation-drill",
        "per-device-credential-revocation",
        "disposable-relay-rebuild",
        "firewall-drift-check",
        "incident-playbook-ready",
        "fresh-backup-after-deploy",
        "app-identity-review",
    },
}

# Acceptance criteria -> required gate-set members.
REQUIRED_GATE_SETS = {
    "staging": {
        "full-predeploy-suite",
        "staging-deploy",
        "non-ru-smoke",
        "ru-fixed-smoke",
        "ru-mobile-smoke",
        "client-regression",
        "old-profile-revocation",
        "fresh-backup-after-deploy",
    },
    "production": {
        "staging-success",
        "non-ru-smoke",
        "ru-fixed-pass-min-1",
        "ru-mobile-pass-min-1",
        "dns-leak",
        "android-kill-switch-primary-profile",
        "old-revoked-credential-fails",
        "certificate-rotation-drill",
        "per-device-credential-revocation",
        "disposable-relay-rebuild",
        "firewall-drift-check",
        "incident-playbook-ready",
        "delivery-token-ttl-revocation",
    },
    "relay-deployment": {
        "certificate-rotation-drill",
        "per-device-credential-revocation",
        "disposable-relay-rebuild",
        "firewall-drift-check",
        "incident-playbook-ready",
    },
    "client-release": {
        "android-api-matrix",
        "app-core-schema-migration",
        "package-visibility-per-app-routing",
        "app-identity-review",
        "logcat-no-secrets",
        "crash-reports-no-secrets",
    },
}

# Acceptance criteria -> required no-ship conditions.
REQUIRED_NOSHIP_CONDITIONS = {
    "xray-config-validation",
    "sing-box-config-validation",
    "firewall-validation",
    "certificate-rotation-drill",
    "per-device-credential-revocation",
    "disposable-relay-rebuild",
    "firewall-drift-check",
    "incident-playbook-ready",
    "dns-leak",
    "kill-switch-failure",
    "revoked-credential-still-connecting",
    "token-or-full-url-in-logs",
    "public-panel-response",
    "primary-and-fallback-same-burned-provider-or-asn",
    "app-identity-review",
}

# Acceptance criteria -> required warn-only conditions.
REQUIRED_WARNONLY_CONDITIONS = {
    "partial-hysteria2-udp-failure",
    "cloudflare-path-failure-non-cf-paths-healthy",
    "one-degraded-ru-operator-selector-avoids-it",
}


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_policy(policy: dict) -> dict:
    """Validate the fleet release/cadence policy. Raises ValueError on defects."""
    if policy.get("version") != EXPECTED_VERSION:
        raise ValueError(
            f"unexpected policy version: {policy.get('version')!r} "
            f"(expected {EXPECTED_VERSION!r})"
        )

    cadences = policy.get("cadences")
    if not isinstance(cadences, dict):
        raise ValueError("policy must declare a 'cadences' object")
    for cadence, required in REQUIRED_CADENCE_CHECKS.items():
        entry = cadences.get(cadence)
        if not isinstance(entry, dict):
            raise ValueError(f"missing cadence: {cadence}")
        checks = set(entry.get("checks", []))
        missing = required - checks
        if missing:
            raise ValueError(
                f"cadence '{cadence}' is missing checks: "
                + ", ".join(sorted(missing))
            )

    gate_sets = policy.get("gateSets")
    if not isinstance(gate_sets, dict):
        raise ValueError("policy must declare a 'gateSets' object")
    for name, required in REQUIRED_GATE_SETS.items():
        entry = gate_sets.get(name)
        if not isinstance(entry, dict):
            raise ValueError(f"missing gate set: {name}")
        requires = set(entry.get("requires", []))
        missing = required - requires
        if missing:
            raise ValueError(
                f"gate set '{name}' is missing required gates: "
                + ", ".join(sorted(missing))
            )

    no_ship = policy.get("noShipPolicy")
    if not isinstance(no_ship, dict):
        raise ValueError("policy must declare a 'noShipPolicy' object")
    no_ship_conditions = set(no_ship.get("conditions", []))
    missing_noship = REQUIRED_NOSHIP_CONDITIONS - no_ship_conditions
    if missing_noship:
        raise ValueError(
            "noShipPolicy.conditions is missing: "
            + ", ".join(sorted(missing_noship))
        )

    warn_only = policy.get("warnOnlyPolicy")
    if not isinstance(warn_only, dict):
        raise ValueError("policy must declare a 'warnOnlyPolicy' object")
    warn_only_conditions = set(warn_only.get("conditions", []))
    missing_warn = REQUIRED_WARNONLY_CONDITIONS - warn_only_conditions
    if missing_warn:
        raise ValueError(
            "warnOnlyPolicy.conditions is missing: "
            + ", ".join(sorted(missing_warn))
        )

    overlap = no_ship_conditions & warn_only_conditions
    if overlap:
        raise ValueError(
            "a condition cannot be both no-ship and warn-only: "
            + ", ".join(sorted(overlap))
        )

    deployment_plane = policy.get("deploymentPlane")
    if not isinstance(deployment_plane, dict):
        raise ValueError("policy must declare a 'deploymentPlane' object")
    documentation = deployment_plane.get("documentation")
    if not isinstance(documentation, str) or not documentation:
        raise ValueError("deploymentPlane.documentation must name a documentation file")
    documentation_path = ROOT / documentation
    if not documentation_path.is_file():
        raise ValueError(f"deploymentPlane.documentation does not exist: {documentation}")
    documentation_text = documentation_path.read_text(encoding="utf-8").lower()
    controls = deployment_plane.get("requiredControls")
    if not isinstance(controls, list):
        raise ValueError("deploymentPlane.requiredControls must be a list")
    controls_by_id = {
        control.get("id"): control
        for control in controls
        if isinstance(control, dict)
    }
    missing_controls = set(REQUIRED_DEPLOYMENT_CONTROLS) - set(controls_by_id)
    if missing_controls:
        raise ValueError(
            "deploymentPlane.requiredControls is missing: "
            + ", ".join(sorted(missing_controls))
        )
    for control_id, heading in REQUIRED_DEPLOYMENT_CONTROLS.items():
        control = controls_by_id[control_id]
        gate_memberships = control.get("gateSets")
        if not isinstance(gate_memberships, list) or "relay-deployment" not in gate_memberships:
            raise ValueError(f"{control_id}: gateSets must include relay-deployment")
        doc_anchor = control.get("docAnchor")
        if not isinstance(doc_anchor, str) or not doc_anchor:
            raise ValueError(f"{control_id}: docAnchor must be a non-empty string")
        expected_anchor = heading.lower().replace(" ", "-")
        if doc_anchor != expected_anchor:
            raise ValueError(f"{control_id}: docAnchor must be {expected_anchor!r}")
        if f"## {heading.lower()}" not in documentation_text:
            raise ValueError(f"{control_id}: documentation is missing heading {heading!r}")
        if control_id not in no_ship_conditions:
            raise ValueError(f"{control_id}: deployment control must be no-ship")
        if not any(control_id in set(entry.get("checks", [])) for entry in cadences.values()):
            raise ValueError(f"{control_id}: deployment control is not assigned to a cadence")
        if not any(control_id in set(entry.get("requires", [])) for entry in gate_sets.values()):
            raise ValueError(f"{control_id}: deployment control is not assigned to a gate set")

    return {
        "cadences": sorted(cadences),
        "gateSets": sorted(gate_sets),
        "deploymentControls": sorted(REQUIRED_DEPLOYMENT_CONTROLS),
        "noShipConditions": sorted(no_ship_conditions),
        "warnOnlyConditions": sorted(warn_only_conditions),
    }


def evaluate_gate_set(policy: dict, gate_set: str, results: dict) -> dict:
    """Evaluate a gate-set run and produce a short sanitized report.

    ``results`` maps gate id -> result state or structured result object.
    No-ship FAILs block (non-zero exit); warn-only FAILs are demoted to WARN
    and surfaced in the report. Missing gates, invalid states, and unscoped N/A
    results are no-ship blockers.
    """
    gate_sets = policy.get("gateSets", {})
    if gate_set not in gate_sets:
        raise ValueError(f"unknown gate set: {gate_set}")

    required = gate_sets[gate_set]["requires"]
    no_ship_conditions = set(policy["noShipPolicy"]["conditions"])
    warn_only_conditions = set(policy["warnOnlyPolicy"]["conditions"])
    result_map = results.get("gateResults", results)
    if not isinstance(result_map, dict):
        raise ValueError("results must be an object of gateId -> state")

    rows: list[dict] = []
    blocking: list[str] = []
    for gate_id in required:
        raw_result = result_map.get(gate_id)
        if raw_result is None:
            rows.append({"gate": gate_id, "state": "FAIL", "note": "missing result"})
            blocking.append(f"{gate_id}: missing result")
            continue
        try:
            result = normalize_gate_result(gate_id, raw_result)
        except ValueError as exc:
            rows.append({"gate": gate_id, "state": "FAIL", "note": str(exc)})
            blocking.append(str(exc))
            continue
        if result.state not in VALID_RESULT_STATES:
            rows.append({"gate": gate_id, "state": "FAIL", "note": f"invalid state {result.state!r}"})
            blocking.append(f"{gate_id}: invalid state {result.state!r}")
            continue
        na_violation = na_out_of_scope_violation(gate_id, result, gate_set=gate_set)
        if na_violation:
            rows.append({"gate": gate_id, "state": "FAIL", "note": na_violation})
            blocking.append(na_violation)
        elif result.state == "FAIL":
            if gate_id in warn_only_conditions and gate_id not in no_ship_conditions:
                rows.append({"gate": gate_id, "state": "WARN", "note": "warn-only: demoted"})
            else:
                rows.append({"gate": gate_id, "state": "FAIL", "note": "no-ship"})
                blocking.append(f"{gate_id}: FAIL (no-ship)")
        else:
            note = f"out-of-scope: {result.reason}" if result.state == "N/A" else ""
            rows.append({"gate": gate_id, "state": result.state, "note": note})

    return {
        "gateSet": gate_set,
        "rows": rows,
        "blocking": blocking,
        "verdict": "FAIL" if blocking else "PASS",
    }


def render_report(policy: dict, summary: dict) -> str:
    lines = ["# Fleet release gating and cadence policy", ""]
    lines.append(f"- Policy version: `{policy.get('version')}`")
    lines.append(f"- Cadences: {', '.join(summary['cadences'])}")
    lines.append(f"- Gate sets: {', '.join(summary['gateSets'])}")
    lines.append(f"- Deployment controls: {len(summary['deploymentControls'])}")
    lines.append(f"- No-ship conditions: {len(summary['noShipConditions'])}")
    lines.append(f"- Warn-only conditions: {len(summary['warnOnlyConditions'])}")
    lines.append("")
    for cadence in summary["cadences"]:
        checks = policy["cadences"][cadence].get("checks", [])
        lines.append(f"## Cadence: {cadence}")
        for check in checks:
            lines.append(f"- {check}")
        lines.append("")
    for name in summary["gateSets"]:
        requires = policy["gateSets"][name].get("requires", [])
        lines.append(f"## Gate set: {name}")
        for gate in requires:
            lines.append(f"- {gate}")
        lines.append("")
    return "\n".join(lines).rstrip()


def render_gate_set_report(evaluation: dict) -> str:
    lines = [f"# Fleet gate-set report: {evaluation['gateSet']}", ""]
    lines.append(f"Verdict: {evaluation['verdict']}")
    lines.append("")
    lines.append("| Gate | State | Note |")
    lines.append("|------|-------|------|")
    for row in evaluation["rows"]:
        lines.append(f"| `{row['gate']}` | {row['state']} | {row['note']} |")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--policy",
        default=str(POLICY_PATH),
        help="Path to the fleet release/cadence policy JSON.",
    )
    parser.add_argument(
        "--gate-set",
        choices=sorted(REQUIRED_GATE_SETS),
        default=None,
        help="Evaluate a specific gate set against --results.",
    )
    parser.add_argument(
        "--results",
        default=None,
        help="Optional gate-results JSON to evaluate (requires --gate-set).",
    )
    parser.add_argument(
        "--report",
        action="store_true",
        help="Print a markdown summary of the policy instead of a plain log.",
    )
    args = parser.parse_args(argv)

    policy_path = Path(args.policy)
    if not policy_path.is_file():
        print(f"fleet release/cadence policy not found: {policy_path}", file=sys.stderr)
        return 1

    policy = load_json(policy_path)
    summary = validate_policy(policy)

    if args.report:
        print(render_report(policy, summary))
    else:
        print("Fleet release gating and cadence policy check")
        print(f"Policy: {policy_path}")
        print("Cadences: " + ", ".join(summary["cadences"]))
        print("Gate sets: " + ", ".join(summary["gateSets"]))
        print(
            f"No-ship conditions: {len(summary['noShipConditions'])}; "
            f"warn-only conditions: {len(summary['warnOnlyConditions'])}"
        )

    if args.results:
        if not args.gate_set:
            print("--results requires --gate-set", file=sys.stderr)
            return 1
        results_path = Path(args.results)
        if not results_path.is_file():
            print(f"gate results file not found: {results_path}", file=sys.stderr)
            return 1
        evaluation = evaluate_gate_set(policy, args.gate_set, load_json(results_path))
        print()
        print(render_gate_set_report(evaluation))
        if evaluation["blocking"]:
            print(file=sys.stderr)
            print(
                f"NO-SHIP: gate set '{args.gate_set}' blocked by:",
                file=sys.stderr,
            )
            for entry in evaluation["blocking"]:
                print(f"  - {entry}", file=sys.stderr)
            return 1

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"Fleet release gate check failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
