---
id: UIX-1792228010381450
title: Expose recovery actions and simplify mode and history choices
kind: feature
status: doing
area: ui
priority: high
owner: UI action clarity integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: ui-action-clarity
created: 2026-10-17
updated: 2026-10-17
---

## Goal

Make recovery and interface choices explicit while giving history results more space and showing new arrivals.

## Acceptance criteria

- The collapsed Home card exposes the first available recovery action and preserves the highest-priority warning.
- Guided and Advanced have descriptions and selected semantics; opening or cancelling the choice does not save a change.
- History search and active filters stay visible, while full controls use a compact disclosure. Remove-one and clear-all preserve unrelated state.
- The new-event count follows matching newly newest identities, not initial snapshots, backfill, filter changes, or group refreshes. Reader position and manual scroll intent stay intact.
- New resources land in all ten locales and the generated translation manifest.
- Relevant behavior tests, locale lint, static analysis, independent review, and rebuilt-APK emulator checks pass.
- Rebase and combined checks precede ff-only main integration and the explicitly authorized remote push.

## Ownership

- Root owns all locale sets, translation manifest, shared RipDpiTestTags, planning records, combined checks, instrumented acceptance, review fixes, and Git integration.
- Home worker owns HomeAdvisories.kt, HomeNetworkConditionBanner.kt, HomeSubscriptionExpiryBanner.kt, adjacent Home presentation helpers, and focused Home tests in RIPDPI-home-action-clarity.
- Persona worker owns the persona row in SettingsPreferenceSections.kt, a new SettingsPersonaPreference helper, and focused settings tests in RIPDPI-persona-choice.
- History worker owns HistoryFilters.kt, HistorySections.kt, HistoryScreen.kt, HistoryUiModels.kt, HistoryUiStateFactory.kt, adjacent app-only projections, and their focused tests in RIPDPI-history-clarity.
- Each writer uses a separate worktree. Workers send test-only patches for centrally observed RED before production changes. Compiler-backed commands run centrally to avoid duplicate native outputs.
- Review agents are read-only. Baselines, golden fixtures, build logic, native code, persistence, and wire contracts remain outside scope.

## Design references

Use components-warning-banner.html, components-settings-row.html, vpn-quick-vs-advanced.html,
components-filter-bar.html, components-log-stream.html, and components-chips.html. Use the existing
Compose components and DESIGN.md when a deck filename has no exact match.
The compact history disclosure and new-count label are deliberate presentation
changes; no exact RDS full-screen reference exists. Preview sources remain read-only.
