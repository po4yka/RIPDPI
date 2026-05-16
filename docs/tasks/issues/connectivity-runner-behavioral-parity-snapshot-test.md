---
title: Connectivity runner behavioral parity snapshot test
type: task
status: done
area: testing
priority: high
owner: Test Automation Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-16
---

- [x] #task Connectivity runner behavioral parity snapshot test #repo/RIPDPI #area/testing #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `connectivity-runner-behavioral-parity-snapshot-test`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-monitor-engine`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-monitor-engine/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

Owner: Test Automation Engineer (commissioned by QA Lead).

Context
ripdpi-monitor-engine extracted connectivity.rs into 10 submodules (environment, dns, web, quic, tcp, service, circumvention, throughput, telegram, support). The refactor must be behavior-preserving across stage IDs, phase strings, total_steps, RunnerOutcome, and event order.

Acceptance criteria
- Snapshot/golden test that runs a fixture ExecutionPlan through every connectivity stage and captures stage IDs, phase strings, total_steps, RunnerOutcome, and the ordered list of recorded events.
- Snapshot is committed and locked.
- Bundled fixtures only; no live network.

Definition of done
PR merged with green snapshot test; reviewed by Senior Network Protocol Engineer; QA Lead acknowledges in POY-4.
