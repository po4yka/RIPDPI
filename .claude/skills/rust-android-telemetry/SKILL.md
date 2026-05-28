---
name: rust-android-telemetry
description: Telemetry and observability discipline for the RIPDPI Android Rust stack — control-plane vs data-plane channel selection (android_logger vs tracing), bounded event ring choice (broadcast vs ArrayQueue), atomic counters, pull-model 1Hz polling, deterministic JSON serialization for goldens. Use when authoring or modifying telemetry emission code, the bounded event ring, the Kotlin-side telemetry consumer, or any per-packet logging.
---

# Rust Android Telemetry -- RIPDPI

## Purpose

Telemetry on Android has different cost shapes than on a server:
- `liblog` JNI overhead per event makes structured logging on the per-packet path a measurable CPU bottleneck.
- Kotlin UI consumers, golden-test harnesses, and CSV exporters all need access to the same event stream — channel selection determines whether a slow consumer blocks the producer.
- LMK can SIGKILL the process at any time, so any "exactly-once" delivery assumption is wrong.
- Privacy rules (see `network-fingerprint-privacy.md`) constrain what may even be emitted.

This skill codifies the channel-selection and serialization rules that have been validated on real Android devices (Pixel, Samsung, OPPO) at 1 Gbps loopback throughput.

## Logging channel selection

| Channel | Use for | Forbidden for | Cost |
|---------|---------|---------------|------|
| `android_logger` (forwarding `log` crate to `__android_log_print`) | Control plane: lifecycle, errors, single-shot diagnostics. | Per-packet, per-byte paths. | ~1 µs per event without args; ~3 µs with formatted args. JNI call into `liblog`. |
| `tracing-android` / `tracing-logcat` (`tracing::Subscriber` to logcat) | Structured control-plane events; spans for control-flow tracing. | Per-packet, per-byte paths. | ~3 µs per `event!()` including formatting and atomic-CAS on subscriber registry. |
| `tracing-android-trace` (Perfetto backend) | Performance investigations only, NOT production. | Permanently enabled in release. | Heavy. Use only behind a debug-build feature flag. |
| Bare `AtomicU64::fetch_add` | Data-plane counters (packets, bytes, drops). | Any event that isn't a count. | Single instruction on ARM64 LDADD/LDSET. |

Rule: any `tracing::event!`, `tracing::span!`, `log::info!`, or `log::debug!` inside a per-packet or per-byte code path is REJECTED. The exception is `tracing::span!(Level::ERROR, ...)` for error paths where the slow case is acceptable.

Detection:

```bash
rg "tracing::|log::" native/rust/crates/ripdpi-proxy-runtime/src/ --type rust -n \
  | grep -vE "control|lifecycle|error" \
  | head -20
```

## Bounded event ring

For control-plane events that multiple consumers must read (Kotlin UI, golden harness, CSV exporter), pick a primitive:

| Primitive | Topology | Backpressure on lag | Memory | RIPDPI use |
|-----------|----------|---------------------|--------|-------------|
| `tokio::sync::broadcast` | SPMC or MPSC; multiple receivers see every message | `Lagged(n)` error returned to slow receivers; fast receivers unaffected | Fixed ring × N receivers | **Recommended for control events.** |
| `crossbeam-queue::ArrayQueue` | MPMC; one receiver pulls one message | Producer's `push` returns `Err` when full; data lost silently if not checked | Fixed ring | Acceptable for single-consumer counters. |
| `tokio::sync::mpsc` | MPSC; single receiver | Producer awaits when full | Bounded with size hint | Useful when ordering across producers must be preserved. |
| `RwLock<VecDeque>` | Any | Blocks hot path on reader | Unbounded | REJECTED. |

The `Lagged(n)` from `broadcast` is the signal you need: explicit count of dropped events per slow receiver. Pattern:

```rust
let (tx, _) = tokio::sync::broadcast::channel::<TelemetryEvent>(1024);

// Producer (in io_loop or session task):
let _ = tx.send(TelemetryEvent::ClientAccepted { ... });

// Consumer (Kotlin polling via JNI, golden harness, etc.):
loop {
    match rx.recv().await {
        Ok(event) => process(event),
        Err(RecvError::Lagged(n)) => {
            // Slow consumer dropped n events; surface via metric.
            metrics::dropped_telemetry_events.fetch_add(n, Ordering::Relaxed);
        }
        Err(RecvError::Closed) => break,
    }
}
```

Forbidden: `while let Ok(event) = rx.recv().await { ... }`. This swallows `Lagged` silently. See `rust-async-internals` skill for the broader anti-pattern.

## Data-plane counters

Per-direction counters (packets, bytes, errors) live as `AtomicU64`:

