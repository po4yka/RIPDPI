# Protocol ground-truth matrix — declared vs implemented

**Audit snapshot: 2026-05-31** (re-audit of the 2026-05-30 baseline against `main` @ `2c00ec9b1`). Read-only multi-agent audit tracing each protocol path Kotlin service layer → JNI bridge → Rust crates and back. Every row carries `file:line` evidence. `implemented` is one of **full** / **partial** / **absent**. A `gap` is recorded where the docs claim a capability the code does not fully implement, or where the code's behavior contradicts the documented behavior.

This is a point-in-time snapshot derived from source at audit time, not a living contract — the code is the source of truth (see AGENTS.md § Project Rules). Re-run the audit before relying on any row; line numbers drift. The UDP/QUIC paths are listed first because they hold the largest declared-vs-implemented divergence.

> **Auditor note (2026-05-31):** the repo root was found as a *bare* checkout with a **stale stray working tree** decoupled from `main` by ~17k lines (the post-2026-05-30 features below were committed but absent on disk). The tree was reset to `main` (`2c00ec9b1`) before this pass. If a re-run shows the new sections 7–12 as "absent", first verify the working tree actually matches `HEAD` (`git diff --stat HEAD`) — you may be reading a stale checkout.

## Highest-leverage findings

- **SOCKS5 UDP ASSOCIATE exists and is on by default — the docs say it does not exist.** Implemented in two independent paths; the Kotlin layer has no field to disable it.
- **Mixed inbound (SOCKS4 + SOCKS5 + HTTP CONNECT on one port) is implemented and undocumented.** First-byte peek dispatches; SOCKS4 inbound is wholly undeclared (§7).
- **VMess, Trojan-Go, and Hysteria v1 were removed entirely** (code + docs) per [ADR 0004](../adr/0004-protocol-support-policy.md) — they were never-completed stubs that carried no traffic. The remaining stubbed relays are **Mieru** and **SSH** (backlog, not legacy); only **AnyTLS** of the extended-outbound set carries traffic. SSH is stubbed *specifically* to avoid opening an unprotected socket — it honors the `VpnService.protect()` invariant (§10).
- **The Xray provider (VLESS/REALITY/XHTTP) is a Go/gomobile path, not Rust**; its `.so` is not committed and its REALITY ECH behavior is opaque to the Kotlin/Rust layers (§11).
- **VLESS REALITY emits no ECH extension at all** (neither real ECH nor GREASE). The cover-domain-conditioned ECH-GREASE policy lives only in ADR 0001 as future intent.
- **DoQ is silently blocked over SOCKS5 transport**, which is the transport the proxy runtime uses internally; the new DoH-JSON survey is **diagnostics-only** and never a runtime resolver path (§4).
- **Entropy padding is configured end-to-end but never applied** in the fake-packet builder.
- **`md5sig` and `tlsminor` are enableable only via the native CLI**, not from the Android JSON config path.

---

