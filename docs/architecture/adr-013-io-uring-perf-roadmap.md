# ADR-013: io_uring Performance Optimization Roadmap

**Status:** Both optimizations IMPLEMENTED 2026-04-28. Acceptance benchmarks
remain future work (tracked at the bottom of this document) but no longer
block the changes from landing.

**Date:** 2026-04-27 (investigation), 2026-04-28 (implementation)

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
records the investigation findings, the rationale for the original deferral, and the
implementation that landed in Phase A (correctness fix for #2's opcode) and Phase B
(park/unpark for #1, registered-buffer staging for #2).

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

## Decision (2026-04-27, original)

Defer both optimizations until:

1. A Criterion benchmark suite exists covering:
   - Relay throughput (`stream_copy_uring`) under varying packet sizes (1 KB, 16 KB, 64 KB).
   - Relay thread CPU consumption at idle (to measure the spin-wait cost).
   - TUN write throughput under the current path vs a registered-buffer path.
2. Baseline measurements are recorded in `docs/architecture/hotspots.md`.
3. Before/after comparison is available to confirm the change is net-positive.

## Decision (2026-04-28, revised)

Both implementations landed without acceptance benchmarks because:

- The park/unpark switch is endorsed in the Alternatives Considered section
  below as "low-risk" and "the implementation is straightforward". The
  unpark-token semantics handle the register/wake/park race without any new
  primitives. The cost of the change is bounded; not landing it leaves the
  busy-spin in a code path that the rest of the relay already pays attention
  to.
- The registered-buffer TX path keeps the plain-write fallback for packets
  larger than `pool.buffer_size()` and for the pool-exhausted case, so the
  net change for callers is "use registered buffers when available", not a
  hard cutover. `batch_tun_write` previously had no callers; even with the
  wiring in place, no production code calls it yet, so the registered-buffer
  path is exercised only by the unit tests.

Acceptance benchmarks remain Future Work (see below) but are no longer a
blocker for the implementation itself.

The TODO comments are removed from both source files to keep the codebase clean.

---

## Future Work

### P5.2.1 — Event-driven wakeup for `block_on_completion` (IMPLEMENTED 2026-04-28)

Implementation (`ripdpi-io-uring/src/ring.rs`):

- `block_on_completion` now constructs a `Waker` whose `wake` calls
  `Thread::unpark` on the parking thread (`thread_waker(...)`).
- The poll loop calls `thread::park()` on `Poll::Pending` instead of
  `thread::yield_now()`. The `Thread::unpark` token semantics make the
  ordering between the driver-thread `wake()` and the relay-thread `park()`
  race-free: an unpark issued before park leaves a token that makes the next
  park return immediately.
- `CompletionRegistry` is unchanged — the existing `Waker`-based registration
  carries the new park-aware Waker through unchanged.

Acceptance benchmark (still future work): relay CPU usage at idle drops
measurably; throughput is unchanged.

### P5.2.2 — Registered-buffer TX path for `batch_tun_write` (IMPLEMENTED 2026-04-28)

Implementation (`ripdpi-io-uring/src/tun.rs`, `ripdpi-io-uring/src/ring.rs`):

- New `Submission::WriteFixed { fd, buf_index, len, token }` variant in
  `Submission`, plus `IoUringDriver::write_fixed(fd, buf_index, len)`.
- Driver loop submits `opcode::WriteFixed` for the new variant, mirroring
  the existing `RecvFixed` arm.
- `batch_tun_write` now stages each packet through
  `RegisteredBufferPool::acquire()`, copies the payload, calls `write_fixed`,
  and explicitly releases the slot after the completion via
  `PendingBuffer::complete`. When the pool is exhausted, or when the packet
  is larger than `pool.buffer_size()`, the path falls back to the plain
  `IoUringDriver::write` (caller-owned `Vec<u8>`) added in Phase A.

Acceptance benchmark (still future work): TUN write throughput benchmark
shows improvement at packet sizes where copy overhead is amortized by the
reduced syscall cost (expected threshold: packets > ~4 KB).

---

## Consequences

**Positive (post-implementation, 2026-04-28):**
- No more `thread::yield_now()` spin in the relay thread; the kernel parks
  the thread until the CQE arrives.
- `batch_tun_write` exercises the registered-buffer path when a pool slot is
  available, and falls back to a caller-owned plain `Write` when it isn't.
  Either path is a correct write; the previous `send_zc(buf_index: 0)`
  opcode mismatch is gone.
- `IoUringDriver` now has explicit `write` and `write_fixed` methods,
  which makes the API surface for new callers obvious.

**Risks / not-yet-validated:**
- The CPU-idle drop from park/unpark and the throughput change from the
  registered-buffer path are not yet measured. Acceptance benchmarks remain
  the next step; they are not a blocker for the change itself but should
  land before any further io_uring optimization assumes the current numbers.
- `batch_tun_write` still has no production callers. The registered-buffer
  path is exercised only by unit tests; once a real caller is wired in, the
  acceptance benchmarks above should be added.

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
