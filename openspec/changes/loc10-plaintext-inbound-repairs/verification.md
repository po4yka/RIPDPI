---
task_id: PRX-1794127994095690
change: loc10-plaintext-inbound-repairs
commit_sha: null
local: required
local_evidence: Regression tests, affected crate tests, scoped clippy, formatting, independent review, task validation and ci-gates must pass.
remote_ci: not_applicable
remote_ci_evidence: Local source and native gates cover this change; no hosted workflow run is owned by this task.
device: not_applicable
device_evidence: No Android API, JNI, service lifecycle or UI change; all changed behavior is exercised by host loopback and configuration tests.
artifact: required
artifact_evidence: Build and run the shipped CLI with the explicit plaintext input option.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PLAIN-POLICY | PRX-1794128118834975 | Raw loopback HTTP and TLS fragmentation, Block and server-first tests | Pending |
| REQ-PLAIN-CANDIDATE | PRX-1794128119346710 | Final-candidate self-loop, mapped address, fallback and allowed origin tests | Pending |
| REQ-PLAIN-ACTIVATION | PRX-1794128119943997 | Shared parser, Android command-line payload and real CLI binary tests | Pending |
| Combined validation | PRX-1794128120543062 | Affected native tests, review, ci-gates, architecture health and locked metadata | Pending |
