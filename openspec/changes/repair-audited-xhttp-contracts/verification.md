---
task_id: TRN-1794371418861139
change: repair-audited-xhttp-contracts
commit_sha: null
local: required
local_evidence: Pending regression tests, independent review, clippy and source gates.
remote_ci: not_applicable
remote_ci_evidence: Acceptance uses the repository local native and offline CI source gates; hosted CI is reported separately after push.
device: not_applicable
device_evidence: Native single-crate repair; Android activation and external transport acceptance are outside this change.
artifact: not_applicable
artifact_evidence: No release artifact, AAR or APK is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TRN04-OPEN | TRN-1794371483895973 | Pending stream-up regression and peer exchange | Pending |
| REQ-TRN04-DROP | TRN-1794371484875463 | Pending HTTP teardown, sibling and half-close regression | Pending |
| REQ-TRN04-URL | TRN-1794371485689522 | Pending fixed request-target vectors and independent peer | Pending |
| REQ-TRN04-POOL | TRN-1794371486485508 | Pending second-carrier and cancellation regression | Pending |
| REQ-TRN04-DOCS | TRN-1794371487347249 | Pending scope and pin document review plus ci-gates | Pending |
