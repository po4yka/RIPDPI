---
title: Audit and add VLESS xtls-rprx-vision-udp443 flow support
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

- [ ] #task Audit and add VLESS xtls-rprx-vision-udp443 flow support #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-vless-flow-xtls-rprx-vision-udp443-support`
- **Verify:** `cargo test -p ripdpi-vless`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-vless/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Audit `ripdpi-vless` flow coverage. xray-core exposes
`xtls-rprx-vision` and the UDP-443 variant `xtls-rprx-vision-udp443`.
Confirm `addons::VISION_ADDONS` matches one of them; add the UDP-443
variant if missing.

## Context

The current `addons.rs` exports a single `VISION_ADDONS` constant.
xray-core has shipped flow variants over time; mismatching the flow
string on the wire produces immediate handshake failure. With
xray-core deprecating VLESS-without-flow on 2026-06-01, the cost of
shipping the wrong flow goes up.

## Acceptance criteria

- [ ] A comment in `addons.rs` cites the upstream commit/tag the
    addons bytes were derived from.
- [ ] If only one flow is supported, the config rejects requests for
    other flows with a clear error.
- [ ] If multi-flow support is decided, expose `flow: VlessFlow` and
    cover each variant with a wire-encoding test.

## Definition of done

- Operators using a UDP-443 flow server can connect, or receive a
  clear "flow not supported" diagnostic.

## Links

- [[recurring-upstream-watch-for-xray-core-reality-ech-xhttp-changes]]
