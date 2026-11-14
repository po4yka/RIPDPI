---
id: ENG-1794637889757963
title: Bound dummy prepend work and repair its contracts
kind: bug
status: review
area: engine
priority: high
owner: DPI-17 remediation team
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-dpi17-audit-findings
created: 2026-11-14
updated: 2026-11-14
status_detail: "Accepted exact 415 source: local gates, full CI, all five emulator APIs, five Xray API35 cases, CodeQL and artifact provenance passed."
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
- Pass Kotlin/JVM tests, app/service Android lint, and exact-SHA CI and CodeQL checks before closure.
- Build the existing OBFS4 asset for all four Android ABIs with reviewed source-patch provenance and default Go linkname checks.

## Ownership

- resource_budget: isolated fix/dpi17-resource-budget worktree; Kotlin data/model validation and tests; native ripdpi-config constants, ripdpi-proxy-config parsing/validation and tests; ripdpi-desync DummyPrepend limits and tests.
- ui_contract: isolated fix/dpi17-ui-contract worktree; all locale resource sets and owning translation export only. This agent is the sole locale writer.
- integrator: fix/dpi17-audit-remediation worktree; portfolio/OpenSpec artifacts, wire_e2e regression, combined validation, independent review, commits, main integration and authorized push.
- Review agents are read-only. Cargo.lock, dependency versions, API snapshots, baselines and golden fixtures have no delegated writer; any necessary generated changes are serialized by the integrator.

## Completion ownership

- Completion integrator: fix/dpi17-completion-final worktree; task/OpenSpec metadata, scripts/tests/test_build_observability_contracts.py, XrayProviderE2ETest pinned-version assertions, Gradle diagnosis and integration.
- completion_android: read-only JVM/lint execution on the combined worktree.
- completion_ci: sole writer in fix/dpi17-obfs4-build for Lyrebird patch files, pluggable source manifest, build-logic patch application/provenance and owning tests. Root owns task/OpenSpec files.
- wire_seed_fix: isolated fix/dpi17-wire-seed worktree; shared Rust QUIC seed padding and its owning regression in ripdpi-packets/src/quic/tests.rs. No production behavior or minimum-length gate changes.

## Scope

Do not turn synthetic decoys into a complete QUIC client or tune DPI efficacy. No root, device or external peer probes. The user authorized one commit per confirmed finding, main integration and remote push.