## 1 — SOCKS5 UDP ASSOCIATE (UDP / QUIC)

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| CMD 0x03 exists at all | Docs: no UDP ASSOCIATE path exists | **full** | `ripdpi-socks5-core/src/lib.rs:95,116-117,133-138`; `server/protocol.rs:421-427` | Declared absence is false. Fully implemented. |
| proxy-runtime ASSOCIATE handler (Android path) | (undocumented) | **full** | `ripdpi-proxy-runtime/src/runtime/handshake.rs:152` (dispatch from `handle_socks5`), `:212` (`fn handle_socks5_udp_associate`); `runtime/udp.rs:41-81` | The path the Android proxy actually uses — entirely undocumented. Line refs drifted from the 2026-05-30 baseline. |
| UDP relay header parse (RSV/FRAG/ATYP) | (undocumented) | **full** | `ripdpi-socks5-core/src/lib.rs:340-375`; `server/udp.rs:96-129` | Undocumented; FRAG≠0 datagrams silently discarded. |
| `network.udp` default | (undocumented) | **full (=true)** | `ripdpi-config/src/model/defaults.rs:68`; CLI `-U/--no-udp` at `parse/cli/options/runtime.rs:29` | On by default — any SOCKS5 client sending 0x03 is accepted. |
| Kotlin can disable UDP ASSOCIATE | docs: n/a | **absent** | `NativeProxyConfig.kt:33-66`; `NetworkSectionCodec.kt:23-29` (no udp field) | App cannot opt out via UI-kind JSON; always inherits Rust default `true`. Control-plane gap. |
| End-to-end Kotlin→JNI→Rust reachable | docs: path doesn't exist | **full** | `RipDpiProxy.kt:177` → `ffi/proxy_bridge/core.rs:39` → `proxy_handshake.rs:34-83` → `handshake.rs:152,212` → `udp.rs:41-81` | Direct contradiction: docs say the path doesn't exist; it is wired and reachable. |
| Upstream SOCKS5 chaining (ext_socks) forwards ASSOCIATE | (undocumented) | **absent** | `ripdpi-socks5-core/src/client/outbound.rs:14` ("out of scope (v1)") | ASSOCIATE not forwarded upstream; relay goes direct, bypassing the chain. |
| VPN-mode UDP/QUIC via TUN→SOCKS bridge | `tunnel.md:16-27` | full | `tunnel.md`; `RUNTIME_MODES.md:158-159` | none — accurate for VPN mode (separate from the direct-client ASSOCIATE feature). |

## 2 — QUIC / UDP datapath (proxy + tun2socks)

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| QUIC enters TUN → SOCKS UDP-associate → desync → upstream | `tunnel.md:22-25` | **full** | `ripdpi-tunnel-core/src/session/socks5/udp_associate.rs:11-59`; `ripdpi-proxy-runtime/src/runtime/udp.rs:41-81` → `ripdpi-desync/src/plan_udp.rs:14-59` | none — QUIC is not dropped or downgraded to TCP 443. |
| UDP chain steps DummyPrepend / QuicSniSplit / QuicFakeVersion / IpFrag2Udp | `proxy-engine.md:450-455` | full | `ripdpi-config/src/model/udp.rs:7-16`; `ripdpi-desync/src/plan_udp/packet_family.rs:41-43`, `fragmentation.rs:8-27` | Wire name is `ipfrag2_udp`, not the doc's `ip_frag2_udp` alias → unknown-kind error if users follow docs. |
| `support_v2` default | docs: **false** | partial | `ripdpi-config/src/model/defaults.rs:101` (=true); `NetworkSectionCodec.kt:35` (=true) | QUIC v2 enabled by default, contradicting documented `false`. |
| PascalCase ⇄ snake_case kind aliases | `proxy-engine.md:457` (both accepted) | partial | `ripdpi-proxy-config/src/convert/chain/udp.rs:8-24`; `StrategyChainModel.kt:132-136` (`.lowercase()`) | True PascalCase (`QuicSniSplit`) not accepted at runtime; only snake/collapsed forms. |
| 8 QUIC surface fields | `proxy-engine.md:609-620` | partial | `NetworkSectionCodec.kt:32-38` (only 5 serialized) | `quic_fake_version` / `quic_bind_low_port` / `quic_migrate_after_handshake` not settable from Android. |
| Static QUIC-Initial fake profile library | `proxy-engine.md:595` | partial | `ripdpi-packets/src/fake_profiles.rs:21-29` (no QUIC variant); `quic/fake_initial.rs:38` (dynamic) | No static profile; generated dynamically. Doc conflates the two. |
| `udplen` raw IPv4 emission | `proxy-engine.md:464` | partial | `ripdpi-strategy-udp/src/lib.rs:38-72` | Also does IPv6; silently no-ops in proxy mode (no VpnMode capability) — undocumented. |
| DTLS fingerprint normalization | `README.md:31` | **absent** | grep `dtls`/`DTLS` → no production matches | Declared, no code. |
| `quic_compat_burst` / `quic_realistic_burst` named variants | proxy-engine.md | partial | enum has `QuicMultiInitialRealistic` / `FakeBurst` instead (`model/udp.rs:12-13`) | Declared variant names do not exist. |

