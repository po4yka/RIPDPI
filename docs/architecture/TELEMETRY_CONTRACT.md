# Telemetry Contract

Who owns each telemetry **event** and **snapshot**, the JSON payload rules that
keep the Rust→Kotlin boundary forward- and backward-safe, and the stable
identifiers that may never be renamed.

Scope: the **runtime telemetry** surface — the proxy / relay / warp / tunnel
snapshots and native events Kotlin polls while a session runs. The diagnostics
**scan** wire contract (`ScanRequest` / `ScanReport`) is a separate, explicitly
versioned contract — see [`DIAGNOSTICS_ARCHITECTURE.md`](DIAGNOSTICS_ARCHITECTURE.md)
and [`CONFIG_CONTRACTS.md`](CONFIG_CONTRACTS.md) §8.

Companion docs: [`CONFIG_CONTRACTS.md`](CONFIG_CONTRACTS.md) (the sibling
config-JSON contract), [`JNI_CONTRACT.md`](JNI_CONTRACT.md) (the boundary the
JSON crosses), [`ARCHITECTURE.md`](ARCHITECTURE.md).

This document is **descriptive** — it changes no telemetry behavior. It cites
the exact files that own each contract.

---

## Ownership — who produces, who consumes

```
Rust runtime  ──emit──▶  android-support event ring  ──┐
ripdpi-telemetry recorder (counters/gauges/histograms) ─┤
                                                        ▼
              ripdpi-android-telemetry-adapter  (projects → NativeRuntimeSnapshot)
                                                        ▼  JSON string over JNI (~1 Hz poll)
              core/engine RipDpi{Proxy,Relay,Warp}.kt / Tun2SocksTunnel.kt  (decode)
                                                        ▼
              core/service RuntimeTelemetryProjection.kt  (enrich)
                                                        ▼
              core/diagnostics-data TelemetrySampleEntity  (persist)  +  UI / widget / export
```

| Telemetry artifact | Producer (Rust) | Consumer (Kotlin) |
|--------------------|-----------------|-------------------|
| Process-global metrics recorder — counters, gauges, latency histograms; `RecorderSnapshot`, `LatencyPercentiles`, `LatencyDistributions` | `native/rust/crates/ripdpi-telemetry` (`src/lib.rs`, `src/recorder/`) | latency portion projected into `NativeRuntimeSnapshot` |
| Runtime snapshot — `NativeRuntimeSnapshot` plus `NativeRuntimeEvent`, `DirectPathLearningSignal`, `TunnelStatsSnapshot` | `native/rust/crates/ripdpi-android-telemetry-adapter` (`src/types.rs`, `src/snapshot.rs`, `src/observer.rs`) | `core/data/model/.../data/NativeRuntimeSnapshot.kt` |
| Native event ring — `NativeEventRecord`, `RingConfig`, `EventDomain` | `native/rust/crates/android-support` (`src/events.rs`) | drained into `NativeRuntimeSnapshot.nativeEvents` |
| Telemetry projection / enrichment | — | `core/service/.../service/telemetry/RuntimeTelemetryProjection.kt` |
| Telemetry persistence | — | `core/diagnostics-data/.../data/diagnostics/DiagnosticsTelemetryEntities.kt` (`TelemetrySampleEntity`, `NativeSessionEventEntity`) |
| Telemetry export (archive) | — | `core/diagnostics/.../diagnostics/export/DiagnosticsArchive*.kt` |

> **Direction of authority.** Rust **produces** telemetry; Kotlin **consumes**
> it read-only. Telemetry never travels Kotlin→Rust. `ripdpi-telemetry` is
> installed process-wide once from `JNI_OnLoad` (`install_recorder()`); it is
> not per-session.

---

## The two telemetry surfaces

### 1. Runtime snapshot — `NativeRuntimeSnapshot`

The pull-model surface. Kotlin polls each runtime (~1 Hz) and receives a JSON
string that decodes into `NativeRuntimeSnapshot` — ~75 fields of session
state: counts, addresses, resolver state, autolearn state, `tunnelStats`,
`latencyDistributions`, a `directPathLearningSignals` list, and a drained
`nativeEvents` list. Built by `ProxyTelemetryState::snapshot()` in
`ripdpi-android-telemetry-adapter`; `ProxyTelemetryObserver` (an
`impl RuntimeTelemetrySink`) feeds it from runtime callbacks.

