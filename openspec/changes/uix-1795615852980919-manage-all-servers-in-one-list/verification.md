---
task_id: UIX-1795615852980919
change: uix-1795615852980919-manage-all-servers-in-one-list
commit_sha: null
local: required
local_evidence: "Branch claude/unified-server-list-2d3269: :app:testGithubDebugUnitTest connection/profiles/awg/components.profiles 507 tests 0 failures; :core:data:testDebugUnitTest RelayCatalogMutationTest + ProfileMutationRecoveryCoordinatorTest 10 tests 0 failures; :app:verifyRoborazziGithubDebug green; :app:lintGithubDebug passed; just ci-gates passed"
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this change.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIX-1795615852980919-001 | UIX-1795615853511465 | `ConnectionScreenTest` "add server menu offers every relay kind that has an editor" (11 audit kinds present, Snell absent, Trojan opens a Mode Editor session) and "vpn add profile menu opens every dedicated editor" | pass (local) |
| REQ-UIX-1795615852980919-002 | UIX-1795615853931821 | `ConnectionScreenTest` "saved AmneziaWG servers share the servers list and open their editor" | pass (local) |
| REQ-UIX-1795615852980919-003 | UIX-1795615854385350 | `ConnectionScreenTest` "long press on a server opens rename duplicate share and delete"; `RelayCatalogMutationTest` (rename moves credentials and selection; delete and rename refuse used profiles). AmneziaWG rows have no Share action yet | partial: AWG share missing |
| REQ-UIX-1795615852980919-004 | UIX-1795615854842462 | Server and preset terms changed in all 10 locales for the Connection sheet, Add menu, editor titles, and share action; `:app:lintGithubDebug` passed. About 170 other strings still say "profile", some for servers | partial |
| REQ-UIX-1795615852980919-005 | UIX-1795615855310994 | `ConnectionViewModelApplyChangesTest` "recommended preset ignores fields kept by a deactivated relay" (active relay keeps Recommended selected); `ConnectionSummaryTest` | pass (local) |
| REQ-UIX-1795615852980919-006 | UIX-1795615855772763 | `ConnectionPathSelectionTest` "relay without a saved profile stays on relay after choosing the recommended preset"; `ConnectionPathMemory` keeps the path across `navigateHome()`. Not exercised on a device | pass (local, no device) |
