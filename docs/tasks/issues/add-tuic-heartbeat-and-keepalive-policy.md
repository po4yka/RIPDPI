---
title: Add TUIC heartbeat and keepalive policy
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Add TUIC heartbeat and keepalive policy #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-tuic-heartbeat-and-keepalive-policy`
- **Verify:** `cargo test -p ripdpi-tuic`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-tuic/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add an explicit application-level heartbeat or QUIC `keep_alive_interval`
policy to `ripdpi-tuic` so long-lived UDP-over-QUIC tunnels do not
idle out under conservative NAT timers.

## Context

Mobile NATs aggressively reclaim UDP bindings (often <30s). QUIC's
default idle timeout is generous, but without keepalive frames or
app-level pings, intermediate NAT rebinding silently breaks the
tunnel. TUIC's `COMMAND_PACKET` flow is the most exposed.

## Acceptance criteria

- [x] (2026-05-15) `Config` exposes `keepalive_interval_ms: u32`
    (`u32` for serde/JNI friendliness; `0` disables). `#[serde(default)]`
    keeps legacy profiles deserializable.
- [x] (2026-05-15) `build_endpoint` calls
    `quinn::TransportConfig::keep_alive_interval(Some(Duration::from_millis(...)))`
    when the field is non-zero.
- [ ] Optionally, a TUIC-level `Heartbeat` command exchange is wired
    when the protocol supports one. **Deferred:** TUIC v5 wire does
    not define a heartbeat opcode separately; the QUIC-level
    keepalive carries NAT survival adequately for current
    deployments.
- [ ] Unit test verifies the keepalive frame timing on a loopback
    pair. **Deferred:** added serde-default test
    `legacy_config_without_keepalive_field_deserializes_with_zero`
    instead; on-wire keepalive timing requires a long-running
    loopback Quinn server harness (tracked under
    `add-port-hopping-window-soak-test-for-hysteria2` shared helper).

## Definition of done

- Idle TUIC tunnels survive a 60s mobile-NAT silence window in
  loopback tests.

## Links

- [[add-port-hopping-window-soak-test-for-hysteria2]]
