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

## Wired, Verified, and Size Cost

This backend is wired end to end and verified: `ripdpi-relay-core` registers `build_tor` in the transport descriptor, `builders/tor.rs` constructs `RelayBackend::Tor` only through `from_bridge_pt_config`, and `RelayKind::Tor` resolves at schema version 6 with `udp_capable = false`. The censored-network threat model is enforced in code and pinned by tests: `build_bridge_pt_config` requires at least one bridge line and one PT binary and rejects direct (non-PT) bridge lines as `DirectBridgeLine`, so there is no direct-bootstrap path through the wired backend (`tests/bridge_pt_config.rs`); `tor_backend_builds_in_process_and_rejects_udp` in relay-core pins the TCP-only contract. Live-circuit coverage (`tests/chutney.rs`) runs against a local Chutney Tor network when `RIPDPI_TOR_CHUTNEY_E2E=1` is set.

Size cost: pulling `arti-client` into the relay cdylib adds `arti-client` plus 36 `tor-*` crates -- about **1.2 MiB of `.text` on `arm64`** (measured via `cargo bloat --crates` on the host `aarch64` target at `opt-level = z`; `tor-proto`, `arti-client`, `tor-netdoc`, `tor-circmgr`, `tor-dirmgr`, and `tor-guardmgr` dominate). `arti-client` is an unconditional dependency, so every shipped build pays this cost even when no Tor profile is enabled. The feasibility gate that accepts this cost is recorded in [ADR 0002](../../../../docs/adr/0002-tor-feasibility.md), including the recommendation to feature-gate Arti if the binary-size budget tightens.

## Non-Goals

- UDP over Tor.
- Running a Tor relay or onion service.
- Replacing low-latency proxy relays as the default.
- Custom Tor path policy.
- Bundling obfs4, WebTunnel, or Snowflake implementations inside Arti.

## Tests

Focused tests live in `tests/bridge_pt_config.rs`, `tests/client.rs`, `tests/state_dns.rs`, and `tests/chutney.rs`. Use `cargo test -p ripdpi-tor` for crate coverage; relay integration is covered from `ripdpi-relay-core` tests that build and dispatch the Tor backend.
