---
task_id: RLY-1794335337545384
change: fix-relay-chain-audit-regressions
commit_sha: null
local: required
local_evidence: Pending targeted regressions, combined gates and independent review.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not required for these local regressions; no hosted workflow run is claimed.
device: not_applicable
device_evidence: Pure mapper, admission, telemetry and owned-task regressions use host tests; physical network acceptance is outside this change.
artifact: not_applicable
artifact_evidence: No release binary or golden baseline is changed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-CHAIN-PERSIST | RLY-1794335430947778 | Targeted app profile round-trip tests | Pending |
| REQ-CHAIN-ADMISSION | RLY-1794335431416389 | Targeted app and service admission tests | Pending |
| REQ-CHAIN-ROLES | RLY-1794335431945519 | Relay-core partial startup, retry and concurrency tests | Pending |
| REQ-CHAIN-TRUST | RLY-1794335432498689 | App full-list trust warning regressions | Pending |
| REQ-CHAIN-SHUTDOWN | RLY-1794335433023467 | AnyTLS and chain carrier cleanup, cancellation and retry tests | Pending |

## Combined gates

RLY-1794335433511524 owns independent review, async cancellation review, targeted Kotlin and Rust tests, staticAnalysis, just ci-gates, architecture health and cargo metadata --locked. Use machine build-gate and documented worker limits. Update this record with observed results and the exact implementation SHA.
