---
task_id: DGN-1788599171554142
change: pinned-peer-before-dns
commit_sha: null
local: passed
local_evidence: Six complete crates passed 535 tests with zero failures and five pre-existing ignored; transport has 59 passing tests. Transport and runner all-target clippy with warnings denied, formatting and file LoC passed. RED cases reproduced eager DNS and deadline exhaustion before correction.
remote_ci: required
remote_ci_evidence: Required PR CI on the corrected application revision must pass before integration.
device: not_applicable
device_evidence: This host transport ordering fix does not change Android lifecycle or JNI; operator and radio tests remain outside this bounded regression.
artifact: not_applicable
artifact_evidence: No distributable release is requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AUDIT-PINNED-FIRST | DGN-1788599295116548 | Lazy candidate, TCP/UDP/route/SOCKS loopback, fallback and deadline regressions | passed |
| REQ-AUDIT-PINNED-FIRST | DGN-1788599297743936 | Six caller crates passed 535 tests; clippy and independent source review; exact-SHA CI remains required | passed |
