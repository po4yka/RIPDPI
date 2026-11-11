---
id: TRN-1794383863211672
title: Repair WireGuard tunnel audit findings
kind: bug
status: review
area: transport
priority: high
owner: wireguard-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun01-wireguard-audit-fixes
created: 2026-11-11
updated: 2026-11-11
status_detail: All confirmed repairs and local gates passed; Android acceptance and exact pushed-SHA hosted CI remain open.
---

## Goal

Repair the TUN-01 findings and confirmed follow-up defects at source SHA ec4f13320a272b945bf2a0497d02fcea545d73c9. Preserve the plain WireGuard, WARP, AWG and WSS boundaries.

## Acceptance criteria

- Each finding has a regression test and an atomic fix commit.
- Native AllowedIPs constrain incoming source and outgoing destination addresses.
- Plain WireGuard uses MTU-bounded padding, authenticated roaming, both endpoint families, count limits and retry jitter.
- Current local source gates and affected Kotlin/Rust tests pass. Reference peer tests distinguish local interoperability from Android acceptance.
- Commit integration to main and push are authorized by the current user request. Device and external-server probes are not authorized.

## Ownership and serialized files

- Import/UDP worker: app AWG import/editor and tests; WireGuardConfParser.kt, WireGuardConfig.kt and parser tests; warp-core virtual_iface/udp.rs. F1 and F5 are separate commits.
- Crypto worker: vendor/boringtun source and README. F2, F7, F8 and verified cookie/clock corrections are separate commits.
- Endpoint worker: warp-core wireguard/tunnel.rs, carrier.rs, socket.rs and config.rs. F3 and F6 are separate commits.
- Integration owner: native AllowedIPs policy, Kotlin service/engine-api DTOs, serialization/contract snapshots and tests, spec/task files, final gates and main integration.
- tunnel.rs is serialized: endpoint worker completes first; integration owner then adds ACL and MTU padding seams. No concurrent writers.
- Cargo.lock, dependency versions, locales, golden fixtures and architecture baselines have no assigned writer and must not change.
- Every writer uses a separate worktree. Review agents are read-only.

- Cookie endpoint follow-up (F10): crypto worker owns only vendored rate_limiter.rs, mod.rs, related tests and README in a separate tun01-cookie-endpoint worktree. The integration owner keeps ROOT immutable while its current checks run. No new dependency or public legacy API removal is allowed.

- Immediate-send follow-up (F11): endpoint worker owns only warp-core wireguard/tunnel.rs and tunnel/roaming_tests.rs in an isolated tun01-send-fallback worktree. ROOT source stays immutable during the current validation. Only unconfirmed UDP initiations may try another candidate; data, cookie replies, WS framing and protected-socket ownership keep their contracts.
