---
title: Surface NO_DIRECT_SOLUTION verdict honestly
type: task
status: todo
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-transport-policy-and-verdicts
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-23
---

- [ ] #task Surface NO_DIRECT_SOLUTION verdict honestly #repo/RIPDPI #area/diagnostics #status/todo 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `surface-no-direct-solution-verdict-honestly`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

When the diagnostic exhausts its arms without a stable success, return
`NO_DIRECT_SOLUTION` rather than keep burning attempts. Surface this to
the user as a real verdict, not an error.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §3 rule 5 and "Phase 4" end
state.

## Acceptance criteria

- [x] Diagnostic returns the verdict with a structured reason code
    (`IP_BLOCKED`, `TLS_BLOCKED_NO_ARMS_WORKED`, `DNS_BLOCKED_NO_ECH`,
    etc.).
- [x] UI/diagnostics surface displays the verdict + reason; does not
    pretend to keep trying.
- [x] A cooldown prevents immediately re-running the full diagnostic for
    the same host on the same network profile.
- [ ] Persisted verdict is subject to the Phase 5 revalidation rules
    (ASN change, access-type change, etc.).

## Implementation note

The first honest-verdict slice landed on 2026-04-23: diagnostics now keep
distinct TLS, QUIC, and likely-IP-block `NO_DIRECT_SOLUTION` causes, and
summary text surfaces the verdict reason instead of pretending the scan
should keep trying. Full Phase 5 persistence / revalidation behavior is
still open.

## Links

- [[Persist direct-mode policy with revalidation]]
- [[Epic - Direct-mode transport policy and verdicts]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]


## doing
