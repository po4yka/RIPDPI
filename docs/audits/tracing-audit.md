# Logging and Structured Telemetry Audit

Audited: 2026-03-24 against `tracing 0.1`, `tracing-subscriber 0.3`, `tracing-log 0.2`.

## Summary

RIPDPI's native Rust layer has already migrated its library crates to
`tracing::*` macros: the CLI, `ripdpi-tunnel-core`, and `ripdpi-runtime` are
fully tracing-native. The remaining `log::` usage is confined to the
`android-support` bridge layer (intentional: `AndroidLogLayer` converts tracing
events to `log::*` for logcat) and `log::LevelFilter` as a type in
`ripdpi-monitor`. The Android telemetry crates (`ripdpi-android`,
`ripdpi-tunnel-android`, `ripdpi-monitor`) bypass `tracing` for their event
rings, using raw `log_with_level()` calls and manual `push_event` plumbing.
Three independent bounded event rings (`VecDeque<NativeRuntimeEvent>`) duplicate
the same push/FIFO-discard/drain-on-poll pattern across proxy, tunnel, and
diagnostics subsystems.

The Android logging bridge (`AndroidLogLayer`) already converts `tracing`
events to `log::*` macros for logcat, and `LogTracer` bridges `log::*` events
into the `tracing` subscriber. This means both ecosystems coexist at runtime,
but the ad-hoc event rings bypass `tracing` entirely. A custom `tracing::Layer`
can replace these rings, unifying structured telemetry under one subscriber
stack.

## Current Logging Landscape

| Crate | Mechanism | Output Target | Notes |
|-------|-----------|---------------|-------|
| `ripdpi-cli` | `tracing::*` macros + `tracing_subscriber::fmt` | stderr | Fully tracing-native. `EnvFilter` with `RUST_LOG` override. `LogTracer` bridges `log::` events. |
| `android-support` | `AndroidLogLayer` (custom `tracing::Layer`) + `android_logger` + `LogTracer` | Android logcat | Converts tracing events -> `log::*` macros -> logcat via `android_logger`. Exports `log_with_level()` free function for dynamic-level log emission. |
| `ripdpi-android` | `log_with_level()` + manual event ring (128) | logcat + event ring | Implements `RuntimeTelemetrySink`. All logging goes through `log_with_level()`, not tracing macros. `ProxyTelemetryState` owns the ring. |
| `ripdpi-tunnel-android` | `log_with_level()` + manual event ring (128) | logcat + event ring | `TunnelTelemetryState` owns the ring. `ArcSwapOption` for string fields. |
| `ripdpi-tunnel-core` | `tracing::*` macros | via subscriber | Fully tracing-native across `io_loop`, `dns_intercept`, `tcp_accept`, `bridge`, `udp_assoc` modules. |
| `ripdpi-runtime` | `tracing::*` macros | via subscriber | Fully tracing-native. No `log::` macros remain. |
| `ripdpi-monitor` | `log_with_level()` + manual event ring (256) | logcat + event ring | Uses `log::LevelFilter` as a type only. Events flow through `SharedState::passive_events` via `log_with_level()`. |
| `ripdpi-ws-tunnel` | `tracing::info!` (benchmarks only) | via subscriber | Single benchmark output site. Fully tracing-native. |
| `ripdpi-telemetry` | None | N/A | Histogram data structures only. No logging. |
| 8 other crates | None | N/A | Pure-logic crates with no logging: `ripdpi-session`, `ripdpi-dns-resolver`, `ripdpi-packets`, `ripdpi-config`, `ripdpi-desync`, `ripdpi-tun-driver`, `ripdpi-proxy-config`, `ripdpi-tunnel-config`, `ripdpi-failure-classifier`. |

**Source files:**
- CLI init: `ripdpi-cli/src/main.rs:66` (`init_logging`)
- CLI telemetry sink: `ripdpi-cli/src/telemetry.rs`
- Android bridge: `android-support/src/lib.rs:58` (`init_android_logging`)
- Android Layer: `android-support/src/lib.rs:184` (`AndroidLogLayer`)
- Proxy ring: `ripdpi-android/src/telemetry.rs:126` (`events: Mutex<VecDeque<NativeRuntimeEvent>>`)
- Tunnel ring: `ripdpi-tunnel-android/src/session/telemetry.rs`
- Monitor ring: `ripdpi-monitor/src/connectivity.rs` + `util.rs:20` (`MAX_PASSIVE_EVENTS`)

