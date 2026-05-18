# Async Pitfall Catalog

Long-form sections moved out of `SKILL.md` to keep the main body focused on RIPDPI's async architecture. Each section retains its severity rating from the original.

## Table of contents

- [Blocking in async — common mistakes](#blocking-in-async--common-mistakes)
- [select! and join! pitfalls](#select-and-join-pitfalls)
- [CancellationToken: child tokens, DropGuard, run_until_cancelled](#cancellationtoken-child-tokens-dropguard-run_until_cancelled)
- [Structured concurrency status (as of 1.94)](#structured-concurrency-status-as-of-194)
- [Cancel-safety is an untyped invariant — annotate explicitly](#cancel-safety-is-an-untyped-invariant--annotate-explicitly)
- [Async-Drop contracts of pooled resource libraries](#async-drop-contracts-of-pooled-resource-libraries)
- [Async closures and the AsyncFn family (stable since Rust 1.85)](#async-closures-and-the-asyncfn-family-stable-since-rust-185)
- [HRTB pitfalls in Fn callbacks](#hrtb-pitfalls-in-fn-callbacks)
- [Async + shared state in event loops](#async--shared-state-in-event-loops)
- [Pin necessity in FFI](#pin-necessity-in-ffi)
- [impl Trait (RPIT) overcaptures lifetimes in edition 2024](#impl-trait-rpit-overcaptures-lifetimes-in-edition-2024)
- [tokio::time::timeout is cooperative](#tokiotimetimeout-is-cooperative--never-fires-on-non-yielding-futures)
- [JoinSet drop cannot abort spawn_blocking threads](#joinset-drop-cannot-abort-spawn_blocking-threads--silent-shutdown-hang)
- [spawn_blocking pool exhaustion from long-lived tasks](#spawn_blocking-pool-exhaustion-from-long-lived-tasks)
- [block_in_place panics on current_thread runtime](#block_in_place-panics-on-current_thread-runtime)
- [broadcast receiver silently drops messages on Lagged](#broadcast-receiver-silently-drops-messages-on-lagged)
- [std::sync::Mutex guard across .await deadlocks silently](#stdsyncmutex-guard-across-await-deadlocks-silently)
- [async fn in traits is not object-safe and has no Send bound](#async-fn-in-traits-is-not-object-safe-and-has-no-send-bound)

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

### CancellationToken: child tokens, DropGuard, run_until_cancelled

`tokio_util::sync::CancellationToken` exposes three idioms beyond `.cancelled().await` that RIPDPI should use for structured concurrency:

**Child tokens — parent cancels children, but child cancellation does not cancel parent.**

```rust
// Parent owns the master token. Each spawned session holds a child token.
let master = CancellationToken::new();
for session_id in sessions {
    let child = master.child_token();
    tokio::spawn(async move {
        run_session(session_id, child).await
    });
}
// Later: master.cancel() — propagates to every child.
// A single child can fail without taking down siblings:
//   child.cancel() inside one task affects only that task.
```

Use for: per-session lifetimes inside a tunnel runtime. The parent runtime token cancels every session on shutdown; a single session's failure does not cancel siblings.

**DropGuard — cancellation on RAII boundary.**

```rust
let token = CancellationToken::new();
let _guard = token.clone().drop_guard();
// ... do work, possibly with early returns / ? ...
// When _guard drops (early return, panic, end of scope), token is cancelled
// automatically. Spawned tasks that hold `token.clone()` observe cancellation.
```

Use for: bounded async operations where cancellation must fire on any early exit. Replaces the manual `defer!`-style cleanup that the discipline skill warns about.

**`run_until_cancelled` — race a future against cancellation, returning the future's value.**

```rust
use tokio_util::sync::CancellationToken;

let token = parent.child_token();
match token.run_until_cancelled(do_work()).await {
    Some(value) => process(value),           // do_work completed
    None         => log::info!("cancelled"), // cancellation fired first
}
```

Equivalent to `tokio::select! { v = do_work() => Some(v), _ = token.cancelled() => None }` but reads better and avoids the `select!`-arm-cancellation footgun (the losing branch is dropped — make sure `do_work` is cancel-safe before using this).

### Structured concurrency status (as of 1.94)

Rust does not have first-class structured concurrency. The community RFC (tokio-rs/tokio#1879, tokio-uring#81) is open. Until it lands, `CancellationToken` + `JoinSet` is the canonical pattern:

- **`JoinSet`** owns a set of spawned tasks. `join_next().await` returns the next-completed result. `abort_all()` aborts all tasks. Dropping the `JoinSet` aborts all tasks (does NOT wait for them — see the existing `JoinSet drop cannot abort spawn_blocking` rule).
- Pair `JoinSet` + a parent `CancellationToken` with `child_token()` per task for graceful cancellation in addition to abrupt abort.
- For task supervision (restart-on-failure), do not roll your own — use a supervised pool crate (`tokio_graceful`, `async-stream` patterns) and document the policy.

Rule: any `for x in xs { tokio::spawn(work(x)); }` loop with N > 1 is a refactor candidate. Either use `JoinSet::spawn` + `join_next` or `futures::future::join_all` (bounded N) / `futures::stream::iter(...).buffer_unordered(K)` (bounded concurrency).

## Cancel-safety is an untyped invariant — annotate explicitly

**Severity: CRITICAL when used inside `select!` / `timeout`**

A future is **cancel-safe** iff dropping it between any two `.await` points leaves observable state consistent. The property is not expressed in any signature — `async fn f(...)` looks identical whether `f` is safe to cancel between its internal `.await`s or not. Borrow checker and clippy do not help. The information lives in caller context (whether the future ends up in `tokio::select!`, `tokio::time::timeout`, or `FuturesUnordered`) plus library documentation that must be read per-method.

### Annotation discipline

Every `async fn` in RIPDPI that may transitively be polled inside `select!` / `timeout` / `FuturesUnordered` MUST carry a doc comment of the form:

```rust
/// cancel-safe: only `.await`s on `read` and `mpsc::recv`, both individually cancel-safe.
async fn read_request(&mut self) -> Result<Request> { /* ... */ }

/// NOT cancel-safe: `db.insert().await` followed by `send_ack().await` —
/// cancellation between them leaves the DB written but the client unacked.
async fn process(&self, stream: TcpStream) -> Result<()> { /* ... */ }
```

Rule: prefix `cancel-safe:` or `NOT cancel-safe:` with a reason. "cancel-safe because idempotent" is not acceptable — idempotence is a property of the OPERATION, cancel-safety is a property of the SCHEDULING. Both must hold independently.

### Library method cancel-safety table

Memorize this; the documentation entries are easy to miss.

| Method | Cancel-safe? | Why |
|--------|--------------|-----|
| `AsyncReadExt::read` | Yes | Single syscall; on cancellation, no bytes consumed. |
| `AsyncReadExt::read_exact` | **No** | May consume some bytes before cancellation; caller loses them. |
| `AsyncWriteExt::write` | Yes | Single syscall. |
| `AsyncWriteExt::write_all` | **No** | Same partial-write hazard. |
| `tokio::sync::Mutex::lock` | Yes | Acquisition is the only state change; cancellation releases the wait. |
| `tokio::sync::oneshot::Receiver` | Yes | Single state transition. |
| `tokio::sync::mpsc::Receiver::recv` | Yes | Documented cancel-safe. |
| `tokio::sync::Notify::notified` | **Conditional** | Must be awaited via `Pin<&mut>` to be cancel-safe; bare `.notified().await` re-arms each call. |
| `tokio::time::sleep` | Yes | Cancellation just drops the timer. |
| `sqlx::Transaction::commit` | **No** | Drop on partial commit triggers implicit blocking rollback. |
| `sqlx::QueryAs::fetch_one` | Conditional | Cancel-safe if the connection is dropped (released to pool); not if reused. |
| `reqwest::RequestBuilder::send` | **No** | Body may be partially sent. |

### Spawn-and-join firewall for non-cancellable critical sections

When a sequence of `.await`s must complete atomically with respect to cancellation, lift it into a spawned task and join the handle:

```rust
async fn process(stream: TcpStream, db: Arc<Db>) -> Result<()> {
    let data = read_message(&stream).await?;
    // From here on, cancellation of `process()` must NOT abort the work.
    let handle = tokio::spawn(async move {
        db.insert(&data).await?;
        send_ack(&stream).await?;
        Ok::<_, Error>(())
    });
    handle.await?  // outer cancellation cancels the join, not the spawned work.
}
```

This trades cooperative cancellation for atomicity. The spawned task will run to completion even if the caller is dropped. Use only when the alternative (data loss or inconsistent state) is worse. Pair with a `tokio::time::timeout` inside the spawned task if unbounded run-time is itself a hazard.

## Async-Drop contracts of pooled resource libraries

**Severity: WARNING**

Async types whose `Drop` performs cleanup (transactions, connections, file handles) have library-specific behavior that is NOT visible in their signatures. LLM-generated code memorizes the API but routinely misses the Drop semantics.

### sqlx 0.7 transactions

```rust
let tx = conn.begin().await?;
// ... operations ...
tx.commit().await?;  // If THIS fails, tx is dropped with no rollback decision made.
```

`Transaction`'s `Drop` impl issues an implicit ROLLBACK via a **blocking syscall** on the connection. Inside a tokio multi-thread runtime this surfaces as a `WARN`-level "blocking call in async context" log from the runtime's blocking-detector. Inside a `current_thread` runtime or under heavy load, it blocks a worker thread until the rollback completes — manifesting as random latency spikes.

Rule: never let a sqlx `Transaction` Drop after a failed `commit().await`. Convert to explicit rollback:

```rust
match work(&mut tx).await {
    Ok(v) => match tx.commit().await {
        Ok(()) => Ok(v),
        Err(e) => {
            // commit failed; Drop will do blocking rollback. Pre-empt it.
            let _ = tx.rollback().await;
            Err(e.into())
        }
    },
    Err(e) => {
        let _ = tx.rollback().await;
        Err(e)
    }
}
```

### deadpool-postgres / deadpool connections

`Object<Manager>::drop` returns the connection to the pool. If the pool's recycle hook performs an async health check, the recycle is enqueued on a background task — which may not run if the runtime is shutting down. Connection leaks under shutdown.

Rule: explicitly call `Object::take()` + `Manager::recycle()` in shutdown paths rather than relying on Drop.

### tokio::fs::File

Drop closes the fd via a `spawn_blocking` to avoid the close syscall on the runtime thread. If the runtime is shutting down with `Runtime::shutdown_timeout(Duration::ZERO)`, the close may not run and the fd leaks to the kernel until process exit.

Rule: in shutdown paths, explicitly `drop(file)` + `tokio::task::yield_now().await` before returning, or use `file.sync_all().await?` followed by explicit drop in a non-shutdown context.

### General audit

For every Drop on an async resource type:
1. Check the library's source for `impl Drop` — does it block, spawn, or no-op?
2. If it blocks: cleanup must run via explicit `.commit() / .rollback() / .close()` before drop.
3. If it spawns: cleanup is fire-and-forget; verify behavior under runtime shutdown.
4. Document the choice in a comment on the variable binding: `// drop here: blocking rollback acceptable in error path`.

## Async closures and the `AsyncFn` family (stable since Rust 1.85)

**Severity: INFO — replaces several long-standing workarounds**

Rust 1.85 (February 2025, RFC 3668) stabilized `async ||` closures and the `AsyncFn` / `AsyncFnMut` / `AsyncFnOnce` trait family. This resolves two long-standing pain points that `rust-async-internals` previously called out under "HRTB pitfalls":

1. Higher-ranked async signatures `for<'a> Fn(&'a T) -> impl Future + 'a` could not be expressed without GATs; `AsyncFn` handles them natively.
2. Returning futures that borrow from captured state required `Box<dyn Future + '_>` workarounds; `async ||` infers the right bound.

```rust
// PREFERRED (1.85+):
fn register<F>(callback: F) where F: AsyncFn(&str) -> Result<u32> { ... }
let cb = async |s: &str| { do_work(s).await };
register(cb);

// LEGACY (still works, but verbose and less inferable):
fn register<F, Fut>(callback: F)
where F: Fn(&str) -> Fut, Fut: Future<Output = Result<u32>> { ... }
let cb = |s: &str| async move { do_work(s).await };
```

Rules for RIPDPI (workspace MSRV is 1.94 — `async ||` is available everywhere):

1. New higher-ranked async bounds: prefer `F: AsyncFn(Args) -> T` over `F: Fn(Args) -> impl Future`.
2. New callbacks captured into structs that span `tokio::spawn`: still need `+ Send + 'static`. The `AsyncFn` family does NOT auto-add `Send`; use `trait_variant::make` or write the bound explicitly: `F: AsyncFn(Args) -> T + Send + Sync + 'static`.
3. Do NOT mass-rewrite existing `|x| async move { ... }` to `async |x|` in unrelated diffs. Migrate site-by-site when touching the callback site for another reason. Premature churn obscures git blame.
4. The legacy HRTB workarounds in the section below (`force_hrtb`, `Box<dyn for<'a> Fn(&'a str) -> ...>`) remain documented for reference but should not be used in new code.

Reference: [RFC 3668](https://github.com/rust-lang/rfcs/blob/master/text/3668-async-closures.md), Rust 1.85 release notes.

## HRTB pitfalls in `Fn` callbacks

**Severity: WARNING**

Higher-Ranked Trait Bounds (HRTBs) — `for<'a> FnMut(&'a T) -> K` — are the correct shape for callbacks that take a reference and return something that must not outlive the reference. But several sharp edges exist:

**Unexpressible with dependent output:** If `K` depends on `'a` (e.g., `K = &'a str`), this cannot be expressed without GATs today:
```rust
// Does NOT compile: K cannot depend on 'a without GATs
fn register<F: for<'a> FnMut(&'a str) -> &'a str>(f: F) {}
```
Workaround: use `Box<dyn for<'a> Fn(&'a str) -> &'a str + 'static>` or restructure to pass owned values.

**Closure inference quirk:** Closures in stable Rust default to fixed-lifetime inference, not HRTB inference. A closure `|s: &str| s` fails to implement `for<'a> Fn(&'a str) -> &'a str` in some compiler versions. Workaround: name the function explicitly or use a helper to force HRTB:
```rust
fn force_hrtb<F: for<'a> Fn(&'a str) -> &'a str>(f: F) -> F { f }
let cb = force_hrtb(|s| s);
```

**Async-Fn with `+Send + 'static`:** Pre-RPITIT (before Rust 1.75), async functions in traits cannot be expressed as `F: for<'a> AsyncFn(&'a T)`. The canonical pre-1.75 workaround is `F: Fn(&T) -> Pin<Box<dyn Future<Output = R> + Send + '_>>`. Post-1.75 with `async fn in traits`, prefer `trait MyTrait { async fn call(&self, t: &T) -> R; }`.

In RIPDPI: the JNI-to-async bridge passes callbacks across thread boundaries; all closures captured in `tokio::spawn` must be `'static + Send`. Avoid capturing `&T` references — convert to owned or `Arc<T>` before `spawn`.

Reference: `crabbook/borrowing_in_generic_functions.md`

## Async + shared state in event loops

**Severity: WARNING**

A captured `&mut State` inside an async block lives for the entire `Future`'s lifetime, from first poll to completion. Two concurrent `Future`s cannot share `&mut State`:
```rust
// DOES NOT COMPILE: two mutable borrows of `state` active at once
let f1 = async { state.handle_packet(pkt1) };
let f2 = async { state.handle_packet(pkt2) };
tokio::join!(f1, f2); // error[E0499]
```

Correct approaches (in order of preference for RIPDPI):
1. **Single-task ownership** (current io_loop design): one task owns `State`; all other tasks communicate via `mpsc` channels. No sharing needed. This is the RIPDPI canonical pattern — preserve it.
2. **`Arc<Mutex<State>>`**: correct but serializes access. Acceptable for low-contention config state; unacceptable on the packet path.
3. **`RefCell<State>` inside a `!Send` single-threaded runtime**: valid for current-thread runtimes. Not applicable to RIPDPI's multi-thread setup.

Nightly/future options (document only, do not use in production today):
- `Context::ext` (unstable): pass state through `Waker` context without `unsafe`.
- Generators with `resume(arg)`: coroutine-style state handoff. Available via the `generator-light` crate on stable.

Reference: `crabbook/event_loops_and_shared_state.md`

## `Pin` necessity in FFI

**Severity: WARNING for self-referential or FFI types**

`Pin<&mut T>` is a guarantee that `T` will not be moved after being pinned. This is required for:
- Self-referential structs (a field contains a pointer to another field of the same struct) — the default use of `Pin` in async state machines.
- FFI types that must not be moved after construction, because C++ objects have non-trivial move constructors that Rust cannot call.

In RIPDPI: `cxx`-generated bindings expose C++ types as `Pin<&mut CppType>`. This is correct — C++ may have a destructor that captures `this`, so the object address must remain stable. The same logic applies to any FFI handle allocated by C and returned by pointer: if the C API says "do not move this after init", wrap it in `Pin<Box<T>>` on the Rust side.

Rules:
- Never write a self-referential struct without `Pin` + `PhantomPinned`.
- Never store a raw pointer to a stack variable and then move the variable.
- `Box::pin(val)` is the easiest way to heap-pin a value in Rust.
- After pinning, use `Pin::get_unchecked_mut` only with a `// SAFETY: we never move T after this point` comment.

Reference: `crabbook/pin.md`

## `impl Trait` (RPIT) overcaptures lifetimes in edition 2024

**Severity: WARNING — edition migration hazard**

In Rust 2021 and earlier, return-position `impl Trait` (RPIT) did NOT implicitly capture lifetime parameters unless explicitly listed. In Rust 2024, all in-scope lifetimes are captured automatically. Consequence: functions that were `'static`-compatible in edition 2021 may become non-`'static` after migration because the return type now captures a lifetime from an input reference.

Concrete symptom: a function returning `impl Future + 'static` that takes `&self` now infers `impl Future + '_` — breaking any `tokio::spawn(obj.method())` call site.

Fix: use precise `use<..>` syntax (stabilized in Rust 1.82) to opt out of capturing specific lifetimes:
```rust
// Edition 2024: explicitly state which lifetimes/types are captured
fn process<'a>(data: &'a str) -> impl Future<Output = u32> + use<> {
    async move { data.len() as u32 }
}
```

The `impl_trait_overcaptures` lint (part of `rust-2024-compatibility` group) flags affected sites before migration. Run `cargo fix --edition` and inspect every RPIT diff carefully.

Reference: [Rust Blog: impl Trait capture rules](https://blog.rust-lang.org/2024/09/05/impl-trait-capture-rules/), Edition Guide RPIT section.

## `tokio::time::timeout` is cooperative — never fires on non-yielding futures

**Severity: CRITICAL**

`tokio::time::timeout` wraps a future and checks the deadline before each poll. If the wrapped future never reaches an `.await` point — tight CPU loop, blocking syscall, heavy synchronous computation — the timeout never fires. The future runs to completion regardless of the deadline.

```rust
// DANGEROUS: looks protected but is not
let result = tokio::time::timeout(
    Duration::from_secs(1),
    async {
        // No .await -- timeout will never fire
        expensive_cpu_computation()
    }
).await;
```

Fix: any blocking or CPU-heavy work must be moved to `spawn_blocking` before wrapping with `timeout`:
```rust
let result = tokio::time::timeout(
    Duration::from_secs(1),
    tokio::task::spawn_blocking(|| expensive_cpu_computation())
).await;
```

In RIPDPI: strategy-probe candidates, DPI classification heuristics, and TLS fingerprinting are CPU-heavy paths. Never wrap them in `timeout` without also moving them to `spawn_blocking`.

## `JoinSet` drop cannot abort `spawn_blocking` threads — silent shutdown hang

**Severity: WARNING**

When a `JoinSet` is dropped, it calls `.abort()` on all tracked futures. However, tasks spawned via `spawn_blocking` run on OS threads and Tokio documents that they cannot be cancelled by `abort`. If a `JoinSet` contains handles to async tasks that internally delegate to `spawn_blocking` (common in database pool workers, file I/O wrappers, and heavy computation), dropping the `JoinSet` during shutdown does not stop the underlying threads.

In practice: the process appears to shut down (the async tasks receive abort) but OS threads continue running until completion, potentially blocking process exit or causing `Runtime::shutdown_timeout` to fire.

Fix: for tasks that use `spawn_blocking` internally, prefer explicit cancellation signalling (a `CancellationToken` passed into the blocking closure) rather than relying on `JoinSet` abort. Always test shutdown behavior under load, not just happy-path sequential tests.

## `spawn_blocking` pool exhaustion from long-lived tasks

**Severity: WARNING**

Tokio's blocking thread pool has a default cap of 512 threads. Each `spawn_blocking` call occupies one thread until the closure completes. Long-running or indefinitely-polling tasks — file watchers, polling loops, persistent connections — exhaust the pool. When the pool is saturated, new `spawn_blocking` calls queue, causing latency spikes that appear as async slowdowns with no obvious cause.

Decision rule:
- **`spawn_blocking`**: bounded CPU work (target < 100 ms), occasional blocking syscalls, short file I/O.
- **`std::thread::spawn`**: indefinite blocking work, event loops, long-lived watchers.

In RIPDPI: the WS tunnel relay (`ripdpi-ws-tunnel`) already uses `std::thread::spawn` correctly. Any new persistent blocking work MUST follow this pattern, not `spawn_blocking`.

## `block_in_place` panics on `current_thread` runtime

**Severity: WARNING**

`tokio::task::block_in_place` migrates the current worker thread to the blocking pool and redistributes other tasks to remaining workers. Two hazards:

1. **Panics on `current_thread` runtime**: `#[tokio::test]` uses `current_thread` by default. Calling `block_in_place` inside a test panics with "can call blocking only when running on the multi-thread runtime". This causes confusing test failures when production code uses `block_in_place`.

2. **Starves `join!` branches**: inside a `join!`, other branches run on the same task. `block_in_place` suspends them for the duration of the blocking call, causing unexpected sequencing (branch A completes; branch B runs only after).

Fix: use `spawn_blocking` instead of `block_in_place` in both cases — it is safe on all runtime flavors and does not affect co-located tasks.

## `broadcast` receiver silently drops messages on `Lagged`

**Severity: WARNING**

`tokio::sync::broadcast` channels have a fixed ring-buffer capacity. A slow receiver that falls behind will have old messages overwritten. The next `recv()` call returns `Err(RecvError::Lagged(n))` — but most code handles only the `Ok(msg)` arm and treats `Lagged` as a transient error, silently dropping `n` events.

```rust
// BUG: Lagged silently discarded
while let Ok(msg) = rx.recv().await {
    process(msg);
}

// CORRECT: handle Lagged explicitly
loop {
    match rx.recv().await {
        Ok(msg) => process(msg),
        Err(RecvError::Lagged(n)) => {
            tracing::warn!("broadcast: dropped {} messages", n);
            // decide: continue, alert, or reconnect
        }
        Err(RecvError::Closed) => break,
    }
}
```

For audit logs, metrics, or state-machine transition messages, `Lagged` drops are data loss. Use `mpsc` with explicit backpressure for lossless delivery.

## `std::sync::Mutex` guard across `.await` deadlocks silently

**Severity: CRITICAL**

`std::sync::Mutex` guards do not implement `Send`. The compiler rejects them in `tokio::spawn` futures (which require `Send`). However, in `current_thread` executors or non-`Send` futures, the compiler accepts the guard crossing an `.await` point. At runtime, if the executor schedules another task that acquires the same lock, it deadlocks: the async task is suspended holding the lock, and the other task blocks.

This pattern works in development (sequential load, single task) and deadlocks only under concurrent production load.

```rust
// DEADLOCK risk: guard lives across .await
let guard = mutex.lock().unwrap();
some_async_op().await;  // another task may try to lock here
drop(guard);

// CORRECT: drop before .await
let value = {
    let guard = mutex.lock().unwrap();
    guard.value.clone()
};
some_async_op().await;
```

Rule: if a `Mutex` guard must genuinely live across `.await`, use `tokio::sync::Mutex`. If it does not need to, drop the guard explicitly before any `.await`.

## `async fn` in traits is not object-safe and has no `Send` bound

**Severity: WARNING**

`async fn` in traits was stabilized in Rust 1.75 (RPITIT). Three non-obvious hazards when replacing `#[async_trait]`:

1. **Not `dyn`-safe**: a trait containing `async fn` cannot be used as `dyn Trait`. Code that previously used `Box<dyn MyTrait>` (via `#[async_trait]` which boxes futures internally) breaks at compile time.

2. **No automatic `Send` bound**: native `async fn` in traits does not add `Send` to the returned future. `tokio::spawn(obj.method())` fails because the future is not `Send`.

3. **Fix for both**: use `#[trait_variant::make(MyTraitSend: Send)]` from the `trait-variant` crate (part of the `async-fn-in-trait` stabilization roadmap) to generate a `Send`-compatible trait variant:
```rust
#[trait_variant::make(MyTraitSend: Send)]
pub trait MyTrait {
    async fn process(&self, input: &str) -> Result<String>;
}
// Now use `MyTraitSend` for tokio::spawn contexts
```

In RIPDPI: any trait with `async fn` methods used in `tokio::spawn` contexts MUST use `trait_variant` or keep `#[async_trait]`. Do not mass-replace `#[async_trait]` without auditing every `Box<dyn>` and `tokio::spawn` use site.
