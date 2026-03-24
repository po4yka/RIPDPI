# Loom Concurrency Audit

Audited: 2026-03-24 against the current `native/rust` tree.

## Summary

The highest-value `loom` targets in RIPDPI are the JNI-facing handle registries
and the telemetry/event snapshots that race with runtime writers.

Important findings from this audit:

- Shared mutable state is concentrated in three layers:
  - session registries and per-session lifecycle state in `ripdpi-android`,
    `ripdpi-tunnel-android`, and diagnostics
  - telemetry/event buffers and stats snapshots shared between runtime threads
    and JNI polling
  - runtime policy/adaptive state inside `ripdpi-runtime`
- I found no explicit `unsafe impl Send` or `unsafe impl Sync` anywhere under
  `native/rust/`.
- I found no `crossbeam` structures in source under `native/rust/`.
- I did not find a raw use-after-free through a stale numeric handle. The
  registries store `Arc<T>`, handles are monotonic, and removed handles are not
  reused. The real risk is stale `Arc<T>` access after `destroy` invalidates the
  handle.
- Existing `loom` coverage is partial and already useful:
  `android-support`, `ripdpi-runtime`, `ripdpi-monitor`, and
  `ripdpi-android` have working `loom` coverage.
- `ripdpi-tunnel-android` already has a `loom` test module, but
  `cargo test -p ripdpi-tunnel-android --features loom --no-run` currently
  fails because `src/session/loom.rs` shadows the external `loom` crate.

## Shared State Inventory

### Primary JNI/session state

| Area | Shared mutable state | Primitive(s) | Concurrent access pattern | Notes |
|------|----------------------|--------------|---------------------------|-------|
| Session handle registries | `android-support::HandleRegistry<T>` used by proxy, tunnel, and diagnostics | `AtomicU64 next`, `Mutex<HashMap<u64, Arc<T>>>` | create inserts; every JNI entry point looks up; destroy removes | Central lifecycle race point. `get()` clones `Arc<T>`, so in-flight JNI work can outlive registry removal. |
| Proxy session state | `ripdpi-android/src/proxy/registry.rs`: `ProxySession.state` | `Mutex<ProxySessionState>` | `start_session`, `stop_session`, `destroy_session`, `update_network_snapshot` all race on the same state lock | `start_session` looks up before taking the state lock. |
| Proxy telemetry | `ripdpi-android/src/telemetry.rs`: `ProxyTelemetryState` | atomics, `ArcSwap<TelemetryStrings>`, `Mutex<VecDeque<NativeRuntimeEvent>>`, `LatencyHistogram` | runtime callback threads write counters/strings/events while JNI `poll_proxy_telemetry` snapshots and drains events | Main producer/consumer telemetry race. |
| Tunnel session state | `ripdpi-tunnel-android/src/session/registry.rs`: `TunnelSession.state`, `last_error` | `Mutex<TunnelSessionState>`, `Arc<Mutex<Option<String>>>` | `start_session`, `stop_session`, `destroy_session`, `stats_session`, `telemetry_session`, worker thread, and JNI pollers share the same session object | `start_session` also looks up before taking the state lock. |
| Tunnel telemetry | `ripdpi-tunnel-android/src/telemetry.rs`: `TunnelTelemetryState` | atomics, `ArcSwapOption<String>`, `Mutex<VecDeque<NativeRuntimeEvent>>`, `LatencyHistogram` | tunnel worker/start/stop/error paths push state and events while JNI telemetry polling drains them | Same bounded event-ring pattern as proxy. |
| Tunnel stats snapshot | `ripdpi-tunnel-core/src/stats.rs`: `Stats` | atomics, several `Mutex<Option<_>>`, `Mutex<VecDeque<u64>>`, `Mutex<Option<Arc<dyn Fn...>>>` | async tunnel tasks write counters/DNS fields while JNI `stats_session` and `telemetry_session` read snapshots | Good sync-only target even though tunnel I/O itself is async. |
| Diagnostics session | `ripdpi-monitor/src/lib.rs`: `MonitorSession.shared`, `cancel`, `worker` | `Arc<Mutex<SharedState>>`, `Arc<AtomicBool>`, `Mutex<Option<JoinHandle<()>>>` | scan worker updates shared progress/report/events while JNI start/cancel/poll/take_report/destroy race on worker + cancel + shared | Destroy is remove-first, then `session.destroy()`, which makes stale-`Arc` races stronger than proxy/tunnel. |

