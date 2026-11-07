---
id: RST-1794036651029797
title: Use tempfile for native atomic file replacement
kind: chore
status: todo
area: rust-native
priority: medium
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: []
spec_mode: required
openspec_change: use-tempfile-for-native-atomic-writes
created: 2026-11-07
updated: 2026-11-07
---

## Goal

The three native state writers must share safe temporary-file lifecycle behavior without losing durability or file permissions.

## Audit evidence

Audit finding 13, 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Use NamedTempFile::new_in in the destination directory, write and sync the file, persist it, then sync the parent directory as required by the existing contract.

Keep size limits, restrictive permissions and permission preservation, old-file retention on failure, and sync_all ordering. Drop cleanup is not a SIGKILL guarantee.

## Acceptance criteria

- A successful replacement exposes a complete state file with the expected contents and permissions.
- A failed write or rename keeps the old state and cleans temporary files during normal error unwinding.
- Each writer preserves its file and directory synchronization contract; existing file formats load unchanged.
- Extend existing failed_replacement_cleans_temporary_file, cache_persist_failure_preserves_existing_file, and cache_persist_preserves_existing_file_permissions tests for all production writers.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 2; recommended position 13 in the epic queue. Prerequisites: None. Respect the epic priority and shared-file queue.

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `native/rust/crates/ripdpi-runtime-policy/src/runtime_policy/autolearn/store.rs`
- `native/rust/crates/ripdpi-runtime-policy/src/runtime_policy/cache/connection.rs`
- `native/rust/crates/ripdpi-runtime-adaptive/src/adaptive_tuning/persistence/file_io.rs`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

tempfile is already used for tests but will enter the production graph. Assess license, transitive dependencies, binary size, platform support, and approval required by repository policy before promotion.

## Verification

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-policy -p ripdpi-runtime-adaptive --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-policy -p ripdpi-runtime-adaptive --all-targets --locked -- -D warnings`
- `cargo deny --manifest-path native/rust/Cargo.toml --locked check`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
