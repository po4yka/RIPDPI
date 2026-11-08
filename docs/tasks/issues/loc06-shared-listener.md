---
id: PRX-1794118808814556
title: Repair shared proxy listener dispatch contracts
kind: bug
status: done
area: proxy
priority: high
owner: Proxy runtime
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-shared-listener
created: 2026-11-08
updated: 2026-11-08
closed_at: "2026-11-08T06:43:37Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Eight fixes with regression tests; the independent LOC-06 wire and DNS probes, the Android settings and lint gates, and the source gates passed.
---

## Goal

Repair LOC-06 F1 through F8 and repeat the independent loopback audit.

## Acceptance criteria

Each finding has a regression check and its own fix commit. Run native tests, the Android configuration gate, the source gates and local wire probes.

## Ownership

The integration worktree owns F1/F2, DNS and raw address readers, the board and specification, and the combined validation. The HTTP worktree owns F3–F6 (HTTP framing, auth and response mapping). The configuration worktree owns F7/F8 (settings copy, translations, port tests and debug CLI). HTTP and DNS changes to separate functions in handshake.rs are integrated by one writer. No parallel edits to locale files, baselines or dependency locks.