## 3 — SOCKS5 TCP CONNECT

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| TCP CONNECT end-to-end (proxy + VPN) | `proxy-engine.md:638` | **full** | `handshake.rs:101-108`; `RipDpiProxy.kt:162-239`; `ffi/proxy_bridge/core.rs:38-79` | none (baseline confirmed). |
| RFC 1929 auth in VPN mode | `proxy-engine.md:72` | full | `proxy_handshake.rs:46-48`; `udp_associate.rs:11-59` | none. |
| Whitelist host-filter group ordering | docs: inserted **before** main group | partial | `ripdpi-proxy-config/src/convert/protocol.rs:32-42` (`groups.push` appends **after**) | Ordering reversed from doc for Whitelist mode. |
| TCP-state predicates `tcp_has_ts` / `tcp_window_lt` / `tcp_mss_lt` | docs: 4 predicates | partial | only `tcp_has_ech` found (`ripdpi-desync/src/types.rs:238`; `config/src/model/offset.rs:19`) | 3 of 4 predicates do not exist in code. |
| Host-autolearn defaults (ttl=600, max=1024) | proxy-engine.md | partial | `ripdpi-config/src/constants.rs:27-28` (=21600s, =512) | Both defaults differ from docs (6h not 10min; 512 not 1024). |
| Freeze-detect defaults (5000ms / 512B) | proxy-engine.md | partial | only test consts found (`state.rs:458-460`) | Documented production defaults unverifiable in source. |
| TCP retransmission via `TCP_INFO` | proxy-engine.md | partial | `ripdpi-tunnel-core/src/io_loop/retransmit.rs:1-123` (heuristic SEQ compare, TUN path) | Uses SEQ heuristic, not `TCP_INFO`; lives in tunnel layer, not proxy session. |
| Failure-classifier 8 signals | proxy-engine.md | full (naming nit) | `ripdpi-failure-classifier/api-snapshot.txt:4-11` | doc says "HTTP failure-page"; code is `HttpBlockpage`. |
| tunnel uses `fast-socks5` | `tunnel.md:144` | partial | no import / Cargo.toml ref found in `ripdpi-tunnel-core` | Claimed dependency not verifiable. |

