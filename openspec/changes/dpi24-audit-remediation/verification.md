---
task_id: ENG-1794637907795927
change: dpi24-audit-remediation
commit_sha: null
local: required
local_evidence: Pending regression tests, affected module tests, source gates, and independent review.
remote_ci: required
remote_ci_evidence: Pending push and hosted workflow results.
device: not_applicable
device_evidence: This local correction uses host socket regressions; no physical-device or external-network acceptance is claimed.
artifact: required
artifact_evidence: Pending generated contract checks and locale validation.
deployment: not_applicable
deployment_evidence: No deployment is owned by this source correction.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI24-COUNT | ENG-1794638210639267 | Kotlin/Rust count and repeated-chain regression tests; malformed internal planner counts | Pending |
| REQ-DPI24-CORRELATION | ENG-1794638225627262 | Actual UDP pump with independent RFC v1/v2 replies; decoy VN, invalid tag, generic UDP and follow-up packets | Pending |
| REQ-DPI24-COMPAT | ENG-1794638225627262 | Independent compatible v1/v2 response vectors, advertised offer checks, and neutral expiry for incomplete offers | Pending |
| REQ-DPI24-GREASE | ENG-1794638225627262 | Independently protected Fixed Bit zero response, grease_quic_bit offer checks, and neutral expiry for incomplete offers | Pending |
| REQ-DPI24-PROBE | ENG-1794638233396968 | Loopback unrelated VN before a valid reply; total deadline with repeated invalid replies; generic UDP compatibility | Pending |
| REQ-DPI24-NAME | ENG-1794638240063808 | Stable ID and old-alias label tests, locale lint, corrected raw-mutation documentation | Pending |
| REQ-DPI24-COUNT | ENG-1794638243019809 | just ci-gates, architecture health, locked Cargo metadata, staticAnalysis, independent review | Pending |
| REQ-DPI24-CORRELATION | ENG-1794638243019809 | Combined regression checks and independent review | Pending |
| REQ-DPI24-PROBE | ENG-1794638243019809 | Combined transport checks and independent review | Pending |
| REQ-DPI24-NAME | ENG-1794638243019809 | Combined locale and contract checks | Pending |
