---
task_id: RST-1794212008025103
change: rst-1794212008025103-correct-tls-clienthello-parsing-and-mutation
commit_sha: a43980af23c2bed696e9b1409889dfc66c26afbc
local: required
local_evidence: On source a43980af23c2bed696e9b1409889dfc66c26afbc, just ci-gates passed without a dependency override and the fragmented TLS adapter regression passed with the vendored boring-sys. Previously the combined packets tests passed 269 unit and 5 golden tests. The runtime-platform API snapshot is Linux-owned and was skipped on macOS.
remote_ci: required
remote_ci_evidence: CI for 7bd65bae644a250a6d00c4207426be1fdffa8607 failed rust-fuzz-smoke and rust-lint; a passing run for the repair remains required.
device: not_applicable
device_evidence: This change does not require physical device operations.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TLS-SNI-BOUNDS | RST-1794212085369550 | Combined cargo test -p ripdpi-packets --locked and independent review | passed |
| REQ-TLS-RECORDS | RST-1794212097659080 | Combined packets tests and adapter regression with the restored vendored boring-sys | passed |
| REQ-TLS-MUTATION | RST-1794212099284584 | Combined packets tests and independent review | passed |
| REQ-TLS-ECH-ID | RST-1794214443209399 | Pending | required |
| REQ-TLS-FUZZ-LOCK | RST-1794214442322852 | Pending | required |