## 4 — DNS (UDP / DoH / DoT / DNSCrypt / DoQ)

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| DoH | declared | **full** | `ripdpi-dns-resolver/src/doh/`, `doh_pipeline.rs` | none. |
| DoT | declared | **full** | resolver dispatch / `hickory_backend.rs` | none — DoT present. |
| DNSCrypt | declared | **full** | `ripdpi-dns-resolver/src/dnscrypt/` | none. |
| DoQ | declared (unconditional) | **partial** | `resolver/doq.rs:11-27` impl; `:14` errors over SOCKS5 | Blocked over SOCKS5 transport — unavailable on the proxy-runtime path; docs omit this. |
| Plain UDP-53 | (undocumented) | **absent (in Rust)** | `RipDpiRuntimeContext.kt:227` null; no UdpSocket resolver | Handled by Android system resolver only; no Rust path. |
| "DoQ 18–22% faster than DoT" | `tunnel.md:9` | **absent (claim)** | no benchmark/measurement code anywhere | Unverifiable performance figure; no supporting code. |
| MapDNS listener 198.18.0.53 | tunnel.md | full | `VpnDnsBuilder.kt:12`; `dns_intercept/config.rs:16-50` | minor doc method-name nit only. |
| ODoH (RFC 9230) | declared | partial | `odoh.rs:1-220` full; `ripdpi-ws-bootstrap/src/endpoint.rs:45` hardcodes `odoh: None` | Works on tunnel path; absent on ws-bootstrap path. |
| Proxy-runtime DNS telemetry (resolver id/protocol/latency/fallback) | `README.md:127` | **absent** | proxy `snapshot.rs:38-142` lacks these fields; only in `ripdpi-tunnel-android` | README mislabels owning subsystem — proxy telemetry has none of these. |
| Per-network-scope cache isolation | declared | partial | isolation only via session restart (`VpnTunnelRefreshCoordinator`); no explicit keying | Implicit via lifecycle, not explicit partitioning. See the resolver-mapping-cache row below for the newer explicit keying on the *resolver-selection* path. |
| Resolver-selection mapping cache (network-scope keyed) | (undocumented) | **full (wired live)** | `ripdpi-runtime-dns-cache/src/resolver_mapping_cache.rs` (`ResolverMappingKey`, 7-day `DEFAULT_TTL`); wired at `ripdpi-ws-bootstrap/src/policy.rs:7,20` (`static RESOLVER_SELECTION_CACHE`), consulted on the hot path `:34` (`select_encrypted_dns_context`) | New since baseline. Explicit `(host, network_scope)` keying for *which resolver* to prefer; populated after a successful encrypted-DNS resolution. Distinct from the per-host policy cache above. Undocumented. |
| DoH-JSON survey probe (Google /resolve, Cloudflare, AdGuard, Alibaba) | (undocumented) | **full (diagnostics-only)** | `ripdpi-diagnostics-probes/src/doh_json_survey.rs:19-24` ("Diagnostics-only — never a runtime resolver path"; runtime resolver stays wire-only, no wire→JSON fallback) | New since baseline. JSON DoH is a survey/evidence path only; the runtime resolver deliberately never falls back to JSON. No declared-vs-impl conflict, but the surface is undocumented. |

## 5 — TLS 1.2/1.3 desync + fake-TLS

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| `tlsrec` record splitting at extension boundary | declared | full | `ripdpi-desync/src/tls_prelude.rs`; `client_hello_offsets/` | none. |
| TLS 1.2 vs 1.3 version-aware splitting | implied | partial | `first_flight_ir.rs:134-144` collects versions but never branches splitting | Splitting is version-agnostic; version data collected, unused (functionally OK; no version-aware logic). |
| `tlsminor` override | docs: "All platforms" | partial | `tls_prelude.rs:229-234` impl; no field in `FakePacketCodec.kt` / `fake_packet.rs` | CLI-only; not reachable from Android JSON. |
| Entropy padding (popcount / shannon / combined) | proxy-engine.md | **partial — not applied** | functions in `ripdpi-packets/src/entropy.rs:96,127`; config wired; never called in `ripdpi-desync/src/fake.rs` | Configured end-to-end but never executed in the fake-packet build path. |
| `auto(echext)` adaptive marker | implied supported | **absent** | `ripdpi-config/src/parse/offsets.rs:180-181` rejects it | Explicitly rejected at parse. |
| Fake dual send (socket vs raw) for fake / fakedsplit / fakeddisorder | flowchart implies both | partial | no `WriteRawFakeTcp` in `DesyncAction` (`types.rs:154-194`); planner emits `Write+SetTtl` only | Those 3 kinds always use socket/SetTtl; raw path only for seqovl/multidisorder/ipfrag2. |
| auto-TTL `max_ttl` default 20 | `proxy-engine.md:215` | partial | `FakePacketCodec.kt:13` sets 12 | default mismatch (12 not 20). |

