---
task_id: DAT-1794720880096281
change: imp02-uri-audit-corrections
commit_sha: 389d98e497b0a92d9dbac0ae3c305b60b3f6d4a6
local: passed
local_evidence: Runtime-state 424, core-data 1001, and app 3497 tests passed with zero failures, errors, or skips. StaticAnalysis, ci-gates, architecture health, locked Cargo metadata, and independent review passed.
remote_ci: required
remote_ci_evidence: Hosted CI acceptance remains pending the authorized main push.
device: not_applicable
device_evidence: Local parser and error handling corrections; no Android VPN or external transport acceptance claim.
artifact: not_applicable
artifact_evidence: No release or binary artifact is produced by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

Source commit: `389d98e497b0a92d9dbac0ae3c305b60b3f6d4a6`.
Only task and verification metadata changes after this source commit.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-URI-CONVERSION | DAT-1794720956862495 | ScannerUriDispatcherTest unsafe-value cases through classifier, scanner, and clipboard; existing valid-link cases | Passed in the 40-test app regression set |
| REQ-AWG-URI-ROUTES | DAT-1794720957411275 | AmneziaWgUriRoutePolicyTest: empty, absent, and nonempty route policies | Passed in the 424-test runtime-state suite |
| REQ-AWG-URI-ENCODE | DAT-1794720957913394 | AmneziaWgUriSignatureExportTest: all slots, mixed slots, and external empty rejection | Passed in the 424-test runtime-state suite |
| REQ-AWG-URI-PRIVACY | DAT-1794720958410098 | ScannerUriDispatcherTest and QrScannerViewModelTest invalid-link/error-state cases | Passed in the 40-test app regression set |
| REQ-AWG-URI-DOCS | DAT-1794720958894163 | URI and form text compared with scalar/HP validators; existing nonzero S3/S4 codec tests; independent source review | Passed |

## Observed commands

Commands ran in the isolated job worktree. Compiler-backed commands used the
machine build gate and at most four Gradle workers. Host checks used the
repository-supported `ripdpi.skipNativeBuild=true` property. This property
omits unrelated native compilation; it does not skip unit tests.

```sh
build-gate --lane ripdpi -- env -u CARGO_BUILD_JOBS ./gradlew \
  :core:data:runtime-state:testDebugUnitTest :core:data:testDebugUnitTest \
  :app:testGithubFullDebugUnitTest -Pripdpi.skipNativeBuild=true \
  --no-daemon --max-workers=4 -Pripdpi.nativeCpuBudget=4

build-gate --lane ripdpi -- env -u CARGO_BUILD_JOBS ./gradlew \
  staticAnalysis -Pripdpi.skipNativeBuild=true --no-daemon \
  --max-workers=4 -Pripdpi.nativeCpuBudget=4

build-gate --lane ripdpi -- env -u CARGO_BUILD_JOBS ./gradlew \
  :app:testGithubFullDebugUnitTest -Pripdpi.skipNativeBuild=true \
  --no-daemon --max-workers=1 -Pripdpi.nativeCpuBudget=4

build-gate --lane ripdpi -- just ci-gates
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1
python3 scripts/ci/check_architecture_health.py
```

- Runtime-state: 424 tests, zero failures, errors, or skips.
- Core-data: 1001 tests, zero failures, errors, or skips.
- Affected app classes: ScannerUriDispatcherTest 19, ClipboardImportClassifierTest 9,
  QrScannerViewModelTest 12; all 40 passed without failures, errors, or skips.
- Final serial full app retry: exit 0, 3497 tests, zero failures, errors, or skips;
  BUILD SUCCESSFUL in 5 min 20 s, 299 actionable tasks.
- StaticAnalysis: exit 0, BUILD SUCCESSFUL, 29 min 11 s; 817 actionable tasks.
  Full and Simple app lint completed.
- ci-gates: exit 0 on the final source; Rust API snapshots match. The gate retains
  its normal Darwin exclusion for the Linux-owned runtime-platform snapshot.
- Architecture health: no new or worsened indicators. No baseline changed.
- Cargo metadata: exit 0 with the lockfile preserved.
- Independent read-only pr-reviewer: no critical findings, warnings, or suggestions.
  The later private route-parser extraction was checked separately and preserved
  the behavior covered by the same 34 source-copy tests.

## Regression and environment history

- Codec source-copy JUnit: 34 tests, three expected failures before correction;
  34 passed after correction and again after the method-length follow-up.
- App baseline with new regressions: 40 tests, six expected failures before F01/F04.
  The same 40 tests passed with the fixes.
- Initial app setup stopped because the pinned upstream NaiveProxy artifact was
  absent. The owning fetch script downloaded arm64-v8a and verified its pinned hash.
- A combined app/static run exhausted local disk and left malformed test output.
  It did not produce a valid final app result. Failed generated test binary and
  execution-history data were preserved in a temporary evidence directory.
- A reused Gradle daemon retained the old history lock. Fresh single-use daemons
  resolved that setup problem without stopping foreign daemons.
- Detekt found the new route guard exceeded the decode method budget. A private
  route-policy parser corrected the violation; no suppression or baseline change.
- The next full app run executed 3487 tests. Its sole failure was SDK fetch setup
  for SettingsPreferencesGroupingTest, with an Operation timed out error. The
  requested API31 artifact subsequently completed, and its SHA512 matched the
  downloaded checksum. No application assertion failed in that run.
- The final full app retry uses the verified cache without changing source,
  repository endpoints, dependencies, or test selection.

The first combined unit command returned exit 1 due to SDK setup; the two
completed data suites remained valid. The final full app command above returned
exit 0 after that environment issue was resolved.

Local diagnostic logs are under `/private/tmp/imp02-*.log`. The codec worker's
source-copy evidence remains in its task-specific temporary directory. These
local diagnostics do not establish Android device or external AWG compatibility.

- A full cached app retry executed 3497 tests; all 41 failures were temporary
  filesystem failures containing `No space left on device`. The same 40 IMP-02
  regression tests remained green. Multiple Robolectric JVMs increased peak
  temporary storage. The final retry passed with `--max-workers=1`, every app
  test and assertion retained, and zero skips. The job native compilation cache
  was not deleted.

## Acceptance boundary

This change corrects five confirmed URI findings. Native AWG packets, JNI,
protobuf, storage schemas, dependencies, locales, golden fixtures, and baseline
files did not change. No physical device, root activation, external peer probe,
or real credential was used. Hosted CI remains a separate acceptance category;
keep the portfolio record in review until its required evidence is available.
