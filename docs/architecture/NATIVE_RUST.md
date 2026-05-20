# Native Rust Workspace — Ownership & Dependency Direction

How the `native/rust/` Cargo workspace is layered, what it builds, and which
way dependencies are allowed to point. This is the ownership map for the Rust
side of RIPDPI; for the whole-app picture see
[`ARCHITECTURE.md`](ARCHITECTURE.md).

Derived directly from [`native/rust/Cargo.toml`](../../native/rust/Cargo.toml)
and the `native/rust/crates/` tree. Where a fact could not be confirmed from
source it is marked **TODO verify** with a path.

---

## Workspace facts

- **99 crates**, all under `native/rust/crates/`. Every directory is a declared
  `[workspace] members` entry — **no orphan directories, no missing members.**
- `edition = "2021"`, `version = "0.1.0"`, `license = "MIT"` (workspace-inherited).
- Build profiles: `release` (thin LTO, `panic = "abort"`, stripped),
  `android-jni` (inherits `release`, **fat LTO**, `opt-level = "z"`,
  `panic = "unwind"`), `android-jni-dev`, `bench`.
- `[patch.crates-io] boring-sys` points at `native/rust/vendor/boring-sys` — a
  vendored, pinned patch crate. It is **not** under `crates/` and **not** a
  workspace member; the BoringSSL ABI pin is load-bearing (see the comment on
  `boring`/`tokio-boring` in `Cargo.toml`).