## 5b — TLS / REALITY (ripdpi-vless)

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| REALITY drives BoringSSL ClientHello hook via `SSL_CTX_set_client_hello_cb` (H1 vendor patch) | `proxy-engine.md:899-924`; `adr/0001:19-21` | **full** | `reality_hook.rs:70-86` (`SSL_handshake_get_x25519_private_key`:76, `SSL_CTX_set_client_hello_cb`:80, `SSL_get_SSL_CTX`:85), `:213-227`; `reality.rs:12,16,46`; `ripdpi-vless/Cargo.toml:8-9` | none — confirmed, including the two vendor-patch-only symbols. |
| REALITY ECH GREASE gated on fingerprint-profile + cover-domain evidence (`RealityEchParity`) | `adr/0001:31,53,77` | **absent** | grep `grease`/`RealityEchParity`/`cover_ech_evidence` in `ripdpi-vless/src/` → 0 matches; `reality.rs:39-101` has no ECH decision point; `adr/0001:77` says "do not change production code" | Declared policy is ADR future-intent. Connect path emits no ECH extension of any kind. |
| VLESS REALITY does NOT use real ECH | `adr/0001:29,59,63` | **full** | `reality.rs:39-101` (no `SSL_CTX_set1_ech_config_list`/ech_config_list/retry); `reality_hook.rs:230-295`; `reality_seal.rs:100-141` (AES-256-GCM SessionID seal) | none — code agrees with ADR. |

## 5c — WS tunnel fake-SNI (proxy-runtime / ripdpi-ws-tunnel)

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| Cover domain in TLS SNI; cert verification disabled when active; gated behind `allow_insecure_sni` (default false) | `proxy-engine.md:755-767` | **full** | `ripdpi-ws-tunnel/src/connect.rs:153,159,162` → `ripdpi-tls-profiles/src/builder.rs:20-21` (`set_verify(NONE)`); gate `ws-tunnel/src/lib.rs:115-120` (`PermissionDenied` without opt-in); field `proxy-runtime-adapter/.../ws_tunnel.rs:23,33`, default `false` | none. HTTP Upgrade `Host` header still uses the real host (`connect.rs:165`); only TLS SNI sees the cover. |
| `wsTunnelFakeSniActive` telemetry counter (Rust → Kotlin, fake-SNI path only) | `proxy-engine.md:778-785` | **full** | `android-telemetry-adapter/src/state.rs:58`, `adaptive.rs:55`, `observer.rs:133-134`, `snapshot.rs:122`, `types.rs:124-125`; Kotlin `NativeRuntimeSnapshot.kt:175` | none — counter exists end-to-end. |

## 5d — Diagnostics TLS "split probing" (ripdpi-diagnostics-tls / monitor-engine)

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| HTTPS reachability check does TLS 1.3 + TLS 1.2 "split probing" | `proxy-engine.md:864` | **partial** | `monitor-engine/.../https/observation_collection/tls_attempts.rs:11-49` (`Tls13Only`:25, `Tls12Only`:35, `Tls13WithEch`:45); handshake `ripdpi-diagnostics-tls/src/tls/probe/capture.rs:48-54` (unmodified stream) | "split" = separate per-version TCP connections, not ClientHello record fragmentation; desync `tls_prelude.rs` splitting is never applied during diagnostics; warmup probes TLS 1.3 only. |

