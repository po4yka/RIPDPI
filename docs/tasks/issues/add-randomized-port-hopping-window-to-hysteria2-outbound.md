---
title: Add randomized port-hopping window to Hysteria2 outbound
type: task
status: backlog
area: transport
priority: medium
owner: unassigned
parent: epic-composable-transport-layer-parity
blocks: []
blocked_by: []
created: 2026-04-25
updated: 2026-04-25
---

- [ ] #task Add randomized port-hopping window to Hysteria2 outbound #repo/RIPDPI #area/transport #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-randomized-port-hopping-window-to-hysteria2-outbound`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-hysteria2`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-hysteria2/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Hysteria 2.8.0 introduced `minHopInterval` / `maxHopInterval` so port-hopping intervals can be randomized within a configured window instead of using a fixed cadence. Adopt the same fields in RIPDPI's Hysteria2 outbound config so the per-session hopping interval becomes unpredictable to interval-based DPI traffic classifiers.

## Research citation

[[ripdpi-android-research-2026-04-25]] §Upstream transport engines — Hysteria app/v2.8.0 (2026-03-30) added `minHopInterval` / `maxHopInterval` for randomized port-hopping (vs the previous fixed interval), reducing predictability for DPI traffic classifiers. Same release added selectable congestion control (3 BBR profiles + Reno) and server-side UDP port-range listening with auto nftables/iptables rule injection.

## Acceptance criteria

- [ ] Outbound config schema gains `minHopInterval` / `maxHopInterval` fields per Hysteria 2.8.0
- [ ] Runtime randomizes hop interval per session within the configured window
- [ ] Telemetry surfaces actual interval distribution per session for verification
- [ ] Backward compatibility: omitted fields fall back to existing fixed-interval behavior

## Links

- Project: [[ripdpi-android]]
- Epic: [[Epic - Composable transport layer parity]]
- Research: [[ripdpi-android-research-2026-04-25]] §Upstream transport engines
