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
spec_reason: docs-only
---

## Goal

Replace stale Android capture inputs and Play Store images through their owning scripts. Restore the scheduled screenshot source gate without changing its validation.

## Acceptance criteria

- Capture 42 real frames for seven locales and six screens from the current APK under an official emulator lease.
- Keep actual VPN consent, measured Home state and completed diagnostics in the capture path.
- Regenerate the source manifest, English aliases and documentation copies through capture-android.py.
- Pass strict source validation and regenerate all 56 marketing images through capture:prod.
- Review the actual images for layout, clipping, locale text and source freshness.
- Preserve application source, scripts, workflows, Roborazzi fixtures and unrelated work.
- Publish the reviewed generated files and inspect exact published push CI and the screenshot workflow.

## Ownership

- Root owns this portfolio record, execution, evidence, combined checks and integration in RIPDPI-diagnostic-progress-feedback.
- The device specialist owns only the generated source frames and manifest in play-store-screenshots/public, plus generated source and marketing PNGs in docs/screenshots, in RIPDPI-marketing-capture-refresh on fix/refresh-play-store-captures.
- The output scope is 147 potential generated paths. Capture scripts define the exact file set. No source hashes are changed by hand.
- The device specialist owns the build-gate and command-scoped official emulator lease during capture. It restores settings and removes only an owned temporary device through the official pool.
- Independent review is read-only. All worktrees and branches are preserved.

## Scope and evidence

The user approved this extension on 2026-10-19. Scheduled Play Store Screenshots run 39236316569 failed before host rendering because its Android input manifest was captured at revision 79f42770660f7d3dcad89bf914b7345e286ff210. The pre-feature source already differed in 537 input paths; current source differs in 551. This is generated documentation and marketing asset maintenance, with no application behavior change.
