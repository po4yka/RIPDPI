---
title: Surface Xray diagnostics and telemetry
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-xray-provider-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Surface Xray diagnostics and telemetry #repo/RIPDPI #area/outbound #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `surface-xray-diagnostics-and-telemetry`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `core/data/runtime-state/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Expose Xray provider state in Home, Diagnostics, exports, and service
telemetry.

## Motivation

The app should make Xray failures diagnosable without turning user profiles or
live endpoints into logs. Existing diagnostics already distinguish native
proxy, relay, WARP, and tunnel state; Xray needs the same typed treatment.

## Scope

- In scope: runtime snapshot fields, Xray version, readiness, listener state,
outbound health, config-validation errors, ping/stat probes where safe, and
redacted export summaries.
- Out of scope: full packet capture of tunneled traffic and endpoint disclosure
in logs or task notes.

## Acceptance criteria

- [ ] Home connection stages identify Xray provider readiness and provider
    failures distinctly from tunnel failures.
- [ ] Diagnostics can run a provider-path check through the active Xray mode.
- [ ] Export/share summaries redact profile credentials and live endpoints.
- [ ] Xray API/stat probing is used only when enabled safely for the Android
    runtime topology.
- [ ] Regression fixtures cover provider healthy, config invalid, protect
    failure, DNS-loop suspected, and outbound unreachable states.

## Design notes

If Xray metrics/API mode is not safe in-process, prefer wrapper `Ping`,
`XrayVersion`, listener readiness, and existing tunnel telemetry for the first
build.

## Risks / open questions

- Provider diagnostics can accidentally become a reachability scanner. Keep it
user-triggered and tied to the active profile.

## Links

- [[Epic - Xray provider mode]]
- [[Run Xray as managed VPN relay runtime]]
- [[ripdpi-android-xray-provider-plan-2026-04-24]]
