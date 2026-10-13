---
task_id: UIX-1791912007411833
change: fix-audited-german-localization
commit_sha: baead0809ff83287df0f12678dc7042c5e2358db
local: blocked
local_evidence: Resource and parity checks, 31 targeted tests, 2504 app tests, 1652 diagnostics tests and 285 Full screenshots passed. Final tests and lint are queued after the LongMethod fix. Simple screenshots require a missing real relay bundle.
remote_ci: required
remote_ci_evidence: Main integration and push are pending local validation.
device: not_applicable
device_evidence: Resource and rendered UI checks own this presentation change; physical-device acceptance is not claimed.
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
| REQ-DE-DYNAMIC | UIX-1791912115576158 | Known and unknown status, readable summary and EN-DE-EN tests | Passed before final method extraction; full retry pending |
| REQ-DE-MEASUREMENTS | UIX-1791912115576158 | Selected app locale, unit thresholds and Long overflow tests | Passed |
| REQ-DE-PARITY | UIX-1791912117280968 | Ten locales, export, app/core tests, Full screenshots and independent review | Android lint and final combined retry pending; Simple input blocked |

## Source and scope

The audit base is `ddd0024d494fb2e734223b8c6e77ec239a255bbe`.
The implementation and regression tests were committed at
`4869735b4313b784eb22ac182fa62eddb1d6f26b`. Test API and lazy-list
corrections were validated at `d4e1473758306d64e69e2fe1030038d14bea7852`.
The final production extraction is `baead0809ff83287df0f12678dc7042c5e2358db`.

The legacy resource diff matches all 112 confirmed keys: 87 English active
keys, 20 semantic active keys and five unused keys. Disputed URI Authority
and Tail wording and optional editorial changes are excluded.

The change adds 38 source strings and one plural resource in all ten
locales. It changes no schema, protocol, dependency, fixture or baseline.
Raw Telegram values, errors and tones remain unchanged. UI and readable
share output use the same locale and bit-rate formatter. Core renderer
defaults preserve technical export headings; the app passes localized headings.

## Observed checks

- Six German resource tests reproduced 107 failures on the audit base.
  After correction, all 26 regressions passed: RU 4, ZH 5, ES 5, HI 6 and DE 6.
  Three missing CLDR `many` forms in ES/FR/PT-BR were corrected after the
  initial combined test exposed them. No test gate was weakened.
- Locale parity, settings option references/order, format arguments, plural
  categories and translation export passed. App: 4,986 strings and 24 plurals;
  service: 10 strings and one plural; Quick Connect: 15 separate strings.
  The manifest has 4,996 string keys.
- Targeted Kotlin and Compose tests passed: 29 app tests and two core tests,
  with zero failures, errors or skips. The initial unsupported imports and
  off-screen lazy-list lookup were corrected. Visibility and overflow
  assertions remain in place.
- The first unfiltered app suite passed all 2,504 tests; the first unfiltered
  core/diagnostics suite passed all 1,652 tests. Both had zero failures,
  errors and skips.
- Full Roborazzi verification passed 285 tests with no fixture changes.
  `RtlVariantGalleryTest.rtlHandshakeTimeline` is the one pre-existing
  ignored test; it is not counted as a pass.
- Static analysis first rejected an 86-line `shareReportLines` method
  against its 80-line limit. Three target-selection captions were extracted
  without a baseline or suppression change. Independent review confirmed
  unchanged resources, arguments, nullable conditions and output order.
  The final complete app/core suites, static analysis, app/service lint and
  Full Roborazzi checks are queued for that committed source.
- Resource regressions, parity/export, architecture and file limits were
  repeated after the extraction and passed. Architecture: 21 existing
  indicators, no new, worsened or stale entries, 116 crates, 354 edges,
  4,049 measured source files and 515,419 source lines. File limits: 4,163
  scanned files, no violations or baseline growth. Locked Cargo metadata
  passed. Task contracts passed for 129 tasks and 365 execution steps.
- Independent production and test-harness reviews found no critical or
  warning findings. Commit hooks passed.

Gradle checks use one worker, disposable JVM outputs, the verified local
Xray AAR and `ripdpi.skipNativeBuild=true`. No APK was assembled with that
flag. These checks do not establish Android native or physical-device acceptance.

Logs and XML reports are in the ignored
`app/build/de-localization-validation/` directory; final retry reports are
in its `final/` subdirectory. Each runner records the tested source SHA.

## German renders

All eight render tests passed using the actual support, privacy, update and
block-editor components at 420 dp / font scale 1.0 and 360 dp / font scale 1.5.
The controls were exercised and each checked caption had a text layout
without visual overflow. Ten PNG captures were inspected in
`app/build/compose-previews/renders/de-*.png`. Long screens remain scrollable;
no unexpected text truncation was observed.

The RDS references are `docs/design/rds/preview/components-settings-row.html`
and `diagnostic-report-summary.html`. Production layout and reference HTML
are unchanged. Native-speaker review is still separate from these checks.

## Simple input boundary

The exact Simple screenshot command failed at `:app:verifyEmbeddedRelayBundle`
before tests: Simple variants require a non-empty
`app/src/simple/assets/embedded-relay-bundle.json`. The real asset is absent.

The prior scoped search found only placeholder CI fixtures, no real bundle
in published or local APKs, and no Terraform/SOPS inputs for the owning
`ripdpi-vpn-deploy` generator. A usable bundle needs deployed relay endpoints
and operator credentials. New production provisioning was not authorized.
No placeholder profile or gate bypass is used as completion evidence.
