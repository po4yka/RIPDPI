---
title: Gate fake-SNI cert-bypass behind allow_insecure_sni flag with telemetry
type: task
status: backlog
area: rust-native
priority: high
owner: unassigned
parent: epic-control-plane-hardening
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Gate fake-SNI cert-bypass behind allow_insecure_sni flag with telemetry #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `gate-fake-sni-cert-bypass-behind-allow-insecure-flag-with-telemetry`
- **Verify:** `cargo test -p ripdpi-ws-tunnel -p ripdpi-vless && ./gradlew :core:engine:testDebugUnitTest`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-ws-tunnel/**`, `native/rust/crates/ripdpi-tls-profiles/**`, `core/engine/**`, `docs/native/proxy-engine.md`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Make the WS-tunnel "fake SNI" mode require an explicit
`allow_insecure_sni: true` config field and emit a runtime telemetry
counter every time a connection is established with TLS verification
disabled, so misconfiguration is visible at deploy time.

## Context

`native/rust/crates/ripdpi-ws-tunnel/src/lib.rs:35-37` documents:

> Certificate validation is disabled when fake SNI is active.

This is intentional for the Telegram-WSS-impersonation path, but the
current config surface allows enabling fake SNI silently with no
operator acknowledgment and no operational signal afterward. By
contrast, the Reality TLS path
(`native/rust/crates/ripdpi-vless/src/reality.rs:85`) is also bypassing
standard cert verification, but Reality has a dedicated cryptographic
auth model; fake-SNI does not.

## Acceptance criteria

- [x] (2026-05-15) `WsTunnelConfig.fake_sni` is only honored when
    `allow_insecure_sni == true`; otherwise the runtime returns a
    `PermissionDenied` `io::Error` before opening the socket. Wired
    in `ripdpi-ws-tunnel/src/lib.rs::relay_ws_tunnel_with`.
- [x] (2026-05-15) The error path is exercised by a unit test:
    `relay_ws_tunnel_refuses_fake_sni_without_allow_insecure_acknowledgement`
    plus a positive
    `relay_ws_tunnel_honours_fake_sni_when_allow_insecure_sni_is_set`.
- [ ] A new `ws_tunnel.fake_sni_active` counter is incremented in
    runtime telemetry per successful handshake; tests assert it fires
    only on the fake-SNI path. **DEFERRED:** ws-tunnel telemetry
    surface is owned by the adapter layer; tracked separately.
- [ ] Service-layer profile import refuses to persist a profile that
    sets `fake_sni` without `allow_insecure_sni`. **DEFERRED:**
    adapter at
    `ripdpi-proxy-runtime-adapter/src/model/config/ws_tunnel.rs:42`
    currently hardcodes `allow_insecure_sni: false`, so catalog
    profiles with `fake_sni` are refused at runtime via the new
    `PermissionDenied` error. Service-layer rejection at *import*
    time pairs with new `WsTunnelSettings.allow_insecure_sni`
    plumbing.
- [ ] `docs/native/proxy-engine.md` documents the new flag and
    links the telemetry counter. **DEFERRED:** add when the
    telemetry counter lands.

## Definition of done

- A config with `fake_sni` set but `allow_insecure_sni` unset cannot
  start the tunnel and produces a recognizable failure-classifier
  result.
- Telemetry counter is visible in the diagnostics export.

## Risks / open questions

- Existing serialized profiles may carry `fake_sni` without the new
  flag. Decide whether to migrate them or reject them; default should
  be reject + surface a one-time UI warning.

## Links

- [[Epic - Control-plane hardening]]
- [[add-no-secret-logging-and-diagnostics-redaction-tests]]
