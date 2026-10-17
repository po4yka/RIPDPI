---
id: UIX-1792213600805378
title: Compact the Simple profile catalog and make empty history useful
kind: feature
status: doing
area: ui
priority: high
owner: Catalog and history UI team
parent: null
blocked_by: []
spec_mode: required
openspec_change: compact-catalog-history
created: 2026-10-17
updated: 2026-10-17
---

## Goal

Show profiles before optional check setup, and give each history state a correct next action.

## Acceptance criteria

- A populated Simple catalog shows a profile in the first compact normal-font viewport.
- Search, selection, measurements, favorites, check-only, check-and-select and cancel stay usable.
- Cleanup and failure recovery remain visible even when optional controls are collapsed.
- Genuine empty history opens Connection or Diagnose without starting an operation.
- Filtered-empty history offers Clear filters and never presents a misleading Connect action.
- Narrow layouts, font scale 2.0, both themes and RTL preserve labels and touch targets.
- Targeted behavior tests, theme checks, lint, static checks and affected screenshots are observed.
- Real Full/Simple APKs are inspected on an exclusively leased emulator.
- Review, rebase, combined checks, ff-only main integration and authorized remote push are observed.

## Ownership

- Root integration: every locale resource, shared test tags, RipDpiNavHost callsite,
  task/OpenSpec records, build and combined checks, translation export and Git integration.
- Catalog worker in RIPDPI-simple-catalog-compact: ProfileUtilityScreen.kt, new
  profile presentation helpers, ProfileUtilityTestTags.kt and catalog UI tests.
  It may update behavior tests in a screenshot file, but no PNG or screenshot record.
- History worker in RIPDPI-history-empty-actions: ui/screens/history files,
  HistoryUiModels.kt, HistoryUiStateFactory.kt and related behavior/route tests.
  No RipDpiNavHost or shared tag edits.
- Device specialist: temporary harness/evidence and exclusive emulator leases only.
- Independent reviewer: read-only source and evidence review. Golden changes,
  if required, need separately scoped user approval and an isolated golden specialist.

## Design references and limits

Use RDS components-empty-state.html, android-history.html, vpn-profile-switcher.html,
components-accordion.html and the executable theme. The profile utility has no
exact screen spec; preserve its existing sections and probe/selection contracts.
Keep measurement results, cleanup and failures visible. Only optional check setup,
secondary check-only action and dates may be disclosed. Exact profile rollback,
new network probes, schema changes and unrelated Advanced/API30 work are out of scope.
