# DNS Relay Circuit Step 0 Plan

## Scope

Route DNS over the active relay circuit without adding a protocol or crate. The fixed seams are `RelayBackend::connect_tcp(target)` in `native/rust/crates/ripdpi-relay-core/src/backend.rs`, the relay SOCKS5 server in `native/rust/crates/ripdpi-relay-core/src/runtime.rs`, `EncryptedDnsConnectHooks` in `native/rust/crates/ripdpi-dns-resolver/src/types/hooks.rs`, and the resolver TCP/SOCKS5 paths in `native/rust/crates/ripdpi-dns-resolver/src/resolver/tcp.rs`.

Non-goals for this integration are new DNS protocols such as DoQ or ODoH, UDP ASSOCIATE Do53 unless explicitly promoted as stretch work, and per-app split-tunnel DNS.

## Normative Inputs

RFC 8484 defines DoH as DNS queries and responses mapped to HTTPS exchanges, with `application/dns-message` as the mandatory DNS wire-format media type. This makes DoH the default relay-routed DNS transport because the relay seam is TCP-oriented and every relay backend already exposes TCP connect semantics.

RFC 7766 defines DNS over TCP behavior and the two-octet length field that prefixes each DNS message on TCP. If DNS-over-TCP is used as fallback, the existing resolver path must preserve that length-prefix framing rather than inventing a local framing.

## Existing Seams

Relay TCP seam: `RelayBackend::connect_tcp(&RelayTargetAddr)` dispatches every implemented relay backend and returns relay-core `BoxedIo`; the SOCKS5 CONNECT server already calls this method when handling upstream targets. This is the correct transport seam and should not be reimplemented.

DNS connector seam: `EncryptedDnsConnectHooks::with_direct_tcp_connector` exists and the resolver uses it before direct bootstrap dialing. When a direct TCP hook is present, the DoH resolver uses its manual HTTP/TLS exchange path, so a connector can own the underlying stream selection while keeping RFC 8484 HTTP framing in dns-resolver.

Current mismatch to resolve before slice 1: the present direct TCP hook type is synchronous and returns `std::net::TcpStream`, while the relay backend seam is async and returns `BoxedIo`. The architecture goal should still prefer hook injection over local SOCKS5, but the first TDD slice must either adapt `EncryptedDnsConnectHooks` to accept a relay-compatible async stream shape or introduce a parallel relay TCP hook without replacing the existing protected-socket hook. Using the local SOCKS5 path is the fallback design only if review rejects a hook-shape extension.

Existing local SOCKS5 path: `EncryptedDnsTransport::Socks5` already calls `connect_socks5_tcp`, and the relay runtime exposes a SOCKS5 listener after building the backend. This path proves the composition is possible without a protocol rewrite, but it adds an avoidable loop through localhost and does not directly test `RelayBackend::connect_tcp`.

## Runtime Owner

The Kotlin runtime owner is `VpnRuntimeCompositionCoordinator`: it starts `SharedProxyRuntimeStack`, which starts the relay supervisor before the proxy, then starts `VpnTunnelRuntime` with the local proxy endpoint. This is the layer that knows whether a relay is active and whether the VPN tunnel is being launched.

The native DNS owner is currently `ripdpi-tunnel-core`: `build_encrypted_dns_resolver` builds `EncryptedDnsResolver` from `mapdns` and always uses `EncryptedDnsTransport::Direct` with protected-socket hooks. This is the native hook point for VPN DNS interception.

The relay backend owner is currently isolated inside `RelayRuntime::run`: it builds the backend, stores it in relay runtime state, binds the local SOCKS5 listener, and then serves SOCKS5 connections. No code currently passes the active backend to the tunnel DNS resolver.

## Leak Inventory

Leak 1, relay endpoint bootstrap deadlock: resolving the relay server hostname through the relay would require the relay to be up before the relay endpoint is known. The implementation must require an IP relay endpoint or allow exactly one scoped direct lookup before tunnel-up, then treat all later DNS queries as relay-routed or failed.

Leak 2, silent fail-open: the current encrypted DNS path is direct/protected. If relay mode is active and the relay is down, leaving this path enabled would resolve hostnames outside the relay. The relay-DNS policy must fail closed by returning resolver errors or queueing until relay readiness, never falling back to direct/system DNS.

Leak 3, Android VPN DNS exposure: `RipDpiVpnService.createBuilder` calls `Builder.addDnsServer(dns)` when the DNS value is non-blank. In encrypted mode, `VpnTunnelRuntime` supplies `198.18.0.53`, and `buildTun2SocksConfig` enables `mapdns` for the same address. That closes app DNS into the TUN only if the native mapdns resolver is itself relay-routed when relay mode is active.

Leak 4, plain profile relay mode: plain UDP DNS mode currently programs the configured plain DNS IP into `VpnService.Builder` and does not enable `mapdns`. If relay mode is active and the new setting is default-on, relay-DNS must force the encrypted mapdns path, defaulting to DoH over relay, or refuse startup; otherwise Android apps can use the configured plain DNS server outside the relay path.

