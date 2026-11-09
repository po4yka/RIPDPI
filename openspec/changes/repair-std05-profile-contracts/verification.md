---
task_id: TRN-1794198826397434
change: repair-std05-profile-contracts
commit_sha: null
local: required
local_evidence: Regressions, affected package tests, Clippy, format and combined gates remain required.
remote_ci: required
remote_ci_evidence: Exact-source hosted CI has not been observed.
device: not_applicable
device_evidence: Host and loopback regressions cover these repairs. Android acceptance is not claimed.
artifact: not_applicable
artifact_evidence: No packaged or release artifact is owned by this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-STD05-F1 | TRN-1794198931080001 | Real padded ClientHello and static metadata regression; profiles tests | Required |
| REQ-STD05-F2 | TRN-1794198932517327 | Isolated validation counter and seed regression; xHTTP tests | Required |
| REQ-STD05-F3 | TRN-1794198934501667 | Successful TLS 1.2 and TLS 1.3 loopback probes and malformed record bounds | Required |
| REQ-STD05-F4 | TRN-1794198935969699 | Published names, native_default and unknown consumer validation matrix | Required |
| REQ-STD05-F5 | TRN-1794198937235252 | Preserved preset assertions and truthful snapshot/evidence descriptions | Required |
| REQ-STD05-F6 | TRN-1794198938191907 | Pinned source comparison and corrected C API/Rust wrapper/hardware description | Required |

## Combined gates

Run cargo test and all-targets Clippy for ripdpi-tls-profiles, ripdpi-xhttp, ripdpi-diagnostics-tls and affected consumer packages with --locked. Run cargo fmt, independent review, just ci-gates, architecture health and locked metadata. Record the source SHA and actual output before main integration. The registry and audit report remain unchanged.