## Tracing Adoption Status

| Crate | `tracing::` native | `log::` macros | Custom event ring | Needs migration |
|-------|-------------------|---------------|-------------------|-----------------|
| `ripdpi-cli` | Yes | No | No | No |
| `ripdpi-tunnel-core` | Yes | No | No | No |
| `android-support` | Yes (Layer impl) | Yes (bridge target) | No | No (already tracing-aware) |
| `ripdpi-android` | No | Yes (via `log_with_level`) | Yes (proxy, 128 cap) | Yes (Phase 1 + 2) |
| `ripdpi-tunnel-android` | No | Yes (via `log_with_level`) | Yes (tunnel, 128 cap) | Yes (Phase 1 + 2) |
| `ripdpi-runtime` | Yes | No | No | No |
| `ripdpi-monitor` | No | No (only `LevelFilter` type) | Yes (monitor, 256 cap) | Yes (Phase 2 only) |
| `ripdpi-ws-tunnel` | Yes | No | No | No |

## Event Ring Architecture (Current State)

### Ring Inventory

| Ring | Owner Struct | Location | Capacity | Event Type |
|------|-------------|----------|----------|------------|
| Proxy | `ProxyTelemetryState::events` | `ripdpi-android/src/telemetry.rs:126` | 128 (`MAX_PROXY_EVENTS`) | `NativeRuntimeEvent` |
| Tunnel | `TunnelTelemetryState::events` | `ripdpi-tunnel-android/src/session/telemetry.rs` | 128 (`MAX_TUNNEL_EVENTS`) | `NativeRuntimeEvent` |
| Monitor | `SharedState::passive_events` | `ripdpi-monitor/src/types/mod.rs` | 256 (`MAX_PASSIVE_EVENTS`) | `NativeSessionEvent` |

### Common Pattern

All three rings use the same design:

```
Storage:    Mutex<VecDeque<EventType>>
Push:       lock -> if len >= MAX { pop_front() } -> push_back(event)
Drain:      lock -> drain(..).collect()  (or mem::take)
Serialize:  serde_json::to_string(&snapshot)  where snapshot includes drained events
JNI return: jstring (JSON)
```

### Event Struct

```rust
// Used by proxy and tunnel (ripdpi-android/src/telemetry.rs:36)
pub(crate) struct NativeRuntimeEvent {
    pub(crate) source: String,      // e.g., "proxy", "tunnel", "autolearn"
    pub(crate) level: String,       // "info", "warn", "error"
    pub(crate) message: String,     // Free-form event message
    pub(crate) created_at: u64,     // Timestamp in milliseconds since epoch
}

// Used by monitor (ripdpi-monitor/src/types/scan.rs:79)
// Identical fields, different struct name
pub struct NativeSessionEvent {
    pub source: String,
    pub level: String,
    pub message: String,
    pub created_at: u64,
}
```

### Double-Logging Path

Each event is logged twice in the Android crates:

1. `log_with_level()` -> `log::*` macro -> `android_logger` -> logcat
2. `push_event_internal()` -> `VecDeque::push_back()` -> ring

Example from `ProxyTelemetryState::mark_running`:

```rust
fn mark_running(&self, bind_addr: String, max_clients: usize, group_count: usize) {
    self.running.store(true, Ordering::Relaxed);
    let message = format!("listener started addr={bind_addr} maxClients={max_clients} groups={group_count}");
    self.log_line("proxy", "info", &message);           // -> logcat
    self.update_strings(|s| s.listener_address = Some(bind_addr.clone()));
    self.push_event_internal("proxy", "info", message);  // -> ring
}
```

A unified tracing Layer would replace both calls with a single
`tracing::info!(...)` that flows through both `AndroidLogLayer` (-> logcat)
and the proposed `EventRingLayer` (-> ring).

## Unification Plan

### Phase 1: Migrate `log::` Call Sites to `tracing::` Macros -- COMPLETE

Library crates (`ripdpi-runtime`, `ripdpi-ws-tunnel`) are already fully
tracing-native. `ripdpi-monitor` uses `log::LevelFilter` as a type but has no
`log::` macro call sites. The only `log::` macros in the workspace are in
`android-support/src/lib.rs` (the bridge layer), which are intentional: they are
the output endpoint of `AndroidLogLayer` and `log_with_level()`.