### Runtime/config-adjacent shared state

| Area | Shared mutable state | Primitive(s) | Concurrent access pattern | Notes |
|------|----------------------|--------------|---------------------------|-------|
| Embedded proxy control | `ripdpi-runtime/src/lib.rs`: `EmbeddedProxyControl.shutdown`, `network_snapshot` | `Arc<AtomicBool>`, `Arc<ArcSwap<Option<NetworkSnapshot>>>` | accept loop reads shutdown; JNI `update_network_snapshot` writes network state; any runtime consumer would read lock-free | `network_snapshot` is currently written from JNI but not consumed in runtime production paths. |
| Runtime policy/cache | `ripdpi-runtime/src/runtime/state.rs`: `cache`, `adaptive_fake_ttl`, `adaptive_tuning`, `retry_stealth`, `active_clients` | `Arc<Mutex<_>>`, `Arc<AtomicUsize>` | per-client worker threads select routes, learn policy, update retry/adaptive state, and manage connection limits concurrently | This is the real mutable "config state" during active connections. `RuntimeConfig` itself is immutable once wrapped in `Arc`. |
| Listener worker pool | `ripdpi-runtime/src/runtime/listeners.rs` | `StdMutex<WorkerPoolState>`, `Condvar` | accept loop enqueues jobs while worker threads dequeue, sleep, and scale the pool | Lower risk than JNI lifecycle, but a real cross-thread queue. |
| Relay session state | `ripdpi-runtime/src/runtime/relay/stream_copy.rs` | `Arc<Mutex<SessionState>>`, `Arc<AtomicBool>` | inbound and outbound relay threads mutate/read shared `SessionState` and coordinate shutdown with `peer_done` | Two relay halves share one session transcript/state object. |

### Secondary shared state and channels

| Area | Shared mutable state | Primitive(s) | Concurrent access pattern | Notes |
|------|----------------------|--------------|---------------------------|-------|
| Metrics recorder | `ripdpi-telemetry/src/recorder.rs`: `InMemoryRecorder` | `RwLock<Vec<(String, Arc<AtomicU64>)>>`, `OnceLock` | metric registration mutates maps while snapshots read them | This is the only production `RwLock` usage I found in `native/rust`. No `Arc<RwLock<_>>` occurrences were found in the JNI/session path. |
| Histogram storage | `ripdpi-telemetry/src/lib.rs`: `LatencyHistogram` | `Arc<Mutex<Histogram<u64>>>` | telemetry writers record latency while snapshot readers compute percentiles | Shared by proxy telemetry and tunnel DNS latency tracking. |
| Tunnel async channels | `ripdpi-tunnel-core/src/io_loop.rs`, `dns_intercept.rs`, `udp_assoc.rs` | `tokio::sync::mpsc::{Sender, Receiver}` | async tasks exchange UDP and DNS events concurrently | Out of scope for `loom`; keep for `shuttle` evaluation. |
| WS tunnel relay queue | `ripdpi-ws-tunnel/src/relay.rs` | `std::sync::mpsc::sync_channel`, `Arc<AtomicBool>` | uplink thread pushes outbound frames while relay loop drains them | Real concurrent queue, but lower priority than JNI registries. |

## Session Handle Registry and Lifecycle

All three JNI-facing registries use the same `HandleRegistry<T>` contract:

1. `insert()` allocates a new numeric handle from `next.fetch_add(...)`.
2. The handle maps to an `Arc<T>` stored in a `Mutex<HashMap<...>>`.
3. `get()` clones the `Arc<T>`.
4. `remove()` removes the map entry and returns the stored `Arc<T>`.

Implications:

