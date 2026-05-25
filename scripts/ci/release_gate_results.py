#!/usr/bin/env python3
"""Shared result parsing for release-gate CI scripts."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any


VALID_RESULT_STATES = {"PASS", "WARN", "FAIL", "N/A"}


@dataclass(frozen=True)
class GateResult:
    state: str
    out_of_scope: bool
    reason: str
    applies_to: tuple[str, ...]
    gate_sets: tuple[str, ...]


def normalize_gate_result(gate_id: str, raw: Any) -> GateResult:
    """Normalize string and structured gate-result records.

    Backward-compatible string results remain valid for PASS/WARN/FAIL. N/A is
    intentionally stricter: callers must reject it unless the result object
    marks a deliberate, scoped out-of-scope decision.
    """
    if isinstance(raw, str):
        return GateResult(raw, False, "", (), ())

    if not isinstance(raw, dict):
        raise ValueError(f"{gate_id}: result must be a state string or object")

    state = raw.get("state")
    if not isinstance(state, str):
        raise ValueError(f"{gate_id}: structured result is missing string state")

    out_of_scope = raw.get("outOfScope", False)
    if not isinstance(out_of_scope, bool):
        raise ValueError(f"{gate_id}: outOfScope must be a boolean when present")

    reason = raw.get("reason", "")
    if reason is None:
        reason = ""
    if not isinstance(reason, str):
        raise ValueError(f"{gate_id}: reason must be a string when present")

    return GateResult(
        state=state,
        out_of_scope=out_of_scope,
        reason=reason.strip(),
        applies_to=_string_tuple(gate_id, raw.get("appliesTo", ()), "appliesTo"),
        gate_sets=_string_tuple(gate_id, raw.get("gateSets", ()), "gateSets"),
    )


def na_out_of_scope_violation(
    gate_id: str,
    result: GateResult,
    *,
    applies_to: str | None = None,
    gate_set: str | None = None,
) -> str | None:
    """Return the blocking reason for an N/A result, or None when scoped."""
    if result.state != "N/A":
        return None
    if not result.out_of_scope:
        return f"{gate_id}: N/A without explicit outOfScope=true"
    if not result.reason:
        return f"{gate_id}: out-of-scope N/A is missing reason"
    if applies_to is not None and applies_to not in result.applies_to:
        return f"{gate_id}: out-of-scope N/A is not scoped to appliesTo={applies_to}"
    if gate_set is not None and gate_set not in result.gate_sets:
        return f"{gate_id}: out-of-scope N/A is not scoped to gateSet={gate_set}"
    return None


def _string_tuple(gate_id: str, value: Any, field_name: str) -> tuple[str, ...]:
    if value in (None, ""):
        return ()
    if isinstance(value, str):
        return (value,)
    if not isinstance(value, (list, tuple)):
        raise ValueError(f"{gate_id}: {field_name} must be a string list when present")
    normalized: list[str] = []
    for index, item in enumerate(value):
        if not isinstance(item, str) or not item:
            raise ValueError(f"{gate_id}: {field_name}[{index}] must be a non-empty string")
        normalized.append(item)
    return tuple(normalized)
