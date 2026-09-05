---
id: DGN-1788599171554142
title: Attempt pinned diagnostics peers before DNS fallback
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics transport audit
parent: null
blocked_by: []
spec_mode: required
openspec_change: pinned-peer-before-dns
created: 2026-09-05
updated: 2026-09-05
---

## Goal

Attempt configured IP peers before fallback DNS can consume the scan deadline. PR 495 comment 3940078569 exposed a SOCKS5 UDP regression and the same existing direct TCP/UDP weakness.

## Acceptance criteria

- Working pinned peers complete without resolving fallback hostnames.
- Failed pinned attempts retain hostname fallback and SNI; DNS-only targets still work.
- Direct TCP/UDP, route experiments, and SOCKS5 UDP share the ordering rule.
- Expired scan deadlines remain errors; tests cover a stalled fallback resolver and failed pinned attempts.

## Ownership

The diagnostics writer owns only ripdpi-diagnostics-transport source and regression tests in an isolated worktree. Audit integration owns diagnostics-runner/connectivity/probes/domain.rs, planning, task state, report, and integration. Review agents are read-only. Cargo.lock, schemas, baselines, and locales are outside this change.
