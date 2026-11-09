---
task_id: TRN-1794198667426286
change: std06-certificate-policies
commit_sha: null
local: required
local_evidence: Focused regressions and combined gates are pending.
remote_ci: required
remote_ci_evidence: No push or hosted run has occurred yet.
device: not_applicable
device_evidence: This change owns Rust verification policy and internal Rust signatures. Host loopback and subprocess regressions exercise the affected logic. No JNI or device-specific behavior changes.
artifact: not_applicable
artifact_evidence: No distributable artifact is owned by this source fix.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-STD06-SAN | TRN-1794198793896705 | TLS 1.2/1.3 DNS/IP SAN and CN-only loopback regressions are pending. | pending |
| REQ-STD06-ROOTS | TRN-1794198794706108 | SSL_CERT_FILE and hashed SSL_CERT_DIR subprocess regressions are pending. | pending |
| REQ-STD06-DIAGNOSTICS | TRN-1794198795509654 | Default rejection and explicit observational verifier regression, plus caller compilation, are pending. | pending |
| REQ-STD06-OVERRIDES | TRN-1794198793896705 | Existing WebTunnel and DoT pin-only handshakes are pending. | pending |

## Combined gates

TRN-1794198796214639 owns affected crate tests and clippy, cargo metadata --locked, architecture health, just ci-gates, and independent review. No result is claimed before its command completes.
