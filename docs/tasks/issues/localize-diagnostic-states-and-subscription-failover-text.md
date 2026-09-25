---
id: UIX-1790340279870792
title: Localize diagnostic states and subscription failover text
kind: bug
status: review
area: ui
priority: medium
owner: Localization UI
parent: null
blocked_by: []
spec_mode: required
openspec_change: localize-diagnostic-and-subscription-status
created: 2026-09-25
updated: 2026-09-25
---

## Goal

Localize diagnostic tool state labels and seven subscription server texts in all affected locales.

## Acceptance criteria

- DNS integrity and domain reachability tool cards show localized Idle, Running, Complete, and Failed states.
- Seven subscription server strings near the top of `strings.xml` are translated in `ar`, `de`, `es`, `fa`, `fr`, `ru`, and `zh-CN`.
- All ten locale sets contain any new keys; locale parity, app lint, and focused UI tests pass.

## Ownership

- Localization UI writer owns `DiagnosticsToolsSection.kt`, relevant focused tests, app locale `strings.xml` files, and this task/OpenSpec change.
- Locale resource sets are serialized with DNS and root-mode branches at integration. `docs/tasks/board.md` is generated at integration.
