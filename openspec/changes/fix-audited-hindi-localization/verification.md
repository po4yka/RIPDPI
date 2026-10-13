---
task_id: UIX-1791880509070975
change: fix-audited-hindi-localization
commit_sha: c9cc6ad417d3503a135b12b39f4d19b131aec822
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: Local rendered UI and resource checks own this presentation change. Physical-device acceptance is not claimed.
artifact: not_applicable
artifact_evidence: No signed APK or release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HI-MEANING | UIX-1791880749935501 | Hindi resource regressions and inspected UI | Required |
| REQ-HI-RESULTS | UIX-1791880769991455 | Known-result and option behavior tests | Required |
| REQ-HI-DATES | UIX-1791880789607066 | Host-pack fallback and selected-locale language-switch tests | Required |
| REQ-HI-PARITY | UIX-1791880815019998 | Parity/export, all-locale contracts, full app/service tests, lint, static analysis, architecture, and independent review | Required |

## Boundaries

No protocol, schema, raw evidence, stored value, dependency, or quality-baseline changes. One Arabic metric screenshot fixture refresh is authorized to match the earlier selected-locale number-formatting correction. Hosted CI, native-speaker review, and physical-device acceptance are separate evidence.

## Scope and source review

The source commit is `c9cc6ad417d3503a135b12b39f4d19b131aec822`. It follows
`git fetch origin` and `git rebase origin/main`; the upstream source was
`17172d4a1fe3bfc02faf6366efad868da2ad3dce` and no rebase was needed.

- Correct 70 existing Hindi entries, including seven unused entries.
- Correct 42 stale profile-editor references in six other locales.
- Translate 46 descriptive option positions. Preserve 13 technical choices,
  all array positions, and all 15 stored-value arrays.
- Add 45 string keys in all ten locales. Localize known DPI, PT, TCP16,
  host-pack, duplicate, and sequential captions.
- Update known PT text and catalog dates after a selected-language change.
  Preserve raw evidence, custom descriptions, and custom PT captions.
- Read Android numeric array values with typed resources and recycle the
  typed array. Stored values zero through three remain exact.

An independent review found no critical or warning findings. A separate
completeness pass found no missing confirmed findings in the session audits.
Previous Russian, Chinese, and Spanish correction commits remain ancestors
of the source baseline and are not undone by this change.

## Observed local evidence

On the synchronized source commit:

- `python3 -m unittest discover -s scripts/ci -p 'test_*localization_regressions.py'`:
  20 tests passed (Russian 4, Chinese 5, Spanish 5, Hindi 6).
- `bash scripts/ci/check-locale-parity.sh`: all ten app, Quick Connect, and
  service locales passed; translated option references and order passed.
- `bash scripts/ci/check-translation-export.sh`: source manifest passed,
  4,958 string keys. App resources have 4,948 strings and 23 plural entries.
- `python3 scripts/ci/check_architecture_health.py`: passed, 21 baseline
  indicators and no new, worsened, or stale indicators; 116 Rust crates.
- `python3 scripts/ci/check_file_loc_limits.py`: passed, 4,156 files,
  no new violations or baseline growth.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1`:
  passed with the pinned workspace.
- `./taskctl validate`: passed, 128 tasks and 361 execution steps.
- Pre-commit formatting, workflow pins, secret/size checks, harness checks,
  staged architecture delta, and Conventional Commit check passed.

Earlier targeted tests exposed two issues before the final run. A test-only
bare Text harness overflowed; it now renders the actual production dropdown
and keeps the overflow assertion. Android `getStringArray` returned null for
numeric options; the production reader now uses `TypedArray.getString`.
The subsequent retained-PT, catalog-date, and dropdown tests passed; the
final numeric-reader rerun passed with the other targeted tests. Full
Android gates remain required below.

## Render inspection

Native Compose test renders were inspected at font scales 1.0 and 1.5.
Hindi host-pack descriptions, duplicate/sequential dropdowns, TCP16 captions,
DPI failure text, and retained PT summaries/verdicts have readable glyphs and
no observed clipping. Renders are local disposable outputs under
`app/build/compose-previews/renders/`; these render checks did not change golden fixtures.

## Remaining gates

The final targeted run passed: 32 tests, zero failures, errors, or skips.
The first full app run executed 2,483 tests, with 628 failures: one
`FileSystemAlreadyExistsException`, 451 later `UnsatisfiedLinkError`
failures, and 176 later `UncaughtExceptionsBeforeTest` failures. The first
failure was in the existing `DiagnosticsRouteTest`; the same Robolectric
ZIP/native-renderer cascade was recorded in the earlier Chinese verification.
No code change, test exclusion, or baseline update was used for this failure.
A fresh standalone full app run is required, followed by service tests,
static analysis, lint, and existing Full/Simple Roborazzi verification.
Hosted CI remains a separate required gate.

Three disputed Hindi DNS terms (Authoritative/AA, Truncated/TC, and
Truncated response) remain unchanged. They are not confirmed defects.
Native-speaker sign-off and physical-device acceptance are not claimed.

## Completed initial gates and pending integration

The fresh standalone app retry passed: 2,483 tests, zero failures, errors,
or skips. `staticAnalysis`, `:app:lintGithubFullDebug`, and
`:core:service:lintDebug` passed on the initial source tree. Static analysis
also built and checked the Simple variant; the command completed in 40m10s.

Two complete service runs each executed 2,230 tests with one failure and
no errors or skips. The first failed the payload-ladder `result.passed`
assertion; the second failed `result.succeeded` in the UDP question-entropy
case. The first failing test passed on the second run. Both tests and their
probe source were unchanged by this localization patch. Real-socket timing
is a possible cause, not an established cause. No tests were excluded or
changed. A final full service check is required on the rebased tree.

Full screenshot verification executed 286 cases: 284 passed, one failed,
and one intentional flavor-assumption skip. `metricMeaningsRtl` differs
from its expected fixture by 8.005 percent. TX/RX decimals now use Arabic
digits, as required by the earlier Spanish locale-formatting correction
`aa28e5aaa`. The fixture still has Latin digits. The render has no observed
clipping; the lower cards move by about 10 px. The image is 420 by 1,500 px
in both versions. Simple screenshot verification was not reached.

The only proposed fixture update is
`app/src/test/screenshots/com.poyka.ripdpi.ui.screenshot.DiagnosticsMeasurementsScreenshotTest.metric_meanings_rtl.png`.
Expected, actual, and compare images were inspected. The user explicitly authorized this one fixture on 2026-10-13 under the
golden-bless rule. Its narrow record and owning-class verification are
assigned to the isolated golden-blesser worktree.

`origin/main` advanced to `287a7962e5f6832fdd44ff4709a284989ef47617` while
validation ran. These unrelated service, native, build, and emulator changes
must be preserved by rebase. Combined-tree gates must be rerun before main
integration. Initial results above are evidence for the old source tree only.

## Rebase evidence

The task branch was rebased without conflicts onto upstream
`287a7962e5f6832fdd44ff4709a284989ef47617`. The localization source is now
`880ad286f4ad50d7b567e46bd47ef2dd4fcf72eb`. An independent review confirmed
that all 16 upstream commits remain ancestors and all task-slice files match
the pre-rebase contents. Resource regressions (20 tests), all-locale parity,
translation export, architecture health, file limits, locked Cargo metadata,
and task contracts passed again. Architecture has 21 baseline indicators,
116 crates, 354 edges, and no new or worsened indicators. File limits scanned
4,162 files with no violations or baseline growth.