No code changes needed for Phase 1.

### Phase 2a: `EventRingLayer` Implementation

**Scope:** New custom `tracing::Layer` in `android-support` that captures
structured events into bounded ring buffers, replacing the ad-hoc rings.

**Architecture:**

```
tracing_subscriber::registry()
    .with(AndroidLogLayer)        // existing: events -> logcat
    .with(EventRingLayer::new(    // new: events -> bounded rings
        RingConfig {
            proxy_capacity: 128,
            tunnel_capacity: 128,
            monitor_capacity: 256,
        }
    ))
    .try_init()
```

**`EventRingLayer` struct:**

```rust
pub struct EventRingLayer {
    proxy_ring:  Arc<Mutex<VecDeque<NativeRuntimeEvent>>>,
    tunnel_ring: Arc<Mutex<VecDeque<NativeRuntimeEvent>>>,
    monitor_ring: Arc<Mutex<VecDeque<NativeRuntimeEvent>>>,
    config: RingConfig,
}

impl<S> Layer<S> for EventRingLayer
where
    S: Subscriber + for<'span> LookupSpan<'span>,
{
    fn on_event(&self, event: &tracing::Event<'_>, _ctx: Context<'_, S>) {
        let mut visitor = MessageFieldFormatter::default();
        event.record(&mut visitor);
        let metadata = event.metadata();

        // Route to correct ring based on a "ring" field or target prefix
        let ring_target = visitor.ring_field()
            .or_else(|| infer_ring_from_target(metadata.target()));

        let (ring, capacity) = match ring_target {
            Some("proxy")  => (&self.proxy_ring,  self.config.proxy_capacity),
            Some("tunnel") => (&self.tunnel_ring,  self.config.tunnel_capacity),
            Some("monitor") => (&self.monitor_ring, self.config.monitor_capacity),
            _ => return,  // Events without ring routing go only to AndroidLogLayer
        };

        let event = NativeRuntimeEvent {
            source: visitor.source_field().unwrap_or(metadata.target()).to_string(),
            level: metadata.level().as_str().to_string(),
            message: visitor.finish(metadata.target()),
            created_at: now_ms(),
        };

        let mut guard = ring.lock().unwrap_or_else(PoisonError::into_inner);
        if guard.len() >= capacity {
            guard.pop_front();
        }
        guard.push_back(event);
    }
}
```

**Ring routing strategy:** Use an explicit `ring = "proxy"` field on tracing
events that should be captured. Events without a `ring` field flow only through
`AndroidLogLayer` to logcat. This avoids brittle target-prefix matching and
makes ring capture opt-in.

Example at call site:
```rust
tracing::info!(ring = "proxy", source = "proxy", "listener started addr={bind_addr}");
```

**Drain API:** Telemetry state structs hold `Arc` references to their ring and
call `drain()` during snapshot assembly, same as today:

```rust
impl EventRingLayer {
    pub fn drain_proxy(&self) -> Vec<NativeRuntimeEvent> {
        self.proxy_ring.lock().unwrap_or_else(PoisonError::into_inner).drain(..).collect()
    }
    // ... drain_tunnel, drain_monitor
}
```

**Estimated diff:** ~200 lines new in `android-support`.

### Phase 2b: Remove `push_event` Plumbing

**Scope:** Migrate `ProxyTelemetryState`, `TunnelTelemetryState`, and
`SharedState` to emit `tracing::*` events instead of calling `push_event` /
`push_event_internal` / `log_with_level` directly.

**Changes per subsystem:**

**Proxy (`ripdpi-android/src/telemetry.rs`):**
- Remove `events: Mutex<VecDeque<NativeRuntimeEvent>>` field
- Remove `push_event_internal()` and `log_line()` methods
- Replace each `log_line + push_event_internal` pair with a single tracing call:

```rust
// Before:
self.log_line("proxy", "info", &message);
self.push_event_internal("proxy", "info", message);

// After:
tracing::info!(ring = "proxy", source = "proxy", "{message}");
```

**Tunnel (`ripdpi-tunnel-android/src/session/telemetry.rs`):**
- Remove `events: Mutex<VecDeque<NativeRuntimeEvent>>` field
- Remove `push_event()` and `drain_events()` methods
- Replace with tracing calls

