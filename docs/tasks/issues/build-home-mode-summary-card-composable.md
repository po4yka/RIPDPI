---
title: Build HomeModeCard composable with toggle and action button variants
type: task
status: backlog
area: ui
priority: high
owner: unassigned
parent: epic-home-config-diagnostics-mode-first-ux
blocks:
  - replace-home-screen-with-three-mode-cards
blocked_by:
  - add-home-mode-state-and-card-ui-models
created: 2026-05-08
updated: 2026-05-08
---

- [ ] #task Build HomeModeCard composable with toggle and action button variants #repo/RIPDPI #area/ui #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `build-home-mode-summary-card-composable`
- **Verify:** `just test-screenshots`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Create the reusable `HomeModeCard` composable that renders a single mode card on the Home screen. Two variants: one with an enable/disable toggle (Local Bypass, VPN), one with a primary action button (Diagnostic). The card body is tappable to navigate deeper.

## Context

All three Home cards share the same visual structure: a title, a one-line config summary, a status chip, and a primary action. Only the action control differs — bypass and VPN get a `Switch`, the diagnostic card gets a filled `Button` labelled "Run scan". The card must show a loading state (indeterminate progress) while the engine is starting or stopping.

## Acceptance criteria

- [ ] `HomeModeCard` composable in `ui/screens/home/components/HomeModeCard.kt`.
- [ ] Parameters:
  - `title: String`
  - `description: String`
  - `primaryLabel: String` — config summary line
  - `secondaryLabel: String?`
  - `isActive: Boolean`
  - `isLoading: Boolean`
  - `actionVariant: HomeModeCardAction` — sealed: `Toggle(checked, onToggle)` or `RunButton(onClick)`
  - `onCardClick: () -> Unit` — fired when the card body (not the control) is tapped
- [ ] Toggle variant: `Switch` aligned to the card trailing edge; disabled and shows `CircularProgressIndicator` when `isLoading`.
- [ ] RunButton variant: full-width `Button` below the summary text; disabled when `isLoading`.
- [ ] Status chip: small pill showing "Active" (green) / "Inactive" (neutral) / "Running…" (yellow) derived from `isActive` + `isLoading`.
- [ ] Card uses `RipDpiTheme` tokens; no hardcoded colors.
- [ ] Roborazzi screenshot baseline for each variant (toggle-off, toggle-on, loading, run-button).
- [ ] Compose semantics: card body has `Role.Button`; toggle has its own merged semantics node.
- [ ] Preview functions for all variants in the file.

## Files likely touched

- New: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/home/components/HomeModeCard.kt`
- New: `app/src/test/…/HomeModeCardTest.kt` (Roborazzi baselines)

## Links

- [[Epic - Redesign Home / Config / Diagnostics tabs for mode-first UX]]
