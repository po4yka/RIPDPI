---
task_id: RST-1794477240707368
change: tun05-native-initial-handshake
commit_sha: 38b8f4bac65f29e14e6031633ce8611c20101b2f
local: passed
local_evidence: Red: /tmp/tun05-native-red2.log, both pending-write regressions failed. Green: /tmp/tun05-native-green.log, both passed. Affected core197 tests passed in /tmp/tun05-native-combined-tests.log.
remote_ci: not_applicable
remote_ci_evidence: Local regression; no hosted CI run is required for this isolated change.
device: not_applicable
device_evidence: Deterministic host cancellation regression; no device behavior claim.
artifact: not_applicable
artifact_evidence: No packaged artifact change is claimed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-STARTUP-STOP | RST-1794477361212948 | cargo test -p ripdpi-warp-core --locked | passed |
| REQ-AWG-STARTUP-DEADLINE | RST-1794477361212948 | cargo test -p ripdpi-warp-core --locked | passed |

## Rebased combined verification

Source commit 38b8f4bac65f29e14e6031633ce8611c20101b2f. Application sources match the tested revision 411ce08e731a7fec44808a87c9d6f1857ede8daf byte for byte; only task/OpenSpec records differ. `just ci-gates` passed (/tmp/tun05-rebased-ci-gates.log). Full staticAnalysis and 2288 Kotlin tests passed (/tmp/tun05-rebased-kotlin-gates.log): service2214, request18, editor37, readiness8, serialization11. Native core197 and carrier37 passed; affected-crate clippy passed without warnings. General and async reviews resolved all findings. No device, external-server, packaged-artifact or deployment acceptance is claimed.
