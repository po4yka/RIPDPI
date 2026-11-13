---
task_id: RST-1794557076132326
change: dpi10-tls-record-repairs
commit_sha: null
local: required
local_evidence: Owning crate tests, formatting, targeted Clippy, source gates, and independent review are pending.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI run is requested; local source gates are required before integration.
device: not_applicable
device_evidence: This change owns native byte processing and host runtime activation, with no Android lifecycle or JNI contract change. Device compatibility is not claimed.
artifact: required
artifact_evidence: Production adapter loopback bytes and independent TLS acceptance are pending.
deployment: not_applicable
deployment_evidence: No release or deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TLSREC-OFFSET | RST-1794557341725489 | Captured TLS boundary regression and crate suite | pending |
| REQ-TLSREC-BUDGET | RST-1794557342575436 | Bounded rejection and useful skip regressions | pending |
| REQ-TLSREC-EVIDENCE | RST-1794557343352527 | Applied-count and independent byte assertions | pending |
| REQ-TLSREC-MULTIRECORD | RST-1794557344427913 | Multi-record corpus and production loopback | pending |
| REQ-TLSREC-ECH | RST-1794557344427913 | Split-SNI activation and opaque bytes | pending |
