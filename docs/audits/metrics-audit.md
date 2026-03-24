# Runtime Metrics Audit & `metrics` Crate Adoption Plan

Audited: 2026-03-24 against metrics 0.24.x, hdrhistogram 7, workspace
telemetry architecture.

## Summary

The native Rust layer has 15+ atomic counters, 3 HDR histograms, 2 event
ring buffers, and autolearn policy state. All data flows through a custom
observer pattern (`RuntimeTelemetrySink`) into JNI-pollable JSON snapshots.

Gaps: no distribution data for connection setup time per strategy family,
no per-resolver DNS latency breakdown, no diagnostic probe duration
histograms, no adaptive TTL effectiveness gauge. The `metrics` crate
facade can fill these gaps as a parallel instrumentation layer without
touching the existing golden-tested snapshot path.

Recommendation: layer `metrics` on top -- do not replace the existing
telemetry. The current system is tightly integrated with JNI and
golden-tested. A parallel `InMemoryRecorder` provides new instrumentation
points while preserving backward compatibility.

## Current Metrics Inventory

### Proxy Counters (`ripdpi-android/src/telemetry.rs`)

| Field | Type | Line | Semantics | Hot Path |
|-------|------|------|-----------|----------|
| `active_sessions` | AtomicU64 | 111 | Gauge: current open connections | Yes |
| `total_sessions` | AtomicU64 | 112 | Counter: cumulative clients accepted | Yes |
| `total_errors` | AtomicU64 | 113 | Counter: cumulative client errors | Yes |
| `network_errors` | AtomicU64 | 114 | Counter: transient reachability errors | Yes |
| `route_changes` | AtomicU64 | 115 | Counter: route group advances | Moderate |
| `retry_paced_count` | AtomicU64 | 116 | Counter: retries with backoff > 0 | Moderate |
| `last_retry_backoff_ms` | AtomicU64 | 117 | Gauge: latest retry backoff duration | Moderate |
| `candidate_diversification_count` | AtomicU64 | 118 | Counter: retry diversification attempts | Moderate |
| `last_route_group` | AtomicI64 | 119 | Gauge: last selected group index (-1 = none) | Moderate |
| `autolearn_enabled` | AtomicBool | 120 | Gauge: autolearn on/off | Rare |
| `learned_host_count` | AtomicU64 | 121 | Gauge: number of learned hosts | Rare |
| `penalized_host_count` | AtomicU64 | 122 | Gauge: number of penalized hosts | Rare |
| `last_autolearn_group` | AtomicI64 | 123 | Gauge: last autolearn group index | Rare |
| `slot_exhaustions` | AtomicU64 | 124 | Counter: client slot limit hits | Rare |

### Proxy Histograms (same file)

| Field | Type | Line | Source Callback | Unit |
|-------|------|------|-----------------|------|
| `tcp_connect_histogram` | LatencyHistogram | 127 | `on_upstream_connected` (RTT) | ms |
| `tls_handshake_histogram` | LatencyHistogram | 128 | `on_tls_handshake_completed` | ms |

### Proxy String State (`TelemetryStrings`, ArcSwap, lines 93-105)

listener_address, upstream_address, upstream_rtt_ms, last_target,
last_host, last_error, last_failure_class, last_fallback_action,
last_retry_reason, last_autolearn_host, last_autolearn_action.
Updated via RCU; read lock-free on snapshot.

### Proxy Event Ring (line 126)

`VecDeque<NativeRuntimeEvent>`, max 128 entries (drained on each poll).
Each event: source, level (info/warn), message, created_at.

### Tunnel Counters (`ripdpi-tunnel-android/src/telemetry.rs`)

| Field | Type | Line | Semantics |
|-------|------|------|-----------|
| `total_sessions` | AtomicU64 | 74 | Counter: cumulative tunnel sessions |
| `total_errors` | AtomicU64 | 75 | Counter: cumulative tunnel errors |

### Tunnel DNS & Traffic Counters (`ripdpi-tunnel-core/src/stats.rs`)