## 6 — TCP desync steps (hostfake, fakedsplit, fakeddisorder, md5sig, fake TTL)

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| hostfake | "Platform: All" | partial | `ripdpi-desync/src/plan_tcp.rs:214-228`; `ripdpi-privileged-ops/src/socket_options.rs:22` returns Unsupported off-Linux | Effectively Linux/Android-only, not "All". |
| hostfake native validation of altorder-without-midhost | "Kotlin **and** native" | partial | `proxy-config/.../validation.rs:78-84` only; CLI parser has no check | Native CLI path does not reject it. |
| fakedsplit | "Platform: All" | partial | `tcp_fake_family/fake_split.rs:15-122`; routes through privileged-ops | Silently fails off-Linux; not documented as platform-limited. |
| fakeddisorder | "Platform: All" + `SetTtl(fake_ttl)` | partial | `tcp_fake_family/fake_disorder.rs:143-188`: first segment hardcodes `TTL=1` | First half uses TTL=1, not `fake_ttl`; doc inaccurate. |
| md5sig (TCP MD5 opt, Kind=19) | strategy surface item | partial | kernel impl complete (`privileged-ops/.../packet_builder.rs:148-158`); no field in `proxy-config` Kotlin path | Not enableable from Android app; CLI-only; needs CAP_NET_ADMIN (EPERM on non-root). |
| fake TTL (fake / fakedsplit) | `SetTtl(fake_ttl)` | full | `plan_tcp/actions.rs:30` | accurate for these two. |
| seqmode=sequential "fails closed" without raw/TCP_REPAIR | proxy-engine.md | partial | fails-closed tests cover flag-override / ipfrag2 only (`special_plan.rs:296+`); no gate for sequential | "fails closed for sequential" not verified — proceeds regardless. Likely doc overclaim. |
| seqovl "fails closed" / "fall back to split" | docs | partial | `plan_tcp.rs:169-170` falls back to split; gate is round==1 + 1500B heuristic, not pure capability check; default-12 overlap unenforced | Behavior ≈ doc but mechanism differs; "default 12" not enforced. |

---

> **Sections 7–12 are new since the 2026-05-30 baseline** (committed after the prior snapshot; see the auditor note at the top). Verified against `main` @ `2c00ec9b1` on 2026-05-31.

## 7 — Inbound protocol modes (transparent / HTTP CONNECT / mixed / byte-prefixed)

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| Mixed inbound: SOCKS4 + SOCKS5 + HTTP CONNECT on one port | (undocumented) | **full** | `ripdpi-proxy-runtime/src/runtime/types.rs:29` (`Mixed { shadowsocks_enabled }`); `runtime/handshake.rs:53` (`fn handle_mixed`), first-byte peek `:62-63` (`b'C'`→HTTP CONNECT), `:73` (`0x04`→SOCKS4), `:74` (`0x05`→SOCKS5) | Single-listener multi-protocol dispatch is undocumented. |
| SOCKS4 / SOCKS4a inbound CONNECT | (undocumented) | **full** | `ripdpi-proxy-runtime/src/runtime/handshake.rs:103` (`fn handle_socks4`); `ripdpi-socks5-core/src/socks4/` | SOCKS4 inbound entirely undeclared in docs. |
| Byte-prefixed mode (SOCKS4/5, no HTTP CONNECT) + optional Shadowsocks | implied (shadowsocks surface) | **full** | `types.rs:30` (`BytePrefixed { shadowsocks_enabled }`); `handshake.rs:38-39` (`0x04`/`0x05` only) | Separate from Mixed; HTTP CONNECT is *not* in the byte-prefixed arm. |
| Mode selection from config | (undocumented) | **full** | `ripdpi-proxy-runtime-adapter/src/model/config/proxy_handshake.rs` (`proxy_protocol_mode`, listener-level modes take precedence over byte-prefixed); `ripdpi-proxy-runtime/src/runtime/state/handshake.rs` (`ProxyProtocolMode::Mixed` → `RuntimeProxyProtocolMode::Mixed`) | Precedence order undocumented. |

## 8 — Allow-LAN binding + auto access token

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| Listener binds `0.0.0.0` when allow-LAN enabled, else loopback | (undocumented in `docs/native/`; noted in `CONFIG_CONTRACTS.md`) | **full** | proto `app_settings.proto:404` (`proxy_allow_lan`); mapper `core/engine/.../NativeProxyRuntimePreferencesMapper.kt` (`ip = if (allowLan) "0.0.0.0" else proxy.proxyIp.ifEmpty { "127.0.0.1" }`) | End-to-end wired Kotlin→config. |
| Auto-generated access token, set only when allow-LAN on | (undocumented in `docs/native/`) | **full** | proto `app_settings.proto:405` (`proxy_lan_auth_token`); mapper sets `authToken = if (allowLan) lanToken else null`; enforced in `ripdpi-proxy-runtime/src/runtime/handshake/protocol_io/readers.rs` (SOCKS5 userpass + HTTP CONNECT auth) | Token is coupled to allow-LAN by the Kotlin mapper; a dedicated Rust-side "non-loopback ⇒ require token" guard was not located in this pass — verify before relying on it. |

