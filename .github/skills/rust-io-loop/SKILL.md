---
name: rust-io-loop
description: >
  RIPDPI io_loop and manual async-poll guidance. Use when adding a new channel or
  socket type to the smoltcp/tokio bridge in `ripdpi-tunnel-core`, reviewing a change
  that touches `try_read_duplex` / `try_write_duplex`, extending session/udp.rs or
  session/tcp.rs, modifying the `ripdpi-io-uring` ring buffer pool, or diagnosing why
  a tunnel future appears to stall. Covers the NoopWaker-based manual polling pattern
  that bridges smoltcp's userspace TCP stack to tokio's async runtime, the
  cancellation-safety caveats that follow from it, and the io_uring registered-buffer
  conventions.
---

# RIPDPI io_loop — Manual Poll Bridge

## The pattern at a glance

`ripdpi-tunnel-core` owns a userspace TCP stack (smoltcp) that must feed into tokio-driven session tasks. The two runtimes don't share a waker — smoltcp's scheduler is step-driven, tokio's is waker-driven. The bridge resolves this by constructing a `NoopWaker`, wrapping it in a `Context`, and manually calling `poll_read` / `poll_write` on a `tokio::io::DuplexStream`:

```rust
pub(super) struct NoopWaker;

impl std::task::Wake for NoopWaker {
    fn wake(self: Arc<Self>) {}
    fn wake_by_ref(self: &Arc<Self>) {}
}

pub(super) fn try_read_duplex(
    stream: &mut tokio::io::DuplexStream,
    buf: &mut [u8],
) -> Option<io::Result<usize>> {
    let waker = Waker::from(Arc::new(NoopWaker));
    let mut cx = Context::from_waker(&waker);
    let mut rb = ReadBuf::new(buf);
    match Pin::new(stream).poll_read(&mut cx, &mut rb) {
        Poll::Ready(Ok(())) => Some(Ok(rb.filled().len())),
        Poll::Ready(Err(e)) => Some(Err(e)),
        Poll::Pending => None,  // bridge will retry next tick
    }
}
```

Anchor: `native/rust/crates/ripdpi-tunnel-core/src/io_loop/bridge.rs:19-45`

## Why the waker is a no-op

The whole `try_*_duplex` family is called from the **io_loop's polling tick**, not from a tokio task. If we gave the duplex stream a real waker, the runtime would record a wake-up registration pointing at a context that no longer exists by the time the wake fires. That's safe (the weak reference is just dropped) but wasteful.

The trade-off: **`Poll::Pending` yields no progress information**. The io_loop decides when to re-poll by its own timing (after smoltcp's `iface.poll()` processes packets, or on a per-tick interval), not by tokio wake signals.

## Invariants the pattern depends on

1. **`try_*_duplex` is NEVER called from inside an async task's `await`.** If it were, `Poll::Pending` would propagate as "task not ready", but with a NoopWaker attached the task would never be woken — **permanent stall**. The bridge functions must be called from synchronous io_loop code.

2. **The stream must outlive the `Context`.** `Pin::new(&mut stream)` is safe because `DuplexStream: Unpin`; the bridge function's own stack frame owns the waker for the duration of the poll call.

3. **Cancellation is cooperative, not waker-driven.** The io_loop checks a `CancellationToken` between polling rounds. A smoltcp socket whose peer duplex stream has been dropped will surface `Poll::Ready(Err(BrokenPipe))` on the next `try_read_duplex` — that's the cancellation signal.

## Extending the bridge

### Adding a new stream type

If you add a new `tokio::io::DuplexStream`-like type to the bridge:

- It must implement `AsyncRead + AsyncWrite + Unpin`.
- The `try_*` wrapper must handle `Poll::Ready(Ok(0))` as a write-zero / read-zero condition and translate it to `WriteZero` / `UnexpectedEof` (see `flush_pending_to_session` at `bridge.rs:47-60` for the canonical handling).
- Do NOT add an `async fn` wrapper that awaits the stream — it will stall under the NoopWaker.

### Adding a new socket channel

New smoltcp sockets (TCP, UDP, raw) must plug into:
- `native/rust/crates/ripdpi-tunnel-core/src/session/tcp.rs` (19 async primitives)
- `native/rust/crates/ripdpi-tunnel-core/src/session/udp.rs` (22 async primitives)
- `native/rust/crates/ripdpi-tunnel-core/src/io_loop/udp_assoc.rs` (16 async primitives)

Follow the `try_*_duplex` convention and call from io_loop tick code, not from async tasks.

## io_uring registered buffer pool (`ripdpi-io-uring`)

The io_uring side of the tunnel uses registered buffer pools for zero-copy `SendZc` / `RecvFixed` / `TunReadBatch`. Key invariants:

1. **Buffers are registered once at startup** and referenced by index in every SQE. Re-registration resets all in-flight operations — don't do it.
2. **SQE submission and CQE completion are NOT synchronized by tokio.** The ring is polled from the io_loop; completions are drained into per-session queues.
3. **Every `unsafe` block in `ring.rs` that constructs an SQE carries a `// SAFETY:` comment naming the buffer-index validity and the fd lifetime.** New SQE constructors must match.
4. **Cancellation**: an in-flight `SendZc` / `RecvFixed` that the session drops must be cancelled via `IORING_OP_ASYNC_CANCEL` — don't just drop the SQE. A fire-and-forget drop leaks the registered buffer until the kernel completes the operation.

Anchor: `native/rust/crates/ripdpi-io-uring/src/ring.rs` (12 unsafe blocks, 2 unsafe trait impls).

### Tokio ≥ 1.51.1 is required

Tokio 1.51.1 fixed a file-descriptor leak when an `io_uring` `open` operation is cancelled before completion — directly RIPDPI-relevant because the io_loop's cancellation path relies on this. Do not bypass or downgrade the tokio pin in `Cargo.toml`.

Reference: [tokio CHANGELOG](https://github.com/tokio-rs/tokio/blob/master/tokio/CHANGELOG.md), [PR #7983](https://github.com/tokio-rs/tokio/pull/7983).

## Pitfalls

- **"My tunnel socket stalls on back-pressure"** — you're probably calling `try_write_duplex` from an async task instead of the io_loop tick. Move the call to the synchronous bridge.
- **"Writes succeed but the peer never sees data"** — smoltcp owes you an `iface.poll()` tick after the write; if the io_loop skipped a tick under load, writes accumulate in the duplex stream until the next tick.
- **"io_uring operations hang after cancellation"** — a dropped SQE without `IORING_OP_ASYNC_CANCEL`. Add the cancel submission in the drop path.

## Related skills

- `rust-async-internals` — tokio runtime config, `CancellationToken`, select!/join! patterns on the session-task side (the async side of the bridge).
- `rust-unsafe` — SAFETY conventions for `ring.rs` SQE construction.
- `rust-panic-safety` — session tasks wrap their bodies in `catch_unwind` before driving the bridge; a panic in the bridge itself propagates to the io_loop task which catches it.
