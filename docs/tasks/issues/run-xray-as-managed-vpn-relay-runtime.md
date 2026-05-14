---
title: Run Xray as managed VPN relay runtime
type: task
status: backlog
area: outbound
priority: high
owner: unassigned
parent: epic-xray-vpn-client-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Run Xray as managed VPN relay runtime #repo/RIPDPI #area/outbound #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `run-xray-as-managed-vpn-relay-runtime`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Implement a supervised Xray runtime that starts, reports readiness, exposes
health, and stops cleanly inside RIPDPI's Android service layer.

## Motivation

Xray must behave like the existing managed proxy/relay runtimes: no ambiguous
"running" state before listeners bind, no silent crashes, no leaked native
resources, and no recursive VPN socket loops.

## Scope

- In scope: `RunXrayFromJSON` startup, `StopXray` shutdown, protect-controller
registration, DNS initialization, readiness probing, state mapping, telemetry
snapshots, and supervisor exit causes.
- Out of scope: UI profile editing and non-Xray providers.

## Acceptance criteria

- [ ] Runtime registers libXray dialer/listener protection before starting
    Xray.
- [ ] Startup waits for a concrete listener or verified Xray state before VPN
    tunnel handoff.
- [ ] Stop path is bounded, idempotent, and reports typed clean/failed stop
    causes.
- [ ] Xray version and basic provider state flow into service telemetry without
    exposing profile secrets.
- [ ] Unit or service tests cover startup failure, invalid config, late stop,
    and crash/exit mapping.

## Design notes

Map Xray readiness and stop outcomes into the same service-level language used
for proxy, relay, WARP, and tunnel runtimes.

## Risks / open questions

- libXray wrapper calls may be process-global; the app should assume only one
active Xray instance until proven otherwise.
- Metrics/API mode may require a child process according to upstream notes;
do not rely on it until tested on Android.

## Links

- [[Epic - Xray VPN client mode]]
- [[Package libXray for Android ABIs]]
- [[Render validated Xray client configs]]
- [[ripdpi-android-xray-vpn-client-plan-2026-04-24]]
