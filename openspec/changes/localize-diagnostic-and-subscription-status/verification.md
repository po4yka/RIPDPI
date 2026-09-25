---
task_id: UIX-1790340279870792
change: localize-diagnostic-and-subscription-status
commit_sha: null
local: passed
local_evidence: "DiagnosticToolStateLabelTest passed; ten-locale key parity and seven-string translation audit passed; :app:lintGithubFullDebug passed with ripdpi.skipNativeBuild=true."
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: "No connected Android device is available for runtime locale inspection."
artifact: not_applicable
artifact_evidence: No published artifact is owned by this UI fix.
deployment: not_applicable
deployment_evidence: No deployment is owned by this UI fix.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LOCALIZED-DIAGNOSTIC-STATUS | UIX-1790340453054597 | `DiagnosticToolStateLabelTest` passed; all four state keys present in ten locale sets | Local passed; device pending |
| REQ-LOCALIZED-SUBSCRIPTION-FAILOVER | UIX-1790340458391814 | Ten-locale key parity, seven-string translation audit, and `:app:lintGithubFullDebug` passed | Local passed; device pending |
