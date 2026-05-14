---
title: Evaluate sing-box 1.14 rule-action model for policy DSL parity
type: task
status: backlog
area: diagnostics
priority: low
owner: unassigned
parent: epic-direct-mode-transport-policy-and-verdicts
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Evaluate sing-box 1.14 rule-action model for policy DSL parity #repo/RIPDPI #area/diagnostics #status/backlog 🔽

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `evaluate-sing-box-1-14-rule-action-model-for-policy-dsl-parity`
- **Verify:** `just lint`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Summarize sing-box 1.14's rule-action model, then decide whether RIPDPI's
direct-mode transport-policy DSL should align vocabulary with it or
deliberately diverge.

## Research citation

[[ripdpi-android-research-2026-04-20]] §Upstream transport engines —
sing-box 1.14.0-alpha.13 (2026-04-17) replaces legacy
inbound/outbound-special-field plumbing with a rule-action model that
supports pre-matching. Aligning (or explicitly diverging with rationale)
makes it cheaper to exchange strategy expressions with the peer
community.

## Acceptance criteria

- [ ] sing-box 1.14 rule-action vocabulary summarized (matchers, action
    types, pre-match semantics).
- [ ] Alignment-vs-divergence decision recorded with rationale on
    [[Epic - Direct-mode transport policy and verdicts]].
- [ ] If alignment chosen: migration sketch for existing
    `TransportPolicy` struct noted; no migration work performed in
    this spike.

## Links

- [[Epic - Direct-mode transport policy and verdicts]]
- [[Define TransportPolicy struct and per-host state]]
- [[Cache transport policy per network and host tuple]]
- [[ripdpi-android-research-2026-04-20]]