## 9 — System HTTP proxy advertisement (Android Q+)

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| `VpnService.Builder.setHttpProxy(ProxyInfo)` advertises the local listener on Android 10+ | `CONFIG_CONTRACTS.md` (listener contract); not in `docs/native/proxy-engine.md` | **full** | `core/service/.../RipDpiVpnService.kt` (`builder.setHttpProxy(buildHttpProxyInfo(httpProxyPort))` gated on `SDK_INT >= Q`; `buildHttpProxyInfo` = `ProxyInfo.buildDirectProxy("127.0.0.1", port, httpProxyExclusionList)`) | Opt-in; loopback excluded from proxying. Effective port follows the proxy listener. |

## 10 — Extended outbound relay protocols (Mieru / SSH / AnyTLS)

> **2026-06-01 — VMess, Trojan-Go, and Hysteria v1 were removed entirely** per [ADR 0004](../adr/0004-protocol-support-policy.md). The `ripdpi-vmess`/`ripdpi-trojan-go`/`ripdpi-hysteria-v1` crates, their `RelayKind`/builder/descriptor/flat-config surface, the Kotlin `ProxyProfile.Vmess`/`TrojanGo`/`HysteriaV1` types + editors + URI-codec arms, the proto fields (reserved), and all locale strings are gone. The relay native-config schema ceiling is now `8`. A persisted config or share-link naming a removed kind is rejected (native `Unsupported` catch-all / URI codec returns `null`); a subscription node naming one is skipped. The rows below cover only the relays that still exist.

The remaining extended-outbound relays still carry the full config parse/validate, typed error rejection, secret redaction, `RelayKind`/`RelayBackend`/transport-descriptor registration (drift-matched Kotlin↔Rust), wire DTO + section codec, URI codec + round-trip tests, Compose editor, and localized strings. Of them, only **AnyTLS** has a live wire engine; **Mieru** and **SSH** remain stubbed behind typed `Unimplemented` errors (backlog work, not legacy).

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| `RelayKind` registration for the remaining kinds | (undocumented) | **full** | `ripdpi-relay-core/src/config/kind.rs` (`Mieru`/`Ssh`/`AnyTls`, alongside the shipped Hysteria2/TUIC/VLESS/etc.) | Config/enum surface complete; removed kinds fall through to the `Unsupported` catch-all. |
| Mieru wire engine | (undocumented) | **absent (stub)** | `builders/mieru.rs` | Session/replay engine not wired. Backlog. |
| SSH wire engine | (undocumented) | **absent (stub — protect-invariant)** | `builders/ssh.rs` ("`russh` … stubbed because the relay layer exposes no protected outbound connector … fails … `Unimplemented` rather than opening an unprotected socket"); `ripdpi-ssh/Cargo.toml` has a real `russh` dep | The `russh` engine is cryptographically real but unreachable from relay until a `VpnService.protect()`-honoring connector exists. A faithful application of `vpnservice-protect-invariant.md`. |
| AnyTLS wire engine | (undocumented) | **full** | `builders/anytls.rs` (builds live `AnyTlsSessionFactory`/`AnyTlsClientConfig`; no `Unimplemented`) | Carries traffic — the only one of the remaining set that does. |
| Upstream SOCKS5 chaining forwards ASSOCIATE | docs: n/a | **absent** | `ripdpi-socks5-core/src/client/outbound.rs:14` ("out of scope (v1)") | Unchanged by the extended-outbound work — ASSOCIATE still not forwarded upstream. |

