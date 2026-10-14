---
task_id: UIX-1791912007411833
change: fix-audited-german-localization
commit_sha: 9e5f528486528adc1f8a8129768fdf99b34838ee
local: blocked
local_evidence: Combined-tree checks passed 2521 app tests, 1652 diagnostics tests, 26 resource regressions, static analysis, app/service lint and 297 Full screenshot tests. One existing RTL test is ignored. Simple screenshots require a missing real relay bundle.
remote_ci: required
remote_ci_evidence: Main was published at 7f6ef3c414ebb0ebe06d6979aec30b7add1dfb48 with explicit permission for all 14 Material 3 commits. Seven hosted workflows started; CI run 38508116683 is in progress. No hosted CI pass is claimed.
device: not_applicable
device_evidence: Resource and rendered UI checks own this presentation change; German physical-device acceptance is not claimed.
artifact: not_applicable
artifact_evidence: No signed APK or release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DE-RESOURCES | UIX-1791912114857745 | Exact 112-key audit scope, 26 resource regressions and German renders | Passed |
| REQ-DE-DYNAMIC | UIX-1791912115576158 | Known/unknown captions, readable summary and EN-DE-EN behavior tests in the full app suite | Passed |
| REQ-DE-MEASUREMENTS | UIX-1791912115576158 | Selected app locale, unit thresholds, shared UI/export formatter and Long overflow tests | Passed |
| REQ-DE-PARITY | UIX-1791912117280968 | Ten locales, export, app/core suites, static analysis, app/service lint, Full screenshots and independent review | Passed; Simple input blocked and hosted CI in progress |

## Source and scope

The audit base is `ddd0024d494fb2e734223b8c6e77ec239a255bbe`.
The final combined checks used `9e5f528486528adc1f8a8129768fdf99b34838ee`.
During validation, local main acquired 14 readability commits ending at
`e6b125c8f98cc7ef820d35c600dbbfed972564bc`. The German job was rebased onto
that local main without conflicts. All 25 owned code, resource, manifest
and regression-script files remained byte-identical to the checked German
implementation before the rebase. Combined checks were then repeated.
The checked job branch was integrated into local main with a fast-forward.

The legacy resource diff matches all 112 confirmed keys: 87 English active
keys, 20 semantic active keys and five unused keys. Disputed URI Authority
and Tail wording and optional editorial changes are excluded.

The change adds 38 source strings and one plural resource in all ten
locales. It changes no schema, protocol or dependency. The German change
refreshes no fixture or baseline. Existing upstream readability fixtures
are preserved and checked on the combined tree.

Raw Telegram values, errors and tones remain unchanged. UI and readable
share output use the same locale and bit-rate formatter. Core renderer
defaults preserve technical export headings; the app passes localized headings.

## Observed combined checks

| Gate | Result | Local evidence |
|---|---|---|
| All resource regressions | 26 passed: RU 4, ZH 5, ES 5, HI 6, DE 6 | `combined-resources.log` |
| Locale and settings parity | All ten locales passed | `combined-parity.log` |
| Translation export | 4,996 string keys in sync | `combined-export.log` |
| `:app:testGithubFullDebugUnitTest` | 2,521 tests; zero failures, errors or skips | `combined/app.log`, copied XML |
| `:core:diagnostics:testDebugUnitTest` | 1,652 tests; zero failures, errors or skips | `combined/core-diagnostics.log`, copied XML |
| `staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug` | Passed; no baseline change | `combined/analysis-lint.log` |
| `:app:verifyRoborazziGithubFullDebug` with the complete screenshot package | 297 passed; one existing ignored test | `combined/full-goldens.log`, copied XML |
| Architecture health | 21 existing indicators; no new, worsened or stale entries | `combined-architecture.log` |
| File limits | No violations, exemptions or baseline growth | `combined-file-limits.log` |
| Locked Cargo metadata | Passed | `combined-cargo-metadata.json` |
| Task contracts | 129 tasks, 366 execution steps | `taskctl validate` output |
| Independent source and test reviews | No critical or warning findings | Reviewer reports in this task |

Logs and XML reports are in the ignored
`app/build/de-localization-validation/` directory. The combined runner
records its source SHA in `combined/source-sha.txt` and its four successful
gate exits in `combined/results.json`.

The app has 4,986 strings and 24 plurals; service has 10 strings and one
plural; Quick Connect has 15 separate strings. Format arguments, plural
categories and settings option references/order passed for every locale.
Architecture measured 116 crates, 354 edges, 4,050 source files and 515,617
source lines. File limits scanned 4,164 files.

`RtlVariantGalleryTest.rtlHandshakeTimeline` is the one pre-existing ignored
screenshot test. It is not counted as a pass. No German fixture refresh
was requested or performed.

Gradle checks use one worker, disposable JVM outputs, the verified local
Xray AAR and `ripdpi.skipNativeBuild=true`. No APK was assembled with that
flag. These checks do not establish Android native or German device acceptance.

## Regression and render evidence

Six German resource tests reproduced 107 failures on the audit base.
Three missing CLDR `many` forms in ES/FR/PT-BR were corrected after the
initial combined test exposed them. The targeted Kotlin/Compose run passed
29 app tests and two core tests with zero failures, errors or skips.
The full suites then checked the same cases without test filters.

Initial test imports and off-screen lazy-list lookup were corrected.
Visibility and overflow assertions remain in place. Static analysis first
rejected an 86-line `shareReportLines` method against its 80-line limit.
Three target-selection captions were extracted without a suppression or
baseline change. Independent review confirmed unchanged resources,
arguments, nullable conditions and output order; all final gates passed.

All eight German render tests passed using the actual support, privacy,
update and block-editor components at 420 dp / font scale 1.0 and
360 dp / font scale 1.5. The controls were exercised and each checked
caption had a text layout without visual overflow. Ten PNG captures were
inspected in `app/build/compose-previews/renders/de-*.png`. Long screens
remain scrollable; no unexpected text truncation was observed.

The RDS references are `docs/design/rds/preview/components-settings-row.html`
and `diagnostic-report-summary.html`. German production layout and reference
HTML are unchanged. Native-speaker review remains separate from these checks.

## Simple input boundary

The exact Simple screenshot command failed at `:app:verifyEmbeddedRelayBundle`
before tests: Simple variants require a non-empty
`app/src/simple/assets/embedded-relay-bundle.json`. The real asset is absent.
Its absence was checked again after the upstream readability integration.
A sub-agent also checked the completed readability worktree and found no
bundle there. Its reported Simple results do not supply this missing input.

The prior scoped search found only placeholder CI fixtures, no real bundle
in published or local APKs, and no Terraform/SOPS inputs for the owning
`ripdpi-vpn-deploy` generator. A usable bundle needs deployed relay endpoints
and operator credentials. New production provisioning was not authorized.
No placeholder profile or gate bypass is used as completion evidence.

## Publication boundary

The user explicitly authorized publication of all local main commits,
including the 14 Material 3 commits. The push published all 23 commits at
`7f6ef3c414ebb0ebe06d6979aec30b7add1dfb48`. A server ref check confirmed
that SHA on origin/main. The server accepted the direct push and reported
existing PR, required-check and CodeQL rules as bypassed. No repository
protection setting was changed.

Seven hosted workflows started for that SHA. At the publication check, all
were in progress, including [CI run 38508116683](https://github.com/po4yka/RIPDPI/actions/runs/38508116683).
The publication step is complete. Hosted CI remains pending, and the real
Simple bundle remains absent. A later documentation-only commit records
this evidence; it does not change the code checked at the source SHA above.