**Monitor (`ripdpi-monitor/src/connectivity.rs`):**
- Remove `passive_events: VecDeque<NativeSessionEvent>` from `SharedState`
- Replace `push_event()` free function with tracing calls

**Snapshot assembly changes:**
- `ProxyTelemetryState::snapshot()` calls `layer.drain_proxy()` instead of
  `self.events.lock().drain(..)`
- Similarly for tunnel and monitor

**Golden re-bless required:** Message format may change slightly because the
Layer's `MessageFieldFormatter` controls serialization of structured fields.
See "Impact on Golden Contracts" below.

**Estimated diff:** ~150 lines removed, ~50 lines added (tracing calls + drain
delegation).

### Phase 2c: Structured Spans for Connection Lifecycle

**Scope:** Add `tracing::Span` instrumentation to per-connection worker paths.

**Span hierarchy:**
```
connection{peer=192.168.1.5:54321}
  route{target=203.0.113.10:443, group=0}
    relay
  close
```

**Benefits:**
- Events emitted within a span automatically carry span fields as context
- The `EventRingLayer` can optionally include span context in ring events
- Replaces manual `format!("session={} source={} ...", ...)` string building
- Enables per-connection filtering via `EnvFilter` (e.g.,
  `RUST_LOG=ripdpi_runtime[connection{peer=specific_ip}]=debug`)

**Implementation:** Add `#[instrument]` attributes or explicit `span.enter()`
calls in `ripdpi-runtime`'s connection handler, route selector, and relay loop.

**No golden impact:** Span context is additive metadata that the
`EventRingLayer` can choose to include or exclude. Initially, exclude to avoid
golden churn; add in a follow-up once the Layer is stable.

**Estimated diff:** ~100 lines in `ripdpi-runtime`.

### Phase 3: `tracing-appender` for File-Based Diagnostics Export

**Scope:** Add a file-based tracing subscriber for persistent diagnostic logs.

**New dependency:** `tracing-appender 0.2` (pure Rust, ~2K SLOC, zero
transitive deps beyond `tracing`).

**CLI (`ripdpi-cli`):**
- Add `--log-file <PATH>` CLI flag
- Create `tracing_appender::rolling::daily()` or `non_blocking()` writer
- Register as an additional Layer alongside `fmt`:

```rust
let file_layer = fmt::layer()
    .with_writer(non_blocking_writer)
    .json();  // Structured JSON for machine parsing

tracing_subscriber::registry()
    .with(env_filter)
    .with(fmt::layer().with_writer(std::io::stderr))  // existing
    .with(file_layer)                                   // new
    .init();
```

**Android (`android-support`):**
- Gate behind `#[cfg(feature = "file-diagnostics")]`
- JNI caller provides a file path (e.g., app-specific storage)
- Layer writes structured JSON events to the file
- Enables diagnostic log capture for bug reports

**Estimated diff:** ~80 lines across `ripdpi-cli` and `android-support`.

**Dependencies:** Phase 1 (all crates emit tracing events). Independent from
Phase 2.

## Custom Layer Architecture Detail

### Subscriber Stack (Android)

```
                         tracing::Event
                              |
                    tracing_subscriber::Registry
                         /         \
              AndroidLogLayer     EventRingLayer
                    |                   |
              log::info!(...)     ring.push_back(event)
                    |                   |
              android_logger     Mutex<VecDeque<_>>
                    |                   |
                 logcat           JNI poll -> JSON
```

### Data Flow

1. Rust code emits `tracing::info!(ring = "proxy", source = "proxy", "listener started addr={addr}")`
2. Registry dispatches to both layers
3. `AndroidLogLayer::on_event()` formats via `MessageFieldFormatter` and calls
   `log::info!()` -> logcat
4. `EventRingLayer::on_event()` reads the `ring` field, routes to proxy ring,
   formats `NativeRuntimeEvent`, pushes to ring
5. JNI `poll_telemetry()` calls `layer.drain_proxy()` -> `Vec<NativeRuntimeEvent>`
   -> `serde_json::to_string()` -> `jstring`

### `EventRingLayer` Access from Telemetry States

The `EventRingLayer` must be accessible from `ProxyTelemetryState::snapshot()`
and `TunnelTelemetryState::snapshot()` for drain calls. Options:

