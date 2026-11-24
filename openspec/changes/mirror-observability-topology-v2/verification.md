---
task_id: DAT-1790868146040476
change: mirror-observability-topology-v2
commit_sha: 892f016fe2b1b518315e281af15378f7021439b5
local: passed
local_evidence: All 31 contracts match producer c7ef9bd519c28841f0c0b74256692fece1aa692a byte-for-byte; JSON and Draft202012 schema valid; one valid v2 and five invalid boundary cases checked; taskctl validate and strict OpenSpec PASS; architecture health PASS with 23 unchanged indicators; locked Cargo metadata --no-deps PASS with 117 members; build-gate -- ./gradlew :core:data:testDebugUnitTest --max-workers=4 --console=plain BUILD SUCCESSFUL in 6m15s with 119 actionable tasks.
remote_ci: passed
remote_ci_evidence: PR 575 run 36895278446 passed JNI API snapshot, Kotlin coverage, and ci-required. The recorded merge commit also passed main run 36900019871.
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
| REQ-TOPOLOGY-V2-ISOLATION | DAT-1790868271659032 | Topology remains the sole deployment contract mirror change. The CI repair normalizes the nightly Arc path alias and records the existing tcp_mss field in the strategy-trait snapshot. Seven checker tests, architecture health, locked metadata, and task contracts passed. Required hosted checks passed and PR 575 merged into main. | passed |

CI repair validation: `build-gate -- env CARGO_BUILD_JOBS=4 python3
scripts/ci/check_rust_api_snapshots.py` PASS for all 12 host-supported targets;
the Linux-owned runtime-platform target is skipped by the existing Darwin policy.
Local generation used cargo-public-api 0.52.0 and nightly 1.98.0 (2026-06-11),
and the hosted current-nightly check passed on the PR. A separate Git blob
comparison at the recorded merge commit found all 31 frozen producer
contracts byte-identical, with no missing or extra files. The merge commit
passed [hosted CI](https://github.com/po4yka/RIPDPI/actions/runs/36900019871).
