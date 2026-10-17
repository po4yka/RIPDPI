## Context

The previous wave supplies static-motion correctness and a return-to-latest
action. This wave keeps those behaviors and changes app presentation only.

## Goals / Non-Goals

- Goal: Expose recovery, explain interface modes, reduce idle filter space, and show truthful new arrivals.
- Non-goal: Change service behavior, data storage, networking, dependencies, or screenshot baselines.

## Decisions

- Preserve the highest-priority Home advisory. Show the first actionable item
  as well when the highest item has no action. Disclose only other items and
  count them accurately. Reuse existing callbacks and labels.
- Use the existing bottom sheet with radio semantics, complete descriptions,
  and cancellation. Save a different selection once; selecting the current
  option only closes the sheet. Keep the persisted persona keys unchanged.
- Keep search and active removable chips outside the history disclosure.
  Count nonblank search and categorical restrictions; exclude auto-following.
  Keep disclosure state per history section through saveable state.
- Project unfiltered event identity and timestamp data in app UI models. A
  bounded high-water tracker distinguishes new matching identities from old
  records and filter changes. Track identities tied at the latest timestamp
  so same-time events count once. Do not store payloads or an unbounded ID set.
- Clear the count at the live edge, after a completed jump, or when content
  filters change. Preserve reader anchors and the existing Auto/Jump intent.
- Root owns locale resources, shared tags, generated translation metadata,
  planning, combined tests, review fixes, and publication. Writers use the
  isolated file lanes recorded in the portfolio task.

## Contracts and ownership

- Only `:app` changes. History projections remain internal UI data.
- No Rust crate, public wire, protobuf, storage, activation, or migration change.
- Locale sets and shared tags have one writer. Golden and quality baselines
  remain read-only. The RDS preview sources remain read-only.

## Risks / Trade-offs

- An actionless priority warning can conceal useful recovery: preserve it and
  expose the first available action with its own context.
- Rapid events or restored filters can inflate the counter: test initial
  snapshots, same timestamps, backfill, group refresh, filters, and user scroll.
- Large text can clip radio descriptions or count labels: allow wrapping and
  scrollable content; inspect light, dark, font2, and Arabic RTL on an emulator.
- Layout changes can invalidate old golden images. Report differences; do not
  bless fixtures without authorization.

## Migration Plan

No data migration is needed. Rollback reverts the app presentation commits.
Run targeted behavior tests, theme tests, Full/service lint, staticAnalysis,
translation export/discovery, architecture health, and locked Cargo metadata.
Build real Full debug and test APKs, record source and artifact hashes, and run
acceptance under an exclusive API37 arm64 pool lease. Rebase before combined
checks, then use authorized ff-only main integration and push. Record hosted
CI separately and retain the task in review while CI evidence is incomplete.
