## Context

The VPN summary currently truncates the saved profile list. The mode editor starts from the active profile. Its ID field can retarget an upsert to another saved profile.

## Goals / Non-Goals

- Goal: Expose all saved profiles and use their persisted data for selection and editing.
- Non-goal: Add a new profile store, editor, relay protocol, or schema.

## Decisions

- Reuse `ConfigRoute`, `ModeEditorRoute`, and the existing profile store. Profile actions pass an ID to `ConfigViewModel`.
- Hydrate an edit from the selected profile record and credentials. Keep save behavior, including runtime restart, in the existing path.
- Keep the ID and relay kind read only for existing profiles. Reject ID collisions and stale profile or credential snapshots at the persistence boundary. The latter protects callers outside the UI.
- Present the first three profiles immediately and the complete collection in a bounded lazy list. Imported kinds without Mode Editor fields retain Select and Share but do not show Edit.
- Selecting a saved profile changes the active settings and restarts the running mode through the existing lifecycle helper.

## Contracts and ownership

- Affected modules: `:app` and `:core:data`. The coordinator accepts an optional expected profile and credential snapshot on relay upsert and checks it under its existing mutex. No Rust crate, JNI, protobuf, wire, or stored schema change.
- Locale resource sets are serialized with the PCAP lane. `docs/tasks/board.md` is generated after integration by the parent agent.

## Risks / Trade-offs

- An inactive profile may contain incomplete fields. Selection reports activation failure without changing another saved profile.
- Concurrent writes can race UI validation. The coordinator compares the current profile and credentials with the editor snapshots under the same mutex used for the upsert.

## Migration Plan

No stored-data migration. Rollback is a revert of app code and strings. Validate targeted app unit tests, lint, and static analysis; device behavior remains a separate gate.
