---
id: UIX-1790339952138380
title: Keep navigation reachable and announce selected DNS option
kind: bug
status: review
area: ui
priority: medium
owner: Accessibility UI
parent: null
blocked_by: []
spec_mode: required
openspec_change: navigation-and-dns-selection-accessibility
created: 2026-09-25
updated: 2026-09-25
status_detail: Short-landscape navigation and DNS selected semantics tests, app lint, and staticAnalysis passed; device and remote CI pending.
---

## Goal

Keep every navigation rail destination reachable on short screens with large text and announce which DNS option is selected.

## Acceptance criteria

- Users can scroll the rail to every destination at 2x font scale in short landscape height.
- Screen readers receive selected state for DNS option cards.
- Focused Compose accessibility tests and app lint pass.

## Ownership

- Accessibility UI writer owns `RipDpiNavRail.kt`, `DnsSettingsCards.kt`, focused tests, and this task/OpenSpec change.
- `DnsSettingsCards.kt` is serialized with the DNS protocol work; DNS writer confirmed no edits there. `docs/tasks/board.md` is generated at integration.
