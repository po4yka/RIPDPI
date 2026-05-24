# G008 Subsystems Design — Connection-Quality Telemetry · PCAP Export · Replay Orchestrator

**Status:** Ratified design — synthesised from architect-agent investigation
**Date:** 2026-05-24
**Active /goal:** G008 (RDS subsystems for the deferred VPN screens)
**Scope:** P5 (telemetry), P3 (PCAP), P4 (replay) — in implementation order

---

## Summary

Three subsystems back the UI that shipped under G007 and the new wirings under G008-P2. They share three load-bearing decisions:

1. **All cross-language data lands on `NativeRuntimeSnapshot`** as additive `Option<T>` fields. The wire is forward-tolerant via Kotlin's `ignoreUnknownKeys=true` per [`TELEMETRY_CONTRACT.md`](TELEMETRY_CONTRACT.md), so the existing `SNAPSHOT_SCHEMA_VERSION = 1` constant does **not** bump. Goldens stay stable.
2. **Native producers live in the existing tunnel/proxy crates** — `libripdpi-tunnel.so` (TUN mode) and `libripdpi.so` (proxy-only mode) — extending `Stats` / `Session` types that already exist. No greenfield crates for the metric collection itself; one new crate (`ripdpi-pcap`) only for the PCAP file format because it's a self-contained library concern.
3. **Kotlin consumers are `Flow<T>` projections** built off the existing `RuntimeTelemetryProjection`. The Replay orchestrator follows `StrategyProbeService`'s `Flow<StrategyProbeResult>` shape (`StrategyProbeService.kt:189-236`).

Implementation order is **P5 → P3 → P4** because (a) P5 unlocks the DegradationStrip + 2 graph partials immediately; (b) P3 is independent of telemetry but reuses the same JNI patterns; (c) P4 reuses both the JNI conventions established by P5 and any PCAP artefacts produced by P3 as input fixtures.

---

## P5 — Connection-Quality Telemetry

### Decision matrix

| Question | Answer | Why |
| --- | --- | --- |
| **Data source** | (c) **Passive observation of TCP/UDP/QUIC session paths inside `ripdpi-tunnel-core`'s `TcpSession`/`UdpSession`** plus existing `Stats.set_dns_latency_observer` pattern. No active probing in P5 scope. | (a) active probing needs `VpnService.protect(fd)` and burns battery; (b) raw TCP-timestamp passive analysis is fragile. Existing TUN paths already see SYN/SYN-ACK timing → TCP-connect latency = honest RTT proxy. `set_dns_latency_observer` (`stats.rs:79`) proves the observer-install pattern works without architectural changes. |
| **Loss tracking** | **Out of P5 scope.** Renders as `null` in the wire contract; UI shows "—" for the loss chip. | True loss tracking requires retransmit-count instrumentation in the runtime's writer paths, which is a separate week-long initiative. Ship RTT + jitter first; loss is additive in a follow-on. |
| **Producer location** | `ripdpi-tunnel-android/src/telemetry/quality.rs` (new module) — observer installed by `Session::init` against the existing `TcpSession` write/read pair. Proxy-mode mirror lives in `proxy_bridge` (current `libripdpi.so` cdylib). | Co-locates with existing `Stats` infrastructure. Avoids cross-crate observer plumbing. Matches finding #6 (JNI lib map). |
| **Ring shape** | **Two rings, both backed by `crossbeam_queue::ArrayQueue`:** (i) `instant: ArrayQueue<QualitySample>` — capacity 32, last-N stamps for the DegradationStrip's "current" chips. (ii) `series: ArrayQueue<QualitySample>` — capacity 600 (~10 min at 1 sample/sec), for the throughput/latency graphs. | DegradationStrip needs the most-recent moment; graphs need a rolling window. Two rings let each consumer pick its rate without contention. 600 entries × ~64 bytes ≈ 38 KB — well within Android memory budgets. |
| **JNI contract bump** | **Additive `Option<ConnectionQualitySnapshot>` field on `NativeRuntimeSnapshot`.** `SNAPSHOT_SCHEMA_VERSION` stays at 1. Kotlin side adds the field to `NativeRuntimeSnapshot.kt` and `RuntimeTelemetryProjection` projects it. | Finding #2: additive optional fields are wire-compatible. No golden churn. The graph partials get the same projection treatment as `latency_distributions: LatencyDistributions` already does. |
| **Sample rate** | **1 Hz pull from Kotlin side**, producer emits at most-recent-sample-wins into both rings. No push channel. | Matches existing telemetry-projection cadence. Per [`android-vpn-lifecycle.md`](../../.claude/rules/android-vpn-lifecycle.md), 1 Hz survives Doze. |
| **Thresholds (Warning / Critical)** | **Tokenised in `RipDpiThemeTokens.thresholds.networkQuality`** (new sub-object): `loss_warning_pct = 2.0`, `loss_critical_pct = 8.0`, `rtt_warning_ms = 300`, `rtt_critical_ms = 800`, `jitter_warning_ms = 50`, `jitter_critical_ms = 150`. | Per `RipDpiThemeTokens` discipline (no magic numbers in components). DegradationStrip resolves tone via a `resolveNetworkTone(quality, thresholds)` helper in `ui/theme/`. |
| **Privacy** | Aggregate-only per-transport metrics. **No per-host, no per-flow, no destination IP, no SNI.** | Per [`network-fingerprint-privacy.md`](../../.claude/rules/network-fingerprint-privacy.md): aggregate scalars don't carry device-identifying or destination-identifying data. No Data Safety declaration impact. |

