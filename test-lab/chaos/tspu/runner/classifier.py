"""Verdict classifier shared between dry-run and (future) live modes.

Given a pattern's `classify(trace, config)` raw output, map it onto one
of the four stable verdicts in `schema.py`.

The mapping is intentionally simple:

- `matched=False` -> bypassed.
- `matched=True` -> blocked (live mode injects the adversary response;
  dry-run records the matched packet index).
- A trace can explicitly carry `"force_degraded": true` to model the
  partial-impact case that live mode would observe (e.g. RST injected
  after handshake completion). v1 fixtures may use this flag to
  exercise the `degraded` verdict.
- Pattern result missing required fields -> inconclusive.
"""

from __future__ import annotations

from typing import Any

from . import schema


def verdict_for(pattern_result: dict[str, Any], trace: dict[str, Any]) -> str:
    if "matched" not in pattern_result:
        return schema.VERDICT_INCONCLUSIVE
    if pattern_result["matched"] is True:
        if trace.get("force_degraded") is True:
            return schema.VERDICT_DEGRADED
        return schema.VERDICT_BLOCKED
    return schema.VERDICT_BYPASSED
