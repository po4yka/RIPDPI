---
task_id: RST-1794212008025103
change: rst-1794212008025103-correct-tls-clienthello-parsing-and-mutation
commit_sha: 8480ede162947052316948e59217c6dfb961d1a0
local: passed
local_evidence: On 8480ede162947052316948e59217c6dfb961d1a0, just ci-gates, cargo metadata --locked, cargo deny 0.19.0 and 355 locked nextest cases for packets, Tor and Android fetch passed. One Tor test reported LEAK once; three isolated repeats did not reproduce it and its cause remains unknown. Linux-owned API snapshot was skipped on macOS. No physical device, external Tor network or old-cache migration was tested.
remote_ci: passed
remote_ci_evidence: Full workflow_dispatch CI https://github.com/po4yka/RIPDPI/actions/runs/42702413061 passed on 8480ede162947052316948e59217c6dfb961d1a0, including rust-lint, rust-fuzz-smoke, Android API 27/35/37 and ci-required.
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
| REQ-TLS-ECH-ID | RST-1794214443209399 | ECH adapter tests, host JNI facade check, scanner and two independent reviews | passed |
| REQ-TLS-FUZZ-LOCK | RST-1794214442322852 | Locked metadata and full fuzz smoke with local ARM cargo-fuzz | passed |
| REQ-CI-TOR-SQLITE | RST-1794220081392995 | cargo-deny 0.19.8, 13 Tor tests, Android arm64 check and independent reviews | passed locally |
| REQ-CI-ECH-DIAGNOSTICS | RST-1794220142663069 | diagnostics detekt, ktlint, unit tests and independent review | passed locally |
