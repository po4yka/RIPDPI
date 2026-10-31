---
id: UIX-1793256927624290
title: Rebuild shared RipDpi components on Material 3
kind: feature
status: review
area: ui
priority: high
owner: UI overhaul
parent: EPC-1793256695505524
blocked_by: []
spec_mode: required
openspec_change: m3-component-facade
created: 2026-10-29
updated: 2026-10-31
---

## Goal

Build every shared RipDpi component on its Material 3 counterpart behind an unchanged facade, and delete the RDS layers that Material 3 now covers.

## Acceptance criteria

- Buttons, icon buttons, chips, text fields, dropdowns, switches, cards, settings rows, dialogs, bottom sheets, snackbars, progress indicators, and tabs use Material 3 components inside their RipDpi facade.
- Public RipDpi signatures, callbacks, semantics, test tags, and automation IDs stay the same, or every call site changes in the same commit.
- RDS state tokens, surface tokens, and the CommonStyle layer are deleted where Material 3 component defaults cover them.
- Custom product components (connection actuator, route diagrams, telemetry plots, log rows) keep their behavior and read Material 3 tokens.
- Stable Material 3 1.5 Expressive components are used where they fit; experimental APIs are not used.

## Ownership

Single writer on `main` unless the execution notes assign separate worktrees. Serialized files follow the epic EPC-1793256695505524.
