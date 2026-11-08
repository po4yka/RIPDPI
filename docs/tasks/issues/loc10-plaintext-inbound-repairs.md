---
id: PRX-1794127994095690
title: Repair plaintext address inbound routing and activation
kind: bug
status: review
area: proxy
priority: high
owner: Network runtime maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: loc10-plaintext-inbound-repairs
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Repair the three confirmed LOC-10 findings without changing its plaintext wire format or access limits.

## Acceptance criteria

- Plaintext input applies destination policy before application data reaches any origin or relay, with fragmented headers and first TLS data.
- Candidate substitution cannot connect to the actual bound listener.
- The shipped CLI and Android command-line conversion support an explicit plaintext input flag.
- Narrow Rust tests, independent review, task validation, and combined ci-gates pass before integration.

## Ownership

- Primary writer: plaintext handshake/reader, opt-in request assembler in proxy-runtime-desync-adapter and canonical HTTP method-prefix helper in ripdpi-packets, and shared first-payload policy flow, task/OpenSpec files, combined validation.
- F2 writer: runtime/state/listener.rs, runtime/listeners.rs, runtime/routing/connect/plan.rs and its child tests, plus public self-loop integration tests. The primary writer applies the small handshake self-target predicate replacement.
- F3 writer: shared CLI runtime options/parser tests, CLI help/binary integration tests, proxy-config integration tests, docs/native/proxy-engine.md.
- Each writer uses a separate worktree. Cargo.lock, dependencies, locales, protobuf/JNI/wire schemas, golden fixtures, and baselines have no writer and must not change.
- All architecture/API generated outputs are serialized to the primary writer after combination.

## Boundaries

- H1 TLS seed assembly is covered by the F1 regression.
- Pending direct-connect cancellation is already owned by PRX-1794125405826480 in the LOC-07 worktree; do not copy or overwrite its uncommitted work. Recheck after rebase.
- Preserve loopback-only input and reject input auth tokens. Do not inherit process environment in Android.
