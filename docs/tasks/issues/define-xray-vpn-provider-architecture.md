---
title: Define Xray VPN provider architecture
type: task
status: todo
area: outbound
priority: high
owner: unassigned
parent: epic-xray-provider-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Define Xray VPN provider architecture #repo/RIPDPI #area/outbound #status/todo ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `define-xray-vpn-provider-architecture`
- **Verify:** `just test-module core:data:runtime-state`
- **Scope (only modify these + this file + the ledger):** `core/data/runtime-state/**`, `core/service/**`, `core/engine/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Define the local provider boundary for embedding Xray as a managed Android VPN
client runtime.

## Context

RIPDPI already has proxy, VPN tunnel, relay, WARP, native readiness, and typed
telemetry concepts. Xray support should reuse those lifecycle patterns instead
of adding a one-off service path.

Plan reference: [[ripdpi-android-xray-provider-plan-2026-04-24]].

## Acceptance criteria

- [ ] Provider model names the first supported provider kinds and the state
    transitions shared by native RIPDPI and Xray paths.
- [ ] Decision recorded for first tunnel topology: existing TUN-to-local-Xray
    inbound versus direct `libXray.SetTunFd`, with explicit tradeoffs.
- [ ] Required Kotlin/Rust/Go wrapper module boundaries are listed with owners:
    `:core:service`, `:core:engine`, and any generated Xray adapter module.
- [ ] Socket-protection, DNS-loop avoidance, telemetry, readiness, and stop
    semantics are described before implementation tasks start.
- [ ] The architecture doc links back to the epic and avoids storing endpoints,
    credentials, or sample live configs.

## Notes

Favor an adapter that hides libXray API churn from service and UI code.

## Links

- [[Epic - Xray provider mode]]
- [[ripdpi-android-xray-provider-plan-2026-04-24]]
