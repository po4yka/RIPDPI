# Final localization audit remediation

Input: final audit of `3c1df4f9ce2d71733ef7e8ea4745470d0efb3dd0`, 2026-10-15.
Task: UIX-1792080010916230.

## Scope

Correct the 17 confirmed groups. The resource audit found 69 unique locale/key
pairs. These pairs overlap the groups and must not be added as separate defects.
Keep all 10 locales in parity. All production code changes are in the app
presentation module. Do not change raw diagnostic records, storage, exports,
protocol identifiers, endpoints, user profile names, or share URIs.

## Corrections and regression coverage

| Group | Correction | Main regression coverage |
|---|---|---|
| F01 | Build the setup sheet from retained profile data at render time. Copy and share use that same current text. | `ProfileShareLocaleTest` |
| F02 | Resolve known generated verdicts, score labels, signal reasons, hints, and recommendations when rendering. Preserve unknown text and destinations. | `DetectionGeneratedLocaleTest`, including evidence, category fallbacks, generic review, and custom text |
| F03 | Translate known bundled profiles only when source, ID, and canonical name match. | `DiagnosticsGeneratedLocaleTest`, all 16 catalog entries and custom guards |
| F04 | Translate active offline, scope, and concurrency diagnoses at the UI boundary. Keep raw evidence and custom advice. | `DiagnosticsGeneratedLocaleTest` |
| F05 | Translate known diagnostic progress messages and phase labels. Preserve custom profile names and phase IDs. | `DiagnosticsGeneratedLocaleTest` |
| F06 | Translate the three known handover classifications. Preserve unknown classifications. | `DiagnosticsGeneratedLocaleTest` |
| F07 | Correct AR/ZH comparison and reference-group terms. | `test_final_localization_regressions.py` and resource review |
| F08 | Use French retry terminology for the actual retry counter. | Resource regressions and review |
| F09 | Translate active RU Updates and ES Strategy packs headings. | Resource review and locale checks |
| F10 | Use complete DNS answer object phrases. Keep generic compression captions in their original context. | `HomeDnsAnswerGrammarRegressionTest`, both answer qualities in all 10 locales |
| F11 | Use count labels in Home fragments and DNS rationale. Labels work at zero, singular, and other quantities. | Home DNS tests and resource regressions |
| F12 | Use a count label for minutes remaining. | English count boundary test and all-locale resource regressions |
| F13 | Use count labels for log entries and captured packets. | English count boundary test and all-locale resource regressions |
| F14 | Translate the known unavailable sentinel in five context fields. Preserve real host and provider values. | `DiagnosticsGeneratedLocaleTest` |
| F15 | Resolve Full QuickTile text through the selected app language on legacy Android. | `QuickTileLocaleTest`, SDK32, frozen English service context, nine app languages and system reset |
| F16 | Save notice resource IDs and raw dynamic messages separately. Resolve translated text at render time. | `AdvancedNoticeLocaleTest`, saved-state restoration and raw-message preservation |
| F17 | Retain built-in export error resource IDs and resolve them at render time. Preserve prepared documents. | `ExportPreviewSheetTest`, `ExportPreviewViewModelTest` |

`AppLanguageTransitionRegressionTest` also checks all 90 directed language
transitions, ten resets to the system language, and ten saved selections after
an AndroidX cache reset. These are JVM checks. They do not replace Android UI or
real SystemUI checks.

The resource change adds 111 keys in each locale: two complete DNS answer
phrases and 109 generated diagnostic messages. Of the 69 audited locale/key
pairs, 68 source entries change. The pt-BR generic compression caption stays
unchanged; its incorrect use in DNS is removed by F10.

## Verification evidence

- DNS grammar RED: the new German sentence test failed at its noun-form check on
  the input implementation. GREEN: the same test passed after the resource and
  call-site correction.
