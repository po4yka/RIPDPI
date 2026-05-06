---
title: Replace hand-rolled hev-style ring buffer in ripdpi-tunnel-core with ringbuf crate
type: task
status: backlog
area: vpn
priority: medium
owner: unassigned
parent: consolidate-rust-manual-implementations-with-vendored-deps
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Replace hand-rolled hev-style ring buffer in ripdpi-tunnel-core with ringbuf crate #repo/RIPDPI #area/vpn #status/backlog 🔼

## Summary

`ripdpi-tunnel-core/src/ring_buffer/mod.rs` is a 160-line safe-Rust two-phase ring buffer modelled on `hev-ring-buffer.c` (two read-head counters). The `ringbuf = "0.4"` crate provides a lock-free SPSC ring buffer with `Producer`/`Consumer` split, `read_chunk`/`write_chunk`, and `AsyncHeapRb` for tokio integration. It matches the existing access pattern and removes 160 lines of maintenance burden.

## Implementation steps

1. Add `ringbuf = { version = "0.4", features = ["alloc"] }` to `[workspace.dependencies]`.
2. Add to `ripdpi-tunnel-core/Cargo.toml`.
3. Replace `RingBuffer` construction with `ringbuf::HeapRb::<u8>::new(capacity)` split into `(producer, consumer)`.
4. Replace two-phase read (`claim_read` / `release_read`) with `consumer.read_chunk(n)` / `rb.commit(n)`.
5. Replace two-phase write with `producer.write_chunk(n)`.
6. Delete `src/ring_buffer/mod.rs` and the `mod ring_buffer` declaration.
7. Run `cargo nextest run -p ripdpi-tunnel-core`.

## Acceptance criteria

- [ ] `ring_buffer/mod.rs` deleted.
- [ ] `ringbuf` in `[workspace.dependencies]`.
- [ ] `cargo nextest run -p ripdpi-tunnel-core` passes.
- [ ] TUN throughput benchmark (`ripdpi-bench`) does not regress by more than 5%.
