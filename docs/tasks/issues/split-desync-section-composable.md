---
title: Split DesyncSection.kt monolithic LazyListScope into per-feature sections
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Split DesyncSection.kt monolithic LazyListScope into per-feature sections #repo/RIPDPI #area/ui #status/backlog 🔼

## Objective

Break the 843-LOC `desyncSection` function (25 parameters, 7+ suppressed detekt violations) into focused `LazyListScope` extension functions, one per logical desync feature group.

## Context

`DesyncSection.kt` (lines 44–763) renders 7+ independent sub-sections in a single function with 25 parameters: adaptive split profile, chain DSL editor, TCP flag profile, fake ordering, host fake, sequence overlap, fake approximation, adaptive fake TTL (three sub-variants), fake payload library (three profile cards), fake TLS, OOB data, drop-SACK, IP-ID mode. The `@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")` at line 43 suppresses enforcement instead of fixing it.

Source: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/DesyncSection.kt:44-763`

## Acceptance criteria

- [ ] Each logical block extracted into its own `LazyListScope` extension function: `adaptiveSplitSection`, `chainEditorSection`, `fakeTlsSection`, `fakePayloadSection`, `adaptiveFakeTtlSection`, `ipDesyncSection` (minimum 6 functions).
- [ ] Each extracted function takes only the `SettingsUiState` slice it actually reads — no 25-parameter signatures.
- [ ] `desyncSection` becomes a coordinator calling the extracted functions; its own parameter count drops to ≤5.
- [ ] `@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")` annotations removed.
- [ ] No visual regression verified via Roborazzi or manual review.

## Definition of done

`@Suppress` annotations absent from `DesyncSection.kt`; each extracted function is ≤150 LOC; Roborazzi settings golden passes.