| Field | Type | Line | Semantics |
|-------|------|------|-----------|
| `tx_packets` | AtomicU64 | 26 | Counter: transmitted packets |
| `tx_bytes` | AtomicU64 | 27 | Counter: transmitted bytes |
| `rx_packets` | AtomicU64 | 28 | Counter: received packets |
| `rx_bytes` | AtomicU64 | 29 | Counter: received bytes |
| `dns_queries_total` | AtomicU64 | 30 | Counter: DNS queries issued |
| `dns_cache_hits` | AtomicU64 | 31 | Counter: DNS cache hits |
| `dns_cache_misses` | AtomicU64 | 32 | Counter: DNS cache misses |
| `dns_failures_total` | AtomicU64 | 33 | Counter: DNS resolution failures |
| `resolver_fallback_active` | AtomicU64 | 40 | Gauge: fallback resolver active (0/1) |

DNS string state (Mutex-guarded): last_dns_host, last_dns_error,
last_host, resolver_endpoint, resolver_latency_ms,
resolver_latency_window (VecDeque<u64>, last 32), resolver_fallback_reason.

### Tunnel Histogram (`ripdpi-tunnel-android/src/telemetry.rs`)

| Field | Type | Line | Source | Unit |
|-------|------|------|--------|------|
| `dns_histogram` | LatencyHistogram | 79 | `dns_latency_observer` callback | ms |

### CLI Counters (`ripdpi-cli/src/telemetry.rs`)

| Field | Type | Line | Semantics |
|-------|------|------|-----------|
| `total_sessions` | AtomicU64 | 9 | Counter |
| `total_errors` | AtomicU64 | 10 | Counter |

Printed via `tracing::info!` on shutdown. No histogram support.

### Monitor Timing (`ripdpi-monitor/src/connectivity/probes.rs`)

| Measurement | Method | Lines | Output Key | Unit |
|-------------|--------|-------|------------|------|
| UDP DNS latency | `Instant::now().elapsed()` | 32-34 | `udpLatencyMs` | ms |
| Encrypted DNS latency | `Instant::now().elapsed()` | 35-37 | `encryptedLatencyMs` | ms |
| QUIC round-trip | `now_ms() - started` | 264-268 | `latencyMs` | ms |
| Throughput | `measure_throughput_window()` | 395-434 | `medianBps` | bps |

All stored as `ProbeDetail` strings in `ProbeResult`. No persistent
histogram -- values are per-scan ephemeral.

### Strategy Scoring (`ripdpi-monitor/src/execution.rs`)

`CandidateScore` (lines 36-44): `weighted_success_score`,
`quality_score`, `latency_sum_ms`, `latency_count`. Used to rank strategy
candidates. Winner selected by: success > quality > latency (ascending).
No histogram; single `average_latency_ms()`.

### Runtime Policy State (`ripdpi-runtime/src/runtime_policy/`)

`LearnedGroupStats` (types.rs:29-33): `success_count`, `failure_count`,
`penalty_until_ms`, `last_success_at_ms`, `last_failure_at_ms` per host
per group. Stored in BTreeMap, persisted as JSON with SHA-256 config
fingerprint.

`AdaptiveFakeTtlResolver` (adaptive_fake_ttl.rs:28-30): HashMap of
per-(group, target) TTL state -- `seed`, `candidates` (ordered Vec<u8>),
`candidate_index`, `pinned_ttl`, `detected_fallback`. Updated on
success/failure/server-TTL observation.

## metrics Crate Mapping

### Existing Metrics -> `metrics` Macros

| Current Pattern | metrics Macro | Notes |
|-----------------|---------------|-------|
| AtomicU64 monotonic (fetch_add) | `counter!()` | total_sessions, total_errors, etc. |
| AtomicU64 last-value (store) | `gauge!()` | learned_host_count, last_retry_backoff_ms |
| AtomicU64 gauge (fetch_add + fetch_sub) | `gauge!()` | active_sessions |
| AtomicBool state | `gauge!()` | running, autolearn_enabled |
| AtomicI64 index | `gauge!()` | last_route_group, last_autolearn_group |
| LatencyHistogram | `histogram!()` | tcp_connect, tls_handshake, dns_resolution |

### Migration Not Required

The existing atomics serve the JNI snapshot path and are golden-tested.
They do not need to be replaced by `metrics` macros. Instead, `metrics`
macros are emitted alongside existing atomics for new instrumentation
points. Existing counters can optionally dual-emit in a future phase.

## New Histogram Metrics to Introduce