- Handles are monotonic and not reused during process lifetime.
- A stale numeric handle after `remove()` becomes "unknown"; it does not alias a
  new session.
- Any thread that already called `get()` keeps a live `Arc<T>` even after the
  handle is removed from the registry.

### Proxy session lifecycle

Files:

- `native/rust/crates/ripdpi-android/src/proxy/registry.rs`
- `native/rust/crates/ripdpi-android/src/proxy/lifecycle.rs`
- `native/rust/crates/ripdpi-android/src/proxy/telemetry.rs`

Lifecycle:

1. `create_session` parses config, creates `ProxyTelemetryState`, and inserts a
   `ProxySession`.
2. `start_session` looks up the session by handle, constructs listener/control,
   then takes `session.state` and transitions `Idle -> Running`.
3. `stop_session` looks up the session, takes `session.state`, clones shutdown
   control, and requests stop.
4. `destroy_session` looks up the session, checks `Idle` under the state lock,
   drops the lock, then removes the handle from the registry.
5. `poll_proxy_telemetry` looks up the session and snapshots
   `session.telemetry`.

Observed race:

- `start_session` does registry lookup before taking `session.state`.
- `destroy_session` checks state before `remove()`.
- Interleaving: start does lookup -> destroy sees `Idle` and removes -> start
  later takes the still-live `Arc<ProxySession>` and marks it `Running`.
- Result: no memory UAF, but a live orphaned proxy session with no registry
  entry and no valid handle for later control.

### Tunnel session lifecycle

Files:

- `native/rust/crates/ripdpi-tunnel-android/src/session/registry.rs`
- `native/rust/crates/ripdpi-tunnel-android/src/session/lifecycle.rs`
- `native/rust/crates/ripdpi-tunnel-android/src/session/stats.rs`
- `native/rust/crates/ripdpi-tunnel-android/src/session/telemetry.rs`

Lifecycle:

1. `create_session` parses config, gets shared Tokio runtime, and inserts a
   `TunnelSession`.
2. `start_session` looks up the session, validates/dups TUN fd, creates cancel
   token and stats, then transitions `Ready -> Starting -> Running`.
3. `stop_session` looks up the session, extracts `Running` state, cancels, and
   joins the worker.
4. `destroy_session` looks up the session, requires `Ready`, then removes the
   handle.
5. `stats_session` and `telemetry_session` look up the session and snapshot
   shared stats/telemetry.

Observed race:

- Same lookup-before-lock orphaning as proxy.
- Interleaving: start does lookup -> destroy sees `Ready` and removes -> start
  later transitions `Ready -> Starting -> Running`.
- Result: live worker thread plus telemetry/stats state surviving after the
  handle is gone.

### Diagnostics session lifecycle

Files:

- `native/rust/crates/ripdpi-android/src/diagnostics/registry.rs`
- `native/rust/crates/ripdpi-android/src/diagnostics/scan.rs`
- `native/rust/crates/ripdpi-android/src/diagnostics/polling.rs`
- `native/rust/crates/ripdpi-monitor/src/lib.rs`

Lifecycle:

1. `create_diagnostics_session` inserts `MonitorSession`.
2. `start_diagnostics_scan`, `cancel_diagnostics_scan`, `poll_progress`,
   `take_report`, and `poll_passive_events` all do registry lookup first.
3. `destroy_diagnostics_session` removes the handle first, then calls
   `session.destroy()`.

Observed race:

- Because removal happens before `session.destroy()` completes, any thread with a
  stale `Arc<MonitorSession>` can still call `start_scan()`, `cancel_scan()`, or
  polling methods after the handle is already invalid.
- `MonitorSession` has no "destroyed" bit, so a stale `Arc` can restart a new
  scan after `destroy()` has returned.
- This is stronger than the proxy/tunnel race because the object can be
  logically resurrected after destruction, not just continue an already-started
  operation.

## Unsafe `Send`/`Sync`

Search result for `unsafe impl (Send|Sync)` under `native/rust`:

- none found

