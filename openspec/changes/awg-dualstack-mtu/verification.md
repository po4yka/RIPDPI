---
task_id: DAT-1792081130689287
change: awg-dualstack-mtu
commit_sha: null
local: required
local_evidence: Eight Python runner and evidence-validator tests passed; architecture health and strict task/OpenSpec gates passed. Focused Gradle tests are queued in the machine build gate; independent host-native and Android runs remain unobserved.
remote_ci: required
remote_ci_evidence: No hosted run observed.
device: required
device_evidence: Pending Android arm64 emulator native-core interop; no physical-device acceptance.
artifact: passed
artifact_evidence: Canonical bundle schema, example, golden-full and negative JSON corpus were compared byte-for-byte with the deployment producer and matched.
deployment: not_applicable
deployment_evidence: Deployment is owned by the linked producer task.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-TRANSPORT | DAT-1792081224193869 | Parser/mapper/contract focused Gradle tests | Queued in machine build gate |
| REQ-AWG-ACTIVATION | DAT-1792081224193869 | Runtime readiness and service resolver focused Gradle tests | Queued in machine build gate |
| REQ-AWG-INTEROP | DAT-1792081224671303 | Independent peer IPv4/IPv6 TCP/UDP at 1280 and 1420 | Pending |
| REQ-AWG-INTEROP | DAT-1792081225208325 | Android arm64 emulator native-core traffic and three reconnects | Pending |

## Independent review

Read-only review approved the strict address/MTU guards and peer lifecycle. The writer fixed both evidence findings: acceptance now rejects untracked build inputs, and APK validation binds the packaged arm64 AWG library to the recorded native-library digest. Scoped re-review reported no further actionable findings; five evidence-validator unit cases passed.