| Metric | Type | Labels | Instrumentation Site | Rationale |
|--------|------|--------|---------------------|-----------|
| `ripdpi_connection_setup_duration_seconds` | histogram | `strategy_family` | `runtime/routing.rs` around `connect_upstream` | Per-strategy-family latency distribution (TCP desync vs QUIC vs WS tunnel) |
| `ripdpi_dns_resolution_duration_seconds` | histogram | `resolver_id`, `protocol` | `ripdpi-dns-resolver/src/resolver.rs` resolve path | Per-resolver performance comparison (UDP vs DoH vs DNSCrypt) |
| `ripdpi_route_selection_duration_seconds` | histogram | -- | `runtime_policy/selection.rs` select path | Detect policy evaluation cost under high host-store cardinality |
| `ripdpi_strategy_probe_duration_seconds` | histogram | `candidate_id`, `family` | `ripdpi-monitor/src/execution.rs` per-candidate | End-to-end probe time per strategy candidate |
| `ripdpi_fake_ttl_effective` | gauge | `group_index` | `adaptive_fake_ttl.rs:33` resolve() return | Track TTL convergence and pin stability |
| `ripdpi_fake_ttl_advances_total` | counter | `group_index` | `adaptive_fake_ttl.rs:63` note_failure() | Rate of TTL search iterations (high = instability) |

## Custom MetricsRecorder Design

### Location

`ripdpi-telemetry/src/recorder.rs` (new file in existing crate).

The `ripdpi-telemetry` crate already owns `LatencyHistogram` and depends
on `hdrhistogram`. Adding the Recorder here avoids new crate creation and
keeps histogram code colocated.

### Architecture

```
InMemoryRecorder
  counters:   HashMap<MetricKey, AtomicU64>       (lock-free increment)
  gauges:     HashMap<MetricKey, AtomicI64>        (lock-free set)
  histograms: HashMap<MetricKey, LatencyHistogram> (Arc<Mutex<Histogram>>)

  install()          -> set_global_recorder (once, at JNI_OnLoad)
  snapshot()         -> RecorderSnapshot   (Serialize, for JNI poll)
  reset_histograms() -> clear all histograms (on session stop)
```

### Constraints

- **Bounded**: max 256 metric keys total. Registration beyond limit is a
  no-op (log warning, do not panic). Prevents unbounded label cardinality
  from consuming mobile memory.
- **No heap allocation on hot path**: counter increment is atomic,
  histogram record is mutex-guarded (same cost as existing
  `LatencyHistogram`).
- **Key storage**: pre-registered at startup via `describe_*!()` macros.
  No dynamic key creation from user input.
- **Thread-safe**: all fields behind atomics or Mutex (same guarantees as
  existing telemetry).
- **JNI-accessible**: `snapshot()` returns a `RecorderSnapshot` that
  serializes to JSON. Polled via a dedicated JNI method, separate from the
  existing `jniPollTelemetry`.

### RecorderSnapshot Format

```rust
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecorderSnapshot {
    pub counters: BTreeMap<String, u64>,
    pub gauges: BTreeMap<String, i64>,
    pub histograms: BTreeMap<String, LatencyPercentiles>,
    pub captured_at: u64,
}
```

## Implementation Plan

### Phase 1: Recorder Skeleton (no export, JNI-pollable)

Goal: add `metrics` dependency, implement `InMemoryRecorder`, emit
counter metrics alongside existing atomics. No golden contract changes.

Files to modify:

| File | Change |
|------|--------|
| `native/rust/Cargo.toml` | Add `metrics = "0.24"` to workspace deps |
| `ripdpi-telemetry/Cargo.toml` | Add `metrics` dependency |
| `ripdpi-telemetry/src/lib.rs` | Add `pub mod recorder;` |
| `ripdpi-telemetry/src/recorder.rs` | **New**: `InMemoryRecorder` + `RecorderSnapshot` + `install()` |
| `ripdpi-android/src/lib.rs` | Call `install()` at JNI_OnLoad |
| `ripdpi-cli/src/main.rs` | Call `install()` at startup |

Tests:
- Unit tests for `InMemoryRecorder` in `recorder.rs`
- Verify existing golden contracts pass unchanged

### Phase 2: Histogram Instrumentation

Goal: add histogram metrics at new call sites. These are genuinely new
measurements that do not exist today.

Files to modify:

