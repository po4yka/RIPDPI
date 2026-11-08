---
id: VPN-1794133736183612
title: Repair VPN lifecycle and packet I/O failure handling
kind: bug
status: review
area: vpn
priority: high
owner: vpn01-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: vpn01-audit-repairs
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Repair the confirmed VPN-01 ownership defect and reproduce the reported TUN
I/O failure hypotheses before changing them. Keep intentional legacy network
ambiguity fail-closed and correct the stale packet-fd documentation.

## Acceptance criteria

- A late predecessor teardown cannot release resources used by a new VPN instance.
- Terminal TUN read or readiness errors cannot leave a busy packet loop.
- Terminal TUN write errors still retire owned TCP and UDP sessions.
- Each behavior change has a failing regression before its implementation and
  a passing regression afterward. Each distinct correction has its own commit.
- Legacy multi-network behavior follows its existing physical-authority contract.
- Affected JVM/Rust tests, static analysis, source gates, and independent review
  pass before integration. Device and hosted evidence remain separate.

## Parallel ownership

- `fix_lifecycle`: `RipDpiVpnService.kt` and owning service reconstruction tests.
  Any extra lifecycle path requires coordination before editing.
- `fix_tun_errors`: `native/rust/crates/ripdpi-tunnel-core/` packet I/O loop and
  owning tests. Read/readiness and write-cleanup corrections use separate commits.
- `fix_legacy_handover`: read-only physical-observer assessment and contract
  disposition. No behavior change is authorized without a confirmed defect and
  an authoritative selection source.
- `vpn01-integration`: task/OpenSpec artifacts, `docs/native/tunnel.md`, combined
  validation, review coordination, integration, and push.
- Every writer uses a separate worktree. Only the integration owner writes task
  records, generated board, and OpenSpec evidence. No dependency, lockfile,
  locale, golden, or baseline changes are planned.
