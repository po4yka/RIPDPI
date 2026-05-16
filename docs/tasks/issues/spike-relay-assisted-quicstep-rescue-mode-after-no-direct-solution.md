---
title: Spike relay-assisted QUICstep rescue mode after NO_DIRECT_SOLUTION
type: task
status: backlog
area: diagnostics
priority: low
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-22
updated: 2026-05-14
---

- [ ] #task Spike relay-assisted QUICstep rescue mode after NO_DIRECT_SOLUTION #repo/RIPDPI #area/diagnostics #status/backlog 🔽

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `spike-relay-assisted-quicstep-rescue-mode-after-no-direct-solution`
- **Verify:** `just lint`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `native/rust/crates/ripdpi-desync-runtime/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Evaluate whether RIPDPI should add a second-tier rescue mode that uses a
relay-assisted QUICstep-style first-flight bootstrap only after direct-mode has
already returned `NO_DIRECT_SOLUTION`.

## Context

The current direct-mode plan explicitly keeps relay-assisted QUICstep out of
scope for the default no-proxy path. Today's [[quicstep-first-flight-hiding]]
note sharpens why: it is strongest only for controlled infrastructure and
first-flight classifiers, and becomes a liability when migration support is
weak or generic QUIC blocking dominates.

That still leaves a possible niche: a post-`NO_DIRECT_SOLUTION` rescue track
for controlled server or CDN-backed controlled property, not arbitrary
third-party sites.

## Acceptance criteria

- [ ] The spike defines the only acceptable deployment scopes for RIPDPI
    (`controlled server` and, if justified, `CDN-backed controlled property`)
    and rejects arbitrary-site assumptions explicitly.
- [ ] The spike records go/no-go criteria using the practical indicators from
    [[quicstep-first-flight-hiding]]: migration support, operator-level QUIC
    blocking, and whether the later path can really detach from the censored
    bootstrap path.
- [ ] The spike decides where this mode would attach in product flow:
    post-`NO_DIRECT_SOLUTION` remediation only, not default transparent mode.
- [ ] The spike records Android-specific costs: battery, background execution,
    socket lifecycle, and policy interaction with the existing relay stack.
- [ ] The output ends with one explicit recommendation:
    `do not pursue`, `research-only`, or `promote to implementation epic`.

## Notes

Do not let this reopen the default direct-mode plan. If the answer is
"interesting but niche", keep it as a parked research branch.

## Links

- [[Epic - Direct-mode transport policy and verdicts]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
- [[quicstep-first-flight-hiding]]


## localization-expansion
