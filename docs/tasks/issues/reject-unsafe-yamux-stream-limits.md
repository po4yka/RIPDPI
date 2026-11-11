---
id: TRN-1794377577809835
title: Reject unsafe yamux stream limits
kind: bug
status: done
area: transport
priority: high
owner: Transport maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: yamux-stream-limits
created: 2026-11-11
updated: 2026-11-11
closed_at: "2026-11-11T07:20:50Z"
closed_reason: Verified bounded yamux stream admission
evidence_summary: Fix 97e4af86a6 passed Rust 1.98.1 tests (191 vless and 9 relay-core mux), real loopback echo at working limit 2048, Clippy, rustfmt, locked metadata, ci-gates, independent correctness and cancellation review. Oversized limits fail before carrier I/O. No Android or 32-bit runtime acceptance is claimed.
---

## Goal

Reject oversized yamux stream limits before carrier I/O without changing the receive budget.

## Acceptance criteria

Profile and direct-constructor boundary tests pass without panic. Affected crate tests, Clippy, rustfmt, locked metadata and ci-gates pass.
