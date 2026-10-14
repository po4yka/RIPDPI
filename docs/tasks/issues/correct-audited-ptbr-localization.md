---
id: UIX-1791978914456411
title: Correct audited Brazilian Portuguese localization
kind: bug
status: doing
area: ui
priority: high
owner: Localization maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-audited-ptbr-localization
created: 2026-10-14
updated: 2026-10-14
---

## Goal

Correct the 14 confirmed Brazilian Portuguese resource keys and three shared presentation defects from the audit at af004adb89dbf443be8f190a3cead0e275026b24.

## Acceptance criteria

The corrected copy preserves source meaning, option names and recovery actions. History uses the selected app language on API 32. Retained Diagnostics, History and subscription presentation, including selected details, updates for EN to pt-BR to EN without a new data emission. Generated unknown-network summaries are localized while real names and raw evidence stay exact. All ten locale sets remain complete. Resource regressions, Kotlin behavior tests, lint, static analysis, Portuguese renders and screenshot comparison pass. The source commit is integrated into main, pushed and has successful hosted CI.

## Ownership

- Resource writer: all locale resource sets, the 14 confirmed keys, the source noise-help clarification in all ten locales, the two related pt-BR Private DNS captions for consistent naming, glossary and provenance, and Portuguese resource regressions. Worktree: RIPDPI-ptbr-resources-20261014. No other writer edits locale resources.
- Locale writer: Diagnostics, History and subscription ViewModel projection invalidation, selected-detail rebuilding, affected Route locale triggers and their production-seam tests. Worktree: RIPDPI-ptbr-reactive-20261014. HistoryConnectionDetailUiFactory is outside this writer's lane.
- History writer: the 13 HistoryConnectionDetailUiFactory resource lookups, Detection History generated network presentation, and their regression tests. Worktree: RIPDPI-ptbr-history-20261014. ViewModel locale invalidation is outside this writer's lane.
- Resource writer also owns new PortugueseLocalizationScreenTest and its new render helper, using production screens at normal and large font scales. Runtime writers own only their separate new behavior tests.
- Integration owner: this worktree, task and specification artifacts, central test and render execution, combined gates, independent review, main integration and publication.

Every writer is isolated and preserves other agents' work. Locale sets, manifests, baselines and fixtures have one owner. No baseline or golden change, schema migration or raw storage/export change is planned. The hosted security gate now requires the existing rustls dependency to move from 0.23.45 to the fixed 0.23.46 patch release. Optional vocabulary, date-format preferences and unconfirmed audit items remain outside scope. Writers first inspect and propose their behavior tests; production edits start after the specification is valid and the relevant RED result is observed.

Existing test-constructor ownership: the locale writer owns HistoryUiStateFactoryTest, HistoryViewModelTest, HistoryRouteTest, HistoryDetailLoaderTest, HistoryTestBuilders and HistoryActionsTest. The History writer owns HistoryConnectionDetailUiFactoryTest, DiagnosticsSpanishPresentationTest and DiagnosticsFrenchPresentationTest. New test files remain in each writer's lane. Root combines the interfaces before running the complete test gate.

## Hosted CI recovery

The source fix was pushed as 5ad5679910eab9569ee5f47245a4eb25fc8c937d. Hosted CI run 38579791823 stopped at cargo-deny after newly published RUSTSEC-2026-0346, RUSTSEC-2026-0347, RUSTSEC-2026-0348 and RUSTSEC-2026-0349 matched rustls 0.23.45. The upstream 0.23.46 release fixes all four. The existing dependency will be updated without new waivers or weaker gates.

Ownership before recovery edits: the security writer alone owns native/rust/Cargo.toml and native/rust/Cargo.lock in RIPDPI-ptbr-rustls-20261014. The integration owner alone owns task/specification evidence in that worktree. Other registered dependency work is preserved. Published package identity and source changes must pass review before compilation. Policy checks, Rust workspace tests/clippy and Android native cross-compilation are required before integration.
