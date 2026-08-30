---
task_id: UIX-1786264762917972
change: uix-1786264762917972-fix-launchedeffect-unit-session-keyed-refresh
commit_sha: f87455fb8de51f5f033bb1ee5c9a522a20239377
local: passed
local_evidence: Targeted session-key tests, the complete GithubFull app unit suite, staticAnalysis, architecture health, and native Xray artifact verification passed locally; no golden files changed.
remote_ci: required
remote_ci_evidence: Not observed; the user explicitly requested push without waiting for GitHub CI.
device: required
device_evidence: Not observed; permission and lifecycle behavior is covered by Compose and Robolectric tests only.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this portfolio area.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIX-1786264762917972-001 | UIX-1786264762918128 | Confirmed the three audited effects in `ReplayHistoryRoute`, `ReplayFailureRoute`, and `QrScannerRoute`; implementation commits `9ffbc86d0` and `f87455fb8`. | passed |
| REQ-UIX-1786264762917972-002 | UIX-1786264762918217 | Replay history keys on the navigation-entry session id, replay failure keys on domain and strategy id, scanner synchronization keys on permission state, and the permission request keys on scanner session id. | passed |
| REQ-UIX-1786264762917972-003 | UIX-1786264762918575 | `ReplayHistoryRouteRefreshTest`, `ReplayFailureRouteAutoStartTest`, and `QrScannerRoutePermissionRecoveryTest` passed under `:app:testGithubFullDebugUnitTest`. | passed |
| REQ-UIX-1786264762917972-004 | UIX-1786264762918016 | `:app:testGithubFullDebugUnitTest` and `staticAnalysis` passed with native build skipped after verifying the exact-main Xray artifact; `git diff origin/main --name-only` found no golden or screenshot changes. | passed |
