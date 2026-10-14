## Context

Task UIX-1791978914456411 corrects the audit at af004adb89dbf443be8f190a3cead0e275026b24. The app already has a locale-aware StringResolver and a route-triggered locale input for Main presentation. Diagnostics, History and subscription projections lack this input. Some selected details are stored as translated UI models.

## Goals / Non-Goals

- Goal: accurate pt-BR copy and language changes on retained production ViewModels with stable user state.
- Goal: locale-aware History captions on API 32 and generated Detection History captions in every selected locale.
- Non-goal: native behavior, storage or wire changes, new dependencies, optional vocabulary changes, or golden updates.

## Decisions

- Reuse StringResolver for the 13 History calls and remove the unused Context constructor parameter. Update test constructors in their assigned lanes.
- Follow MainUiStateOwner: each affected Route observes locale tags and calls its retained ViewModel. Locale tags are an input to presentation flow only. No global locale singleton is needed.
- Retain raw selected connection, session and approach details in memory. Project them in the UI factory or assembler on locale changes. Remap selected probe and candidate by stable ID. Do not reload repository data or reset selection, filters or sensitive visibility.
- Resolve exact Unknown and Unknown network summaries only with the existing unknown-fingerprint marker. Keep persisted text and export values exact. This marker is the existing producer contract for generated fallbacks.
- Correct 14 pt-BR audit keys and two related DNS captions. Clarify the existing noise-help key in all ten locales. No new key is planned. The existing 5002-string export manifest remains unchanged unless discovery finds a required new key.
- Keep all locale files and new Portuguese screen-render tests with one resource writer. Root owns specification, integration and central test execution. Shared existing test-constructor changes are split explicitly between the History and locale writers.

## Contracts and ownership

- Affected module: app, including main and Simple resources. Service resources are validated but no service behavior is changed.
- No Rust production source, JNI, protobuf, database, network activation, dependency or public wire contract is changed.
- HistoryDetailLoader and selected-state models are internal app presentation contracts. They may return or retain raw details to permit reprojection. Their fakes and tests change in the same source commit.
- Locale writer owns raw selection, projection invalidation and affected Routes. History writer owns HistoryConnectionDetailUiFactory and Detection History presentation. Resource writer owns all locale sets, glossary, provenance and resource tests.
- Locale writer owns constructor changes in HistoryUiStateFactoryTest, HistoryViewModelTest, HistoryRouteTest, HistoryDetailLoaderTest and HistoryTestBuilders. History writer owns remaining factory-constructor changes. These lanes remain isolated until root integration.
- Baselines and golden fixtures remain read-only. Generated board and manifest use their owning commands.

## Risks / Trade-offs

- Rapid locale changes can expose cached details or stale selections. Retained production tests use frozen data, stable IDs and EN to pt-BR to EN.
- Legacy records with fingerprint unknown and a real ISP name exactly equal to Unknown are indistinguishable from generated fallbacks. Treat the two reserved values with the unknown marker as generated for presentation. Preserve their stored values and document this legacy ambiguity. A schema migration is outside this fix.
- Robolectric native font initialization can fail before test bodies on this host. Keep semantic RED evidence separate from infrastructure failures, and rerun the unfiltered app gate in a fresh standalone invocation if needed.
- Simple screenshot checks require a genuine embedded relay bundle. Inspect the owning generation path and available inputs; do not use a CI placeholder as local completion evidence.
- AI-authored copy corrections do not add native-speaker approval. Record this in provenance.

## Migration Plan

No data migration or compatibility break is planned. Existing keys and records remain valid. Revert the scoped source commit to roll back presentation only.

Before publication, run resource regressions and parity/export, the unfiltered app unit gate, app Full and Simple lint, service lint, staticAnalysis, Portuguese normal and large-font renders, Full Roborazzi comparison, task/OpenSpec contracts, architecture health, file limits and locked Cargo metadata. Rebase onto origin/main, rerun combined-tree gates as required, fast-forward main and push. Observe successful hosted CI on the published source SHA. Device, release artifact and deployment acceptance are separate from this source change.
