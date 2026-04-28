# ADR-013: io_uring Performance Optimization Roadmap

**Status:** Investigation complete; both optimizations deferred pending benchmark infrastructure.

**Date:** 2026-04-27

---

## Context

Two performance-oriented TODOs existed in the io_uring integration layer:

1. `ripdpi-runtime/src/runtime/relay/stream_copy_uring.rs` — `block_on_completion` uses a
   spin-wait loop (`thread::yield_now()` with a noop waker) while waiting for io_uring CQEs to
   arrive from the driver thread.

2. `ripdpi-io-uring/src/tun.rs` — `batch_tun_write` copies packets from smoltcp's
   `VecDeque<Vec<u8>>` into a plain `send_zc` submission with `buf_index: 0` rather than first
   staging them into registered buffers and using `WriteFixed`.

Both were marked `// TODO(npochaev): Consider ...` during initial implementation.  This ADR
records the investigation findings and the rationale for deferring both.

---

## Investigation Findings

### P5.2.1 — `block_on_completion` spin-wait (stream_copy_uring.rs)

`block_on_completion` is implemented with a noop-waker `Future` poller that calls
`thread::yield_now()` on each `Poll::Pending`.  It runs on a dedicated `std::thread`
(`ripdpi-dn-zc`) that is already blocked on `TcpStream::read` for the majority of its lifetime.
The spin only occurs in the window between a read completing and the matching CQE arriving from
the driver thread — typically sub-millisecond on a lightly loaded ring.

A more efficient alternative would use `thread::park` / `thread::unpark` (or a
`std::sync::Condvar`) with the `CompletionRegistry` storing the thread handle and calling
`unpark` instead of `waker.wake()`.  `tokio::sync::Notify` is **not applicable** here because
neither `ripdpi-runtime` nor `ripdpi-io-uring` depends on tokio; the relay threads are plain
`std::thread` instances.

**Why deferred:** The relay thread is I/O-bound, not CPU-bound.  The spin window is bounded by
CQE latency (single syscall round-trip), not by connection throughput.  Replacing the noop waker
with `park`/`unpark` requires threading a `Thread` handle through `CompletionRegistry` alongside
the existing `Waker` slot, changing `IoUringDriver`'s registration API.  Without a Criterion
benchmark on relay throughput and CPU idle-consumption, we cannot confirm the change improves the
metric that matters.  The risk of introducing a park/unpark race (missed unpark before park)
outweighs the speculative benefit.

### P5.2.2 — Registered-buffer variant for tx_queue (tun.rs)

`batch_tun_write` currently calls `uring.send_zc(tun_fd, 0, pkt.len())` — where `buf_index: 0`
is a placeholder, not an actual registered buffer.  The packet data lives in smoltcp's
`Vec<u8>` and must be copied regardless.  Using `WriteFixed` instead would require:

1. Acquiring a slot from `RegisteredBufferPool`.
2. Copying the `Vec<u8>` payload into the registered buffer.
3. Submitting `WriteFixed` with the correct index and length.
4. Releasing the buffer after the CQE arrives.

The net effect is a copy (heap → registered page) plus a syscall-free kernel path, versus the
current path which avoids the registered-buffer copy but pays a `sendmsg`-equivalent per packet.
Whether the registered-buffer path wins depends on packet size distribution and copy bandwidth —
both of which require measurement.  The existing comment in the source already captures this
tradeoff accurately.

**Why deferred:** No benchmark exists for TUN write throughput under the registered-buffer path.
The change would also require `RegisteredBufferPool` to be accessible from `batch_tun_write`,
which currently only receives `&IoUringDriver` and a packet slice — a minor API change, but one
that should land alongside evidence it helps.

---

## Decision

Defer both optimizations until:

1. A Criterion benchmark suite exists covering:
   - Relay throughput (`stream_copy_uring`) under varying packet sizes (1 KB, 16 KB, 64 KB).
   - Relay thread CPU consumption at idle (to measure the spin-wait cost).
   - TUN write throughput under the current path vs a registered-buffer path.
2. Baseline measurements are recorded in `docs/architecture/hotspots.md`.
3. Before/after comparison is available to confirm the change is net-positive.

The TODO comments are removed from both source files to keep the codebase clean.  The roadmap
items are tracked here instead.

---

## Future Work

### P5.2.1 — Event-driven wakeup for `block_on_completion`

Replace the noop-waker spin-loop with `thread::park` / `thread::unpark`:

- Add a `Thread` field to `WakerSlot::Waiting` in `CompletionRegistry`.
- On registration: store `thread::current()` instead of a `Waker`.
- On `complete`: call `thread.unpark()`.
- `block_on_completion`: call `thread::park()` on `Poll::Pending` instead of `yield_now()`.

This eliminates busy-spinning entirely.  The `Thread::unpark` call is safe to issue before
`park` (it sets a token), so the race that affects raw condvar usage does not apply here.

Acceptance: relay CPU usage at idle drops measurably in the benchmark; throughput is unchanged.

### P5.2.2 — Registered-buffer TX path for `batch_tun_write`

Extend `batch_tun_write` to accept a `&RegisteredBufferPool`, acquire a slot per packet, copy
the payload, and submit `WriteFixed` instead of `send_zc` with index 0.  Release the buffer
after the CQE is reaped.

Acceptance: TUN write throughput benchmark shows improvement at packet sizes where copy overhead
is amortized by the reduced syscall cost (expected threshold: packets > ~4 KB).

---

## Consequences

**Positive:**
- No speculative performance code is shipped without evidence.
- The roadmap is explicit and actionable once benchmark infrastructure exists.
- Source files are clean — no dangling TODOs.

**Negative / Risks:**
- The spin-wait in `block_on_completion` remains.  On a lightly loaded relay thread this is
  acceptable; on a heavily loaded device it consumes one CPU yield slot per CQE round-trip.
- `batch_tun_write` continues using `send_zc` with `buf_index: 0`, which is semantically
  incorrect for the ZC path (no registered buffer is backing it).  This is functionally
  equivalent to a plain send on the driver side but wastes the ZC opcode.  Fixing the opcode to
  `Write` (non-fixed, non-ZC) would be a correctness improvement independent of the registered-
  buffer optimization — tracked separately if it becomes a priority.

---

## Alternatives Considered

**Implement P5.2.1 now (park/unpark).**  Rejected for this commit: the change is safe in
isolation but the benefit is unquantified.  If the relay thread's `yield_now` spin turns out to
be measurable in a profiling session, this should be the first thing to land — it is low-risk
and the implementation is straightforward (see Future Work above).

**Use `eventfd` for wakeup.**  An `eventfd` would allow the driver thread to signal the relay
thread without a Waker/Thread abstraction.  More complex than `park`/`unpark` and requires an
additional fd per relay thread.  Rejected in favour of the simpler `park`/`unpark` approach.

**Implement P5.2.2 now (registered-buffer TX).**  Rejected: requires API change to
`batch_tun_write` and `RegisteredBufferPool`, and the benefit depends on packet size
distribution that we have not measured.

---

## Related

- `native/rust/crates/ripdpi-runtime/src/runtime/relay/stream_copy_uring.rs` — `block_on_completion`, `copy_inbound_zc`
- `native/rust/crates/ripdpi-io-uring/src/ring.rs` — `block_on_completion` implementation, `CompletionRegistry`
- `native/rust/crates/ripdpi-io-uring/src/tun.rs` — `batch_tun_write`
- `native/rust/crates/ripdpi-io-uring/src/bufpool.rs` — `RegisteredBufferPool`
- `docs/architecture/hotspots.md` — performance baseline target
