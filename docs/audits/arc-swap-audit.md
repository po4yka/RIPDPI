# Shared State and arc-swap Migration Audit

Audited: 2026-03-24 against `arc-swap 1.x`, `std::sync::Mutex`, `std::sync::atomic`.

## Summary

RIPDPI's native Rust layer shares mutable state between JNI polling threads
and the runtime (proxy workers, tunnel tasks, diagnostic scans). The primary
synchronization primitives today are `std::sync::Mutex`, `AtomicU64/AtomicBool`,
and `flume` channels. No `RwLock` is used anywhere.

This audit maps every `Arc<Mutex<T>>` in `native/rust/`, evaluates whether
`arc-swap` can replace the mutex for lock-free reads, and proposes a phased
migration for the highest-value targets.

## Shared State Inventory

### JNI-to-Runtime Boundary (Cross-Thread)

| # | Struct | Field | Type `T` | Sync Primitive | Writer(s) | Writer Freq | Reader(s) | Reader Freq | Contention Risk |
|---|--------|-------|----------|----------------|-----------|-------------|-----------|-------------|-----------------|
| 1 | `EmbeddedProxyControl` | `network_snapshot` | `Option<NetworkSnapshot>` | `Arc<Mutex<_>>` | JNI thread (Android `NetworkCallback`) | Rare (network change) | Proxy runtime (per-connection route planning) | Hot (per-conn) | **Moderate**: rare writer but reader is on the hot path; mutex acquisition adds latency to every connection setup |
| 2 | `ProxyTelemetryState` | `strings` | `TelemetryStrings` (11 `Option<String>` + event VecDeque) | `Mutex<_>` | Blocking worker threads (per-event: error, route, target) | Moderate (per-event) | JNI poll thread | ~1/sec | Low: brief lock for string swap; events already migrated to `flume` |
| 3 | `TunnelTelemetryState` | `upstream_address` | `Option<String>` | `Mutex<_>` | Tunnel async task (on start) | Once per session | JNI poll thread | ~1/sec | Negligible |
| 4 | `TunnelTelemetryState` | `last_error` | `Option<String>` | `Mutex<_>` | Tunnel async task (on error) | Rare | JNI poll thread | ~1/sec | Negligible |
| 5 | `MonitorSession` | `shared` | `SharedState` (progress + report + passive events) | `Arc<Mutex<_>>` | Scan worker thread (`set_progress`, `finish_with_report`) | Continuous during scan | JNI poll thread | Polled | Low: brief lock for struct replace; scan is short-lived |

**Source files:**
- #1: `ripdpi-runtime/src/lib.rs:75`
- #2: `ripdpi-android/src/telemetry.rs:123`
- #3: `ripdpi-tunnel-android/src/telemetry.rs:73`
- #4: `ripdpi-tunnel-android/src/telemetry.rs:74`
- #5: `ripdpi-monitor/src/lib.rs:52`

### Runtime-Internal (Worker-to-Worker)

| # | Struct | Field | Type `T` | Sync Primitive | Writer(s) | Writer Freq | Reader(s) | Reader Freq | Contention Risk |
|---|--------|-------|----------|----------------|-----------|-------------|-----------|-------------|-----------------|
| 6 | `RuntimeState` | `cache` | `RuntimePolicy` | `Arc<Mutex<_>>` | Worker threads (autolearn update) | Per autolearn event | Worker threads (route selection) | Per connection | Low-moderate: read-modify-write cycle |
| 7 | `RuntimeState` | `adaptive_fake_ttl` | `AdaptiveFakeTtlResolver` | `Arc<Mutex<_>>` | Worker threads (TTL observation) | Per connection | Worker threads (plan selection) | Per connection | Low: short critical section |
| 8 | `RuntimeState` | `adaptive_tuning` | `AdaptivePlannerResolver` | `Arc<Mutex<_>>` | Worker threads (outcome observation) | Per connection | Worker threads (plan selection) | Per connection | Low: short critical section |
| 9 | `RuntimeState` | `retry_stealth` | `RetryPacer` | `Arc<Mutex<_>>` | Worker threads (failure recording) | Per failure | Worker threads (retry delay) | Per retry | Low |
| 10 | `HealthRegistry` | `inner` | `HealthRegistryInner` | `Arc<Mutex<_>>` | DNS observer callbacks (EWMA update) | Per DNS query | Ranking queries | Per resolver selection | Low: short EWMA arithmetic |
| 11 | `LatencyHistogram` | `inner` | `Histogram<u64>` | `Arc<Mutex<_>>` | Worker threads (per-connection latency) | Per connection | JNI snapshot | ~1/sec | Low: histogram record is fast; ~2KB mutable state |