### 2. Metrics recorder — `ripdpi-telemetry`

The process-global counter / gauge / latency-histogram recorder
(`RecorderSnapshot` = `counters`, `gauges`, `histograms`, `capturedAt`). Its
latency histograms are projected into `NativeRuntimeSnapshot.latencyDistributions`
(`dnsResolution` / `tcpConnect` / `tlsHandshake`, each an optional
`LatencyPercentiles`). Recorded with data-plane `AtomicU64` discipline — see
the `rust-android-telemetry` skill.

### 3. Native events — `NativeRuntimeEvent`

A bounded per-domain event ring (`android-support/src/events.rs`,
`RingConfig` — 128 entries per runtime domain, 256 for diagnostics). Each
`NativeRuntimeEvent` carries `source`, `level`, `message`, `createdAt`, and the
optional `kind`, `runtimeId`, `mode`, `policySignature`, `fingerprintHash`,
`subsystem`. Drained into `NativeRuntimeSnapshot.nativeEvents` on each poll.

---

## Stable identifiers

Every cross-boundary telemetry string is a frozen wire contract — **add new
values, never rename or repurpose an existing one** (mirrors
[`CONFIG_CONTRACTS.md`](CONFIG_CONTRACTS.md) §5).

| Identifier class | Values / source of truth |
|------------------|--------------------------|
| **Event domain** (`source`) | `proxy`, `relay`, `warp`, `tunnel`, `diagnostics` — `EventDomain` in `android-support/src/events.rs`. `monitor` is accepted as an input alias normalized to `diagnostics`. |
| **Event `kind`** | Optional and sparse — most events carry no `kind`. Defined today: `runtime_ready`, `runtime_stopped` (`ripdpi-android-telemetry-adapter/src/lifecycle.rs`). Read by Kotlin via `nativeEvents.any { it.kind == "runtime_ready" }`. |
| **Event `level`** | `info`, `warn`, `error` — log-level strings. |
| **Direct-path learning event** | `QUIC_SUCCESS`, `QUIC_BLOCKED_TCP_OK`, `TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK`, `ALL_IPS_FAILED`, `NO_TCP_FALLBACK_DETECTED` — Rust `DirectPathLearningSignal.event: String`, decoded Kotlin-side into the `DirectPathLearningEvent` enum **by exact name**. See the forward-compatibility note below. |

---

## Payload rules

The telemetry payload is JSON: Rust `serde` serializes, Kotlin
`kotlinx.serialization` decodes. Both sides must agree on every key.

- **`camelCase` keys, both sides.** Rust types carry `#[serde(rename_all =
  "camelCase")]`; Kotlin uses the default field name. A JSON key is a wire
  contract — never rename it (it also names a column in `TelemetrySampleEntity`
  and a field in the golden fixtures).
- **Absent ⇒ default.** Rust omits empty optionals (`Option<T>` with
  `#[serde(skip_serializing_if = "Option::is_none")]`) and `false` booleans
  (`skip_serializing_if = "is_false"`). Every Kotlin field in
  `NativeRuntimeSnapshot` / `NativeRuntimeEvent` / `LatencyDistributions` /
  `TunnelStats` therefore has a default (`= null`, `= 0`, `= false`,
  `= emptyList()`) so an absent key is always safe.
- **Additive and defaulted.** A new telemetry field is safe only if it is an
  `Option<T>`/`skip_serializing_if` (or otherwise omittable) on the Rust side
  **and** a defaulted field on the Kotlin side.
- **No schema version.** Unlike the diagnostics wire contract
  (`DIAGNOSTICS_ENGINE_SCHEMA_VERSION`, [`CONFIG_CONTRACTS.md`](CONFIG_CONTRACTS.md)
  §8), the runtime-telemetry payload carries **no version field**.
  Compatibility rests entirely on the additive-and-defaulted rule plus the
  Kotlin parsers' unknown-key tolerance below.