That means the current concurrency risk is coming from higher-level lifecycle
and synchronization design, not from custom unsafe thread-safety claims.

## When a JNI Thread Can Access an Already-Destroyed Session

If "use-after-free via handle" means a stale numeric handle reaching freed
memory, I did not find that in the current design. `Arc<T>` prevents that class
of bug.

If "already-destroyed session" means "the handle has been destroyed and JNI can
still touch the session object", then it can happen under these conditions:

1. A JNI entry point successfully calls registry `get()` before a concurrent
   `destroy` removes the handle.
   Affected paths: proxy start/stop/poll/update snapshot, tunnel start/stop/
   stats/telemetry, diagnostics start/cancel/poll/take_report.
   Outcome: the thread holds `Arc<T>` and can continue using the session after
   handle removal.

2. Proxy or tunnel `start_session` wins after `destroy_session` has already
   validated idle/ready state and removed the handle.
   Outcome: orphaned live session or worker thread without any remaining handle.

3. Diagnostics `destroy_diagnostics_session` removes from the registry before
   `session.destroy()` completes, and `MonitorSession` itself has no destroyed
   flag.
   Outcome: a stale `Arc<MonitorSession>` can restart a scan after destroy.

4. Telemetry/stats pollers race with destroy after lookup but before snapshot.
   Outcome: polling succeeds against a session whose handle is already gone.

What does not happen:

- stale-handle aliasing to a different session: not possible with the current
  monotonic handle allocator
- raw free-after-handle-remove: not with the current `Arc<T>` registry design

## Critical Concurrent Paths

### 1. Session handle lifecycle: create -> start -> stop -> destroy vs concurrent telemetry poll

Risk: highest.

Why:

- The registry and the per-session state lock are separate synchronization
  domains.
- Lookups happen before state transitions.
- Destruction is not linearized with later per-session operations.
- Diagnostics can be logically restarted after destroy because there is no
  tombstone inside `MonitorSession`.

Minimum invariants that need `loom` coverage:

- once destroy commits, no later start/poll/update/stop should succeed on that
  logical session
- a running session must still be reachable by a live handle
- exactly one start wins when two starts race
- destroy must not silently orphan work

### 2. Telemetry ring: write (runtime) vs drain (JNI poll)

Risk: high-medium.

Why:

- Both proxy and tunnel telemetry use a bounded `VecDeque` behind a `Mutex`.
- Runtime threads push events while JNI polling drains the queue to JSON.
- This is memory-safe today, but correctness depends on event visibility and
  boundedness semantics.

Key properties to verify:

- queue length never exceeds configured capacity
- no event is observed twice
- drained events are a prefix of the produced event stream modulo explicit
  overflow eviction
- concurrent push and drain never corrupt queue state

### 3. Config reload during active connections

Risk: medium.

Native-Rust reality check:

- `RuntimeConfig` is immutable once wrapped in `Arc`.
- The mutable config-adjacent state is `RuntimePolicy`, adaptive planners,
  retry pacer, `active_clients`, and `EmbeddedProxyControl.network_snapshot`.

Why it still matters:

- active client threads concurrently read/write route selection state
- `retry_stealth` and adaptive resolvers have explicit lock-ordering
  assumptions
- future consumers of `network_snapshot` would add another cross-thread read
  path

This is more about deadlock/invariant safety than stale handles. It is a second
wave `loom` target after the lifecycle models are in place.

### 4. Handover-triggered restart: old session teardown vs new session setup

Risk: medium.

Current native state:

- proxy JNI can push `network_snapshot`, but runtime code does not currently
  read it in production
- tunnel telemetry updates `upstream_address` across restarts, but restart is
  still represented as old session teardown plus new session setup
- handles are monotonic, so an old handle never aliases the new session

The real concurrency issue is not handle reuse. It is:

- old session teardown racing with stale pollers
- old session destroy racing with late start on the same `Arc<T>`
- new session creation starting while old worker/telemetry still drains out

## Loom Test Targets

### Priority 1: Session registry concurrent create/poll/destroy

Target:

