# RIPDPI Glossary

Precise, repository-specific definitions of the domain terms used across the
architecture docs, code, and commit messages. Terms are alphabetical. Each
entry cites where the concept lives in the tree.

For the bigger picture see [`ARCHITECTURE.md`](ARCHITECTURE.md); for how to
extend these concepts see [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md).

---

**adaptive fallback** — The Tier-2 runtime behavior that, when the direct path
fails for a specific reason (TCP reset, TLS error, HTTP redirect, connect
failure), automatically re-evaluates and ranks alternative transport arms
instead of staying on a dead strategy. Gated by the `adaptive_fallback_*`
fields in `app_settings.proto`; the ranking logic lives in
`native/rust/crates/ripdpi-runtime-adaptive`. Distinct from a **strategy
chain**, which is a fixed ordered list; adaptive fallback *chooses between*
options at runtime.

**candidate** — One concrete **strategy** configuration that a strategy
**probe** tests during a diagnostics scan. The current TCP and QUIC suites are
planned by `native/rust/crates/ripdpi-diagnostics-candidates` (see
[`DIAGNOSTICS_ARCHITECTURE.md`](DIAGNOSTICS_ARCHITECTURE.md) § Probe & candidate
registration flow); exact membership
depends on the selected suite and runtime capability probes. A candidate is
what is *tried*; a **verdict** is the conclusion.

**control plane** — Everything that configures, starts, stops, and observes the
runtime: config translation, JNI lifecycle calls, ~1 Hz telemetry polling,
connection-policy resolution, diagnostics orchestration. It runs in Kotlin
(`:core:service`, `:core:engine`) plus native control entry points and crosses
the JNI boundary only at coarse granularity. Contrast **data plane**. See
[`JNI_CONTRACT.md`](JNI_CONTRACT.md) §12.

**data plane** — Per-packet work: SOCKS5 sessions, the **TUN** packet pump,
**desync** mutation, **relay** transport, DNS forwarding. It runs entirely in
native Rust inside the `.so` libraries with **no JNI on the hot path** — a JNI
call per packet would be a measurable bottleneck. Contrast **control plane**.

**desync** — DPI-desync evasion: deliberately mutating a connection's on-wire
packet sequence (segment splitting, disorder, fake-packet injection, OOB bytes,
TLS-record fragmentation, IP fragmentation) so a Deep Packet Inspection
middlebox cannot match or block the flow. Planning lives in
`native/rust/crates/ripdpi-desync`; execution in `ripdpi-desync-runtime`. See
the `desync-engine` skill.

**exact config replay** — Replaying a previously validated network's strategy
configuration verbatim. The normalized `proxyConfigJson` is persisted in
`remembered_network_policies` (its volatile runtime/log context stripped by
`RipDpiProxyJsonCodec.stripRuntimeContext`) and, on reconnect, re-applied with
fresh per-session context by `RipDpiProxyJsonCodec.rewriteJson`. The strategy
body is the persisted identity; session context is re-derived. See
[`CONFIG_CONTRACTS.md`](CONFIG_CONTRACTS.md) §4.

**in-path scan** — A diagnostics scan that runs *through* the active proxy or
VPN path, measuring targets as the user's traffic actually experiences them.
Selected by `diagnostics_default_scan_path_mode = "in_path"`. Contrast
**raw-path scan**.

**monitor lane** — A parallel probe-execution track inside the diagnostics
engine — there is a TCP lane and a QUIC lane, and strategy-probe progress
reports the active lane plus the candidate index within it. The diagnostics
probe crates are adapted into lanes by
`native/rust/crates/ripdpi-monitor-lane-adapter` and driven by
`ripdpi-monitor-engine`.

**native handle** — An opaque `jlong` returned by a `jniCreate` export that
identifies a live native session. Handles are issued and retired by
`android_support::HandleRegistry<T>`; `0` is the "no handle" / failure
sentinel. A handle is valid only between `create` and `destroy` and must never
be reused after `destroy`. See [`JNI_CONTRACT.md`](JNI_CONTRACT.md) §4.

**policy memory** — The per-network store of validated outcomes. A
privacy-preserving SHA-256 network fingerprint keys both
`remembered_network_policies` (validated per-network winning config) and
`host-autolearn-v2.json` (per-host learning, segmented by `networkScopeKey`).
Resolution flows through `ConnectionPolicyResolver` in `:core:service`. See
[`.claude/rules/network-fingerprint-privacy.md`](../../.claude/rules/network-fingerprint-privacy.md).

**privileged operation** — A raw-socket, `TCP_REPAIR`, or IP-fragmentation
operation that requires `uid 0` (FakeRst, MultiDisorder, raw IPv4/IPv6 emit,
…). Primitives live in `native/rust/crates/ripdpi-privileged-ops`; each is
dispatched to the **root helper** when available and **must** fall back to a
local non-privileged path otherwise. Opt-in behind `root_mode_enabled`.

**probe** — One diagnostic test executed against one target — an HTTP, TLS,
DNS, TCP fat-header, Telegram-availability, or strategy probe. Probe types are
implemented in the `ripdpi-diagnostics-*` crates and orchestrated by
`ripdpi-diagnostics-runner` / `ripdpi-monitor-engine`. A strategy probe
iterates **candidates**; the scan pipeline ends in a **verdict**.

