---
task_id: UIX-1791532861060606
change: connection-control-redesign
commit_sha: null
local: required
local_evidence: Implementation tests have not run. Only planning validation is available.
remote_ci: required
remote_ci_evidence: No push or hosted CI run is authorized or observed.
device: required
device_evidence: No device or emulator acceptance was performed.
artifact: required
artifact_evidence: No new Compose render or installable artifact exists.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HCC-ACTION | UIX-1791533045357434 | Component tests and state renders are required after implementation. | required |
| REQ-HCC-ROUTE | UIX-1791533045357434 | Separate route semantics and touch targets need behavior tests. | required |
| REQ-HCC-EVIDENCE | UIX-1791533044507020 | Resolver regression test must retain unknown progress after five seconds without events. | required |
| REQ-HCC-INPUT | UIX-1791533046236328 | Touch, keyboard, confirmation, disabled semantics, and lockdown tests are required. | required |
| REQ-HCC-ADAPT | UIX-1791533047120589 | Inspect light/dark, 320dp, fontScale 2.0, and RTL renders; run locale lint. | required |
| REQ-HCC-RESEARCH | UIX-1791533043597405 | Mobbin tool inventory is empty; plugin search returned no Mobbin entry. Public discover page did not provide the requested screens. | blocked |

## Planned gates

- `build-gate -- ./gradlew :app:testGithubFullDebugUnitTest --tests 'com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator*Test' --tests 'com.poyka.ripdpi.activities.MainViewModelTest' --max-workers=4 -Pripdpi.nativeCpuBudget=4`
- `build-gate -- ./gradlew :app:testGithubFullDebugUnitTest --tests 'com.poyka.ripdpi.ui.theme.*' --max-workers=4 -Pripdpi.nativeCpuBudget=4`
- `build-gate -- ./gradlew :app:lintGithubFullDebug :core:service:lintDebug staticAnalysis --max-workers=4 -Pripdpi.nativeCpuBudget=4`
- `build-gate -- compose-preview render --module app --variant githubFullDebug --filter Actuator --timeout 300 --progress`
- Independent pr-reviewer inspection before the implementation commit.
- Separately authorized narrow golden verification/update if the final rendered home layout changes its baseline.

## Research access

The requested Mobbin MCP research has not run. The server is not configured in the current task's MCP tool inventory. No Mobbin benchmark result is claimed.

Official connection instructions: https://docs.mobbin.com/mcp/clients/codex-app

Implementation remains blocked until the integration is connected or the user selects another reference source.
