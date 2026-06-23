# VPN Socket-Protection & TUN-Recursion Audit

**Commit:** `33c5885d8` (== `main`) · **Date:** 2026-06-23
**Scope:** Every outbound socket-creation site across the RIPDPI native Rust crates and the Kotlin protect-server lifecycle, audited against the `VpnService.protect()` invariant (loopback-skip, protect-before-connect/bind, fail-closed, VPN/proxy mode-gating) for both the in-process registry path and the `ripdpi-subprocess-protect` UDS/SCM_RIGHTS helper path.

## §0 Auditor's reconciliation — GOVERNS THE VERDICT (supersedes §Executive verdict below)

> The multi-agent workflow that produced the body of this report rated the two
> diagnostics probes as **P0 "live TUN-recursion loops"** and rated the ~10
> standalone relay transports as **P2 "defense-in-depth only, saved by app-UID
> exclusion."** Those two ratings are **mutually inconsistent**, and a direct
> source check resolves the inconsistency in favour of the lower severity.

**Corrected verdict: `Partially safe`.** There is **no routing state in which an
unprotected in-process socket actually loops back into RIPDPI's own TUN**, so the
"live exponential loop / P0" framing is over-stated. What is real is an
**inconsistent per-socket `protect()` coverage** that breaches the *letter* of the
project's own invariant rule and rests the hard guarantee on a single OS-level
backstop. Fix it, but it is not a ship-blocking emergency.

**Why the P0 is over-stated — the own-UID exclusion is uniform.**
`computeAppRoutingPlan` (`core/service/.../VpnAppExclusionPolicy.kt:62-89`) adds
RIPDPI's **own package** to `addDisallowedApplication` in **every** branch
(full-tunnel `:70`, include-empty `:75`, exclude `:82`, off `:86`; allow-only `:77`
omits own-package so it bypasses too), and `shouldExcludeOwnPackage()` is a
hard-coded `true` (`:152`). The comment at `:55` states this exists "to avoid
self-loop." `addDisallowedApplication` is **UID-scoped** — it excludes the app's
entire UID (and same-UID child helpers) from the TUN at the OS routing layer.
Therefore:

- The diagnostics probes and the relay transports are **the same class** of
  in-process, same-UID socket. The own-UID exclusion that the report uses to
  spare the relay family **applies identically to the diagnostics probes.** The
  report cannot exempt one and not the other.
- A diagnostics scan runs either **VPN-up** (own-UID excluded → egress on the
  underlying network, no loop) or **VPN-down** (no TUN exists → no loop). In
  **neither** state does the unprotected socket loop. The claimed "kernel
  captures their sockets back into the TUN" requires the app's own UID to be
  *routed into* the TUN, which `computeAppRoutingPlan` never does.

**Why it is still a real (non-emergency) gap — P1/P2, fix recommended.**

1. **The project's own governing contract treats `protect()` as load-bearing.**
   `.claude/rules/vpnservice-protect-invariant.md` mandates a per-socket
   `protect_socket(fd)` before every non-loopback connect/bind and calls any
   missing one a *CRITICAL finding* — it does **not** sanction
   `addDisallowedApplication(own)` as a substitute. Under the letter of that rule,
   the unprotected **diagnostics probes AND the ~10 relay transports are ALL
   violations of equal class.** The report under-reports by excusing the relay
   family.
2. **Coverage is genuinely inconsistent** (confirmed): the `socket_protector`
   seam exists in `ripdpi-xhttp` and `ripdpi-vless` only — **not** in
   `ripdpi-relay-tls-transports`, `ripdpi-trojan`, `ripdpi-shadowtls`, or
   `ripdpi-diagnostics-*`. So some families protect-before-connect and others
   rely solely on UID exclusion.
3. **The hard guarantee should not rest on UID exclusion alone.** It is one
   routing-plan refactor (`shouldExcludeOwnPackage()` → `false`, an allow-only
   plan that includes own-package, or an OEM `addDisallowedApplication` quirk)
   away from turning every unprotected site into a real loop. Defense-in-depth is
   the whole point of the invariant rule.