**protect callback** — The mechanism by which native Rust asks the Android side
to call `VpnService.protect(fd)` on an outbound socket so its traffic bypasses
the **TUN** device (preventing a routing loop). Defined as the `ProtectCallback`
trait in `native/rust/crates/ripdpi-native-protect`; satisfied either by a JNI
callback (`ripdpi-android-vpn-protect-adapter`) or a Unix-socket fallback
(`VpnProtectSocketServer.kt`). See
[`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md)
and [`JNI_CONTRACT.md`](JNI_CONTRACT.md) §10.

**raw-path scan** — A diagnostics scan that **stops the VPN service** and
connects directly (no **TUN**), so `setsockopt(IP_TTL)` and fake-packet
techniques work without a **protect callback**. Selected by
`diagnostics_default_scan_path_mode = "raw_path"`. Contrast **in-path scan**.

**relay** — Chaining the local proxy or VPN traffic through an encrypted
transport to a server or bridge path the user configures. Current relay-kind
paths include VLESS Reality/xHTTP, Hysteria2, TUIC v5, MASQUE, ShadowTLS,
Trojan, AnyTLS, Shadowsocks, Mieru, SSH, Tor, NaiveProxy, Google Apps Script, Cloudflare
Tunnel, in-repository WebTunnel, and external Snowflake/obfs4 PT paths. Mieru is currently TCP-only; its UDP capability remains disabled. Shared
orchestration for native-wired relay backends is `ripdpi-relay-core`; the JNI
entrypoint is `libripdpi-relay.so` (crate `ripdpi-relay-android`). WARP and
AmneziaWG are separate VPN/tunnel profile surfaces. Both proxy and VPN modes
work with or without a relay.

**relay profile** — A saved, named relay endpoint plus its credentials and
transport parameters, referenced by `relay_profile_id` in `app_settings.proto`.
Profiles are created by hand or imported (QR scan, clipboard, share-sheet,
subscription). The active relay kind is the `relay_kind` string (see
[`CONFIG_CONTRACTS.md`](CONFIG_CONTRACTS.md) §5).

**root helper** — `ripdpi-root-helper`, a standalone privileged ELF binary
(crate `native/rust/crates/ripdpi-root-helper`, not a `.so`) extracted from APK
assets, launched via `su`, and reached over Unix-socket IPC. It executes
**privileged operations** on rooted devices. Opt-in behind `root_mode_enabled`;
the app must fully function on non-rooted devices without it. Lifecycle owner:
`RootHelperManager.kt`.

**runtime mode** — How RIPDPI routes traffic: **proxy mode** (a local SOCKS5
listener) or **VPN mode** (Android `VpnService` + **TUN**), set by the
`ripdpi_mode` field (`"vpn"` | `"proxy"`) in `app_settings.proto`. Diagnostics,
**relay**, and the **root helper** compose into either mode rather than being
modes themselves. See [`ARCHITECTURE.md`](ARCHITECTURE.md) §2.

**SOCKS endpoint** — The local SOCKS5 proxy listener on a configured localhost
port. In proxy mode apps connect to it directly; in VPN mode the TUN-to-SOCKS
bridge forwards device traffic into it. SOCKS protocol primitives live in
`native/rust/crates/ripdpi-socks5-core`.

**strategy** — A single packet-mutation technique. For TCP this is a chain-step
*kind* (`split`, `disorder`, `tlsrec`, `fake`, `hostfake`, `oob`, `ipfrag2`, …);
the protocol-specific implementations live in the `ripdpi-strategy-*` crates
behind the `ripdpi-strategy-trait` contract and are aggregated by
`ripdpi-strategy-registry`. A strategy is one step; a **strategy chain** is the
ordered sequence.

**strategy chain** — An ordered list of **strategy** steps applied in sequence
to a connection, each with optional per-step activation filters (round,
payload size, stream-byte range). Stored as `tcp_chain_steps` /
`udp_chain_steps` (`StrategyTcpStep[]` / `StrategyUdpStep[]`) in
`app_settings.proto`; modeled Kotlin-side by the `StrategyChain*` family in
`:core:data:model` and authorable via the `StrategyChainDsl`.

**telemetry event** — A discrete runtime event (lifecycle change, route
decision, retry, DNS failover, …) recorded into a fixed-capacity bounded ring
and drained by the Kotlin side on its ~1 Hz poll. Native event records, routing,
and drain helpers live in `native/rust/crates/android-support/src/events.rs`;
`monitor` aliases `diagnostics`, and `amneziawg` aliases the WARP-family ring.
`ripdpi-telemetry` owns the separate process-global metrics recorder. Telemetry
payloads are golden-locked contracts; no packet payloads are recorded.

**TUN** — The Android `VpnService` virtual network interface. In VPN mode its
file descriptor (from `VpnService.Builder.establish()`) is handed across JNI to
`ripdpi-tunnel-android` / `ripdpi-tunnel-core`, which run the TUN-to-SOCKS
bridge. The TUN fd is adopted by Rust on `start`; Kotlin owns the backing
`ParcelFileDescriptor`. See [`JNI_CONTRACT.md`](JNI_CONTRACT.md) §9.

**verdict** — The typed diagnostic classification of a target, produced
authoritatively by native Rust (`ripdpi-diagnostics-classification`,
`classification/diagnosis.rs`) and surfaced — not suppressed — in the UI.
The user-facing verdicts: `TRANSPARENT_WORKS` (raw path works),
`OWNED_STACK_ONLY` (works only via the app's owned TLS stack),
`NO_DIRECT_SOLUTION` (on-device mutation cannot recover it; relay required),
`IP_BLOCK_SUSPECT` (IP-level block detected). Kotlin maps a verdict to UI and
**policy memory** without re-classifying.

---

## See also

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — module map, runtime modes, control/data plane
- [`NATIVE_RUST.md`](NATIVE_RUST.md) — the crates named above, by layer
- [`JNI_CONTRACT.md`](JNI_CONTRACT.md) — native handles, protect callback, TUN fd
- [`CONFIG_CONTRACTS.md`](CONFIG_CONTRACTS.md) — strategy chains, stable identifiers, config replay
- [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) — adding strategies, relays, probes, settings