**Option A (recommended): Pass ring `Arc` at construction.**
```rust
pub(crate) struct ProxyTelemetryState {
    // ... atomics, strings, histograms ...
    event_ring: Arc<Mutex<VecDeque<NativeRuntimeEvent>>>,  // shared with EventRingLayer
}
```
The ring `Arc` is created by `EventRingLayer::new()` and passed to
`ProxyTelemetryState::new()`. This preserves the current drain-in-snapshot
pattern with minimal API change.

**Option B: Global Layer access via `tracing::dispatcher`.**
Not recommended: requires downcasting the subscriber, which is fragile and
not supported by the `tracing` API without `dyn Any` workarounds.

### Volatile Field Scrubbing

Currently, golden test scrubbers sanitize volatile fields post-serialization
(e.g., `created_at` -> 0, port numbers -> placeholders). With the
`EventRingLayer`, scrubbing can optionally move into the Layer:

- **Test builds:** `EventRingLayer` could accept a `scrub: bool` flag that
  zeros `created_at` at push time, removing the need for post-hoc scrubbing
- **Production builds:** No scrubbing (real timestamps)

However, this optimization is **not recommended for initial implementation**.
The existing scrub-in-test-assertions pattern works correctly and is well
understood. Moving scrubbing into the Layer couples test concerns with
production code. Defer until the scrubbing logic becomes a maintenance burden.

## Impact on Golden Contracts

### Phase 2b: Golden Re-Bless Required

When `push_event_internal` calls are replaced with `tracing::info!(ring = "proxy", ...)`
calls, the `EventRingLayer` controls the message format. The
`MessageFieldFormatter` in `android-support` serializes events as:

```
message field1=value1 field2=value2
```

Current manually-formatted messages:
```
listener started addr=127.0.0.1:1080 maxClients=256 groups=3
client error: connection reset
route selected phase=initial group=0 target=203.0.113.10:443 host=example.com
route advanced target=203.0.113.10:443 from=0 to=1 trigger=3 host=example.com
```

If Phase 2b preserves the manual `format!()` strings and passes them as the
`message` field to tracing, the ring event message will be identical:

```rust
// Preserves current message format
let message = format!("listener started addr={bind_addr} maxClients={max_clients} groups={group_count}");
tracing::info!(ring = "proxy", source = "proxy", "{message}");
```

**Recommendation:** Phase 2b should preserve manual `format!()` strings to
avoid golden churn. Structured fields (`tracing::info!(addr = %bind_addr, ...)`)
can be adopted incrementally in Phase 2c or later, with a deliberate golden
re-bless.

### Affected Golden Files

| Crate | Golden Directory | File Count | Impact |
|-------|------------------|------------|--------|
| `android-support` | `tests/golden/` | 4 | Phase 2b: only if `MessageFieldFormatter` output changes |
| `ripdpi-android` | `tests/golden/` | ~4 | Phase 2b: event messages in `native_events` array |
| `ripdpi-tunnel-android` | `tests/golden/` | ~6 | Phase 2b: event messages in `native_events` array |
| `ripdpi-monitor` | `tests/golden/` | ~4 | Phase 2b: passive event messages |

### Migration Procedure

1. Run golden tests before migration: `cargo test` in affected crates
2. Apply Phase 2b changes
3. Run golden tests -- expect failures if message format changed
4. If failures: `RIPDPI_BLESS_GOLDENS=1 cargo test`
5. Review blessed diffs: changes should be limited to `native_events[].message`
   fields and should be semantically equivalent
6. Document format changes in commit message

## Performance Analysis

### Per-Event Overhead Comparison

| Operation | Current | After Phase 2 |
|-----------|---------|---------------|
| Log to logcat | `log_with_level()` -> `log::*` -> `android_logger`: ~200ns | `tracing::info!()` -> `AndroidLogLayer` -> `log::*` -> `android_logger`: ~250ns |
| Push to ring | `Mutex::lock()` + `String::to_string()` x3 + `VecDeque::push_back()`: ~200-300ns | `EventRingLayer::on_event()`: dispatcher lookup (~1ns) + visitor traversal (~50-100ns) + ring push (~100-200ns): ~150-300ns |
| **Total per event** | **~400-500ns** (two separate paths) | **~250-350ns** (single tracing event, two Layers) |

