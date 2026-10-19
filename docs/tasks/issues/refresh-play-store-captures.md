---
id: UIX-1792406373590934
title: Refresh Android sources and Play Store screenshots
kind: chore
status: doing
area: ui
priority: high
owner: Marketing capture integration
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-19
updated: 2026-10-19
spec_reason: tooling-only
---

## Goal

Replace stale Android capture inputs and Play Store images through their owning scripts. Restore the scheduled screenshot source gate without changing its validation.

## Acceptance criteria

- Capture 42 real frames for seven locales and six screens from the current APK under an official emulator lease.
- Keep actual VPN consent, measured Home state and completed diagnostics in the capture path.
- Regenerate the source manifest, English aliases and documentation copies through capture-android.py.
- Pass strict source validation and regenerate all 56 marketing images through capture:prod.
- Review the actual images for layout, clipping, locale text and source freshness.
- Preserve production application source, workflows, Roborazzi fixtures and unrelated work. Limit capture-tool changes to the reviewed framing corrections below.
- Publish the reviewed generated files and inspect exact published push CI and the screenshot workflow.

## Ownership

- Root owns this portfolio record, execution, evidence, combined checks and integration in RIPDPI-diagnostic-progress-feedback.
- Root also owns narrow diagnostic and relay viewport corrections in play-store-screenshots/scripts/capture-android.py, source_captures.py and their focused tests. They use real scrolling to hide preceding partial controls and keep complete target sections visible. Relay gestures use the actual scroll gutter to avoid editable fields. The English relay profile retains the stacked header at font scale 1.5. All seven diagnostic and relay locales require fresh visual review.
- The Chinese relay capture keeps its original display profile and uses the actual content end. It requires two unchanged observations and complete TLS and Advanced bounds. Preceding presets must be whole or fully above the viewport. Failed profile probes and observer results remain separate evidence.
- Root owns Home framing corrections in the same scripts and tests. Home captures must keep actual strategy titles and all measurement captions visible. Root also owns strict backup viewport receipts in validate-source-captures.py. Root also owns narrow DNS and backup framing corrections identified by the complete locale review and their profile documentation in play-store-screenshots/README.md. Application source remains unchanged.
- The device specialist owns only the generated source frames and manifest in play-store-screenshots/public, plus generated source and marketing PNGs in docs/screenshots, in RIPDPI-marketing-current-deps on fix/refresh-play-store-current-deps. The prior writer worktree and commit c66367b12 are preserved as evidence for the prior dependencies.
- The output scope is 147 potential generated paths. Capture scripts define the exact file set. No source hashes are changed by hand.
- The device specialist owns the build-gate and command-scoped official emulator lease during capture. It restores settings and removes only an owned temporary device through the official pool.
- Independent review is read-only. All worktrees and branches are preserved.
- Root owns the marketing generator font fix in src/app/layout.tsx, src/app/page.tsx, capture.mjs, public/fonts, package.json and the host font validation and tests in scripts. The Linux CI artifact showed missing Chinese marketing glyphs on all seven Chinese pages. Use a pinned local font with its redistribution license and a regression gate; add no package dependency. The Android source frames remain unchanged. The device specialist owns subsequent generated marketing outputs after the source fix is committed and reviewed.

- The recovery test worker owns only app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/XrayProviderE2ETest.kt and TestSupport.kt in the isolated RIPDPI-xray-dns-recovery-acceptance worktree on fix/xray-dns-recovery-acceptance. First add safe phase, query and response observations and reproduce the four-case provider suite. Keep all assertions, expected counts, timeout limits and negative bypass checks unchanged until the observed cause supports a reviewed correction. The worker owns the serialized local build and official emulator lease for this diagnosis. Root owns task records and integration. Other agents inspect code and evidence read-only.
- The published API 35 provider gate found DNS SERVFAIL after TCP recovery. This adds test diagnosis to CI acceptance, not a production network change. Production Kotlin, Rust, wire contracts, fixture protocols, dependencies and quality gates remain outside this diagnostic writer scope. A production correction needs a separate design and explicit ownership before editing. The existing Android source capture manifest excludes these test files.

## Scope and evidence

The user approved this extension on 2026-10-19. Scheduled Play Store Screenshots run 39236316569 failed before host rendering because its Android input manifest was captured at revision 79f42770660f7d3dcad89bf914b7345e286ff210. The pre-feature source already differed in 537 input paths; current source differs in 551. This is capture tooling and generated documentation maintenance, with no application behavior change. Actual capture review found a partial preceding diagnostic action at the old fixed scroll position. A fresh complete batch must use the corrected tool; incomplete captures remain diagnostic evidence.

Before integration, origin/main advanced to 99b8b39d9852f37ec40677cf5bb14e7e31c532b1. The version catalog and Rust lockfile changed. Strict source validation rejected the prior capture manifest. Preserve the accepted prior batch and repeat the current APK, source capture and production render gates from the rebased tree. Current native producer artifacts must match the new lockfile and build owners; the previous native donor is not eligible. No freshness, quality or provenance check is relaxed.
