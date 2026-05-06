---
title: Replace custom RawWaker vtable and block_on executor in ripdpi-io-uring with waker-fn and pollster
type: task
status: backlog
area: rust-native
priority: high
owner: unassigned
parent: consolidate-rust-manual-implementations-with-vendored-deps
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Replace custom RawWaker vtable and block_on executor in ripdpi-io-uring with waker-fn and pollster #repo/RIPDPI #area/rust-native #status/backlog ⏫

## Summary

`ripdpi-io-uring/src/ring/thread_waker.rs` implements a 4-function `RawWaker` vtable (5 `unsafe` blocks) for thread park/unpark. `ripdpi-io-uring/src/ring/blocking.rs` hand-rolls a `block_on` poll loop on top of it. Both can be replaced with `waker-fn = "1"` and `pollster = "0.3"` with no behaviour change.

## Affected files

- `native/rust/crates/ripdpi-io-uring/src/ring/thread_waker.rs` — delete entirely
- `native/rust/crates/ripdpi-io-uring/src/ring/blocking.rs` — replace with `pollster::block_on`

## Implementation steps

1. Add to `[workspace.dependencies]`:
   ```toml
   waker-fn  = "1"
   pollster  = "0.3"
   ```
2. Add both to `ripdpi-io-uring/Cargo.toml`.
3. Replace the waker construction in `blocking.rs`:
   ```rust
   let thread = std::thread::current();
   let waker = waker_fn::waker_fn(move || thread.unpark());
   ```
4. Replace `block_on_completion` body with `pollster::block_on(future)` or inline the park loop using the waker above if the io_uring CQE notification path requires custom park semantics — document the choice.
5. Delete `thread_waker.rs` and remove its `mod` declaration.
6. Run `cargo nextest run -p ripdpi-io-uring`.

## Acceptance criteria

- [ ] `thread_waker.rs` deleted.
- [ ] Zero `unsafe` blocks in `blocking.rs`.
- [ ] `cargo nextest run -p ripdpi-io-uring` passes.
- [ ] `cargo clippy -p ripdpi-io-uring` no warnings.
- [ ] `waker-fn` and `pollster` added to `[workspace.dependencies]`.
