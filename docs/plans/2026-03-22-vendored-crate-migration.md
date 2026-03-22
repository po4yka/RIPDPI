# Vendored Crate Migration Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace all vendored third-party crates (`ciadpi-*` from byedpi, `hs5t-*` from hev-socks5-tunnel) with RIPDPI-owned Rust modules to eliminate external project dependencies entirely.

**Architecture:** Phased inside-out migration. Each phase extracts one vendored crate into a new RIPDPI-owned crate with an identical public API, updates all import sites, removes the vendored crate, and verifies with the existing test suite. Phases are ordered by coupling depth: lightest dependencies first, heaviest last.

**Tech Stack:** Rust (stable toolchain), Android NDK, `mio`, `tokio`, `smoltcp`, `aes-gcm`, `hkdf`, `sha2`, `serde`, `nix`

---

## Current State

### What RIPDPI already owns

| Layer | Crate | Role |
|-------|-------|------|
| Proxy event loop | `ripdpi-runtime` | Connection state machine, SOCKS5 handshake, relay, retry/routing, adaptive feedback |
| Config bridge | `ripdpi-proxy-config` | Kotlin UI types to native `RuntimeConfig` conversion |
| Diagnostics | `ripdpi-monitor` | Active scans, passive telemetry, DNS probing |
| JNI orchestration | `ripdpi-android` | Handle registry, config parsing, telemetry marshaling |
| Tunnel JNI bridge | `hs5t-android` | VPN-mode TUN fd lifecycle, tunnel telemetry |
| WS tunnel | `ripdpi-ws-tunnel` | Telegram WebSocket fallback (independent, no vendored deps) |
| Failure classification | `ripdpi-failure-classifier` | Shared error enums |

### What is still vendored

| Vendored Crate | LOC | Consumers | What it provides |
|---|---|---|---|
| `ciadpi-session` | 558 | `ripdpi-runtime` (10 imports) | SOCKS4/5 + HTTP CONNECT parsing, session state observation, trigger detection |
| `ciadpi-packets` | 2,050 | `ripdpi-runtime`, `ripdpi-proxy-config`, `ripdpi-monitor` (15 imports) | HTTP/TLS/QUIC packet parsing, fake packet building, protocol constants |
| `ciadpi-config` | 2,025 | `ripdpi-runtime`, `ripdpi-proxy-config`, `ripdpi-monitor`, `ripdpi-android` (50+ imports) | `RuntimeConfig`, `DesyncGroup`, offset expressions, activation filters, cache operations |
| `ciadpi-desync` | 2,297 | `ripdpi-runtime` (12 imports) | TCP/UDP desync planning, packet mutation sequencing, activation context |
| `hs5t-config` | 547 | `hs5t-android` (4 imports) | Tunnel YAML config structs |
| `hs5t-core` | 2,386 | `hs5t-android` (6 imports) | TUN-to-SOCKS event loop (smoltcp + tokio), stats, DNS, packet classification |
| `hs5t-tunnel` | 456 | `hs5t-android` tests only (2 imports) | Linux TUN device driver (ioctl) |

**Total: ~10.3K LOC, 7 crates, ~72 import sites across 18 source files.**

### Dependency graph

```
ripdpi-android ──> ripdpi-runtime ──> ciadpi-config
                                  ──> ciadpi-desync ──> ciadpi-config
                                  │                 ──> ciadpi-packets
                                  │                 ──> ciadpi-session
                                  ──> ciadpi-packets
                                  ──> ciadpi-session

ripdpi-proxy-config ──> ciadpi-config
                    ──> ciadpi-packets

ripdpi-monitor ──> ciadpi-config
               ──> ciadpi-packets

hs5t-android ──> hs5t-config
             ──> hs5t-core ──> hs5t-config
                           ──> hs5t-tunnel (Linux TUN driver)
                           ──> ripdpi-dns-resolver
```

---

## Migration Phases

### Phase 1: Session Protocol (ciadpi-session) -- ~1-2 weeks

**Why first:** Smallest crate (558 LOC), minimal external deps (only `ciadpi-config` and `ciadpi-packets`), clean protocol-focused API. Only consumed by `ripdpi-runtime`.

**New crate:** `native/rust/crates/ripdpi-session`

**Public API to reimplement:**

