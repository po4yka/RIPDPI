---
task_id: TRN-1786264762917677
change: trn-1786264762917677-wire-amneziawg-rtk-south-jc4-cohort-into-android-client
commit_sha: 3dec5338c6efd2177b3dacb2860665a141ae25e7
local: passed
local_evidence: On source a4c5c898f, the Rust synthetic-endpoint test passed. AwgCohortCatalogLoadTest and AmneziaWgRuntimeSupervisorTest passed all 25 JVM tests with zero skips.
remote_ci: passed
remote_ci_evidence: PR 697 run 41191370173 passed rust-native-x86_64, the arm64-v8a, armeabi-v7a, and x86 packaging jobs, Rust workspace tests, Kotlin coverage, and ci-required at the recorded PR head. The native and service files match the merge commit ac167d411.
device: not_applicable
device_evidence: This task verifies profile import, startup retries, and a synthetic endpoint handshake. Its acceptance scope has no physical device run.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this portfolio area.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TRN-1786264762917677-001 | TRN-1786264762919013 | Hosted native jobs passed for arm64-v8a, armeabi-v7a, x86, and x86_64. | passed |
| REQ-TRN-1786264762917677-002 | TRN-1786264762919672 | Subscription parsing and profile activation carry AWG parameters through to the runtime. Hosted Kotlin coverage and 17 cohort catalog tests passed. | passed |
| REQ-TRN-1786264762917677-003 | TRN-1786264762919567 | The `rtk_south_cohort_handshakes_with_a_synthetic_awg_endpoint` Rust test passed with the pinned Rust 1.100.0 toolchain. | passed |
| REQ-TRN-1786264762917677-004 | TRN-1786264762919854 | Catalog tests verified the configurable limit. Supervisor tests verified that failed starts use fresh runtimes within that limit. | passed |
| REQ-TRN-1786264762917677-005 | TRN-1786264762919975 | The Android profile uses the native AWG WireGuard engine. The WireGuard WebSocket carrier is a separate transport. | passed |

The [hosted CI run](https://github.com/po4yka/RIPDPI/actions/runs/41191370173) passed before PR 697 merged. The producer cohort was added to the deployment repository at `aa6d5d228` and subsequently renamed there to `narrow-junk-sequential`. The local handshake test uses a synthetic server.