| File | Change |
|------|--------|
| `ripdpi-runtime/Cargo.toml` | Add `metrics` dependency |
| `ripdpi-runtime/src/runtime/routing.rs` | Wrap `connect_upstream` in `Instant::now()` + `histogram!("ripdpi_connection_setup_duration_seconds")` |
| `ripdpi-dns-resolver/Cargo.toml` | Add `metrics` dependency |
| `ripdpi-dns-resolver/src/resolver.rs` | Emit `histogram!("ripdpi_dns_resolution_duration_seconds")` around resolve |
| `ripdpi-runtime/src/adaptive_fake_ttl.rs` | Emit `gauge!("ripdpi_fake_ttl_effective")` from `resolve()` return |
| `ripdpi-telemetry/src/recorder.rs` | Implement `register_histogram` / `record_histogram` backed by `LatencyHistogram` |

Golden contract impact: none. New data flows through the Recorder path,
not through `NativeRuntimeSnapshot`.

New golden contracts: add `ripdpi-telemetry/tests/golden/` for
`RecorderSnapshot` serialization.

### Phase 3: Diagnostics Export Bundle

Goal: export percentile data into `ScanReport`, add strategy probe timing
and adaptive TTL metrics.

Files to modify:

| File | Change |
|------|--------|
| `ripdpi-monitor/Cargo.toml` | Add `metrics` dependency |
| `ripdpi-monitor/src/execution.rs` | Emit `histogram!("ripdpi_strategy_probe_duration_seconds")` per candidate |
| `ripdpi-monitor/src/engine/report.rs` | Include `RecorderSnapshot` in `ScanReport` |
| `ripdpi-monitor/src/types/request.rs` | Add optional `metrics_summary` field to `ScanReport` |
| `ripdpi-android/src/lib.rs` | New JNI method `jniGetMetricsSnapshot` returning `RecorderSnapshot` JSON |

Golden contract impact: `ScanReport` golden contracts gain a new
`metricsSummary` field. Bless with `RIPDPI_BLESS_GOLDENS=1`.

## Binary Size Analysis

| Component | Estimated Size | Notes |
|-----------|---------------|-------|
| `metrics` crate | ~15 KB | Facade only, no export backend |
| HashMap storage | ~5 KB | Bounded to 256 keys |
| hdrhistogram (existing) | 0 KB | Already in dependency tree |

Total: ~20 KB added to `libripdpi.so`. The `metrics` crate is lighter
than `dashmap` (~20 KB). Using `HashMap` behind a `RwLock` instead of
`DashMap` avoids the extra dependency -- registration happens at startup
(not hot path), so the lock is uncontended.

## Golden Contract Compatibility

### Phase 1-2: No Changes

The `metrics` Recorder is a parallel data path. Existing
`NativeRuntimeSnapshot` (proxy golden contracts) and tunnel snapshots are
serialized from `ProxyTelemetryState` / `TunnelTelemetryState`. The
Recorder does not touch these structs.

### Phase 3: Additive Field

`ScanReport` gains an optional `metricsSummary` field:

```rust
#[serde(skip_serializing_if = "Option::is_none")]
pub metrics_summary: Option<RecorderSnapshot>,
```

This is purely additive. Existing golden contracts serialize `None` as
absent (skip_serializing_if). New golden contracts are added for reports
that include metrics data.

### Future Unification (Optional)

If the team decides to unify the two paths, the approach is:
1. Add `metricsSnapshot` to `NativeRuntimeSnapshot` with
   `skip_serializing_if = "Option::is_none"`
2. Bless goldens with `RIPDPI_BLESS_GOLDENS=1`
3. Kotlin deserialization already ignores unknown fields

## Risk Register

| Risk | Impact | Mitigation |
|------|--------|------------|
| Binary size growth | Low | ~20 KB; no DashMap |
| Global recorder singleton | Panic on double-install | `try_set_global_recorder` with log on conflict |
| Hot-path histogram contention | Mutex on record | Same as existing `LatencyHistogram` (proven) |
| Unbounded label cardinality | Memory growth | 256-key hard cap; no dynamic labels |
| Test isolation | Recorder bleeds across tests | `metrics::with_local_recorder` in unit tests |
| Phase 3 golden bless | CI breakage on merge | Scope bless to a single commit with clear message |