**Corrected severity for the two diagnostics probes: `P1`** (not P0) — a clear
`protect()`-coverage regression: their *sibling* transfer probes route through a
protect-aware `TransportConfig` (per the report's diagnostics finding), so the
intent to protect is established and these two bypass it. They do not loop today
(own-UID exclusion), so they are not P0. The ~10 standalone relay transports
remain `P2` (same root cause, never had the seam — lower priority).

**Residual uncertainty (honest):** I assert from Android platform semantics that
`addDisallowedApplication(ownPackage)` reliably keeps the app's own-UID sockets
off the TUN. If the team's lived experience is that this is *unreliable* on some
OEM/Android-version (which would explain why they built the entire `protect()` +
`subprocess-protect` stack rather than relying on UID exclusion), then the
diagnostics probes — and equally the relay transports — escalate back toward
P0/P1, and the verdict moves to `High-risk`. Either way, the **fix is the same**
(thread `socket_protector` into the diagnostics probes and the standalone relay
builders) and the **report's selective P0/P2 split is not defensible.** I'd ask
the maintainer to confirm the project's intended reliance on UID exclusion vs.
`protect()` before finalizing the severity.

> The rest of this report (the verified socket inventory across ~50 call sites,
> the soundness verdicts on the in-process registry, JNI bridge, subprocess
> SCM_RIGHTS mechanism, Kotlin lifecycle/env-gating, WARP/AmneziaWG, ws-tunnel,
> xHTTP family, and the three subprocess helpers) was independently verified and
> stands. Only the P0 severity labels and the High-risk top-line are corrected by
> this §0.

---

## Executive verdict (workflow synthesis — SUPERSEDED by §0)

**High-risk.** *(workflow's original rating; corrected to `Partially safe` in §0)*

Two reachable **P0** TUN-recursion fail-opens exist in the diagnostics subsystem: the Telegram WS-tunnel probe (`ws_tls.rs:77-87`) and the Telegram DC reachability probe (`dc.rs:42`) both connect Direct + UNPROTECTED to non-loopback Telegram endpoints, and both run during an in-path (VPN-up) diagnostics scan where the protect callback is live and the TUN owns the route. The two probes bypass the protect-aware `TransportConfig` path their sibling transfer probes use, so the kernel captures their sockets back into RIPDPI's own TUN → exponential packet loop — exactly the failure the invariant exists to prevent. Everything else is sound: the in-process registry, the JNI bridge, the subprocess SCM_RIGHTS mechanism, the Kotlin lifecycle/env-gating, WARP/AmneziaWG, ws-tunnel, the xHTTP relay family, and the three subprocess helpers all protect-before-connect and fail closed where required. The large family of standalone relay transports (shadowsocks/trojan/anytls/shadowtls/mieru/ssh/tuic/hysteria2/masque) connect unprotected but are a defense-in-depth gap, not a live loop, because RIPDPI's own app UID is always excluded from the TUN by `computeAppRoutingPlan`. The two P0s must be fixed before ship; the relay/UID-exclusion divergence and stale comments are P2/P3 follow-ups.

| Severity | Count |
|----------|-------|
| P0 | 2 |
| P1 | 0 |
| P2 | 4 |
| P3 | 14 |

## Socket protection matrix

Grouped by family. Duplicates across the inventory sweep and subsystem finders collapsed to one row.

### In-process registry data plane (sound)

| Socket family | Crate / file:line | Dest | Mode | Protect path | Timing | Fail mode | Verdict |
|---|---|---|---|---|---|---|---|
| Tunnel TCP/UDP session | `ripdpi-tunnel-core` `session/tcp.rs:88-92`, `session/udp.rs:55-56` | conditional | vpn | in-process registry | protect-before | fail-closed | OK |
| DNS-intercept connect/bind | `ripdpi-tunnel-core` `io_loop/dns_intercept/protect_hooks.rs:25-42` | conditional | vpn | callback→UDS | protect-before | fail-closed (refuses if no mechanism under TUN) | OK |
| xHTTP / VLESS / VLESS-Reality / Cloudflare relay | `ripdpi-xhttp` `connect.rs:96-128` | remote | both | in-process registry | protect-before (+ protect-before-DNS) | fail-closed | OK (gold standard) |
| VLESS connect | `ripdpi-vless` `lib.rs:152-196` | remote | vpn | in-process registry | protect-before | fail-closed (Err if no callback for non-loopback) | OK |
| DoQ UDP binder | `ripdpi-android-platform-adapter` `doq.rs:108-117` | remote | both | in-process registry | bind-then-protect (pre-send) | fail-closed (`?`) | OK |
| In-app HTTP fetch | `ripdpi-android-fetch-adapter` `socket_protection.rs:38-54` | remote | both | in-process registry | protect-before | no-op off-TUN | OK |
| DNS direct TCP | `ripdpi-dns-resolver` `resolver/tcp.rs:22-30` | conditional | vpn | in-process registry | protect-before | fail-closed when require-flag set | OK |
| proxy-runtime adapter | `ripdpi-proxy-runtime-adapter` `platform.rs:50-73` | remote | both | in-process registry | protect-before | fail-closed | OK (gated on `protect_path=Some`, set by proxy-runtime settings) |
| runtime-platform facade | `ripdpi-runtime-platform` `vpn_protect.rs:15-21` | conditional | both | callback-first / UDS / no-op | protect-before | fail-closed | OK |

### WARP / AmneziaWG (sound)

| Socket family | Crate / file:line | Dest | Mode | Protect path | Timing | Fail mode | Verdict |
|---|---|---|---|---|---|---|---|
| WG UDP tunnel socket | `ripdpi-warp-core` `wireguard/socket.rs:14-21` | remote | vpn | in-process registry | bind-then-protect (pre-send) | fail-closed (drops fd) | OK |
| WARP endpoint probe socket | `ripdpi-warp-core` `endpoint_probe.rs:129-141` | remote | both | in-process registry | bind-then-protect | fail-closed | OK |
| WARP provisioning connect | `ripdpi-warp-android` `provisioning.rs:195-217` | remote | vpn | in-process registry | protect-before | fail-closed | OK |
| WG-over-WS carrier (literal + hostname) | `ripdpi-wireguard-ws` `connect.rs:103-111, 159-192` | remote | vpn | in-process registry | protect-before (both v4/v6 before resolve) | fail-closed | OK |
| AWG carrier protector seam | `ripdpi-amneziawg-android` `carrier_protect.rs:71-74` | remote | vpn | in-process registry | protect-before | fail-closed | OK (note WAWG-1: prod uses `WarpPlatform::carrier_protector()` not this seam) |
| WARP/AWG loopback SOCKS listener | `ripdpi-warp-core` `socks.rs:99 + runtime.rs:179-181` | loopback | both | n/a (loopback-guarded) | n/a | n/a | OK |
| smoltcp virtual sockets | `ripdpi-warp-core` `virtual_iface/socket_factory.rs:18-31` | n/a | both | n/a (no kernel fd) | n/a | n/a | OK |

### Subprocess helpers + contract crate (sound)

| Socket family | Crate / file:line | Dest | Mode | Protect path | Timing | Fail mode | Verdict |
|---|---|---|---|---|---|---|---|
| naiveproxy upstream | `ripdpi-naiveproxy` `connect_tunnel.rs:29-31` | remote | vpn | subprocess UDS | protect-before | fail-closed (ack≠0) | OK |
| webtunnel upstream (sync+async) | `ripdpi-webtunnel` `client.rs:38, 61` | remote | both | subprocess UDS | protect-before | fail-closed | OK |
| cloudflare-origin upstream | `ripdpi-cloudflare-origin` `session.rs:25-27` | conditional | vpn | subprocess UDS | protect-before | fail-closed | OK |
| SCM_RIGHTS client (async+blocking) | `ripdpi-subprocess-protect` `lib.rs:58-122` | conditional | vpn | subprocess UDS | protect-before (`:120`/`:143` before connect `:121`/`:144`) | fail-closed (ack≠0; no-op when path unset) | OK |
| helper SOCKS/HTTP/xHTTP listeners | naiveproxy `relay.rs:16`, webtunnel `pt.rs:146`, cloudflare-origin `http_server.rs:45` | loopback | both | n/a | n/a | n/a | OK |

### ws-tunnel (sound)

| Socket family | Crate / file:line | Dest | Mode | Protect path | Timing | Fail mode | Verdict |
|---|---|---|---|---|---|---|---|
| Telegram WS carrier TCP | `ripdpi-ws-tunnel` `connect.rs:68-97` | remote | both | UDS-first else callback | protect-before | fail-closed (ack≠0) | OK |
| callback fallback / UDS sender | `ripdpi-ws-tunnel` `protect.rs:52-60`, `protect.rs:9-36` | conditional | both | in-process / UDS | protect-before | fail-closed | OK |
| ws relay (try_clone) | `ripdpi-ws-tunnel` `relay.rs:47-49` | loopback | both | n/a (reuses protected fd) | n/a | n/a | OK |

### Relay carrier sockets — UID-exclusion-only (defense-in-depth gap, P2/P3)

| Socket family | Crate / file:line | Dest | Mode | Protect path | Timing | Fail mode | Verdict |
|---|---|---|---|---|---|---|---|
| Shadowsocks TCP/UDP | `ripdpi-relay-tls-transports` `shadowsocks.rs:242-263` | remote | vpn | none (UID exclusion) | no-protect | n/a | RISK (REL-1) |
| Trojan TCP/UDP | `ripdpi-trojan` `lib.rs:258, 280` | remote | both | none | no-protect | n/a | RISK (REL-1) |
| AnyTLS | `ripdpi-anytls` `session.rs:253` | remote | both | none | no-protect | n/a | RISK (REL-1, stale comment REL-3) |
| ShadowTLS | `ripdpi-shadowtls` `client.rs:43` | remote | both | none | no-protect | n/a | RISK (REL-1, stale comment REL-3) |
| Mieru | `ripdpi-relay-tls-transports` `mieru.rs:74` | remote | both | none | no-protect | n/a | RISK (REL-1, TODO REL-4) |
| SSH | `ripdpi-ssh` `lib.rs:14-26` | remote | both | none | no-protect | n/a | RISK (REL-1; comment correct) |
| TUIC QUIC UDP | `ripdpi-tuic` `endpoint.rs:53-68` | remote | vpn | none | no-protect | n/a | RISK (REL-2) |
| Hysteria2 QUIC UDP | `ripdpi-hysteria2` `quic_transport/endpoint.rs:30-43`, `salamander.rs:23` | remote | vpn | none | no-protect | n/a | RISK (REL-2) |
| MASQUE QUIC + H2 fallback | `ripdpi-masque` `h3/socket.rs:14-16`, `h2.rs:72,148` | remote | vpn | none (only ECH-DNS bound) | no-protect | n/a | RISK (REL-2) |
| Apps-Script domain fronter | `ripdpi-apps-script-core` `domain_fronter/tls.rs:39` | remote | both | none | no-protect | n/a | RISK (UID exclusion; serves loopback SOCKS) |
| Relay loopback SOCKS / UDP-associate | `ripdpi-relay-core` `runtime.rs:97`, `socks/udp.rs:64` | loopback | both | n/a | n/a | n/a | OK |

### Diagnostics

| Socket family | Crate / file:line | Dest | Mode | Protect path | Timing | Fail mode | Verdict |
|---|---|---|---|---|---|---|---|
| Direct probe connect/bind | `ripdpi-diagnostics-transport` `transport/protect.rs:39-53`, `route_experiment/{tcp,udp}.rs` | conditional | both | in-process registry | protect-before | fail-closed (no-op off-TUN) | OK |
| **Telegram WS-tunnel probe** | `ripdpi-diagnostics-transport` `ws_tls.rs:77-87` | **remote** | **vpn/in-path** | **none** | **no-protect** | **fail-OPEN** | **RISK (DIAG-1, P0)** |
| **Telegram DC reachability probe** | `ripdpi-diagnostics-telegram` `telegram/dc.rs:42` | **remote** | **vpn/in-path** | **none** | **no-protect** | **fail-OPEN** | **RISK (DIAG-2, P0)** |
| Dormant probes-crate runners | `ripdpi-diagnostics-probes` `mtproto_reachability.rs:245`, `hickory_rustls_ech_driver.rs:154`, `throughput.rs:124` | remote | unknown | none | no-protect | fail-open | RISK (DIAG-3, P2 — not live-dispatched) |
| SOCKS-relay UDP bind | `ripdpi-diagnostics-transport` `transport/socks5.rs:185-188` | loopback | proxy | n/a (loopback proxy) | n/a | n/a | OK (DIAG-4 narrow note) |
| TCP-Fast-Open capability probe | `ripdpi-diagnostics-candidates` `candidates/platform.rs:28` | n/a | n/a | n/a (no connect) | n/a | n/a | OK |

### Protect mechanism (not socket sites)

| Socket family | Crate / file:line | Role | Fail mode | Verdict |
|---|---|---|---|---|
| JNI protect callback | `ripdpi-android-vpn-protect-adapter` `protect_callback.rs:24-45` | in-process protect impl | fail-closed (`protect()==false`→PermissionDenied) | OK |
| Registry dispatch | `ripdpi-native-protect` `lib.rs:142-148` | generation-guarded slot | fail-closed (empty→NotConnected) | OK |
| UDS control socket | `ripdpi-subprocess-protect` `lib.rs:64` | AF_UNIX filesystem path | fail-closed (Err on dead server) | OK (never enters IP/TUN stack) |

## Findings

### P0

#### **[DIAG-1] Telegram WS-tunnel diagnostics probe connects Direct + UNPROTECTED in VPN/in-path mode** (P0)
`native/rust/crates/ripdpi-diagnostics-transport/src/ws_tls.rs:77-87`

**Evidence:** `connect_tcp` does `match timeout { Some(timeout) => TcpStream::connect_timeout(&addr, timeout), None => TcpStream::connect(addr) }` with no protect call anywhere in the 163-line file (verified: zero `protect` / `has_protect_callback` / `VpnService` references). Reached via `telegram/report.rs:18` → `ws_tunnel.rs:24-25` `WsOverTlsConnector.probe_with_key_log(&telegram_ws_target(resolved_addr), ...)` → `connect_with_key_log` → `connect_tcp`, targeting `kws2.web.telegram.org:443` (`ws_tunnel.rs:8-10`), a non-loopback host. By contrast the sibling transfer probes (`transfer.rs`) reach the network through `TransportConfig::Direct` → `transport/tcp.rs:75,96` → `transport/protect.rs:29-44` `protected_tcp_connect`, which protects the fd before connect; the WS probe bypasses `TransportConfig` entirely and uses a raw `TcpStream`.

**Impact:** `TelegramRunner` (`monitor-engine engine/runners/connectivity/telegram.rs:24-42`) runs whenever `plan.request.telegram_target.is_some()`, with no `path_mode` gate. Per `RUNTIME_MODES.md:186-188` an in-path scan keeps the VPN service (and its registered protect callback) intact, so this non-loopback socket is captured by RIPDPI's own TUN route → the exponential packet loop the invariant exists to prevent.

**Repro/test:** In-path (VPN-up) diagnostics scan with a `telegram_target` set; the `telegram_availability` `wsTunnel` sub-probe connects unprotected. Static: `grep -n 'TcpStream::connect' native/rust/crates/ripdpi-diagnostics-transport/src/ws_tls.rs` has no paired protect.

**Confidence:** high (re-verified against source bytes during synthesis).

> **Verifier verdict (upheld, P0):** Bug is real and reachable in VPN/in-path mode; caller chain, non-loopback target, and live protect callback all confirmed. The finding's secondary claim that commit `7bc3bac46`'s "WS-tunnel protect is dead/absent" is a misattribution — that commit touched the `ripdpi-ws-tunnel` crate (which it did protect) and never claimed to touch `ws_tls.rs`; the core defect stands. Verifier also notes an identical unprotected `TcpStream::connect_timeout` at `dc.rs:42` (tracked as DIAG-2).

#### **[DIAG-2] Telegram DC reachability probe connects Direct + UNPROTECTED in VPN/in-path mode** (P0)
`native/rust/crates/ripdpi-diagnostics-telegram/src/telegram/dc.rs:42`

**Evidence:** `dc.rs` imports `std::net::TcpStream` (`:1`) and `dc.rs:42` does `TcpStream::connect_timeout(&addr, dc_timeout)` where `addr = SocketAddr::new(ip, port)` (`:40`) over ports `{configured, 443, 80}` (`:6-7, 66-74`). Targets are gated to genuine Telegram DC IPs (`:30-36` via `ripdpi_ws_tunnel::classify_target` — `149.154.*` / `91.108.*`, non-loopback), so the loopback-skip exemption does not apply. There is zero protect call in the file. `report.rs:17` calls `telegram_dc_probe(target)` with NO transport argument, unlike the download/upload probes at `report.rs:15-16` which thread `transport` into `open_probe_stream` (`transfer.rs:120/241`) → `diagnostics-transport tcp.rs:75/96/106` `protected_tcp_connect` → `protect.rs:29-44` (fd protected before connect when a callback is registered and target is non-loopback).

**Impact:** Same TUN-loop fail-open as DIAG-1. `telegram.rs:34-40` runs `record_telegram_probe` whenever `telegram_target.is_some()`; the protect callback is registered process-globally by the VPN data-plane crates, so during an in-path scan the siblings protect while the DC probe does not.

**Repro/test:** In-path diagnostics scan with `telegram_target.dc_endpoints`; the `dcReachable` sub-probe connects unprotected to Telegram DC IPs. Static: `grep -n 'TcpStream::connect' native/rust/crates/ripdpi-diagnostics-telegram/src/telegram/dc.rs`.

**Confidence:** high (re-verified against source bytes during synthesis).

> **Verifier verdict (upheld, P0):** Confirmed unprotected non-loopback connect that loops into the TUN whenever the VPN tunnel is up. One framing correction: the genuine TUN-loop window is when the transport is **Direct** (RawPath, or InPath without a configured proxy) while the VPN is up — not the SOCKS5 in-path case (the SOCKS path deliberately traverses the tunnel). Because the DC probe is Direct in *every* mode, the P0 conclusion holds.

### P1

_None._

### P2

#### **[REL-1] Relay outbound carrier sockets (shadowsocks/trojan/anytls/shadowtls/mieru/ssh) rely solely on UID routing exclusion** (P2, unverified)
`ripdpi-trojan/src/lib.rs:258`, `ripdpi-anytls/src/session.rs:253`, `ripdpi-shadowtls/src/client.rs:43`, `ripdpi-relay-tls-transports/src/{shadowsocks.rs:245, mieru.rs:74}`

**Evidence:** The standalone backend builders (`backend/builder/builders/*.rs`) drop both `context.outbound_bind_ip` and `context.socket_protector`; their session factories open a bare `TcpStream::connect((server, port))` to a non-loopback relay. Protection is provided only by UID-level routing exclusion: `computeAppRoutingPlan` always excludes `ownPackage` from the TUN (`VpnAppExclusionPolicy.kt:70,75,82,86`; AllowOnly omits `ownPackage` at `:77`; pinned by `VpnAppExclusionPolicyTest.kt:118,151,166,181`). `vpnservice-protect-invariant.md` and `JNI_CONTRACT §10` state protect is mandatory "No exceptions," and the rule's own audit grep would flag all of these as CRITICAL. WARP — the structurally identical in-process data plane — instead protects every non-loopback socket (`warp-core/src/platform.rs:70-73`, `wireguard/socket.rs:19`).

**Impact:** Latent, not a live loop. If the UID-exclusion assumption ever regresses (split-tunnel refactor, an OEM ignoring `addDisallowedApplication`, or a future per-process relay UID), every one of these transports silently loops into the TUN with exponential growth, while WARP stays safe. The divergence also defeats the documented audit by filling the invariant grep with accepted false-positives.

**Repro/test:** Set `relay_kind=trojan` (or anytls/shadowtls/mieru/ss), blank `relay_outbound_bind_ip`, VPN mode: the carrier connect runs with no protect; remove `addDisallowedApplication(ownPackage)` and the loop fires. `git -C … grep -n 'socket_protector' native/rust/crates/ripdpi-relay-core/src/backend/builder/builders/` — only cloudflare_tunnel/vless/vless_reality consume it.

**Confidence:** high.

#### **[REL-2] QUIC relay carrier sockets (Hysteria2 / TUIC / MASQUE) bind wildcard 0.0.0.0:0 unprotected; masque builder binds only the ECH-DNS lookup** (P2, unverified)
`ripdpi-hysteria2/src/quic_transport/endpoint.rs:30-43`, `ripdpi-masque/src/{h3/socket.rs:14-16, h2.rs:72,148}`

**Evidence:** `build_hysteria2` and `build_tuic` pass neither `outbound_bind_ip` nor `socket_protector`. `build_masque` (`masque.rs:10,27-29`) threads `outbound_bind_ip` ONLY into `resolve_ech_config_via_encrypted_dns` (the DoH/DoT lookup), NOT the QUIC carrier: the QUIC socket is built by the shared `build_client_udp_socket`, binding `(UNSPECIFIED,0)` (`endpoint.rs:31-41`) with no protect; the MASQUE H2 fallback (`h2.rs:72,148`) is a bare `TcpStream::connect`. Descriptors mark hysteria2/masque `supports_outbound_bind_ip:false`.

**Impact:** Same latent loop as REL-1 for the QUIC/UDP carriers. MASQUE is worse-documented because the builder *looks* like it threads the bind IP (it only binds the ECH side-channel), inviting a reviewer to assume the carrier is bound.

**Repro/test:** `relay_kind=hysteria2|tuic_v5|masque`, VPN mode: the quinn UDP endpoint binds `0.0.0.0:0` unprotected; safe only via app-UID TUN exclusion.

**Confidence:** high.

#### **[DIAG-3] Dormant probes-crate runners (mtproto/ech/throughput) connect Direct + unprotected but are not live-dispatched** (P2, unverified)
`ripdpi-diagnostics-probes/src/{mtproto_reachability.rs:245, hickory_rustls_ech_driver.rs:154, throughput.rs:124}`

**Evidence:** Each does a `tokio::net::TcpStream::connect` to DC/host targets with no protect. Grep shows NO live dispatch of `MtprotoReachabilityRunner` / `EchHandshake` / probes-crate `ThroughputRunner` in `ripdpi-diagnostics-runner/src` or `ripdpi-monitor-engine/src` — only feature-contract-harness tests reference them. The live engine throughput path uses the protect-aware `measure_throughput_window` in the runner crate (`endpoint/throughput.rs:17`).

**Impact:** Not reachable in a live VPN-mode scan today (no current TUN loop), but landmines: wiring any of these into the runner registry without routing through `connect_transport_observed` reintroduces a DIAG-1/DIAG-2-class fail-open.

**Repro/test:** `grep -rn 'MtprotoReachabilityRunner|EchHandshake|ThroughputRunner::measure'` under runner/engine src returns no non-test caller.

**Confidence:** high.

#### **[WS-2] diagnostics Telegram WS probe (duplicate surface of DIAG-1)** (P2, unverified)
`ripdpi-diagnostics-transport/src/ws_tls.rs:79-80`

This is the same call site reported as the P0 **DIAG-1** by the diagnostics finder. The ws-tunnel finder flagged it as "out of ws-tunnel scope, medium confidence, for the diagnostics auditor to confirm." The diagnostics auditor confirmed it and the verifier upheld it at **P0** — see DIAG-1 above. Retained here only to note the cross-subsystem overlap; the operative severity is **P0 (DIAG-1)**.

### P3

#### **[PC-1] `has_protect_callback()` swallows lock poison (fail-open) while `protect_socket_via_callback()` panics on it** (P3, unverified)
`ripdpi-native-protect/src/lib.rs:150-152`. `has_protect_callback()` is `PROTECT_CB.read().is_ok_and(...)` → returns `false` on poison; consumers gate `if !has_protect_callback() { return Ok(()) }`, so a poisoned lock would connect UNPROTECTED. `protect_socket_via_callback()` (`:143`) does `.read().expect(...)` → panics on the same condition. Poison is unreachable today (write critical sections are infallible assignments; the callback runs under a read guard, whose panics don't poison). Theoretical only; worth aligning both to the same fail-closed poison policy.

#### **[PC-2] TOCTOU between `has_protect_callback()` and `protect_socket_via_callback()` in consumers — benign** (P3, unverified)
`ripdpi-native-protect/src/lib.rs:142-152`. An unregister landing between the check and the call makes the call return `Err(NotConnected)`, which FAILS the connect rather than allowing an unprotected one. The reverse race protects an extra socket. No path to an unprotected non-loopback connect; documented so the two-step is not mistaken for a fail-open.

#### **[SUBP-IPv6] IPv4-mapped IPv6 loopback (`::ffff:127.0.0.1`) treated as non-loopback — over-protects (fail-safe)** (P3, unverified)
`ripdpi-subprocess-protect/src/lib.rs:90`. `protect_if_needed` gates on `!addr.ip().is_loopback()`; std `Ipv6Addr::is_loopback()` is true only for `::1`, so `::ffff:127.0.0.1` reports non-loopback and a protect round-trip is attempted. This is the SAFE direction (over-protect a real-loopback target). The skip is non-spoofable because `addr` is the DNS-resolved `SocketAddr` (`lib.rs:106/131`), not a hostname literal — a public IP can never be mis-classified as loopback. No traffic-loop impact.

#### **[REL-3] Stale AnyTLS/ShadowTLS comments claim "only reachable as a relay-chain entry hop" — both have standalone backends** (P3, unverified)
`ripdpi-anytls/src/session.rs:248-252`, `ripdpi-shadowtls/src/client.rs:38-42`. Both comments assert the bare connect "only runs off-TUN" and is "bind-protected upstream via `outbound_bind_ip` ... `reject_bind_for_kind`." False: `transport_descriptor.rs` registers `anytls` (`:231 build_anytls`) and `shadowtls_v3` (`:207 build_shadowtls`) as standalone top-level relay kinds reachable in VPN mode; `reject_bind_for_kind` (`chain.rs:168-176`) only returns `Err` when a bind IP is set — it never binds or protects. The true safety reason is UID exclusion (correctly stated only in `ripdpi-ssh/src/lib.rs:19-26`). Documentation rot on the codebase's most dangerous invariant. (Same defect previously logged as SUB-2.)

#### **[REL-4] Mieru carries an unresolved TODO admitting protect coverage is unverified before live traffic** (P3, unverified)
`ripdpi-relay-tls-transports/src/mieru.rs:67-74`. `// TODO: confirm the Mieru RelayKind is covered by the relay protect chain before shipping live traffic.` precedes `tokio::net::TcpStream::connect((config.server.as_str(), config.port))`. `build_mieru` passes no protector and the descriptor sets `supports_outbound_bind_ip:false`, so the per-socket protect the TODO defers does not exist anywhere in the Mieru path. (Same as the earlier SUB-3.)

#### **[WAWG-1] Production WS-carrier protect goes through the fail-OPEN-on-`None` `WarpPlatform::carrier_protector()`, not the fail-closed `carrier_socket_protector()` seam** (P3, unverified)
`ripdpi-warp-core/src/platform.rs:52-60`. `carrier_protector()` returns a closure whose `None` arm is `Ok(())` (no-op). The AWG runtime calls this (`amneziawg_runtime.rs:535`), NOT the dedicated fail-closed `carrier_socket_protector()` (`ripdpi-amneziawg-android/src/carrier_protect.rs:71`, which is `#[allow(dead_code)]` and never called from a non-test path). The `None` arm is reachable only via `AmneziaWgRuntime::new` (default platform); production always uses `with_platform(config, amneziawg_platform())` (`lifecycle.rs:41`) so the protector is `Some` and delegates to the fail-closed `protect_socket_via_callback`. No production fail-open; safety rests on the constructor choice rather than the seam being intrinsically fail-closed.

#### **[WAWG-2] WARP/AWG probe and tunnel sockets fail closed when the protect callback is not yet registered — correct, but probes are unusable in proxy-only / VPN-down mode** (P3, unverified)
`ripdpi-warp-android/src/endpoint_probe.rs:19-20`. `warp_platform()`'s protector is `protect_socket_via_callback`, returning `Err(NotConnected)` when no callback is registered (`native-protect lib.rs:146`); `bind_probe_socket`/`bind_tunnel_socket` propagate and drop the socket. Desired fail-closed posture; noted only that a probe invoked before `VpnNativeProtectRegistration.register(service)` fails with an IO error.

#### **[KLE-1] cloudflared sidecar's outbound edge connection is unprotected in VPN mode (external-binary limitation)** (P3, unverified)
`core/service/src/main/kotlin/com/poyka/ripdpi/services/CloudflarePublishProcess.kt:118-127`. `launchCloudflaredProcess` does not inject `RIPDPI_PROTECT_PATH` (unlike `launchOriginProcess` at `:69-71`); cloudflared is an external Cloudflare-supplied Go binary with no `ripdpi-cloudflared` crate, so it physically cannot honor the env var. The Kotlin code matches the stated design. In VPN mode the edge connection can be captured by the TUN — a known architectural gap that cannot be closed at the Kotlin layer.

#### **[KLE-2] Benign race: relay-subprocess spawn vs VPN teardown reads `ActiveProtectSocketPathProvider.current()` unsynchronized (both branches fail-closed)** (P3, unverified)
`core/service/.../SubprocessSocksRelayManager.kt:70`. The `AtomicReference` read is atomic; the spawn sees old path or null. Old-path → helper connects to a UDS about to close → fails closed (`subprocess-protect lib.rs:64`). Null → helper no-ops (proxy-only semantics). Neither branch is fail-open.

#### **[TEST-1] No test for `VpnServiceSessionLifecycle` protect-path set/clear ORDERING** (P3, unverified)
`core/service/.../VpnServiceSessionLifecycle.kt:27`. `createShellDelegate()` does `start(); set(socketPath); register(service)` (`:27-29`); `cleanupNativeProtect()` does `clear(); …stop()` (`:62-66`). No `*SessionLifecycle*` test exists. This ordering is the invariant glue — a reorder of set/start or clear/stop, or a dropped `clear()` on revoke/destroy, would ship silently. Highest-value missing test in scope (see Missing tests).

#### **[TEST-2] No test that the subprocess async `protected_tcp_connect` protects before connect (only the blocking path is covered)** (P3, unverified)
`ripdpi-subprocess-protect/src/lib.rs:101`. The async path is the one used by all three helpers (`cloudflare-origin session.rs:25`, `webtunnel client.rs:61`, `naiveproxy connect_tunnel.rs:30`), yet inline tests exercise only `protected_tcp_connect_blocking` (`:231, :242`). The async arm could diverge (e.g. protect moved after connect) with every existing test still passing.

#### **[TEST-3] cloudflare-origin and webtunnel env-read wiring (`protect_path_from_env`) is untested at the crate boundary** (P3, unverified)
`ripdpi-cloudflare-origin/src/config.rs:35`, `ripdpi-webtunnel/src/bridge_line.rs:100`. Both call `protect_path_from_env()` but their config tests cover only parsing; `protect_path` is `#[serde(skip)]` so goldens can't observe it. naiveproxy at least asserts field plumbing (`config.rs:218`). A refactor dropping the env-read line would make the helper connect UNPROTECTED in VPN mode with no failing unit test.

#### **[TEST-4] No stale-UDS-path test (path advertised but listener already stopped)** (P3, unverified)
`ripdpi-subprocess-protect/src/lib.rs:64`. Existing `protect_fd_errors_when_no_server_listening` covers a never-bound path. There is no test for the realistic Android teardown race (a real on-disk AF_UNIX path whose listener has `stop()`-ed → ECONNREFUSED). The code already fails closed; this would pin the contract within `PROTECT_TIMEOUT`.

#### **[TEST-5] ws-tunnel datagram/UDP sockets are never routed through protect (TCP-only) — latent product gap** (P3, unverified)
`ripdpi-ws-tunnel/src/connect.rs:79`. `connect_tcp_socket_with_impl` only builds `Type::STREAM`; there is no production UDP socket in ws-tunnel today, so nothing is unprotected. Forward-looking: a future datagram transport could be added without a protect call and the TCP-only tests would not catch it.

#### **[TEST-6] No cross-language byte-compatibility test pinning the `'1'` request byte / `0|1` ack across both Rust senders and the Kotlin server** (P3, unverified)
`ripdpi-subprocess-protect/src/lib.rs:68` and `ripdpi-ws-tunnel/src/protect.rs:21` each send `b"1"` + SCM_RIGHTS; the Kotlin `VpnProtectSocketServerTest` uses a `FakeProtectSocketClientSession` returning a hardcoded `1` rather than reading the real bytes. The two senders and the Kotlin reader could drift without a single test failing.

## Refuted / non-issues

- **WS-1 — ws-tunnel Telegram bootstrap protect path** (`ripdpi-ws-tunnel/src/connect.rs:79-93`): verified CORRECT. `protect_socket`/`protect_via_callback_if_active` runs before `connect_socket`; `?` drops the socket (closes fd) on protect Err; ack≠0 → `PermissionDenied`. Tests `connect_tcp_socket_protects_before_connecting` and the protect callback tests pin ordering, fail-closed, loopback-skip, and no-callback no-op. Informational confirmation, not a defect.
- **Commit `7bc3bac46` "WS-tunnel protect is dead/absent"** (DIAG-1 secondary claim): refuted by the verifier. That commit touched `ripdpi-ws-tunnel/src/{connect.rs,protect.rs}` (which it correctly protected) and never claimed to touch diagnostics `ws_tls.rs`. The misattribution does not affect the upheld DIAG-1 defect.
- **DIAG-4 — diagnostics SOCKS-relay UDP bind** (`transport/socks5.rs:185-188`): correct by design. The relay targets the loopback SOCKS proxy (`proxy_host` is `127.0.0.1` per `monitor-proxy-runtime/lib.rs:43`); in-path SOCKS UDP must traverse the tunnel and is intentionally unprotected. Risk only if a future caller supplies a non-loopback `proxy_host` — not reachable today.
- **`reject_bind_for_kind`** (`relay-core protocols/chain.rs:168-176`): verified to fail closed correctly when a bind IP is supplied (test `:301-316`). It is not a protect mechanism, but it is sound for what it does.
- **proxy-runtime adapter `protect_path=None` gating** (`proxy-runtime-adapter/src/platform.rs:51`): the in-process callback is consulted only when `protect_path` is `Some`; the VPN-vs-proxy gating lives in proxy-runtime settings (`state/routing.rs:45`), outside protect-core. Flagged for confirmation in proxy-runtime scope, not a defect in the audited path.

## Missing tests

1. **`VpnServiceSessionLifecycleTest.advertises_protect_path_only_while_server_listening`** — `core/service/src/test/.../services/VpnServiceSessionLifecycleTest.kt`. Inject a fake `ActiveProtectSocketPathProvider` and a `VpnProtectSocketServer` spy recording `start()`/`stop()` order. Assert: `provider.current()==null` before `createShellDelegate()`; `==socketPath` after, with `server.start()` called **before** `provider.set`; `==null` after `destroy()`/`onRevoke()`, with `stop()` called **after** `clear()`. (Closes TEST-1.)
2. **`async_loopback_connect_skips_protect_even_with_a_bogus_path`** + **`async_no_path_connects_unprotected`** — `ripdpi-subprocess-protect/src/lib.rs` tests mod. Mirror the blocking tests at `:231/:242` but call `protected_tcp_connect(addr, Some("/definitely/not/a/socket")).await`. Assert loopback/no-path skip protect and a bogus path fails closed. (Closes TEST-2.)
3. **`config_reads_protect_path_from_env`** — in both `ripdpi-cloudflare-origin/src/config.rs` and `ripdpi-webtunnel/src/bridge_line.rs` tests. Set `RIPDPI_PROTECT_PATH` under a process-global env-mutation `Mutex`; assert the constructed config's `protect_path == set value`; unset and assert `None`. (Closes TEST-3.)
4. **`protect_fd_fails_closed_on_stale_uds_path`** — `ripdpi-subprocess-protect/src/lib.rs`. Bind a `UnixListener`, capture its path, drop the listener, then assert `protect_fd(fd, path)` returns `Err(ConnectionRefused|NotFound)` within `PROTECT_TIMEOUT` (not a hang). (Closes TEST-4.)
5. **`telegram_dc_probe_is_protected_in_vpn_mode`** (NEW, gates DIAG-2) — `ripdpi-diagnostics-telegram` tests. Register a recording protect callback, run `telegram_dc_probe` against a non-loopback test DC, and assert the callback was invoked before the connect (or that the probe is routed through `connect_transport_observed`). Should FAIL at `33c5885d8`.
6. **`telegram_ws_tunnel_probe_is_protected_in_vpn_mode`** (NEW, gates DIAG-1) — `ripdpi-diagnostics-transport` tests. Register a recording protect callback, drive `WsOverTlsConnector.probe_with_key_log` to a non-loopback target, assert protect-before-connect. Should FAIL at `33c5885d8`.
7. **`udp_bind_protects_before_bind`** (forward-looking, TEST-5) — add only if/when ws-tunnel gains a datagram socket, paralleling `diagnostics-transport/src/transport/protect.rs::protected_udp_bind` coverage.
8. **Cross-language handshake golden** (TEST-6) — pin the request payload to exactly `b"1"` and ack semantics `0=ok / 1=deny`, asserted in both `ripdpi-subprocess-protect` and `ripdpi-ws-tunnel` tests, plus a Kotlin test feeding the real bytes (not a faked `readHandshake()` return) through `VpnProtectSocketServer.handleClientSession`.

## Final recommendation

**FIX-BEFORE-SHIP — NOT a hard BLOCK (per §0).** *(The workflow wrote "BLOCK" on
the premise of a live P0 TUN loop. §0 corrects that: `computeAppRoutingPlan`
excludes the app's own UID from the TUN in every routing plan, so no unprotected
in-process socket actually loops today. This is therefore a strongly-recommended
fix, not an emergency stop-ship. It reverts to a hard BLOCK only if the maintainer
confirms `protect()` is load-bearing and own-UID exclusion is NOT relied upon — see
§0 residual uncertainty.)*

The two diagnostics fail-opens (DIAG-1/DIAG-2) and the standalone relay/QUIC
coverage gaps (REL-1/REL-2) are **the same class** of missing-`protect()` defect;
the report's own REL-1 analysis ("latent, not a live loop... safety rests on UID
exclusion") applies identically to the diagnostics probes. Close the diagnostics
gap first (it regressed from a protected sibling path, so it is the clearest bug);
treat the relay/QUIC gap as the lower-priority follow-up. Conditions to clear the
fix-before-ship flag:

1. **Fix DIAG-1** — route `ws_tls.rs::connect_tcp` (Telegram WS-tunnel probe) through the protect-aware `TransportConfig` / `protected_tcp_connect` path, OR add a `protect_for_target` call before `TcpStream::connect`/`connect_timeout` (`ws_tls.rs:77-87`) so the fd is protected before connect, loopback-skipped, no-op off-TUN, fail-closed on protect error.
2. **Fix DIAG-2** — thread the `transport` argument into `telegram_dc_probe` (it is already passed to the sibling download/upload probes at `report.rs:15-16` but omitted at `:17`), or otherwise protect the `dc.rs:42` connect. The DC probe is Direct in every mode, so the protect must apply regardless of `path_mode`.
3. **Add the gating tests** (Missing-tests #5 and #6) and confirm they FAIL at `33c5885d8` and PASS after the fix.

Recommended (non-blocking) follow-ups for a subsequent PR: resolve the relay UID-exclusion divergence (REL-1/REL-2) by either threading `socket_protector` into the standalone relay/QUIC builders to match the xHTTP and WARP families, or formally documenting UID exclusion as the sanctioned second layer and amending the invariant rule's audit grep to stop flagging accepted relay call sites; correct the stale AnyTLS/ShadowTLS comments (REL-3) and resolve the Mieru TODO (REL-4); align the poison policy of `has_protect_callback()` with the dispatch path (PC-1); and gate the dormant probes-crate runners (DIAG-3) so they cannot be wired into the registry without routing through `connect_transport_observed`.
