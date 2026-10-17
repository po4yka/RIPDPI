---
task_id: UIX-1792244042561922
change: ui-transition-clarity
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this app presentation change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UTC-RTL | UIX-1792244219751542 | 8 actual-offset and retained-state tests, including static and reduced motion | passed |
| REQ-UTC-FEEDBACK | UIX-1792244220667211 | 7 frozen-clock status, semantics, same-status, reduced/off and action tests | passed |
| REQ-UTC-PREVIEW | UIX-1792244221670352 | 5 safe projection tests and 17 review panel tests | passed |
| REQ-UTC-SNAPSHOT | UIX-1792244221670352 | 69 MainViewModel tests, hidden settings identity, telemetry stability, stale runtime and one-shot review | passed |
| REQ-UTC-VALIDATION | UIX-1792244222504548 | Combined gates, independent review, real APK and leased emulator | pending |

## Planned acceptance

Observe RED/GREEN focused tests, theme tests, Full/service locale lint and
staticAnalysis. Validate translation export, architecture health, full locked
Cargo metadata and task contracts. Under an exclusive emulator pool lease,
inspect RTL/LTR forward/reverse transitions, status success/fault/cancel,
normal/reduced/off motion, reconnect preview/confirm/cancel/stale state,
light/dark and font2. Record app and test source SHA and APK hashes. Preserve
existing golden captures; report any intentional diff before blessing.

UIX-1792244223589481 owns authorized ff-only main integration, ordinary push,
and exact-published-SHA hosted CI inspection after rebase and combined gates.
The saved summary is partial: catalog-dependent route selection is resolved by
the service when reconnecting. A settings token covers all AppSettings values;
it does not claim to freeze independent provider catalogs.

## Local checks before the source commit

The combined unit cohort passed: 121 tests, 0 failures, 0 errors, 0 skips.
staticAnalysis, Full and Simple app lint, service lint, translation export,
architecture health, full locked Cargo metadata, task contracts, and strict
OpenSpec validation passed. Read-only independent review and structured review
reported no actionable blockers. No golden fixture was changed.

Runtime-panel golden verification, the real APK build, emulator acceptance,
and exact published revision CI remain required. The normal unit task excludes
screenshot classes; the 121-test count does not include golden checks.