```rust
pub struct DataPlaneCounters {
    pub tx_packets: AtomicU64,
    pub tx_bytes:   AtomicU64,
    pub rx_packets: AtomicU64,
    pub rx_bytes:   AtomicU64,
    pub drops:      AtomicU64,
}

impl DataPlaneCounters {
    pub fn record_tx(&self, n: usize) {
        self.tx_packets.fetch_add(1, Ordering::Relaxed);
        self.tx_bytes.fetch_add(n as u64, Ordering::Relaxed);
    }
}
```

Ordering: `Relaxed` is correct for counters (atomicity, no happens-before required). See `memory-model` skill for the rationale.

## Pull-model 1 Hz polling from Kotlin

A push-model where Rust calls back into Java per event creates JNI back-pressure (5–15 µs per `attach_current_thread + call_method`). Pull-model:

```rust
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_TelemetryNative_jniSnapshot(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
) -> JString {
    env.with_env(|env| -> jni::errors::Result<JString> {
        let snap = TelemetrySnapshot {
            counters: COUNTERS.read(),
            events: EVENT_BUFFER.drain(),
            // ...
        };
        let json = serde_json::to_string(&snap)
            .map_err(|e| jni::errors::Error::JavaException)?;
        env.new_string(json)
    })
    .into_outcome()
    .ok_or_throw(env, JString::default())
}
```

Kotlin polls at 1 Hz from a coroutine, parses the JSON, updates UI. Single JNI call per second regardless of event rate.

## Deterministic JSON for goldens

Goldens compare serialized telemetry across runs. Use `serde_json::ser::PrettyFormatter` with 2-space indent AND sorted keys for stability:

```rust
use serde::Serialize;
use serde_json::ser::{PrettyFormatter, Serializer};

fn to_golden_json<T: Serialize>(value: &T) -> Result<String, serde_json::Error> {
    let mut buf = Vec::new();
    let mut ser = Serializer::with_formatter(&mut buf, PrettyFormatter::with_indent(b"  "));
    value.serialize(&mut ser)?;
    Ok(String::from_utf8(buf).expect("valid UTF-8"))
}
```

For sorted keys: `serde_json` does not sort by default. Either use `#[derive(Serialize)]` with field order = alphabetical, or use a `BTreeMap<String, T>` instead of `HashMap`, or pipe through a post-processing step that sorts.

Add to `tests/golden/scrub.json`:

```json
{
  "scrub_paths": [
    "$.events[*].timestamp_ms",
    "$.events[*].request_id",
    "$.counters.uptime_ms",
    "$.fingerprint.first_seen_ms"
  ]
}
```

The `golden-blesser` agent reads `scrub.json` before classifying a diff as semantic vs volatile.

## Privacy floor

Per `network-fingerprint-privacy.md`, telemetry emission MUST NOT include:
- Raw BSSID, SSID (use SHA-256 fingerprint contribution).
- IMEI, IMSI, MEID, ESN under any encoding.
- IP addresses of user devices (LAN or WAN).
- TLS secrets, ClientHello payload bytes, HTTP request bodies.
- Packet payloads. (Counters, sizes, flow IDs — yes. Bytes — no.)

Grep audit before every release:

```bash
rg "bssid|ssid|imei|imsi|raw_ip" native/rust/ --type rust -n \
  | grep -v "// allow:" \
  | head -20
```

## Crash and ANR observability

`liblog` truncates long lines. For panic info: install `std::panic::set_hook` in `JNI_OnLoad` that calls `android_log_writer::write_log!()` with the full payload split into <= 4 KiB chunks. Otherwise the panic info is truncated and the root cause is invisible in logcat.

ANR (Application Not Responding) is a Kotlin-side concept; from Rust's side, the signal is `Tokio runtime stalled > N seconds` measurable via a heartbeat task. Heartbeat:

```rust
tokio::spawn(async move {
    loop {
        tokio::time::sleep(Duration::from_secs(1)).await;
        HEARTBEAT.store(SystemTime::now()
            .duration_since(UNIX_EPOCH).unwrap().as_secs(),
            Ordering::Relaxed);
    }
});
```

Kotlin's main thread polls `HEARTBEAT` via JNI; if stale > 10s, raises an ANR-precursor alert.

## Related skills

- `rust-async-internals` — `broadcast` `Lagged` handling; `JoinSet` shutdown.
- `memory-model` — `Relaxed` ordering for counters.
- `rust-android-jni` — JNI call cost; thread-naming for logcat readability.
- `network-fingerprint-privacy.md` rule — what cannot appear in emitted telemetry.
- `android-vpn-lifecycle.md` rule — process-death implications for telemetry buffers.
- `golden-blesser` agent — golden diff classification.
