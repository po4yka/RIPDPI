---
name: rust-android-telemetry
description: Telemetry and observability discipline for the RIPDPI Android Rust stack — control-plane vs data-plane logging, bounded flume event queues, atomic counters, snapshot polling, readiness callbacks, and deterministic JSON contracts. Use when authoring or modifying telemetry emission code, the bounded event ring, the Kotlin-side telemetry consumer, or any per-packet logging.
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

The live implementation is `android-support/src/events.rs`: each `EventRingLayer` owns a bounded `flume` queue. Emission is non-blocking. When the queue is full, the oldest record is removed and the new record is retried; the dropped-event counter is incremented explicitly. Proxy, relay, WARP/AmneziaWG, tunnel, and diagnostics domains use separate rings, with accepted aliases normalized at the boundary.

Do not replace this with `tokio::sync::broadcast`, an unbounded queue, or a blocking mutex-backed buffer. Consumers drain a single domain ring into the polled runtime snapshot; they are not independent subscribers that must each receive every event. Preserve capacity bounds, FIFO order among retained events, drop-oldest behavior, and the observable dropped count.

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

## Snapshot polling and readiness push

Runtime telemetry remains a coarse-grained pull surface: Kotlin polls a JSON snapshot that contains counters and drained bounded events. Never call into Java per packet or per telemetry event.

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

Runtime readiness is the deliberate exception: ADR 0003 replaced readiness polling with a one-shot `onRuntimeReady()` JNI callback. Keep readiness callback registration/generation tokens separate from the periodic telemetry snapshot and never reintroduce a readiness sentinel that depends on the next poll.

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
