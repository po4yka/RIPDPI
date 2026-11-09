---
id: TRN-1794211839090466
title: Repair STD-10 HTTP relay carrier contracts
kind: bug
status: review
area: transport
priority: high
owner: transport-maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-std10-http-carrier-contracts
created: 2026-11-09
updated: 2026-11-09
---

## Goal

Correct the seven confirmed STD-10 findings without adding unsupported relay modes.

## Acceptance criteria

- Preserve active xHTTP siblings when another open is cancelled.
- Validate peer CONNECT and DATAGRAM settings before MASQUE use.
- Carry valid non-200 H2 CONNECT success responses.
- Reject malformed capsules, incomplete FIN and forbidden capsule response headers.
- Document the Rust and Chromium Naive consumers.
- Pass affected Rust tests, independent review and just ci-gates; create one fix commit per finding.

## Parallel ownership

- Coordinator: integration worktree, task/OpenSpec records, H2 carrier, Cargo manifests/lock, source gates and build prerequisite.
- xhttp-repair: ripdpi-xhttp and ripdpi-naiveproxy documentation only; separate worktree.
- h3-repair: ripdpi-masque/src/h3 and UDP sender changes; vendor/h3 only if required; separate worktree.
- capsule-repair: ripdpi-masque/src/capsule.rs and response.rs plus isolated test modules only; separate worktree.
- Serialized lanes: Cargo.toml/Cargo.lock belong only to the coordinator. Coordinator integrates h3 and capsule callers after both workers finish. No worker changes baselines, locales, task records or another lane.
