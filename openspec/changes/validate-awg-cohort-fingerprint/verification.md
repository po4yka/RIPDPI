---
task_id: DAT-1791479983861691
change: validate-awg-cohort-fingerprint
commit_sha: null
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
