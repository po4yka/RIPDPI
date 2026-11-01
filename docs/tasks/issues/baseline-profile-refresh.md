---
id: AND-1793534742072614
title: Regenerate the baseline profile after the Material 3 overhaul
kind: chore
status: backlog
area: android
priority: low
owner: UI overhaul
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-01
updated: 2026-11-01
spec_reason: tooling-only
---

## Goal

`app/src/main/baseline-prof.txt` lists classes and signatures that the Material 3 overhaul deleted or renamed. Examples: `RipDpiButtonStateTokens`, `RipDpiControlStateTokens`, `ripDpiStyleContent`, `RipDpiStyleStateKeys` `getLoading` and `getCompact`, the old density signatures, and `ripDpiExtendedColors(ColorScheme;Z)`. Stale rules match nothing, so they add size and hide the real hot paths of the new UI.

The goal is a profile that lists only classes and methods that exist. Regenerate it through its owning generator on a device or emulator. Never edit the file by hand.

## Ownership

`app/src/main/baseline-prof.txt` is a generated artifact. The generator lives in the `:baselineprofile` module. One writer. Use a leased emulator slot from `docs/contributor/android-emulator-pool.md` for the run.

## Acceptance criteria

- The regenerated `baseline-prof.txt` contains none of the deleted or renamed symbols named in the goal.
- The profile was produced by the generator on a device or emulator, not by hand. The commit message names the generator and the device.
- `./gradlew assembleRelease` still builds with the new profile.
