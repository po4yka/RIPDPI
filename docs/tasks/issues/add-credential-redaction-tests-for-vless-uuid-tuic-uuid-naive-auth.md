---
title: Add credential redaction tests for VLESS UUID, TUIC UUID, NaiveProxy auth
type: task
status: backlog
area: testing
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Add credential redaction tests for VLESS UUID, TUIC UUID, NaiveProxy auth #repo/RIPDPI #area/testing #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-credential-redaction-tests-for-vless-uuid-tuic-uuid-naive-auth`
- **Verify:** `cargo test -p ripdpi-vless -p ripdpi-tuic -p ripdpi-naiveproxy -p ripdpi-ws-tunnel`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-vless/**`, `native/rust/crates/ripdpi-tuic/**`, `native/rust/crates/ripdpi-naiveproxy/**`, `native/rust/crates/ripdpi-ws-tunnel/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Extend the no-secret-logging test surface to specifically cover
VLESS UUIDs, TUIC UUIDs, NaiveProxy `Proxy-Authorization` headers,
and the WS-tunnel MTProto seed bytes. Tracing macros must never emit
these in plaintext.

## Context

[[add-no-secret-logging-and-diagnostics-redaction-tests]] establishes
the general policy. The protocol-specific fields are easy to miss:
`VlessRealityConfig.uuid`, `Config` UUIDs in TUIC, the `Proxy-Authorization`
basic-auth value in NaiveProxy, and the 64-byte MTProto seed. Each
needs a targeted assertion.

## Acceptance criteria

- [ ] A per-crate test asserts that `tracing` events emitted on a
    representative happy-path connect do not contain the UUID or
    credential as a substring of any captured line.
- [ ] A per-crate test asserts that error events triggered by
    misconfiguration do not echo the credential.
- [ ] The MTProto seed test asserts that the 64-byte init buffer is
    never logged in full hex.
- [ ] If a tracing call requires partial visibility (e.g. last 4
    bytes), a small `redact_uuid`/`redact_seed` helper centralises
    the format.

## Definition of done

- Removing the redaction in any one crate fails its targeted test.

## Links

- [[add-no-secret-logging-and-diagnostics-redaction-tests]]
- [[gate-fake-sni-cert-bypass-behind-allow-insecure-flag-with-telemetry]]
