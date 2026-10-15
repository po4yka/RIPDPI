---
task_id: DAT-1792081130689287
change: awg-dualstack-mtu
commit_sha: null
local: required
local_evidence: Fifty native task CLI tests and twelve offline runner/evidence-validator tests passed; architecture health and strict task/OpenSpec gates passed. The baseline Go peer, production Android arm64 JNI library and isolated APK compiled on 0d341495c. The first resumed focused Gradle run failed before tests due to a same-worktree buildLogic lock collision; its serialized retry is running. Offline fixtures and compilation do not establish traffic acceptance.
remote_ci: required
remote_ci_evidence: Exact commit 0d341495c passed hosted JVM/app unit tests and static analysis in CI 38784005347. Its full 58-job inventory had no failures while release/native jobs remained active. Earlier bootstrap/fingerprint fixture failures and the ReturnCount violation were corrected without weakening checks. This test-host follow-up still requires exact-head revalidation.
device: required
device_evidence: Required scope is Android arm64 emulator native_core only, with both MTUs and three reconnect cycles each. The incomplete installed API32 image prevented startup; a valid API35 arm64 pool image ran the genuine JNI host, which failed authenticated readiness because its required protection callback was missing. The host now registers real Android VpnService.protect after OS authorization; fresh exact-source traffic verification remains required. Physical-device and Android VpnService TUN/no-leak checks are not gates.
artifact: passed
artifact_evidence: Canonical bundle schema, example, golden-full and negative JSON corpus were compared byte-for-byte with the deployment producer and matched.
deployment: not_applicable
deployment_evidence: Deployment is owned by the linked producer task.
---

# Verification

## Acceptance scope

The Android acceptance gate is emulator/native_core only. It does not require a physical device or Android VpnService TUN/no-leak adapter. Linux forwarding/no-leak verification is owned by the producer task. Missing emulator traffic evidence continues to block this task.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-FEDERATION | DAT-1792081224671303 | Native export/history tests and producer task-federation against this checkout | Passed 184 nodes and 31 byte-identical shared artifacts on 0d341495c |
| REQ-AWG-TRANSPORT | DAT-1792081224193869 | Parser/mapper/contract Gradle tests | Hosted JVM gate passed on 0d341495c |
| REQ-AWG-ACTIVATION | DAT-1792081224193869 | Runtime readiness and service resolver Gradle tests | Hosted JVM gate passed on 0d341495c |
| REQ-AWG-INTEROP | DAT-1792081224671303 | Independent peer IPv4/IPv6 TCP/UDP at 1280 and 1420 | Pending |
| REQ-AWG-INTEROP | DAT-1792081225208325 | Android arm64 emulator native-core traffic and three reconnects | Pending |

## Fixture integration audit

A full source search covered deployed bundle literals and builders in core data, runtime-state, app subscription/import tests, simple-flavor seeding tests and the checked-in CI bundle. All 21 statically parsed AWG entries now carry ordered IPv4 /32 and IPv6 /128 addresses plus an explicit supported MTU. Interpolated Room and mapper builders were inspected separately. The missing-address derived fixture was updated to mutate the new two-address input; missing/invalid address negatives require the exact early unsupported-profile exception and retain every no-mutation assertion; and the CI-seeding assertion now checks both imported families and MTU1420. S3/S4 and future-schema negatives have otherwise-valid transport and positive controls, so a transport refusal cannot satisfy those tests accidentally.

## Independent review

Read-only review approved the strict address/MTU guards and peer lifecycle. The writer fixed both evidence findings: acceptance now rejects untracked build inputs, and APK validation binds the packaged arm64 AWG library to the recorded native-library digest. Scoped re-review reported no further actionable findings; five evidence-validator unit cases passed.
