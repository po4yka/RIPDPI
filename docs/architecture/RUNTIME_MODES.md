# Runtime Modes

The five runtime paths — **proxy**, **VPN/TUN**, **diagnostics**, **relay**, and
the optional **root helper** — and how they start, interact, and tear down.
This expands [`ARCHITECTURE.md`](ARCHITECTURE.md) §2.

Evidence is cited by file path. Native crate names follow the current taxonomy
in [`NATIVE_RUST.md`](NATIVE_RUST.md).

---

## Invariant — the non-root baseline

> **RIPDPI must fully function on non-rooted devices.** Proxy mode, VPN mode,
> diagnostics, relay, on-device packet strategies, and encrypted DNS all run
> with **no root**. The **root helper** and the privileged operations it
> unlocks (FakeRst, MultiDisorder, IP fragmentation, raw IPv4/IPv6 emission)
> are **opt-in behind `root_mode_enabled`** and **degrade gracefully** when
> root is absent. A code path that hard-requires root is a bug — see
> [AGENTS.md](../../AGENTS.md) § Project Rules.

This invariant constrains every section below.

---

## The runtime mode state model

RIPDPI's runtime state is **not a single type** — it is a small set of
explicit enums plus several *inferred* layers. This section is the canonical
map; §1–§8 elaborate each flow.

### Explicit state — the enums

| Type | Values | Owns | Defined in |
|------|--------|------|------------|
| `Mode` | `Proxy`, `VPN` | *which* runtime kind. Persisted (`@SerialName`); mutually exclusive at runtime. | `core/data/model/.../data/AppStatus.kt` |
| `AppStatus` | `Halted`, `Running` | coarse *is a runtime active*. | `core/data/model/.../data/AppStatus.kt` |
| `ServiceStatus` | `Disconnected`, `Connected`, `Failed` | finer per-supervisor connection health; projected into `AppStatus` by `RuntimeTelemetryProjection`. | `core/data/model/.../data/ServiceStatus.kt` |
| `ServiceLifecycleStateMachine.State` | `STOPPED`, `STARTING`, `RUNNING`, `STOPPING` | the service-internal start/stop phase guard. | `core/service/.../services/ServiceLifecycleStateMachine.kt` |

The **canonical runtime observable** is `ServiceStateStore.status`, a
`StateFlow<Pair<AppStatus, Mode>>` — the coarse status paired with the active
mode. `ServiceStatus` and `ServiceLifecycleStateMachine.State` are the finer,
service-internal representations that feed it.

> Not to be confused: `RipDpiHostsConfig.Mode`
> (`Disable` / `Blacklist` / `Whitelist`) is an unrelated hosts-filter config
> enum, not a runtime mode.

### Inferred state — relay, root, diagnostics

Relay, the root helper, and diagnostics are **not** `Mode` values — they are
layers and sessions whose "active" state is *inferred*:

| Layer | "Active" is inferred from |
|-------|---------------------------|
| Relay | `relay_enabled` / `relay_kind != "off"` settings + a non-zero `RipDpiRelay` / `RipDpiWarp` / subprocess handle. Composes *into* proxy or VPN (§4). |
| Root helper | the `root_mode_enabled` setting + `RootHelperManager.socketPath != null` (§5). |
| Diagnostics scan | a non-zero `NetworkDiagnostics` native handle; raw-path vs in-path is the `ScanPathMode` on the request (§3). |

### Native-runtime liveness — the handle pattern

Every engine wrapper — `RipDpiProxy`, `Tun2SocksTunnel`, `RipDpiRelay`,
`RipDpiWarp`, `NetworkDiagnostics` (`core/engine/.../core/`) — tracks
running-vs-stopped from a **private nullable native handle** (`var handle =
0L`; non-zero ⇒ a live native session). There is no per-wrapper state enum and
no public `isRunning` / `state` accessor; an invalid lifecycle call surfaces as
a `NativeError.NotRunning` / `AlreadyRunning` exception. Handle lifecycle is in
[`JNI_CONTRACT.md`](JNI_CONTRACT.md).

### Why there is no single `RuntimeMode` type

