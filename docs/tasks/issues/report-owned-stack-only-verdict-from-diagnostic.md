---
title: Report OWNED_STACK_ONLY verdict from diagnostic
type: task
status: todo
area: diagnostics
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-23
---

- [ ] #task Report OWNED_STACK_ONLY verdict from diagnostic #repo/RIPDPI #area/diagnostics #status/todo 🔼

## Summary

When transparent arms (A3–A8) all fail but an owned-stack arm (A9/A10)
works, the diagnostic returns `OWNED_STACK_ONLY`. Surface that as a real
verdict, not a failure — "open this host inside the RIPDPI browser" is a
legitimate outcome.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §4 and
`classify_success(arm)` in Phase 4.

## Current status

This task is partially landed in `/Users/po4yka/GitRep/RIPDPI`:

- The diagnostics UI now treats `OWNED_STACK_ONLY` as a real outcome and
offers a direct action to open the authority in the RIPDPI browser.
- Session-row projections carry the launch URL and owned-stack-only flag so
remediation can be derived from persisted diagnostic output.
- Remaining work still belongs to the direct-mode state-machine / policy path:
owning the final classifier arm mapping, persisting the verdict as a
reusable transport-policy outcome for future flows, and returning a
structured transparent-mode-not-supported result to third-party traffic.

## Acceptance criteria

- [ ] Diagnostic's `classify_success` returns `OWNED_STACK_ONLY` when the
    winning arm is A9 or A10 and no transparent arm succeeded.
- [x] UI/diagnostics surface: "Transparent mode: no / Owned-stack mode:
    yes" with a direct action to open the URL in the in-app browser.
- [ ] Persisted policy sets `outcome = OWNED_STACK_ONLY` on the
    `TransportPolicy` so subsequent flows skip transparent attempts.
- [ ] Third-party apps hitting this host in transparent mode get a
    structured "not supported in transparent mode" result, not a silent
    failure.

## Links

- [[Implement direct-mode diagnostic orchestrator Phases 1-4]]
- [[Epic - Owned-stack mode with Android 17 ECH]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
