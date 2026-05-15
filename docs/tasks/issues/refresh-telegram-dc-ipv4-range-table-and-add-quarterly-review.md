---
title: Refresh Telegram DC IPv4 range table and add quarterly review
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

- [ ] #task Refresh Telegram DC IPv4 range table and add quarterly review #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `refresh-telegram-dc-ipv4-range-table-and-add-quarterly-review`
- **Verify:** `cargo test -p ripdpi-ws-tunnel -- dc`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-ws-tunnel/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Re-verify the Telegram DC IPv4 ranges in `dc::dc_from_ip` against
`core.telegram.org`'s current published ranges and add a quarterly
review obligation in the strategy-pack operations doc.

## Context

The current `dc_from_ip` table is IPv4-only (see
[[add-ipv6-telegram-dc-classification-to-ws-tunnel]] for the v6
gap). Telegram has rotated v4 ranges historically; a stale table
silently degrades to passthrough for traffic that should tunnel.

## Acceptance criteria

- [ ] `dc::tests` includes a "table provenance" test naming the
    last review date and the source URL.
- [ ] A quarterly review obligation is recorded in
    `docs/strategy-pack-operations.md` with a named owner role.
- [ ] If the audit finds rotated ranges, the table is updated and
    new ranges are unit-tested.

## Definition of done

- Table provenance is documented in code; review obligation is on
  the operations doc.

## Links

- [[add-ipv6-telegram-dc-classification-to-ws-tunnel]]
- [[ws-tunnel-telegram]]