```
// Constants (SOCKS protocol bytes)
S_AUTH_NONE, S_AUTH_BAD, S_ATP_I4, S_ATP_ID, S_ATP_I6
S_CMD_CONN, S_CMD_BIND, S_CMD_AUDP
S_ER_OK, S_ER_GEN, S_ER_DENY, S_ER_NET, S_ER_HOST, S_ER_CONN, S_ER_TTL, S_ER_CMD, S_ER_ATP
S4_OK, S4_ER, S_VER5, S_VER4
S_SIZE_MIN, S_SIZE_I4, S_SIZE_I6, S_SIZE_ID

// Enums
SocketType { Stream, Datagram }
ClientRequest { Socks4Connect, Socks5Connect, Socks5UdpAssociate, HttpConnect }
ProxyReply { Socks4, Socks5, Http }
SessionPhase { Handshake, Connected, Closed }
TriggerEvent { Redirect, SslErr, Connect, Torst }

// Structs
TargetAddr { addr: SocketAddr }
OutboundProgress { round, payload_size, stream_start, stream_end }
SessionState { phase, round_count, recv_count, ... }
  -> observe_outbound(&mut self, payload: &[u8]) -> OutboundProgress
  -> observe_datagram_outbound(&mut self, payload: &[u8]) -> OutboundProgress
  -> observe_inbound(&mut self, payload: &[u8])
SessionConfig { resolve: bool, ipv6: bool }
SessionError { code: u8 }

// Traits
NameResolver { resolve(&self, host, socket_type) -> Option<SocketAddr> }

// Functions
parse_socks4_request, parse_socks5_request, parse_http_connect_request
encode_socks4_reply, encode_socks5_reply, encode_http_connect_reply
detect_response_trigger
```

**Import sites to update (10 lines in ripdpi-runtime):**

| File | Line | Current import |
|------|------|----------------|
| `src/runtime/udp.rs` | 11 | `use ciadpi_session::{SessionState, SocketType, S_ATP_I4, S_ATP_I6}` |
| `src/runtime/relay.rs` | 13 | `use ciadpi_session::SessionState` |
| `src/runtime/relay.rs` | 556 | `use ciadpi_session::TriggerEvent` (test) |
| `src/runtime/routing.rs` | 9 | `use ciadpi_session::{...}` |
| `src/runtime/handshake.rs` | 12 | `use ciadpi_session::{...}` |
| `src/runtime/handshake.rs` | 759 | `use ciadpi_session::{S_CMD_CONN, S_VER5}` (test) |
| `src/runtime/desync.rs` | 11 | `use ciadpi_session::OutboundProgress` |
| `src/runtime.rs` | 47 | `use ciadpi_session::{...}` (test) |

**Steps:**

1. Create `native/rust/crates/ripdpi-session/Cargo.toml` with no vendored deps
2. Copy and adapt the 558-LOC `lib.rs` -- the protocol logic is RFC-specified and self-contained
3. Port inline tests from `ciadpi-session` (~14 tests)
4. Write additional tests for SOCKS4/5 edge cases and HTTP CONNECT parsing
5. Update `ripdpi-runtime/Cargo.toml`: replace `ciadpi-session` with `ripdpi-session`
6. Find-and-replace `use ciadpi_session` -> `use ripdpi_session` in all 8 import sites
7. Run: `cargo test -p ripdpi-session --lib && cargo test -p ripdpi-runtime --lib`
8. Run: `bash scripts/ci/run-rust-native-checks.sh`
9. Remove `ciadpi-session` from workspace deps in `native/rust/Cargo.toml`
10. Commit

**Risk:** Low. Protocol parsing is well-specified. The vendored crate depends on `ciadpi-config` and `ciadpi-packets` for some types, but RIPDPI's session crate can use its own minimal types until those crates are also migrated.

---

### Phase 2: Packet Parsing (ciadpi-packets) -- ~2-3 weeks

**Why second:** Self-contained packet logic with clear boundaries. Crypto deps (`aes`, `aes-gcm`, `hkdf`, `sha2`) are standard crates. Used by 3 RIPDPI crates.

**New crate:** `native/rust/crates/ripdpi-packets`

**Public API to reimplement:**

