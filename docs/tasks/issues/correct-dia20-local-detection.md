---
id: DGN-1794755305945452
title: Correct DIA-20 local detection evidence
kind: bug
status: doing
area: diagnostics
priority: medium
owner: Diagnostics integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-dia20-local-detection
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Correct the eight findings from the DIA-20 audit. Report what the local TLS provider and Android package queries can observe. Preserve incomplete results and protect installed-app data in private exports.

## Acceptance criteria

- Fix F1 through F8 in eight separate code commits with regressions.
- Verify the combined core and app paths, source gates, and independent review before pushing.
- Keep native ClientHello measurements, browser parity, and device acceptance outside this repair.

## Ownership

- tls: F1 and TLS checker/recommendation parts of F2. Own TLS checker, recommendations and their tests.
- integration: remaining F2 presentation and F3/F4 classifier and narrative. Own shared models, Compose, locale and API snapshot paths, planning, validation and integration.
- inventory: F6 and F7. Own package and APK inspection, DirectSigns, direct port wiring and their focused tests.
- export: F5 and F8. Own privacy projection and plain, debug, JSON and Markdown formatters and tests.
- Each writer has a separate worktree. Integration applies the lane commits, reviews the combined tree and owns main and remote publication.
