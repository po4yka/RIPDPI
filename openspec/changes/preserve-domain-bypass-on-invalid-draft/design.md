## Context

`RuleRepository.saveDomainBypassList` treats an invalid-only draft like an empty list and deletes the managed rule. The editor then reports that the list was cleared.

## Goals / Non-Goals

- Goal: Preserve the active rule and show errors for an invalid-only draft.
- Non-goal: Change partial saves, validation syntax, or the explicit blank-list clear action.

## Decisions

- Return the compile result without a database write when it has errors and no clean lines. Keep the existing repository as the save boundary.
- Suppress the editor confirmation for that result and disable Save while the visible draft is known to be invalid-only. The repository guard also covers the brief validation debounce.

## Contracts and ownership

- Affects `:app` and `:core:data`. No Rust crate, shared serialized file, schema, wire contract, dependency, or locale key changes.
- This change owns the domain bypass editor, repository, their tests, and its task and OpenSpec files.

## Risks / Trade-offs

- Validation is debounced in the editor, so Save can remain briefly enabled after an edit. The repository guard prevents the data loss in that interval.

## Migration Plan

No data migration. Existing rules stay valid. Rollback restores the old save behavior. Verify with the repository and ViewModel unit tests, app and data lint checks, and `./taskctl validate`.
