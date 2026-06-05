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
updated: 2026-06-05
---

## Summary

Build CensorLab locally, replay a middlebox-like scenario against RIPDPI's direct-mode arms, and decide whether to adopt, fork, or reject it as an offline censor-replay harness for the orchestration test posture.

## Research citation

ripdpi-android-research-2026-04-20 §Academic papers — CensorLab (arxiv 2412.16349) is a testbed for replaying censor strategies against bypass tools. Having an offline replay that exercises our six arms without a real middlebox egress reduces regression risk on every release.

## Acceptance criteria

- [ ] CensorLab built locally and documented (OS, deps, gotchas).
- [ ] One middlebox-like scenario replayed against at least two named arms with captured verdicts.
- [ ] Verdict on coverage: does it exercise all six transparent-mode arms plus the DoH/DoQ classifier, or is it partial.
- [ ] Decision recorded on adopt / fork / reject with the next concrete action (integrate into CI, fork and extend, or drop).

## Links

- [[Epic - Orchestration test posture]]
- Add orchestration failure-injection harness
- [[Build CensorLab-style offline strategy-pack pipeline]]
- ripdpi-android-research-2026-04-20


## owned-stack-mode-with

## Work log

- 2026-06-05: No CensorLab harness exists in the repo; no build instructions, no replay scenario output, no adopt/fork/reject decision recorded anywhere. All four acceptance criteria remain open. `core/diagnostics/replay` is unrelated (transport-policy replay, not censor replay). Spike is pure future work.
