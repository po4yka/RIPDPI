---
task_id: PRX-1794211693515272
change: fix-http-request-boundaries
commit_sha: null
local: required
local_evidence: Pending targeted tests, independent decoders, review, and offline CI gates.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not an acceptance dependency of this local protocol repair; push results will be reported separately.
device: not_applicable
device_evidence: Byte parsing and stream ownership are verified on the host. Android and root transport acceptance are not claimed.
artifact: not_applicable
artifact_evidence: No Android native artifact or release is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HTTP-MUT-001 | PRX-1794211790672166 | Runtime sender, Lua flow, TUN regressions and h11/hyper-h2 decoding | Pending |
| REQ-HTTP-MUT-002 | PRX-1794211791494644 | Incomplete Lua head regression and h11 decoding | Pending |
| REQ-HTTP-MUT-003 | PRX-1794211792270604 | Packet and builtin LF/body tests | Pending |
| REQ-HTTP-MUT-004 | PRX-1794211793025990 | Authority and ambiguity classifier tests | Pending |
| REQ-HTTP-MUT-005 | PRX-1794211793798887 | Method EOL tests and independent decoder | Pending |

Combined gates and independent review: PRX-1794211794419926.
