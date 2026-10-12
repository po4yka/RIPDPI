---
id: UIX-1791793531264679
title: Improve Material 3 readability and navigation
kind: feature
status: review
area: ui
priority: high
owner: ui-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: material3-readability
created: 2026-10-12
updated: 2026-10-12
status_detail: Implementation complete; local UI, lint, and screenshot checks passed. Fresh theme and 2x font renders inspected. Main integration authorized; no push requested.
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
- typography follow-up worker: OnboardingOptionCard.kt and OnboardingOptionCardTest.kt in a separate fix/material3-option-reflow worktree; prevent title/badge compression after the shared type increase.
- settings worker: SettingsRow.kt and SettingsRowTest.kt; follow-up ownership includes RipDpiRowMetrics in Spacing.kt for a 40dp leading badge, 32dp leading symbol, and 24dp trailing chevron. This writer alone owns these row metrics.
- diagnostics worker: DiagnosticsOverviewSection.kt, DiagnosticsSectionSwitcherTest.kt, diagnostics preview functions.
- integration owner: DESIGN.md, docs/design-system.md, task and OpenSpec artifacts, combined-tree validation and preview captures.
- golden-blesser: app/src/test/screenshots in the isolated test/material3-ui-goldens worktree; user approved the affected UI fixtures on 2026-10-12.
- Serialized: golden-blesser owns screenshot fixtures, the typography worker owns type and icon tokens, the settings worker owns row metrics, and the integration owner owns the generated board. These paths have one writer each. Other workers do not update fixtures, locales, or planning artifacts.
- Each writer uses a separate worktree. Main integration and commits are authorized by the user; push is not requested.