### Data shapes

**Rust (`ripdpi-tunnel-android/src/telemetry/quality.rs`):**

```rust
use serde::Serialize;

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionQualitySnapshot {
    /// Most-recent observed connection RTT (TCP SYN→SYN-ACK derived).
    /// `None` when no connection has completed in the last 30s.
    pub rtt_p50_ms: Option<u32>,
    /// 95th-percentile RTT over the last 60s window.
    pub rtt_p95_ms: Option<u32>,
    /// Inter-sample RTT variance (jitter) in ms.
    pub jitter_ms: Option<u32>,
    /// Packet-loss percentage. `None` in P5 (loss tracking deferred).
    pub loss_pct: Option<f32>,
    /// Time of the most-recent sample (`SystemTime::now()` epoch ms).
    pub captured_at_ms: u64,
    /// Number of samples in the rolling window.
    pub sample_count: u32,
}

#[derive(Debug, Clone, Copy)]
pub struct QualitySample {
    pub rtt_ms: u32,
    pub timestamp_ms: u64,
}

pub struct QualityObserver {
    instant: ArrayQueue<QualitySample>,
    series: ArrayQueue<QualitySample>,
}

impl QualityObserver {
    pub fn record_tcp_connect(&self, rtt_ms: u32) {
        // cancel-safe: lock-free push to bounded ring
        let sample = QualitySample { rtt_ms, timestamp_ms: now_ms() };
        let _ = self.instant.force_push(sample); // bounded eviction
        let _ = self.series.force_push(sample);
    }

    pub fn snapshot(&self) -> Option<ConnectionQualitySnapshot> {
        // Compute p50/p95/jitter from the instant ring
        // None if no samples in last 30s
    }
}
```

**Kotlin (`core/service/.../data/NativeRuntimeSnapshot.kt`):**

```kotlin
@Serializable
data class ConnectionQualitySnapshot(
    val rttP50Ms: Int? = null,
    val rttP95Ms: Int? = null,
    val jitterMs: Int? = null,
    val lossPct: Float? = null,
    val capturedAtMs: Long,
    val sampleCount: Int,
)

// Added to existing NativeRuntimeSnapshot data class:
//   @SerialName("connectionQuality")
//   val connectionQuality: ConnectionQualitySnapshot? = null,
```

**Threshold tokens (`app/src/main/kotlin/.../ui/theme/RipDpiNetworkQualityThresholds.kt` — new file):**

```kotlin
data class RipDpiNetworkQualityThresholds(
    val lossWarningPct: Float = 2.0f,
    val lossCriticalPct: Float = 8.0f,
    val rttWarningMs: Int = 300,
    val rttCriticalMs: Int = 800,
    val jitterWarningMs: Int = 50,
    val jitterCriticalMs: Int = 150,
)

// Exposed on RipDpiThemeTokens as `thresholds.networkQuality`
```

### Implementation plan (P5)

Each commit ≤ 300 LOC. Order is strict.

