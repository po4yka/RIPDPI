# Change: Serve the VPN tunnel from a private Unix proxy listener

Task ID: `VPN-1795773987131888`

## Why

In VPN mode the TUN-to-SOCKS tunnel reaches the native proxy through a TCP listener on `127.0.0.1`. The listener uses an ephemeral port and a session token, so another app cannot use it. Another app can still find it with a loopback port scan and can see that an authenticated SOCKS listener exists. Every installed app can connect to loopback; no app can open a socket file in the private files directory of this app. A Unix domain socket in that directory removes the listener from the loopback surface for this hop.

## What Changes

- In VPN mode with the native proxy provider, the proxy listens on a Unix domain socket in the app-private files directory. It does not bind a TCP listener for the tunnel-to-proxy hop.
- The tunnel sends TCP flows and UDP flows to the proxy through that socket. UDP uses the existing private association with the same datagram framing; the datagram socket is a passed `AF_UNIX` socket instead of a loopback UDP socket.
- The in-app clients that measure the active tunnel path (onboarding validation, onboarding volume check, selector payload probe, field report upload, in-path diagnostics scan) use the same Unix listener.
- BREAKING: the tunnel JNI config payload changes from schema version 3 to 4 and gains a Unix upstream path.
- BREAKING: the proxy session overrides gain a Unix listen path; VPN mode no longer sends `listenPortOverride = 0`.
- BREAKING: the diagnostics in-path route contract gains a Unix path form.
- BREAKING: the runtime listener-started telemetry callback reports a listener address that can be a Unix path.
- Proxy mode keeps its TCP listener. The Xray provider keeps its loopback inbound.

## Capabilities

### New Capabilities

- `vpn-private-proxy-listener`: the VPN-mode transport between the tunnel, the in-app path clients, and the native proxy, and the rules that keep it off the loopback surface.

### Modified Capabilities

- None. `socks5-token-auth`, `socks5-udp-association-contracts`, and `shared-proxy-listener` keep their requirements for TCP listeners; the new capability states which of their rules also apply to the Unix listener.

## Impact

- Rust crates: `ripdpi-proxy-runtime`, `ripdpi-proxy-runtime-adapter`, `ripdpi-android-proxy-adapter`, `ripdpi-proxy-config`, `ripdpi-config`, `ripdpi-runtime-api`, `ripdpi-android-telemetry-adapter`, `ripdpi-ws-transport-port`, `ripdpi-ws-tunnel`, `ripdpi-socks5-core`, `ripdpi-tunnel-core`, `ripdpi-tunnel-config`, `ripdpi-tunnel-android`, `ripdpi-diagnostics-contracts`, `ripdpi-diagnostics-transport`.
- Kotlin modules: `:core:engine`, `:core:engine-api`, `:core:service`, `:core:data` (runtime state), `:core:diagnostics`, `:app`.
- Contracts: tunnel config payload schema, proxy session overrides, diagnostics scan request, Rust API snapshots, round-trip fixtures, `docs/architecture/CONFIG_CONTRACTS.md`, `docs/architecture/RUNTIME_MODES.md`, `docs/native/proxy-engine.md`.
- No persisted setting changes. No storage migration.