**Net impact:** Equal or ~20-30% faster per event because the double-logging
path (separate `log_with_level` + `push_event`) is replaced by a single tracing
event dispatched to two Layers. The `MessageFieldFormatter` visitor runs once
per Layer invocation, but the string is allocated once.

### Span Overhead (Phase 2c)

- Span enter/exit: ~200ns (thread-local span stack push/pop)
- Per-connection overhead: negligible vs. TCP connect latency (~10-100ms)
- Memory per span: ~64 bytes (metadata pointer + field storage)

### Memory

Ring buffer sizes are unchanged (128/128/256 events). The `EventRingLayer`
struct adds ~3 `Arc` pointers (~24 bytes) and a `RingConfig` (~24 bytes).
Net memory delta: negligible.

## Feature Flags

### `tracing` Crate: NOT Feature-Gated

`tracing` is already a workspace dependency (`tracing = "0.1"` in workspace
`Cargo.toml`). All Android crates depend on it transitively through
`android-support`. Making it optional via `#[cfg(feature = "tracing")]` would
add complexity without benefit -- the dependency is already unconditional.

### `file-diagnostics` Feature: Phase 3 Only

```toml
# android-support/Cargo.toml
[features]
file-diagnostics = ["tracing-appender"]

[dependencies]
tracing-appender = { workspace = true, optional = true }
```

This gates the `tracing-appender` dependency and filesystem I/O. The feature is
off by default for Android builds (no filesystem write needed for normal
operation) and enabled in `ripdpi-cli` where file logging is always available.

### `EventRingLayer`: Platform-Gated

```rust
#[cfg(target_os = "android")]
pub struct EventRingLayer { /* ... */ }
```

The `EventRingLayer` is only meaningful on Android where JNI polling consumes
ring events. On desktop (CLI), the `tracing_subscriber::fmt` layer handles all
output. Events with `ring = "proxy"` fields are simply ignored by the fmt layer
since it treats them as regular fields.

## Migration Sequence

| Phase | Scope | Estimated Diff | Crate Changes | Risk | Depends On |
|-------|-------|----------------|---------------|------|------------|
| ~~1~~ | ~~`log::*` -> `tracing::*` macro swap~~ | ~~Done~~ | ~~Already tracing-native~~ | ~~N/A~~ | ~~None~~ |
| 2a | `EventRingLayer` implementation | ~200 lines new | `android-support` | Low: golden tests guard output | None |
| 2b | Remove `push_event` plumbing | ~150 lines removed | `ripdpi-android`, `ripdpi-tunnel-android`, `ripdpi-monitor` | Low: golden re-bless required | Phase 2a |
| 2c | Structured spans for connection lifecycle | ~100 lines | `ripdpi-runtime` | Low: additive, no API change | Phase 2a |
| 3 | `tracing-appender` file export | ~80 lines | `ripdpi-cli`, `android-support` | Minimal: additive feature | None |

**Recommended PR structure:**
- Phase 2a + 2b as a single PR (Layer + telemetry refactor, golden re-bless)
- Phase 2c as a follow-up PR (spans, no golden impact)
- Phase 3 as independent PR (new dependency, feature-gated)

## Future Considerations

### Span-Enriched Ring Events

Once Phase 2c adds structured spans, the `EventRingLayer` could include span
context in ring events. For example, a `client error` event within a
`connection{peer=192.168.1.5:54321}` span could produce:

```json
{
  "source": "proxy",
  "level": "warn",
  "message": "client error: connection reset",
  "peer": "192.168.1.5:54321",
  "createdAt": 1711234567890
}
```

This would require extending `NativeRuntimeEvent` with an optional `fields`
map and updating the Kotlin-side parser. Defer until the Android UI needs
per-connection event context.

### `tracing-opentelemetry` Integration

If RIPDPI adds server-side telemetry collection, `tracing-opentelemetry`
provides a `Layer` that exports spans and events to OpenTelemetry collectors.
The unified tracing subscriber stack makes this a drop-in addition with no
changes to instrumented code.

### Consolidating Event Types

`NativeRuntimeEvent` (proxy/tunnel) and `NativeSessionEvent` (monitor) have
identical fields. Phase 2a can unify them into a single type in
`android-support`, reducing duplication.
