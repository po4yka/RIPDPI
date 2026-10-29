---
task_id: UIX-1792059951283875
change: fix-audited-persian-localization
commit_sha: 73dc62806735a11e7b62fa46cdfebb1e1db01fca
local: passed
local_evidence: Final and rebased gates passed. Exact test counts, render checks, inputs and limits are listed below.
remote_ci: passed
remote_ci_evidence: All seven push workflows pass on source 73dc62806735a11e7b62fa46cdfebb1e1db01fca. CI 38733825932 and ci-required pass. Exact receipts are listed below.
device: not_applicable
device_evidence: JVM behavior and production render checks cover this presentation change. Physical-device and carrier acceptance are separate.
artifact: not_applicable
artifact_evidence: No signed release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-FA-COPY | UIX-1792060147847428 | Exact 121-key audit set, 72 resource tests and real Support/Pause renders | Passed |
| REQ-FA-STRATEGY | UIX-1792060148876253 | Five behavior tests, four production render tests, raw grouping and retained locale projection | Passed |
| REQ-FA-HOME | UIX-1792060150290271 | Nine behavior tests, two production render tests and frozen MainViewModel locale refresh | Passed |
| REQ-FA-PARITY | UIX-1792060152291884 | Full/Simple tests, all locale/export contracts, lint, static analysis, Roborazzi, architecture, Cargo and review | Passed |

Publication belongs to UIX-1792060153763952. Main integration, push and hosted CI are explicitly authorized. No golden or quality baseline changed. The confirmed audit source is a6b286e21ebf52d4d8901c63481b304122a7556d. Disputed terms, inactive resources, unused stageAnnouncement and unrendered stage.headline remain outside this correction.

## Local evidence

- All 121 changed existing Persian keys exactly equal the confirmed audit set; no audited key is missing and no extra existing key changed. New presentation resources contain 194 strings and four plurals in all ten locales. Native language names, argument signatures, XML types and quantity categories pass the resource contracts.
- The eight new Python resource tests reproduce 103 failures on the audit source. All 72 resource regression tests pass after correction. The baseline JVM run has 13 tests: 12 expected failures and one raw-preservation control passes. The failures expose English copy, generated presentation and altered unknown identifiers.
- Final unfiltered app unit tasks pass on source 73dc62806735a11e7b62fa46cdfebb1e1db01fca: GithubFull has 2,615 tests and GithubSimple has 2,784 tests, with zero failures, errors or skips. This includes all 24 new behavior and production render tests and the expanded MainViewModel retained-state test.
- Both owning Roborazzi tasks pass. GithubFull has 298 screenshot tests with one existing ignored RTL gallery case. GithubSimple has 310 tests with that same ignored case and 28 existing Full-experience conditions. There are no new skips, failed comparisons or fixture updates. All five Full Persian locale screenshots pass.
- Real production Home, Approach, Strategy, Support Settings and pause-recovery scenes cover normal width and 360 dp at font scale 1.5. Root inspected normal and narrow images. Tests check actual modal density, viewport and glyph bounds, height, ellipsis, scroll access and raw callback identifiers. The changed security warning preserves credential and trust terms. Clock recovery names the visible Resume now action and both recovery callbacks pass.
- A test-only Strategy render correction scrolls nested containers from outer to inner. For an unbounded LazyRow semantics paragraph only, it measures the same text, style, font resolver, density and direction at its real drawing width; line count and drawn height must stay equal. The existing RTL clipping assertions stay active. Independent review checked the exact Compose 1.12.1 source that rebuilds semantics at infinite width while drawing at the measured size. No production layout or shared assertion changed.
- App Full lint, app Simple lint, service lint and staticAnalysis pass. Architecture has 21 baseline indicators and zero new, worsened or stale indicators. File limits, locked Cargo metadata, locale parity, settings-label order, translation export, task contracts and strict OpenSpec validation pass on the rebased tree.
- The first static analysis run found 18 new detekt issues. The owners fixed five Kotlin files without suppressions, rule changes or baseline edits. Independent review confirms argument order and arity, raw fallback and all 65 assertions in five Strategy tests. A Java probe confirms identical match status and capture values for 27 pattern pairs and 37 sample values.
- JVM checks use ripdpi.skipNativeBuild=true, a verified real Xray AAR and an authentic ignored Simple relay bundle. Both input verifiers pass. These inputs are not committed. Local JVM checks do not claim a native rebuild, emulator acceptance or a signed release.
- Independent source, resource, render-harness and quality-remedy reviews report no remaining actionable finding. Confidence colors use a typed grade; retained EN to FA to EN and AR to FA to EN preserve the same run and raw evidence. Raw IDs, custom names, protocols, endpoints, producer records, storage and export formats stay unchanged.

These checks do not claim native-speaker, physical-device or carrier-network acceptance.

## Published source evidence

Source 73dc62806735a11e7b62fa46cdfebb1e1db01fca contains the localization and quality commits. It was fast-forward integrated into a clean main and pushed to origin. Main and origin/main match. All seven push workflows completed with success on 2026-10-15.

- [CI](https://github.com/po4yka/RIPDPI/actions/runs/38733825932): 47 jobs pass, 17 are skipped by existing conditions and none fail. All five Android API 27/33/35/36/37 emulator jobs, native packaging, JNI, Roborazzi, static analysis, coverage, three debug builds and all channel Full/Simple release builds pass.
- [CodeQL](https://github.com/po4yka/RIPDPI/actions/runs/38733825689).
- [Secret Scan](https://github.com/po4yka/RIPDPI/actions/runs/38733825894).
- [fleet-fixtures](https://github.com/po4yka/RIPDPI/actions/runs/38733825923).
- [harness-checks](https://github.com/po4yka/RIPDPI/actions/runs/38733825942).
- [i18n locale parity](https://github.com/po4yka/RIPDPI/actions/runs/38733825833).
- [i18n translation-export gate](https://github.com/po4yka/RIPDPI/actions/runs/38733825691).

These receipts apply to the source SHA. The later evidence-only commit changes task status and verification text. It does not change production code, resources, tests or fixtures. CI on the final main must also finish successfully. No physical-device, carrier-network, native-speaker or signed-release acceptance is claimed.
