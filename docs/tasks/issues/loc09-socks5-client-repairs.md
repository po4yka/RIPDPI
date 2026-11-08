---
id: PRX-1794125331192374
title: Repair SOCKS5 client audit defects
kind: bug
status: doing
area: proxy
priority: high
owner: Network client repair coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-socks5-client-contracts
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Repair the six confirmed LOC-09 SOCKS5 client findings. Preserve valid TCP and UDP traffic, session credentials, socket protection, and bounded shutdown.

## Acceptance criteria

- Reject malformed CONNECT and ASSOCIATE reply headers before payload use.
- Stop TUN UDP traffic when its control TCP closes; use a new association for later traffic.
- Bound upstream TCP connect and negotiation by one deadline.
- Accept IPv6 proxy hosts in DNS HTTP transport without changing host identity.
- Support domain relay endpoints with bounded resolution and retain numeric endpoints.
- Reject invalid credential byte lengths before authentication output.
- Recheck the audit hypotheses, fix confirmed defects, and record intentional scope limits.
- Keep one atomic commit for each finding, run affected tests and required source gates, obtain independent review, then integrate main and push origin/main.

## Ownership and serialized lanes

- Coordinator: this task record, OpenSpec artifacts, docs, Kotlin LocalSocksAuth/RelayCapabilityProbe and their tests, final gates, integration, and push.
- Tunnel worker: native/rust/crates/ripdpi-tunnel-core only; F1 tunnel reply checks, F2 UDP lifetime, F5 tunnel domain relay endpoints, wildcard handling, and short-domain datagrams.
- Core/DNS worker: ripdpi-socks5-core, ripdpi-dns-resolver, ripdpi-diagnostics-transport; F1 shared reply checks, F4 proxy IPv6, F5 shared/diagnostic domain relay endpoints, F6 credential bounds, and strict DNS auth policy.
- Upstream worker: ripdpi-proxy-runtime and ripdpi-proxy-runtime-adapter; F3 setup deadline and conflicting per-endpoint credentials investigation.
- Each writer uses its own dedicated worktree. No writer reverts another writer's edits. No worker commits until independent review has passed.
- Serialized files stay with the coordinator: Cargo.lock, dependency manifests, task board, specs, baselines, golden fixtures, locale sets, and all API snapshot outputs. Do not change dependencies, baselines, locale resources, or golden fixtures.
