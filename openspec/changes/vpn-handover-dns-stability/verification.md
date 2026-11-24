---
task_id: VPN-1795491626935120
change: vpn-handover-dns-stability
commit_sha: null
local: passed
local_evidence: Two service editions, model and runtime-state suites, and targeted app suites passed 5478 JVM tests. The two tunnel crates passed 660 tests with one existing ignored test. Offline repository source gates passed.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No APK publication is part of this change.
deployment: not_applicable
deployment_evidence: No deployment is part of this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN-DNS-RECOVERY | VPN-1795491698510187 | JVM controller regressions cover failure bursts, exhausted retry, mixed samples, and privacy exclusions. Native tests separate resolver and local queue failures. | Passed |
| REQ-VPN-LESS-DISRUPTION | VPN-1795491699006166 | VPN coordinator regressions cover reuse with unchanged effective inputs, DNS-only TUN replacement, and replacement after a superseding physical route change. | Passed |
| REQ-VPN-HANDOVER-EVIDENCE | VPN-1795491699006166 | VPN coordinator and guided acceptance regressions cover ready versus revalidated, generation-bound forwarding, and a fresh UDP payload probe. | Passed |

## Commands

- `./gradlew :core:service:testStandardDebugUnitTest :core:service:testExtendedDebugUnitTest :core:data:runtime-state:testDebugUnitTest :core:data:model:testStandardDebugUnitTest :core:data:model:testExtendedDebugUnitTest` with `-Pripdpi.skipNativeBuild=true`.
- `./gradlew :app:testGithubDebugUnitTest --tests '*MainViewModelTest' --tests '*SelectorRecoveryScheduleTest'` with `-Pripdpi.skipNativeBuild=true`.
- `cargo test --locked -j 4 -p ripdpi-tunnel-core -p ripdpi-tunnel-android --lib` from `native/rust`.
- `just ci-gates`.

Physical Wi-Fi-to-cellular forwarding and device DNS recovery still need direct
device evidence.
