---
task_id: DAT-1790868146040476
change: mirror-observability-topology-v2
commit_sha: 8c6892ccdb7246427cc63ad3118140b1eeed3bea
local: passed
local_evidence: All 31 contracts match producer c7ef9bd519c28841f0c0b74256692fece1aa692a byte-for-byte; JSON and Draft202012 schema valid; one valid v2 and five invalid boundary cases checked; taskctl validate and strict OpenSpec PASS; architecture health PASS with 23 unchanged indicators; locked Cargo metadata --no-deps PASS with 117 members; build-gate -- ./gradlew :core:data:testDebugUnitTest --max-workers=4 --console=plain BUILD SUCCESSFUL in 6m15s with 119 actionable tasks.
remote_ci: required
remote_ci_evidence: Prior head d17ab3ae5c7a7f3d779e74e6e28d22322e7d189e failed CI run 36885319054 in JNI API snapshot check; authorized repair is pending exact-head hosted verification and merge.
device: not_applicable
device_evidence: Test-resource-only mirror; no device behavior changes.
artifact: not_applicable
artifact_evidence: No release artifact is produced.
deployment: not_applicable
deployment_evidence: Producer infrastructure is outside this client mirror task.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TOPOLOGY-V2-MIRROR | DAT-1790868271135771 | Source 8c6892ccdb7246427cc63ad3118140b1eeed3bea; all 31 mirrors byte-identical to frozen producer c7ef9bd519c28841f0c0b74256692fece1aa692a; schema and boundary checks, architecture, task/spec, locked metadata, and 801 core data tests in 86 suites PASS with no failures, errors, or skips. | passed |
| REQ-TOPOLOGY-V2-ISOLATION | DAT-1790868271659032 | Topology remains the sole deployment contract mirror change. Authorized CI repair normalizes the nightly Arc path alias and regenerates only strategy-trait's snapshot for the existing tcp_mss field. Seven checker tests PASS after reproducing the alias failure; type-argument changes and added fields still fail comparison. Narrow generated snapshot diff contains exactly one added tcp_mss line and read-only recheck PASS. Architecture health, locked metadata (117 members), and task contracts PASS. Exact-head hosted checks and protected-main merge pending. | required |

CI repair validation: `build-gate -- env CARGO_BUILD_JOBS=4 python3
scripts/ci/check_rust_api_snapshots.py` PASS for all 12 host-supported targets;
the Linux-owned runtime-platform target is skipped by the existing Darwin policy.
Local generation used cargo-public-api 0.52.0 and nightly 1.98.0 (2026-06-11),
so the hosted current-nightly result remains a separate acceptance gate.
