---
id: RST-1794684264866031
title: Repair DPI-36 packet error handling and scope claims
kind: bug
status: doing
area: rust-native
priority: medium
owner: packet-repair
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi36-packet-errors
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Preserve safe packet delivery and stop when TCP restoration is uncertain. Repair audit findings F1, F2 and F3 only.

## Acceptance criteria

- RST cleanup failures stop both runtime executor paths before original writes.
- Explicit safe UDP builder errors preserve the same fallback through direct and helper paths.
- Uncertain or opaque UDP errors never trigger a repeated datagram.
- MD5 documentation matches fragment builder scope.
- Regression tests, combined gates and independent review pass; platform execution limits are recorded.

## Ownership

The packet-repair integrator owns all writes. Subagents provide read-only analysis and review. Helper error metadata and all shared files have one writer.
