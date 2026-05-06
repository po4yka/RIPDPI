---
title: Replace android-support Mutex<VecDeque> event queues with flume::bounded channels
type: task
status: backlog
area: android
priority: medium
owner: unassigned
parent: consolidate-rust-manual-implementations-with-vendored-deps
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Replace android-support Mutex<VecDeque> event queues with flume::bounded channels #repo/RIPDPI #area/android #status/backlog 🔼

## Summary

`android-support/src/events.rs` maintains 5 separate `Arc<Mutex<VecDeque<NativeEventRecord>>>` queues (proxy, relay, warp, tunnel, diagnostics), each with manual capacity-cap logic that drops the oldest entry when full. This is a bounded MPSC drain-only pattern; `flume::bounded` (already a workspace dep in `ripdpi-io-uring`) replaces the lock + cap logic with a single constructor call. The Kotlin drain side calls `try_iter()` instead of `lock().drain(..)`.

## Affected file

`native/rust/crates/android-support/src/events.rs`

## Implementation steps

1. Add `flume` to `android-support/Cargo.toml` (it is already in `[workspace.dependencies]` via `ripdpi-io-uring`; add the workspace inherit).
2. Replace each `Arc<Mutex<VecDeque<NativeEventRecord>>>` field with `(flume::Sender<NativeEventRecord>, flume::Receiver<NativeEventRecord>)` returned by `flume::bounded(cap)`.
3. Remove the manual `if deque.len() >= cap { deque.pop_front(); }` guard — `flume::bounded` enforces the cap; use `try_send` and discard `Err(Full)` to preserve drop-oldest semantics, or use `force_send` if the flume version supports it.
4. Replace drain calls from Kotlin JNI path: `receiver.try_iter().collect::<Vec<_>>()`.
5. Delete the manual cap-check branches and associated tests; add tests for bounded-drop behaviour via `try_send` overflow.
6. `cargo nextest run -p android-support`.

## Acceptance criteria

- [ ] `flume` dep added to `android-support/Cargo.toml`.
- [ ] All 5 `Mutex<VecDeque>` fields replaced with `flume` channel pairs.
- [ ] Manual cap-drop logic deleted.
- [ ] `cargo nextest run -p android-support` passes.
- [ ] JNI drain path in `ripdpi-android` compiles without change to the Kotlin-side API.
