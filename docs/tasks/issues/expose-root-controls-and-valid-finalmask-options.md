---
id: UIX-1790339200447975
title: Expose root controls and valid Finalmask options
kind: bug
status: review
area: ui
priority: medium
owner: UI controls
parent: null
blocked_by: []
spec_mode: required
openspec_change: expose-root-and-finalmask-controls
created: 2026-09-25
updated: 2026-09-25
status_detail: Focused root mode and Finalmask tests, app/service lint, and staticAnalysis passed; device and remote CI pending.
---

## Goal

Make root strategies reachable and opt-in from the Settings UI, and show Finalmask only for relay configurations that can save it.

## Acceptance criteria

- Root strategies is reachable while root mode is off; the screen can enable and disable root mode through persisted settings.
- Finalmask controls appear for VLESS or VLESS Reality over xHTTP and Cloudflare Tunnel; unsupported relays can clear an old choice but cannot select one that save will reject.
- Focused UI and persistence tests pass, locale keys remain complete, and app lint passes.

## Ownership

- UI controls writer owns `SettingsPreferenceSections.kt`, `RootModeStrategiesScreen.kt`, `RelayFields.kt`, focused tests, and this task/OpenSpec change.
- UI controls writer owns any new root-mode string keys in locale resources; other concurrent writers use separate worktrees and must rebase before integration.
- `docs/tasks/board.md` and locale resource sets are serialized at integration on `main`.