Leak 5, resolver fallback pool: encrypted DNS fallback endpoints are acceptable only if the actual TCP dial for each fallback goes through the relay connector. Fallback must not mean fallback to the direct/protected socket hook while relay-DNS is required.

## Decision

Prefer hook injection. The implementation should wire a relay-backed TCP connector into `EncryptedDnsConnectHooks` at the runtime layer that owns both relay state and tunnel DNS configuration, so DoH and DNS-over-TCP use the existing resolver framing while TCP egress uses `RelayBackend::connect_tcp`.

Do not reimplement relay connect, SOCKS5, DoH, or DNS-over-TCP. Do not use system DNS after relay readiness except the explicit relay endpoint bootstrap allowance.

If the hook-shape mismatch is approved for code change, implement the smallest resolver hook extension that can carry an async `AsyncRead + AsyncWrite` stream from relay-core while preserving the existing `std::net::TcpStream` protected-socket hook for direct mode. If review rejects changing the hook shape, use `EncryptedDnsTransport::Socks5` to target the existing relay SOCKS listener, but keep the same fail-closed and bootstrap tests.

## TDD Plan

Slice 1, relay-backed connector via `EncryptedDnsConnectHooks`: first add a failing turmoil test in `ripdpi-dns-resolver` or a narrow runtime integration crate proving a DoH query reaches only a fake relay backend and never the direct dialer; run the targeted `cargo test` and record the failure; implement minimal hook injection/adapter; rerun the same test, `cargo fmt --check`, and targeted clippy; commit `feat(dns-relay): route resolver tcp through relay`.

Slice 2, fail-closed policy: first add turmoil tests for relay active/up and relay active/down, asserting up resolves through the relay and down returns a resolution error with zero direct egress; run failing `cargo test`; implement the relay-DNS required policy so direct/system fallback is impossible; verify with targeted tests, fmt, and clippy; commit `feat(dns-relay): fail closed when relay dns is required`.

Slice 3, relay endpoint bootstrap: first add turmoil coverage that the relay endpoint hostname is resolved once before tunnel-up, with a counter proving exactly one direct bootstrap query and all later DNS routed through the relay; run failing `cargo test`; implement the bootstrap gate using pinned IPs first and one scoped direct lookup only when needed; verify; commit `feat(dns-relay): constrain relay endpoint bootstrap`.

Slice 4, Android VpnService DNS capture: first add Kotlin/JNI contract tests showing relay-active DNS forces `VpnService.Builder` to use the mapdns interceptor and native tunnel config to require relay-routed resolver, including plain-profile relay mode; run failing Gradle unit tests; implement the setting propagation and JNI/config fields; verify Gradle tests plus targeted native tests; commit `feat(dns-relay): close vpn dns leak through tun`.

Slice 5, setting, telemetry, docs: first add tests for the default-on-when-relay-active setting and telemetry events for relay DNS route, fail-closed, and bootstrap direct lookup; run failing tests; implement UI/data/defaults, telemetry, localized strings, docs, and goldens with `RIPDPI_BLESS_GOLDENS` only when fixture changes are non-tautological; verify cargo, clippy, fmt, Gradle unit/static checks, cargo-deny; commit `feat(dns-relay): add relay dns policy setting`.

## Test Matrix

DoH-over-relay prevents hostname egress outside the relay: turmoil asserts the fake relay backend sees the DoH TCP target and the direct dial counter remains zero.

Fail-closed prevents silent direct fallback: relay active plus down relay returns a resolver error or queued state, and direct/system DNS counters remain zero.

Bootstrap prevents relay self-deadlock and bootstrap leaks: a relay hostname may be resolved directly exactly once before relay startup, or startup requires an IP endpoint; after startup, the direct DNS counter must remain unchanged.

Android VPN DNS capture prevents app DNS bypass: encrypted and relay-forced profiles program `198.18.0.53` into `VpnService.Builder`, mapdns is enabled in `Tun2SocksConfig`, and native DNS resolution uses the relay-required connector.

Fallback resolver pool prevents hidden fallback leaks: every fallback endpoint uses the same relay connector when relay-DNS is required, and a down relay fails the pool rather than trying protected direct sockets.

## Implemented Contract

The relay DNS policy is default-on when relay mode is active. The persisted setting is `relay_dns_over_tunnel_enabled`; unset values are treated as enabled so upgraded installations fail closed without requiring a migration write. An explicit false value disables forced relay DNS for operator troubleshooting, but the default app settings serialize the field as true.

Android VPN mode closes the app-DNS leak by programming the map-DNS interceptor address when relay DNS is forced, even if the selected profile is plain DNS. The native tunnel config then marks `routeDnsThroughSocks5`, causing the encrypted resolver to use the existing local SOCKS5 path rather than direct/protected resolver sockets; because the local proxy stack is relay-backed when relay mode is active, relay-down DNS fails instead of falling back to system DNS.

Telemetry exposes `relayDnsRoute` and `relayDnsFailClosed` on the tunnel runtime snapshot, emits a `relay_dns_route` native event when the fail-closed route is active, and emits `relay_endpoint_bootstrap_direct_lookup` on the relay event ring for the one allowed direct relay endpoint lookup before tunnel startup.