```
// Protocol detection constants
IS_TCP, IS_UDP, IS_HTTP, IS_HTTPS, IS_IPV4
QUIC_V1_VERSION, QUIC_V2_VERSION

// HTTP mutation flags
MH_HMIX, MH_SPACE, MH_DMIX, MH_UNIXEOL, MH_METHODEOL

// Default fake payloads
DEFAULT_FAKE_TLS, DEFAULT_FAKE_HTTP, DEFAULT_FAKE_UDP, DEFAULT_FAKE_QUIC_COMPAT_LEN

// Structs
HttpHost<'a> { host, port }
HttpMarkerInfo { method_start, host_start, host_end, port }
TlsMarkerInfo { ext_len_start, sni_ext_start, host_start, host_end }
QuicInitialInfo { version, client_hello, tls_info, is_crypto_complete }
PacketMutation { rc, bytes }
OracleRng (seeded PRNG)

// Enums
HttpFakeProfile, TlsFakeProfile, UdpFakeProfile

// Functions
http_marker_info(), tls_marker_info(), is_tls_client_hello(), is_http()
parse_quic_initial(), build_realistic_quic_initial()
mod_http_like_c()
http_fake_profile_bytes(), tls_fake_profile_bytes(), udp_fake_profile_bytes()
```

**Import sites to update (15 lines across 3 crates):**

- `ripdpi-runtime`: 7 files (adaptive_tuning, matching, relay, retry, udp, desync, network_e2e)
- `ripdpi-proxy-config`: 2 files (convert, tests)
- `ripdpi-monitor`: 2 files (execution, connectivity)

**Steps:**

1. Create `native/rust/crates/ripdpi-packets/Cargo.toml` with `aes`, `aes-gcm`, `hkdf`, `sha2`
2. Reimplement HTTP/TLS/QUIC parsing (the vendored code is 2,050 LOC across 2 files)
3. Port `fake_profiles.rs` (134 LOC of curated byte arrays)
4. Port ~40 inline tests from vendored crate
5. Port oracle diff tests from `third_party/byedpi/crates/ciadpi-packets/tests/`
6. Update Cargo.toml in `ripdpi-runtime`, `ripdpi-proxy-config`, `ripdpi-monitor`
7. Find-and-replace `use ciadpi_packets` -> `use ripdpi_packets` across all 15 import sites
8. Run full test suite: `bash scripts/ci/run-rust-native-checks.sh`
9. Remove `ciadpi-packets` from workspace deps
10. Commit

**Risk:** Medium. QUIC Initial decryption (AES-GCM + HKDF) requires exact cryptographic correctness. The oracle diff test fixtures from the vendored crate should be preserved as golden regression tests.

---

### Phase 3: Configuration Types (ciadpi-config) -- ~2-3 weeks

**Why third:** After packets and session are migrated, `ciadpi-config` has no remaining vendored deps. It's the most widely imported crate (50+ sites) but is purely type definitions -- no I/O, no runtime behavior.

**New crate:** `native/rust/crates/ripdpi-config`

**Public API to reimplement (partial list -- largest surface):**

```
// Constants
VERSION, detection flags (DETECT_*), auto flags (AUTO_*), fake mode flags (FM_*)
HOST_AUTOLEARN_DEFAULT_*

// Core types
RuntimeConfig, DesyncGroup, DesyncMode
OffsetExpr, OffsetBase, OffsetProto, PartSpec
TcpChainStep, TcpChainStepKind, UdpChainStep, UdpChainStepKind
ActivationFilter, NumericRange<T>
QuicFakeProfile, QuicInitialMode, AutoTtlConfig
CacheEntry, StartupEnv, ParseResult

// Functions
parse_cli(), parse_hosts_spec()
load_cache_entries_from_path(), dump_cache_entries()
prefix_match_bytes()
```

**Import sites to update (50+ lines across 4 crates):**

- `ripdpi-runtime`: ~30 import lines across 16 modules
- `ripdpi-android`: ~4 import lines
- `ripdpi-proxy-config`: ~3 import lines
- `ripdpi-monitor`: ~2 import lines

**Steps:**

1. Create `native/rust/crates/ripdpi-config/Cargo.toml`
2. Start with type definitions: enums, structs, constants (pure data, no logic)
3. Implement `parse_cli()` -- CLI argument parser (~300 LOC of the original)
4. Implement cache operations: `load_cache_entries_from_path`, `dump_cache_entries`
5. Port ~27 inline tests + oracle diff tests from vendored crate
6. Bulk find-and-replace `use ciadpi_config` -> `use ripdpi_config` across all 50+ sites
7. Update all 4 consumer Cargo.toml files
8. Run: `cargo test --workspace` and `bash scripts/ci/run-rust-native-checks.sh`
9. Remove `ciadpi-config` from workspace deps
10. Commit