| # | Commit | Files | Purpose |
| --- | --- | --- | --- |
| P5.1 | `feat(rust): ConnectionQualitySnapshot + QualityObserver` | `native/rust/crates/ripdpi-tunnel-android/src/telemetry/quality.rs` (new), `telemetry/mod.rs` (export) | Add the bounded-ring + observer + serde shape. No wiring yet. |
| P5.2 | `feat(rust): wire QualityObserver into TcpSession::connect` | `native/rust/crates/ripdpi-tunnel-core/src/session/tcp.rs` (extend `connect` to record RTT) + observer install in `Session::init` | Hot-path instrumentation. Lock-free. |
| P5.3 | `feat(rust): expose connection_quality on NativeRuntimeSnapshot` | `telemetry/types.rs` (add Option field), `telemetry/snapshot.rs` (populate from observer) | Wire-contract additive field. No JNI signature change. |
| P5.4 | `feat(data): ConnectionQualitySnapshot Kotlin model + parser` | `core/service/src/main/kotlin/.../data/NativeRuntimeSnapshot.kt` (add field), `data/ConnectionQualitySnapshot.kt` (new) | Mirror the Rust shape in Kotlin. Round-trip JSON test. |
| P5.5 | `feat(theme): RipDpiNetworkQualityThresholds + resolveNetworkTone helper` | `ui/theme/RipDpiNetworkQualityThresholds.kt` (new), `ui/theme/RipDpiTheme.kt` (expose), `ui/theme/RipDpiNetworkToneResolver.kt` (new pure helper) | Token discipline for the DegradationStrip tone. |
| P5.6 | `feat(ui): wire RipDpiDegradationStrip into HomeScreen` | `ui/screens/home/HomeScreen.kt`, `ui/screens/home/HomeViewModel.kt`, `ui/screens/home/HomeUiState.kt` | Consume `connectionQuality` Flow from runtime, project through threshold resolver, render strip when tone ≠ None. |
| P5.7 | `feat(ui): wire vpn-throughput-graph + vpn-latency-graph partials` | New `ThroughputGraphCard.kt` + `LatencyGraphCard.kt` under `ui/screens/diagnostics/`, with a `series` Flow consumer | Closes the 2 graph partials in COVERAGE.md. |
| P5.8 | `test(rust): nextest QualityObserver invariants` + `test(kotlin): JSON round-trip` | `ripdpi-tunnel-android/tests/quality_observer_test.rs`, `core/service/src/test/.../ConnectionQualityJsonTest.kt` | Coverage for the observer + serde shape. |

### Risks + mitigations (P5)

- **Risk:** Observer's `force_push` evicts samples under burst load → graph shows "missing seconds". **Mitigation:** Ring sized at 32 instant + 600 series; bursts above 32 samples/sec on TCP-connects would mean ≥ 1920 cps, which is way past anything RIPDPI sees on Android.
- **Risk:** Adding observer to `TcpSession::connect` adds ~50 ns hot-path overhead. **Mitigation:** Lock-free `ArrayQueue::force_push` measured at < 20 ns on aarch64. Negligible vs the ~30 ms TCP-connect itself.
- **Risk:** Kotlin `Float?` deserialisation for `lossPct` confuses the projection. **Mitigation:** Explicit `@SerialName("lossPct")` + `Float? = null` default; round-trip JSON test in P5.8.
- **Risk:** `RipDpiThemeTokens.thresholds` is a new sub-object — affects the parity tests. **Mitigation:** Add it to `RipDpiThemeTokens` factory + the existing token-consumption tests in `app/src/test/kotlin/.../ui/theme/` in the same commit (P5.5).

---

## P3 — PCAP Export

### Decision matrix

