---
title: Gate fake-SNI cert-bypass behind allow_insecure_sni flag with telemetry
type: task
status: done
area: rust-native
priority: high
owner: unassigned
parent: epic-control-plane-hardening
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-29
---

- [x] #task Gate fake-SNI cert-bypass behind allow_insecure_sni flag with telemetry #repo/RIPDPI #area/rust-native #status/done 🔼

## Summary

Make the WS-tunnel "fake SNI" mode require an explicit `allow_insecure_sni: true` config field and emit a runtime telemetry counter every time a connection is established with TLS verification disabled, so misconfiguration is visible at deploy time.

## Context

`native/rust/crates/ripdpi-ws-tunnel/src/lib.rs:35-37` documents:

> Certificate validation is disabled when fake SNI is active.

This is intentional for the Telegram-WSS-impersonation path, but the current config surface allows enabling fake SNI silently with no operator acknowledgment and no operational signal afterward. By contrast, the Reality TLS path (`native/rust/crates/ripdpi-vless/src/reality.rs:85`) is also bypassing standard cert verification, but Reality has a dedicated cryptographic auth model; fake-SNI does not.

## Acceptance criteria

- [x] (2026-05-15) `WsTunnelConfig.fake_sni` is only honored when `allow_insecure_sni == true`; otherwise the runtime returns a `PermissionDenied` `io::Error` before opening the socket. Wired in `ripdpi-ws-tunnel/src/lib.rs::relay_ws_tunnel_with`.
- [x] (2026-05-15) The error path is exercised by a unit test: `relay_ws_tunnel_refuses_fake_sni_without_allow_insecure_acknowledgement` plus a positive `relay_ws_tunnel_honours_fake_sni_when_allow_insecure_sni_is_set`.
- [x] (2026-05-29) A new `wsTunnelFakeSniActive` counter is incremented in runtime telemetry per successful fake-SNI handshake; tests assert it fires only on the fake-SNI path. `RuntimeTelemetrySink::on_ws_tunnel_fake_sni_active` (default no-op) is fired from the ws-fallback success arm gated on `RuntimeState::ws_tunnel_fake_sni_active()`; the counter is an `AtomicU64` in `ProxyTelemetryState` surfaced through `NativeRuntimeSnapshot.wsTunnelFakeSniActive` (Rust + Kotlin). Covered by `fake_sni_counter_fires_only_when_cover_and_opt_in_are_both_set` / `fake_sni_counter_silent_without_opt_in_or_cover` and `proxy_ws_tunnel_fake_sni_counter_increments_and_surfaces_in_snapshot`.
- [x] (2026-05-29) Service-layer refuses to persist `fake_sni` without `allow_insecure_sni`. The hardcoded `allow_insecure_sni: false` in the adapter is replaced by config plumbing (`ws_tunnel_allow_insecure_sni` through `RuntimeAdaptiveSettings` → `WsTunnelSettings`); the settings-restore path sanitises an unacknowledged cover via `WsProfileImportValidator` (`sanitizeRestoredWsTunnelFakeSni`), and the advanced-settings UI toggle prevents the unsafe combination at write time. (`XrayConfigValidator` remains a ready unit-tested gate with no import flow to wire into yet — blocked on the xray-import task.)
- [x] (2026-05-29) `docs/native/proxy-engine.md` documents the flag and the telemetry counter — see the "Fake-SNI cover domain" subsection under MTProto WebSocket Tunnel.

## Definition of done

- [x] A config with `fake_sni` set but `allow_insecure_sni` unset cannot start the tunnel and produces a recognizable failure-classifier result.
- [x] Telemetry counter is visible in the diagnostics export (`NativeRuntimeSnapshot.wsTunnelFakeSniActive`, serialized through `pollTelemetry`).

## Risks / open questions

- Existing serialized profiles may carry `fake_sni` without the new flag. Decide whether to migrate them or reject them; default should be reject + surface a one-time UI warning.

## Links

- [[Epic - Control-plane hardening]]
- add-no-secret-logging-and-diagnostics-redaction-tests (closed task)
