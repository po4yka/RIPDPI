---
task_id: DAT-1788656601400373
change: mirror-real-vps-awg-nat-evidence-schema4
commit_sha: 13c887971fb80b9a74b398699a0916b551f30ee7
local: passed
local_evidence: Producer c8ad0861711eb5fb63c6fad46c28c179678d51a5 resolved exactly; target is byte-identical by cmp; SHA-256 9f6412bf84d6cc2f24e5b4d6e4ef190da5ec2b75b802daa3ada9b11779efcb30; JSON parsing, Draft 2020-12 declaration, v4 version check, task/OpenSpec validation, architecture health, diff check, and configured hooks passed.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: This test-resource-only mirror does not change or require device or emulator behavior.
artifact: not_applicable
artifact_evidence: This change does not produce an APK or other release artifact.
deployment: not_applicable
deployment_evidence: No deployment is owned by this client contract mirror.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-MIRROR-BYTE-IDENTITY | DAT-1788656710269110 | Producer `c8ad0861711eb5fb63c6fad46c28c179678d51a5`; `cmp` passed; SHA-256 `9f6412bf84d6cc2f24e5b4d6e4ef190da5ec2b75b802daa3ada9b11779efcb30`; JSON parsing and v4 version check passed. | passed |
| REQ-MIRROR-SCOPE-ISOLATION | DAT-1788656714841372 | Pending final source-only diff review and exact-head hosted checks after an authorized publish. | required |
