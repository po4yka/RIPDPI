---
task_id: UIX-1786264762917972
change: uix-1786264762917972-fix-launchedeffect-unit-session-keyed-refresh
commit_sha: 841860c55739b839ae1056707a2bd113034eda25
local: passed
local_evidence: Revalidated current main on 2026-08-31. The three targeted Compose/Robolectric regression classes and the complete :app:testGithubFullDebugUnitTest suite passed with ripdpi.skipNativeBuild=true; staticAnalysis passed; architecture health reported Current 23, Baseline 23, New 0, Worsened 0, Stale 0; 18 native architecture contract tests and taskctl validation passed; no golden or screenshot files changed after the final implementation commit. Read-only code-mapper and implementation-review subagents found no blocking defect and confirmed commits 9ffbc86d0, f87455fb8, and 841860c55 are already ancestors of current main.
remote_ci: not_applicable
remote_ci_evidence: The owner explicitly requested local verification and push without launching, waiting for, or monitoring GitHub CI/CD.
device: not_applicable
device_evidence: The acceptance contract explicitly permits Compose/Robolectric or unit coverage; this change only corrects effect-key and retained-state semantics and does not change physical camera or device integration behavior.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this portfolio area.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIX-1786264762917972-001 | UIX-1786264762918128 | Confirmed the three audited effects in `ReplayHistoryRoute`, `ReplayFailureRoute`, and `QrScannerRoute`; implementation commits `9ffbc86d0`, `f87455fb8`, and `841860c55`. | passed |
| REQ-UIX-1786264762917972-002 | UIX-1786264762918217 | Replay history keys on the navigation-entry session id, replay failure keys on domain and strategy id, scanner synchronization keys on permission state, and the permission request keys on scanner session id. | passed |
| REQ-UIX-1786264762917972-003 | UIX-1786264762918575 | `ReplayHistoryRouteRefreshTest`, `ReplayFailureRouteAutoStartTest`, and `QrScannerRoutePermissionRecoveryTest` passed under `:app:testGithubFullDebugUnitTest`, including same-session permission changes without a stale second prompt. | passed |
| REQ-UIX-1786264762917972-004 | UIX-1786264762918016 | `:app:testGithubFullDebugUnitTest` and `staticAnalysis` passed with native build skipped after verifying the exact-main Xray artifact; `git diff origin/main --name-only` found no golden or screenshot changes. | passed |
