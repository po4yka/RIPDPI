---
task_id: CIC-1792987990860435
change: cic-1792987990860435-service-client-ci-prerequisites
commit_sha: null
local: required
local_evidence: Locked non-test WebTunnel check passed at 7e5559d6. Local Simple Home execution stopped before tests because the private embedded relay bundle is absent; hosted Android unit tests passed at the same commit. Guide, task and architecture checks are required.
remote_ci: required
remote_ci_evidence: Required hosted client checks must pass before merge.
device: not_applicable
device_evidence: No device or Android runtime behavior changes; this change fixes dependency feature selection and tests.
artifact: required
artifact_evidence: Hosted pluggable transport packaging must produce its normal assets.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SCCP-BUILD | CIC-1792988158457520 | Locked non-test WebTunnel check and hosted packaging at 7e5559d6 | passed |
| REQ-SCCP-UI | CIC-1792988159191603 | Hosted Android unit tests at 7e5559d6; local tests blocked by absent private bundle | passed remotely |
| REQ-SCCP-GUIDE | CIC-1792994175016752 | Complete guide generator unit suite, 13 tests passed | passed |
| REQ-SCCP-SIZE | CIC-1793000057523106 | Five CI ZIP digests and eight stripped library sizes verified; old policy fails and new policy passes | passed locally; hosted CI required |
