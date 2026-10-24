---
task_id: PRX-1792849175606125
change: first-response-duplex-continuation
commit_sha: 47f9c8f237d68fdc3db9348a6f65fe4be3470603
local: required
local_evidence: The real-proxy baseline completes a split-header 1 MiB POST with autolearn disabled, but fails with autolearn enabled after the origin receives zero body bytes. Direct native Hysteria completes download and upload, including a 100 ms header-body pause. These are failure-isolation results; corrected implementation tests remain required.
remote_ci: required
remote_ci_evidence: Corrected native source and Linux copy-path acceptance remain pending. Existing Linux workspace commands do not enable the optional io-uring feature; this change requires an explicit feature-enabled gate with proof that the io_uring path ran without capability fallback.
device: required
device_evidence: The baseline physical USB Hysteria scenario passed DNS and 1 MiB download but failed upload. Cleanup passed. Automatic recovery was not reached.
artifact: required
artifact_evidence: A fresh genuine Android artifact with the corrected source remains required.
deployment: not_applicable
deployment_evidence: No provider, host, credential or deployment action is included.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PROXY-DUPLEX | PRX-1792849486028429 | Real proxy baseline reproduces the upload deadlock; corrected split-header and early-response cases remain pending | Confirmed defect |
| REQ-PROXY-NO-CONTINUATION-REPLAY | PRX-1792849486474789 | Alternate-route zero-replay regression remains pending | Required |
| REQ-PROXY-RESPONSE-OBSERVATION | PRX-1792849486028429 | Shared observer and exact-byte regressions remain pending on both copy paths | Required |
| REQ-PROXY-DUPLEX-CLEANUP | PRX-1792849486474789 | Half-close, cancellation and shutdown regressions remain pending | Required |

Physical acceptance and rebuilt artifact evidence map to
`PRX-1792849486914254`; no execution step is complete yet.