- Locale parity and translation export: PASS.
- Python localization regressions: 76 tests, PASS.
- Architecture health: 21 existing indicators, no new or worsened indicator.
- Locked Cargo metadata: PASS; no Rust source or dependency change.
- Unfiltered app JVM tests: Full 2631 and Simple 2800, all 5431 PASS, no failures,
  errors, or skipped tests. Seven new regression classes are present in each run.
- App lint Full/Simple, service lint, and full static analysis: PASS. These local
  gates use Gradle offline mode with cached dependencies. The first online lint
  attempt waited on Google Maven version metadata; hosted CI checks online.
- The first combined compile found three old test constructor calls. The next
  run found two test assumptions and one detekt return-count violation. The
  corrected tree passed the full combined run. No baseline or gate was relaxed.
- Roborazzi verification Full and Simple: PASS, 578 executed checks. The 30
  existing skips consist of 28 Full-only preview assumptions in Simple and two
  occurrences of the ignored RTL gallery class (one per variant). The ignored
  gallery is not visual evidence. No golden or baseline file changed.
- Full and Simple ARM64 debug APK builds: PASS with genuine native inputs and
  the verified Simple bundle. These APKs were built from the final source tree
  before its commit. No native-build skip was used for the APK builds.
- Android API37 emulator: all ten languages in both variants passed current
  resource checks and process-restart persistence. Full uses the in-app picker;
  Simple uses the Android app-locale framework. Both use the documented debug
  data preset. These checks do not prove VPN or network service operation.
- Both variants follow system Russian after the app override is cleared. UI
  snapshots for all ten languages and DE/AR/FA at 150% font were inspected.
  No text overlap or unexpected truncation was seen in the captured viewports.
  Full settings remain scrollable; these captures do not prove every off-screen
  row. Device language, font, and both app overrides were restored. The pool
  command returned zero and released its lease.
- Local logs, JUnit XML, APK hashes, UI XML, and screenshots are retained in
  `/tmp/ripdpi-final-remediation-20261015/`. Hosted CI results are listed below.

## Review and limits

One resource writer owns every locale XML file and the translation manifest.
Other workers supply code or translation tables in separate worktrees. A
read-only reviewer checked producer mappings and preservation of raw/custom
data. No confirmed source defect remained in that review. A separate semantic
review corrected HI/ZH CDN request terminology against the current checker.

This work does not claim native-speaker approval of every old sentence,
physical-device acceptance, or a real legacy Android SystemUI session. A UI
render must be inspected before it is used as visual evidence. Golden changes
require approval for their exact fixture paths.

## Publication

Source commit: `3925a43bdb0c380e7a48e68ed18ea8c3e67e79ac`.
The job branch was rebased on `origin/main`. Combined JVM, lint, static analysis,
Roborazzi, locale, architecture, and locked metadata gates passed again. Main
was updated by fast-forward and pushed to origin.

All seven workflows for this exact source SHA completed with success:

- [CI and ci-required](https://github.com/po4yka/RIPDPI/actions/runs/38773292352)
- [Locale parity](https://github.com/po4yka/RIPDPI/actions/runs/38773292199)
- [Translation export](https://github.com/po4yka/RIPDPI/actions/runs/38773292484)
- [CodeQL](https://github.com/po4yka/RIPDPI/actions/runs/38773292355)
- [Secret Scan](https://github.com/po4yka/RIPDPI/actions/runs/38773292307)
- [Harness checks](https://github.com/po4yka/RIPDPI/actions/runs/38773292247)
- [Fleet fixtures](https://github.com/po4yka/RIPDPI/actions/runs/38773292546)

The main workflow has 47 successful jobs and 17 jobs skipped by its normal
routing rules. Successful jobs include app JVM tests, static analysis,
Roborazzi, Kotlin/Rust coverage, all ABI packaging, Xray-linked tests,
instrumentation on API27/33/35/36/37, and debug and Full/Simple release builds
for GitHub, F-Droid, and Play. No workflow rerun was needed.

The follow-up commit records this observed evidence and closes the portfolio
record. It changes documentation only. Its own routed CI must pass after push;
the full source verification above remains tied to the source SHA.
