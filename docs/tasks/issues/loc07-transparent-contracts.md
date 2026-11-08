---
id: PRX-1794125405826480
title: Repair Transparent TCP ingress contracts
kind: bug
status: review
area: proxy
priority: high
owner: Transparent ingress integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: loc07-transparent-contracts
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Repair the confirmed LOC-07 TCP ingress failures and verify shutdown while direct connect is pending. The user authorized separate fix commits, integration into main, and a remote push.

## Acceptance criteria

- Transparent rejects IPv6 destinations when IPv6 is disabled and accepts canonical IPv4.
- Linux original-destination conversion retains a nonzero IPv6 scope ID.
- A separate local service with the listener port remains reachable. Exact, wildcard and dual-stack self targets remain rejected.
- Pending TCP connect ends on runtime shutdown without an unowned socket or late relay.
- Meaningful regressions fail before each fix and pass after it. Existing auth, routing and relay gates stay intact.
- Independent review, affected Rust tests and lint, locked metadata and just ci-gates pass before integration.
- Each defect has a separate fix commit. The final main SHA is pushed and verified against origin.

## Ownership and serialized lanes

- Integration owner: planning, task lifecycle, docs/native/proxy-engine.md, combined checks, review and main integration in the job worktree.
- Integration owner owns the network_e2e fixture repairs found by the combined gate. The cancellation worker repaired the repeated-reset case in network_e2e.rs and its existing block_signal module as the sole source/index writer in the job worktree while the integration owner was read-only. No routing or learning production change is included.
- Ingress worker: F1 and F3 in ripdpi-proxy-runtime and listener projections; one commit per finding. It owns handshake.rs and its tests.
- Sockaddr worker: F2 in ripdpi-privileged-ops/src/linux/fd.rs and src/linux/tests/fd.rs.
- Cancellation worker: confirmed H1 in ripdpi-proxy-runtime-adapter connect functions and runtime/routing/connect/{socket,error,plan}.rs plus routing/retry.rs, with real pending-connect and cancellation-telemetry regressions. It owns only the connect section of platform.rs.
- platform.rs has separate function owners: ingress owns listener metadata; cancellation owns TCP connect control. These writers use separate worktrees. Integration serializes their commits, resolves any overlap and validates the combined file. Listener state belongs only to ingress.
- Review agents are read-only. Each writer uses a separate worktree. Workers do not edit planning, generated boards, Cargo.lock, dependency manifests, baselines, locales, contracts or golden fixtures.

## Scope limits

No system firewall rules, root activation, physical device probes or external server probes. Automatic redirect setup, TPROXY, UDP ingress and Android VPN composition changes are outside this repair. Local evidence does not prove a real REDIRECT deployment.
