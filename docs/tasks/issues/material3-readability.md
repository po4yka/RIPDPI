---
id: UIX-1791793531264679
title: Improve Material 3 readability and navigation
kind: feature
status: doing
area: ui
priority: high
owner: ui-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: material3-readability
created: 2026-10-12
updated: 2026-10-12
---

## Goal

Make primary text, icons, settings rows, and diagnostics navigation readable at standard and large font sizes.

## Acceptance criteria

- Primary UI text uses 16/24sp; supporting settings text uses 14/20sp; primary action and navigation icons use 24dp.
- Settings values wrap without reserving half of each row for short actions.
- Diagnostics sections use accessible Material 3 tabs without a vertical fixed selector at large font scale.
- Local UI tests and lint pass; fresh light, dark, and 2x font captures are inspected.
- DESIGN.md records the revised roles and layouts.

## Ownership

- typography worker: Type.kt, RipDpiIcons.kt, HomeModeCard.kt, ProfileUtilityScreen.kt, typography tests.
- settings worker: SettingsRow.kt and SettingsRowTest.kt.
- diagnostics worker: DiagnosticsOverviewSection.kt, DiagnosticsSectionSwitcherTest.kt, diagnostics preview functions.
- integration owner: DESIGN.md, docs/design-system.md, task and OpenSpec artifacts, combined-tree validation and preview captures.
- Serialized: screenshot fixtures, shared theme tokens, and generated board are integration-owner-only. Workers do not update fixtures, locales, or planning artifacts.
- Each writer uses a separate worktree. Main integration and commits are authorized by the user; push is not requested.
