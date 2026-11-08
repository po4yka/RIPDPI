---
id: VPN-1794142179721255
title: Fix TUN UDP error generation and ICMP quotes
kind: bug
status: done
area: vpn
priority: high
owner: VPN error implementation
parent: null
blocked_by: []
spec_mode: required
openspec_change: vpn04-icmp-errors
created: 2026-11-08
updated: 2026-11-08
closed_at: "2026-11-08T13:17:01Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: F1-F3 committed separately; 384 unit, 8 TUN E2E and 1 dual-family ICMP test passed; Clippy, fmt, ci-gates and independent wire/runtime/async reviews passed. Android and remote ICMP relay are not claimed.
---

## Goal

Fix VPN-04 findings F1-F3: preserve UDP error quotes, use the IPv6 MTU budget, and report a local forwarding failure through the non-root TUN path.

## Acceptance criteria

- F2 and F3 have independent wire regression tests.
- F1 has IPv4/IPv6 packet-loop tests, error-source, generation, cancellation and rate-limit checks.
- Cargo tests, affected-crate Clippy, ci-gates and independent review pass.
- Each finding has a separate fix commit; main is fast-forwarded and pushed only after validation.

## Ownership

- Wire worker: packet/icmp.rs and its packet regression tests, isolated wire worktree; F2 then F3.
- Primary: runtime UDP routing, originator, tests, docs and all task/OpenSpec artifacts in the job worktree.
- Read-only reviewers: wire correctness and async cancellation.
- No writer changes Cargo.lock, dependency versions, locales, schemas, baselines or golden fixtures.
- Primary alone integrates commits, updates generated task board, and pushes main.