**Source files:**
- #6-9: `ripdpi-runtime/src/runtime/state.rs:22-25`
- #10: `ripdpi-dns-resolver/src/health.rs:78`
- #11: `ripdpi-telemetry/src/lib.rs:16`

### Infrastructure (Low-Frequency)

| # | Struct | Field | Type `T` | Sync Primitive | Writer(s) | Writer Freq | Reader(s) | Reader Freq | Contention Risk |
|---|--------|-------|----------|----------------|-----------|-------------|-----------|-------------|-----------------|
| 12 | `HandleRegistry` | `inner` | `HashMap<u64, Arc<T>>` | `Mutex<_>` | JNI create/destroy | Once per session lifecycle | Every JNI call (get) | Per JNI call | Negligible: 1-2 sessions typical |
| 13 | `TunnelSession` | `last_error` | `Option<String>` | `Arc<Mutex<_>>` | Worker thread on panic/error | Rare | JNI destroy path | Once | Negligible |

**Source files:**
- #12: `android-support/src/lib.rs:29`
- #13: `ripdpi-tunnel-android/src/session/registry.rs:21`

### Already Lock-Free (No Change Needed)

| Primitive | Count | Usage | Ordering |
|-----------|-------|-------|----------|
| `AtomicU64` | 15+ (proxy) + 8 (tunnel stats) + 2 (CLI) | Telemetry counters (sessions, errors, routes) | Relaxed |
| `AtomicBool` | 3 (proxy/tunnel running) + 19 (shutdown signals) | State flags, cancellation | Release/Acquire for shutdown; Relaxed for telemetry |
| `AtomicUsize` | 1 | Active client slot counter | AcqRel (CAS loop) |
| `AtomicI64` | 2 | Last route/autolearn group index | Relaxed |
| `flume::bounded` | 2 (proxy events, tunnel events) | Event ring buffers (128 capacity) | N/A (channel) |

## arc-swap Applicability Matrix

| # | Target | Read:Write Ratio | Immutable Snapshot? | Clone Cost | arc-swap Verdict | Action |
|---|--------|-------------------|---------------------|------------|-----------------|--------|
| 1 | `network_snapshot` | **High** (per-conn read, rare write) | Yes (`NetworkSnapshot` is value type) | Cheap (small struct) | **Excellent** | **Migrate (Phase 1)** |
| 2 | `ProxyTelemetryState::strings` | ~1:N (1 read/sec, N writes/event) | Yes (with clone-modify-store) | Cheap (11 `Option<String>` pointers) | **Good** (needs `rcu()` for concurrent writers) | **Migrate (Phase 2a)** |
| 3 | `TunnelTelemetryState::upstream_address` | ~1:0 (1 read/sec, written once) | Yes | Trivial | **Good** | **Migrate (Phase 2b)** |
| 4 | `TunnelTelemetryState::last_error` | ~1:rare | Yes | Trivial | **Good** | **Migrate (Phase 2b)** |
| 5 | `MonitorSession::shared` | Polled:continuous | **No** (`std::mem::take` drain pattern) | N/A | **Poor** | Skip |
| 6-9 | `RuntimeState::cache/adaptive_*` | ~1:1 (worker:worker) | **No** (read-modify-write cycle) | N/A | **Poor** | Skip |
| 10 | `HealthRegistry` | ~1:1 | **No** (EWMA read-modify-write) | N/A | **Poor** | Skip |
| 11 | `LatencyHistogram` | ~N:1 | **No** (~2KB mutable `Histogram`) | Expensive | **Not applicable** | Skip |
| 12 | `HandleRegistry` | Low:low | **No** (insert/remove HashMap) | N/A | **Not worth it** | Skip |
| 13 | `TunnelSession::last_error` | Rare:rare | Yes | Trivial | Marginal benefit | Skip (too rare to matter) |

