---
task_id: RST-1794478552541413
change: tun05-ws-control-messages
commit_sha: 38b8f4bac65f29e14e6031633ce8611c20101b2f
local: passed
local_evidence: Red /tmp/tun05-control-red.log: valid controls returned UnexpectedFrameType. Initial green /tmp/tun05-control-green.log: carrier27 unit plus8 integration passed. Async review required nonblocking Ping handling; Final /tmp/tun05-control-review-green.log: 27 unit plus10 integration passed, including pending-send data and Close progress.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI claim.
device: not_applicable
device_evidence: Host transport regression only.
artifact: not_applicable
artifact_evidence: No packaged artifact claim.
deployment: not_applicable
deployment_evidence: No deployment owned.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-WS-CONTROL | RST-1794478582133767 | cargo test -p ripdpi-wireguard-ws --locked | passed |

## Rebased combined verification

Source commit 38b8f4bac65f29e14e6031633ce8611c20101b2f. Application sources match the tested revision 411ce08e731a7fec44808a87c9d6f1857ede8daf byte for byte; only task/OpenSpec records differ. `just ci-gates` passed (/tmp/tun05-rebased-ci-gates.log). Full staticAnalysis and 2288 Kotlin tests passed (/tmp/tun05-rebased-kotlin-gates.log): service2214, request18, editor37, readiness8, serialization11. Native core197 and carrier37 passed; affected-crate clippy passed without warnings. General and async reviews resolved all findings. No device, external-server, packaged-artifact or deployment acceptance is claimed.
