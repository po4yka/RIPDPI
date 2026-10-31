---
id: TST-1793464904072920
title: Refresh sub-threshold stale Roborazzi goldens and tighten the comparison
kind: bug
status: backlog
area: testing
priority: medium
owner: UI overhaul
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-31
updated: 2026-10-31
spec_reason: test-only
---

## Goal

The committed Roborazzi goldens show the UI that the code renders, and a visible UI change fails verification instead of hiding under the compare threshold.

## Context

Both capture helpers compare with `changeThreshold = 0.01F` (`RipDpiScreenshotTestSupport.kt`, `RoborazziCaptureHelpers.kt`), which allows 1% of the pixels to change. On 2026-10-31, during the Material 3 epic, a full `recordRoborazziGithubFullDebug` on m3-screen-layouts changed 283 goldens. About 198 of them differ by more than antialiasing (a 12% gray threshold on the difference image), although verify passed for all of them. Examples:

- the Rule editor goldens still show the bottom Save that moved to the top app bar;
- 55 captures differ only in the top app bar title row (y=24);
- the chip and segmented-button heights changed in the component facade;
- the dropdown chevron changed.

The two Strategy config Persian RTL goldens and the contrast-fix goldens were refreshed then; the rest were not.

## Acceptance criteria

- Every golden that differs from a fresh record by more than antialiasing is reviewed by family and re-recorded with owner approval, or the difference is fixed in code.
- The comparison catches a moved action or a changed control size on a full-screen capture, for example through a lower `changeThreshold`, a per-pixel tolerance with a small allowed count, or region checks. A test or a seeded change shows that the new rule fails where the 1% rule passed.
- Both verifyRoborazzi variants pass locally and in CI after the change.
