---
title: Add mixed SOCKS5 and HTTP CONNECT inbound listener
type: task
status: backlog
area: proxy
priority: medium
owner: unassigned
parent: epic-system-http-proxy-service-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add mixed SOCKS5 and HTTP CONNECT inbound listener #repo/RIPDPI #area/proxy #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-mixed-socks5-and-http-connect-inbound-listener`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `native/rust/crates/ripdpi-socks5-core/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Extend the existing local-SOCKS5 inbound into a "mixed" inbound that also
speaks HTTP CONNECT on the same port (protocol detected from the first
bytes).

## Context

Reference implementation's `mixedPort` accepts SOCKS5 greeting and HTTP CONNECT on one TCP
port. For local-only traffic from apps that honor the Android system
proxy, this is the simplest path. First-byte switch: `0x05` → SOCKS5,
`CONNECT ` prefix → HTTP.

## Acceptance criteria

- [ ] Single listener binds a configurable port (default 2080) and
    dispatches per-connection to SOCKS5 or HTTP CONNECT handler.
- [ ] HTTP CONNECT supports TLS tunnels only; no HTTP proxying of
    cleartext requests (no TLS interception anywhere).
- [ ] No authentication; listener is bound to `127.0.0.1` by default.
    An opt-in "allow LAN" toggle binds to all interfaces with a stern
    warning modal.
- [ ] Port collision surfaces a typed error with suggested next port.
- [ ] Both SOCKS5 and CONNECT paths route through the existing outbound
    dispatch; no parallel supervisor.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt` — the sing-box `mixed` inbound generation (search for `"mixed"` as `type:` value). reference implementation delegates the actual listener to sing-box; `mixedPort` from `DataStore` flows into the generated JSON config.
- `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — `mixedPort` property (default 2080), `socksPort`, `httpPort`. Port the default port, offset-by-user-index pattern (for multi-user Android support).

**Outbound engine (NOT from reference implementation):** sing-box's `protocol/mixed` inbound in Go handles the first-byte dispatch (`0x05` → SOCKS5, `CONNECT ` → HTTP). RIPDPI implements this in Rust — simple state machine, ~50 lines. Reuse the existing SOCKS5 inbound code in `ripdpi-runtime`; add HTTP CONNECT branch.

**Adapt:** Default port 2080, multi-user port-offset pattern. **Skip:** sing-box Go implementation.

## Links

- [[Epic - System HTTP proxy service mode]]