### Why Skipped Targets Stay as Mutex

**RuntimeState fields (cache, adaptive_fake_ttl, adaptive_tuning, retry_stealth):**
Both writers and readers are proxy worker threads performing read-modify-write
cycles. `arc-swap` would require `load() -> clone -> modify -> store()` with a
CAS retry loop, which is no better than `Mutex` for this pattern and adds
allocation overhead per write.

**LatencyHistogram:**
`Histogram<u64>` is ~2KB of mutable internal state. `record()` mutates
in-place. arc-swap would require cloning ~2KB per write (per-connection),
turning an O(1) mutex operation into O(N) clone. Net negative.

**MonitorSession::shared:**
`poll_passive_events_json` calls `std::mem::take(&mut shared.passive_events)`,
which is inherently a mutable drain. arc-swap cannot model drain semantics
without a separate channel (which `flume` already provides for event rings).

**HandleRegistry:**
Typically holds 1-2 sessions. Insert on create, remove on destroy. Lock
contention is negligible. The complexity of migrating a `HashMap` behind
arc-swap (which requires full clone on mutation) is not justified.

**HealthRegistry:**
`record_endpoint_outcome` reads the current EWMA score, applies the
exponential moving average formula, and writes back. This is a textbook
read-modify-write that requires mutual exclusion.

## Implementation Plan

### Dependency Addition

Add `arc-swap` to the workspace:

```toml
# native/rust/Cargo.toml [workspace.dependencies]
arc-swap = "1"
```

`arc-swap` is a pure-Rust crate with zero transitive dependencies (~3K SLOC).
No impact on binary size or cross-compilation for Android NDK targets.

### Phase 1: `EmbeddedProxyControl::network_snapshot`

**Why first:** Classic SPSC pattern with the best read:write ratio. Writer is
the JNI thread (rare). Reader is the proxy runtime hot path (per-connection).
`NetworkSnapshot` is a small value struct, already `Clone`. Smallest possible
change to validate the dependency addition and build pipeline.

**File:** `ripdpi-runtime/src/lib.rs`

**Current code (lines 69-142):**
```rust
pub struct EmbeddedProxyControl {
    shutdown: Arc<AtomicBool>,
    telemetry: Option<Arc<dyn RuntimeTelemetrySink>>,
    runtime_context: Option<ProxyRuntimeContext>,
    network_snapshot: Arc<std::sync::Mutex<Option<NetworkSnapshot>>>,
}

impl EmbeddedProxyControl {
    // ...
    pub fn update_network_snapshot(&self, snapshot: NetworkSnapshot) {
        if let Ok(mut slot) = self.network_snapshot.lock() {
            *slot = Some(snapshot);
        }
    }

    pub fn current_network_snapshot(&self) -> Option<NetworkSnapshot> {
        self.network_snapshot.lock().ok().and_then(|guard| guard.clone())
    }
}
```

