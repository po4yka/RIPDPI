---
task_id: DAT-1793954543063965
change: generated-settings-json
commit_sha: f6ec8b55155d2c1a75b667a628a188069093c9e5
local: passed
local_evidence: Invalid-value and restore regressions failed before the fix. The final settings suite passed 87 tests and the data suite passed 984 tests, with no skipped tests. Independent review passed. Rebased source gates and full static analysis passed. Architecture health and locked Cargo metadata passed.
remote_ci: not_applicable
remote_ci_evidence: On 2026-11-06 the user explicitly waived waiting for hosted CI and requested final integration and push. Native-size gates passed for all three debug variants. The latest main CI was still running when this requirement was waived; no complete green CI result is claimed.
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
| REQ-SETTINGS-JSON-001 | DAT-1793964177685626 | Native-size gates passed; the user waived hosted CI waiting | Passed |
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
- These are historical failures. Later corrections and the final acceptance decision are recorded below. Device acceptance is not claimed.

## Approved CI completion

On 2026-11-06, the user approved a native-size limit increase and requested green CI and task closure. Preserve all eight historical byte measurements, the existing approval record, and all gate behavior. Use authenticated CI artifacts to validate the smallest limit increase under the existing rounding policy. The previous source validation remains valid. Policy validation passed. The user later removed the requirement to wait for hosted CI, as recorded below.

The approved policy was checked against artifacts from CI run `42198808996` (source `83ec7d0ddb8b63899700e6213ebf6d4233a449cd`). Native build inputs are unchanged through the policy validation base `2a8fb69d9c914e211ef4c9007505e1a6025613b9`. All five ZIP digests matched GitHub metadata. Copies of the four ABI shards were stripped with the pinned NDK 29 tool; all eight tracked byte counts matched the CI report. The old size policy failed for three libraries and the total; the approved policy passed against the same files. ELF verification passed for five libraries on four ABIs.

The limits are 671744 bytes per library, 4.7 percent total growth, and 3670016 bytes total absolute growth. At that validation revision, the rounding rule left 24480 bytes for the largest individual growth and 14459 bytes for total growth. All eight historical measurements, previous provenance, gate code, coverage, and bloat limits remain unchanged. The 16 existing size-policy and verifier tests, policy delta validation, documentation review, and native verifier review passed. `build-gate -- just ci-gates`, architecture health, and locked Cargo metadata passed on the combined base. The final hosted acceptance decision is recorded below.


## Final acceptance decision

On 2026-11-06, the user explicitly requested completion, integration into `main`, and push without further CI waiting. Hosted CI completion is therefore not an acceptance gate for this delivery. This decision does not change any workflow, test assertion, verifier, or baseline measurement.

- The approved native-size policy is integrated in `6e4b57254982e597f3cc317dfdbc962c8f48a7e3`.
- All three debug native-size and ELF gates passed in [run 42226996320](https://github.com/po4yka/RIPDPI/actions/runs/42226996320) on `0a6426f52da0b0b7122c4b3df67843e2ca55e089`. The authenticated report ZIP matched SHA-256 `4e16071cb3bbf6c7ad3aa3c537efb072623889ec1ea53d1d5dd6f679436fd8e8`. All eight libraries passed. Total size was 80230800 bytes against a limit of 80238131, leaving 7331 bytes.
- That full run failed only the Android UI interaction checks on API 33, 35, and 37 and the aggregate gate. The shared failure occurred when a test clicked before the initial status data changed the layout. The test now waits for the existing strategy-panel tag before scrolling and issuing the same touch click. Assertions and the timeout are unchanged.
- [PR 711](https://github.com/po4yka/RIPDPI/pull/711) merged that test correction as `f6ec8b55155d2c1a75b667a628a188069093c9e5`. It changes no native input. [Main CI run 42238737865](https://github.com/po4yka/RIPDPI/actions/runs/42238737865) was still running at the user's decision. No terminal success is claimed for this run.
- Earlier DNS instrumentation and selector retry failures were corrected by their owners and integrated. Local settings, backup, static analysis, source-gate, and native-policy evidence above remains recorded separately from hosted results.
