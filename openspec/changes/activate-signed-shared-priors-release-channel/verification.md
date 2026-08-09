---
task_id: SVC-1786272083078316
change: activate-signed-shared-priors-release-channel
commit_sha: null
local: required
local_evidence: Pending implementation and signed-fixture gates.
remote_ci: required
remote_ci_evidence: Pending exact-SHA hosted CI.
device: required
device_evidence: Pending Android refresh/apply observation on the exact artifact.
artifact: required
artifact_evidence: Pending exact APK public-configuration and secret inspection.
deployment: required
deployment_evidence: Pending owner-signed manifest/payload publication and successful consumption receipt.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SHARED-PRIORS-PRODUCTION-IDENTITY | CIC-1786272226226226 | Exact APK public-key/URL inspection and owner fingerprint receipt | Pending |
| REQ-SHARED-PRIORS-FAIL-CLOSED | SVC-1786272226221574 | Rust and service missing/wrong/tampered/oversized fixture tests | Pending |
| REQ-SHARED-PRIORS-APPLY | SVC-1786272226229267 | Exact signed release download, native apply result, and active-store observation | Pending |
| REQ-SHARED-PRIORS-PRIVACY | SVC-1786272226224464 | Network request contract tests and exact-artifact inspection | Pending |
| REQ-SHARED-PRIORS-EVIDENCE | CIC-1786272226226226 | Exact-SHA local, CI, artifact, device, and deployment receipts | Pending |
