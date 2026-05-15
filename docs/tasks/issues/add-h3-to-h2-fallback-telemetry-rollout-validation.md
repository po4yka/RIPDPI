---
title: Validate H3-to-H2 MASQUE fallback telemetry sufficiency
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

- [ ] #task Validate H3-to-H2 MASQUE fallback telemetry sufficiency #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-h3-to-h2-fallback-telemetry-rollout-validation`
- **Verify:** `cargo test -p ripdpi-masque`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-masque/**`, `docs/native/relay-masque-status.md`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`docs/native/relay-masque-status.md` flags
"continued verification that HTTP/3 to HTTP/2 fallback telemetry is
sufficient for rollout decisions" as remaining work. Define the
telemetry contract and add tests asserting that every distinct
fallback-trigger reason is captured.

## Context

The existing test
`quic_migration_snapshot_records_http2_fallback_reason` covers one
case. Rollout decisions need to distinguish at least: handshake
failure, post-handshake idle, transport error, server-side rejection,
and explicit downgrade.

## Acceptance criteria

- [ ] An enum (or string vocabulary) enumerates fallback-trigger
    reasons.
- [ ] Each reason has a dedicated unit test asserting the snapshot
    captures it.
- [ ] The telemetry export schema is documented in
    `docs/native/relay-masque-status.md`.

## Definition of done

- A new fallback reason cannot be added in the future without also
  adding a test, by virtue of the enum match being non-exhaustive
  in the assertion helper.

## Links

- [[relay-masque-status]]
