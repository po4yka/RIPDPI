---
name: rust-async-internals
description: Rust async internals skill for debugging async Rust in the RIPDPI project. Use when diagnosing select!/join! pitfalls, blocking-in-async issues, JNI-to-async bridging, tokio runtime configuration for Android NDK, CancellationToken patterns, or the io_loop event-driven architecture. Activates on queries about async internals, select!, blocking in async, JNI async bridging, CancellationToken, or io_loop design.
---

# Rust Async Internals -- RIPDPI

## Purpose

Guide agents through async patterns specific to RIPDPI: JNI-to-async bridging,
the io_loop event-driven architecture, tokio runtime configuration for Android
NDK, CancellationToken-based shutdown, and common select!/join! pitfalls.

## Triggers

- "Why is the tunnel blocking / slow?"
- "How does JNI call into async Rust here?"
- "How does select! work and what are the pitfalls?"
- "How is the tokio runtime configured?"
- "How does cancellation / shutdown work?"

## Project async architecture

RIPDPI has two independent tokio runtimes bridged from JNI:

1. **Tunnel runtime** (`ripdpi-tunnel-android`): shared `multi_thread(2)` runtime
   stored in a `OnceCell<Arc<Runtime>>`. A dedicated `std::thread` calls
   `runtime.block_on(run_tunnel(...))` so the JNI thread is not blocked.

2. **Proxy runtime** (`ripdpi-android`): `run_proxy_with_embedded_control` blocks
   the calling JNI thread directly (the Android Service thread). Uses an
   `IdleGuard` drop-safety pattern to reset state on panic.

### JNI-to-async bridge pattern

```rust
// ripdpi-tunnel-android/src/session/lifecycle.rs -- the canonical pattern
//
// JNI create_session() returns a jlong handle.
// JNI start_session() spawns a std::thread that calls runtime.block_on():
let worker = std::thread::Builder::new()
    .name("ripdpi-tunnel-worker".into())
    .spawn(move || {
        let result = std::panic::catch_unwind(AssertUnwindSafe(|| {
            runtime.block_on(run_tunnel(config, fd, cancel, stats))
        }));
        // Handle Ok/Err/panic, update last_error and telemetry
    });
// The JNI thread returns immediately; stop_session() cancels via token.
```

Key rules for this pattern:
- Never call `block_on` from a JNI callback thread directly for long-running
  work -- spawn a dedicated thread so the JNI call returns promptly.
- Duplicate file descriptors (`nix::unistd::dup`) before passing to async --
  Android VpnService can revoke the original fd at any time.
- Wrap `block_on` in `catch_unwind` -- a panic must not unwind through JNI.

### Required tokio version floor

Do **not** downgrade tokio below these versions:

