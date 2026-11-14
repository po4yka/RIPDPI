---
task_id: ENG-1794638006879351
change: dpi25-quic-initial-contracts
commit_sha: null
local: required
local_evidence: Owning Rust and Kotlin tests, static analysis, ci-gates and independent review are pending.
remote_ci: not_applicable
remote_ci_evidence: Local repair acceptance owns source gates; hosted CI is reported separately after the authorized push.
device: not_applicable
device_evidence: No Android service or JNI contract changes. Host UDP sockets and an independent QUIC receiver cover these source repairs; device acceptance is not claimed.
artifact: required
artifact_evidence: Independent QUIC decode and receiver results are pending.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI25-SEED | ENG-1794638217039651 | Owning seed tests and independent receiver, v1/v2 and both profiles | pending |
| REQ-DPI25-STATE | ENG-1794638218458787 | Independent CID/CRYPTO/nonce checks | pending |
| REQ-DPI25-TTL | ENG-1794638219805332 | Config tests and actual IPv4/IPv6 socket TTL tests | pending |
| REQ-DPI25-COUNT | ENG-1794638221270523 | Kotlin/native rejection boundaries and internal planner checks | pending |
| REQ-DPI25-SIZE | ENG-1794638223409621 | Encoded lengths and independent decrypt of boundary packets | pending |
| REQ-DPI25-FALLBACK | ENG-1794638231446674 | Owning fallback tests, ci-gates and independent review | pending |