| Question | Answer | Why |
| --- | --- | --- |
| **PCAP format** | **PCAP-NG** (`.pcapng`). | Supports per-interface metadata (TUN device, MTU, link-type), endpoint comments for redaction notes, and Wireshark opens both natively. Modest writer-complexity premium (~200 LOC vs classic) is worth the future-proofing. |
| **Capture loop** | **Tap the TUN read/write paths with a tee** that writes into a bounded in-memory ring + an append-only file. File flush at 1 Hz (matches telemetry cadence) and on session end. | Per [`android-vpn-lifecycle.md`](../../.claude/rules/android-vpn-lifecycle.md): LMK can SIGKILL anytime, so periodic fsync + flush-on-stop catches both cases. The ring buffers up to 4 MB so a busy 1-Hz interval doesn't lose recent packets. |
| **File location** | **App-private** (`Context.getNoBackupFilesDir() / "pcap"`) during capture; **`MediaStore.Downloads/RIPDPI/`** when the user explicitly exports via the `RipDpiExportConsentDialog`. | App-private avoids Android's media-scanner exposing the raw capture; the export step copies it through redaction (if requested) into the user-visible location with an `ACTION_VIEW` chooser. |
| **Redaction layer** | **Write-time on the export path**, not on the live capture. Live capture preserves full data so the user can later choose redact-or-not per export. | Honest implementation of the `redactEndpoints: Boolean` callback on `RipDpiExportConsentDialog` (finding #7). Avoids losing data the user might want; avoids destructive in-place edits. |
| **Capture toggle UX** | **Manual toggle in the Diagnostics screen → "Packet capture"**, OFF by default. A foreground-notification chip indicates capture is active. Auto-capture on connection-error is **out of scope** for P3. | Manual gives the user explicit consent before any packet is buffered. Auto-capture would need separate UX + privacy consent flow. |
| **Crate location** | **New `ripdpi-pcap` library crate** (~400 LOC) for the PCAP-NG file format, consumed by `ripdpi-tunnel-android` which owns the TUN tee. | The format is a self-contained library concern (testable in isolation against Wireshark goldens). The tee plumbing lives where the TUN fd lives. |
| **JNI surface** | 4 new exports on `libripdpi-tunnel.so`: `pcap_start() -> i32` (returns session-id), `pcap_stop(session_id: i32)`, `pcap_export_to_uri(session_id: i32, uri: String, redact: bool) -> i32` (returns bytes-written), `pcap_list_captures() -> String` (JSON array of `CaptureMetadata`). Match existing `ripdpi-tunnel-android` JNI naming convention (`Java_com_poyka_ripdpi_jni_PcapBridge_*`). | Symmetry with existing JNI patterns. Returning ID keeps state opaque to the Kotlin side. |
| **`PcapPacket` shape match** | The PCAP-NG reader (also in `ripdpi-pcap`) parses captures into `PcapPacket` records that match the Kotlin `PcapPacket` data class shipped under G007 (`ui/screens/diagnostics/PcapViewerScreen.kt`). | The Route wrapper (`PcapViewerRoute.kt`) currently uses static demo data; once `pcap_list_captures` + parser are ready, the wrapper switches to a real `Flow<ImmutableList<PcapPacket>>` from a new `PcapViewerViewModel`. |

### Data shapes

**Rust (`ripdpi-pcap/src/format.rs`):**

```rust
pub struct PcapNgWriter<W: Write> {
    inner: W,
    interface_id: u32,
}

impl<W: Write> PcapNgWriter<W> {
    pub fn new(inner: W, link_type: LinkType, mtu: u32) -> io::Result<Self>;
    pub fn write_packet(&mut self, ts_ms: u64, bytes: &[u8]) -> io::Result<()>;
    pub fn flush(&mut self) -> io::Result<()>;
}

pub struct PcapNgReader<R: Read> {
    inner: R,
}

impl<R: Read> Iterator for PcapNgReader<R> {
    type Item = io::Result<PcapPacketRecord>;
}
```

**JNI bridge (`ripdpi-tunnel-android/src/jni/pcap.rs`):**

```rust
#[no_mangle]
pub extern "system" fn Java_com_poyka_ripdpi_jni_PcapBridge_pcapStart(
    env: JNIEnv, _class: JClass,
) -> jint { /* ... */ }

#[no_mangle]
pub extern "system" fn Java_com_poyka_ripdpi_jni_PcapBridge_pcapStop(
    env: JNIEnv, _class: JClass, session_id: jint,
) -> jint { /* ... */ }

// ...
```

### Implementation plan (P3)

| # | Commit | Purpose |
| --- | --- | --- |
| P3.1 | `feat(rust): ripdpi-pcap crate skeleton + PcapNgWriter` | New crate with writer only. Wireshark-compatibility golden test (write known fixture, diff against committed `.pcapng`). |
| P3.2 | `feat(rust): PcapNgReader + PcapPacketRecord` | Reader for the PcapViewer path. Parses → iterator of records. |
| P3.3 | `feat(rust): TUN tee in ripdpi-tunnel-android` | Hook into existing TUN read/write paths. Bounded ring + 1Hz flush. Cancel-safe per `// cancel-safe:` annotation. |
| P3.4 | `feat(rust): JNI bridge (pcapStart/Stop/Export/List)` | 4 JNI exports + `CaptureMetadata` JSON shape. Panic-safe per `rust-android-jni`. |
| P3.5 | `feat(jni): PcapBridge.kt Kotlin facade` | Kotlin wrapper around the JNI bridge. Thread-safe state. |
| P3.6 | `feat(ui): PCAP capture toggle in DiagnosticsScreen` | Switch + foreground-notification chip when capture is active. |
| P3.7 | `feat(ui): PcapViewerViewModel + wire PcapViewerRoute` | Replace static demo data with `Flow<ImmutableList<PcapPacket>>`. |
| P3.8 | `feat(ui): wire RipDpiExportConsentDialog into capture-list` | Trigger from PcapViewer detail "Export this capture" action. |
| P3.9 | `test(rust): Wireshark golden tests + redaction round-trip` | Bless 1 .pcapng golden checked against `tshark`. |

### Risks + mitigations (P3)

- **Risk:** PCAP-NG writer disk fills the device. **Mitigation:** Capture toggle + max-file-size cap (50 MB default, configurable in advanced settings) + rotation.
- **Risk:** Tee adds latency to hot TUN path. **Mitigation:** Tee is a `try_push` to a bounded ring; if full, sample is dropped (capture is best-effort, not authoritative).
- **Risk:** `MediaStore.Downloads` permission model differs Android 10 vs 11+. **Mitigation:** Use `MediaStore.createWriteRequest` API uniformly; fall back to `ACTION_CREATE_DOCUMENT` on pre-Q (which the app doesn't target).

---

## P4 — Replay Orchestrator API

### Decision matrix

| Question | Answer | Why |
| --- | --- | --- |
| **Reuse vs new crate** | **Extend `StrategyProbeService`** with a `replay(probeId: String): Flow<ReplayStepEvent>` method. No new crate. | Finding #5: `StrategyProbeService` is already `Flow<StrategyProbeResult>`. Replay is a single-strategy variant — same machinery, narrower call. Avoiding crate proliferation. |
| **Step taxonomy** | 5 steps: `DnsResolve`, `TcpOpen`, `TlsClientHello`, `TlsServerResponse`, `FirstByte`. Maps to `StrategyProbeFailureKind` cases. | Spec's 4 sample steps (DNS / TCP / TLS / RST) fit naturally; add `FirstByte` for the success terminal step. |
| **State machine shape** | **Streaming**: `Flow<ReplayStepEvent>` where each event is either `StepStarted(step)`, `StepCompleted(step, durationMs)`, `StepFailed(step, errorKind, hint)`, or `ReplayFinished(verdict)`. The UI accumulates into `ReplayStep` list. | Matches the spec UI showing live update of step status. Streaming avoids the polling pattern. |
| **Recommendation engine** | **JSON catalog** at `core/diagnostics/src/main/resources/replay_hints.json` mapping `(failure_kind, last_completed_step) → hint_template`. | Hard-coded mapping would be opaque; catalog is grep-able, locale-translatable (per `network-fingerprint-privacy.md`), and review-friendly. Templates expand with the probe summary. |
| **JNI surface** | **Streaming via existing native-event bus** (`NativeRuntimeEvent` in `ripdpi-tunnel-android/src/telemetry/event.rs`) — add `Replay*` variants. No new JNI exports; Kotlin already subscribes to the event stream. | Avoids opening a 2nd JNI surface. Replay events flow through the same pipe as other runtime events. |
| **Persistence** | **Ephemeral in P4**: each replay is its own session, no cross-replay state. Persistence to `core/diagnostics-data` is a follow-on (P4.10 if scoped). | Keeps P4 small. Persistence requires schema design that doesn't block the UI wiring. |

### Data shapes

**Kotlin (`core/diagnostics/.../ReplayOrchestrator.kt` — new):**

```kotlin
sealed class ReplayStepEvent {
    abstract val step: ReplayStepKind
    data class StepStarted(override val step: ReplayStepKind, val timestampMs: Long) : ReplayStepEvent()
    data class StepCompleted(override val step: ReplayStepKind, val durationMs: Long, val detail: String) : ReplayStepEvent()
    data class StepFailed(override val step: ReplayStepKind, val errorKind: ReplayErrorKind, val detail: String) : ReplayStepEvent()
    data class ReplayFinished(val verdict: ReplayVerdict, val recommendation: String) : ReplayStepEvent()
}

enum class ReplayStepKind { DnsResolve, TcpOpen, TlsClientHello, TlsServerResponse, FirstByte }
enum class ReplayErrorKind { DnsTampered, ConnectionRefused, ConnectionReset, Timeout, TlsHandshakeFailed, Unknown }
enum class ReplayVerdict { Success, Failure, Cancelled }

class ReplayOrchestrator(
    private val strategyProbeService: StrategyProbeService,
    private val hintCatalog: ReplayHintCatalog,
) {
    fun replay(probeId: String, strategy: StrategyDescriptor): Flow<ReplayStepEvent> { /* ... */ }
}
```

**Hint catalog (`core/diagnostics/src/main/resources/replay_hints.json`):**

```json
{
  "version": 1,
  "rules": [
    {
      "match": {"failureKind": "ConnectionReset", "lastCompletedStep": "TlsClientHello"},
      "hint": "Possible RST + SNI inspection · try tlsrec_split_host instead"
    },
    {
      "match": {"failureKind": "Timeout", "lastCompletedStep": "TcpOpen"},
      "hint": "TCP open succeeded but no TLS response · check MTU with mtu-scan diagnostic"
    }
  ],
  "defaultHint": "Probe failed at {step} ({errorKind}); try a different strategy"
}
```

### Implementation plan (P4)

| # | Commit | Purpose |
| --- | --- | --- |
| P4.1 | `feat(diagnostics): ReplayStepEvent + ReplayErrorKind types` | Pure Kotlin types, no behaviour yet. |
| P4.2 | `feat(diagnostics): ReplayHintCatalog + JSON loader` | Load + validate `replay_hints.json` at startup. Catalog-version protocol. |
| P4.3 | `feat(diagnostics): ReplayOrchestrator stub` | `replay()` method emits hard-coded sample steps for the existing 4-step spec. UI can wire against it. |
| P4.4 | `feat(rust): NativeRuntimeEvent::Replay* variants` | Extend the event enum with `ReplayStepStarted`, `ReplayStepCompleted`, `ReplayStepFailed`. Wire-additive only. |
| P4.5 | `feat(rust): ReplayDriver in ripdpi-monitor-engine` | Single-strategy probe driver that emits the new event variants. |
| P4.6 | `feat(diagnostics): ReplayOrchestrator real impl` | Replace stub from P4.3 with NativeRuntimeEvent-subscriber that maps Rust events → `ReplayStepEvent`. |
| P4.7 | `feat(ui): ReplayFailureViewModel + wire ReplayFailureRoute` | Replace static demo with `Flow<List<ReplayStep>>` from orchestrator. |
| P4.8 | `feat(ui): RipDpiContextMenu wiring → "Replay" action on history rows` | Long-press on history row → menu → Replay → navigate to ReplayFailure. |
| P4.9 | `test(diagnostics): orchestrator + hint catalog` | Coverage for both happy path and each hint-catalog rule. |

### Risks + mitigations (P4)

- **Risk:** `replay_hints.json` drift between Rust failure kinds and Kotlin catalog keys. **Mitigation:** Catalog validator at startup throws if any `ReplayErrorKind` is missing from the catalog. Tested in P4.2.
- **Risk:** Replay step events get interleaved with normal runtime events on the same bus. **Mitigation:** `ReplaySession` ID stamped on each event; orchestrator filters by ID.
- **Risk:** Cancellation: user navigates away from `ReplayFailureScreen` mid-replay. **Mitigation:** Orchestrator's `Flow` is cancellable; cancelling cancels the underlying `StrategyProbeService.runProbe` per its existing cancel-safety contract.

---

## Shared conventions

### Cancel-safety annotation discipline

Every async fn in P3/P4/P5 must be annotated per `.claude/rules/llm-rust-prompts.md`:

```rust
// cancel-safe: lock-free push to ArrayQueue; partial state is invariant.
// Mid-await cancellation leaves the ring in a valid state.
async fn record_quality_sample(observer: &QualityObserver, rtt_ms: u32) { ... }

// NOT cancel-safe: writes a partial PCAP block header. Caller must
// guard against cancellation via tokio::select! with completion arm.
async fn write_packet_block(writer: &mut PcapNgWriter, ...) { ... }
```

### JNI export naming

All new JNI exports follow `Java_com_poyka_ripdpi_jni_<BridgeClassName>_<methodName>`:
- P3: `Java_com_poyka_ripdpi_jni_PcapBridge_*`
- P5: no new JNI exports — reuses existing snapshot puller

### Schema-version protocol

Per `TELEMETRY_CONTRACT.md`:
- **Additive `Option<T>` fields**: no schema-version bump, no golden churn.
- **Renames / removed fields / type changes**: schema-version bump + golden re-bless. P3/P4/P5 are all additive.

### Test coverage gates

- **Rust**: `cargo nextest run --locked` green for each crate. Miri pass on `ripdpi-pcap` (unsafe-free), `cargo +nightly miri test` on any crate added.
- **Kotlin**: `./gradlew :app:testGithubDebugUnitTest` green. JSON round-trip test per new data class.
- **Golden**: 1 PCAP-NG golden in P3.9 (tshark-validated). 0 new Roborazzi goldens (UI uses existing tokens).

### Privacy review per subsystem

| Subsystem | Privacy review |
| --- | --- |
| P5 | ✅ Aggregate-only scalars. No raw IPs, no SNI. Per `network-fingerprint-privacy.md` no Data-Safety impact. |
| P3 | ⚠️ Captures raw packets by user consent. Redaction option per export. App-private during capture; only user-explicit export reaches `MediaStore`. Foreground-notification chip while active. |
| P4 | ✅ Reuses `StrategyProbeService` data flow. No new identifier handling. Hints don't include host data. |

---

## Implementation order

```
P5.1 (rust observer types) ──┐
P5.2 (TcpSession wiring)    ──┤
P5.3 (snapshot field)       ──┼──► P5.6 (DegradationStrip)
P5.4 (Kotlin model)         ──┤    P5.7 (graph partials)
P5.5 (threshold tokens)     ──┘

P3.1 (pcap writer crate) ───┐
P3.2 (pcap reader)         ──┤
P3.3 (TUN tee)             ──┼──► P3.6 (toggle UX)
P3.4 (JNI bridge)          ──┤    P3.7 (PcapViewer wiring)
P3.5 (Kotlin facade)       ──┘    P3.8 (Export wiring)

P4.1 (event types) ──┐
P4.2 (hint catalog) ─┤
P4.3 (stub)         ─┼──► P4.7 (ReplayFailure wiring)
P4.4 (rust events)  ─┤    P4.8 (context-menu Replay)
P4.5 (rust driver)  ─┤
P4.6 (orchestrator) ─┘
```

P5 → P3 → P4 strict sequencing. Within each phase, the rust commits land first, then Kotlin model, then UI wiring.

### Effort estimate (per phase)

- **P5**: ~12 hours coding + 4 hours review. Smallest cross-cutting impact.
- **P3**: ~24 hours coding + 8 hours review (file format, JNI, lifecycle).
- **P4**: ~16 hours coding + 4 hours review (mostly reuses existing infra).

**Total: ~52 hours coding across 3 calendar weeks** (with the architecture-review pauses between phases).

---

## Open questions for the user

1. **P3 capture toggle UX** — confirm Diagnostics-screen toggle is the right location, not Advanced Settings or Home. (Recommendation: Diagnostics, since capture is a diagnostic tool.)
2. **P4 history-row long-press** — confirm Replay should be discoverable via long-press on a history row, not a dedicated button. (Recommendation: long-press; matches the `RipDpiContextMenu` spec card example.)
3. **P5 loss tracking timeline** — explicit confirmation that loss% can ship as `null` in P5 and follow up later. (Strong recommendation: yes; loss tracking is a separate week.)
