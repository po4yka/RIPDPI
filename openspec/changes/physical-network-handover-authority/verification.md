---
task_id: SVC-1792856195708453
change: physical-network-handover-authority
commit_sha: b75cbf1a31f8d85cb788f6278e02aee9e9b5d6ef
local: required
local_evidence: Independent source review confirms application-default fingerprint and MTU authority. Source regressions and correction remain required.
remote_ci: required
remote_ci_evidence: Hosted gates on the corrected source remain required.
device: required
device_evidence: Two earlier Hysteria runs at 38e64afcf9f097a60c8dd9146ed431c108fe35ef failed after transport-switch restarts during sleep and wake. The logs do not establish physical-network versus VPN-overlay attribution. Other three USB scenarios and cleanup passed.
artifact: required
artifact_evidence: Genuine artifacts on the corrected source remain required.
deployment: not_applicable
deployment_evidence: No provider, credential, host or deployment action is included.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PHYSICAL-AUTHORITY | SVC-1792856270513687 | Selected physical source and one observer with matching fingerprint and MTU | Required |
| REQ-PHYSICAL-CHANGES | SVC-1792856270934423 | Overlay, true transport, DNS, private-DNS, route, MTU and unrelated callback regressions | Required |
| REQ-PHYSICAL-UNAVAILABLE | SVC-1792856270934423 | Incomplete, blocked, lost, restored and legacy ambiguity tests | Required |
| REQ-PHYSICAL-PRIVACY | SVC-1792856270934423 | Unchanged scope hashes and serialization with exact binder-issued DNS authority | Required |
| REQ-PHYSICAL-ACCEPTANCE | SVC-1792856271355520 | Complete affected suites, reviewed source, genuine artifact, four USB scenarios and hosted gates | Required |
