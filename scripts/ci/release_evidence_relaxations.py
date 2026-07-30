#!/usr/bin/env python3
"""Read the evidence relaxations declared by the DNS/IPv6/kill-switch policy.

The gate inventory in ``dns-ipv6-killswitch-gates.json`` stays fully no-ship:
every DNS leak, IPv6 leak, and kill-switch failure remains a release blocker.
What this module exposes is narrower -- which *evidence acquisition* obligations
the policy no longer requires before a gate may report PASS. Keeping the list in
the policy artifact rather than scattered across the enforcement scripts means a
reviewer can see the whole relaxed surface in one diff, and re-tightening is a
single-line edit.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = ROOT / "quality/release-gates/dns-ipv6-killswitch-gates.json"

# Every relaxation the enforcement scripts know how to honour. An id outside
# this set is a policy typo, not a silent no-op.
KNOWN_RELAXATIONS = frozenset(
    {
        "android-action-selector-receipt",
        "producer-collector-workload-allowlist",
        "private-runner-configuration",
        "exact-sha-physical-run",
    }
)


def relaxed_requirements(policy_path: Path | None = None) -> frozenset[str]:
    """Return the evidence requirements the policy no longer treats as mandatory."""
    path = POLICY_PATH if policy_path is None else policy_path
    try:
        policy = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"DNS evidence policy is unreadable: {error}") from error
    if not isinstance(policy, dict):
        raise ValueError("DNS evidence policy must be a JSON object")
    block = policy.get("relaxedEvidenceRequirements", {})
    if not isinstance(block, dict):
        raise ValueError("relaxedEvidenceRequirements must be an object")
    declared = block.get("requirements", [])
    if not isinstance(declared, list) or any(
        not isinstance(item, str) for item in declared
    ):
        raise ValueError(
            "relaxedEvidenceRequirements.requirements must be an array of strings"
        )
    if len(declared) != len(set(declared)):
        raise ValueError(
            "relaxedEvidenceRequirements.requirements must not repeat an entry"
        )
    unknown = sorted(set(declared) - KNOWN_RELAXATIONS)
    if unknown:
        raise ValueError(
            "unknown relaxed evidence requirement: " + ", ".join(unknown)
        )
    return frozenset(declared)


def is_relaxed(requirement: str, policy_path: Path | None = None) -> bool:
    """Return whether ``requirement`` has been relaxed by the release policy."""
    if requirement not in KNOWN_RELAXATIONS:
        raise ValueError(f"unknown evidence requirement: {requirement}")
    return requirement in relaxed_requirements(policy_path)
