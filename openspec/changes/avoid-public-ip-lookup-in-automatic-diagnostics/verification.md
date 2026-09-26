---
task_id: DGN-1790434142197991
change: avoid-public-ip-lookup-in-automatic-diagnostics
commit_sha: null
local: required
local_evidence: Focused recording-provider scenarios pass after observed red failures for automatic capture and DNS-corrected re-probe. All 1436 diagnostics unit tests pass with zero failures or skips. Module detekt passes using ripdpi.skipNativeBuild=true.
remote_ci: required
remote_ci_evidence: Pending branch integration and CI run.
device: not_applicable
device_evidence: The provider flag is covered by JVM tests. No Android device behavior changes.
artifact: required
artifact_evidence: Recording-provider tests assert automatic and manual pre/post arguments and the complete automatic DNS-corrected re-probe sequence of three false requests.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AUTOMATIC-PUBLIC-IP | DGN-1790434216591296 | Recording-provider tests and full module unit gate | Pass |

## Residual

Automatic diagnostics can still use other configured probe targets. This change only disables the additional external public-IP resolver calls from snapshot capture. A complete public-IP opt-in remains a product decision.
Native binaries were skipped for the JVM gate, so this check does not verify APK assembly or device behavior.
