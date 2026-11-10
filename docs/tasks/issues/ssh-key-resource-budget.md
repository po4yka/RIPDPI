---
id: RLY-1794325389593996
title: Bound SSH private-key decoding work
kind: bug
status: review
area: relay
priority: high
owner: SSH maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: ssh-key-resource-budget
created: 2026-11-10
updated: 2026-11-10
---

## Goal

Reject private keys above an explicit resource budget and keep permitted decoding off Tokio workers. Close must join all admitted decoding work.

## Acceptance criteria

Local bounded PoC reproduces executor starvation. Regression tests cover resource limits, supported containers, runtime responsiveness, cancellation, and owned cleanup. Locked native checks and source gates pass.

## Ownership

- Root writer: ripdpi-ssh source and tests, task/spec records, verification and audit report.
- Isolated vendor writer in /tmp/ripdpi-rly11-h1-budget: russh keys/format, keys/mod.rs, README.ripdpi.md. Root imports its reviewed diff.
- No Cargo.lock, dependency, baseline, locale, JNI, or stored-schema edits are planned.