A unified sealed `RuntimeMode` — one type capturing mode + status + relay +
root + diagnostics — would read better, but `Mode` is referenced in ~140 files
and `AppStatus` in ~45; collapsing them onto a new type is broad rewiring of
every status consumer and a behavior risk to start/stop. It is therefore a
**documented future refactor**, not adopted here — the model above is the
contract to reason against today.

The safe first step now exists as `RuntimeModeProjection` plus
`RuntimeModeProjectionStore` (`core/service/.../service/runtime/`). This is a
derived, read-only view over `ServiceStateStore.status`, runtime telemetry,
root-mode settings, and diagnostics scan activity. It does not replace `Mode`,
`AppStatus`, `ServiceStatus`, or any start/stop path; deferred inputs such as
per-supervisor lifecycle phase and root-helper socket availability remain
unobserved until a low-risk read-only seam exists.

---

## 1. Proxy mode flow

Proxy mode exposes the native local SOCKS5 proxy directly on a configured
localhost port. `ripdpi_mode = "proxy"` in `app_settings.proto`.

**Entry service:** `RipDpiProxyService` (`core/service/.../services/RipDpiProxyService.kt`)
— an `androidx.lifecycle.LifecycleService` (no `VpnService`). `onCreate`
registers the notification channel and builds a `ProxyServiceRuntimeCoordinator`
(`com.poyka.ripdpi.service.runtime.proxy`); `onStartCommand` calls
`startForeground` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (API 34+) and
delegates to a `ServiceShellDelegate`.

**Start chain** (per [`docs/native/proxy-engine.md`](../native/proxy-engine.md)
§ Android Proxy mode):

```
RipDpiProxyService.startProxy()
  → ConnectionPolicyResolver.resolve()        — per-network policy / remembered config
  → RipDpiProxy.startProxy()                  — core/engine/.../core/RipDpiProxy.kt
  → jniCreate(configJson)  → native handle    — libripdpi.so (crate ripdpi-android)
  → jniStart(handle)       — BLOCKING; runs the proxy event loop on the IO dispatcher
```

The native proxy runtime is the `ripdpi-proxy-runtime` crate, linked into
`libripdpi.so`. `jniStart` is **blocking** — `RipDpiProxy` runs it under
`withContext(Dispatchers.IO)` and `yield()`s first; `jniStop` wakes the
listener and requests shutdown; `jniDestroy` retires the native handle.

**Endpoint:** proxy mode binds the user-configured `proxy_ip` / `proxy_port`
and exposes it directly. Apps that support SOCKS5 / HTTP CONNECT connect to it;
strategy mutations and (if configured) relay chaining apply to all traffic
entering the proxy.

---

## 2. VPN / TUN mode flow

VPN mode redirects **all** device traffic through RIPDPI by running the same
local SOCKS5 proxy **plus** a TUN-to-SOCKS bridge. `ripdpi_mode = "vpn"`.

**Entry service:** `RipDpiVpnService` (`core/service/.../services/RipDpiVpnService.kt`)
extends `LifecycleVpnService` (an Android `VpnService`). `onCreate` builds the
foreground-notification controller, the underlying-network binder, and the
`VpnServiceSessionLifecycle`.

**TUN device.** `RipDpiVpnService.createBuilder()` configures the
`VpnService.Builder`: `setSession("RIPDPI")`, `setMtu(1500)`
(`defaultTun2SocksTunnelMtu`), tunnel addresses `10.10.10.10/32` and (if IPv6
enabled) `fd00::1/128`, default routes `0.0.0.0/0` and `::/0`. App exclusions:
RIPDPI's own package and, per `VpnAppExclusionPolicy`, configured Russian apps;
`VpnDhtMitigationPolicy` adds `excludeRoute` entries on API 33+.
`Builder.establish()` yields the TUN `ParcelFileDescriptor`.

**Start ordering** — the proxy comes up first, then the tunnel:

```
RipDpiVpnService  → ConnectionPolicyResolver.resolve()
  1. RipDpiProxy.startProxy()    — same jniCreate/jniStart path as §1
       VPN applies runtime-only sessionOverrides: listenPortOverride=0
       (ephemeral localhost bind) + a fresh authToken (mandatory local auth)
  2. ProxyRuntimeSupervisor.start() waits for readiness, polls telemetry,
       resolves the actual ephemeral listenerAddress (fails closed if none)
  3. RipDpiVpnService.startTun2Socks()
       → Tun2SocksTunnel.start(config, tunFd)   — core/engine/.../core/Tun2SocksTunnel.kt
       → jniCreate(configJson) → jniStart(handle, tunFd)   — libripdpi-tunnel.so
       → ripdpi_tunnel_core::run_tunnel()  on a native worker thread (NON-blocking start)
```

