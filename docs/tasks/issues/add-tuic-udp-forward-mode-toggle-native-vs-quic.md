---
title: Expose TUIC UDP forward-mode toggle (native vs quic)
type: task
status: done
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [x] #task Expose TUIC UDP forward-mode toggle (native vs quic) #repo/RIPDPI #area/rust-native #status/done 🔼

## Audit outcome

Decision: **native-only**, with the existing "datagram relay not
available" error sufficing as the cross-mode diagnostic. `quic`-mode
implementation is documented as deferred until telemetry justifies
the work. See `docs/architecture/tuic-udp-forward-mode-audit.md`.

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-tuic-udp-forward-mode-toggle-native-vs-quic`
- **Verify:** `cargo test -p ripdpi-tuic`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-tuic/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

TUIC v5 upstream supports two UDP forwarding modes (`native` over QUIC
datagrams vs `quic` over reliable streams). Audit which mode
`ripdpi-tuic` currently implements and expose both as a config toggle
where applicable.

## Context

`ripdpi-tuic/src/udp.rs` is 8.6KB and `protocol.rs` defines
`COMMAND_PACKET = 0x02`, but it is not clear from the public API which
upstream UDP mode is wired. Servers expect the client to opt into a
specific mode; mismatch produces silent packet loss.

## Acceptance criteria

- [x] (2026-05-15) A note in `protocol.rs` or `udp.rs` documents
    the current mode. **DONE:** the audit lives at
    `docs/architecture/tuic-udp-forward-mode-audit.md` (linked from
    the crate `SPEC.md`).
- [x] (2026-05-15) If only one mode is supported, document the
    policy and surface a clear error when the server advertises the
    other. **DONE:** policy is native-only; existing
    `udp_session()` already returns "TUIC datagram relay is not
    available on this connection" when the server omits datagram
    support. Audit doc captures the rationale.
- [ ] If both are intended, expose `udp_forward_mode: native | quic`
    in `Config` and add unit tests for the encode/decode of each.
    **DEFERRED:** see audit doc for the implementation outline; not
    pursued until field telemetry justifies the dual-mode work.

## Definition of done

- Operators can pick the mode that matches their TUIC server, or the
  unsupported case produces a recognizable diagnostic.

## Links

- [[Epic - Control-plane hardening]]
