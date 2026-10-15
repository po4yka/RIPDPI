## Context

Task UIX-1792040423920590 repairs the Arabic audit at 4e132e8c. MainUiStateOwner already projects with a locale-aware StringResolver. Home still copies generated English notes, evidence and outcomes into UI.

## Goals / Non-Goals

- Goal: correct all 86 active Arabic entries and all eight confirmed Home groups, with retained language switching.
- Non-goal: new features, raw diagnostics changes, storage migrations, new dependencies, native or service behavior, optional editorial refinements or unrelated golden updates.

## Decisions

- Localize at app presentation boundaries with existing StringResolver and plural APIs. Keep diagnostics producers, persisted results and exports exact.
- Guard recognized generated text by complete producer shape or structured fields. Do not run global English replacements over arbitrary evidence. Preserve custom and unknown prose.
- Keep Home priority, ordering, max-three summary and max-four action limits, deduplication and uncertainty. Preserve protocol tokens, authorities, URLs, identifiers and counters.
- Reuse current locale invalidation and prove EN to Arabic to EN against the same raw result. Add focused resolvers rather than expanding already large projection files.
- One resource owner adds new keys in all ten locales and regenerates the export manifest through its owner script. Correct source ambiguity in all locales only where the audit proves it.
- The real SettingsRow reproduced reversed numeric IPv6 order in both configuration screens under RTL. Set ContentOrLtr only for listen-address and relay-endpoint values. Preserve Arabic titles, disabled and unknown captions, and the default behavior of other rows.

## Contracts and ownership

- Only app production sources change, including main and Simple resource sets. Service lint checks parity without service behavior changes.
- No Rust crate, JNI, protobuf, wire, schema, configuration activation or storage contract change. No migration.
- Resource writer alone owns locale sets, glossary/provenance, export manifest and resource regressions in RIPDPI-ar-resources-20261015.
- Home writer owns app Home projection/resolvers and behavior tests in RIPDPI-ar-home-20261015.
- Root owns planning, evidence, RTL lane, combined validation, independent review, integration and publication in RIPDPI-ar-localization-20261015.
- RDS layout and tokens remain unchanged. Existing Home analysis views are the presentation boundary. The user approved only the health_rtl.png golden after review of the corrected cache-misses label. Other golden fixtures and quality baselines are read-only.

## Risks / Trade-offs

- Legacy generated prose can resemble custom text. Match exact producer formats and structured context; test custom and delimiter-bearing values.
- Formatting and plural errors can hide behind key parity. Test placeholders, Arabic categories and retained production projection separately.
- Source tests and renders do not provide native-speaker or physical-device acceptance. Record those limits explicitly.
- Heavy tests share the machine build gate with other work. Root serializes commands, preserves foreign jobs and uses worker/native CPU limits.

## Migration Plan

No migration or breaking change. Revert the scoped source commit to roll back. Run resource regressions and locale/export contracts, app Full and Simple unit tests, both app lint variants, service lint, staticAnalysis, Arabic production renders and Roborazzi comparison. Run task/OpenSpec validation, architecture health, file limits and locked Cargo metadata. After independent review, commit, fetch/rebase origin/main, rerun combined-tree gates, fast-forward main and push. Wait for successful hosted CI on the published source SHA.