- **≥ 1.42.1** — fixes a `broadcast::Sender::clone()` soundness bug (missing synchronization for `Send + !Sync` payloads) and a `CancellationToken` race where futures that polled to `Ready` before the token fired were not cancelled. RIPDPI's connection-level abort paths rely on the cancellation fix. See the tokio [CHANGELOG](https://github.com/tokio-rs/tokio/blob/master/tokio/CHANGELOG.md) and [PR #7462](https://github.com/tokio-rs/tokio/pull/7462).
- **≥ 1.51.1** — fixes a file-descriptor leak when an `io_uring` `open` operation is cancelled before completion. `ripdpi-tunnel-core` cancels in-flight FS/IO operations on session teardown; below this version the leaked fds accumulate until the process exits. See [PR #7983](https://github.com/tokio-rs/tokio/pull/7983).

If `cargo tree -i tokio` shows a version below the floor, promote it in `native/rust/Cargo.toml` workspace dependencies (never downgrade a transitive dep to work around a breaking change — open an upstream issue instead).

### Runtime configuration for Android NDK

```rust
// ripdpi-tunnel-android/src/session/registry.rs
tokio::runtime::Builder::new_multi_thread()
    .worker_threads(2)          // constrained for mobile battery/CPU
    .thread_stack_size(1024 * 1024) // 1 MiB -- Android default is small
    .thread_name("ripdpi-tunnel-tokio")
    .enable_all()
    .build()
```

Android-specific considerations:
- `worker_threads(2)` balances throughput vs battery drain on mobile.
- Explicit `thread_stack_size(1 MiB)` -- Android NDK default stack is often
  too small for deep async state machines.
- The runtime is stored in `OnceCell<Arc<Runtime>>` and shared across sessions
  to avoid repeated thread pool creation.
- `current_thread` runtime is used only in tests and the DNS resolver's
  synchronous `resolve_blocking()` path.

## io_loop event-driven architecture

The core tunnel runs a single-task 6-phase loop (`io_loop_task` in
`ripdpi-tunnel-core/src/io_loop.rs`):

1. **Drain TUN fd** -- read raw IP packets via `AsyncFd::try_io`, classify
2. **smoltcp poll** -- advance TCP state machines in userspace
3. **New sessions** -- detect ESTABLISHED sockets, spawn `TcpSession` tasks
4. **Duplex bridge** -- pump data between smoltcp sockets and session tasks
5. **Flush tx_queue** -- write smoltcp packets back to TUN fd
6. **Wait** -- `select!` on TUN readable / smoltcp timer / UDP / DNS / cancel

Phase 6 select pattern:

```rust
tokio::select! {
    _ = tun.readable() => {},
    _ = tokio::time::sleep(smol_delay) => {},
    udp_event = udp_rx.recv() => { handle_udp_event(...) }
    dns_result = async { ... }, if dns_resp_rx.is_some() => { ... }
    _ = cancel.cancelled() => { break; }
}
```

This is NOT a typical "spawn per connection" design. One task owns the entire
smoltcp stack. Individual TCP/UDP sessions ARE spawned as separate tokio tasks
that communicate back via `mpsc` channels and `tokio::io::duplex` pairs.

**The smoltcp ↔ duplex bridge uses a `NoopWaker`-based manual poll pattern**: see `rust-io-loop` skill for the full treatment of `try_read_duplex` / `try_write_duplex` in `io_loop/bridge.rs:19-45`. The short version: the bridge calls `poll_read` / `poll_write` directly from the io_loop tick with a discarded waker. Consequence: the `try_*_duplex` family must NEVER be called from inside an async `await` — a `Poll::Pending` under the NoopWaker stalls the task permanently. If you find yourself writing `async fn` wrappers around duplex streams in this crate, stop and consult the io_loop skill.

## Blocking in async -- common mistakes

```rust
// WRONG: blocks a runtime thread, starves other tasks
async fn bad() {
    std::thread::sleep(Duration::from_secs(1));
    std::fs::read_to_string("file.txt").unwrap();
}

// CORRECT: async equivalents
async fn good() {
    tokio::time::sleep(Duration::from_secs(1)).await;
    tokio::fs::read_to_string("file.txt").await.unwrap();
}

// CORRECT: offload unavoidable blocking work
async fn with_blocking() {
    tokio::task::spawn_blocking(|| heavy_cpu_work()).await.unwrap();
}
```

In RIPDPI specifically: the WS tunnel (`ripdpi-ws-tunnel`) uses synchronous
`std::net::TcpStream` + tungstenite deliberately -- it runs on a dedicated
thread, not inside the tokio runtime. This is intentional, not a bug.

## select! and join! pitfalls

```rust
// select! completes when FIRST branch finishes; LOSING branches are DROPPED.
// Their futures are cancelled -- any in-progress work is lost.
tokio::select! {
    result = fetch_a() => { /* fetch_b() dropped */ }
    result = fetch_b() => { /* fetch_a() dropped */ }
}

// Biased select: always checks branches in order. Use for priority shutdown.
loop {
    tokio::select! {
        biased;
        _ = cancel.cancelled() => break,  // always checked first
        msg = rx.recv() => process(msg),
    }
}

// join! waits for ALL to complete -- no cancellation surprise.
let (a, b) = tokio::join!(fetch_a(), fetch_b());
```

### CancellationToken pattern (used throughout RIPDPI)

```rust
// Parent creates token, passes child tokens to spawned work
let cancel = CancellationToken::new();
let child_cancel = cancel.child_token();

tokio::spawn(async move {
    tokio::select! {
        _ = child_cancel.cancelled() => { /* clean shutdown */ }
        _ = do_work() => {}
    }
});

// Later: cancel.cancel() propagates to all child tokens
```

RIPDPI uses `tokio_util::sync::CancellationToken` (not `tokio::sync::Notify`)
for structured shutdown. The tunnel session state machine transitions through
`Ready -> Starting -> Running -> Destroyed`, with the token stored in the
`Starting` and `Running` variants.

## Debugging async issues

- **Task starvation**: Look for blocking calls in async context. The io_loop
  is especially sensitive -- a single blocked poll starves all TCP sessions.
- **Deadlock at shutdown**: Check that `cancel.cancelled()` is in every
  `select!` loop. Missing it causes tasks that never terminate.
- **fd leaks**: Verify `OwnedFd` cleanup on all error paths in JNI lifecycle
  functions. The dup'd fd must be closed even if `start_session` fails.

Note: `tokio-console` is not practical for this project -- it requires the
`tokio_unstable` cfg flag and `console-subscriber`, which add overhead and
complexity to Android NDK cross-compilation. Use `tracing` spans and
`RUST_LOG` filtering instead.

## Related skills

- `.claude/skills/rust-debugging/` -- GDB/LLDB debugging of async Rust
- `.claude/skills/rust-profiling/` -- cargo-flamegraph with async stack frames
- `.claude/skills/memory-model/` -- memory ordering in async contexts
