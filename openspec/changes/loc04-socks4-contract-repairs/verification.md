---
task_id: PRX-1794116241356560
change: loc04-socks4-contract-repairs
commit_sha: null
local: required
local_evidence: Regression tests, affected Rust crate checks, offline source gates, and independent review are pending.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not an acceptance gate for this local Rust repair. Push is requested, but hosted run success is not claimed.
device: not_applicable
device_evidence: No Android lifecycle, JNI, or platform-specific contract changes are planned. Loopback host tests verify these protocol repairs.
artifact: not_applicable
artifact_evidence: No signed or packaged binary delivery is requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-S4-CONNECT | PRX-1794116315304352 | Owning behavior tests and combined Rust/source gates | Pending |
| REQ-S4-FIN | PRX-1794116317212493 | Owning behavior tests and combined Rust/source gates | Pending |
| REQ-S4-FAMILY | PRX-1794116318838623 | Owning behavior tests and combined Rust/source gates | Pending |
| REQ-S4-CANCEL | PRX-1794116320267158 | Owning behavior tests and combined Rust/source gates | Pending |
| REQ-S4-PARSE | PRX-1794116322076440 | Owning behavior tests and combined Rust/source gates | Pending |
| REQ-S4-LIMIT | PRX-1794116324708518 | Owning behavior tests and combined Rust/source gates | Pending |
| REQ-S4-COMPAT | PRX-1794116326351813 | Owning behavior tests and combined Rust/source gates | Pending |
