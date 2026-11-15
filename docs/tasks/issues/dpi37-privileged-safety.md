---
id: RST-1794722628748844
title: Repair privileged operation error and integrity contracts
kind: bug
status: review
area: rust-native
priority: high
owner: Privileged operations integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi37-privileged-safety
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Repair the three confirmed DPI-37 safety defects without changing packet techniques or root activation.

## Acceptance criteria

- Supported ICMP receive payloads cross bounded command-specific IPC; unsupported peers refuse before delivery.
- Invalid ICMPv4 checksums are rejected by the production parser.
- Raw fake and flagged TCP cleanup failures retain both causes as terminal errors.
- Regression checks, owning gates and independent review have observed evidence; each fix is committed separately and integrated into main for the requested push.

## Parallel ownership

The integrator alone owns F1 IPC sources, ROOT_HELPER_CONTRACT.md, task and OpenSpec files. checksum_fix owns only the ICMPv4 parser and tests. repair_fix owns only raw fake/flagged cleanup, tcp_repair/sockopt.rs and their narrow test support. Each writer uses a dedicated worktree. Cargo.lock, dependencies, locales, baselines and generated outputs have no writer. Review agents are read-only.