- `[workspace.metadata.ripdpi]` records two non-production sets:
  `test-support-crates` and `local-debug-crates` (see [§8](#8-test--support--local-debug-crates)).

---

## 1. Production artifacts

Eight crates are artifact roots. Everything else is a library crate compiled
**into** one of them. Artifacts are produced by the `ripdpi.android.rust-native`
convention plugin
([`build-logic/.../ripdpi.android.rust-native.gradle.kts`](../../build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts)).

| Artifact | Crate (root) | Kind | Built by | Packaged as |
|----------|--------------|------|----------|-------------|
| `libripdpi.so` | `ripdpi-android` | `cdylib` | `:core:engine:buildRustNativeLibs` | `jniLibs/<abi>/` |
| `libripdpi-relay.so` | `ripdpi-relay-android` | `cdylib` | `:core:engine:buildRustNativeLibs` | `jniLibs/<abi>/` |
| `libripdpi-warp.so` | `ripdpi-warp-android` | `cdylib` | `:core:engine:buildRustNativeLibs` | `jniLibs/<abi>/` |
| `libripdpi-tunnel.so` | `ripdpi-tunnel-android` | `cdylib` | `:core:engine:buildRustNativeLibs` | `jniLibs/<abi>/` |
| `ripdpi-root-helper` | `ripdpi-root-helper` | `bin` | `:core:engine:buildRustRootHelper` | APK assets (`rootHelperAssets/bin/<abi>/`) — run via `su` |
| `ripdpi-naiveproxy` | `ripdpi-naiveproxy` | `bin` (`src/main.rs`) | NaiveProxy artifact task | APK assets — run as a subprocess helper |
| `ripdpi-cloudflare-origin` | `ripdpi-cloudflare-origin` | `bin` | Cloudflare-origin artifact task | APK assets — run as a subprocess helper |
| `ripdpi` | `ripdpi-cli` | `bin` | `cargo build` (desktop) | **Not** in the APK — macOS/Linux dev binary |

> **Correction to older docs.** [AGENTS.md](../../AGENTS.md) § Native Code and
> [`docs/native/README.md`](../native/README.md) describe **two** `.so` files
> (`libripdpi.so`, `libripdpi-tunnel.so`). The Gradle plugin's artifact specs
> actually build **four** JNI `.so` libraries — the relay and WARP runtimes
> ship as their own `libripdpi-relay.so` / `libripdpi-warp.so`, not linked into
> `libripdpi.so`. This document reflects the plugin spec list.
>
> The native README also refers to crates named `ripdpi-runtime` and
> `ripdpi-monitor`. Those names no longer exist — the workspace was decomposed
> into the `ripdpi-proxy-runtime` + `ripdpi-runtime-*` family and the
> `ripdpi-monitor-*` family. Treat the native README's crate names as stale.

First-level composition of each artifact root (direct internal dependencies):

- **`ripdpi-android`** → the seven `ripdpi-android-*` adapters + `android-support`
  + `ripdpi-strategy-config` + `ripdpi-strategy-lua`.
- **`ripdpi-tunnel-android`** → `ripdpi-tunnel-core`, `ripdpi-tunnel-config`,
  `ripdpi-runtime-platform`, `ripdpi-telemetry`, `android-support`.
- **`ripdpi-relay-android`** → `ripdpi-relay-core`, `ripdpi-apps-script-core`,
  `android-support`.
- **`ripdpi-warp-android`** → `ripdpi-warp-core`, `ripdpi-native-protect`,
  `ripdpi-tls-profiles`, `android-support`.
- **`ripdpi-root-helper`** → `ripdpi-privileged-ops`, `ripdpi-ipfrag`,
  `ripdpi-root-helper-protocol`.

---

## 2. Crate taxonomy

Nine layers. A crate appears in exactly one layer. Counts sum to 99.

| # | Layer | Count | Crates |
|---|-------|-------|--------|
| L0 | **support / test / dev** | 5 | `golden-test-support`, `local-network-fixture`, `native-soak-support`, `ripdpi-bench`, `ripdpi-cli` |
| L1 | **protocol / core** | 9 | `ripdpi-packets`, `ripdpi-tls-profiles`, `ripdpi-socks5-core`, `ripdpi-ipfrag`, `ripdpi-collections`, `ripdpi-geo`, `ripdpi-protocol-detect`, `ripdpi-protocol-loopback`, `ripdpi-dns-resolver` |
| L2 | **contracts / config** | 9 | `ripdpi-config`, `ripdpi-proxy-config`, `ripdpi-tunnel-config`, `ripdpi-strategy-config`, `ripdpi-strategy-trait`, `ripdpi-runtime-api`, `ripdpi-runtime-decision-ports`, `ripdpi-diagnostics-contracts`, `ripdpi-telemetry` |
| L3 | **domain logic** | 15 | `ripdpi-desync`, `ripdpi-desync-runtime`, `ripdpi-failure-classifier`, `ripdpi-session`, `ripdpi-routing`, `ripdpi-shared-priors`, `ripdpi-runtime-policy`, `ripdpi-runtime-adaptive`, `ripdpi-runtime-strategy`, `ripdpi-strategy-http`, `ripdpi-strategy-ipv6`, `ripdpi-strategy-lua`, `ripdpi-strategy-udp`, `ripdpi-strategy-window`, `ripdpi-strategy-registry` |
| L4 | **runtime / application** | 8 | `ripdpi-proxy-runtime`, `ripdpi-proxy-runtime-adapter`, `ripdpi-proxy-runtime-desync-adapter`, `ripdpi-runtime-services`, `ripdpi-runtime-dns-cache`, `ripdpi-tunnel-core`, `ripdpi-tunnel-intercept`, `ripdpi-ws-bootstrap` |
| L5 | **platform / privileged** | 8 | `ripdpi-runtime-platform`, `ripdpi-native-protect`, `ripdpi-tun-driver`, `ripdpi-io-uring`, `ripdpi-capabilities`, `ripdpi-privileged-ops`, `ripdpi-root-helper-protocol`, `ripdpi-root-helper` |
| L6 | **diagnostics / monitor** | 18 | 14 × `ripdpi-diagnostics-*` (all except `-contracts`) + `ripdpi-monitor-engine`, `ripdpi-monitor-adapter`, `ripdpi-monitor-lane-adapter`, `ripdpi-monitor-proxy-runtime` |
| L7 | **relay transports** | 15 | `ripdpi-relay-core`, `ripdpi-relay-mux`, `ripdpi-hysteria2`, `ripdpi-masque`, `ripdpi-tuic`, `ripdpi-shadowtls`, `ripdpi-shadowsocks`, `ripdpi-trojan`, `ripdpi-vless`, `ripdpi-xhttp`, `ripdpi-cloudflare-origin`, `ripdpi-naiveproxy`, `ripdpi-warp-core`, `ripdpi-apps-script-core`, `ripdpi-ws-tunnel` |
| L8 | **Android / JNI adapters** | 12 | `android-support`, the seven `ripdpi-android-*` adapters, `ripdpi-android`, `ripdpi-tunnel-android`, `ripdpi-relay-android`, `ripdpi-warp-android` |

`ripdpi-diagnostics-contracts` is counted under L2 (it is a wire contract); the
other 14 `ripdpi-diagnostics-*` crates are L6.

---

## 3. Dependency-direction policy

```
        L8  Android / JNI adapters        (cdylib + JNI; the only `.so` roots)
              │  may depend on ▼
   L4 ── L6 ── L7   runtime · diagnostics/monitor · relay transports
              │  may depend on ▼
        L3  domain logic
              │  may depend on ▼
   L1 ── L2 ── L5   protocol/core · contracts/config · platform/privileged
              │  may depend on ▼
        L0  support  (dev-dependencies only — see §8)
```

Rules, all of which **hold today** in `Cargo.toml`:

1. **Dependencies point inward / downward only.** No L1/L2/L5 crate may depend
   on an L3+ crate; no L3 crate may depend on L4/L6/L7/L8. The artifact roots
   (L8 cdylibs, the bin crates) are sinks — nothing depends on them.
2. **JNI containment.** Only the 12 L8 crates may depend on the `jni` crate or
   on `android-support`. Today exactly 11 crates pull `jni`
   (`ripdpi-android-telemetry-adapter` is the one L8 crate without it), and
   `android-support` is consumed by L8 crates *only*. Every other crate must
   stay JNI-free — see [§5](#5-crates-that-must-stay-androidjni-free).
3. **`cdylib` is L8-exclusive.** Exactly four crates set
   `crate-type = ["cdylib"]`; all four are L8 artifact roots. No other crate
   may become a `cdylib`.
4. **Platform ports, not platform impls.** `ripdpi-runtime-platform` and
   `ripdpi-native-protect` define platform capability/protection ports. Core
   and runtime crates depend on the **port** crate; the concrete
   implementations are supplied by the L8 adapters and the privileged binary.
5. **Contracts are the stable ABI.** L2 crates (plus `ripdpi-strategy-trait`,
   `ripdpi-runtime-api`, `ripdpi-root-helper-protocol`) carry wide fan-in by
   design. Treat any change to their public types as a breaking wire-contract
   change; golden contract tests cover them.
6. **Support crates are dev-only.** The four `test-support-crates` must appear
   only in `[dev-dependencies]`; `ripdpi-cli` (`local-debug-crates`) is desktop
   tooling and must never be an Android dependency. Verified: every occurrence
   of those five crates today is a dev-dependency or none.

Highest fan-in hubs (a change here ripples widest): `ripdpi-failure-classifier`
(17 consumers), `ripdpi-config` (16), `ripdpi-diagnostics-contracts` (15),
`ripdpi-packets` (14), `ripdpi-proxy-config` (14).

---

## 4. Crate classification table

One sub-table per layer (the layer is the heading). Columns: **Responsibility**,
**API surface** (the *kind* of surface and its stability expectation — not an
enumeration of exported symbols; read each crate's `src/lib.rs` for the exact
`pub` list), **Key internal deps**, **Coupling / risk**, **Action**.

### L0 — support / test / dev

| Crate | Responsibility | API surface | Key internal deps | Coupling / risk | Action |
|-------|----------------|-------------|-------------------|-----------------|--------|
| `golden-test-support` | Golden-fixture comparison + bless helpers | Test helpers | — | None — dev-only | Keep |
| `local-network-fixture` | In-process network fixture (also builds a fixture binary) | Test fixtures + `bin` | — | `kind=bin`; dev-only | Keep |
| `native-soak-support` | Soak-test scaffolding | Test helpers | — | Dev-only | Keep |
| `ripdpi-bench` | Criterion benchmark harness | Bench harness | — (dev-deps on runtime crates) | No workspace consumer (expected) | Keep |
| `ripdpi-cli` | Desktop CLI (`ripdpi`) for proxy runtime dev | `bin` | `ripdpi-config`, `ripdpi-proxy-runtime`, `ripdpi-runtime-api`, `ripdpi-failure-classifier`, `ripdpi-telemetry` | Desktop-only; not in APK | Keep — never make it an Android dep |

### L1 — protocol / core

| Crate | Responsibility | API surface | Key internal deps | Coupling / risk | Action |
|-------|----------------|-------------|-------------------|-----------------|--------|
| `ripdpi-packets` | Protocol classification + field extraction (TLS/HTTP/QUIC), mutation markers | Traits + registry + types | — (leaf) | Fan-in 14 | Keep — treat as core API |
| `ripdpi-tls-profiles` | TLS fingerprint / ClientHello profile catalog | Profile constants + types | `ripdpi-packets` | Fan-in 10; pulls `boring` | Keep |
| `ripdpi-socks5-core` | SOCKS5 / SOCKS4 protocol primitives | Codec types | — (leaf) | Shared by relay + tunnel + dns | Keep |
| `ripdpi-ipfrag` | IP-level fragmentation (TCP + UDP/QUIC, v4/v6) | Functions + types | — (leaf) | Fan-in 6 | Keep |
| `ripdpi-collections` | Generic data structures | Container types | — (leaf) | Low | Keep |
| `ripdpi-geo` | Geo / IP database lookup (maxminddb) | Lookup API | — (leaf) | Used by `ripdpi-proxy-runtime` | Keep |
| `ripdpi-protocol-detect` | Stream protocol detection | Detector types | `ripdpi-strategy-trait` | **No workspace consumer found** | Verify wiring (feature-gated / pending?) |
| `ripdpi-protocol-loopback` | Loopback protocol test harness | Harness API | — (leaf) | **No workspace consumer found**; pulls `tokio` | Verify role — may be harness-only |
| `ripdpi-dns-resolver` | Encrypted DNS client (DoH/DoT/DNSCrypt/DoQ) | Resolver API (async) | `ripdpi-socks5-core` | Fan-in 7; heavy ext deps (`quinn`, `boring`, `reqwest`) | Keep |

### L2 — contracts / config

| Crate | Responsibility | API surface | Key internal deps | Coupling / risk | Action |
|-------|----------------|-------------|-------------------|-----------------|--------|
| `ripdpi-config` | **Core** runtime/CLI config model + parsing | Config structs (serde) | `ripdpi-packets` | Fan-in 16; name implies "CLI-only" but it is the shared core config | Doc: clarify scope; do not rename (constraint) |
| `ripdpi-proxy-config` | Proxy `RuntimeConfig` shape + strategy-config translation bridge | Config structs (serde) | `ripdpi-config`, `ripdpi-packets` | Fan-in 14 | Keep — treat as wire contract |
| `ripdpi-tunnel-config` | TUN-to-SOCKS tunnel config model | Config structs | — (leaf) | Low | Keep |
| `ripdpi-strategy-config` | Strategy-chain config model | Config structs | — (leaf) | Consumed by `ripdpi-android` directly | Keep |
| `ripdpi-strategy-trait` | The strategy contract trait | Trait | — (leaf) | Fan-in 9 (all `ripdpi-strategy-*`) | Keep — hand-author, do not auto-generate |
| `ripdpi-runtime-api` | Runtime API / port types | Traits + types | `ripdpi-failure-classifier`, `ripdpi-proxy-config` | Fan-in 8 | Keep |
| `ripdpi-runtime-decision-ports` | Hexagonal decision-port traits | Traits | `ripdpi-config`, `ripdpi-desync`, `ripdpi-failure-classifier`, `ripdpi-proxy-config` | Port layer for L3/L4 | Keep |
| `ripdpi-diagnostics-contracts` | Diagnostics wire contracts (`ScanRequest`/`ScanReport`) | Serde types | `ripdpi-proxy-config`, `ripdpi-telemetry` | Fan-in 15; Kotlin/Rust wire contract | Keep — golden-locked |
| `ripdpi-telemetry` | Telemetry data structures + contracts | Serde types | — (leaf) | Wide fan-in via contracts | Keep — golden-locked |

### L3 — domain logic

| Crate | Responsibility | API surface | Key internal deps | Coupling / risk | Action |
|-------|----------------|-------------|-------------------|-----------------|--------|
| `ripdpi-desync` | DPI desync packet-strategy planning | Planner types | `ripdpi-config`, `ripdpi-ipfrag`, `ripdpi-packets`, `ripdpi-strategy-trait`, `ripdpi-tls-profiles` | Fan-in 10 | Keep |
| `ripdpi-desync-runtime` | Desync execution runtime | Runtime types | `ripdpi-desync`, `ripdpi-session`, `ripdpi-proxy-config`, … | Mid fan-out | Keep |
| `ripdpi-failure-classifier` | Connection-failure + block-signal classification | `classify_*` fns + types | `ripdpi-packets` | **Fan-in 17 (highest)** — shared by L6 + L4 | Keep — treat API as a contract |
| `ripdpi-session` | Session state machine + policy store | State-machine API | `ripdpi-packets` | Fan-in 5 | Keep |
| `ripdpi-routing` | Routing rule engine | Rule API | — (leaf) | **No workspace consumer found** | Verify wiring |
| `ripdpi-shared-priors` | Offline-learner signed shared-priors bundles | Parser + verifier API | — (leaf) | Fail-secure parser (see architecture/README) | Keep |
| `ripdpi-runtime-policy` | Runtime policy logic | Policy types | `ripdpi-desync`, `ripdpi-session`, `ripdpi-runtime-decision-ports`, … | Mid | Keep |
| `ripdpi-runtime-adaptive` | Adaptive runtime (UCB1 / bandit scoring) | Scorer API | `ripdpi-runtime-policy`, `ripdpi-runtime-decision-ports`, … | Mid | Keep |
| `ripdpi-runtime-strategy` | Runtime strategy selection | Selector API | `ripdpi-desync`, `ripdpi-shared-priors`, … | `ripdpi-shared-priors` is both dep and dev-dep | Keep |
| `ripdpi-strategy-http` | HTTP-mutation strategy impl | `impl` of strategy trait | `ripdpi-strategy-trait` | Registered via registry | Keep |
| `ripdpi-strategy-ipv6` | IPv6 extension-header strategy impl | `impl` of strategy trait | `ripdpi-strategy-trait` | — | Keep |
| `ripdpi-strategy-lua` | Lua `rawsend` strategy impl | `impl` of strategy trait | `ripdpi-strategy-trait` | Pulls `mlua`; consumed by `ripdpi-android` directly | Keep |
| `ripdpi-strategy-udp` | UDP length-field strategy impl | `impl` of strategy trait | `ripdpi-strategy-trait` | — | Keep |
| `ripdpi-strategy-window` | TCP window-clamp strategy impl | `impl` of strategy trait | `ripdpi-strategy-trait` | — | Keep |
| `ripdpi-strategy-registry` | Aggregates all strategy impls into a registry | Registry API | all `ripdpi-strategy-*` + `ripdpi-desync` | Aggregator — keep thin | Keep |

### L4 — runtime / application

| Crate | Responsibility | API surface | Key internal deps | Coupling / risk | Action |
|-------|----------------|-------------|-------------------|-----------------|--------|
| `ripdpi-proxy-runtime` | Local SOCKS5 proxy runtime | Runtime entrypoint | `ripdpi-proxy-runtime-adapter`, `ripdpi-runtime-api`, `ripdpi-geo`, `ripdpi-io-uring` | Core of `libripdpi.so` | Keep |
| `ripdpi-proxy-runtime-adapter` | Composition/wiring for the proxy runtime | Builder/wiring API | 10 internal deps | High fan-out — wiring crate | Keep thin; resist absorbing logic |
| `ripdpi-proxy-runtime-desync-adapter` | Wires desync pipeline into the proxy runtime | Adapter API | **12 internal deps** | Highest fan-out in workspace — god-adapter risk | Watch — keep wiring-only |
| `ripdpi-runtime-services` | Runtime service composition | Service API | `ripdpi-runtime-{adaptive,api,policy,strategy}`, … | Mid | Keep |
| `ripdpi-runtime-dns-cache` | Runtime DNS cache | Cache API | — (leaf) | **No workspace consumer found** | Verify wiring |
| `ripdpi-tunnel-core` | TUN-to-SOCKS bridge runtime | Runtime entrypoint | `ripdpi-tun-driver`, `ripdpi-tunnel-intercept`, `ripdpi-dns-resolver`, … | Core of `libripdpi-tunnel.so`; `smoltcp` | Keep |
| `ripdpi-tunnel-intercept` | TUN-egress packet interception + mutation | Intercept API | `ripdpi-strategy-registry`, `ripdpi-runtime-platform`, … | Mid | Keep |
| `ripdpi-ws-bootstrap` | WebSocket-tunnel bootstrap orchestration | Bootstrap API | `ripdpi-ws-tunnel`, `ripdpi-dns-resolver`, `ripdpi-runtime-platform`, … | Mid | Keep |

### L5 — platform / privileged

| Crate | Responsibility | API surface | Key internal deps | Coupling / risk | Action |
|-------|----------------|-------------|-------------------|-----------------|--------|
| `ripdpi-runtime-platform` | Platform capability + port decomposition | Port traits + types | `ripdpi-capabilities`, `ripdpi-native-protect`, `ripdpi-privileged-ops`, `ripdpi-root-helper-protocol`, … | Fan-in 8; platform hub | Keep — keep JNI-free |
| `ripdpi-native-protect` | `VpnService.protect` socket-protection mechanism (port) | Protect-callback API | — (leaf) | Fan-in 6 | Keep — keep JNI-free |
| `ripdpi-tun-driver` | Raw TUN device socket handling | Driver API | — (leaf) | `tun-rs` | Keep |
| `ripdpi-io-uring` | io_uring async I/O (Linux) | I/O API | — (leaf) | Linux-only path | Keep |
| `ripdpi-capabilities` | Device capability model + detection | Capability types | — (leaf) | Feeds privileged-ops + platform | Keep |
| `ripdpi-privileged-ops` | Privileged raw-socket operations | Op functions | `ripdpi-capabilities`, `ripdpi-config`, `ripdpi-desync`, `ripdpi-ipfrag` | Privileged path | Keep — see [§7](#7-root-helper--privileged-crates) |
| `ripdpi-root-helper-protocol` | Root-helper Unix-socket IPC protocol | Wire types | — (leaf) | IPC contract crate | Keep — golden-locked |
| `ripdpi-root-helper` | Standalone privileged helper binary | `bin` | `ripdpi-privileged-ops`, `ripdpi-ipfrag`, `ripdpi-root-helper-protocol` | Runs as uid 0 via `su` | Keep — security-sensitive |

### L6 — diagnostics / monitor

| Crate | Responsibility | API surface | Key internal deps | Coupling / risk | Action |
|-------|----------------|-------------|-------------------|-----------------|--------|
| `ripdpi-diagnostics-candidates` | Strategy-probe candidate planning | Planner API | `ripdpi-diagnostics-contracts`, `ripdpi-runtime-platform`, … | Mid | Keep |
| `ripdpi-diagnostics-classification` | Probe-result verdict classification | Classifier API | `ripdpi-diagnostics-candidates`, `ripdpi-failure-classifier`, … | Mid | Keep |
| `ripdpi-diagnostics-dns` | DNS integrity / tampering probes | Probe API | `ripdpi-diagnostics-transport`, `ripdpi-dns-resolver`, … | Fan-in 6 | Keep |
| `ripdpi-diagnostics-fat-header` | TCP fat-header probes | Probe API | `ripdpi-diagnostics-{http,tls,transport}` | — | Keep |
| `ripdpi-diagnostics-http` | HTTP reachability probes | Probe API | `ripdpi-diagnostics-{tls,transport}`, `ripdpi-failure-classifier` | Fan-in 6 | Keep |
| `ripdpi-diagnostics-net` | Net-probe aggregation | Probe API | `ripdpi-diagnostics-{contracts,dns,fat-header,http,telegram,tls,transport}` | **No workspace consumer**; dep set mirrors `ripdpi-diagnostics-protocols` | Verify — possibly superseded by `-protocols` |
| `ripdpi-diagnostics-parsers` | Response parsers (HTTP/TLS/SSH) | Parser API | `ripdpi-failure-classifier` | **No workspace consumer found** | Verify wiring |
| `ripdpi-diagnostics-pcap` | PCAP diagnostic recording | Recorder API | — (leaf) | Used by `ripdpi-android-proxy-adapter` | Keep |
| `ripdpi-diagnostics-probes` | Probe-task execution | Probe API | `ripdpi-diagnostics-{classification,contracts,http}`, … | — | Keep |
| `ripdpi-diagnostics-protocols` | Protocol-probe aggregation | Probe API | `ripdpi-diagnostics-{contracts,dns,fat-header,http,telegram,tls,transport}` | Consumed by `-runner` | Keep |
| `ripdpi-diagnostics-runner` | Scan runner / orchestration | Runner API | `ripdpi-diagnostics-{candidates,classification,protocols}`, … | Mid | Keep |
| `ripdpi-diagnostics-telegram` | Telegram-availability probes | Probe API | `ripdpi-diagnostics-{contracts,http,tls,transport}` | — | Keep |
| `ripdpi-diagnostics-tls` | TLS reachability probes | Probe API | `ripdpi-diagnostics-{contracts,dns,transport}`, `ripdpi-tls-profiles` | Fan-in 6 | Keep |
| `ripdpi-diagnostics-transport` | Transport-layer probe primitives | Probe primitives | `ripdpi-diagnostics-contracts`, `ripdpi-socks5-core` | Fan-in 9 | Keep |
| `ripdpi-monitor-engine` | Active-scan engine | Engine API | `ripdpi-monitor-adapter`, `ripdpi-monitor-lane-adapter`, `ripdpi-runtime-platform`, … | Core diagnostics engine | Keep |
| `ripdpi-monitor-adapter` | Monitor ↔ diagnostics-contracts adapter | Adapter API | `ripdpi-diagnostics-contracts`, `ripdpi-failure-classifier`, `ripdpi-proxy-config` | — | Keep |
| `ripdpi-monitor-lane-adapter` | Probe-lane adapter over diagnostics crates | Adapter API | 8 × `ripdpi-diagnostics-*` | High fan-out — wiring crate | Keep thin |
| `ripdpi-monitor-proxy-runtime` | Monitor ↔ proxy-runtime passive-telemetry adapter | Adapter API | `ripdpi-monitor-engine`, `ripdpi-proxy-runtime`, `ripdpi-runtime-api` | Monitor observes L4 — outer adapter | Keep |

### L7 — relay transports

| Crate | Responsibility | API surface | Key internal deps | Coupling / risk | Action |
|-------|----------------|-------------|-------------------|-----------------|--------|
| `ripdpi-relay-core` | Shared relay backend + capability surface | Relay traits + orchestration | `ripdpi-relay-mux` + 6 transport crates | Aggregates all transports | Keep |
| `ripdpi-relay-mux` | Relay-session pooling + stream-lease mux | Pool API | — (leaf) | Fan-in via `relay-core`, `vless` | Keep |
| `ripdpi-hysteria2` | Hysteria2 transport | Transport client | — (leaf) | `quinn` | Keep |
| `ripdpi-masque` | MASQUE / HTTP-3 proxy transport | Transport client | `ripdpi-hysteria2`, `ripdpi-tls-profiles` | `quinn`, `boring` | Keep |
| `ripdpi-tuic` | TUIC v5 transport | Transport client | — (leaf) | `quinn` | Keep |
| `ripdpi-shadowtls` | ShadowTLS v3 camouflage | Transport client | — (leaf) | — | Keep |
| `ripdpi-shadowsocks` | Shadowsocks transport | Transport client | — (leaf) | **No workspace consumer** — not in `relay-core` | Verify wiring vs README claim of SS support |
| `ripdpi-trojan` | Trojan transport | Transport client | — (leaf) | **No workspace consumer** — not in `relay-core` | Verify wiring |
| `ripdpi-vless` | VLESS Reality / xHTTP transport | Transport client | `ripdpi-relay-mux`, `ripdpi-tls-profiles` | `boring`; used by `xhttp`, `cloudflare-origin` | Keep |
| `ripdpi-xhttp` | xHTTP transport (VLESS xHTTP, CF Tunnel) | Transport client | `ripdpi-vless`, `ripdpi-tls-profiles` | — | Keep |
| `ripdpi-cloudflare-origin` | Local xHTTP origin helper for CF Tunnel publish | `bin` | `ripdpi-vless` | Subprocess helper binary | Keep |
| `ripdpi-naiveproxy` | NaiveProxy helper | `bin` (`src/main.rs`) | — (leaf) | Subprocess helper binary | Keep |
| `ripdpi-warp-core` | WARP runtime + AmneziaWG codec | Runtime API | — (leaf) | `smoltcp`; root of `libripdpi-warp.so` | Keep |
| `ripdpi-apps-script-core` | Google Apps Script relay path | Transport client | — (leaf) | Consumed by `ripdpi-relay-android` | Keep |
| `ripdpi-ws-tunnel` | MTProto WebSocket tunnel for Telegram | Tunnel client | `ripdpi-tls-profiles` | `boring` | Keep |

### L8 — Android / JNI adapters

| Crate | Responsibility | API surface | Key internal deps | Coupling / risk | Action |
|-------|----------------|-------------|-------------------|-----------------|--------|
| `android-support` | Shared JNI/Android primitives — `ffi_boundary`, handle registry, `android_logger` | JNI helpers | — (leaf) | `jni`; consumed by L8 only | Keep — must never enter L0–L7 |
| `ripdpi-android-bridge-support` | Shared JNI bridge helpers | JNI helpers | `android-support` | `jni` | Keep |
| `ripdpi-android-proxy-adapter` | Proxy JNI adapter | JNI exports | `ripdpi-proxy-runtime`, `ripdpi-runtime-api`, … (8) | `jni` | Keep |
| `ripdpi-android-diagnostics-adapter` | Diagnostics JNI adapter | JNI exports | `ripdpi-monitor-engine`, `ripdpi-monitor-proxy-runtime`, … | `jni` | Keep |
| `ripdpi-android-fetch-adapter` | Owned-TLS fetch JNI adapter | JNI exports | `ripdpi-dns-resolver`, `ripdpi-native-protect`, `ripdpi-tls-profiles` | `jni` | Keep |
| `ripdpi-android-platform-adapter` | Platform-port JNI adapter | JNI exports | `ripdpi-runtime-platform`, `ripdpi-shared-priors`, … (7) | `jni` | Keep |
| `ripdpi-android-vpn-protect-adapter` | `VpnService.protect` JNI adapter | JNI exports | `ripdpi-native-protect` | `jni` | Keep |
| `ripdpi-android-telemetry-adapter` | Telemetry projection adapter | Projection API | `ripdpi-telemetry`, `ripdpi-runtime-api`, … (5) | **No `jni` dep** — pure projection, still L8 | Keep |
| `ripdpi-android` | `libripdpi.so` — proxy/diagnostics/strategy JNI entrypoint | `cdylib` + `JNI_OnLoad` | the 7 `ripdpi-android-*` adapters + `android-support` + 2 strategy crates | `jni`; artifact root | Keep |
| `ripdpi-tunnel-android` | `libripdpi-tunnel.so` — TUN bridge JNI entrypoint | `cdylib` + JNI | `ripdpi-tunnel-core`, `ripdpi-runtime-platform`, … | `jni`; artifact root | Keep |
| `ripdpi-relay-android` | `libripdpi-relay.so` — relay JNI entrypoint (`RipDpiRelayNativeBindings`) | `cdylib` + JNI | `ripdpi-relay-core`, `ripdpi-apps-script-core`, `android-support` | `jni`; artifact root | **TODO verify** Kotlin counterpart `RipDpiRelayNativeBindings` + that `libripdpi-relay.so` is loaded — class not seen in `core/engine/.../core/` top-level listing |
| `ripdpi-warp-android` | `libripdpi-warp.so` — WARP JNI entrypoint | `cdylib` + JNI | `ripdpi-warp-core`, `ripdpi-native-protect`, `ripdpi-tls-profiles`, `android-support` | `jni`; artifact root | Keep |

---

## 5. Crates that must stay Android/JNI-free

Every crate **except the 12 L8 crates** must not depend on `jni`,
`android-support`, `android_logger`, or any `ndk-*` crate. That is **87 crates**
— all of L0–L7:

> `golden-test-support`, `local-network-fixture`, `native-soak-support`,
> `ripdpi-bench`, `ripdpi-cli`, `ripdpi-packets`, `ripdpi-tls-profiles`,
> `ripdpi-socks5-core`, `ripdpi-ipfrag`, `ripdpi-collections`, `ripdpi-geo`,
> `ripdpi-protocol-detect`, `ripdpi-protocol-loopback`, `ripdpi-dns-resolver`,
> `ripdpi-config`, `ripdpi-proxy-config`, `ripdpi-tunnel-config`,
> `ripdpi-strategy-config`, `ripdpi-strategy-trait`, `ripdpi-runtime-api`,
> `ripdpi-runtime-decision-ports`, `ripdpi-diagnostics-contracts`,
> `ripdpi-telemetry`, `ripdpi-desync`, `ripdpi-desync-runtime`,
> `ripdpi-failure-classifier`, `ripdpi-session`, `ripdpi-routing`,
> `ripdpi-shared-priors`, `ripdpi-runtime-policy`, `ripdpi-runtime-adaptive`,
> `ripdpi-runtime-strategy`, `ripdpi-strategy-http`, `ripdpi-strategy-ipv6`,
> `ripdpi-strategy-lua`, `ripdpi-strategy-udp`, `ripdpi-strategy-window`,
> `ripdpi-strategy-registry`, `ripdpi-proxy-runtime`,
> `ripdpi-proxy-runtime-adapter`, `ripdpi-proxy-runtime-desync-adapter`,
> `ripdpi-runtime-services`, `ripdpi-runtime-dns-cache`, `ripdpi-tunnel-core`,
> `ripdpi-tunnel-intercept`, `ripdpi-ws-bootstrap`, `ripdpi-runtime-platform`,
> `ripdpi-native-protect`, `ripdpi-tun-driver`, `ripdpi-io-uring`,
> `ripdpi-capabilities`, `ripdpi-privileged-ops`, `ripdpi-root-helper-protocol`,
> `ripdpi-root-helper`, the 14 `ripdpi-diagnostics-*` crates (all except
> `-contracts`, which is L2 and also JNI-free), the 4 `ripdpi-monitor-*`
> crates, and the 15 L7 relay-transport crates.

Load-bearing cases: `ripdpi-runtime-platform` and `ripdpi-native-protect` are
the *platform ports* — they must define the abstraction and stay JNI-free so
the L8 adapters can implement them. `ripdpi-proxy-runtime`, `ripdpi-tunnel-core`,
`ripdpi-monitor-engine`, and `ripdpi-relay-core` are the runtime cores compiled
into the `.so` files but must themselves contain no JNI.

This invariant holds in `Cargo.toml` today: only the 11 JNI-bearing L8 crates
pull `jni`, and `android-support` is consumed by L8 crates only.

---

## 6. Outer Android adapter crates

The **12 L8 crates** — the only crates allowed to touch JNI / `android-support`,
and the only `cdylib` roots:

**Artifact roots (`cdylib` → `.so`):**
`ripdpi-android`, `ripdpi-tunnel-android`, `ripdpi-relay-android`,
`ripdpi-warp-android`.

**Adapter libraries (linked into the roots above):**
`android-support`, `ripdpi-android-bridge-support`,
`ripdpi-android-proxy-adapter`, `ripdpi-android-diagnostics-adapter`,
`ripdpi-android-fetch-adapter`, `ripdpi-android-platform-adapter`,
`ripdpi-android-vpn-protect-adapter`, `ripdpi-android-telemetry-adapter`.

`ripdpi-android-telemetry-adapter` is the one L8 crate with no `jni` dependency
(it only projects telemetry types) — still L8 by role and naming.

---

## 7. Root-helper / privileged crates

Privileged execution is opt-in (rooted devices, `root_mode_enabled`) and isolated
into a small set of crates:

| Crate | Role |
|-------|------|
| `ripdpi-root-helper` | Standalone `bin` — runs as uid 0, spawned via `su`; Unix-socket IPC with `SCM_RIGHTS` fd passing |
| `ripdpi-root-helper-protocol` | The Unix-socket IPC wire protocol shared by the helper and its in-app client |
| `ripdpi-privileged-ops` | Privileged raw-socket operations (FakeRst, MultiDisorder, IP fragmentation, raw IPv4/IPv6 emit) |

Closely related platform crates (capability-gated, **not** themselves
privileged binaries): `ripdpi-capabilities` (decides whether a privileged
emitter may run), `ripdpi-runtime-platform` (dispatches to the helper or to
local calls), `ripdpi-native-protect` (`VpnService.protect`). All non-root
features must degrade gracefully when root is unavailable — see
[AGENTS.md](../../AGENTS.md) § Project Rules and
[`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md).

---

## 8. Test / support / local-debug crates

These exist so they do **not** pollute the production mental model. They are
declared in `[workspace.metadata.ripdpi]`:

- `test-support-crates = ["golden-test-support", "local-network-fixture",
  "native-soak-support", "ripdpi-bench"]` — test/bench/debug infrastructure.
  The `Cargo.toml` comment states they **must never appear as non-dev
  dependencies of production crates**; verified true today (every occurrence is
  a `[dev-dependencies]` entry).
- `local-debug-crates = ["ripdpi-cli"]` — the desktop CLI (`ripdpi` binary).
  Useful for running the proxy runtime on macOS/Linux; **not packaged in the
  APK** and never an Android dependency.

When reasoning about what ships, exclude these five crates. `local-network-fixture`
also builds a fixture `bin`, and `ripdpi-bench` is a Criterion harness — neither
is a runtime artifact.

---

## Open verification items

These are flagged inline above; collected here for triage. None block the build.

- **`ripdpi-relay-android` Kotlin counterpart** — exports
  `Java_..._RipDpiRelayNativeBindings_*`, but a `RipDpiRelayNativeBindings.kt`
  was not seen in the `core/engine/src/main/kotlin/com/poyka/ripdpi/core/`
  top-level listing. Verify the class location and that `libripdpi-relay.so` is
  loaded (cf. `RipDpiNativeLoader.kt`, which loads only `"ripdpi"`).
- **Library crates with no workspace consumer** — `ripdpi-protocol-detect`,
  `ripdpi-protocol-loopback`, `ripdpi-routing`, `ripdpi-runtime-dns-cache`,
  `ripdpi-diagnostics-net`, `ripdpi-diagnostics-parsers`, `ripdpi-shadowsocks`,
  `ripdpi-trojan` are not referenced by any other crate's `[dependencies]` or
  `[dev-dependencies]`. They may be feature-gated, test-only, or pending
  integration — verify each before assuming it is wired (or dead).
- **`ripdpi-diagnostics-net`** mirrors `ripdpi-diagnostics-protocols`'
  dependency set; confirm whether `-net` is superseded by `-protocols`.
- **`ripdpi-config` naming** — `docs/native/README.md` calls it "CLI
  configuration parsing", but 16 crates across runtime/desync/diagnostics
  depend on it: it is the shared core config model. Per task constraints it is
  **not** renamed here — clarify in docs.

---

## Deeper docs

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — whole-app architecture entrypoint.
- [`docs/native/README.md`](../native/README.md) — native module narrative and
  per-crate notes (note the stale `ripdpi-runtime` / `ripdpi-monitor` / two-`.so`
  references corrected in [§1](#1-production-artifacts)).
- [`docs/native/proxy-engine.md`](../native/proxy-engine.md),
  [`docs/native/tunnel.md`](../native/tunnel.md) — runtime internals.
- [`architecture/README.md`](README.md) — ownership-boundary notes, including
  `native-runner-and-platform-decomposition.md` and
  `post-poy7-decomposition-gradient.md`, which document the crate split.
- [`.claude/rules/rust-toolchain-pin.md`](../../.claude/rules/rust-toolchain-pin.md),
  [`.claude/rules/llm-rust-prompts.md`](../../.claude/rules/llm-rust-prompts.md)
  — `--locked` discipline and AI-diff acceptance gates.
