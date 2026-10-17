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
| REQ-UTC-RTL | UIX-1792244219751542 | Actual section offset, LTR/RTL and reverse regression | pending |
| REQ-UTC-FEEDBACK | UIX-1792244220667211 | Frozen-clock transition, same-status, reduced/off and action tests | pending |
| REQ-UTC-PREVIEW | UIX-1792244221670352 | Safe current/saved display, unknown/custom/privacy tests | pending |
| REQ-UTC-SNAPSHOT | UIX-1792244221670352 | Runtime and whole-settings identity change invalidation tests | pending |
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
