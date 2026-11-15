---
id: DGN-1794730805168562
title: Preserve audited TLS probe evidence
kind: bug
status: doing
area: diagnostics
priority: medium
owner: DIA-02 audit team
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-dia02-tls-evidence
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Repair DIA-02 F1–F8 so TLS reports identify what each connection measured, which trust policy ran, and whether a failure can be attributed.

## Acceptance criteria

- Preserve the first complete locally written ClientHello JA3 on success and failure. Check record and handshake boundaries and normalize with an independent known answer.
- Keep endpoints, TLS version, negotiated ALPN, elapsed time, certificate evidence, and retry outcomes on their own attempts. Project the evidence through strategy and web diagnostics.
- Distinguish local certificate checks, peer alerts, ServerHello, administrative interruption, and unknown cause. Keep ECH-only results descriptive.
- Select ECH fallback for a known connected peer. Record configuration source and ECH outcome. Retry at most once after authenticated rejection.
- Mark custom trust and prevent unverified outer connections from authorizing ECH retry.
- Demonstrate each finding with a regression and a separate fix commit. Pass affected local tests and combined source gates.
- Keep JA4 computation, browser parity, device tests, and public-server probes outside this change.

## Ownership

- tls owns F1, F3, F4 and F8 in the diagnostics TLS probe crate, except the JA3 parser, in a separate worktree.
- ja3 owns F6 under `ripdpi-diagnostics-tls/src/ja3` in a separate worktree.
- Integrator owns F2, F5 and F7 across monitor engine, diagnostics runner and Kotlin consumers in the integration worktree. Coordinate any F5 changes to shared TLS APIs with tls before editing.
- Integrator owns task state, OpenSpec, the generated board, serialized contracts, API snapshots when required, and combined verification. No dependency, locale, or baseline changes are planned.
