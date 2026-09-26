---
task_id: AND-1790430239664191
change: fix-app-lock-clock-skew
commit_sha: null
local: blocked
local_evidence: Focused clock tests, full :app unit tests, :app:detekt, and :app:ktlintMainSourceSetCheck passed after the expiry fix. Workspace staticAnalysis failed on six unrelated :core:diagnostics ktlint findings after rebase.
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: No reboot or clock-change device run has been observed.
artifact: not_applicable
artifact_evidence: No packaged artifact is part of this local source change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-APP-LOCK-PIN-MONOTONIC | AND-1790430363742695 | RED AssertionError for clock jump and observed expiry, then `PinLockoutManagerTest` passed | Local pass; device pending |
| REQ-APP-LOCK-RELOCK-MONOTONIC | AND-1790430370488377 | RED AssertionError, then `AppLockLifecycleObserverTest` passed | Local pass; device pending |
