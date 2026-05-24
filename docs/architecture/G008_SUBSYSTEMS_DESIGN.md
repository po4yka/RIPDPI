# G008 Subsystems Design — Connection-Quality Telemetry / PCAP Export / Replay Orchestrator

## Summary

Three subsystems wire data-plane realities (TUN packet flow, SOCKS5 connect latency, probe failures) into three already-shipped UI surfaces. The order is **P5 first (telemetry) → P3 (PCAP) → P4 (replay)** because P5 reuses the strongest existing pattern (additive snapshot field plus observer-callback into a histogram, exactly mirroring `Stats::set_dns_latency_observer` at `native/rust/crates/ripdpi-tunnel-core/src/stats.rs:79`), and P5's bounded-ring shape becomes the template that P4's step events follow. P3 is the largest greenfield piece and benefits from the JNI conventions already locked by P5.

All three honor the existing telemetry contract verbatim: `NativeRuntimeSnapshot` additive fields with `#[serde(skip_serializing_if = "Option::is_none")]`, Kotlin defaulted fields, `Json { ignoreUnknownKeys = true }` on the decoders, no `SNAPSHOT_SCHEMA_VERSION` bump (`native/rust/crates/ripdpi-tunnel-android/src/telemetry/types.rs:18`). The wire stays forward-tolerant, goldens move atomically with the field additions, no JNI surface multiplication beyond the existing `jniGetTelemetry` / new explicit `pcap_*` puller, and every outbound socket honors `VpnService.protect()` per `.claude/rules/vpnservice-protect-invariant.md`.

---

## P5 — Connection-Quality Telemetry

### Decision matrix

| Question | Answer | Why |
| --- | --- | --- |
| **Data source** | **(c) Piggyback** primary; (a) active probe as fallback gated by a settings flag (default off). | We already see every TCP target via `TcpSession::run_with_proxy` at `native/rust/crates/ripdpi-tunnel-core/src/session/tcp.rs:32`. SOCKS5-handshake-to-CONNECT-ACK round-trip = local-loopback RTT (negligible) + upstream proxy RTT + target SYN/SYN+ACK time. This is the realest signal available without burning battery and without violating the protect invariant. Passive TCP-timestamp parsing (b) is rejected: NAT-rewrite fragility plus zero coverage of UDP/QUIC. Active probe (a) is kept as an explicit, user-toggled feature for diagnostics ("send 5 ICMP-equivalent UDP echoes to 1.1.1.1") but is NOT the source of the always-on metrics. Loss% is derived from `total_sessions` vs `tcp_session_failures` in a sliding window; jitter is the population stddev of the per-session RTTs in the window. |
| **Producer location** | **New `ripdpi-quality` library crate** (pure, `#![forbid(unsafe_code)]`) + **call sites in `ripdpi-tunnel-core/src/session/tcp.rs`** and **`ripdpi-proxy-runtime`** for the SOCKS5 connect-RTT observer. | A library crate, not adapter, because all four runtimes (tunnel/proxy/relay/warp) need the same `QualityWindow`/`QualityAggregator`. The crate name parallels `ripdpi-telemetry`. The wiring (install observer on session start, drain into snapshot) mirrors `Stats::set_dns_latency_observer` (`stats.rs:79-81`). No new monitor crate — the four `ripdpi-monitor-*` crates own diagnostics probing, not data-plane telemetry. |
| **Ring shape** | **Two rings, one struct.** `QualityWindow` holds two `hdrhistogram::Histogram<u64>` instances: a 60-second instant window (decayed each 1s tick) for the DegradationStrip's "now" values and a 15-minute series window for the graphs. Sample budget: 4096 entries per window (≈4 KiB at 2-digit precision). The "since 12:14" label is `window_start_at_ms: u64` set when the strip first transitions Warning→shown. | One struct, two windows, because the strip and the graphs read the same producer; splitting would force two locks and double the observer wiring. 15 min covers the graph zoom, 60 s catches transient degradation, 4096 samples bounds memory. |
| **JNI contract** | **Additive field on `NativeRuntimeSnapshot`** named `connection_quality: Option<ConnectionQualitySnapshot>` with `#[serde(skip_serializing_if = "Option::is_none")]`. **NO `SNAPSHOT_SCHEMA_VERSION` bump.** | `TELEMETRY_CONTRACT.md:114-116` ("additive and defaulted") explicitly says routine field additions don't bump schemaVersion. The existing `latency_distributions: Option<LatencyDistributions>` field at `telemetry/types.rs:69` is the canonical precedent — same pattern. Adding a second JNI getter doubles the JNI surface, complicates poll-orchestration in `core/service/.../services/ServiceTelemetryLoopCoordinator.kt`, and gains nothing because the existing 1Hz poll cadence is already what the strip needs. Goldens DO shift (the new field appears in `ServiceTelemetryGoldenTest`), so the field addition + golden rebless ship as one atomic commit pair per `golden-bless-discipline.md`. |
| **Thresholds** | New tokens in **`app/src/main/kotlin/com/poyka/ripdpi/ui/theme/RipDpiQualityThresholds.kt`** (Kotlin-side; thresholds are presentation policy, not data-plane policy): `lossWarnPct = 2.0f, lossCriticalPct = 8.0f, rttWarnMs = 300L, rttCriticalMs = 800L, jitterWarnMs = 30L, jitterCriticalMs = 100L, minSampleCountForVerdict = 12`. Below `minSampleCountForVerdict` the strip is suppressed entirely (no false alarms during cold start). | The strip is a Kotlin Compose component — thresholds are a UI policy decision and must be testable without crossing JNI. Hard numbers come from IETF RFC 8083 (loss thresholds for interactive media), Telegram VoIP MOS curves (RTT > 300 = degraded), and the strip-design intent (warning at "noticeable", critical at "actively bad"). 12-sample minimum chosen because a 1Hz cadence × 12s gives a stable mean before alarming. |
| **Privacy** | **Confirmed aggregate-only.** `ConnectionQualitySnapshot` carries `loss_pct: f32`, `rtt_p50_ms: u64`, `rtt_p95_ms: u64`, `jitter_ms: u64`, `sample_count: u64`, `window_start_at_ms: u64`, `transport_kind: String` ("tcp_proxy" / "tcp_tunnel" / "udp_relay"). NO host, IP, port, BSSID, SSID, IMEI, IMSI in any field. The `transport_kind` is a frozen enum-string per `TELEMETRY_CONTRACT.md` stable-identifiers rules. | `network-fingerprint-privacy.md` "Forbidden inputs" — none of the listed identifiers appear. The shape is structurally incapable of leaking endpoint information because the producer aggregates before crossing the histogram boundary. Per-host data is killed at the call site — `TcpSession::run_with_proxy` does NOT pass `target: TargetAddr` into the observer. |

### Implementation plan

Each commit < 300 LOC of diff. Conventional commits.

1. **`feat(quality): introduce ripdpi-quality crate with QualityWindow primitive`**
   - Create `native/rust/crates/ripdpi-quality/` with `Cargo.toml`, `src/lib.rs`, `src/window.rs`, `src/snapshot.rs`, `src/tests.rs`. `QualityWindow` wraps two `hdrhistogram::Histogram<u64>` + an `AtomicU64` failure counter + an `AtomicU64` success counter + an `ArcSwap<Option<u64>>` for `window_start_at_ms`. Mirror `LatencyHistogram` API at `native/rust/crates/ripdpi-telemetry/src/lib.rs:14-71`. `#![forbid(unsafe_code)]` is mandatory.
   - Export `ConnectionQualitySnapshot` with `serde::Serialize` + `#[serde(rename_all = "camelCase")]` matching the existing wire conventions at `telemetry/types.rs:7`.
   - Add to `native/rust/Cargo.toml` `[workspace.dependencies]` block and `[workspace.members]`.
   - Files: `native/rust/crates/ripdpi-quality/**`, `native/rust/Cargo.toml`.

