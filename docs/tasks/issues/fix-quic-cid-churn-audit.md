---
id: ENG-1794637897912969
title: Fix CID churn audit regressions
kind: bug
status: doing
area: engine
priority: high
owner: DPI22 integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-quic-cid-churn-audit
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Fix the three confirmed DPI-22 defects without changing the synthetic-decoy scope.

## Acceptance criteria

Count boundaries and aggregate budgets pass Kotlin/native tests; CID uniqueness passes v1/v2 wire tests; maximum IPv4 input passes real loopback emission; independent review and CI source gates pass. Each defect has its own commit. Main integration and remote push are authorized by the current request.


## Parallel ownership

- cid_budget: core/data/model strategy validation and tests; ripdpi-proxy-config UDP validation/tests; ripdpi-desync plan_udp.rs and private budget tests.
- cid_uniqueness: ripdpi-desync packet_family.rs, churn_gap.rs, and private CID tests.
- initial_length: ripdpi-packets QUIC build.rs and focused packet tests; optional new runtime adapter regression.
- integrator: this task, OpenSpec, combined validation, review, commits, main integration, and push.
- No writer changes Cargo.lock, dependency versions, locales, golden fixtures, or quality baselines. Shared test modules use separate files. Every writer uses a separate worktree.
