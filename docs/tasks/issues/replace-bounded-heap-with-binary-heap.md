---
id: RST-1794036632883567
title: Replace BoundedHeap with the standard BinaryHeap
kind: chore
status: review
area: rust-native
priority: medium
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: []
spec_mode: required
openspec_change: replace-bounded-heap-with-binary-heap
created: 2026-11-07
updated: 2026-11-07
status_detail: Implementation and required local checks passed; awaiting committed acceptance evidence.
---

## Goal

UDP association eviction must use the Rust standard heap without changing bounded history or stale-entry handling.

## Audit evidence

Audit finding 11 (attachment 1), 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Use BinaryHeap<Reverse<UdpEvictionEntry>> and remove the unused ripdpi-collections crate, its extra operations, and crate wiring after confirming all consumers.

Keep the explicit 2048-entry threshold, heap rebuilding, association ID/generation checks, eviction order, and bounded activity history. Capacity is not a size limit.

## Acceptance criteria

- When an association must be evicted, the same oldest eligible association is selected.
- Stale entries and reused source addresses cannot evict a newer association generation.
- History stays bounded after more than 2048 activity records; no caller, manifest, API snapshot, or architecture entry still requires ripdpi-collections.
- Use and extend existing UDP tests for stale entries, source-address reuse, eviction order, and repeated activity beyond the history limit.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 2; recommended position 11 in the epic queue. Prerequisites: None. Respect the epic priority and shared-file queue.

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `native/rust/crates/ripdpi-collections`
- `native/rust/crates/ripdpi-tunnel-core/src/io_loop/udp_assoc/eviction.rs`
- `native/rust/crates/ripdpi-tunnel-core/src/io_loop/udp_assoc/tests.rs`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

Use only existing platform or workspace libraries. Check the direct module dependency before editing.

## Verification

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-tunnel-core --locked`
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --no-deps --format-version 1`
- `python3 scripts/ci/check_architecture_health.py --check`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
