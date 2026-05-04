---
title: Connectivity runner behavioral parity snapshot test
type: task
status: doing
area: testing
priority: high
owner: Test Automation Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Connectivity runner behavioral parity snapshot test #repo/RIPDPI #area/testing #status/doing ⏫

Owner: Test Automation Engineer (commissioned by QA Lead).

Context
ripdpi-monitor-engine extracted connectivity.rs into 10 submodules (environment, dns, web, quic, tcp, service, circumvention, throughput, telegram, support). The refactor must be behavior-preserving across stage IDs, phase strings, total_steps, RunnerOutcome, and event order.

Acceptance criteria
- Snapshot/golden test that runs a fixture ExecutionPlan through every connectivity stage and captures stage IDs, phase strings, total_steps, RunnerOutcome, and the ordered list of recorded events.
- Snapshot is committed and locked.
- Bundled fixtures only; no live network.

Definition of done
PR merged with green snapshot test; reviewed by Senior Network Protocol Engineer; QA Lead acknowledges in POY-4.
