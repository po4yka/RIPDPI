---
task_id: RLY-1790329719797199
change: saved-relay-profile-management
commit_sha: null
local: required
local_evidence: "Targeted app/core:data unit tests passed; app and service lint plus staticAnalysis passed with ripdpi.skipNativeBuild=true."
remote_ci: required
remote_ci_evidence: Pending push and hosted CI on the integrated main branch.
device: required
device_evidence: Pending connected-device profile selection and editor interaction proof.
artifact: not_applicable
artifact_evidence: No distributable artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RLY-1790329719797199-001 | RLY-1790329946121394 | ConfigScreenTest reaches fourth profile and limits Edit to supported kinds | partial |
| REQ-RLY-1790329719797199-002 | RLY-1790329946121394 | Repository selection retains imported SSH credentials; UI uses saved active profile, not draft | partial |
| REQ-RLY-1790329719797199-003 | RLY-1790329946121394 | Repository hydration loads inactive profile and credentials | partial |
| REQ-RLY-1790329719797199-004 | RLY-1790329940999261 | Collision, immutable ID/kind, stale credentials, and coordinator CAS tests pass | partial |

## Local gates (2026-09-25)

- `:core:data:testDebugUnitTest :app:testGithubFullDebugUnitTest` with three targeted test filters and `-Pripdpi.skipNativeBuild=true`: passed in 19 seconds after the final edit (296 tasks, 10 executed).
- `:app:lintGithubFullDebug :core:service:lintDebug staticAnalysis` with `-Pripdpi.skipNativeBuild=true`: passed in 4 minutes 16 seconds (728 tasks, 66 executed).
- `./taskctl openspec cli validate saved-relay-profile-management --strict`, `xmllint --noout app/src/main/res/values*/strings.xml`, and `git diff --check`: passed.

These are local native-less checks. Hosted CI and device interaction remain open. The generated task board is updated after integration with the parallel PCAP change.
