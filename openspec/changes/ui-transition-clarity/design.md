## Context

The existing runtime model has confirmed safe metadata and a pending status, not
a complete future effective configuration. See proposal and delta requirements.

## Goals / Non-Goals

- Goal: match History motion to reading direction, make status feedback clear, and show a truthful reconnect comparison.
- Non-goal: change runtime activation, stored settings, service admission, or resolve future route selection in the UI.

## Decisions

- Mirror the existing ordinal direction with LocalLayoutDirection; retain travel distance, timing, saved section state, and current reduced/static policy.
- Fade only the connection indicator in a fixed slot. Key the transition by status, keep loading outside the action, and retain one current accessible status. Disabled motion renders the final indicator directly.
- Use the existing scrollable confirmation dialog with stacked current and saved summaries. Compare only safe known facets; label route resolution and unlisted/custom settings limits explicitly.
- Assign a new opaque identity when the distinct settings flow emits, before combining telemetry and runtime state. Carry no settings values or hashes in that identity. Changing settings or the existing runtime confirmation invalidates the dialog at render and click time.
- Root owns all ten locale resource sets and generated translation metadata; workers supply required keys. New comparison text does not change the idle Home card or existing golden captures.

## Contracts and ownership

Only app presentation, internal UI inputs, resources and tests change. No Rust,
JNI, protobuf, storage, schema, dependency or service contract changes. Keep
ConfirmedRuntime and current reconnect admission checks authoritative. File
ownership and isolated worktrees are recorded in the portfolio issue before writes.

Use RDS motion-page-transitions, motion-reduced-motion, motion-connection-states,
and components-dialog references. The comparison has no exact RDS sheet. Stacked
safe values and a short fixed-slot fade are intentional presentation choices.

## Risks / Trade-offs

- Saved intent may differ from effective runtime: use saved wording and explicit route-resolution/unknown notes.
- Settings change while reviewing: opaque settings identity plus runtime guard invalidates old review, including changes to undisplayed fields.
- Secret-bearing fields: project allowlisted labels only and never render raw configuration, IDs, endpoints, profile names or hashes.
- Large font and RTL: use wrapping, scrollable stacked content and complete actions; inspect normal and font2 RTL on a leased emulator.

## Migration Plan

No migration is needed. Rollback uses scoped commits. Observe meaningful test-only
RED before each production fix, then focused GREEN, all theme tests, Full and
service locale lint, staticAnalysis, translation-export check, architecture health,
full locked Cargo metadata, and strict task validation. Build real GithubFull app
and test APKs with verified unchanged native inputs. Inspect normal/reduced/off
motion, LTR/RTL directions, status and cancellation, preview/confirm/cancel/stale
snapshots, dark/light and font2 using a command-scoped emulator pool lease.
Rebase against origin/main before combined checks, integrate ff-only, push only
the authorized main branch, and inspect exact-SHA hosted CI separately.
