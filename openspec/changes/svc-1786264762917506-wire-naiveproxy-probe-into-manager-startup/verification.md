---
task_id: SVC-1786264762917506
change: svc-1786264762917506-wire-naiveproxy-probe-into-manager-startup
commit_sha: 304d8a3b8217a931812f7cfab04292dc6d36b5c2
local: passed
local_evidence: Full core service unit suite, staticAnalysis, architecture health, Cargo metadata, task contracts, and pre-commit hooks passed locally.
remote_ci: required
remote_ci_evidence: Not observed; the user explicitly requested push without waiting for GitHub CI/CD.
device: required
device_evidence: Not observed; helper process lifecycle is covered by Robolectric and host-process tests, but no physical-device run was performed.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this portfolio area.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SVC-1786264762917506-001 | SVC-1786264762919787 | Pre-existing task evidence records the bundled helper's single schema-1 `RIPDPI-PROBE` line and native format/capability tests; helper emission code was unchanged by this implementation. | passed |
| REQ-SVC-1786264762917506-002 | SVC-1786264762919579 | `NaiveProxyProbeParserTest` and the manager round-trip test validate marker, JSON fields, and schema range. | passed |
| REQ-SVC-1786264762917506-003 | SVC-1786264762919010 | `NaiveProxyManager` extracts once, probes before launch, rejects unsupported schema with `RelayConfigRejected`, and maps it to wire class `relay_compatibility`. | passed |
| REQ-SVC-1786264762917506-004 | SVC-1786264762919278 | Successful preflight passes the exact probed file into the existing subprocess `--version`, `RIPDPI-READY`, and `RIPDPI-ERROR` pipeline. | passed |
| REQ-SVC-1786264762917506-005 | SVC-1786264762919141 | `NaiveProxyManagerPreflightTest` covers round-trip, schema refusal, no schema-0 fallback, exact binary identity, every-start probing, timeout/cancellation cleanup, forced termination, and telemetry. | passed |
| REQ-SVC-1786264762917506-006 | SVC-1786264762919098 | Runtime, helper README, specification, and schema-version documentation describe mandatory schema-1 preflight and fail-closed policy. | passed |