**Risk:** Medium. The sheer number of import sites (50+) makes this the highest-volume refactor. Use `sed` or `ast-grep` for bulk renaming. The `parse_cli()` function is the only non-trivial logic -- everything else is type definitions.

**Note:** After this phase, `ciadpi-desync` is the only remaining byedpi crate, and it depends on `ciadpi-config`, `ciadpi-packets`, and `ciadpi-session` -- all of which are now RIPDPI-owned. Update `ciadpi-desync/Cargo.toml` to point at the new crates temporarily.

---

### Phase 4: Desync Engine (ciadpi-desync) -- ~3-4 weeks

**Why fourth:** Hardest crate -- core algorithmic complexity. Depends on all three previously migrated crates. Only consumed by `ripdpi-runtime`.

**New crate:** `native/rust/crates/ripdpi-desync`

**Public API to reimplement:**

```
// Structs
ProtoInfo { kind, http, tls }
TcpSegmentHint { snd_mss, advmss, pmtu, ip_header_overhead }
ActivationContext { round, payload_size, stream_start, stream_end, transport, ... }
AdaptivePlannerHints { split_offset_base, tls_record_offset_base, ... }
PlannedStep { kind, start, end }
HostFakeSpan { host_start, host_end, midhost }
DesyncPlan { tampered, steps, proto, actions }
TamperResult, FakePacketPlan, DesyncError

// Enums
ActivationTransport { Tcp, Udp }
AdaptiveTlsRandRecProfile { Balanced, Tight, Wide }
AdaptiveUdpBurstProfile { Balanced, Conservative, Aggressive }
DesyncAction { Write, WriteUrgent, SetTtl, RestoreDefaultTtl, SetMd5Sig, ... }

// Functions
planner_for_desync_group() -- the core planning function
activation_filter_matches()
```

**Import sites to update (12 lines in ripdpi-runtime):**

| File | Import |
|------|--------|
| `src/adaptive_tuning.rs` | `AdaptivePlannerHints`, `AdaptiveTlsRandRecProfile`, `AdaptiveUdpBurstProfile` |
| `src/platform/linux.rs` | `TcpSegmentHint` |
| `src/platform/mod.rs` | `TcpSegmentHint` |
| `src/retry_stealth.rs` | `AdaptivePlannerHints` |
| `src/runtime/udp.rs` | `plan_udp`, `ActivationTransport`, `DesyncAction` |
| `src/runtime/desync.rs` | `DesyncGroup`, `RuntimeConfig`, `TcpChainStepKind`, planner, context types |
| `src/runtime/retry.rs` | `AdaptivePlannerHints` |
| `src/runtime/adaptive.rs` | `AdaptivePlannerHints` |

**Steps:**

1. Create `native/rust/crates/ripdpi-desync/Cargo.toml` depending on `ripdpi-config`, `ripdpi-packets`, `ripdpi-session`
2. Reimplement `planner_for_desync_group()` -- the core desync planning algorithm (~800 LOC)
3. Reimplement activation filter matching, TCP segment hinting, adaptive planner hints
4. Reimplement `DesyncAction` enum and the action serialization layer
5. Port ~54 inline tests from vendored crate
6. Port oracle diff tests and action planning tests
7. Update `ripdpi-runtime/Cargo.toml` and all 12 import sites
8. Run: `cargo test -p ripdpi-desync --lib && cargo test -p ripdpi-runtime --lib`
9. Run: `bash scripts/ci/run-rust-native-checks.sh`
10. Run: `bash scripts/ci/run-rust-network-e2e.sh` (critical -- validates live proxy behavior)
11. Remove `ciadpi-desync` from workspace deps
12. Commit

**Risk:** High. `planner_for_desync_group()` contains the core packet manipulation scheduling logic -- offset resolution, fake packet interleaving, disorder sequencing, TLS record splitting. This is where correctness matters most. The oracle diff tests from the vendored crate are essential regression coverage.

**After Phase 4:** All byedpi vendored crates are removed. The entire `native/rust/third_party/byedpi/` directory can be deleted (including unused `ciadpi-bin` and `ciadpi-jni`).

---

### Phase 5: Tunnel Config (hs5t-config) -- ~1 week

