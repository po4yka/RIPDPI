---
task_id: DAT-1793954543063965
change: generated-settings-json
commit_sha: 83ec7d0ddb8b63899700e6213ebf6d4233a449cd
local: passed
local_evidence: Invalid-value and restore regressions failed before the fix. The final settings suite passed 87 tests and the data suite passed 984 tests, with no skipped tests. Independent review passed. Rebased source gates and full static analysis passed. Architecture health and locked Cargo metadata passed.
remote_ci: blocked
remote_ci_evidence: CI run 42198808996 failed in unchanged Android instrumentation and native-size checks. Static analysis, Kotlin coverage, Roborazzi, release verification, and CodeQL run 42198808882 passed. The task remains open.
device: not_applicable
device_evidence: This codec change has no device-specific behavior; no physical-device acceptance is claimed.
artifact: not_applicable
artifact_evidence: No release artifact is distributed by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SETTINGS-JSON-001 | DAT-1793954649593724 | Settings JSON round-trip and shape tests | Passed |
| REQ-SETTINGS-JSON-002 | DAT-1793954649593724 | Invalid-value and atomic backup restore tests | Passed |
| REQ-SETTINGS-JSON-003 | DAT-1793954649593724 | Secret exclusion and transport validation tests | Passed |

## Local behavior evidence

- The original codec failed 14 of 18 invalid-value cases and the backup restore atomicity regression.
- The first generated-codec run passed the invalid-value and backup regressions. It exposed an existing AWG default asymmetry: omitted disabled values became a custom preset that could not be restored again.
- Four AWG-related regressions then failed before the mapper correction. The final mapper uses shared preset inference and validates the original wire profile and headers, including inferred custom settings.
- `build-gate -- ./gradlew --max-workers=4 :core:data:settings:ktlintFormat :core:data:settings:testDebugUnitTest :core:data:testDebugUnitTest` passed. The suites contain 87 and 984 tests; none were skipped.
- Independent code and documentation reviews found no remaining defects. No device, live-service, release artifact, or deployment evidence is claimed.

- `build-gate -- just ci-gates` passed before and after rebase. Architecture health and `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1` passed after rebase.
- The initial full static analysis stopped on an ambient `CARGO_BUILD_JOBS` override. The next run compiled native outputs but stopped on the missing pinned NaiveProxy artifact. The repository fetch script downloaded and verified the arm64-v8a artifact.
- `build-gate -- env -u CARGO_BUILD_JOBS ./gradlew --max-workers=4 -Pripdpi.nativeCpuBudget=2 staticAnalysis` then passed all 814 tasks. The settings and data test tasks passed on the rebased tree; the settings task reused unchanged results.

- After the parallel DNS migration reached `main`, the branch was rebased onto `1a1a1698e`. The settings suite (87 tests), data suite (984 tests), and full `staticAnalysis` passed together: 842 Gradle tasks, zero failed or skipped tests. Architecture health, locked Cargo metadata, and `build-gate -- just ci-gates` also passed on this base.

## Hosted validation

- Integrated source and local evidence: `83ec7d0ddb8b63899700e6213ebf6d4233a449cd`.
- [CI run 42198808996](https://github.com/po4yka/RIPDPI/actions/runs/42198808996) failed. Static analysis, Kotlin coverage, Roborazzi, all six release verification variants, native packaging, and JNI snapshot checks passed.
- Debug and release instrumentation compilation failed at `NativeDoqQuicClientInstrumentedTest.kt:30`: the call passes an `Int` where the DNS codec now requires a `ByteArray`. This unchanged call also failed on the preceding main commit in [CI run 42198423850](https://github.com/po4yka/RIPDPI/actions/runs/42198423850).
- All three debug APK size checks failed for unchanged native inputs. `libripdpi.so` exceeded the allowed size for arm64-v8a (12138200 > 12124600), x86 (11870796 > 11831760), and x86_64 (13600616 > 13559560). Total native size was 80223672 bytes; the limit was 80044100 bytes. No native source or size baseline changed in this task.
- [CodeQL run 42198808882](https://github.com/po4yka/RIPDPI/actions/runs/42198808882), Secret Scan, harness checks, and fleet fixture checks passed.
- These CI blockers remain open. The task is not archived or closed, and device acceptance is not claimed.
