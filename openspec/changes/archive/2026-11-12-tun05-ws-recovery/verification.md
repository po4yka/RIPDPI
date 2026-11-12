---
task_id: SVC-1794477234440384
change: tun05-ws-recovery
commit_sha: 38b8f4bac65f29e14e6031633ce8611c20101b2f
local: passed
local_evidence: Targeted supervisor, WS recovery, and VPN exit tests passed; service detekt and ktlintCheck passed. See /tmp/tun05-recovery-final-gates.log. Combined source gates and exact integration SHA are recorded by the parent integration task.
remote_ci: not_applicable
remote_ci_evidence: Parent owns remote push and CI observation.
device: not_applicable
device_evidence: No device acceptance is claimed by this local fix.
artifact: not_applicable
artifact_evidence: No native artifact is changed by the recovery supervisor.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-WS-RECOVERY | SVC-1794477296252372 | AmneziaWgRuntimeSupervisorTest and AmneziaWgWsRecoveryTest | passed |
| REQ-AWG-WS-STOP | SVC-1794477296252372 | AmneziaWgRuntimeSupervisorTest and AmneziaWgWsRecoveryTest | passed |
| REQ-AWG-WS-BOOTSTRAP | SVC-1794477296252372 | AmneziaWgWsRecoveryTest and config serialization tests | passed |

## Full service regression follow-up

The combined service suite found duplicate Stop signals in three existing cleanup tests. The supervisor now shares one Stop result for the captured runtime, publishes expected-stop state before a suspending signal, and joins without sending twice. Incomplete cleanup can still retry. The original assertions remain unchanged.

- Independent review approved the final signal ownership and error propagation.
- `./gradlew -Pripdpi.skipNativeBuild=true :core:service:detekt :core:service:ktlintCheck :core:service:testDebugUnitTest --console=plain` passed in 1m26s; log `/tmp/tun05-stop-once-full-gates.log`.
- The suite includes a new delayed-Stop regression for expected exit classification and one signal per owned cleanup.

## Rebased combined verification

Source commit 38b8f4bac65f29e14e6031633ce8611c20101b2f. Application sources match the tested revision 411ce08e731a7fec44808a87c9d6f1857ede8daf byte for byte; only task/OpenSpec records differ. `just ci-gates` passed (/tmp/tun05-rebased-ci-gates.log). Full staticAnalysis and 2288 Kotlin tests passed (/tmp/tun05-rebased-kotlin-gates.log): service2214, request18, editor37, readiness8, serialization11. Native core197 and carrier37 passed; affected-crate clippy passed without warnings. General and async reviews resolved all findings. No device, external-server, packaged-artifact or deployment acceptance is claimed.
