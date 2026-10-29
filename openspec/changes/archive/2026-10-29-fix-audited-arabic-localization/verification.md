---
task_id: UIX-1792040423920590
change: fix-audited-arabic-localization
commit_sha: 632f25dc83af9ab13c11665ba0d8b406d7abc6df
local: passed
local_evidence: Final and rebased local gates passed. Test counts, renders, inputs and limits are listed below.
remote_ci: passed
remote_ci_evidence: All seven push workflows passed on source 632f25dc83af9ab13c11665ba0d8b406d7abc6df. CI 38701531805 and ci-required passed. Exact receipts are listed below.
device: not_applicable
device_evidence: JVM behavior and render checks cover this app presentation change. No physical-device or carrier acceptance is claimed.
artifact: not_applicable
artifact_evidence: No signed release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AR-COPY | UIX-1792040522331980 | Resource semantic regressions and Arabic production renders | Passed |
| REQ-AR-HOME | UIX-1792040522854476 | Production Home projection covers all eight groups and raw fallback | Passed |
| REQ-AR-RETAINED | UIX-1792040522854476 | Same raw result follows EN to Arabic to EN | Passed |
| REQ-AR-PARITY | UIX-1792040523369123 | Locale/export, placeholders, lint, Roborazzi and independent review | Passed |

Publication belongs to UIX-1792040523843403. Main integration, push and hosted CI have explicit user authorization. The user approved only `app/src/test/screenshots/com.poyka.ripdpi.ui.screenshot.DiagnosticsMeasurementsScreenshotTest.health_rtl.png` after review. No other golden or quality baseline may change.

## Local evidence

- Baseline resource regressions failed before correction. Thirteen Home behavior tests reproduced the generated-English and retained-locale defects; eleven failed and two preservation controls passed. Both real configuration-screen endpoint tests reproduced reversed numeric IPv6 order under RTL before the direction fix.
- Final unfiltered Full: 2,889 tests, 2,888 passed and one existing ignored RTL gallery case. Final unfiltered Simple: 3,070 tests, 3,041 passed, the same ignored case and 28 existing Full-experience assumptions. There are no failed tests or errors.
- Both Roborazzi tasks passed. The user approved only `DiagnosticsMeasurementsScreenshotTest.health_rtl.png`; its corrected cache-misses caption wraps onto a third line. The isolated record command ran one test and changed one fixture. Other golden and quality baselines stay unchanged.
- Eighteen Arabic PNGs cover Home analysis, Support Settings, pause recovery, DNS counters and both endpoint rows. Normal width and 360 dp at font scale 1.5 are covered. Actual modal text density is asserted. The test helper accounts only for a verified single-line RTL semantics width discrepancy and still checks drawn width, viewport, height, ellipsis and max lines.
- New and existing locale resources pass parity, placeholder, plural and export checks. The 55 new strings and three new plurals are present in all ten locales. Grade adjectives agree with their captions in Arabic, Spanish, French, Portuguese and Russian.
- Android JVM checks use `ripdpi.skipNativeBuild=true` and a verified real Xray AAR. Simple screenshots use an authentic ignored relay bundle recovered from successful CI, not the CI stub. These inputs are not committed. Native and emulator evidence belongs to hosted CI.
- Independent resource, Home, RTL, test and final wrapper reviews found no further actionable issue. Architecture health has no new, worsened or stale indicators; file limits and locked Cargo metadata pass.

The local checks do not claim native-speaker, physical-device, carrier-network or signed-release acceptance.

## Published source evidence

Source `632f25dc83af9ab13c11665ba0d8b406d7abc6df` was fast-forward integrated into main and pushed to origin. All seven push workflows completed with success on 2026-10-15.

- [CI](https://github.com/po4yka/RIPDPI/actions/runs/38701531805): 47 jobs passed, 17 were skipped by existing conditions, and none failed. All five Android API 27/33/35/36/37 emulator jobs, Roborazzi, static analysis, coverage, all three debug builds and all channel Full/Simple release builds passed.
- [i18n locale parity](https://github.com/po4yka/RIPDPI/actions/runs/38701531989).
- [i18n translation-export gate](https://github.com/po4yka/RIPDPI/actions/runs/38701531846).
- [CodeQL](https://github.com/po4yka/RIPDPI/actions/runs/38701531867).
- [Secret Scan](https://github.com/po4yka/RIPDPI/actions/runs/38701531732).
- [harness-checks](https://github.com/po4yka/RIPDPI/actions/runs/38701531636).
- [fleet-fixtures](https://github.com/po4yka/RIPDPI/actions/runs/38701531720).

These receipts apply to the source SHA. The later evidence-only commit does not change production resources, code, tests or fixtures. No physical-device, carrier-network or native-speaker acceptance is claimed.