**Why fifth:** Trivial serde structs (547 LOC), completely isolated in `hs5t-android`.

**New crate:** `native/rust/crates/ripdpi-tunnel-config`

**Public API to reimplement:**

```
ConfigError { Yaml, MismatchedCredentials, Io }
Config { tunnel, socks5, mapdns, misc }
TunnelConfig { name, mtu, multi_queue, ipv4, ipv6, ... }
Socks5Config { port, address, udp, username, password, mark, ... }
MapDnsConfig { address, port, network, resolver fields, ... }
MiscConfig { task_stack_size, tcp_buffer_size, timeouts, ... }
```

**Import sites to update (4 in hs5t-android + 2 in tests):**

**Steps:**

1. Create `native/rust/crates/ripdpi-tunnel-config/Cargo.toml` with `serde`, `serde_yaml`, `thiserror`
2. Copy and adapt the 547-LOC config structs (pure serde derives)
3. Port ~21 inline tests
4. Update `hs5t-android/Cargo.toml` and 6 import sites
5. Run: `cargo test -p ripdpi-tunnel-config --lib && cargo test -p hs5t-android --lib`
6. Remove `hs5t-config` from workspace deps
7. Commit

**Risk:** Low. Purely declarative serde types.

---

### Phase 6: TUN Device Driver (hs5t-tunnel) -- ~2-3 weeks

**Why sixth:** Platform-specific Linux ioctl code. Only used in `hs5t-android` tests (not production JNI path on Android).

**New crate:** `native/rust/crates/ripdpi-tun-driver`

**Public API to reimplement:**

```
TunnelError { Io, Ioctl }
trait TunnelDriver: Send + Sync {
    fn open(name, multi_queue) -> Result<Self>
    fn fd(&self) -> RawFd
    fn name(&self) -> &str
    fn index(&self) -> u32
    fn set_mtu(&self, mtu) -> Result<()>
    fn set_ipv4(&self, addr, prefix) -> Result<()>
    fn set_ipv6(&self, addr, prefix) -> Result<()>
    fn set_up(&self) -> Result<()>
    fn set_down(&self) -> Result<()>
}
LinuxTunnel (cfg target_os = "linux")
```

**Steps:**

1. Create `native/rust/crates/ripdpi-tun-driver/Cargo.toml` with `libc`, `nix`, `thiserror`
2. Reimplement `LinuxTunnel` with TUN ioctl calls (368 LOC of `linux.rs`)
3. Port ~8 inline tests
4. Update `hs5t-android/Cargo.toml` (test dependency only)
5. Update 2 test import sites in `linux_tun_e2e.rs` and `linux_tun_soak.rs`
6. Run TUN tests: `RIPDPI_RUN_TUN_E2E=1 cargo test -p hs5t-android --test linux_tun_e2e`
7. Remove `hs5t-tunnel` from workspace deps
8. Commit

**Risk:** Medium. Platform ioctl code requires Linux for testing. CI already has privileged Linux TUN lanes.

---

### Phase 7: Tunnel Core (hs5t-core) -- ~3-4 weeks

**Why last:** Largest vendored crate (2,386 LOC), heaviest dependencies (smoltcp + tokio), most complex runtime. Completely isolated in VPN mode path.

**New crate:** `native/rust/crates/ripdpi-tunnel-core`

**Public API to reimplement:**

```
run_tunnel() -- main async entry point
io_loop_task() -- smoltcp packet processing loop
classify_ip_packet(), IpClass
TunDevice, ActiveSessions, SessionEntry
Stats, DnsStatsSnapshot
```

**Steps:**

1. Create `native/rust/crates/ripdpi-tunnel-core/Cargo.toml` with `smoltcp`, `tokio`, `tokio-util`, `nix`, `fast-socks5`, `tracing`, `anyhow`; depend on `ripdpi-tunnel-config`, `ripdpi-tun-driver`, `ripdpi-dns-resolver`
2. Reimplement `io_loop_task()` -- the smoltcp-based packet processing loop (1,317 LOC, largest single module)
3. Reimplement `device.rs` (TUN device abstraction, 244 LOC)
4. Reimplement `sessions.rs` (SOCKS5 session tracking, 166 LOC)
5. Reimplement `stats.rs` (counter collection, 237 LOC)
6. Reimplement `classify.rs` (IP packet classification, 187 LOC)
7. Reimplement `tunnel_api.rs` (async entry point, 130 LOC)
8. Port ~23 inline tests across all submodules
9. Update `hs5t-android/Cargo.toml` and 6 import sites
10. Run: `cargo test -p ripdpi-tunnel-core --lib && cargo test -p hs5t-android --lib`
11. Run TUN E2E: `RIPDPI_RUN_TUN_E2E=1 bash scripts/ci/run-linux-tun-soak.sh`
12. Run Android instrumentation: `./gradlew :app:connectedDebugAndroidTest`
13. Remove `hs5t-core` from workspace deps
14. Delete `native/rust/third_party/hev-socks5-tunnel/` entirely
15. Commit

