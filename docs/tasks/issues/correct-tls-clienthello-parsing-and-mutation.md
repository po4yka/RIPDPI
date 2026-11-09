---
id: RST-1794212008025103
title: Correct TLS ClientHello parsing and mutation
kind: bug
status: review
area: rust-native
priority: high
owner: TLS maintenance
parent: null
blocked_by: []
spec_mode: required
openspec_change: rst-1794212008025103-correct-tls-clienthello-parsing-and-mutation
created: 2026-11-09
updated: 2026-11-09
---

## Goal

Inspect and mutate TLS ClientHello without crossing SNI boundaries, losing fragmented names, dropping trailing records, or placing padding after PSK.

## Acceptance criteria

- Regression tests cover all four audit findings and relevant failure paths.
- Targeted locked tests, independent review, and required source gates pass or have an explicit blocker.
- Ownership: SNI bounds, record-aware consumers, and mutation use separate worktrees; the integrator owns shared tests, API snapshots, and combined validation.
