---
id: DGN-1794730862541358
title: Correct TCP, TLS, and WebSocket probe outcomes
kind: bug
status: doing
area: diagnostics
priority: high
owner: Diagnostics
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-dia01-probe-outcomes
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Return accurate transport observations for the nine DIA-01 findings.

## Acceptance criteria

Check cancellation and deadlines, direct WSS fallback, WSS target settings and
subprotocol agreement, typed TLS evidence, IN_PATH lookup isolation, local
TTL, and reported timing with the owning tests.

## Ownership

- WSS: transport `ws_tls*`, socket watchdog, and Telegram projection.
- TLS: diagnostic TLS capture/classification, version-split consumer, and direct-mode policy.
- Web: domain probe cancellation and informational resolution.
- Integration: transport address/TCP/SOCKS control, TTL, timing, shared wire
  fixtures and API snapshots. Apply TTL and timing changes after the TLS and WSS
  owning changes. Keep each implementation in a dedicated worktree.
