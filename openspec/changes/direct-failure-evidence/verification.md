---
task_id: DGN-1791484568964479
change: direct-failure-evidence
commit_sha: 2f4fe75409f0e2de98f14bec7d903e2d515ff42a
local: passed
local_evidence: "Combined publication tree passed 1508 core diagnostics tests, 11 targeted app tests, staticAnalysis, app and service lint, architecture health, and locked Cargo metadata. Native classification 70 and runner 78 tests passed; two existing runner tests were ignored. Both changed monitor-engine regression tests passed."
remote_ci: required
remote_ci_evidence: "CI started for the exact commit; acceptance is pending. https://github.com/po4yka/RIPDPI/actions/runs/37831951483"
device: not_applicable
device_evidence: Pure diagnosis projection and existing observation contracts.
artifact: not_applicable
artifact_evidence: No packaged artifacts change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIRECT-UNKNOWN-CAUSE | DGN-1791484597972931 | DirectModePolicySupportTest | Passed |

## Scope and remaining evidence

Local tests validate measurement boundaries. They do not prove provider restrictions on a live connection. No device or packaged native artifact validation was run. A broader monitor-engine run has three failures outside this change; the final-report golden and HTTP blockpage classification failure also reproduce on the original main checkout. Required remote CI is still pending.