## 11 — Xray provider (libXray — VLESS / REALITY / XHTTP)

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| Provider is Go (gomobile AAR), not Rust | `docs/native/libxray-packaging.md` | **full** | `scripts/native/build-libxray.sh`, `scripts/native/libxray-build.Dockerfile` (gomobile `bind`, per-ABI `.so` in an `.aar`); verify gate `scripts/native/verify-libxray-artifacts.sh` | Separate runtime from the native Rust path; `.so` is **not committed** (built on demand, version-pinned). |
| Kotlin VLESS/REALITY/XHTTP config render + validate | (provider docs) | **full** | `core/data/catalog/src/main/kotlin/com/poyka/ripdpi/data/xray/` (`XrayProfile.kt`, `XrayConfigRenderer.kt`, validator, tester, redactor) | Config layer complete and deterministic. |
| Xray REALITY ECH emission | not declared | **unverifiable from this tree** | Kotlin renderer emits no `echConfig`; the actual handshake is inside the Go `xray-core` AAR | Unlike `ripdpi-vless` (which provably emits no ECH, §5b), the Xray path's ECH behavior is opaque to Kotlin/Rust — cannot be asserted either way here. |

## 12 — N-hop relay chains (2..=4 hops)

| Step | Declared | Impl | Evidence | Gap |
|---|---|---|---|---|
| Ordered chain of 2–4 hops over the flat wire (schema v7) | `CONFIG_CONTRACTS.md` | **full (wire)** | `core/engine-api/.../RelayNativeConfig.kt:269` (`RelayNativeConfigSchemaVersion = 7`), `:281`/`:284` (`RelayChainMinHops=2`/`RelayChainMaxHops=4`); validation `RelaySectionsDto.kt:57` | Schema version is now **7**, not the 6 quoted in CLAUDE.md/AGENTS.md prose — update that prose. |
| Per-hop bind/protect; entry-only outbound bind | `CONFIG_CONTRACTS.md` | **full** | `ripdpi-relay-core` chain backend/builder (entry hop carries the outbound bind IP; later hops tunnel `connect_over` the prior stream) | none verified-contradictory. |
| QUIC-only kinds rejected at non-entry positions | `CONFIG_CONTRACTS.md` | partial | builder rejects QUIC-only kinds off the entry hop; an explicit standalone validation rule was not separately confirmed in this pass | Re-verify the rejection rule's exact site before relying on it. |

---

## Method & scope

- **2026-05-30 baseline:** two read-only multi-agent workflows — a 6-path sweep, then a 3-area follow-up (`ripdpi-vless` REALITY, `ripdpi-ws-tunnel` fake-SNI, `ripdpi-diagnostics-tls`). Each path/area ran a docs-extractor and a code-extractor in parallel, then an adversarial reconciler that re-read cited `file:line` before accepting a status.
- **2026-05-31 re-audit (this revision):** re-verified §§1–6 line refs against `main` @ `2c00ec9b1` (only §1 ASSOCIATE-handler refs drifted) and added §§7–12 plus two §4 rows for the post-baseline features (mixed/byte-prefixed inbound, allow-LAN + token, system HTTP proxy, extended outbound relays, Xray provider, N-hop chains, DoH-JSON survey, resolver-mapping cache). **Caveat:** the on-disk tree was initially a stale stray checkout (~17k lines behind `HEAD`); several first-pass agents read it and wrongly reported §§7–12 features as "absent". The tree was reset to `main` and every §§7–12 / §4-new row was re-verified by direct read. The Xray REALITY ECH row remains *unverifiable* (Go AAR, opaque to this tree).
- "Declared" sources: `docs/native/proxy-engine.md`, `docs/native/tunnel.md`, `docs/native/README.md`, `docs/native/libxray-packaging.md`, `README.md`, `docs/architecture/*.md` (incl. `CONFIG_CONTRACTS.md`), `docs/adr/0001-reality-ech.md`.
- This document is condensed to substantive rows (every gap plus the load-bearing confirmations). It is not exhaustive and not a regression gate — treat it as an audit lead-sheet, re-verify before acting.
