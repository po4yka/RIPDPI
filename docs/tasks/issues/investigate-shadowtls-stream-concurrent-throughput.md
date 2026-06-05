---
title: Investigate ShadowTlsStream concurrent read/write throughput collapse
type: task
status: todo
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-05
updated: 2026-06-05
---

## Summary

`ShadowTlsStream` (`native/rust/crates/ripdpi-shadowtls/src/stream.rs`) shares
the `pending_frame` and `pending_frame_offset` fields between its `poll_read`
and `poll_write` implementations. Under concurrent read+write — `tokio::io::split`
or the relay datapath's `copy_bidirectional` — this serializes bidirectional
traffic into a frame-by-frame ping-pong and collapses throughput.

## Context

Surfaced by the per-transport throughput benches
(`add-protocol-throughput-benchmarks-for-each-transport`). Measured on loopback
with a 1 MiB full-duplex round-trip:

- VLESS+Reality: ~70 MiB/s
- VLESS-over-xHTTP-over-Reality: ~430 MiB/s
- **ShadowTLS v3: ~0.5 MiB/s** (≈2 s per 1 MiB)

With `MAX_WRITE_PAYLOAD_LEN = 16_380`, 1 MiB is ~64 frames; ~31 ms/frame matches
the ~40 ms delayed-ACK timer. `TCP_NODELAY` on both ends did **not** help, which
fits a serialized request/response pattern (delayed-ACK is receiver-side and
fires when there is no return data to piggyback the ACK on). The shared
`pending_frame` state means a queued outbound frame and an in-progress inbound
frame cannot be in flight at the same time, forcing the alternation. Data
integrity was correct in the measured run, so this presents as a performance
pathology rather than corruption — but the shared mutable framing state across
both half-streams should be audited for soundness, not only speed.

## Acceptance criteria

- [ ] Root-cause confirmed: reproduce the serialization and identify whether the
      shared `pending_frame`/`pending_frame_offset` state (and/or delayed-ACK)
      is the cause, with a written analysis.
- [ ] Separate the read and write framing state so a queued outbound frame and
      an in-progress inbound frame can be in flight concurrently (or document why
      the current sharing is sound and the throughput is acceptable).
- [ ] Re-enable the ShadowTLS case in `ripdpi-bench/benches/protocol_throughput.rs`
      and confirm throughput is in the same order of magnitude as the other TLS
      transports.
- [ ] The fix goes through the async diff-acceptance gate (`pr-reviewer` /
      `async-cancel-safety`) per `.claude/rules/llm-rust-prompts.md`.

## Definition of done

- ShadowTLS sustains representative full-duplex throughput on loopback, and the
  throughput bench includes it without an order-of-magnitude gap.

## Links

- [[add-protocol-throughput-benchmarks-for-each-transport]]

## Work log

- 2026-06-05: discovered while wiring per-transport throughput benches; ShadowTLS
  deferred out of the committed bench with this finding (the other two transports
  pipeline cleanly). `ShadowTlsStream` struct holds a single `pending_frame` /
  `pending_frame_offset` used by both `poll_read` and `poll_write`.
