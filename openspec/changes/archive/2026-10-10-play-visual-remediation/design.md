## Context

See proposal.md and the visual audit. Existing route navigation can reach the required settings. Capture is a real device operation on a dedicated emulator.

## Goals / Non-Goals

- Goal: resolve all eleven visual finding groups with real feature frames, clear copy and verified exports.
- Non-goal: change native network behavior or publish to Google Play.

## Decisions

- Use a dedicated emulator, real connection and completed diagnostics where measured data are shown. Existing live service mode is allowed; simulated connected presets and demo results are excluded.
- Capture complete frames at a documented physical size suitable for legible store layouts. Preserve source pixels and explain any detail view with explicit source bounds.
- Localize existing generic UI strings across all ten app locales. Preserve catalog IDs, protocol tokens and user-defined names.
- Bundle a licensed, pinned Persian font and retain monochrome brand tokens.
- Keep existing export formats, sizes and file names. Separate README layout from Play file dimensions.

## Contracts and ownership

- Capture worker: all app locale resources and minimal Kotlin presentation mapping needed for generic labels; play-store-screenshots/scripts capture/source validators/tests; public/screenshots raw images and manifest; docs/screenshots/ui raw copies. Own all serialized locale sets. No production network or storage contract edits.
- Layout worker: play-store-screenshots/src, public/fonts and font license, capture.mjs, renderer README, package scripts only if needed, generated marketing PNGs. No raw capture or app edits.
- Integration owner: all nine README galleries, generator AGENTS.md and screenshot skill factual guidance, task and OpenSpec records, combined validation and authorized main integration/push.
- Reviewers: read-only. No Rust crates, shared dependency locks, baselines or golden fixtures change.

## Risks / Trade-offs

- Capture inputs depend on app text: integrate app changes before the final capture and render.
- Internet diagnostics may fail: preserve observed results and show useful classification rather than claiming universal success.
- A state warning must not be hidden: choose a complete meaningful viewport, not painted pixels.
- Large overlays may exceed text limits: enforce geometry and independent thumbnail review.

## Migration Plan

No persisted schema migration. Rebuild the APK, capture all source frames, validate source integrity, render all outputs, inspect full and thumbnail sizes and update READMEs. Rollback is a normal source commit revert followed by recapture, with no device or user data migration.

Gates: affected app unit tests; app/service locale lint; source-capture tests and validator; production renderer; strict checks for 56 PNG files; 49 locale layout checks; browser JPEG smoke; nine README selectors; harness-check; architecture-health; locked Cargo metadata; independent source and visual review. Hosted CI is recorded separately; no deployment or Play upload is owned.