**After migration:**
```rust
use arc_swap::ArcSwap;

pub struct EmbeddedProxyControl {
    shutdown: Arc<AtomicBool>,
    telemetry: Option<Arc<dyn RuntimeTelemetrySink>>,
    runtime_context: Option<ProxyRuntimeContext>,
    network_snapshot: Arc<ArcSwap<Option<NetworkSnapshot>>>,
}

impl EmbeddedProxyControl {
    fn new_with_context(/* ... */) -> Self {
        Self {
            // ...
            network_snapshot: Arc::new(ArcSwap::from_pointee(None)),
        }
    }

    pub fn update_network_snapshot(&self, snapshot: NetworkSnapshot) {
        self.network_snapshot.store(Arc::new(Some(snapshot)));
    }

    pub fn current_network_snapshot(&self) -> Option<NetworkSnapshot> {
        (**self.network_snapshot.load()).clone()
    }
}
```

**Cargo.toml change:** Add `arc-swap = { workspace = true }` to `ripdpi-runtime`.

**Estimated diff:** ~15 lines in `lib.rs`, 2 lines across Cargo.toml files.

**Behavioral change:** `update_network_snapshot` no longer silently drops the
update if the mutex is poisoned (there is no mutex to poison). This is correct:
a poisoned mutex here would indicate a panic in `current_network_snapshot`,
which cannot panic since it only clones.

### Phase 2a: `ProxyTelemetryState::strings`

**File:** `ripdpi-android/src/telemetry.rs`

**Current pattern:** `strings: Mutex<TelemetryStrings>`. Worker threads acquire
the mutex per-event to update one or two string fields. JNI `snapshot()` acquires
the mutex to clone all 11 fields and drain the event VecDeque.

**Note:** Events are already migrated to `flume::bounded(128)` (from the
channels audit). The `strings` mutex now only protects the 11 `Option<String>`
fields. The `events` VecDeque in `TelemetryStrings` may still exist for
backward compatibility but is drained via `flume` in the current code.

**After migration:**
```rust
use arc_swap::ArcSwap;

pub(crate) struct ProxyTelemetryState {
    // ... atomics unchanged ...
    strings: ArcSwap<TelemetryStrings>,
    // ... histograms unchanged ...
}
```

**Writer pattern (using `rcu` for correctness):**
```rust
fn update_strings<F: Fn(&TelemetryStrings) -> TelemetryStrings>(&self, f: F) {
    self.strings.rcu(|current| f(current));
}

// Example: on_client_error
pub(crate) fn on_client_error(&self, error: String) {
    self.total_errors.fetch_add(1, Ordering::Relaxed);
    self.update_strings(|s| {
        let mut new = s.clone();
        new.last_error = Some(error.clone());
        new
    });
}
```

**Reader pattern (snapshot):**
```rust
pub(crate) fn snapshot(&self) -> NativeRuntimeSnapshot {
    let strings_guard = self.strings.load();
    let strings = &**strings_guard;
    // Read fields directly from the guard -- no mutex, no blocking
    NativeRuntimeSnapshot {
        listener_address: strings.listener_address.clone(),
        // ... etc ...
    }
}
```

**Correctness: lost-update prevention.** Without `rcu()`, two concurrent
workers could each `load()` the same snapshot, modify different fields, and the
last `store()` would silently drop the other's update. `rcu()` retries on
conflict (CAS loop), guaranteeing all updates are applied. At the observed
write frequency (moderate, per-event), CAS retries are extremely rare.

**Write sites to update (~10):**
- `mark_running` (listener_address)
- `mark_stopped` (no string change, only events -- can skip if events are flume-only)
- `on_client_error` (last_error)
- `on_client_io_error` (last_error, last_failure_class)
- `on_route_selected` (last_target, last_host, upstream_address)
- `on_route_advanced` (last_route_group via atomic, but upstream_address/rtt via strings)
- `on_failure_classified` (last_failure_class, last_fallback_action)
- `on_retry_paced` (last_retry_reason)
- `on_upstream_connected` (upstream_address, upstream_rtt_ms)
- `on_autolearn_event` (last_autolearn_host, last_autolearn_action)

