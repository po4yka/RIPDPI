---
task_id: TRN-1792853642811403
change: xudp-source-authority
commit_sha: 04d842534bdb550a9f5477c3e528b68ee3f6ab3a
local: required
local_evidence: Independent source review confirms that the pinned peer omits per-packet source metadata and its SOCKS path uses the initial request. Corrected real-stream and cancellation regressions remain required.
remote_ci: required
remote_ci_evidence: Hosted checks on corrected source remain required.
device: required
device_evidence: Physical Hysteria at the recorded source passed download, upload, sleep/wake stream, automatic TCP reserve selection and reserve download. The next DNS query reached the peer but timed out after the client reported MissingSourceTarget. Cleanup passed. Explicit UDP recovery was not reached.
artifact: required
artifact_evidence: A genuine Android artifact with the corrected runtime remains required.
deployment: not_applicable
deployment_evidence: No provider, host, credential or deployment action is included.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-XUDP-INITIAL-SOURCE | TRN-1792853830474026 | Physical log and independent pinned-peer source review confirm the omitted-source defect | Correction required |
| REQ-XUDP-SOURCE-ISOLATION | TRN-1792853830914890 | Initial A, later uplink B, explicit response C and omitted response A regression | Required |
| REQ-XUDP-SOURCE-LIFECYCLE | TRN-1792853830914890 | Queue cancellation, immediate reply and fresh-carrier isolation regressions | Required |
| REQ-XUDP-SOURCE-LIFECYCLE | TRN-1792853831345454 | Genuine APK, physical reserve DNS and explicit recovery with hosted native gates | Required |

The baseline peer retained two DNS receipts and four completed transfers.
The client log reports an omitted source target immediately after its reserve
XUDP handshake. No fixture rule, assertion or timeout was changed.
