"""Stable identifiers used in the matrix-runner output contract.

The verdict report is consumed by triage tooling; field names and verdict
values are part of the public contract.
"""

from __future__ import annotations

VERDICT_BYPASSED = "bypassed"
VERDICT_BLOCKED = "blocked"
VERDICT_DEGRADED = "degraded"
VERDICT_INCONCLUSIVE = "inconclusive"

ALL_VERDICTS = (VERDICT_BYPASSED, VERDICT_BLOCKED, VERDICT_DEGRADED, VERDICT_INCONCLUSIVE)

REPORT_SCHEMA_VERSION = 1