`ripdpi-tunnel-core` reads IP packets from the TUN fd and opens SOCKS5 sessions
to `127.0.0.1:<ephemeral port>` (RFC 1929 auth with the session token) into the
proxy runtime, which applies the desync pipeline before traffic egresses.

**`libripdpi-tunnel.so` depends on `libripdpi.so` already being active** — the
tunnel forwards into the proxy's SOCKS endpoint. Stop chain:
`RipDpiVpnService.stopTun2Socks()` → `Tun2SocksTunnel.stop()` → `jniStop` →
`CancellationToken::cancel()` → worker join. See
[`docs/native/tunnel.md`](../native/tunnel.md) § App Call Chain.

**Socket protection.** Every non-loopback upstream socket the native core opens
must be passed to `VpnService.protect(fd)` before `connect`/`bind` or it loops
back into the TUN. Registered at VPN start via `VpnNativeProtectRegistration`;
see [`JNI_CONTRACT.md`](JNI_CONTRACT.md) §10 and
[`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md).

---

## 3. Diagnostics — raw-path and in-path flows

Diagnostics scans probe targets and produce typed verdicts. Driven from
`:core:diagnostics`; the native bridge is
`core/engine/.../core/NetworkDiagnostics.kt`
(`jniCreate` / `jniStartScan` / `jniPollProgress` / `jniTakeReport` /
`jniPollPassiveEvents` / `jniCancelScan` / `jniDestroy`), backed by the
`ripdpi-monitor-engine` crate linked into `libripdpi.so`. The path mode is
`diagnostics_default_scan_path_mode` in `app_settings.proto`.

**In-path scan** (`"in_path"`). Probes run **through the active proxy or VPN
path**, measuring targets exactly as the user's traffic experiences them. The
running service is left intact.

**Raw-path scan** (`"raw_path"`). The diagnostics path **stops the VPN service
before probing** and connects **directly** — no TUN. Because there is no TUN,
`setsockopt(IP_TTL)` and fake-packet techniques work **without** a protect
callback (stopping the service unregisters both protect mechanisms — see
[AGENTS.md](../../AGENTS.md) § VPN Socket Protection). Partial results are
recovered via a short grace-period poll after cancellation.

**Home composite run.** The home analysis uses the 9-stage
`HomeCompositeStageSpecs` sequence: audit first, middle raw-path stages
serially, `path_comparison` after the middle group, passive
`vpn_route_evidence` next, and `dpi_strategy` last. A failed audit stage skips
the rest, and a VPN halt mid-stage marks it FAILED — see
[AGENTS.md](../../AGENTS.md) § Home Composite Diagnostic Run.
Automatic probing/audit is unavailable when command-line settings are enabled.

Diagnostics orchestration on the service side flows through
`DefaultDiagnosticsRuntimeCoordinator` (`core/service/.../services/`).

---

## 4. Relay profile flow

A **relay** chains the local proxy or VPN traffic through an encrypted
transport to a user-configured server. Relay composes **into** proxy or VPN
mode — it is not a separate mode, and both base modes work with or without a
relay (`app_settings.proto`: `relay_enabled`, `relay_kind`, `relay_profile_id`).

- A **relay profile** is a saved endpoint + credentials + transport parameters,
  created by hand or imported (QR scan, clipboard, share-sheet, subscription).
- `relay_kind` selects the transport (`off`, `vless`, `vless_reality`,
  `hysteria2`, `chain_relay`, `masque`, `anytls`, `cloudflare_tunnel`,
  `tuic_v5`, `shadowtls_v3`, `trojan`, `shadowsocks`, `mieru`, `ssh`, `naiveproxy`, `tor`,
  `google_apps_script`, `snowflake`, `webtunnel`, `obfs4`). Kotlin resolves it through the
  `*RelayKindResolver` classes + `RelayKindResolverRegistry` in
  `core/service/.../services/`.
- JNI-embedded relays run in `libripdpi-relay.so` (crate `ripdpi-relay-android`,
  bridge `core/engine/.../core/RipDpiRelay.kt`); shared orchestration is
  `ripdpi-relay-core` for the native-wired backends. WARP runs in
  `libripdpi-warp.so` (`ripdpi-warp-android` / `RipDpiWarp.kt`). NaiveProxy,
  Cloudflare-origin publish helper, and WebTunnel run as in-repository helper
  subprocesses; Snowflake and obfs4 run as external pluggable-transport
  binaries supervised by service code. Snowflake remains the external Go
  `ripdpi-snowflake` binary by decision.
- When no relay is configured, traffic exits the device directly and only the
  on-device packet strategies change the path.

See [`docs/relay-profile-examples.md`](../relay-profile-examples.md) and
[`docs/native/README.md`](../native/README.md) § Relay Transport Expansion.

---

## 5. Root-helper optional flow

The root helper is **opt-in** and **only** starts when `root_mode_enabled` is
set. It unlocks privileged raw-socket operations; without it the affected
strategies degrade (see the [non-root invariant](#invariant--the-non-root-baseline)).

**Lifecycle** (`core/service/.../services/RootHelperManager.kt`,
`RootDetector.kt`; [`docs/packet-strategy-runtime.md`](../packet-strategy-runtime.md)
§ Root Helper Lifecycle):

```
Service → RootHelperManager.ensureStarted(rootModeEnabled)
  → extract ripdpi-root-helper binary from APK assets
  → launch via `su` (tries `su -c`, then `su 0 sh -c`)
  → poll the Unix socket for readiness (session-nonce guarded)
  → publish rootHelperSocketPath ONLY after the socket accepts a connection
```

The path is then passed to native code as `rootHelperSocketPath` in
`Tun2SocksConfig` (proxy mode uses `RipDpiProxyUIPreferences.rootHelperSocketPath`).
`ripdpi-runtime-platform` checks `with_root_helper()` per privileged operation
and **falls back to a local non-privileged attempt** when no helper is
registered. Privileged primitives live in `ripdpi-privileged-ops`; the IPC
protocol (`CMD_*`) is `ripdpi-root-helper-protocol`. The helper is a standalone
ELF binary (crate `ripdpi-root-helper`), **not** a `.so`.

Shutdown is bounded — `RootHelperManager.stop()` (called from both
`RipDpiVpnService.onDestroy` and `RipDpiProxyService.onDestroy`) asks the helper
to stop, waits briefly, and force-kills only if it does not exit.

---

## 6. Encrypted DNS / TUN interaction (high level)

When `dns_mode = "encrypted"`, VPN mode intercepts DNS inside the tunnel rather
than leaking plaintext UDP/53 (see [`docs/native/tunnel.md`](../native/tunnel.md)
§ DNS interception flow):

- `RipDpiVpnService.buildTun2SocksConfig()` enables a **MapDNS** listener at
  `198.18.0.53:53` with a synthetic `198.18.0.0/15` address pool and passes the
  active encrypted-resolver definition (DoH / DoT / DNSCrypt / DoQ) into
  `Tun2SocksConfig`.
- `ripdpi-tunnel-core` routes DNS queries to MapDNS, resolves them through the
  shared encrypted resolver, allocates a synthetic IP, caches the
  real↔synthetic mapping (LRU), and returns the synthetic IP to the app.
- Follow-up traffic to a synthetic IP is rewritten back to the real upstream
  before a SOCKS session opens. Active TCP sessions **pin** their cache entry
  against LRU eviction so a long-lived connection cannot lose its mapping.
- The active resolver may come from current settings **or** from a validated
  remembered VPN-only DNS policy replayed for the current network.
- Proxy mode (no TUN) does not run MapDNS; encrypted DNS there is handled in
  the proxy/diagnostics resolver paths.

DNS failover (catastrophic resolver errors → alternate resolvers) is summarized
in [AGENTS.md](../../AGENTS.md) § DNS Resolver Resilience.

---

## 7. Lifecycle transition summary

`BaseServiceRuntimeCoordinator` (`core/service/.../services/ServiceRuntimeCoordinator.kt`)
owns shared lifecycle sequencing, restart/backoff, and stop/start orchestration;
`ProxyServiceRuntimeCoordinator` and `VpnServiceRuntimeCoordinator` implement
mode-specific orchestration through concrete subclasses and composed collaborators
(see [`architecture/README.md`](README.md) § Ownership Boundaries).

| Transition | What happens |
|------------|--------------|
| **Start (proxy)** | `onStartCommand` → `startForeground` (≤5 s) → `ConnectionPolicyResolver.resolve()` → `RipDpiProxy.startProxy()` → blocking `jniStart` |
| **Start (VPN)** | `startForeground` → resolve policy → start proxy (ephemeral port + fresh auth token) → `ProxyRuntimeSupervisor` waits for the listener address → `startTun2Socks()` |
| **Network handover** | `NetworkHandoverMonitor` → **full proxy+tunnel restart under the service mutex**; auth token and ephemeral port rotate. A DNS-only rebuild reuses the existing endpoint (proxy not restarted). |
| **Raw-path diagnostics** | VPN service is **stopped** before probing, then reconstructed afterward; protect mechanisms unregister/re-register across the boundary |
| **Revoke (VPN)** | `onRevoke()` sets `revoked = true` and runs the shell-delegate revoke path; `onDestroy` tears the session down |
| **Stop / destroy** | `onDestroy` → coordinator/session teardown → `RootHelperManager.stop()` → native `jniStop`/`jniDestroy` |
| **Process death (LMK)** | No Drop runs; state must already be persisted (see [`.claude/rules/android-vpn-lifecycle.md`](../../.claude/rules/android-vpn-lifecycle.md)). Sticky restart aborts if the notification permission was revoked. |

---

## 8. Mode interaction risks

- **Raw-path diagnostics interrupts the VPN.** A `raw_path` scan stops the VPN
  service mid-session and reconstructs it after. A scan racing a handover or a
  user stop must order teardown/rebuild carefully; partial results rely on the
  grace-period poll.
- **Proxy and VPN are mutually exclusive at runtime.** Switching modes requires
  a full teardown of one service before the other starts — there is one
  foreground service / one `VpnService` session at a time.
- **Tunnel depends on the proxy.** `libripdpi-tunnel.so` forwards into
  `libripdpi.so`'s SOCKS endpoint; the proxy must be ready (and its ephemeral
  listener address resolved) before `startTun2Socks()`. VPN startup **fails
  closed** if the proxy never publishes a listener address.
- **`protect()` is mandatory in VPN mode.** Any upstream socket opened without
  protection loops into the TUN with exponential traffic growth. Raw-path scans
  are exempt only because they connect with no TUN present.
- **Handover restart rotates the localhost contract.** The ephemeral proxy port
  and auth token change on a full restart; any component caching the old
  endpoint must re-read it. DNS-only rebuilds intentionally keep the endpoint.
- **Relay failure must not kill the base path.** Relay composes into proxy/VPN;
  a relay transport fault should surface as a relay error, not tear down the
  proxy or VPN session.
- **Root helper is per-service and best-effort.** It is started and stopped
  with the owning service; if it fails to come up, privileged strategies must
  degrade, never block startup ([non-root invariant](#invariant--the-non-root-baseline)).
- **`jniStart` blocking contract differs.** The proxy `jniStart` occupies its
  thread (run on `Dispatchers.IO`); the tunnel `jniStart` returns after worker
  launch. Confusing the two stalls the runtime — see [`JNI_CONTRACT.md`](JNI_CONTRACT.md) §12.

---

## Cross-references

| Topic | Source |
|-------|--------|
| Architecture overview, control/data plane | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Domain terms (verdict, probe, TUN, protect callback, …) | [`GLOSSARY.md`](GLOSSARY.md) |
| JNI boundary, handle lifecycle, protect callback | [`JNI_CONTRACT.md`](JNI_CONTRACT.md) |
| Proxy engine internals | [`docs/native/proxy-engine.md`](../native/proxy-engine.md) |
| TUN-to-SOCKS tunnel internals | [`docs/native/tunnel.md`](../native/tunnel.md) |
| TUN-egress packet strategies + root-helper lifecycle | [`docs/packet-strategy-runtime.md`](../packet-strategy-runtime.md) |
| Diagnostics surface, home composite run, DNS resilience | [`AGENTS.md`](../../AGENTS.md) |
| Adding a runtime feature safely | [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) |
