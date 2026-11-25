# ADR 0010: core/engine-api code decisions

> Status: Accepted. Decision date: 2026-11-25.

## Context

These decisions were recorded in source comments in `core/engine-api/`. The comments are deleted. This file keeps the reasons that the code does not show.

## Decisions

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/HostAutolearnStorage.kt#recordHostAutolearnErase

A running or starting engine keeps the learned hosts in memory. It writes them back on persist and at shutdown. An erase by the user therefore stays pending until `applyPendingHostAutolearnErase` deletes the store before the next engine loads it. Call the apply function only before a native runtime that loads the store starts.

A small window remains. `ProxyRuntimeSupervisor` knows only that its own previous native run has returned. No process-wide signal shows that a native proxy is alive. If an older native run outlives its stop timeout and writes after a new session applied the erase, the old hosts come back. A stop that times out already leaves that session in the cleanup-pending state.

The two file helpers return false on failure, so the caller shows "not cleared" and does not crash.

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/LocalSocksAuth.kt#LocalSocksCredentials

Every installed app can reach an Android loopback port. Each runtime session therefore gets a new RFC 1929 username and password pair from a CSPRNG. The pair stays in memory. Do not log, persist, export, or put it into telemetry.

The registry maps a listener address to the credentials that its owner issued. The owner registers the listener when it reports its address and removes it when it stops. Removal succeeds only while the entry still holds the same credentials, so a newer listener on the same port keeps its own. In-app clients that know only the address (probes that relay telemetry drives) read the credentials from the registry. The secret then never travels through telemetry or settings.

The client offers only the authentication method that matches the credentials. A listener that requires authentication never gets a session without authentication. An unresolved target goes out as a domain name, so the listener resolves it, as `java.net` SOCKS does.

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/RelayNativeConfig.kt#ResolvedRipDpiRelayConfig

`ResolvedRipDpiRelayConfig` is the wire contract with the Rust `FlatResolvedRelayRuntimeConfig` deserializer. Its field set, names, defaults, and the `finalmask` and `shadowTlsInner` objects change only together with the Rust side in the same commit (see `docs/architecture/CONFIG_CONTRACTS.md`).

The `Relay*Section` models only group the flat fields by concern. Only the flat DTO is serialized, so the sections have no `@Serializable` annotation and no JSON constraint. `toSections` and `toResolvedConfig` are exact inverses.

The schema version is 10. Every config must carry it. Missing and other versions are rejected. Version 10 requires explicit TLS fingerprint fields at the top level, in chain hops, and in the ShadowTLS inner config.

Rust declares `realityPublicKey` and `realityShortId` without a serde default. Kotlin must encode an empty value for them.

The ordered `chainHops` list goes on the wire only for chains with 3 or 4 hops. A two-hop chain stays in the `chainEntry*` and `chainExit*` fields, so its wire shape stays the same as in version 6. When `chainHops` is empty, the fold builds a two-element list from the scalar fields. Scalar fields are derived mirrors of the first and last hop. They are not the executable identity. Intermediate hops cannot go through the flat two-hop slots. Today the list has two hops.

The Naive and pluggable-transport helper inputs are `@Transient`. They are Android helper launch inputs and the native flat schema has no such fields.

The string form of the DTO must not print relay and listener secrets. A chain hop count outside the minimum and maximum raises `RelayChainHopCountException` with the count. Nothing truncates the list silently. An empty hop list is valid only for a config that is not a chain relay.

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/RipDpiAmneziaWgRuntime.kt#defaultAmneziaWgReadyTimeoutMs

The ready timeout is 15 seconds. This is three WireGuard `REKEY_TIMEOUT` periods of 5 seconds. WireGuard resends an unanswered initiation only after one period. The deadline must also cover the answer to the resend, so one lost datagram is recoverable. The WebSocket carrier has a longer deadline so that WSS setup and peer authentication do not change the UDP deadline.

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/RipDpiAmneziaWgRuntime.kt#ResolvedRipDpiAmneziaWgConfig

Field names mirror the Rust `AmneziaWgProfileConfig` (serde camelCase). `RipDpiAmneziaWgConfigSerializationTest` guards the keys, because an unknown key is ignored and a missing key fails deserialization. The carrier fields and the nested v3 object have defaults and are not encoded when default. A profile that omits them, and a native core that predates the carrier, load without change. The native schema does not change. The carrier tokens (`udp`, `ws`) are pinned with `@SerialName`.

The endpoint host, the port, and the WebSocket URL are user config. They must not reach telemetry or logs in plain form (see `.claude/rules/network-fingerprint-privacy.md`). Keys, endpoints, and tunnel addresses show only their presence in the string form.

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/RipDpiWarpRuntime.kt#defaultWarpReadyTimeoutMs

The ready timeout is 25 seconds. It covers the native DNS budget of 10 seconds and the authenticated handshake budget of 12 seconds.

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxyUIConfig.kt#RipDpiProxyUIConfig

