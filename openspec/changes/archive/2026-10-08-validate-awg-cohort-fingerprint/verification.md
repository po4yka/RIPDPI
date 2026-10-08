---
task_id: DAT-1791479983861691
change: validate-awg-cohort-fingerprint
commit_sha: 7447a1c8a194a1af4fe1f28a7b45be6f3b86acfb
local: passed
local_evidence: 35 parser and contract JVM tests passed; core data and runtime-state ktlintCheck and detekt passed.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is tracked separately after push; targeted local gates cover this change.
device: not_applicable
device_evidence: Pure subscription parsing; no runtime implementation changes.
artifact: not_applicable
artifact_evidence: No release artifact is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-COHORT-REJECT | DAT-1791480056427884 | AwgCohortFingerprintImportTest; RED 3 expected failures, GREEN 4 passed | Passed |
| REQ-AWG-COHORT-COMPAT | DAT-1791480056427884 | RipdpiBundleContractTest, SingBoxRipdpiExtensionParserTest, WireGuardIniSubscriptionParserTest; 31 passed | Passed |

## Local evidence

- RED: `:core:data:testDebugUnitTest --tests com.poyka.ripdpi.data.AwgCohortFingerprintImportTest` on the original production parser: 4 tests, 3 expected assertion failures, no errors or skips.
- GREEN: the same task with `AwgCohortFingerprintImportTest`, `RipdpiBundleContractTest`, `SingBoxRipdpiExtensionParserTest`, and `WireGuardIniSubscriptionParserTest`: 35 tests, zero failures, errors, or skips.
- `:core:data:ktlintCheck :core:data:runtime-state:ktlintCheck :core:data:detekt :core:data:runtime-state:detekt` passed. Initial test formatting findings were corrected before the final run; no baselines changed.
- `python3 scripts/ci/check_architecture_health.py`: 21 unchanged indicators, no new or worsened entries.
- Gradle used `--max-workers=4 -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=4`, 5 GiB Gradle and 3 GiB Kotlin heaps, and 15 second HTTP timeouts. The integration owner serialized builds across worktrees.
- No hosted CI, device, native artifact, or live server result is inferred. A matching fingerprint does not prove that a bundle is current on the server.

## Combined tree verification

The final combined gate passed on `7a4a87a9d90e1575264ad3f3ae643e9580fbec4f`: 269 targeted tests (134 core data, 10 runtime state, 10 service, 24 Simple seeder, and 91 Full app), with zero failures, errors, or skips. The runtime-state tests were up to date on unchanged source. `staticAnalysis` passed in the same invocation: 816 tasks, 167 executed, 5 from cache, and 644 up to date. Architecture health reported 21 unchanged indicators with no new or worsened entries; `cargo metadata --manifest-path native/rust/Cargo.toml --locked` passed. The integration owner supplied these results and the local Gradle log `/tmp/awg-p2-rebased-fresh.log`. Hosted CI, device, native artifact, and live-server results are not inferred.

The previous combined tree `8ff34e99bc60970a77a3d35e6a67aa35f0905b03` also passed 269 targeted tests and `staticAnalysis`. The first run after rebase failed because the disk was full and was followed by heap and result serialization failures. It was superseded by the successful repeat gate above.