2. **`feat(quality): wire TCP-session observer into ripdpi-tunnel-core::Stats`**
   - Add `set_quality_observer(observer: Arc<dyn Fn(QualitySample) + Send + Sync>)` to `Stats` at `native/rust/crates/ripdpi-tunnel-core/src/stats.rs:79`, parallel to `set_dns_latency_observer`.
   - `QualitySample { rtt_ms: u64, succeeded: bool }` defined in `ripdpi-tunnel-core` (NOT in `ripdpi-quality` — tunnel-core stays observer-pattern-agnostic per the `dns_latency_observer` precedent).
   - At `session/tcp.rs:50-65` (`run_with_proxy`), measure `Instant::now()` before `super::socks5::connect(proxy, &self.target).await?` and after; on success record `(elapsed_ms, true)`, on each `Err(_)` arm record `(elapsed_ms, false)`. Cancel-safety annotation: `// cancel-safe: observer invoke is synchronous, no .await between measurement and emit`.
   - Files: `native/rust/crates/ripdpi-tunnel-core/src/stats.rs`, `native/rust/crates/ripdpi-tunnel-core/src/session/tcp.rs`, `native/rust/crates/ripdpi-tunnel-core/src/lib.rs` (re-export `QualitySample`).

3. **`feat(quality): install QualityWindow in tunnel-android telemetry state`**
   - Add `pub(crate) quality_window: Arc<ripdpi_quality::QualityWindow>` to `TunnelTelemetryState` at `native/rust/crates/ripdpi-tunnel-android/src/telemetry/state.rs:12-22`.
   - Wire observer in `session/lifecycle/telemetry.rs::wire_session_telemetry` (currently called from `lifecycle.rs:89`): `stats.set_quality_observer(Arc::new({ let w = telemetry.quality_window.clone(); move |s| w.record(s) }))`.
   - In `telemetry/snapshot.rs:18-82` add `connection_quality: self.quality_window.snapshot()` near the existing `latency_distributions` line at `snapshot.rs:75`.
   - Add `connection_quality: Option<ConnectionQualitySnapshot>` to `NativeRuntimeSnapshot` at `telemetry/types.rs:69` with the same `#[serde(skip_serializing_if = "Option::is_none")]` decoration as `latency_distributions`.
   - Files: `native/rust/crates/ripdpi-tunnel-android/src/telemetry/{types.rs,state.rs,snapshot.rs}`, `native/rust/crates/ripdpi-tunnel-android/src/session/lifecycle/telemetry.rs`, `native/rust/crates/ripdpi-tunnel-android/Cargo.toml`.

4. **`feat(quality): parallel wiring for proxy/relay/warp runtimes`**
   - Apply the same observer + snapshot-field pattern to `ripdpi-android-telemetry-adapter`, `ripdpi-relay-android/src/telemetry.rs`, `ripdpi-warp-android/src/telemetry.rs`. Each runtime needs its own `QualityWindow` instance; aggregation across runtimes happens Kotlin-side.
   - Files: `native/rust/crates/ripdpi-android-telemetry-adapter/src/**`, `native/rust/crates/ripdpi-relay-android/src/telemetry.rs`, `native/rust/crates/ripdpi-warp-android/src/telemetry.rs`.

5. **`feat(quality): Kotlin ConnectionQualitySnapshot DTO + telemetry projection`**
   - Add `ConnectionQualitySnapshot` data class to `core/data/src/main/kotlin/com/poyka/ripdpi/data/NativeRuntimeSnapshot.kt` (or sibling) with `@Serializable`, all fields defaulted, mirroring the field shape exactly. `Json { ignoreUnknownKeys = true }` is already configured by the decoder per `TELEMETRY_CONTRACT.md:147`.
   - In `core/service/src/main/kotlin/com/poyka/ripdpi/service/telemetry/RuntimeTelemetryProjection.kt:226-236` `enrichRuntimeSnapshot`, pass through the `connectionQuality` field unchanged (no enrichment — the Rust producer is authoritative).
   - Files: `core/data/src/main/kotlin/com/poyka/ripdpi/data/*Quality*.kt`, `core/data/src/main/kotlin/com/poyka/ripdpi/data/NativeRuntimeSnapshot.kt`, `core/service/src/main/kotlin/com/poyka/ripdpi/service/telemetry/RuntimeTelemetryProjection.kt`.

6. **`feat(ui): RipDpiQualityThresholds tokens + DegradationStrip wiring`**
   - Create `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/RipDpiQualityThresholds.kt` with the constants listed in the threshold row. Token-consumption tests under `app/src/test/kotlin/com/poyka/ripdpi/ui/theme/` per `.claude/rules/rds-spec.md`.
   - Add a thin selector `fun ConnectionQualitySnapshot.toDegradationStripState(thresholds: RipDpiQualityThresholds): DegradationStripState?` that returns `null` when `sample_count < minSampleCountForVerdict`, else maps to the `RipDpiDegradationStrip` props at `app/src/main/kotlin/com/poyka/ripdpi/ui/components/feedback/RipDpiDegradationStrip.kt:53-180` (title/body/metrics/sinceLabel/tone). The `sinceLabel` formats `window_start_at_ms` with the app locale.
   - Wire the selector into `core/service/.../services/ServiceTelemetryCoordinator` to publish a `Flow<DegradationStripState?>` consumed by the home screen composable.
   - 7-locale strings: `vpn_quality_strip_warning_title`, `vpn_quality_strip_critical_title`, `vpn_quality_strip_body_warning`, `vpn_quality_strip_body_critical`, `vpn_quality_strip_since_format`, `vpn_quality_strip_reprobe`, `vpn_quality_strip_dismiss`, `vpn_quality_metric_loss`, `vpn_quality_metric_rtt_p50`, `vpn_quality_metric_jitter` shipped in `values/`, `values-ru/`, `values-es/`, `values-de/`, `values-fr/`, `values-fa/`, `values-zh-rCN/strings.xml` in the same commit per the project rule.
   - Files: `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/RipDpiQualityThresholds.kt`, `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/home/*.kt` (wiring), `app/src/main/res/values*/strings.xml` (7 locales), `app/src/test/kotlin/com/poyka/ripdpi/ui/theme/RipDpiQualityThresholdsTest.kt`.

7. **`feat(ui): realtime sample stream for throughput/latency graphs`**
   - Add `Flow<ConnectionQualitySnapshot>` accessor to the same telemetry coordinator. Graph composables (`vpn-throughput-graph`, `vpn-latency-graph` per the RDS deck) collect the last 60 snapshots (60s at 1Hz) into a `StateFlow<ImmutableList<QualitySamplePoint>>`. No new wire fields — the graph is a UI-side rolling buffer over the same snapshot stream.
   - Files: graph composables (TBD by which RDS slug they live under), home view-model.

8. **`test(quality): goldens + property tests + loom test for concurrent observer`**
   - Add `ConnectionQualitySnapshot` to `NativeTelemetryGoldenTest` (`:core:engine`) and `ServiceTelemetryGoldenTest` (`:core:service`) fixtures. Atomic rebless commit per `golden-bless-discipline.md` — the commit message MUST include the words "intentional behavioral change" and reference the G008-P5 issue.
   - Add `loom` test in `ripdpi-quality/tests/loom_concurrent_record.rs` for the observer-vs-snapshot race (one writer thread recording samples, one snapshot thread reading; assert no panic, monotonic `sample_count`).
   - `proptest` on the percentile derivation (p50 of a sorted slice equals `slice[len/2]` within 2-significant-digit precision).
   - Files: `core/engine/src/test/kotlin/.../NativeTelemetryGoldenTest.kt`, `core/service/src/test/kotlin/.../ServiceTelemetryGoldenTest.kt`, `core/engine/src/test/resources/golden/*.json`, `native/rust/crates/ripdpi-quality/tests/**`.

