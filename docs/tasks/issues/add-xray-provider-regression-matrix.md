---
title: Add Xray provider regression matrix
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-xray-provider-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Add Xray provider regression matrix #repo/RIPDPI #area/outbound #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-xray-provider-regression-matrix`
- **Verify:** `./gradlew :core:engine:testDebugUnitTest :core:service:testDebugUnitTest`
- **Scope (only modify these + this file + the ledger):** `core/engine/**`, `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add focused automated coverage for the first Xray provider integration.

## Context

The risky parts are lifecycle, config rendering, socket protection, DNS loops, provider telemetry, and Android VPN handoff. Tests should lock those down before Xray mode becomes a default or recommended fallback.

## Acceptance criteria

- [ ] Config golden tests cover VLESS/REALITY, XHTTP, invalid combinations, and redaction.
- [ ] Service tests cover Xray startup failure, readiness timeout, stop, restart, and handover behavior.
- [ ] Protect-fd tests prove Xray dialer/listener sockets use the Android VPN protection path.
- [ ] DNS-loop regression proves provider bootstrap DNS does not re-enter TUN.
- [ ] Device/emulator smoke test verifies active VPN traffic exits through the Xray outbound path.
- [ ] CI or documented manual lanes identify which Xray tests need network, emulator, or private fixture dependencies.

## Notes

Keep private endpoints out of fixtures. Use local synthetic fixtures or operator-provided private test profiles outside the vault.

## Links

- [[Epic - Xray provider mode]]
- [[Bridge TUN traffic through Xray local inbound]]
- [[Surface Xray diagnostics and telemetry]]
- ripdpi-android-xray-provider-plan-2026-04-24
