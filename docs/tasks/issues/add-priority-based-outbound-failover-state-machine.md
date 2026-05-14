---
title: Add priority-based outbound failover state machine
type: task
status: done
area: vpn
priority: high
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-14
---

- [x] #task Add priority-based outbound failover state machine #repo/RIPDPI #area/vpn #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-priority-based-outbound-failover-state-machine`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-strategy`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-runtime-strategy/**`, `native/rust/crates/ripdpi-runtime-policy/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Implement a priority-based outbound failover state machine that prefers primary REALITY, then HTTPS fallback, then Hysteria2 only when UDP is viable, while still allowing manual selector override.

## Motivation

Manual failover leaves users guessing which profile works. Latency-only auto-selection can choose a fast but fragile UDP path. RIPDPI needs policy-aware failover that understands censorship-bypass priorities.

## Scope

- In scope: connection states, health probes, manual selector override, URL-test style scoring, UDP viability gate, and UI state for active outbound.
- Out of scope: adding new transport protocols beyond the initial primary/fallback/speed roles.

## Acceptance criteria

- [ ] State machine represents `CONNECTED_PRIMARY`, `TRY_HTTPS_FALLBACK`, `TRY_HYSTERIA2`, `WHITELIST_MODE_HINT`, and `BLOCKED_RECONNECTING`.
- [ ] Default auto mode tests primary REALITY and HTTPS fallback before considering Hysteria2.
- [ ] Hysteria2 becomes an auto candidate only after UDP/443 viability is confirmed for the current network.
- [ ] Manual selector override is visible and can be reset to auto.
- [ ] Existing connections are not interrupted unless the user or emergency failover policy explicitly requests it.

## Design notes

This is different from subscription group selection. It is the runtime outbound policy for a single device profile.

## Risks / open questions

- Health probes must avoid creating a recognizable, high-frequency pattern against the same endpoints.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- [[Epic - Xray VPN client mode]]
- [[Add selector outbound runtime for group-based profile switching]]

## Work log

- 2026-05-14: Implemented the failover state machine as a new
  `outbound_failover` module in `ripdpi-runtime-strategy` (the crate the
  Verify command pins). Chosen over `ripdpi-runtime-policy` because the
  feature is a runtime *strategy* decision -- which outbound role carries
  traffic given current network + health signals -- a sibling concern to
  `strategy_evolver`; it is self-contained and pulls nothing from
  `ripdpi-runtime-policy`.
- `OutboundFailover` models the five required states: `ConnectedPrimary`,
  `TryHttpsFallback`, `TryHysteria2`, `WhitelistModeHint`,
  `BlockedReconnecting`. Auto mode walks `OutboundRole` in strict priority
  order (Primary REALITY -> HTTPS fallback -> Hysteria2).
- Hysteria2 is gated behind `UdpViability`: it becomes an auto candidate
  only when UDP/443 is *confirmed* `Viable`. `Unknown` UDP with both
  higher roles down yields `WhitelistModeHint` (actionable for the user);
  confirmed `Blocked` UDP yields a hard `BlockedReconnecting`. UDP turning
  `Blocked` while Hysteria2 is active re-evaluates immediately.
- Manual selector override (`set_manual_override` / `clear_manual_override`)
  pins a role and suspends auto transitions; clearing restarts the
  priority walk from the primary. Health is still recorded under override.
- Non-interruption invariant: routine `HealthProbe` triggers only advance
  state when the *active* role crosses the failure threshold; probing a
  non-active role is record-only. Only an explicit `EmergencyFailover`
  trigger may move off a still-healthy role. URL-test EWMA latency scoring
  and a `min_probe_spacing` gate (`probe_allowed`) keep health checks from
  forming a recognizable high-frequency pattern.
- Verify: `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-strategy`
  -> exit 0, 107 tests passed (26 new `outbound_failover` tests), 0 warnings.
