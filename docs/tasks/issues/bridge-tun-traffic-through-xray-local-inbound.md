---
title: Bridge TUN traffic through Xray local inbound
type: task
status: backlog
area: outbound
priority: high
owner: unassigned
parent: epic-xray-provider-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Bridge TUN traffic through Xray local inbound #repo/RIPDPI #area/outbound #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `bridge-tun-traffic-through-xray-local-inbound`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `native/rust/crates/ripdpi-tunnel-core/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Route Android VPN TUN traffic through Xray's local inbound for the first Xray tunneled outbound profile milestone.

## Motivation

RIPDPI already has a well-tested TUN-to-SOCKS path with DNS interception, handover handling, and telemetry. Using Xray as the local inbound preserves that path while adding Xray outbound support.

## Scope

- In scope: local Xray SOCKS/HTTP inbound selection, tunnel config handoff, auth/localhost hardening, DNS-loop avoidance, handover restart behavior, and traffic-smoke validation.
- Out of scope: shipping direct `libXray.SetTunFd` until lifecycle and telemetry parity are proven.

## Acceptance criteria

- [ ] VPN startup can select Xray as the tunnel's upstream local endpoint.
- [ ] Xray outbound sockets and DNS are protected so provider traffic does not loop into the TUN fd.
- [ ] Existing tunnel telemetry remains available when the upstream endpoint is Xray instead of RIPDPI-native proxy.
- [ ] Network handover restarts both Xray and tunnel when the local inbound or provider route changes.
- [ ] A local/device smoke test proves traffic exits through the Xray outbound.

## Design notes

Keep the direct `SetTunFd` path as an explicit follow-up decision, not an accidental first implementation.

## Risks / open questions

- Xray local inbound authentication support must be validated before exposing any localhost listener beyond the tunnel's private use.
- DNS interception ownership needs one clear source of truth: RIPDPI tunnel, Xray DNS, or a deliberately split model.

## Links

- [[Epic - Xray provider mode]]
- [[Run Xray as managed VPN relay runtime]]
- ripdpi-android-xray-provider-plan-2026-04-24
