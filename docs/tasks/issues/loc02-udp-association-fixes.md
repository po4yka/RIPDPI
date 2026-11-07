---
id: PRX-1794076309764211
title: Fix SOCKS5 UDP association audit findings
kind: bug
status: review
area: proxy
priority: high
owner: LOC-02 integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: loc02-udp-association-fixes
created: 2026-11-07
updated: 2026-11-07
---

## Goal

Fix the six confirmed LOC-02 UDP association defects with one fix commit per finding.

## Acceptance criteria

- F1: Stop cancels pending upstream setup; no datagram is sent after stop returns.
- F2: Upstream control EOF/RST invalidates its UDP binding.
- F3: Replies from an unexpected upstream peer are not relabelled as the requested target.
- F4: The internal relay listener rejects UDP when the profile disables it.
- F5: An oversized SS2022 datagram does not end the association.
- F6: Source-port rebind does not break an upstream SOCKS binding.
- Regression tests, source gates, locked metadata, and independent reviews pass.
- Each fix is committed separately, integrated into main, and pushed as requested.

## Parallel ownership

- Lifetime worker: F1 and F2; proxy-runtime UDP setup/control lifecycle, the adapter cancellable TCP connector, udp.rs loop and new lifecycle integration tests. It owns upstream_socks.rs. It does not edit upstream_pump.rs or udp/tests.rs.
- Datagram worker: F3 and F6; proxy-runtime upstream_pump.rs, migration.rs, the adapter UDP codec policy wrapper, existing udp/tests.rs and new datagram integration tests. It does not edit upstream_socks.rs or udp.rs.
- Relay worker: F4 and F5; relay-core UDP admission/capabilities and relay-tls-transports SS2022 send limits/error handling, with owning tests.
- DNS helper worker: the F1 cancellation API in dns-resolver and ws-bootstrap plus owning stalled-query tests. It uses a separate worktree. The lifetime writer alone connects those APIs to UDP callers.
- Integrator: portfolio/OpenSpec artifacts, conflict resolution, combined checks, review, integration and push.
- Cargo.lock, dependency manifests, wire schemas, locale sets, baselines and registries are serialized to the integrator; only the integrator may add the existing libc dependency edge for SS2022 error classification; no version or other serialized-file change is planned.
- All writers use separate worktrees. No writer changes main directly. Audit hypotheses are not presumed defects.