**Risk:** High. The smoltcp integration in `io_loop.rs` is the most complex piece -- it manages a userspace TCP/IP stack with DNS interception, mapped-address rewriting, and SOCKS5 forwarding. Thorough E2E testing on real Android devices is essential.

---

## Post-Migration Cleanup

After all 7 phases are complete:

1. **Delete vendored directories:**
   ```bash
   rm -rf native/rust/third_party/byedpi/
   rm -rf native/rust/third_party/hev-socks5-tunnel/
   rmdir native/rust/third_party/
   ```

2. **Clean workspace Cargo.toml:** Remove `exclude` entries for `third_party/` and all `ciadpi-*` / `hs5t-*` workspace dependency declarations

3. **Update Cargo.lock:** `cargo update` to drop transitive deps from removed crates

4. **Update docs:**
   - `docs/native/byedpi.md` -- rename to `docs/native/proxy-engine.md`, remove all byedpi references
   - `docs/native/hev-socks5-tunnel.md` -- rename to `docs/native/tunnel.md`, remove all hs5t references
   - `docs/native/README.md` -- update crate names and links
   - `CLAUDE.md` -- update any references to vendored paths

5. **Update CI:**
   - `scripts/ci/run-rust-native-checks.sh` -- remove vendored byedpi/hs5t test sections
   - `.github/workflows/ci.yml` -- remove "vendored parity smoke" references

6. **Update deny.toml:** Remove any vendored crate exceptions from `cargo-deny` config

7. **Update .gitignore:** Remove any vendored-specific entries

---

## Timeline Summary

| Phase | Crate | New RIPDPI Crate | Est. Duration | Risk |
|-------|-------|------------------|---------------|------|
| 1 | `ciadpi-session` | `ripdpi-session` | 1-2 weeks | Low |
| 2 | `ciadpi-packets` | `ripdpi-packets` | 2-3 weeks | Medium |
| 3 | `ciadpi-config` | `ripdpi-config` | 2-3 weeks | Medium |
| 4 | `ciadpi-desync` | `ripdpi-desync` | 3-4 weeks | High |
| 5 | `hs5t-config` | `ripdpi-tunnel-config` | 1 week | Low |
| 6 | `hs5t-tunnel` | `ripdpi-tun-driver` | 2-3 weeks | Medium |
| 7 | `hs5t-core` | `ripdpi-tunnel-core` | 3-4 weeks | High |
| -- | Cleanup | -- | 1 week | Low |
| | | **Total** | **~15-21 weeks** | |

## Verification Checkpoints

Each phase must pass before proceeding:

1. `cargo test --workspace` -- all RIPDPI crate tests pass
2. `bash scripts/ci/run-rust-native-checks.sh` -- full CI parity
3. `bash scripts/ci/run-rust-network-e2e.sh` -- live proxy E2E (phases 1-4)
4. `./gradlew testDebugUnitTest` -- Kotlin/JVM tests pass
5. `./gradlew :app:connectedDebugAndroidTest` -- Android instrumentation (phase 7)
6. `RIPDPI_RUN_TUN_E2E=1 bash scripts/ci/run-linux-tun-soak.sh` -- TUN E2E (phases 6-7)

## Migration Principles

- **API-identical first, improve later.** Each new crate must expose the exact same public types and functions as the vendored original. Refactoring the API surface is a separate effort after migration.
- **Preserve all test fixtures.** Oracle diff JSON files and golden test data from vendored crates must be copied into the new crates as regression anchors.
- **One crate per branch.** Each phase gets its own feature branch and PR. Never migrate two crates in the same PR.
- **No behavioral changes.** The migration must be a pure ownership transfer. Any bug fixes, optimizations, or API improvements happen in follow-up PRs.
