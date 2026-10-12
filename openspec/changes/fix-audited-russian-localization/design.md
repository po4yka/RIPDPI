## Context

The audit found semantic errors in Russian XML, English prose in Kotlin presentation, English plural suffixes, and incomplete AndroidX locale integration. Protocol and export schemas remain unchanged.

## Goals / Non-Goals

- Goal: Close every confirmed audit finding with regression evidence.
- Non-goal: Change network behavior, user data, dependencies, layout, or golden fixtures.

## Decisions

- Use AndroidX app locale persistence and locale-aware non-Activity contexts. Keep existing backup deny-all rules.
- Use resource plurals for event and active session counts. Keep machine counts unchanged.
- Reuse existing resource keys where meanings match. Add small resource presenters for known dynamic text; preserve unknown and user-defined values.
- Keep catalog and wire strings unchanged and translate them at the presentation boundary using stable IDs.
- Give all locale XML and the export manifest to one writer. Code writers provide a resource request manifest before final compilation.

## Contracts and ownership

- app owns locale selection, presentation, and app resources. core/service owns notification presentation and service resources.
- No Rust crate, JNI, protobuf, persisted settings schema, or export contract changes.
- The portfolio ownership section defines separate writer worktrees and the serialized resource lane.
- Existing design layouts and theme tokens remain in use; this change changes text only.

## Risks / Trade-offs

- Locale storage differs below API 33: test the older API contract and non-Activity resolution explicitly.
- Dynamic labels can contain user values: localize only known presentation text.
- Translations can alter layout: verify the Russian home scene and inspect affected text.
- Golden changes require explicit fixture authorization; do not record or bless expected images.

## Migration Plan

AndroidX stores selected locales in app-private storage already excluded from backup. No schema migration is required. Revert the scoped commits to roll back. Run app/service tests, lint, static analysis, locale parity/export, architecture health, locked Cargo metadata, and screenshot verification on the combined branch before main integration.
