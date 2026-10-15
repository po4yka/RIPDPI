## Context

The confirmed audit at a6b286e21 covers 121 active Persian resources and six generated display groups. Existing Arabic fixes already rebuild retained Home state on language changes. Current producers emit English evidence text that is also persisted or exported. The change corrects app presentation while preserving those records.

## Goals / Non-Goals

- Goal: accurate Persian copy and generated Approach, Strategy and Home output in the selected language, with complete resource parity.
- Non-goal: change network decisions, user data, service lifecycles, schemas, raw diagnostics or export contracts. No dormant stage announcement flow is activated.

## Decisions

- Correct the 121 confirmed resource keys only. Keep uncertain terms and inactive audit candidates outside the source change.
- Reuse exact existing labels. Add missing display templates through one resource owner in every locale. Use plural resources where count grammar requires them.
- Resolve known producer templates at the app presentation boundary. Match exact current generated shapes and typed fields; preserve unknown text and custom parameter values. Do not translate by broad substring replacement.
- Resolve locale-sensitive values at projection time. Use the existing retained locale invalidation path and verify EN to Persian to EN with the same raw data.
- Carry confidence grade separately from translated text so colors do not depend on English low/medium/high substrings.
- Group strategy probes by raw protocol detail keys before translating their labels.
- Keep existing Compose layout, RDS tokens and API call compatibility. Matching RDS screens remain read-only; this change changes display content only.

## Contracts and ownership

- Affected production module: app presentation and locale resources. Core diagnostics producers are inspected but not changed. No Rust crate, JNI, protobuf, wire or storage contract changes are planned.
- Resource writer owns all locale XML, translation manifest, provenance and resource regression scripts. Home and Strategy writers own disjoint Kotlin helpers and tests. Exact worktrees and paths are recorded in the portfolio task before writing.
- Integration owner controls planning, combined-tree validation, independent review, commit, rebase, main integration, push and hosted CI. Golden fixtures and quality baselines remain read-only unless the human authorizes a concrete path after visual review.

## Risks / Trade-offs

- Producer-template drift can leave English text. Derive cases from current producer code and test known variants, unknown fallback and raw-data preservation.
- RTL text can wrap after correction. Render production scenes at normal width and 360 dp with font scale 1.5; inspect clipping and boundaries. Keep ASCII endpoints and identifiers intact.
- Resource growth can introduce missing translations or arguments. Validate XML types, placeholders and plural categories across all ten locales and run Android lint.
- Local JVM tests do not prove native or device behavior. Use a verified real Xray AAR and authentic Simple relay bundle locally; record the native-less JVM property explicitly. Hosted CI must run its normal native, APK and emulator jobs.

## Migration Plan

No persisted migration or activation change. Existing raw records remain readable. Reverting the source commit restores previous display text. Before integration, run Full and Simple app unit tests and Roborazzi, both app lint variants, service lint, staticAnalysis, locale/export/resource checks, architecture health, file limits and locked cargo metadata. Fetch and rebase origin/main, repeat combined-tree gates, fast-forward main, push and observe green CI on the published SHA. Record evidence in a later documentation commit and verify CI on that final main as well.