**Cargo.toml change:** Add `arc-swap = { workspace = true }` to `ripdpi-android`.

**Estimated diff:** ~80-100 lines in `telemetry.rs`.

### Phase 2b: `TunnelTelemetryState` String Fields

**File:** `ripdpi-tunnel-android/src/telemetry.rs`

**Current:**
```rust
pub(crate) struct TunnelTelemetryState {
    // ... atomics ...
    upstream_address: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
    // ... flume + histogram ...
}
```

**After migration:**
```rust
use arc_swap::ArcSwapOption;

pub(crate) struct TunnelTelemetryState {
    // ... atomics unchanged ...
    upstream_address: ArcSwapOption<String>,
    last_error: ArcSwapOption<String>,
    // ... flume + histogram unchanged ...
}
```

**Writer:**
```rust
pub(crate) fn mark_started(&self, upstream: String) {
    self.running.store(true, Ordering::Relaxed);
    self.total_sessions.fetch_add(1, Ordering::Relaxed);
    self.upstream_address.store(Some(Arc::new(upstream.clone())));
    self.push_event("tunnel", "info", format!("tunnel started upstream={upstream}"));
}

pub(crate) fn record_error(&self, error: String) {
    self.total_errors.fetch_add(1, Ordering::Relaxed);
    self.last_error.store(Some(Arc::new(error.clone())));
    self.push_event("tunnel", "warn", format!("tunnel error: {error}"));
}
```

**Reader:**
```rust
// In snapshot():
upstream_address: self.upstream_address.load().as_ref().map(|a| (**a).clone()),
last_error: self.last_error.load().as_ref().map(|a| (**a).clone()),
```

No lost-update concern: `upstream_address` and `last_error` are independent
fields written by a single task. No concurrent writers to the same field.

**Cargo.toml change:** Add `arc-swap = { workspace = true }` to `ripdpi-tunnel-android`.

**Estimated diff:** ~20 lines.

## Memory Ordering Considerations

### arc-swap Guarantees

`arc-swap` provides the following ordering guarantees (from its documentation):

- `store()` uses Release ordering internally
- `load()` uses Acquire ordering internally
- This means: any data written before `store()` is visible after `load()` on
  another thread (happens-before relationship)

### Comparison with Current Mutex Guarantees

`Mutex::lock()` provides a full acquire barrier on lock, and release on unlock.
This is strictly stronger than arc-swap's guarantees because mutex also provides
mutual exclusion (only one thread in the critical section).

For the migrated targets, mutual exclusion is **not required** because:

