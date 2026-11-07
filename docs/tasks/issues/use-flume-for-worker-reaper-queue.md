---
id: DGN-1794036659395595
title: Use a bounded flume queue for WorkerReaper
kind: chore
status: todo
area: diagnostics
priority: medium
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: []
spec_mode: required
openspec_change: use-flume-for-worker-reaper-queue
created: 2026-11-07
updated: 2026-11-07
---

## Goal

WorkerReaper must support multiple consumers and bounded enqueue waits without a handwritten spin loop.

## Audit evidence

Audit finding 14, 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Use existing flume::bounded, cloned receivers, and send_timeout. Keep the domain owner of JoinHandle and pending counters.

Keep queue capacity 64, the 5 ms enqueue budget, returned JoinHandle ownership on rejection, worker-start failure handling, and shutdown order.

## Acceptance criteria

- Independent completed workers are joined concurrently without head-of-line blocking.
- A full or disconnected queue returns the original JoinHandle within the existing bounded enqueue policy.
- Capacity, pending counts, failed worker creation, and shutdown retain ownership safety without a Mutex-wrapped receiver or yield retry loop.
- Extend worker_reaper_joins_workers_concurrently_without_head_of_line_blocking and worker_reaper_rejects_work_after_bounded_queue_saturates; cover shutdown and receiver disconnection.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 2; recommended position 14 in the epic queue. Prerequisites: None. Respect the epic priority and shared-file queue.

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `native/rust/crates/ripdpi-monitor-engine/src/session/reaper.rs`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

Use only existing platform or workspace libraries. Check the direct module dependency before editing.

## Verification

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-monitor-engine --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-monitor-engine --all-targets --locked -- -D warnings`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
