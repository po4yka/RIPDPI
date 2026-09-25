## Context

The app already persists these relay kinds in `RelayProfileRecord` and `RelayCredentialRecord`. The Mode Editor kind list and draft fields cover only some kinds, while the existing source-record copy preserves fields that are not overwritten.

## Goals / Non-Goals

- Goal: Make the six missing kinds fully editable through the existing Mode Editor draft, validation, and persistence paths.
- Non-goal: Change native relays, import formats, or add a new profile editor.

## Decisions

- Reuse existing profile/credential records and Mode Editor actions. Map each exposed field through draft hydration and persistence.
- Preserve source-record fields not represented in the editor by copying the source record. Keep the existing saved-ID and compare-and-swap checks.
- Add kind selection only with a complete form and validation; keep unsupported combinations disabled.

## Contracts and ownership

- Android `:app` Compose, ViewModel draft mapping, validation, persistence, and tests are affected. `core:data` storage types and `core:service` activation contracts remain unchanged. No Rust crate, JNI, protobuf, or schema change.
- The relay editor agent owns these app paths and this task/OpenSpec record. Other P2 worktrees own separate paths. Locale resources and generated task board are serialized at integration.

## Risks / Trade-offs

- Secret loss on unrelated edits: hydrate every credential and test a round trip with unchanged secrets.
- Stale overwrite: retain the existing profile identity and compare-and-swap behavior and test rejection.
- Runtime incompatibility: validate requirements from the current service resolver before saving.

## Migration Plan

No data migration. Existing records gain editing controls. Rollback restores the old UI; saved records remain compatible. Run targeted app tests, app lint, task validation, and combined-tree gates before integration.
