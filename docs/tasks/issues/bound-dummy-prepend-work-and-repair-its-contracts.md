---
id: ENG-1794637889757963
title: Bound dummy prepend work and repair its contracts
kind: bug
status: blocked
area: engine
priority: high
owner: DPI-17 remediation team
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-dpi17-audit-findings
created: 2026-11-14
updated: 2026-11-14
status_detail: Implementation and native/source gates are complete; JVM tests and Android lint require unavailable official dependency artifacts. See verification.md.
---

## Goal

Fix the three confirmed DPI-17 audit findings: bound DummyPrepend work, correct the UI contract, and repair the stale wire regression test. Keep the compact decoy representation and existing no-op and round behavior.

## Acceptance criteria

- Reject DummyPrepend counts above 64 per step or in total across a UDP chain at Kotlin and native JSON boundaries.
- Bound direct-model planner work to 64 Dummy packets, each at most 4096 bytes, with a pre-allocation host-length guard.
- Keep count=0 and unparsed UDP as no-op and preserve the original payload.
- Correct the explanatory string in all ten locales.
- Run real loopback wire regression with the environment gate enabled; verify generated QUIC and the original payload.
- Pass affected unit tests, locale lint, source CI gates, and independent review.

## Ownership

- resource_budget: isolated fix/dpi17-resource-budget worktree; Kotlin data/model validation and tests; native ripdpi-config constants, ripdpi-proxy-config parsing/validation and tests; ripdpi-desync DummyPrepend limits and tests.
- ui_contract: isolated fix/dpi17-ui-contract worktree; all locale resource sets and owning translation export only. This agent is the sole locale writer.
- integrator: fix/dpi17-audit-remediation worktree; portfolio/OpenSpec artifacts, wire_e2e regression, combined validation, independent review, commits, main integration and authorized push.
- Review agents are read-only. Cargo.lock, dependency versions, API snapshots, baselines and golden fixtures have no delegated writer; any necessary generated changes are serialized by the integrator.

## Scope

Do not turn synthetic decoys into a complete QUIC client or tune DPI efficacy. No root, device or external peer probes. The user authorized one commit per confirmed finding, main integration and remote push.