- a small extracted lifecycle model that combines:
  - `HandleRegistry`
  - per-session state machine
  - one or two representative JNI operations: `start`, `poll`, `destroy`

Why first:

- Existing tests already cover isolated primitives, but they do not cover the
  combined registry-plus-session interleaving that creates orphaned sessions.

Recommended model shapes:

- proxy model: `Idle | Running`, threads for `start`, `poll`, `destroy`
- tunnel model: `Ready | Starting | Running`, threads for `start`, `destroy`,
  optional `stats/telemetry`
- diagnostics model: `start_scan`, `destroy`, `poll`, with an internal
  `destroyed` tombstone added to the model to show the missing invariant

Core invariants:

- removed handle => future lookup fails
- no running state may exist without at least one live registry entry or an
  explicit borrowed reference that predated destroy
- destroy must either observe "already running" and fail, or linearize before
  later operations
- diagnostics destroy must forbid later `start_scan` on stale references

Model size estimate:

| Model | Initial thread shape | Estimated state space |
|-------|----------------------|-----------------------|
| Proxy lifecycle | main thread create, then 3 worker threads: start/poll/destroy | medium, roughly low thousands if kept to one handle and one state transition |
| Tunnel lifecycle | main thread create, then 2-3 worker threads: start/destroy/(telemetry or stats) | medium to large, low thousands; avoid modeling the async worker itself |
| Diagnostics lifecycle | 3 worker threads: start/destroy/poll | medium, hundreds to low thousands |

Keep `create` sequential in the first cut. A fully concurrent create/start/
poll/destroy model will blow up much faster and is not needed to expose the
current bug class.

### Priority 2: Event ring concurrent push/drain

Target:

- a tiny generic model for the bounded `VecDeque` event buffer used by proxy and
  tunnel telemetry

Why second:

- It is shared across both adapters.
- The data structure is simple enough to exhaustively model.

Suggested model:

- capacity 2 or 3 only
- one or two producer threads
- one drainer thread

Core invariants:

- length is always `<= capacity`
- overflow eviction is oldest-first only
- a drained event is returned at most once
- no panic or poisoned-state dependency

Model size estimate:

| Model | Initial thread shape | Estimated state space |
|-------|----------------------|-----------------------|
| Single producer + drain | 2 threads | small, tens to low hundreds |
| Two producers + drain | 3 threads | medium, hundreds to low thousands |

### Priority 3: Telemetry snapshot concurrent write/read

Target:

- a sync-only model for the telemetry snapshot path:
  atomics plus latest-string/latest-error state plus event drain

Why third:

- It verifies the whole JNI telemetry poll contract, not just the queue.
- It complements the existing state-machine tests and golden JSON tests.

Practical constraint:

- `ArcSwap` is not `loom`-aware.
- Do not try to `loom` the real `ArcSwap` implementation directly.
- Instead, model the same contract with a loom-friendly latest-value cell, for
  example `Arc<Mutex<Option<Arc<T>>>>`, and verify the higher-level invariants.

Core invariants:

- snapshot never sees torn state
- active/running counters remain internally consistent
- last-error/latest-string reads are either old or new, never invalid
- event drain remains exclusive under concurrent writers

Model size estimate:

| Model | Initial thread shape | Estimated state space |
|-------|----------------------|-----------------------|
| Writer + snapshot reader + event producer | 3 threads | medium, hundreds to about a thousand with tiny value domains |

## Loom Setup

### 1. Fix the current harness before adding new models

First blocker:

- `ripdpi-tunnel-android/src/session/loom.rs` currently shadows the external
  `loom` crate, so the crate does not build with `--features loom`.

Recommended fix:

- rename the module to `loom_tests.rs`, matching `ripdpi-android`
- or import the crate explicitly as `::loom`
- do this before expanding tunnel coverage

### 2. Standardize sync abstraction

`android-support/src/sync.rs` is the pattern to reuse:

- `std::sync::{Arc, Mutex}` on production builds
- `loom::sync::{Arc, Mutex}` under loom builds
- `AtomicU64` wrapped with an `AtomicUsize` compatibility helper because loom
  does not provide `AtomicU64`