- **Golden-locked.** `NativeTelemetryGoldenTest` (`:core:engine`) and
  `ServiceTelemetryGoldenTest` (`:core:service`) pin the payload shape. A
  field rename, an event-name change, or a removed field is a contract change
  — re-bless only under [`golden-bless-discipline.md`](../../.claude/rules/golden-bless-discipline.md).
- **Privacy floor.** Telemetry must never carry raw `BSSID` / `SSID` / `IMEI`
  / `IMSI` or device IPs — only the SHA-256 fingerprint hash. See
  [`network-fingerprint-privacy.md`](../../.claude/rules/network-fingerprint-privacy.md)
  and the `rust-android-telemetry` skill.

---

## Forward compatibility — unknown fields and events

The runtime-telemetry parsers are **already forward-tolerant**, and that is the
intended posture:

- **Unknown fields are ignored.** All four engine telemetry decoders —
  `RipDpiProxy.kt`, `Tun2SocksTunnel.kt`, `RipDpiWarp.kt`, `RipDpiRelay.kt` —
  configure `Json { ignoreUnknownKeys = true }`. A future Rust build that adds
  a snapshot or event field does **not** break an older Kotlin build.
- **Unknown event kinds are preserved.** `NativeRuntimeEvent.kind` is a plain
  `String?`; a new `kind` value decodes verbatim, with no enum to reject it.
- **Absent fields fall back.** Every telemetry model field is defaulted (see
  payload rules), so a newer Kotlin build reading older JSON is also safe.

These guarantees are locked by forward-compatibility tests in
`core/data/src/test/.../data/NativeRuntimeSnapshotTest.kt` — unknown top-level
field, unknown nested-event field, and unknown event `kind` all decode
cleanly.

**The one non-tolerant spot — `DirectPathLearningEvent`.** Rust emits
`DirectPathLearningSignal.event` as a free `String`, but Kotlin decodes it into
the `DirectPathLearningEvent` **enum by exact name**. `kotlinx.serialization`
rejects an unknown enum value, so a future Rust-side value would fail to decode
the *entire* enclosing `NativeRuntimeSnapshot`. Making the enum tolerant is
**not a low-risk change**: a sentinel variant would ripple into the three
exhaustive `when` expressions in
`core/service/.../services/DirectPathPolicyLearner.kt`
(`computeTransportClass`, `computeReasonCode`, `signalOrder`) and force a
behavioral decision in the policy learner. Therefore: **adding a
`DirectPathLearningEvent` value is a coordinated change** — land the Rust
emitter, the Kotlin enum variant, and the `DirectPathPolicyLearner` `when`
arms in one commit. It is not additive-safe like an ordinary telemetry field.

---

## Adding a telemetry field or event

1. **New snapshot/event field.** Rust: add it omittable (`Option<T>` +
   `#[serde(skip_serializing_if)]`, or a `skip`-able default). Kotlin: add a
   defaulted field to the matching `@Serializable` class. Update the goldens
   under supervision. No version bump — the field is additive.
2. **New event `kind`.** Just emit it from Rust; `kind` is an open `String?`.
   Document the new value in the §Stable identifiers table.
3. **New event domain.** Add the `EventDomain` arm in `android-support` and a
   ring capacity in `RingConfig`; the `source` string is then frozen.
4. **New `DirectPathLearningEvent`.** Coordinated change — see the note above.
5. **Never** rename a JSON key, repurpose an identifier, remove a field, or
   make a new field required.

---

## Cross-references

| Topic | Source |
|-------|--------|
| Sibling config-JSON contract; `schemaVersion` discussion | [`CONFIG_CONTRACTS.md`](CONFIG_CONTRACTS.md) §5, §8 |
| Diagnostics scan wire contract (versioned) | [`DIAGNOSTICS_ARCHITECTURE.md`](DIAGNOSTICS_ARCHITECTURE.md) |
| The JNI boundary the JSON crosses | [`JNI_CONTRACT.md`](JNI_CONTRACT.md) |
| Telemetry emission discipline (channels, rings, counters) | `rust-android-telemetry` skill |
| Telemetry privacy bounds | [`network-fingerprint-privacy.md`](../../.claude/rules/network-fingerprint-privacy.md) |
| Golden bless discipline | [`golden-bless-discipline.md`](../../.claude/rules/golden-bless-discipline.md) |
