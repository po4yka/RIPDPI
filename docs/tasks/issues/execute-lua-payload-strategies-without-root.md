---
id: RST-1790669115219534
title: Execute Lua payload strategies without root
kind: feature
status: review
area: rust-native
priority: high
owner: Native strategy
parent: null
blocked_by: []
spec_mode: required
openspec_change: lua-nonroot-socket-execution
created: 2026-09-29
updated: 2026-09-29
status_detail: Implementation published to main; remote CI evidence remains pending.
---

## Goal

Execute supported Lua payload plans on the existing protected TCP and UDP sockets on non-root Android devices. Keep raw packet operations on the opt-in root path.

## Acceptance criteria

1. Settings carry the YAML and the app-private Lua jail to the native proxy in both UI and command-line modes.
2. TCP Write, ordered Split, and validated payload-only zapret plans run without raw sockets. UDP payload replacement sends one real datagram.
3. Unsupported plans follow on_fail before any socket write. Failed TUN replacement injection forwards the original packet.
4. Lua state has a per-flow owner and is released on flow teardown. The same Lua step does not run in both TUN and proxy.
5. Relevant native, Kotlin contract, and non-root Android checks have recorded results. Commit and push the result to main as requested.

## Ownership

- Primary: proxy execution, flow lifetime, TUN executor, Cargo.lock, documentation, and combined validation in lua-nonroot.
- Lua worker: strategy-lua, strategy-registry, and adapter socket_lua.rs in lua-socket-planner.
- Transport worker: Kotlin runtime config and services, Rust proxy runtime-context fields, and tunnel config fields in lua-config-transport.
- Golden specialist: only the authorized TUN field manifest in lua-tun-contract.
- Review and test agents: read-only validation. No shared-file writers.
