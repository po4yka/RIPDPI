# ripdpi-tor

**Responsibility:** opt-in Arti-backed Tor client backend for relay-core TCP connections.

This crate wraps `arti-client` for RIPDPI's relay backend boundary. It builds Arti client config from TOML or from explicit bridge plus pluggable-transport inputs, prepares app-owned Arti state/cache directories, validates censored-network bridge/PT configuration, and adapts `TorClient::connect((host, port))` to relay-core's boxed async TCP stream.

## Current Scope

- TCP relay connections through Arti are supported through `TorRelayClient::connect_tcp`.
- Hostname resolution through Tor is exposed by `TorRelayClient::resolve_hostname`.
- Bridge+PT config requires at least one bridge line and at least one matching PT binary.
- Direct bridge lines are rejected for the bridge+PT profile path; configured bridges must name a transport that has a matching PT binary entry.
- State and cache directories are created and write-probed before use.
- The relay-core integration is `RelayBackendConfig::Tor` / `RelayKind::Tor` / `RelayBackend::Tor`; Android resolver code disables UDP for Tor profiles and derives Arti paths from app-private storage.

## Non-Goals

- UDP over Tor.
- Running a Tor relay or onion service.
- Replacing low-latency proxy relays as the default.
- Custom Tor path policy.
- Bundling obfs4, WebTunnel, or Snowflake implementations inside Arti.

## Tests

Focused tests live in `tests/bridge_pt_config.rs`, `tests/client.rs`, `tests/state_dns.rs`, and `tests/chutney.rs`. Use `cargo test -p ripdpi-tor` for crate coverage; relay integration is covered from `ripdpi-relay-core` tests that build and dispatch the Tor backend.
