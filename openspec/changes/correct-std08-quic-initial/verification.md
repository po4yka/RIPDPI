---
task_id: DGN-1794211297803022
change: correct-std08-quic-initial
commit_sha: null
local: required
local_evidence: Pre-change adapter check fails because main removed vendor/boring-sys/build/main.rs. STD-08 regressions and combined checks are pending.
remote_ci: required
remote_ci_evidence: Pending the authorized push.
device: required
device_evidence: Internal JNI signature and emulator binding smoke are pending. No physical device or external server acceptance is planned.
artifact: not_applicable
artifact_evidence: No release or published binary is owned by this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this repair.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-QUIC-ACTIVE-HELLO | DGN-1794211430951611 | Owning regression and independent validation pending | Pending |
| REQ-QUIC-RESPONSE-INTEGRITY | DGN-1794211431545271 | Owning regression and independent validation pending | Pending |
| REQ-QUIC-INITIAL-TOKEN | DGN-1794211432222833 | Owning regression and independent validation pending | Pending |
| REQ-QUIC-INITIAL-ACK | DGN-1794211432765485 | Owning regression and independent validation pending | Pending |
| REQ-QUIC-DATAGRAM-CRYPTO | DGN-1794211433260336 | Owning regression and independent validation pending | Pending |
| REQ-QUIC-HELLO-COMPLETE | DGN-1794211433822597 | Owning regression and independent validation pending | Pending |
| REQ-QUIC-PROBE-CANCEL | DGN-1794211434635760 | Owning regression and independent validation pending | Pending |

## Scope

Baseline: a7e8417a4baa2b96919f003f218884ab0da6d9d4. The preceding audit observed the failures on the same STD-08 source tree. C, local V, selected RFC S and external E remain separate. No external E is claimed. Do not archive until required evidence is resolved.
