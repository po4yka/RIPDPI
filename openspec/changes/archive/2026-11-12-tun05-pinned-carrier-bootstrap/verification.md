---
task_id: RST-1794477836924583
change: tun05-pinned-carrier-bootstrap
commit_sha: 38b8f4bac65f29e14e6031633ce8611c20101b2f
local: passed
local_evidence: /tmp/tun05-native-combined-tests.log: core197 and carrier27 unit plus7 integration passed. Numeric dial reached loopback with original TLS SNI and HTTP identity; empty and invalid numeric lists rejected.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI result is claimed.
device: not_applicable
device_evidence: Device underlay acceptance belongs to the service recovery change.
artifact: not_applicable
artifact_evidence: No packaged artifact is claimed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-WSS-PINNED-DIAL | RST-1794478016736497 | cargo test -p ripdpi-wireguard-ws -p ripdpi-warp-core --locked | passed |
| REQ-WSS-URL-IDENTITY | RST-1794478016736497 | cargo test -p ripdpi-wireguard-ws --locked | passed |

## Rebased combined verification

Source commit 38b8f4bac65f29e14e6031633ce8611c20101b2f. Application sources match the tested revision 411ce08e731a7fec44808a87c9d6f1857ede8daf byte for byte; only task/OpenSpec records differ. `just ci-gates` passed (/tmp/tun05-rebased-ci-gates.log). Full staticAnalysis and 2288 Kotlin tests passed (/tmp/tun05-rebased-kotlin-gates.log): service2214, request18, editor37, readiness8, serialization11. Native core197 and carrier37 passed; affected-crate clippy passed without warnings. General and async reviews resolved all findings. No device, external-server, packaged-artifact or deployment acceptance is claimed.