`fakeSni` sets a cover domain for the TLS SNI of the WebSocket tunnel. The native runtime refuses a fake-SNI cover unless the operator also opts in to disabled certificate verification. The relay listener credentials are set only at runtime and never persisted. The default WireGuard keepalive is 25 seconds, the historical WARP value. WARP uses no preshared key. A generic AmneziaWG peer may use one.

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/XrayNativeBridge.kt#XrayNativeBridge

Register the protect controller with `registerProtect` before `start`. Xray-core opens outbound sockets as soon as it starts. If the controller is not yet registered, the first outbound socket goes into the VPN TUN route and a packet loop starts (see `.claude/rules/vpnservice-protect-invariant.md`). A protect result of false must make libXray close the socket and fail the connection. It must not continue unprotected.

A start code of 0 means only that the start was accepted. Readiness comes from `listenerReady`. `isAlive` lets the caller fail fast with a crash cause and not wait for the full readiness timeout. Stop must throw unless native cleanup is confirmed. Every blocking native operation runs on the process-owned native worker.

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/XrayRuntimeOwner.kt#XrayRuntimeOwner

The native lane is process-owned. Bind it once per process, not to a service scope. Cancelling an await never cancels a native operation and never releases the session lease. Each lease admits one start, one observation, and one stop, even if the Go side hangs. The relay bootstrap lookup runs off the native lane, so a restart can resolve while the live session holds its lease. A detached lookup bounds the blocking DNS jobs that cancellation could queue. The stop path completes its async work only after the state is published and released, never while it holds the lock.

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/XrayProviderOrchestrator.kt#XrayProviderOrchestrator

Start Xray before the tunnel. The runtime registers the protect controller before libXray opens a socket. The orchestrator then waits for readiness and only then points the tunnel at the inbound. A tunnel that starts first sends packets to a port that does not listen yet.

The tunnel upstream comes from the Xray inbound. When the inbound port or the provider kind changes, restart both Xray and the tunnel together. If only one half restarts, the tunnel points at a dead inbound. A route that is equal to the active route keeps the session. A forced replacement also covers changes that do not move the loopback port (credentials, DNS, routing rules, upstream server).

A new runtime for each start keeps the provider state machine linear. Each session starts from `Stopped`. The rendered config is held only during the start call, so the orchestrator keeps no secrets. On any failure, tear down both halves so the caller never sees a half-started session. Set the active runtime and route before the first native side effect so cleanup also covers a partial start. If cleanup is incomplete, the route and the TUN barrier stay owned. The orchestrator never touches the TUN fd. The service owns it (see `docs/architecture/JNI_CONTRACT.md`). The caller serializes the lifecycle calls, so tunnel implementations need no internal locking.

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/XrayTunnelHandoff.kt#XrayTunnelHandoff

The native provider returns `TunnelUpstream.Native`. It carries no Xray endpoint and keeps the RIPDPI tun2socks upstream. Xray is opt-in and does not change the native path.

The tunnel, not Xray, owns DNS in the `TunToLocalInbound` topology. The tunnel already resolves DNS locally and sends only the resolved connections to the SOCKS inbound. If Xray also resolved DNS through its own outbound, a second DNS path would exist with different caching, leak, and strategy behavior. That outbound socket is protected, so it would not loop into the TUN. The two owners do not overlap: the tunnel owns resolution and the protect seam owns socket routing. `DnsLoopOwner.Xray` is reserved for the future `LibXraySetTunFd` topology, where Xray owns the packet loop and DNS. That topology fails fast today. It must not fall through to a path that works but leaks. The inbound host is always loopback, never a routable address.

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/routing/DestinationRoutingPolicy.kt#DestinationRoutingPolicy

The first matching rule in list order wins. A destination with no match uses `defaultAction`, which defaults to the tunneled path on purpose. The policy copies its collections and exposes unmodifiable views, so a compiled snapshot cannot change after publication.

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/SshHostKeyProbeContract.kt#SshProbeSocketController

JNI calls the controller before connect. The owner must protect and bind the socket, or deny it.

### core/engine-api/src/test/kotlin/com/poyka/ripdpi/core/XrayProtectFdContractTest.kt#XrayProtectFdContractTest

Every non-loopback outbound socket of Xray must pass through `VpnService.protect` before connect or bind. A denial fails that socket. Loopback inbound sockets are never offered to protect, because the rule limits protect to non-loopback targets. The real gomobile bridge is not verified in CI. A fake bridge stands in for its contract.

### core/engine-api/src/test/kotlin/com/poyka/ripdpi/core/XrayServiceLifecycleMatrixTest.kt#XrayServiceLifecycleMatrixTest

The test uses an Unconfined native-stop dispatcher, and production uses `Dispatchers.IO`. With the Unconfined dispatcher, the fake bridge runs stop on the test thread. A real IO worker races the `runTest` virtual clock and makes the test flaky.