1. **`network_snapshot` (#1):** Single writer (JNI), single conceptual reader
   (runtime). The value is an immutable snapshot replaced atomically. No
   read-modify-write.

2. **`TunnelTelemetryState` strings (#3, #4):** Single writer per field. No
   concurrent writes to the same field.

3. **`ProxyTelemetryState::strings` (#2):** Multiple concurrent writers to
   different fields within the same struct. `rcu()` provides CAS-based mutual
   exclusion for the struct-level swap, equivalent to a mutex for correctness
   but without blocking readers.

### Ordering for Telemetry Atomics

The existing `AtomicU64` counters use `Ordering::Relaxed`. This is correct
because telemetry is best-effort: a JNI poll seeing a slightly stale counter is
acceptable. The arc-swap migration does not change this -- the atomics remain
Relaxed, and the arc-swap load/store (Acquire/Release) only governs the string
fields.

There is no requirement for a total ordering between atomic counters and
arc-swap string fields. The `snapshot()` function reads both independently and
assembles them into a JSON snapshot. Minor inconsistency between counters and
strings within a single snapshot is acceptable for diagnostic telemetry.

## Testing Strategy

### Phase 1 Verification

1. **Existing unit tests:** `cargo test -p ripdpi-runtime` covers the
   `EmbeddedProxyControl` API contract:
   - `network_snapshot_starts_empty_and_accepts_update`
   - `cloned_proxy_controls_share_snapshot_slot`

2. **Integration tests:** `cargo test -p ripdpi-android` covers JNI-level
   telemetry and lifecycle tests.

3. **Cross-compilation:** `cargo build --target aarch64-linux-android -p ripdpi-android`
   to verify `arc-swap` compiles for Android NDK targets.

### Phase 2 Verification

1. **Golden snapshot tests:** Both `ripdpi-android` and `ripdpi-tunnel-android`
   have golden JSON tests that serialize telemetry snapshots and compare against
   checked-in golden files. These tests will catch any field name or value
   regressions:
   - `ripdpi-android/src/telemetry.rs` golden tests
   - `ripdpi-tunnel-android/src/telemetry.rs` golden tests

2. **Behavioral equivalence:** The golden tests verify that the serialized
   output is byte-identical before and after migration. Run with
   `RIPDPI_BLESS_GOLDENS=1` only if the field shape changes (it should not).

### Loom Compatibility

`arc-swap` is **not** compatible with `loom` (the concurrency model checker).
However, this is not a concern because:

- The `loom`-abstracted sync primitives live in `ripdpi-runtime/src/sync.rs`
- The migrated fields (`network_snapshot`, telemetry strings) use
  `std::sync::Mutex` directly, not the loom-abstracted wrappers
- No loom tests exercise the JNI-to-runtime telemetry boundary

Add a comment in the code noting this decision for future maintainers.

### Stress Testing (Future)

For high-confidence validation, consider adding a stress test that:

1. Spawns N writer threads updating telemetry strings at maximum rate
2. Spawns 1 reader thread calling `snapshot()` in a tight loop
3. Runs for 10 seconds and verifies:
   - No panics or data corruption
   - All writes are eventually visible (eventual consistency)
   - Reader never blocks (measure p99 latency of `snapshot()`)

This can use `std::thread` directly (no loom needed) and would complement the
existing golden tests with a concurrency-focused regression guard.

## Migration Sequence

| Phase | Scope | Estimated Diff | Crate Changes | Risk |
|-------|-------|----------------|---------------|------|
| 1 | `EmbeddedProxyControl::network_snapshot` | ~30 lines | `ripdpi-runtime` | Minimal: SPSC, no concurrent writers |
| 2a | `ProxyTelemetryState::strings` | ~80-100 lines | `ripdpi-android` | Low: `rcu()` handles concurrent writers; golden tests guard output |
| 2b | `TunnelTelemetryState::upstream_address` + `last_error` | ~20 lines | `ripdpi-tunnel-android` | Minimal: independent fields, single writer each |

**Recommended PR structure:**
- Phase 1 as a standalone PR (validates dependency, build pipeline, cross-compile)
- Phase 2a + 2b as a single follow-up PR (same pattern, related crates)

## Future Considerations

### RuntimeState Fields (Phase 3, Deferred)

If profiling reveals mutex contention on `RuntimeState::cache` (the routing
policy cache), the access pattern could potentially be restructured:

- Build the complete `RuntimePolicy` snapshot periodically (e.g., after each
  autolearn batch) and swap it atomically via `ArcSwap`
- Workers read the latest policy via `load()` (zero-cost)
- Autolearn updates accumulate in a separate buffer and are flushed to the
  canonical policy on a timer

This would require a larger refactor of the autolearn/policy update flow and
is not justified without profiling evidence of contention.

### LatencyHistogram Alternative

If histogram contention becomes measurable, an alternative to arc-swap is
per-thread histograms merged on snapshot:

- Each worker thread owns a thread-local `Histogram`
- `snapshot()` iterates thread-locals and merges (HdrHistogram supports `add`)
- Eliminates all shared-state contention for histogram recording

This is a larger change and should only be pursued if latency recording shows
up in profiling.
