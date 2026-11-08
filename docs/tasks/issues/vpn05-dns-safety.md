---
id: DNS-1794142176893534
title: Repair VPN-05 DNS mapping ownership and response integrity
kind: bug
status: doing
area: dns
priority: high
owner: Network engineering
parent: null
blocked_by: []
spec_mode: required
openspec_change: vpn05-dns-safety
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Fix the five confirmed VPN-05 defects: issued-address ownership, per-response mapping leases, stale-result isolation, interceptor-address reservation, and AD flags after synthesis.

## Acceptance criteria

- F1: A previously issued address never changes host ownership within one native tunnel session, including eviction and generation reset.
- F2: Every A record in a successful rewrite has a live correct mapping; failed rewrites release temporary leases.
- F3: A stale direct result cannot delete a fresh encrypted/current mapping.
- F4: The configured interceptor address is not issued to clients.
- F5: Changed DNS content clears AD; unchanged content preserves it.
- Each defect has a separate implementation commit, a regression test, and independent review.
- Run the tunnel-core suite, affected-crate lint/format checks, task validation, and just ci-gates before main integration.

## Parallel ownership

- Mapping writer: `ripdpi-tunnel-core/src/dns_cache/state.rs`, mapping tests, and `dns_intercept/config.rs` for F1/F4. F1 then F4 are separate commits.
- Rewrite writer: `ripdpi-tunnel-core/src/dns_cache/packet.rs` and rewrite/authenticity tests for F2/F5. F2 then F5 are separate commits.
- Primary writer: `dns_intercept/response_processing.rs`, the existing stale-result regression in `io_loop/phases.rs`, and new stale-result tests, DNS documentation, task and OpenSpec artifacts, combined validation and integration.
- `dns_cache/mod.rs` test-module declarations are serialized by the primary writer during integration. No Cargo.lock, dependency manifest, schema, locale, baseline, or golden changes are planned.
- Each writer uses a separate worktree. Review agents are read-only. The primary writer alone updates task state and integrates branches.
