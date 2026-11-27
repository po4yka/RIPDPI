---
id: VPN-1795773987131888
title: Serve the VPN tunnel from a private Unix proxy listener
kind: feature
status: doing
area: vpn
priority: medium
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: vpn-1795773987131888-serve-the-vpn-tunnel-from-a-private-unix-proxy-listener
created: 2026-11-27
updated: 2026-11-27
---

## Goal

In VPN mode with the native proxy provider, the tunnel and the in-app path clients reach the proxy through a Unix domain socket in the app-private files directory. No TCP listener exists on loopback for that hop, so another app cannot find or reach it.

## Acceptance criteria

- A native VPN session owns no loopback or wildcard TCP listener for the tunnel-to-proxy hop; the emulator end-to-end test proves it while TCP and UDP round trips pass.
- UDP keeps the private association framing and remote-error feedback over a passed `AF_UNIX` datagram socket.
- Onboarding validation, the volume check, the selector payload probe, the field report upload, and the in-path diagnostics scan work through the Unix listener, including HTTPS on the lowest and the current API level.
- Proxy mode and the Xray provider keep their TCP listeners.
- The start fails closed when the socket cannot be bound; no TCP listener replaces it.

Ownership: one writer in the `feat/vpn-unix-proxy-listener` worktree. Serialized shared files: `EngineContract.kt`, diagnostics schema-version constants, Rust `api-snapshot.txt` files, round-trip fixtures, and `native/rust/Cargo.toml`.