Recommended rollout:

1. Keep the existing cargo feature gate: `--features loom` activates the
   dependency.
2. Add `#[cfg(loom)]` and `#[cfg(not(loom))]` alongside feature-gated code for
   loom-only model code and build-path selection.
3. Run loom builds with a custom cfg as well, for example:

```bash
RUSTFLAGS="--cfg loom" cargo test -p android-support --features loom -- loom
RUSTFLAGS="--cfg loom" cargo test -p ripdpi-android --features loom -- loom
RUSTFLAGS="--cfg loom" cargo test -p ripdpi-monitor --features loom -- loom
RUSTFLAGS="--cfg loom" cargo test -p ripdpi-tunnel-android --features loom -- loom
```

If the workspace enables `unexpected_cfgs` checking, declare `cfg(loom)` as an
expected custom cfg so these builds stay warning-clean.

### 3. Prefer extracted sync-only models over direct production execution

Use direct production code only when the code already goes through a sync
abstraction layer.

For the main targets here:

- registry tests should model the lifecycle logic with loom-friendly session
  objects, not actual JNI calls
- telemetry tests should model `ArcSwap`-backed latest values with a simpler
  loom-compatible cell
- tunnel async worker code should be explicitly out of scope for loom

### 4. Use `loom::thread` and tiny state domains

Guidelines:

- use `loom::model` plus `loom::thread::spawn`
- keep counts tiny: one handle, one or two events, one restart
- split large stories into separate models rather than one giant scenario
- avoid real I/O, Tokio runtime creation, JNI env objects, and wall-clock time

### 5. Keep loom in a separate test lane

Recommendation:

- local development: opt-in command or script
- CI: separate manual/nightly lane rather than every PR by default

Reasons:

- `loom` tests are slow
- schedule explosion is sensitive to even small model growth
- they are best used as focused regression tests for specific invariants

## Constraints

- `loom` does not model Tokio task scheduling, so `ripdpi-tunnel-core` async
  paths such as `io_loop`, DNS worker tasks, and UDP association tasks should be
  excluded from the first `loom` rollout.
- `OnceLock`, `OnceCell`, and `ArcSwap` should be treated as boundaries and
  replaced with local loom-friendly models where needed.
- JNI env objects and Android platform side effects should stay outside the
  model; test the state machine and synchronization contract, not the FFI.

## Complementary Tools

- Use `--cfg loom` in loom test builds together with the existing `loom`
  feature flag.
- Use `#[cfg(loom)]` and `#[cfg(not(loom))]` to keep loom-only helpers and
  replacements out of production builds.
- Keep the existing non-loom tests:
  state-machine tests, fault injection, and golden telemetry tests still cover
  behavior that loom should not duplicate.

## Async Alternative: `shuttle`

For the async tunnel code that loom cannot cover, evaluate the `shuttle` crate.

Best candidates:

- `ripdpi-tunnel-core/src/io_loop.rs`
- `ripdpi-tunnel-core/src/io_loop/udp_assoc.rs`
- `ripdpi-tunnel-core/src/io_loop/dns_intercept.rs`

Why:

- these paths already use Tokio channels and task scheduling
- the bug class there is task interleaving, not only mutex/atomic ordering

Recommended split:

- use `loom` for sync-only registry, telemetry, and snapshot invariants
- evaluate `shuttle` for async cancellation, queue, and task-ordering bugs

## Recommended Rollout

1. Fix `ripdpi-tunnel-android`'s current `loom` compile blocker.
2. Add a shared lifecycle model for registry-backed sessions and make it fail on
   the current proxy/tunnel/diagnostics stale-handle races.
3. Decide the intended destroy semantics:
   either tombstone sessions and reject stale refs, or make destroy linearize
   with all later session operations.
4. Add the bounded event-ring model and telemetry snapshot model.
5. Put the loom suite behind a dedicated local command and optional CI lane.
6. Evaluate `shuttle` separately for async tunnel internals.
