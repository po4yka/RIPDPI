# RIPDPI Architecture — Start Here

The concise architecture map for RIPDPI. Read this first, then follow the
[deeper docs](#8-deeper-docs) for any subsystem.

This file is a navigational map, not a spec. Where it cannot be confirmed from
current source it says so explicitly with **TODO verify** and a file path.

---

## 1. What RIPDPI does

RIPDPI is an Android network-path diagnostics and optimization toolkit. It runs
entirely on-device with **no backend server** ([AGENTS.md](../../AGENTS.md) §
Project Rules). Three capabilities work independently or combined:

1. **On-device packet strategies** — applies configurable packet-level
   transformations (TCP split/disorder, fake injection, OOB, TLS record
   fragmentation, QUIC/DTLS variation, etc.) without routing to a relay.
2. **VPN relay** — chains local proxy or VPN traffic through encrypted relay
   protocols (VLESS Reality/xHTTP, WARP, MASQUE, Hysteria2, TUIC v5, ShadowTLS,
   NaiveProxy, AmneziaWG, Cloudflare Tunnel) to a server the user controls.
3. **Diagnostics** — scans each connection target, produces a typed verdict,
   and stores it per network fingerprint for automatic replay.

See [README.md](../../README.md) for the user-facing feature list.

---

## 2. Runtime modes

| Mode | Entry service | What runs | Native path |
|------|---------------|-----------|-------------|
| **Proxy** | `RipDpiProxyService.kt` | Local SOCKS5 proxy on a localhost port | `RipDpiProxy.kt` → `libripdpi.so` |
| **VPN / TUN** | `RipDpiVpnService.kt` (extends `LifecycleVpnService.kt`) | `VpnService` TUN device → TUN-to-SOCKS bridge → local proxy | `Tun2SocksTunnel.kt` → `libripdpi-tunnel.so`, then `libripdpi.so` |
| **Diagnostics** | Driven from `:core:diagnostics` UI | Active scans + DNS/TLS/strategy probes; stops the VPN service for RAW_PATH scans | `NetworkDiagnostics.kt` → `ripdpi-monitor` (linked into `libripdpi.so`) |
| **Relay** | Composed into proxy/VPN mode | Encrypted relay transport; JNI-embedded relays via `RipDpiRelay.kt` / `RipDpiWarp.kt`; subprocess helpers (NaiveProxy, `cloudflared`) via `Subprocess*` services | Relay/WARP crates linked into `libripdpi.so`; helper binaries via subprocess |
| **Root helper** (optional, opt-in) | `RootHelperManager.kt` | Privileged raw-socket ops (FakeRst, MultiDisorder, IP fragmentation, raw IPv4/IPv6 emit) behind `root_mode_enabled` | `ripdpi-root-helper` ELF binary, Unix-socket IPC with SCM_RIGHTS fd passing |

Both proxy and VPN modes work **with or without** a relay configured. The app
must fully function on **non-rooted devices**; root features degrade gracefully.

---

## 3. Android module ownership map

Modules from [`settings.gradle.kts`](../../settings.gradle.kts). Dependency
direction flows downward (`:app` depends on everything below it).

| Module | Owns |
|--------|------|
| `:app` | Jetpack Compose UI (Material 3), navigation, ViewModels |
| `:core:service` | Android VPN + proxy foreground services; relay orchestration, DNS failover, connection-policy resolution, root-helper lifecycle, subprocess relay supervision |
| `:core:engine` | Rust native libraries + JNI bridge; Kotlin↔native config codecs |
| `:core:diagnostics` | Active diagnostics, passive telemetry collection, diagnostics UI logic |
| `:core:diagnostics-data` | Protobuf schemas + data contracts for diagnostics |
| `:core:detection` | VPN/DPI detection and checkers (consensus, privacy, `vpn`, `dpi`, `export`, `community`, `probe` subpackages); depends on `:xray-protos` |
| `:core:data` | Aggregator — `api`-exports the four sub-modules below; Room DB (KSP), backup, rules |
| `:core:data:model` | App-settings + geosite protobuf schemas (see [§6](#6-config-flow)) |
| `:core:data:settings` | Settings persistence (DataStore-backed) |
| `:core:data:runtime-state` | Runtime/session state |
| `:core:data:catalog` | Diagnostics / strategy-pack catalog data |
| `:xray-protos` | Java-library: Xray/V2Ray protobuf schemas (VLESS, Reality, transport) + gRPC; consumed by `:core:detection` |
| `:quality:detekt-rules` | Custom detekt rules (DI guardrails, Hilt ViewModel checks) |
| `:baselineprofile` | Baseline profile generation for runtime performance |

> **Note:** [AGENTS.md](../../AGENTS.md) § Architecture shows the older
> single-module `:core:data` and does not list `:core:detection` or
> `:xray-protos`. This table reflects the current `settings.gradle.kts`.
> **TODO verify** the exact responsibility split between `:core:data:settings`
> and `:core:data:model` against `core/data/settings/` and `core/data/model/`.

---

## 4. Native Rust artifact map

The Rust workspace is at [`native/rust/`](../../native/rust/Cargo.toml) — a
cdylib-bearing Cargo workspace of ~100 crates. Three artifacts are produced for
Android by [`:core:engine`](../../core/engine/build.gradle.kts) via the
`ripdpi.android.rust-native` convention plugin.

| Artifact | Kind | Source crate | Kotlin bridge | Used in |
|----------|------|--------------|---------------|---------|
| `libripdpi.so` | Shared library (JNI) | `crates/ripdpi-android` | `RipDpiProxy.kt`, `NetworkDiagnostics.kt`, `RipDpiRelay.kt`, `RipDpiWarp.kt` | Proxy, VPN, diagnostics, relay, WARP |
| `libripdpi-tunnel.so` | Shared library (JNI) | `crates/ripdpi-tunnel-android` | `Tun2SocksTunnel.kt` | VPN mode only (TUN-to-SOCKS bridge) |
| `ripdpi-root-helper` | Standalone ELF binary | `crates/ripdpi-root-helper` | `RootHelperManager.kt` (lifecycle), `RootDetector.kt` (root check) | Rooted devices only, opt-in |

- `libripdpi.so` is loaded by `RipDpiNativeLoader.kt` via
  `System.loadLibrary("ripdpi")`.
- **Relay / WARP have no separate `.so`** — `ripdpi-relay-core`,
  `ripdpi-relay-mux`, `ripdpi-xhttp`, `ripdpi-tuic`, `ripdpi-shadowtls`,
  `ripdpi-vless`, `ripdpi-warp-core`, `ripdpi-cloudflare-origin`, etc. are
  linked into `libripdpi.so` and reached through `RipDpiRelay.kt` /
  `RipDpiWarp.kt`.
- **Subprocess helpers** — `ripdpi-naiveproxy` and a bundled `cloudflared`
  run as separate processes (not JNI-embedded), supervised by the
  `Subprocess*` / `Cloudflare*` services in `:core:service`.
  **TODO verify** how each subprocess helper binary is packaged and extracted —
  see `core/service/.../services/SubprocessRelayBinaryExtractor.kt`.
- `ripdpi` (desktop CLI, `crates/ripdpi-cli`) is a development binary for
  macOS/Linux and is **not** packaged in the APK.
- Crate-by-crate detail and the dependency graph live in
  [`docs/native/README.md`](../native/README.md).

Supported ABIs: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`. Never edit `.so`
files — they are compiled from source.

---

## 5. Kotlin ↔ Rust control flow

```
:app (Compose UI, ViewModels)
   │  start / stop / configure
   ▼
:core:service  ── ServiceRuntimeCoordinator owns lifecycle sequencing,
   │               restart/backoff, mode policies
   │            ── ConnectionPolicyResolver resolves per-network policy
   │               (RipDpiProxyService / RipDpiVpnService are the entry services)
   ▼
:core:engine   ── JNI bridges: RipDpiProxy.kt, Tun2SocksTunnel.kt,
   │               NetworkDiagnostics.kt, RipDpiRelay.kt, RipDpiWarp.kt
   ▼
native Rust    ── libripdpi.so / libripdpi-tunnel.so
                   ripdpi-runtime drives the proxy; ripdpi-tunnel-core drives
                   the TUN bridge; ripdpi-monitor drives diagnostics scans
```

- **VPN socket protection invariant** — every non-loopback outbound socket the
  Rust core opens must be passed to `VpnService.protect(fd)` before
  `connect`/`bind`, or it loops back into the TUN device. Dual mechanism: JNI
  callback (`vpn_protect.rs` / `VpnNativeProtectRegistration.kt`) preferred,
  Unix-socket fallback (`VpnProtectSocketServer.kt`). See
  [`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md).
- **Telemetry is pull-model** — `:core:service` polls native snapshots once per
  second and stores only metadata (counters, lifecycle changes, route
  decisions). No packet payloads are persisted.
- **Diagnosis classification is Rust-authoritative** — the native monitor
  collects packet/TLS/DNS/timing evidence and emits the final verdict; Kotlin
  maps it to UI and persistence without re-classifying
  ([architecture/README.md](README.md) § Ownership Boundaries).

---

## 6. Config flow

User settings become a Rust `RuntimeConfig` through a one-way translation
pipeline. Kotlin is **authoritative** for strategy models, defaults,
validation, and JSON serialization; Rust **consumes** the JSON
([architecture/README.md](README.md) § Ownership Boundaries).

```
app_settings.proto  (core/data/model/src/main/proto/app_settings.proto)
   │  Protobuf + Jetpack DataStore
   ▼
Kotlin settings & strategy models  (:core:data:settings, StrategyChains.kt)
   │  user-facing models, defaults, validation
   ▼
JSON codecs in :core:engine
   │  RipDpiProxyJsonCodec.kt + core/codec/*Codec.kt
   │  (ChainsCodec, FakePacketCodec, RelaySectionCodec, NetworkSectionCodec,
   │   AdaptiveSectionCodec, WarpTunnelSectionCodec, RuntimeContextCodec)
   ▼
native config JSON  ── passed over JNI ──▶  Rust
   │
   ▼
ripdpi-proxy-config / ripdpi-config  ──▶  RuntimeConfig  ──▶  ripdpi-runtime
```

- `ripdpi-proxy-config` is the shared translation crate that aligns
  UI-configured JSON, diagnostics recommendation drafts, probing candidate
  overlays, and CLI config around one `RuntimeConfig` shape
  ([docs/native/README.md](../native/README.md) § Shared Strategy Bridge).
- Validated per-network winners are persisted as exact `proxyConfigJson` in
  `remembered_network_policies` and replayed on reconnect.
- **TODO verify** the proto path: [AGENTS.md](../../AGENTS.md) cites
  `core/data/src/main/proto/app_settings.proto`, but the file currently lives at
  `core/data/model/src/main/proto/app_settings.proto`.

---

## 7. Control plane vs data plane

| | Control plane | Data plane |
|---|---|---|
| **Responsibility** | Config translation, JNI lifecycle calls (start/stop/restart), 1 Hz telemetry polling, connection-policy resolution, diagnostics orchestration | Packet processing — SOCKS5 sessions, TUN packet pump, desync mutation, relay transport, DNS forwarding |
| **Where it runs** | Kotlin (`:core:service`, `:core:engine`) + native control entry points | Entirely native Rust inside `libripdpi.so` / `libripdpi-tunnel.so` |
| **Crosses JNI?** | Yes — but only at lifecycle and polling boundaries | **No** — no JNI call on the per-packet hot path |
| **Logging channel** | `android_logger` (logcat) | `tracing` (kept off per-packet paths for cost reasons) |

The boundary is deliberate: JNI overhead (~3 µs/event) makes per-packet calls
across the boundary a measurable bottleneck at 1 Gbps, so the data plane stays
fully native and the control plane communicates over coarse-grained config JSON
and pulled telemetry snapshots. The one exception is `VpnService.protect(fd)`,
a per-socket (not per-packet) control-plane call required by the
[protect invariant](#5-kotlin--rust-control-flow).

---

## 8. Deeper docs

| Topic | Document |
|-------|----------|
| Compact architecture notes (ownership, runtime behavior, follow-ups) | [`architecture/README.md`](README.md) |
| Native modules, crate dependency graph, runtime topology | [`docs/native/README.md`](../native/README.md) |
| Proxy engine and strategy surface | [`docs/native/proxy-engine.md`](../native/proxy-engine.md) |
| TUN-to-SOCKS bridge | [`docs/native/tunnel.md`](../native/tunnel.md) |
| Packet strategy runtime (TUN-egress + root-helper raw packets) | [`docs/packet-strategy-runtime.md`](../packet-strategy-runtime.md) |
| Diagnostics scan pipeline | [`docs/native/README.md`](../native/README.md) § Diagnostics and Telemetry |
| Relay profile examples | [`docs/relay-profile-examples.md`](../relay-profile-examples.md) |
| Architecture quality gates | [`architecture/quality-gates.md`](quality-gates.md) |
| Testing, E2E, golden contracts | [`docs/testing.md`](../testing.md) |
| Full project reference (build, CI, rules, skills) | [`AGENTS.md`](../../AGENTS.md) |
| Forward roadmap | [`ROADMAP.md`](../../ROADMAP.md) |
| Cross-tool engineering rules | [`.claude/rules/`](../../.claude/rules/) |