### Risks + mitigations

- **Histogram lock contention on hot path.** `Mutex<Histogram>` per `LatencyHistogram` shows ≈70 ns per record on aarch64. The hot path is one SOCKS5 connect per new TCP flow (~10-100/s sustained), so contention is non-issue. Mitigated by sizing: `hdrhistogram` with 2-sig-digit precision over 60s window is ~2 KB per histogram, 4 KB per `QualityWindow`, 16 KB across the four runtimes.
- **"Since 12:14" semantics under app process death.** LMK kill clears `window_start_at_ms`. Mitigation: do NOT persist `window_start_at_ms` — after process resume the strip is suppressed for `minSampleCountForVerdict` seconds, then re-anchored. Persisting would require fsync after every transition per `.claude/rules/android-vpn-lifecycle.md`, which is disproportionate for a UI affordance.
- **Threshold-cliff oscillation.** A loss% sitting on the 2.0 line will flip Warning on/off each tick. Mitigation: hysteresis built into the `toDegradationStripState` selector — once Warning is shown, it stays until `loss_pct < lossWarnPct * 0.7f`. Hard-coded in the selector, unit-tested.
- **Jitter computation choice.** "Jitter" can mean RFC 3550 (smoothed |Δ|) or population stddev. Pick **RFC 3550 jitter**: `J = J + (|D(i-1,i)| - J) / 16` matches industry tooling (Wireshark RTP stats) and is cheap. The choice is locked in the snapshot's `jitter_ms` field semantics — document in `ripdpi-quality/src/lib.rs` rustdoc.
- **Active-probe (option a) regression.** Behind a `quality.active_probe_enabled = false` settings flag. When enabled, the prober is a tokio task that issues UDP echoes to 1.1.1.1:443 — the socket MUST be `protect_socket(fd)`-wrapped per `.claude/rules/vpnservice-protect-invariant.md`, with the `jni-bridge-verifier` grep rule covering it. Treat as a follow-up PR after the piggyback shipping is stable.

---

## P3 — PCAP Export

### Decision matrix

| Question | Answer | Why |
| --- | --- | --- |
| **PCAP format** | **Classic libpcap (`*.pcap`)** with `LINKTYPE_RAW` (101) header. | Wireshark/tshark/tcpdump all open it without arguments; the parser we'd need Kotlin-side is 24 bytes of file header + 16 bytes of per-packet header. PCAP-NG metadata adds zero diagnostic value over the strategy fingerprint we already log separately. Raw IP linktype because the TUN device hands us bare IPv4/IPv6 packets (no Ethernet frame) per `native/rust/crates/ripdpi-tunnel-core/src/io_loop.rs:124` `phases::drain_tun`. |
| **Capture loop** | **(A) Tee in the TUN drain/flush path with a bounded `crossbeam::ArrayQueue<PcapRecord>` and a single dedicated writer thread.** | The `io_loop_task` at `native/rust/crates/ripdpi-tunnel-core/src/io_loop.rs:110-141` is the only place every TUN packet passes. Tap `phases::drain_tun` (input) and `phases::flush_tun` (output) at lines 124 and 130. ArrayQueue is lock-free MPSC — the io_loop never blocks. The writer thread is a `std::thread` (NOT tokio — fs writes are blocking and we do not want to disturb the runtime), drains the queue, batches into 64KiB writes, calls `fsync` every 1s OR every 1MiB written (whichever first). Memory-mapped writes (B) are rejected because mmap on app-private storage forces virtual-memory pressure that competes with the WireGuard userspace stack at peak throughput. |
| **File location** | **`context.filesDir / "pcap" / "<session-id>-<unix-ms>.pcap"`** (app-private, NOT externalCacheDir — files survive low-storage cleanup). Capture rotates at 16 MiB per file with a 4-file retention cap = 64 MiB worst case. Periodic fsync per the capture-loop decision above. **NEVER `MediaStore.Downloads` during capture** — copy to `MediaStore.Downloads/RIPDPI/` is the export step, gated on user consent. | App-private avoids `WRITE_EXTERNAL_STORAGE` scope (rejected on Android 13+ anyway). 16 MiB rotation gives ≈90 s of capture at typical streaming throughput (≈1.5 Mbps post-overhead) — long enough for any post-mortem, short enough to bound LMK loss. Per `.claude/rules/android-vpn-lifecycle.md`: state must survive `SIGKILL`; periodic fsync at 1 Hz captures every state transition without per-packet I/O. Treat the per-packet fsync rule (the "never `serde_json::to_writer` without fsync" rule from the lifecycle doc) as fulfilled at 1 Hz granularity here — the rule's intent is "no full LMK cycle of work in flight," and 1 s of pcap data is bounded loss. |
| **Export flow** | **`ActivityResultContracts.CreateDocument("application/vnd.tcpdump.pcap")`** — user picks destination via SAF. Then a coroutine in `ripdpi-pcap-export` Kotlin module copies the chosen capture file (after redaction if requested) to the user's chosen URI. NOT auto-copy to `MediaStore.Downloads/RIPDPI/`. | SAF puts the user in control of where the file lands (Files app, Drive, Telegram saved messages, …). `MediaStore.Downloads` requires `READ_MEDIA_*` permissions on Android 13+ and silently fails on shared-user-storage edge cases. SAF is one less permission, one fewer permission prompt, and the standard Android pattern for "export to user". The Files-app default destination is `~/Documents` which is fine for diagnostics. |
| **Redaction** | **Write-time, in the export path.** When `redactEndpoints == true` (the dialog flag at `app/src/main/kotlin/com/poyka/ripdpi/ui/components/feedback/RipDpiExportConsentDialog.kt:44`), the export pipeline opens the source `.pcap`, walks each packet, rewrites the IP header's `src` and `dst` fields to `0.0.0.0` (or `::` for IPv6), recomputes the IP and TCP/UDP checksums, and writes to the user's URI. The on-disk capture file always contains the real IPs — redaction is a transform applied during export only. TLS SNI is NOT redacted by `redactEndpoints` because the dialog flag is explicitly "Endpoint IPs" (see `RipDpiExportConsentDialogKt.kt:172-175` consent items). | Per `.claude/rules/network-fingerprint-privacy.md`: "no IPv4/IPv6 addresses of user devices in any persisted artifact." The export is what the user receives; an unredacted export violates the rule. The on-disk capture stays in app-private storage and is wiped on session-stop; it never reaches the user's storage in unredacted form unless the user explicitly unchecks the redaction box. Implementation lives in a new `ripdpi-pcap` library crate's `redact` module — call sites in Kotlin pass file paths, not buffers. |
| **Capture toggle UX** | **Manual toggle in Settings → Developer → "Packet capture (for diagnostics)" with an explicit consent screen.** Default OFF. When enabled, captures the next session start-to-stop in a 16 MiB-rotating file set. Auto-start on connection-error rejected because it captures sensitive traffic without per-session consent. Always-on rejected because it burns ≈3% CPU and 64 MiB disk continuously. | Privacy posture demands explicit user action per session class. The "consent screen" enumerates exactly what's captured (the same content as `RipDpiExportConsentDialog`'s consent items): packet headers, TLS SNI, endpoint IPs (redactable), strategy fingerprint. Setting flips a `bool` in `AppSettings` proto — capture activates on the NEXT session start, never mid-session (avoids the "I changed the toggle, did the current packet capture?" ambiguity). |
| **Crate location** | **New `ripdpi-pcap` library crate** (pure, `#![forbid(unsafe_code)]`, no Android deps) + a thin **integration in `ripdpi-tunnel-android`** (the tap on the io_loop tap). Export step is **Kotlin in a new `core/pcap-export/` module**. | The crate split mirrors the existing `ripdpi-telemetry` (library) → `ripdpi-tunnel-android` (Android integration) pattern at `native/rust/crates/ripdpi-telemetry/src/lib.rs` and `native/rust/crates/ripdpi-tunnel-android/src/telemetry/`. Keeping pcap writing in Rust is essential (zero-copy IP-header parsing, checksum recompute). Export-to-user is Kotlin because SAF is Java-only. The new module avoids bloating `:core:diagnostics`. |
| **JNI surface** | **Four new JNI methods on `Tun2SocksNativeBindings`** (matching the existing pattern at `native/rust/crates/ripdpi-tunnel-android/src/entry.rs:48-101`): `jniPcapStart(handle, capture_dir, max_file_bytes, max_files) -> Long` (returns capture-set id, 0 on failure), `jniPcapStop(handle, capture_set_id)`, `jniPcapListCaptures(handle) -> jstring` (JSON array of `{path, byteSize, packetCount, startedAt, endedAt}`), `jniPcapRedactToFile(source_path, dest_fd) -> jstring` (returns "ok" or error message; takes an fd so SAF-provided ParcelFileDescriptor can be passed directly). Lifecycle: capture-set is bound to the tunnel session — `jniDestroy` on the session implicitly retires any capture-set. | Symmetric to the existing handle-lifecycle contract at `JNI_CONTRACT.md` §4. Using a capture-set id (separate from the session handle) keeps the future "multiple capture sets per session" extensibility open. The fd-based redact contract avoids string-path SAF translation gymnastics. All four methods sit under `ffi_boundary` panic containment per `entry.rs:54`. |

