---
id: UIX-1791958951401122
title: Correct audited French localization
kind: bug
status: done
area: ui
priority: high
owner: Localization maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-audited-french-localization
created: 2026-10-14
updated: 2026-10-29
closed_at: "2026-10-29T09:46:20Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Source abb49ce0daca560d6acd7d606d4650f50e44793a on main: 40 resource tests, locale parity, 2546 app tests, 297 screenshot tests, lint, staticAnalysis and architecture health passed; CI 38547366408 and ci-required passed. No physical-device acceptance claimed."
---

## Goal

Correct the audited French copy and display values without changes to raw data or unrelated work.

## Acceptance criteria

All 138 audited resource keys are corrected; release and debug presentation regressions pass; all locale contracts, app/service lint, static analysis, French renders and combined gates pass. Main is fast-forward integrated, pushed and hosted CI succeeds for the source commit.

## Ownership and audit scope

- Resource writer: French audit resources and every locale resource set, translation manifest, provenance and French resource regression script. Dedicated worktree: RIPDPI-fr-resources-20261014. No other writer changes locale files.
- Runtime writer: app presentation source for Live states, unknown network, RTT bands and the debug performance card. Dedicated worktree: RIPDPI-fr-runtime-20261014. No schema, storage, protocol or export changes.
- Test writer: new French behavior and render tests only. Dedicated worktree: RIPDPI-fr-tests-20261014. Golden fixtures are read-only.
- Integration owner: this job worktree, task and specification artifacts, combined gates, independent review and authorized publication to main.
- All writers preserve other agents' work. Cargo.lock, dependency versions, baselines and protocol registries remain outside scope.

The baseline is 3458ed9c1541d9a5217e5c4618845ecefd4a97bd. Correct 138 confirmed resource keys (134 with discovered call sites and four with unverified runtime reachability), three release presentation defects and the debug-only card. Exclude disputed metered/RTT labels and optional vocabulary.

## Observed early evidence

The first Live regression failed for the expected reason: two French stopped labels were expected and zero were found. The run had one test failure, zero errors and zero skips. The baseline source was unchanged. The real Xray AAR passed content-bound provenance and four-ABI verification (SHA256 c5865cc277651bc7964500333566f9c540fe5c4053881aa109785c8d5b24ab35). The combined resource suite passed 40 tests; independent review found no resource or first-cycle runtime defect.

## Behavior regression evidence

Each of the four runtime changes has an observed failure before the production fix and a passing rerun after it. Each failing run has one assertion failure, zero errors and zero skipped tests. The stopped Live badge/headline, generated unknown network, RTT display in Live and History, and French debug prose/decimal formatting are covered. Raw sample values remain unchanged. The debug test also retains expanded state and updates its cached timing summary across English, French and English. The separate initial debug test compile failure was repaired and is not counted as behavior evidence.

Independent final review found no actionable defect in C1 through C4, the 25 Kotlin regression tests, or the resource contracts. Architecture health passed with zero new or worsened indicators (116 crates); file LoC limits passed with zero new or grown exemptions.

## Full test and render evidence

The unfiltered Github Full unit suite passed 2,546 tests with zero failures, errors or skipped tests. It includes all 25 new French tests. All 16 PNG captures were inspected at 420dp with normal text and 360dp with 1.5 font scale. The native font-loader collision did not recur in the standalone rerun. Three existing expectations now check localized lowercase display captions while raw fixtures remain unchanged.

The initial Live overflow oracle produced four false positives. Actual line ends matched Text width (48/64px at normal scale, 72/96px at large scale), while Compose retained a 311–372px paragraph allocation. The final oracle checks every line in visible Text bounds, height overflow, omitted lines and ellipsis. Independent review and the passing full suite confirmed it. No production layout or golden fixture was changed.

## Screenshot comparison evidence

The Github Full Roborazzi comparison gate passed: 297 executed tests, zero failures or errors. The pre-existing RtlVariantGalleryTest.rtlHandshakeTimeline remains ignored because its RTL goldens have not been approved. It is outside this French change and is not counted as executed evidence. No golden fixture was recorded or changed. Local Simple screenshots need an authentic embedded relay bundle, which is absent in this worktree; no CI stub was used.

## Completed local gates

After the scoped Kotlin formatter, staticAnalysis, app Github Full and Simple lint, and service lint all passed. The formatter used the pinned ktlint 1.8.0 and was restricted to 13 owned Kotlin files. Independent review found no actionable issue. The final combined gates were rerun after rebase onto the published main tree. No physical-device or native packet acceptance is claimed.

## Main publication and hosted evidence

Source commit abb49ce0daca560d6acd7d606d4650f50e44793a was fast-forward integrated into main and pushed to origin. All seven workflows passed on this exact source SHA. [CI run 38547366408](https://github.com/po4yka/RIPDPI/actions/runs/38547366408) and ci-required passed: all five API 27/33/35/36/37 emulator jobs, all Full and Simple release builds, Roborazzi, static analysis and coverage succeeded. The existing route conditions skipped 17 unrelated jobs; 47 jobs passed and none failed. Exact workflow links are retained in the change's verification.md.

The implementation and hosted verification are complete. This task stays in review with its records intact. Local Simple screenshots, physical-device and carrier-network behavior, and native-speaker acceptance are not claimed.
