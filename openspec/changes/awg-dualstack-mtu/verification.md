---
task_id: "DAT-1792081130689287"
change: "awg-dualstack-mtu"
commit_sha: "b92db30c6124e453a67ab64307b608d027f6baec"
local: "passed"
local_evidence: "Focused JVM/Detekt passed 135 tests with zero failures/skips; combined Persian/resolver locale tests passed all 10. Immutable 504 baseline reproduced both TCP regressions, then the separately compiled fixed tree passed all 94 warp-core tests. Normal hooks including workspace Clippy with warnings denied passed. Fifty native task CLI tests, fourteen offline runner/validator tests and thirty-two emulator-pool tests passed earlier on unchanged paths; offline fixtures do not count as traffic."
remote_ci: passed
remote_ci_evidence: "Exact compiled/runtime source b92db30c6124e453a67ab64307b608d027f6baec CI 38816708930 completed all 64 jobs: 46 success, 18 planned skips, zero failures or active jobs. This includes aggregate, static analysis, JVM/app, Kotlin coverage, every selected Rust workspace/network/lint/coverage/Miri/loom/turmoil/fuzz/native ABI packaging lane, all three release verification lanes and Android instrumentation APIs 27/33/35/36/37. Subsequent metadata-only commits do not change executable inputs; their selected task/document checks are reported separately in the PR."
device: "passed"
device_evidence: "Exact-b92 production Android arm64 JNI/APK passed all eight API 35 emulator/native_core sessions, MTUs 1280 and 1420 times cycle 0 baseline and reconnects 1..3, retaining one independent Go v0.2.12 peer per MTU. All 64 measured IPv4/IPv6 TCP/UDP/DNS observations passed and all 8 real VpnService.protect calls succeeded. Invocation nonce awg-native-20261015-b92db30c6-5; evidence.json SHA256 e46419f582221aa444e528d7cdf9cbdc7ae76d3e52d60b2a0a020b3e26f9d0b5. Client validator and producer canonical Make evidence target both passed. Owned emulator cleanup completed. Physical-device and Android TUN/no-leak checks are outside the required scope."
artifact: "passed"
artifact_evidence: "All 31 shared bundle/schema/example/negative/golden contract artifacts match the producer byte-identically. Canonical producer federation passed 185 tasks against compiled b92. Native library SHA256 efb05d8c9ab278732bb0f7d14ed85065cde1e4582ac554ce830fd37bf4ef4e9f equals the packaged APK native digest; APK SHA256 5eef75426ddf6098d69a94e4cad4ba624bff0c72f7a9b8fc4f7f50999e3ccbca. Independent peer binary SHA256 4e62b91e762ab182a4638bcc34409139a1e265376454cf32bb17af4b9d4febcb."
deployment: "not_applicable"
deployment_evidence: "Deployment is owned by the linked producer task."
---

# Verification

## Acceptance scope

The Android acceptance gate is emulator/native_core only. Physical-device and Android VpnService TUN/no-leak checks are outside this change; Linux forwarding/no-leak verification belongs to the producer task. The measured source is b92db30c6124e453a67ab64307b608d027f6baec. Subsequent verification/task-only commits do not change its executable inputs.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-FEDERATION | DAT-1792081224671303 | Native export/history tests and canonical producer task-federation | Passed 185 tasks against b92; 31 byte-identical shared artifacts |
| REQ-AWG-TRANSPORT | DAT-1792081224193869 | Focused parser/mapper/contract Gradle tests | Passed in 135-test local gate and exact-b92 hosted JVM gate |
| REQ-AWG-ACTIVATION | DAT-1792081224193869 | Activation/readiness/service resolver tests | Passed local and exact-b92 hosted JVM gates |
| REQ-AWG-INTEROP | DAT-1792081224671303 | Exact independent AWG v0.2.12 Go peer, production Android JNI, IPv4/IPv6 TCP/UDP/DNS | Eight actual sessions passed at MTUs 1280 and 1420 |
| REQ-AWG-INTEROP | DAT-1792081225208325 | API 35 arm64 emulator native_core, persistent peer per MTU, cycle 0 plus restarts 1..3 | Passed 64 traffic observations and 8 real socket-protection results; client and producer validators passed |

## Reconnect regression

The earlier 504 native run passed its initial 1280 session and failed the first reconnect TCP read. An actual peer packet trace showed source port 1000 and initial SYN sequence 4260165794 repeated after reconnect. Two component regressions failed on that immutable baseline. The fixed source uses OS entropy for TCP seeds and the existing port ring, keeps port leases through cleanup, drains accepted payload before FIN, and polls RST before bounded abandoned-socket removal. All 94 warp-core tests pass, including five packet-level sequence/FIN/order/reset regressions. The successful eight-session run used a rebuilt library and APK from the fixed source; it did not reset the peer or loosen acceptance deadlines.

## Artifact identity

The invocation is bound to nonce `awg-native-20261015-b92db30c6-5` and source `b92db30c6124e453a67ab64307b608d027f6baec`. The independent peer uses v0.2.12 at commit `2e3f7d122ca8ef61e403fddc48a9db8fccd95dbf`, built with Go 1.27.1. The validator checks all eight raw instrumentation method results, payload hashes, DNS semantics, selected MTUs, real protection results, stable peer endpoints and compiled-library/APK digest equality. Production JNI/protobuf interfaces and dependency pins remain unchanged.

## Fixture integration audit

A full source search covered deployed bundle literals and builders in core data, runtime-state, app subscription/import tests, simple-flavor seeding tests and the checked-in CI bundle. All 21 statically parsed AWG entries now carry ordered IPv4 /32 and IPv6 /128 addresses plus an explicit supported MTU. Interpolated Room and mapper builders were inspected separately. The missing-address derived fixture was updated to mutate the new two-address input; missing/invalid address negatives require the exact early unsupported-profile exception and retain every no-mutation assertion; and the CI-seeding assertion now checks both imported families and MTU1420. S3/S4 and future-schema negatives have otherwise-valid transport and positive controls, so a transport refusal cannot satisfy those tests accidentally.

## Independent review

Read-only review approved the strict address/MTU guards and peer lifecycle. The writer fixed both evidence findings: acceptance now rejects untracked build inputs, and APK validation binds the packaged arm64 AWG library to the recorded native-library digest. Scoped re-review reported no further actionable findings; five evidence-validator unit cases passed.

The native reconnect review also found that shared EOF state could overtake queued payload. The final implementation permits FIN only after the ordered EOF marker, retains lease ownership until socket cleanup and emits a polled RST on the bounded fallback. Re-review found no remaining material findings; all five packet regressions and the actual eight-session emulator matrix passed.
