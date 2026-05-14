---
title: Spike CensorLab as offline censor-replay harness
type: task
status: backlog
area: testing
priority: medium
owner: unassigned
parent: epic-orchestration-test-posture
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Spike CensorLab as offline censor-replay harness #repo/RIPDPI #area/testing #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `spike-censorlab-as-offline-censor-replay-harness`
- **Verify:** `just lint`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-desync/**`, `native/rust/crates/ripdpi-diagnostics-probes/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Build CensorLab locally, replay a TSPU-like scenario against RIPDPI's
direct-mode arms, and decide whether to adopt, fork, or reject it as an
offline censor-replay harness for the orchestration test posture.

## Research citation

[[ripdpi-android-research-2026-04-20]] §Academic papers — CensorLab
(arxiv 2412.16349) is a testbed for replaying censor strategies against
bypass tools. Having an offline replay that exercises our six arms
without a real TSPU egress reduces regression risk on every release.

## Acceptance criteria

- [ ] CensorLab built locally and documented (OS, deps, gotchas).
- [ ] One TSPU-like scenario replayed against at least two named arms
    with captured verdicts.
- [ ] Verdict on coverage: does it exercise all six transparent-mode
    arms plus the DoH/DoQ classifier, or is it partial.
- [ ] Decision recorded on adopt / fork / reject with the next concrete
    action (integrate into CI, fork and extend, or drop).

## Links

- [[Epic - Orchestration test posture]]
- [[Add orchestration failure-injection harness]]
- [[Build CensorLab-style offline strategy-pack pipeline]]
- [[ripdpi-android-research-2026-04-20]]


## owned-stack-mode-with