### Implementation plan

Each commit < 300 LOC.

1. **`feat(pcap): introduce ripdpi-pcap library crate with libpcap writer + reader`**
   - `native/rust/crates/ripdpi-pcap/Cargo.toml`, `src/lib.rs`, `src/writer.rs`, `src/reader.rs`, `src/redact.rs`, `src/rotation.rs`, `src/tests.rs`. `PcapWriter::new(path, max_bytes)`, `PcapWriter::write_packet(&[u8], unix_micros)`, `PcapWriter::finalize()`. Classic format: 24-byte global header (magic `0xa1b2c3d4`, version 2.4, GMT offset 0, sigfigs 0, snaplen 65535, network `101`=LINKTYPE_RAW). Per-packet: 16-byte `pcaprec_hdr` (`ts_sec`, `ts_usec`, `incl_len`, `orig_len`) then the raw IP packet bytes.
   - `redact::rewrite_endpoints(src_path, dst_fd)`: stream-process, rewrite IPv4 src/dst to `0.0.0.0`, IPv6 src/dst to `::`, recompute IPv4 header checksum (1's complement sum), zero TCP/UDP checksum (the relaxed-checksum convention; modern tooling tolerates). `etherparse` or hand-rolled — vote hand-rolled because checksum recompute is 12 lines and `etherparse` pulls in unneeded protocol parsers.
   - `proptest` on roundtrip: write random IPv4 packets, read them back, byte-identical (modulo redaction).
   - Files: `native/rust/crates/ripdpi-pcap/**`, `native/rust/Cargo.toml` (workspace member + workspace dep).

2. **`feat(pcap): bounded queue + writer-thread plumbing in ripdpi-tunnel-android`**
   - Add `src/pcap.rs` module: `PcapCaptureSet { queue: Arc<ArrayQueue<PcapRecord>>, writer_thread: JoinHandle<()>, stop: Arc<AtomicBool>, set_id: u64, dir: PathBuf, ... }`. `start(handle, dir, max_file_bytes, max_files) -> u64` spawns the writer thread; `stop(handle, set_id)` flips `stop`, joins, fsyncs.
   - Queue capacity: 1024 records (≈1.5 MiB at MTU 1500; ≈ 6s at 200 pkt/s sustained). On queue-full, increment a `drops` counter and drop the packet — capture is best-effort. Wire `drops` into the capture-set metadata so the UI can warn if drops > 0.
   - Files: `native/rust/crates/ripdpi-tunnel-android/src/pcap.rs`, `native/rust/crates/ripdpi-tunnel-android/src/lib.rs` (mod declaration), `Cargo.toml` (add `ripdpi-pcap`, `crossbeam-queue`).

3. **`feat(pcap): tap drain/flush phases without disturbing the hot path`**
   - In `ripdpi-tunnel-core/src/io_loop.rs:121-131` (`io_loop_task`), thread an `Option<Arc<dyn PacketObserver>>` through `setup_io_loop` (`io_loop/setup.rs`). `PacketObserver::on_inbound(&[u8])` and `on_outbound(&[u8])` are called from `phases::drain_tun` (inbound) and `phases::flush_tun` (outbound) ONLY when `Some`. Cancel-safety annotation on `io_loop_task`: existing — no change. The observer trait is a synchronous `Fn`, NOT async, so no .await is added to the io_loop.
   - The `PacketObserver` lives in `ripdpi-tunnel-core` (next to `Stats::set_dns_latency_observer` precedent). `ripdpi-tunnel-android::pcap::PcapCaptureSet` implements it by pushing into the `ArrayQueue`.
   - Files: `native/rust/crates/ripdpi-tunnel-core/src/io_loop.rs`, `native/rust/crates/ripdpi-tunnel-core/src/io_loop/{phases.rs,setup.rs}`, `native/rust/crates/ripdpi-tunnel-core/src/lib.rs` (export the trait).

4. **`feat(pcap): JNI exports — start/stop/list/redact`**
   - Four exports per the decision-matrix row. Each delegates to a `pcap_*_entry` function in `src/session/pcap.rs` (new module under `session/`, NOT under `entry.rs`). The entry functions use `ffi_boundary(...)` for panic containment per `entry.rs:54-65` precedent.
   - `jniPcapListCaptures` returns a JSON string (`serde_json::to_string`) of `Vec<PcapCaptureMetadata>`, the same shape Kotlin will deserialize. Use `kotlinx.serialization` on the Kotlin side, `serde` on the Rust side, `camelCase` keys per `TELEMETRY_CONTRACT.md:104-107`.
   - `jniPcapRedactToFile(source_path, dest_fd)`: takes a `jint` fd (Kotlin passes `ParcelFileDescriptor.detachFd()` from the SAF document URI). Rust wraps the fd in `OwnedFd` via `FromRawFd`, streams the redaction. The fd is closed by Rust on completion (per `OwnedFd` Drop semantics). Kotlin must call `detachFd` (not `fd`) to transfer ownership.
   - Files: `native/rust/crates/ripdpi-tunnel-android/src/session/pcap.rs`, `native/rust/crates/ripdpi-tunnel-android/src/entry.rs` (add the four `pub extern "system" fn`s).

5. **`feat(pcap): Kotlin Tun2SocksNativeBindings + Tun2SocksTunnel API`**
   - Add the four `external fun` declarations to `Tun2SocksNativeBindings` (`core/engine/.../core/Tun2SocksTunnel.kt`).
   - Add a `PcapController` class to `core/pcap-export/` (new Gradle module) with `start(session: Tun2SocksTunnel, dir: File, maxFileBytes: Long, maxFiles: Int): Long`, `stop(session, captureSetId)`, `listCaptures(session): ImmutableList<PcapCaptureMetadata>`, `redactToUri(session, sourcePath: File, dest: Uri, redactEndpoints: Boolean)`. The redact step opens a `ParcelFileDescriptor` via `contentResolver.openFileDescriptor(dest, "w")` then passes `detachFd()` into the JNI call.
   - Files: `core/engine/src/main/kotlin/com/poyka/ripdpi/core/Tun2SocksTunnel.kt`, `core/pcap-export/build.gradle.kts`, `core/pcap-export/src/main/kotlin/.../PcapController.kt`, settings.gradle.kts (include the module).

6. **`feat(pcap): Kotlin PcapReader for the viewer screen`**
   - `core/pcap-export/src/main/kotlin/.../PcapReader.kt` — parses the libpcap header + per-packet records into `PcapPacket` exactly matching the existing data class at `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/PcapViewerScreen.kt:43-51`. Compute `summary` field by sniffing the first byte (`0x45..0x4F` = IPv4, `0x60..0x6F` = IPv6) then protocol (TCP=6, UDP=17), and for TCP/443 with TLS-prelude bytes (`0x16 0x03`) format "ClientHello" / "ServerHello" / etc. Use only the bytes already in the file — NO live network lookup.
   - Files: `core/pcap-export/src/main/kotlin/.../PcapReader.kt`, `core/pcap-export/src/test/**`.

7. **`feat(ui): PcapViewerScreen wiring + capture-list screen`**
   - The existing screen at `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/PcapViewerScreen.kt:62-187` already consumes `ImmutableList<PcapPacket>` — view-model glues it to `PcapReader.read(file)`.
   - New `PcapCaptureListScreen` (per RDS deck — verify slug, add to `docs/design/rds/COVERAGE.md` PR-side) shows `listCaptures()` output and routes to the viewer or the export consent dialog.
   - When user taps Export and confirms in `RipDpiExportConsentDialog` (`onExport(redactEndpoints)` at `RipDpiExportConsentDialog.kt:44`), launch `ActivityResultContracts.CreateDocument("application/vnd.tcpdump.pcap")`. The result-handler calls `PcapController.redactToUri(...)`.
   - Files: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/{PcapViewerScreen.kt,PcapCaptureListScreen.kt}`, view-model, navigation graph.

8. **`feat(ui): Settings → Developer toggle + 7-locale strings`**
   - Toggle wired to a new `AppSettings` proto field `pcap_capture_enabled: bool` (additive, default false — proto `CONFIG_CONTRACTS.md` compat is preserved).
   - 7-locale strings: `vpn_dev_pcap_toggle_label`, `vpn_dev_pcap_toggle_desc`, `vpn_dev_pcap_consent_title`, `vpn_dev_pcap_consent_warning`, `vpn_pcap_capture_list_title`, `vpn_pcap_capture_list_empty`, `vpn_pcap_capture_drop_warning_format`, plus the four already-present `vpn_pcap_viewer_*` and `vpn_export_consent_*` strings (already in `RipDpiExportConsentDialog.kt:56-67` reference list, audit they're 7-locale already).
   - Files: `core/data/src/main/proto/app_settings.proto`, `app/src/main/res/values*/strings.xml` (7 locales), settings screen composable.

9. **`test(pcap): roundtrip + redaction + process-death fixtures`**
   - Roborazzi screenshots for the viewer (existing previews at `PcapViewerScreen.kt:358-384` are the seed) — only IF goldens already cover this surface, otherwise defer to a separate visual-PR.
   - Process-death simulation: `adb shell am kill <package>` mid-capture, verify the next session lists the previous in-flight `.pcap` file and that file is readable (a partial-write at the tail is tolerated by the reader: incomplete final record is dropped).
   - `proptest` on redact round-trip (any IPv4 packet survives redact-then-parse).
   - Files: `core/pcap-export/src/test/**`, `native/rust/crates/ripdpi-pcap/tests/**`.

### Risks + mitigations

- **Disk-fill DoS by a runaway capture.** Bounded by 16 MiB × 4-file cap = 64 MiB worst case, automatic per-file rotation, automatic per-session cleanup after 7 days (background WorkManager job). Mitigation locked into `PcapCaptureSet::start` parameters; the parameters are NOT user-tunable through the UI to prevent accidental misconfiguration.
- **Capture writes blocking on slow flash storage.** Writer is on a dedicated `std::thread::Builder::new().name("ripdpi-pcap-writer-N")` (per `.claude/rules/android-vpn-lifecycle.md` thread-naming). ArrayQueue full → drop with counter (NEVER blocking-push). Visible in the UI as a yellow warning chip on the capture-list row.
- **TLS SNI hostnames leaked via the unredacted capture.** Expected behavior — the `RipDpiExportConsentDialog` consent items at `RipDpiExportConsentDialog.kt:167-170` explicitly warn "Hostnames visible." A second redaction option ("Redact SNI") is a follow-up; not in MVP because parsing TLS records to find SNI is more complex than IP-header rewrite and the user can choose not to export.
- **fd lifetime mistake in `jniPcapRedactToFile`.** Highest unsafe risk in this design. The contract: Kotlin calls `detachFd()` (transfers ownership), Rust wraps in `OwnedFd::from_raw_fd(fd)` (now solely responsible for close). If Kotlin calls `fd` (peek) instead of `detachFd`, both sides close the fd, yielding EBADF or a future-fd reuse race. Mitigation: the Kotlin wrapper class `PcapController` is the only call site; documented contract; an `assert(fd > 0)` in Rust; a Kotlin-side unit test that the wrong `fd` vs `detachFd` choice is caught. `unsafe { OwnedFd::from_raw_fd(...) }` requires `// SAFETY:` block per `.claude/rules/llm-rust-prompts.md`.
- **PCAP-format edge cases on the reader side.** Truncated final record (process-death mid-write) is the common case. `PcapReader::read` MUST tolerate `UnexpectedEof` on the LAST record — drop it, return everything before. Tested explicitly.
- **Privacy regression via the on-disk capture file.** The file lives in `filesDir/pcap/` which is `0700` per Android sandbox. NOT visible to other apps. NOT backed up by Android Auto-Backup (must add `<exclude domain="file" path="pcap/" />` to `data_extraction_rules.xml` and `full_backup_content.xml`). This is a checklist item, not a code change.

---

## P4 — Replay Orchestrator

### Decision matrix

| Question | Answer | Why |
| --- | --- | --- |
| **Reuse vs new crate** | **New Kotlin class `DefaultProbeReplayService` in `core/diagnostics/`**, NOT a new Rust crate. Reuses `StrategyProbeTransport` (`StrategyProbeService.kt:177-179`) for the actual probe execution; replay is an orchestration layer above it. | The existing `StrategyProbeService` at `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/StrategyProbeService.kt:189-236` runs many strategies × many domains. Replay runs ONE strategy × ONE domain but instruments each protocol step (DNS / TCP-connect / TLS / first-byte). The instrumentation is OkHttp `EventListener` granularity (already used by `ProbeEventListener` at `StrategyProbeService.kt:346-376`) — Kotlin-native, no Rust changes needed. A separate Rust replay crate would duplicate code with no payoff because the four step types (DNS / TCP / TLS / first-byte) already have clean Kotlin observation points via OkHttp's `EventListener`. |
| **What does a step mean?** | **Five canonical step kinds**: `DnsResolve`, `TcpOpen`, `TlsClientHello` (sent), `TlsHandshake` (full handshake complete), `FirstByte` (HTTP response head received OR explicit failure event). Mapped 1:1 to OkHttp `EventListener` callbacks: `dnsStart`/`dnsEnd`, `connectStart`/`connectEnd`, `secureConnectStart`/(sniff via `connect_packets`), `secureConnectEnd`, `responseHeadersEnd`. Failure events: `TcpReset` (synthesized when `connectFailed` with EOF/RST cause), `TlsAlert` (synthesized from `secureConnectFailed`). | The UI sample at `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/ReplayFailureScreen.kt:177-201` shows four steps (DNS, TCP open, TLS ClientHello, TLS reset) — the five canonical kinds cover that case plus the success path. Mapping to OkHttp is the same machinery `OkHttpStrategyProbeTransport` already uses — minimal new code. The `StrategyProbeFailureKind` enum (`StrategyProbeService.kt:85-89`) maps onto failure kinds: `Timeout → step has no terminal event → mark as `Pending` with elapsed ms`; `ConnectionFailed → TcpReset or TlsAlert depending on phase`; `DnsTampered → DnsResolve step succeeds but with a divergence note`. |
| **State machine boundaries** | **Streaming `Flow<ReplayStep>`** following the existing `StrategyProbeService.run(): Flow<StrategyProbeResult>` shape at `StrategyProbeService.kt:66`. The flow emits each step's `Pending → Success/Failure` transition (one per state change), terminates when the probe terminates (success or non-recoverable failure). The UI collects each emission, replaces the corresponding step in its `ImmutableList<ReplayStep>` (keyed by `ReplayStep.name`), and renders. The terminal step's `status` field carries the verdict. | Streaming is what the existing UI expects implicitly — each step in `ReplayFailureScreen.kt:175-201` has a `status: ReplayStepStatus { Success, Failure, Pending }` which only makes sense if the UI sees intermediate states. Request-response would force the UI to show all steps as Pending then flip them all at the end — wrong shape for a "watch the probe replay" experience. Following the existing `Flow` pattern keeps the testing approach identical (`StrategyProbeServiceTest.kt` precedent at `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/StrategyProbeServiceTest.kt`). |
| **Recommendation engine** | **JSON catalog** `core/diagnostics/src/main/assets/diagnostics/replay_recommendations.json` keyed by `(terminal_step, failure_signal)` tuple. E.g. `{"step":"TlsHandshake","signal":"reset_after_client_hello","recommendation":"Possible RST + SNI inspection · try tlsrec_split_host instead"}`. Loaded into a `ReplayRecommendationEngine` singleton on app start. Falls back to a generic message ("Probe failed at <step>; try a different strategy") when no rule matches. | Hard-coded mapping in source would force a code-change-and-release for every new pattern; the strategy catalog precedent at `core/diagnostics/src/main/assets/diagnostics/` (which contains the diagnostics packs/profiles per `.claude/rules/diagnostics-system`) shows the JSON-asset pattern is established. The catalog is versioned and can be A/B'd via remote config later. 7-locale strings live OUTSIDE the JSON — the catalog stores a string-resource key (`R.string.vpn_replay_rec_rst_sni_inspection`), the UI resolves it. JSON catalog has no localized strings → no locale rot. |
| **JNI surface** | **NONE.** Replay is entirely Kotlin. No Rust JNI bridge added. | The probe runs through `OkHttpStrategyProbeTransport` at `StrategyProbeService.kt:293-376` which already speaks the local SOCKS5 proxy (`127.0.0.1:1080`). The strategy is activated through `SettingsStrategyProbeActivator` at `StrategyProbeService.kt:270-291` which mutates `AppSettings` proto — that's the existing path through the native runtime. Adding a JNI surface for replay would replicate `StrategyEngineNativeBindings::injectProbeResults` (`NativeStrategyProbeResultInjector.inject` at `StrategyProbeService.kt:403-428`) with no benefit. The cost-of-not-doing is zero: the OkHttp `EventListener` gives us step-level latency without going to Rust. |
| **Persistence** | **Ephemeral session-scoped + opt-in archive.** Replay results live in a `MutableStateFlow<ReplayResult>` held by the view-model. NOT persisted to `core/diagnostics-data/` by default — replay is a debug-time inspection, not a historical-trend signal. User can tap "Save to diagnostics archive" which routes through the existing `DiagnosticsArchiveApi` at `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsArchiveApi.kt`, formatting the replay as a JSON blob attached to the current scan archive. | Persisting every replay would inflate the `:core:diagnostics-data` Room database with low-value churn (each replay is a snapshot of a specific failing strategy; the strategy-probe table already captures the strategy's overall success rate). Opt-in archive preserves the rare "save for support ticket" case. The 30-day retention story stays unchanged. |

### Implementation plan

Each commit < 300 LOC.

1. **`feat(replay): ReplayStep model + ReplayService interface in :core:diagnostics`**
   - `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/ReplayStep.kt` — sealed-class hierarchy `ReplayStepKind { DnsResolve, TcpOpen, TlsClientHello, TlsHandshake, FirstByte }` + a status `enum ReplayStepStatus { Pending, Success, Failure }` (this already exists in the UI at `ReplayFailureScreen.kt:34` — REUSE that, do not duplicate; move to a shared location if necessary).
   - `ReplayProbeRequest(domain: String, strategyId: String, timeoutMs: Long)`, `ReplayProbeResult(steps: List<ReplayStep>, terminalStep: ReplayStepKind, terminalStatus: ReplayStepStatus, recommendation: String)`.
   - `interface ProbeReplayService { fun run(request: ReplayProbeRequest): Flow<ReplayStep> }`.
   - Files: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/{ReplayStep.kt,ProbeReplayService.kt}`, possibly relocate `ReplayStep`/`ReplayStepStatus` from the UI module to here and have the UI re-export.

2. **`feat(replay): DefaultProbeReplayService backed by OkHttp EventListener`**
   - `DefaultProbeReplayService` implementing `ProbeReplayService`. Uses the existing `StrategyProbeActivator` to activate the strategy, then runs an OkHttp HEAD request with a `ReplayEventListener` that emits a step on each `EventListener` callback. Listener pseudo-code:
     ```kotlin
     override fun dnsStart(call: Call, domainName: String) { emit(DnsResolve, Pending) }
     override fun dnsEnd(call: Call, ...) { emit(DnsResolve, Success) }
     override fun connectStart(...) { emit(TcpOpen, Pending) }
     override fun connectEnd(...) { emit(TcpOpen, Success) }
     override fun secureConnectStart(...) { emit(TlsClientHello, Pending); emit(TlsClientHello, Success) }  // we don't get a "sent" hook, infer from secureConnectStart
     override fun secureConnectEnd(...) { emit(TlsHandshake, Success) }
     override fun responseHeadersEnd(...) { emit(FirstByte, Success) }
     override fun callFailed(call: Call, ioe: IOException) { emit(currentStep, Failure, ioe) }
     ```
     The `emit` shim writes to a `Channel<ReplayStep>` consumed by the flow.
   - On `callFailed`, classify cause: `SSLException` containing `"handshake_failure"` → TLS-alert; `IOException` containing `"Connection reset"` → TCP RST; `SocketTimeoutException` → timeout. Pattern-match against the catalog.
   - Cancel safety: the `OkHttp.Call` is cancelled via `call.cancel()` in the flow's `awaitClose`. Annotated `// cancel-safe: call.cancel() is sync, EventListener emits are non-blocking`.
   - Files: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/DefaultProbeReplayService.kt`.

3. **`feat(replay): ReplayRecommendationEngine + JSON catalog`**
   - `ReplayRecommendationEngine.recommendationFor(terminalStep: ReplayStepKind, signal: String): RecommendationKey` returns a string-resource key or `RecommendationKey.GenericFallback`. The catalog JSON ships under `core/diagnostics/src/main/assets/diagnostics/replay_recommendations.json` with shape `[{step, signal, recommendationKey}]`. Loaded lazily, cached.
   - Files: `core/diagnostics/src/main/assets/diagnostics/replay_recommendations.json`, `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/ReplayRecommendationEngine.kt`, unit tests.

4. **`feat(replay): ReplayFailureRoute wiring (view-model + navigation)`**
   - `ReplayFailureViewModel(replayService: ProbeReplayService, recommendationEngine: ReplayRecommendationEngine)` exposes `StateFlow<ReplayUiState>`. The UI screen at `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/ReplayFailureScreen.kt:44-105` already takes the right shape (`steps: ImmutableList<ReplayStep>`, `recommendation: String`).
   - The existing `ReplayFailureRoute.kt` at `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/ReplayFailureRoute.kt` is the integration point — wire it to launch a replay on entry, collect the flow, update the state.
   - Replay entry-points: from the diagnostics scan results when a strategy has failed, from the live diagnostics section when an active session encounters errors.
   - Files: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/ReplayFailureRoute.kt`, new view-model file, Hilt module updates.

5. **`feat(replay): 7-locale strings + RDS spec link`**
   - 7-locale strings: each entry in the recommendation catalog has a `recommendationKey` that resolves to a `R.string.vpn_replay_rec_*` resource. Initial catalog entries (5-8 patterns): `vpn_replay_rec_rst_after_client_hello`, `vpn_replay_rec_dns_tampered`, `vpn_replay_rec_tcp_unreachable`, `vpn_replay_rec_handshake_alert`, `vpn_replay_rec_timeout_no_response`, `vpn_replay_rec_generic_fallback`. Plus `vpn_replay_failure_replay`, `vpn_replay_failure_recommendation_title`, `vpn_replay_failure_header_format`, `title_replay_failure` if not already present.
   - PR description links the RDS spec card (slug `replay-failure-screen` per `docs/design/rds/preview/`).
   - Files: `app/src/main/res/values*/strings.xml` × 7 locales.

6. **`feat(replay): opt-in archive integration`**
   - "Save to diagnostics archive" button on the replay screen routes to `DiagnosticsArchiveApi.attachReplay(scanId, ReplayProbeResult)`. JSON-serialized via the diagnostics archive's existing redaction pipeline (which already enforces the privacy rules per `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsArchiveApi.kt`).
   - Files: archive API extension, UI button wiring, 1 new 7-locale string `vpn_replay_save_to_archive`.

7. **`test(replay): StrategyProbeService-style unit tests + recommendation-catalog parity test`**
   - Replay service unit-test pattern mirrors `StrategyProbeServiceTest.kt`. Use OkHttp `MockWebServer` to inject scripted failures at each phase (TCP RST mid-handshake, TLS alert, DNS resolution failure via overridden `Dns` interface).
   - Catalog parity test: every key in `replay_recommendations.json` MUST resolve to an existing `R.string.vpn_replay_rec_*` resource in `values/strings.xml`, AND that string MUST exist in all 7 locales. Test asserts: enumerate JSON, look up each `recommendationKey` in `R.string`, fail with the missing key name if absent.
   - Files: `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/replay/**`.

### Risks + mitigations

- **Strategy mutation racing with the live VPN session.** Activating a strategy for replay mutates `AppSettings` proto via `SettingsStrategyProbeActivator.activate` at `StrategyProbeService.kt:281-286`. If the VPN is actively running, the live runtime will reconfigure mid-session. Mitigation: replay is permitted ONLY when the VPN is in `Halted` state OR when the user explicitly opts in via a "Replay disrupts live session" confirmation dialog (similar to the existing strategy-probe activator's restore-on-finally pattern at `StrategyProbeService.kt:230-234`). Activator's `restore(snapshot)` (line 288) returns the user's prior settings — the same `try { activate(); ... } finally { withContext(NonCancellable) { restore(snapshot) } }` shape from `StrategyProbeService.kt:201-234` is preserved verbatim.
- **OkHttp EventListener missing one of the 5 step kinds.** `secureConnectStart` doesn't separate "sending ClientHello" from "received ServerHello" — we infer the boundary. If a future TLS edge case makes the inference wrong, the step boundary is wrong. Mitigation: keep the 5 kinds frozen until proven inadequate; the OkHttp callbacks are stable across versions.
- **Recommendation catalog drift between locales.** The parity test in commit 7 is the gate. The 7-locale-in-same-commit project rule per `CLAUDE.md` is the second gate.
- **Replay against a target that is genuinely down.** All 5 steps would fail with `TcpUnreachable`; the recommendation is `vpn_replay_rec_generic_fallback`. This is correct behavior — replay diagnoses what's wrong, not what's up. UI surfaces it honestly.
- **`SettingsStrategyProbeActivator.restore` failure during cancellation.** If `restore` itself fails, the user's settings stay mutated. The existing `NonCancellable` wrapper at `StrategyProbeService.kt:231` prevents flow cancellation from skipping `restore`. Replay follows the identical pattern. If `restore` throws, surface as a "settings may have been altered — please reconnect" toast.

---

## Shared conventions

### Cancel-safety annotation discipline

Every new `async fn` and every new `tokio::select!` arm in the three subsystems carries a one-line comment, per `.claude/rules/llm-rust-prompts.md` item 3. Sample formats:

```rust
// cancel-safe: observer.record() is synchronous, no .await between measurement
//              and emit; spurious cancellation between the Instant capture and
//              the record call drops at most one sample.
async fn record_tcp_connect_rtt(...) -> io::Result<()> { ... }

// NOT cancel-safe: pcap writer thread holds the queue lock; cancellation
//                  between pop and write loses one packet (acceptable, capture
//                  is best-effort). DO NOT use inside tokio::select! arms.
fn drain_pcap_queue(...) { ... }
```

`async-cancel-safety` sub-agent (per `.claude/rules/llm-rust-prompts.md` cross-references) runs as a PR gate over the touched files.

### JNI export naming convention

Follow the established pattern at `native/rust/crates/ripdpi-tunnel-android/src/entry.rs:48-101`:

- Symbol: `Java_com_poyka_ripdpi_core_<KotlinClass>NativeBindings_jni<Verb>`.
- All exports wrapped in `ffi_boundary(default_value, move || delegate_entry(env, ...))` per `entry.rs:54` for panic containment.
- Long-running work spawned on a dedicated `std::thread::Builder::new().name("ripdpi-<purpose>-N")` — NEVER blocks the JNI caller per `JNI_CONTRACT.md` §12 and `.claude/rules/android-vpn-lifecycle.md` thread-naming.
- Lifecycle: every `jni<Verb>Start` returns an opaque positive `jlong` (`0` = failure); every `jni<Verb>Stop`/`jni<Verb>Destroy` is idempotent on the Rust side.

### Schema-version-bump protocol

Per `docs/architecture/TELEMETRY_CONTRACT.md:114-129`: **routine additive field additions do NOT bump `SNAPSHOT_SCHEMA_VERSION`**. The current value `SNAPSHOT_SCHEMA_VERSION = 1` at `native/rust/crates/ripdpi-tunnel-android/src/telemetry/types.rs:18` stays `1` for all three subsystems. The constant is reserved for a future *breaking* change.

For each additive field:
1. Rust side: `#[serde(skip_serializing_if = "Option::is_none")]` + `Option<T>`.
2. Kotlin side: defaulted field on the matching `@Serializable` class.
3. Golden fixture update + Kotlin/Rust golden test re-bless in the SAME commit per `.claude/rules/golden-bless-discipline.md` — commit message must include "intentional behavioral change in `<subsystem>`" plus the issue reference.
4. NO `Json { ignoreUnknownKeys }` toggle change — that's already on per `TELEMETRY_CONTRACT.md:147`.

### Test coverage gates per crate

| Crate | Required test types | Where to wire |
| --- | --- | --- |
| `ripdpi-quality` | `cargo nextest run --locked`, `proptest` on percentile derivation, `loom` test for observer-vs-snapshot race | `native/rust/crates/ripdpi-quality/tests/**` |
| `ripdpi-pcap` | `cargo nextest run --locked`, `proptest` on write-then-read roundtrip, `proptest` on redact roundtrip, Miri on the unsafe `OwnedFd::from_raw_fd` site | `native/rust/crates/ripdpi-pcap/tests/**` + `.github/workflows/miri-nightly.yml` job inclusion per `.claude/rules/llm-rust-prompts.md` "CI infrastructure expectations" |
| `ripdpi-tunnel-android` (pcap tap) | Existing `cargo nextest` profile + new integration test driving a fake `TunDevice` through a session with capture enabled | `native/rust/crates/ripdpi-tunnel-android/src/pcap.rs` `#[cfg(test)]` block |
| `:core:diagnostics` (replay) | Junit + MockWebServer + 7-locale parity test for the recommendation catalog | `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/replay/**` |
| `:core:pcap-export` | Junit on the reader/writer roundtrip + a Robolectric test on the `ContentResolver.openFileDescriptor` path | `core/pcap-export/src/test/**` |
| Wire goldens | `NativeTelemetryGoldenTest` + `ServiceTelemetryGoldenTest` updated atomically with the additive field commits | `core/engine/src/test/**`, `core/service/src/test/**` |

`--locked` discipline per `.claude/rules/rust-toolchain-pin.md`: every cargo invocation in CI passes `--locked`. New crates' `Cargo.toml` MUST NOT pin a dependency version not already in the workspace `[workspace.dependencies]`.

`#![forbid(unsafe_code)]` mandatory on `ripdpi-quality` and `ripdpi-pcap` (pure libraries). Only `ripdpi-tunnel-android::pcap` is permitted one `unsafe { OwnedFd::from_raw_fd(fd) }` block — with a `// SAFETY:` comment per `.claude/rules/llm-rust-prompts.md` "Diff acceptance gate".

### VPN protect invariant audit

Per `.claude/rules/vpnservice-protect-invariant.md`, the grep covering all three subsystems' new code:

```bash
rg "TcpStream::connect|UdpSocket::bind|mio::net::TcpSocket::connect|tokio::net::(TcpStream|UdpSocket)" \
   native/rust/crates/ripdpi-quality/ \
   native/rust/crates/ripdpi-pcap/ \
   native/rust/crates/ripdpi-tunnel-android/src/pcap.rs \
   --type rust -n
```

Expected matches: ZERO for `ripdpi-quality` and `ripdpi-pcap` (both are pure libraries that don't open sockets). `ripdpi-tunnel-android::pcap` is filesystem-only, also zero matches expected. The **only** outbound-socket addition across the three subsystems is the optional P5 active-probe (gated behind `quality.active_probe_enabled = false`); it lives in `ripdpi-quality::active_probe` (a feature-gated module) and its socket creation must be preceded by `protect_socket(fd)` per the rule. The grep MUST be in the PR description as a verification step.

### Privacy audit

Per `.claude/rules/network-fingerprint-privacy.md`:

```bash
grep -rE 'imei|imsi|bssid|carrier_name' \
  native/rust/crates/ripdpi-quality/ \
  native/rust/crates/ripdpi-pcap/ \
  native/rust/crates/ripdpi-tunnel-android/src/pcap.rs \
  core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/ \
  core/pcap-export/src/main/kotlin/ \
  --type rust --type kotlin | grep -v "// allow:"
```

Expected matches: ZERO. The PCAP file contains IP addresses (acceptable — they're transport-layer, redactable on export). Telemetry quality snapshots contain only aggregate counters. Replay results contain hostname (the user-typed target) but no device identifiers — the hostname is user-input, distinct from device-leaked identifiers.

---

## Implementation order

```
                    P5-1: ripdpi-quality crate
                            │
                            ▼
                    P5-2: TCP-session observer in tunnel-core
                            │
                            ▼
                    P5-3: tunnel-android wiring
                            │
                            ├──▶  P5-4: proxy/relay/warp parallel wiring
                            │              │
                            │              ▼
                            │     P5-5: Kotlin DTO + projection
                            │              │
                            │              ▼
                            │     P5-6: Thresholds + DegradationStrip
                            │              │
                            │              ▼
                            │     P5-7: Graph stream
                            │              │
                            │              ▼
                            │     P5-8: Goldens + property/loom tests
                            │              │
                            ▼              │
                    P3-1: ripdpi-pcap crate  (parallel-OK once P5-1 is in)
                            │
                            ▼
                    P3-2: tunnel-android pcap module
                            │
                            ▼
                    P3-3: io_loop observer tap in tunnel-core
                            │
                            ▼
                    P3-4: JNI exports
                            │
                            ▼
                    P3-5: Kotlin PcapController + bindings
                            │
                            ├──▶  P3-6: PcapReader
                            │              │
                            │              ▼
                            │     P3-7: UI wiring (viewer + list + export)
                            │              │
                            │              ▼
                            │     P3-8: Settings + 7-locale strings
                            │              │
                            │              ▼
                            │     P3-9: Tests + Miri job
                            │              │
                            ▼              │
                    P4-1: ReplayStep model  (parallel-OK; no Rust deps)
                            │
                            ▼
                    P4-2: DefaultProbeReplayService
                            │
                            ▼
                    P4-3: RecommendationEngine + catalog
                            │
                            ▼
                    P4-4: ReplayFailureRoute + view-model
                            │
                            ▼
                    P4-5: 7-locale strings + RDS link
                            │
                            ▼
                    P4-6: Archive integration
                            │
                            ▼
                    P4-7: Tests + parity gate
```

Critical-path note: P5-3 (additive `connection_quality` field on `NativeRuntimeSnapshot`) is the first golden-shifting commit. The `ServiceTelemetryGoldenTest` rebless MUST land in the same PR per `.claude/rules/golden-bless-discipline.md`. P3 can start in parallel after P5-1, since `ripdpi-pcap` has no dependency on `ripdpi-quality`. P4 can start in parallel at any time — it touches no Rust crates and no JNI surface.

Per-PR gates (executor enforces):
- `cargo nextest run --workspace --locked` green
- `cargo clippy --workspace --locked -- -D warnings` green
- `./gradlew lint test` green
- Goldens unchanged OR rebless commit pair present with the "intentional behavioral change" sentinel in the message
- 7-locale parity audit (`scripts/check-readme-selectors.sh` analog for strings if it exists, or the inline `for XX in ru es de fr fa zh-rCN; do ... done` from `.claude/rules/rds-spec.md`)
- VPN-protect grep matches the expected count
- Privacy grep returns zero

---

## Open questions for the user

Three calls that are policy decisions, not architecture decisions — I picked the path I'd defend, but flag them for explicit confirmation:

1. **Pcap capture toggle UX location.** I picked "Settings → Developer → Packet capture". An alternative is "diagnostics screen contextual button" — captures only when the user is actively diagnosing. The first is more discoverable for power users; the second is more privacy-preserving by default. My call: Developer setting, because diagnostics-scoped capture would miss the "I had a problem an hour ago, let me look at the pcap" workflow. Confirm or override.

2. **Replay against a live VPN session.** I picked "permitted with confirmation dialog." Alternative is "forbidden — Halt the VPN first." The first preserves the ability to A/B-test a replacement strategy without dropping the user's session; the second is operationally cleaner. My call: permitted with confirmation, because the existing `StrategyProbeService` already supports this pattern (`activator.capture()` snapshot + `finally { restore }` at `StrategyProbeService.kt:201-234`). Confirm or override.

3. **PCAP retention default.** I picked "16 MiB × 4 files × 7-day automatic cleanup." Reasonable alternatives: smaller (4 MiB × 2 × 24h) for tighter privacy; larger (64 MiB × 8 × 30d) for richer post-mortem. Pick a knob value and lock it. My call: 16 MiB × 4 × 7d as a middle ground that respects flash wear and storage budget on low-end devices.
